package org.dkf.jed2k;

import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.PacketCombiner;
import org.dkf.jed2k.protocol.PacketHeader;
import org.dkf.jed2k.protocol.PacketKey;
import org.dkf.jed2k.protocol.Serializable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class Ed2kCoreReliabilityTest {

    @Test
    void exactPieceMultipleStillHasACompleteLastPiece() {
        long size = Constants.PIECE_SIZE * 2;

        assertEquals(Constants.PIECE_SIZE, Transfer.bytesInLastPiece(size));
        assertEquals(Constants.BLOCKS_PER_PIECE, Transfer.blocksInLastPiece(size));
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
