package com.tangluobo.tomato;

import javafx.application.Application;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

public class Launcher {
    public static void main(String[] args) {
        // 关闭 HiDPI 自动缩放：让 1 逻辑像素 = 1 物理像素，避免 Windows 显示缩放（如 150%）把界面整体放大
        System.setProperty("prism.allowhidpi", "false");
        // 启用LCD文字渲染，消除模糊
        System.setProperty("prism.lcdtext", "true");
        System.setProperty("prism.text", "lcd");
        // GUI executables have no reliable console handles. Preserve errors in a writable
        // per-user log instead of silently discarding JavaFX/Native Image startup failures.
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows");
        if (windows && !"1".equals(System.getenv("TOMATO_CONSOLE_DIAGNOSTICS"))
                && !(args.length == 1 && "--aot-adb-video-self-check".equals(args[0]))) {
            PrintStream diagnostics = openDiagnostics();
            System.setOut(diagnostics);
            System.setErr(diagnostics);
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("Uncaught exception on " + thread.getName());
            error.printStackTrace(System.err);
        });
        try {
            if (args.length == 1 && "--aot-adb-video-self-check".equals(args[0])) {
                com.tangluobo.tomato.module.connect.handler.AdbVideoSelfCheck.run();
            } else if (args.length > 0 && "--aot-ui-self-check".equals(args[0])) {
                if (args.length != 2) throw new IllegalArgumentException("Self-check requires an isolated profile directory");
                Files.createDirectories(Path.of(args[1]));
                System.setProperty("user.home", Path.of(args[1]).toAbsolutePath().toString());
                Application.launch(AotUiSelfCheck.class, args);
            } else {
                Application.launch(TomatoApplication.class, args);
            }
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static PrintStream openDiagnostics() {
        Path[] directories = {
                Path.of(System.getProperty("user.home", "."), ".tomato", "logs"),
                Path.of(System.getProperty("java.io.tmpdir", "."), "tomato-logs")
        };
        for (Path directory : directories) {
            try {
                PrintStream log = openDiagnostics(directory);
                log.println("Tomato launch " + java.time.Instant.now());
                log.println("Runtime: " + System.getProperty("java.runtime.version")
                        + ", native image: " + System.getProperty("org.graalvm.nativeimage.imagecode", "false"));
                return log;
            } catch (IOException | SecurityException ignored) {
                // Try the temporary directory if the user profile is not writable.
            }
        }
        return new PrintStream(OutputStream.nullOutputStream(), true);
    }

    static PrintStream openDiagnostics(Path directory) throws IOException {
        Files.createDirectories(directory);
        // One log per invocation prevents concurrent launches from overwriting each other.
        Path file = Files.createTempFile(directory, "startup-", ".log");
        return new PrintStream(Files.newOutputStream(file, StandardOpenOption.WRITE), true,
                StandardCharsets.UTF_8);
    }
}
