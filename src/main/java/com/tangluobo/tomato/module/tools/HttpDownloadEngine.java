package com.tangluobo.tomato.module.tools;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Adapts the HTTP range downloader to the common embedded download engine API. */
final class HttpDownloadEngine implements DownloadEngine {

    private final MultiThreadDownloader delegate = new MultiThreadDownloader();

    @Override
    public Result download(String source, Path destinationDirectory, int parallelism,
                           Listener listener, Path resumeTarget) throws Exception {
        Listener sink = listener == null ? Listener.NONE : listener;
        MultiThreadDownloader.Result result = delegate.download(source, destinationDirectory,
                parallelism, new MultiThreadDownloader.Listener() {
                    @Override
                    public void onStatus(String message) {
                        sink.onStatus(message);
                    }

                    @Override
                    public void onPrepared(MultiThreadDownloader.Metadata metadata, Path target,
                                           int actualThreads) {
                        sink.onPrepared(new Metadata(target.getFileName().toString(),
                                metadata.contentLength(), target,
                                metadata.rangeSupported() ? actualThreads + "线程" : "单线程"));
                    }

                    @Override
                    public void onProgress(MultiThreadDownloader.Progress progress) {
                        sink.onProgress(new Progress(progress.downloadedBytes(),
                                progress.totalBytes(), progress.activeThreads(),
                                "分配" + progress.rebalanceCount() + " · 换线"
                                        + progress.connectionRestartCount()));
                    }
                }, resumeTarget);
        return new Result(result.file(), result.downloadedBytes(),
                result.threadCount() + "线程 · 分配" + result.rebalanceCount()
                        + " · 换线" + result.connectionRestartCount());
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }

    @Override
    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitStopped(timeout, unit);
    }

    static void discardRecord(Path target) {
        MultiThreadDownloader.discardDownloadRecord(target);
    }
}
