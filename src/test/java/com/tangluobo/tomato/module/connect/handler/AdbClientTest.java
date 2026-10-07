package com.tangluobo.tomato.module.connect.handler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdbClientTest {
    @Test
    void loadsBundledNativeFfmpegDecoder() {
        assertDoesNotThrow(() -> {
            Path executable = Path.of(AdbClient.resolveFfmpegExecutable());
            assertTrue(Files.isRegularFile(executable));
        });
    }

    @Test
    void buildsTcpEndpointsForHostnamesAndIpv6() {
        assertEquals("phone.lan:5555", AdbClient.endpoint("phone.lan", 5555));
        assertEquals("phone.lan:4321", AdbClient.endpoint("adb://phone.lan:4321", 5555));
        assertEquals("[2001:db8::2]:5555", AdbClient.endpoint("2001:db8::2", 5555));
        assertEquals("[2001:db8::2]:4321", AdbClient.endpoint("[2001:db8::2]", 4321));
    }

    @Test
    void connectsCapturesAndSendsAndroidInputCommands() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        runner.reply(textResult(0, "connected to phone.lan:5555\n"));
        runner.reply(textResult(0, "device\n"));
        runner.reply(textResult(0, "Physical size: 1080x2400\n"));
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        byte[] warning = "Warning: Multiple displays were found.\n".getBytes(StandardCharsets.UTF_8);
        byte[] warningAndPng = new byte[warning.length + png.length];
        System.arraycopy(warning, 0, warningAndPng, 0, warning.length);
        System.arraycopy(png, 0, warningAndPng, warning.length, png.length);
        runner.reply(new AdbClient.CommandResult(0, warningAndPng, new byte[0]));
        runner.reply(textResult(0, ""));
        runner.reply(textResult(0, ""));

        AdbClient client = new AdbClient("phone.lan", 5555, "custom-adb", runner);
        client.connect();
        assertArrayEquals(png, client.captureScreen());
        client.tap(12, 34);
        client.keyEvent(AdbClient.KEYCODE_HOME);

        assertEquals(List.of("custom-adb", "connect", "phone.lan:5555"), runner.commands.get(0));
        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "get-state"), runner.commands.get(1));
        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "shell", "wm", "size"),
                runner.commands.get(2));
        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "exec-out", "sh", "-c",
                "while screenrecord --output-format=h264 --bit-rate 2000000 -; do :; done"),
                client.videoStreamCommand());
        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "exec-out",
                        "screencap", "-d", "0", "-p"),
                runner.commands.get(3));
        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "shell", "input", "tap", "12", "34"),
                runner.commands.get(4));
        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "shell", "input", "keyevent", "3"),
                runner.commands.get(5));
    }

    @Test
    void rejectsUnauthorizedDevicesWithUsefulMessage() {
        RecordingRunner runner = new RecordingRunner();
        runner.reply(textResult(0, "connected to phone.lan:5555\n"));
        runner.reply(textResult(0, "unauthorized\n"));
        AdbClient client = new AdbClient("phone.lan", 5555, "adb", runner);

        IOException error = assertThrows(IOException.class, client::connect);

        assertTrue(error.getMessage().contains("未授权"));
    }

    @Test
    void escapesTextForAndroidInputCommand() {
        assertEquals("hello%sworld", AdbClient.encodeInputText("hello world"));
        assertEquals("A%sb\\&c", AdbClient.encodeInputText("A b&c"));
        assertEquals("ascii", AdbClient.encodeInputText("中ascii文"));
    }

    @Test
    void persistentScreenCommandReusesOneAdbProcess() {
        AdbClient client = new AdbClient("phone.lan", 5555, "custom-adb", new RecordingRunner());

        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "exec-out", "sh", "-c",
                "while screencap -d 0 -p 2>/dev/null; do :; done"), client.screenStreamCommand());
        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "exec-out", "sh", "-c",
                "while screenrecord --output-format=h264 --bit-rate 2000000 -; do :; done"),
                client.videoStreamCommand());
        assertEquals(List.of("custom-adb", "-s", "phone.lan:5555", "shell", "-T"), client.controlCommand());
    }

    @Test
    void parsesPersistentShellAcknowledgementsWithoutAcceptingEchoedCommands() {
        String marker = "__TOMATO_INPUT_7__:";

        assertEquals(0, AdbClient.parseControlAck(marker + "0", marker));
        assertEquals(127, AdbClient.parseControlAck("device:/ $ " + marker + "127", marker));
        assertEquals(null, AdbClient.parseControlAck("echo " + marker + "$?", marker));
    }

    @Test
    void parsesAndScalesDeviceDisplaySizeForFastVideo() {
        AdbClient.ScreenSize size = AdbClient.parseDisplaySize(
                "Physical size: 1080x2400\nOverride size: 720x1600\n");

        assertEquals(new AdbClient.ScreenSize(720, 1600), size);
        assertEquals(new AdbClient.ScreenSize(464, 1024),
                AdbClient.scaledVideoSize(new AdbClient.ScreenSize(1080, 2400), 1024));
        assertEquals(new AdbClient.ScreenSize(800, 600),
                AdbClient.scaledVideoSize(new AdbClient.ScreenSize(800, 600), 1024));
    }

    @Test
    void extractsBackToBackPngFramesFromPersistentStream() throws Exception {
        byte[] frame = java.util.HexFormat.of().parseHex(
                "89504e470d0a1a0a0000000049454e4400000000");
        byte[] warning = "Warning: Multiple displays were found.\n".getBytes(StandardCharsets.UTF_8);
        byte[] concatenated = new byte[warning.length * 2 + frame.length * 2];
        int offset = 0;
        System.arraycopy(warning, 0, concatenated, offset, warning.length);
        offset += warning.length;
        System.arraycopy(frame, 0, concatenated, offset, frame.length);
        offset += frame.length;
        System.arraycopy(warning, 0, concatenated, offset, warning.length);
        offset += warning.length;
        System.arraycopy(frame, 0, concatenated, offset, frame.length);
        ByteArrayInputStream input = new ByteArrayInputStream(concatenated);

        assertArrayEquals(frame, AdbClient.readPngFrame(input));
        assertArrayEquals(frame, AdbClient.readPngFrame(input));
        assertThrows(IOException.class, () -> AdbClient.readPngFrame(input));
    }

    @Test
    void stripsTextBeforeOneShotPngPayload() {
        byte[] png = java.util.HexFormat.of().parseHex("89504e470d0a1a0a0102");
        byte[] warning = "Warning: select a display.\n".getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[warning.length + png.length];
        System.arraycopy(warning, 0, output, 0, warning.length);
        System.arraycopy(png, 0, output, warning.length, png.length);

        assertArrayEquals(png, AdbClient.extractPngPayload(output));
    }

    private static AdbClient.CommandResult textResult(int exitCode, String stdout) {
        return new AdbClient.CommandResult(exitCode, stdout.getBytes(StandardCharsets.UTF_8), new byte[0]);
    }

    private static final class RecordingRunner implements AdbClient.CommandRunner {
        private final Queue<AdbClient.CommandResult> replies = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();

        void reply(AdbClient.CommandResult result) {
            replies.add(result);
        }

        @Override
        public AdbClient.CommandResult run(List<String> command, Duration timeout) {
            commands.add(command);
            return replies.remove();
        }
    }
}
