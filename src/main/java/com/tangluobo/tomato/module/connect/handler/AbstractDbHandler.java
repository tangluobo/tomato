package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.dialog.CreateDatabaseDialog;
import com.tangluobo.tomato.module.connect.dialog.EditDatabaseDialog;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.dialog.GlobalConfigDialog;
import com.tangluobo.tomato.module.connect.dialog.RestoreDialog;
import com.tangluobo.tomato.module.connect.service.BackupService;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.module.connect.view.SqlEditorView;
import com.tangluobo.tomato.module.connect.view.TableDataView;
import com.tangluobo.tomato.module.connect.view.TableStructureView;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据库连接处理器抽象基类。
 * 封装 MySQL/PostgreSQL/Oracle 等关系型数据库在连接树中的公共行为，
 * 差异点（数据库节点展开方式、主机图标更新、模式加载等）由子类实现。
 *
 * 设计说明：
 * - 公共逻辑（加载数据库列表、表/视图文件夹、新建表/查询、删除节点等）放在此基类
 * - 抽象方法定义差异点，由 MysqlDbHandler/PostgresDbHandler/OracleDbHandler 实现
 * - 通过持有的 ConnectModule 引用访问共享 UI 状态（树、tab 面板、图标等）
 * - 实现 ConnectHandler 接口，使 handleConnect 也可通过统一分发机制调用
 */
public abstract class AbstractDbHandler implements ConnectHandler {

    /** 关联的连接模块，提供共享 UI 状态与公共回调 */
    protected final ConnectModule module;

    protected AbstractDbHandler(ConnectModule module) {
        this.module = module;
    }

    /**
     * 此处理器对应的数据库连接类型
     */
    public abstract ConnectType getConnectType();

    // ==================== 抽象方法：差异点 ====================

