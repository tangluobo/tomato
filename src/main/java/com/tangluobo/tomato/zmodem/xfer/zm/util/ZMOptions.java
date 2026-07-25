package com.tangluobo.tomato.zmodem.xfer.zm.util;

public enum ZMOptions {
    CANFDX(0x01),
    CANOVIO(0x02),
    CANBRK(0x04),
    CANCRY(0x08),
    CANLZW(0x10),
    CANFC32(0x20),
    ESCCTL(0x40),
    ESC8(0x80),
    ZCBIN(0x01);

    private byte value;

    private ZMOptions(char b) { value = (byte) b; }
    private ZMOptions(int b) { value = (byte) b; }
    private ZMOptions(byte b) { value = b; }

    public byte value() { return value; }

    public static byte with(ZMOptions... oo) {
        byte r = 0;
        for (ZMOptions o : oo) r = (byte) (r | o.value());
        return r;
    }

    public static ZMOptions forbyte(byte b) {
        for (ZMOptions zb : values()) {
            if (zb.value() == b) return zb;
        }
        return null;
    }
}
