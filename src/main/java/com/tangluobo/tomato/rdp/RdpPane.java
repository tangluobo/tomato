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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

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
    private Circle statusDot;
    private Label stateLabel;
    private Label connLabel;
    private Label resolutionLabel;

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
        HBox statusBar = createStatusBar();
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
     */
    public void connect(String host, int port, String username, String password,
                        String domain, int screenWidth, int screenHeight, int colorDepth,
                        boolean useSsl) {
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
                        screenWidth, screenHeight, colorDepth, useSsl);
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
