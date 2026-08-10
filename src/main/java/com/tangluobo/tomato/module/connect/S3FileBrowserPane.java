package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.connect.service.OssService;
import com.tangluobo.tomato.module.connect.service.S3Service;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.SnapshotParameters;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.Scene;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
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

    // Markdown 扩展名集合
    private static final Set<String> MARKDOWN_EXTENSIONS = new HashSet<>();
    static {
        MARKDOWN_EXTENSIONS.add("md");
        MARKDOWN_EXTENSIONS.add("markdown");
        MARKDOWN_EXTENSIONS.add("mdown");
        MARKDOWN_EXTENSIONS.add("mkd");
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
    private TextField currentPathField;
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

    // 编辑器 Tab 页（中心区域：文件浏览 + 多个 markdown 编辑器）
    private TabPane editorTabPane;
    private Tab browseTab;

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

    /**
     * 判断文件名是否为 Markdown 文件
     */
    private boolean isMarkdownFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return MARKDOWN_EXTENSIONS.contains(ext);
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
        // 当前路径输入框（可编辑，回车跳转；顶到视图切换按钮，始终显示文本框样式）
        currentPathField = new TextField("/");
        currentPathField.setPrefHeight(25);
        currentPathField.setMinWidth(0);
        currentPathField.setPrefWidth(0);
        currentPathField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(currentPathField, Priority.ALWAYS);
        currentPathField.setStyle("-fx-font-size: 12px; -fx-text-fill: #333; -fx-background-color: white; -fx-background-insets: 0; -fx-background-radius: 0; -fx-padding: 2 6; -fx-border-color: #3399ff; -fx-border-width: 1; -fx-border-insets: 0; -fx-border-radius: 0;");
        currentPathField.setTooltip(new Tooltip("点击编辑路径，回车进入目录"));
        // 获得焦点：全选文本；失去焦点：还原当前路径
        currentPathField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                Platform.runLater(currentPathField::selectAll);
            } else {
                updatePathLabel();
            }
        });
        // 回车：跳转到输入路径
        currentPathField.setOnAction(e -> {
            String input = currentPathField.getText();
            pathBar.requestFocus(); // 转移焦点以触发失焦恢复显示
            navigateToPath(input);
        });

        // 顶部：路径导航栏
        pathBar = new HBox(8);
        pathBar.setAlignment(Pos.CENTER_LEFT);
        pathBar.setPadding(new Insets(6, 10, 6, 10));
        pathBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;");

        pathBar.getChildren().add(currentPathField);

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

        // 底部：状态栏（连接状态 + 主机信息）
        HBox statusBar = new HBox(8);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 10, 4, 10));
        statusBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;");

        statusDot = new Circle(5);
        statusDot.setFill(Color.GRAY);
        statusBar.getChildren().add(statusDot);

        stateLabel = new Label("连接中...");
        stateLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(stateLabel);

        Label sep1 = new Label("|");
        sep1.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        statusBar.getChildren().add(sep1);

        connLabel = new Label(config.getName() + " (" + (config.getEndpoint() != null ? config.getEndpoint() : config.getRegion()) + ")");
        connLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(connLabel);

        setBottom(statusBar);

        // 中心区域：TabPane（第一个 Tab 为文件浏览，后续为 markdown 编辑器）
        editorTabPane = new TabPane();
        editorTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        browseTab = new Tab("文件浏览");
        browseTab.setClosable(false);
        editorTabPane.getTabs().add(browseTab);
        setCenter(editorTabPane);

        initListView();
        initIconView();

        // Ctrl+V 粘贴上传（图片或文件）
        this.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.V) {
                // 焦点在文本输入框中时不拦截（让用户正常粘贴文本）
                if (getScene() != null && getScene().getFocusOwner() instanceof TextInputControl) {
                    return;
                }
                // 仅在浏览 Tab 激活时处理
                if (editorTabPane.getSelectionModel().getSelectedItem() != browseTab) {
                    return;
                }
                handlePasteFromClipboard();
                e.consume();
            }
        });
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
                } else if (event.getButton() == MouseButton.SECONDARY && !row.isEmpty()) {
                    fileTable.getSelectionModel().select(row.getItem());
                    selectedItem = row.getItem();
                }
            });
            // 拖拽下载：从列表行拖出 -> 下载到临时目录后放入剪贴板
            row.setOnDragDetected(event -> {
                if (row.isEmpty()) return;
                FileItem item = row.getItem();
                if (!item.isDirectory()) {
                    File tempFile = downloadToTemp(item);
                    if (tempFile != null) {
                        Dragboard db = row.startDragAndDrop(TransferMode.COPY);
                        ClipboardContent content = new ClipboardContent();
                        content.putFiles(Collections.singletonList(tempFile));
                        db.setContent(content);
                    }
                }
                event.consume();
            });
            // 拖拽上传：拖到行上 -> 接受文件
            row.setOnDragOver(event -> {
                if (currentBucket != null && event.getDragboard().hasFiles()) {
                    event.acceptTransferModes(TransferMode.COPY);
                }
                event.consume();
            });
            row.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (currentBucket != null && db.hasFiles()) {
                    uploadLocalFiles(db.getFiles());
                    success = true;
                }
                event.setDropCompleted(success);
                event.consume();
            });
            return row;
        });

        // 整个表格也支持拖拽上传
        fileTable.setOnDragOver(event -> {
            if (currentBucket != null && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        fileTable.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (currentBucket != null && db.hasFiles()) {
                uploadLocalFiles(db.getFiles());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
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
        iconFlowPane.setFocusTraversable(true);

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

        // 拖拽上传：拖到图标视图（FlowPane 和 ScrollPane 均支持）
        setupDragUpload(iconFlowPane);
        setupDragUpload(iconScrollPane);
    }

    /**
     * 为节点绑定拖拽上传：从本地文件系统拖入文件即上传到当前 bucket/prefix
     */
    private void setupDragUpload(javafx.scene.Node node) {
        node.setOnDragOver(event -> {
            if (currentBucket != null && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        node.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (currentBucket != null && db.hasFiles()) {
                uploadLocalFiles(db.getFiles());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void switchViewMode(ViewMode mode) {
        currentViewMode = mode;

        VBox centerBox = new VBox();

        if (mode == ViewMode.ICON) {
            rebuildIconView();
            centerBox.getChildren().add(iconScrollPane);
            VBox.setVgrow(iconScrollPane, Priority.ALWAYS);
        } else {
            centerBox.getChildren().add(fileTable);
            VBox.setVgrow(fileTable, Priority.ALWAYS);
        }

        // 切换浏览视图时回到浏览 Tab
        browseTab.setContent(centerBox);
        editorTabPane.getSelectionModel().select(browseTab);
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
            } else if (e.getButton() == MouseButton.SECONDARY) {
                clearIconSelection();
                selectIconBox(box, item);
                selectedItem = item;
            }
        });

        // 拖拽下载：从图标拖出 -> 下载到临时目录后放入剪贴板
        box.setOnDragDetected(e -> {
            if (!item.isDirectory()) {
                File tempFile = downloadToTemp(item);
                if (tempFile != null) {
                    Dragboard db = box.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(Collections.singletonList(tempFile));
                    db.setContent(content);
                }
            }
            e.consume();
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

        // 编辑菜单项（仅 markdown 文件显示）
        MenuItem editMdItem = new MenuItem("编辑 Markdown");
        editMdItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null) openMarkdownEditor(selected);
        });

        // 下载菜单项（仅文件可用）
        MenuItem downloadItem = new MenuItem("下载...");
        downloadItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null && !selected.isDirectory()) handleDownload(selected);
        });

        // 复制访问地址（仅文件可用，需配置访问URL）
        MenuItem copyUrlItem = new MenuItem("复制访问地址");
        copyUrlItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null && !selected.isDirectory()) handleCopyAccessUrl(selected);
        });

        // 创建目录（仅 Bucket 内可用）
        MenuItem mkdirItem = new MenuItem("新建目录");
        mkdirItem.setOnAction(e -> handleCreateDirectory());

        // 上传文件（仅 Bucket 内可用）
        MenuItem uploadItem = new MenuItem("上传文件...");
        uploadItem.setOnAction(e -> handleUploadFiles());

        // 粘贴上传（仅 Bucket 内可用，支持粘贴剪贴板中的图片或文件）
        MenuItem pasteUploadItem = new MenuItem("粘贴上传 (Ctrl+V)");
        pasteUploadItem.setOnAction(e -> handlePasteFromClipboard());

        // 创建文件（仅 Bucket 内可用）
        MenuItem createFileItem = new MenuItem("创建文件");
        createFileItem.setOnAction(e -> handleCreateFile());

        // 重命名菜单项（文件和目录可用，Bucket 不可用）
        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> handleRename());

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

        menu.getItems().addAll(openItem, previewItem, editMdItem, downloadItem, copyUrlItem, new SeparatorMenuItem(),
                mkdirItem, uploadItem, pasteUploadItem, createFileItem, renameItem, deleteItem, new SeparatorMenuItem(), viewMenu, new SeparatorMenuItem(), refreshItem);

        // 右键菜单显示时动态控制各项可见性
        menu.setOnShowing(e -> {
            FileItem selected = getSelectedItem();
            previewItem.setVisible(selected != null && !selected.isDirectory() && isImageFile(selected.getDisplayName()));
            editMdItem.setVisible(selected != null && !selected.isDirectory() && isMarkdownFile(selected.getDisplayName()));
            downloadItem.setVisible(selected != null && !selected.isDirectory());
            copyUrlItem.setVisible(selected != null && !selected.isDirectory());
            mkdirItem.setVisible(currentBucket != null);
            uploadItem.setVisible(currentBucket != null);
            pasteUploadItem.setVisible(currentBucket != null);
            createFileItem.setVisible(currentBucket != null);
            // 重命名：仅在 Bucket 内且选中了文件或目录（非 Bucket）时可用
            renameItem.setVisible(selected != null && currentBucket != null && !selected.isBucket());
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
                    createBucketBtn.setManaged(true);

                    if (currentViewMode == ViewMode.ICON) {
                        rebuildIconView();
                        loadThumbnailsForIconView();
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
                    createBucketBtn.setManaged(false);
                    selectedItem = null;

                    if (currentViewMode == ViewMode.ICON) {
                        rebuildIconView();
                        loadThumbnailsForIconView();
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
     * 处理双击：目录进入，图片预览，markdown 编辑
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
        } else if (isMarkdownFile(item.getDisplayName())) {
            // 双击 markdown 文件 -> 编辑器
            openMarkdownEditor(item);
        }
    }

    /**
     * 打开 Markdown 编辑器 Tab：异步下载内容后新建编辑器 Tab
     * 若该 key 已有打开的 Tab，则直接选中
     */
    private void openMarkdownEditor(FileItem item) {
        if (currentBucket == null) return;
        String fileKey = item.getKey();
        String fileName = item.getDisplayName();

        // 复用已打开的 Tab
        for (Tab tab : editorTabPane.getTabs()) {
            if (tab.getUserData() instanceof String tabKey && tabKey.equals(fileKey)) {
                editorTabPane.getSelectionModel().select(tab);
                return;
            }
        }

        // 占位 Tab，先显示加载状态
        Tab editorTab = new Tab(fileName);
        editorTab.setUserData(fileKey);
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(40, 40);
        StackPane loading = new StackPane(indicator);
        loading.setStyle("-fx-background-color: white;");
        editorTab.setContent(loading);
        editorTabPane.getTabs().add(editorTab);
        editorTabPane.getSelectionModel().select(editorTab);

        MarkdownEditorPane.loadMarkdownContent(config, currentBucket, fileKey, (content, err) -> {
            if (err != null) {
                editorTab.setContent(new Label("加载失败: " + err));
                return;
            }
            MarkdownEditorPane editor = new MarkdownEditorPane(config, currentBucket, fileKey, fileName, content);
            editorTab.setContent(editor);
            editor.setOnTitleChange(title -> editorTab.setText(title));
            editorTab.setText(editor.getDisplayTitle());
            // 关闭前检查未保存
            editorTab.setOnCloseRequest(ev -> {
                if (editor.isModified()) {
                    ev.consume();
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                    confirm.setTitle("未保存");
                    confirm.setHeaderText("文件 \"" + fileName + "\" 已修改未保存，是否保存？");
                    ButtonType saveBtn = new ButtonType("保存", ButtonBar.ButtonData.YES);
                    ButtonType discardBtn = new ButtonType("不保存", ButtonBar.ButtonData.NO);
                    ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                    confirm.getButtonTypes().setAll(saveBtn, discardBtn, cancelBtn);
                    confirm.showAndWait().ifPresent(resp -> {
                        if (resp == saveBtn) {
                            editor.save();
                        } else if (resp == cancelBtn) {
                            return;
                        }
                        // 保存或不保存：移除 Tab
                        editorTabPane.getTabs().remove(editorTab);
                    });
                }
            });
        });
    }

    /**
     * 右键创建文件：弹窗输入文件名，在当前 bucket/prefix 下新建空 markdown 并打开编辑器
     */
    private void handleCreateFile() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再创建文件");
            alert.showAndWait();
            return;
        }
        TextInputDialog dialog = new TextInputDialog("新文件.md");
        dialog.setTitle("创建文件");
        dialog.setHeaderText(null);
        dialog.setContentText("文件名：");
        dialog.showAndWait().ifPresent(name -> {
            String fileName = name.trim();
            if (fileName.isEmpty()) return;
            // 拼接完整 key
            String fileKey = (currentPrefix != null ? currentPrefix : "") + fileName;
            new Thread(() -> {
                try {
                    if (isAliyunOSS) {
                        OssService.putObject(config, currentBucket, fileKey, "");
                    } else {
                        S3Service.putObject(config, currentBucket, fileKey, "");
                    }
                    Platform.runLater(() -> {
                        refresh();
                        // 直接打开编辑器 Tab
                        FileItem newItem = new FileItem();
                        newItem.setName(fileName);
                        newItem.setKey(fileKey);
                        newItem.setDirectory(false);
                        newItem.setBucket(false);
                        openMarkdownEditor(newItem);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("创建失败");
                        alert.setHeaderText(null);
                        alert.setContentText("创建文件失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "MD-CreateFile").start();
        });
    }

    /**
     * 右键新建目录：在当前 bucket/prefix 下创建子目录（S3/OSS 中为以 / 结尾的空对象）
     */
    private void handleCreateDirectory() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再创建目录");
            alert.showAndWait();
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建目录");
        dialog.setHeaderText(null);
        dialog.setContentText("目录名称：");
        dialog.showAndWait().ifPresent(name -> {
            String dirName = name.trim();
            if (dirName.isEmpty()) return;
            // 禁止包含路径分隔符，避免产生意外路径
            if (dirName.contains("/")) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("目录名称不能包含 / 字符");
                alert.showAndWait();
                return;
            }
            // 拼接完整 prefix
            String dirKey = (currentPrefix != null ? currentPrefix : "") + dirName + "/";
            new Thread(() -> {
                try {
                    if (isAliyunOSS) {
                        OssService.createDirectory(config, currentBucket, dirKey);
                    } else {
                        S3Service.createDirectory(config, currentBucket, dirKey);
                    }
                    Platform.runLater(this::refresh);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("创建失败");
                        alert.setHeaderText(null);
                        alert.setContentText("创建目录失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "S3-Mkdir").start();
        });
    }

    /**
     * 将 JavaFX Image 转为 PNG 字节数组
     * 使用 PixelReader 直接读取像素数据，避免 SwingFXUtils 的格式转换问题
     */
    private byte[] imageToPngBytes(Image image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        if (w <= 0 || h <= 0) return null;

        // 限制图片尺寸，防止 OOM
        if ((long) w * h > 10_000_000) {
            return null;
        }

        // 使用 PixelReader 直接读取像素数据
        int[] pixels = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);

        // 构造 BufferedImage（TYPE_INT_ARGB 更通用，支持透明通道）
        java.awt.image.BufferedImage bImage = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bImage.setRGB(0, 0, w, h, pixels, 0, w);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try {
            boolean written = javax.imageio.ImageIO.write(bImage, "png", baos);
            if (!written) return null;
            byte[] result = baos.toByteArray();
            return result.length > 0 ? result : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 粘贴上传：从系统剪贴板上传图片或文件
     * 优先级：文件路径 > URL > 图片数据
     * 微信/浏览器复制图片时，剪贴板通常同时包含文件引用、URL和图像数据
     */
    private void handlePasteFromClipboard() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再粘贴上传");
            alert.showAndWait();
            return;
        }

        Clipboard clipboard = Clipboard.getSystemClipboard();

        // 1. 优先处理剪贴板中的文件引用（CF_HDROP，如从文件管理器复制的文件）
        //    这保留原始文件格式，无需重新编码
        if (clipboard.hasFiles()) {
            uploadLocalFiles(clipboard.getFiles());
            return;
        }

        // 2. 从剪贴板的 URL 或 HTML 中提取图片链接，下载原图
        //    浏览器复制图片时通常会在剪贴板中放入源 URL
        String imageUrl = extractImageUrlFromClipboard(clipboard);
        if (imageUrl != null) {
            downloadAndUploadUrl(imageUrl);
            return;
        }

        // 3. 最后兜底：从剪贴板图像数据转换为 PNG 上传
        if (clipboard.hasImage()) {
            handlePasteImageData(clipboard);
            return;
        }

        stateLabel.setText("剪贴板中没有图片或文件");
    }

    /**
     * 从剪贴板提取图片 URL
     * 检查 hasUrl() 和 hasHtml()（解析 HTML 中的 <img src>）
     */
    private String extractImageUrlFromClipboard(Clipboard clipboard) {
        // 直接 URL
        if (clipboard.hasUrl()) {
            String url = clipboard.getUrl();
            if (url != null && !url.trim().isEmpty() && isImageUrl(url)) {
                return url.trim();
            }
        }
        // HTML 中提取 <img src="...">
        if (clipboard.hasHtml()) {
            String html = clipboard.getHtml();
            if (html != null && !html.isEmpty()) {
                String src = extractImgSrc(html);
                if (src != null && isImageUrl(src)) {
                    return src;
                }
            }
        }
        return null;
    }

    /**
     * 从 HTML 中提取第一个 <img src="..."> 的值
     */
    private String extractImgSrc(String html) {
        int imgTag = html.toLowerCase().indexOf("<img");
        if (imgTag < 0) return null;
        String sub = html.substring(imgTag);
        int srcIdx = sub.indexOf("src=");
        if (srcIdx < 0) return null;
        String afterSrc = sub.substring(srcIdx + 5);
        char quote = afterSrc.charAt(0);
        if (quote == '"' || quote == '\'') {
            int end = afterSrc.indexOf(quote, 1);
            if (end > 0) return afterSrc.substring(1, end);
        } else {
            // 无引号，取到空格或 >
            int end = afterSrc.length();
            for (int i = 0; i < end; i++) {
                char c = afterSrc.charAt(i);
                if (c == ' ' || c == '>' || c == '\t' || c == '\n') {
                    end = i;
                    break;
                }
            }
            return afterSrc.substring(0, end);
        }
        return null;
    }

    /**
     * 判断 URL 是否指向图片（通过扩展名）
     */
    private boolean isImageUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // 去除查询参数
        int queryIdx = lower.indexOf('?');
        if (queryIdx > 0) lower = lower.substring(0, queryIdx);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")
                || lower.endsWith(".svg") || lower.endsWith(".ico") || lower.endsWith(".tiff");
    }

    /**
     * 从 URL 下载图片并上传到 S3/OSS
     */
    private void downloadAndUploadUrl(String url) {
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        // 去除查询参数
        int queryIdx = fileName.indexOf('?');
        if (queryIdx > 0) fileName = fileName.substring(0, queryIdx);
        if (fileName.isEmpty()) fileName = "paste_" + System.currentTimeMillis() + ".img";

        String key = (currentPrefix != null ? currentPrefix : "") + fileName;
        stateLabel.setText("下载中: " + fileName);

        String finalFileName = fileName;
        new Thread(() -> {
            try {
                java.net.URL urlObj = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("GET");
                String contentType = conn.getContentType();
                byte[] data;
                try (java.io.InputStream is = conn.getInputStream();
                     java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        baos.write(buf, 0, len);
                    }
                    data = baos.toByteArray();
                }
                conn.disconnect();

                if (data.length == 0) {
                    Platform.runLater(() -> {
                        stateLabel.setText("下载失败：文件为空");
                    });
                    return;
                }

                final byte[] uploadData = data;
                Platform.runLater(() -> stateLabel.setText("上传中: " + finalFileName));
                if (isAliyunOSS) {
                    OssService.uploadFile(config, currentBucket, key,
                            new java.io.ByteArrayInputStream(uploadData),
                            uploadData.length, contentType);
                } else {
                    S3Service.uploadFile(config, currentBucket, key,
                            new java.io.ByteArrayInputStream(uploadData),
                            uploadData.length, contentType);
                }
                Platform.runLater(() -> {
                    stateLabel.setText("上传完成: " + finalFileName);
                    refresh();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    stateLabel.setText("下载失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("下载失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "S3-UrlDownload").start();
    }

    /**
     * 从剪贴板图像数据转换为 PNG 上传（兜底方案）
     */
    private void handlePasteImageData(Clipboard clipboard) {
        Image image = clipboard.getImage();
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            stateLabel.setText("剪贴板中没有有效的图片");
            return;
        }

        String fileName = "paste_" + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png";
        String key = (currentPrefix != null ? currentPrefix : "") + fileName;

        byte[] imageBytes;
        try {
            imageBytes = imageToPngBytes(image);
            if (imageBytes == null || imageBytes.length == 0) {
                stateLabel.setText("图片转换失败");
                return;
            }
        } catch (Exception e) {
            stateLabel.setText("图片转换失败");
            e.printStackTrace();
            return;
        }

        stateLabel.setText("上传中: " + fileName);
        new Thread(() -> {
            try {
                java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(imageBytes);
                if (isAliyunOSS) {
                    OssService.uploadFile(config, currentBucket, key, bis, imageBytes.length, "image/png");
                } else {
                    S3Service.uploadFile(config, currentBucket, key, bis, imageBytes.length, "image/png");
                }
                Platform.runLater(() -> {
                    stateLabel.setText("上传完成: " + fileName);
                    refresh();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    stateLabel.setText("上传失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("上传失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "S3-PasteUpload").start();
    }

    /**
     * 右键上传文件：弹窗选择本地文件，逐个上传到当前 bucket/prefix
     */
    private void handleUploadFiles() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再上传文件");
            alert.showAndWait();
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要上传的文件");
        List<File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        uploadLocalFiles(files);
    }

    /**
     * 上传本地文件列表到当前 bucket/prefix
     */
    private void uploadLocalFiles(List<File> files) {
        if (currentBucket == null || files == null || files.isEmpty()) return;
        stateLabel.setText("上传中... (0/" + files.size() + ")");
        new Thread(() -> {
            int success = 0;
            int failed = 0;
            String lastError = null;
            for (File file : files) {
                String key = (currentPrefix != null ? currentPrefix : "") + file.getName();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    long size = file.length();
                    String contentType = java.net.URLConnection.guessContentTypeFromName(file.getName());
                    if (contentType == null) contentType = "application/octet-stream";
                    if (isAliyunOSS) {
                        OssService.uploadFile(config, currentBucket, key, fis, size, contentType);
                    } else {
                        S3Service.uploadFile(config, currentBucket, key, fis, size, contentType);
                    }
                    success++;
                } catch (Exception e) {
                    failed++;
                    lastError = e.getMessage();
                    e.printStackTrace();
                }
                final int done = success + failed;
                Platform.runLater(() -> stateLabel.setText("上传中... (" + done + "/" + files.size() + ")"));
            }
            final int okCount = success;
            final int failCount = failed;
            final String err = lastError;
            Platform.runLater(() -> {
                if (failCount == 0) {
                    stateLabel.setText("上传完成: 成功 " + okCount + " 个");
                } else {
                    stateLabel.setText("上传结束: 成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("部分上传失败");
                    alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    alert.setContentText(err != null ? err : "");
                    alert.showAndWait();
                }
                refresh();
            });
        }, "S3-Upload").start();
    }

    /**
     * 右键下载文件：选择保存位置，下载指定文件
     */
    private void handleDownload(FileItem item) {
        if (currentBucket == null || item == null || item.isDirectory()) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("保存文件");
        fileChooser.setInitialFileName(item.getDisplayName());
        File saveFile = fileChooser.showSaveDialog(getScene().getWindow());
        if (saveFile == null) return;

        stateLabel.setText("下载中: " + item.getDisplayName());
        new Thread(() -> {
            try (InputStream is = isAliyunOSS
                    ? OssService.getObjectStream(config, currentBucket, item.getKey())
                    : S3Service.getObjectStream(config, currentBucket, item.getKey())) {
                Files.copy(is, saveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Platform.runLater(() -> {
                    stateLabel.setText("下载完成: " + item.getDisplayName());
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("下载完成");
                    alert.setHeaderText(null);
                    alert.setContentText("文件已保存到: " + saveFile.getAbsolutePath());
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    stateLabel.setText("下载失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("下载失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "S3-Download").start();
    }

    /**
     * 拼接文件的公共访问URL并复制到剪贴板。
     * 规则：访问URL前缀（去除末尾 /）+ "/" + 桶名 + "/" + 文件 key
     * 若未配置访问URL，提示用户先配置。
     */
    private void handleCopyAccessUrl(FileItem item) {
        String baseUrl = config.getPublicAccessUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("尚未配置访问URL，请在连接配置中设置访问URL字段");
            alert.showAndWait();
            return;
        }
        String trimmedBase = baseUrl.trim();
        while (trimmedBase.endsWith("/")) {
            trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
        }
        String key = item.getKey();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        String fullUrl = trimmedBase + "/" + currentBucket + "/" + key;

        ClipboardContent content = new ClipboardContent();
        content.putString(fullUrl);
        Clipboard.getSystemClipboard().setContent(content);

        stateLabel.setText("已复制访问地址: " + fullUrl);
    }

    /**
     * 下载文件到临时目录（用于拖拽下载）
     */
    private File downloadToTemp(FileItem item) {
        if (currentBucket == null || item == null || item.isDirectory()) return null;
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "tomato-s3");
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = new File(tempDir, item.getDisplayName());
            try (InputStream is = isAliyunOSS
                    ? OssService.getObjectStream(config, currentBucket, item.getKey())
                    : S3Service.getObjectStream(config, currentBucket, item.getKey())) {
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (Exception e) {
            Platform.runLater(() -> stateLabel.setText("拖拽下载失败: " + e.getMessage()));
            return null;
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

        // 收集当前目录中所有图片文件
        List<FileItem> imageItems = new ArrayList<>();
        int currentIndex = -1;
        for (int i = 0; i < fileData.size(); i++) {
            FileItem fi = fileData.get(i);
            if (!fi.isDirectory() && isImageFile(fi.getDisplayName())) {
                imageItems.add(fi);
                if (fi == item || fi.getKey().equals(item.getKey())) {
                    currentIndex = imageItems.size() - 1;
                }
            }
        }
        if (imageItems.isEmpty() || currentIndex < 0) return;

        Stage previewStage = new Stage();
        previewStage.setTitle("图片预览");
        previewStage.setMinWidth(600);
        previewStage.setMinHeight(500);
        previewStage.setWidth(800);
        previewStage.setHeight(600);

        // 初始加载动画
        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(60, 60);
        StackPane loadingPane = new StackPane(loadingIndicator);
        loadingPane.setPrefSize(780, 520);
        loadingPane.setStyle("-fx-background-color: #2b2b2b;");
        previewStage.setScene(new Scene(loadingPane));

        // 使用数组以便在lambda中修改
        final int[] imageIndex = {currentIndex};

        // 加载图片的回调接口
        Runnable loadImage = new Runnable() {
            @Override
            public void run() {
                int idx = imageIndex[0];
                if (idx < 0 || idx >= imageItems.size()) return;
                FileItem currentItem = imageItems.get(idx);

                // 显示加载动画
                ProgressIndicator indicator = new ProgressIndicator();
                indicator.setPrefSize(60, 60);
                StackPane pane = new StackPane(indicator);
                pane.setPrefSize(780, 520);
                pane.setStyle("-fx-background-color: #2b2b2b;");
                previewStage.setScene(new Scene(pane));

                new Thread(() -> {
                    try {
                        InputStream is;
                        if (isAliyunOSS) {
                            is = OssService.getObjectStream(config, currentBucket, currentItem.getKey());
                        } else {
                            is = S3Service.getObjectStream(config, currentBucket, currentItem.getKey());
                        }

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
                                showImageInPreviewStage(previewStage, image, currentItem, imageItems, imageIndex, this);
                            } catch (Exception e) {
                                Label errorLabel = new Label("图片加载失败: " + e.getMessage());
                                errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
                                StackPane errorPane = new StackPane(errorLabel);
                                errorPane.setPrefSize(780, 520);
                                errorPane.setStyle("-fx-background-color: #2b2b2b;");
                                previewStage.setScene(new Scene(errorPane));
                            }
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            Label errorLabel = new Label("图片下载失败: " + e.getMessage());
                            errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
                            StackPane errorPane = new StackPane(errorLabel);
                            errorPane.setPrefSize(780, 520);
                            errorPane.setStyle("-fx-background-color: #2b2b2b;");
                            previewStage.setScene(new Scene(errorPane));
                        });
                    }
                }, "S3-LoadImage").start();
            }
        };

        // 首次加载
        loadImage.run();
        previewStage.show();
    }

    /**
     * 在预览窗口中展示图片（支持缩放、拖拽、上一张/下一张、下载、删除）
     */
    private void showImageInPreviewStage(Stage stage, Image image, FileItem item,
                                          List<FileItem> imageItems, int[] imageIndex, Runnable loadImage) {
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
        double contentWidth = stage.getWidth() > 0 ? stage.getWidth() : 800;
        double contentHeight = stage.getHeight() > 0 ? stage.getHeight() - 40 : 560;

        double fitWidth = Math.min(imgWidth, contentWidth - 20);
        double fitHeight = Math.min(imgHeight, contentHeight - 60);
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

        // 设置标题：文件名、尺寸、大小
        stage.setTitle(String.format("%s  |  %dx%d  |  %s  (%d/%d)",
                item.getDisplayName(), (int) imgWidth, (int) imgHeight, item.getFormattedSize(),
                imageIndex[0] + 1, imageItems.size()));

        // 工具栏
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #3c3c3c;");

        // 上一张按钮
        Button prevBtn = new Button("◀ 上一张");
        prevBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        prevBtn.setDisable(imageIndex[0] <= 0);
        prevBtn.setOnAction(e -> {
            if (imageIndex[0] > 0) {
                imageIndex[0]--;
                loadImage.run();
            }
        });
        toolbar.getChildren().add(prevBtn);

        // 下一张按钮
        Button nextBtn = new Button("下一张 ▶");
        nextBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        nextBtn.setDisable(imageIndex[0] >= imageItems.size() - 1);
        nextBtn.setOnAction(e -> {
            if (imageIndex[0] < imageItems.size() - 1) {
                imageIndex[0]++;
                loadImage.run();
            }
        });
        toolbar.getChildren().add(nextBtn);

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
            if (s > 1) s = 1;
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

        // 分隔
        Separator sep2 = new Separator();
        sep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        toolbar.getChildren().add(sep2);

        // 下载按钮
        Button downloadBtn = new Button("下载");
        downloadBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        downloadBtn.setOnAction(e -> {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("保存文件");
            fileChooser.setInitialFileName(item.getDisplayName());
            java.io.File saveFile = fileChooser.showSaveDialog(stage);
            if (saveFile == null) return;

            new Thread(() -> {
                try {
                    InputStream is;
                    if (isAliyunOSS) {
                        is = OssService.getObjectStream(config, currentBucket, item.getKey());
                    } else {
                        is = S3Service.getObjectStream(config, currentBucket, item.getKey());
                    }
                    java.nio.file.Files.copy(is, saveFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    is.close();
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("下载完成");
                        alert.setHeaderText(null);
                        alert.setContentText("文件已保存到: " + saveFile.getAbsolutePath());
                        alert.showAndWait();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("下载失败");
                        alert.setHeaderText(null);
                        alert.setContentText(ex.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "S3-Download").start();
        });
        toolbar.getChildren().add(downloadBtn);

        // 删除按钮
        Button deleteBtn = new Button("删除");
        deleteBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-text-fill: #ff6b6b;");
        deleteBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("删除确认");
            confirm.setHeaderText(null);
            confirm.setContentText("确定要删除文件 \"" + item.getDisplayName() + "\" 吗？");
            confirm.showAndWait().ifPresent(response -> {
                if (response != ButtonType.OK) return;

                new Thread(() -> {
                    try {
                        if (isAliyunOSS) {
                            OssService.deleteObject(config, currentBucket, item.getKey());
                        } else {
                            S3Service.deleteObject(config, currentBucket, item.getKey());
                        }
                        Platform.runLater(() -> {
                            // 从列表中移除已删除项
                            imageItems.remove(imageIndex[0]);
                            fileData.remove(item);
                            if (imageItems.isEmpty()) {
                                stage.close();
                                refresh();
                            } else {
                                if (imageIndex[0] >= imageItems.size()) {
                                    imageIndex[0] = imageItems.size() - 1;
                                }
                                refresh();
                                loadImage.run();
                            }
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("删除失败");
                            alert.setHeaderText(null);
                            alert.setContentText(ex.getMessage());
                            alert.showAndWait();
                        });
                    }
                }, "S3-Delete").start();
            });
        });
        toolbar.getChildren().add(deleteBtn);

        VBox content = new VBox();
        content.getChildren().addAll(toolbar, imageContainer);
        VBox.setVgrow(imageContainer, Priority.ALWAYS);

        stage.setScene(new Scene(content));

        // 键盘快捷键：左右箭头切换图片
        stage.getScene().setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.LEFT && imageIndex[0] > 0) {
                imageIndex[0]--;
                loadImage.run();
            } else if (e.getCode() == javafx.scene.input.KeyCode.RIGHT && imageIndex[0] < imageItems.size() - 1) {
                imageIndex[0]++;
                loadImage.run();
            }
        });
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
        ImageView thumbView = new ImageView(fullImage);
        thumbView.setFitWidth(w * scale);
        thumbView.setFitHeight(h * scale);
        thumbView.setPreserveRatio(true);
        thumbView.setSmooth(true);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(javafx.scene.paint.Color.TRANSPARENT);
        Image thumbnail = thumbView.snapshot(params, null);

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
     * 异步加载图标视图中所有图片文件的缩略图
     */
    private void loadThumbnailsForIconView() {
        if (currentBucket == null) return;
        for (FileItem item : fileData) {
            if (!item.isDirectory() && isImageFile(item.getDisplayName())) {
                new Thread(() -> {
                    try {
                        InputStream is;
                        if (isAliyunOSS) {
                            is = OssService.getObjectStream(config, currentBucket, item.getKey());
                        } else {
                            is = S3Service.getObjectStream(config, currentBucket, item.getKey());
                        }
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
                                if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) return;
                                updateIconBoxWithThumbnail(item, image);
                            } catch (Exception ignored) {}
                        });
                    } catch (Exception ignored) {}
                }, "S3-Thumb-" + item.getName()).start();
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
            currentPathField.setText("/");
        } else {
            String path = "/" + currentBucket + "/" + (currentPrefix != null ? currentPrefix : "");
            currentPathField.setText(path);
        }
    }

    /**
     * 跳转到指定路径（由路径输入框回车触发）
     * 支持格式："/"（根=Bucket列表）、"/bucket"、"/bucket/prefix/"、"/bucket/prefix1/prefix2"
     */
    private void navigateToPath(String input) {
        if (input == null) return;
        String path = input.trim();
        if (path.isEmpty()) {
            updatePathLabel();
            return;
        }

        // 规范化：确保以 / 开头
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // 去除多余的末尾 /
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        // 根目录：加载 Bucket 列表
        if (path.equals("/")) {
            pathHistory.clear();
            loadBuckets();
            return;
        }

        // /bucket 或 /bucket/prefix1/prefix2
        String rest = path.substring(1);
        int firstSlash = rest.indexOf('/');
        String bucket;
        String prefix;
        if (firstSlash < 0) {
            bucket = rest;
            prefix = "";
        } else {
            bucket = rest.substring(0, firstSlash);
            prefix = rest.substring(firstSlash + 1);
            if (!prefix.isEmpty() && !prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
        }

        if (bucket.isEmpty()) {
            pathHistory.clear();
            loadBuckets();
            return;
        }

        loadObjects(bucket, prefix);
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

    /**
     * 右键重命名：弹窗输入新名称，文件/目录均可重命名（Bucket 不支持）
     */
    private void handleRename() {
        FileItem selected = getSelectedItem();
        if (selected == null || currentBucket == null || selected.isBucket()) return;

        String oldName = selected.getDisplayName();
        TextInputDialog dialog = new TextInputDialog(oldName);
        dialog.setTitle("重命名");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(newName -> {
            newName = newName.trim();
            if (newName.isEmpty() || newName.equals(oldName)) return;
            // 禁止包含路径分隔符
            if (newName.contains("/")) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("名称不能包含 / 字符");
                alert.showAndWait();
                return;
            }

            // 计算新旧 key
            String oldKey = selected.getKey();
            String newKey;
            if (selected.isDirectory()) {
                // 目录：oldKey 格式 prefix/oldname/，newKey 格式 prefix/newname/
                String parentPrefix = oldKey.substring(0, oldKey.length() - oldName.length() - 1);
                newKey = parentPrefix + newName + "/";
            } else {
                // 文件：oldKey 格式 prefix/oldname，newKey 格式 prefix/newname
                String parentPrefix = oldKey.substring(0, oldKey.length() - oldName.length());
                newKey = parentPrefix + newName;
            }

            stateLabel.setText("重命名中...");
            new Thread(() -> {
                try {
                    if (isAliyunOSS) {
                        OssService.renameObject(config, currentBucket, oldKey, newKey, selected.isDirectory());
                    } else {
                        S3Service.renameObject(config, currentBucket, oldKey, newKey, selected.isDirectory());
                    }
                    Platform.runLater(() -> {
                        stateLabel.setText("重命名完成");
                        refresh();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        stateLabel.setText("重命名失败");
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("重命名失败");
                        alert.setHeaderText(null);
                        alert.setContentText(e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "S3-Rename").start();
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
                            S3Service.deleteBucket(config, selected.getName());
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
