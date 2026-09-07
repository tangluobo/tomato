package com.tangluobo.tomato.module.tools;

import org.dkf.jed2k.AddTransferParams;
import org.dkf.jed2k.EMuleLink;
import org.dkf.jed2k.Session;
import org.dkf.jed2k.Settings;
import org.dkf.jed2k.TransferHandle;
import org.dkf.jed2k.TransferStatus;
import org.dkf.jed2k.alert.Alert;
import org.dkf.jed2k.alert.TransferDiskIOErrorAlert;
import org.dkf.jed2k.alert.TransferResumeDataAlert;
import org.dkf.jed2k.disk.DesktopFileHandler;
import org.dkf.jed2k.kad.DhtTracker;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.kad.KadEntry;
import org.dkf.jed2k.protocol.kad.KadId;
import org.dkf.jed2k.protocol.kad.KadNodesDat;
import org.dkf.jed2k.protocol.server.ServerMet;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fully embedded pure-Java eDonkey/ED2K downloader with server and Kad bootstrapping. */
final class Ed2kDownloadEngine implements DownloadEngine {

    private static final URI SERVER_LIST = URI.create("https://upd.emule-security.org/server.met");
    private static final URI NODE_LIST = URI.create("https://upd.emule-security.org/nodes.dat");
    private static final long CHECKPOINT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(12);
    private static final long NO_SOURCE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(25);
    private static final long SOURCE_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(12);
    private static final long STALL_RECOVERY_NANOS = TimeUnit.SECONDS.toNanos(45);
    private static final long KAD_BOOTSTRAP_DELAY_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final int MAX_KAD_BOOTSTRAP_NODES = 32;
    private static final Path IDENTITY_FILE = Path.of(System.getProperty("user.home"),
            ".tomato", "ed2k-user-hash");
    private static final Object IDENTITY_LOCK = new Object();

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile Session session;
    private volatile DhtTracker dht;
    private volatile TransferHandle transfer;

