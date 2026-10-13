package com.tangluobo.tomato.module.connect.handler;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** One version-pinned scrcpy server owns both capture and input coordinate mapping. */
final class ScrcpyControlClient implements AutoCloseable {
    static final String VERSION = "3.3.4";
    private static final String RESOURCE = "/adb/scrcpy-server-v" + VERSION + ".jar";
    private static final String SHA256 = "8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private final String executable;
    private final String endpoint;
    private final AdbClient.CommandRunner runner;
    private final String remotePath;
    private final int scid;
    private volatile boolean closed;
    private volatile String serverError;
    private volatile String lastLog = "";
    private int forwardedPort;
    private Process server;
    private Socket socket;
    private OutputStream output;
    private Socket videoSocket;
    private DataInputStream videoInput;
    private AdbClient.ScreenSize videoSize;
    private final boolean videoEnabled;
    private final int maxSize;
    private final int bitRate;

    private ScrcpyControlClient(String executable, String endpoint, AdbClient.CommandRunner runner,
                               boolean videoEnabled, int maxSize, int bitRate) {
        this.executable = executable;
        this.endpoint = endpoint;
        this.runner = runner;
        this.videoEnabled = videoEnabled;
        this.maxSize = maxSize;
        this.bitRate = bitRate;
        scid = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        remotePath = "/data/local/tmp/tomato-control-" + Integer.toHexString(scid) + ".jar";
    }

    static ScrcpyControlClient open(String executable, String endpoint, AdbClient.CommandRunner runner) throws IOException {
        return open(executable, endpoint, runner, false, 1920, 8_000_000);
    }

    static ScrcpyControlClient open(String executable, String endpoint, AdbClient.CommandRunner runner,
                                    boolean video, int maxSize, int bitRate) throws IOException {
        ScrcpyControlClient client = new ScrcpyControlClient(executable, endpoint, runner, video, maxSize, bitRate);
        try {
            client.start();
            return client;
        } catch (IOException | RuntimeException error) {
            client.close();
            throw new IOException("scrcpy 直接控制不可用：" + error.getMessage(), error);
        }
    }

