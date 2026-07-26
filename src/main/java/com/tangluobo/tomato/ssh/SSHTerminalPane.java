package com.tangluobo.tomato.ssh;

import com.tangluobo.tomato.ssh.zmodem.ZModem;
import com.tangluobo.tomato.ssh.zmodem.util.CustomFile;
import com.tangluobo.tomato.ssh.zmodem.util.FileAdapter;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.util.ZModemCharacter;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSH终端组件，使用VT100终端模拟器，支持ZModem协议（rz/sz文件传输）
 * 继承BorderPane，中间放终端，底部放状态栏，右侧可展开SFTP文件浏览器
 */
public class SSHTerminalPane extends BorderPane {

    // ZModem协议前缀: ** ZDLE
    private static final char[] ZMODEM_PREFIX = new char[]{
            (char) ZModemCharacter.ZPAD.value(),
            (char) ZModemCharacter.ZPAD.value(),
            (char) ZModemCharacter.ZDLE.value()
    };

    private final TerminalEmulator emulator;
    private final TerminalView terminalView;
    private SSHSession sshSession;
    private Thread readThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ZModem zmodem;
    private volatile boolean inZModemMode = false;

    // 断开连接回调
    private Runnable onDisconnect;

    // 粘贴回调
    private Runnable onPaste;

    // 右键菜单
    private final ContextMenu contextMenu;

    // 渲染节流
    private long lastRenderTime = 0;
    private static final long RENDER_INTERVAL = 33; // ~30fps
    private boolean renderPending = false;

    // 状态栏
    private final Label stateLabel;
    private final Label connLabel;
    private final Label encodingLabel;
    private final Circle statusDot;
    private final Button folderBtn;
    private final Button monitorBtn;

    // 终端容器
    private final Pane terminalPane;
    private final SplitPane splitPane;
    private final ScrollBar scrollBar;
    private final javafx.scene.layout.VBox rightPanel;

    // SFTP文件浏览器
    private SFTPFileBrowser fileBrowser;
    private SFTPClient sftpClient;
    private boolean fileBrowserVisible = false;

    // 监控视图
    private boolean monitorVisible = false;
    private MonitorPanel monitorPanel;

    // 防止scrollbar↔render循环
    private boolean updatingScrollbar = false;

    // 连接信息
    private String host;
    private int port;
    private String username;
    private String password;
    private List<String> privateKeyPaths;

    // 连接丢失标志（非用户主动断开）
    private volatile boolean connectionLost = false;

