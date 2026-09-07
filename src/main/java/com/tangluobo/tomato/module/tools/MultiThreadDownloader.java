package com.tangluobo.tomato.module.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP/HTTPS range downloader with dynamic tail splitting.
 *
 * <p>Each worker writes directly to its absolute file offset. When a worker becomes idle it
 * takes the latter half of the active segment with the largest remaining byte count, provided
 * that the remainder is both larger than 2 MiB and larger than 10% of that segment's original
 * assignment. The shared file is forced to disk while the victim segment is locked before its
 * end offset is shortened, so reassignment never overlaps uncommitted data. A coordinator also
 * watches active range streams and refreshes stalled or disproportionately slow connections
 * without discarding bytes that were already written.</p>
 */
public final class MultiThreadDownloader {

    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 32;
    public static final int DEFAULT_THREADS = 8;

    static final long MIN_REBALANCE_REMAINING = 2L * 1024 * 1024;
    private static final long MIN_INITIAL_SEGMENT = 1024L * 1024;
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;
    private static final long CHECKPOINT_INTERVAL_NANOS = 1_000_000_000L;
    private static final Duration DEFAULT_STALLED_CONNECTION_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DEFAULT_SPEED_SAMPLE_WINDOW = Duration.ofSeconds(4);
    private static final long MIN_FAST_CONNECTION_RATE = 512L * 1024L;
    private static final int SLOW_CONNECTION_RATIO = 4;
    private static final int MAX_CONNECTION_RESTARTS = 3;
    private static final long WATCHDOG_INTERVAL_MILLIS = 250L;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_RETRIES = 3;
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+|\\*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*=UTF-8''([^;]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME = Pattern.compile("filename=\\\"?([^\\\";]+)\\\"?",
            Pattern.CASE_INSENSITIVE);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Set<InputStream> openStreams = ConcurrentHashMap.newKeySet();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong lastProgressNanos = new AtomicLong();
    private final AtomicLong lastCheckpointNanos = new AtomicLong();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger rebalanceCount = new AtomicInteger();
    private final AtomicInteger connectionRestartCount = new AtomicInteger();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Object rebalanceLock = new Object();
    private final Object checkpointLock = new Object();
    private final HttpClient client;
    private final long stalledConnectionTimeoutNanos;
    private final long speedSampleWindowNanos;
    private volatile Thread coordinatorThread;
    private volatile DownloadCheckpoint checkpoint;
    private volatile ExecutorService workerPool;
    private volatile Listener listener = Listener.NONE;
    private volatile long totalBytes = -1L;

    public MultiThreadDownloader() {
        this(DEFAULT_STALLED_CONNECTION_TIMEOUT, DEFAULT_SPEED_SAMPLE_WINDOW);
    }

    MultiThreadDownloader(Duration stalledConnectionTimeout, Duration speedSampleWindow) {
        if (stalledConnectionTimeout == null || stalledConnectionTimeout.isZero()
                || stalledConnectionTimeout.isNegative()) {
            throw new IllegalArgumentException("慢连接超时时间必须大于 0");
        }
        if (speedSampleWindow == null || speedSampleWindow.isZero()
                || speedSampleWindow.isNegative()) {
            throw new IllegalArgumentException("测速周期必须大于 0");
        }
        stalledConnectionTimeoutNanos = stalledConnectionTimeout.toNanos();
        speedSampleWindowNanos = speedSampleWindow.toNanos();
        client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                // Independent HTTP/1.1 range connections avoid a throttled HTTP/2 connection
                // dragging every worker down together.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Result download(String url, Path destinationDirectory, int requestedThreads,
                           Listener listener) throws Exception {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("请输入下载地址");
        return download(URI.create(url.trim()), destinationDirectory, requestedThreads, listener, null);
    }

    public Result download(String url, Path destinationDirectory, int requestedThreads,
                           Listener listener, Path resumeTarget) throws Exception {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("请输入下载地址");
        return download(URI.create(url.trim()), destinationDirectory, requestedThreads, listener,
                resumeTarget);
    }

    public Result download(URI uri, Path destinationDirectory, int requestedThreads,
                           Listener listener) throws Exception {
        return download(uri, destinationDirectory, requestedThreads, listener, null);
    }

    public Result download(URI uri, Path destinationDirectory, int requestedThreads,
                           Listener listener, Path resumeTarget) throws Exception {
        Thread currentThread = Thread.currentThread();
        coordinatorThread = currentThread;
        try {
            return executeDownload(uri, destinationDirectory, requestedThreads, listener, resumeTarget);
        } finally {
            if (coordinatorThread == currentThread) coordinatorThread = null;
            stopped.countDown();
        }
    }

    private Result executeDownload(URI uri, Path destinationDirectory, int requestedThreads,
                                   Listener listener, Path resumeTarget) throws Exception {
        if (uri == null || uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("仅支持 HTTP/HTTPS 下载地址");
        }
        if (destinationDirectory == null) throw new IllegalArgumentException("请选择保存目录");
        if (cancelled.get()) throw new CancellationException("下载已取消");

        this.listener = listener != null ? listener : Listener.NONE;
        Files.createDirectories(destinationDirectory);
        if (!Files.isDirectory(destinationDirectory) || !Files.isWritable(destinationDirectory)) {
            throw new IOException("保存目录不可写: " + destinationDirectory);
        }

        Metadata metadata = inspect(uri);
        totalBytes = metadata.contentLength();
        int threadLimit = clampThreads(requestedThreads);
        DownloadCheckpoint currentCheckpoint = prepareCheckpoint(destinationDirectory,
                metadata.fileName(), uri, metadata.contentLength(), resumeTarget);
        checkpoint = currentCheckpoint;
        Path target = currentCheckpoint.target();
        Path partial = currentCheckpoint.partial();
        long restoredBytes = currentCheckpoint.completed().coveredBytes();
        downloadedBytes.set(restoredBytes);
        long remainingBytes = metadata.contentLength() > 0
                ? Math.max(0L, metadata.contentLength() - restoredBytes) : metadata.contentLength();
        int actualThreads = selectActualThreadCount(metadata, threadLimit, remainingBytes);

        try (FileChannel channel = currentCheckpoint.channel()) {
            this.listener.onPrepared(metadata, target, actualThreads);
            if (restoredBytes > 0 && metadata.rangeSupported()) {
                this.listener.onStatus("已读取下载记录，继续下载剩余 "
                        + (metadata.contentLength() - restoredBytes) + " 字节");
            }
            reportProgress(true);
            if (metadata.rangeSupported() && metadata.contentLength() > 0) {
                List<Range> missing = currentCheckpoint.completed()
                        .missingRanges(metadata.contentLength());
                if (!missing.isEmpty()) {
                    downloadRanges(metadata.effectiveUri(), missing, actualThreads, channel);
                }
            } else {
                if (restoredBytes > 0) {
                    this.listener.onStatus("服务器不支持断点续传，正在重新下载");
                    channel.truncate(0L);
                    currentCheckpoint.completed().clear();
                    downloadedBytes.set(0L);
                }
                downloadSingle(metadata.effectiveUri(), channel);
            }
            checkCancelled();
            channel.force(true);
            persistCheckpoint(currentCheckpoint, channel);
        } catch (Exception ex) {
            shutdownWorkers();
            closeOpenStreams();
            persistCheckpointAfterFailure(currentCheckpoint);
            if (cancelled.get()) throw new CancellationException("下载已取消");
            throw ex;
        } finally {
            shutdownWorkers();
            closeOpenStreams();
        }

        moveCompletedFile(partial, target);
        deleteCheckpointRecord(currentCheckpoint.recordFile());
        checkpoint = null;
        reportProgress(true);
        Result result = new Result(target, downloadedBytes.get(), metadata.rangeSupported(),
                actualThreads, rebalanceCount.get(), connectionRestartCount.get());
        this.listener.onCompleted(result);
        return result;
    }

    public void cancel() {
        cancelled.set(true);
        closeOpenStreams();
        ExecutorService pool = workerPool;
        if (pool != null) pool.shutdownNow();
        Thread coordinator = coordinatorThread;
        if (coordinator != null) coordinator.interrupt();
    }

    public static void discardDownloadRecord(Path target) {
        if (target == null) return;
        try {
            Files.deleteIfExists(partialPath(target));
        } catch (IOException ignored) {
        }
        try {
            Path record = recordPath(target);
            Files.deleteIfExists(record);
            Files.deleteIfExists(record.resolveSibling(record.getFileName() + ".tmp"));
        } catch (IOException ignored) {
        }
    }

    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }

