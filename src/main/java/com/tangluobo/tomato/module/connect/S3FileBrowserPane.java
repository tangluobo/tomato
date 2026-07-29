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
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * S3/OSS文件浏览器面板
 * 支持浏览Bucket列表、进入Bucket浏览文件/目录
 * 支持S3（AWS S3/MinIO）和阿里云OSS两种连接类型
 * 支持图标视图和列表视图两种模式
 * 支持图片文件预览
 */
public class S3FileBrowserPane extends BorderPane {

    private final ConnectionConfig config;
    private final boolean isAliyunOSS;

    // 图片扩展名集合
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>();
    static {
        IMAGE_EXTENSIONS.add("jpg"); IMAGE_EXTENSIONS.add("jpeg");
        IMAGE_EXTENSIONS.add("png"); IMAGE_EXTENSIONS.add("gif");
        IMAGE_EXTENSIONS.add("bmp"); IMAGE_EXTENSIONS.add("webp");
        IMAGE_EXTENSIONS.add("svg"); IMAGE_EXTENSIONS.add("ico");
        IMAGE_EXTENSIONS.add("tiff"); IMAGE_EXTENSIONS.add("tif");
    }

    // 视图模式
    private enum ViewMode { ICON, LIST }
    private ViewMode currentViewMode = ViewMode.ICON;

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
    private ToggleButton iconViewBtn;
    private ToggleButton listViewBtn;

    // 列表视图
    private TableView<FileItem> fileTable;

    // 图标视图
    private ScrollPane iconScrollPane;
    private FlowPane iconFlowPane;

    // 数据
    private ObservableList<FileItem> fileData = FXCollections.observableArrayList();

    // 当前浏览状态
    private String currentBucket = null;
    private String currentPrefix = "";
    private final List<String> pathHistory = new ArrayList<>();

    // 选中状态
    private FileItem selectedItem = null;

    // 图标
    private Image folderIcon;
    private Image folderLargeIcon;
    private Image bucketIcon;
    private Image bucketLargeIcon;
    private Image fileIcon;
    private Image fileLargeIcon;
    private Image imageFileIcon;
    private Image imageFileLargeIcon;

    public S3FileBrowserPane(ConnectionConfig config) {
        this.config = config;
        this.isAliyunOSS = config.getType() == ConnectType.ALIYUN_OSS;

        loadIcons();
        initializeUI();
        switchViewMode(currentViewMode);
        loadBuckets();
    }

