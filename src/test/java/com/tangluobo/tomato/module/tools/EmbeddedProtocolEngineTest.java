package com.tangluobo.tomato.module.tools;

import bt.module.BtModuleProvider;
import bt.runtime.Config;
import bt.torrent.maker.TorrentBuilder;
import org.dkf.jed2k.EMuleLink;
import org.dkf.jed2k.Session;
import org.dkf.jed2k.PeerConnection;
import org.dkf.jed2k.TransferStatus;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.kad.KadNodesDat;
import org.dkf.jed2k.protocol.server.GetFileSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Arrays;
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
