package com.tangluobo.tomato.module.connect.handler;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToolBar;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** JavaFX view for Tomato's embedded VNC (RFB) client. */
final class VncPane extends BorderPane {
    private static final int X11_BACKSPACE = 0xff08;
    private static final int X11_TAB = 0xff09;
    private static final int X11_RETURN = 0xff0d;
    private static final int X11_ESCAPE = 0xff1b;
    private static final int X11_HOME = 0xff50;
    private static final int X11_LEFT = 0xff51;
    private static final int X11_UP = 0xff52;
    private static final int X11_RIGHT = 0xff53;
    private static final int X11_DOWN = 0xff54;
    private static final int X11_PAGE_UP = 0xff55;
    private static final int X11_PAGE_DOWN = 0xff56;
    private static final int X11_END = 0xff57;
    private static final int X11_INSERT = 0xff63;
    private static final int X11_DELETE = 0xffff;

    private final String host;
    private final int port;
    private final String password;
    private final boolean clipboardEnabled;
    private final Supplier<String> passwordSupplier;
    private final Label status = new Label("未连接");
    private final ImageView imageView = new ImageView();
    private final StackPane viewport = new StackPane(imageView);
    private final Button reconnectButton = new Button("重新连接");
    private final AtomicLong generation = new AtomicLong();
    private final Map<KeyCode, Integer> pressedKeys = new EnumMap<>(KeyCode.class);

    private volatile VncClient client;
    private WritableImage image;
    private int framebufferWidth;
    private int framebufferHeight;
    private int pointerMask;

