package com.tangluobo.tomato.module.tools;

import bt.Bt;
import bt.StandaloneClientBuilder;
import bt.data.StorageUnit;
import bt.data.file.FileSystemStorage;
import bt.dht.DHTConfig;
import bt.dht.DHTModule;
import bt.metainfo.MetadataService;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;
import bt.runtime.BtClient;
import bt.runtime.Config;
import bt.torrent.TorrentSessionState;

import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Fully embedded pure-Java BitTorrent client for magnet links and .torrent URLs/files. */
final class TorrentDownloadEngine implements DownloadEngine {

    static final Duration PEER_DISCOVERY_INTERVAL = Duration.ofSeconds(3);
    static final Duration PEER_RETRY_INTERVAL = Duration.ofSeconds(20);
    static final Duration PEER_CONNECT_TIMEOUT = Duration.ofSeconds(12);
    static final Duration PEER_INACTIVITY_TIMEOUT = Duration.ofSeconds(25);
    static final Duration TRACKER_QUERY_INTERVAL = Duration.ofMinutes(1);
    static final Duration TRACKER_TIMEOUT = Duration.ofSeconds(15);
    static final Duration CONNECTED_SESSION_STALL_TIMEOUT = Duration.ofSeconds(45);
    static final Duration DISCONNECTED_SESSION_STALL_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration UNREACHABLE_PEER_BAN = Duration.ofMinutes(2);
    private static final Duration TIMED_OUT_ASSIGNMENT_BAN = Duration.ofSeconds(20);
    private static final int MAX_SESSION_RESTARTS = 2;
    private static final long PROGRESS_INTERVAL_MILLIS = 250L;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile BtClient client;

    @Override
    public Result download(String source, Path destinationDirectory, int parallelism,
                           Listener listener, Path resumeTarget) throws Exception {
        Listener sink = listener == null ? Listener.NONE : listener;
        try {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("请输入 BT 下载地址");
            if (destinationDirectory == null) throw new IllegalArgumentException("请选择保存目录");
            Files.createDirectories(destinationDirectory);
            if (cancelled.get()) throw new CancellationException("下载已取消");

            int peerLimit = MultiThreadDownloader.clampThreads(parallelism);
            String normalized = source.trim();
            Torrent localTorrent = loadMetadata(normalized);
            AtomicReference<Torrent> torrentRef = new AtomicReference<>(localTorrent);
            AtomicReference<Path> targetRef = new AtomicReference<>(resumeTarget);
            if (localTorrent != null) {
                Path target = torrentTarget(destinationDirectory, localTorrent);
                targetRef.set(target);
                sink.onPrepared(new Metadata(localTorrent.getName(), localTorrent.getSize(), target,
                        "BT · " + peerLimit + "连接"));
                sink.onStatus("正在校验本地分片");
                if (hasCompletePayload(destinationDirectory, localTorrent)) {
                    sink.onProgress(new Progress(localTorrent.getSize(), localTorrent.getSize(), 0,
                            "BT · 已校验"));
                    return new Result(target, localTorrent.getSize(),
                            "BT · 本地文件已完整");
                }
            }
            long transferredBeforeSession = 0L;
            int sessionRestarts = 0;
            while (true) {
                Config config = optimizedConfig(peerLimit, freeTcpPort());
                DHTConfig dht = new DHTConfig();
                dht.setListeningPort(freeUdpPort());
                dht.setShouldUseRouterBootstrap(true);

                int restartIndex = sessionRestarts;
                AtomicBoolean restartRequested = new AtomicBoolean();
                AtomicLong sessionTransferred = new AtomicLong();
                AtomicReference<String> lastStatus = new AtomicReference<>();
                StallMonitor stallMonitor = new StallMonitor(CONNECTED_SESSION_STALL_TIMEOUT,
                        DISCONNECTED_SESSION_STALL_TIMEOUT);
                StandaloneClientBuilder builder = Bt.client()
                        .config(config)
                        .storage(new FileSystemStorage(destinationDirectory))
                        .autoLoadModules()
                        .module(new DHTModule(dht))
                        .afterTorrentFetched(torrent -> {
                            torrentRef.set(torrent);
                            Path target = torrentTarget(destinationDirectory, torrent);
                            targetRef.set(target);
                            sink.onPrepared(new Metadata(torrent.getName(), torrent.getSize(), target,
                                    "BT · " + peerLimit + "连接"));
                            updateStatus(sink, lastStatus, restartIndex == 0
                                    ? "正在校验本地分片" : "BT 已重连，正在校验已有分片");
                        })
                        .stopWhenDownloaded();
                Torrent cachedTorrent = torrentRef.get();
                configureSource(builder, normalized, cachedTorrent);

                BtClient currentClient = builder.build();
                client = currentClient;
                long transferredBase = transferredBeforeSession;
                updateStatus(sink, lastStatus, restartIndex == 0
                        ? "正在获取种子信息和节点" : "正在重新获取 BT 节点");
                try {
                    currentClient.startAsync(state -> {
                        long sessionBytes = Math.max(0L, state.getDownloaded());
                        sessionTransferred.accumulateAndGet(sessionBytes, Math::max);
                        int peers = state.getConnectedPeers() == null
                                ? 0 : state.getConnectedPeers().size();
                        int piecesRemaining = state.getPiecesRemaining();
                        long now = System.nanoTime();
                        reportProgress(state, torrentRef.get(), peerLimit, peers,
                                saturatingAdd(transferredBase, sessionBytes), sink);

                        boolean stalled = stallMonitor.observe(sessionBytes,
                                state.getPiecesComplete(), piecesRemaining, peers, now);
                        if (peers == 0) {
                            updateStatus(sink, lastStatus, "正在查找 BT 节点");
                        } else if (stallMonitor.idleNanos(now) >= TimeUnit.SECONDS.toNanos(10)) {
                            updateStatus(sink, lastStatus, "BT 暂无数据，正在更换无响应节点");
                        } else {
                            updateStatus(sink, lastStatus, "BT 下载中");
                        }

                        if (stalled && restartIndex < MAX_SESSION_RESTARTS
                                && restartRequested.compareAndSet(false, true)) {
                            updateStatus(sink, lastStatus, "BT 长时间无数据，正在重连节点（"
                                    + (restartIndex + 1) + "/" + MAX_SESSION_RESTARTS + "）");
                            currentClient.stop();
                        }
                    }, PROGRESS_INTERVAL_MILLIS).join();
                } catch (CompletionException failure) {
                    if (cancelled.get()) throw new CancellationException("下载已取消");
                    Throwable cause = failure.getCause();
                    if (cause instanceof Exception exception) throw exception;
                    throw failure;
                } finally {
                    if (client == currentClient) client = null;
                }
                if (cancelled.get()) throw new CancellationException("下载已取消");
                if (restartRequested.get()) {
                    transferredBeforeSession = saturatingAdd(transferredBeforeSession,
                            sessionTransferred.get());
                    sessionRestarts++;
                    continue;
                }
                break;
            }

            Torrent torrent = torrentRef.get();
            if (torrent == null) throw new IllegalStateException("未能获取种子信息");
            Path target = targetRef.get();
            sink.onProgress(new Progress(torrent.getSize(), torrent.getSize(), 0,
                    "BT · 已校验"));
            return new Result(target, torrent.getSize(), "BT · " + peerLimit + "连接 · 重连"
                    + sessionRestarts);
        } finally {
            stopped.countDown();
        }
    }

