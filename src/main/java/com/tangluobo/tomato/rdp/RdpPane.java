package com.tangluobo.tomato.rdp;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * RDP远程桌面JavaFX容器组件
 * 通过SwingNode嵌入sshtools RDP库的Swing渲染组件
 */
public class RdpPane extends BorderPane {

    private static final Logger logger = Logger.getLogger(RdpPane.class.getName());

    private RdpClient rdpClient;
    private SwingNode swingNode;
    private volatile JScrollPane desktopScrollPane;
    private volatile JComponent desktopDisplay;

    // 状态栏组件
    private HBox statusBar;
    private Circle statusDot;
    private Label stateLabel;
    private Label connLabel;
    private Label resolutionLabel;

    // 全屏支持
    private Tab ownerTab;                 // 所属tab（全屏还原时放回）
    private Stage fullScreenStage;        // 非null表示当前处于全屏
    private StackPane fullScreenRoot;
    private HBox exitBar;                 // 顶部悬浮"退出全屏"按钮（mstsc风格）
    private PauseTransition hideExitBarTimer;
    private TranslateTransition exitBarSlide;

    // 连接信息
    private String host;
    private int port;
    private String username;
    private String password;
    private String domain;
    private int screenWidth;
    private int screenHeight;
    private int colorDepth;

    public RdpPane() {
        rdpClient = new RdpClient();
        initializeUI();
    }

    private void initializeUI() {
        // 中心：SwingNode嵌入RDP渲染
        swingNode = new SwingNode();
        setCenter(swingNode);
        // SwingNode does not reliably propagate JavaFX layout changes to a nested
        // JScrollPane. Keep the Swing viewport in sync so resize exposes/repaints
        // the newly visible desktop area immediately.
        swingNode.layoutBoundsProperty().addListener((obs, oldValue, newValue) -> resizeDesktopViewport());

        // 底部：状态栏
        statusBar = createStatusBar();
        setBottom(statusBar);

        // 设置样式
        getStyleClass().add("rdp-pane");
    }

    private HBox createStatusBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        // 状态指示灯
        statusDot = new Circle(5);
        statusDot.setFill(Color.GRAY);
        bar.getChildren().add(statusDot);

        // 状态标签
        stateLabel = new Label("未连接");
        stateLabel.setStyle("-fx-font-size: 11px;");
        bar.getChildren().add(stateLabel);

        // 分隔
        Label sep1 = new Label("|");
        sep1.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        bar.getChildren().add(sep1);

        // 连接信息
        connLabel = new Label("");
        connLabel.setStyle("-fx-font-size: 11px;");
        bar.getChildren().add(connLabel);

        // 弹性空间
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().add(spacer);

        // 分辨率标签
        resolutionLabel = new Label("");
        resolutionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        bar.getChildren().add(resolutionLabel);

