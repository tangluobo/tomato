package org.dkf.jed2k;

import org.dkf.jed2k.data.PieceBlock;
import org.dkf.jed2k.exception.ErrorCode;
import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.disk.DesktopFileHandler;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.PacketCombiner;
import org.dkf.jed2k.protocol.PacketHeader;
import org.dkf.jed2k.protocol.PacketKey;
import org.dkf.jed2k.protocol.Serializable;
import org.dkf.jed2k.protocol.client.Hello;
import org.dkf.jed2k.protocol.client.OutOfParts;
import org.dkf.jed2k.protocol.client.QueueRanking;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed2kCoreReliabilityTest {

    @Test
    void hierarchicalStatisticsDoNotCountLifetimeBytesOnEveryTick() {
        Statistics peer = new Statistics();
        Statistics transfer = new Statistics();

        peer.receiveBytes(0, 1_024);
        transfer.addCurrent(peer);
        peer.secondTick(1_000);
        transfer.secondTick(1_000);
        assertEquals(1_024, transfer.totalPayloadDownload());

        for (int i = 0; i < 5; i++) {
            transfer.addCurrent(peer);
            peer.secondTick(1_000);
            transfer.secondTick(1_000);
        }
        assertEquals(1_024, transfer.totalPayloadDownload());
        assertEquals(0, transfer.downloadPayloadRate());
    }

    @Test
    void exactPieceMultipleStillHasACompleteLastPiece() {
        long size = Constants.PIECE_SIZE * 2;

        assertEquals(Constants.PIECE_SIZE, Transfer.bytesInLastPiece(size));
        assertEquals(Constants.BLOCKS_PER_PIECE, Transfer.blocksInLastPiece(size));
    }

    @Test
    void usesProtocolStandardBlockSizeAndShortPieceTail() {
        assertEquals(180 * 1024L, Constants.BLOCK_SIZE);
        assertEquals(53, Constants.BLOCKS_PER_PIECE);

        PieceBlock tail = new PieceBlock(0, Constants.BLOCKS_PER_PIECE - 1);
        assertEquals(143_360, tail.size(Constants.PIECE_SIZE * 2));
        assertEquals(Constants.PIECE_SIZE, new PieceBlock(1, 0)
                .range(Constants.PIECE_SIZE * 2).left);
    }

    @Test
    void uses64BitPartRequestsOnlyWhenASelectedRangeCrosses4GiB() {
        long fileSize = 8L * 1024 * 1024 * 1024;
        assertFalse(PeerConnection.needs64BitOffsets(
                List.of(PieceBlock.make(0)), fileSize));
        assertTrue(PeerConnection.needs64BitOffsets(
                List.of(PieceBlock.make(0x1_0000_0000L)), fileSize));
    }

    @Test
    void restoredHashSetCanBeReusedForAnotherPeer() throws Exception {
        ArrayList<Hash> parts = new ArrayList<>(List.of(
                Hash.fromString("00112233445566778899AABBCCDDEEFF"),
                Hash.fromString("FFEEDDCCBBAA99887766554433221100")));
        Hash root = Hash.fromHashSet(parts);
        AddTransferParams params = new AddTransferParams(root, 1L,
                Constants.PIECE_SIZE * 2, Path.of("unused.part").toFile(), false);
        Transfer transfer = new Transfer(params,
                new PiecePicker(2, Constants.BLOCKS_PER_PIECE));

        assertFalse(transfer.hasHashSet());
        transfer.setHashSet(root, parts);
        assertTrue(transfer.hasHashSet());
    }

    @Test
    void openingResumedDownloadForWritingDoesNotTruncateIt() throws Exception {
        Path file = Files.createTempFile("jed2k-resume-", ".part");
        try {
            Files.write(file, new byte[]{1, 2, 3, 4});
            DesktopFileHandler handler = new DesktopFileHandler(file.toFile());
            handler.getWriteChannel().position(2);
            handler.getWriteChannel().write(ByteBuffer.wrap(new byte[]{9}));
            handler.close();

            assertArrayEquals(new byte[]{1, 2, 9, 4}, Files.readAllBytes(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void peerReconnectDeadlineBlocksOnlyUntilItExpires() throws Exception {
        Policy policy = new Policy(null);
        Peer peer = new Peer(new Endpoint(0x0100007f, 4662), true, 0);
        peer.setNextConnection(2_000L);
        policy.addPeer(peer);

        assertNull(policy.findConnectCandidate(1_999L));
        assertSame(peer, policy.findConnectCandidate(2_000L));
    }

    @Test
    void uploadRequestWaitIsNotClosedByOrdinaryPeerTimeout() throws Exception {
        Settings settings = new Settings();
        settings.peerConnectionTimeout = 1;
        settings.peerQueueResponseTimeout = 60;
        Session session = new Session(settings);

        try (Selector selector = Selector.open()) {
            session.selector = selector;
            Peer peer = new Peer(new Endpoint(0x0100007f, 4662), true, 0);
            PeerConnection connection = PeerConnection.make(session, peer.getEndpoint(), null, peer);
            connection.lastReceive = Time.currentTime() - Time.seconds(5);
            connection.beginUploadWait(Time.currentTime());

            connection.secondTick(1_000);

            assertTrue(connection.isQueued());
            assertTrue(connection.isAwaitingUploadResponse());
            assertFalse(connection.isDisconnecting());
            connection.close(ErrorCode.NO_ERROR);
        }
    }

    @Test
    void peerHelloAdvertisesActiveServerEndpoint() throws Exception {
        Endpoint serverEndpoint = new Endpoint(0x0100007f, 4661);
        Session session = new Session(new Settings()) {
            @Override
            public Endpoint getConnectedServerEndpoint() {
                return new Endpoint(serverEndpoint.getIP(), serverEndpoint.getPort());
            }
        };

        try (Selector selector = Selector.open()) {
            session.selector = selector;
            PeerConnection connection = PeerConnection.make(session,
                    new Endpoint(0x0200007f, 4662), null, null);

            Hello hello = (Hello) connection.prepareHello(new Hello());

            assertEquals(serverEndpoint, hello.serverPoint);
            connection.close(ErrorCode.NO_ERROR);
        }
    }

    @Test
    void awaitingQueueResponseCannotAlsoReportDownloading() throws Exception {
        Session session = new Session(new Settings());
        try (Selector selector = Selector.open()) {
            session.selector = selector;
            PeerConnection connection = PeerConnection.make(session,
                    new Endpoint(0x0100007f, 4662), null, null);
            connection.statistics().receiveBytes(0, 1_024);
            connection.statistics().secondTick(1_000);
            assertTrue(connection.statistics().downloadPayloadRate() > 0);

            connection.beginUploadWait(Time.currentTime());

            assertTrue(connection.isQueued());
            assertFalse(connection.isDownloading());
            connection.close(ErrorCode.NO_ERROR);
        }
    }

    @Test
    void unansweredUploadRequestUsesEd2kReaskDeadline() throws Exception {
        Settings settings = new Settings();
        settings.peerConnectionTimeout = 1;
        settings.peerQueueResponseTimeout = 1;
        settings.peerReaskTime = 29 * 60;
        Session session = new Session(settings);

        try (Selector selector = Selector.open()) {
            session.selector = selector;
            Peer peer = new Peer(new Endpoint(0x0100007f, 4662), true, 0);
            PeerConnection connection = PeerConnection.make(session, peer.getEndpoint(), null, peer);
            long before = Time.currentTime();
            long requestTime = before - Time.seconds(2);
            connection.beginUploadWait(requestTime);

            connection.secondTick(1_000);

            assertTrue(connection.isDisconnecting());
            assertFalse(connection.isFailed());
            assertEquals(requestTime + Time.minutes(29), peer.getNextConnection());
        }
    }

    @Test
    void rankedQueueIsRefreshedOnEd2kReaskCadence() throws Exception {
        Settings settings = new Settings();
        settings.peerReaskTime = 29 * 60;
        Session session = new Session(settings);

        try (Selector selector = Selector.open()) {
            session.selector = selector;
            PeerConnection connection = PeerConnection.make(session,
                    new Endpoint(0x0100007f, 4662), null, null);
            long requestTime = Time.currentTime();
            connection.beginUploadWait(requestTime);
            connection.onQueueRanking(new QueueRanking());

            assertTrue(connection.isQueued());
            assertFalse(connection.isAwaitingUploadResponse());
            assertFalse(connection.shouldReaskQueue(
                    requestTime + Time.minutes(29) - 1));
            assertTrue(connection.shouldReaskQueue(
                    requestTime + Time.minutes(29)));
            connection.close(ErrorCode.NO_ERROR);
        }
    }

    @Test
    void outOfPartsReturnsToQueueWithoutFailingAndKeepsReaskDeadline() throws Exception {
        Settings settings = new Settings();
        settings.peerReaskTime = 29 * 60;
        Session session = new Session(settings);

        try (Selector selector = Selector.open()) {
            session.selector = selector;
            Peer peer = new Peer(new Endpoint(0x0100007f, 4662), true, 0);
            Hash hash = Hash.fromString("00112233445566778899AABBCCDDEEFF");
            AddTransferParams params = new AddTransferParams(hash, 1L,
                    Constants.PIECE_SIZE, Path.of("unused.part").toFile(), false);
            Transfer transfer = new Transfer(params,
                    new PiecePicker(1, Constants.BLOCKS_PER_PIECE));
            PeerConnection connection = PeerConnection.make(
                    session, peer.getEndpoint(), transfer, peer);
            long before = Time.currentTime();

            connection.onClientOutOfParts(new OutOfParts());

            assertTrue(connection.isQueued());
            assertFalse(connection.isAwaitingUploadResponse());
            assertFalse(connection.isDisconnecting());
            assertFalse(connection.isFailed());

            // A later transport close must preserve the logical queue entry
            // and defer the next TCP reask instead of incrementing failCount.
            connection.close(ErrorCode.NO_ERROR);
            long after = Time.currentTime();
            assertFalse(connection.isFailed());
            assertTrue(peer.getNextConnection() >= before + Time.minutes(29));
            assertTrue(peer.getNextConnection() <= after + Time.minutes(29));
        }
    }

    @Test
    void incomingHighIdPeerCanBeMatchedToItsOnlyTransfer() throws Exception {
        Session session = new Session(new Settings());
        Endpoint endpoint = new Endpoint(0x0100007f, 4662);
        Hash hash = Hash.fromString("00112233445566778899AABBCCDDEEFF");
        AddTransferParams params = new AddTransferParams(hash, 1L,
                Constants.PIECE_SIZE, Path.of("unused.part").toFile(), false);
        Transfer transfer = new Transfer(params,
                new PiecePicker(1, Constants.BLOCKS_PER_PIECE));
        transfer.addPeer(endpoint, PeerInfo.SERVER);
        session.transfers.put(hash, transfer);

        assertSame(transfer, session.findTransferForPeer(endpoint));
        assertNull(session.findTransferForPeer(new Endpoint(0x0200007f, 4662)));
    }

    @Test
    void attachingAcceptedPeerDoesNotDuplicateSessionSocketRegistry() throws Exception {
        Session session = new Session(new Settings());
        Endpoint endpoint = new Endpoint(0x0100007f, 4662);
        Hash hash = Hash.fromString("00112233445566778899AABBCCDDEEFF");
        AddTransferParams params = new AddTransferParams(hash, 1L,
                Constants.PIECE_SIZE, Path.of("unused.part").toFile(), false);
        Transfer transfer = new Transfer(params,
                new PiecePicker(1, Constants.BLOCKS_PER_PIECE));
        transfer.addPeer(endpoint, PeerInfo.SERVER);

        try (Selector selector = Selector.open()) {
            session.selector = selector;
            PeerConnection connection = PeerConnection.make(session, endpoint, null, null);
            session.connections.add(connection);

            transfer.attachPeer(connection);

            assertEquals(1, session.connections.size());
            connection.close(ErrorCode.NO_ERROR);
            assertTrue(session.connections.isEmpty());
        }
    }

    @Test
    void closingSameEndpointSocketRemovesOnlyThatConnectionInstance() throws Exception {
        Session session = new Session(new Settings());
        Endpoint endpoint = new Endpoint(0x0100007f, 4662);

        try (Selector selector = Selector.open()) {
            session.selector = selector;
            PeerConnection first = PeerConnection.make(session, endpoint, null, null);
            PeerConnection second = PeerConnection.make(session, endpoint, null, null);
            session.connections.add(first);
            session.connections.add(second);

            session.closeConnection(second);

            assertEquals(1, session.connections.size());
            assertSame(first, session.connections.get(0));
            second.close(ErrorCode.NO_ERROR);
            first.close(ErrorCode.NO_ERROR);
            assertTrue(session.connections.isEmpty());
        }
    }

    @Test
    void partialSocketWritesDoNotDropTheUnwrittenTail() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            byte[] payload = new byte[8 * 1024 * 1024];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);

            try (Selector selector = Selector.open();
                 ServerSocketChannel listener = ServerSocketChannel.open()) {
                listener.bind(new InetSocketAddress("127.0.0.1", 0));
                try (SocketChannel client = SocketChannel.open()) {
                    client.socket().setSendBufferSize(1_024);
                    client.connect((InetSocketAddress) listener.getLocalAddress());
                    try (SocketChannel receiver = listener.accept()) {
                        receiver.configureBlocking(false);
                        Session session = new Session(new Settings());
                        session.selector = selector;
                        RawConnection connection = new RawConnection(session, client,
                                payload.length + 1);
                        connection.send(new RawPacket(payload));

                        ByteArrayOutputStream received = new ByteArrayOutputStream(payload.length);
                        ByteBuffer readBuffer = ByteBuffer.allocate(64 * 1024);
                        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                        while (received.size() < payload.length && System.nanoTime() < deadline) {
                            connection.onWriteable();
                            int read;
                            do {
                                readBuffer.clear();
                                read = receiver.read(readBuffer);
                                if (read > 0) received.write(readBuffer.array(), 0, read);
                            } while (read > 0);
                            Thread.onSpinWait();
                        }

                        assertArrayEquals(payload, received.toByteArray());
                    }
                }
            }
        });
    }

    private static final class RawConnection extends PeerConnection {
        RawConnection(Session session, SocketChannel socket, int bufferSize) throws Exception {
            super(ByteBuffer.allocate(16), ByteBuffer.allocate(bufferSize),
                    new RawPacketCombiner(), session, socket);
        }

        void send(Serializable packet) {
            write(packet);
        }

    }

    private static final class RawPacket implements Serializable {
        private final byte[] data;

        RawPacket(byte[] data) {
            this.data = Arrays.copyOf(data, data.length);
        }

        @Override
        public ByteBuffer get(ByteBuffer source) {
            return source;
        }

        @Override
        public ByteBuffer put(ByteBuffer destination) {
            return destination.put(data);
        }

        @Override
        public int bytesCount() {
            return data.length;
        }
    }

    private static final class RawPacketCombiner extends PacketCombiner {
        @Override
        public boolean pack(Serializable object, ByteBuffer destination) throws JED2KException {
            if (object.bytesCount() > destination.remaining()) return false;
            object.put(destination);
            return true;
        }

        @Override
        protected Class<? extends Serializable> keyToPacket(PacketKey key) {
            return null;
        }

        @Override
        protected PacketKey classToKey(Class<? extends Serializable> type) {
            return null;
        }

        @Override
        public int serviceSize(PacketHeader header) {
            return 0;
        }
    }
}
