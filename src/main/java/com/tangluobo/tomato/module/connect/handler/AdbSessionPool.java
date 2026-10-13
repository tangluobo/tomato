package com.tangluobo.tomato.module.connect.handler;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shares one ADB screen service per device endpoint.
 *
 * <p>Views share one encoder. Encoder-free screenshots remain available for firmware whose
 * hardware encoder leaks DMA buffers. Reopening a device reuses the session.</p>
 */
final class AdbSessionPool {
    private static final long IDLE_CLOSE_DELAY_SECONDS = 30;
    private static final Map<SessionKey, SharedSession> SESSIONS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService IDLE_CLOSER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ADB-session-idle-closer");
                thread.setDaemon(true);
                return thread;
            });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(AdbSessionPool::shutdownAll,
                "ADB-session-pool-shutdown"));
    }

    private AdbSessionPool() {
    }

    static Lease acquire(String host, int port, String configuredExecutable, Listener listener) {
        Objects.requireNonNull(listener, "listener");
        int effectivePort = port > 0 ? port : 5555;
        SessionKey key = new SessionKey(AdbClient.endpoint(host, effectivePort),
                AdbClient.resolveExecutable(configuredExecutable));
        SharedSession session = SESSIONS.computeIfAbsent(key, SharedSession::new);
        return session.acquire(listener);
    }

    static int sessionCount() {
        return SESSIONS.size();
    }

    private static void shutdownAll() {
        SESSIONS.values().forEach(SharedSession::shutdown);
        SESSIONS.clear();
        IDLE_CLOSER.shutdownNow();
    }

    interface Listener {
        void onStatus(boolean connected, String message);

        void onFrame(byte[] png, String endpoint);

        default void onVideoFrame(AdbClient.VideoFrame frame, AdbClient.ScreenSize displaySize,
                                  String endpoint) { }
    }

    static final class Lease implements AutoCloseable {
        private static final AtomicLong POINTER_IDS = new AtomicLong();
        private final long pointerId = POINTER_IDS.incrementAndGet();
        private final SharedSession session;
        private final Listener listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private AdbClient gestureClient;
        private int touchX, touchY, touchWidth, touchHeight;

        private Lease(SharedSession session, Listener listener) {
            this.session = session;
            this.listener = listener;
        }

        boolean isConnected() {
            return !closed.get() && session.isConnected();
        }

        boolean hasDirectControl() {
            AdbClient active = session.client;
            return !closed.get() && active != null && active.hasDirectControl();
        }

        String controlDescription() {
            AdbClient active = session.client;
            return active == null ? "" : active.controlDescription();
        }

        synchronized void touch(int action, int x, int y, int width, int height) throws IOException {
            requireOpen();
            if (action == 0) gestureClient = session.activeClient();
            AdbClient active = gestureClient;
            if (active == null) return;
            touchX = x;
            touchY = y;
            touchWidth = width;
            touchHeight = height;
            active.touch(action, pointerId, x, y, width, height);
            if (action == 1) gestureClient = null;
        }

        void scroll(int x, int y, int width, int height, double horizontal, double vertical) throws IOException {
            requireOpen().activeClient().scroll(x, y, width, height, horizontal, vertical);
        }

        private synchronized void cancelTouch() {
            AdbClient active = gestureClient;
            gestureClient = null;
            if (active != null) {
                try {
                    active.touch(1, pointerId, touchX, touchY, touchWidth, touchHeight);
                } catch (IOException ignored) { }
            }
        }

        void reconnect() {
            if (!closed.get()) session.reconnect();
        }

        void setVideoEnabled(boolean enabled) {
            if (!closed.get()) {
                session.videoEnabled = enabled;
                session.reconnect();
            }
        }

        void setVideoQuality(int maxEdge, int bitRate) {
            if (!closed.get()) {
                session.videoMaxEdge = maxEdge;
                session.videoBitRate = bitRate;
                session.videoEnabled = true;
                session.reconnect();
            }
        }

        void tap(int x, int y) throws IOException {
            requireOpen().tap(x, y);
        }

        void swipe(int startX, int startY, int endX, int endY, int durationMillis) throws IOException {
            requireOpen().swipe(startX, startY, endX, endY, durationMillis);
        }

        void keyEvent(int keyCode) throws IOException {
            requireOpen().keyEvent(keyCode);
        }

        void text(String value) throws IOException {
            requireOpen().text(value);
        }

        private SharedSession requireOpen() throws IOException {
            if (closed.get()) throw new IOException("ADB 共享会话已关闭");
            return session;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                Thread.ofVirtual().name("ADB-pointer-release").start(this::cancelTouch);
                session.release(listener);
            }
        }
    }

    private record SessionKey(String endpoint, String executable) {
    }

    private static final class SharedSession {
        private final SessionKey key;
        private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
        private final ExecutorService worker;
        private final AtomicBoolean workerRunning = new AtomicBoolean();
        private final Object resourceLock = new Object();

        private volatile AdbClient client;
        private volatile AdbClient.ScreenStream stream;
        private volatile AdbClient.VideoStream videoStream;
        private volatile boolean videoEnabled = true;
        private volatile int videoMaxEdge = 1920;
        private volatile int videoBitRate = 8_000_000;
        private volatile boolean connected;
        private volatile boolean closed;
        private volatile boolean stopRequested;
        private volatile String statusMessage;
        private volatile byte[] lastFrame;
        private volatile AdbClient.VideoFrame lastVideoFrame;
        private volatile AdbClient.ScreenSize lastDisplaySize;
        private ScheduledFuture<?> idleClose;

        private SharedSession(SessionKey key) {
            this.key = key;
            this.statusMessage = "正在连接 " + key.endpoint() + "…";
            this.worker = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ADB-shared-screen-" + key.endpoint());
                thread.setDaemon(true);
                return thread;
            });
        }

        private Lease acquire(Listener listener) {
            synchronized (resourceLock) {
                if (closed) throw new IllegalStateException("ADB 共享会话已关闭");
                stopRequested = false;
                cancelIdleCloseLocked();
            }
            listeners.add(listener);
            notifySnapshot(listener);
            ensureWorker();
            return new Lease(this, listener);
        }

        private void release(Listener listener) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                if (connected) {
                    scheduleIdleClose();
                } else {
                    requestStop();
                }
            }
        }

        private boolean isConnected() {
            return connected;
        }

        private void reconnect() {
            synchronized (resourceLock) {
                stopRequested = false;
                cancelIdleCloseLocked();
            }
            publishStatus(false, "正在重新连接 " + key.endpoint() + "…");
            closeCurrentResources();
            ensureWorker();
        }

        private void tap(int x, int y) throws IOException {
            activeClient().tap(x, y);
        }

        private void swipe(int startX, int startY, int endX, int endY, int durationMillis) throws IOException {
            activeClient().swipe(startX, startY, endX, endY, durationMillis);
        }

        private void keyEvent(int keyCode) throws IOException {
            activeClient().keyEvent(keyCode);
        }

        private void text(String value) throws IOException {
            activeClient().text(value);
        }

        private AdbClient activeClient() throws IOException {
            AdbClient current = client;
            if (!connected || current == null) throw new IOException("ADB 设备尚未连接");
            return current;
        }

        private void ensureWorker() {
            if (!shouldRun()) return;
            if (workerRunning.compareAndSet(false, true)) worker.execute(this::captureLoop);
        }

        private void captureLoop() {
            try {
                while (shouldRun()) {
                    try {
                        captureUntilDisconnected();
                    } catch (IOException | RuntimeException error) {
                        if (shouldRun()) {
                            publishStatus(false, "连接中断，正在重试：" + safeMessage(error));
                            try {
                                Thread.sleep(1_000);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    } finally {
                        connected = false;
                        closeCurrentResources();
                    }
                }
            } finally {
                workerRunning.set(false);
                if (shouldRun()) ensureWorker();
            }
        }

        private void captureUntilDisconnected() throws IOException {
            AdbClient next = new AdbClient(key.endpoint(), 5555, key.executable());
            next.setVideoQuality(videoMaxEdge, videoBitRate);
            next.setVideoEnabled(videoEnabled);
            synchronized (resourceLock) {
                if (!shouldRun()) return;
                client = next;
            }
            publishStatus(false, "正在连接 " + key.endpoint() + "…");
            next.connect();
            if (!shouldRun()) return;

            if (videoEnabled) {
                try {
                    captureVideo(next);
                    return;
                } catch (IOException error) {
                    if (!shouldRun() || client != next) return;
                    videoEnabled = false;
                    // A failed video closes the server's control socket too. Reconnect with
                    // an encoder-free shell session instead of keeping a dead direct controller.
                    publishStatus(false, "视频不可用，正在回退稳定截图：" + safeMessage(error));
                    throw new IOException("scrcpy 视频失败，改用截图模式", error);
                }
            }

            connected = true;
            publishStatus(true, "已连接 · " + key.endpoint() + " · 共享稳定模式");
            publishFrame(next.captureScreen());

            AdbClient.ScreenStream nextStream = next.openScreenStream();
            synchronized (resourceLock) {
                if (!shouldRun() || client != next) {
                    nextStream.close();
                    return;
                }
                stream = nextStream;
            }
            try (nextStream) {
                while (shouldRun() && client == next) {
                    publishFrame(nextStream.readFrame());
                }
            } finally {
                synchronized (resourceLock) {
                    if (stream == nextStream) stream = null;
                }
            }
        }

        private boolean shouldRun() {
            return !closed && !stopRequested;
        }

        private void captureVideo(AdbClient next) throws IOException {
            AdbClient.VideoStream opened = next.openVideoStream();
            synchronized (resourceLock) {
                if (!shouldRun() || client != next) {
                    opened.close();
                    return;
                }
                videoStream = opened;
            }
            lastFrame = null;
            ScheduledFuture<?> startupTimeout = IDLE_CLOSER.schedule(opened::close, 10, TimeUnit.SECONDS);
            try (opened) {
                while (shouldRun() && client == next) {
                    AdbClient.VideoFrame frame = opened.readFrame();
                    startupTimeout.cancel(false);
                    // The in-process decoder returns an owned frame; no second full-frame clone.
                    AdbClient.VideoFrame snapshot = frame;
                    // Input positions must use this exact video's size, including after rotation.
                    AdbClient.ScreenSize coordinates = new AdbClient.ScreenSize(frame.width(), frame.height());
                    lastDisplaySize = coordinates;
                    lastVideoFrame = snapshot;
                    if (!connected) publishStatus(true, "已连接 · H.264低延迟模式");
                    for (Listener listener : listeners) {
                        try {
                            listener.onVideoFrame(snapshot, coordinates, key.endpoint());
                        } catch (RuntimeException ignored) {
                            // A faulty view must not interrupt other subscribers.
                        }
                    }
                }
            } finally {
                startupTimeout.cancel(false);
                synchronized (resourceLock) {
                    if (videoStream == opened) videoStream = null;
                }
            }
        }

        private void scheduleIdleClose() {
            synchronized (resourceLock) {
                if (closed || !listeners.isEmpty() || stopRequested) return;
                cancelIdleCloseLocked();
                idleClose = IDLE_CLOSER.schedule(() -> {
                    synchronized (resourceLock) {
                        idleClose = null;
                        if (closed || !listeners.isEmpty()) return;
                        stopRequested = true;
                        connected = false;
                        statusMessage = "已断开 · " + key.endpoint();
                    }
                    closeCurrentResources();
                }, IDLE_CLOSE_DELAY_SECONDS, TimeUnit.SECONDS);
            }
        }

        private void requestStop() {
            synchronized (resourceLock) {
                stopRequested = true;
                cancelIdleCloseLocked();
            }
            closeCurrentResources();
        }

        private void cancelIdleCloseLocked() {
            if (idleClose != null) {
                idleClose.cancel(false);
                idleClose = null;
            }
        }

        private void publishFrame(byte[] png) {
            lastVideoFrame = null;
            lastFrame = png;
            for (Listener listener : listeners) {
                try {
                    listener.onFrame(png, key.endpoint());
                } catch (RuntimeException ignored) {
                    // One closed or faulty view must not stop the shared device stream.
                }
            }
        }

        private void publishStatus(boolean isConnected, String message) {
            connected = isConnected;
            statusMessage = message;
            for (Listener listener : listeners) {
                try {
                    listener.onStatus(isConnected, message);
                } catch (RuntimeException ignored) {
                    // Keep the shared session available to the remaining views.
                }
            }
        }

        private void notifySnapshot(Listener listener) {
            try {
                listener.onStatus(connected, statusMessage);
                byte[] frame = lastFrame;
                if (frame != null) listener.onFrame(frame, key.endpoint());
                AdbClient.VideoFrame video = lastVideoFrame;
                if (video != null) listener.onVideoFrame(video, lastDisplaySize, key.endpoint());
            } catch (RuntimeException ignored) {
                // The normal capture loop will provide the next state/frame update.
            }
        }

        private void closeCurrentResources() {
            AdbClient.ScreenStream oldStream;
            AdbClient.VideoStream oldVideo;
            AdbClient oldClient;
            synchronized (resourceLock) {
                oldStream = stream;
                stream = null;
                oldVideo = videoStream;
                videoStream = null;
                oldClient = client;
                client = null;
            }
            if (oldStream != null) oldStream.close();
            if (oldVideo != null) oldVideo.close();
            if (oldClient != null) {
                Thread.ofVirtual().name("ADB-shared-close").start(oldClient::closeSession);
            }
        }

        private void shutdown() {
            synchronized (resourceLock) {
                closed = true;
                stopRequested = true;
                cancelIdleCloseLocked();
            }
            listeners.clear();
            closeCurrentResources();
            worker.shutdownNow();
        }

        private static String safeMessage(Throwable error) {
            String message = error.getMessage();
            return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        }
    }
}
