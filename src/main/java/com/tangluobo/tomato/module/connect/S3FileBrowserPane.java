package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * S3/OSS文件浏览器面板
 * 支持浏览Bucket列表、进入Bucket浏览文件/目录
 * 支持S3（AWS S3/MinIO）和阿里云OSS两种连接类型
 */
public class S3FileBrowserPane extends BorderPane {

    private final ConnectionConfig config;
    private final boolean isAliyunOSS;

    // 状态栏组件
    private Circle statusDot;
    private Label stateLabel;
    private Label connLabel;

    // 路径导航
    private HBox pathBar;
    private Label currentPathLabel;
    private Button refreshBtn;
    private Button upBtn;
    private Button createBucketBtn;

    // 文件列表
    private TableView<FileItem> fileTable;
    private ObservableList<FileItem> fileData = FXCollections.observableArrayList();

    // 当前浏览状态
    private String currentBucket = null;
    private String currentPrefix = "";
    private final List<String> pathHistory = new ArrayList<>();

    // 图标
    private Image folderIcon;
    private Image bucketIcon;
    private Image fileIcon;

    public S3FileBrowserPane(ConnectionConfig config) {
        this.config = config;
        this.isAliyunOSS = config.getType() == ConnectType.ALIYUN_OSS;

        loadIcons();
        initializeUI();
        loadBuckets();
    }