    private void loadIcons() {
        try { folderIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png")); } catch (Exception e) { folderIcon = null; }
        try { bucketIcon = new Image(getClass().getResourceAsStream(isAliyunOSS ? "/images/connect/aliyun_oss.png" : "/images/connect/s3.png")); } catch (Exception e) { bucketIcon = null; }

        // 大图标版本（48x48）
        try { folderLargeIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png"), 48, 48, true, true); } catch (Exception e) { folderLargeIcon = null; }
        try { bucketLargeIcon = new Image(getClass().getResourceAsStream(isAliyunOSS ? "/images/connect/aliyun_oss.png" : "/images/connect/s3.png"), 48, 48, true, true); } catch (Exception e) { bucketLargeIcon = null; }

        // 文件图标
        fileIcon = createFileIcon(16);
        fileLargeIcon = createFileIcon(48);

        // 图片文件图标（带图片标识的文件图标）
        imageFileIcon = createImageFileIcon(16);
        imageFileLargeIcon = createImageFileIcon(48);
    }

    private Image createFileIcon(int size) {
        javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane();
        pane.setPrefSize(size, size);

        double s = size;
        javafx.scene.shape.Rectangle body = new javafx.scene.shape.Rectangle(s * 0.15, s * 0.05, s * 0.55, s * 0.9);
        body.setFill(Color.WHITE);
        body.setStroke(Color.valueOf("#90CAF9"));
        body.setStrokeWidth(Math.max(1, size * 0.04));
        body.setArcWidth(s * 0.06);
        body.setArcHeight(s * 0.06);

        javafx.scene.shape.Polygon ear = new javafx.scene.shape.Polygon();
        ear.getPoints().addAll(s * 0.55, s * 0.05, s * 0.55, s * 0.25, s * 0.85, s * 0.25);
        ear.setFill(Color.valueOf("#E3F2FD"));
        ear.setStroke(Color.valueOf("#90CAF9"));
        ear.setStrokeWidth(Math.max(1, size * 0.04));

        javafx.scene.shape.Line line1 = new javafx.scene.shape.Line(s * 0.25, s * 0.4, s * 0.6, s * 0.4);
        line1.setStroke(Color.valueOf("#BBDEFB"));
        line1.setStrokeWidth(Math.max(1, size * 0.03));
        javafx.scene.shape.Line line2 = new javafx.scene.shape.Line(s * 0.25, s * 0.55, s * 0.55, s * 0.55);
        line2.setStroke(Color.valueOf("#BBDEFB"));
        line2.setStrokeWidth(Math.max(1, size * 0.03));
        javafx.scene.shape.Line line3 = new javafx.scene.shape.Line(s * 0.25, s * 0.7, s * 0.5, s * 0.7);
        line3.setStroke(Color.valueOf("#BBDEFB"));
        line3.setStrokeWidth(Math.max(1, size * 0.03));

        pane.getChildren().addAll(body, ear, line1, line2, line3);

        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return pane.snapshot(sp, null);
    }

    private Image createImageFileIcon(int size) {
        javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane();
        pane.setPrefSize(size, size);

        double s = size;
        // 文件主体
        javafx.scene.shape.Rectangle body = new javafx.scene.shape.Rectangle(s * 0.15, s * 0.05, s * 0.55, s * 0.9);
        body.setFill(Color.WHITE);
        body.setStroke(Color.valueOf("#4CAF50"));
        body.setStrokeWidth(Math.max(1, size * 0.04));
        body.setArcWidth(s * 0.06);
        body.setArcHeight(s * 0.06);

        // 折角
        javafx.scene.shape.Polygon ear = new javafx.scene.shape.Polygon();
        ear.getPoints().addAll(s * 0.55, s * 0.05, s * 0.55, s * 0.25, s * 0.85, s * 0.25);
        ear.setFill(Color.valueOf("#C8E6C9"));
        ear.setStroke(Color.valueOf("#4CAF50"));
        ear.setStrokeWidth(Math.max(1, size * 0.04));

        // 图片标识：小山和太阳
        // 太阳
        javafx.scene.shape.Circle sun = new javafx.scene.shape.Circle(s * 0.32, s * 0.35, s * 0.06);
        sun.setFill(Color.valueOf("#FFC107"));
        // 山
        javafx.scene.shape.Polygon mountain = new javafx.scene.shape.Polygon();
        mountain.getPoints().addAll(
                s * 0.22, s * 0.7,
                s * 0.38, s * 0.42,
                s * 0.54, s * 0.7
        );
        mountain.setFill(Color.valueOf("#66BB6A"));
        // 小山
        javafx.scene.shape.Polygon smallMountain = new javafx.scene.shape.Polygon();
        smallMountain.getPoints().addAll(
                s * 0.4, s * 0.7,
                s * 0.52, s * 0.5,
                s * 0.62, s * 0.7
        );
        smallMountain.setFill(Color.valueOf("#81C784"));

        pane.getChildren().addAll(body, ear, sun, mountain, smallMountain);

        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return pane.snapshot(sp, null);
    }

    /**
     * 判断文件名是否为图片
     */
    private boolean isImageFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private Image getIconForItem(FileItem item, boolean large) {
        if (item.isDirectory()) {
            if (item.isBucket()) {
                return large ? bucketLargeIcon : bucketIcon;
            }
            return large ? folderLargeIcon : folderIcon;
        }
        // 图片文件使用图片图标
        if (isImageFile(item.getDisplayName())) {
            return large ? imageFileLargeIcon : imageFileIcon;
        }
        return large ? fileLargeIcon : fileIcon;
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

        // 视图切换按钮
        ToggleGroup viewToggleGroup = new ToggleGroup();

        iconViewBtn = new ToggleButton("⊞");
        iconViewBtn.setTooltip(new Tooltip("图标视图"));
        iconViewBtn.setToggleGroup(viewToggleGroup);
        iconViewBtn.setSelected(true);
        iconViewBtn.setStyle("-fx-font-size: 14px; -fx-padding: 2 6; -fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
        iconViewBtn.setOnAction(e -> switchViewMode(ViewMode.ICON));

        listViewBtn = new ToggleButton("≡");
        listViewBtn.setTooltip(new Tooltip("列表视图"));
        listViewBtn.setToggleGroup(viewToggleGroup);
        listViewBtn.setSelected(false);
        listViewBtn.setStyle("-fx-font-size: 14px; -fx-padding: 2 6; -fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0;");
        listViewBtn.setOnAction(e -> switchViewMode(ViewMode.LIST));

        pathBar.getChildren().addAll(iconViewBtn, listViewBtn);

        Label sep2 = new Label("|");
        sep2.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        pathBar.getChildren().add(sep2);

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

        initListView();
        initIconView();

        currentPathLabel = new Label("/");
        currentPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        currentPathLabel.setPadding(new Insets(4, 10, 4, 10));
    }

    private void initListView() {
        fileTable = new TableView<>();
        fileTable.setItems(fileData);
        fileTable.setStyle("-fx-font-size: 12px;");
        fileTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    FileItem item = row.getItem();
                    handleDoubleClick(item);
                } else if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                    selectedItem = row.getItem();
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
                    iv.setImage(getIconForItem(item, false));
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
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isDirectory() ? (data.getValue().isBucket() ? "Bucket" : "目录") : (isImageFile(data.getValue().getDisplayName()) ? "图片" : "文件")));
        typeCol.setPrefWidth(80);

        fileTable.getColumns().addAll(nameCol, sizeCol, modifiedCol, typeCol);
        fileTable.setContextMenu(createContextMenu());
    }

