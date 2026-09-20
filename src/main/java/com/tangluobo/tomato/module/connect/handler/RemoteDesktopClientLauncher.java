package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Starts a native VNC/SPICE client. remote-viewer is preferred because its
 * one-time .vv file supports both protocols without exposing passwords in the
 * process command line.
 */
final class RemoteDesktopClientLauncher {
    private static final String REMOTE_VIEWER = "remote-viewer";

    private RemoteDesktopClientLauncher() {
    }

    static String launch(ConnectionConfig config) throws IOException, ClientNotFoundException {
        Path configured = configuredClient(config).orElse(null);
        if (configured != null) {
            if (config.getType() == ConnectType.SPICE || isRemoteViewer(configured)) {
                launchRemoteViewer(configured, config);
                return configured.getFileName().toString();
            }
            launchVncViewer(configured, config);
            return configured.getFileName().toString();
        }

        Path remoteViewer = findExecutable(remoteViewerCandidates()).orElse(null);
        if (remoteViewer != null) {
            launchRemoteViewer(remoteViewer, config);
            return remoteViewer.getFileName().toString();
        }

        if (config.getType() == ConnectType.VNC) {
            Path vncViewer = findExecutable(vncViewerCandidates()).orElse(null);
            if (vncViewer != null) {
                launchVncViewer(vncViewer, config);
                return vncViewer.getFileName().toString();
            }
            if (launchVncUri(config)) return "系统 VNC 客户端";
        }

        throw new ClientNotFoundException(config.getType() == ConnectType.SPICE
                ? "未找到 remote-viewer。SPICE 连接需要安装 virt-viewer，或在连接配置中指定 remote-viewer 路径。"
                : "未找到可用的 VNC 客户端。请安装 virt-viewer、TigerVNC、TightVNC 或 RealVNC，或在连接配置中指定客户端路径。");
    }

