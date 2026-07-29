package com.tangluobo.tomato.ssh.zmodem.xfer.util;

public interface XCRC {
    int getCRCLength();
    long calcCRC(byte[] block);
}
