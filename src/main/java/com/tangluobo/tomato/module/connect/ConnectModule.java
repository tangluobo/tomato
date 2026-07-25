package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.ssh.SSHTerminalPane;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
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
import javafx.embed.swing.SwingFXUtils;

import javax.swing.filechooser.FileSystemView;
import java.awt.image.BufferedImage;
import java.io.File;
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
    private Image folderIcon;
    private TextField searchField;

    @Override
    public String getName() {
        return "连接";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
        folderIcon = getSystemFolderIcon();

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

        itemConfigMap = new HashMap<>();
        connections = ConfigManager.loadConnections();
        loadTree();

        setupContextMenu();
        setupDragAndDrop();

        // 搜索过滤
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterTree(newVal));

        sidebarContainer.getChildren().addAll(headerBar, treeView);
        treeView.prefHeightProperty().bind(sidebarContainer.heightProperty().subtract(50));
    }

    private Image getSystemFolderIcon() {
        try {
            FileSystemView view = FileSystemView.getFileSystemView();
            File tempFolder = new File(System.getProperty("user.home"));
            javax.swing.Icon swingIcon = view.getSystemIcon(tempFolder);
            
            if (swingIcon != null) {
                int width = swingIcon.getIconWidth();
                int height = swingIcon.getIconHeight();
                BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                swingIcon.paintIcon(null, bufferedImage.getGraphics(), 0, 0);
                return SwingFXUtils.toFXImage(bufferedImage, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        try {
            return new Image(getClass().getResourceAsStream("/images/connect/folder.png"));
        } catch (Exception e) {
            return null;
        }
    }

    private void loadTree() {
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
        return item;
    }

    private ImageView getIconForConfig(ConnectionConfig config) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(16);
        imageView.setFitHeight(16);

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
                ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                if (targetConfig != null && targetConfig.getType() != null) {
                    // 具体连接节点：只能连接、编辑、删除，不能创建子节点
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

            contextMenu.show(treeView, event.getScreenX(), event.getScreenY());
        });

        treeView.setOnMousePressed(event -> contextMenu.hide());

        treeView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
                if (selectedItem != null) {
                    ConnectionConfig config = itemConfigMap.get(selectedItem);
                    if (config != null && config.getType() != null) {
                        handleConnect(config);
                    }
                }
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
                        setText(item);
                        TreeItem<String> treeItem = getTreeItem();
                        if (treeItem != null) {
                            setGraphic(treeItem.getGraphic());
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
     * 处理连接，以标签方式打开终端
     */
    private void handleConnect(ConnectionConfig config) {
        if (contentArea == null || terminalTabPane == null) return;

        // 确保TabPane已安装到contentPaneVBox
        if (!ensureTabPaneInstalled()) return;

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

        SSHTerminalPane terminalPane = new SSHTerminalPane();

        Tab tab = new Tab(config.getName());
        tab.setContent(terminalPane);
        tab.setUserData(config.getId());

        // 标签右键菜单：复制会话
        ContextMenu tabContextMenu = new ContextMenu();
        MenuItem copySessionItem = new MenuItem("复制会话");
        copySessionItem.setOnAction(e -> handleConnect(config));
        tabContextMenu.getItems().add(copySessionItem);
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

        // 断开连接时关闭标签
        terminalPane.setOnDisconnect(() -> {
            Platform.runLater(() -> terminalTabPane.getTabs().remove(tab));
        });

        doConnect(terminalPane, config);
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
        new Thread(() -> {
            try {
                terminalPane.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
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

        ConnectTypeDialog typeDialog = new ConnectTypeDialog(stage);
        ConnectType type = typeDialog.showAndWait();
        if (type == null) return;

        ConnectionConfigDialog configDialog = new ConnectionConfigDialog(stage, type);
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

        ConnectionConfigDialog dialog = new ConnectionConfigDialog(stage, existingConfig.getType(), existingConfig);
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
        Label hint = new Label("双击左侧SSH连接以打开终端");
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