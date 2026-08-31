package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.GlobalConfig;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import com.tangluobo.rdp4j.RdpPane;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.Map;

/**
 * RDP 远程桌面连接处理器。
 * 完整封装 RDP tab 创建、密码输入、连接建立逻辑。
 */
public class RdpConnectHandler implements ConnectHandler {

    /** handler按连接动作创建，因此独立窗口注册表需要跨实例共享（仅在JavaFX线程访问）。 */
    private static final Map<String, Stage> OPEN_WINDOWS = new HashMap<>();
    private static Image mstscWindowIcon;

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.RDP;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        if (isWindowMode(config)) {
            Stage existing = OPEN_WINDOWS.get(config.getId());
            if (existing != null) {
                RdpPane existingPane = existing.getUserData() instanceof RdpPane pane ? pane : null;
                if (shouldReuseRdpWindow(existing.isShowing(),
                        existingPane != null && existingPane.isFullScreen())) {
                    if (existingPane != null && existingPane.isFullScreen()) {
                        existingPane.activateFullScreenWindow();
                    } else {
                        existing.setIconified(false);
                        existing.toFront();
                        existing.requestFocus();
                    }
                    return;
                }
            }
            createRdpView(module, config, true);
            return;
        }
        // 若已有打开的 RDP tab，直接切换选中
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showTerminalView();
                return;
            }
        }
        createRdpView(module, config, false);
    }

    /**
     * 创建 RDP tab 并发起连接
     */
    private void createRdpView(ConnectModule module, ConnectionConfig config, boolean windowMode) {
        String password = config.getPassword();
        if (password == null || password.isEmpty()) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            password = pwdResult.getPassword();
            if (pwdResult.isSavePassword()) {
                config.setPassword(password);
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        RdpPane rdpPane = new RdpPane();
        if (windowMode) {
            rdpPane.suppressInitialWindowScrollBars();
            rdpPane.setHideOwnerWindowInFullScreen(true);
        }
        Tab tab = new Tab(config.getName());
        tab.setContent(rdpPane);
        tab.setUserData(config.getId());
        rdpPane.setOwnerTab(tab);

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem fullScreenItem = new MenuItem("全屏");
        fullScreenItem.setOnAction(e -> rdpPane.toggleFullScreen());
        // 全屏不保留本地键盘快捷键，退出操作由顶部控制栏提供。
        tabContextMenu.setOnShowing(e ->
                fullScreenItem.setText(rdpPane.isFullScreen() ? "退出全屏" : "全屏"));

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) rdpPane.getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            module.saveConnections();
        });

        MenuItem globalConfigItem = new MenuItem("全局配置");
        globalConfigItem.setOnAction(e -> module.openSettingsTabWithRdpSelected());

        tabContextMenu.getItems().addAll(fullScreenItem, sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);

        Stage rdpWindow;
        if (windowMode) {
            TabPane windowTabs = new TabPane(tab);
            // 独立窗口只有一个RDP会话，隐藏无意义的单标签标题栏。
            windowTabs.setStyle("-fx-tab-min-height: 0; -fx-tab-max-height: 0;"
                    + " -fx-padding: 0; -fx-background-insets: 0;"
                    + " -fx-background-color: black; -fx-border-width: 0;");
            rdpWindow = new Stage();
            rdpWindow.setUserData(rdpPane);
            rdpWindow.setTitle(config.getName() != null ? config.getName() : "远程桌面");
            rdpWindow.setScene(new Scene(windowTabs,
                    Math.max(640, config.getScreenWidth()),
                    Math.max(480, config.getScreenHeight() + 32)));
            Stage mainStage = module.getTerminalTabPane().getScene() != null
                    ? (Stage) module.getTerminalTabPane().getScene().getWindow() : null;
            if (mainStage != null) {
                // 不设置owner：拥有窗口在Windows任务栏中会附属于主窗口，无法作为
                // 独立RDP窗口通过任务栏预览切换。优先使用系统mstsc官方图标。
                Image mstscIcon = loadMstscWindowIcon();
                if (mstscIcon != null) {
                    rdpWindow.getIcons().setAll(mstscIcon);
                } else {
                    rdpWindow.getIcons().setAll(mainStage.getIcons());
                }
            }
            OPEN_WINDOWS.put(config.getId(), rdpWindow);
            rdpWindow.setOnHidden(e -> {
                if (rdpPane.isOwnerWindowTemporarilyHiddenForFullScreen()) {
                    return;
                }
                OPEN_WINDOWS.remove(config.getId(), rdpWindow);
                rdpPane.disconnect();
            });
            windowTabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
                if (windowTabs.getTabs().isEmpty()) {
                    rdpWindow.close();
                }
            });
            tab.setOnClosed(e -> rdpWindow.close());
            // 独立RDP窗口的“最大化”直接进入RDP全屏，而不是普通窗口最大化。
            // Windows标题栏最大化按钮和双击标题栏都会更新maximizedProperty。
            rdpWindow.maximizedProperty().addListener((obs, wasMaximized, isMaximized) -> {
                if (isMaximized && !rdpPane.isFullScreen()) {
                    javafx.application.Platform.runLater(() -> {
                        if (rdpWindow.isShowing() && !rdpPane.isFullScreen()) {
                            rdpWindow.setMaximized(false);
                            rdpPane.toggleFullScreen();
                        }
                    });
                }
            });
            rdpWindow.show();
            final boolean[] resizeTracking = {false, false}; // armed, already enabled
            javafx.beans.value.ChangeListener<Number> manualResizeListener = (obs, oldValue, newValue) -> {
                if (resizeTracking[0] && !resizeTracking[1]
                        && Math.abs(newValue.doubleValue() - oldValue.doubleValue()) > 0.5) {
                    // 延迟到本轮窗口状态变化完成后判断，避免把点击最大化进入全屏
                    // 产生的尺寸变化误认为用户手动缩放。
                    javafx.application.Platform.runLater(() -> {
                        if (!resizeTracking[1] && !rdpWindow.isMaximized() && !rdpPane.isFullScreen()) {
                            resizeTracking[1] = true;
                            rdpPane.enableWindowScrollBars();
                        }
                    });
                }
            };
            rdpWindow.widthProperty().addListener(manualResizeListener);
            rdpWindow.heightProperty().addListener(manualResizeListener);
            javafx.application.Platform.runLater(() -> {
                // 仅把隐藏标签作为全屏还原锚点，不让TabPane的标题区、内容区边框
                // 在系统标题栏与远程画面之间留下空白。
                Node header = windowTabs.lookup(".tab-header-area");
                if (header != null) {
                    header.setVisible(false);
                    header.setManaged(false);
                }
                Node content = windowTabs.lookup(".tab-content-area");
                if (content != null) {
                    content.setStyle("-fx-padding: 0; -fx-background-insets: 0;"
                            + " -fx-background-color: black; -fx-border-width: 0;");
                }
                windowTabs.requestLayout();
                javafx.application.Platform.runLater(() ->
                {
                    fitWindowToConfiguredDesktop(rdpWindow, rdpPane, config);
                    javafx.animation.PauseTransition armResize = new javafx.animation.PauseTransition(
                            javafx.util.Duration.millis(300));
                    armResize.setOnFinished(event -> resizeTracking[0] = true);
                    armResize.play();
                });
            });
        } else {
            rdpWindow = null;
            tab.setOnClosed(e -> {
                rdpPane.disconnect();
                if (module.getTerminalTabPane().getTabs().isEmpty()) {
                    module.showWelcomeView();
                }
            });
        }

        if (!windowMode) {
            module.getTerminalTabPane().getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab == tab) {
                    rdpPane.requestRdpFocus();
                }
            });
            module.getTerminalTabPane().getTabs().add(tab);
            module.getTerminalTabPane().getSelectionModel().select(tab);
            module.showTerminalView();
        } else {
            rdpWindow.requestFocus();
            rdpPane.requestRdpFocus();
        }

        int rdpPort = config.getPort() > 0 ? config.getPort() : 3389;
        int width;
        int height;
        if (config.isFullscreen()) {
            // 全屏模式：动态获取主窗口所在屏幕（无窗口时取主屏）大小作为分辨率
            javafx.geometry.Rectangle2D bounds = resolveScreenBounds(module);
            width = (int) Math.floor(bounds.getWidth());
            height = (int) Math.floor(bounds.getHeight());
        } else {
            width = config.getScreenWidth() > 0 ? config.getScreenWidth() : 1024;
            height = config.getScreenHeight() > 0 ? config.getScreenHeight() : 768;
        }
        int bpp = config.getColorDepth() > 0 ? config.getColorDepth() : 24;
        String domain = config.getDomain();

        rdpPane.connect(config.getHost(), rdpPort, config.getUsername(), password,
                domain, width, height, bpp, config.isUseSsl(), config.isMapClipboard(),
                config.isEnableSound());

        if (config.isFullscreen()) {
            // “默认全屏”不仅决定远程桌面分辨率，也决定双击打开后的实际
            // 显示状态。延后一轮，确保独立窗口或标签页已经挂到 Scene。
            javafx.application.Platform.runLater(() -> {
                if (rdpPane.getScene() != null && !rdpPane.isFullScreen()) {
                    rdpPane.toggleFullScreen();
                }
            });
        }
    }

    private static boolean isWindowMode(ConnectionConfig config) {
        String mode = config.getRdpOpenMode();
        if (mode == null || mode.isBlank()) {
            mode = GlobalConfig.getInstance().getRdpOpenMode();
        }
        return !"TAB".equalsIgnoreCase(mode);
    }

    static boolean shouldReuseRdpWindow(boolean ownerWindowShowing, boolean paneFullScreen) {
        return ownerWindowShowing || paneFullScreen;
    }

    private static Image loadMstscWindowIcon() {
        if (mstscWindowIcon != null) {
            return mstscWindowIcon;
        }
        try {
            java.net.URL resource = RdpConnectHandler.class.getResource("/images/rdp/mstsc.png");
            if (resource == null) {
                return null;
            }
            mstscWindowIcon = new Image(resource.toExternalForm());
            return mstscWindowIcon;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void fitWindowToConfiguredDesktop(Stage stage, RdpPane pane, ConnectionConfig config) {
        if (config.isFullscreen() || pane.getCenter() == null) {
            return;
        }
        int targetWidth = config.getScreenWidth() > 0 ? config.getScreenWidth() : 1024;
        int targetHeight = config.getScreenHeight() > 0 ? config.getScreenHeight() : 768;
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getScreensForRectangle(
                stage.getX(), stage.getY(), Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()))
                .stream().findFirst().orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
        if (desktopRequiresFullScreen(targetWidth, targetHeight, screen)) {
            // A desktop as large as the available work area cannot fit in a
            // decorated window. Windows may clamp that window to the monitor,
            // making it look full-screen even though RdpPane never created its
            // real full-screen layer (and therefore has no top control bar).
            // Enter the same RDP full-screen mode used by the explicit setting.
            if (stage.isShowing() && !pane.isFullScreen()) {
                pane.toggleFullScreen();
            }
            return;
        }
        double viewportWidth = pane.getCenter().getLayoutBounds().getWidth();
        double viewportHeight = pane.getCenter().getLayoutBounds().getHeight();
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }
        stage.setWidth(Math.min(screen.getWidth(), stage.getWidth() + targetWidth - viewportWidth));
        stage.setHeight(Math.min(screen.getHeight(), stage.getHeight() + targetHeight - viewportHeight));
    }

    static boolean desktopRequiresFullScreen(int desktopWidth, int desktopHeight,
                                             javafx.geometry.Rectangle2D visualBounds) {
        if (visualBounds == null || desktopWidth <= 0 || desktopHeight <= 0) {
            return false;
        }
        return desktopWidth >= Math.floor(visualBounds.getWidth())
                || desktopHeight >= Math.floor(visualBounds.getHeight());
    }

    /**
     * 获取主窗口所在屏幕的可视区域（全屏分辨率用）；无窗口时取主屏
     */
    private javafx.geometry.Rectangle2D resolveScreenBounds(ConnectModule module) {
        try {
            javafx.scene.Scene scene = module.getTerminalTabPane().getScene();
            if (scene != null && scene.getWindow() != null) {
                javafx.stage.Window window = scene.getWindow();
                javafx.geometry.Rectangle2D windowBounds = new javafx.geometry.Rectangle2D(
                        window.getX(), window.getY(), 1, 1);
                for (javafx.stage.Screen screen : javafx.stage.Screen.getScreensForRectangle(windowBounds)) {
                    return screen.getBounds();
                }
            }
        } catch (Exception ignored) {
        }
        return javafx.stage.Screen.getPrimary().getBounds();
    }
}
