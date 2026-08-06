package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.S3FileBrowserPane;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.geometry.Insets;
import javafx.stage.Stage;

/**
 * S3/OSS 对象存储连接处理器
 */
public class S3ConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.S3 || type == ConnectType.ALIYUN_OSS;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showTerminalView();
                return;
            }
        }

        if (config.getPassword() == null || config.getPassword().isEmpty()) {
            Dialog<String> pwdDialog = new Dialog<>();
            pwdDialog.setTitle("输入Secret Key");
            pwdDialog.setHeaderText(config.getName() + " (" + config.getUsername() + "@" + (config.getEndpoint() != null ? config.getEndpoint() : config.getRegion()) + ")");
            pwdDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20, 10, 10, 10));
            PasswordField pf = new PasswordField();
            pf.setPrefWidth(250);
            pf.setPromptText("Secret Key");
            grid.add(new Label("Secret Key："), 0, 0);
            grid.add(pf, 1, 0);
            pwdDialog.getDialogPane().setContent(grid);
            pwdDialog.setResultConverter(dialogButton -> dialogButton == ButtonType.OK ? pf.getText() : null);
            final String[] passwordHolder = new String[1];
            pwdDialog.showAndWait().ifPresentOrElse(pwd -> passwordHolder[0] = pwd, () -> {});
            if (passwordHolder[0] == null || passwordHolder[0].isEmpty()) return;
            config.setPassword(passwordHolder[0]);
        }

        S3FileBrowserPane fileBrowserPane = new S3FileBrowserPane(config);

        Tab tab = new Tab(config.getName());
        tab.setContent(fileBrowserPane);
        tab.setUserData(config.getId());

        try {
            Image tabIcon = new Image(getClass().getResourceAsStream(config.getType().getIconPath()));
            if (tabIcon != null) {
                ImageView tabIconView = new ImageView(tabIcon);
                tabIconView.setFitWidth(16);
                tabIconView.setFitHeight(16);
                tab.setGraphic(tabIconView);
            }
        } catch (Exception e) {}

        ContextMenu tabContextMenu = new ContextMenu();
        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> fileBrowserPane.refresh());
        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = module.getStage();
            SessionConfigDialog.show(stage, config);
            module.saveConnections();
        });
        tabContextMenu.getItems().addAll(refreshItem, new SeparatorMenuItem(), sessionConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();
    }
}