    private Metadata inspect(URI requestedUri) throws Exception {
        listener.onStatus("正在获取文件信息...");
        URI effectiveUri = requestedUri;
        long length = -1L;
        String disposition = null;

        try {
            HttpRequest headRequest = requestBuilder(requestedUri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> head = client.send(headRequest, HttpResponse.BodyHandlers.discarding());
            if (head.statusCode() >= 200 && head.statusCode() < 400) {
                effectiveUri = head.uri();
                length = headerLong(head, "Content-Length", -1L);
                disposition = head.headers().firstValue("Content-Disposition").orElse(null);
            }
        } catch (IOException ignored) {
            // A number of download endpoints reject HEAD but still support GET and byte ranges.
        }

        if (length == 0L) {
            return new Metadata(effectiveUri, resolveFileName(effectiveUri, disposition), 0L, false);
        }

        HttpRequest probe = requestBuilder(effectiveUri)
                .header("Range", "bytes=0-0")
                .GET().build();
        HttpResponse<InputStream> response = client.send(probe, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("服务器返回 HTTP " + status);
            }
            effectiveUri = response.uri();
            if (disposition == null) {
                disposition = response.headers().firstValue("Content-Disposition").orElse(null);
            }
            boolean rangeSupported = status == 206;
            if (rangeSupported) {
                long rangeTotal = parseContentRangeTotal(
                        response.headers().firstValue("Content-Range").orElse(null));
                if (rangeTotal >= 0) length = rangeTotal;
            } else if (length < 0) {
                length = headerLong(response, "Content-Length", -1L);
            }
            return new Metadata(effectiveUri, resolveFileName(effectiveUri, disposition),
                    length, rangeSupported && length > 0);
        }
    }

