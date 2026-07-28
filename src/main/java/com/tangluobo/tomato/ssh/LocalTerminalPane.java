package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地终端组件，使用VT100终端模拟器，连接本地Shell进程
 * Windows: 支持 cmd 和 powershell
 * Linux/macOS: 使用系统默认Shell (bash/zsh)
 */
public class LocalTerminalPane extends BorderPane {

    private final TerminalEmulator emulator;
    private final TerminalView terminalView;
    private Process shellProcess;
    private Thread readThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 状态栏
    private final Label stateLabel;
    private final Label shellLabel;
    private final Circle statusDot;

    // 终端容器
    private final Pane terminalPane;
    private final ScrollBar scrollBar;

    // 右键菜单
    private final ContextMenu contextMenu;

    // 渲染节流
    private long lastRenderTime = 0;
    private static final long RENDER_INTERVAL = 33;
    private boolean renderPending = false;

    // 防止scrollbar循环
    private boolean updatingScrollbar = false;

    // 交替缓冲区状态跟踪
    private boolean lastAltBufferState = false;

    // Shell类型
    private String shellType;
    private OutputStream processOutput;

    public LocalTerminalPane() {
        emulator = new TerminalEmulator();
        terminalView = new TerminalView(emulator);

        // 状态栏
        HBox statusBar = new HBox();
        statusBar.setStyle("-fx-background-color: #FFFFFB; -fx-padding: 2 10; -fx-alignment: center-left; -fx-border-color: #e0e0e0; -fx-border-width: 1 0 0 0;");

        statusDot = new Circle(4, Color.RED);
        HBox.setMargin(statusDot, new javafx.geometry.Insets(0, 4, 0, 0));

        stateLabel = new Label("未连接");
        stateLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");
        HBox.setMargin(stateLabel, new javafx.geometry.Insets(0, 8, 0, 0));

        shellLabel = new Label("");
        shellLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label encodingLabel = new Label("UTF-8");
        encodingLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");

        statusBar.getChildren().addAll(statusDot, stateLabel, shellLabel, encodingLabel, spacer);

        // 终端区域 + 右侧滚动条
        scrollBar = new ScrollBar();
        scrollBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        scrollBar.setStyle("-fx-background-color: #2d2d2d;");
        scrollBar.setPrefWidth(12);
        scrollBar.setMin(0);
        scrollBar.setMax(0);
        scrollBar.setValue(0);
        scrollBar.setVisible(false);
        scrollBar.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (updatingScrollbar) return;
            if (emulator.isUsingAltBuffer()) return;
            double visibleAmt = scrollBar.getVisibleAmount();
            int scrollbackSize = emulator.getScrollbackSize();
            double val = newVal.doubleValue();
            int offset = (int) Math.round(scrollbackSize - val + visibleAmt - 1);
            terminalView.setScrollOffset(Math.max(0, Math.min(offset, scrollbackSize)));
        });

        terminalPane = new Pane() {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                double w = getWidth();
                double h = getHeight();
                if (w > 0 && h > 0) {
                    double sbWidth = scrollBar.isVisible() ? scrollBar.getWidth() : 0;
                    terminalView.relocate(0, 0);
                    terminalView.resize(w - sbWidth, h);
                    scrollBar.resizeRelocate(w - scrollBar.getPrefWidth(), 0, scrollBar.getPrefWidth(), h);
                }
            }
        };
        terminalPane.getChildren().addAll(terminalView, scrollBar);
        terminalPane.setStyle("-fx-background-color: #1e1e1e;");
        terminalPane.setMaxWidth(Double.MAX_VALUE);
        terminalPane.setMaxHeight(Double.MAX_VALUE);
        terminalPane.setPrefWidth(800);
        terminalPane.setPrefHeight(600);

        // 滚动条回调
        terminalView.setScrollbarHandler((scrollbackSize, scrollOffset, visibleRows) -> {
            Platform.runLater(() -> {
                updatingScrollbar = true;
                try {
                    int currentSbSize = emulator.getScrollbackSize();
                    int currentOffset = emulator.getScrollOffset();
                    if (currentSbSize > 0) {
                        scrollBar.setVisible(true);
                        int totalContent = currentSbSize + visibleRows;
                        scrollBar.setMin(0);
                        scrollBar.setMax(totalContent - 1);
                        scrollBar.setVisibleAmount(visibleRows);
                        scrollBar.setValue(currentSbSize - currentOffset + visibleRows - 1);
                    } else {
                        scrollBar.setVisible(false);
                    }
                } finally {
                    updatingScrollbar = false;
                }
            });
        });

        setCenter(terminalPane);
        setBottom(statusBar);
        setStyle("-fx-background-color: #1e1e1e;");
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setPrefWidth(800);
        setPrefHeight(600);

        // 设置终端响应回调
        emulator.setResponseHandler(data -> {
            if (processOutput != null) {
                try {
                    processOutput.write(data);
                    processOutput.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        // 设置键盘输入回调
        terminalView.setKeyInputHandler(data -> {
            if (processOutput != null && running.get()) {
                try {
                    processOutput.write(data);
                    processOutput.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        // 右键菜单
        contextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> terminalView.copySelection());
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> doPaste());
        MenuItem copyPasteItem = new MenuItem("复制并粘贴");
        copyPasteItem.setOnAction(e -> {
            terminalView.copySelection();
            doPaste();
        });
        MenuItem selectAllItem = new MenuItem("全选");
        selectAllItem.setOnAction(e -> terminalView.selectAll());
        MenuItem clearItem = new MenuItem("清除选择");
        clearItem.setOnAction(e -> terminalView.clearSelection());
        contextMenu.getItems().addAll(copyItem, pasteItem, copyPasteItem, new SeparatorMenuItem(), selectAllItem, clearItem);

        setOnContextMenuRequested(e -> {
            copyItem.setDisable(!terminalView.hasSelection());
            copyPasteItem.setDisable(!terminalView.hasSelection());
            clearItem.setDisable(!terminalView.hasSelection());
            contextMenu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        setOnMousePressed(e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });

        terminalView.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });
    }

    /**
     * 连接本地终端
     * @param terminalType Windows: "cmd" 或 "powershell"; Linux/macOS: 忽略，使用系统默认Shell
     */
    public void connect(String terminalType) {
        this.shellType = terminalType;
        String os = System.getProperty("os.name", "").toLowerCase();

        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                // Windows: cmd.exe 和 powershell.exe 自带控制台，可直接使用
                if ("powershell".equalsIgnoreCase(terminalType)) {
                    pb = new ProcessBuilder("powershell.exe");
                    this.shellType = "PowerShell";
                } else {
                    pb = new ProcessBuilder("cmd.exe");
                    this.shellType = "CMD";
                }
                pb.redirectErrorStream(true);
            } else {
                // Linux/macOS: 需要通过 script 命令分配 PTY，否则 bash/zsh 不会输出提示符
                String shell;
                if (os.contains("mac")) {
                    shell = new File("/bin/zsh").exists() ? "/bin/zsh" : "/bin/bash";
                } else {
                    shell = new File("/bin/bash").exists() ? "/bin/bash" : "/bin/sh";
                }
                this.shellType = shell;

                // 使用 script 命令分配 PTY
                // Linux (GNU): script -qec "shell -il" /dev/null
                // macOS (BSD): script -q /dev/null shell -il
                String scriptPath = null;
                for (String candidate : new String[]{"/usr/bin/script", "/usr/local/bin/script", "/bin/script"}) {
                    if (new File(candidate).exists()) {
                        scriptPath = candidate;
                        break;
                    }
                }

                if (scriptPath != null) {
                    if (os.contains("mac")) {
                        // macOS BSD script: script -q /dev/null shell -il
                        pb = new ProcessBuilder(scriptPath, "-q", "/dev/null", shell, "-il");
                    } else {
                        // Linux GNU script: script -qec "shell -il" /dev/null
                        pb = new ProcessBuilder(scriptPath, "-qec", shell + " -il", "/dev/null");
                    }
                } else {
                    // 没有 script 命令，回退到直接启动 shell（可能没有提示符）
                    pb = new ProcessBuilder(shell, "-il");
                }
            }

            pb.redirectErrorStream(true);
            shellProcess = pb.start();
            processOutput = shellProcess.getOutputStream();
            running.set(true);

            updateStatusBar("已连接");

            // 启动读取线程
            startReadThread();

            Platform.runLater(() -> terminalView.requestFocus());

        } catch (IOException e) {
            Platform.runLater(() -> {
                emulator.process(("\r\n[启动本地终端失败: " + e.getMessage() + "]\r\n").getBytes());
                scheduleRender();
                updateStatusBar("启动失败");
            });
            e.printStackTrace();
        }
    }

    /**
     * 设置回滚行数
     */
    public void setScrollbackLines(int lines) {
        emulator.setMaxScrollback(lines);
    }

    private void updateStatusBar(String state) {
        Platform.runLater(() -> {
            boolean connected = state.equals("已连接");
            statusDot.setFill(connected ? Color.valueOf("#4CAF50") : Color.RED);
            stateLabel.setText(state);
            if (shellType != null) {
                shellLabel.setText(shellType);
            }
        });
    }

    private void doPaste() {
        if (processOutput == null || !running.get()) return;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            String text = clipboard.getString();
            if (text != null && !text.isEmpty()) {
                text = text.replace("\r\n", "\r").replace("\n", "\r");
                try {
                    processOutput.write(text.getBytes());
                    processOutput.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                terminalView.clearSelection();
            }
        }
    }

    public void disconnect() {
        running.set(false);
        terminalView.stopBlink();

        if (shellProcess != null) {
            // 先尝试正常终止，再强制杀掉
            shellProcess.destroy();
            try {
                if (!shellProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    shellProcess.destroyForcibly();
                }
            } catch (InterruptedException ignored) {}
            shellProcess = null;
        }
        processOutput = null;

        updateStatusBar("已断开");
    }

    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[4096];

            while (running.get() && shellProcess != null && shellProcess.isAlive()) {
                try {
                    InputStream is = shellProcess.getInputStream();
                    int len = is.read(buffer);
                    if (len == -1) break;

                    final byte[] data = new byte[len];
                    System.arraycopy(buffer, 0, data, 0, len);
                    Platform.runLater(() -> {
                        emulator.process(data);
                        scheduleRender();
                    });

                } catch (IOException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
            running.set(false);
            Platform.runLater(() -> {
                emulator.process(("\r\n[本地终端已退出]\r\n").getBytes());
                scheduleRender();
                updateStatusBar("已退出");
            });
        }, "LocalTerminal-Read-Thread");
        readThread.setDaemon(true);
        readThread.start();
    }

    private void scheduleRender() {
        long now = System.currentTimeMillis();
        if (now - lastRenderTime >= RENDER_INTERVAL) {
            lastRenderTime = now;
            terminalView.render();
            renderPending = false;
            checkAltBufferState();
        } else if (!renderPending) {
            renderPending = true;
            javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(RENDER_INTERVAL - (now - lastRenderTime)));
            delay.setOnFinished(e -> {
                lastRenderTime = System.currentTimeMillis();
                terminalView.render();
                renderPending = false;
                checkAltBufferState();
            });
            delay.play();
        }
    }

    private void checkAltBufferState() {
        boolean altBuffer = emulator.isUsingAltBuffer();
        if (altBuffer != lastAltBufferState) {
            lastAltBufferState = altBuffer;
            if (altBuffer) {
                stateLabel.setText("已连接 [ALT]");
            } else {
                stateLabel.setText("已连接");
            }
        }
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    public TerminalView getTerminalView() {
        return terminalView;
    }
}
