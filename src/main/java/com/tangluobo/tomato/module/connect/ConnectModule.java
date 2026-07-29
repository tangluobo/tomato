package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.rdp.RdpPane;
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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ConnectModule implements Module {
    private TreeView<String> treeView;
    private TreeItem<String> root;
    private List<ConnectionConfig> connections;
    private Map<TreeItem<String>, ConnectionConfig> itemConfigMap;
    private Map<TreeItem<String>, DatabaseNodeData> dbNodeDataMap;
    private Map<TreeItem<String>, Boolean> connectionStateMap;
    private TreeItem<String> editingItem;
    private TreeItem<String> selectedItemBeforeClick;
    private TreeItem<String> recentlyEditedItem;
    private Timeline singleClickTimer;
    private Image folderIcon;
    private Image dbIcon;
    private Image dbIconGray;
    private Image tableIcon;
    private Image viewIcon;
    private Image functionIcon;
    private Image backupIcon;
    private Image queryIcon;
    private Image rocketmqTopicIcon;
    private Image rocketmqConsumerIcon;
    private Image rocketmqClusterIcon;
    private Image rocketmqMessageIcon;
    private TextField searchField;

    // 内容区域
    private VBox contentArea;
    private TabPane terminalTabPane;

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
        try { tableIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { tableIcon = null; }
        try { viewIcon = new Image(getClass().getResourceAsStream("/images/connect/view.png")); } catch (Exception e) { viewIcon = null; }
        try { queryIcon = new Image(getClass().getResourceAsStream("/images/connect/query.png")); } catch (Exception e) { queryIcon = null; }
        try { functionIcon = new Image(getClass().getResourceAsStream("/images/connect/function.png")); } catch (Exception e) { functionIcon = null; }
        try { backupIcon = new Image(getClass().getResourceAsStream("/images/connect/backup.png")); } catch (Exception e) { backupIcon = null; }
        try { rocketmqTopicIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { rocketmqTopicIcon = null; }
        try { rocketmqConsumerIcon = new Image(getClass().getResourceAsStream("/images/connect/user.png")); } catch (Exception e) { rocketmqConsumerIcon = null; }
        try { rocketmqClusterIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { rocketmqClusterIcon = null; }
        try { rocketmqMessageIcon = new Image(getClass().getResourceAsStream("/images/connect/code.png")); } catch (Exception e) { rocketmqMessageIcon = null; }
    }

    private ImageView getDbNodeIcon(DatabaseNodeData data) {
        ImageView iv = new ImageView();
        iv.setFitWidth(20);
        iv.setFitHeight(20);
        Image icon = switch (data.getType()) {
            case DATABASE -> data.isOpened() ? dbIcon : dbIconGray;
            case REDIS_DB -> data.isOpened() ? dbIcon : dbIconGray;
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
        };
        if (icon != null) iv.setImage(icon);
        return iv;
    }

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

    private ImageView getIconForConfig(ConnectionConfig config) {
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

    private TreeItem<String> findItemById(TreeItem<String> root, String id) {
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
                            editDbItem.setOnAction(e -> handleEditDatabase(targetItem, dbData));
                            MenuItem deleteDbItem = new MenuItem("删除");
                            deleteDbItem.setOnAction(e -> handleDeleteDatabase(targetItem, dbData));
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> handleRefreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(new SeparatorMenuItem(), editDbItem, deleteDbItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case REDIS_DB -> {
                            MenuItem openItem = new MenuItem("打开");
                            openItem.setOnAction(e -> handleRedisDbDoubleClick(targetItem, dbData));
                            contextMenu.getItems().add(openItem);
                        }
                        case ROCKETMQ_TOPICS_FOLDER, ROCKETMQ_CONSUMERS_FOLDER, ROCKETMQ_CLUSTER_FOLDER, ROCKETMQ_MESSAGES_FOLDER -> {
                            MenuItem openItem = new MenuItem("打开");
                            openItem.setOnAction(e -> handleRocketmqFolderDoubleClick(targetItem, dbData));
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> handleRefreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(openItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case TABLES_FOLDER, VIEWS_FOLDER -> {
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> handleRefreshDbNode(targetItem, dbData));
                            contextMenu.getItems().add(refreshItem);
                        }
                        case QUERY_FOLDER -> {
                            MenuItem newQueryItem = new MenuItem("新建查询");
                            newQueryItem.setOnAction(e -> handleNewQuery(targetItem, dbData));
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> handleRefreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(newQueryItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case BACKUP_FOLDER -> {
                            MenuItem newBackupItem = new MenuItem("新建备份");
                            newBackupItem.setOnAction(e -> handleNewBackup(targetItem, dbData));
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> handleRefreshDbNode(targetItem, dbData));
                            contextMenu.getItems().addAll(newBackupItem, new SeparatorMenuItem(), refreshItem);
                        }
                        case TABLE, VIEW -> {
                            MenuItem designItem = new MenuItem("设计表");
                            designItem.setOnAction(e -> handleTableStructureDoubleClick(targetItem, dbData));
                            MenuItem openDataItem = new MenuItem("打开数据");
                            openDataItem.setOnAction(e -> handleTableDataDoubleClick(targetItem, dbData));
                            MenuItem deleteItem = new MenuItem("删除");
                            deleteItem.setOnAction(e -> handleDeleteDbNodes());
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
                        if (isDatabase) {
                            MenuItem createDbItem = new MenuItem("新建数据库");
                            createDbItem.setOnAction(e -> handleCreateDatabase(targetItem, targetConfig));
                            contextMenu.getItems().add(createDbItem);
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem refreshItem = new MenuItem("刷新");
                                refreshItem.setOnAction(e -> handleRefreshDbHost(targetItem, targetConfig));
                                contextMenu.getItems().add(refreshItem);
                            }
                        }
                        if (isRedis) {
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem refreshItem = new MenuItem("刷新");
                                refreshItem.setOnAction(e -> handleRefreshDbHost(targetItem, targetConfig));
                                contextMenu.getItems().add(refreshItem);
                            }
                        }
                        if (isRocketmq) {
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem refreshItem = new MenuItem("刷新");
                                refreshItem.setOnAction(e -> handleRefreshDbHost(targetItem, targetConfig));
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
                    boolean isDatabase = config.getType() == ConnectType.MYSQL
                            || config.getType() == ConnectType.POSTGRESQL
                            || config.getType() == ConnectType.ORACLE;
                    boolean isRedis = config.getType() == ConnectType.REDIS;
                    boolean isRocketmq = config.getType() == ConnectType.ROCKETMQ;
                    if (isDatabase) {
                        handleDbHostDoubleClick(selectedItem, config);
                    } else if (isRedis) {
                        handleRedisHostDoubleClick(selectedItem, config);
                    } else if (isRocketmq) {
                        handleRocketmqHostDoubleClick(selectedItem, config);
                    } else {
                        handleConnect(config);
                    }
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

    private void handleDbHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        if (config.getPassword() == null) {
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
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    updateHostIcon(hostItem, config, true);

                    hostItem.getChildren().clear();
                    for (String dbName : databases) {
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        dbItem.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName)));
                        dbNodeDataMap.put(dbItem, new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName));
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hostItem.setGraphic(getIconForConfig(config));
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

    private void handleRedisHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
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
                    updateHostIcon(hostItem, config, true);

                    hostItem.getChildren().clear();
                    for (String dbIndex : databases) {
                        String dbName = "db" + dbIndex;
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        dbItem.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.REDIS_DB, dbName, config, dbName)));
                        dbNodeDataMap.put(dbItem, new DatabaseNodeData(DatabaseNodeData.NodeType.REDIS_DB, dbName, config, dbName));
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hostItem.setGraphic(getIconForConfig(config));
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

    private void handleRocketmqHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);

        new Thread(() -> {
            try {
                boolean connected = RocketmqService.testConnection(config);
                if (!connected) {
                    Platform.runLater(() -> {
                        hostItem.setGraphic(getIconForConfig(config));
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法连接到RocketMQ NameServer: " + config.getHost() + ":" + config.getPort());
                        alert.showAndWait();
                    });
                    return;
                }
                Platform.runLater(() -> {
                    updateHostIcon(hostItem, config, true);
                    hostItem.getChildren().clear();

                    // 主题节点
                    TreeItem<String> topicsFolder = new TreeItem<>("主题");
                    topicsFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.ROCKETMQ_TOPICS_FOLDER, "主题", config, "")));
                    dbNodeDataMap.put(topicsFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.ROCKETMQ_TOPICS_FOLDER, "主题", config, ""));

                    // 消息节点
                    TreeItem<String> messagesFolder = new TreeItem<>("消息");
                    messagesFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.ROCKETMQ_MESSAGES_FOLDER, "消息", config, "")));
                    dbNodeDataMap.put(messagesFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.ROCKETMQ_MESSAGES_FOLDER, "消息", config, ""));

                    // 消费者组节点
                    TreeItem<String> consumersFolder = new TreeItem<>("消费者组");
                    consumersFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.ROCKETMQ_CONSUMERS_FOLDER, "消费者组", config, "")));
                    dbNodeDataMap.put(consumersFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.ROCKETMQ_CONSUMERS_FOLDER, "消费者组", config, ""));

                    // 集群节点
                    TreeItem<String> clusterFolder = new TreeItem<>("集群");
                    clusterFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.ROCKETMQ_CLUSTER_FOLDER, "集群", config, "")));
                    dbNodeDataMap.put(clusterFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.ROCKETMQ_CLUSTER_FOLDER, "集群", config, ""));

                    hostItem.getChildren().addAll(topicsFolder, messagesFolder, consumersFolder, clusterFolder);
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hostItem.setGraphic(getIconForConfig(config));
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到RocketMQ " + config.getName() + ": " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "RocketMQ-Connect").start();
    }

    private void handleRocketmqFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String folderName = data.getName();
        DatabaseNodeData.NodeType nodeType = data.getType();

        // 生成唯一tabId
        String tabId = "rocketmq_" + config.getId() + "_" + nodeType.name();
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }

        // 打开RocketMQ管理视图
        RocketmqDataView dataView = new RocketmqDataView(config);

        // 根据节点类型切换到对应的Tab
        switch (nodeType) {
            case ROCKETMQ_TOPICS_FOLDER -> dataView.getMainTabPane().getSelectionModel().select(0);
            case ROCKETMQ_MESSAGES_FOLDER -> dataView.getMainTabPane().getSelectionModel().select(1);
            case ROCKETMQ_CONSUMERS_FOLDER -> dataView.getMainTabPane().getSelectionModel().select(2);
            case ROCKETMQ_CLUSTER_FOLDER -> dataView.getMainTabPane().getSelectionModel().select(3);
            default -> {}
        }

        String tabTitle = folderName + "(" + config.getHost() + ":" + config.getPort() + ")-RocketMQ";
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

        ContextMenu rocketmqTabContextMenu = new ContextMenu();
        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> {
            // 切换到对应tab后触发刷新
            int selectedIndex = dataView.getMainTabPane().getSelectionModel().getSelectedIndex();
            if (selectedIndex == 0) {
                // Topic tab - 刷新在dataView内部处理
            }
        });
        rocketmqTabContextMenu.getItems().add(refreshItem);
        tab.setContextMenu(rocketmqTabContextMenu);

        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();
    }

    private void handleDbNodeDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case DATABASE -> handleDatabaseDoubleClick(item, data);
            case REDIS_DB -> handleRedisDbDoubleClick(item, data);
            case TABLES_FOLDER -> handleTablesFolderDoubleClick(item, data);
            case VIEWS_FOLDER -> handleViewsFolderDoubleClick(item, data);
            case TABLE, VIEW -> handleTableDataDoubleClick(item, data);
            case QUERY -> handleQueryDoubleClick(item, data);
            case BACKUP -> handleRestoreBackup(item, data);
            case QUERY_FOLDER -> item.setExpanded(!item.isExpanded());
            case BACKUP_FOLDER -> {
                loadBackupsForFolder(item, data.getConnectionConfig(), data.getDatabaseName());
                item.setExpanded(!item.isExpanded());
            }
            case ROCKETMQ_TOPICS_FOLDER, ROCKETMQ_CONSUMERS_FOLDER, ROCKETMQ_CLUSTER_FOLDER, ROCKETMQ_MESSAGES_FOLDER -> {
                handleRocketmqFolderDoubleClick(item, data);
            }
        }
    }

    private void handleDatabaseDoubleClick(TreeItem<String> dbItem, DatabaseNodeData data) {
        if (data.isOpened()) {
            dbItem.setExpanded(!dbItem.isExpanded());
            return;
        }
        openDatabase(dbItem, data);
    }

    private void handleRedisDbDoubleClick(TreeItem<String> dbItem, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        String dbName = data.getDatabaseName();
        int dbIndex = 0;
        if (dbName.startsWith("db")) {
            try {
                dbIndex = Integer.parseInt(dbName.substring(2));
            } catch (NumberFormatException ignored) {}
        }

        String tabId = "redis_" + data.getConnectionConfig().getId() + "_" + dbName;
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }

        RedisDataView dataView = new RedisDataView(data.getConnectionConfig(), dbIndex);

        ConnectionConfig config = data.getConnectionConfig();
        String tabTitle = dbName + "(" + config.getHost() + ":" + config.getPort() + ")-Redis";
        Tab tab = new Tab(tabTitle);

        try {
            Image redisIcon = new Image(getClass().getResourceAsStream("/images/connect/redis.png"));
            ImageView tabIconView = new ImageView(redisIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(tabIconView);
        } catch (Exception ignored) {}

        tab.setContent(dataView);
        tab.setUserData(tabId);

        ContextMenu redisTabContextMenu = new ContextMenu();
        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> dataView.loadKeyTree());
        redisTabContextMenu.getItems().add(refreshItem);
        tab.setContextMenu(redisTabContextMenu);

        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();
    }

    private void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        data.setOpened(true);
        dbItem.setGraphic(getDbNodeIcon(data));

        ConnectionConfig config = data.getConnectionConfig();

        TreeItem<String> tablesFolder = new TreeItem<>("表");
        tablesFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, data.getDatabaseName())));
        dbNodeDataMap.put(tablesFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, data.getDatabaseName()));

        TreeItem<String> viewsFolder = new TreeItem<>("视图");
        viewsFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, data.getDatabaseName())));
        dbNodeDataMap.put(viewsFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, data.getDatabaseName()));

        TreeItem<String> functionFolder = new TreeItem<>("函数");
        functionFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, data.getDatabaseName())));
        dbNodeDataMap.put(functionFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, data.getDatabaseName()));

        TreeItem<String> queryFolder = new TreeItem<>("查询");
        queryFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, data.getDatabaseName())));
        dbNodeDataMap.put(queryFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, data.getDatabaseName()));

        loadQueriesForFolder(queryFolder, config, data.getDatabaseName());

        TreeItem<String> backupFolder = new TreeItem<>("备份");
        backupFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, data.getDatabaseName())));
        dbNodeDataMap.put(backupFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, data.getDatabaseName()));

        loadBackupsForFolder(backupFolder, config, data.getDatabaseName());

        dbItem.getChildren().addAll(tablesFolder, viewsFolder, functionFolder, queryFolder, backupFolder);
        dbItem.setExpanded(true);

        loadTablesForFolder(tablesFolder, config, data.getDatabaseName(), false);
        loadViewsForFolder(viewsFolder, config, data.getDatabaseName(), false);
    }

    private void closeDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        removeDbNodeDataRecursive(dbItem);
        dbItem.getChildren().clear();
        data.setOpened(false);
        dbItem.setGraphic(getDbNodeIcon(data));
        dbItem.setExpanded(false);
    }

    private void handleTablesFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadTablesForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), true);
    }

    private void handleViewsFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadViewsForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), true);
    }

    private void loadQueriesForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName) {
        List<String> queryNames = SqlEditorView.listQueries(config.getName(), dbName);
        folderItem.getChildren().clear();
        for (String queryName : queryNames) {
            TreeItem<String> queryItem = new TreeItem<>(queryName);
            queryItem.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, queryName, config, dbName)));
            dbNodeDataMap.put(queryItem, new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, queryName, config, dbName));
            folderItem.getChildren().add(queryItem);
        }
    }

    private void loadTablesForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName, boolean autoExpand) {
        new Thread(() -> {
            try {
                List<String> tables = DatabaseService.getTables(config, dbName);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (String tableName : tables) {
                        TreeItem<String> tableItem = new TreeItem<>(tableName);
                        tableItem.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.TABLE, tableName, config, dbName)));
                        dbNodeDataMap.put(tableItem, new DatabaseNodeData(DatabaseNodeData.NodeType.TABLE, tableName, config, dbName));
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

    private void loadViewsForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName, boolean autoExpand) {
        new Thread(() -> {
            try {
                List<String> views = DatabaseService.getViews(config, dbName);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (String viewName : views) {
                        TreeItem<String> viewItem = new TreeItem<>(viewName);
                        viewItem.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.VIEW, viewName, config, dbName)));
                        dbNodeDataMap.put(viewItem, new DatabaseNodeData(DatabaseNodeData.NodeType.VIEW, viewName, config, dbName));
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

    private void loadColumnsForTable(TreeItem<String> tableItem, ConnectionConfig config, String dbName, String tableName) {
        new Thread(() -> {
            try {
                List<Map<String, String>> columns = DatabaseService.getTableColumns(config, dbName, tableName);
                Platform.runLater(() -> {
                    tableItem.getChildren().clear();
                    for (Map<String, String> col : columns) {
                        String colName = col.get("字段名");
                        String typeInfo = col.get("类型");
                        String displayText = colName + "  " + typeInfo;
                        TreeItem<String> colItem = new TreeItem<>(displayText);
                        boolean isPk = "是".equals(col.get("主键"));
                        ImageView iv = new ImageView();
                        iv.setFitWidth(16);
                        iv.setFitHeight(16);
                        javafx.scene.shape.Rectangle rect = new javafx.scene.shape.Rectangle(10, 10);
                        rect.setFill(isPk ? Color.valueOf("#1E88E5") : Color.valueOf("#999999"));
                        rect.setArcWidth(2);
                        rect.setArcHeight(2);
                        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
                        sp.setFill(Color.TRANSPARENT);
                        Image colIcon = rect.snapshot(sp, null);
                        iv.setImage(colIcon);
                        colItem.setGraphic(iv);
                        tableItem.getChildren().add(colItem);
                    }
                    tableItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载列信息: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "DB-LoadColumns").start();
    }

    private void handleTableStructureDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        String tabId = "struct_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName() + "_" + data.getName();
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }

        TableStructureView structView = new TableStructureView(data.getConnectionConfig(), data.getDatabaseName(), data.getName());

        ConnectionConfig config = data.getConnectionConfig();
        String typeLabel = data.getType() == DatabaseNodeData.NodeType.VIEW ? "视图" : "表";
        String tabTitle = data.getName() + "@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-" + typeLabel + "结构";
        Tab tab = new Tab(tabTitle);
        Image tabIcon = data.getType() == DatabaseNodeData.NodeType.VIEW ? viewIcon : tableIcon;
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
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.TABLE);
            GlobalConfig globalConfig = GlobalConfig.getInstance();
            structView.applyTableConfig(globalConfig);
        });
        MenuItem structRefreshItem = new MenuItem("刷新结构");
        structRefreshItem.setOnAction(e -> structView.loadStructure());
        structTabContextMenu.getItems().addAll(structConfigItem, structRefreshItem);
        tab.setContextMenu(structTabContextMenu);

        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();
    }

    private void handleTableDataDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        String tabId = data.getConnectionConfig().getId() + "_" + data.getDatabaseName() + "_" + data.getName();
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showDataView();
                return;
            }
        }

        TableDataView dataView = new TableDataView(data.getConnectionConfig(), data.getDatabaseName(), data.getName());

        ConnectionConfig config = data.getConnectionConfig();
        String typeLabel = data.getType() == DatabaseNodeData.NodeType.VIEW ? "视图" : "表";
        String tabTitle = data.getName() + "@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-" + typeLabel;
        Tab tab = new Tab(tabTitle);
        Image tabIcon = data.getType() == DatabaseNodeData.NodeType.VIEW ? viewIcon : tableIcon;
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
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.TABLE);
            GlobalConfig globalConfig = GlobalConfig.getInstance();
            dataView.applyTableConfig(globalConfig);
        });
        MenuItem tableRefreshItem = new MenuItem("刷新数据");
        tableRefreshItem.setOnAction(e -> dataView.refreshData());
        tableTabContextMenu.getItems().addAll(tableConfigItem, tableRefreshItem);
        tab.setContextMenu(tableTabContextMenu);

        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();
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

    private void loadBackupsForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName) {
        List<String> backupNames = BackupService.listBackups(config.getName(), dbName);
        folderItem.getChildren().clear();
        for (String backupName : backupNames) {
            TreeItem<String> backupItem = new TreeItem<>(backupName);
            backupItem.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP, backupName, config, dbName)));
            dbNodeDataMap.put(backupItem, new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP, backupName, config, dbName));
            folderItem.getChildren().add(backupItem);
        }
    }

    private void handleNewBackup(TreeItem<String> folderItem, DatabaseNodeData data) {
        BackupDialog dialog = new BackupDialog(getStage(),
                data.getConnectionConfig(), data.getDatabaseName());
        dialog.showAndWait();

        loadBackupsForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName());
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

    private void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        connectionStateMap.put(hostItem, connected);
        if (config.getType() == ConnectType.MYSQL) {
            updateMysqlHostIcon(hostItem, config);
        } else {
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
    }

    private void updateMysqlHostIcon(TreeItem<String> hostItem, ConnectionConfig config) {
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

    private void handleCreateDatabase(TreeItem<String> hostItem, ConnectionConfig config) {
        if (config.getPassword() == null) {
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
            pwdDialog.setResultConverter(dialogButton -> dialogButton == ButtonType.OK ? pf.getText() : null);
            final String[] passwordHolder = new String[1];
            pwdDialog.showAndWait().ifPresentOrElse(pwd -> passwordHolder[0] = pwd, () -> {});
            if (passwordHolder[0] == null || passwordHolder[0].isEmpty()) return;
            config.setPassword(passwordHolder[0]);
        }

        Stage stage = getStage();
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
                        handleRefreshDbHost(hostItem, config);
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

    private void handleEditDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();

        if (config.getPassword() == null) {
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
            pwdDialog.setResultConverter(dialogButton -> dialogButton == ButtonType.OK ? pf.getText() : null);
            final String[] passwordHolder = new String[1];
            pwdDialog.showAndWait().ifPresentOrElse(pwd -> passwordHolder[0] = pwd, () -> {});
            if (passwordHolder[0] == null || passwordHolder[0].isEmpty()) return;
            config.setPassword(passwordHolder[0]);
        }

        Stage stage = getStage();
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
                                handleRefreshDbNode(dbItem, data);
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

    private void handleDeleteDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
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
                pwdDialog.setResultConverter(dialogButton -> dialogButton == ButtonType.OK ? pf.getText() : null);
                final String[] passwordHolder = new String[1];
                pwdDialog.showAndWait().ifPresentOrElse(pwd -> passwordHolder[0] = pwd, () -> {});
                if (passwordHolder[0] == null || passwordHolder[0].isEmpty()) return;
                config.setPassword(passwordHolder[0]);
            }

            new Thread(() -> {
                try {
                    DatabaseService.dropDatabase(config, dbName);
                    Platform.runLater(() -> {
                        removeDbNodeDataRecursive(dbItem);
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

    private void handleRefreshDbHost(TreeItem<String> hostItem, ConnectionConfig config) {
        if (config.getType() == ConnectType.ROCKETMQ) {
            for (TreeItem<String> child : hostItem.getChildren()) {
                removeDbNodeDataRecursive(child);
            }
            hostItem.getChildren().clear();
            handleRocketmqHostDoubleClick(hostItem, config);
            return;
        }
        if (config.getPassword() == null) {
            handleDbHostDoubleClick(hostItem, config);
            return;
        }
        new Thread(() -> {
            try {
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    for (TreeItem<String> child : hostItem.getChildren()) {
                        removeDbNodeDataRecursive(child);
                    }
                    hostItem.getChildren().clear();
                    for (String dbName : databases) {
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        dbItem.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName)));
                        dbNodeDataMap.put(dbItem, new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName));
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

    private void removeDbNodeDataRecursive(TreeItem<String> item) {
        dbNodeDataMap.remove(item);
        for (TreeItem<String> child : item.getChildren()) {
            removeDbNodeDataRecursive(child);
        }
    }

    private void handleRefreshDbNode(TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        switch (data.getType()) {
            case DATABASE -> {
                if (data.isOpened()) {
                    removeDbNodeDataRecursive(item);
                    item.getChildren().clear();
                    openDatabase(item, data);
                }
            }
            case TABLES_FOLDER -> {
                item.getChildren().clear();
                loadTablesForFolder(item, config, data.getDatabaseName(), false);
            }
            case VIEWS_FOLDER -> {
                item.getChildren().clear();
                loadViewsForFolder(item, config, data.getDatabaseName(), false);
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

    private void handleDeleteDbNodes() {
        ObservableList<TreeItem<String>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        List<TreeItem<String>> tableItems = new ArrayList<>();
        List<TreeItem<String>> viewItems = new ArrayList<>();

        for (TreeItem<String> item : selectedItems) {
            DatabaseNodeData data = dbNodeDataMap.get(item);
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
                DatabaseNodeData data = dbNodeDataMap.get(item);
                String key = data.getConnectionConfig().getId() + "|" + data.getDatabaseName();
                groupedTables.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<String, List<TreeItem<String>>> entry : groupedTables.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                String configId = parts[0];
                String dbName = parts[1];
                List<String> tableNames = entry.getValue().stream()
                        .map(TreeItem::getValue).toList();
                ConnectionConfig cfg = connections.stream()
                        .filter(c -> c.getId().equals(configId)).findFirst().orElse(null);
                if (cfg == null) continue;

                try {
                    DatabaseService.dropTables(cfg, dbName, tableNames);
                    Platform.runLater(() -> {
                        for (TreeItem<String> item : entry.getValue()) {
                            dbNodeDataMap.remove(item);
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
                DatabaseNodeData data = dbNodeDataMap.get(item);
                String key = data.getConnectionConfig().getId() + "|" + data.getDatabaseName();
                groupedViews.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<String, List<TreeItem<String>>> entry : groupedViews.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                String configId = parts[0];
                String dbName = parts[1];
                List<String> viewNames = entry.getValue().stream()
                        .map(TreeItem::getValue).toList();
                ConnectionConfig cfg = connections.stream()
                        .filter(c -> c.getId().equals(configId)).findFirst().orElse(null);
                if (cfg == null) continue;

                try {
                    DatabaseService.dropViews(cfg, dbName, viewNames);
                    Platform.runLater(() -> {
                        for (TreeItem<String> item : entry.getValue()) {
                            dbNodeDataMap.remove(item);
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

    private void handleConnect(ConnectionConfig config) {
        if (contentArea == null || terminalTabPane == null) return;

        if (!ensureTabPaneInstalled()) return;

        if (config.getType() == ConnectType.LOCAL_TERMINAL) {
            doLocalTerminalConnect(config);
            return;
        }

        boolean isS3orOSS = config.getType() == ConnectType.S3 || config.getType() == ConnectType.ALIYUN_OSS;
        if (isS3orOSS) {
            doS3Connect(config);
            return;
        }

        if (config.getType() == ConnectType.RDP) {
            for (Tab tab : terminalTabPane.getTabs()) {
                if (config.getId().equals(tab.getUserData())) {
                    terminalTabPane.getSelectionModel().select(tab);
                    showTerminalView();
                    return;
                }
            }
            doRdpConnect(config);
            return;
        }

        boolean isDatabase = config.getType() == ConnectType.MYSQL
                || config.getType() == ConnectType.POSTGRESQL
                || config.getType() == ConnectType.ORACLE;
        boolean isRedis = config.getType() == ConnectType.REDIS;

        if (isDatabase) {
            TreeItem<String> hostItem = findItemById(root, config.getId());
            if (hostItem != null) {
                handleDbHostDoubleClick(hostItem, config);
            }
            return;
        }

        if (isRedis) {
            TreeItem<String> hostItem = findItemById(root, config.getId());
            if (hostItem != null) {
                handleRedisHostDoubleClick(hostItem, config);
            }
            return;
        }

        if (config.getType() == ConnectType.ROCKETMQ) {
            TreeItem<String> hostItem = findItemById(root, config.getId());
            if (hostItem != null) {
                handleRocketmqHostDoubleClick(hostItem, config);
            }
            return;
        }

        SSHTerminalPane terminalPane = new SSHTerminalPane();

        int scrollback = config.getScrollbackLines() != null ?
                config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        terminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(config.getName());
        tab.setContent(terminalPane);
        tab.setUserData(config.getId());

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem copySessionItem = new MenuItem("复制会话");
        copySessionItem.setOnAction(e -> handleConnect(config));

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            int newScrollback = config.getScrollbackLines() != null ?
                    config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
            terminalPane.setScrollbackLines(newScrollback);
            ConfigManager.saveConnections(connections);
        });

        MenuItem globalConfigItem = new MenuItem("终端配置");
        globalConfigItem.setOnAction(e -> {
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.SSH);
            if (config.getScrollbackLines() == null) {
                terminalPane.setScrollbackLines(GlobalConfig.getInstance().getScrollbackLines());
            }
        });

        tabContextMenu.getItems().addAll(copySessionItem, new SeparatorMenuItem(), sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            terminalPane.disconnect();
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();

        doConnect(terminalPane, config);
    }

    private void showTerminalView() {
        // 已直接使用terminalTabPane，无需隐藏/显示其他元素
        if (terminalTabPane != null) {
            terminalTabPane.setVisible(true);
            terminalTabPane.setManaged(true);
        }
    }

    private void showDataView() {
        showTerminalView();
    }

    private void showWelcomeView() {
        // 无标签时保持TabPane可见，但可以清空标签或显示提示
        if (terminalTabPane != null) {
            terminalTabPane.setVisible(true);
            terminalTabPane.setManaged(true);
        }
    }

    private void doConnect(SSHTerminalPane terminalPane, ConnectionConfig config) {
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

    private void doLocalTerminalConnect(ConnectionConfig config) {
        for (Tab tab : terminalTabPane.getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showTerminalView();
                return;
            }
        }

        LocalTerminalPane localTerminalPane = new LocalTerminalPane();

        int scrollback = config.getScrollbackLines() != null ?
                config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        localTerminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(config.getName());
        tab.setContent(localTerminalPane);
        tab.setUserData(config.getId());

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem copySessionItem = new MenuItem("复制会话");
        copySessionItem.setOnAction(e -> doLocalTerminalConnect(config));

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            int newScrollback = config.getScrollbackLines() != null ?
                    config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
            localTerminalPane.setScrollbackLines(newScrollback);
            ConfigManager.saveConnections(connections);
        });

        MenuItem globalConfigItem = new MenuItem("终端配置");
        globalConfigItem.setOnAction(e -> {
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.SSH);
            if (config.getScrollbackLines() == null) {
                localTerminalPane.setScrollbackLines(GlobalConfig.getInstance().getScrollbackLines());
            }
        });

        tabContextMenu.getItems().addAll(copySessionItem, new SeparatorMenuItem(), sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            localTerminalPane.disconnect();
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();

        String terminalType = config.getTerminalType() != null ? config.getTerminalType() : "cmd";
        localTerminalPane.connect(terminalType);
    }

    private void doS3Connect(ConnectionConfig config) {
        for (Tab tab : terminalTabPane.getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showTerminalView();
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
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            ConfigManager.saveConnections(connections);
        });
        tabContextMenu.getItems().addAll(refreshItem, new SeparatorMenuItem(), sessionConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();
    }

    private void doRdpConnect(ConnectionConfig config) {
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
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            ConfigManager.saveConnections(connections);
        });

        tabContextMenu.getItems().add(sessionConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            rdpPane.disconnect();
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == tab) {
                rdpPane.requestRdpFocus();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();

        int rdpPort = config.getPort() > 0 ? config.getPort() : 3389;
        int width = config.getScreenWidth() > 0 ? config.getScreenWidth() : 1024;
        int height = config.getScreenHeight() > 0 ? config.getScreenHeight() : 768;
        int bpp = config.getColorDepth() > 0 ? config.getColorDepth() : 24;
        String domain = config.getDomain();

        rdpPane.connect(config.getHost(), rdpPort, config.getUsername(), password,
                domain, width, height, bpp, config.isUseSsl());
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

    private Stage getStage() {
        Node node = treeView;
        while (node != null && !(node.getScene() != null && node.getScene().getWindow() instanceof Stage)) {
            node = node.getParent();
        }
        if (node != null && node.getScene() != null && node.getScene().getWindow() instanceof Stage) {
            return (Stage) node.getScene().getWindow();
        }
        return null;
    }

    private boolean ensureTabPaneInstalled() {
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
    }
}