    private void downloadSingle(URI uri, FileChannel channel) throws Exception {
        listener.onStatus("服务器不支持分段下载，已切换为单线程");
        HttpRequest request = requestBuilder(uri).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("下载失败，服务器返回 HTTP " + response.statusCode());
        }
        InputStream input = response.body();
        openStreams.add(input);
        long position = 0L;
        try (input) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                checkCancelled();
                if (count == 0) continue;
                writeAt(channel, buffer, count, position);
                checkpoint.completed().add(position, position + count - 1L);
                position += count;
                downloadedBytes.addAndGet(count);
                maybePersistCheckpoint(channel);
                reportProgress(false);
            }
        } finally {
            openStreams.remove(input);
        }
        if (totalBytes < 0) totalBytes = position;
    }

    private void downloadRanges(URI uri, List<Range> missingRanges, int threads,
                                FileChannel channel) throws Exception {
        listener.onStatus("正在使用 " + threads + " 个线程下载");
        List<Segment> initial = new ArrayList<>();
        for (Range range : splitRangesForWorkers(missingRanges, threads)) {
            initial.add(new Segment(range.startInclusive(), range.endInclusive()));
        }
        Set<Segment> activeSegments = ConcurrentHashMap.newKeySet();
        activeSegments.addAll(initial);
        workerPool = Executors.newFixedThreadPool(Math.min(threads, initial.size()),
                daemonThreadFactory());
        CompletionService<Void> completions = new ExecutorCompletionService<>(workerPool);
        List<Future<?>> futures = new ArrayList<>(initial.size());
        for (Segment segment : initial) {
            futures.add(completions.submit(() -> {
                workerLoop(uri, segment, activeSegments, channel);
                return null;
            }));
        }

        Throwable failure = null;
        int completed = 0;
        while (completed < futures.size()) {
            try {
                Future<Void> future = completions.poll(WATCHDOG_INTERVAL_MILLIS,
                        TimeUnit.MILLISECONDS);
                refreshSlowConnections(activeSegments);
                if (future == null) continue;
                completed++;
                future.get();
            } catch (ExecutionException ex) {
                if (failure == null) failure = ex.getCause();
                cancelRemaining(futures);
                break;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (failure == null) failure = ex;
                cancelRemaining(futures);
                break;
            } catch (CancellationException ex) {
                if (failure == null && !cancelled.get()) failure = ex;
            }
        }
        if (cancelled.get()) throw new CancellationException("下载已取消");
        if (failure != null) {
            if (failure instanceof Exception exception) throw exception;
            throw new IOException("分段下载失败", failure);
        }
    }

    private void workerLoop(URI uri, Segment initialSegment, Set<Segment> activeSegments,
                            FileChannel channel) throws Exception {
        Segment segment = initialSegment;
        while (segment != null) {
            checkCancelled();
            activeWorkers.incrementAndGet();
            try {
                downloadSegmentWithRetries(uri, segment, channel);
            } finally {
                activeWorkers.decrementAndGet();
                activeSegments.remove(segment);
            }
            channel.force(false);
            segment = splitLargestRemaining(activeSegments, channel);
            if (segment != null) activeSegments.add(segment);
        }
    }

    private void downloadSegmentWithRetries(URI uri, Segment segment, FileChannel channel) throws Exception {
        IOException lastFailure = null;
        int failedAttempts = 0;
        while (failedAttempts < MAX_RETRIES) {
            checkCancelled();
            try {
                downloadSegmentOnce(uri, segment, channel);
                segment.clearConnectionRestartRequest();
                return;
            } catch (IOException ex) {
                if (cancelled.get()) throw new CancellationException("下载已取消");
                String restartReason = segment.consumeConnectionRestartRequest();
                if (restartReason != null) {
                    // Resume at nextOffset immediately. An adaptive refresh is not a network
                    // failure and therefore must not consume the ordinary retry allowance.
                    continue;
                }
                failedAttempts++;
                lastFailure = ex;
                if (failedAttempts == MAX_RETRIES) break;
                listener.onStatus("分段连接中断，正在重试 (" + failedAttempts + "/"
                        + MAX_RETRIES + ")");
                try {
                    Thread.sleep(250L * failedAttempts);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("下载已取消");
                }
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("分段下载失败");
    }

    private void downloadSegmentOnce(URI uri, Segment segment, FileChannel channel) throws Exception {
        long requestStart;
        long requestEnd;
        synchronized (segment) {
            requestStart = segment.nextOffset;
            requestEnd = segment.endInclusive;
        }
        if (requestStart > requestEnd) return;

        HttpRequest request = requestBuilder(uri)
                .header("Range", "bytes=" + requestStart + "-" + requestEnd)
                .GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 206) {
            response.body().close();
            throw new IOException("服务器未按范围返回数据（HTTP " + response.statusCode() + "）");
        }
        InputStream input = response.body();
        openStreams.add(input);
        segment.attach(input, System.nanoTime());
        try (input) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                checkCancelled();
                int requested;
                synchronized (segment) {
                    long remaining = segment.endInclusive - segment.nextOffset + 1L;
                    if (remaining <= 0) break;
                    requested = (int) Math.min(buffer.length, remaining);
                }

                // Never hold the segment lock while waiting on the network. This lets an idle
                // worker split a slow segment and lets the watchdog close a stalled stream.
                int count = input.read(buffer, 0, requested);
                if (count < 0) throw new IOException("服务器提前结束分段数据");
                if (count == 0) continue;

                int accepted;
                synchronized (segment) {
                    long remaining = segment.endInclusive - segment.nextOffset + 1L;
                    accepted = (int) Math.min(count, Math.max(0L, remaining));
                    if (accepted == 0) continue;
                    long writeStart = segment.nextOffset;
                    writeAt(channel, buffer, accepted, writeStart);
                    segment.nextOffset += accepted;
                    segment.recordProgress(System.nanoTime());
                    checkpoint.completed().add(writeStart, writeStart + accepted - 1L);
                }
                downloadedBytes.addAndGet(accepted);
                maybePersistCheckpoint(channel);
                reportProgress(false);
            }
        } finally {
            segment.detach(input);
            openStreams.remove(input);
        }
    }

    private void refreshSlowConnections(Set<Segment> activeSegments) {
        long now = System.nanoTime();
        List<SegmentHealth> health = new ArrayList<>();
        for (Segment segment : activeSegments) {
            SegmentHealth sample = segment.observe(now, speedSampleWindowNanos);
            if (sample != null) health.add(sample);
        }

        for (SegmentHealth sample : health) {
            if (sample.remaining() > 0
                    && sample.idleNanos() >= stalledConnectionTimeoutNanos) {
                restartConnection(sample.segment(), "长时间未收到数据");
            }
        }

        long fastest = health.stream()
                .mapToLong(SegmentHealth::bytesPerSecond)
                .max().orElse(-1L);
        if (fastest < MIN_FAST_CONNECTION_RATE) return;
        for (SegmentHealth sample : health) {
            if (sample.remaining() > MIN_REBALANCE_REMAINING
                    && sample.bytesPerSecond() >= 0
                    && sample.bytesPerSecond() * SLOW_CONNECTION_RATIO < fastest) {
                restartConnection(sample.segment(), "速度明显低于其他线程");
            }
        }
    }

    private void restartConnection(Segment segment, String reason) {
        InputStream stream = segment.requestConnectionRestart(reason);
        if (stream == null) return;
        int sequence = connectionRestartCount.incrementAndGet();
        SegmentPosition position = segment.position();
        listener.onStatus("检测到慢连接，正在换线并从已下载位置继续");
        listener.onConnectionRestarted(new ConnectionRestart(position.nextOffset(),
                position.endInclusive(), reason, sequence));
        reportProgress(true);
        try {
            stream.close();
        } catch (IOException ignored) {
            // The reader will either observe this close or retry after its own I/O failure.
        }
    }

    private Segment splitLargestRemaining(Set<Segment> activeSegments, FileChannel channel)
            throws IOException {
        synchronized (rebalanceLock) {
            Segment largest = null;
            long largestRemaining = 0L;
            for (Segment candidate : activeSegments) {
                long remaining = candidate.remaining();
                if (candidate.canSplit(remaining) && remaining > largestRemaining) {
                    largest = candidate;
                    largestRemaining = remaining;
                }
            }
            if (largest == null) return null;

            synchronized (largest) {
                long remaining = largest.endInclusive - largest.nextOffset + 1L;
                if (!largest.canSplit(remaining)) return null;
                // Flush every absolute-position write before the victim range is shortened.
                channel.force(false);
                long oldEnd = largest.endInclusive;
                long stolenStart = largest.nextOffset + remaining / 2L;
                largest.endInclusive = stolenStart - 1L;
                Segment stolen = new Segment(stolenStart, oldEnd);
                int count = rebalanceCount.incrementAndGet();
                listener.onRebalanced(new Rebalance(stolenStart, oldEnd, remaining, count));
                reportProgress(true);
                return stolen;
            }
        }
    }

    private void reportProgress(boolean force) {
        long now = System.nanoTime();
        long previous = lastProgressNanos.get();
        if (!force && previous != 0L && now - previous < PROGRESS_INTERVAL_NANOS) return;
        if (force || lastProgressNanos.compareAndSet(previous, now)) {
            listener.onProgress(new Progress(downloadedBytes.get(), totalBytes,
                    activeWorkers.get(), rebalanceCount.get(), connectionRestartCount.get()));
        }
    }

    private void checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("下载已取消");
        }
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(30))
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Tomata-Downloader/1.0");
    }

    private static void writeAt(FileChannel channel, byte[] bytes, int length, long position)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length);
        long writePosition = position;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, writePosition);
            if (written <= 0) throw new IOException("无法写入下载文件");
            writePosition += written;
        }
    }

    private static int selectActualThreadCount(Metadata metadata, int requested,
                                               long remainingBytes) {
        if (!metadata.rangeSupported() || metadata.contentLength() <= 0) return 1;
        long usefulSegments = Math.max(1L,
                (remainingBytes + MIN_INITIAL_SEGMENT - 1L) / MIN_INITIAL_SEGMENT);
        return (int) Math.min(requested, usefulSegments);
    }

    static int clampThreads(int threads) {
        return Math.max(MIN_THREADS, Math.min(MAX_THREADS, threads));
    }

    static List<Range> partitionRanges(long size, int threads) {
        if (size <= 0) return List.of();
        int count = Math.max(1, Math.min(threads, (int) Math.min(Integer.MAX_VALUE, size)));
        long base = size / count;
        long extra = size % count;
        List<Range> ranges = new ArrayList<>(count);
        long start = 0L;
        for (int i = 0; i < count; i++) {
            long length = base + (i < extra ? 1L : 0L);
            ranges.add(new Range(start, start + length - 1L));
            start += length;
        }
        return List.copyOf(ranges);
    }

    private static List<Range> splitRangesForWorkers(List<Range> missingRanges, int threads) {
        List<Range> ranges = new ArrayList<>(missingRanges);
        while (ranges.size() < threads) {
            int largestIndex = -1;
            long largestLength = 1L;
            for (int index = 0; index < ranges.size(); index++) {
                long length = ranges.get(index).length();
                if (length > largestLength) {
                    largestIndex = index;
                    largestLength = length;
                }
            }
            if (largestIndex < 0) break;
            Range largest = ranges.remove(largestIndex);
            long firstEnd = largest.startInclusive() + largest.length() / 2L - 1L;
            ranges.add(new Range(largest.startInclusive(), firstEnd));
            ranges.add(new Range(firstEnd + 1L, largest.endInclusive()));
        }
        ranges.sort((left, right) -> Long.compare(left.startInclusive(), right.startInclusive()));
        return List.copyOf(ranges);
    }

    private static long headerLong(HttpResponse<?> response, String name, long fallback) {
        try {
            return response.headers().firstValue(name).map(Long::parseLong).orElse(fallback);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseContentRangeTotal(String value) {
        if (value == null) return -1L;
        Matcher matcher = CONTENT_RANGE_TOTAL.matcher(value.trim());
        if (!matcher.matches() || matcher.group(1).equals("*")) return -1L;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String resolveFileName(URI uri, String contentDisposition) {
        String candidate = null;
        if (contentDisposition != null) {
            Matcher encoded = FILENAME_STAR.matcher(contentDisposition);
            if (encoded.find()) {
                try {
                    candidate = URLDecoder.decode(encoded.group(1).trim(), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (candidate == null) {
                Matcher plain = FILENAME.matcher(contentDisposition);
                if (plain.find()) candidate = plain.group(1).trim();
            }
        }
        if (candidate == null || candidate.isBlank()) {
            String path = uri.getPath();
            int slash = path != null ? path.lastIndexOf('/') : -1;
            candidate = path != null && slash + 1 < path.length() ? path.substring(slash + 1) : "";
        }
        if (candidate == null || candidate.isBlank()) candidate = "download.bin";
        candidate = candidate.replaceAll("[\\x00-\\x1f<>:\\\"/\\\\|?*]", "_").trim();
        while (candidate.endsWith(".") || candidate.endsWith(" ")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate.isBlank() ? "download.bin" : candidate;
    }

    private DownloadCheckpoint prepareCheckpoint(Path directory, String fileName, URI source,
                                                 long contentLength, Path resumeTarget)
            throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (resumeTarget != null) {
            Path target = resumeTarget.toAbsolutePath().normalize();
            if (normalizedDirectory.equals(target.getParent()) && !Files.exists(target)) {
                Path partial = partialPath(target);
                Path recordFile = recordPath(target);
                RangeTracker restored = readCheckpoint(recordFile, source, contentLength, partial);
                if (restored != null) {
                    FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE);
                    return new DownloadCheckpoint(source, contentLength, target, partial,
                            recordFile, channel, restored);
                }
            }
        }

        ReservedTarget reserved = reserveTarget(normalizedDirectory, fileName);
        DownloadCheckpoint created = new DownloadCheckpoint(source, contentLength,
                reserved.target(), reserved.partial(), recordPath(reserved.target()),
                reserved.channel(), new RangeTracker());
        try {
            persistCheckpoint(created, created.channel());
            return created;
        } catch (IOException ex) {
            created.channel().close();
            Files.deleteIfExists(created.partial());
            throw ex;
        }
    }

    private void maybePersistCheckpoint(FileChannel channel) {
        DownloadCheckpoint current = checkpoint;
        if (current == null) return;
        long now = System.nanoTime();
        long previous = lastCheckpointNanos.get();
        if (previous != 0L && now - previous < CHECKPOINT_INTERVAL_NANOS) return;
        if (!lastCheckpointNanos.compareAndSet(previous, now)) return;
        try {
            persistCheckpoint(current, channel);
        } catch (IOException ex) {
            listener.onStatus("下载记录保存失败，将在任务结束时重试: " + ex.getMessage());
        }
    }

    private void persistCheckpointAfterFailure(DownloadCheckpoint current) {
        boolean interrupted = Thread.interrupted();
        try (FileChannel channel = FileChannel.open(current.partial(), StandardOpenOption.WRITE)) {
            persistCheckpoint(current, channel);
        } catch (IOException ignored) {
            // Keep the last successfully persisted record; unrecorded bytes are safely downloaded again.
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void persistCheckpoint(DownloadCheckpoint current, FileChannel channel)
            throws IOException {
        synchronized (checkpointLock) {
            List<Range> ranges = current.completed().snapshot();
            channel.force(false);
            Properties properties = new Properties();
            properties.setProperty("version", "1");
            properties.setProperty("source", current.source().toString());
            properties.setProperty("length", Long.toString(current.contentLength()));
            properties.setProperty("ranges", encodeRanges(ranges));
            Path temporary = current.recordFile().resolveSibling(
                    current.recordFile().getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                properties.store(output, "Tomata downloader resume record");
            }
            try {
                Files.move(temporary, current.recordFile(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, current.recordFile(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static RangeTracker readCheckpoint(Path recordFile, URI source, long contentLength,
                                               Path partial) {
        if (!Files.isRegularFile(recordFile) || !Files.isRegularFile(partial)) return null;
        try (InputStream input = Files.newInputStream(recordFile)) {
            Properties properties = new Properties();
            properties.load(input);
            if (!"1".equals(properties.getProperty("version"))) return null;
            if (!source.toString().equals(properties.getProperty("source"))) return null;
            if (Long.parseLong(properties.getProperty("length", "-1")) != contentLength) return null;
            if (contentLength <= 0) return new RangeTracker();
            RangeTracker tracker = RangeTracker.decode(
                    properties.getProperty("ranges", ""), contentLength);
            long partialSize = Files.size(partial);
            for (Range range : tracker.snapshot()) {
                if (range.endInclusive() >= partialSize) return null;
            }
            return tracker;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String encodeRanges(List<Range> ranges) {
        StringBuilder encoded = new StringBuilder();
        for (Range range : ranges) {
            if (!encoded.isEmpty()) encoded.append(',');
            encoded.append(range.startInclusive()).append('-').append(range.endInclusive());
        }
        return encoded.toString();
    }

    private static Path partialPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static Path recordPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".part.meta");
    }

    private static void deleteCheckpointRecord(Path recordFile) {
        try {
            Files.deleteIfExists(recordFile);
            Files.deleteIfExists(recordFile.resolveSibling(recordFile.getFileName() + ".tmp"));
        } catch (IOException ignored) {
            recordFile.toFile().deleteOnExit();
        }
    }

    private static ReservedTarget reserveTarget(Path directory, String fileName) throws IOException {
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String suffix = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 0; index < Integer.MAX_VALUE; index++) {
            String candidate = index == 0 ? fileName : stem + " (" + index + ")" + suffix;
            Path target = directory.resolve(candidate);
            if (Files.exists(target)) continue;
            Path partial = partialPath(target);
            try {
                FileChannel channel = FileChannel.open(partial, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                if (Files.exists(target)) {
                    channel.close();
                    Files.deleteIfExists(partial);
                    continue;
                }
                return new ReservedTarget(target, partial, channel);
            } catch (FileAlreadyExistsException ignored) {
                // Another task already reserved this name. Try the next numbered candidate.
            }
        }
        throw new IOException("无法生成可用的下载文件名");
    }

    private static void moveCompletedFile(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target);
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "Downloader-Part-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record ReservedTarget(Path target, Path partial, FileChannel channel) {
    }

    private record DownloadCheckpoint(URI source, long contentLength, Path target, Path partial,
                                      Path recordFile, FileChannel channel,
                                      RangeTracker completed) {
    }

    private void cancelRemaining(List<Future<?>> futures) {
        closeOpenStreams();
        for (Future<?> future : futures) future.cancel(true);
    }

    private void shutdownWorkers() {
        ExecutorService pool = workerPool;
        workerPool = null;
        if (pool == null) return;
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeOpenStreams() {
        for (InputStream stream : openStreams) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        openStreams.clear();
    }

    static record Range(long startInclusive, long endInclusive) {
        long length() {
            return endInclusive - startInclusive + 1L;
        }
    }

    private static final class RangeTracker {
        private final TreeMap<Long, Long> ranges = new TreeMap<>();

        private synchronized void add(long start, long end) {
            if (start < 0 || end < start) throw new IllegalArgumentException("无效的下载区间");
            Map.Entry<Long, Long> floor = ranges.floorEntry(start);
            if (floor != null && floor.getValue() >= start - 1L) {
                start = floor.getKey();
                end = Math.max(end, floor.getValue());
                ranges.remove(floor.getKey());
            }
            Map.Entry<Long, Long> next = ranges.ceilingEntry(start);
            while (next != null && next.getKey() <= end + 1L) {
                end = Math.max(end, next.getValue());
                ranges.remove(next.getKey());
                next = ranges.ceilingEntry(start);
            }
            ranges.put(start, end);
        }

        private synchronized long coveredBytes() {
            long total = 0L;
            for (Map.Entry<Long, Long> range : ranges.entrySet()) {
                total += range.getValue() - range.getKey() + 1L;
            }
            return total;
        }

        private synchronized List<Range> snapshot() {
            List<Range> snapshot = new ArrayList<>(ranges.size());
            ranges.forEach((start, end) -> snapshot.add(new Range(start, end)));
            return List.copyOf(snapshot);
        }

        private synchronized List<Range> missingRanges(long totalLength) {
            if (totalLength <= 0) return List.of();
            List<Range> missing = new ArrayList<>();
            long position = 0L;
            for (Map.Entry<Long, Long> completed : ranges.entrySet()) {
                if (position < completed.getKey()) {
                    missing.add(new Range(position, completed.getKey() - 1L));
                }
                position = Math.max(position, completed.getValue() + 1L);
            }
            if (position < totalLength) missing.add(new Range(position, totalLength - 1L));
            return List.copyOf(missing);
        }

        private synchronized void clear() {
            ranges.clear();
        }

        private static RangeTracker decode(String encoded, long totalLength) {
            RangeTracker tracker = new RangeTracker();
            if (encoded == null || encoded.isBlank()) return tracker;
            for (String item : encoded.split(",")) {
                int separator = item.indexOf('-');
                if (separator <= 0 || separator == item.length() - 1) {
                    throw new IllegalArgumentException("下载记录区间格式错误");
                }
                long start = Long.parseLong(item.substring(0, separator));
                long end = Long.parseLong(item.substring(separator + 1));
                if (start < 0 || end < start || end >= totalLength) {
                    throw new IllegalArgumentException("下载记录区间超出文件大小");
                }
                tracker.add(start, end);
            }
            return tracker;
        }
    }

    private static final class Segment {
        private final long assignedLength;
        private long nextOffset;
        private long endInclusive;
        private InputStream activeStream;
        private long lastReadNanos;
        private long sampleStartedNanos;
        private long sampleStartOffset;
        private long sampledBytesPerSecond = -1L;
        private long sampledAtNanos;
        private int connectionRestarts;
        private String connectionRestartReason;

        private Segment(long startInclusive, long endInclusive) {
            this.nextOffset = startInclusive;
            this.endInclusive = endInclusive;
            this.assignedLength = endInclusive - startInclusive + 1L;
        }

        private synchronized long remaining() {
            return Math.max(0L, endInclusive - nextOffset + 1L);
        }

        private synchronized boolean canSplit(long remaining) {
            long tenPercent = Math.max(1L, (assignedLength + 9L) / 10L);
            return remaining > MIN_REBALANCE_REMAINING && remaining > tenPercent;
        }

        private synchronized void attach(InputStream stream, long now) {
            activeStream = stream;
            lastReadNanos = now;
            sampleStartedNanos = now;
            sampleStartOffset = nextOffset;
            sampledBytesPerSecond = -1L;
            sampledAtNanos = 0L;
        }

        private synchronized void detach(InputStream stream) {
            if (activeStream == stream) activeStream = null;
        }

        private synchronized void recordProgress(long now) {
            lastReadNanos = now;
        }

        private synchronized SegmentHealth observe(long now, long sampleWindowNanos) {
            if (activeStream == null) return null;
            if (now - sampleStartedNanos >= sampleWindowNanos) {
                long elapsed = Math.max(1L, now - sampleStartedNanos);
                long bytes = Math.max(0L, nextOffset - sampleStartOffset);
                sampledBytesPerSecond = (long) Math.min(Long.MAX_VALUE,
                        bytes * (1_000_000_000.0d / elapsed));
                sampledAtNanos = now;
                sampleStartedNanos = now;
                sampleStartOffset = nextOffset;
            }
            long recentRate = sampledAtNanos != 0L
                    && now - sampledAtNanos <= sampleWindowNanos * 2L
                    ? sampledBytesPerSecond : -1L;
            return new SegmentHealth(this,
                    Math.max(0L, endInclusive - nextOffset + 1L),
                    Math.max(0L, now - lastReadNanos), recentRate);
        }

        private synchronized InputStream requestConnectionRestart(String reason) {
            if (activeStream == null || connectionRestartReason != null
                    || connectionRestarts >= MAX_CONNECTION_RESTARTS) return null;
            connectionRestarts++;
            connectionRestartReason = reason;
            return activeStream;
        }

        private synchronized String consumeConnectionRestartRequest() {
            String reason = connectionRestartReason;
            connectionRestartReason = null;
            return reason;
        }

        private synchronized void clearConnectionRestartRequest() {
            connectionRestartReason = null;
        }

        private synchronized SegmentPosition position() {
            return new SegmentPosition(nextOffset, endInclusive);
        }
    }

    private record SegmentHealth(Segment segment, long remaining, long idleNanos,
                                 long bytesPerSecond) {}

    private record SegmentPosition(long nextOffset, long endInclusive) {}

    public record Metadata(URI effectiveUri, String fileName, long contentLength,
                           boolean rangeSupported) {}

    public record Progress(long downloadedBytes, long totalBytes, int activeThreads,
                           int rebalanceCount, int connectionRestartCount) {}

    public record Rebalance(long startInclusive, long endInclusive, long victimRemaining,
                            int sequence) {
        public long reassignedBytes() {
            return endInclusive - startInclusive + 1L;
        }
    }

    public record ConnectionRestart(long nextOffset, long endInclusive, String reason,
                                    int sequence) {}

    public record Result(Path file, long downloadedBytes, boolean rangeSupported,
                         int threadCount, int rebalanceCount, int connectionRestartCount) {}

    public interface Listener {
        Listener NONE = new Listener() {};

        default void onStatus(String message) {}
        default void onPrepared(Metadata metadata, Path target, int actualThreads) {}
        default void onProgress(Progress progress) {}
        default void onRebalanced(Rebalance rebalance) {}
        default void onConnectionRestarted(ConnectionRestart restart) {}
        default void onCompleted(Result result) {}
    }
}
