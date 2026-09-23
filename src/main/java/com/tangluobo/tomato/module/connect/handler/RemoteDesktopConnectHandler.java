package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import javafx.scene.control.Alert;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;

/** Opens remote desktops inside Tomato without launching an external viewer. */
public class RemoteDesktopConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.VNC || type == ConnectType.SPICE;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        if (config.getType() == ConnectType.SPICE) {
            showEmbeddedSpiceNotSupported(module);
            return;
        }
        openVncTab(module, config);
    }

    private void openVncTab(ConnectModule module, ConnectionConfig config) {
        if (!module.ensureTabPaneInstalled()) return;
        String tabId = "vnc_" + config.getId();
        for (Tab existing : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(existing.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(existing);
                module.showTerminalView();
                if (existing.getContent() instanceof VncPane pane) pane.requestFocus();
                return;
            }
        }

        int port = config.getPort() > 0 ? config.getPort() : 5900;
        VncPane pane = new VncPane(config.getHost(), port, config.getPassword(),
                config.isMapClipboard(), () -> promptForVncPassword(module, config));
        Tab tab = new Tab(config.getName() == null || config.getName().isBlank()
                ? config.getHost() : config.getName(), pane);
        tab.setUserData(tabId);

        MenuItem reconnect = new MenuItem("重新连接");
        reconnect.setOnAction(event -> pane.reconnect());
        MenuItem disconnect = new MenuItem("断开");
        disconnect.setOnAction(event -> pane.disconnect());
        MenuItem fullScreen = new MenuItem("全屏");
        fullScreen.setOnAction(event -> pane.toggleFullScreen());
        tab.setContextMenu(new ContextMenu(reconnect, disconnect, fullScreen));
        tab.setOnClosed(event -> {
            pane.disconnect();
            if (module.getTerminalTabPane().getTabs().isEmpty()) module.showWelcomeView();
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();
        pane.connect();
        if (config.isFullscreen()) {
            javafx.application.Platform.runLater(pane::toggleFullScreen);
        }
    }

    private String promptForVncPassword(ConnectModule module, ConnectionConfig config) {
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            return config.getPassword();
        }
        PasswordPromptDialog.Result result = PasswordPromptDialog.show(
                "输入 VNC 密码",
                config.getName() + " (" + config.getHost() + ':' + config.getPort() + ')',
                "VNC 密码：", null, "保存密码");
        if (result == null || result.getPassword() == null || result.getPassword().isEmpty()) {
            return null;
        }
        if (result.isSavePassword()) {
            config.setPassword(result.getPassword());
            config.setSavePassword(true);
            module.saveConnections();
        }
        return result.getPassword();
    }

    private void showEmbeddedSpiceNotSupported(ConnectModule module) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        if (module.getStage() != null) alert.initOwner(module.getStage());
        alert.setTitle("SPICE 暂不可用");
        alert.setHeaderText("Tomato 不会再启动外部远程桌面客户端");
        alert.setContentText("内置 VNC 客户端已经可用；内置 SPICE 协议仍在开发中。");
        alert.showAndWait();
    }
}