    private void loadIcons() {
        try { folderIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png")); } catch (Exception e) { folderIcon = null; }
        try { bucketIcon = new Image(getClass().getResourceAsStream(isAliyunOSS ? "/images/connect/aliyun_oss.png" : "/images/connect/s3.png")); } catch (Exception e) { bucketIcon = null; }
        // 文件图标使用代码生成
        javafx.scene.shape.Rectangle rect = new javafx.scene.shape.Rectangle(10, 12);
        rect.setFill(Color.valueOf("#4FC3F7"));
        rect.setArcWidth(2);
        rect.setArcHeight(2);
        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        fileIcon = rect.snapshot(sp, null);
    }

    private void initializeUI() {
        // 顶部：路径导航栏
        pathBar = new HBox(8);
        pathBar.setAlignment(Pos.CENTER_LEFT);
        pathBar.setPadding(new Insets(6, 10, 6, 10));
        pathBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;");

        statusDot = new Circle(5);
        statusDot.setFill(Color.GRAY);
        pathBar.getChildren().add(statusDot);

        stateLabel = new Label("连接中...");
        stateLabel.setStyle("-fx-font-size: 12px;");
        pathBar.getChildren().add(stateLabel);

        Label sep1 = new Label("|");
        sep1.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        pathBar.getChildren().add(sep1);

        connLabel = new Label(config.getName() + " (" + (config.getEndpoint() != null ? config.getEndpoint() : config.getRegion()) + ")");
        connLabel.setStyle("-fx-font-size: 11px;");
        pathBar.getChildren().add(connLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        pathBar.getChildren().add(spacer);

        upBtn = new Button("↑ 上级");
        upBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        upBtn.setOnAction(e -> navigateUp());
        pathBar.getChildren().add(upBtn);

        refreshBtn = new Button("⟳ 刷新");
        refreshBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        refreshBtn.setOnAction(e -> refresh());
        pathBar.getChildren().add(refreshBtn);

        createBucketBtn = new Button("+ 新建Bucket");
        createBucketBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-background-radius: 4;");
        createBucketBtn.setOnAction(e -> handleCreateBucket());
        pathBar.getChildren().add(createBucketBtn);

        setTop(pathBar);

        // 中间：文件列表表格
        fileTable = new TableView<>();
        fileTable.setItems(fileData);
        fileTable.setStyle("-fx-font-size: 12px;");
        fileTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    FileItem item = row.getItem();
                    handleDoubleClick(item);
                }
            });
            return row;
        });

        // 名称列
        TableColumn<FileItem, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getDisplayName()));
        nameCol.setCellFactory(col -> new TableCell<FileItem, String>() {
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(name);
                    FileItem item = getTableView().getItems().get(getIndex());
                    ImageView iv = new ImageView();
                    iv.setFitWidth(16);
                    iv.setFitHeight(16);
                    if (item.isDirectory()) {
                        if (item.isBucket()) {
                            iv.setImage(bucketIcon);
                        } else {
                            iv.setImage(folderIcon);
                        }
                    } else {
                        iv.setImage(fileIcon);
                    }
                    if (iv.getImage() != null) setGraphic(iv);
                }
            }
        });
        nameCol.setPrefWidth(300);

        // 大小列
        TableColumn<FileItem, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getFormattedSize()));
        sizeCol.setPrefWidth(100);

        // 修改时间列
        TableColumn<FileItem, String> modifiedCol = new TableColumn<>("修改时间");
        modifiedCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getLastModifiedDisplay()));
        modifiedCol.setPrefWidth(180);

        // 类型列
        TableColumn<FileItem, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isDirectory() ? (data.getValue().isBucket() ? "Bucket" : "目录") : "文件"));
        typeCol.setPrefWidth(80);

        fileTable.getColumns().addAll(nameCol, sizeCol, modifiedCol, typeCol);

        // 右键菜单
        fileTable.setContextMenu(createContextMenu());

        VBox centerBox = new VBox();
        // 当前路径标签
        currentPathLabel = new Label("/");
        currentPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666; -fx-padding: 4 10;");
        currentPathLabel.setPadding(new Insets(4, 10, 4, 10));
        centerBox.getChildren().addAll(currentPathLabel, fileTable);
        VBox.setVgrow(fileTable, Priority.ALWAYS);

        setCenter(centerBox);
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem("打开");
        openItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) handleDoubleClick(selected);
        });

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> handleDelete());

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refresh());

        menu.getItems().addAll(openItem, new SeparatorMenuItem(), deleteItem, new SeparatorMenuItem(), refreshItem);
        return menu;
    }

    /**
     * 加载Bucket列表
     */
    private void loadBuckets() {
        new Thread(() -> {
            try {
                List<String> buckets;
                if (isAliyunOSS) {
                    buckets = OssService.listBuckets(config);
                } else {
                    buckets = S3Service.listBuckets(config);
                }

                Platform.runLater(() -> {
                    statusDot.setFill(Color.GREEN);
                    stateLabel.setText("已连接");
                    currentBucket = null;
                    currentPrefix = "";
                    pathHistory.clear();
                    updatePathLabel();

                    fileData.clear();
                    for (String bucketName : buckets) {
                        FileItem item = new FileItem();
                        item.setName(bucketName);
                        item.setKey(bucketName);
                        item.setDirectory(true);
                        item.setBucket(true);
                        fileData.add(item);
                    }

                    // Bucket层级不显示上级和新建Bucket按钮
                    upBtn.setDisable(true);
                    createBucketBtn.setVisible(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusDot.setFill(Color.RED);
                    stateLabel.setText("连接失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到 " + config.getName() + ": " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "S3-LoadBuckets").start();
    }

    /**
     * 加载Bucket中的对象列表
     */
    private void loadObjects(String bucketName, String prefix) {
        new Thread(() -> {
            try {
                List<?> objects;
                if (isAliyunOSS) {
                    objects = OssService.listObjects(config, bucketName, prefix);
                } else {
                    objects = S3Service.listObjects(config, bucketName, prefix);
                }

                Platform.runLater(() -> {
                    currentBucket = bucketName;
                    currentPrefix = prefix != null ? prefix : "";
                    updatePathLabel();

                    fileData.clear();
                    for (Object obj : objects) {
                        FileItem item = new FileItem();
                        if (isAliyunOSS) {
                            OssService.OssObjectInfo ossObj = (OssService.OssObjectInfo) obj;
                            item.setName(ossObj.getDisplayName());
                            item.setKey(ossObj.getKey());
                            item.setDirectory(ossObj.isDirectory());
                            item.setSize(ossObj.getSize());
                            item.setLastModified(ossObj.getLastModified() != null ? ossObj.getLastModified().toString() : "");
                            item.setBucket(false);
                        } else {
                            S3Service.S3ObjectInfo s3Obj = (S3Service.S3ObjectInfo) obj;
                            item.setName(s3Obj.getDisplayName());
                            item.setKey(s3Obj.getKey());
                            item.setDirectory(s3Obj.isDirectory());
                            item.setSize(s3Obj.getSize());
                            item.setLastModified(s3Obj.getLastModified() != null ? s3Obj.getLastModified().toString() : "");
                            item.setBucket(false);
                        }
                        fileData.add(item);
                    }

                    upBtn.setDisable(false);
                    createBucketBtn.setVisible(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载文件列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "S3-LoadObjects").start();
    }

    /**
     * 处理双击
     */
    private void handleDoubleClick(FileItem item) {
        if (item.isDirectory()) {
            if (item.isBucket()) {
                // 双击Bucket：进入根目录
                pathHistory.add("/");
                loadObjects(item.getName(), "");
            } else {
                // 双击目录：进入子目录
                pathHistory.add(currentPrefix);
                loadObjects(currentBucket, item.getKey());
            }
        }
    }

    /**
     * 返回上级目录
     */
    private void navigateUp() {
        if (currentBucket == null) return;

        if (currentPrefix == null || currentPrefix.isEmpty()) {
            // 当前在Bucket根目录，返回Bucket列表
            pathHistory.clear();
            loadBuckets();
        } else {
            // 返回上级目录
            String parentPrefix = getParentPrefix(currentPrefix);
            loadObjects(currentBucket, parentPrefix);
        }
    }

    private String getParentPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        String trimmed = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0) return "";
        return trimmed.substring(0, lastSlash + 1);
    }

    /**
     * 刷新当前视图
     */
    public void refresh() {
        if (currentBucket == null) {
            loadBuckets();
        } else {
            loadObjects(currentBucket, currentPrefix);
        }
    }

    /**
     * 更新路径标签
     */
    private void updatePathLabel() {
        if (currentBucket == null) {
            currentPathLabel.setText("/");
        } else {
            String path = "/" + currentBucket + "/" + (currentPrefix != null ? currentPrefix : "");
            currentPathLabel.setText(path);
        }
    }

    /**
     * 新建Bucket
     */
    private void handleCreateBucket() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建Bucket");
        dialog.setHeaderText(null);
        dialog.setContentText("Bucket名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String bucketName = name.trim();

            new Thread(() -> {
                try {
                    if (isAliyunOSS) {
                        OssService.createBucket(config, bucketName);
                    } else {
                        S3Service.createBucket(config, bucketName);
                    }
                    Platform.runLater(() -> {
                        loadBuckets();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("创建失败");
                        alert.setHeaderText(null);
                        alert.setContentText("创建Bucket失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "S3-CreateBucket").start();
        });
    }

    /**
     * 删除选中的对象或Bucket
     */
    private void handleDelete() {
        FileItem selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除确认");
        confirm.setHeaderText(null);
        if (selected.isBucket()) {
            confirm.setContentText("确定要删除Bucket \"" + selected.getName() + "\" 吗？Bucket必须为空才能删除。");
        } else {
            confirm.setContentText("确定要删除文件 \"" + selected.getName() + "\" 吗？");
        }

        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;

            new Thread(() -> {
                try {
                    if (selected.isBucket()) {
                        // 删除空Bucket需要客户端API支持，这里暂时只删除对象
                        if (isAliyunOSS) {
                            // 阿里云OSS没有直接删Bucket的简单方法
                            throw new Exception("请通过管理控制台删除Bucket");
                        } else {
                            // AWS S3删除空Bucket
                            software.amazon.awssdk.services.s3.S3Client s3Client = S3Service.createClient(config);
                            try {
                                s3Client.deleteBucket(software.amazon.awssdk.services.s3.model.DeleteBucketRequest.builder()
                                        .bucket(selected.getName()).build());
                            } finally {
                                s3Client.close();
                            }
                        }
                    } else {
                        if (isAliyunOSS) {
                            OssService.deleteObject(config, currentBucket, selected.getKey());
                        } else {
                            S3Service.deleteObject(config, currentBucket, selected.getKey());
                        }
                    }
                    Platform.runLater(() -> refresh());
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("删除失败");
                        alert.setHeaderText(null);
                        alert.setContentText(e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "S3-Delete").start();
        });
    }

    /**
     * 文件项数据模型
     */
    public static class FileItem {
        private String name;
        private String key;
        private boolean isDirectory;
        private boolean isBucket;
        private long size;
        private String lastModified;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public boolean isDirectory() { return isDirectory; }
        public void setDirectory(boolean directory) { this.isDirectory = directory; }

        public boolean isBucket() { return isBucket; }
        public void setBucket(boolean bucket) { this.isBucket = bucket; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public String getLastModified() { return lastModified; }
        public void setLastModified(String lastModified) { this.lastModified = lastModified; }

        public String getDisplayName() { return name; }

        public String getFormattedSize() {
            if (isDirectory) return "";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return (size / 1024) + " KB";
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }

        public String getLastModifiedDisplay() {
            if (lastModified == null || lastModified.isEmpty()) return "";
            // 简化时间显示
            try {
                if (lastModified.length() > 19) {
                    return lastModified.substring(0, 19);
                }
                return lastModified;
            } catch (Exception e) {
                return lastModified;
            }
        }
    }
}
