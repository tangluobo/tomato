package com.tangluobo.tomato.module.tools;

import bt.module.BtModuleProvider;
import bt.runtime.Config;
import bt.torrent.maker.TorrentBuilder;
import org.dkf.jed2k.EMuleLink;
import org.dkf.jed2k.Session;
import org.dkf.jed2k.PeerConnection;
import org.dkf.jed2k.Peer;
import org.dkf.jed2k.PiecePicker;
import org.dkf.jed2k.TransferStatus;
import org.dkf.jed2k.data.PieceBlock;
import org.dkf.jed2k.protocol.BitField;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.kad.KadNodesDat;
import org.dkf.jed2k.protocol.server.GetFileSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class EmbeddedProtocolEngineTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void mergedBtModuleKeepsDhtAndHttpTrackerProviders() {
        var providers = ServiceLoader.load(BtModuleProvider.class).stream()
                .map(provider -> provider.type().getName())
                .toList();
        assertTrue(providers.contains("bt.dht.DHTModuleProvider"));
        assertTrue(providers.contains("bt.tracker.http.HttpTrackerModuleProvider"));
    }

    @Test
    void bundledEd2kCoreParsesFileIdentity() throws Exception {
        EMuleLink link = EMuleLink.fromString(
                "ed2k://|file|example.iso|7340032|31D6CFE0D16AE931B73C59D7E0C089C0|/");
        assertEquals(EMuleLink.LinkType.FILE, link.getType());
        assertEquals("example.iso", link.getStringValue());
        assertEquals(7_340_032L, link.getNumberValue());
    }

    @Test
    void bundledEd2kCoreCanRefreshSourcesAfterReconnect() throws Exception {
        assertEquals(void.class,
                Session.class.getMethod("requestSources", Hash.class, long.class).getReturnType());
        assertEquals(void.class,
                Session.class.getMethod("requestServerSources", Hash.class, long.class).getReturnType());
        assertEquals(void.class,
                Session.class.getMethod("recoverTransfer", Hash.class, long.class).getReturnType());
        assertEquals(boolean.class, PeerConnection.class.getMethod("isQueued").getReturnType());
        assertEquals(int.class, TransferStatus.class.getField("numConnectedPeers").getType());
        assertEquals(int.class, TransferStatus.class.getField("numQueuedPeers").getType());
    }

    @Test
    void legacyOkHttpClientRemainsCompatibleWithSharedOkioRuntime() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread responder = Thread.ofPlatform().start(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(socket.getInputStream()));
                     OutputStream output = socket.getOutputStream()) {
                    while (!reader.readLine().isEmpty()) {
                        // Consume the request headers before sending the response.
                    }
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                            + "Connection: close\r\n\r\nok").getBytes());
                    output.flush();
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            com.squareup.okhttp.OkHttpClient client = new com.squareup.okhttp.OkHttpClient();
            com.squareup.okhttp.Request request = new com.squareup.okhttp.Request.Builder()
                    .url("http://127.0.0.1:" + server.getLocalPort() + "/compatibility")
                    .build();

            com.squareup.okhttp.Response response = client.newCall(request).execute();
            try {
                assertEquals(200, response.code());
                assertEquals("ok", response.body().string());
            } finally {
                response.body().close();
            }
            responder.join(2_000L);
            assertFalse(responder.isAlive());
        }
    }

    @Test
    void ed2kLargeFileSourceRequestAdvertisesEverySerializedByte() throws Exception {
        GetFileSources request = new GetFileSources(Hash.fromString(
                "31D6CFE0D16AE931B73C59D7E0C089C0"), 1, 0x23456789);
        ByteBuffer packet = ByteBuffer.allocate(request.bytesCount()).order(ByteOrder.LITTLE_ENDIAN);

        request.put(packet);

        assertEquals(28, request.bytesCount());
        assertEquals(0, packet.remaining());
    }

    @Test
    void ed2kIdentityIsUniqueAndStableAcrossRestarts() throws Exception {
        Path identityFile = temporaryDirectory.resolve("ed2k-user-hash");

        Hash first = Ed2kDownloadEngine.loadOrCreateUserHash(identityFile);
        Hash second = Ed2kDownloadEngine.loadOrCreateUserHash(identityFile);

        assertEquals(first, second);
        assertEquals(32, Files.readString(identityFile).trim().length());
        assertEquals(14, Byte.toUnsignedInt(first.at(5)));
        assertEquals(111, Byte.toUnsignedInt(first.at(14)));
    }

    @Test
    void ed2kActivityMonitorOnlyResetsOnNewPayload() {
        Ed2kDownloadEngine.ActivityMonitor monitor = new Ed2kDownloadEngine.ActivityMonitor(100L);

        assertFalse(monitor.observe(0L, 0L, 200L));
        assertEquals(200L, monitor.idleNanos(300L));
        assertTrue(monitor.observe(0L, 1_024L, 400L));
        assertEquals(50L, monitor.idleNanos(450L));
        assertFalse(monitor.observe(0L, 1_024L, 500L));
        assertEquals(100L, monitor.idleNanos(500L));
    }

    @Test
    void ed2kKeepsExpandingSourcesWhileOnlyOneSlowPeerTransfers() {
        TransferStatus status = new TransferStatus();
        status.numPeers = 80;
        status.numDownloadingPeers = 1;
        status.downloadPayloadRate = 32 * 1_024;

        assertTrue(Ed2kDownloadEngine.needsMoreSources(status, 8));

        status.numDownloadingPeers = 3;
        status.downloadPayloadRate = 1024 * 1_024;
        assertFalse(Ed2kDownloadEngine.needsMoreSources(status, 8));
    }

    @Test
    void ed2kPickerRequestsOnlyPiecesAdvertisedByThePeer() {
        PiecePicker picker = new PiecePicker(3, 50);
        Peer peer = new Peer(new Endpoint(0x0100007f, 4662), true, 0);
        BitField available = new BitField(3);
        available.setBit(1);
        var selected = new ArrayList<PieceBlock>();

        picker.pickPieces(selected, 3, peer, PeerConnection.PeerSpeed.SLOW, available);

        assertEquals(3, selected.size());
        assertTrue(selected.stream().allMatch(block -> block.pieceIndex == 1));
    }

    @Test
    void bundledEd2kServerListProvidesImmediateOfflineBootstrap() throws Exception {
        byte[] data;
        try (var input = getClass().getResourceAsStream("/server.met")) {
            assertTrue(input != null);
            data = input.readAllBytes();
        }

        var servers = Ed2kDownloadEngine.parseServers(data);

        assertFalse(servers.isEmpty());
        assertTrue(servers.stream().allMatch(server -> !server.host().isBlank()
                && server.port() > 0));
    }

    @Test
    void torrentConfigRecyclesUnresponsivePeersPromptly() {
        Config config = TorrentDownloadEngine.optimizedConfig(8, 6881);

        assertEquals(TorrentDownloadEngine.PEER_DISCOVERY_INTERVAL,
                config.getPeerDiscoveryInterval());
        assertEquals(TorrentDownloadEngine.PEER_RETRY_INTERVAL,
                config.getPeerConnectionRetryInterval());
        assertEquals(TorrentDownloadEngine.PEER_CONNECT_TIMEOUT,
                config.getPeerConnectionTimeout());
        assertEquals(TorrentDownloadEngine.PEER_INACTIVITY_TIMEOUT,
                config.getPeerConnectionInactivityThreshold());
        assertEquals(TorrentDownloadEngine.TRACKER_QUERY_INTERVAL,
                config.getTrackerQueryInterval());
        assertEquals(TorrentDownloadEngine.TRACKER_TIMEOUT, config.getTrackerTimeout());
        assertEquals(8, config.getMaxConcurrentlyActivePeerConnectionsPerTorrent());
        assertEquals(32, config.getMaxPeerConnectionsPerTorrent());
    }

    @Test
    void torrentStallMonitorUsesSeparateConnectedAndDiscoveryTimeouts() {
        TorrentDownloadEngine.StallMonitor monitor = new TorrentDownloadEngine.StallMonitor(
                Duration.ofSeconds(1), Duration.ofSeconds(2));

        assertFalse(monitor.observe(0L, 0, 10, 0, 0L));
        assertFalse(monitor.observe(0L, 0, 10, 0,
                TimeUnit.MILLISECONDS.toNanos(1_999)));
        assertTrue(monitor.observe(0L, 0, 10, 0, TimeUnit.SECONDS.toNanos(2)),
                "a stuck discovery session should eventually be rebuilt");

        TorrentDownloadEngine.StallMonitor connected = new TorrentDownloadEngine.StallMonitor(
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertFalse(connected.observe(1_000L, 0, 10, 1, TimeUnit.SECONDS.toNanos(6)));
        assertFalse(connected.observe(1_000L, 0, 10, 1,
                TimeUnit.MILLISECONDS.toNanos(6_999)));
        assertTrue(connected.observe(1_000L, 0, 10, 1, TimeUnit.SECONDS.toNanos(7)));

        TorrentDownloadEngine.StallMonitor disconnected = new TorrentDownloadEngine.StallMonitor(
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertFalse(disconnected.observe(1_000L, 1, 10, 1, 0L));
        assertFalse(disconnected.observe(1_000L, 1, 10, 0,
                TimeUnit.MILLISECONDS.toNanos(1_999)));
        assertTrue(disconnected.observe(1_000L, 1, 10, 0, TimeUnit.SECONDS.toNanos(2)));
    }

    @Test
    void versionTwoKadNodeListDoesNotRequireBootstrapSection() throws Exception {
        ByteBuffer data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(2).putInt(0).flip();
        KadNodesDat nodes = new KadNodesDat();
        nodes.get(data);
        assertTrue(Ed2kDownloadEngine.kadEntries(nodes).isEmpty());
    }

    @Test
    void torrentEngineVerifiesExistingPiecesAndCompletesWithoutExternalClient() throws Exception {
        Path sourceDirectory = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path destinationDirectory = Files.createDirectory(temporaryDirectory.resolve("destination"));
        byte[] content = new byte[320 * 1024];
        Arrays.fill(content, (byte) 0x5a);
        Path sourceFile = sourceDirectory.resolve("payload.bin");
        Files.write(sourceFile, content);
        Files.write(destinationDirectory.resolve("payload.bin"), content);
        byte[] torrentBytes = new TorrentBuilder()
                .rootPath(sourceDirectory)
                .addFiles(sourceFile)
                .build();
        Path torrentFile = temporaryDirectory.resolve("payload.torrent");
        Files.write(torrentFile, torrentBytes);

        TorrentDownloadEngine engine = new TorrentDownloadEngine();
        DownloadEngine.Result result = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> engine.download(torrentFile.toUri().toString(), destinationDirectory,
                        2, DownloadEngine.Listener.NONE, null));

        assertEquals(content.length, result.downloadedBytes());
        assertTrue(Files.exists(result.file()));
    }
}
