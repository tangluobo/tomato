package com.tangluobo.tomato.module.connect.handler;

import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.scene.image.PixelBuffer;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Embedded Android desktop backed by one shared ADB session per endpoint. */
final class AdbPane extends BorderPane {
    private static final double VIEWPORT_PADDING = 8;
    private static final Map<KeyCode, Integer> ANDROID_KEYS = Map.ofEntries(
            Map.entry(KeyCode.ESCAPE, AdbClient.KEYCODE_BACK),
            Map.entry(KeyCode.BACK_SPACE, AdbClient.KEYCODE_DEL),
            Map.entry(KeyCode.ENTER, AdbClient.KEYCODE_ENTER),
            Map.entry(KeyCode.TAB, AdbClient.KEYCODE_TAB),
            Map.entry(KeyCode.UP, AdbClient.KEYCODE_DPAD_UP),
            Map.entry(KeyCode.DOWN, AdbClient.KEYCODE_DPAD_DOWN),
            Map.entry(KeyCode.LEFT, AdbClient.KEYCODE_DPAD_LEFT),
            Map.entry(KeyCode.RIGHT, AdbClient.KEYCODE_DPAD_RIGHT),
            Map.entry(KeyCode.HOME, AdbClient.KEYCODE_HOME)
    );

    private final String host;
    private final int port;
    private final String adbExecutablePath;
    private final Label status = new Label("未连接");
    private final ImageView imageView = new ImageView();
    private final StackPane viewport = new StackPane(imageView);
    private final Button reconnectButton = new Button("重新连接");
    private final AdbInputDispatcher inputExecutor = new AdbInputDispatcher();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong inputGeneration = new AtomicLong();
    private final AtomicReference<FrameUpdate> pendingFrame = new AtomicReference<>();
    private WritableImage videoImage;
    private PixelBuffer<ByteBuffer> videoPixels;
    private final AnimationTimer frameTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (pendingFrame.get() != null) renderLatestFrame();
        }
    };

    private volatile AdbSessionPool.Lease session;
    private volatile boolean connected;
    private volatile int framebufferWidth;
    private volatile int framebufferHeight;
    private int dragStartX;
    private int dragStartY;
    private long dragStartNanos;
    private boolean directGesture;
    private boolean pointerDown;
    private volatile long inputErrorUntil;

    AdbPane(String host, int port, String adbExecutablePath) {
        this.host = host;
        this.port = port > 0 ? port : 5555;
        this.adbExecutablePath = adbExecutablePath;
        initializeView();
    }

    private void initializeView() {
        frameTimer.start();
        setStyle("-fx-background-color: #161616;");
        setFocusTraversable(true);

        status.setStyle("-fx-text-fill: #555; -fx-padding: 0 8 0 4;");
        reconnectButton.setOnAction(event -> connect());
        Button disconnectButton = actionButton("断开", "断开 ADB 设备", this::disconnect);
        Button backButton = keyButton("↩ 返回", "Android 返回键", AdbClient.KEYCODE_BACK);
        Button homeButton = keyButton("⌂ Home", "Android 主屏幕", AdbClient.KEYCODE_HOME);
        Button recentButton = keyButton("▣ 最近任务", "打开最近任务", AdbClient.KEYCODE_APP_SWITCH);
        Button powerButton = keyButton("⏻ 电源", "电源键", AdbClient.KEYCODE_POWER);
        Button volumeDownButton = keyButton("音量−", "降低音量", AdbClient.KEYCODE_VOLUME_DOWN);
        Button volumeUpButton = keyButton("音量+", "提高音量", AdbClient.KEYCODE_VOLUME_UP);
        Button pasteButton = actionButton("粘贴", "把本地剪贴板中的 ASCII 文本输入设备", this::pasteClipboard);
        Button fullScreenButton = actionButton("全屏", "切换全屏", this::toggleFullScreen);
        reconnectButton.setTooltip(new Tooltip("重新连接设备"));
        Button fastButton = actionButton("流畅视频", "切换该设备所有窗口到 H.264 视频", () -> {
            if (session != null) session.setVideoEnabled(true);
        });
        Button stableButton = actionButton("稳定截图", "编码器不兼容时切换到 PNG 截图", () -> {
            if (session != null) session.setVideoEnabled(false);
        });
        ComboBox<String> quality = new ComboBox<>();
        quality.getItems().addAll("高清 1920", "原生分辨率", "流畅 1024");
        quality.setValue("高清 1920");
        quality.setTooltip(new Tooltip("切换该设备所有窗口的画质；原生分辨率需要更多带宽"));
        quality.setOnAction(event -> {
            if (session == null) return;
            switch (quality.getSelectionModel().getSelectedIndex()) {
                case 1 -> session.setVideoQuality(0, 16_000_000);
                case 2 -> session.setVideoQuality(1024, 4_000_000);
                default -> session.setVideoQuality(1920, 8_000_000);
            }
        });

        ToolBar toolbar = new ToolBar(status, reconnectButton, disconnectButton, fastButton, stableButton, quality,
                new Separator(), backButton, homeButton, recentButton, powerButton,
                volumeDownButton, volumeUpButton, pasteButton, new Separator(), fullScreenButton);
        setTop(toolbar);

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        // ImageView must not participate in StackPane's preferred-size calculation. Binding
        // its fit size to the complete (padded) parent size otherwise creates a layout loop:
        // child = parent, preferred parent = child + padding, so every pulse grows the image.
        imageView.setManaged(false);
        viewport.setPadding(new Insets(VIEWPORT_PADDING));
        viewport.widthProperty().addListener((observable, oldValue, newValue) -> layoutFrame());
        viewport.heightProperty().addListener((observable, oldValue, newValue) -> layoutFrame());
        viewport.setStyle("-fx-background-color: #161616;");
        setCenter(viewport);

        imageView.setOnMousePressed(this::handleMousePressed);
        imageView.setOnMouseDragged(this::handleMouseDragged);
        imageView.setOnMouseReleased(this::handleMouseReleased);
        imageView.setOnScroll(this::handleScroll);
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        addEventFilter(KeyEvent.KEY_TYPED, this::handleKeyTyped);
    }

    void connect() {
        AdbSessionPool.Lease current = session;
        if (current != null) {
            reconnectButton.setDisable(true);
            current.reconnect();
            return;
        }
        long connectionGeneration = generation.incrementAndGet();
        connected = false;
        reconnectButton.setDisable(true);
        updateStatus("正在连接 " + AdbClient.endpoint(host, port) + "…");
        try {
            session = AdbSessionPool.acquire(host, port, adbExecutablePath,
                    new AdbSessionPool.Listener() {
                        @Override
                        public void onStatus(boolean sessionConnected, String message) {
                            if (generation.get() != connectionGeneration) return;
                            if (!sessionConnected) {
                                inputGeneration.incrementAndGet();
                                inputExecutor.clear();
                            }
                            connected = sessionConnected;
                            updateStatus(message);
                            Platform.runLater(() -> {
                                if (!sessionConnected) pointerDown = false;
                                reconnectButton.setDisable(false);
                                if (sessionConnected) requestFocus();
                            });
                        }

                        @Override
                        public void onFrame(byte[] png, String endpoint) {
                            if (generation.get() != connectionGeneration) return;
                            try {
                                Image frame = decodePngFrame(png);
                                int width = (int) Math.round(frame.getWidth());
                                int height = (int) Math.round(frame.getHeight());
                                queueFrame(new FrameUpdate(frame, width, height, endpoint,
                                        "ADB共享稳定模式", connectionGeneration, null));
                            } catch (IOException error) {
                                updateStatus("画面解码失败：" + safeMessage(error));
                            }
                        }

                        @Override
                        public void onVideoFrame(AdbClient.VideoFrame frame,
                                                 AdbClient.ScreenSize displaySize, String endpoint) {
                            if (generation.get() != connectionGeneration) return;
                            queueFrame(new FrameUpdate(null, displaySize.width(), displaySize.height(),
                                    endpoint, "scrcpy视频", connectionGeneration, frame));
                        }
                    });
        } catch (RuntimeException error) {
            updateStatus("连接失败：" + safeMessage(error));
            reconnectButton.setDisable(false);
            return;
        }
    }

    private static Image decodePngFrame(byte[] png) throws IOException {
        Image frame = new Image(new ByteArrayInputStream(png));
        if (frame.isError() || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            throw new IOException("无法解码设备屏幕截图");
        }
        return frame;
    }

    void disconnect() {
        pointerDown = false;
        inputExecutor.clear();
        generation.incrementAndGet();
        connected = false;
        AdbSessionPool.Lease current = session;
        session = null;
        updateStatus("已断开");
        reconnectButton.setDisable(false);
        if (current != null) current.close();
    }

    void dispose() {
        frameTimer.stop();
        disconnect();
        inputExecutor.close();
    }

    /** Keep only the newest decoded frame so a busy JavaFX thread never accumulates visual lag. */
    private void queueFrame(FrameUpdate update) {
        pendingFrame.set(update);
    }

    private void renderLatestFrame() {
            FrameUpdate update = pendingFrame.getAndSet(null);
            if (update == null || generation.get() != update.generation()) return;
            Image image = update.image();
            if (update.video() != null) {
                AdbClient.VideoFrame video = update.video();
                if (videoImage == null || videoImage.getWidth() != video.width() || videoImage.getHeight() != video.height()) {
                    videoPixels = new PixelBuffer<>(video.width(), video.height(),
                            ByteBuffer.allocateDirect(video.bgra().length), PixelFormat.getByteBgraPreInstance());
                    videoImage = new WritableImage(videoPixels);
                }
                videoPixels.updateBuffer(buffer -> {
                    buffer.getBuffer().clear().put(video.bgra()).rewind();
                    return null;
                });
                image = videoImage;
            }
            framebufferWidth = update.width();
            framebufferHeight = update.height();
            imageView.setImage(image);
            layoutFrame();
            if (System.nanoTime() >= inputErrorUntil) {
                AdbSessionPool.Lease active = session;
                status.setText("已连接 · " + update.endpoint() + " · "
                        + (update.video() == null ? update.width() : update.video().width()) + "×"
                        + (update.video() == null ? update.height() : update.video().height())
                        + " · " + update.transport() + " · " + (active == null ? "" : active.controlDescription()));
            }
    }

    void toggleFullScreen() {
        if (getScene() != null && getScene().getWindow() instanceof Stage stage) {
            stage.setFullScreen(!stage.isFullScreen());
        }
    }

    void sendVirtualKey(int keyCode) {
        submitInput(active -> active.keyEvent(keyCode));
    }

    private void layoutFrame() {
        Image frame = imageView.getImage();
        if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) return;
        double availableWidth = Math.max(0, viewport.getWidth() - VIEWPORT_PADDING * 2);
        double availableHeight = Math.max(0, viewport.getHeight() - VIEWPORT_PADDING * 2);
        if (availableWidth <= 0 || availableHeight <= 0) return;

        double scale = Math.min(availableWidth / frame.getWidth(), availableHeight / frame.getHeight());
        double displayWidth = Math.max(1, frame.getWidth() * scale);
        double displayHeight = Math.max(1, frame.getHeight() * scale);
        imageView.setFitWidth(displayWidth);
        imageView.setFitHeight(displayHeight);
        imageView.relocate(
                VIEWPORT_PADDING + (availableWidth - displayWidth) / 2,
                VIEWPORT_PADDING + (availableHeight - displayHeight) / 2);
    }

    private Button keyButton(String text, String tooltip, int keyCode) {
        return actionButton(text, tooltip, () -> sendVirtualKey(keyCode));
    }

    private Button actionButton(String text, String tooltip, Runnable action) {
        Button button = new Button(text);
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> action.run());
        return button;
    }

    private void handleMousePressed(MouseEvent event) {
        requestFocus();
        if (!connected || framebufferWidth <= 0 || framebufferHeight <= 0) return;
        int[] point = remotePoint(event.getX(), event.getY());
        dragStartX = point[0];
        dragStartY = point[1];
        dragStartNanos = System.nanoTime();
        pointerDown = true;
        AdbSessionPool.Lease active = session;
        directGesture = active != null && active.hasDirectControl();
        if (directGesture) submitTouch(0, point[0], point[1]);
        event.consume();
    }

    private void handleMouseDragged(MouseEvent event) {
        if (pointerDown && directGesture) {
            int[] point = remotePoint(event.getX(), event.getY());
            submitTouch(2, point[0], point[1]);
        }
        event.consume();
    }

    private void handleMouseReleased(MouseEvent event) {
        if (!pointerDown) return;
        pointerDown = false;
        int[] end = remotePoint(event.getX(), event.getY());
        if (directGesture) {
            submitTouch(1, end[0], end[1]);
            event.consume();
            return;
        }
        int dx = end[0] - dragStartX;
        int dy = end[1] - dragStartY;
        long heldMillis = (System.nanoTime() - dragStartNanos) / 1_000_000;
        // The gesture is sent only on mouse release. Replaying its full local duration would
        // make every drag feel twice as slow, so keep the device-side animation short.
        int duration = (int) Math.min(300, Math.max(80, heldMillis / 3));
        if (Math.hypot(dx, dy) < Math.max(8, Math.min(framebufferWidth, framebufferHeight) / 100.0)) {
            submitInput(active -> active.tap(end[0], end[1]));
        } else {
            int startX = dragStartX;
            int startY = dragStartY;
            submitInput(active -> active.swipe(startX, startY, end[0], end[1], duration));
        }
        event.consume();
    }

    private void handleScroll(ScrollEvent event) {
        int[] point = remotePoint(event.getX(), event.getY());
        AdbSessionPool.Lease current = session;
        if (current != null && current.hasDirectControl()) {
            int width = framebufferWidth;
            int height = framebufferHeight;
            double horizontal = Math.max(-4, Math.min(4, event.getDeltaX() / 40));
            double vertical = Math.max(-4, Math.min(4, event.getDeltaY() / 40));
            submitInput("scroll", active -> active.scroll(point[0], point[1], width, height, horizontal, vertical));
            event.consume();
            return;
        }
        int distance = Math.max(80, framebufferHeight / 4);
        int endY = event.getDeltaY() < 0 ? point[1] - distance : point[1] + distance;
        endY = Math.max(0, Math.min(Math.max(0, framebufferHeight - 1), endY));
        int finalEndY = endY;
        submitInput("scroll", active -> active.swipe(point[0], point[1], point[0], finalEndY, 100));
        event.consume();
    }

    private void handleKeyPressed(KeyEvent event) {
        if ((event.isControlDown() || event.isMetaDown()) && event.getCode() == KeyCode.V) {
            pasteClipboard();
            event.consume();
            return;
        }
        Integer androidKey = ANDROID_KEYS.get(event.getCode());
        if (androidKey != null) {
            sendVirtualKey(androidKey);
            event.consume();
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) return;
        String character = event.getCharacter();
        if (character == null || character.isEmpty() || character.charAt(0) < 0x20) return;
        sendText(character);
        event.consume();
    }

    private void pasteClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) sendText(clipboard.getString());
    }

    private void sendText(String text) {
        String encoded = AdbClient.encodeInputText(text);
        if (encoded.isEmpty() && text != null && !text.isEmpty()) {
            updateStatus("ADB 键盘输入仅支持 ASCII 文本");
            return;
        }
        submitInput(active -> active.text(text));
    }

    private int[] remotePoint(double x, double y) {
        Bounds bounds = imageView.getBoundsInLocal();
        double width = Math.max(1, bounds.getWidth());
        double height = Math.max(1, bounds.getHeight());
        int remoteX = (int) Math.floor((x - bounds.getMinX()) / width * framebufferWidth);
        int remoteY = (int) Math.floor((y - bounds.getMinY()) / height * framebufferHeight);
        remoteX = Math.max(0, Math.min(Math.max(0, framebufferWidth - 1), remoteX));
        remoteY = Math.max(0, Math.min(Math.max(0, framebufferHeight - 1), remoteY));
        return new int[]{remoteX, remoteY};
    }

    private void submitInput(InputAction action) {
        submitInput(null, action);
    }

    private void submitTouch(int action, int x, int y) {
        int width = framebufferWidth;
        int height = framebufferHeight;
        submitInput(action == 2 ? "move" : null, active -> active.touch(action, x, y, width, height));
    }

    private void submitInput(String replaceable, InputAction action) {
        AdbSessionPool.Lease active = session;
        if (!connected || active == null || !active.isConnected()) return;
        long connectionGeneration = generation.get();
        long epoch = inputGeneration.get();
        boolean accepted = inputExecutor.submit(replaceable, () -> {
            if (generation.get() != connectionGeneration || inputGeneration.get() != epoch
                    || session != active || !active.isConnected()) return;
            try {
                action.run(active);
            } catch (IOException error) {
                inputErrorUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                updateStatus("控制失败：" + safeMessage(error));
            }
        });
        if (!accepted) {
            inputErrorUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            updateStatus("控制指令积压，请等待或重新连接");
        }
    }

    private void updateStatus(String text) {
        if (Platform.isFxApplicationThread()) status.setText(text);
        else Platform.runLater(() -> status.setText(text));
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface InputAction {
        void run(AdbSessionPool.Lease session) throws IOException;
    }

    private record FrameUpdate(Image image, int width, int height, String endpoint,
                               String transport, long generation, AdbClient.VideoFrame video) {
    }
}