    VncPane(String host, int port, String password, boolean clipboardEnabled,
            Supplier<String> passwordSupplier) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.clipboardEnabled = clipboardEnabled;
        this.passwordSupplier = passwordSupplier;
        initializeView();
    }

    private void initializeView() {
        setStyle("-fx-background-color: #161616;");
        setFocusTraversable(true);

        status.setStyle("-fx-text-fill: #555; -fx-padding: 0 8 0 4;");
        reconnectButton.setOnAction(event -> reconnect());
        Button disconnectButton = new Button("断开");
        disconnectButton.setOnAction(event -> disconnect());
        Button fullScreenButton = new Button("全屏");
        fullScreenButton.setOnAction(event -> toggleFullScreen());
        ToolBar toolbar = new ToolBar(status, reconnectButton, disconnectButton, fullScreenButton);
        setTop(toolbar);

        viewport.setAlignment(Pos.CENTER);
        viewport.setPadding(Insets.EMPTY);
        viewport.setStyle("-fx-background-color: #161616;");
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);
        imageView.fitWidthProperty().bind(viewport.widthProperty());
        imageView.fitHeightProperty().bind(viewport.heightProperty());
        setCenter(viewport);

        imageView.setOnMousePressed(this::handleMousePressed);
        imageView.setOnMouseReleased(this::handleMouseReleased);
        imageView.setOnMouseMoved(this::handleMouseMove);
        imageView.setOnMouseDragged(this::handleMouseMove);
        imageView.setOnScroll(this::handleScroll);
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        addEventFilter(KeyEvent.KEY_RELEASED, this::handleKeyReleased);
    }

    void connect() {
        long connectionGeneration = generation.incrementAndGet();
        disconnectClient();
        updateStatus("正在连接 " + host + ":" + port + "…");
        reconnectButton.setDisable(true);

        AtomicBoolean failed = new AtomicBoolean();
        VncClient next = new VncClient(host, port, password, new VncClient.Listener() {
            @Override
            public String onPasswordRequired() {
                return requestPassword();
            }

            @Override
            public void onConnected(String desktopName, int width, int height) {
                if (generation.get() != connectionGeneration) return;
                Platform.runLater(() -> {
                    if (generation.get() != connectionGeneration) return;
                    ensureImage(width, height);
                    status.setText("已连接 · " + desktopName + " · " + width + "×" + height);
                    reconnectButton.setDisable(false);
                    requestFocus();
                });
            }

            @Override
            public void onFrameUpdate(int width, int height,
                                      List<VncClient.RawRectangle> rectangles) {
                if (generation.get() != connectionGeneration) return;
                Platform.runLater(() -> applyFrameUpdate(connectionGeneration, width, height, rectangles));
            }

            @Override
            public void onClipboardText(String text) {
                if (!clipboardEnabled || generation.get() != connectionGeneration) return;
                Platform.runLater(() -> {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(text);
                    Clipboard.getSystemClipboard().setContent(content);
                });
            }

            @Override
            public void onBell() {
                if (generation.get() == connectionGeneration) {
                    Platform.runLater(() -> java.awt.Toolkit.getDefaultToolkit().beep());
                }
            }

            @Override
            public void onDisconnected() {
                if (generation.get() != connectionGeneration || failed.get()) return;
                Platform.runLater(() -> {
                    status.setText("连接已断开");
                    reconnectButton.setDisable(false);
                });
            }

            @Override
            public void onError(String message, Throwable error) {
                if (generation.get() != connectionGeneration) return;
                failed.set(true);
                Platform.runLater(() -> {
                    status.setText("连接失败：" + message);
                    reconnectButton.setDisable(false);
                });
            }
        });
        client = next;
        Thread.ofVirtual().name("vnc-" + host + ':' + port).start(next::run);
    }

    private String requestPassword() {
        if (passwordSupplier == null) return null;
        if (Platform.isFxApplicationThread()) return passwordSupplier.get();

        FutureTask<String> prompt = new FutureTask<>(passwordSupplier::get);
        Platform.runLater(prompt);
        try {
            return prompt.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException error) {
            return null;
        }
    }

    void reconnect() {
        connect();
    }

    void disconnect() {
        generation.incrementAndGet();
        disconnectClient();
        updateStatus("已断开");
        reconnectButton.setDisable(false);
    }

    void toggleFullScreen() {
        if (getScene() != null && getScene().getWindow() instanceof Stage stage) {
            stage.setFullScreen(!stage.isFullScreen());
        }
    }

    private void disconnectClient() {
        VncClient current = client;
        client = null;
        if (current != null) current.close();
        pressedKeys.clear();
        pointerMask = 0;
    }

    private void applyFrameUpdate(long connectionGeneration, int width, int height,
                                  List<VncClient.RawRectangle> rectangles) {
        if (generation.get() != connectionGeneration) return;
        ensureImage(width, height);
        for (VncClient.RawRectangle rectangle : rectangles) {
            if (rectangle.x() < 0 || rectangle.y() < 0
                    || rectangle.x() + rectangle.width() > framebufferWidth
                    || rectangle.y() + rectangle.height() > framebufferHeight) {
                continue;
            }
            image.getPixelWriter().setPixels(rectangle.x(), rectangle.y(),
                    rectangle.width(), rectangle.height(), PixelFormat.getByteBgraInstance(),
                    rectangle.bgraPixels(), 0, rectangle.width() * 4);
        }
    }

    private void ensureImage(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (image == null || framebufferWidth != width || framebufferHeight != height) {
            framebufferWidth = width;
            framebufferHeight = height;
            image = new WritableImage(width, height);
            imageView.setImage(image);
        }
    }

    private void handleMousePressed(MouseEvent event) {
        requestFocus();
        pointerMask |= buttonMask(event.getButton());
        sendPointer(event);
        event.consume();
    }

    private void handleMouseReleased(MouseEvent event) {
        pointerMask &= ~buttonMask(event.getButton());
        sendPointer(event);
        event.consume();
    }

    private void handleMouseMove(MouseEvent event) {
        sendPointer(event);
        event.consume();
    }

    private void handleScroll(ScrollEvent event) {
        int scrollButton = event.getDeltaY() > 0 ? 8 : 16;
        int[] point = remotePoint(event.getX(), event.getY());
        sendSafely(current -> {
            current.sendPointerEvent(pointerMask | scrollButton, point[0], point[1]);
            current.sendPointerEvent(pointerMask, point[0], point[1]);
        });
        event.consume();
    }

    private void sendPointer(MouseEvent event) {
        int[] point = remotePoint(event.getX(), event.getY());
        sendSafely(current -> current.sendPointerEvent(pointerMask, point[0], point[1]));
    }

    private int[] remotePoint(double x, double y) {
        double displayedWidth = Math.max(1.0, imageView.getBoundsInLocal().getWidth());
        double displayedHeight = Math.max(1.0, imageView.getBoundsInLocal().getHeight());
        int remoteX = (int) Math.floor(x * framebufferWidth / displayedWidth);
        int remoteY = (int) Math.floor(y * framebufferHeight / displayedHeight);
        return new int[]{Math.max(0, Math.min(framebufferWidth - 1, remoteX)),
                Math.max(0, Math.min(framebufferHeight - 1, remoteY))};
    }

    private void handleKeyPressed(KeyEvent event) {
        if (event.isControlDown() && event.getCode() == KeyCode.V && clipboardEnabled) {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (clipboard.hasString()) {
                sendSafely(current -> current.sendClipboardText(clipboard.getString()));
            }
        }
        int keySym = keySym(event);
        if (keySym != 0 && !pressedKeys.containsKey(event.getCode())) {
            pressedKeys.put(event.getCode(), keySym);
            sendSafely(current -> current.sendKeyEvent(true, keySym));
            event.consume();
        }
    }

    private void handleKeyReleased(KeyEvent event) {
        Integer keySym = pressedKeys.remove(event.getCode());
        if (keySym != null) {
            sendSafely(current -> current.sendKeyEvent(false, keySym));
            event.consume();
        }
    }

    static int keySym(KeyEvent event) {
        KeyCode code = event.getCode();
        return switch (code) {
            case BACK_SPACE -> X11_BACKSPACE;
            case TAB -> X11_TAB;
            case ENTER -> X11_RETURN;
            case ESCAPE -> X11_ESCAPE;
            case HOME -> X11_HOME;
            case LEFT -> X11_LEFT;
            case UP -> X11_UP;
            case RIGHT -> X11_RIGHT;
            case DOWN -> X11_DOWN;
            case PAGE_UP -> X11_PAGE_UP;
            case PAGE_DOWN -> X11_PAGE_DOWN;
            case END -> X11_END;
            case INSERT -> X11_INSERT;
            case DELETE -> X11_DELETE;
            case SHIFT -> 0xffe1;
            case CONTROL -> 0xffe3;
            case ALT -> 0xffe9;
            case META, WINDOWS -> 0xffe7;
            case CAPS -> 0xffe5;
            case F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 ->
                    0xffbd + Integer.parseInt(code.getName().substring(1));
            case SPACE -> ' ';
            case MINUS -> '-';
            case EQUALS -> '=';
            case OPEN_BRACKET -> '[';
            case CLOSE_BRACKET -> ']';
            case BACK_SLASH -> '\\';
            case SEMICOLON -> ';';
            case QUOTE -> '\'';
            case COMMA -> ',';
            case PERIOD -> '.';
            case SLASH -> '/';
            case BACK_QUOTE -> '`';
            case ADD -> '+';
            case SUBTRACT -> '-';
            case MULTIPLY -> '*';
            case DIVIDE -> '/';
            case DECIMAL -> '.';
            default -> printableKeySym(event);
        };
    }

    private static int printableKeySym(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code.isLetterKey()) return Character.toLowerCase(code.getName().charAt(0));
        if (code.isDigitKey()) {
            String name = code.getName();
            return name.charAt(name.length() - 1);
        }
        String text = event.getText();
        if (text != null && text.codePointCount(0, text.length()) == 1) {
            int codePoint = text.codePointAt(0);
            return codePoint <= 0xff ? codePoint : 0x01000000 | codePoint;
        }
        return 0;
    }

    private static int buttonMask(MouseButton button) {
        return switch (button) {
            case PRIMARY -> 1;
            case MIDDLE -> 2;
            case SECONDARY -> 4;
            default -> 0;
        };
    }

    private void sendSafely(IoAction action) {
        VncClient current = client;
        if (current == null || !current.isConnected()) return;
        try {
            action.run(current);
        } catch (IOException error) {
            updateStatus("输入发送失败：" + error.getMessage());
        }
    }

    private void updateStatus(String text) {
        if (Platform.isFxApplicationThread()) status.setText(text);
        else Platform.runLater(() -> status.setText(text));
    }

    @FunctionalInterface
    private interface IoAction {
        void run(VncClient client) throws IOException;
    }
}