    public SSHTerminalPane() {
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

        connLabel = new Label("");
        connLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");
        HBox.setMargin(connLabel, new javafx.geometry.Insets(0, 8, 0, 0));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        encodingLabel = new Label("UTF-8");
        encodingLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");

        // SFTP文件浏览器开关按钮
        folderBtn = new Button();
        folderBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
        folderBtn.setGraphic(createIcon("/images/connect/folder.png", false));
        folderBtn.setOnAction(e -> toggleFileBrowser());

        // 监控视图开关按钮
        monitorBtn = new Button();
        monitorBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
        monitorBtn.setGraphic(createIcon("/images/connect/monitor.png", false));
        monitorBtn.setOnAction(e -> toggleMonitor());

        statusBar.getChildren().addAll(statusDot, stateLabel, connLabel, encodingLabel, spacer, folderBtn, monitorBtn);

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
            if (updatingScrollbar) return; // 防止循环
            // value从顶部算：value越大越靠近底部
            // offset从底部算：scrollbackSize - (value - scrollbackSize + visibleRows - 1)
            double visibleAmt = scrollBar.getVisibleAmount();
            int maxScrollOffset = (int) (scrollBar.getMax() - visibleAmt + 1);
            int offset = maxScrollOffset - (int) (newVal.doubleValue() + 0.5);
            terminalView.setScrollOffset(Math.max(0, Math.min(offset, emulator.getScrollbackSize())));
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

        // 滚动条回调：更新滚动条状态
        terminalView.setScrollbarHandler((scrollbackSize, scrollOffset, visibleRows) -> {
            Platform.runLater(() -> {
                updatingScrollbar = true; // 防止循环
                try {
                    if (scrollbackSize > 0) {
                        scrollBar.setVisible(true);
                        // 总内容 = scrollbackSize + visibleRows
                        int totalContent = scrollbackSize + visibleRows;
                        scrollBar.setMin(0);
                        scrollBar.setMax(totalContent - 1);
                        scrollBar.setVisibleAmount(visibleRows);
                        // value从顶部算：scrollbackSize - offset + (visibleRows - 1)
                        // offset=0(底部)时 value = scrollbackSize + visibleRows - 1
                        // offset=scrollbackSize(顶部)时 value = visibleRows - 1
                        scrollBar.setValue(scrollbackSize - scrollOffset + visibleRows - 1);
                    } else {
                        scrollBar.setVisible(false);
                    }
                } finally {
                    updatingScrollbar = false;
                }
            });
        });

        // 右侧面板：文件浏览器 + 监控面板（垂直排列）
        rightPanel = new javafx.scene.layout.VBox();
        rightPanel.setStyle("-fx-background-color: #FFFFFF;");

        // SplitPane: 终端 + 右侧面板，支持拖拽调整宽度
        splitPane = new SplitPane();
        splitPane.getItems().add(terminalPane);
        splitPane.setDividerPositions(1.0);
//        splitPane.setStyle("-fx-background-color: #1e1e1e;");

        setCenter(splitPane);
        setBottom(statusBar);
        setStyle("-fx-background-color: #1e1e1e;");

        // 关键：默认maxWidth/maxHeight=USE_COMPUTED_SIZE=prefSize=0
        // 必须设为MAX_VALUE，否则任何布局容器都不会给它分配空间
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setPrefWidth(800);
        setPrefHeight(600);

        // 设置终端响应回调（DA查询、DSR查询等需要回传数据）
        emulator.setResponseHandler(data -> {
            if (sshSession != null && sshSession.isConnected()) {
                try {
                    OutputStream os = sshSession.getOutputStream();
                    if (os != null) {
                        os.write(data);
                        os.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        // 设置OSC7目录变化回调，通知文件浏览器跟随
        emulator.setCwdChangeListener(path -> {
            if (fileBrowser != null) {
                fileBrowser.onTerminalCwdChanged(path);
            }
        });

        // 终端大小变化时通知SSH服务器
        terminalView.setResizeHandler((cols, rows, width, height) -> {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.resize(cols, rows, width, height);
            }
        });

        // 设置键盘输入回调
        terminalView.setKeyInputHandler(data -> {
            // 连接丢失时，按回车重新连接
            if (connectionLost) {
                if (data.length == 1 && (data[0] == '\r' || data[0] == '\n')) {
                    reconnect();
                }
                return;
            }
            if (sshSession == null || !sshSession.isConnected()) return;
            if (inZModemMode) {
                // ZModem传输中，Ctrl+C取消
                if (data.length == 1 && data[0] == 0x03) {
                    try { if (zmodem != null) zmodem.cancel(); } catch (IOException ignored) {}
                }
                return;
            }
            try {
                OutputStream os = sshSession.getOutputStream();
                if (os != null) {
                    os.write(data);
                    os.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        // 右键菜单（CRT风格）
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

        // 右键弹出菜单
        setOnContextMenuRequested(e -> {
            copyItem.setDisable(!terminalView.hasSelection());
            copyPasteItem.setDisable(!terminalView.hasSelection());
            clearItem.setDisable(!terminalView.hasSelection());
            contextMenu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // 点击其他位置时隐藏右键菜单
        setOnMousePressed(e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });
        // Canvas会拦截鼠标事件，需要在terminalView上也监听
        // 使用addEventHandler而非setOnMousePressed，避免覆盖TerminalView中的选择逻辑
        terminalView.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });
    }

    /**
     * 切换文件浏览器显示
     */
    private void toggleFileBrowser() {
        if (fileBrowserVisible) {
            // 关闭文件浏览器
            if (fileBrowser != null && rightPanel.getChildren().contains(fileBrowser)) {
                rightPanel.getChildren().remove(fileBrowser);
            }
            fileBrowserVisible = false;
            folderBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
            folderBtn.setGraphic(createIcon("/images/connect/folder.png", false));
            updateRightPanelVisibility();
        } else {
            // 打开文件浏览器
            if (sshSession == null || !sshSession.isConnected()) return;
            if (fileBrowser == null) {
                sftpClient = new SFTPClient();
                fileBrowser = new SFTPFileBrowser(sshSession, sftpClient);
            }
            ensureRightPanelVisible();
            if (!rightPanel.getChildren().contains(fileBrowser)) {
                rightPanel.getChildren().add(0, fileBrowser);
            }
            splitPane.setDividerPositions(0.7);
            fileBrowser.initConnection();
            fileBrowserVisible = true;
            folderBtn.setStyle("-fx-background-color: #e0e0e0; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand; -fx-border-radius: 3;");
            folderBtn.setGraphic(createIcon("/images/connect/folder.png", true));
        }
    }

    /**
     * 切换监控视图显示
     */
    private void toggleMonitor() {
        if (monitorVisible) {
            // 关闭监控视图
            if (monitorPanel != null) {
                monitorPanel.stopMonitoring();
                if (rightPanel.getChildren().contains(monitorPanel)) {
                    rightPanel.getChildren().remove(monitorPanel);
                }
            }
            monitorVisible = false;
            monitorBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
            monitorBtn.setGraphic(createIcon("/images/connect/monitor.png", false));
            updateRightPanelVisibility();
        } else {
            // 打开监控视图
            if (sshSession == null || !sshSession.isConnected()) return;
            if (monitorPanel == null) {
                monitorPanel = new MonitorPanel(sshSession);
            }
            ensureRightPanelVisible();
            if (!rightPanel.getChildren().contains(monitorPanel)) {
                rightPanel.getChildren().add(monitorPanel);
            }
            monitorPanel.startMonitoring();
            monitorVisible = true;
            monitorBtn.setStyle("-fx-background-color: #e0e0e0; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand; -fx-border-radius: 3;");
            monitorBtn.setGraphic(createIcon("/images/connect/monitor.png", true));
        }
    }

    /**
     * 确保右侧面板在SplitPane中可见
     */
    private void ensureRightPanelVisible() {
        if (!splitPane.getItems().contains(rightPanel)) {
            splitPane.getItems().add(rightPanel);
            if (fileBrowserVisible && monitorVisible) {
                splitPane.setDividerPositions(0.7);
            } else {
                splitPane.setDividerPositions(0.8);
            }
        }
    }

    /**
     * 更新右侧面板的可见性
     */
    private void updateRightPanelVisibility() {
        if (!fileBrowserVisible && !monitorVisible) {
            if (splitPane.getItems().contains(rightPanel)) {
                splitPane.getItems().remove(rightPanel);
            }
        } else if (!splitPane.getItems().contains(rightPanel)) {
            splitPane.getItems().add(rightPanel);
            splitPane.setDividerPositions(fileBrowserVisible && monitorVisible ? 0.7 : 0.8);
        }
    }

    /**
     * 设置回滚行数
     */
    public void setScrollbackLines(int lines) {
        emulator.setMaxScrollback(lines);
    }

    /**
     * 连接SSH
     */
    public void connect(String host, int port, String username, String password) throws Exception {
        connect(host, port, username, password, (List<String>) null);
    }

    public void connect(String host, int port, String username, String password, String privateKeyPath) throws Exception {
        connect(host, port, username, password, privateKeyPath != null ? List.of(privateKeyPath) : null);
    }

    public void connect(String host, int port, String username, String password, List<String> privateKeyPaths) throws Exception {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPaths = privateKeyPaths;
        this.connectionLost = false;

        sshSession = new SSHSession(host, port, username, password, privateKeyPaths);
        sshSession.connect();
        running.set(true);

        updateStatusBar("已连接");

        // 启用调试日志（写入/tmp/terminal_debug.log）
        try {
            PrintWriter pw = new PrintWriter(new FileWriter("/tmp/terminal_debug.log"));
            emulator.setDebugWriter(line -> {
                pw.print(line);
                pw.flush();
            });
        } catch (Exception ignored) {}

        // 通知SSH服务器终端大小
        sshSession.resize(emulator.getCols(), emulator.getRows(),
                (int) terminalView.getCharWidth() * emulator.getCols(),
                (int) terminalView.getCharHeight() * emulator.getRows());

        startReadThread();
        // requestFocus必须在FX线程执行
        Platform.runLater(() -> terminalView.requestFocus());
    }

    /**
     * 更新状态栏
     */
    private void updateStatusBar(String state) {
        Platform.runLater(() -> {
            boolean connected = state.equals("已连接") || state.startsWith("ZModem");
            statusDot.setFill(connected ? Color.valueOf("#4CAF50") : Color.RED);
            stateLabel.setText(state);
            if (host != null) {
                connLabel.setText(username + "@" + host + ":" + port);
            }
        });
    }

    /**
     * 创建图标ImageView
     * @param path 图标资源路径
     * @param active 是否激活状态
     */
    private ImageView createIcon(String path, boolean active) {
        Image image = new Image(getClass().getResourceAsStream(path));
        ImageView iv = new ImageView(image);
        iv.setFitWidth(16);
        iv.setFitHeight(16);
        iv.setOpacity(active ? 1.0 : 0.6);
        return iv;
    }

    /**
     * 粘贴剪贴板内容到终端
     */
    private void doPaste() {
        if (sshSession == null || !sshSession.isConnected()) return;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            String text = clipboard.getString();
            if (text != null && !text.isEmpty()) {
                // 将换行符转换为回车，适配终端输入
                text = text.replace("\r\n", "\r").replace("\n", "\r");
                try {
                    OutputStream os = sshSession.getOutputStream();
                    if (os != null) {
                        os.write(text.getBytes());
                        os.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                terminalView.clearSelection();
            }
        }
    }

    /**
     * 断开连接（用户主动关闭标签时调用）
     */
    public void disconnect() {
        running.set(false);
        connectionLost = false;
        terminalView.stopBlink();
        if (zmodem != null) {
            try { zmodem.cancel(); } catch (IOException ignored) {}
        }
        if (readThread != null) {
            readThread.interrupt();
        }
        if (sftpClient != null) {
            sftpClient.disconnect();
        }
        if (sshSession != null) {
            sshSession.disconnect();
            sshSession = null;
        }
        // 关闭文件浏览器
        if (fileBrowser != null) {
            splitPane.getItems().remove(fileBrowser);
        }
        fileBrowserVisible = false;
        fileBrowser = null;
        sftpClient = null;

        // 关闭监控视图
        if (monitorPanel != null) {
            monitorPanel.stopMonitoring();
            if (rightPanel.getChildren().contains(monitorPanel)) {
                rightPanel.getChildren().remove(monitorPanel);
            }
        }
        monitorVisible = false;
        monitorPanel = null;

        updateStatusBar("已断开");
    }

    /**
     * 重新连接
     */
    private void reconnect() {
        if (host == null || password == null) return;
        connectionLost = false;
        updateStatusBar("重新连接中...");

        new Thread(() -> {
            try {
                // 清理旧会话
                if (sftpClient != null) {
                    sftpClient.disconnect();
                }
                if (sshSession != null) {
                    sshSession.disconnect();
                    sshSession = null;
                }
                if (readThread != null) {
                    readThread.interrupt();
                    readThread = null;
                }

                sshSession = new SSHSession(host, port, username, password, privateKeyPaths);
                sshSession.connect();
                running.set(true);

                // 通知SSH服务器终端大小
                sshSession.resize(emulator.getCols(), emulator.getRows(),
                        (int) terminalView.getCharWidth() * emulator.getCols(),
                        (int) terminalView.getCharHeight() * emulator.getRows());

                // 如果文件浏览器打开，重新初始化SFTP
                if (fileBrowserVisible && fileBrowser != null) {
                    sftpClient = new SFTPClient();
                    fileBrowser = new SFTPFileBrowser(sshSession, sftpClient);
                    Platform.runLater(() -> {
                        if (!splitPane.getItems().contains(fileBrowser)) {
                            splitPane.getItems().add(fileBrowser);
                            splitPane.setDividerPositions(0.7);
                        }
                        fileBrowser.initConnection();
                    });
                }

                Platform.runLater(() -> {
                    emulator.process(("\r\n[重新连接成功]\r\n").getBytes());
                    scheduleRender();
                    updateStatusBar("已连接");
                    terminalView.requestFocus();
                });

                startReadThread();
            } catch (Exception e) {
                connectionLost = true;
                Platform.runLater(() -> {
                    emulator.process(("\r\n[重新连接失败: " + e.getMessage() + "]\r\n").getBytes());
                    scheduleRender();
                    updateStatusBar("重连失败 - 按回车重试");
                });
            }
        }, "SSH-Reconnect").start();
    }

    public void setOnDisconnect(Runnable callback) {
        this.onDisconnect = callback;
    }

    public boolean isConnected() {
        return sshSession != null && sshSession.isConnected();
    }

    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[4096];

            while (running.get() && sshSession != null && sshSession.isConnected()) {
                try {
                    InputStream is = sshSession.getInputStream();
                    if (is == null) break;

                    int len = is.read(buffer);
                    if (len == -1) break;

                    // 检测ZModem协议前缀
                    int zmodemStart = indexOfZModem(buffer, len);
                    if (zmodemStart != -1 && !inZModemMode) {
                        // 先处理ZModem前缀之前的数据
                        if (zmodemStart > 0) {
                            final byte[] beforeData = new byte[zmodemStart];
                            System.arraycopy(buffer, 0, beforeData, 0, zmodemStart);
                            Platform.runLater(() -> {
                                emulator.process(beforeData);
                                scheduleRender();
                            });
                        }

                        // 解析ZModem帧类型: ** ZDLE B frame_type
                        // frame[5]: 48='0'=sz, 49='1'=rz
                        byte[] frame = new byte[len - zmodemStart];
                        System.arraycopy(buffer, zmodemStart, frame, 0, frame.length);
                        boolean isSz = frame.length > 5 && frame[5] == 48;

                        // 创建ZModem输入流（将帧数据预存到缓冲）
                        ZModemInputStream zmodemIn = new ZModemInputStream(is, frame);

                        if (isSz) {
                            handleSzDownload(zmodemIn, sshSession.getOutputStream());
                        } else {
                            handleRzUpload(zmodemIn, sshSession.getOutputStream());
                        }
                        continue;
                    }

                    // 普通输出，交给终端模拟器处理
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

            // 连接丢失，显示提示并等待用户按回车重连
            connectionLost = true;
            Platform.runLater(() -> {
                emulator.process(("\r\n[连接已断开 - 按回车重新连接]\r\n").getBytes());
                scheduleRender();
                updateStatusBar("已断开 - 按回车重连");
            });
        }, "SSH-Read-Thread");
        readThread.setDaemon(true);
        readThread.start();
    }

    /**
     * 节流渲染，避免每收到一个字节就渲染一次
     */
    private void scheduleRender() {
        long now = System.currentTimeMillis();
        if (now - lastRenderTime >= RENDER_INTERVAL) {
            lastRenderTime = now;
            terminalView.render();
            renderPending = false;
        } else if (!renderPending) {
            renderPending = true;
            // 延迟渲染
            javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(RENDER_INTERVAL - (now - lastRenderTime)));
            delay.setOnFinished(e -> {
                lastRenderTime = System.currentTimeMillis();
                terminalView.render();
                renderPending = false;
            });
            delay.play();
        }
    }

    /**
     * 处理rz上传文件（远端执行rz，本地发送文件给远端）
     */
    private void handleRzUpload(ZModemInputStream zmodemIn, OutputStream outputStream) {
        inZModemMode = true;
        updateStatusBar("ZModem 上传中...");
        Platform.runLater(() -> {
            emulator.process(("\r\n[ZModem] 检测到rz上传请求，请选择要上传的文件...\r\n").getBytes());
            scheduleRender();
        });

        try {
            zmodem = new ZModem(zmodemIn, outputStream);
            List<File> selectedFiles = openFileDialog();
            if (selectedFiles == null || selectedFiles.isEmpty()) {
                Platform.runLater(() -> {
                    emulator.process(("\r\n[ZModem] 未选择文件，取消上传\r\n").getBytes());
                    scheduleRender();
                });
                zmodem.cancel();
                return;
            }

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 正在上传文件...\r\n").getBytes());
                scheduleRender();
            });

            zmodem.send(() -> {
                List<FileAdapter> files = new ArrayList<>();
                for (File f : selectedFiles) {
                    files.add(new CustomFile(f));
                }
                return files;
            });

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 上传完成\r\n").getBytes());
                scheduleRender();
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 上传失败: " + e.getMessage() + "\r\n").getBytes());
                scheduleRender();
            });
            e.printStackTrace();
        } finally {
            inZModemMode = false;
            zmodem = null;
            updateStatusBar("已连接");
        }
    }

    /**
     * 处理sz下载文件（远端执行sz，本地接收远端文件）
     */
    private void handleSzDownload(ZModemInputStream zmodemIn, OutputStream outputStream) {
        inZModemMode = true;
        updateStatusBar("ZModem 下载中...");
        Platform.runLater(() -> {
            emulator.process(("\r\n[ZModem] 检测到sz下载请求，请选择保存目录...\r\n").getBytes());
            scheduleRender();
        });

        try {
            zmodem = new ZModem(zmodemIn, outputStream);
            File saveDir = openDirDialog();
            if (saveDir == null) {
                Platform.runLater(() -> {
                    emulator.process(("\r\n[ZModem] 未选择保存目录，取消下载\r\n").getBytes());
                    scheduleRender();
                });
                zmodem.cancel();
                return;
            }
            if (!saveDir.exists()) saveDir.mkdirs();

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 正在下载文件到: " + saveDir.getAbsolutePath() + "\r\n").getBytes());
                scheduleRender();
            });

            zmodem.receive(() -> new CustomFile(saveDir));

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 下载完成\r\n").getBytes());
                scheduleRender();
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 下载失败: " + e.getMessage() + "\r\n").getBytes());
                scheduleRender();
            });
            e.printStackTrace();
        } finally {
            inZModemMode = false;
            zmodem = null;
            updateStatusBar("已连接");
        }
    }

    private List<File> openFileDialog() {
        CompletableFuture<List<File>> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("选择要上传的文件");
                fileChooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("所有文件", "*.*")
                );
                List<File> files = fileChooser.showOpenMultipleDialog(getStage());
                future.complete(files != null ? files : List.of());
            } catch (Exception e) {
                future.complete(List.of());
            }
        });
        try {
            return future.get();
        } catch (Exception e) {
            return List.of();
        }
    }

    private File openDirDialog() {
        CompletableFuture<File> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
                dirChooser.setTitle("选择保存目录");
                File dir = dirChooser.showDialog(getStage());
                future.complete(dir);
            } catch (Exception e) {
                future.complete(null);
            }
        });
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }

    private Stage getStage() {
        return (Stage) getScene().getWindow();
    }

    /**
     * 在buffer中查找ZModem协议前缀位置
     */
    private static int indexOfZModem(byte[] buffer, int len) {
        if (len < ZMODEM_PREFIX.length) return -1;
        for (int i = 0; i <= len - ZMODEM_PREFIX.length; i++) {
            boolean match = true;
            for (int j = 0; j < ZMODEM_PREFIX.length; j++) {
                if ((buffer[i + j] & 0xFF) != ZMODEM_PREFIX[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    /**
     * ZModem输入流，将已读取的帧数据预存到缓冲
     */
    private static class ZModemInputStream extends InputStream {
        private final InputStream input;
        private final List<Byte> buffer;

        public ZModemInputStream(InputStream input, byte[] initialData) {
            this.input = input;
            this.buffer = new ArrayList<>();
            for (byte b : initialData) {
                this.buffer.add(b);
            }
        }

        @Override
        public int read() throws IOException {
            if (!buffer.isEmpty()) {
                return buffer.removeFirst() & 0xFF;
            }
            return input.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!buffer.isEmpty()) {
                int count = 0;
                while (!buffer.isEmpty() && count < len) {
                    b[off + count] = buffer.removeFirst();
                    count++;
                }
                return count;
            }
            return input.read(b, off, len);
        }
    }

    /**
     * 获取终端模拟器
     */
    public TerminalEmulator getEmulator() {
        return emulator;
    }

    /**
     * 获取终端视图
     */
    public TerminalView getTerminalView() {
        return terminalView;
    }

    /**
     * 导出终端缓冲区内容（调试用）
     */
    public String dumpBuffer() {
        return emulator.dumpBuffer();
    }
}