    @Override
    public Result download(String source, Path destinationDirectory, int parallelism,
                           Listener listener, Path resumeTarget) throws Exception {
        Listener sink = listener == null ? Listener.NONE : listener;
        Path target = null;
        Path resumeFile = null;
        try {
            EMuleLink link = EMuleLink.fromString(source == null ? null : source.trim());
            if (link.getType() != EMuleLink.LinkType.FILE || link.getNumberValue() <= 0) {
                throw new IllegalArgumentException("仅支持 ED2K 文件链接");
            }
            Files.createDirectories(destinationDirectory);
            int connectionLimit = MultiThreadDownloader.clampThreads(parallelism);
            target = resumeTarget != null ? resumeTarget.toAbsolutePath().normalize()
                    : uniqueTarget(destinationDirectory, safeName(link.getStringValue()));
            resumeFile = resumePath(target);
            sink.onPrepared(new Metadata(target.getFileName().toString(), link.getNumberValue(),
                    target, "ED2K · " + connectionLimit + "连接"));
            sink.onStatus(Files.isRegularFile(resumeFile) ? "正在读取 ED2K 下载记录" : "正在连接 ED2K 网络");

            Settings settings = new Settings();
            settings.userAgent = loadOrCreateUserHash(IDENTITY_FILE);
            settings.clientName = "Tomato";
            settings.modName = "TomatoDownloader";
            int peerPort = freePeerPort();
            settings.listenPort = peerPort;
            settings.udpPort = peerPort;
            settings.sessionConnectionsLimit = connectionLimit;
            settings.maxPeerListSize = Math.max(20, connectionLimit * 8);
            settings.maxConnectionsPerSecond = Math.max(2, Math.min(10, connectionLimit));
            settings.peerConnectionTimeout = 30;
            // Server rotation is managed here so a connected server with no
            // sources cannot leave the task waiting forever.
            settings.reconnectoToServer = false;
            settings.serverPingTimeout = 60;

            Session currentSession = new Session(settings);
            session = currentSession;
            currentSession.start();
            try {
                currentSession.startUPnP();
            } catch (Exception ignored) {
                // Port forwarding is best-effort; low-ID clients can still use
                // server and Kad sources that accept outgoing connections.
            }
            KadBootstrap kadBootstrap = startKad(currentSession, peerPort, sink);
            ServerCursor serverCursor = new ServerCursor(loadServers(sink));
            ServerCandidate currentServer = connectNextServer(currentSession, serverCursor, sink);

            AddTransferParams restored = readResume(resumeFile, target, link);
            TransferHandle handle = restored == null
                    ? currentSession.addTransfer(link.getHash(), link.getNumberValue(), target.toFile())
                    : currentSession.addTransfer(restored);
            transfer = handle;
            if (!handle.isValid()) throw new IOException("无法创建 ED2K 下载任务");
            if (handle.isPaused()) handle.resume();
            // Old resume files may contain sources disabled by the previous
            // retry policy. Revive them once before the first connection pass.
            currentSession.recoverTransfer(link.getHash(), link.getNumberValue());

            long lastCheckpoint = 0L;
            long now = System.nanoTime();
            long connectionDeadline = now + CONNECT_TIMEOUT_NANOS;
            long nextSourceRefresh = now;
            long nextServerRotation = now + NO_SOURCE_TIMEOUT_NANOS;
            long nextStallRecovery = now + STALL_RECOVERY_NANOS;
            long kadBootstrapAt = now + KAD_BOOTSTRAP_DELAY_NANOS;
            boolean kadBootstrapAttempted = false;
            String connectedServer = "";
            ActivityMonitor activity = new ActivityMonitor(now);
            while (!cancelled.get() && !handle.isFinished()) {
                TransferStatus status = handle.getStatus();
                now = System.nanoTime();
                boolean madeProgress = activity.observe(status.totalDone, status.downloadPayload, now);
                boolean transferring = status.downloadPayloadRate > 0 || madeProgress;
                sink.onProgress(new Progress(status.totalDone, link.getNumberValue(),
                        status.numConnectedPeers, "ED2K · " + status.numConnectedPeers + "/"
                        + connectionLimit + "连接 · " + status.numPeers + "来源",
                        status.downloadPayload));

                if (!kadBootstrapAttempted && kadBootstrap != null && now >= kadBootstrapAt) {
                    bootstrapKad(kadBootstrap);
                    currentSession.requestSources(link.getHash(), link.getNumberValue());
                    kadBootstrapAttempted = true;
                    nextSourceRefresh = now + SOURCE_REFRESH_NANOS;
                }

                String serverId = currentSession.getConnectedServerId();
                if (madeProgress) {
                    nextServerRotation = now + NO_SOURCE_TIMEOUT_NANOS;
                    nextStallRecovery = now + STALL_RECOVERY_NANOS;
                }

                // A known source may merely be offline, rejected or queued.
                // Continue server/Kad discovery until payload actually moves.
                if (!transferring && now >= nextSourceRefresh) {
                    currentSession.requestServerSources(link.getHash(), link.getNumberValue());
                    nextSourceRefresh = now + SOURCE_REFRESH_NANOS;
                }
                if (!transferring && now >= nextStallRecovery) {
                    currentSession.recoverTransfer(link.getHash(), link.getNumberValue());
                    nextStallRecovery = now + STALL_RECOVERY_NANOS;
                }

                if (!serverId.isBlank()) {
                    if (!serverId.equals(connectedServer)) {
                        connectedServer = serverId;
                        nextSourceRefresh = now;
                        nextServerRotation = now + NO_SOURCE_TIMEOUT_NANOS;
                    }
                    if (!transferring && now >= nextServerRotation && serverCursor.size() > 1) {
                        currentServer = connectNextServer(currentSession, serverCursor, sink);
                        connectedServer = "";
                        connectionDeadline = now + CONNECT_TIMEOUT_NANOS;
                        nextServerRotation = now + NO_SOURCE_TIMEOUT_NANOS;
                    } else if (transferring) {
                        sink.onStatus("ED2K 下载中 · " + status.numConnectedPeers + " 个连接");
                    } else if (status.numPeers > 0) {
                        sink.onStatus(waitingSourceStatus(status, serverCursor,
                                kadBootstrap, nextServerRotation - now));
                    } else {
                        sink.onStatus(noSourceStatus(serverId, serverCursor,
                                kadBootstrap, nextServerRotation - now));
                    }
                } else {
                    connectedServer = "";
                    if (now >= connectionDeadline && serverCursor.size() > 0) {
                        currentServer = connectNextServer(currentSession, serverCursor, sink);
                        connectionDeadline = now + CONNECT_TIMEOUT_NANOS;
                    } else {
                        sink.onStatus(connectingStatus(currentServer, serverCursor, kadBootstrap));
                    }
                }

                if (lastCheckpoint == 0L || now - lastCheckpoint >= CHECKPOINT_INTERVAL_NANOS) {
                    currentSession.saveResumeData();
                    lastCheckpoint = now;
                }
                drainAlerts(currentSession, resumeFile);
                TimeUnit.MILLISECONDS.sleep(250L);
            }
            if (cancelled.get()) throw new CancellationException("下载已取消");
            saveResumeAndWait(currentSession, resumeFile);
            Files.deleteIfExists(resumeFile);
            sink.onProgress(new Progress(link.getNumberValue(), link.getNumberValue(), 0,
                    "ED2K · 已校验"));
            return new Result(target, link.getNumberValue(), "ED2K · " + connectionLimit + "连接");
        } finally {
            Session currentSession = session;
            if (currentSession != null && resumeFile != null) {
                try {
                    saveResumeAndWait(currentSession, resumeFile);
                } catch (Exception ignored) {
                }
                currentSession.abort();
            }
            DhtTracker currentDht = dht;
            if (currentDht != null) currentDht.abort();
            joinQuietly(currentSession, 2_000L);
            joinQuietly(currentDht, 2_000L);
            stopped.countDown();
        }
    }