    static byte[] bundledServer() throws IOException {
        try (InputStream resource = ScrcpyControlClient.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) throw new IOException("缺少内置 scrcpy 控制服务");
            byte[] bytes = resource.readAllBytes();
            if (!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
                throw new IOException("内置 scrcpy 控制服务校验失败");
            }
            return bytes;
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private void start() throws IOException {
        Path local = Files.createTempFile("tomato-scrcpy-", ".jar");
        try {
            Files.write(local, bundledServer());
            run("push", local.toString(), remotePath);
        } finally {
            Files.deleteIfExists(local);
        }
        String name = String.format("scrcpy_%08x", scid);
        String port = run("forward", "tcp:0", "localabstract:" + name).stdoutText().trim();
        try {
            forwardedPort = Integer.parseInt(port);
            if (forwardedPort <= 0 || forwardedPort > 65535) throw new NumberFormatException(port);
        } catch (NumberFormatException error) {
            throw new IOException("ADB 未返回有效的控制端口：" + port, error);
        }
        server = new ProcessBuilder(command("shell", "-T", "CLASSPATH=" + remotePath,
                "app_process", "/", "com.genymobile.scrcpy.Server", VERSION,
                "scid=" + Integer.toHexString(scid), "tunnel_forward=true", "video=" + videoEnabled,
                "max_size=" + maxSize, "video_bit_rate=" + bitRate, "max_fps=60",
                "video_codec_options=i-frame-interval=1",
                "send_codec_meta=true", "send_frame_meta=true",
                "audio=false", "control=true", "send_device_meta=false", "send_dummy_byte=true",
                "clipboard_autosync=false", "power_on=false", "cleanup=false", "log_level=warn"))
                .redirectErrorStream(true).start();
        Thread.ofVirtual().name("ADB-scrcpy-log").start(this::readServerLog);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && server.isAlive() && serverError == null) {
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress("127.0.0.1", forwardedPort), 250);
                candidate.setSoTimeout(250);
                candidate.setTcpNoDelay(true);
                if (candidate.getInputStream().read() != 0) throw new IOException("控制服务握手失败");
                candidate.setSoTimeout(0);
                if (videoEnabled) {
                    videoSocket = candidate;
                    videoSocket.setSoTimeout(10_000);
                    // The first socket is video; the second is control and has no dummy byte.
                    socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", forwardedPort), 1000);
                    socket.setTcpNoDelay(true);
                    videoInput = new DataInputStream(new BufferedInputStream(videoSocket.getInputStream(), 64 * 1024));
                    videoSize = readVideoHeader(videoInput);
                    videoSocket.setSoTimeout(0);
                } else {
                    socket = candidate;
                }
                output = socket.getOutputStream();
                Thread.ofVirtual().name("ADB-scrcpy-recv").start(() -> {
                    try {
                        socket.getInputStream().transferTo(OutputStream.nullOutputStream());
                    } catch (IOException ignored) {
                    } finally {
                        if (!closed && serverError == null) serverError = "scrcpy 控制连接已断开";
                    }
                });
                return;
            } catch (IOException error) {
                candidate.close();
                // Once the first socket was accepted, retrying would bind the wrong protocol.
                if (videoSocket != null) throw error;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("启动控制服务被中断", interrupted);
                }
            }
        }
        throw new IOException(serverError != null ? serverError : "控制服务启动超时 " + lastLog);
    }

    private void readServerLog() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastLog = line.length() > 500 ? line.substring(0, 500) : line;
                if (line.contains("ERROR") || line.contains("Exception") || line.contains("INJECT_EVENTS")) {
                    serverError = lastLog;
                }
            }
        } catch (IOException ignored) {
        }
    }

    boolean isAlive() {
        return !closed && serverError == null && server != null && server.isAlive();
    }

    AdbClient.ScreenSize videoSize() {
        return videoSize;
    }

    DataInputStream videoInput() throws IOException {
        if (videoInput == null) throw new IOException("scrcpy 视频流未启动");
        return videoInput;
    }

    static AdbClient.ScreenSize readVideoHeader(DataInputStream input) throws IOException {
        int codec = input.readInt();
        if (codec != 0x68323634) throw new IOException("scrcpy 未返回 H.264 视频，编码器状态：" + codec);
        int width = input.readInt();
        int height = input.readInt();
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
            throw new IOException("scrcpy 视频尺寸无效：" + width + "×" + height);
        }
        return new AdbClient.ScreenSize(width, height);
    }

    String diagnostics() {
        return serverError != null ? serverError : lastLog;
    }

    void closeVideo() {
        if (videoSocket != null) {
            try {
                videoSocket.close();
            } catch (IOException ignored) { }
        }
    }

    synchronized void touch(int action, long pointerId, int x, int y, int width, int height) throws IOException {
        send(touchMessage(action, pointerId, x, y, width, height));
    }

    static byte[] touchMessage(int action, long pointerId, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw new IllegalArgumentException("无效的触控画面尺寸");
        }
        return ByteBuffer.allocate(32).put((byte) 2).put((byte) action).putLong(pointerId)
                .putInt(Math.max(0, Math.min(width - 1, x))).putInt(Math.max(0, Math.min(height - 1, y)))
                .putShort((short) width).putShort((short) height)
                .putShort((short) (action == 1 ? 0 : 0xffff)).putInt(0).putInt(0).array();
    }

    synchronized void keyEvent(int keyCode) throws IOException {
        send(keyMessage(0, keyCode));
        send(keyMessage(1, keyCode));
    }

    static byte[] keyMessage(int action, int keyCode) {
        return ByteBuffer.allocate(14).put((byte) 0).put((byte) action).putInt(keyCode).putInt(0).putInt(0).array();
    }

    synchronized void text(String value) throws IOException {
        // Android's virtual keyboard only maps supported characters; retain the UI's ASCII contract.
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < bytes.length; offset += 300) {
            int length = Math.min(300, bytes.length - offset);
            send(ByteBuffer.allocate(5 + length).put((byte) 1).putInt(length).put(bytes, offset, length).array());
        }
    }

    synchronized void scroll(int x, int y, int width, int height, double horizontal, double vertical) throws IOException {
        send(scrollMessage(x, y, width, height, horizontal, vertical));
    }

    static byte[] scrollMessage(int x, int y, int width, int height, double horizontal, double vertical) {
        return ByteBuffer.allocate(21).put((byte) 3).putInt(x).putInt(y).putShort((short) width).putShort((short) height)
                .putShort(scrollValue(horizontal)).putShort(scrollValue(vertical)).putInt(0).array();
    }

    private static short scrollValue(double value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value / 16 * 32768)));
    }

    private void send(byte[] bytes) throws IOException {
        if (!isAlive()) throw new IOException(serverError == null ? "scrcpy 控制服务已停止，请重新连接" : serverError);
        try {
            output.write(bytes);
            output.flush();
        } catch (IOException error) {
            serverError = "scrcpy 控制连接写入失败，请重新连接";
            throw new IOException(serverError, error);
        }
    }

    private List<String> command(String... args) {
        List<String> command = new ArrayList<>(List.of(executable, "-s", endpoint));
        command.addAll(List.of(args));
        return command;
    }

    private AdbClient.CommandResult run(String... args) throws IOException {
        AdbClient.CommandResult result = runner.run(command(args), COMMAND_TIMEOUT);
        if (result.exitCode() != 0) throw new IOException(result.combinedText().trim());
        return result;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeVideo();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) { }
        }
        if (server != null) {
            server.destroy();
            Process process = server;
            Thread.ofVirtual().name("ADB-scrcpy-stop").start(() -> {
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            });
        }
        // Remove only this session's forward and uploaded server, never another scrcpy instance.
        try {
            if (forwardedPort > 0) run("forward", "--remove", "tcp:" + forwardedPort);
        } catch (IOException ignored) { }
        try {
            run("shell", "rm", "-f", remotePath);
        } catch (IOException ignored) { }
    }
}
