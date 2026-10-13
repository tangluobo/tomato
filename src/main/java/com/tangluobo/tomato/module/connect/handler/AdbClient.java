package com.tangluobo.tomato.module.connect.handler;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.ffmpeg.ffmpeg;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small command-line ADB adapter used by the embedded Android desktop view. */
final class AdbClient {
    static final int KEYCODE_HOME = 3;
    static final int KEYCODE_BACK = 4;
    static final int KEYCODE_POWER = 26;
    static final int KEYCODE_VOLUME_UP = 24;
    static final int KEYCODE_VOLUME_DOWN = 25;
    static final int KEYCODE_ENTER = 66;
    static final int KEYCODE_DEL = 67;
    static final int KEYCODE_TAB = 61;
    static final int KEYCODE_DPAD_UP = 19;
    static final int KEYCODE_DPAD_DOWN = 20;
    static final int KEYCODE_DPAD_LEFT = 21;
    static final int KEYCODE_DPAD_RIGHT = 22;
    static final int KEYCODE_APP_SWITCH = 187;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INPUT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_PNG_CHUNK_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PNG_FRAME_BYTES = 96 * 1024 * 1024;
    private static final int MAX_PNG_PREFIX_BYTES = 64 * 1024;
    private static final int MAX_VIDEO_EDGE = 1920;
    private static final int VIDEO_BIT_RATE = 8_000_000;
    private static final String PRIMARY_DISPLAY_ID = "0";
    private static final String SCREEN_STREAM_COMMAND =
            "while screencap -d " + PRIMARY_DISPLAY_ID + " -p 2>/dev/null; do :; done";
    private static final Pattern DISPLAY_SIZE_PATTERN = Pattern.compile("(?:Physical|Override) size:\\s*(\\d+)x(\\d+)");
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final String executable;
    private final String endpoint;
    private final CommandRunner commandRunner;
    private volatile ScreenSize displaySize;
    private volatile ControlChannel controlChannel;
    private volatile ScrcpyControlClient directControl;
    private volatile String controlDescription = "ADB命令控制";
    private volatile boolean sessionClosed;
    private volatile int videoMaxEdge = MAX_VIDEO_EDGE;
    private volatile int videoBitRate = VIDEO_BIT_RATE;
    private volatile boolean videoEnabled = true;

    void setVideoEnabled(boolean enabled) {
        videoEnabled = enabled;
    }

    void setVideoQuality(int maxEdge, int bitRate) {
        if (maxEdge < 0 || bitRate <= 0) throw new IllegalArgumentException("无效的视频质量参数");
        videoMaxEdge = maxEdge;
        videoBitRate = bitRate;
    }

    private ScreenSize videoSize() {
        return videoMaxEdge == 0 ? displaySize : scaledVideoSize(displaySize, videoMaxEdge);
    }

    AdbClient(String host, int port, String configuredExecutable) {
        this(host, port, configuredExecutable, new SystemCommandRunner());
    }

