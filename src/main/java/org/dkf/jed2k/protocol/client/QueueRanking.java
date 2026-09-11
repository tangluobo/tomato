package org.dkf.jed2k.protocol.client;

import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.protocol.Dispatchable;
import org.dkf.jed2k.protocol.Dispatcher;
import org.dkf.jed2k.protocol.Serializable;

import java.nio.ByteBuffer;

public class QueueRanking implements Serializable, Dispatchable {
    private static final int RESERVED_BYTES = 10;
    public long rank = 0;

    @Override
    public ByteBuffer get(ByteBuffer src) throws JED2KException {
        rank = Short.toUnsignedInt(src.getShort());
        // eMule sends ten reserved bytes after the uint16 rank.  Older peers
        // may send only the rank, so consume the extension when present.
        src.position(src.position() + Math.min(RESERVED_BYTES, src.remaining()));
        return src;
    }

    @Override
    public ByteBuffer put(ByteBuffer dst) throws JED2KException {
        dst.putShort((short) rank);
        for (int i = 0; i < RESERVED_BYTES; i++) dst.put((byte) 0);
        return dst;
    }

    @Override
    public int bytesCount() {
        return Short.BYTES + RESERVED_BYTES;
    }

    @Override
    public void dispatch(Dispatcher dispatcher) throws JED2KException {
        dispatcher.onQueueRanking(this);
    }
}
