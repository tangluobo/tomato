package com.tangluobo.tomato.module.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadTaskStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void savesAndRestoresEveryFieldAtomically() throws Exception {
        Path storeFile = tempDirectory.resolve("nested/downloads.json");
        DownloadTaskStore.TaskRecord expected = new DownloadTaskStore.TaskRecord(
                "https://example.com/archive.zip", "HTTP", tempDirectory.toString(), 12,
                "archive.zip", 1_234_567L, 9_876_543L,
                tempDirectory.resolve("archive.zip").toString(), null,
                "DOWNLOADING", "正在使用 12 个线程下载", 12, 3);

        DownloadTaskStore.save(storeFile, List.of(expected));
        List<DownloadTaskStore.TaskRecord> restored = DownloadTaskStore.load(storeFile);

        assertEquals(1, restored.size());
        DownloadTaskStore.TaskRecord actual = restored.getFirst();
        assertEquals(expected.url(), actual.url());
        assertEquals(expected.protocol(), actual.protocol());
        assertEquals(expected.directory(), actual.directory());
        assertEquals(expected.requestedThreads(), actual.requestedThreads());
        assertEquals(expected.fileName(), actual.fileName());
        assertEquals(expected.downloadedBytes(), actual.downloadedBytes());
        assertEquals(expected.totalBytes(), actual.totalBytes());
        assertEquals(expected.targetFile(), actual.targetFile());
        assertEquals(expected.completedFile(), actual.completedFile());
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.actualThreads(), actual.actualThreads());
        assertEquals(expected.rebalanceCount(), actual.rebalanceCount());
        assertTrue(Files.isRegularFile(storeFile));
        assertFalse(Files.exists(storeFile.resolveSibling("downloads.json.tmp")));
    }

    @Test
    void damagedStoreDoesNotPreventDownloaderFromOpening() throws Exception {
        Path storeFile = tempDirectory.resolve("downloads.json");
        Files.writeString(storeFile, "{not-json", StandardCharsets.UTF_8);

        assertTrue(DownloadTaskStore.load(storeFile).isEmpty());
    }
}