    AdbClient(String host, int port, String configuredExecutable, CommandRunner commandRunner) {
        this.executable = resolveExecutable(configuredExecutable);
        this.endpoint = endpoint(host, port);
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    String endpoint() {
        return endpoint;
    }

    String executable() {
        return executable;
    }

    ScreenSize displaySize() {
        return displaySize;
    }

    boolean hasDirectControl() {
        // Keep the gesture protocol stable on failure: report an error instead of replaying it.
        return directControl != null;
    }

    String controlDescription() {
        return controlDescription;
    }

    void touch(int action, long pointerId, int x, int y, int width, int height) throws IOException {
        requireDirectControl().touch(action, pointerId, x, y, width, height);
    }

    void scroll(int x, int y, int width, int height, double horizontal, double vertical) throws IOException {
        requireDirectControl().scroll(x, y, width, height, horizontal, vertical);
    }

    private ScrcpyControlClient requireDirectControl() throws IOException {
        ScrcpyControlClient control = directControl;
        if (control == null || sessionClosed) throw new IOException("直接控制不可用，请重新连接");
        return control;
    }

    void connect() throws IOException {
        CommandResult connectResult = run(CONNECT_TIMEOUT, "connect", endpoint);
        String connectMessage = connectResult.combinedText().toLowerCase(Locale.ROOT);
        if (connectResult.exitCode() != 0 || containsFailure(connectMessage)) {
            throw commandFailure("连接设备失败", connectResult);
        }

        CommandResult stateResult = run(CONNECT_TIMEOUT, "-s", endpoint, "get-state");
        String state = stateResult.stdoutText().trim();
        if (stateResult.exitCode() != 0 || !"device".equals(state)) {
            String detail = stateResult.combinedText().trim();
            if (detail.isEmpty()) detail = "设备状态为 " + (state.isEmpty() ? "unknown" : state);
            throw new IOException("ADB 设备尚未就绪或未授权：" + detail);
        }

        try {
            CommandResult sizeResult = run(INPUT_TIMEOUT, "-s", endpoint, "shell", "wm", "size");
            if (sizeResult.exitCode() == 0) displaySize = parseDisplaySize(sizeResult.stdoutText());
        } catch (IOException ignored) {
            // screenrecord can still select the device's native size if wm is unavailable.
        }

        if (commandRunner instanceof SystemCommandRunner) {
            if (videoEnabled) {
            try {
                ScrcpyControlClient control = ScrcpyControlClient.open(executable, endpoint, commandRunner,
                        true, videoMaxEdge, videoBitRate);
                synchronized (this) {
                    if (sessionClosed) {
                        control.close();
                        throw new IOException("ADB 会话已关闭");
                    }
                    directControl = control;
                    displaySize = control.videoSize();
                    controlDescription = "scrcpy直接控制";
                }
                return;
            } catch (IOException error) {
                if (sessionClosed) throw error;
                controlDescription = "ADB兼容控制（" + error.getMessage() + "）";
            }
            }
            try {
                ControlChannel channel = new ControlChannel(new ProcessBuilder(controlCommand()).start());
                synchronized (this) {
                    if (sessionClosed) {
                        channel.close();
                        throw new IOException("ADB 会话已关闭");
                    }
                    controlChannel = channel;
                }
            } catch (IOException ignored) {
                // Individual adb shell commands remain available as a compatibility fallback.
            }
        }
    }

    byte[] captureScreen() throws IOException {
        CommandResult result = run(CAPTURE_TIMEOUT, "-s", endpoint, "exec-out",
                "screencap", "-d", PRIMARY_DISPLAY_ID, "-p");
        if (result.exitCode() != 0) throw commandFailure("读取设备画面失败", result);
        byte[] png = extractPngPayload(result.stdout());
        if (png == null) {
            throw new IOException("ADB 未返回有效的屏幕截图：" + usefulMessage(result));
        }
        return png;
    }

    /**
     * Starts one long-lived adb process that emits concatenated PNG screenshots. Reusing the
     * device transport removes the process startup and handshake cost previously paid per frame.
     */
    ScreenStream openScreenStream() throws IOException {
        List<String> command = screenStreamCommand();
        try {
            return new ScreenStream(new ProcessBuilder(command).start());
        } catch (IOException error) {
            throw new IOException("无法启动 ADB 屏幕流，请检查 adb 程序和设备连接：" + error.getMessage(), error);
        }
    }

    /** Starts Android's hardware H.264 screen encoder and decodes its low-latency stream. */
    VideoStream openVideoStream() throws IOException {
        ScrcpyControlClient control = directControl;
        if (control == null || control.videoSize() == null) {
            throw new IOException("scrcpy 视频服务未启动，" + controlDescription);
        }
        return new VideoStream(control);
    }

    /** Compatibility implementation retained for command diagnostics, not used for live mirroring. */
    private VideoStream openLegacyVideoStream() throws IOException {
        ScreenSize target = videoSize();
        if (target == null) {
            throw new IOException("无法读取设备屏幕尺寸，不能启动 H.264 视频流");
        }
        try {
            List<Process> pipeline = ProcessBuilder.startPipeline(List.of(
                    new ProcessBuilder(videoStreamCommand()),
                    new ProcessBuilder(ffmpegCommand(target))));
            return new VideoStream(pipeline.get(0), pipeline.get(1), target);
        } catch (IOException | RuntimeException error) {
            throw new IOException("无法启动 ADB 视频流，请检查 adb 程序和设备连接：" + error.getMessage(), error);
        }
    }

    void tap(int x, int y) throws IOException {
        if (hasDirectControl() && displaySize != null) {
            touch(0, -2, x, y, displaySize.width(), displaySize.height());
            touch(1, -2, x, y, displaySize.width(), displaySize.height());
            return;
        }
        runInput("tap", Integer.toString(x), Integer.toString(y));
    }

    void swipe(int startX, int startY, int endX, int endY, int durationMillis) throws IOException {
        runInput("swipe", Integer.toString(startX), Integer.toString(startY),
                Integer.toString(endX), Integer.toString(endY),
                Integer.toString(Math.max(50, durationMillis)));
    }

    void keyEvent(int keyCode) throws IOException {
        if (hasDirectControl()) {
            requireDirectControl().keyEvent(keyCode);
            return;
        }
        runInput("keyevent", Integer.toString(keyCode));
    }

    void text(String value) throws IOException {
        if (value == null || value.isEmpty()) return;
        if (hasDirectControl()) {
            requireDirectControl().text(value.replaceAll("[^\\x20-\\x7e]", ""));
            return;
        }
        runInput("text", encodeInputText(value));
    }

    void disconnect() {
        closeSession();
        try {
            run(INPUT_TIMEOUT, "disconnect", endpoint);
        } catch (IOException ignored) {
            // The view is already closing; a missing or stopped adb process needs no UI error.
        }
    }

    /** Closes this view's processes without disconnecting a newly reused endpoint globally. */
    void closeSession() {
        ScrcpyControlClient direct;
        synchronized (this) {
            sessionClosed = true;
            direct = directControl;
            directControl = null;
        }
        if (direct != null) direct.close();
        closeControlChannel();
    }

    private void runInput(String... arguments) throws IOException {
        if (sessionClosed) throw new IOException("ADB 会话已关闭");
        ControlChannel channel = controlChannel;
        if (channel != null) {
            try {
                channel.send(arguments);
                return;
            } catch (IOException error) {
                closeControlChannel();
                // An acknowledgement timeout does not mean the input was not executed.
                // Replaying it could duplicate taps/text and stalls every later queued event.
                throw new IOException("ADB 控制失败，请重新连接：" + error.getMessage(), error);
            }
        }
        List<String> args = new ArrayList<>();
        args.add("-s");
        args.add(endpoint);
        args.add("shell");
        args.add("input");
        args.addAll(List.of(arguments));
        CommandResult result = run(INPUT_TIMEOUT, args.toArray(String[]::new));
        if (result.exitCode() != 0 || containsFailure(result.combinedText().toLowerCase(Locale.ROOT))) {
            throw commandFailure("发送控制指令失败", result);
        }
    }

    private CommandResult run(Duration timeout, String... arguments) throws IOException {
        return commandRunner.run(buildCommand(arguments), timeout);
    }

    private List<String> buildCommand(String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add(executable);
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    List<String> screenStreamCommand() {
        return buildCommand("-s", endpoint, "exec-out", "sh", "-c", SCREEN_STREAM_COMMAND);
    }

    List<String> videoStreamCommand() {
        ScreenSize size = videoSize();
        String remote = "while screenrecord --output-format=h264 --bit-rate "
                + videoBitRate + (size == null ? "" : " --size " + size.width() + "x" + size.height())
                + " -; do :; done";
        return buildCommand("-s", endpoint, "exec-out", "sh", "-c", remote);
    }

    static String resolveFfmpegExecutable() throws IOException {
        try {
            String path = Loader.load(ffmpeg.class);
            if (path == null || path.isBlank()) throw new IOException("内置 FFmpeg 路径为空");
            return path;
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            throw new IOException("无法加载当前平台的内置 FFmpeg：" + error.getMessage(), error);
        }
    }

    static List<String> ffmpegCommand(ScreenSize target) throws IOException {
        Objects.requireNonNull(target, "target");
        return List.of(resolveFfmpegExecutable(),
                "-hide_banner", "-loglevel", "warning", "-nostats",
                "-flags", "low_delay", "-avioflags", "direct",
                "-analyzeduration", "0", "-probesize", "32768", "-threads", "1",
                "-f", "h264", "-i", "pipe:0",
                "-map", "0:v:0", "-an", "-sn", "-dn",
                "-vf", "scale=" + target.width() + ':' + target.height() + ":flags=fast_bilinear",
                "-pix_fmt", "bgra", "-fps_mode", "passthrough",
                "-flush_packets", "1", "-f", "rawvideo", "pipe:1");
    }

    List<String> controlCommand() {
        // A non-PTY shell does not echo commands or inject prompts, which makes the
        // acknowledgement protocol deterministic on Windows, Linux and macOS hosts.
        return buildCommand("-s", endpoint, "shell", "-T");
    }

    private void closeControlChannel() {
        ControlChannel channel = controlChannel;
        controlChannel = null;
        if (channel != null) channel.close();
    }

    static String endpoint(String host, int port) {
        String value = host == null ? "" : host.trim();
        if (value.startsWith("adb://")) value = value.substring("adb://".length());
        if (value.isEmpty()) throw new IllegalArgumentException("ADB 主机地址不能为空");
        int effectivePort = port > 0 ? port : 5555;
        if (value.matches("^\\[[^]]+]:\\d+$") || value.matches("^[^:]+:\\d+$")) return value;
        if (value.startsWith("[") && value.endsWith("]")) return value + ':' + effectivePort;
        if (value.indexOf(':') >= 0) return '[' + value + "]:" + effectivePort;
        return value + ':' + effectivePort;
    }

    static ScreenSize parseDisplaySize(String output) {
        if (output == null) return null;
        Matcher matcher = DISPLAY_SIZE_PATTERN.matcher(output);
        ScreenSize result = null;
        while (matcher.find()) {
            try {
                int width = Integer.parseInt(matcher.group(1));
                int height = Integer.parseInt(matcher.group(2));
                if (width > 0 && height > 0) result = new ScreenSize(width, height);
            } catch (NumberFormatException ignored) {
                // Keep looking for another well-formed size line.
            }
        }
        return result;
    }

    static ScreenSize scaledVideoSize(ScreenSize source, int maxEdge) {
        if (source == null || maxEdge <= 0) return null;
        int longest = Math.max(source.width(), source.height());
        if (longest <= maxEdge) return source;
        double scale = (double) maxEdge / longest;
        int width = alignTo16(source.width() * scale);
        int height = alignTo16(source.height() * scale);
        return new ScreenSize(width, height);
    }

    private static int alignTo16(double value) {
        return Math.max(16, (int) Math.round(value / 16.0) * 16);
    }

    static String resolveExecutable(String configuredExecutable) {
        if (configuredExecutable != null && !configuredExecutable.isBlank()) {
            return configuredExecutable.trim();
        }
        String executableName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win") ? "adb.exe" : "adb";
        for (String variable : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String sdkRoot = System.getenv(variable);
            if (sdkRoot == null || sdkRoot.isBlank()) continue;
            Path candidate = Path.of(sdkRoot, "platform-tools", executableName);
            if (Files.isRegularFile(candidate)) return candidate.toString();
        }
        return "adb";
    }

    /** Android's input command represents spaces as %s and is evaluated by a device shell. */
    static String encodeInputText(String value) {
        StringBuilder encoded = new StringBuilder(value.length() * 2);
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == ' ') {
                encoded.append("%s");
            } else if (codePoint >= 0x21 && codePoint <= 0x7e) {
                char character = (char) codePoint;
                if ("\\\"'`$&|;<>*?()[]{}!#~".indexOf(character) >= 0) encoded.append('\\');
                encoded.append(character);
            }
            // `adb shell input text` cannot reliably inject non-ASCII characters.
        }
        return encoded.toString();
    }