    static String buildConnectionFile(ConnectionConfig config) {
        StringBuilder content = new StringBuilder(192);
        content.append("[virt-viewer]\n")
                .append("type=").append(config.getType() == ConnectType.SPICE ? "spice" : "vnc").append('\n')
                .append("host=").append(escapeIni(config.getHost())).append('\n')
                .append("port=").append(effectivePort(config)).append('\n');
        if (config.getType() == ConnectType.SPICE && config.getRemoteDesktopTlsPort() > 0) {
            content.append("tls-port=").append(config.getRemoteDesktopTlsPort()).append('\n');
        }
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            content.append("username=").append(escapeIni(config.getUsername())).append('\n');
        }
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            content.append("password=").append(escapeIni(config.getPassword())).append('\n');
        }
        if (config.getName() != null && !config.getName().isBlank()) {
            content.append("title=").append(escapeIni(config.getName())).append('\n');
        }
        content.append("fullscreen=").append(config.isFullscreen() ? '1' : '0').append('\n')
                .append("delete-this-file=1\n");
        return content.toString();
    }

    static List<String> buildVncViewerCommand(Path executable, ConnectionConfig config) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        if (config.isFullscreen()) command.add("-FullScreen");
        String host = config.getHost();
        if (host != null && host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            host = "[" + host + "]";
        }
        command.add(host + "::" + effectivePort(config));
        return command;
    }

    static int effectivePort(ConnectionConfig config) {
        return config.getPort() > 0 ? config.getPort() : 5900;
    }

    private static void launchRemoteViewer(Path executable, ConnectionConfig config) throws IOException {
        Path connectionFile = Files.createTempFile("tomato-remote-", ".vv");
        connectionFile.toFile().deleteOnExit();
        restrictToOwner(connectionFile);
        Files.writeString(connectionFile, buildConnectionFile(config), StandardCharsets.UTF_8);
        try {
            Process process = start(List.of(executable.toString(), connectionFile.toString()));
            process.onExit().thenRun(() -> deleteQuietly(connectionFile));
        } catch (IOException e) {
            deleteQuietly(connectionFile);
            throw e;
        }
    }

    private static void launchVncViewer(Path executable, ConnectionConfig config) throws IOException {
        ProcessBuilder builder = processBuilder(buildVncViewerCommand(executable, config));
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            builder.environment().put("VNC_USERNAME", config.getUsername());
        }
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            builder.environment().put("VNC_PASSWORD", config.getPassword());
        }
        builder.start();
    }

    private static boolean launchVncUri(ConnectionConfig config) {
        String uri = "vnc://" + uriHost(config.getHost()) + ":" + effectivePort(config);
        String os = osName();
        try {
            if (os.contains("mac")) {
                start(List.of("/usr/bin/open", uri));
                return true;
            }
            if (!os.contains("win")) {
                Optional<Path> xdgOpen = findExecutable(List.of(Path.of("xdg-open")));
                if (xdgOpen.isPresent()) {
                    start(List.of(xdgOpen.get().toString(), uri));
                    return true;
                }
            }
        } catch (IOException ignored) {
            // The caller will show the normal client-not-found guidance.
        }
        return false;
    }

    private static Process start(List<String> command) throws IOException {
        return processBuilder(command).start();
    }

    private static ProcessBuilder processBuilder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder;
    }

    private static Optional<Path> configuredClient(ConnectionConfig config) throws ClientNotFoundException {
        String configured = config.getRemoteDesktopClientPath();
        if (configured == null || configured.isBlank()) return Optional.empty();
        Path path;
        try {
            path = Path.of(configured.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ClientNotFoundException("配置的远程桌面客户端路径无效：" + configured);
        }
        if (!Files.isRegularFile(path)) {
            throw new ClientNotFoundException("找不到配置的远程桌面客户端：" + path);
        }
        return Optional.of(path);
    }

    private static Optional<Path> findExecutable(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (candidate == null) continue;
            if (candidate.getNameCount() == 1) {
                Optional<Path> fromPath = findOnPath(candidate.toString());
                if (fromPath.isPresent()) return fromPath;
            } else if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findOnPath(String executable) {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) return Optional.empty();
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            try {
                Path candidate = Path.of(directory, executable);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate.toAbsolutePath().normalize());
            } catch (RuntimeException ignored) {
                // Ignore malformed PATH entries.
            }
        }
        return Optional.empty();
    }

    private static List<Path> remoteViewerCandidates() {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(Path.of(windows() ? "remote-viewer.exe" : REMOTE_VIEWER));
        if (windows()) {
            addWindowsRemoteViewerCandidates(candidates, System.getenv("ProgramFiles"));
            addWindowsRemoteViewerCandidates(candidates, System.getenv("ProgramFiles(x86)"));
            addPath(candidates, System.getenv("LOCALAPPDATA"), "Programs", "VirtViewer", "bin", "remote-viewer.exe");
        } else {
            candidates.add(Path.of("/usr/bin/remote-viewer"));
            candidates.add(Path.of("/usr/local/bin/remote-viewer"));
            candidates.add(Path.of("/opt/homebrew/bin/remote-viewer"));
            candidates.add(Path.of("/Applications/Remote Viewer.app/Contents/MacOS/remote-viewer"));
        }
        return List.copyOf(candidates);
    }

    private static List<Path> vncViewerCandidates() {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(Path.of(windows() ? "vncviewer.exe" : "vncviewer"));
        if (windows()) {
            String programFiles = System.getenv("ProgramFiles");
            String programFilesX86 = System.getenv("ProgramFiles(x86)");
            addPath(candidates, programFiles, "TigerVNC", "vncviewer.exe");
            addPath(candidates, programFiles, "TightVNC", "tvnviewer.exe");
            addPath(candidates, programFiles, "RealVNC", "VNC Viewer", "vncviewer.exe");
            addPath(candidates, programFiles, "uvnc bvba", "UltraVNC", "vncviewer.exe");
            addPath(candidates, programFilesX86, "TightVNC", "tvnviewer.exe");
            addPath(candidates, programFilesX86, "RealVNC", "VNC Viewer", "vncviewer.exe");
            addPath(candidates, programFilesX86, "uvnc bvba", "UltraVNC", "vncviewer.exe");
        } else {
            candidates.add(Path.of("/usr/bin/vncviewer"));
            candidates.add(Path.of("/usr/local/bin/vncviewer"));
            candidates.add(Path.of("/opt/homebrew/bin/vncviewer"));
        }
        return List.copyOf(candidates);
    }

    private static void addWindowsRemoteViewerCandidates(Set<Path> candidates, String rootValue) {
        if (rootValue == null || rootValue.isBlank()) return;
        Path root = Path.of(rootValue);
        addPath(candidates, rootValue, "VirtViewer", "bin", "remote-viewer.exe");
        if (!Files.isDirectory(root)) return;
        try (var directories = Files.list(root)) {
            directories.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("virtviewer"))
                    .map(path -> path.resolve("bin").resolve("remote-viewer.exe"))
                    .forEach(candidates::add);
        } catch (IOException ignored) {
            // PATH and manually configured client remain available.
        }
    }

    private static void addPath(Set<Path> paths, String root, String... parts) {
        if (root == null || root.isBlank()) return;
        Path path = Path.of(root, parts);
        paths.add(path);
    }

    private static boolean isRemoteViewer(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).contains(REMOTE_VIEWER);
    }

    private static String escapeIni(String value) {
        return value.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n");
    }

    private static String uriHost(String host) {
        if (host != null && host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            return "[" + host + "]";
        }
        return host;
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows temp files inherit the user's temp-directory ACL.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static boolean windows() {
        return osName().contains("win");
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    static final class ClientNotFoundException extends Exception {
        ClientNotFoundException(String message) {
            super(message);
        }
    }
}
