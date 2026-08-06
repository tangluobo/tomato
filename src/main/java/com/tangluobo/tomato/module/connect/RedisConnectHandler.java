package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.geometry.Insets;

import java.util.List;

/**
 * Redis 连接处理器
 */
public class RedisConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.REDIS;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            handleHostDoubleClick(module, hostItem, config);
        }
    }

    @Override
    public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        if (config.getPassword() == null) {
            Dialog<String> pwdDialog = new Dialog<>();
            pwdDialog.setTitle("输入密码");
            pwdDialog.setHeaderText(config.getName() + " (" + config.getHost() + ":" + config.getPort() + ")");
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
            pwdDialog.setResultConverter(dialogButton -> dialogButton == ButtonType.OK ? pf.getText() : null);
            final String[] passwordHolder = new String[1];
            pwdDialog.showAndWait().ifPresentOrElse(pwd -> passwordHolder[0] = pwd, () -> {});
            if (passwordHolder[0] == null || passwordHolder[0].isEmpty()) return;
            config.setPassword(passwordHolder[0]);
        }

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);

        new Thread(() -> {
            try {
                List<String> databases = RedisService.getDatabases(config);
                Platform.runLater(() -> {
                    module.updateHostIcon(hostItem, config, true);

                    hostItem.getChildren().clear();
                    for (String dbIndex : databases) {
                        String dbName = "db" + dbIndex;
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        DatabaseNodeData data = new DatabaseNodeData(DatabaseNodeData.NodeType.REDIS_DB, dbName, config, dbName);
                        dbItem.setGraphic(module.getDbNodeIcon(data));
                        module.getDbNodeDataMap().put(dbItem, data);
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hostItem.setGraphic(module.getIconForConfig(config));
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到Redis " + config.getName() + ": " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "Redis-LoadDatabases").start();
    }

    /** 刷新 Redis 主机：清空子节点并重新触发双击连接 */
    public void refreshHost(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        for (TreeItem<String> child : hostItem.getChildren()) {
            module.removeDbNodeDataRecursive(child);
        }
        hostItem.getChildren().clear();
        module.triggerHostDoubleClick(hostItem, config);
    }
}