    private static void reportProgress(TorrentSessionState state, Torrent torrent, int peerLimit,
                                       int peers, long transferredBytes, Listener sink) {
        if (state == null) return;
        long total = torrent == null ? -1L : torrent.getSize();
        long completed = -1L;
        if (total >= 0 && state.getPiecesTotal() > 0) {
            completed = Math.min(total, Math.round(total
                    * ((double) state.getPiecesComplete() / state.getPiecesTotal())));
        } else if (total >= 0 && state.getLeft() >= 0) {
            completed = Math.max(0L, total - state.getLeft());
        }
        String phase = state.getPiecesRemaining() == 0 ? "校验完成" : peers + "/" + peerLimit + "节点";
        sink.onProgress(new Progress(Math.max(0L, completed), total, peers, "BT · " + phase,
                transferredBytes));
    }

    static Config optimizedConfig(int peerLimit, int acceptorPort) {
        Config config = new Config();
        config.setAcceptorPort(acceptorPort);
        config.setNumOfHashingThreads(Math.max(1,
                Math.min(peerLimit, Runtime.getRuntime().availableProcessors())));
        config.setMaxConcurrentlyActivePeerConnectionsPerTorrent(peerLimit);
        config.setMaxPeerConnectionsPerTorrent(Math.max(peerLimit, peerLimit * 4));
        config.setMaxPendingConnectionRequests(Math.max(50, peerLimit * 8));
        config.setPeerDiscoveryInterval(PEER_DISCOVERY_INTERVAL);
        config.setPeerConnectionRetryInterval(PEER_RETRY_INTERVAL);
        config.setPeerConnectionRetryCount(8);
        config.setPeerConnectionTimeout(PEER_CONNECT_TIMEOUT);
        config.setPeerHandshakeTimeout(PEER_CONNECT_TIMEOUT);
        config.setPeerConnectionInactivityThreshold(PEER_INACTIVITY_TIMEOUT);
        config.setTrackerQueryInterval(TRACKER_QUERY_INTERVAL);
        config.setTrackerTimeout(TRACKER_TIMEOUT);
        config.setUnreachablePeerBanDuration(UNREACHABLE_PEER_BAN);
        config.setTimeoutedAssignmentPeerBanDuration(TIMED_OUT_ASSIGNMENT_BAN);
        return config;
    }

    private static void configureSource(StandaloneClientBuilder builder, String source,
                                        Torrent cachedTorrent) throws Exception {
        if (cachedTorrent != null) {
            builder.torrent(() -> cachedTorrent);
        } else if (source.regionMatches(true, 0, "magnet:", 0, 7)) {
            builder.magnet(source);
        } else {
            builder.torrent(URI.create(source).toURL());
        }
    }

