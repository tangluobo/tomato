package com.tangluobo.tomato.zmodem.xfer.zm.packet;

import com.tangluobo.tomato.zmodem.xfer.util.ASCII;
import com.tangluobo.tomato.zmodem.xfer.util.Buffer;
import com.tangluobo.tomato.zmodem.xfer.util.ByteBuffer;
import com.tangluobo.tomato.zmodem.xfer.zm.util.ZMPacket;

public class Cancel extends ZMPacket {
    @Override
    public Buffer marshall() {
        ByteBuffer buff = ByteBuffer.allocate(16);
        for (int i = 0; i < 8; i++) buff.put(ASCII.CAN.value());
        for (int i = 0; i < 8; i++) buff.put(ASCII.BS.value());
        buff.flip();
        return buff;
    }

    @Override
    public String toString() { return "Cancel: CAN * 8 + BS * 8"; }
}
