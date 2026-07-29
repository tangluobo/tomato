package com.tangluobo.tomato.ssh.zmodem.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface FileAdapter {
    String getName();
    InputStream getInputStream() throws IOException;
    OutputStream getOutputStream() throws IOException;
    OutputStream getOutputStream(boolean append) throws IOException;
    FileAdapter getChild(String name);
    long length();
    boolean isDirectory();
    boolean exists();
}