    private static boolean containsFailure(String text) {
        return text.contains("failed") || text.contains("unable") || text.contains("cannot")
                || text.contains("refused") || text.contains("unauthorized") || text.contains("offline")
                || text.contains("error") || text.contains("exception");
    }

    static Integer parseControlAck(String line, String marker) {
        String value = line == null ? "" : line.trim();
        int markerIndex = value.lastIndexOf(marker);
        if (markerIndex < 0) return null;
        String status = value.substring(markerIndex + marker.length()).trim();
        if (!status.matches("\\d+")) return null;
        try {
            return Integer.parseInt(status);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static byte[] extractPngPayload(byte[] bytes) {
        if (bytes == null || bytes.length < PNG_SIGNATURE.length) return null;
        int lastStart = bytes.length - PNG_SIGNATURE.length;
        for (int start = 0; start <= lastStart; start++) {
            int offset = 0;
            while (offset < PNG_SIGNATURE.length
                    && bytes[start + offset] == PNG_SIGNATURE[offset]) {
                offset++;
            }
            if (offset == PNG_SIGNATURE.length) {
                return start == 0 ? bytes : Arrays.copyOfRange(bytes, start, bytes.length);
            }
        }
        return null;
    }

    /** Reads exactly one PNG, tolerating device warnings between back-to-back frames. */
    static byte[] readPngFrame(InputStream input) throws IOException {
        byte[] signature = seekPngSignature(input);

        ByteArrayOutputStream frame = new ByteArrayOutputStream(1024 * 1024);
        frame.write(signature);
        long frameBytes = signature.length;
        while (true) {
            byte[] lengthBytes = readExactly(input, 4);
            int chunkLength = ((lengthBytes[0] & 0xff) << 24)
                    | ((lengthBytes[1] & 0xff) << 16)
                    | ((lengthBytes[2] & 0xff) << 8)
                    | (lengthBytes[3] & 0xff);
            if (chunkLength < 0 || chunkLength > MAX_PNG_CHUNK_BYTES) {
                throw new IOException("ADB 屏幕流包含异常 PNG 数据块：" + chunkLength);
            }
            byte[] chunkType = readExactly(input, 4);
            frameBytes += 12 + chunkLength;
            if (frameBytes > MAX_PNG_FRAME_BYTES) {
                throw new IOException("ADB 屏幕截图过大，已停止读取");
            }
            frame.write(lengthBytes);
            frame.write(chunkType);
            copyExactly(input, frame, chunkLength);
            frame.write(readExactly(input, 4)); // CRC
            if (chunkType[0] == 'I' && chunkType[1] == 'E'
                    && chunkType[2] == 'N' && chunkType[3] == 'D') {
                return frame.toByteArray();
            }
        }
    }

    private static byte[] seekPngSignature(InputStream input) throws IOException {
        int matched = 0;
        int scanned = 0;
        while (scanned < MAX_PNG_PREFIX_BYTES + PNG_SIGNATURE.length) {
            int next = input.read();
            if (next < 0) {
                if (scanned == 0) throw new EOFException("ADB 屏幕流已结束");
                throw new IOException("ADB 屏幕流格式无效（未找到 PNG 帧头）");
            }
            scanned++;
            if (next == (PNG_SIGNATURE[matched] & 0xff)) {
                matched++;
                if (matched == PNG_SIGNATURE.length) return PNG_SIGNATURE.clone();
            } else {
                matched = next == (PNG_SIGNATURE[0] & 0xff) ? 1 : 0;
            }
        }
        throw new IOException("ADB 屏幕流格式无效（PNG 帧前的输出过长）");
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("ADB 屏幕流在 PNG 帧中途结束");
        return bytes;
    }

    private static void copyExactly(InputStream input, ByteArrayOutputStream output, int length) throws IOException {
        byte[] buffer = new byte[Math.min(64 * 1024, Math.max(1, length))];
        int remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("ADB 屏幕流在 PNG 数据块中途结束");
            if (read == 0) continue;
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private IOException commandFailure(String prefix, CommandResult result) {
        return new IOException(prefix + "：" + usefulMessage(result));
    }

    private static String usefulMessage(CommandResult result) {
        String message = result.combinedText().trim();
        if (message.isEmpty()) return "命令退出码 " + result.exitCode();
        return message.length() <= 500 ? message : message.substring(0, 500) + "…";
    }

    interface CommandRunner {
        CommandResult run(List<String> command, Duration timeout) throws IOException;
    }

    record CommandResult(int exitCode, byte[] stdout, byte[] stderr) {
        CommandResult {
            stdout = stdout == null ? new byte[0] : stdout;
            stderr = stderr == null ? new byte[0] : stderr;
        }

        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String combinedText() {
            String output = stdoutText();
            String error = new String(stderr, StandardCharsets.UTF_8);
            if (output.isBlank()) return error;
            if (error.isBlank()) return output;
            return output + System.lineSeparator() + error;
        }
    }

    record ScreenSize(int width, int height) {
        ScreenSize {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("屏幕尺寸必须为正数");
        }
    }

    static final class VideoStream implements AutoCloseable {
        private final ScrcpyVideoDecoder nativeDecoder;
        private final ScrcpyControlClient scrcpy;
        private final Process adbProcess;
        private final Process decoderProcess;
        private final InputStream frames;
        private final ErrorCapture adbStderr;
        private final ErrorCapture decoderStderr;
        private final int width;
        private final int height;
        private final byte[] bgra;
        private volatile boolean closed;

        private VideoStream(Process adbProcess, Process decoderProcess, ScreenSize size) {
            nativeDecoder = null;
            scrcpy = null;
            this.adbProcess = adbProcess;
            this.decoderProcess = decoderProcess;
            this.frames = new BufferedInputStream(decoderProcess.getInputStream(), 512 * 1024);
            this.adbStderr = new ErrorCapture(adbProcess.getErrorStream(), "ADB-video-stderr");
            this.decoderStderr = new ErrorCapture(decoderProcess.getErrorStream(), "FFmpeg-video-stderr");
            this.width = size.width();
            this.height = size.height();
            this.bgra = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        }

        private VideoStream(ScrcpyControlClient scrcpy) throws IOException {
            this.scrcpy = scrcpy;
            nativeDecoder = new ScrcpyVideoDecoder(scrcpy);
            adbProcess = decoderProcess = null;
            frames = null;
            adbStderr = decoderStderr = null;
            width = height = 0;
            bgra = null;
        }

        VideoFrame readFrame() throws IOException {
            if (nativeDecoder != null) {
                try {
                    return nativeDecoder.readFrame();
                } catch (IOException error) {
                    throw new IOException(error.getMessage() + " " + scrcpy.diagnostics(), error);
                }
            }
            try {
                int offset = 0;
                while (!closed && offset < bgra.length) {
                    int read = frames.read(bgra, offset, bgra.length - offset);
                    if (read < 0) {
                        throw new EOFException("ADB H.264 视频流已结束");
                    }
                    offset += read;
                }
                if (closed) throw new EOFException("ADB 视频流已关闭");
                return new VideoFrame(width, height, bgra);
            } catch (IOException error) {
                throw streamFailure(error.getMessage(), error);
            }
        }

        private IOException streamFailure(String message, Exception cause) {
            String detail = stderrText().trim();
            if (detail.length() > 500) detail = detail.substring(detail.length() - 500);
            String combined = message == null || message.isBlank() ? "ADB 视频流读取失败" : message;
            if (!detail.isBlank()) combined += "：" + detail;
            return new IOException(combined, cause);
        }

        private String stderrText() {
            String adb = adbStderr.text().trim();
            String decoder = decoderStderr.text().trim();
            if (adb.isBlank()) return decoder;
            if (decoder.isBlank()) return adb;
            return "ADB: " + adb + System.lineSeparator() + "FFmpeg: " + decoder;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (nativeDecoder != null) {
                nativeDecoder.close();
                return;
            }
            closeProcessStream(decoderProcess, frames, "FFmpeg-video-close");
            stopProcess(adbProcess, "ADB-video-close");
        }
    }

    record VideoFrame(int width, int height, byte[] bgra) {
    }

    private static final class ControlChannel implements AutoCloseable {
        private static final String CLOSED_MARKER = "__TOMATO_ADB_SHELL_CLOSED__";
        private static final long ACK_TIMEOUT_MILLIS = 2_000;
        private final Process process;
        private final OutputStream commands;
        private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        private long commandSequence;
        private volatile boolean closed;

        private ControlChannel(Process process) {
            this.process = process;
            this.commands = process.getOutputStream();
            readResponses(process.getInputStream());
            drain(process.getErrorStream(), "ADB-control-stderr");
        }

        synchronized void send(String... arguments) throws IOException {
            if (closed || !process.isAlive()) throw new IOException("ADB 控制通道已断开");
            String marker = "__TOMATO_INPUT_" + (++commandSequence) + "__:";
            String command = "input " + String.join(" ", arguments) + "; echo " + marker + "$?\n";
            commands.write(command.getBytes(StandardCharsets.UTF_8));
            commands.flush();

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACK_TIMEOUT_MILLIS);
            StringBuilder output = new StringBuilder();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("ADB 控制通道执行超时");
                String line;
                try {
                    line = responses.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("ADB 控制指令被中断", error);
                }
                if (line == null) throw new IOException("ADB 控制通道执行超时");
                if (CLOSED_MARKER.equals(line)) throw new IOException("ADB 控制通道已断开");
                Integer status = parseControlAck(line, marker);
                if (status != null) {
                    String detail = output.toString().trim();
                    if (status != 0 || containsFailure(detail.toLowerCase(Locale.ROOT))) {
                        throw new IOException(detail.isBlank()
                                ? "ADB input 命令退出码 " + status : detail);
                    }
                    return;
                }
                if (output.length() < 1_000) output.append(line).append('\n');
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                commands.write("exit\n".getBytes(StandardCharsets.UTF_8));
                commands.flush();
                commands.close();
            } catch (IOException ignored) {
            }
            process.destroy();
        }

        private void readResponses(InputStream input) {
            Thread.ofVirtual().name("ADB-control-stdout").start(() -> {
                try (input; BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) responses.offer(line);
                } catch (IOException ignored) {
                } finally {
                    responses.offer(CLOSED_MARKER);
                }
            });
        }

        private static void drain(InputStream input, String threadName) {
            Thread.ofVirtual().name(threadName).start(() -> {
                try (input) {
                    input.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
            });
        }
    }

    /** Continuously drains stderr while retaining only a small diagnostic tail. */
    private static final class ErrorCapture implements Runnable {
        private static final int CAPACITY = 8 * 1024;
        private final InputStream input;
        private final byte[] tail = new byte[CAPACITY];
        private int writePosition;
        private long totalBytes;

        private ErrorCapture(InputStream input, String threadName) {
            this.input = input;
            Thread.ofVirtual().name(threadName).start(this);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) append(buffer, read);
                }
            } catch (IOException ignored) {
            }
        }

        private synchronized void append(byte[] bytes, int length) {
            for (int i = 0; i < length; i++) {
                tail[writePosition] = bytes[i];
                writePosition = (writePosition + 1) % tail.length;
                totalBytes++;
            }
        }

        private synchronized String text() {
            int length = (int) Math.min(totalBytes, tail.length);
            if (length == 0) return "";
            byte[] snapshot = new byte[length];
            int start = totalBytes < tail.length ? 0 : writePosition;
            int firstPart = Math.min(length, tail.length - start);
            System.arraycopy(tail, start, snapshot, 0, firstPart);
            if (firstPart < length) System.arraycopy(tail, 0, snapshot, firstPart, length - firstPart);
            return new String(snapshot, StandardCharsets.UTF_8);
        }
    }

    static final class ScreenStream implements AutoCloseable {
        private final Process process;
        private final InputStream frames;
        private final ErrorCapture stderr;
        private volatile boolean closed;

        private ScreenStream(Process process) {
            this.process = process;
            this.frames = process.getInputStream();
            this.stderr = new ErrorCapture(process.getErrorStream(), "ADB-screen-stderr");
        }

        byte[] readFrame() throws IOException {
            try {
                return readPngFrame(frames);
            } catch (IOException error) {
                String detail = stderrText();
                if (!detail.isBlank()) {
                    throw new IOException(error.getMessage() + "：" + detail.trim(), error);
                }
                throw error;
            }
        }

        private String stderrText() {
            return stderr.text();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            closeProcessStream(process, frames, "ADB-screen-close");
        }
    }

    private static void closeProcessStream(Process process, InputStream stream, String threadName) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
        stopProcess(process, threadName);
    }

    private static void stopProcess(Process process, String threadName) {
        process.destroy();
        Thread.ofVirtual().name(threadName).start(() -> {
            try {
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        });
    }

    private static final class SystemCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command, Duration timeout) throws IOException {
            Process process;
            try {
                process = new ProcessBuilder(command).start();
            } catch (IOException error) {
                throw new IOException("无法启动 ADB，请安装 Android platform-tools 或在连接配置中选择 adb 程序："
                        + error.getMessage(), error);
            }

            FutureTask<byte[]> stdout = streamReader(process.getInputStream());
            FutureTask<byte[]> stderr = streamReader(process.getErrorStream());
            Thread.ofVirtual().name("ADB-stdout").start(stdout);
            Thread.ofVirtual().name("ADB-stderr").start(stderr);
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("ADB 命令执行超时（" + timeout.toSeconds() + " 秒）");
                }
                return new CommandResult(process.exitValue(), stdout.get(2, TimeUnit.SECONDS),
                        stderr.get(2, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("ADB 命令被中断", error);
            } catch (ExecutionException | TimeoutException error) {
                process.destroyForcibly();
                throw new IOException("读取 ADB 命令输出失败", error);
            }
        }

        private static FutureTask<byte[]> streamReader(InputStream input) {
            return new FutureTask<>(() -> {
                try (input) {
                    return input.readAllBytes();
                }
            });
        }
    }
}
