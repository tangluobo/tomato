package com.tangluobo.tomato.ssh.zmodem.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class CustomFile implements FileAdapter {
    private File file = null;

    public CustomFile(File file) {
        this.file = file;
    }

    @Override
    public String getName() {
        return file.getName();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new FileInputStream(file);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return getOutputStream(false);
    }

    @Override
    public OutputStream getOutputStream(boolean append) throws IOException {
        return new BufferedOutputStream(new FileOutputStream(file, append));
    }

    @Override
    public FileAdapter getChild(String name) {
        if (name.equals(file.getName())) {
            return this;
        } else if (file.isDirectory()) {
            return new CustomFile(new File(file.getAbsolutePath(), name));
        }
        return null;
    }

    @Override
    public long length() {
        return file.length();
    }

    @Override
    public boolean isDirectory() {
        return file.isDirectory();
    }

    @Override
    public boolean exists() {
        return file.exists();
    }
}
