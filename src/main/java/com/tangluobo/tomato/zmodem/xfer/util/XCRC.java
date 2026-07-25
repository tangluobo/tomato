package com.tangluobo.tomato.zmodem.xfer.util;

public interface XCRC {
    int getCRCLength();
    long calcCRC(byte[] block);
}
