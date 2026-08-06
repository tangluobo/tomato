package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.module.connect.dialog.BackupDialog;
import com.tangluobo.tomato.module.connect.dialog.ConnectionConfigDialog;
import com.tangluobo.tomato.module.connect.dialog.FolderDialog;
import com.tangluobo.tomato.module.connect.dialog.RestoreDialog;
import com.tangluobo.tomato.module.connect.handler.*;
import com.tangluobo.tomato.ssh.LocalTerminalPane;
import com.tangluobo.tomato.ssh.SSHTerminalPane;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.stage.Stage;
import javafx.beans.value.ChangeListener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ConnectModule implements Module {
    private TreeView<String> treeView;
    TreeItem<String> root;
    private List<ConnectionConfig> connections;
    private Map<TreeItem<String>, ConnectionConfig> itemConfigMap;
    private Map<TreeItem<String>, DatabaseNodeData> dbNodeDataMap;
    private Map<TreeItem<String>, Boolean> connectionStateMap;
    private Set<TreeItem<String>> connectingHosts;
    private TreeItem<String> editingItem;
    private TreeItem<String> selectedItemBeforeClick;
    private TreeItem<String> recentlyEditedItem;
    private Timeline singleClickTimer;
    private Image folderIcon;
    private Image dbIcon;
    private Image dbIconGray;
    private Image schemaIcon;
    private Image schemaOpenIcon;
    private Image tableIcon;
    private Image viewIcon;
    private Image functionIcon;
    private Image backupIcon;
    private Image queryIcon;
    private Image rocketmqTopicIcon;
    private Image rocketmqConsumerIcon;
    private Image rocketmqClusterIcon;
    private Image rocketmqMessageIcon;
    private Image rocketmqTopicItemIcon;
    private Image rocketmqConsumerItemIcon;
    private Image rocketmqBrokerItemIcon;
    private Image rocketmqMessageItemIcon;
    private Image aliyunProductIcon;
    private Image aliyunEcsIcon;
    private Image aliyunDomainIcon;
    private TextField searchField;

    // 内容区域
    private VBox contentArea;
    TabPane terminalTabPane;

    @Override
    public String getName() {
        return "连接";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
        folderIcon = loadFolderIcon();
        loadDbIcons();

        HBox headerBar = new HBox();
        headerBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #D9D9D7; -fx-border-width: 0 0 1 0;");
        headerBar.setPrefHeight(52);
        headerBar.setMinHeight(52);
        headerBar.setMaxHeight(52);
        headerBar.setPadding(new Insets(10, 15, 10, 15));

        TextField searchField = new TextField();
        searchField.setPromptText("搜索");
        searchField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 6 10; -fx-font-size: 13px; -fx-border-color: transparent;");
        searchField.prefWidthProperty().bind(headerBar.widthProperty());
        this.searchField = searchField;

        headerBar.getChildren().add(searchField);

        treeView = new TreeView<>();
        treeView.setStyle("-fx-background-color: transparent; -fx-cell-size: 35px;");
        treeView.setFixedCellSize(35);
        treeView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        root = new TreeItem<>("连接");
        root.setExpanded(true);
        treeView.setRoot(root);
        treeView.setShowRoot(false);
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        itemConfigMap = new HashMap<>();
        dbNodeDataMap = new HashMap<>();
        connectionStateMap = new HashMap<>();
        connectingHosts = new HashSet<>();
        connections = ConfigManager.loadConnections();
        loadTree();

        setupContextMenu();
        setupDragAndDrop();

        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterTree(newVal));

        sidebarContainer.getChildren().addAll(headerBar, treeView);
        treeView.prefHeightProperty().bind(sidebarContainer.heightProperty().subtract(50));
    }

    private Image loadFolderIcon() {
        try {
            return new Image(getClass().getResourceAsStream("/images/connect/folder.png"));
        } catch (Exception e) {
            return null;
        }
    }

    private void loadDbIcons() {
        try { dbIcon = new Image(getClass().getResourceAsStream("/images/connect/database.png")); } catch (Exception e) { dbIcon = null; }
        try { dbIconGray = new Image(getClass().getResourceAsStream("/images/connect/database_gray.png")); } catch (Exception e) { dbIconGray = null; }
        try { schemaIcon = new Image(getClass().getResourceAsStream("/images/connect/mod.png")); } catch (Exception e) { schemaIcon = null; }
        try { schemaOpenIcon = new Image(getClass().getResourceAsStream("/images/connect/mod_open.png")); } catch (Exception e) { schemaOpenIcon = null; }
        try { tableIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { tableIcon = null; }
        try { viewIcon = new Image(getClass().getResourceAsStream("/images/connect/view.png")); } catch (Exception e) { viewIcon = null; }
        try { queryIcon = new Image(getClass().getResourceAsStream("/images/connect/query.png")); } catch (Exception e) { queryIcon = null; }
        try { functionIcon = new Image(getClass().getResourceAsStream("/images/connect/function.png")); } catch (Exception e) { functionIcon = null; }
        try { backupIcon = new Image(getClass().getResourceAsStream("/images/connect/backup.png")); } catch (Exception e) { backupIcon = null; }
        try { rocketmqTopicIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { rocketmqTopicIcon = null; }
        try { rocketmqConsumerIcon = new Image(getClass().getResourceAsStream("/images/connect/user.png")); } catch (Exception e) { rocketmqConsumerIcon = null; }
        try { rocketmqClusterIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { rocketmqClusterIcon = null; }
        try { rocketmqMessageIcon = new Image(getClass().getResourceAsStream("/images/connect/code.png")); } catch (Exception e) { rocketmqMessageIcon = null; }
        try { rocketmqTopicItemIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { rocketmqTopicItemIcon = null; }
        try { rocketmqConsumerItemIcon = new Image(getClass().getResourceAsStream("/images/connect/user.png")); } catch (Exception e) { rocketmqConsumerItemIcon = null; }
        try { rocketmqBrokerItemIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { rocketmqBrokerItemIcon = null; }
        try { rocketmqMessageItemIcon = new Image(getClass().getResourceAsStream("/images/connect/code.png")); } catch (Exception e) { rocketmqMessageItemIcon = null; }
        try { aliyunProductIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { aliyunProductIcon = null; }
        try { aliyunEcsIcon = new Image(getClass().getResourceAsStream("/images/connect/server.png")); } catch (Exception e) { aliyunEcsIcon = null; }
        try { aliyunDomainIcon = new Image(getClass().getResourceAsStream("/images/connect/s3.png")); } catch (Exception e) { aliyunDomainIcon = null; }
    }

    /**
     * 根据连接类型创建对应的数据库处理器
     */
    AbstractDbHandler createDbHandler(ConnectionConfig config) {
        return switch (config.getType()) {
            case MYSQL -> new MysqlDbHandler(this);
            case POSTGRESQL -> new PostgresDbHandler(this);
            case ORACLE -> new OracleDbHandler(this);
            default -> null;
        };
    }

    /** 供 handler 调用：根据节点数据获取图标 */
    public ImageView getDbNodeIcon(DatabaseNodeData data) {
        ImageView iv = new ImageView();
        iv.setFitWidth(20);
        iv.setFitHeight(20);
        Image icon = switch (data.getType()) {
            case DATABASE -> data.isOpened() ? dbIcon : dbIconGray;
            case REDIS_DB -> data.isOpened() ? dbIcon : dbIconGray;
            case SCHEMA -> data.isOpened() ? schemaOpenIcon : schemaIcon;
            case TABLES_FOLDER -> tableIcon;
            case VIEWS_FOLDER -> viewIcon;
            case QUERY_FOLDER -> queryIcon;
            case FUNCTION_FOLDER -> functionIcon;
            case BACKUP_FOLDER -> backupIcon;
            case TABLE -> tableIcon;
            case VIEW -> viewIcon;
            case BACKUP -> backupIcon;
            case QUERY -> queryIcon;
            case ROCKETMQ_TOPICS_FOLDER -> rocketmqTopicIcon;
            case ROCKETMQ_CONSUMERS_FOLDER -> rocketmqConsumerIcon;
            case ROCKETMQ_CLUSTER_FOLDER -> rocketmqClusterIcon;
            case ROCKETMQ_MESSAGES_FOLDER -> rocketmqMessageIcon;
            case ROCKETMQ_TOPIC -> rocketmqTopicItemIcon;
            case ROCKETMQ_CONSUMER -> rocketmqConsumerItemIcon;
            case ROCKETMQ_BROKER -> rocketmqBrokerItemIcon;
            case ROCKETMQ_MESSAGE -> rocketmqMessageItemIcon;
            case ALIYUN_PRODUCT_FOLDER -> aliyunProductIcon;
            case ALIYUN_ECS_INSTANCE -> aliyunEcsIcon;
            case ALIYUN_DOMAIN -> aliyunDomainIcon;
        };
        if (icon != null) iv.setImage(icon);
        return iv;
    }

    /** 供 handler 调用：获取表图标（原始 Image） */
    public Image getTableIcon() { return tableIcon; }

    /** 供 handler 调用：获取视图图标（原始 Image） */
    public Image getViewIcon() { return viewIcon; }

    /** 供 handler 调用：获取查询图标（原始 Image） */
    Image getQueryIcon() { return queryIcon; }

    private void loadTree() {
        root.getChildren().clear();
        itemConfigMap.clear();
        dbNodeDataMap.clear();
        for (ConnectionConfig config : connections) {
            if (config.getParentId() == null || config.getParentId().isEmpty()) {
                TreeItem<String> item = createTreeItem(config);
                root.getChildren().add(item);
            }
        }
        for (ConnectionConfig config : connections) {
            if (config.getParentId() != null && !config.getParentId().isEmpty()) {
                TreeItem<String> parent = findItemById(root, config.getParentId());
                if (parent != null) {
                    TreeItem<String> item = createTreeItem(config);
                    parent.getChildren().add(item);
                }
            }
        }
    }

    private void filterTree(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            loadTree();
            return;
        }
        String kw = keyword.trim().toLowerCase();

        root.getChildren().clear();
        itemConfigMap.clear();

        for (ConnectionConfig config : connections) {
            if (config.getParentId() == null || config.getParentId().isEmpty()) {
                TreeItem<String> item = createTreeItem(config);
                root.getChildren().add(item);
            }
        }
        for (ConnectionConfig config : connections) {
            if (config.getParentId() != null && !config.getParentId().isEmpty()) {
                TreeItem<String> parent = findItemById(root, config.getParentId());
                if (parent != null) {
                    TreeItem<String> item = createTreeItem(config);
                    parent.getChildren().add(item);
                }
            }
        }

        filterTreeItem(root, kw);
        expandAll(root);
    }

    private boolean filterTreeItem(TreeItem<String> item, String keyword) {
        boolean selfMatch = item.getValue() != null && item.getValue().toLowerCase().contains(keyword);

        if (selfMatch) {
            for (TreeItem<String> child : item.getChildren()) {
                filterTreeItem(child, keyword);
            }
            return true;
        }

        boolean childMatch = false;
        List<TreeItem<String>> toRemove = new ArrayList<>();
        for (TreeItem<String> child : item.getChildren()) {
            boolean match = filterTreeItem(child, keyword);
            if (match) {
                childMatch = true;
            } else {
                toRemove.add(child);
            }
        }
        item.getChildren().removeAll(toRemove);
        return childMatch;
    }

    private void expandAll(TreeItem<String> item) {
        item.setExpanded(true);
        for (TreeItem<String> child : item.getChildren()) {
            expandAll(child);
        }
    }

    private TreeItem<String> createTreeItem(ConnectionConfig config) {
        TreeItem<String> item = new TreeItem<>(config.getName());
        item.setGraphic(getIconForConfig(config));
        itemConfigMap.put(item, config);
        connectionStateMap.put(item, false);

        if (config.getType() == ConnectType.MYSQL) {
            item.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                updateMysqlHostIcon(item, config);
            });
        }

        if (config.getType() == ConnectType.REDIS) {
            item.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                Boolean connected = connectionStateMap.get(item);
                if (connected != null && connected) {
                    updateHostIcon(item, config, true);
                }
            });
        }

        return item;
    }

    /** 供 handler 调用：根据连接配置获取图标 */
    public ImageView getIconForConfig(ConnectionConfig config) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(20);
        imageView.setFitHeight(20);

        if (config.getType() == null) {
            if (folderIcon != null) {
                imageView.setImage(folderIcon);
            }
        } else {
            try {
                String iconPath = config.getType().getIconPath();
                Image icon = new Image(getClass().getResourceAsStream(iconPath));
                if (icon != null) {
                    imageView.setImage(icon);
                }
            } catch (Exception e) {
            }
        }
        return imageView;
    }

    /** 供 handler 调用：获取树根节点 */
    public TreeItem<String> getRoot() {
        return root;
    }

    /** 供 handler 调用：获取连接中的主机节点集合 */
    public Set<TreeItem<String>> getConnectingHosts() {
        return connectingHosts;
    }

    /** 供 handler 调用：获取节点数据映射 */
    public Map<TreeItem<String>, DatabaseNodeData> getDbNodeDataMap() {
        return dbNodeDataMap;
    }

    /** 供 handler 调用：获取树视图 */
    public TreeView<String> getTreeView() {
        return treeView;
    }

    /** 供 handler 调用：更新连接状态映射 */
    public void markConnectionState(TreeItem<String> hostItem, boolean connected) {
        connectionStateMap.put(hostItem, connected);
    }

    /** 供 handler 调用：根据 ID 查找树节点 */
    public TreeItem<String> findItemById(TreeItem<String> root, String id) {
        ConnectionConfig config = itemConfigMap.get(root);
        if (config != null && config.getId().equals(id)) {
            return root;
        }
        for (TreeItem<String> child : root.getChildren()) {
            TreeItem<String> found = findItemById(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private ContextMenu contextMenu;

    private void setupContextMenu() {
        contextMenu = new ContextMenu();

        treeView.setOnContextMenuRequested(event -> {
            contextMenu.hide();
            contextMenu.getItems().clear();

            Node node = event.getPickResult().getIntersectedNode();
            TreeItem<String> clickedItem = null;
            while (node != null && !(node instanceof TreeCell)) {
                node = node.getParent();
            }
            if (node instanceof TreeCell<?> cell) {
                clickedItem = (TreeItem<String>) cell.getTreeItem();
            }

            final TreeItem<String> targetItem = clickedItem;

            if (targetItem == null) {
                MenuItem addFolder = new MenuItem("新建目录");
                addFolder.setOnAction(e -> handleAddFolder(root));
                MenuItem addConnection = new MenuItem("新建连接");
                addConnection.setOnAction(e -> handleAddConnection(root));
                contextMenu.getItems().addAll(addFolder, addConnection);
            } else {
                DatabaseNodeData dbData = dbNodeDataMap.get(targetItem);
                if (dbData != null) {
                    switch (dbData.getType()) {
                        case DATABASE -> {
                            if (dbData.isOpened()) {
                                MenuItem closeDbItem = new MenuItem("关闭");
                                closeDbItem.setOnAction(e -> closeDatabase(targetItem, dbData));
                                contextMenu.getItems().add(closeDbItem);
                            } else {
                                MenuItem openDbItem = new MenuItem("打开");
                                openDbItem.setOnAction(e -> openDatabase(targetItem, dbData));
                                contextMenu.getItems().add(openDbItem);
                            }
                            MenuItem editDbItem = new MenuItem("编辑");
                            editDbItem.setOnAction(e -> {
                                AbstractDbHandler h = createDbHandler(dbData.getConnectionConfig());
                                if (h != null) h.handleEditDatabase(targetItem, dbData);
                            });
                            MenuItem deleteDbItem = new MenuItem("删除");
                            deleteDbItem.setOnAction(e -> {
                                AbstractDbHandler h = createDbHandler(dbData.getConnectionConfig());
                                if (h != null) h.handleDeleteDatabase(targetItem, dbData);
                            });
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> refreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(new SeparatorMenuItem(), editDbItem, deleteDbItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case REDIS_DB -> {
                            MenuItem openItem = new MenuItem("打开");
                            openItem.setOnAction(e -> {
                                ConnectHandler h = createConnectHandler(dbData.getConnectionConfig());
                                if (h instanceof RedisConnectHandler r) {
                                    r.handleRedisDbDoubleClick(this, targetItem, dbData);
                                }
                            });
                            contextMenu.getItems().add(openItem);
                        }
                        case SCHEMA -> {
                            MenuItem openItem = new MenuItem("打开");
                            openItem.setOnAction(e -> handleSchemaDoubleClick(targetItem, dbData));
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> refreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(openItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case ROCKETMQ_TOPICS_FOLDER, ROCKETMQ_CONSUMERS_FOLDER, ROCKETMQ_CLUSTER_FOLDER, ALIYUN_PRODUCT_FOLDER -> {
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> refreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(refreshItem);
                        }
                        case ROCKETMQ_TOPIC -> {
                            MenuItem openItem = new MenuItem("查看详情");
                            openItem.setOnAction(e -> handleRocketmqTopicDoubleClick(targetItem, dbData));
                            MenuItem deleteItem = new MenuItem("删除主题");
                            deleteItem.setOnAction(e -> handleDeleteRocketmqTopic(targetItem, dbData));
                            contextMenu.getItems().addAll(openItem, new SeparatorMenuItem(), deleteItem);
                        }
                        case ROCKETMQ_CONSUMER -> {
                            MenuItem openItem = new MenuItem("查看详情");
                            openItem.setOnAction(e -> handleRocketmqConsumerDoubleClick(targetItem, dbData));
                            contextMenu.getItems().addAll(openItem);
                        }
                        case ROCKETMQ_BROKER -> {
                            MenuItem openItem = new MenuItem("查看详情");
                            openItem.setOnAction(e -> handleRocketmqBrokerDoubleClick(targetItem, dbData));
                            contextMenu.getItems().addAll(openItem);
                        }
                        case TABLES_FOLDER -> {
                            MenuItem newTableItem = new MenuItem("新建表");
                            newTableItem.setOnAction(e -> {
                                AbstractDbHandler h = createDbHandler(dbData.getConnectionConfig());
                                if (h != null) h.handleNewTable(targetItem, dbData);
                            });
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> refreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(newTableItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case VIEWS_FOLDER -> {
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> refreshDbNode(targetItem, dbData));
                            contextMenu.getItems().add(refreshItem);
                        }
                        case QUERY_FOLDER -> {
                            MenuItem newQueryItem = new MenuItem("新建查询");
                            newQueryItem.setOnAction(e -> handleNewQuery(targetItem, dbData));
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> refreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(newQueryItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case BACKUP_FOLDER -> {
                            MenuItem newBackupItem = new MenuItem("新建备份");
                            newBackupItem.setOnAction(e -> handleNewBackup(targetItem, dbData));
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> refreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(newBackupItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case TABLE, VIEW -> {
                            MenuItem designItem = new MenuItem("设计表");
                            designItem.setOnAction(e -> {
                                AbstractDbHandler h = createDbHandler(dbData.getConnectionConfig());
                                if (h != null) h.handleTableStructureDoubleClick(targetItem, dbData);
                            });
                            MenuItem openDataItem = new MenuItem("打开数据");
                            openDataItem.setOnAction(e -> {
                                AbstractDbHandler h = createDbHandler(dbData.getConnectionConfig());
                                if (h != null) h.handleTableDataDoubleClick(targetItem, dbData);
                            });
                            MenuItem deleteItem = new MenuItem("删除");
                            deleteItem.setOnAction(e -> deleteDbNodes());
                            contextMenu.getItems().addAll(designItem, openDataItem, new SeparatorMenuItem(), deleteItem);
                        }
                        case QUERY -> {
                            MenuItem openQueryItem = new MenuItem("打开");
                            openQueryItem.setOnAction(e -> handleQueryDoubleClick(targetItem, dbData));
                            MenuItem renameQueryItem = new MenuItem("重命名");
                            renameQueryItem.setOnAction(e -> handleRenameQuery(targetItem, dbData));
                            MenuItem deleteQueryItem = new MenuItem("删除");
                            deleteQueryItem.setOnAction(e -> handleDeleteQuery(targetItem, dbData));
                            contextMenu.getItems().addAll(openQueryItem, new SeparatorMenuItem(), renameQueryItem, deleteQueryItem);
                        }
                        case BACKUP -> {
                            MenuItem restoreItem = new MenuItem("还原备份");
                            restoreItem.setOnAction(e -> handleRestoreBackup(targetItem, dbData));
                            MenuItem openDirItem = new MenuItem("打开备份目录");
                            openDirItem.setOnAction(e -> handleOpenBackupDir(dbData));
                            MenuItem renameBackupItem = new MenuItem("重命名");
                            renameBackupItem.setOnAction(e -> handleRenameBackup(targetItem, dbData));
                            MenuItem deleteBackupItem = new MenuItem("删除");
                            deleteBackupItem.setOnAction(e -> handleDeleteBackup(targetItem, dbData));
                            contextMenu.getItems().addAll(restoreItem, new SeparatorMenuItem(), openDirItem, new SeparatorMenuItem(), renameBackupItem, deleteBackupItem);
                        }
                    }
                } else {
                    ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                    if (targetConfig != null && targetConfig.getType() != null) {
                        boolean isDatabase = targetConfig.getType() == ConnectType.MYSQL
                                || targetConfig.getType() == ConnectType.POSTGRESQL
                                || targetConfig.getType() == ConnectType.ORACLE;
                        boolean isRedis = targetConfig.getType() == ConnectType.REDIS;
                        boolean isRocketmq = targetConfig.getType() == ConnectType.ROCKETMQ;
                        boolean isAliyun = targetConfig.getType() == ConnectType.ALIYUN;
                        if (isDatabase) {
                            MenuItem createDbItem = new MenuItem("新建数据库");
                            createDbItem.setOnAction(e -> {
                                AbstractDbHandler h = createDbHandler(targetConfig);
                                if (h != null) h.handleCreateDatabase(targetItem, targetConfig);
                            });
                            contextMenu.getItems().add(createDbItem);
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem refreshItem = new MenuItem("刷新");
                                refreshItem.setOnAction(e -> refreshDbHost(targetItem, targetConfig));
                                MenuItem closeConnItem = new MenuItem("关闭连接");
                                closeConnItem.setOnAction(e -> closeHostConnection(targetItem, targetConfig));
                                contextMenu.getItems().addAll(refreshItem, closeConnItem);
                            }
                        }
                        if (isRedis) {
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem refreshItem = new MenuItem("刷新");
                                refreshItem.setOnAction(e -> refreshDbHost(targetItem, targetConfig));
                                MenuItem closeConnItem = new MenuItem("关闭连接");
                                closeConnItem.setOnAction(e -> closeHostConnection(targetItem, targetConfig));
                                contextMenu.getItems().addAll(refreshItem, closeConnItem);
                            }
                        }
                        if (isRocketmq) {
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem refreshItem = new MenuItem("刷新");
                                refreshItem.setOnAction(e -> refreshDbHost(targetItem, targetConfig));
                                MenuItem closeConnItem = new MenuItem("关闭连接");
                                closeConnItem.setOnAction(e -> closeHostConnection(targetItem, targetConfig));
                                contextMenu.getItems().addAll(refreshItem, closeConnItem);
                            }
                        }
                        if (isAliyun) {
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem refreshItem = new MenuItem("刷新");
                                refreshItem.setOnAction(e -> refreshDbHost(targetItem, targetConfig));
                                contextMenu.getItems().add(refreshItem);
                            }
                        }
                        MenuItem connectItem = new MenuItem("连接");
                        connectItem.setOnAction(e -> handleConnect(targetConfig));
                        MenuItem editItem = new MenuItem("编辑");
                        editItem.setOnAction(e -> handleEdit(targetItem));
                        MenuItem renameItem = new MenuItem("重命名");
                        renameItem.setOnAction(e -> {
                            editingItem = targetItem;
                            Platform.runLater(() -> {
                                treeView.requestFocus();
                                treeView.setEditable(true);
                                treeView.edit(targetItem);
                            });
                        });
                        MenuItem copyItem = new MenuItem("复制连接");
                        copyItem.setOnAction(e -> handleCopyConnection(targetItem, targetConfig));
                        MenuItem deleteItem = new MenuItem("删除");
                        deleteItem.setOnAction(e -> handleDelete(targetItem));
                        contextMenu.getItems().addAll(connectItem, new SeparatorMenuItem(), editItem, renameItem, copyItem, new SeparatorMenuItem(), deleteItem);
                    } else {
                        MenuItem addFolder = new MenuItem("新建目录");
                        addFolder.setOnAction(e -> handleAddFolder(targetItem));
                        MenuItem addConnection = new MenuItem("新建连接");
                        addConnection.setOnAction(e -> handleAddConnection(targetItem));
                        MenuItem renameItem = new MenuItem("重命名");
                        renameItem.setOnAction(e -> {
                            editingItem = targetItem;
                            Platform.runLater(() -> {
                                treeView.requestFocus();
                                treeView.setEditable(true);
                                treeView.edit(targetItem);
                            });
                        });
                        MenuItem deleteItem = new MenuItem("删除");
                        deleteItem.setOnAction(e -> handleDelete(targetItem));
                        contextMenu.getItems().addAll(addFolder, addConnection, new SeparatorMenuItem(), renameItem, deleteItem);
                    }
                }
            }

            contextMenu.show(treeView, event.getScreenX(), event.getScreenY());
        });

        treeView.setOnMousePressed(event -> contextMenu.hide());

        treeView.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (event.getClickCount() == 1) {
                selectedItemBeforeClick = treeView.getSelectionModel().getSelectedItem();
            }
        });

        treeView.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;

            TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem == null) return;

            // 判断点击是否在选中项的文本区域上（排除箭头和图标区域）
            Node clickedNode = event.getPickResult().getIntersectedNode();
            TreeItem<String> clickedItem = null;
            boolean isTextClick = false;
            Node n = clickedNode;
            while (n != null && !(n instanceof TreeCell)) {
                if (n.getClass().getName().equals("com.sun.javafx.scene.control.LabeledText")) {
                    isTextClick = true;
                }
                n = n.getParent();
            }
            if (n instanceof TreeCell<?> cell) {
                clickedItem = (TreeItem<String>) cell.getTreeItem();
            }
            boolean clickOnSelectedItem = isTextClick && selectedItem == clickedItem;

            DatabaseNodeData dbData = dbNodeDataMap.get(selectedItem);
            ConnectionConfig config = itemConfigMap.get(selectedItem);
            boolean isTableOrView = dbData != null
                    && (dbData.getType() == DatabaseNodeData.NodeType.TABLE || dbData.getType() == DatabaseNodeData.NodeType.VIEW);
            boolean isFolder = dbData == null && config != null && config.getType() == null;
            boolean isHost = dbData == null && config != null && config.getType() != null;

            boolean wasAlreadySelected = clickOnSelectedItem && selectedItem == selectedItemBeforeClick;
            boolean canReedit = wasAlreadySelected || (clickOnSelectedItem && selectedItem == recentlyEditedItem);
            if (selectedItem != recentlyEditedItem) {
                recentlyEditedItem = null;
            }

            if (dbData != null) {
                if (event.getClickCount() == 2) {
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }
                    event.consume();
                    handleDbNodeDoubleClick(selectedItem, dbData);
                    selectedItemBeforeClick = null;
                    recentlyEditedItem = null;
                    if (editingItem != null) {
                        editingItem = null;
                        treeView.setEditable(false);
                    }
                    return;
                }

                if (event.getClickCount() == 1) {
                    if (isTableOrView && canReedit && editingItem == null) {
                        TreeItem<String> itemToEdit = selectedItem;
                        if (singleClickTimer != null) {
                            singleClickTimer.stop();
                        }
                        singleClickTimer = new Timeline(new KeyFrame(
                                javafx.util.Duration.millis(300),
                                ae -> {
                                    if (editingItem == null && itemToEdit == treeView.getSelectionModel().getSelectedItem()) {
                                        editingItem = itemToEdit;
                                        recentlyEditedItem = null;
                                        treeView.requestFocus();
                                        treeView.setEditable(true);
                                        treeView.edit(itemToEdit);
                                    }
                                    singleClickTimer = null;
                                }
                        ));
                        singleClickTimer.play();
                        selectedItemBeforeClick = null;
                        return;
                    }
                }
            } else if (event.getClickCount() == 2) {
                if (singleClickTimer != null) {
                    singleClickTimer.stop();
                    singleClickTimer = null;
                }
                if (editingItem != null) {
                    editingItem = null;
                    treeView.setEditable(false);
                }
                if (isHost) {
                    triggerHostDoubleClick(selectedItem, config);
                }
                selectedItemBeforeClick = null;
                recentlyEditedItem = null;
            } else if ((isFolder || isHost) && event.getClickCount() == 1 && canReedit && editingItem == null) {
                TreeItem<String> itemToEdit = selectedItem;
                if (singleClickTimer != null) {
                    singleClickTimer.stop();
                }
                singleClickTimer = new Timeline(new KeyFrame(
                        javafx.util.Duration.millis(300),
                        ae -> {
                            if (editingItem == null && itemToEdit == treeView.getSelectionModel().getSelectedItem()) {
                                editingItem = itemToEdit;
                                recentlyEditedItem = null;
                                treeView.requestFocus();
                                treeView.setEditable(true);
                                treeView.edit(itemToEdit);
                            }
                            singleClickTimer = null;
                        }
                ));
                singleClickTimer.play();
                selectedItemBeforeClick = null;
            }
        });
    }

    private static final String DRAG_PREFIX = "ConnectItem|";

    private void setupDragAndDrop() {
        treeView.setCellFactory(tv -> {
            TreeCell<String> cell = new TreeCell<>() {
                private final Path arrowPath;
                private final StackPane disclosurePane;
                private TreeItem<String> currentTreeItem;
                private ChangeListener<Boolean> expandedListener;
                private TextField editField;

                {
                    arrowPath = new Path(
                            new MoveTo(2, 0),
                            new LineTo(7, 5),
                            new LineTo(2, 10)
                    );
                    arrowPath.setStroke(Color.valueOf("#888888"));
                    arrowPath.setStrokeWidth(1.8);
                    arrowPath.setFill(null);
                    arrowPath.setStrokeLineCap(StrokeLineCap.ROUND);
                    arrowPath.setStrokeLineJoin(StrokeLineJoin.ROUND);

                    disclosurePane = new StackPane(arrowPath);
                    disclosurePane.setAlignment(Pos.CENTER);
                    disclosurePane.setPrefSize(16, 35);
                    disclosurePane.setMinSize(16, 35);

                    setDisclosureNode(disclosurePane);
                }

                @Override
                public void startEdit() {
                    TreeItem<String> treeItem = getTreeItem();
                    if (treeItem == null || editingItem != treeItem) {
                        return;
                    }
                    super.startEdit();

                    String currentName = treeItem.getValue();
                    editField = new TextField(currentName);
                    editField.setStyle("-fx-padding: 1 4; -fx-font-size: 13px; -fx-background-color: white; -fx-border-color: #07c160; -fx-border-radius: 3; -fx-background-radius: 3;");
                    editField.setPrefWidth(getWidth() - 40);

                    editField.setOnAction(e -> commitEdit(editField.getText()));

                    editField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                        if (!isNowFocused && editingItem == treeItem) {
                            commitEdit(editField.getText());
                        }
                    });

                    editField.setOnKeyReleased(e -> {
                        if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                            cancelEdit();
                        }
                    });

                    setText(null);
                    Node icon = treeItem.getGraphic();
                    HBox editBox = new HBox(icon, editField);
                    editBox.setAlignment(Pos.CENTER_LEFT);
                    editBox.setSpacing(4);
                    setGraphic(editBox);
                    editField.selectAll();
                    Platform.runLater(() -> editField.requestFocus());
                }

                @Override
                public void commitEdit(String newValue) {
                    TreeItem<String> treeItem = getTreeItem();
                    if (treeItem == null || editingItem != treeItem) return;

                    String oldName = treeItem.getValue();
                    String newName = newValue.trim();
                    recentlyEditedItem = treeItem;
                    editingItem = null;
                    editField = null;
                    treeView.edit(null);
                    treeView.setEditable(false);

                    setText(oldName);
                    setGraphic(treeItem.getGraphic());

                    if (newName.isEmpty() || newName.equals(oldName)) return;

                    DatabaseNodeData dbData = dbNodeDataMap.get(treeItem);
                    if (dbData != null) {
                        commitTableNameRename(treeItem, dbData, oldName, newName);
                    } else {
                        ConnectionConfig cfg = itemConfigMap.get(treeItem);
                        if (cfg != null) {
                            cfg.setName(newName);
                            ConfigManager.saveConnections(connections);
                            treeItem.setValue(newName);
                        }
                    }
                }

                @Override
                public void cancelEdit() {
                    TreeItem<String> treeItem = getTreeItem();
                    recentlyEditedItem = treeItem;
                    editingItem = null;
                    editField = null;
                    treeView.edit(null);
                    treeView.setEditable(false);

                    super.cancelEdit();
                    if (treeItem != null) {
                        setText(treeItem.getValue());
                        setGraphic(treeItem.getGraphic());
                    }
                }

                @Override
                protected void updateItem(String item, boolean empty) {
                    if (currentTreeItem != null && expandedListener != null) {
                        currentTreeItem.expandedProperty().removeListener(expandedListener);
                        expandedListener = null;
                    }
                    currentTreeItem = null;

                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        TreeItem<String> treeItem = getTreeItem();
                        if (editingItem == treeItem && editField != null) {
                            setText(null);
                            setGraphic(editField);
                        } else {
                            setText(item);
                            if (treeItem != null) {
                                setGraphic(treeItem.getGraphic());
                            }
                        }
                        if (treeItem != null) {
                            arrowPath.setRotate(treeItem.isExpanded() ? 90 : 0);
                            currentTreeItem = treeItem;
                            expandedListener = (obs, wasExpanded, isExpanded) ->
                                    arrowPath.setRotate(isExpanded ? 90 : 0);
                            treeItem.expandedProperty().addListener(expandedListener);
                        }
                    }
                }
            };

            cell.setOnDragDetected(event -> {
                if (cell.isEmpty()) {
                    event.consume();
                    return;
                }
                TreeItem<String> item = cell.getTreeItem();
                if (item == null || item == root) {
                    event.consume();
                    return;
                }
                Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                ConnectionConfig config = itemConfigMap.get(item);
                if (config == null) {
                    event.consume();
                    return;
                }
                content.putString(DRAG_PREFIX + config.getId());
                db.setContent(content);
                event.consume();
            });

            cell.setOnDragOver(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                    TreeItem<String> targetItem = cell.getTreeItem();
                    if (targetItem == null || targetItem == root) {
                        event.acceptTransferModes(TransferMode.MOVE);
                    } else {
                        ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                        if (targetConfig != null && targetConfig.getType() == null) {
                            event.acceptTransferModes(TransferMode.MOVE);
                        }
                    }
                }
                event.consume();
            });

            cell.setOnDragEntered(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                    TreeItem<String> targetItem = cell.getTreeItem();
                    if (targetItem == null || targetItem == root) {
                        cell.setStyle("-fx-background-color: #e0e0e0;");
                    } else {
                        ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                        if (targetConfig != null && targetConfig.getType() == null) {
                            cell.setStyle("-fx-background-color: #e0e0e0;");
                        }
                    }
                }
                event.consume();
            });

            cell.setOnDragExited(event -> {
                cell.setStyle("");
                event.consume();
            });

            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                    String dragId = db.getString().substring(DRAG_PREFIX.length());
                    TreeItem<String> targetItem = cell.getTreeItem();

                    TreeItem<String> newParent;
                    if (targetItem == null || targetItem == root) {
                        newParent = root;
                    } else {
                        ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                        if (targetConfig != null && targetConfig.getType() == null) {
                            newParent = targetItem;
                        } else {
                            newParent = root;
                        }
                    }

                    TreeItem<String> draggedItem = findItemById(root, dragId);
                    if (draggedItem != null && draggedItem != newParent && !isDescendant(draggedItem, newParent)) {
                        moveItem(draggedItem, newParent);
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });

            return cell;
        });

        treeView.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        treeView.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                String dragId = db.getString().substring(DRAG_PREFIX.length());
                TreeItem<String> draggedItem = findItemById(root, dragId);
                if (draggedItem != null && draggedItem.getParent() != root) {
                    moveItem(draggedItem, root);
                    success = true;
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private boolean isDescendant(TreeItem<String> ancestor, TreeItem<String> possibleDescendant) {
        if (ancestor == possibleDescendant) return true;
        for (TreeItem<String> child : ancestor.getChildren()) {
            if (isDescendant(child, possibleDescendant)) {
                return true;
            }
        }
        return false;
    }

    private void moveItem(TreeItem<String> item, TreeItem<String> newParent) {
        ConnectionConfig config = itemConfigMap.get(item);
        if (config == null) return;

        item.getParent().getChildren().remove(item);

        if (newParent == root) {
            config.setParentId(null);
        } else {
            ConnectionConfig parentConfig = itemConfigMap.get(newParent);
            if (parentConfig != null) {
                config.setParentId(parentConfig.getId());
            }
        }

        newParent.getChildren().add(item);
        newParent.setExpanded(true);

        ConfigManager.saveConnections(connections);
    }

    private void handleRocketmqFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();

        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }

        ConnectHandler rqHandler = createConnectHandler(config);
        if (rqHandler instanceof RocketmqConnectHandler rq) {
            rq.loadTopicsForFolder(this, folderItem, config);
        }
        folderItem.setExpanded(true);
    }

    private void handleAliyunProductFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        ConnectHandler alHandler = createConnectHandler(data.getConnectionConfig());
        if (alHandler instanceof AliyunConnectHandler al) {
            al.loadAliyunProductChildren(this, folderItem, data);
        }
        folderItem.setExpanded(true);
    }

    private void handleRocketmqTopicDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String topicName = data.getName();
        String tabId = "rocketmq_topic_" + config.getId() + "_" + topicName;

        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }

        RocketmqDataView dataView = new RocketmqDataView(config, topicName);
        dataView.selectTopicTab(topicName);

        String tabTitle = topicName + "(" + config.getHost() + ":" + config.getPort() + ")";
        Tab tab = new Tab(tabTitle);

        try {
            Image rocketmqIcon = new Image(getClass().getResourceAsStream("/images/connect/rocketmq.png"));
            ImageView tabIconView = new ImageView(rocketmqIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(tabIconView);
        } catch (Exception ignored) {}

        tab.setContent(dataView);
        tab.setUserData(tabId);
        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();
    }

    private void handleRocketmqConsumerDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        // 双击消费者组项也打开消费者组一级标签
        TreeItem<String> parent = item.getParent();
        if (parent != null) {
            DatabaseNodeData parentData = dbNodeDataMap.get(parent);
            if (parentData != null) {
                ConnectHandler handler = createConnectHandler(parentData.getConnectionConfig());
                if (handler instanceof RocketmqConnectHandler rqHandler) {
                    rqHandler.handleConsumersFolderDoubleClick(this, parent, parentData);
                }
                return;
            }
        }
        // 如果无法获取父节点，直接打开消费者组标签
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String tabId = "rocketmq_consumers_" + config.getId();
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }
    }

    private void handleRocketmqClusterFolderDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String tabId = "rocketmq_cluster_" + config.getId();

        // 如果已有该集群标签，直接选中
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }

        // 创建集群一级标签
        VBox clusterContent = new VBox(0);
        clusterContent.setPadding(new Insets(8));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");

        toolbar.getChildren().add(refreshBtn);

        TableView<ObservableList<String>> clusterTable = new TableView<>();
        clusterTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        clusterTable.setPlaceholder(new Label("无数据"));

        TableColumn<ObservableList<String>, String> brokerNameCol = new TableColumn<>("BrokerName");
        brokerNameCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(0)));
        brokerNameCol.setPrefWidth(200);

        TableColumn<ObservableList<String>, String> brokerIdCol = new TableColumn<>("BrokerId");
        brokerIdCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(1)));
        brokerIdCol.setPrefWidth(100);

        TableColumn<ObservableList<String>, String> addressCol = new TableColumn<>("地址");
        addressCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(2)));
        addressCol.setPrefWidth(250);

        TableColumn<ObservableList<String>, String> roleCol = new TableColumn<>("角色");
        roleCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(3)));
        roleCol.setPrefWidth(100);

        TableColumn<ObservableList<String>, String> versionCol = new TableColumn<>("版本");
        versionCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().size() > 4 ? param.getValue().get(4) : ""));
        versionCol.setPrefWidth(150);

        clusterTable.getColumns().addAll(brokerNameCol, brokerIdCol, addressCol, roleCol, versionCol);

        javafx.collections.ObservableList<ObservableList<String>> clusterData = javafx.collections.FXCollections.observableArrayList();

        Runnable loadCluster = () -> {
            new Thread(() -> {
                try {
                    List<Map<String, Object>> cluster = RocketmqService.getClusterInfo(config);
                    Platform.runLater(() -> {
                        clusterData.clear();
                        for (Map<String, Object> c : cluster) {
                            ObservableList<String> row = javafx.collections.FXCollections.observableArrayList();
                            row.add(String.valueOf(c.getOrDefault("brokerName", "")));
                            row.add(String.valueOf(c.getOrDefault("brokerId", "")));
                            row.add(String.valueOf(c.getOrDefault("address", "")));
                            row.add(String.valueOf(c.getOrDefault("role", "")));
                            row.add(String.valueOf(c.getOrDefault("version", "")));
                            clusterData.add(row);
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("加载失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法加载集群信息: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "RocketMQ-LoadClusterTab").start();
        };

        refreshBtn.setOnAction(e -> loadCluster.run());
        clusterTable.setItems(clusterData);

        clusterContent.getChildren().addAll(toolbar, clusterTable);
        VBox.setVgrow(clusterTable, Priority.ALWAYS);

        String tabTitle = "集群(" + config.getHost() + ":" + config.getPort() + ")";
        Tab tab = new Tab(tabTitle);

        try {
            Image rocketmqIcon = new Image(getClass().getResourceAsStream("/images/connect/rocketmq.png"));
            ImageView tabIconView = new ImageView(rocketmqIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(tabIconView);
        } catch (Exception ignored) {}

        tab.setContent(clusterContent);
        tab.setUserData(tabId);
        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();

        // 加载集群数据
        loadCluster.run();

        // 同时加载集群子节点到树中
        ConnectHandler rqHandler = createConnectHandler(config);
        if (rqHandler instanceof RocketmqConnectHandler rq) {
            rq.loadClusterForFolder(this, item, config);
        }
        item.setExpanded(true);
    }

    private void handleRocketmqBrokerDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        // 双击Broker节点也打开集群一级标签
        TreeItem<String> parent = item.getParent();
        if (parent != null) {
            DatabaseNodeData parentData = dbNodeDataMap.get(parent);
            if (parentData != null) {
                handleRocketmqClusterFolderDoubleClick(parent, parentData);
                return;
            }
        }
        // 如果无法获取父节点，直接打开集群标签
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String tabId = "rocketmq_cluster_" + config.getId();
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }
    }

    private void handleDeleteRocketmqTopic(TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String topicName = data.getName();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除主题: " + topicName);
        confirm.setContentText("删除后不可恢复，确定要删除吗？");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        RocketmqService.deleteTopic(config, topicName);
                        Platform.runLater(() -> {
                            Alert info = new Alert(Alert.AlertType.INFORMATION);
                            info.setTitle("成功");
                            info.setHeaderText(null);
                            info.setContentText("主题 " + topicName + " 已删除");
                            info.showAndWait();
                            TreeItem<String> parent = item.getParent();
                            if (parent != null) {
                                parent.getChildren().remove(item);
                                dbNodeDataMap.remove(item);
                            }
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("删除失败");
                            alert.setHeaderText(null);
                            alert.setContentText("删除主题失败: " + e.getMessage());
                            alert.showAndWait();
                        });
                    }
                }, "RocketMQ-DeleteTopic").start();
            }
        });
    }

    private void handleDbNodeDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case DATABASE -> handleDatabaseDoubleClick(item, data);
            case REDIS_DB -> {
                ConnectHandler h = createConnectHandler(data.getConnectionConfig());
                if (h instanceof RedisConnectHandler r) {
                    r.handleRedisDbDoubleClick(this, item, data);
                }
            }
            case SCHEMA -> handleSchemaDoubleClick(item, data);
            case TABLES_FOLDER -> {
                AbstractDbHandler dbh = createDbHandler(data.getConnectionConfig());
                if (dbh != null) dbh.handleTablesFolderDoubleClick(item, data);
            }
            case VIEWS_FOLDER -> {
                AbstractDbHandler dbh = createDbHandler(data.getConnectionConfig());
                if (dbh != null) dbh.handleViewsFolderDoubleClick(item, data);
            }
            case TABLE, VIEW -> {
                AbstractDbHandler h = createDbHandler(data.getConnectionConfig());
                if (h != null) h.handleTableDataDoubleClick(item, data);
            }
            case QUERY -> handleQueryDoubleClick(item, data);
            case BACKUP -> handleRestoreBackup(item, data);
            case QUERY_FOLDER -> item.setExpanded(!item.isExpanded());
            case BACKUP_FOLDER -> {
                AbstractDbHandler handler = createDbHandler(data.getConnectionConfig());
                if (handler != null) {
                    handler.loadBackupsForFolder(item, data.getConnectionConfig(), data.getDatabaseName());
                }
                item.setExpanded(!item.isExpanded());
            }
            case ROCKETMQ_TOPICS_FOLDER -> handleRocketmqFolderDoubleClick(item, data);
            case ROCKETMQ_CONSUMERS_FOLDER -> {
                ConnectHandler handler = createConnectHandler(data.getConnectionConfig());
                if (handler instanceof RocketmqConnectHandler rqHandler) {
                    rqHandler.handleConsumersFolderDoubleClick(this, item, data);
                }
            }
            case ROCKETMQ_CLUSTER_FOLDER -> handleRocketmqClusterFolderDoubleClick(item, data);
            case ROCKETMQ_TOPIC -> handleRocketmqTopicDoubleClick(item, data);
            case ROCKETMQ_CONSUMER -> handleRocketmqConsumerDoubleClick(item, data);
            case ROCKETMQ_BROKER -> handleRocketmqBrokerDoubleClick(item, data);
            case ALIYUN_PRODUCT_FOLDER -> handleAliyunProductFolderDoubleClick(item, data);
            case ALIYUN_ECS_INSTANCE -> { /* TODO: show ECS instance detail */ }
            case ALIYUN_DOMAIN -> {
                ConnectHandler alHandler = createConnectHandler(data.getConnectionConfig());
                if (alHandler instanceof AliyunConnectHandler al) {
                    al.handleAliyunDomainDoubleClick(this, item, data);
                }
            }
        }
    }

    /**
     * 双击模式节点：委托给对应类型的数据库处理器
     */
    private void handleSchemaDoubleClick(TreeItem<String> schemaItem, DatabaseNodeData data) {
        AbstractDbHandler handler = createDbHandler(data.getConnectionConfig());
        if (handler != null) {
            handler.handleSchemaDoubleClick(schemaItem, data);
        }
    }

    private void handleDatabaseDoubleClick(TreeItem<String> dbItem, DatabaseNodeData data) {
        if (data.isOpened()) {
            dbItem.setExpanded(!dbItem.isExpanded());
            return;
        }
        openDatabase(dbItem, data);
    }

    /**
     * 打开数据库节点：委托给对应类型的数据库处理器
     */
    private void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        AbstractDbHandler handler = createDbHandler(data.getConnectionConfig());
        if (handler != null) {
            handler.openDatabase(dbItem, data);
        }
    }

    private void closeDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        removeDbNodeDataRecursive(dbItem);
        dbItem.getChildren().clear();
        data.setOpened(false);
        dbItem.setGraphic(getDbNodeIcon(data));
        dbItem.setExpanded(false);
    }

    private void handleNewQuery(TreeItem<String> folderItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        SqlEditorView editorView = new SqlEditorView(connections, config, dbName);

        Tab tab = new Tab("*未保存查询");
        Image tabIcon = queryIcon;
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
                queryItem.setGraphic(getDbNodeIcon(queryData));
                dbNodeDataMap.put(queryItem, queryData);
                folderItem.getChildren().add(queryItem);
                folderItem.setExpanded(true);

                editorView.setQueryNode(queryItem);

                String newTabId = "query_" + config.getId() + "_" + dbName + "_" + queryName;
                tab.setUserData(newTabId);
            });
        });

        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        editorView.markModified();

        if (!ensureTabPaneInstalled()) return;
        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();
    }

    private void handleQueryDoubleClick(TreeItem<String> queryItem, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        String tabId = "query_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName() + "_" + data.getName();
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }

        SqlEditorView editorView = new SqlEditorView(connections, data.getConnectionConfig(), data.getDatabaseName());
        editorView.setQueryName(data.getName());
        editorView.setQueryNode(queryItem);
        editorView.loadFromFile(data.getConnectionConfig().getName(), data.getDatabaseName(), data.getName());

        Tab tab = new Tab(data.getName());
        Image tabIcon = queryIcon;
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
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();
    }

    private void handleRenameQuery(TreeItem<String> queryItem, DatabaseNodeData data) {
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
            dbNodeDataMap.remove(queryItem);
            dbNodeDataMap.put(queryItem, newData);
        });
    }

    private void commitTableNameRename(TreeItem<String> item, DatabaseNodeData dbData, String oldName, String newName) {
        ConnectionConfig config = dbData.getConnectionConfig();
        String dbName = dbData.getDatabaseName();
        new Thread(() -> {
            try {
                if (dbData.getType() == DatabaseNodeData.NodeType.TABLE) {
                    DatabaseService.renameTable(config, dbName, oldName, newName);
                } else {
                    DatabaseService.renameView(config, dbName, oldName, newName);
                }
                Platform.runLater(() -> {
                    item.setValue(newName);
                    dbNodeDataMap.put(item, new DatabaseNodeData(dbData.getType(), newName, config, dbName));
                    item.getChildren().clear();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    item.setValue(oldName);
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("重命名失败");
                    alert.setHeaderText(null);
                    alert.setContentText("重命名失败: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "DB-RenameTable").start();
    }

    private String sanitizeForFs(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
    }

    private void handleDeleteQuery(TreeItem<String> queryItem, DatabaseNodeData data) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除查询");
        confirm.setHeaderText("确定要删除查询 \"" + data.getName() + "\" 吗？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                SqlEditorView.cleanupQueryFile(data.getConnectionConfig().getName(), data.getDatabaseName(), data.getName());
                dbNodeDataMap.remove(queryItem);
                queryItem.getParent().getChildren().remove(queryItem);
            }
        });
    }

    private void handleNewBackup(TreeItem<String> folderItem, DatabaseNodeData data) {
        BackupDialog dialog = new BackupDialog(getStage(),
                data.getConnectionConfig(), data.getDatabaseName());
        dialog.showAndWait();

        AbstractDbHandler handler = createDbHandler(data.getConnectionConfig());
        if (handler != null) {
            handler.loadBackupsForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName());
        }
    }

    private void handleDeleteBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除备份");
        confirm.setHeaderText("确定要删除备份 \"" + data.getName() + "\" 吗？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                BackupService.deleteBackupFile(data.getConnectionConfig().getName(),
                        data.getDatabaseName(), data.getName());
                dbNodeDataMap.remove(backupItem);
                backupItem.getParent().getChildren().remove(backupItem);
            }
        });
    }

    private void handleRenameBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
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
                dbNodeDataMap.remove(backupItem);
                dbNodeDataMap.put(backupItem, newData);
            } catch (Exception e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("重命名失败");
                err.setHeaderText(null);
                err.setContentText(e.getMessage());
                err.showAndWait();
            }
        });
    }

    private void handleRestoreBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
        Stage stage = getStage();
        if (stage == null) return;

        RestoreDialog dialog = new RestoreDialog(stage,
                data.getConnectionConfig(), data.getDatabaseName(), data.getName());
        dialog.showAndWait();
    }

    private void handleOpenBackupDir(DatabaseNodeData data) {
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

    /** 供 handler 调用：更新主机节点图标（根据连接状态分发到具体 handler） */
    public void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        connectionStateMap.put(hostItem, connected);
        AbstractDbHandler handler = createDbHandler(config);
        if (handler != null) {
            handler.updateHostIcon(hostItem, config, connected);
        } else {
            updateHostIconGeneric(hostItem, config, connected);
        }
    }

    /** 供非数据库类型(SSH/RDP 等)及 PG/Oracle handler 调用：通用主机图标更新 */
    public void updateHostIconGeneric(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(16);
        imageView.setFitHeight(16);
        try {
            String iconPath = config.getType().getIconPath();
            Image icon = new Image(getClass().getResourceAsStream(iconPath));
            if (icon != null) {
                imageView.setImage(icon);
                if (connected) {
                    imageView.setStyle("-fx-effect: dropshadow(gaussian, #4CAF50, 2, 0.5, 0, 0);");
                }
            }
        } catch (Exception e) {
            // fallback
        }
        hostItem.setGraphic(imageView);
    }

    /** 供 MySQL handler 调用：MySQL 专用主机图标更新 */
    public void updateMysqlHostIcon(TreeItem<String> hostItem, ConnectionConfig config) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(16);
        imageView.setFitHeight(16);
        try {
            String iconPath = hostItem.isExpanded() ? "/images/connect/mysql_open.png" : "/images/connect/mysql.png";
            Image icon = new Image(getClass().getResourceAsStream(iconPath));
            if (icon != null) {
                imageView.setImage(icon);
                Boolean connected = connectionStateMap.get(hostItem);
                if (connected != null && connected) {
                    imageView.setStyle("-fx-effect: dropshadow(gaussian, #4CAF50, 2, 0.5, 0, 0);");
                }
            }
        } catch (Exception e) {
            // fallback
        }
        hostItem.setGraphic(imageView);
    }

    /**
     * 关闭主机连接：释放底层连接资源（JDBC/Jedis/MQAdmin），清空树子节点并重置图标。
     * 适用于已展开（已连接）的数据库/Redis/RocketMQ 主机节点。
     */
    private void closeHostConnection(TreeItem<String> hostItem, ConnectionConfig config) {
        // 后台关闭底层连接（避免阻塞UI线程）
        new Thread(() -> {
            try {
                AbstractDbHandler handler = createDbHandler(config);
                if (handler != null) {
                    handler.closeConnection(config);
                } else if (config.getType() == ConnectType.REDIS) {
                    RedisService.closeJedisCluster(config);
                } else if (config.getType() == ConnectType.ROCKETMQ) {
                    RocketmqService.closeAdmin(config);
                }
            } catch (Exception ignored) {
            }
        }, "CloseHostConnection").start();

        // 清空子节点及其 dbNodeDataMap 映射
        for (TreeItem<String> child : hostItem.getChildren()) {
            removeDbNodeDataRecursive(child);
        }
        hostItem.getChildren().clear();
        hostItem.setExpanded(false);

        // 重置图标为未连接状态
        connectionStateMap.put(hostItem, false);
        updateHostIcon(hostItem, config, false);
        treeView.refresh();
    }

    /** 供 handler 调用：递归移除节点映射 */
    public void removeDbNodeDataRecursive(TreeItem<String> item) {
        dbNodeDataMap.remove(item);
        for (TreeItem<String> child : item.getChildren()) {
            removeDbNodeDataRecursive(child);
        }
    }

    /** 供 handler 调用：注册节点数据映射 */
    public void putDbNodeData(TreeItem<String> item, DatabaseNodeData data) {
        dbNodeDataMap.put(item, data);
    }

    /** 供 handler 调用：根据 id 查找连接配置 */
    public ConnectionConfig findConnectionById(String id) {
        return connections.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    private void handleConnect(ConnectionConfig config) {
        if (contentArea == null || terminalTabPane == null) return;

        if (!ensureTabPaneInstalled()) return;

        ConnectHandler handler = createConnectHandler(config);
        if (handler != null) {
            handler.handleConnect(this, config);
        }
    }

    /**
     * 根据连接类型创建对应的连接处理器
     */
    private ConnectHandler createConnectHandler(ConnectionConfig config) {
        ConnectType type = config.getType();
        // 数据库类型（MySQL/PostgreSQL/Oracle）复用 AbstractDbHandler
        AbstractDbHandler dbHandler = createDbHandler(config);
        if (dbHandler != null) {
            return dbHandler;
        }
        // 其他类型
        if (type == ConnectType.REDIS) return new RedisConnectHandler();
        if (type == ConnectType.ROCKETMQ) return new RocketmqConnectHandler();
        if (type == ConnectType.ALIYUN) return new AliyunConnectHandler();
        if (type == ConnectType.LOCAL_TERMINAL) return new LocalTerminalConnectHandler();
        if (type == ConnectType.S3 || type == ConnectType.ALIYUN_OSS) return new S3ConnectHandler();
        if (type == ConnectType.RDP) return new RdpConnectHandler();
        if (type == ConnectType.SSH) return new SshTerminalConnectHandler();
        return null;
    }

    /** 供 handler 调用：获取终端 Tab 面板 */
    public TabPane getTerminalTabPane() {
        return terminalTabPane;
    }

    /** 供 RdpConnectHandler 调用 */
    public void showTerminalView() {
        // 已直接使用terminalTabPane，无需隐藏/显示其他元素
        if (terminalTabPane != null) {
            terminalTabPane.setVisible(true);
            terminalTabPane.setManaged(true);
        }
    }

    /** 供 handler 调用：显示欢迎视图 */
    public void showWelcomeView() {
        // 无标签时保持TabPane可见，但可以清空标签或显示提示
        if (terminalTabPane != null) {
            terminalTabPane.setVisible(true);
            terminalTabPane.setManaged(true);
        }
    }

    /** 供 handler 调用：保存连接配置 */
    public void saveConnections() {
        ConfigManager.saveConnections(connections);
    }

    /** 供 handler 调用：触发连接（用于"复制会话"菜单） */
    public void triggerConnect(ConnectionConfig config) {
        handleConnect(config);
    }

    /** 双击主机节点：通过对应 handler 加载主机资源列表 */
    public void triggerHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        ConnectHandler handler = createConnectHandler(config);
        if (handler != null) {
            handler.handleHostDoubleClick(this, hostItem, config);
        }
    }

    /** 刷新主机节点 dispatcher：根据连接类型分发到对应处理器 */
    void refreshDbHost(TreeItem<String> hostItem, ConnectionConfig config) {
        ConnectType type = config.getType();
        boolean isDatabase = type == ConnectType.MYSQL
                || type == ConnectType.POSTGRESQL
                || type == ConnectType.ORACLE;
        if (isDatabase) {
            AbstractDbHandler handler = createDbHandler(config);
            if (handler != null) {
                handler.refreshDbHost(hostItem, config);
            }
        } else {
            // Redis/RocketMQ/Aliyun 等：清空子节点后重新触发双击连接
            for (TreeItem<String> child : hostItem.getChildren()) {
                removeDbNodeDataRecursive(child);
            }
            hostItem.getChildren().clear();
            triggerHostDoubleClick(hostItem, config);
        }
    }

    /** 刷新节点 dispatcher：根据节点类型分发到对应处理器 */
    void refreshDbNode(TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        switch (data.getType()) {
            case DATABASE, SCHEMA, TABLES_FOLDER, VIEWS_FOLDER, QUERY_FOLDER, BACKUP_FOLDER -> {
                AbstractDbHandler handler = createDbHandler(config);
                if (handler != null) {
                    handler.refreshDbNode(item, data);
                }
            }
            case ROCKETMQ_TOPICS_FOLDER, ROCKETMQ_CONSUMERS_FOLDER, ROCKETMQ_CLUSTER_FOLDER -> {
                item.getChildren().clear();
                ConnectHandler handler = createConnectHandler(config);
                if (handler instanceof RocketmqConnectHandler rq) {
                    rq.refreshDbNode(this, item, data);
                }
            }
            case ALIYUN_PRODUCT_FOLDER, ALIYUN_DOMAIN -> {
                ConnectHandler handler = createConnectHandler(config);
                if (handler instanceof AliyunConnectHandler al) {
                    al.refreshDbNode(this, item, data);
                }
            }
            default -> {}
        }
    }

    /** 删除节点 dispatcher：根据选中项的连接配置分发到对应数据库处理器 */
    void deleteDbNodes() {
        ObservableList<TreeItem<String>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        ConnectionConfig cfg = null;
        for (TreeItem<String> item : selectedItems) {
            DatabaseNodeData data = dbNodeDataMap.get(item);
            if (data != null) {
                cfg = data.getConnectionConfig();
                break;
            }
        }
        if (cfg == null) return;
        AbstractDbHandler handler = createDbHandler(cfg);
        if (handler != null) {
            handler.handleDeleteDbNodes();
        }
    }

    /** 供 handler 调用：显示数据视图（实际为终端视图） */
    public void showDataView() {
        showTerminalView();
    }

    private void handleAddFolder(TreeItem<String> parent) {
        Stage stage = getStage();
        if (stage == null) return;

        FolderDialog dialog = new FolderDialog(stage);
        String folderName = dialog.showAndWait();
        if (folderName != null) {
            ConnectionConfig folderConfig = new ConnectionConfig();
            folderConfig.setId(ConfigManager.generateId());
            folderConfig.setName(folderName);
            ConnectionConfig parentConfig = itemConfigMap.get(parent);
            if (parent != root && parentConfig != null) {
                folderConfig.setParentId(parentConfig.getId());
            }
            folderConfig.setType(null);

            connections.add(folderConfig);
            ConfigManager.saveConnections(connections);

            TreeItem<String> folderItem = new TreeItem<>(folderName);
            if (folderIcon != null) {
                ImageView icon = new ImageView(folderIcon);
                icon.setFitWidth(16);
                icon.setFitHeight(16);
                folderItem.setGraphic(icon);
            }
            itemConfigMap.put(folderItem, folderConfig);
            parent.getChildren().add(folderItem);
        }
    }

    private void handleAddConnection(TreeItem<String> parent) {
        Stage stage = getStage();
        if (stage == null) return;

        ConnectionConfigDialog configDialog = new ConnectionConfigDialog(stage);
        ConnectionConfig config = configDialog.showAndWait();
        if (config != null) {
            config.setId(ConfigManager.generateId());
            ConnectionConfig parentConfig = itemConfigMap.get(parent);
            if (parent != root && parentConfig != null) {
                config.setParentId(parentConfig.getId());
            }

            connections.add(config);
            ConfigManager.saveConnections(connections);

            TreeItem<String> connectionItem = createTreeItem(config);
            parent.getChildren().add(connectionItem);
        }
    }

    private void handleEdit(TreeItem<String> item) {
        ConnectionConfig existingConfig = itemConfigMap.get(item);
        if (existingConfig == null || existingConfig.getType() == null) return;

        Stage stage = getStage();
        if (stage == null) return;

        ConnectionConfigDialog dialog = new ConnectionConfigDialog(stage, existingConfig);
        ConnectionConfig updatedConfig = dialog.showAndWait();
        if (updatedConfig != null) {
            connections.removeIf(c -> c.getId().equals(existingConfig.getId()));
            connections.add(updatedConfig);
            ConfigManager.saveConnections(connections);

            itemConfigMap.remove(item);
            itemConfigMap.put(item, updatedConfig);

            item.setValue(updatedConfig.getName());
        }
    }

    private void handleCopyConnection(TreeItem<String> item, ConnectionConfig sourceConfig) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        ConnectionConfig copiedConfig = gson.fromJson(gson.toJson(sourceConfig), ConnectionConfig.class);
        copiedConfig.setId(ConfigManager.generateId());
        copiedConfig.setName(sourceConfig.getName() + " - 副本");

        ConnectionConfig parentConfig = itemConfigMap.get(item);
        if (parentConfig != null) {
            copiedConfig.setParentId(parentConfig.getParentId());
        }

        connections.add(copiedConfig);
        ConfigManager.saveConnections(connections);

        TreeItem<String> copiedItem = createTreeItem(copiedConfig);
        TreeItem<String> parent = item.getParent();
        if (parent != null) {
            int index = parent.getChildren().indexOf(item);
            parent.getChildren().add(index + 1, copiedItem);
        }
    }

    private void handleDelete(TreeItem<String> item) {
        ConnectionConfig config = itemConfigMap.get(item);
        if (config == null) return;

        boolean isFolder = config.getType() == null;
        boolean hasChildren = !item.getChildren().isEmpty();

        if (isFolder && hasChildren) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("删除目录");
            alert.setHeaderText("确定要删除目录 \"" + config.getName() + "\" 吗？");
            alert.setContentText("该目录下包含子节点，请选择操作：");

            ButtonType keepChildrenBtn = new ButtonType("保留子节点");
            ButtonType deleteAllBtn = new ButtonType("连同子节点一起删除");
            ButtonType cancelBtn = ButtonType.CANCEL;

            alert.getButtonTypes().setAll(keepChildrenBtn, deleteAllBtn, cancelBtn);

            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent()) {
                if (result.get() == keepChildrenBtn) {
                    String parentId = config.getParentId();
                    for (TreeItem<String> child : item.getChildren()) {
                        ConnectionConfig childConfig = itemConfigMap.get(child);
                        if (childConfig != null) {
                            childConfig.setParentId(parentId);
                        }
                    }
                    connections.removeIf(c -> c.getId().equals(config.getId()));
                    ConfigManager.saveConnections(connections);
                    loadTree();
                } else if (result.get() == deleteAllBtn) {
                    removeConfigAndChildren(config.getId());
                    ConfigManager.saveConnections(connections);
                    loadTree();
                }
            }
        } else {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("删除确认");
            alert.setHeaderText("确定要删除 \"" + config.getName() + "\" 吗？");

            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                removeConfigAndChildren(config.getId());
                ConfigManager.saveConnections(connections);
                loadTree();
            }
        }
    }

    private void removeConfigAndChildren(String parentId) {
        connections.removeIf(config -> {
            if (config.getId().equals(parentId)) {
                return true;
            }
            if (parentId.equals(config.getParentId())) {
                removeConfigAndChildren(config.getId());
                return true;
            }
            return false;
        });
    }

    /** 供 handler 调用：获取所属 Stage */
    public Stage getStage() {
        Node node = treeView;
        while (node != null && !(node.getScene() != null && node.getScene().getWindow() instanceof Stage)) {
            node = node.getParent();
        }
        if (node != null && node.getScene() != null && node.getScene().getWindow() instanceof Stage) {
            return (Stage) node.getScene().getWindow();
        }
        return null;
    }

    /** 供 handler 调用：确认 TabPane 已安装 */
    public boolean ensureTabPaneInstalled() {
        // 现在 terminalTabPane 始终在 contentArea 中，直接返回 true
        return terminalTabPane != null;
    }

    @Override
    public void loadContent(VBox contentArea) {
        this.contentArea = contentArea;
        contentArea.getChildren().clear();

        // 直接创建并添加 TabPane
        terminalTabPane = new TabPane();
        terminalTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        terminalTabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        terminalTabPane.setFocusTraversable(false);
        VBox.setVgrow(terminalTabPane, Priority.ALWAYS);

        contentArea.getChildren().add(terminalTabPane);
        terminalTabPane.setVisible(true);
        terminalTabPane.setManaged(true);

        // 切换标签时自动将输入焦点转移到终端视图，无需再点击终端区域
        terminalTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null) return;
            if (newTab.getContent() instanceof SSHTerminalPane pane) {
                pane.requestTerminalFocus();
            } else if (newTab.getContent() instanceof LocalTerminalPane pane) {
                pane.requestTerminalFocus();
            }
        });
    }
}