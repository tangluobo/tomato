package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.rdp.RdpPane;
import com.tangluobo.tomato.ssh.LocalTerminalPane;
import com.tangluobo.tomato.ssh.SSHTerminalPane;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
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
    // 记录clickCount==1的MOUSE_PRESSED时的选中项，用于判断"再次点击已选中节点"
    private TreeItem<String> selectedItemBeforeClick;
    // 单击编辑延迟定时器，用于区分单击和双击
    private Timeline singleClickTimer;
    private Image folderIcon;
    private Image dbIcon;
    private Image dbIconGray;
    private Image tableIcon;
    private Image viewIcon;
    private Image functionIcon;
    private Image backupIcon;
    private Image queryIcon;
    private TextField searchField;

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

        // 搜索过滤
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
        // 子文件夹统一使用folderIcon，实体节点使用各自的专用图标
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

    /**
     * 根据搜索关键词过滤树：匹配节点 + 其父节点 + 其所有子节点
     */
    private void filterTree(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            // 无搜索词，重建完整树
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

        // 过滤：移除不匹配且无匹配后代的节点
        filterTreeItem(root, kw);

        // 展开所有可见的节点
        expandAll(root);
    }

    /**
     * 递归过滤树节点，返回该节点或其后代是否匹配
     */
    private boolean filterTreeItem(TreeItem<String> item, String keyword) {
        boolean selfMatch = item.getValue() != null && item.getValue().toLowerCase().contains(keyword);

        // 如果自身匹配，递归保留所有子节点（但仍需过滤孙级以高亮匹配）
        if (selfMatch) {
            for (TreeItem<String> child : item.getChildren()) {
                filterTreeItem(child, keyword);
            }
            return true;
        }

        // 自身不匹配，检查子节点
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

    /**
     * 展开所有节点
     */
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

    private VBox contentArea;
    private VBox welcomePane;
    private TabPane terminalTabPane;
    private ScrollPane contentScrollPane;
    private VBox contentPaneVBox;

    private ContextMenu contextMenu;

    private void setupContextMenu() {
        contextMenu = new ContextMenu();

        treeView.setOnContextMenuRequested(event -> {
            // 先隐藏旧菜单
            contextMenu.hide();
            contextMenu.getItems().clear();

            // 判断右键是否点击在某个节点上，还是空白区域
            Node node = event.getPickResult().getIntersectedNode();
            TreeItem<String> clickedItem = null;
            // 向上遍历找到TreeCell
            while (node != null && !(node instanceof TreeCell)) {
                node = node.getParent();
            }
            if (node instanceof TreeCell<?> cell) {
                clickedItem = (TreeItem<String>) cell.getTreeItem();
            }

            // 只有明确点击在节点上时才作为选中项，否则为null（代表根级）
            final TreeItem<String> targetItem = clickedItem;

            if (targetItem == null) {
                // 空白区域右键：以root为父级，可创建根下的目录和连接
                MenuItem addFolder = new MenuItem("新建目录");
                addFolder.setOnAction(e -> handleAddFolder(root));
                MenuItem addConnection = new MenuItem("新建连接");
                addConnection.setOnAction(e -> handleAddConnection(root));
                contextMenu.getItems().addAll(addFolder, addConnection);
            } else {
                // 检查是否为数据库动态节点
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
                    // 数据库类型连接节点：新建数据库、刷新
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
                    // Redis类型连接节点：刷新
                    if (isRedis) {
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
                    MenuItem deleteItem = new MenuItem("删除");
                    deleteItem.setOnAction(e -> handleDelete(targetItem));
                    contextMenu.getItems().addAll(connectItem, new SeparatorMenuItem(), editItem, deleteItem);
                } else {
                    // 目录节点：可以创建子目录/连接，可删除
                    MenuItem addFolder = new MenuItem("新建目录");
                    addFolder.setOnAction(e -> handleAddFolder(targetItem));
                    MenuItem addConnection = new MenuItem("新建连接");
                    addConnection.setOnAction(e -> handleAddConnection(targetItem));
                    MenuItem deleteItem = new MenuItem("删除");
                    deleteItem.setOnAction(e -> handleDelete(targetItem));
                    contextMenu.getItems().addAll(addFolder, addConnection, new SeparatorMenuItem(), deleteItem);
                }
                }
            }

            contextMenu.show(treeView, event.getScreenX(), event.getScreenY());
        });

        treeView.setOnMousePressed(event -> contextMenu.hide());

        // 在第一次MOUSE_PRESSED(clickCount==1)前记录当前选中项
        // 用于判断"点击的节点是否本来就处于选中状态"
        treeView.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (event.getClickCount() == 1) {
                // 只在第一次按下时记录，避免双击的第二次按下覆盖
                selectedItemBeforeClick = treeView.getSelectionModel().getSelectedItem();
            }
        });

        // 检测"再次点击已选中的表/视图节点"以进入编辑模式
        // 核心判断：如果MOUSE_CLICKED时当前选中项 == selectedItemBeforeClick，
        // 说明节点在点击前就已经选中，这次是"再次点击"
        // 使用延迟区分单击和双击：单击延迟300ms触发编辑，双击取消延迟并执行打开
        treeView.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;

            TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem == null) return;

            DatabaseNodeData dbData = dbNodeDataMap.get(selectedItem);
            boolean isTableOrView = dbData != null
                && (dbData.getType() == DatabaseNodeData.NodeType.TABLE || dbData.getType() == DatabaseNodeData.NodeType.VIEW);

            // 判断：点击的节点在点击前是否已选中
            boolean wasAlreadySelected = selectedItem == selectedItemBeforeClick;

            if (dbData != null) {
                if (event.getClickCount() == 2) {
                    // 双击时取消单击延迟定时器，避免同时触发编辑
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }

                    event.consume(); // 捕获阶段消费事件，阻止TreeView默认双击展开

                    // 双击 -> 执行双击打开，不进入编辑
                    handleDbNodeDoubleClick(selectedItem, dbData);
                    selectedItemBeforeClick = null;
                    if (editingItem != null) {
                        editingItem = null;
                        treeView.setEditable(false);
                    }
                    return;
                }

                if (event.getClickCount() == 1) {
                    // 单击已选中的表/视图 -> 延迟进入编辑模式，等待判断是否为双击
                    if (isTableOrView && wasAlreadySelected && editingItem == null) {
                        TreeItem<String> itemToEdit = selectedItem;
                        if (singleClickTimer != null) {
                            singleClickTimer.stop();
                        }
                        singleClickTimer = new Timeline(new KeyFrame(
                            javafx.util.Duration.millis(300),
                            ae -> {
                                if (editingItem == null && itemToEdit == treeView.getSelectionModel().getSelectedItem()) {
                                    editingItem = itemToEdit;
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
                // 检查是否为连接配置节点
                ConnectionConfig config = itemConfigMap.get(selectedItem);
                if (config != null && config.getType() != null) {
                    boolean isDatabase = config.getType() == ConnectType.MYSQL
                        || config.getType() == ConnectType.POSTGRESQL
                        || config.getType() == ConnectType.ORACLE;
                    boolean isRedis = config.getType() == ConnectType.REDIS;
                    if (isDatabase) {
                        handleDbHostDoubleClick(selectedItem, config);
                    } else if (isRedis) {
                        handleRedisHostDoubleClick(selectedItem, config);
                    } else {
                        handleConnect(config);
                    }
                }
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
                    // 创建自定义展开/折叠箭头
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
                        // 不是我们要编辑的项，忽略
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
                    // 保留图标，将图标和编辑框放在HBox中
                    Node icon = treeItem.getGraphic();
                    HBox editBox = new HBox(icon, editField);
                    editBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
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
                    editingItem = null;
                    editField = null;
                    treeView.setEditable(false);

                    // 恢复正常显示（暂不更新TreeItem值，等重命名成功后再更新）
                    setText(oldName);
                    setGraphic(treeItem.getGraphic());

                    if (newName.isEmpty() || newName.equals(oldName)) return;

                    // 执行重命名
                    DatabaseNodeData dbData = dbNodeDataMap.get(treeItem);
                    if (dbData != null) {
                        commitTableNameRename(treeItem, dbData, oldName, newName);
                    }
                }

                @Override
                public void cancelEdit() {
                    TreeItem<String> treeItem = getTreeItem();
                    editingItem = null;
                    editField = null;
                    treeView.setEditable(false);

                    super.cancelEdit();
                    if (treeItem != null) {
                        setText(treeItem.getValue());
                        setGraphic(treeItem.getGraphic());
                    }
                }

                @Override
                protected void updateItem(String item, boolean empty) {
                    // 清理旧监听
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
                        // 如果正在编辑当前项，保持编辑状态
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
                            // 更新箭头方向
                            arrowPath.setRotate(treeItem.isExpanded() ? 90 : 0);
                            currentTreeItem = treeItem;
                            expandedListener = (obs, wasExpanded, isExpanded) ->
                                arrowPath.setRotate(isExpanded ? 90 : 0);
                            treeItem.expandedProperty().addListener(expandedListener);
                        }
                    }
                }
            };

            // 拖拽源：开始拖拽
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

            // 拖拽经过：判断是否可以放置
            cell.setOnDragOver(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                    TreeItem<String> targetItem = cell.getTreeItem();
                    // 只能放到目录节点（type==null）或空白区域
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

            // 拖拽进入：高亮
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

            // 拖拽退出：取消高亮
            cell.setOnDragExited(event -> {
                cell.setStyle("");
                event.consume();
            });

            // 放置：移动节点到新的父级
            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                    String dragId = db.getString().substring(DRAG_PREFIX.length());
                    TreeItem<String> targetItem = cell.getTreeItem();

                    // 确定目标父级
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

                    // 查找被拖拽的节点
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

        // 空白区域也支持拖拽放置（移动到根级）
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

    /**
     * 判断possibleDescendant是否是ancestor的后代
     */
    private boolean isDescendant(TreeItem<String> ancestor, TreeItem<String> possibleDescendant) {
        if (ancestor == possibleDescendant) return true;
        for (TreeItem<String> child : ancestor.getChildren()) {
            if (isDescendant(child, possibleDescendant)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 移动节点到新的父级
     */
    private void moveItem(TreeItem<String> item, TreeItem<String> newParent) {
        ConnectionConfig config = itemConfigMap.get(item);
        if (config == null) return;

        // 从旧父级移除
        item.getParent().getChildren().remove(item);

        // 更新parentId
        if (newParent == root) {
            config.setParentId(null);
        } else {
            ConnectionConfig parentConfig = itemConfigMap.get(newParent);
            if (parentConfig != null) {
                config.setParentId(parentConfig.getId());
            }
        }

        // 添加到新父级
        newParent.getChildren().add(item);
        newParent.setExpanded(true);

        // 持久化
        ConfigManager.saveConnections(connections);
    }

    /**
     * 双击数据库主机节点：连接数据库并加载数据库列表
     */
    private void handleDbHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        // 如果已连接且已展开，则折叠
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        // 如果需要密码但未保存，弹出密码输入框
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

        // 显示加载转圈效果
        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);

        // 异步连接并加载数据库列表
        new Thread(() -> {
            try {
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    // 更新主机节点图标为已连接状态
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
                    // 恢复原图标
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

    /**
     * 双击Redis主机节点：连接Redis并加载数据库列表
     */
    private void handleRedisHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        // 如果已连接且已展开，则折叠
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        // 如果需要密码但未保存，弹出密码输入框
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

        // 显示加载转圈效果
        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);

        // 异步连接并加载数据库列表
        new Thread(() -> {
            try {
                List<String> databases = RedisService.getDatabases(config);
                Platform.runLater(() -> {
                    // 更新主机节点图标为已连接状态
                    updateHostIcon(hostItem, config, true);

                    hostItem.getChildren().clear();
                    for (String dbIndex : databases) {
                        String dbName = "db" + dbIndex;
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        // 使用数据库图标（灰色，未打开状态）
                        dbItem.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.REDIS_DB, dbName, config, dbName)));
                        dbNodeDataMap.put(dbItem, new DatabaseNodeData(DatabaseNodeData.NodeType.REDIS_DB, dbName, config, dbName));
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    // 恢复原图标
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

    /**
     * 双击数据库动态节点：根据节点类型加载子节点
     */
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
        }
    }

    /**
     * 双击数据库节点：加载"表"和"视图"文件夹节点
     */
    private void handleDatabaseDoubleClick(TreeItem<String> dbItem, DatabaseNodeData data) {
        if (data.isOpened()) {
            // 已打开状态：折叠/展开切换
            dbItem.setExpanded(!dbItem.isExpanded());
            return;
        }

        // 打开数据库：加载表和视图
        openDatabase(dbItem, data);
    }

    /**
     * 双击Redis数据库节点：以Tab形式打开Redis数据视图
     */
    private void handleRedisDbDoubleClick(TreeItem<String> dbItem, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        // 解析数据库编号（格式为 "db0", "db1" 等）
        String dbName = data.getDatabaseName();
        int dbIndex = 0;
        if (dbName.startsWith("db")) {
            try {
                dbIndex = Integer.parseInt(dbName.substring(2));
            } catch (NumberFormatException ignored) {}
        }

        String tabId = "redis_" + data.getConnectionConfig().getId() + "_" + dbName;
        // 同一个数据库只允许一个标签
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

        // 设置标签头图标
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

    /**
     * 打开数据库节点：加载表和视图文件夹，图标变彩色
     */
    private void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        data.setOpened(true);
        dbItem.setGraphic(getDbNodeIcon(data));

        ConnectionConfig config = data.getConnectionConfig();

        // 添加"表"和"视图"文件夹节点
        TreeItem<String> tablesFolder = new TreeItem<>("表");
        tablesFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, data.getDatabaseName())));
        dbNodeDataMap.put(tablesFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, data.getDatabaseName()));

        TreeItem<String> viewsFolder = new TreeItem<>("视图");
        viewsFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, data.getDatabaseName())));
        dbNodeDataMap.put(viewsFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, data.getDatabaseName()));

        TreeItem<String> functionFolder = new TreeItem<>("函数");
        functionFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, data.getDatabaseName())));
        dbNodeDataMap.put(functionFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, data.getDatabaseName()));

        // 添加"查询"文件夹节点
        TreeItem<String> queryFolder = new TreeItem<>("查询");
        queryFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, data.getDatabaseName())));
        dbNodeDataMap.put(queryFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, data.getDatabaseName()));

        // 从文件系统加载已保存的查询
        loadQueriesForFolder(queryFolder, config, data.getDatabaseName());

        TreeItem<String> backupFolder = new TreeItem<>("备份");
        backupFolder.setGraphic(getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, data.getDatabaseName())));
        dbNodeDataMap.put(backupFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, data.getDatabaseName()));

        loadBackupsForFolder(backupFolder, config, data.getDatabaseName());

        dbItem.getChildren().addAll(tablesFolder, viewsFolder, functionFolder,queryFolder,backupFolder);
        dbItem.setExpanded(true);

        // 自动加载表和视图（默认不展开子节点）
        loadTablesForFolder(tablesFolder, config, data.getDatabaseName(), false);
        loadViewsForFolder(viewsFolder, config, data.getDatabaseName(), false);
    }

    /**
     * 关闭数据库节点：清除子节点，图标变灰色
     */
    private void closeDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        removeDbNodeDataRecursive(dbItem);
        dbItem.getChildren().clear();
        data.setOpened(false);
        dbItem.setGraphic(getDbNodeIcon(data));
        dbItem.setExpanded(false);
    }

    /**
     * 双击"表"文件夹节点：加载表列表并展开
     */
    private void handleTablesFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadTablesForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), true);
    }

    /**
     * 双击"视图"文件夹节点：加载视图列表并展开
     */
    private void handleViewsFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadViewsForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), true);
    }

    /**
     * 从文件系统加载已保存的查询列表到指定文件夹节点
     */
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

    /**
     * 异步加载表列表到指定文件夹节点
     * @param autoExpand 加载完成后是否自动展开
     */
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

    /**
     * 异步加载视图列表到指定文件夹节点
     * @param autoExpand 加载完成后是否自动展开
     */
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

    /**
     * 双击表/视图节点：在树中展开显示列名
     */
    private void handleTableNodeDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (!item.getChildren().isEmpty()) {
            item.setExpanded(!item.isExpanded());
            return;
        }
        loadColumnsForTable(item, data.getConnectionConfig(), data.getDatabaseName(), data.getName());
    }

    /**
     * 异步加载表的列名作为子节点
     */
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
                        // 列节点图标：小矩形
                        boolean isPk = "是".equals(col.get("主键"));
                        ImageView iv = new ImageView();
                        iv.setFitWidth(16);
                        iv.setFitHeight(16);
                        // 用代码生成列图标
                        javafx.scene.shape.Rectangle rect = new javafx.scene.shape.Rectangle(10, 10);
                        rect.setFill(isPk ? javafx.scene.paint.Color.valueOf("#1E88E5") : javafx.scene.paint.Color.valueOf("#999999"));
                        rect.setArcWidth(2);
                        rect.setArcHeight(2);
                        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
                        sp.setFill(javafx.scene.paint.Color.TRANSPARENT);
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

    /**
     * 右键"设计表"：以Tab形式打开表结构视图
     */
    private void handleTableStructureDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        String tabId = "struct_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName() + "_" + data.getName();
        // 同一个表/视图只允许一个标签，再次双击定位到已有标签
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
        // 设置标签头图标：表或视图
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
        structRefreshItem.setOnAction(e -> {
            structView.loadStructure();
        });
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

    /**
     * 双击表/视图节点：以Tab形式打开表格数据视图
     */
    private void handleTableDataDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (contentArea == null || terminalTabPane == null) return;
        if (!ensureTabPaneInstalled()) return;

        String tabId = data.getConnectionConfig().getId() + "_" + data.getDatabaseName() + "_" + data.getName();
        // 同一个表/视图只允许一个标签，再次双击定位到已有标签
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
        // 设置标签头图标：表或视图
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
        tableRefreshItem.setOnAction(e -> {
            dataView.refreshData();
        });
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

    /**
     * 右键"查询"文件夹：新建查询（直接创建编辑器标签）
     */
    private void handleNewQuery(TreeItem<String> folderItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        // 找到查询文件夹对应的数据库节点，用于保存时添加子节点
        SqlEditorView editorView = new SqlEditorView(connections, config, dbName);

        // 设置标签
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

        // 标题变更回调
        editorView.setOnTitleChange(title -> tab.setText(title));

        // 保存请求回调：弹出输入框，创建树节点
        editorView.setOnSaveRequest(() -> {
            TextInputDialog dialog = new TextInputDialog("查询" + (folderItem.getChildren().size() + 1));
            dialog.setTitle("保存查询");
            dialog.setHeaderText(null);
            dialog.setContentText("查询名称：");
            dialog.showAndWait().ifPresent(name -> {
                if (name.trim().isEmpty()) return;

                String queryName = name.trim();
                editorView.doSave(queryName);

                // 创建树节点
                TreeItem<String> queryItem = new TreeItem<>(queryName);
                DatabaseNodeData queryData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, queryName, config, dbName);
                queryItem.setGraphic(getDbNodeIcon(queryData));
                dbNodeDataMap.put(queryItem, queryData);
                folderItem.getChildren().add(queryItem);
                folderItem.setExpanded(true);

                editorView.setQueryNode(queryItem);

                // 更新tab的id以便后续查找
                String newTabId = "query_" + config.getId() + "_" + dbName + "_" + queryName;
                tab.setUserData(newTabId);
            });
        });

        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        // 标记为修改状态（新查询默认未保存）
        editorView.markModified();

        if (!ensureTabPaneInstalled()) return;
        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showDataView();
    }

    /**
     * 双击查询节点：打开SQL编辑器标签
     */
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

        // 标题变更回调
        editorView.setOnTitleChange(title -> tab.setText(title));

        // 保存请求回调：弹出输入框
        editorView.setOnSaveRequest(() -> {
            TextInputDialog dialog = new TextInputDialog(data.getName());
            dialog.setTitle("保存查询");
            dialog.setHeaderText(null);
            dialog.setContentText("查询名称：");
            dialog.showAndWait().ifPresent(name -> {
                if (name.trim().isEmpty()) return;
                editorView.doSave(name.trim());
                // 更新树节点
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

    /**
     * 重命名查询节点
     */
    private void handleRenameQuery(TreeItem<String> queryItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog(data.getName());
        dialog.setTitle("重命名查询");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String newName = name.trim();

            // 读取旧文件内容
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

    /**
     * 提交表/视图重命名（从行内编辑触发）
     */
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
                    // 更新树节点和元数据
                    item.setValue(newName);
                    dbNodeDataMap.put(item, new DatabaseNodeData(dbData.getType(), newName, config, dbName));
                    // 清除列子节点，下次展开时重新加载
                    item.getChildren().clear();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    // 重命名失败，恢复旧名称
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

    /**
     * 删除查询节点
     */
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

    /**
     * 打开备份目录并选中备份文件
     */
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
                        // 打开目录并选中文件
                        if (desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) {
                            desktop.browseFileDirectory(backupFile.toFile());
                        } else {
                            // 不支持选中文件时，仅打开目录
                            desktop.open(backupDir.toFile());
                        }
                    } else {
                        desktop.open(backupDir.toFile());
                    }
                }
            } catch (Exception e) {
                // 兜底：使用系统命令打开目录
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

    /**
     * 更新主机节点图标（连接/断开状态）
     */
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

    /**
     * 新建数据库
     */
    private void handleCreateDatabase(TreeItem<String> hostItem, ConnectionConfig config) {
        // 需要密码才能操作
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
                    // 刷新数据库列表
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

    /**
     * 编辑数据库（修改字符集/排序规则）
     */
    private void handleEditDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();

        // 需要密码才能操作
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

        // 异步获取当前数据库的字符集和排序规则，然后打开编辑对话框
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
                                // 刷新数据库节点
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

    /**
     * 删除数据库（确认提示）
     */
    private void handleDeleteDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        // 确认删除
        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("删除数据库");
        confirm.setHeaderText("确定要删除数据库 \"" + dbName + "\" 吗？");
        confirm.setContentText("此操作不可撤销，该数据库中的所有数据将被永久删除！");
        confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.YES) return;

            // 需要密码才能操作
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
                        // 从树中移除节点
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

    /**
     * 刷新数据库主机节点下的数据库列表
     */
    private void handleRefreshDbHost(TreeItem<String> hostItem, ConnectionConfig config) {
        if (config.getPassword() == null) {
            handleDbHostDoubleClick(hostItem, config);
            return;
        }
        new Thread(() -> {
            try {
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    // 清除旧的子节点对应的dbNodeDataMap
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

    /**
     * 递归清除TreeItem及其子节点在dbNodeDataMap中的映射
     */
    private void removeDbNodeDataRecursive(TreeItem<String> item) {
        dbNodeDataMap.remove(item);
        for (TreeItem<String> child : item.getChildren()) {
            removeDbNodeDataRecursive(child);
        }
    }

    /**
     * 刷新数据库动态节点
     */
    private void handleRefreshDbNode(TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        switch (data.getType()) {
            case DATABASE -> {
                if (data.isOpened()) {
                    // 已打开状态：清除子节点后重新加载
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

    /**
     * 删除选中的表/视图节点（支持多选批量操作）
     */
    private void handleDeleteDbNodes() {
        // 收集所有选中的 TABLE/VIEW 节点
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

        // 构建确认提示
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

        // 设置按钮文本更醒目
        ButtonType deleteBtn = new ButtonType("确认删除");
        confirm.getButtonTypes().setAll(deleteBtn, ButtonType.CANCEL);

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != deleteBtn) return;

        // 按连接配置+数据库分组处理
        // 表删除
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

        // 视图删除
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

    /**
     * 处理连接，以标签方式打开终端
     */
    private void handleConnect(ConnectionConfig config) {
        if (contentArea == null || terminalTabPane == null) return;

        // 确保TabPane已安装到contentPaneVBox
        if (!ensureTabPaneInstalled()) return;

        // 本地终端类型：打开本地命令行
        if (config.getType() == ConnectType.LOCAL_TERMINAL) {
            doLocalTerminalConnect(config);
            return;
        }

        // S3/阿里云OSS类型：打开文件浏览器
        boolean isS3orOSS = config.getType() == ConnectType.S3 || config.getType() == ConnectType.ALIYUN_OSS;
        if (isS3orOSS) {
            doS3Connect(config);
            return;
        }

        // RDP同一主机只允许一个标签，再次双击定位到已有标签
        if (config.getType() == ConnectType.RDP) {
            for (Tab tab : terminalTabPane.getTabs()) {
                if (config.getId().equals(tab.getUserData())) {
                    terminalTabPane.getSelectionModel().select(tab);
                    showTerminalView();
                    return;
                }
            }
        }

        // RDP类型：使用Java原生RDP客户端连接
        if (config.getType() == ConnectType.RDP) {
            doRdpConnect(config);
            return;
        }

        // 数据库类型连接：通过树节点加载数据库列表
        boolean isDatabase = config.getType() == ConnectType.MYSQL
            || config.getType() == ConnectType.POSTGRESQL
            || config.getType() == ConnectType.ORACLE;
        boolean isRedis = config.getType() == ConnectType.REDIS;

        if (isDatabase) {
            // 找到对应的主机树节点并触发展开
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

        SSHTerminalPane terminalPane = new SSHTerminalPane();

        // 应用scrollback配置（会话配置优先，否则使用全局配置）
        int scrollback = config.getScrollbackLines() != null ?
            config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        terminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(config.getName());
        tab.setContent(terminalPane);
        tab.setUserData(config.getId());

        // 标签右键菜单
        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem copySessionItem = new MenuItem("复制会话");
        copySessionItem.setOnAction(e -> handleConnect(config));

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            // 应用更新后的配置
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

        // 标签关闭时断开连接
        tab.setOnClosed(e -> {
            terminalPane.disconnect();
            // 没有标签时恢复欢迎页
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();

        doConnect(terminalPane, config);
    }

    /**
     * 处理数据库类型连接（MySQL/PostgreSQL/Oracle）
     * 如果启用了SSH通道，先建立SSH隧道，再通过隧道连接数据库
     */
    private void handleDatabaseConnect(ConnectionConfig config) {
        SSHTerminalPane terminalPane = new SSHTerminalPane();

        Tab tab = new Tab(config.getName());
        tab.setContent(terminalPane);
        tab.setUserData(config.getId());

        // 标签关闭时断开连接
        tab.setOnClosed(e -> {
            terminalPane.disconnect();
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();

        // 启用SSH通道时，先建立隧道
        if (config.isUseSshTunnel()) {
            doDatabaseConnectWithTunnel(terminalPane, config);
        } else {
            // 直连数据库（通过SSH终端连接到数据库服务器）
            doDatabaseConnectDirect(terminalPane, config);
        }
    }

    /**
     * 通过SSH通道连接数据库
     */
    private void doDatabaseConnectWithTunnel(SSHTerminalPane terminalPane, ConnectionConfig config) {
        // 检查SSH通道密码
        String tunnelPassword = config.getSshTunnelPassword();
        if (config.isSshTunnelUsePassword() && tunnelPassword == null) {
            // 弹出输入SSH通道密码对话框
            Dialog<String> pwdDialog = new Dialog<>();
            pwdDialog.setTitle("输入SSH通道密码");
            pwdDialog.setHeaderText(config.getName() + " - SSH通道 (" + config.getSshTunnelUsername() + "@" + config.getSshTunnelHost() + ")");
            pwdDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20, 10, 10, 10));
            PasswordField pf = new PasswordField();
            pf.setPrefWidth(250);
            grid.add(new Label("SSH密码："), 0, 0);
            grid.add(pf, 1, 0);
            pwdDialog.getDialogPane().setContent(grid);

            pwdDialog.setResultConverter(dialogButton -> dialogButton == ButtonType.OK ? pf.getText() : null);

            final String[] passwordHolder = new String[1];
            pwdDialog.showAndWait().ifPresentOrElse(pwd -> {
                passwordHolder[0] = pwd;
            }, () -> {});

            if (passwordHolder[0] == null || passwordHolder[0].isEmpty()) return;
            tunnelPassword = passwordHolder[0];
            config.setSshTunnelPassword(tunnelPassword);
        }

        final String finalTunnelPassword = tunnelPassword;
        new Thread(() -> {
            try {
                // 建立SSH通道
                SshTunnel tunnel = SshTunnel.fromConfig(config);
                int localPort = tunnel.connect();

                Platform.runLater(() -> {
                    terminalPane.getEmulator().process(
                        (String.format("\r\n[SSH通道] 已通过 %s:%d 建立到 %s:%d 的隧道\r\n",
                            config.getSshTunnelHost(), config.getSshTunnelPort(),
                            config.getHost(), config.getPort())).getBytes());
                    terminalPane.getEmulator().process(
                        (String.format("[SSH通道] 本地转发端口: 127.0.0.1:%d\r\n", localPort)).getBytes());
                    terminalPane.getEmulator().process(
                        (String.format("[数据库] %s://%s:***@127.0.0.1:%d/%s\r\n",
                            config.getType().getCode().toLowerCase(),
                            config.getUsername(), localPort,
                            config.getDatabase() != null ? config.getDatabase() : "")).getBytes());
                    terminalPane.getEmulator().process("\r\n[提示] SSH通道已就绪，可通过本地转发端口连接数据库\r\n".getBytes());
                    terminalPane.getTerminalView().render();
                });

                // 关联tunnel到tab，关闭时自动断开
                Tab currentTab = terminalTabPane.getTabs().stream()
                    .filter(t -> config.getId().equals(t.getUserData()))
                    .findFirst().orElse(null);
                if (currentTab != null) {
                    SshTunnel oldTunnel = (SshTunnel) currentTab.getProperties().get("sshTunnel");
                    if (oldTunnel != null) oldTunnel.disconnect();
                    currentTab.getProperties().put("sshTunnel", tunnel);
                    final SshTunnel finalTunnel = tunnel;
                    currentTab.setOnClosed(e -> {
                        finalTunnel.disconnect();
                        terminalPane.disconnect();
                        if (terminalTabPane.getTabs().isEmpty()) {
                            showWelcomeView();
                        }
                    });
                }

            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("SSH通道连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("SSH通道建立失败: " + e.getMessage());
                    alert.showAndWait();
                    terminalPane.disconnect();
                });
                e.printStackTrace();
            }
        }, "SSH-Tunnel-Connect").start();
    }

    /**
     * 直连数据库（不通过SSH通道，使用SSH终端连接到数据库服务器）
     */
    private void doDatabaseConnectDirect(SSHTerminalPane terminalPane, ConnectionConfig config) {
        // 数据库直连：需要密码时弹出输入
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

            pwdDialog.showAndWait().ifPresentOrElse(pwd -> {
                if (!pwd.isEmpty()) {
                    doDatabaseConnectWithPassword(terminalPane, config, pwd);
                }
            }, () -> {});
        } else {
            doDatabaseConnectWithPassword(terminalPane, config, config.getPassword());
        }
    }

    private void doDatabaseConnectWithPassword(SSHTerminalPane terminalPane, ConnectionConfig config, String password) {
        new Thread(() -> {
            try {
                // 通过SSH终端连接到数据库服务器
                terminalPane.connect(config.getHost(), config.getPort(), config.getUsername(), password, (List<String>) null);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("数据库连接失败: " + e.getMessage());
                    alert.showAndWait();
                    terminalPane.disconnect();
                });
                e.printStackTrace();
            }
        }, "DB-Connect").start();
    }

    private void showTerminalView() {
        // 隐藏ScrollPane（包含欢迎页），显示TabPane
        if (contentScrollPane != null) {
            contentScrollPane.setVisible(false);
            contentScrollPane.setManaged(false);
        }
        terminalTabPane.setVisible(true);
        terminalTabPane.setManaged(true);
    }

    private void showDataView() {
        // 隐藏ScrollPane（包含欢迎页），显示TabPane
        if (contentScrollPane != null) {
            contentScrollPane.setVisible(false);
            contentScrollPane.setManaged(false);
        }
        terminalTabPane.setVisible(true);
        terminalTabPane.setManaged(true);
    }

    private void showWelcomeView() {
        // 显示ScrollPane（包含欢迎页），隐藏TabPane
        terminalTabPane.setVisible(false);
        terminalTabPane.setManaged(false);
        if (contentScrollPane != null) {
            contentScrollPane.setVisible(true);
            contentScrollPane.setManaged(true);
        }
    }

    private void doConnect(SSHTerminalPane terminalPane, ConnectionConfig config) {
        // 需要密码认证但密码未保存时，弹出输入密码对话框
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

    /**
     * 处理本地终端连接
     */
    private void doLocalTerminalConnect(ConnectionConfig config) {
        // 同一个本地终端配置只允许一个标签，再次双击定位到已有标签
        for (Tab tab : terminalTabPane.getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showTerminalView();
                return;
            }
        }

        LocalTerminalPane localTerminalPane = new LocalTerminalPane();

        // 应用scrollback配置
        int scrollback = config.getScrollbackLines() != null ?
            config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        localTerminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(config.getName());
        tab.setContent(localTerminalPane);
        tab.setUserData(config.getId());

        // 标签右键菜单
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

        // 标签关闭时断开连接
        tab.setOnClosed(e -> {
            localTerminalPane.disconnect();
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();

        // 启动本地终端
        String terminalType = config.getTerminalType() != null ? config.getTerminalType() : "cmd";
        localTerminalPane.connect(terminalType);
    }

    /**
     * 处理S3/阿里云OSS连接，打开文件浏览器标签
     */
    private void doS3Connect(ConnectionConfig config) {
        // 同一个S3/OSS配置只允许一个标签，再次双击定位到已有标签
        for (Tab tab : terminalTabPane.getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                showTerminalView();
                return;
            }
        }

        // 需要密钥但未保存时，弹出输入框
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

        // 设置标签图标
        try {
            Image tabIcon = new Image(getClass().getResourceAsStream(config.getType().getIconPath()));
            if (tabIcon != null) {
                ImageView tabIconView = new ImageView(tabIcon);
                tabIconView.setFitWidth(16);
                tabIconView.setFitHeight(16);
                tab.setGraphic(tabIconView);
            }
        } catch (Exception e) {}

        // 标签右键菜单
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

        // 标签关闭时
        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();
    }

    /**
     * 处理RDP连接，使用Java原生RDP协议实现远程桌面
     */
    private void doRdpConnect(ConnectionConfig config) {
        // 需要密码但密码未保存时，弹出输入密码对话框
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

        // 创建RDP面板
        RdpPane rdpPane = new RdpPane();

        Tab tab = new Tab(config.getName());
        tab.setContent(rdpPane);
        tab.setUserData(config.getId());

        // 标签右键菜单（RDP不支持复制会话）
        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) terminalTabPane.getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            ConfigManager.saveConnections(connections);
        });

        tabContextMenu.getItems().add(sessionConfigItem);
        tab.setContextMenu(tabContextMenu);

        // 标签关闭时断开连接
        tab.setOnClosed(e -> {
            rdpPane.disconnect();
            if (terminalTabPane.getTabs().isEmpty()) {
                showWelcomeView();
            }
        });

        // Tab选中时请求RDP画布焦点
        terminalTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == tab) {
                rdpPane.requestRdpFocus();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        showTerminalView();

        // 获取RDP配置参数
        int rdpPort = config.getPort() > 0 ? config.getPort() : 3389;
        int width = config.getScreenWidth() > 0 ? config.getScreenWidth() : 1024;
        int height = config.getScreenHeight() > 0 ? config.getScreenHeight() : 768;
        int bpp = config.getColorDepth() > 0 ? config.getColorDepth() : 24;
        String domain = config.getDomain();

        // 执行RDP连接
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

    private void handleDelete(TreeItem<String> item) {
        ConnectionConfig config = itemConfigMap.get(item);
        if (config == null) return;

        boolean isFolder = config.getType() == null;
        boolean hasChildren = !item.getChildren().isEmpty();

        if (isFolder && hasChildren) {
            // 目录且有子节点：让用户选择保留子节点还是一起删除
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
                    // 只删除目录，子节点移到上级
                    String parentId = config.getParentId();
                    // 将子节点的parentId设为被删目录的parentId
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
                // CANCEL: 不做任何操作
            }
        } else {
            // 连接节点或空目录：简单确认
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

    /**
     * 延迟初始化：将TabPane加入contentPaneVBox（绕过ScrollPane限制）
     */
    private boolean ensureTabPaneInstalled() {
        if (terminalTabPane == null) return false;
        if (terminalTabPane.getParent() != null) return true; // 已安装

        // 延迟查找父级结构
        if (contentScrollPane == null || contentPaneVBox == null) {
            Node parent = contentArea.getParent();
            while (parent != null) {
                if (parent instanceof ScrollPane sp) {
                    contentScrollPane = sp;
                    if (sp.getParent() instanceof VBox vb) {
                        contentPaneVBox = vb;
                    }
                    break;
                }
                parent = parent.getParent();
            }
        }

        if (contentPaneVBox != null) {
            contentPaneVBox.getChildren().add(terminalTabPane);
            return true;
        }
        return false;
    }

    @Override
    public void loadContent(VBox contentArea) {
        this.contentArea = contentArea;
        contentArea.getChildren().clear();

        // 欢迎页
        welcomePane = new VBox();
        welcomePane.setPadding(new Insets(30));
        Label title = new Label("连接管理");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label hint = new Label("双击左侧连接以打开终端/文件浏览器");
        hint.setStyle("-fx-font-size: 13px; -fx-text-fill: #888888;");
        welcomePane.getChildren().addAll(title, hint);

        contentArea.getChildren().add(welcomePane);

        // 终端标签页
        terminalTabPane = new TabPane();
        terminalTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        terminalTabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        terminalTabPane.setFocusTraversable(false);
        VBox.setVgrow(terminalTabPane, javafx.scene.layout.Priority.ALWAYS);
        terminalTabPane.setVisible(false);
        terminalTabPane.setManaged(false);
        // TabPane将延迟安装到contentPaneVBox，在首次连接时执行
    }
}