    private KadBootstrap startKad(Session currentSession, int port, Listener sink) {
        DhtTracker tracker = null;
        try {
            tracker = new DhtTracker(port, new KadId(Hash.random(false)), null);
            dht = tracker;
            currentSession.setDhtTracker(tracker);
            tracker.start();
            List<Endpoint> bootstrapEndpoints = new ArrayList<>();
            try {
                byte[] data = fetch(NODE_LIST);
                ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                KadNodesDat nodes = new KadNodesDat();
                nodes.get(buffer);
                List<KadEntry> entries = kadEntries(nodes);
                tracker.addKadEntries(entries);
                entries.stream()
                        .map(entry -> entry.getKadEndpoint().getEndpoint())
                        .filter(Endpoint::defined)
                        .limit(MAX_KAD_BOOTSTRAP_NODES)
                        .forEach(bootstrapEndpoints::add);
            } catch (Exception ex) {
                sink.onStatus("Kad 节点列表获取失败，正在使用 ED2K 服务器");
            }
            return new KadBootstrap(tracker, List.copyOf(bootstrapEndpoints));
        } catch (Exception ex) {
            if (tracker != null) tracker.abort();
            sink.onStatus("Kad 启动失败，正在使用 ED2K 服务器");
            return null;
        }
    }

    private List<ServerCandidate> loadServers(Listener sink) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(fetch(SERVER_LIST)).order(ByteOrder.LITTLE_ENDIAN);
            ServerMet serverMet = new ServerMet();
            serverMet.get(buffer);
            List<ServerCandidate> servers = serverMet.getServers().getList().stream()
                    .filter(server -> server.getHost() != null && !server.getHost().isBlank())
                    .filter(server -> server.getPort() > 0)
                    .map(server -> new ServerCandidate(server.getName(), server.getHost(), server.getPort()))
                    .sorted(Comparator.comparingInt(Ed2kDownloadEngine::serverPriority))
                    .toList();
            if (!servers.isEmpty()) return servers;
        } catch (Exception ex) {
            sink.onStatus("服务器列表获取失败，正在使用 Kad");
        }
        return List.of();
    }

    private static ServerCandidate connectNextServer(Session currentSession,
                                                       ServerCursor cursor, Listener sink) {
        ServerCandidate server = cursor.next();
        if (server == null) {
            sink.onStatus("没有可用服务器，正在通过 Kad 查找来源");
            return null;
        }
        currentSession.connectoTo(server.displayName(), server.host(), server.port());
        sink.onStatus("正在连接 ED2K 服务器 " + server.displayName()
                + "（" + cursor.position() + "/" + cursor.size() + "）");
        return server;
    }

    private static int serverPriority(ServerCandidate server) {
        String name = server.displayName().toLowerCase(Locale.ROOT);
        return name.contains("test") || name.contains("rust") ? 1 : 0;
    }

    private static void bootstrapKad(KadBootstrap bootstrap) {
        try {
            if (bootstrap.tracker().needBootstrap() && !bootstrap.endpoints().isEmpty()) {
                bootstrap.tracker().bootstrap(bootstrap.endpoints());
            }
        } catch (Exception ignored) {
            // The already queued nodes.dat contacts continue bootstrapping.
        }
    }

    static List<KadEntry> kadEntries(KadNodesDat nodes) {
        List<KadEntry> entries = new ArrayList<>(nodes.getContacts());
        List<KadEntry> bootstrapEntries = nodes.getBootstrapEntries().getList();
        if (bootstrapEntries != null) entries.addAll(bootstrapEntries);
        return entries;
    }

    private byte[] fetch(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Tomato/1.0 ED2K")
                .GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String noSourceStatus(String serverId, ServerCursor cursor,
                                         KadBootstrap kad, long remainingNanos) {
        long seconds = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(remainingNanos) + 1L);
        String name = serverId == null || serverId.isBlank() ? "ED2K 服务器" : serverId;
        String switchHint = cursor.size() > 1 ? "，" + seconds + " 秒后切换服务器" : "";
        return "已连接 " + name + "，暂未找到在线来源" + switchHint
                + kadSuffix(kad);
    }

    private static String waitingSourceStatus(TransferStatus status, ServerCursor cursor,
                                              KadBootstrap kad, long remainingNanos) {
        long seconds = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(remainingNanos) + 1L);
        String queue = status.numQueuedPeers > 0
                ? "，其中 " + status.numQueuedPeers + " 个正在排队"
                : "，正在连接或等待响应";
        String switchHint = cursor.size() > 1 ? "，" + seconds + " 秒后切换服务器" : "";
        return "已找到 " + status.numPeers + " 个来源" + queue
                + "；仍在继续搜索" + switchHint + kadSuffix(kad);
    }

    private static String connectingStatus(ServerCandidate server, ServerCursor cursor, KadBootstrap kad) {
        if (server == null) return "正在通过 Kad 查找文件来源" + kadSuffix(kad);
        return "正在连接 " + server.displayName() + "（" + cursor.position() + "/"
                + cursor.size() + "）" + kadSuffix(kad);
    }

    private static String kadSuffix(KadBootstrap kad) {
        if (kad == null) return "";
        try {
            var size = kad.tracker().getRoutingTableSize();
            return " · Kad " + (size.getLeft() + size.getRight()) + " 节点";
        } catch (Exception ignored) {
            return " · Kad 引导中";
        }
    }

    private static AddTransferParams readResume(Path resumeFile, Path target, EMuleLink link) {
        if (!Files.isRegularFile(resumeFile)) return null;
        try {
            byte[] bytes = Files.readAllBytes(resumeFile);
            AddTransferParams params = new AddTransferParams();
            params.get(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
            if (!params.getHash().equals(link.getHash())
                    || params.getSize().longValue() != link.getNumberValue()) return null;
            params.setExternalFileHandler(new DesktopFileHandler(target.toFile()));
            return params;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void drainAlerts(Session currentSession, Path resumeFile) throws Exception {
        Alert alert;
        while ((alert = currentSession.popAlert()) != null) {
            if (alert instanceof TransferResumeDataAlert resumeAlert) {
                writeResume(resumeFile, resumeAlert.trd);
            } else if (alert instanceof TransferDiskIOErrorAlert diskError) {
                throw new IOException("ED2K 文件写入失败: " + diskError.ec.getDescription());
            }
        }
    }

    private static void saveResumeAndWait(Session currentSession, Path resumeFile) throws Exception {
        currentSession.saveResumeData();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500L);
        do {
            drainAlerts(currentSession, resumeFile);
            if (System.nanoTime() >= deadline) break;
            TimeUnit.MILLISECONDS.sleep(50L);
        } while (!currentSession.isFinished());
        drainAlerts(currentSession, resumeFile);
    }

    private static void writeResume(Path resumeFile, AddTransferParams params) throws Exception {
        Path temporary = resumeFile.resolveSibling(resumeFile.getFileName() + ".tmp");
        ByteBuffer buffer = ByteBuffer.allocate(params.bytesCount()).order(ByteOrder.LITTLE_ENDIAN);
        params.put(buffer);
        buffer.flip();
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, resumeFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, resumeFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path uniqueTarget(Path directory, String fileName) throws IOException {
        Path target = directory.resolve(fileName);
        if (!Files.exists(target)) return target;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String suffix = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path candidate = directory.resolve(base + " (" + i + ")" + suffix);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("无法生成可用文件名");
    }

    private static String safeName(String value) {
        String result = value == null ? "download.ed2k" : value.replace('/', '_').replace('\\', '_').trim();
        if (result.isBlank() || result.equals(".") || result.equals("..")) return "download.ed2k";
        return result;
    }

    private static Path resumePath(Path target) {
        return target.resolveSibling(target.getFileName() + ".ed2k.resume");
    }

    static Hash loadOrCreateUserHash(Path identityFile) throws IOException {
        synchronized (IDENTITY_LOCK) {
            if (Files.isRegularFile(identityFile)) {
                String value = Files.readString(identityFile, StandardCharsets.US_ASCII).trim();
                if (value.matches("(?i)[0-9a-f]{32}")) return Hash.fromString(value);
            }

            Hash identity = Hash.random(true);
            Path parent = identityFile.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = identityFile.resolveSibling(identityFile.getFileName() + ".tmp");
            Files.writeString(temporary, identity.toString(), StandardCharsets.US_ASCII);
            try {
                Files.move(temporary, identityFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, identityFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return identity;
        }
    }

    static void discardRecord(Path target) {
        if (target == null) return;
        try {
            Files.deleteIfExists(resumePath(target));
        } catch (IOException ignored) {
        }
    }

    private static int freePeerPort() throws IOException {
        for (int attempt = 0; attempt < 32; attempt++) {
            try (ServerSocket tcp = new ServerSocket(0)) {
                int port = tcp.getLocalPort();
                try (DatagramSocket ignored = new DatagramSocket(port)) {
                    return port;
                } catch (IOException ignored) {
                    // Try another TCP-selected port that is also free for UDP.
                }
            }
        }
        throw new IOException("无法分配 ED2K TCP/UDP 端口");
    }

    private record ServerCandidate(String name, String host, int port) {
        String displayName() {
            return name == null || name.isBlank() ? host + ":" + port : name;
        }
    }

    private static final class ServerCursor {
        private final List<ServerCandidate> servers;
        private int index = -1;

        private ServerCursor(List<ServerCandidate> servers) {
            this.servers = servers;
        }

        ServerCandidate next() {
            if (servers.isEmpty()) return null;
            index = (index + 1) % servers.size();
            return servers.get(index);
        }

        int size() {
            return servers.size();
        }

        int position() {
            return index < 0 ? 0 : index + 1;
        }
    }

    private record KadBootstrap(DhtTracker tracker, List<Endpoint> endpoints) {
    }

    static final class ActivityMonitor {
        private long lastDone = -1L;
        private long lastPayload = -1L;
        private long lastActivityAt;

        ActivityMonitor(long now) {
            lastActivityAt = now;
        }

        boolean observe(long done, long payload, long now) {
            boolean progressed = lastDone >= 0L && (done > lastDone || payload > lastPayload);
            lastDone = Math.max(lastDone, done);
            lastPayload = Math.max(lastPayload, payload);
            if (progressed) lastActivityAt = now;
            return progressed;
        }

        long idleNanos(long now) {
            return Math.max(0L, now - lastActivityAt);
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        if (thread == null) return;
        try {
            thread.join(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        TransferHandle handle = transfer;
        if (handle != null) handle.pause();
    }

    @Override
    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }
}
