package com.tangluobo.tomato.ssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpATTRS;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * SFTP客户端，基于JSch的ChannelSftp实现文件浏览、上传、下载
 */
public class SFTPClient {

    private ChannelSftp channel;
    private boolean connected = false;

    /**
     * 文件条目
     */
    public static class FileEntry {
        private String name;
        private String path;
        private boolean directory;
        private long size;
        private long modifyTime;

        public FileEntry(String name, String path, boolean directory, long size, long modifyTime) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.modifyTime = modifyTime;
        }

        public String getName() { return name; }
        public String getPath() { return path; }
        public boolean isDirectory() { return directory; }
        public long getSize() { return size; }
        public long getModifyTime() { return modifyTime; }
    }

    /**
     * 连接SFTP通道（复用已有的SSH Session）
     */
    public void connect(Session sshSession) throws Exception {
        if (connected && channel != null && channel.isConnected()) return;
        channel = (ChannelSftp) sshSession.openChannel("sftp");
        channel.connect(10000);
        connected = true;
    }

    public boolean isConnected() {
        return connected && channel != null && channel.isConnected();
    }

    /**
     * 列出目录下的文件
     */
    public List<FileEntry> listFiles(String path) throws SftpException {
        Vector<ChannelSftp.LsEntry> entries = channel.ls(path);
        List<FileEntry> result = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) continue;
            SftpATTRS attrs = entry.getAttrs();
            String fullPath = path.endsWith("/") ? path + name : path + "/" + name;
            result.add(new FileEntry(name, fullPath, attrs.isDir(), attrs.getSize(), attrs.getMTime() * 1000L));
        }
        // 目录排前面
        result.sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return result;
    }

    /**
     * 获取当前工作目录
     */
    public String pwd() throws SftpException {
        return channel.pwd();
    }

    /**
     * 切换目录
     */
    public void cd(String path) throws SftpException {
        channel.cd(path);
    }

    /**
     * 下载文件
     */
    public void download(String remotePath, String localPath) throws SftpException {
        channel.get(remotePath, localPath);
    }

    /**
     * 上传文件
     */
    public void upload(String localPath, String remotePath) throws SftpException {
        channel.put(localPath, remotePath);
    }

    /**
     * 上传文件（从InputStream）
     */
    public void upload(InputStream src, String remotePath) throws SftpException {
        channel.put(src, remotePath);
    }

    /**
     * 创建目录
     */
    public void mkdir(String path) throws SftpException {
        channel.mkdir(path);
    }

    /**
     * 删除文件
     */
    public void rm(String path) throws SftpException {
        channel.rm(path);
    }

    /**
     * 删除目录
     */
    public void rmdir(String path) throws SftpException {
        channel.rmdir(path);
    }

    /**
     * 获取文件属性
     */
    public boolean exists(String path) {
        try {
            channel.stat(path);
            return true;
        } catch (SftpException e) {
            return false;
        }
    }

    /**
     * 断开SFTP连接
     */
    public void disconnect() {
        connected = false;
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
    }
}
