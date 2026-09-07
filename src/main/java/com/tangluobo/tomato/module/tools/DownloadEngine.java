package com.tangluobo.tomato.module.tools;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Common lifecycle and progress contract for the embedded HTTP, BitTorrent and ED2K engines. */
interface DownloadEngine {

    Result download(String source, Path destinationDirectory, int parallelism,
                    Listener listener, Path resumeTarget) throws Exception;

    void cancel();

    boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException;

    record Metadata(String fileName, long totalBytes, Path target, String mode) {
    }

    record Progress(long downloadedBytes, long totalBytes, int activeWorkers, String detail,
                    long transferredBytes) {

        Progress(long downloadedBytes, long totalBytes, int activeWorkers, String detail) {
            this(downloadedBytes, totalBytes, activeWorkers, detail, downloadedBytes);
        }
    }

    record Result(Path file, long downloadedBytes, String detail) {
    }

    interface Listener {
        Listener NONE = new Listener() { };

        default void onStatus(String message) { }

        default void onPrepared(Metadata metadata) { }

        default void onProgress(Progress progress) { }
    }
}
