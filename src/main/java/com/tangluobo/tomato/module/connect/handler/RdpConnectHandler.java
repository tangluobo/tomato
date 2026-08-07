package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import com.tangluobo.tomato.rdp.RdpPane;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

/**
 * RDP 远程桌面连接处理器。
 * 完整封装 RDP tab 创建、密码输入、连接建立逻辑。
 */
public class RdpConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.RDP;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        // 若已有打开的 RDP tab，直接切换选中
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showTerminalView();
                return;
            }
        }
        createRdpTab(module, config);
    }

    /**
     * 创建 RDP tab 并发起连接
     */
    private void createRdpTab(ConnectModule module, ConnectionConfig config) {
        String password = config.getPassword();
        if (password == null || password.isEmpty()) {
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

            var result = pwdDialog.showAndWait();
            if (result.isEmpty() || result.get().isEmpty()) return;
            password = result.get();
        }

        RdpPane rdpPane = new RdpPane();

        Tab tab = new Tab(config.getName());
        tab.setContent(rdpPane);
        tab.setUserData(config.getId());

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            module.saveConnections();
        });

        tabContextMenu.getItems().add(sessionConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            rdpPane.disconnect();
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == tab) {
                rdpPane.requestRdpFocus();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        int rdpPort = config.getPort() > 0 ? config.getPort() : 3389;
        int width = config.getScreenWidth() > 0 ? config.getScreenWidth() : 1024;
        int height = config.getScreenHeight() > 0 ? config.getScreenHeight() : 768;
        int bpp = config.getColorDepth() > 0 ? config.getColorDepth() : 24;
        String domain = config.getDomain();

        rdpPane.connect(config.getHost(), rdpPort, config.getUsername(), password,
                domain, width, height, bpp, config.isUseSsl());
    }
}
