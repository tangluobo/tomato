package com.tangluobo.tomato.ssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * SSH会话管理，使用JSch库，支持多密钥认证
 */
public class SSHSession {

    private JSch jsch;
    private Session session;
    private ChannelShell channel;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean connected = false;

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
        jsch = new JSch();

        // 密钥认证：添加所有密钥
        if (privateKeyPaths != null && !privateKeyPaths.isEmpty()) {
            for (String keyPath : privateKeyPaths) {
                if (keyPath != null && !keyPath.isEmpty()) {
                    if (password != null && !password.isEmpty()) {
                        jsch.addIdentity(keyPath, password);
                    } else {
                        jsch.addIdentity(keyPath);
                    }
                }
            }
        }

        session = jsch.getSession(username, host, port);

        // 无密钥时使用密码认证
        if (privateKeyPaths == null || privateKeyPaths.isEmpty()) {
            session.setPassword(password);
        }

        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(30000);

        channel = (ChannelShell) session.openChannel("shell");
        channel.setPtyType("xterm-256color", 80, 24, 640, 480);
        inputStream = channel.getInputStream();
        outputStream = channel.getOutputStream();
        channel.connect(30000);
        connected = true;
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
        connected = false;
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }
        inputStream = null;
        outputStream = null;
    }
}
