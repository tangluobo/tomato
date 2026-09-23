package com.tangluobo.tomato.module.connect.handler;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small, dependency-free RFB client used by Tomato's embedded VNC view.
 *
 * <p>The client intentionally negotiates a 32-bit true-colour pixel format and advertises only
 * encodings it implements. This keeps the protocol state deterministic and lets every compliant
 * VNC server fall back to the mandatory raw encoding.</p>
 */
final class VncClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private static final long MAX_RECTANGLE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_FRAMEBUFFER_PIXELS = 64L * 1024 * 1024;
    private static final int SECURITY_NONE = 1;
    private static final int SECURITY_VNC_AUTH = 2;
    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_HEXTILE = 5;
    private static final int ENCODING_DESKTOP_SIZE = -223;

    interface Listener {
        default String onPasswordRequired() { return null; }
        default void onConnected(String desktopName, int width, int height) {}
        default void onFrameUpdate(int framebufferWidth, int framebufferHeight,
                                   List<RawRectangle> rectangles) {}
        default void onClipboardText(String text) {}
        default void onBell() {}
        default void onDisconnected() {}
        default void onError(String message, Throwable error) {}
    }

    record RawRectangle(int x, int y, int width, int height, byte[] bgraPixels) {}

    private final String host;
    private final int port;
    private final String password;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Socket socket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private volatile boolean connected;
    private volatile int framebufferWidth;
    private volatile int framebufferHeight;

    VncClient(String host, int port, String password, Listener listener) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("VNC 主机不能为空");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("VNC 端口无效: " + port);
        this.host = host.trim();
        this.port = port;
        this.password = password == null ? "" : password;
        this.listener = listener == null ? new Listener() {} : listener;
    }

    /** Connects, performs the RFB handshake, and reads messages until disconnected. */
    void run() {
        try {
            connect();
            readMessages();
        } catch (Exception error) {
            if (!closed.get()) {
                listener.onError(readableMessage(error), error);
            }
        } finally {
            close();
            listener.onDisconnected();
        }
    }

    private void connect() throws IOException, GeneralSecurityException {
        Socket created = new Socket();
        socket = created;
        created.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        created.setTcpNoDelay(true);
        created.setKeepAlive(true);
        input = new DataInputStream(new BufferedInputStream(created.getInputStream(), 64 * 1024));
        output = new DataOutputStream(new BufferedOutputStream(created.getOutputStream(), 64 * 1024));

        int protocolMinor = negotiateProtocolVersion();
        negotiateSecurity(protocolMinor);
        sendClientInit();
        readServerInit();
        sendPixelFormat();
        sendEncodings();
        connected = true;
        requestFramebufferUpdate(false);
    }

    private int negotiateProtocolVersion() throws IOException {
        byte[] banner = readBytes(12);
        String version = new String(banner, StandardCharsets.US_ASCII);
        if (!version.matches("RFB 003\\.\\d{3}\\n")) {
            throw new IOException("服务器返回了无效的 RFB 版本: " + version.strip());
        }
        int serverMinor = Integer.parseInt(version.substring(8, 11));
        int selectedMinor = serverMinor >= 8 ? 8 : serverMinor >= 7 ? 7 : 3;
        writeAscii(String.format("RFB 003.%03d\n", selectedMinor));
        return selectedMinor;
    }

    private void negotiateSecurity(int protocolMinor) throws IOException, GeneralSecurityException {
        if (protocolMinor == 3) {
            int securityType = input.readInt();
            if (securityType == 0) throw new IOException(readFailureReason());
            if (securityType == SECURITY_VNC_AUTH) {
                performVncAuthentication(requirePassword());
                readSecurityResult(protocolMinor);
            } else if (securityType != SECURITY_NONE) {
                throw new IOException("VNC 服务器要求不支持的认证类型: " + securityType);
            }
            return;
        }

        int count = input.readUnsignedByte();
        if (count == 0) throw new IOException(readFailureReason());
        byte[] offered = readBytes(count);
        int selected = chooseSecurityType(offered, password);
        if (selected == 0) {
            throw new IOException("VNC 服务器未提供支持的认证方式（仅支持 None 和 VNC Password）");
        }
        String authenticationPassword = selected == SECURITY_VNC_AUTH ? requirePassword() : null;
        writeByte(selected);
        if (selected == SECURITY_VNC_AUTH) performVncAuthentication(authenticationPassword);
        readSecurityResult(protocolMinor);
    }

    private String requirePassword() throws IOException {
        if (password != null && !password.isEmpty()) return password;
        String promptedPassword = listener.onPasswordRequired();
        if (promptedPassword == null || promptedPassword.isEmpty()) {
            throw new IOException("VNC 服务器需要密码");
        }
        return promptedPassword;
    }

    static int chooseSecurityType(byte[] offered, String password) {
        boolean none = false;
        boolean vnc = false;
        for (byte type : offered) {
            none |= Byte.toUnsignedInt(type) == SECURITY_NONE;
            vnc |= Byte.toUnsignedInt(type) == SECURITY_VNC_AUTH;
        }
        if (vnc && password != null && !password.isEmpty()) return SECURITY_VNC_AUTH;
        if (none) return SECURITY_NONE;
        return vnc ? SECURITY_VNC_AUTH : 0;
    }

    private void performVncAuthentication(String authenticationPassword)
            throws IOException, GeneralSecurityException {
        byte[] challenge = readBytes(16);
        writeBytes(encryptChallenge(challenge, authenticationPassword));
    }

    static byte[] encryptChallenge(byte[] challenge, String password)
            throws GeneralSecurityException {
        if (challenge == null || challenge.length != 16) {
            throw new IllegalArgumentException("VNC challenge must contain 16 bytes");
        }
        byte[] passwordBytes = (password == null ? "" : password)
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] key = new byte[8];
        for (int i = 0; i < Math.min(key.length, passwordBytes.length); i++) {
            key[i] = reverseBits(passwordBytes[i]);
        }
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
        return cipher.doFinal(challenge);
    }

    private static byte reverseBits(byte value) {
        int current = Byte.toUnsignedInt(value);
        int reversed = Integer.reverse(current) >>> 24;
        return (byte) reversed;
    }

    private void readSecurityResult(int protocolMinor) throws IOException {
        int result = input.readInt();
        if (result == 0) return;
        String reason = protocolMinor >= 8 ? readFailureReason() : "认证被服务器拒绝";
        throw new IOException(reason.isBlank() ? "VNC 认证失败" : reason);
    }

    private String readFailureReason() throws IOException {
        long length = Integer.toUnsignedLong(input.readInt());
        if (length > MAX_TEXT_BYTES) throw new IOException("VNC 错误消息过长: " + length);
        return new String(readBytes((int) length), StandardCharsets.UTF_8);
    }

    private void sendClientInit() throws IOException {
        writeByte(1); // Share the desktop with other clients.
    }

    private void readServerInit() throws IOException {
        framebufferWidth = input.readUnsignedShort();
        framebufferHeight = input.readUnsignedShort();
        validateFramebufferSize(framebufferWidth, framebufferHeight);
        readBytes(16); // Server pixel format; replaced by sendPixelFormat().
        long nameLength = Integer.toUnsignedLong(input.readInt());
        if (nameLength > MAX_TEXT_BYTES) throw new IOException("VNC 桌面名称过长: " + nameLength);
        String name = new String(readBytes((int) nameLength), StandardCharsets.UTF_8);
        listener.onConnected(name, framebufferWidth, framebufferHeight);
    }

    private void sendPixelFormat() throws IOException {
        synchronized (writeLock) {
            ensureWritable();
            output.writeByte(0);
            output.write(new byte[3]);
            output.writeByte(32); // bits per pixel
            output.writeByte(24); // depth
            output.writeByte(0);  // little endian
            output.writeByte(1);  // true colour
            output.writeShort(255);
            output.writeShort(255);
            output.writeShort(255);
            output.writeByte(16); // red shift
            output.writeByte(8);  // green shift
            output.writeByte(0);  // blue shift
            output.write(new byte[3]);
            output.flush();
        }
    }

    private void sendEncodings() throws IOException {
        synchronized (writeLock) {
            ensureWritable();
            output.writeByte(2);
            output.writeByte(0);
            output.writeShort(3);
            output.writeInt(ENCODING_HEXTILE);
            output.writeInt(ENCODING_RAW);
            output.writeInt(ENCODING_DESKTOP_SIZE);
            output.flush();
        }
    }

    private void readMessages() throws IOException {
        while (!closed.get()) {
            int messageType;
            try {
                messageType = input.readUnsignedByte();
            } catch (EOFException eof) {
                return;
            }
            switch (messageType) {
                case 0 -> readFramebufferUpdate();
                case 1 -> readColourMapEntries();
                case 2 -> listener.onBell();
                case 3 -> readServerCutText();
                default -> throw new IOException("不支持的 VNC 服务器消息: " + messageType);
            }
        }
    }

    private void readFramebufferUpdate() throws IOException {
        input.readUnsignedByte();
        int rectangleCount = input.readUnsignedShort();
        List<RawRectangle> rectangles = new ArrayList<>(Math.min(rectangleCount, 64));
        for (int i = 0; i < rectangleCount; i++) {
            int x = input.readUnsignedShort();
            int y = input.readUnsignedShort();
            int width = input.readUnsignedShort();
            int height = input.readUnsignedShort();
            int encoding = input.readInt();
            if (encoding == ENCODING_DESKTOP_SIZE) {
                validateFramebufferSize(width, height);
                framebufferWidth = width;
                framebufferHeight = height;
            } else if (encoding == ENCODING_RAW) {
                rectangles.add(readRawRectangle(x, y, width, height));
            } else if (encoding == ENCODING_HEXTILE) {
                rectangles.add(readHextileRectangle(x, y, width, height));
            } else {
                throw new IOException("服务器发送了未协商的 VNC 编码: " + encoding);
            }
        }
        listener.onFrameUpdate(framebufferWidth, framebufferHeight, List.copyOf(rectangles));
        requestFramebufferUpdate(true);
    }

    private RawRectangle readRawRectangle(int x, int y, int width, int height) throws IOException {
        long length = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        if (length > MAX_RECTANGLE_BYTES || length > Integer.MAX_VALUE) {
            throw new IOException("VNC 画面矩形过大: " + width + "x" + height);
        }
        byte[] pixels = readBytes((int) length);
        // Requested pixel format is B, G, R, unused. JavaFX expects B, G, R, alpha.
        for (int i = 3; i < pixels.length; i += 4) pixels[i] = (byte) 0xff;
        return new RawRectangle(x, y, width, height, pixels);
    }

    private RawRectangle readHextileRectangle(int x, int y, int width, int height)
            throws IOException {
        long length = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        if (length > MAX_RECTANGLE_BYTES || length > Integer.MAX_VALUE) {
            throw new IOException("VNC Hextile 矩形过大: " + width + "x" + height);
        }
        byte[] pixels = new byte[(int) length];
        byte[] background = new byte[]{0, 0, 0, (byte) 0xff};
        byte[] foreground = new byte[]{0, 0, 0, (byte) 0xff};

        for (int tileY = 0; tileY < height; tileY += 16) {
            int tileHeight = Math.min(16, height - tileY);
            for (int tileX = 0; tileX < width; tileX += 16) {
                int tileWidth = Math.min(16, width - tileX);
                int subencoding = input.readUnsignedByte();
                if ((subencoding & 1) != 0) {
                    readRawTile(pixels, width, tileX, tileY, tileWidth, tileHeight);
                    continue;
                }
                if ((subencoding & 2) != 0) background = readPixel();
                fillRectangle(pixels, width, tileX, tileY, tileWidth, tileHeight, background);
                if ((subencoding & 4) != 0) foreground = readPixel();
                if ((subencoding & 8) == 0) continue;

                int subrectangles = input.readUnsignedByte();
                boolean coloured = (subencoding & 16) != 0;
                for (int i = 0; i < subrectangles; i++) {
                    byte[] colour = coloured ? readPixel() : foreground;
                    int xy = input.readUnsignedByte();
                    int wh = input.readUnsignedByte();
                    int subX = xy >>> 4;
                    int subY = xy & 0x0f;
                    int subWidth = (wh >>> 4) + 1;
                    int subHeight = (wh & 0x0f) + 1;
                    if (subX + subWidth > tileWidth || subY + subHeight > tileHeight) {
                        throw new IOException("VNC Hextile 子矩形越界");
                    }
                    fillRectangle(pixels, width, tileX + subX, tileY + subY,
                            subWidth, subHeight, colour);
                }
            }
        }
        return new RawRectangle(x, y, width, height, pixels);
    }

    private void readRawTile(byte[] destination, int rectangleWidth, int tileX, int tileY,
                             int tileWidth, int tileHeight) throws IOException {
        byte[] row = new byte[tileWidth * 4];
        for (int rowIndex = 0; rowIndex < tileHeight; rowIndex++) {
            input.readFully(row);
            for (int alpha = 3; alpha < row.length; alpha += 4) row[alpha] = (byte) 0xff;
            int offset = ((tileY + rowIndex) * rectangleWidth + tileX) * 4;
            System.arraycopy(row, 0, destination, offset, row.length);
        }
    }

    private byte[] readPixel() throws IOException {
        byte[] pixel = readBytes(4);
        pixel[3] = (byte) 0xff;
        return pixel;
    }

    private static void fillRectangle(byte[] destination, int rectangleWidth, int x, int y,
                                      int width, int height, byte[] pixel) {
        for (int row = y; row < y + height; row++) {
            int offset = (row * rectangleWidth + x) * 4;
            for (int column = 0; column < width; column++) {
                System.arraycopy(pixel, 0, destination, offset + column * 4, 4);
            }
        }
    }

    private void readColourMapEntries() throws IOException {
        input.readUnsignedByte();
        input.readUnsignedShort();
        int colourCount = input.readUnsignedShort();
        skipFully((long) colourCount * 6L);
    }

    private void readServerCutText() throws IOException {
        skipFully(3);
        long length = Integer.toUnsignedLong(input.readInt());
        if (length > MAX_TEXT_BYTES) throw new IOException("VNC 剪贴板内容过长: " + length);
        byte[] bytes = readBytes((int) length);
        listener.onClipboardText(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    void requestFramebufferUpdate(boolean incremental) throws IOException {
        synchronized (writeLock) {
            ensureWritable();
            output.writeByte(3);
            output.writeByte(incremental ? 1 : 0);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(framebufferWidth);
            output.writeShort(framebufferHeight);
            output.flush();
        }
    }

    void sendKeyEvent(boolean down, int keySym) throws IOException {
        synchronized (writeLock) {
            ensureWritable();
            output.writeByte(4);
            output.writeByte(down ? 1 : 0);
            output.writeShort(0);
            output.writeInt(keySym);
            output.flush();
        }
    }

    void sendPointerEvent(int buttonMask, int x, int y) throws IOException {
        synchronized (writeLock) {
            ensureWritable();
            output.writeByte(5);
            output.writeByte(buttonMask & 0xff);
            output.writeShort(clamp(x, 0, Math.max(0, framebufferWidth - 1)));
            output.writeShort(clamp(y, 0, Math.max(0, framebufferHeight - 1)));
            output.flush();
        }
    }

    void sendClipboardText(String text) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length > MAX_TEXT_BYTES) throw new IOException("剪贴板内容过长");
        synchronized (writeLock) {
            ensureWritable();
            output.writeByte(6);
            output.write(new byte[3]);
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
        }
    }

    boolean isConnected() {
        return connected && !closed.get();
    }

    private void ensureWritable() throws IOException {
        if (closed.get() || output == null) throw new IOException("VNC 连接已关闭");
    }

    private void writeAscii(String value) throws IOException {
        writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeByte(int value) throws IOException {
        synchronized (writeLock) {
            ensureWritable();
            output.writeByte(value);
            output.flush();
        }
    }

    private void writeBytes(byte[] bytes) throws IOException {
        synchronized (writeLock) {
            ensureWritable();
            output.write(bytes);
            output.flush();
        }
    }

    private byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private void skipFully(long length) throws IOException {
        while (length > 0) {
            int skipped = input.skipBytes((int) Math.min(length, Integer.MAX_VALUE));
            if (skipped <= 0) {
                if (input.read() < 0) throw new EOFException();
                skipped = 1;
            }
            length -= skipped;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void validateFramebufferSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_FRAMEBUFFER_PIXELS) {
            throw new IOException("VNC 桌面尺寸无效或过大: " + width + "x" + height);
        }
    }

    private static String readableMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        connected = false;
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        input = null;
        output = null;
    }
}
