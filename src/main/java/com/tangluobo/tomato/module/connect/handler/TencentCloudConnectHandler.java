package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.service.TencentCloudService;
import com.tangluobo.tomato.module.connect.view.AliyunDomainDataView;
import javafx.application.Platform;
import javafx.scene.control.*;
import java.util.List;
import java.util.Map;

/** 腾讯云开放平台连接处理器。 */
public class TencentCloudConnectHandler implements ConnectHandler {
    @Override public boolean supports(ConnectType type) { return type == ConnectType.TENCENT_CLOUD; }
    @Override public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> item = module.findItemById(module.getRoot(), config.getId());
        if (item != null) handleHostDoubleClick(module, item, config);
    }
    @Override public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) { hostItem.setExpanded(!hostItem.isExpanded()); return; }
        if (config.getPassword() == null || config.getPassword().isEmpty()) {
            PasswordPromptDialog.Result r = PasswordPromptDialog.show("输入 SecretKey", config.getName() + " (" + config.getUsername() + ")",
                    "SecretKey：", "腾讯云 SecretKey", "保存密钥");
            if (r == null || r.getPassword() == null || r.getPassword().isEmpty()) return;
            config.setPassword(r.getPassword());
            if (r.isSavePassword()) { config.setSavePassword(true); module.saveConnections(); }
        }
        hostItem.setGraphic(new ProgressIndicator());
        new Thread(() -> {
            try {
                TencentCloudService.verifyCredentialsOrThrow(config);
                Platform.runLater(() -> {
                    module.updateHostIcon(hostItem, config, true);
                    for (TencentCloudService.TencentProduct p : TencentCloudService.getSupportedProducts()) {
                        DatabaseNodeData d = new DatabaseNodeData(DatabaseNodeData.NodeType.TENCENT_PRODUCT_FOLDER, p.name(), config, p.code());
                        TreeItem<String> child = new TreeItem<>(p.name(), module.getDbNodeIcon(d));
                        module.getDbNodeDataMap().put(child, d); hostItem.getChildren().add(child);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) { Platform.runLater(() -> { hostItem.setGraphic(module.getIconForConfig(config)); new Alert(Alert.AlertType.ERROR, "腾讯云连接失败：" + e.getMessage(), ButtonType.OK).showAndWait(); }); }
        }, "TencentCloud-Connect").start();
    }
    public void handleProductFolderDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        if (!item.getChildren().isEmpty()) { item.setExpanded(!item.isExpanded()); return; }
        loadChildren(module, item, data);
    }
    public void loadChildren(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        new Thread(() -> {
            try {
                boolean cvm = "cvm".equals(data.getDatabaseName());
                List<Map<String, Object>> values = cvm ? TencentCloudService.getCvmInstances(data.getConnectionConfig(), "ap-guangzhou") : TencentCloudService.getDomainList(data.getConnectionConfig());
                Platform.runLater(() -> {
                    item.getChildren().clear();
                    for (Map<String, Object> value : values) {
                        String name = String.valueOf(value.getOrDefault(cvm ? "instanceName" : "domainName", ""));
                        if (cvm) name += " (" + value.getOrDefault("status", "") + ")";
                        DatabaseNodeData.NodeType type = cvm ? DatabaseNodeData.NodeType.TENCENT_CVM_INSTANCE : DatabaseNodeData.NodeType.TENCENT_DOMAIN;
                        DatabaseNodeData d = new DatabaseNodeData(type, name, data.getConnectionConfig(), String.valueOf(value.getOrDefault("instanceId", "")));
                        TreeItem<String> child = new TreeItem<>(name, module.getDbNodeIcon(d)); module.getDbNodeDataMap().put(child, d); item.getChildren().add(child);
                    }
                    item.setExpanded(true);
                });
            } catch (Exception e) { Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "加载失败：" + e.getMessage(), ButtonType.OK).showAndWait()); }
        }, "TencentCloud-LoadProduct").start();
    }
    public void openDomain(ConnectModule module, DatabaseNodeData data) {
        TabPane pane = module.getTerminalTabPane(); if (pane == null || !module.ensureTabPaneInstalled()) return;
        String id = "tencent_domain_" + data.getConnectionConfig().getId() + "_" + data.getName();
        for (Tab t : pane.getTabs()) if (id.equals(t.getUserData())) { pane.getSelectionModel().select(t); module.showDataView(); return; }
        Tab tab = new Tab("子域名(" + data.getName() + ")"); tab.setUserData(id);
        tab.setContent(new AliyunDomainDataView(data.getConnectionConfig(), data.getName(), module.getConnections()));
        pane.getTabs().add(tab); pane.getSelectionModel().select(tab); module.showDataView();
    }
    public void refreshDbNode(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        item.getChildren().clear();
        if (data.getType() == DatabaseNodeData.NodeType.TENCENT_PRODUCT_FOLDER) loadChildren(module, item, data); else openDomain(module, data);
    }
    @Override public void populateNodeContextMenu(ConnectModule module, ContextMenu menu, TreeItem<String> item, DatabaseNodeData data) {
        if (data.getType() == DatabaseNodeData.NodeType.TENCENT_PRODUCT_FOLDER || data.getType() == DatabaseNodeData.NodeType.TENCENT_DOMAIN) {
            MenuItem refresh = new MenuItem("刷新"); refresh.setOnAction(e -> module.refreshDbNode(item, data)); menu.getItems().add(refresh);
        }
    }
}
