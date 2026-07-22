package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.Module;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.geometry.Insets;
import javafx.stage.Stage;
import javafx.embed.swing.SwingFXUtils;

import javax.swing.filechooser.FileSystemView;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class ConnectModule implements Module {
    private TreeView<String> treeView;
    private TreeItem<String> root;
    private List<ConnectionConfig> connections;
    private Map<TreeItem<String>, ConnectionConfig> itemConfigMap;
    private Image folderIcon;

    @Override
    public String getName() {
        return "连接";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
        folderIcon = getSystemFolderIcon();

        TextField searchField = new TextField();
        searchField.setPromptText("搜索");
        searchField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 6 10; -fx-font-size: 13px; -fx-border-color: transparent;");
        VBox.setMargin(searchField, new Insets(10, 10, 10, 10));

        treeView = new TreeView<>();
        treeView.setStyle("-fx-background-color: transparent;");
        root = new TreeItem<>("连接");
        root.setExpanded(true);
        treeView.setRoot(root);
        treeView.setShowRoot(false);

        itemConfigMap = new HashMap<>();
        connections = ConfigManager.loadConnections();
        loadTree();

        setupContextMenu();

        sidebarContainer.getChildren().addAll(searchField, treeView);
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

    private TreeItem<String> createTreeItem(ConnectionConfig config) {
        String displayName = config.getName();
        if (config.getType() != null) {
            displayName = config.getType().getDisplayName() + ": " + config.getName();
        }
        TreeItem<String> item = new TreeItem<>(displayName);
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

    private void setupContextMenu() {
        treeView.setOnContextMenuRequested(event -> {
            TreeItem<String> tempItem = treeView.getSelectionModel().getSelectedItem();
            final TreeItem<String> selectedItem = tempItem == null ? root : tempItem;

            ContextMenu contextMenu = new ContextMenu();

            MenuItem addFolder = new MenuItem("新建目录");
            addFolder.setOnAction(e -> handleAddFolder(selectedItem));

            MenuItem addConnection = new MenuItem("新建连接");
            addConnection.setOnAction(e -> handleAddConnection(selectedItem));

            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> handleDelete(selectedItem));

            if (selectedItem != root) {
                contextMenu.getItems().addAll(addFolder, addConnection, new SeparatorMenuItem(), deleteItem);
            } else {
                contextMenu.getItems().addAll(addFolder, addConnection);
            }

            contextMenu.show(treeView, event.getScreenX(), event.getScreenY());
        });

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

    private void handleDelete(TreeItem<String> item) {
        ConnectionConfig config = itemConfigMap.get(item);
        if (config != null) {
            removeConfigAndChildren(config.getId());
            ConfigManager.saveConnections(connections);
            loadTree();
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

    private void handleConnect(ConnectionConfig config) {
        Stage stage = getStage();
        if (stage == null) return;

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("连接信息");
        alert.setHeaderText("连接类型：" + config.getType().getDisplayName());
        alert.setContentText("名称：" + config.getName() + "\n主机：" + config.getHost() + "\n端口：" + config.getPort() + "\n用户名：" + config.getUsername());
        alert.showAndWait();
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

    @Override
    public void loadContent(VBox contentArea) {
        contentArea.getChildren().clear();

        Label title = new Label("连接管理");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        VBox form = new VBox(15);
        form.setPadding(new Insets(20, 0, 0, 0));

        TextField hostField = new TextField();
        hostField.setPromptText("主机地址");
        hostField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 8 12;");

        TextField portField = new TextField();
        portField.setPromptText("端口");
        portField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 8 12;");

        TextField userField = new TextField();
        userField.setPromptText("用户名");
        userField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 8 12;");

        Button connectBtn = new Button("连接");
        connectBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 100px;");

        form.getChildren().addAll(hostField, portField, userField, connectBtn);
        contentArea.getChildren().addAll(title, form);
    }
}