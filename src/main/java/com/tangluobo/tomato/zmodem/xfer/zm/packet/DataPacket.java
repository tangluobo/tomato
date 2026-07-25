package com.tangluobo.tomato.zmodem.xfer.zm.packet;

import com.tangluobo.tomato.zmodem.xfer.util.Arrays;
import com.tangluobo.tomato.zmodem.xfer.util.Buffer;
import com.tangluobo.tomato.zmodem.xfer.util.ByteBuffer;
import com.tangluobo.tomato.zmodem.xfer.util.CRC;
import com.tangluobo.tomato.zmodem.xfer.util.InvalidChecksumException;
import com.tangluobo.tomato.zmodem.xfer.zm.util.ZDLEEncoder;
import com.tangluobo.tomato.zmodem.xfer.zm.util.ZMPacket;
import com.tangluobo.tomato.zmodem.xfer.zm.util.ZModemCharacter;

public class DataPacket extends ZMPacket {
    public static DataPacket unmarshall(Buffer buff, CRC crc) {
        byte[] data = new byte[buff.remaining() - crc.size() - 1];
        buff.get(data);
        ZModemCharacter type = ZModemCharacter.forbyte(buff.get());
        byte[] netCrc = new byte[crc.size()];
        buff.get(netCrc);
        if (!Arrays.equals(netCrc, crc.getBytes())) throw new InvalidChecksumException();
        return new DataPacket(type, data);
    }

    private final ZModemCharacter type;
    private byte[] data = new byte[0];

    public DataPacket(ZModemCharacter fe) { type = fe; }
    public DataPacket(ZModemCharacter fr, byte[] d) { this(fr); data = d; }

    public ZModemCharacter type() { return type; }
    public byte[] data() { return data; }
    public void setData(byte[] d) { data = d; }
    public void copyData(byte[] d) { data = Arrays.copyOf(d, d.length); }

    @Override
    public Buffer marshall() {
        ZDLEEncoder encoder;
        ByteBuffer buff = ByteBuffer.allocate(data.length * 2 + 64);
        CRC crc = new CRC(CRC.Type.CRC16);
        encoder = new ZDLEEncoder(data);
        crc.update(data);
        buff.put(encoder.zdle(), 0, encoder.zdleLen());
        buff.put(ZModemCharacter.ZDLE.value());
        crc.update(type.value());
        buff.put(type.value());
        crc.finalized();
        encoder = new ZDLEEncoder(crc.getBytes());
        buff.put(encoder.zdle(), 0, encoder.zdleLen());
        buff.flip();
        return buff;
    }

    @Override
    public String toString() { return type + ":" + data.length + " bytes"; }
}
