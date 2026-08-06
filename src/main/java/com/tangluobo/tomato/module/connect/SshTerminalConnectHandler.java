package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.connect.dialog.GlobalConfigDialog;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import com.tangluobo.tomato.ssh.SSHTerminalPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

import java.util.List;

/**
 * SSH 终端连接处理器（默认分支）。
 * 完整封装 SSH 终端 tab 创建、密码输入、连接建立逻辑。
 */
public class SshTerminalConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.SSH;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        createSshTerminalTab(module, config);
    }

    /**
     * 创建 SSH 终端 tab 并发起连接
     */
    private void createSshTerminalTab(ConnectModule module, ConnectionConfig config) {
        SSHTerminalPane terminalPane = new SSHTerminalPane();

        int scrollback = config.getScrollbackLines() != null ?
                config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        terminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(config.getName());
        tab.setContent(terminalPane);
        tab.setUserData(config.getId());

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem copySessionItem = new MenuItem("复制会话");
        copySessionItem.setOnAction(e -> module.triggerConnect(config));

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            int newScrollback = config.getScrollbackLines() != null ?
                    config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
            terminalPane.setScrollbackLines(newScrollback);
            module.saveConnections();
        });

        MenuItem globalConfigItem = new MenuItem("终端配置");
        globalConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.SSH);
            if (config.getScrollbackLines() == null) {
                terminalPane.setScrollbackLines(GlobalConfig.getInstance().getScrollbackLines());
            }
        });

        tabContextMenu.getItems().addAll(copySessionItem, new SeparatorMenuItem(), sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            terminalPane.disconnect();
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        doConnect(module, terminalPane, config);
    }

    /**
     * 处理密码输入并触发连接
     */
    private void doConnect(ConnectModule module, SSHTerminalPane terminalPane, ConnectionConfig config) {
        if (config.isUsePassword() && config.getPassword() == null) {
            Dialog<String> pwdDialog = new Dialog<>();
            pwdDialog.setTitle("输入密码");
            pwdDialog.setHeaderText(config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")");
            pwdDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20, 10, 10, 10));
            PasswordField pf = new PasswordField();
            pf.setPrefWidth(250);
            grid.add(new Label("密码："), 0, 0);
            grid.add(pf, 1, 0);
            pwdDialog.getDialogPane().setContent(grid);

            pwdDialog.setResultConverter(dialogButton -> {
                if (dialogButton == ButtonType.OK) {
                    return pf.getText();
                }
                return null;
            });

            pwdDialog.showAndWait().ifPresentOrElse(pwd -> {
                if (pwd.isEmpty()) return;
                connectWithAuth(terminalPane, config, pwd);
            }, () -> {});
        } else {
            connectWithAuth(terminalPane, config, config.getPassword());
        }
    }

    /**
     * 后台建立 SSH 连接
     */
    private void connectWithAuth(SSHTerminalPane terminalPane, ConnectionConfig config, String password) {
        List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
        new Thread(() -> {
            try {
                terminalPane.connect(config.getHost(), config.getPort(), config.getUsername(), password, keyPaths);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("SSH连接失败: " + e.getMessage());
                    alert.showAndWait();
                    terminalPane.disconnect();
                });
                e.printStackTrace();
            }
        }, "SSH-Connect").start();
    }
}
