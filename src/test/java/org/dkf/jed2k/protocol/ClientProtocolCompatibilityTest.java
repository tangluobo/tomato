package org.dkf.jed2k.protocol;

import org.dkf.jed2k.protocol.client.QueueRank;
import org.dkf.jed2k.protocol.client.QueueRanking;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProtocolCompatibilityTest {

    @Test
    void partStatusUsesEd2kLeastSignificantBitFirstOrder() throws Exception {
        BitField status = new BitField(10);
        status.setBit(0);
        status.setBit(7);
        status.setBit(8);
        status.setBit(9);
        ByteBuffer wire = ByteBuffer.allocate(status.bytesCount()).order(ByteOrder.LITTLE_ENDIAN);

        status.put(wire);

        assertArrayEquals(new byte[]{0x0a, 0x00, (byte) 0x81, 0x03}, wire.array());

        BitField decoded = new BitField();
        decoded.get(ByteBuffer.wrap(new byte[]{0x08, 0x00, 0x01}).order(ByteOrder.LITTLE_ENDIAN));
        assertTrue(decoded.getBit(0));
        assertFalse(decoded.getBit(7));
    }

    @Test
    void leastSignificantBitOperationsKeepCountsAndTrailingBitsConsistent() {
        BitField status = new BitField(new byte[]{(byte) 0xff}, 3);
        assertEquals(0x07, Byte.toUnsignedInt(status.bytes()[0]));
        assertEquals(3, status.count());

        status.resize(6, true);
        assertEquals(0x3f, Byte.toUnsignedInt(status.bytes()[0]));
        assertEquals(6, status.count());

        status.clearBit(4);
        assertFalse(status.getBit(4));
        assertEquals(5, status.count());
    }

    @Test
    void parsesLegacyEdonkeyQueueRankAsUnsigned32BitValue() throws Exception {
        org.dkf.jed2k.protocol.client.PacketCombiner combiner =
                new org.dkf.jed2k.protocol.client.PacketCombiner();
        PacketHeader header = new PacketHeader();
        header.reset(PacketKey.pk(PacketCombiner.ProtocolType.OP_EDONKEYPROT.value,
                (byte) 0x5c), 1 + Integer.BYTES);
        ByteBuffer body = ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xf1234567);
        body.flip();

        Serializable packet = combiner.unpack(header, body);

        QueueRank rank = assertInstanceOf(QueueRank.class, packet);
        assertEquals(0xf1234567L, rank.rank);
        assertEquals(Integer.BYTES, rank.bytesCount());
    }

    @Test
    void parsesExtendedQueueRankingAsUnsigned16BitValue() throws Exception {
        org.dkf.jed2k.protocol.client.PacketCombiner combiner =
                new org.dkf.jed2k.protocol.client.PacketCombiner();
        PacketHeader header = new PacketHeader();
        header.reset(PacketKey.pk(PacketCombiner.ProtocolType.OP_EMULEPROT.value,
                (byte) 0x60), 1 + 12);
        ByteBuffer body = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) 0xf123);
        body.put(new byte[10]);
        body.flip();

        Serializable packet = combiner.unpack(header, body);

        QueueRanking rank = assertInstanceOf(QueueRanking.class, packet);
        assertEquals(0xf123L, rank.rank);
        assertEquals(12, rank.bytesCount());
        assertFalse(body.hasRemaining());
    }
}
