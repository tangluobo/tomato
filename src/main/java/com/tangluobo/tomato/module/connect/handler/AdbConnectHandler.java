package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;

/** Opens a remotely connected Android device in an embedded ADB desktop tab. */
public final class AdbConnectHandler implements ConnectHandler {
    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.ADB;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        if (!module.ensureTabPaneInstalled()) return;
        String tabId = "adb_" + config.getId();
        for (Tab existing : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(existing.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(existing);
                module.showTerminalView();
                if (existing.getContent() instanceof AdbPane pane) pane.requestFocus();
                return;
            }
        }

        int port = config.getPort() > 0 ? config.getPort() : 5555;
        AdbPane pane = new AdbPane(config.getHost(), port, config.getAdbExecutablePath());
        String tabTitle = config.getName() == null || config.getName().isBlank()
                ? config.getHost() : config.getName();
        Tab tab = new Tab(tabTitle, pane);
        tab.setUserData(tabId);

        MenuItem reconnect = new MenuItem("重新连接");
        reconnect.setOnAction(event -> pane.connect());
        MenuItem disconnect = new MenuItem("断开");
        disconnect.setOnAction(event -> pane.disconnect());
        MenuItem back = new MenuItem("返回");
        back.setOnAction(event -> pane.sendVirtualKey(AdbClient.KEYCODE_BACK));
        MenuItem home = new MenuItem("Home");
        home.setOnAction(event -> pane.sendVirtualKey(AdbClient.KEYCODE_HOME));
        MenuItem recent = new MenuItem("最近任务");
        recent.setOnAction(event -> pane.sendVirtualKey(AdbClient.KEYCODE_APP_SWITCH));
        MenuItem fullScreen = new MenuItem("全屏");
        fullScreen.setOnAction(event -> pane.toggleFullScreen());
        tab.setContextMenu(new ContextMenu(reconnect, disconnect, new SeparatorMenuItem(),
                back, home, recent, new SeparatorMenuItem(), fullScreen));
        tab.setOnClosed(event -> {
            pane.dispose();
            if (module.getTerminalTabPane().getTabs().isEmpty()) module.showWelcomeView();
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();
        pane.connect();
        if (config.isFullscreen()) Platform.runLater(pane::toggleFullScreen);
    }
}
