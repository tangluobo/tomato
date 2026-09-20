package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.DatabaseNodeData;
import com.tangluobo.tomato.module.connect.DatabaseLogoLoader;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.service.MongoService;
import com.tangluobo.tomato.module.connect.view.MongoCollectionView;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.image.ImageView;

import java.util.List;

/** MongoDB 专用连接树：主机 → 数据库 → 集合。 */
public class MongoConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.MONGODB;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) handleHostDoubleClick(module, hostItem, config);
    }

    @Override
    public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem,
                                      ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }
        if (config.getUsername() != null && !config.getUsername().isBlank()
                && config.getPassword() == null) {
            PasswordPromptDialog.Result result = PasswordPromptDialog.show(
                    "输入密码", config.getName() + " (" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (result == null || result.getPassword() == null) return;
            config.setPassword(result.getPassword());
            if (result.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        setLoading(hostItem);
        runAsync("MongoDB-LoadDatabases", () -> MongoService.getDatabases(config), databases -> {
            module.updateHostIconGeneric(hostItem, config, true);
            hostItem.getChildren().clear();
            for (String database : databases) {
                TreeItem<String> databaseItem = new TreeItem<>(database);
                DatabaseNodeData data = new DatabaseNodeData(
                        DatabaseNodeData.NodeType.MONGO_DATABASE, database, config, database);
                databaseItem.setGraphic(module.getDbNodeIcon(data));
                module.putDbNodeData(databaseItem, data);
                hostItem.getChildren().add(databaseItem);
            }
            hostItem.setExpanded(true);
        }, error -> {
            hostItem.setGraphic(module.getIconForConfig(config));
            showError(module, "连接失败", "无法连接到 MongoDB " + config.getName(), error);
        });
    }

    public void handleDatabaseDoubleClick(ConnectModule module, TreeItem<String> databaseItem,
                                          DatabaseNodeData data) {
        if (!databaseItem.getChildren().isEmpty()) {
            databaseItem.setExpanded(!databaseItem.isExpanded());
            return;
        }
        loadCollections(module, databaseItem, data);
    }

    public void handleCollectionDoubleClick(ConnectModule module, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null || !module.ensureTabPaneInstalled()) return;
        String tabId = "mongo_" + data.getConnectionConfig().getId() + "_"
                + data.getDatabaseName() + "_" + data.getName();
        for (Tab existing : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(existing.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(existing);
                module.showDataView();
                return;
            }
        }

        MongoCollectionView view = new MongoCollectionView(
                data.getConnectionConfig(), data.getDatabaseName(), data.getName());
        Tab tab = new Tab(data.getName() + "@" + data.getDatabaseName());
        tab.setContent(view);
        tab.setUserData(tabId);
        AbstractDbHandler.tagDatabaseObjectTab(tab, data.getConnectionConfig().getId());
        try {
            ImageView icon = new ImageView(DatabaseLogoLoader.load(
                    ConnectType.MONGODB.getIconPath(), 18, 18));
            icon.setFitWidth(18);
            icon.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(icon));
        } catch (Exception ignored) {
        }
        tab.setOnClosed(event -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) module.showWelcomeView();
        });
        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    @Override
    public void populateNodeContextMenu(ConnectModule module, ContextMenu menu,
                                        TreeItem<String> item, DatabaseNodeData data) {
        if (data.getType() == DatabaseNodeData.NodeType.MONGO_DATABASE) {
            MenuItem open = new MenuItem("打开");
            open.setOnAction(event -> handleDatabaseDoubleClick(module, item, data));
            MenuItem create = new MenuItem("新建集合");
            create.setOnAction(event -> promptCreateCollection(module, item, data));
            MenuItem refresh = new MenuItem("刷新");
            refresh.setOnAction(event -> refreshDatabase(module, item, data));
            menu.getItems().addAll(open, create, refresh);
        } else if (data.getType() == DatabaseNodeData.NodeType.MONGO_COLLECTION) {
            MenuItem open = new MenuItem("打开文档");
            open.setOnAction(event -> handleCollectionDoubleClick(module, data));
            MenuItem rename = new MenuItem("重命名集合");
            rename.setOnAction(event -> promptRenameCollection(module, item, data));
            MenuItem drop = new MenuItem("删除集合");
            drop.setOnAction(event -> confirmDropCollection(module, item, data));
            menu.getItems().addAll(open, rename, drop);
        }
    }

    public void closeConnection(ConnectModule module, ConnectionConfig config) {
        MongoService.close(config);
        if (module.getTerminalTabPane() != null) {
            AbstractDbHandler.removeDatabaseObjectTabsForConnection(
                    module.getTerminalTabPane().getTabs(), config.getId());
        }
    }

    /** MongoDB 数据库在首次写入集合时创建，因此主机级创建动作同时要求一个初始集合名。 */
    public void promptCreateDatabase(ConnectModule module, TreeItem<String> hostItem,
                                     ConnectionConfig config) {
        TextInputDialog databaseDialog = new TextInputDialog();
        databaseDialog.setTitle("新建 MongoDB 数据库");
        databaseDialog.setHeaderText("MongoDB 数据库将在创建首个集合时生成");
        databaseDialog.setContentText("数据库名：");
        DialogPositionUtil.centerOnOwner(databaseDialog, module.getStage());
        databaseDialog.showAndWait().ifPresent(databaseName -> {
            TextInputDialog collectionDialog = new TextInputDialog("items");
            collectionDialog.setTitle("新建 MongoDB 数据库");
            collectionDialog.setHeaderText(databaseName.trim());
            collectionDialog.setContentText("初始集合名：");
            DialogPositionUtil.centerOnOwner(collectionDialog, module.getStage());
            collectionDialog.showAndWait().ifPresent(collectionName ->
                    runAsync("MongoDB-CreateDatabase", () -> {
                        MongoService.createCollection(config, databaseName, collectionName);
                        return Boolean.TRUE;
                    }, ignored -> refreshHost(module, hostItem, config),
                            error -> showError(module, "创建失败", "创建 MongoDB 数据库失败", error)));
        });
    }

    private void refreshHost(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        for (TreeItem<String> child : hostItem.getChildren()) {
            module.removeDbNodeDataRecursive(child);
        }
        hostItem.getChildren().clear();
        handleHostDoubleClick(module, hostItem, config);
    }

    private void loadCollections(ConnectModule module, TreeItem<String> databaseItem,
                                 DatabaseNodeData data) {
        setLoading(databaseItem);
        runAsync("MongoDB-LoadCollections",
                () -> MongoService.getCollections(data.getConnectionConfig(), data.getDatabaseName()),
                collections -> {
                    databaseItem.getChildren().clear();
                    data.setOpened(true);
                    databaseItem.setGraphic(module.getDbNodeIcon(data));
                    for (String collection : collections) {
                        TreeItem<String> collectionItem = new TreeItem<>(collection);
                        DatabaseNodeData collectionData = new DatabaseNodeData(
                                DatabaseNodeData.NodeType.MONGO_COLLECTION, collection,
                                data.getConnectionConfig(), data.getDatabaseName());
                        collectionItem.setGraphic(module.getDbNodeIcon(collectionData));
                        module.putDbNodeData(collectionItem, collectionData);
                        databaseItem.getChildren().add(collectionItem);
                    }
                    databaseItem.setExpanded(true);
                }, error -> {
                    databaseItem.setGraphic(module.getDbNodeIcon(data));
                    showError(module, "加载失败", "读取 MongoDB 集合失败", error);
                });
    }

    private void refreshDatabase(ConnectModule module, TreeItem<String> databaseItem,
                                 DatabaseNodeData data) {
        for (TreeItem<String> child : databaseItem.getChildren()) {
            module.removeDbNodeDataRecursive(child);
        }
        databaseItem.getChildren().clear();
        loadCollections(module, databaseItem, data);
    }

    private void promptCreateCollection(ConnectModule module, TreeItem<String> databaseItem,
                                        DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建 MongoDB 集合");
        dialog.setHeaderText(data.getDatabaseName());
        dialog.setContentText("集合名：");
        DialogPositionUtil.centerOnOwner(dialog, module.getStage());
        dialog.showAndWait().ifPresent(name -> runAsync("MongoDB-CreateCollection", () -> {
            MongoService.createCollection(data.getConnectionConfig(), data.getDatabaseName(), name);
            return Boolean.TRUE;
        }, ignored -> refreshDatabase(module, databaseItem, data),
                error -> showError(module, "创建失败", "创建 MongoDB 集合失败", error)));
    }

    private void promptRenameCollection(ConnectModule module, TreeItem<String> item,
                                        DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog(data.getName());
        dialog.setTitle("重命名 MongoDB 集合");
        dialog.setHeaderText(data.getDatabaseName());
        dialog.setContentText("新集合名：");
        DialogPositionUtil.centerOnOwner(dialog, module.getStage());
        dialog.showAndWait().ifPresent(name -> runAsync("MongoDB-RenameCollection", () -> {
            MongoService.renameCollection(data.getConnectionConfig(), data.getDatabaseName(), data.getName(), name);
            return Boolean.TRUE;
        }, ignored -> {
            TreeItem<String> parent = item.getParent();
            DatabaseNodeData parentData = module.getDbNodeDataMap().get(parent);
            if (parent != null && parentData != null) refreshDatabase(module, parent, parentData);
        }, error -> showError(module, "重命名失败", "重命名 MongoDB 集合失败", error)));
    }

    private void confirmDropCollection(ConnectModule module, TreeItem<String> item,
                                       DatabaseNodeData data) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("删除集合");
        alert.setHeaderText("确定删除集合 “" + data.getName() + "” 吗？");
        alert.setContentText("集合内所有文档和索引都会被删除，此操作不可撤销。");
        DialogPositionUtil.centerOnOwner(alert, module.getStage());
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        runAsync("MongoDB-DropCollection", () -> {
            MongoService.dropCollection(data.getConnectionConfig(), data.getDatabaseName(), data.getName());
            return Boolean.TRUE;
        }, ignored -> {
            module.removeDbNodeDataRecursive(item);
            if (item.getParent() != null) item.getParent().getChildren().remove(item);
        }, error -> showError(module, "删除失败", "删除 MongoDB 集合失败", error));
    }

    private static void setLoading(TreeItem<String> item) {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(16, 16);
        progress.setMaxSize(16, 16);
        item.setGraphic(progress);
    }

    private static void showError(ConnectModule module, String title, String header, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(rootMessage(error));
        DialogPositionUtil.centerOnOwner(alert, module.getStage());
        alert.showAndWait();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static <T> void runAsync(String name, ThrowingSupplier<T> task,
                                     java.util.function.Consumer<T> success,
                                     java.util.function.Consumer<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                T result = task.get();
                Platform.runLater(() -> success.accept(result));
            } catch (Throwable error) {
                Platform.runLater(() -> failure.accept(error));
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
