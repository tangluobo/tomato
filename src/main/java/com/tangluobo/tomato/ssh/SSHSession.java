package com.tangluobo.tomato.ssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSH会话管理，使用JSch库，支持多密钥认证
 */
public class SSHSession {

    private volatile JSch jsch;
    private volatile Session session;
    private volatile ChannelShell channel;
    private volatile InputStream inputStream;
    private volatile OutputStream outputStream;
    private volatile boolean connected = false;
    private final AtomicLong lifecycle = new AtomicLong();

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final List<String> privateKeyPaths;

    public SSHSession(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPaths = null;
    }

    public SSHSession(String host, int port, String username, String password, String privateKeyPath) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPaths = privateKeyPath != null ? List.of(privateKeyPath) : null;
    }

    public SSHSession(String host, int port, String username, String password, List<String> privateKeyPaths) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPaths = privateKeyPaths;
    }

    /**
     * 连接SSH服务器
     */
    public void connect() throws JSchException, IOException {
        ChannelShell previousChannel;
        Session previousSession;
        long attempt;
        synchronized (this) {
            attempt = lifecycle.incrementAndGet();
            connected = false;
            previousChannel = channel;
            previousSession = session;
            channel = null;
            session = null;
            inputStream = null;
            outputStream = null;
        }
        if (previousChannel != null) previousChannel.disconnect();
        if (previousSession != null) previousSession.disconnect();

        JSch newJsch = new JSch();
        jsch = newJsch;

        // 密钥认证：添加所有密钥
        if (privateKeyPaths != null && !privateKeyPaths.isEmpty()) {
            for (String keyPath : privateKeyPaths) {
                if (keyPath != null && !keyPath.isEmpty()) {
                    if (password != null && !password.isEmpty()) {
                        newJsch.addIdentity(keyPath, password);
                    } else {
                        newJsch.addIdentity(keyPath);
                    }
                }
            }
        }

        Session newSession = newJsch.getSession(username, host, port);
        synchronized (this) {
            ensureCurrent(attempt);
            session = newSession;
        }

        // 无密钥时使用密码认证
        if (privateKeyPaths == null || privateKeyPaths.isEmpty()) {
            newSession.setPassword(password);
        }

        newSession.setConfig("StrictHostKeyChecking", "no");
        newSession.setServerAliveInterval(10000);
        newSession.setServerAliveCountMax(3);
        ChannelShell newChannel = null;
        try {
            newSession.connect(30000);
            ensureCurrent(attempt);

            newChannel = (ChannelShell) newSession.openChannel("shell");
            newChannel.setPtyType("xterm-256color", 80, 24, 640, 480);
            InputStream newInput = newChannel.getInputStream();
            OutputStream newOutput = newChannel.getOutputStream();
            synchronized (this) {
                ensureCurrent(attempt);
                channel = newChannel;
            }
            newChannel.connect(30000);
            synchronized (this) {
                ensureCurrent(attempt);
                inputStream = newInput;
                outputStream = newOutput;
                connected = true;
            }
        } catch (JSchException | IOException e) {
            if (newChannel != null) newChannel.disconnect();
            newSession.disconnect();
            synchronized (this) {
                if (session == newSession) session = null;
                if (channel == newChannel) channel = null;
                if (lifecycle.get() == attempt) {
                    connected = false;
                    inputStream = null;
                    outputStream = null;
                }
            }
            throw e;
        }
    }

    /**
     * 调整终端大小
     */
    public void resize(int cols, int rows, int width, int height) {
        if (channel != null) {
            channel.setPtySize(cols, rows, width, height);
        }
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * 获取JSch Session对象（用于SFTP等子通道）
     */
    public Session getJschSession() {
        return session;
    }

    public boolean isConnected() {
        return connected && channel != null && channel.isConnected();
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        ChannelShell oldChannel;
        Session oldSession;
        synchronized (this) {
            lifecycle.incrementAndGet();
            connected = false;
            oldChannel = channel;
            oldSession = session;
            channel = null;
            session = null;
            inputStream = null;
            outputStream = null;
        }
        if (oldChannel != null) oldChannel.disconnect();
        if (oldSession != null) oldSession.disconnect();
    }

    private void ensureCurrent(long attempt) throws JSchException {
        if (lifecycle.get() != attempt) {
            throw new JSchException("SSH connection was cancelled");
        }
    }
}
