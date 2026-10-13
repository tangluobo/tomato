package com.tangluobo.tomato;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {
    @TempDir Path directory;

    @Test void nativeMetadataRegistersTheCursorCallbackUsedBySvgIcons() throws Exception {
        try (var input = getClass().getResourceAsStream("/META-INF/native-image/reachability-metadata.json")) {
            var metadata = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var cursor = java.util.stream.StreamSupport.stream(metadata.getAsJsonArray("reflection").spliterator(), false)
                    .map(com.google.gson.JsonElement::getAsJsonObject)
                    .filter(item -> item.get("type").isJsonPrimitive()
                            && item.get("type").getAsString().equals("java.awt.Cursor"))
                    .findFirst().orElseThrow();
            assertTrue(cursor.get("jniAccessible").getAsBoolean());
            assertTrue(java.util.stream.StreamSupport.stream(cursor.getAsJsonArray("methods").spliterator(), false)
                    .anyMatch(method -> method.getAsJsonObject().get("name").getAsString().equals("setPData")));
        }
    }

    @Test void nativeMetadataIncludesEveryConnectionTypeIncludingFutureEnums() throws Exception {
        try (var input = getClass().getResourceAsStream("/META-INF/native-image/reachability-metadata.json")) {
            var metadata = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var entries = metadata.getAsJsonArray("reflection");
            var entry = java.util.stream.StreamSupport.stream(entries.spliterator(), false)
                    .map(com.google.gson.JsonElement::getAsJsonObject)
                    .filter(item -> item.get("type").getAsString().equals(
                            "com.tangluobo.tomato.module.connect.ConnectType"))
                    .findFirst().orElseThrow();
            assertTrue(entry.get("allDeclaredFields").getAsBoolean());
        }
    }

    @Test void capturesStartupCauseAndKeepsConcurrentLaunchLogsSeparate() throws Exception {
        try (var first = Launcher.openDiagnostics(directory);
             var second = Launcher.openDiagnostics(directory)) {
            new IllegalStateException("启动失败", new UnsatisfiedLinkError("missing native library"))
                    .printStackTrace(first);
            second.println("second launch");
        }
        try (var files = Files.list(directory)) {
            var logs = files.toList();
            assertEquals(2, logs.size());
            String captured = Files.readString(logs.get(0)) + Files.readString(logs.get(1));
            assertTrue(captured.contains("启动失败"));
            assertTrue(captured.contains("missing native library"));
            assertTrue(captured.contains("second launch"));
        }
    }
}