        return bar;
    }

    /**
     * 连接到RDP服务器
     *
     * @param mapClipboard 是否启用剪贴板同步（本地与远程桌面互拷文本）
     * @param enableSound  是否启用远程音频重定向（远程桌面声音在本地播放）
     */
    public void connect(String host, int port, String username, String password,
                        String domain, int screenWidth, int screenHeight, int colorDepth,
                        boolean useSsl, boolean mapClipboard, boolean enableSound) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.domain = domain;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.colorDepth = colorDepth;

        // 更新状态栏
        updateStatus(ConnectionState.CONNECTING);
        connLabel.setText(username + "@" + host + ":" + port);
        resolutionLabel.setText(screenWidth + "x" + screenHeight + " @" + colorDepth);

        // 先显示加载占位面板（Swing组件在EDT创建，SwingNode.setContent在JavaFX线程）
        SwingUtilities.invokeLater(() -> {
            JPanel loadingPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER));
            loadingPanel.setBackground(java.awt.Color.WHITE);
            JLabel loadingLabel = new JLabel("正在连接到 " + host + " ...");
            loadingLabel.setFont(loadingLabel.getFont().deriveFont(java.awt.Font.PLAIN, 14));
            loadingPanel.add(loadingLabel);
            Platform.runLater(() -> swingNode.setContent(loadingPanel));
        });

        // 设置连接就绪回调 - 连接成功后才设置画布到SwingNode
        rdpClient.setOnConnected(v -> {
            final JComponent displayComponent = rdpClient.getDisplayComponent();
            if (displayComponent == null) {
                logger.warning("RDP显示组件为null，无法显示");
                Platform.runLater(() -> updateStatus(ConnectionState.ERROR));
                return;
            }
            logger.info("RDP显示组件: " + displayComponent.getClass().getSimpleName()
                    + " size=" + displayComponent.getSize()
                    + " prefSize=" + displayComponent.getPreferredSize());
            // 关键：禁用输入法。javardp的字符键依赖KEY_TYPED事件（keyChar查映射），
            // 中文输入法会拦截/组合按键导致keyChar异常或缺失，出现按键错误。
            // 禁用后按键直接透传AWT事件，不经过输入法（远程桌面客户端标准做法）。
            displayComponent.enableInputMethods(false);

            // 在EDT上用JScrollPane包装显示组件（WrappedImage实现了Scrollable）
            SwingUtilities.invokeLater(() -> {
                displayComponent.setSize(displayComponent.getPreferredSize());
                JScrollPane scrollPane = new JScrollPane(displayComponent);
                scrollPane.setBackground(java.awt.Color.BLACK);
                scrollPane.getViewport().setBackground(java.awt.Color.BLACK);
                // 去掉Metal LAF默认的四边线边框（全屏时会呈现为屏幕四周的白线）
                scrollPane.setBorder(null);
                scrollPane.getViewport().setBorder(null);
                scrollPane.setDoubleBuffered(true);
                desktopDisplay = displayComponent;
                desktopScrollPane = scrollPane;
                // 在JavaFX Application Thread上设置SwingNode内容
                Platform.runLater(() -> {
                    swingNode.setContent(scrollPane);
                    resizeDesktopViewport();
                    SwingUtilities.invokeLater(() -> {
                        displayComponent.revalidate();
                        displayComponent.repaint();
                        displayComponent.requestFocusInWindow();
                    });
                    // DISPLAY ready 仅说明协议握手已完成；等待首个 bitmap 后再显示已连接。
                });
            });
            // 诊断日志已在RdpPatch.processBitmapUpdates中实现，此处不再重复
        });

        // 设置断开回调
        rdpClient.setOnDisconnected(reason -> {
            Platform.runLater(() -> {
                updateStatus(ConnectionState.DISCONNECTED);
                stateLabel.setText("已断开: " + reason);
            });
        });

        rdpClient.setOnFirstFrame(v -> Platform.runLater(() -> updateStatus(ConnectionState.CONNECTED)));

        // 在EDT中初始化RDP连接（画布不在SwingNode中显示，直到onConnected回调）
        SwingUtilities.invokeLater(() -> {
            try {
                rdpClient.connect(host, port, username, password, domain,
                        screenWidth, screenHeight, colorDepth, useSsl, mapClipboard, enableSound);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "RDP连接失败: " + e.getMessage(), e);
                Platform.runLater(() -> {
                    updateStatus(ConnectionState.ERROR);
                    stateLabel.setText("连接失败: " + e.getMessage());
                });
            }
        });
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        // 断开前先退出全屏，把组件还原到tab中，避免全屏窗口残留
        if (fullScreenStage != null) {
            exitFullScreen();
        }
        if (rdpClient != null) {
            rdpClient.disconnect();
        }
        SwingUtilities.invokeLater(() -> swingNode.setContent(null));
        desktopScrollPane = null;
        desktopDisplay = null;
        updateStatus(ConnectionState.DISCONNECTED);
    }

    /**
     * 查询连接状态
     */
    public boolean isConnected() {
        return rdpClient != null && rdpClient.isConnected();
    }

    /**
     * 请求焦点（确保键盘事件正确路由到RDP画布）
     */
    public void requestRdpFocus() {
        if (swingNode != null) {
            swingNode.requestFocus();
        }
    }

    // ==================== 全屏支持 ====================

    /**
     * 设置所属tab（全屏还原时需要把组件放回tab）
     */
    public void setOwnerTab(Tab ownerTab) {
        this.ownerTab = ownerTab;
    }

    public boolean isFullScreen() {
        return fullScreenStage != null;
    }

    /**
     * 切换全屏/窗口模式
     */
    public void toggleFullScreen() {
        if (fullScreenStage != null) {
            exitFullScreen();
        } else {
            enterFullScreen();
        }
    }

    /**
     * 进入全屏：把RDP视图从tab移到独立全屏窗口，隐藏状态栏。
     * 鼠标靠近屏幕顶部边沿时滑出"退出全屏"悬浮按钮（mstsc风格）。
     */
    private void enterFullScreen() {
        if (ownerTab == null || fullScreenStage != null) {
            return;
        }
        // tab内容用占位面板顶替，保持tab结构不变
        ownerTab.setContent(new StackPane());
        // 全屏时隐藏状态栏，只显示远程桌面
        setBottom(null);
        // 全屏期间背景全黑：缩放取整产生的边缘缝隙显示为黑色而不是浅色底
        setStyle("-fx-background-color: black;");

        // 顶部悬浮"退出全屏"按钮
        exitBar = new HBox();
        exitBar.setAlignment(Pos.CENTER);
        exitBar.setStyle("-fx-background-color: rgba(0,120,215,0.92); -fx-background-radius: 0 0 8 8;"
                + " -fx-padding: 5 14 5 14; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 6, 0.3, 0, 2);");
        Button exitBtn = new Button("退出全屏");
        exitBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 12px;"
                + " -fx-padding: 2 4 2 4; -fx-cursor: hand;");
        exitBtn.setOnAction(e -> exitFullScreen());
        exitBar.getChildren().add(exitBtn);
        exitBar.setVisible(false);
        exitBar.setManaged(false);
        exitBar.setTranslateY(-40);
        // 鼠标悬停在按钮上时保持显示，移开后计时隐藏
        exitBar.setOnMouseEntered(e -> { if (hideExitBarTimer != null) hideExitBarTimer.stop(); });
        exitBar.setOnMouseExited(e -> { if (hideExitBarTimer != null) hideExitBarTimer.playFromStart(); });

        hideExitBarTimer = new PauseTransition(Duration.millis(2500));
        hideExitBarTimer.setOnFinished(e -> hideExitBar());

        // 顶部透明感应条：SwingNode会拦截鼠标事件导致Scene过滤器收不到，
        // 用一个覆盖顶部5px的FX节点通过MOUSE_ENTERED可靠触发退出按钮
        Region topEdgeTrigger = new Region();
        topEdgeTrigger.setPrefHeight(5);
        topEdgeTrigger.setMaxHeight(5);
        topEdgeTrigger.setStyle("-fx-background-color: transparent;");
        topEdgeTrigger.setOnMouseEntered(e -> showExitBar());
        topEdgeTrigger.setOnMouseMoved(e -> showExitBar());
        StackPane.setAlignment(topEdgeTrigger, Pos.TOP_CENTER);

        fullScreenRoot = new StackPane(this);
        fullScreenRoot.setStyle("-fx-background-color: black;");
        fullScreenRoot.getChildren().addAll(topEdgeTrigger, exitBar);
        // managed=false时StackPane不再自动布局exitBar，手动绑定顶部居中定位
        // （layoutY保持0贴顶，隐藏态通过translateY滑出屏幕外实现）
        exitBar.layoutXProperty().bind(
                fullScreenRoot.widthProperty().subtract(exitBar.widthProperty()).divide(2));

        Scene scene = new Scene(fullScreenRoot, Color.BLACK);
        // 兜底：鼠标靠近顶部边沿（5px内）时显示退出按钮
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (e.getSceneY() <= 5) {
                showExitBar();
            }
        });

        fullScreenStage = new Stage();
        fullScreenStage.setFullScreen(true);
        fullScreenStage.setTitle(ownerTab.getText() != null ? ownerTab.getText() : "远程桌面");
        fullScreenStage.setScene(scene);
        // 全屏窗口定位到主窗口所在屏幕（多显示器时与tab所在屏一致）
        try {
            Scene mainScene = ownerTab.getTabPane().getScene();
            if (mainScene != null && mainScene.getWindow() != null) {
                Window mainWin = mainScene.getWindow();
                Rectangle2D probe = new Rectangle2D(mainWin.getX(), mainWin.getY(), 1, 1);
                for (Screen screen : Screen.getScreensForRectangle(probe)) {
                    fullScreenStage.setX(screen.getBounds().getMinX());
                    fullScreenStage.setY(screen.getBounds().getMinY());
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        // 用户按Esc等退出JavaFX全屏状态时，同步还原到tab
        fullScreenStage.fullScreenProperty().addListener((obs, was, is) -> {
            if (!is) {
                exitFullScreen();
            }
        });
        fullScreenStage.setOnCloseRequest(e -> exitFullScreen());
        fullScreenStage.show();
        // 立即应用全屏视口（隐藏滚动条+缩放铺满），后续布局变化由layoutBounds监听器同步
        resizeDesktopViewport();
        requestRdpFocus();
    }

    /**
     * 退出全屏：关闭全屏窗口，把RDP视图还原到tab并恢复状态栏
     */
    private void exitFullScreen() {
        Stage stage = fullScreenStage;
        if (stage == null) {
            return;
        }
        fullScreenStage = null;
        fullScreenRoot = null;
        exitBar = null;
        hideExitBarTimer = null;
        exitBarSlide = null;
        // 恢复缩放（窗口模式1:1显示）
        if (swingNode != null) {
            swingNode.getTransforms().clear();
        }
        // 恢复滚动条策略
        final JScrollPane scrollPane = desktopScrollPane;
        if (scrollPane != null) {
            SwingUtilities.invokeLater(() -> {
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            });
        }
        // 恢复状态栏和tab内容
        setBottom(statusBar);
        setStyle(null);
        if (ownerTab != null && ownerTab.getTabPane() != null) {
            ownerTab.setContent(this);
        }
        try {
            stage.close();
        } catch (Exception ignored) {
        }
        resizeDesktopViewport();
        requestRdpFocus();
    }

    private void showExitBar() {
        if (exitBar == null || exitBar.isVisible()) {
            return;
        }
        exitBar.setVisible(true);
        if (exitBarSlide != null) {
            exitBarSlide.stop();
        }
        exitBarSlide = new TranslateTransition(Duration.millis(200), exitBar);
        exitBarSlide.setToY(0);
        exitBarSlide.play();
        if (hideExitBarTimer != null) {
            hideExitBarTimer.playFromStart();
        }
    }

    private void hideExitBar() {
        if (exitBar == null || !exitBar.isVisible()) {
            return;
        }
        if (exitBarSlide != null) {
            exitBarSlide.stop();
        }
        double hideY = -(exitBar.getHeight() + 8);
        exitBarSlide = new TranslateTransition(Duration.millis(200), exitBar);
        exitBarSlide.setToY(hideY);
        exitBarSlide.setOnFinished(e -> {
            if (exitBar != null) {
                exitBar.setVisible(false);
            }
        });
        exitBarSlide.play();
    }

    private void resizeDesktopViewport() {
        if (swingNode == null) {
            return;
        }
        final int width = Math.max(1, (int) Math.ceil(swingNode.getLayoutBounds().getWidth()));
        final int height = Math.max(1, (int) Math.ceil(swingNode.getLayoutBounds().getHeight()));
        final JScrollPane scrollPane = desktopScrollPane;
        final JComponent display = desktopDisplay;
        if (scrollPane == null || display == null) {
            return;
        }
        if (fullScreenStage != null) {
            // 全屏模式：视口与远程桌面同尺寸并隐藏滚动条（画面不裁剪），
            // 通过swingNode上的Scale变换等比缩放铺满整个屏幕
            SwingUtilities.invokeLater(() -> {
                java.awt.Dimension canvasSize = display.getPreferredSize();
                scrollPane.setPreferredSize(canvasSize);
                scrollPane.setSize(canvasSize);
                scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                scrollPane.doLayout();
                scrollPane.revalidate();
                display.revalidate();
                display.repaint();
                scrollPane.getViewport().revalidate();
                scrollPane.getViewport().repaint();
                scrollPane.repaint();
            });
            applyFullScreenScale();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            java.awt.Dimension viewportSize = new java.awt.Dimension(width, height);
            scrollPane.setPreferredSize(viewportSize);
            scrollPane.setSize(viewportSize);
            scrollPane.doLayout();
            scrollPane.revalidate();
            display.revalidate();
            display.repaint();
            scrollPane.getViewport().revalidate();
            scrollPane.getViewport().repaint();
            scrollPane.repaint();
        });
    }

    /**
     * 全屏时把远程桌面画面等比缩放铺满屏幕（保持宽高比、居中显示，无滚动条）
     */
    private void applyFullScreenScale() {
        if (swingNode == null || fullScreenStage == null) {
            return;
        }
        double areaW = swingNode.getLayoutBounds().getWidth();
        double areaH = swingNode.getLayoutBounds().getHeight();
        if (areaW <= 0 || areaH <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        double scale = Math.min(areaW / screenWidth, areaH / screenHeight);
        if (!Double.isFinite(scale) || scale <= 0) {
            return;
        }
        swingNode.getTransforms().clear();
        if (Math.abs(scale - 1.0) > 0.001) {
            // 以swingNode中心为轴缩放，保持画面居中
            swingNode.getTransforms().add(new Scale(scale, scale, areaW / 2, areaH / 2));
        }
    }

    private void updateStatus(ConnectionState state) {
        switch (state) {
            case DISCONNECTED:
                statusDot.setFill(Color.GRAY);
                stateLabel.setText("未连接");
                break;
            case CONNECTING:
                statusDot.setFill(Color.ORANGE);
                stateLabel.setText("连接中...");
                break;
            case CONNECTED:
                statusDot.setFill(Color.GREEN);
                stateLabel.setText("已连接");
                break;
            case ERROR:
                statusDot.setFill(Color.RED);
                stateLabel.setText("连接失败");
                break;
        }
    }

    private enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }
}