    private void initIconView() {
        iconFlowPane = new FlowPane();
        iconFlowPane.setHgap(8);
        iconFlowPane.setVgap(8);
        iconFlowPane.setPadding(new Insets(12));
        iconFlowPane.setStyle("-fx-background-color: white;");

        iconScrollPane = new ScrollPane(iconFlowPane);
        iconScrollPane.setFitToWidth(true);
        iconScrollPane.setFitToHeight(true);
        iconScrollPane.setStyle("-fx-background-color: white;");
        iconScrollPane.setContextMenu(createContextMenu());

        iconFlowPane.setOnMouseClicked(e -> {
            if (e.getTarget() == iconFlowPane) {
                clearIconSelection();
                selectedItem = null;
            }
        });
    }

    private void switchViewMode(ViewMode mode) {
        currentViewMode = mode;

        VBox centerBox = new VBox();
        centerBox.getChildren().add(currentPathLabel);

        if (mode == ViewMode.ICON) {
            rebuildIconView();
            centerBox.getChildren().add(iconScrollPane);
            VBox.setVgrow(iconScrollPane, Priority.ALWAYS);
        } else {
            centerBox.getChildren().add(fileTable);
            VBox.setVgrow(fileTable, Priority.ALWAYS);
        }

        setCenter(centerBox);
    }

    private void rebuildIconView() {
        iconFlowPane.getChildren().clear();

        for (FileItem item : fileData) {
            VBox iconBox = createIconBox(item);
            iconFlowPane.getChildren().add(iconBox);
        }
    }

    private VBox createIconBox(FileItem item) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefWidth(90);
        box.setPadding(new Insets(6, 4, 6, 4));
        box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand;");

        // 图标
        ImageView iconView = new ImageView();
        iconView.setImage(getIconForItem(item, true));
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        iconView.setPreserveRatio(true);
        iconView.setSmooth(true);
        box.getChildren().add(iconView);