    /**
     * Loads ordinary torrent metadata before starting network services. Magnet links deliberately
     * return {@code null}, because their metadata is only available from peers.
     */
    private static Torrent loadMetadata(String source) throws Exception {
        if (source.regionMatches(true, 0, "magnet:", 0, 7)) return null;
        return new MetadataService().fromUrl(URI.create(source).toURL());
    }

    /**
     * Fast path for an already downloaded payload. Besides avoiding unnecessary sockets and DHT
     * bootstrap work, this makes opening a completed torrent deterministic while offline.
     */
    static boolean hasCompletePayload(Path directory, Torrent torrent) throws Exception {
        List<TorrentFile> files = torrent.getFiles();
        if (files.isEmpty()) return torrent.getSize() == 0L;

        FileSystemStorage storage = new FileSystemStorage(directory);
        List<StorageUnit> units = new ArrayList<>(files.size());
        try {
            for (TorrentFile file : files) {
                StorageUnit unit = storage.getUnit(torrent, file);
                units.add(unit);
                if (unit.size() != file.getSize()) return false;
            }

            Iterator<byte[]> expectedHashes = torrent.getChunkHashes().iterator();
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            long bytesRemaining = torrent.getSize();
            int unitIndex = 0;
            long unitOffset = 0L;

            while (bytesRemaining > 0L) {
                if (!expectedHashes.hasNext()) return false;
                long chunkRemaining = Math.min(torrent.getChunkSize(), bytesRemaining);
                long chunkLength = chunkRemaining;
                sha1.reset();

                while (chunkRemaining > 0L) {
                    while (unitIndex < files.size()
                            && unitOffset >= files.get(unitIndex).getSize()) {
                        unitIndex++;
                        unitOffset = 0L;
                    }
                    if (unitIndex >= units.size()) return false;

                    long available = files.get(unitIndex).getSize() - unitOffset;
                    int readLength = (int) Math.min(Math.min(chunkRemaining, available), buffer.length);
                    ByteBuffer target = ByteBuffer.wrap(buffer, 0, readLength);
                    units.get(unitIndex).readBlockFully(target, unitOffset);
                    sha1.update(buffer, 0, readLength);
                    unitOffset += readLength;
                    chunkRemaining -= readLength;
                }

                if (!MessageDigest.isEqual(sha1.digest(), expectedHashes.next())) return false;
                bytesRemaining -= chunkLength;
            }
            return !expectedHashes.hasNext();
        } finally {
            for (StorageUnit unit : units) {
                try {
                    unit.close();
                } catch (Exception ignored) {
                    // Closing one cached file must not prevent the remaining files from closing.
                }
            }
        }
    }

    private static void updateStatus(Listener sink, AtomicReference<String> previous,
                                     String status) {
        String old = previous.getAndSet(status);
        if (!status.equals(old)) sink.onStatus(status);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static Path torrentTarget(Path directory, Torrent torrent) {
        List<TorrentFile> files = torrent.getFiles();
        if (files.size() != 1) return directory.resolve(safeName(torrent.getName()));
        Path target = directory;
        for (String element : files.get(0).getPathElements()) target = target.resolve(safeName(element));
        return target.normalize();
    }

    private static String safeName(String value) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")) return "_";
        return value.replace('/', '_').replace('\\', '_').trim();
    }

    private static int freeTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static final class StallMonitor {
        private final long connectedTimeoutNanos;
        private final long disconnectedTimeoutNanos;
        private long lastActivityNanos = Long.MIN_VALUE;
        private long lastDownloaded = -1L;
        private int lastPiecesComplete = -1;

        StallMonitor(Duration connectedTimeout, Duration disconnectedTimeout) {
            connectedTimeoutNanos = positiveNanos(connectedTimeout);
            disconnectedTimeoutNanos = positiveNanos(disconnectedTimeout);
        }

        synchronized boolean observe(long downloaded, int piecesComplete, int piecesRemaining,
                                     int connectedPeers, long nowNanos) {
            if (lastActivityNanos == Long.MIN_VALUE || downloaded < lastDownloaded
                    || piecesComplete < lastPiecesComplete) {
                lastActivityNanos = nowNanos;
            } else if (downloaded > lastDownloaded || piecesComplete > lastPiecesComplete) {
                lastActivityNanos = nowNanos;
            }
            lastDownloaded = downloaded;
            lastPiecesComplete = piecesComplete;
            if (piecesRemaining <= 0) return false;
            long timeout = connectedPeers > 0
                    ? connectedTimeoutNanos : disconnectedTimeoutNanos;
            return nowNanos - lastActivityNanos >= timeout;
        }

        synchronized long idleNanos(long nowNanos) {
            return lastActivityNanos == Long.MIN_VALUE
                    ? 0L : Math.max(0L, nowNanos - lastActivityNanos);
        }

        private static long positiveNanos(Duration duration) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("BT 卡顿检测时间必须大于 0");
            }
            return duration.toNanos();
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        BtClient current = client;
        if (current != null) current.stop();
    }

    @Override
    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }
}
