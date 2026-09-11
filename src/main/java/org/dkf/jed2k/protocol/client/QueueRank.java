package org.dkf.jed2k.protocol.client;

import org.dkf.jed2k.exception.JED2KException;

import java.nio.ByteBuffer;

/** Legacy eDonkey queue rank packet (OP_EDONKEYPROT:OP_QUEUERANK). */
public class QueueRank extends QueueRanking {

    @Override
    public ByteBuffer get(ByteBuffer src) throws JED2KException {
        rank = Integer.toUnsignedLong(src.getInt());
        return src;
    }

    @Override
    public ByteBuffer put(ByteBuffer dst) throws JED2KException {
        return dst.putInt((int) rank);
    }

    @Override
    public int bytesCount() {
        return Integer.BYTES;
    }
}