    /**
     * 打开数据库节点：展开下级目录结构。
     * PostgreSQL 实现为加载模式(schema)节点；MySQL/Oracle 实现为直接加载表/视图/函数/查询/备份文件夹。
     */
    public abstract void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data);

    /**
     * 更新主机节点图标（根据连接状态）。
     * MySQL 有特殊的图标更新逻辑；其他数据库使用通用逻辑。
     */
    public abstract void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected);

    /**
     * 是否支持模式(schema)层级。
     * PostgreSQL 返回 true（数据库→模式→表）；MySQL/Oracle 返回 false。
     */
    public boolean supportsSchema() {
        return false;
    }

    /**
     * 双击模式节点处理。仅 PostgreSQL 实现；其他数据库默认空操作。
     */
    public void handleSchemaDoubleClick(TreeItem<String> schemaItem, DatabaseNodeData data) {
        // 默认无操作：MySQL/Oracle 无 schema 层级
    }

    /**
     * 刷新模式节点。仅 PostgreSQL 实现；其他数据库默认空操作。
     */
    public void refreshSchema(TreeItem<String> schemaItem, DatabaseNodeData data) {
        // 默认无操作
    }

    // ==================== 公共方法 ====================

    /**
     * 双击主机节点：加载数据库列表。
     * MySQL/PostgreSQL/Oracle 逻辑一致：密码输入 → 后台调 DatabaseService.getDatabases → 填充数据库节点。
     */
    public void handleHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        doHandleDbHostDoubleClick(hostItem, config);
    }

    /**
     * 执行数据库主机连接的公共流程（密码输入 → 加载数据库列表 → 填充节点）。
     * MySQL/PostgreSQL/Oracle 逻辑一致。
     */
    void doHandleDbHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        if (module.getConnectingHosts().contains(hostItem)) {
            return;
        }
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        if (config.getPassword() == null) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        module.getConnectingHosts().add(hostItem);

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);
        module.getTreeView().refresh();

        new Thread(() -> {
            try {
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    module.getConnectingHosts().remove(hostItem);
                    module.markConnectionState(hostItem, true);
                    updateHostIcon(hostItem, config, true);

                    hostItem.getChildren().clear();
                    for (String dbName : databases) {
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        DatabaseNodeData data = new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName);
                        dbItem.setGraphic(module.getDbNodeIcon(data));
                        module.getDbNodeDataMap().put(dbItem, data);
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    module.getConnectingHosts().remove(hostItem);
                    hostItem.setGraphic(module.getIconForConfig(config));
                    module.getTreeView().refresh();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到 " + config.getName() + ": " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "DB-LoadDatabases").start();
    }

    /**
     * 关闭主机连接：释放 JDBC 连接资源。
     * 关系型数据库统一调用 DatabaseService.closeConnection。
     */
    public void closeConnection(ConnectionConfig config) {
        try {
            DatabaseService.closeConnection(config.getId());
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断给定的连接配置是否由本处理器处理
     */
    public boolean handles(ConnectionConfig config) {
        return config.getType() == getConnectType();
    }

    // ==================== ConnectHandler 接口实现 ====================

    @Override
    public boolean supports(ConnectType type) {
        return type == getConnectType();
    }

    /**
     * 执行连接：找到主机树节点并触发双击连接流程
     */
    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            handleHostDoubleClick(hostItem, config);
        }
    }

    /**
     * 接口方法：双击主机节点。
     * 委托给本类已有的 handleHostDoubleClick(hostItem, config)（持有 module 引用，无需重复查找）。
     */
    @Override
    public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        handleHostDoubleClick(hostItem, config);
    }

    // ==================== 新建/编辑/删除数据库 ====================

    /** 新建数据库 */
    public void handleCreateDatabase(TreeItem<String> hostItem, ConnectionConfig config) {
        if (config.getPassword() == null) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        Stage stage = module.getStage();
        if (stage == null) return;

        CreateDatabaseDialog dialog = new CreateDatabaseDialog(stage, config);
        dialog.showAndWait();

        if (!dialog.isConfirmed()) return;

        String dbName = dialog.getDatabaseName();
        String charset = dialog.getCharset();
        String collation = dialog.getCollation();

        new Thread(() -> {
            try {
                DatabaseService.createDatabase(config, dbName, charset, collation);
                Platform.runLater(() -> {
                    if (!hostItem.getChildren().isEmpty()) {
                        refreshDbHost(hostItem, config);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("创建失败");
                    alert.setHeaderText(null);
                    alert.setContentText("创建数据库失败: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "DB-CreateDatabase").start();
    }

    /** 编辑数据库（修改字符集/排序规则） */
    public void handleEditDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();

        if (config.getPassword() == null) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        Stage stage = module.getStage();
        if (stage == null) return;

        String dbName = data.getDatabaseName();

        new Thread(() -> {
            try {
                String[] charsetCollation = DatabaseService.getDatabaseCharsetCollation(config, dbName);
                String currentCharset = charsetCollation[0];
                String currentCollation = charsetCollation[1];

                Platform.runLater(() -> {
                    EditDatabaseDialog dialog = new EditDatabaseDialog(stage, config, dbName, currentCharset, currentCollation);
                    dialog.showAndWait();

                    if (!dialog.isConfirmed()) return;

                    String charset = dialog.getCharset();
                    String collation = dialog.getCollation();

                    new Thread(() -> {
                        try {
                            DatabaseService.alterDatabase(config, dbName, charset, collation);
                            Platform.runLater(() -> {
                                refreshDbNode(dbItem, data);
                            });
                        } catch (Exception e) {
                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setTitle("修改失败");
                                alert.setHeaderText(null);
                                alert.setContentText("修改数据库失败: " + e.getMessage());
                                alert.showAndWait();
                            });
                        }
                    }, "DB-AlterDatabase").start();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("查询失败");
                    alert.setHeaderText(null);
                    alert.setContentText("获取数据库信息失败: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "DB-GetDbInfo").start();
    }

    /** 删除数据库 */
    public void handleDeleteDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("删除数据库");
        confirm.setHeaderText("确定要删除数据库 \"" + dbName + "\" 吗？");
        confirm.setContentText("此操作不可撤销，该数据库中的所有数据将被永久删除！");
        confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.YES) return;

            if (config.getPassword() == null) {
                PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                        "输入密码",
                        config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                        "密码：", null, "保存密码");
                if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
                config.setPassword(pwdResult.getPassword());
                if (pwdResult.isSavePassword()) {
                    config.setSavePassword(true);
                    module.saveConnections();
                }
            }

            new Thread(() -> {
                try {
                    DatabaseService.dropDatabase(config, dbName);
                    Platform.runLater(() -> {
                        module.removeDbNodeDataRecursive(dbItem);
                        dbItem.getParent().getChildren().remove(dbItem);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("删除失败");
                        alert.setHeaderText(null);
                        alert.setContentText("删除数据库失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "DB-DropDatabase").start();
        });
    }

    /** 批量删除表/视图节点 */
    public void handleDeleteDbNodes() {
        ObservableList<TreeItem<String>> selectedItems = module.getTreeView().getSelectionModel().getSelectedItems();
        List<TreeItem<String>> tableItems = new ArrayList<>();
        List<TreeItem<String>> viewItems = new ArrayList<>();

        for (TreeItem<String> item : selectedItems) {
            DatabaseNodeData data = module.getDbNodeDataMap().get(item);
            if (data != null) {
                if (data.getType() == DatabaseNodeData.NodeType.TABLE) {
                    tableItems.add(item);
                } else if (data.getType() == DatabaseNodeData.NodeType.VIEW) {
                    viewItems.add(item);
                }
            }
        }

        if (tableItems.isEmpty() && viewItems.isEmpty()) return;

        StringBuilder msg = new StringBuilder("确定要删除以下对象吗？此操作不可恢复！\n\n");
        if (!tableItems.isEmpty()) {
            msg.append("表：\n");
            for (TreeItem<String> item : tableItems) {
                msg.append("  - ").append(item.getValue()).append("\n");
            }
        }
        if (!viewItems.isEmpty()) {
            msg.append("视图：\n");
            for (TreeItem<String> item : viewItems) {
                msg.append("  - ").append(item.getValue()).append("\n");
            }
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText(null);
        confirm.setContentText(msg.toString());

        ButtonType deleteBtn = new ButtonType("确认删除");
        confirm.getButtonTypes().setAll(deleteBtn, ButtonType.CANCEL);

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != deleteBtn) return;

        if (!tableItems.isEmpty()) {
            Map<String, List<TreeItem<String>>> groupedTables = new HashMap<>();
            for (TreeItem<String> item : tableItems) {
                DatabaseNodeData data = module.getDbNodeDataMap().get(item);
                String schemaName = data.getSchemaName() != null ? data.getSchemaName() : "";
                String key = data.getConnectionConfig().getId() + "|" + data.getDatabaseName() + "|" + schemaName;
                groupedTables.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<String, List<TreeItem<String>>> entry : groupedTables.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                String configId = parts[0];
                String dbName = parts[1];
                String schemaName = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
                List<String> tableNames = entry.getValue().stream()
                        .map(TreeItem::getValue).toList();
                ConnectionConfig cfg = module.findConnectionById(configId);
                if (cfg == null) continue;

                try {
                    DatabaseService.dropTables(cfg, dbName, schemaName, tableNames);
                    Platform.runLater(() -> {
                        for (TreeItem<String> item : entry.getValue()) {
                            module.getDbNodeDataMap().remove(item);
                            item.getParent().getChildren().remove(item);
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert err = new Alert(Alert.AlertType.ERROR);
                        err.setTitle("删除失败");
                        err.setHeaderText(null);
                        err.setContentText(e.getMessage());
                        err.showAndWait();
                    });
                }
            }
        }

        if (!viewItems.isEmpty()) {
            Map<String, List<TreeItem<String>>> groupedViews = new HashMap<>();
            for (TreeItem<String> item : viewItems) {
                DatabaseNodeData data = module.getDbNodeDataMap().get(item);
                String schemaName = data.getSchemaName() != null ? data.getSchemaName() : "";
                String key = data.getConnectionConfig().getId() + "|" + data.getDatabaseName() + "|" + schemaName;
                groupedViews.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<String, List<TreeItem<String>>> entry : groupedViews.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                String configId = parts[0];
                String dbName = parts[1];
                String schemaName = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
                List<String> viewNames = entry.getValue().stream()
                        .map(TreeItem::getValue).toList();
                ConnectionConfig cfg = module.findConnectionById(configId);
                if (cfg == null) continue;

                try {
                    DatabaseService.dropViews(cfg, dbName, schemaName, viewNames);
                    Platform.runLater(() -> {
                        for (TreeItem<String> item : entry.getValue()) {
                            module.getDbNodeDataMap().remove(item);
                            item.getParent().getChildren().remove(item);
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert err = new Alert(Alert.AlertType.ERROR);
                        err.setTitle("删除失败");
                        err.setHeaderText(null);
                        err.setContentText(e.getMessage());
                        err.showAndWait();
                    });
                }
            }
        }
    }

    // ==================== 刷新 ====================

    /** 刷新数据库主机：重新加载数据库列表 */
    public void refreshDbHost(TreeItem<String> hostItem, ConnectionConfig config) {
        if (config.getPassword() == null) {
            module.triggerHostDoubleClick(hostItem, config);
            return;
        }
        new Thread(() -> {
            try {
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    for (TreeItem<String> child : hostItem.getChildren()) {
                        module.removeDbNodeDataRecursive(child);
                    }
                    hostItem.getChildren().clear();
                    for (String dbName : databases) {
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        dbItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName)));
                        module.getDbNodeDataMap().put(dbItem, new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName));
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("刷新失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法刷新数据库列表: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "DB-RefreshDatabases").start();
    }

    /** 刷新数据库节点（仅处理数据库相关类型） */
    public void refreshDbNode(TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        switch (data.getType()) {
            case DATABASE -> {
                if (data.isOpened()) {
                    module.removeDbNodeDataRecursive(item);
                    item.getChildren().clear();
                    openDatabase(item, data);
                }
            }
            case SCHEMA -> {
                module.removeDbNodeDataRecursive(item);
                item.getChildren().clear();
                data.setOpened(false);
                item.setGraphic(module.getDbNodeIcon(data));
                handleSchemaDoubleClick(item, data);
            }
            case TABLES_FOLDER -> {
                item.getChildren().clear();
                loadTablesForFolder(item, config, data.getDatabaseName(), data.getSchemaName(), false);
            }
            case VIEWS_FOLDER -> {
                item.getChildren().clear();
                loadViewsForFolder(item, config, data.getDatabaseName(), data.getSchemaName(), false);
            }
            case QUERY_FOLDER -> {
                loadQueriesForFolder(item, config, data.getDatabaseName());
            }
            case BACKUP_FOLDER -> {
                loadBackupsForFolder(item, config, data.getDatabaseName());
            }
            default -> {}
        }
    }

    // ==================== 加载列表到 folder 节点 ====================

    /** 加载表列表到指定文件夹节点 */
    public void loadTablesForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName, String schemaName, boolean autoExpand) {
        new Thread(() -> {
            try {
                List<String> tables = DatabaseService.getTables(config, dbName, schemaName);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (String tableName : tables) {
                        TreeItem<String> tableItem = new TreeItem<>(tableName);
                        DatabaseNodeData tableData = new DatabaseNodeData(DatabaseNodeData.NodeType.TABLE, tableName, config, dbName, schemaName);
                        tableItem.setGraphic(module.getDbNodeIcon(tableData));
                        module.getDbNodeDataMap().put(tableItem, tableData);
                        folderItem.getChildren().add(tableItem);
                    }
                    folderItem.setExpanded(autoExpand);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载表列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "DB-LoadTables").start();
    }

    /** 加载视图列表到指定文件夹节点 */
    public void loadViewsForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName, String schemaName, boolean autoExpand) {
        new Thread(() -> {
            try {
                List<String> views = DatabaseService.getViews(config, dbName, schemaName);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (String viewName : views) {
                        TreeItem<String> viewItem = new TreeItem<>(viewName);
                        DatabaseNodeData viewData = new DatabaseNodeData(DatabaseNodeData.NodeType.VIEW, viewName, config, dbName, schemaName);
                        viewItem.setGraphic(module.getDbNodeIcon(viewData));
                        module.getDbNodeDataMap().put(viewItem, viewData);
                        folderItem.getChildren().add(viewItem);
                    }
                    folderItem.setExpanded(autoExpand);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载视图列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "DB-LoadViews").start();
    }

    /**
     * 在单个线程中顺序加载表和视图列表，避免两个线程并发使用同一JDBC连接。
     * JDBC Connection不是线程安全的，并发使用会导致协议损坏和挂起。
     */
    public void loadTablesAndViewsForFolder(TreeItem<String> tablesFolder, TreeItem<String> viewsFolder,
                                             ConnectionConfig config, String dbName, String schemaName, boolean autoExpand) {
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
            // 顺序加载表列表
            try {
                List<String> tables = DatabaseService.getTables(config, dbName, schemaName);
                Platform.runLater(() -> {
                    tablesFolder.getChildren().clear();
                    for (String tableName : tables) {
                        TreeItem<String> tableItem = new TreeItem<>(tableName);
                        DatabaseNodeData tableData = new DatabaseNodeData(DatabaseNodeData.NodeType.TABLE, tableName, config, dbName, schemaName);
                        tableItem.setGraphic(module.getDbNodeIcon(tableData));
                        module.getDbNodeDataMap().put(tableItem, tableData);
                        tablesFolder.getChildren().add(tableItem);
                    }
                    tablesFolder.setExpanded(autoExpand);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载表列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }

            // 顺序加载视图列表（在表列表加载完成后，确保不并发使用连接）
            try {
                List<String> views = DatabaseService.getViews(config, dbName, schemaName);
                Platform.runLater(() -> {
                    viewsFolder.getChildren().clear();
                    for (String viewName : views) {
                        TreeItem<String> viewItem = new TreeItem<>(viewName);
                        DatabaseNodeData viewData = new DatabaseNodeData(DatabaseNodeData.NodeType.VIEW, viewName, config, dbName, schemaName);
                        viewItem.setGraphic(module.getDbNodeIcon(viewData));
                        module.getDbNodeDataMap().put(viewItem, viewData);
                        viewsFolder.getChildren().add(viewItem);
                    }
                    viewsFolder.setExpanded(autoExpand);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载视图列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadTablesAndViews").start();
    }

    /** 加载查询列表到指定文件夹节点 */
    public void loadQueriesForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName) {
        List<String> queryNames = SqlEditorView.listQueries(config.getName(), dbName);
        folderItem.getChildren().clear();
        for (String queryName : queryNames) {
            TreeItem<String> queryItem = new TreeItem<>(queryName);
            queryItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, queryName, config, dbName)));
            module.getDbNodeDataMap().put(queryItem, new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, queryName, config, dbName));
            folderItem.getChildren().add(queryItem);
        }
    }

    /** 加载备份列表到指定文件夹节点 */
    public void loadBackupsForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName) {
        List<String> backupNames = BackupService.listBackups(config.getName(), dbName);
        folderItem.getChildren().clear();
        for (String backupName : backupNames) {
            TreeItem<String> backupItem = new TreeItem<>(backupName);
            backupItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP, backupName, config, dbName)));
            module.getDbNodeDataMap().put(backupItem, new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP, backupName, config, dbName));
            folderItem.getChildren().add(backupItem);
        }
    }

    // ==================== 打开数据库/构建文件夹 ====================

    /**
     * 打开数据库节点并直接加载 5 个文件夹（表/视图/函数/查询/备份）。
     * 供 MySQL/Oracle 等无 schema 层级的数据库处理器调用。
     */
    protected void openDatabaseWithFolders(TreeItem<String> dbItem, DatabaseNodeData data) {
        data.setOpened(true);
        dbItem.setGraphic(module.getDbNodeIcon(data));

        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        TreeItem<String> tablesFolder = new TreeItem<>("表");
        tablesFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, dbName)));
        module.getDbNodeDataMap().put(tablesFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, dbName));

        TreeItem<String> viewsFolder = new TreeItem<>("视图");
        viewsFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, dbName)));
        module.getDbNodeDataMap().put(viewsFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, dbName));

        TreeItem<String> functionFolder = new TreeItem<>("函数");
        functionFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, dbName)));
        module.getDbNodeDataMap().put(functionFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, dbName));

        TreeItem<String> queryFolder = new TreeItem<>("查询");
        queryFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, dbName)));
        module.getDbNodeDataMap().put(queryFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, dbName));

        loadQueriesForFolder(queryFolder, config, dbName);

        TreeItem<String> backupFolder = new TreeItem<>("备份");
        backupFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, dbName)));
        module.getDbNodeDataMap().put(backupFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, dbName));

        loadBackupsForFolder(backupFolder, config, dbName);

        dbItem.getChildren().addAll(tablesFolder, viewsFolder, functionFolder, queryFolder, backupFolder);
        dbItem.setExpanded(true);

        // 使用单线程顺序加载表和视图，避免并发使用同一JDBC连接
        loadTablesAndViewsForFolder(tablesFolder, viewsFolder, config, dbName, null, false);
    }

    /**
     * 为 schema 节点构建 5 个子文件夹（表/视图/函数/查询/备份）。
     * 供 PostgreSQL 等支持 schema 层级的数据库处理器调用。
     */
    protected void buildSchemaFolders(TreeItem<String> schemaItem, ConnectionConfig config, String dbName, String schemaName) {
        TreeItem<String> tablesFolder = new TreeItem<>("表");
        DatabaseNodeData tablesData = new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, dbName, schemaName);
        tablesFolder.setGraphic(module.getDbNodeIcon(tablesData));
        module.getDbNodeDataMap().put(tablesFolder, tablesData);

        TreeItem<String> viewsFolder = new TreeItem<>("视图");
        DatabaseNodeData viewsData = new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, dbName, schemaName);
        viewsFolder.setGraphic(module.getDbNodeIcon(viewsData));
        module.getDbNodeDataMap().put(viewsFolder, viewsData);

        TreeItem<String> functionFolder = new TreeItem<>("函数");
        DatabaseNodeData functionData = new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, dbName, schemaName);
        functionFolder.setGraphic(module.getDbNodeIcon(functionData));
        module.getDbNodeDataMap().put(functionFolder, functionData);

        TreeItem<String> queryFolder = new TreeItem<>("查询");
        DatabaseNodeData queryData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, dbName, schemaName);
        queryFolder.setGraphic(module.getDbNodeIcon(queryData));
        module.getDbNodeDataMap().put(queryFolder, queryData);
        loadQueriesForFolder(queryFolder, config, dbName);

        TreeItem<String> backupFolder = new TreeItem<>("备份");
        DatabaseNodeData backupData = new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, dbName, schemaName);
        backupFolder.setGraphic(module.getDbNodeIcon(backupData));
        module.getDbNodeDataMap().put(backupFolder, backupData);
        loadBackupsForFolder(backupFolder, config, dbName);

        schemaItem.getChildren().addAll(tablesFolder, viewsFolder, functionFolder, queryFolder, backupFolder);
    }

    // ==================== 文件夹双击：加载表/视图列表 ====================

    /** 双击表文件夹：若已加载则切换展开状态，否则加载表列表 */
    public void handleTablesFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadTablesForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), true);
    }

    /** 双击视图文件夹：若已加载则切换展开状态，否则加载视图列表 */
    public void handleViewsFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadViewsForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), true);
    }

    // ==================== 表/视图 Tab 打开 ====================

    /** 新建表：打开表结构设计 Tab */
    public void handleNewTable(TreeItem<String> item, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = "newtable_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "_" + data.getSchemaName() : "");
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        TableStructureView structView = new TableStructureView(data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), null);

        ConnectionConfig config = data.getConnectionConfig();
        String tabTitle = "新建表@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-表结构";
        Tab tab = new Tab(tabTitle);
        Image tableIcon = module.getTableIcon();
        if (tableIcon != null) {
            ImageView tabIconView = new ImageView(tableIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(tabIconView);
        }
        tab.setContent(structView);
        tab.setUserData(tabId);

        // 新建表保存成功后：更新 tab 标题/userData（切换为设计表标识）并刷新表树
        final Tab finalTab = tab;
        structView.setOnTableCreated(newTableName -> {
            finalTab.setText(newTableName + "@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-表结构");
            finalTab.setUserData("struct_" + config.getId() + "_" + data.getDatabaseName() + "_" + newTableName);
            refreshDbNode(item, data);
        });

        ContextMenu structTabContextMenu = new ContextMenu();
        MenuItem structConfigItem = new MenuItem("表格配置");
        structConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.TABLE);
            GlobalConfig globalConfig = GlobalConfig.getInstance();
            structView.applyTableConfig(globalConfig);
        });
        MenuItem structRefreshItem = new MenuItem("刷新结构");
        structRefreshItem.setOnAction(e -> structView.loadStructure());
        structTabContextMenu.getItems().addAll(structConfigItem, structRefreshItem);
        tab.setContextMenu(structTabContextMenu);

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 设计表：打开表/视图结构 Tab */
    public void handleTableStructureDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = "struct_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "_" + data.getSchemaName() : "") + "_" + data.getName();
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        TableStructureView structView = new TableStructureView(data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), data.getName());

        ConnectionConfig config = data.getConnectionConfig();
        String typeLabel = data.getType() == DatabaseNodeData.NodeType.VIEW ? "视图" : "表";
        String tabTitle = data.getName() + "@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-" + typeLabel + "结构";
        Tab tab = new Tab(tabTitle);
        Image tabIcon = data.getType() == DatabaseNodeData.NodeType.VIEW ? module.getViewIcon() : module.getTableIcon();
        if (tabIcon != null) {
            ImageView tabIconView = new ImageView(tabIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(tabIconView);
        }
        tab.setContent(structView);
        tab.setUserData(tabId);

        ContextMenu structTabContextMenu = new ContextMenu();
        MenuItem structConfigItem = new MenuItem("表格配置");
        structConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.TABLE);
            GlobalConfig globalConfig = GlobalConfig.getInstance();
            structView.applyTableConfig(globalConfig);
        });
        MenuItem structRefreshItem = new MenuItem("刷新结构");
        structRefreshItem.setOnAction(e -> structView.loadStructure());
        structTabContextMenu.getItems().addAll(structConfigItem, structRefreshItem);
        tab.setContextMenu(structTabContextMenu);

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 打开数据：打开表/视图数据 Tab */
    public void handleTableDataDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = data.getConnectionConfig().getId() + "_" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "_" + data.getSchemaName() : "") + "_" + data.getName();
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        TableDataView dataView = new TableDataView(data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), data.getName());

        ConnectionConfig config = data.getConnectionConfig();
        String typeLabel = data.getType() == DatabaseNodeData.NodeType.VIEW ? "视图" : "表";
        String tabTitle = data.getName() + "@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-" + typeLabel;
        Tab tab = new Tab(tabTitle);
        Image tabIcon = data.getType() == DatabaseNodeData.NodeType.VIEW ? module.getViewIcon() : module.getTableIcon();
        if (tabIcon != null) {
            ImageView tabIconView = new ImageView(tabIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(tabIconView);
        }
        tab.setContent(dataView);
        tab.setUserData(tabId);

        ContextMenu tableTabContextMenu = new ContextMenu();
        MenuItem tableConfigItem = new MenuItem("表格配置");
        tableConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.TABLE);
            GlobalConfig globalConfig = GlobalConfig.getInstance();
            dataView.applyTableConfig(globalConfig);
        });
        MenuItem tableRefreshItem = new MenuItem("刷新数据");
        tableRefreshItem.setOnAction(e -> dataView.refreshData());
        tableTabContextMenu.getItems().addAll(tableConfigItem, tableRefreshItem);
        tab.setContextMenu(tableTabContextMenu);

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    // ==================== 右键菜单 ====================

    /**
     * 为数据库相关节点构建右键菜单项。
     * 处理 DATABASE/SCHEMA/TABLES_FOLDER/VIEWS_FOLDER/QUERY_FOLDER/BACKUP_FOLDER/TABLE/VIEW 类型节点。
     */
    @Override
    public void populateNodeContextMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case DATABASE -> {
                if (data.isOpened()) {
                    MenuItem closeDbItem = new MenuItem("关闭");
                    closeDbItem.setOnAction(e -> closeDatabase(item, data));
                    contextMenu.getItems().add(closeDbItem);
                } else {
                    MenuItem openDbItem = new MenuItem("打开");
                    openDbItem.setOnAction(e -> openDatabase(item, data));
                    contextMenu.getItems().add(openDbItem);
                }
                MenuItem editDbItem = new MenuItem("编辑");
                editDbItem.setOnAction(e -> handleEditDatabase(item, data));
                MenuItem deleteDbItem = new MenuItem("删除");
                deleteDbItem.setOnAction(e -> handleDeleteDatabase(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(new SeparatorMenuItem(), editDbItem, deleteDbItem, new SeparatorMenuItem(), refreshItem);
            }
            case SCHEMA -> {
                MenuItem openItem = new MenuItem("打开");
                openItem.setOnAction(e -> handleSchemaDoubleClick(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(openItem, new SeparatorMenuItem(), refreshItem);
            }
            case TABLES_FOLDER -> {
                MenuItem newTableItem = new MenuItem("新建表");
                newTableItem.setOnAction(e -> handleNewTable(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(newTableItem, new SeparatorMenuItem(), refreshItem);
            }
            case VIEWS_FOLDER -> {
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().add(refreshItem);
            }
            case QUERY_FOLDER -> {
                MenuItem newQueryItem = new MenuItem("新建查询");
                newQueryItem.setOnAction(e -> handleNewQuery(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(newQueryItem, new SeparatorMenuItem(), refreshItem);
            }
            case BACKUP_FOLDER -> {
                MenuItem newBackupItem = new MenuItem("新建备份");
                newBackupItem.setOnAction(e -> module.handleNewBackup(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(newBackupItem, new SeparatorMenuItem(), refreshItem);
            }
            case TABLE, VIEW -> {
                MenuItem designItem = new MenuItem("设计表");
                designItem.setOnAction(e -> handleTableStructureDoubleClick(item, data));
                MenuItem openDataItem = new MenuItem("打开数据");
                openDataItem.setOnAction(e -> handleTableDataDoubleClick(item, data));
                MenuItem deleteItem = new MenuItem("删除");
                deleteItem.setOnAction(e -> module.deleteDbNodes());
                contextMenu.getItems().addAll(designItem, openDataItem, new SeparatorMenuItem(), deleteItem);
            }
            case QUERY -> {
                MenuItem openQueryItem = new MenuItem("打开");
                openQueryItem.setOnAction(e -> handleQueryDoubleClick(item, data));
                MenuItem renameQueryItem = new MenuItem("重命名");
                renameQueryItem.setOnAction(e -> handleRenameQuery(item, data));
                MenuItem deleteQueryItem = new MenuItem("删除");
                deleteQueryItem.setOnAction(e -> handleDeleteQuery(item, data));
                contextMenu.getItems().addAll(openQueryItem, new SeparatorMenuItem(), renameQueryItem, deleteQueryItem);
            }
            case BACKUP -> {
                MenuItem restoreItem = new MenuItem("还原备份");
                restoreItem.setOnAction(e -> handleRestoreBackup(item, data));
                MenuItem openDirItem = new MenuItem("打开备份目录");
                openDirItem.setOnAction(e -> handleOpenBackupDir(data));
                MenuItem renameBackupItem = new MenuItem("重命名");
                renameBackupItem.setOnAction(e -> handleRenameBackup(item, data));
                MenuItem deleteBackupItem = new MenuItem("删除");
                deleteBackupItem.setOnAction(e -> handleDeleteBackup(item, data));
                contextMenu.getItems().addAll(restoreItem, new SeparatorMenuItem(), openDirItem, new SeparatorMenuItem(), renameBackupItem, deleteBackupItem);
            }
            default -> {}
        }
    }

    /** 双击数据库节点：已打开则切换展开状态，未打开则打开 */
    public void handleDatabaseDoubleClick(TreeItem<String> dbItem, DatabaseNodeData data) {
        if (data.isOpened()) {
            dbItem.setExpanded(!dbItem.isExpanded());
            return;
        }
        openDatabase(dbItem, data);
    }

    /** 关闭数据库节点：清理子节点数据并恢复未打开状态 */
    public void closeDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        module.removeDbNodeDataRecursive(dbItem);
        dbItem.getChildren().clear();
        data.setOpened(false);
        dbItem.setGraphic(module.getDbNodeIcon(data));
        dbItem.setExpanded(false);
    }

    /**
     * MySQL 专用主机图标更新：展开时使用 mysql_open.png，收起时使用 mysql.png，
     * 已连接时叠加绿色光晕。
     */
    protected void updateMysqlHostIcon(TreeItem<String> hostItem, ConnectionConfig config) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(16);
        imageView.setFitHeight(16);
        try {
            String iconPath = hostItem.isExpanded() ? "/images/connect/mysql_open.png" : "/images/connect/mysql.png";
            Image icon = new Image(getClass().getResourceAsStream(iconPath));
            if (icon != null) {
                imageView.setImage(icon);
                if (module.isHostConnected(hostItem)) {
                    imageView.setStyle("-fx-effect: dropshadow(gaussian, #4CAF50, 2, 0.5, 0, 0);");
                }
            }
        } catch (Exception e) {
            // fallback
        }
        hostItem.setGraphic(imageView);
    }

    // ==================== 查询节点 ====================

    /** 新建查询：打开未保存的 SQL 编辑器 Tab，保存时创建查询节点 */
    public void handleNewQuery(TreeItem<String> folderItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        SqlEditorView editorView = new SqlEditorView(module.getConnections(), config, dbName);

        Tab tab = new Tab("*未保存查询");
        Image tabIcon = module.getQueryIcon();
        if (tabIcon != null) {
            ImageView tabIconView = new ImageView(tabIcon);
            tabIconView.setFitWidth(14);
            tabIconView.setFitHeight(14);
            tab.setGraphic(tabIconView);
        }

        String tabId = "query_new_" + System.currentTimeMillis();
        tab.setUserData(tabId);
        tab.setContent(editorView);

        editorView.setOnTitleChange(title -> tab.setText(title));

        editorView.setOnSaveRequest(() -> {
            TextInputDialog dialog = new TextInputDialog("查询" + (folderItem.getChildren().size() + 1));
            dialog.setTitle("保存查询");
            dialog.setHeaderText(null);
            dialog.setContentText("查询名称：");
            dialog.showAndWait().ifPresent(name -> {
                if (name.trim().isEmpty()) return;

                String queryName = name.trim();
                editorView.doSave(queryName);

                TreeItem<String> queryItem = new TreeItem<>(queryName);
                DatabaseNodeData queryData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, queryName, config, dbName);
                queryItem.setGraphic(module.getDbNodeIcon(queryData));
                module.getDbNodeDataMap().put(queryItem, queryData);
                folderItem.getChildren().add(queryItem);
                folderItem.setExpanded(true);

                editorView.setQueryNode(queryItem);

                String newTabId = "query_" + config.getId() + "_" + dbName + "_" + queryName;
                tab.setUserData(newTabId);
            });
        });

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        editorView.markModified();

        if (!module.ensureTabPaneInstalled()) return;
        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 双击查询节点：打开 SQL 编辑器 Tab */
    public void handleQueryDoubleClick(TreeItem<String> queryItem, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = "query_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName() + "_" + data.getName();
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        SqlEditorView editorView = new SqlEditorView(module.getConnections(), data.getConnectionConfig(), data.getDatabaseName());
        editorView.setQueryName(data.getName());
        editorView.setQueryNode(queryItem);
        editorView.loadFromFile(data.getConnectionConfig().getName(), data.getDatabaseName(), data.getName());

        Tab tab = new Tab(data.getName());
        Image tabIcon = module.getQueryIcon();
        if (tabIcon != null) {
            ImageView tabIconView = new ImageView(tabIcon);
            tabIconView.setFitWidth(14);
            tabIconView.setFitHeight(14);
            tab.setGraphic(tabIconView);
        }
        tab.setContent(editorView);
        tab.setUserData(tabId);

        editorView.setOnTitleChange(title -> tab.setText(title));

        editorView.setOnSaveRequest(() -> {
            TextInputDialog dialog = new TextInputDialog(data.getName());
            dialog.setTitle("保存查询");
            dialog.setHeaderText(null);
            dialog.setContentText("查询名称：");
            dialog.showAndWait().ifPresent(name -> {
                if (name.trim().isEmpty()) return;
                editorView.doSave(name.trim());
                queryItem.setValue(name.trim());
            });
        });

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 重命名查询节点：重命名文件并更新节点数据 */
    public void handleRenameQuery(TreeItem<String> queryItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog(data.getName());
        dialog.setTitle("重命名查询");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String newName = name.trim();

            String oldSanitizedConn = sanitizeForFs(data.getConnectionConfig().getName());
            String oldSanitizedDb = sanitizeForFs(data.getDatabaseName());
            String oldSanitizedQuery = sanitizeForFs(data.getName());
            String newSanitizedQuery = sanitizeForFs(newName);

            java.nio.file.Path oldFile = Paths.get(System.getProperty("user.home") + "/.tomato",
                    oldSanitizedConn, oldSanitizedDb, "query", oldSanitizedQuery + ".sql");
            java.nio.file.Path newFile = Paths.get(System.getProperty("user.home") + "/.tomato",
                    oldSanitizedConn, oldSanitizedDb, "query", newSanitizedQuery + ".sql");

            try {
                if (Files.exists(oldFile)) {
                    String content = Files.readString(oldFile, StandardCharsets.UTF_8);
                    Files.createDirectories(newFile.getParent());
                    Files.writeString(newFile, content, StandardCharsets.UTF_8);
                    Files.deleteIfExists(oldFile);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            queryItem.setValue(newName);
            DatabaseNodeData newData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, newName, data.getConnectionConfig(), data.getDatabaseName());
            module.getDbNodeDataMap().remove(queryItem);
            module.getDbNodeDataMap().put(queryItem, newData);
        });
    }

    /** 删除查询节点：清理文件并移除节点 */
    public void handleDeleteQuery(TreeItem<String> queryItem, DatabaseNodeData data) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除查询");
        confirm.setHeaderText("确定要删除查询 \"" + data.getName() + "\" 吗？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                SqlEditorView.cleanupQueryFile(data.getConnectionConfig().getName(), data.getDatabaseName(), data.getName());
                module.getDbNodeDataMap().remove(queryItem);
                queryItem.getParent().getChildren().remove(queryItem);
            }
        });
    }

    // ==================== 备份节点 ====================

    /** 还原备份：打开还原对话框 */
    public void handleRestoreBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
        Stage stage = module.getStage();
        if (stage == null) return;

        RestoreDialog dialog = new RestoreDialog(stage,
                data.getConnectionConfig(), data.getDatabaseName(), data.getName());
        dialog.showAndWait();
    }

    /** 打开备份所在目录 */
    public void handleOpenBackupDir(DatabaseNodeData data) {
        String sanitizedConn = sanitizeForFs(data.getConnectionConfig().getName());
        String sanitizedDb = sanitizeForFs(data.getDatabaseName());
        java.nio.file.Path backupDir = Paths.get(System.getProperty("user.home") + "/.tomato",
                sanitizedConn, sanitizedDb, "backup");
        java.nio.file.Path backupFile = backupDir.resolve(data.getName() + ".nb3");

        new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                    if (backupFile.toFile().exists()) {
                        if (desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) {
                            desktop.browseFileDirectory(backupFile.toFile());
                        } else {
                            desktop.open(backupDir.toFile());
                        }
                    } else {
                        desktop.open(backupDir.toFile());
                    }
                }
            } catch (Exception e) {
                try {
                    String[] cmd = {
                            "xdg-open", backupDir.toAbsolutePath().toString()
                    };
                    Runtime.getRuntime().exec(cmd);
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("打开目录失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法打开备份目录: " + ex.getMessage());
                        alert.showAndWait();
                    });
                }
            }
        }, "OpenBackupDir").start();
    }

    /** 重命名备份：重命名文件并更新节点数据 */
    public void handleRenameBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog(data.getName());
        dialog.setTitle("重命名备份");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String newName = name.trim();
            try {
                BackupService.renameBackupFile(data.getConnectionConfig().getName(),
                        data.getDatabaseName(), data.getName(), newName);
                backupItem.setValue(newName);
                DatabaseNodeData newData = new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP,
                        newName, data.getConnectionConfig(), data.getDatabaseName());
                module.getDbNodeDataMap().remove(backupItem);
                module.getDbNodeDataMap().put(backupItem, newData);
            } catch (Exception e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("重命名失败");
                err.setHeaderText(null);
                err.setContentText(e.getMessage());
                err.showAndWait();
            }
        });
    }

    /** 删除备份节点：删除文件并移除节点 */
    public void handleDeleteBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除备份");
        confirm.setHeaderText("确定要删除备份 \"" + data.getName() + "\" 吗？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                BackupService.deleteBackupFile(data.getConnectionConfig().getName(),
                        data.getDatabaseName(), data.getName());
                module.getDbNodeDataMap().remove(backupItem);
                backupItem.getParent().getChildren().remove(backupItem);
            }
        });
    }

    // ==================== 工具方法 ====================

    /** 文件系统名称清理：替换非法字符 */
    private String sanitizeForFs(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
    }
}
