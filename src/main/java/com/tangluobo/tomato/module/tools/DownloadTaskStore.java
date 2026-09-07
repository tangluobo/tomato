package com.tangluobo.tomato.module.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persists the downloader task list independently from the JavaFX view lifecycle. */
final class DownloadTaskStore {

    private static final Path STORE_FILE = Path.of(System.getProperty("user.home"),
            ".tomato", "downloads.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DownloadTaskStore() {
    }

    static List<TaskRecord> load() {
        return load(STORE_FILE);
    }

    static List<TaskRecord> load(Path file) {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            StoreData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    StoreData.class);
            if (data == null || data.version != 1 || data.tasks == null) return List.of();
            return List.copyOf(data.tasks);
        } catch (Exception ex) {
            System.err.println("[DownloadTaskStore] 加载下载记录失败: " + ex.getMessage());
            return List.of();
        }
    }

    static void save(List<TaskRecord> tasks) throws IOException {
        save(STORE_FILE, tasks);
    }

    static void save(Path file, List<TaskRecord> tasks) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary,
                GSON.toJson(new StoreData(1, tasks == null ? List.of() : tasks)),
                StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static final class TaskRecord {
        private String url;
        private String protocol;
        private String directory;
        private int requestedThreads;
        private String fileName;
        private long downloadedBytes;
        private long totalBytes;
        private String targetFile;
        private String completedFile;
        private String state;
        private String status;
        private int actualThreads;
        private int rebalanceCount;

        TaskRecord(String url, String protocol, String directory, int requestedThreads, String fileName,
                   long downloadedBytes, long totalBytes, String targetFile,
                   String completedFile, String state, String status, int actualThreads,
                   int rebalanceCount) {
            this.url = url;
            this.protocol = protocol;
            this.directory = directory;
            this.requestedThreads = requestedThreads;
            this.fileName = fileName;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.targetFile = targetFile;
            this.completedFile = completedFile;
            this.state = state;
            this.status = status;
            this.actualThreads = actualThreads;
            this.rebalanceCount = rebalanceCount;
        }

        TaskRecord(String url, String directory, int requestedThreads, String fileName,
                   long downloadedBytes, long totalBytes, String targetFile,
                   String completedFile, String state, String status, int actualThreads,
                   int rebalanceCount) {
            this(url, null, directory, requestedThreads, fileName, downloadedBytes, totalBytes,
                    targetFile, completedFile, state, status, actualThreads, rebalanceCount);
        }

        String url() { return url; }
        String protocol() { return protocol; }
        String directory() { return directory; }
        int requestedThreads() { return requestedThreads; }
        String fileName() { return fileName; }
        long downloadedBytes() { return downloadedBytes; }
        long totalBytes() { return totalBytes; }
        String targetFile() { return targetFile; }
        String completedFile() { return completedFile; }
        String state() { return state; }
        String status() { return status; }
        int actualThreads() { return actualThreads; }
        int rebalanceCount() { return rebalanceCount; }
    }

    private static final class StoreData {
        private int version;
        private List<TaskRecord> tasks;

        private StoreData(int version, List<TaskRecord> tasks) {
            this.version = version;
            this.tasks = new ArrayList<>(tasks);
        }
    }
}
