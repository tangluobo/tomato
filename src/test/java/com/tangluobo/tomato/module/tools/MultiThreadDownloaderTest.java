package com.tangluobo.tomato.module.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiThreadDownloaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void partitionsFileWithoutGapsOrOverlap() {
        List<MultiThreadDownloader.Range> ranges = MultiThreadDownloader.partitionRanges(10, 3);

        assertEquals(List.of(
                new MultiThreadDownloader.Range(0, 3),
                new MultiThreadDownloader.Range(4, 6),
                new MultiThreadDownloader.Range(7, 9)), ranges);
        assertEquals(1, MultiThreadDownloader.clampThreads(0));
        assertEquals(32, MultiThreadDownloader.clampThreads(100));
    }

    @Test
    void completedWorkerReassignsSlowSegmentsAndPreservesFileContent() throws Exception {
        byte[] content = new byte[12 * 1024 * 1024 + 123];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31 + 7);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/sample.bin", exchange -> serveRangeFile(exchange, content));
        server.start();

        AtomicInteger rebalances = new AtomicInteger();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/sample.bin";
            MultiThreadDownloader downloader = new MultiThreadDownloader();
            MultiThreadDownloader.Result result = downloader.download(url, tempDirectory, 2,
                    new MultiThreadDownloader.Listener() {
                        @Override
                        public void onRebalanced(MultiThreadDownloader.Rebalance rebalance) {
                            rebalances.incrementAndGet();
                        }
                    });

            assertTrue(rebalances.get() > 0, "the fast worker should split the slow range");
            assertEquals(rebalances.get(), result.rebalanceCount());
            assertEquals(2, result.threadCount());
            assertEquals(content.length, result.downloadedBytes());
            assertArrayEquals(content, Files.readAllBytes(result.file()));
            assertEquals("sample.bin", result.file().getFileName().toString());
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }

    @Test
    void stalledRangeConnectionIsReopenedFromItsDownloadedOffset() throws Exception {
        byte[] content = new byte[10 * 1024 * 1024 + 37];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 19 + 3);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        AtomicBoolean stallFirstRange = new AtomicBoolean(true);
        AtomicInteger fullRangeRequests = new AtomicInteger();
        server.setExecutor(serverThreads);
        server.createContext("/stalled.bin", exchange -> serveRangeFileWithOneStall(
                exchange, content, stallFirstRange, fullRangeRequests));
        server.start();

        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/stalled.bin";
            MultiThreadDownloader downloader = new MultiThreadDownloader(
                    Duration.ofMillis(350), Duration.ofSeconds(30));
            MultiThreadDownloader.Result result = downloader.download(url, tempDirectory, 2, null);

            assertTrue(result.connectionRestartCount() > 0,
                    "a stalled response should be replaced without reopening the app");
            assertTrue(fullRangeRequests.get() > 2,
                    "the replacement request should resume the unfinished range");
            assertEquals(content.length, result.downloadedBytes());
            assertArrayEquals(content, Files.readAllBytes(result.file()));
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }

    @Test
    void concurrentDownloadsAtomicallyReserveDistinctFileNames() throws Exception {
        byte[] content = new byte[3 * 1024 * 1024 + 17];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 13 + 5);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        ExecutorService downloadTasks = Executors.newFixedThreadPool(2);
        server.setExecutor(serverThreads);
        server.createContext("/sample.bin", exchange -> serveRangeFile(exchange, content));
        server.start();

        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/sample.bin";
            Future<MultiThreadDownloader.Result> first = downloadTasks.submit(() ->
                    new MultiThreadDownloader().download(url, tempDirectory, 2, null));
            Future<MultiThreadDownloader.Result> second = downloadTasks.submit(() ->
                    new MultiThreadDownloader().download(url, tempDirectory, 2, null));

            MultiThreadDownloader.Result firstResult = first.get(30, TimeUnit.SECONDS);
            MultiThreadDownloader.Result secondResult = second.get(30, TimeUnit.SECONDS);
            List<String> names = List.of(firstResult.file().getFileName().toString(),
                            secondResult.file().getFileName().toString())
                    .stream().sorted().toList();

            assertEquals(List.of("sample (1).bin", "sample.bin"), names);
            assertArrayEquals(content, Files.readAllBytes(firstResult.file()));
            assertArrayEquals(content, Files.readAllBytes(secondResult.file()));
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
            downloadTasks.shutdownNow();
        }
    }

    @Test
    void retryReadsCheckpointAndDownloadsOnlyMissingRanges() throws Exception {
        byte[] content = new byte[12 * 1024 * 1024 + 91];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 17 + 11);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        ExecutorService firstAttemptThread = Executors.newSingleThreadExecutor();
        AtomicBoolean countResumeRequests = new AtomicBoolean();
        AtomicLong resumeResponseBytes = new AtomicLong();
        server.setExecutor(serverThreads);
        server.createContext("/resume.bin", exchange -> serveRangeFile(exchange, content,
                countResumeRequests, resumeResponseBytes));
        server.start();

        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/resume.bin";
            MultiThreadDownloader firstDownloader = new MultiThreadDownloader();
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<Path> target = new AtomicReference<>();
            Future<?> firstAttempt = firstAttemptThread.submit(() ->
                    firstDownloader.download(url, tempDirectory, 2,
                            new MultiThreadDownloader.Listener() {
                                @Override
                                public void onPrepared(MultiThreadDownloader.Metadata metadata,
                                                       Path preparedTarget, int actualThreads) {
                                    target.set(preparedTarget);
                                }

                                @Override
                                public void onProgress(MultiThreadDownloader.Progress progress) {
                                    if (progress.downloadedBytes() >= 2L * 1024 * 1024
                                            && cancelled.compareAndSet(false, true)) {
                                        firstDownloader.cancel();
                                    }
                                }
                            }));

            ExecutionException stopped = assertThrows(ExecutionException.class,
                    () -> firstAttempt.get(30, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, stopped.getCause());
            assertTrue(cancelled.get());
            assertTrue(Files.isRegularFile(target.get().resolveSibling(
                    target.get().getFileName() + ".part")));
            assertTrue(Files.isRegularFile(target.get().resolveSibling(
                    target.get().getFileName() + ".part.meta")));

            countResumeRequests.set(true);
            MultiThreadDownloader.Result resumed = new MultiThreadDownloader().download(
                    url, tempDirectory, 2, null, target.get());

            assertEquals(content.length, resumed.downloadedBytes());
            assertArrayEquals(content, Files.readAllBytes(resumed.file()));
            assertTrue(resumeResponseBytes.get() < content.length,
                    "retry should request fewer bytes than a complete download");
            assertFalse(Files.exists(resumed.file().resolveSibling(
                    resumed.file().getFileName() + ".part.meta")));
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
            firstAttemptThread.shutdownNow();
        }
    }

    private static void serveRangeFile(HttpExchange exchange, byte[] content) throws IOException {
        serveRangeFile(exchange, content, null, null);
    }

    private static void serveRangeFile(HttpExchange exchange, byte[] content,
                                       AtomicBoolean countResponseBytes,
                                       AtomicLong responseBytes) throws IOException {
        boolean countThisRequest = countResponseBytes != null && countResponseBytes.get();
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"sample.bin\"");
        if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(content.length));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        if (rangeHeader == null) {
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(content);
                if (countThisRequest) responseBytes.addAndGet(content.length);
            }
            return;
        }

        Matcher matcher = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(rangeHeader);
        if (!matcher.matches()) {
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return;
        }
        int start = Math.toIntExact(Long.parseLong(matcher.group(1)));
        int end = Math.min(content.length - 1,
                Math.toIntExact(Long.parseLong(matcher.group(2))));
        int length = end - start + 1;
        exchange.getResponseHeaders().set("Content-Range",
                "bytes " + start + "-" + end + "/" + content.length);
        exchange.sendResponseHeaders(206, length);
        try (OutputStream output = exchange.getResponseBody()) {
            int position = start;
            while (position <= end) {
                int count = Math.min(64 * 1024, end - position + 1);
                output.write(content, position, count);
                if (countThisRequest) responseBytes.addAndGet(count);
                output.flush();
                position += count;
                if (start == 0 && length > 1) {
                    try {
                        Thread.sleep(4);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
            // A victim request is intentionally closed early after its tail is reassigned.
        }
    }

    private static void serveRangeFileWithOneStall(HttpExchange exchange, byte[] content,
                                                    AtomicBoolean stallFirstRange,
                                                    AtomicInteger fullRangeRequests)
            throws IOException {
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"stalled.bin\"");
        if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(content.length));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        Matcher matcher = Pattern.compile("bytes=(\\d+)-(\\d+)").matcher(rangeHeader);
        if (!matcher.matches()) {
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return;
        }
        int start = Math.toIntExact(Long.parseLong(matcher.group(1)));
        int end = Math.min(content.length - 1,
                Math.toIntExact(Long.parseLong(matcher.group(2))));
        int length = end - start + 1;
        boolean shouldStall = length > 1 && stallFirstRange.compareAndSet(true, false);
        if (length > 1) fullRangeRequests.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Range",
                "bytes " + start + "-" + end + "/" + content.length);
        exchange.sendResponseHeaders(206, length);
        try (OutputStream output = exchange.getResponseBody()) {
            int position = start;
            if (shouldStall) {
                int first = Math.min(64 * 1024, length);
                output.write(content, position, first);
                output.flush();
                position += first;
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            while (position <= end) {
                int count = Math.min(64 * 1024, end - position + 1);
                output.write(content, position, count);
                output.flush();
                position += count;
            }
        } catch (IOException ignored) {
            // The downloader deliberately closes the stalled response and resumes by range.
        }
    }
}