        // 名称
        Label nameLabel = new Label(item.getDisplayName());
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #333; -fx-alignment: CENTER;");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(82);
        nameLabel.setAlignment(Pos.CENTER);
        box.getChildren().add(nameLabel);

        // 鼠标事件
        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                clearIconSelection();
                selectIconBox(box, item);
                selectedItem = item;

                if (e.getClickCount() == 2) {
                    handleDoubleClick(item);
                }
            }
        });

        box.setOnMouseEntered(e -> {
            if (box.getUserData() != "selected") {
                box.setStyle("-fx-background-color: #f0f7ff; -fx-background-radius: 6; -fx-cursor: hand;");
            }
        });
        box.setOnMouseExited(e -> {
            if (box.getUserData() != "selected") {
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand;");
            }
        });

        return box;
    }

    private void selectIconBox(VBox box, FileItem item) {
        box.setUserData("selected");
        box.setStyle("-fx-background-color: #cce5ff; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: #3399ff; -fx-border-width: 1; -fx-border-radius: 6;");
    }

    private void clearIconSelection() {
        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                box.setUserData(null);
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand;");
            }
        }
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem("打开");
        openItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null) handleDoubleClick(selected);
        });

        // 预览菜单项（仅图片文件显示）
        MenuItem previewItem = new MenuItem("预览图片");
        previewItem.setOnAction(e -> handlePreview());

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> handleDelete());

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refresh());

        Menu viewMenu = new Menu("视图");
        MenuItem iconViewItem = new MenuItem("图标视图");
        iconViewItem.setOnAction(e -> switchViewMode(ViewMode.ICON));
        MenuItem listViewItem = new MenuItem("列表视图");
        listViewItem.setOnAction(e -> switchViewMode(ViewMode.LIST));
        viewMenu.getItems().addAll(iconViewItem, listViewItem);

        menu.getItems().addAll(openItem, previewItem, new SeparatorMenuItem(), deleteItem, new SeparatorMenuItem(), viewMenu, new SeparatorMenuItem(), refreshItem);

        // 右键菜单显示时动态控制预览项可见性
        menu.setOnShowing(e -> {
            FileItem selected = getSelectedItem();
            previewItem.setVisible(selected != null && !selected.isDirectory() && isImageFile(selected.getDisplayName()));
        });

        return menu;
    }

    private FileItem getSelectedItem() {
        if (currentViewMode == ViewMode.LIST) {
            return fileTable.getSelectionModel().getSelectedItem();
        }
        return selectedItem;
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

                    upBtn.setDisable(true);
                    createBucketBtn.setVisible(true);

                    if (currentViewMode == ViewMode.ICON) {
                        rebuildIconView();
                    }
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
                    selectedItem = null;

                    if (currentViewMode == ViewMode.ICON) {
                        rebuildIconView();
                    }
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
     * 处理双击：目录进入，图片预览
     */
    private void handleDoubleClick(FileItem item) {
        if (item == null) return;

        if (item.isDirectory()) {
            if (item.isBucket()) {
                pathHistory.add("/");
                loadObjects(item.getName(), "");
            } else {
                pathHistory.add(currentPrefix);
                loadObjects(currentBucket, item.getKey());
            }
        } else if (isImageFile(item.getDisplayName())) {
            // 双击图片文件 -> 预览
            handlePreview(item);
        }
    }

    /**
     * 预览图片（使用当前选中项）
     */
    private void handlePreview() {
        FileItem selected = getSelectedItem();
        if (selected == null || selected.isDirectory() || !isImageFile(selected.getDisplayName())) return;
        handlePreview(selected);
    }

    /**
     * 预览图片：从S3/OSS下载并显示
     */
    private void handlePreview(FileItem item) {
        if (currentBucket == null) return;

        // 加载提示
        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(60, 60);

        Dialog<Void> previewDialog = new Dialog<>();
        previewDialog.setTitle("图片预览 - " + item.getDisplayName());
        previewDialog.setHeaderText(null);
        previewDialog.getDialogPane().setMinSize(600, 500);
        previewDialog.getDialogPane().setPrefSize(800, 600);
        previewDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        // 先显示加载动画
        StackPane loadingPane = new StackPane(loadingIndicator);
        loadingPane.setPrefSize(780, 520);
        loadingPane.setStyle("-fx-background-color: #2b2b2b;");
        previewDialog.getDialogPane().setContent(loadingPane);

        // 异步加载图片
        new Thread(() -> {
            try {
                InputStream is;
                if (isAliyunOSS) {
                    is = OssService.getObjectStream(config, currentBucket, item.getKey());
                } else {
                    is = S3Service.getObjectStream(config, currentBucket, item.getKey());
                }

                // 读取到内存（InputStream需要在后台线程读取完）
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                is.close();
                byte[] imageBytes = baos.toByteArray();

                Platform.runLater(() -> {
                    try {
                        Image image = new Image(new java.io.ByteArrayInputStream(imageBytes));
                        if (image.isError()) {
                            throw new Exception("图片格式不支持或文件已损坏");
                        }

                        showImageInPreviewDialog(previewDialog, image, item);

                    } catch (Exception e) {
                        Label errorLabel = new Label("图片加载失败: " + e.getMessage());
                        errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
                        StackPane errorPane = new StackPane(errorLabel);
                        errorPane.setPrefSize(780, 520);
                        errorPane.setStyle("-fx-background-color: #2b2b2b;");
                        previewDialog.getDialogPane().setContent(errorPane);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Label errorLabel = new Label("图片下载失败: " + e.getMessage());
                    errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
                    StackPane errorPane = new StackPane(errorLabel);
                    errorPane.setPrefSize(780, 520);
                    errorPane.setStyle("-fx-background-color: #2b2b2b;");
                    previewDialog.getDialogPane().setContent(errorPane);
                });
            }
        }, "S3-LoadImage").start();

        previewDialog.show();
    }

    /**
     * 在预览对话框中展示图片（支持缩放和拖拽）
     */
    private void showImageInPreviewDialog(Dialog<Void> dialog, Image image, FileItem item) {
        double imgWidth = image.getWidth();
        double imgHeight = image.getHeight();

        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        // 检查是否需要生成缩略图用于图标视图
        if (currentViewMode == ViewMode.ICON && imgWidth > 0 && imgHeight > 0) {
            updateIconBoxWithThumbnail(item, image);
        }

        // 图片容器（可拖拽）
        StackPane imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: #2b2b2b;");

        // 初始适配：适应窗口大小
        double dialogContentWidth = dialog.getDialogPane().getWidth() > 0 ? dialog.getDialogPane().getWidth() : 780;
        double dialogContentHeight = dialog.getDialogPane().getHeight() > 0 ? dialog.getDialogPane().getHeight() : 520;

        double fitWidth = Math.min(imgWidth, dialogContentWidth - 20);
        double fitHeight = Math.min(imgHeight, dialogContentHeight - 60);
        double scale = Math.min(fitWidth / imgWidth, fitHeight / imgHeight);
        if (scale < 1) {
            imageView.setFitWidth(imgWidth * scale);
            imageView.setFitHeight(imgHeight * scale);
        }

        // 滚轮缩放
        imageContainer.setOnScroll((ScrollEvent event) -> {
            double zoomFactor = 1.05;
            double delta = event.getDeltaY();
            if (delta < 0) {
                zoomFactor = 1.0 / zoomFactor;
            }

            double currentFitW = imageView.getFitWidth() > 0 ? imageView.getFitWidth() : imgWidth;
            double currentFitH = imageView.getFitHeight() > 0 ? imageView.getFitHeight() : imgHeight;

            double newW = currentFitW * zoomFactor;
            double newH = currentFitH * zoomFactor;

            // 限制缩放范围
            double minSize = 50;
            double maxSize = imgWidth * 10;
            if (newW < minSize || newH < minSize || newW > maxSize || newH > maxSize) return;

            imageView.setFitWidth(newW);
            imageView.setFitHeight(newH);
            event.consume();
        });

        // 拖拽移动
        final double[] dragStart = new double[2];
        final double[] translateStart = new double[2];
        imageContainer.setOnMousePressed(e -> {
            dragStart[0] = e.getSceneX();
            dragStart[1] = e.getSceneY();
            translateStart[0] = imageView.getTranslateX();
            translateStart[1] = imageView.getTranslateY();
        });
        imageContainer.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - dragStart[0];
            double dy = e.getSceneY() - dragStart[1];
            imageView.setTranslateX(translateStart[0] + dx);
            imageView.setTranslateY(translateStart[1] + dy);
        });

        // 工具栏
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #3c3c3c;");

        Label infoLabel = new Label(String.format("%s  |  %dx%d  |  %s",
                item.getDisplayName(), (int) imgWidth, (int) imgHeight, item.getFormattedSize()));
        infoLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px;");
        toolbar.getChildren().add(infoLabel);

        Region toolSpacer = new Region();
        HBox.setHgrow(toolSpacer, Priority.ALWAYS);
        toolbar.getChildren().add(toolSpacer);

        // 适配窗口按钮
        Button fitBtn = new Button("适配窗口");
        fitBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        fitBtn.setOnAction(e -> {
            double cw = imageContainer.getWidth();
            double ch = imageContainer.getHeight();
            if (cw <= 0 || ch <= 0) return;
            double s = Math.min((cw - 20) / imgWidth, (ch - 20) / imgHeight);
            if (s > 1) s = 1; // 不放大超过原始大小
            imageView.setFitWidth(imgWidth * s);
            imageView.setFitHeight(imgHeight * s);
            imageView.setTranslateX(0);
            imageView.setTranslateY(0);
        });
        toolbar.getChildren().add(fitBtn);

        // 原始大小按钮
        Button originalBtn = new Button("1:1");
        originalBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        originalBtn.setOnAction(e -> {
            imageView.setFitWidth(imgWidth);
            imageView.setFitHeight(imgHeight);
            imageView.setTranslateX(0);
            imageView.setTranslateY(0);
        });
        toolbar.getChildren().add(originalBtn);

        VBox content = new VBox();
        content.getChildren().addAll(toolbar, imageContainer);
        VBox.setVgrow(imageContainer, Priority.ALWAYS);

        dialog.getDialogPane().setContent(content);
    }

    /**
     * 更新图标视图中图片文件的缩略图
     */
    private void updateIconBoxWithThumbnail(FileItem item, Image fullImage) {
        // 生成缩略图
        double thumbSize = 48;
        double w = fullImage.getWidth();
        double h = fullImage.getHeight();
        if (w <= 0 || h <= 0) return;

        double scale = Math.min(thumbSize / w, thumbSize / h);
        Image thumbnail = new Image(fullImage.getUrl(), w * scale, h * scale, true, true);

        // 在图标视图中找到对应的VBox并替换图标
        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                // 找到对应的item（通过名称匹配）
                if (box.getChildren().size() >= 2
                        && box.getChildren().get(1) instanceof Label label
                        && item.getDisplayName().equals(label.getText())) {

                    // 替换图标为缩略图
                    if (box.getChildren().get(0) instanceof ImageView iconView) {
                        iconView.setImage(thumbnail);
                        // 添加圆角边框效果
                        iconView.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-border-radius: 4;");
                    }
                    break;
                }
            }
        }
    }

    /**
     * 返回上级目录
     */
    private void navigateUp() {
        if (currentBucket == null) return;

        if (currentPrefix == null || currentPrefix.isEmpty()) {
            pathHistory.clear();
            loadBuckets();
        } else {
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

    private void updatePathLabel() {
        if (currentBucket == null) {
            currentPathLabel.setText("/");
        } else {
            String path = "/" + currentBucket + "/" + (currentPrefix != null ? currentPrefix : "");
            currentPathLabel.setText(path);
        }
    }

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
                    Platform.runLater(() -> loadBuckets());
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

    private void handleDelete() {
        FileItem selected = getSelectedItem();
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
                        if (isAliyunOSS) {
                            throw new Exception("请通过管理控制台删除Bucket");
                        } else {
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
