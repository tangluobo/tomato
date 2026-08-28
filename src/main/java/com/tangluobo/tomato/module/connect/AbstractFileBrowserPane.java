package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Callback;
import javafx.util.Duration;

import javax.swing.filechooser.FileSystemView;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件浏览器抽象基类。
 * <p>
 * 汇集 S3FileBrowserPane / SFTPFileBrowser / FTPFileBrowserPane 共有的 UI 代码：
 * 视图模式（图标/列表/列）、选中逻辑、单击重命名、右键菜单、框选、图标加载。
 * <p>
 * 子类需实现后端操作（doRefresh / doNavigateTo / doRename / doDelete / doMkdir /
 * doUpload / doDownload / doDownloadToTemp / isConnected），并按需覆盖钩子方法
 * （supportsBuckets / supportsColumnView / supportsMarkdownEditor ...）以开启对应能力。
 */
public abstract class AbstractFileBrowserPane extends BorderPane {

    // ==================== 视图模式 ====================
    public enum ViewMode { ICON, LIST, COLUMN }
    protected ViewMode currentViewMode = ViewMode.LIST;

    // ==================== UI 组件 ====================
    protected TableView<FileItem> fileTable;
    protected FlowPane iconFlowPane;
    protected ScrollPane iconScrollPane;
    protected HBox columnContainer;
    protected ScrollPane columnScrollPane;
    protected final List<ListView<FileItem>> columnListViews = new ArrayList<>();
    protected final List<ObservableList<FileItem>> columnItems = new ArrayList<>();
    protected final List<String> columnPaths = new ArrayList<>();

    protected final ObservableList<FileItem> fileData = FXCollections.observableArrayList();

    protected HBox pathBar;
    protected TextField currentPathField;
    protected Button refreshBtn;
    protected Button upBtn;
    protected Button mkdirBtn;
    protected Button uploadBtn;
    protected ToggleButton iconViewBtn;
    protected ToggleButton listViewBtn;
    protected ToggleButton columnViewBtn;
    protected Label statusLabel;

    protected String currentPath = "/";

    // ==================== 选中 / 编辑状态 ====================
    protected FileItem selectedItem;        // 主选中项（右键菜单用）
    protected FileItem clickedBeforeItem;   // 上次点击前已选中的项
    protected final Set<FileItem> iconSelectedItems = new LinkedHashSet<>();
    protected Rectangle selectionRect;       // 框选矩形
    protected double selStartX, selStartY;   // 框选起点（相对 iconFlowPane）
    protected FileItem editingItem;          // 当前正在重命名的项
    protected javafx.animation.Timeline singleClickTimer;
    protected Popup iconEditPopup;
    protected TextField iconEditField;
    private Scene shortcutScene;
    private javafx.event.EventHandler<KeyEvent> shortcutKeyFilter;

    // ==================== 上传进度对话框（Ctrl+V 粘贴上传 / 拖拽上传 / 菜单上传） ====================
    private Stage uploadProgressStage;
    private ProgressBar uploadProgressBar;
    private Label uploadProgressLabel;
    private Label uploadProgressDetailLabel;
    private final AtomicBoolean uploadCancelled = new AtomicBoolean(false);

    // ==================== 图标 ====================
    protected Image folderIcon;
    protected Image folderLargeIcon;
    protected Image defaultFileIcon;
    protected final Map<String, Image> systemIconCache = new HashMap<>();
    protected final Map<String, Image> systemLargeIconCache = new HashMap<>();
    protected final File iconTempDir;
    // 文件类型图标缓存（/images/connect/fileTypes/）
    protected final Map<String, Image> fileTypeIconCache = new HashMap<>();
    protected final Map<String, Image> fileTypeLargeIconCache = new HashMap<>();

    // 图片 / Markdown 扩展名
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>();
    private static final Set<String> MARKDOWN_EXTENSIONS = new HashSet<>();
    static {
        IMAGE_EXTENSIONS.add("jpg"); IMAGE_EXTENSIONS.add("jpeg");
        IMAGE_EXTENSIONS.add("png"); IMAGE_EXTENSIONS.add("gif");
        IMAGE_EXTENSIONS.add("bmp"); IMAGE_EXTENSIONS.add("webp");
        IMAGE_EXTENSIONS.add("svg"); IMAGE_EXTENSIONS.add("ico");
        IMAGE_EXTENSIONS.add("tiff"); IMAGE_EXTENSIONS.add("tif");
        MARKDOWN_EXTENSIONS.add("md");
        MARKDOWN_EXTENSIONS.add("markdown");
        MARKDOWN_EXTENSIONS.add("mdown");
        MARKDOWN_EXTENSIONS.add("mkd");
    }

    // ==================== 构造 ====================
    protected AbstractFileBrowserPane() {
        // 创建临时目录用于获取系统图标
        iconTempDir = new File(System.getProperty("java.io.tmpdir"), "tomato-icons");
        if (!iconTempDir.exists()) iconTempDir.mkdirs();

        // 目录图标统一使用 folder.png（与 S3 风格一致）
        folderIcon = loadIcon("/images/connect/folder.png");
        // 大尺寸版本（图标视图用，48x48）
        folderLargeIcon = folderIcon != null
                ? new Image(getClass().getResourceAsStream("/images/connect/folder.png"), 48, 48, true, true)
                : null;

        // 获取系统默认文件图标
        Image sysFileIcon = getSystemFileIcon("txt");
        defaultFileIcon = sysFileIcon != null ? sysFileIcon : createFileTypeIcon("?", "#9E9E9E");

        setMinHeight(200);
        setStyle("-fx-background-color: #FFFFFF;");

        initializeUI();
    }

    /**
     * 设置初始文件视图模式（在导航前或后调用均可；若 UI 已构建则立即切换）。
     */
    public void setInitialViewMode(ViewMode mode) {
        if (mode != null) {
            this.currentViewMode = mode;
            if (iconScrollPane != null) {
                switchViewMode(mode);
            }
        }
    }

    // ==================== UI 初始化 ====================
    protected void initializeUI() {
        // ---- 路径输入框 ----
        currentPathField = new TextField("/");
        currentPathField.setPrefHeight(25);
        currentPathField.setMinWidth(0);
        currentPathField.setPrefWidth(0);
        currentPathField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(currentPathField, Priority.ALWAYS);
        currentPathField.setStyle("-fx-font-size: 12px; -fx-text-fill: #333; -fx-background-color: white; -fx-background-insets: 0; -fx-background-radius: 0; -fx-padding: 2 6; -fx-border-color: #3399ff; -fx-border-width: 1; -fx-border-insets: 0; -fx-border-radius: 0;");
        currentPathField.setTooltip(new Tooltip("点击编辑路径，回车进入目录"));
        currentPathField.setOnAction(e -> navigateTo(currentPathField.getText().trim()));

        // ---- 顶部路径导航栏 ----
        pathBar = new HBox(8);
        pathBar.setAlignment(Pos.CENTER_LEFT);
        pathBar.setPadding(new Insets(6, 10, 6, 10));
        pathBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;");
        pathBar.getChildren().add(currentPathField);

        // 视图切换按钮组
        ToggleGroup viewToggleGroup = new ToggleGroup();

        iconViewBtn = new ToggleButton("⊞");
        iconViewBtn.setTooltip(new Tooltip("图标视图"));
        iconViewBtn.setToggleGroup(viewToggleGroup);
        iconViewBtn.setStyle("-fx-font-size: 14px; -fx-padding: 2 6; -fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
        iconViewBtn.setOnAction(e -> switchViewMode(ViewMode.ICON));
        pathBar.getChildren().add(iconViewBtn);

        listViewBtn = new ToggleButton("≡");
        listViewBtn.setTooltip(new Tooltip("列表视图"));
        listViewBtn.setToggleGroup(viewToggleGroup);
        listViewBtn.setSelected(true);
        listViewBtn.setStyle("-fx-font-size: 14px; -fx-padding: 2 6; -fx-background-radius: 0; -fx-border-radius: 0;");
        listViewBtn.setOnAction(e -> switchViewMode(ViewMode.LIST));
        pathBar.getChildren().add(listViewBtn);

        columnViewBtn = new ToggleButton("⫶");
        columnViewBtn.setTooltip(new Tooltip("列视图（多级目录）"));
        columnViewBtn.setToggleGroup(viewToggleGroup);
        columnViewBtn.setStyle("-fx-font-size: 14px; -fx-padding: 2 6; -fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0;");
        columnViewBtn.setOnAction(e -> switchViewMode(ViewMode.COLUMN));
        pathBar.getChildren().add(columnViewBtn);
        // 不支持列视图的子类隐藏该按钮
        if (!supportsColumnView()) {
            columnViewBtn.setDisable(true);
            columnViewBtn.setVisible(false);
        }

        Label sep = new Label("|");
        sep.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        pathBar.getChildren().add(sep);

        upBtn = new Button("↑ 上级");
        upBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        upBtn.setOnAction(e -> navigateUp());
        pathBar.getChildren().add(upBtn);

        refreshBtn = new Button("⟳ 刷新");
        refreshBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        refreshBtn.setOnAction(e -> refresh());
        pathBar.getChildren().add(refreshBtn);

        mkdirBtn = new Button("+ 新建目录");
        mkdirBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        mkdirBtn.setOnAction(e -> handleMkdir());
        pathBar.getChildren().add(mkdirBtn);

        uploadBtn = new Button("↑ 上传");
        uploadBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-background-radius: 4;");
        uploadBtn.setOnAction(e -> handleUpload());
        pathBar.getChildren().add(uploadBtn);

        setTop(pathBar);

        // ---- 列表 / 图标 / 列视图 ----
        initListView();
        initIconView();
        initColumnView();

        switchViewMode(currentViewMode);

        // ---- 底部状态栏 ----
        setBottom(createStatusBar());

        // Ctrl+V 粘贴上传快捷键
        setupKeyboardShortcuts();
        setupRootDropTarget();
    }

    /** Capture drops before TableView/ListView skins can consume the native drag event. */
    private void setupRootDropTarget() {
        addEventFilter(DragEvent.DRAG_OVER, event -> {
            if (!event.getDragboard().getContentTypes().isEmpty()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                event.consume();
            }
        });
        addEventFilter(DragEvent.DRAG_DROPPED, event -> {
            boolean uploaded = uploadFilesFromClipboard(event.getDragboard());
            event.setDropCompleted(uploaded);
            if (!uploaded) {
                setStatus("无法读取拖入的 ZIP 文件（格式: " + describeClipboardFormats(event.getDragboard()) + ")");
            }
            event.consume();
        });
    }

    private String describeClipboardFormats(Clipboard clipboard) {
        String value = clipboard.getContentTypes().stream()
                .flatMap(format -> format.getIdentifiers().stream())
                .collect(java.util.stream.Collectors.joining(", "));
        return value.isEmpty() ? "未知" : value;
    }

    /**
     * 创建状态栏。默认实现只有 statusLabel，子类可覆盖以添加状态指示灯、连接信息等。
     * 覆盖时应初始化 statusLabel（或将其指向子类自己的 Label）。
     */
    protected Node createStatusBar() {
        HBox statusBar = new HBox(8);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 10, 4, 10));
        statusBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;");
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(statusLabel);
        return statusBar;
    }

    /**
     * 初始化列表视图：fileTable + 列定义 + 行工厂 + 右键菜单
     */
    protected void initListView() {
        fileTable = new TableView<>();
        fileTable.setStyle("-fx-font-size: 12px; -fx-background-color: #fff;");
        fileTable.getStyleClass().add("sftp-file-table");
        fileTable.setPlaceholder(new Label("空目录"));
        fileTable.setFixedCellSize(26);
        fileTable.setMinHeight(80);
        fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fileTable.setItems(fileData);

        TableColumn<FileItem, String> nameCol = new TableColumn<>("名称");
        nameCol.setEditable(true);
        nameCol.setCellValueFactory(new PropertyValueFactory<>("displayName"));
        nameCol.setCellFactory(createEditableNameCellFactory());
        nameCol.setPrefWidth(300);

        TableColumn<FileItem, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setCellValueFactory(new PropertyValueFactory<>("displaySize"));
        sizeCol.setPrefWidth(100);
        sizeCol.setStyle("-fx-alignment: center-right;");

        TableColumn<FileItem, String> timeCol = new TableColumn<>("修改时间");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("displayTime"));
        timeCol.setPrefWidth(180);
        timeCol.setStyle("-fx-alignment: center-left;");

        TableColumn<FileItem, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("displayType"));
        typeCol.setPrefWidth(80);

        fileTable.getColumns().addAll(nameCol, sizeCol, timeCol, typeCol);
        fileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        setupTableRowFactory();
        fileTable.setContextMenu(createContextMenu());
    }

    /**
     * 初始化图标视图容器（FlowPane + ScrollPane + 框选）
     */
    protected void initIconView() {
        iconFlowPane = new FlowPane();
        iconFlowPane.setHgap(8);
        iconFlowPane.setVgap(8);
        iconFlowPane.setPadding(new Insets(12));
        iconFlowPane.setStyle("-fx-background-color: white;");

        // 框选矩形（不参与布局，覆盖在图标上方）
        selectionRect = new Rectangle();
        selectionRect.setFill(Color.rgb(51, 153, 255, 0.15));
        selectionRect.setStroke(Color.rgb(51, 153, 255, 0.8));
        selectionRect.setStrokeWidth(1);
        selectionRect.setManaged(false);
        selectionRect.setMouseTransparent(true);
        selectionRect.setVisible(false);

        iconScrollPane = new ScrollPane(iconFlowPane);
        iconScrollPane.setFitToWidth(true);
        iconScrollPane.setFitToHeight(true);
        iconScrollPane.setStyle("-fx-background-color: white;");
        iconScrollPane.setContextMenu(createContextMenu());

        // 拖拽上传
        iconScrollPane.setOnDragOver(e -> {
            if (hasUploadableFiles(e.getDragboard())) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        iconScrollPane.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            success = uploadFilesFromClipboard(db);
            e.setDropCompleted(success);
            e.consume();
        });

        // 框选：在图标视图空白处按下鼠标开始框选
        iconScrollPane.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            // 判断是否点击在图标项上：沿事件链向上查找带 "fileItem" 属性的 VBox（图标项）。
            // 注意：点击图标图像(ImageView)或文件名(Label/LabeledText)时 target 是其子节点，
            // 直接 instanceof VBox 判断会误判为空白点击而清空选中，导致"已选中再单击"无法进入重命名。
            Node targetNode = e.getTarget() instanceof Node ? (Node) e.getTarget() : null;
            boolean clickedOnIconBox = false;
            while (targetNode != null && targetNode != iconScrollPane) {
                if (targetNode instanceof VBox && targetNode.getProperties().containsKey("fileItem")) {
                    clickedOnIconBox = true;
                    break;
                }
                if (targetNode == iconFlowPane) break;
                targetNode = targetNode.getParent();
            }
            if (clickedOnIconBox) return; // 点在图标项上，交给图标项自身的点击处理
            // 空白处点击：清空选中并开始框选
            if (!e.isControlDown() && !e.isShiftDown()) {
                iconSelectedItems.clear();
                refreshIconSelectionStyles();
            }
            javafx.geometry.Point2D p = iconFlowPane.screenToLocal(e.getScreenX(), e.getScreenY());
            selStartX = p.getX();
            selStartY = p.getY();
            selectionRect.setX(selStartX);
            selectionRect.setY(selStartY);
            selectionRect.setWidth(0);
            selectionRect.setHeight(0);
            selectionRect.setVisible(true);
            // 确保矩形在最上层
            if (!iconFlowPane.getChildren().contains(selectionRect)) {
                iconFlowPane.getChildren().add(selectionRect);
            } else {
                selectionRect.toFront();
            }
        });

        iconScrollPane.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!selectionRect.isVisible()) return;
            javafx.geometry.Point2D p = iconFlowPane.screenToLocal(e.getScreenX(), e.getScreenY());
            double nx = Math.min(selStartX, p.getX());
            double ny = Math.min(selStartY, p.getY());
            double nw = Math.abs(p.getX() - selStartX);
            double nh = Math.abs(p.getY() - selStartY);
            selectionRect.setX(nx);
            selectionRect.setY(ny);
            selectionRect.setWidth(nw);
            selectionRect.setHeight(nh);
            updateRubberBandSelection(nx, ny, nw, nh, e.isControlDown());
        });

        iconScrollPane.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> selectionRect.setVisible(false));
    }

    /**
     * 初始化列视图容器（HBox + ScrollPane）
     */
    protected void initColumnView() {
        columnContainer = new HBox();
        columnContainer.setStyle("-fx-background-color: white;");

        columnScrollPane = new ScrollPane(columnContainer);
        columnScrollPane.setFitToHeight(true);
        columnScrollPane.setFitToWidth(false);
        columnScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        columnScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        columnScrollPane.setStyle("-fx-background-color: white;");
        columnScrollPane.setContextMenu(createContextMenu());

        // 拖拽上传
        columnScrollPane.setOnDragOver(e -> {
            if (hasUploadableFiles(e.getDragboard())) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        columnScrollPane.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            success = uploadFilesFromClipboard(db);
            e.setDropCompleted(success);
            e.consume();
        });
    }

    // ==================== 视图模式切换 ====================
    protected void switchViewMode(ViewMode mode) {
        // 不支持列视图的子类回退到列表视图
        if (mode == ViewMode.COLUMN && !supportsColumnView()) {
            mode = ViewMode.LIST;
        }
        currentViewMode = mode;

        // 同步切换按钮选中状态
        if (iconViewBtn != null) iconViewBtn.setSelected(mode == ViewMode.ICON);
        if (listViewBtn != null) listViewBtn.setSelected(mode == ViewMode.LIST);
        if (columnViewBtn != null) columnViewBtn.setSelected(mode == ViewMode.COLUMN);

        VBox centerBox = new VBox();

        if (mode == ViewMode.ICON) {
            rebuildIconView();
            centerBox.getChildren().add(iconScrollPane);
            VBox.setVgrow(iconScrollPane, Priority.ALWAYS);
            if (supportsThumbnails()) {
                loadThumbnails();
            }
        } else if (mode == ViewMode.COLUMN) {
            rebuildColumnView();
            centerBox.getChildren().add(columnScrollPane);
            VBox.setVgrow(columnScrollPane, Priority.ALWAYS);
        } else {
            centerBox.getChildren().add(fileTable);
            VBox.setVgrow(fileTable, Priority.ALWAYS);
        }

        applyCenter(centerBox);
    }

    /**
     * 应用中心区域内容。默认直接 setCenter。
     * 需要在中心区域使用 TabPane（如 Markdown 编辑器标签页）的子类可覆盖此方法。
     */
    protected void applyCenter(Node content) {
        setCenter(content);
    }

    /**
     * 重建图标视图：清空并填充所有文件项
     */
    protected void rebuildIconView() {
        if (iconFlowPane == null) return;
        iconFlowPane.getChildren().clear();
        iconSelectedItems.clear();
        for (FileItem item : fileData) {
            iconFlowPane.getChildren().add(createIconBox(item));
        }
        // 框选矩形最后添加，确保渲染在所有图标之上
        iconFlowPane.getChildren().add(selectionRect);
        if (supportsThumbnails() && currentViewMode == ViewMode.ICON) {
            loadThumbnails();
        }
    }

    /**
     * 重建列视图：基于当前路径创建第一列
     */
    protected void rebuildColumnView() {
        if (columnContainer == null) return;
        columnContainer.getChildren().clear();
        columnListViews.clear();
        columnItems.clear();
        columnPaths.clear();

        ObservableList<FileItem> colData = FXCollections.observableArrayList(fileData);
        columnItems.add(colData);
        columnPaths.add(currentPath != null ? currentPath : "/");
        ListView<FileItem> lv = createColumnListView(0);
        columnListViews.add(lv);
        columnContainer.getChildren().add(lv);
    }

    /**
     * 创建一列 ListView
     */
    protected ListView<FileItem> createColumnListView(int colIndex) {
        ListView<FileItem> lv = new ListView<>(columnItems.get(colIndex));
        lv.setPrefWidth(220);
        lv.setMinWidth(180);
        lv.setMaxWidth(220);
        lv.setStyle("-fx-background-color: white; -fx-background-insets: 0; -fx-padding: 0; -fx-border-color: transparent #e5e5e5 transparent transparent; -fx-border-width: 0 1 0 0; -fx-hbar-policy: NEVER;");

        lv.setCellFactory(list -> new ListCell<FileItem>() {
            {
                setStyle("-fx-padding: 4 8;");
            }

            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    HBox row = new HBox(6);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setMaxWidth(Double.MAX_VALUE);
                    ImageView iv = new ImageView(getIconForItem(item, false));
                    iv.setFitWidth(16);
                    iv.setFitHeight(16);
                    Label name = new Label(item.getDisplayName());
                    name.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
                    name.setMaxWidth(Double.MAX_VALUE);
                    name.setWrapText(false);
                    name.setTextOverrun(OverrunStyle.ELLIPSIS);
                    HBox.setHgrow(name, Priority.ALWAYS);
                    row.getChildren().addAll(iv, name);
                    if (item.isDirectory()) {
                        Label arrow = new Label("›");
                        arrow.setStyle("-fx-text-fill: #999; -fx-font-size: 16px;");
                        row.getChildren().add(arrow);
                    }
                    setGraphic(row);
                    setText(null);
                }
            }
        });

        lv.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if (val != null) {
                selectedItem = val;
                onColumnItemSelected(val, colIndex);
            }
        });

        lv.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                FileItem sel = lv.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    handleDoubleClick(sel);
                }
            }
        });

        lv.setContextMenu(createContextMenu());
        return lv;
    }

    /**
     * 列内选中项变化：截断右侧列，若为目录则异步加载子目录到新列
     */
    protected void onColumnItemSelected(FileItem item, int colIndex) {
        truncateColumns(colIndex + 1);
        if (item.isDirectory()) {
            loadColumnAsync(item.getPath(), colIndex + 1);
        }
    }

    /**
     * 截断保留 [0, keepCount) 范围内的列
     */
    protected void truncateColumns(int keepCount) {
        while (columnListViews.size() > keepCount) {
            int last = columnListViews.size() - 1;
            columnContainer.getChildren().remove(columnListViews.get(last));
            columnListViews.remove(last);
            columnItems.remove(last);
            columnPaths.remove(last);
        }
    }

    /**
     * 由子类实现：异步加载子目录到指定列。子类获取数据后应通过 Platform.runLater
     * 调用 {@link #addColumn(int, String, List)} 将新列追加到列视图。
     */
    protected abstract void loadColumnAsync(String path, int colIndex);

    /**
     * 子类在 loadColumnAsync 中获取到目录数据后调用本方法添加新列（须在 JavaFX 线程调用）。
     */
    protected void addColumn(int colIndex, String path, List<FileItem> items) {
        if (currentViewMode != ViewMode.COLUMN) return; // 已切换视图，丢弃
        truncateColumns(colIndex);
        ObservableList<FileItem> colData = FXCollections.observableArrayList(items);
        columnItems.add(colData);
        columnPaths.add(path);
        ListView<FileItem> lv = createColumnListView(colIndex);
        columnListViews.add(lv);
        columnContainer.getChildren().add(lv);
    }

    // ==================== 框选 / 图标选中样式 ====================
    /**
     * 框选时更新选中项
     */
    protected void updateRubberBandSelection(double x, double y, double w, double h, boolean additive) {
        javafx.geometry.Bounds selBounds = new javafx.geometry.BoundingBox(x, y, w, h);
        if (!additive) {
            iconSelectedItems.clear();
        }
        for (javafx.scene.Node node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                FileItem item = (FileItem) box.getProperties().get("fileItem");
                if (item == null) continue;
                // 使用 getBoundsInParent 获取相对于 iconFlowPane 的坐标，与选框坐标系一致
                javafx.geometry.Bounds boxBounds = box.getBoundsInParent();
                if (selBounds.intersects(boxBounds)) {
                    iconSelectedItems.add(item);
                } else if (!additive) {
                    iconSelectedItems.remove(item);
                }
            }
        }
        refreshIconSelectionStyles();
    }

    /**
     * 刷新所有图标项的选中样式
     */
    protected void refreshIconSelectionStyles() {
        for (javafx.scene.Node node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                FileItem item = (FileItem) box.getProperties().get("fileItem");
                if (item == null) continue;
                applyIconSelectionStyle(box, iconSelectedItems.contains(item));
            }
        }
    }

    /**
     * 应用图标项的选中/未选中样式
     */
    protected void applyIconSelectionStyle(VBox box, boolean selected) {
        if (selected) {
            box.setStyle("-fx-background-color: rgba(51,153,255,0.12); -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: #3399ff; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
        } else {
            box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: transparent; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
        }
    }

    /**
     * 清空图标视图选中状态
     */
    protected void clearIconSelection() {
        iconSelectedItems.clear();
        refreshIconSelectionStyles();
    }

    // ==================== 图标项 ====================
    /**
     * 创建图标视图中的单个图标项
     */
    protected VBox createIconBox(FileItem item) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefWidth(90);
        box.setPadding(new Insets(6, 4, 6, 4));
        applyIconSelectionStyle(box, false);
        box.getProperties().put("fileItem", item);

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
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setMaxWidth(82);
        nameLabel.setAlignment(Pos.CENTER);
        box.getChildren().add(nameLabel);

        // 记录点击前已选中的项（用于判断"已选中再点击"进入重命名编辑）
        box.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                // 记录本次点击前该项是否已处于单选状态
                clickedBeforeItem = (iconSelectedItems.size() == 1 && iconSelectedItems.contains(item)) ? item : null;
                // 同步选中（支持 Ctrl/Shift 多选）
                if (e.isControlDown()) {
                    if (iconSelectedItems.contains(item)) {
                        iconSelectedItems.remove(item);
                    } else {
                        iconSelectedItems.add(item);
                    }
                } else if (e.isShiftDown()) {
                    iconSelectedItems.add(item);
                } else {
                    iconSelectedItems.clear();
                    iconSelectedItems.add(item);
                }
                selectedItem = item;
                refreshIconSelectionStyles();
                e.consume();
            }
        });

        // 鼠标点击：双击打开、已选中再单击进入重命名、右键选中（参考 S3）
        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                // 双击：取消重命名定时器，执行打开
                if (e.getClickCount() == 2) {
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }
                    if (editingItem != null) {
                        cancelIconEdit();
                    }
                    handleDoubleClick(item);
                    e.consume();
                    return;
                }

                boolean ctrl = e.isControlDown();
                boolean shift = e.isShiftDown();

                // 单击：判断是否"已选中再点击"以进入重命名编辑
                boolean wasAlreadySelected = !ctrl && !shift
                        && clickedBeforeItem == item
                        && iconSelectedItems.size() == 1
                        && iconSelectedItems.contains(item)
                        && editingItem == null;
                if (wasAlreadySelected) {
                    final FileItem itemToEdit = item;
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                    }
                    singleClickTimer = new javafx.animation.Timeline(
                            new javafx.animation.KeyFrame(Duration.millis(300), ae -> {
                                if (editingItem == null
                                        && iconSelectedItems.size() == 1
                                        && iconSelectedItems.contains(itemToEdit)) {
                                    startIconEdit(itemToEdit);
                                }
                                singleClickTimer = null;
                            }));
                    singleClickTimer.play();
                    e.consume();
                    return;
                }
                // 正常选择已在 mousePressed 中处理
            } else if (e.getButton() == MouseButton.SECONDARY) {
                // 右键时：如果当前没选中才选中它（保持多选状态不变）
                if (!iconSelectedItems.contains(item)) {
                    boolean append = e.isControlDown() || e.isShiftDown();
                    if (!append) {
                        iconSelectedItems.clear();
                    }
                    iconSelectedItems.add(item);
                    selectedItem = item;
                    refreshIconSelectionStyles();
                }
            }
        });

        // 拖拽下载
        box.setOnDragDetected(e -> {
            if (!item.isDirectory()) {
                File tempFile = doDownloadToTemp(item);
                if (tempFile != null) {
                    Dragboard db = box.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(Collections.singletonList(tempFile));
                    db.setContent(content);
                }
            }
            e.consume();
        });

        return box;
    }

    // ==================== 表格行工厂 ====================
    /**
     * 表格行工厂：单击选中、双击打开、已选中再单击进入重命名、右键选中、拖拽上传/下载
     */
    protected void setupTableRowFactory() {
        fileTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (row.isEmpty()) return;
                if (event.getButton() == MouseButton.SECONDARY) {
                    // 右键时先选中该行再弹出菜单
                    if (!fileTable.getSelectionModel().isSelected(row.getIndex())) {
                        fileTable.getSelectionModel().select(row.getItem());
                    }
                    selectedItem = row.getItem();
                    return;
                }
                if (event.getButton() != MouseButton.PRIMARY) return;

                // 双击：取消重命名定时器，执行打开
                if (event.getClickCount() == 2) {
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }
                    if (editingItem != null) {
                        cancelListEdit();
                    }
                    FileItem clicked = row.getItem();
                    if (clicked != null) {
                        handleDoubleClick(clicked);
                    }
                    event.consume();
                    return;
                }

                // 单击：判断是否"已选中再点击"以进入重命名编辑
                if (event.getClickCount() == 1 && !event.isControlDown() && !event.isShiftDown()) {
                    int rowIdx = row.getIndex();
                    // clickedBeforeItem == row.getItem()：确保点击的就是之前已选中的那一项
                    // （与树节点 selectedItem == selectedItemBeforeClick、图标视图 clickedBeforeItem == item 逻辑一致），
                    // 否则"选中A后单击B"会误触发B的重命名
                    boolean wasAlreadySelected = clickedBeforeItem != null
                            && clickedBeforeItem == row.getItem()
                            && editingItem == null
                            && fileTable.getSelectionModel().getSelectedIndices().size() == 1
                            && fileTable.getSelectionModel().isSelected(rowIdx);
                    if (wasAlreadySelected) {
                        final int editRow = rowIdx;
                        if (singleClickTimer != null) {
                            singleClickTimer.stop();
                        }
                        singleClickTimer = new javafx.animation.Timeline(
                                new javafx.animation.KeyFrame(Duration.millis(300), ae -> {
                                    if (editingItem == null
                                            && fileTable.getSelectionModel().getSelectedIndices().size() == 1
                                            && fileTable.getSelectionModel().isSelected(editRow)) {
                                        startListEdit(editRow);
                                    }
                                    singleClickTimer = null;
                                }));
                        singleClickTimer.play();
                        event.consume();
                        return;
                    }
                }

                // 正常单击：记录主选中项
                selectedItem = row.getItem();
            });

            // 拖拽：从远程拖到本地（下载到临时目录后拖出）
            row.setOnDragDetected(e -> {
                if (row.isEmpty()) return;
                FileItem item = row.getItem();
                if (!item.isDirectory()) {
                    File tempFile = doDownloadToTemp(item);
                    if (tempFile != null) {
                        Dragboard db = row.startDragAndDrop(TransferMode.COPY);
                        ClipboardContent content = new ClipboardContent();
                        List<File> files = new ArrayList<>();
                        files.add(tempFile);
                        content.putFiles(files);
                        db.setContent(content);
                    }
                }
                e.consume();
            });

            // 拖拽：从本地拖到远程（上传）
            row.setOnDragOver(e -> {
                Dragboard db = e.getDragboard();
                if (hasUploadableFiles(db)) {
                    e.acceptTransferModes(TransferMode.COPY);
                }
                e.consume();
            });

            row.setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                boolean success = false;
                success = uploadFilesFromClipboard(db);
                e.setDropCompleted(success);
                e.consume();
            });

            return row;
        });

        // 记录点击前已选中的项（用于判断"已选中再点击"进入重命名编辑）
        fileTable.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                var selItems = fileTable.getSelectionModel().getSelectedItems();
                if (selItems.size() == 1 && selItems.get(0) != null) {
                    clickedBeforeItem = selItems.get(0);
                } else {
                    clickedBeforeItem = null;
                }
            }
        });

        // 整个表格也支持拖拽上传
        fileTable.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (hasUploadableFiles(db)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });

        fileTable.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            success = uploadFilesFromClipboard(db);
            e.setDropCompleted(success);
            e.consume();
        });
    }

    // ==================== 可编辑单元格工厂 ====================
    /**
     * 名称列的可编辑单元格工厂：支持内联 TextField 重命名编辑（参考 S3 实现）
     */
    protected Callback<TableColumn<FileItem, String>, TableCell<FileItem, String>> createEditableNameCellFactory() {
        return col -> new TableCell<FileItem, String>() {
            private TextField editField;

            @Override
            public void startEdit() {
                super.startEdit();
                if (isEmpty()) return;
                final FileItem item = getTableView().getItems().get(getIndex());
                editingItem = item;
                editField = new TextField(item.getDisplayName());
                editField.setStyle("-fx-padding: 0 4; -fx-font-size: 12px; -fx-background-color: white; -fx-border-color: #3399ff; -fx-border-radius: 0; -fx-background-radius: 0;");

                editField.setOnAction(e -> commitRenameFromField(editField.getText()));
                editField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                    if (!isNowFocused && editingItem == item) {
                        commitRenameFromField(editField.getText());
                    }
                });
                editField.setOnKeyReleased(e -> {
                    if (e.getCode() == KeyCode.ESCAPE) {
                        cancelListEdit();
                    }
                });

                HBox box = new HBox(6);
                box.setAlignment(Pos.CENTER_LEFT);
                ImageView iv = new ImageView(getIconForItem(item, false));
                iv.setFitWidth(16);
                iv.setFitHeight(16);
                iv.setPreserveRatio(true);
                box.getChildren().add(iv);
                box.getChildren().add(editField);
                setText(null);
                setGraphic(box);
                editField.selectAll();
                Platform.runLater(() -> editField.requestFocus());
            }

            @Override
            public void commitEdit(String newValue) {
                // 实际提交由 commitRenameFromField 处理
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                editingItem = null;
                fileTable.setEditable(false);
                updateItem(getItem(), false);
            }

            private void commitRenameFromField(String newName) {
                if (editingItem == null) {
                    cancelListEdit();
                    return;
                }
                FileItem item = editingItem;
                commitRename(item, newName, () -> cancelListEdit(), () -> refresh());
            }

            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                    setGraphic(null);
                } else if (!isEditing()) {
                    setText(name);
                    FileItem item = getTableView().getItems().get(getIndex());
                    ImageView iv = new ImageView();
                    iv.setFitWidth(16);
                    iv.setFitHeight(16);
                    iv.setImage(getIconForItem(item, false));
                    if (iv.getImage() != null) setGraphic(iv);
                }
            }
        };
    }

    // ==================== 右键菜单 ====================
    /**
     * 创建右键菜单：包含所有可能的菜单项，显示时根据钩子动态控制可见性。
     */
    protected ContextMenu createContextMenu() {
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
            if (selected != null && !selected.isDirectory()) {
                handleDownload(selected);
            }
        });

        // 复制访问地址（仅文件可用，需配置访问URL）
        MenuItem copyUrlItem = new MenuItem("复制访问地址");
        copyUrlItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null && !selected.isDirectory()) {
                handleCopyAccessUrl(selected);
            }
        });

        // 复制菜单项
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> handleCopy());

        // 粘贴菜单项
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> handlePaste());

        // 新建目录
        MenuItem mkdirItem = new MenuItem("新建目录");
        mkdirItem.setOnAction(e -> handleMkdir());

        // 上传文件
        MenuItem uploadItem = new MenuItem("上传文件...");
        uploadItem.setOnAction(e -> handleUpload());

        // 创建文件
        MenuItem createFileItem = new MenuItem("创建文件");
        createFileItem.setOnAction(e -> handleCreateFile());

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> handleDeleteSelected());

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> handleRename());

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refresh());

        Menu viewMenu = new Menu("视图");
        MenuItem iconViewItem = new MenuItem("图标视图");
        iconViewItem.setOnAction(e -> switchViewMode(ViewMode.ICON));
        MenuItem listViewItem = new MenuItem("列表视图");
        listViewItem.setOnAction(e -> switchViewMode(ViewMode.LIST));
        MenuItem columnViewItem = new MenuItem("列视图");
        columnViewItem.setOnAction(e -> switchViewMode(ViewMode.COLUMN));
        viewMenu.getItems().addAll(iconViewItem, listViewItem, columnViewItem);

        menu.getItems().addAll(openItem, previewItem, editMdItem, downloadItem, copyUrlItem, copyItem, pasteItem, new SeparatorMenuItem(),
                mkdirItem, uploadItem, createFileItem, deleteItem, renameItem, new SeparatorMenuItem(), viewMenu, new SeparatorMenuItem(), refreshItem);

        // 右键菜单显示时动态控制各项可见性
        menu.setOnShowing(e -> {
            List<FileItem> selected = getSelectedItems();
            boolean single = selected.size() == 1;
            FileItem first = selected.isEmpty() ? null : selected.get(0);

            openItem.setVisible(single && first != null);
            previewItem.setVisible(supportsImagePreview() && single && first != null && !first.isDirectory() && isImageFile(first.getDisplayName()));
            editMdItem.setVisible(supportsMarkdownEditor() && single && first != null && !first.isDirectory() && isMarkdownFile(first.getDisplayName()));
            downloadItem.setVisible(single && first != null && !first.isDirectory());
            copyUrlItem.setVisible(supportsBuckets() && single && first != null && !first.isDirectory());

            // 复制/粘贴：仅支持复制粘贴的子类可见；本地剪贴板有文件时所有面板都可粘贴上传
            boolean copyPaste = supportsCopyPaste();
            copyItem.setVisible(copyPaste && !selected.isEmpty());
            copyItem.setText(selected.size() > 1 ? "复制(" + selected.size() + "项)" : "复制");
            boolean clipboardHasFiles = hasUploadableFiles(Clipboard.getSystemClipboard());
            pasteItem.setVisible(clipboardHasFiles || (copyPaste && hasCopyData()));
            pasteItem.setText(clipboardHasFiles ? "粘贴上传文件" : "粘贴");

            createFileItem.setVisible(supportsCreateFile());
            deleteItem.setVisible(!selected.isEmpty());
            deleteItem.setText(selected.size() > 1 ? "删除(" + selected.size() + "项)" : "删除");
            // Bucket 不支持重命名
            renameItem.setVisible(single && first != null && !(supportsBuckets() && first.isBucket()));
            // 列视图菜单项可见性
            columnViewItem.setVisible(supportsColumnView());
        });

        return menu;
    }

    /**
     * 获取当前选中项（按当前视图模式分派）
     */
    protected FileItem getSelectedItem() {
        if (currentViewMode == ViewMode.LIST) {
            return fileTable.getSelectionModel().getSelectedItem();
        } else if (currentViewMode == ViewMode.COLUMN) {
            for (ListView<FileItem> lv : columnListViews) {
                FileItem sel = lv.getSelectionModel().getSelectedItem();
                if (sel != null) return sel;
            }
            return selectedItem;
        }
        // 图标视图
        return selectedItem;
    }

    /**
     * 获取所有选中项（支持多选：图标视图框选、列表视图 Ctrl/Shift 多选）
     */
    protected List<FileItem> getSelectedItems() {
        if (currentViewMode == ViewMode.LIST) {
            return new ArrayList<>(fileTable.getSelectionModel().getSelectedItems());
        } else if (currentViewMode == ViewMode.COLUMN) {
            List<FileItem> items = new ArrayList<>();
            for (ListView<FileItem> lv : columnListViews) {
                FileItem sel = lv.getSelectionModel().getSelectedItem();
                if (sel != null) items.add(sel);
            }
            if (items.isEmpty() && selectedItem != null) {
                items.add(selectedItem);
            }
            return items;
        }
        // 图标视图
        if (!iconSelectedItems.isEmpty()) {
            return new ArrayList<>(iconSelectedItems);
        }
        if (selectedItem != null) {
            return new ArrayList<>(Collections.singletonList(selectedItem));
        }
        return new ArrayList<>();
    }

    /**
     * 双击/打开处理：目录则进入，图片则预览（若支持），Markdown 则编辑（若支持）。
     * 子类可覆盖以扩展普通文件的打开行为。
     */
    protected void handleDoubleClick(FileItem item) {
        if (item == null) return;
        if (item.isDirectory()) {
            navigateTo(item.getPath());
        } else if (supportsImagePreview() && isImageFile(item.getDisplayName())) {
            handlePreview();
        } else if (supportsMarkdownEditor() && isMarkdownFile(item.getDisplayName())) {
            openMarkdownEditor(item);
        } else {
            openFileLocally(item);
        }
    }

    /**
     * 打开普通文件（如下载到临时目录后用本地应用打开）。默认空实现，子类按需覆盖。
     */
    protected void openFileLocally(FileItem item) {}

    // ==================== 重命名编辑 ====================
    /**
     * 右键菜单"重命名"入口：按当前视图分派到对应的内联编辑。
     * Bucket 不支持重命名（S3/OSS Bucket 名称不可修改）。
     */
    protected void handleRename() {
        FileItem selected = getSelectedItem();
        if (selected == null) return;
        if (supportsBuckets() && selected.isBucket()) {
            Alert a = new Alert(Alert.AlertType.WARNING);
            a.setTitle("重命名");
            a.setHeaderText(null);
            a.setContentText("Bucket不支持重命名");
            DialogPositionUtil.centerOnOwner(a, this);
            a.showAndWait();
            return;
        }
        if (currentViewMode == ViewMode.ICON) {
            startIconEdit(selected);
        } else if (currentViewMode == ViewMode.LIST) {
            int idx = fileTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0) startListEdit(idx);
        } else if (currentViewMode == ViewMode.COLUMN) {
            startColumnEdit(selected);
        }
    }

    /** 列表视图：在指定行号上启动名称列内联编辑 */
    protected void startListEdit(int row) {
        if (editingItem != null) return;
        if (row < 0 || row >= fileTable.getItems().size()) return;
        fileTable.setEditable(true);
        fileTable.edit(row, fileTable.getColumns().get(0));
    }

    /** 取消列表视图编辑 */
    protected void cancelListEdit() {
        editingItem = null;
        fileTable.setEditable(false);
        fileTable.edit(-1, null);
    }

    /**
     * 图标视图：用 Popup 浮窗悬浮在名称 Label 上方编辑（不参与布局、不受裁剪）
     */
    protected void startIconEdit(FileItem item) {
        if (editingItem != null) return;
        VBox box = null;
        for (javafx.scene.Node node : iconFlowPane.getChildren()) {
            if (node instanceof VBox v && item.equals(v.getProperties().get("fileItem"))) {
                box = v;
                break;
            }
        }
        if (box == null) return;
        editingItem = item;
        // 编辑时图标不显示选中样式
        applyIconSelectionStyle(box, false);

        // 找到名称 Label（图标 ImageView 之后的第一个 Label）
        Label nameLabel = null;
        for (int i = 0; i < box.getChildren().size(); i++) {
            if (box.getChildren().get(i) instanceof Label) {
                nameLabel = (Label) box.getChildren().get(i);
                break;
            }
        }
        if (nameLabel == null) { editingItem = null; return; }

        javafx.geometry.Bounds labelSceneBounds = nameLabel.localToScene(nameLabel.getBoundsInLocal());
        javafx.scene.Scene scene = nameLabel.getScene();
        if (scene == null || scene.getWindow() == null) { editingItem = null; return; }
        Window window = scene.getWindow();
        double screenX = window.getX() + scene.getX() + labelSceneBounds.getMinX();
        double screenY = window.getY() + scene.getY() + labelSceneBounds.getMinY();

        iconEditField = new TextField(item.getDisplayName());
        iconEditField.setStyle("-fx-padding: 0 6; -fx-font-size: 11px; -fx-background-color: white; -fx-border-color: #3399ff; -fx-border-width: 1.5; -fx-border-radius: 0; -fx-background-radius: 0;");
        // 宽度按内容计算，允许比 Label 宽
        Text measureText = new Text(item.getDisplayName());
        measureText.setFont(Font.font(11));
        double contentWidth = measureText.getLayoutBounds().getWidth() + 20;
        double labelW = nameLabel.getWidth();
        if (labelW <= 0) labelW = nameLabel.prefWidth(-1);
        double fieldWidth = Math.max(contentWidth, labelW);
        double labelH = nameLabel.getHeight();
        if (labelH <= 0) labelH = nameLabel.prefHeight(-1);
        double fieldHeight = (labelH + 8) * 0.6;
        iconEditField.setPrefWidth(fieldWidth);
        iconEditField.setPrefHeight(fieldHeight);
        iconEditField.setMinWidth(fieldWidth);
        iconEditField.setAlignment(Pos.CENTER);

        iconEditField.setOnAction(e -> commitRename(item, iconEditField.getText(), this::cancelIconEdit, this::refresh));
        iconEditField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused && editingItem == item) {
                commitRename(item, iconEditField.getText(), this::cancelIconEdit, this::refresh);
            }
        });
        iconEditField.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelIconEdit();
            }
        });

        iconEditPopup = new Popup();
        iconEditPopup.setAutoFix(false);
        iconEditPopup.setAutoHide(true);
        iconEditPopup.setHideOnEscape(false);
        double offsetX = (fieldWidth - labelW) / 2.0;
        double offsetY = (labelH - fieldHeight) / 2.0;
        iconEditPopup.getContent().add(iconEditField);
        iconEditPopup.show(window, screenX - offsetX, screenY + offsetY);
        iconEditField.selectAll();
        Platform.runLater(() -> iconEditField.requestFocus());
    }

    /** 取消图标视图编辑：隐藏并移除 Popup，清除选中状态 */
    protected void cancelIconEdit() {
        if (editingItem == null) return;
        editingItem = null;
        if (iconEditPopup != null) {
            iconEditPopup.hide();
            iconEditPopup = null;
            iconEditField = null;
        }
        clearIconSelection();
    }

    /**
     * 列视图：用 Popup 浮窗悬浮在选中单元格上方编辑
     */
    protected void startColumnEdit(FileItem item) {
        if (editingItem != null) return;
        ListView<FileItem> targetLv = null;
        int targetColIndex = -1;
        for (int i = 0; i < columnListViews.size(); i++) {
            ListView<FileItem> lv = columnListViews.get(i);
            FileItem sel = lv.getSelectionModel().getSelectedItem();
            if (item.equals(sel)) {
                targetLv = lv;
                targetColIndex = i;
                break;
            }
        }
        if (targetLv == null) return;

        ListCell<FileItem> targetCell = null;
        for (var node : targetLv.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> c && item.equals(c.getItem())) {
                @SuppressWarnings("unchecked")
                ListCell<FileItem> cast = (ListCell<FileItem>) c;
                targetCell = cast;
                break;
            }
        }
        if (targetCell == null) return;
        editingItem = item;

        javafx.geometry.Bounds cellSceneBounds = targetCell.localToScene(targetCell.getBoundsInLocal());
        javafx.scene.Scene scene = targetCell.getScene();
        if (scene == null || scene.getWindow() == null) { editingItem = null; return; }
        Window window = scene.getWindow();
        double screenX = window.getX() + scene.getX() + cellSceneBounds.getMinX();
        double screenY = window.getY() + scene.getY() + cellSceneBounds.getMinY();

        iconEditField = new TextField(item.getDisplayName());
        iconEditField.setStyle("-fx-padding: 0 6; -fx-font-size: 12px; -fx-background-color: white; -fx-border-color: #3399ff; -fx-border-width: 1.5; -fx-border-radius: 0; -fx-background-radius: 0;");
        double cellW = targetCell.getWidth();
        double cellH = targetCell.getHeight();
        iconEditField.setPrefWidth(Math.max(cellW - 8, 80));
        iconEditField.setPrefHeight(cellH * 0.6);
        iconEditField.setMinWidth(80);

        final int colIdx = targetColIndex;
        iconEditField.setOnAction(e -> commitRename(item, iconEditField.getText(), this::cancelColumnEdit, () -> reloadColumn(colIdx)));
        iconEditField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused && editingItem == item) {
                commitRename(item, iconEditField.getText(), this::cancelColumnEdit, () -> reloadColumn(colIdx));
            }
        });
        iconEditField.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelColumnEdit();
            }
        });

        iconEditPopup = new Popup();
        iconEditPopup.setAutoFix(false);
        iconEditPopup.setAutoHide(true);
        iconEditPopup.setHideOnEscape(false);
        iconEditPopup.getContent().add(iconEditField);
        iconEditPopup.show(window, screenX + 4, screenY + (cellH - cellH * 0.6) / 2.0);
        iconEditField.selectAll();
        Platform.runLater(() -> iconEditField.requestFocus());
    }

    /** 取消列视图编辑 */
    protected void cancelColumnEdit() {
        if (editingItem == null) return;
        editingItem = null;
        if (iconEditPopup != null) {
            iconEditPopup.hide();
            iconEditPopup = null;
            iconEditField = null;
        }
    }

    /**
     * 重载列视图指定列（重命名成功后刷新该列数据）
     */
    protected void reloadColumn(int colIdx) {
        if (colIdx < 0 || colIdx >= columnPaths.size()) {
            refresh();
            return;
        }
        loadColumnAsync(columnPaths.get(colIdx), colIdx);
    }

    /**
     * 提交重命名：校验名称后异步执行后端 rename。
     * 名称校验后先调用 onCancel 恢复 UI，再在后台线程调用 {@link #doRename}，
     * 成功后 Platform.runLater 调用 onSuccess。每个子类的 doRename 负责自行计算
     * 新的完整路径/key 并执行后端重命名。
     */
    protected void commitRename(FileItem item, String newName, Runnable onCancel, Runnable onSuccess) {
        if (editingItem == null) return;
        // 去除目录名末尾的 "/"（目录 displayName 带 "/"）
        String name = newName == null ? "" : newName.trim();
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty() || name.equals(item.getName())) {
            onCancel.run();
            return;
        }
        if (name.contains("/")) {
            onCancel.run();
            Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.WARNING);
                a.setTitle("重命名失败");
                a.setHeaderText(null);
                a.setContentText("名称不能包含 \"/\"");
                DialogPositionUtil.centerOnOwner(a, this);
                a.showAndWait();
            });
            return;
        }

        final FileItem editItem = item;
        final String finalNewName = name;
        // 先恢复 UI，再异步重命名
        onCancel.run();

        new Thread(() -> {
            try {
                doRename(editItem, finalNewName);
                Platform.runLater(() -> {
                    if (onSuccess != null) onSuccess.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("重命名失败: " + e.getMessage());
                    if (onSuccess != null) onSuccess.run();
                });
            }
        }, "File-Rename").start();
    }

    // ==================== 导航 / 刷新（委托子类后端实现） ====================
    /**
     * 导航到指定路径：检查连接后委托子类实现。
     */
    public void navigateTo(String path) {
        if (!isConnected()) {
            statusLabel.setText("未连接");
            return;
        }
        doNavigateTo(path);
    }

    /**
     * 刷新当前目录。
     */
    public void refresh() {
        doRefresh();
    }

    /**
     * 返回上级目录。默认按路径字符串截断，S3 等基于 bucket/prefix 的子类可覆盖。
     */
    protected void navigateUp() {
        if (currentPath == null || currentPath.equals("/")) return;
        int lastSlash = currentPath.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : currentPath.substring(0, lastSlash);
        navigateTo(parent);
    }

    // ==================== 上传 / 下载 / 删除 / 新建目录 ====================
    /**
     * 上传文件：打开文件选择器，选择后委托子类上传。
     */
    protected void handleUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要上传的文件");
        List<File> files = chooser.showOpenMultipleDialog(getStage());
        if (files == null || files.isEmpty()) return;
        doUpload(files);
    }

    private static final String WINDOWS_VIRTUAL_FILE_MIME = "message/external-body";

    /** JavaFX exposes Windows ZIP-folder entries as indexed FILECONTENTS data formats. */
    protected boolean hasUploadableFiles(Clipboard clipboard) {
        if (clipboard == null) return false;
        if (clipboard.hasFiles() && clipboard.getFiles() != null && !clipboard.getFiles().isEmpty()) {
            return true;
        }
        return clipboard.getContentTypes().stream().anyMatch(this::isWindowsVirtualFileFormat);
    }

    /**
     * Resolves regular file-list data and Windows virtual FILECONTENTS data, then starts upload.
     * Reading happens synchronously because virtual drag data is valid only during the drop call.
     */
    protected boolean uploadFilesFromClipboard(Clipboard clipboard) {
        if (!hasUploadableFiles(clipboard)) return false;
        try {
            List<File> files = new ArrayList<>();
            if (clipboard.hasFiles() && clipboard.getFiles() != null) {
                files.addAll(clipboard.getFiles());
            }
            // Shell IDList retains the hierarchy of directories dragged out of ZIP folders.
            // FILECONTENTS descriptors may enumerate only leaf files, which would flatten the
            // tree before SFTP sees it, so prefer the Shell representation whenever available.
            byte[] shellIdList = readShellIdList(clipboard);
            if (shellIdList != null) {
                return uploadWindowsVirtualFiles(shellIdList);
            }
            files.addAll(materializeWindowsVirtualFiles(clipboard));
            if (files.isEmpty()) return false;
            doUpload(files);
            return true;
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("上传失败");
            alert.setHeaderText("无法读取压缩包中的文件");
            alert.setContentText(ex.getMessage() != null ? ex.getMessage() : ex.toString());
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return false;
        }
    }

    private byte[] readShellIdList(Clipboard clipboard) {
        for (DataFormat format : clipboard.getContentTypes()) {
            boolean shellIdList = format.getIdentifiers().stream()
                    .anyMatch(id -> id.equalsIgnoreCase("Shell IDList Array"));
            if (!shellIdList) continue;
            Object value = clipboard.getContent(format);
            if (value instanceof ByteBuffer buffer) {
                ByteBuffer copy = buffer.slice();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                return bytes;
            }
            if (value instanceof byte[] bytes) return bytes;
        }
        return null;
    }

    private boolean isWindowsVirtualFileFormat(DataFormat format) {
        return format.getIdentifiers().stream().anyMatch(id ->
                id.toLowerCase(java.util.Locale.ROOT).startsWith(WINDOWS_VIRTUAL_FILE_MIME));
    }

    private List<File> materializeWindowsVirtualFiles(Clipboard clipboard) throws IOException {
        List<DataFormat> formats = clipboard.getContentTypes().stream()
                .filter(this::isWindowsVirtualFileFormat)
                .sorted(java.util.Comparator.comparingInt(this::virtualFileIndex))
                .toList();
        if (formats.isEmpty()) return List.of();

        Path root = Files.createTempDirectory("tomato-upload-");
        List<File> files = new ArrayList<>(formats.size());
        try {
            for (DataFormat format : formats) {
                Object value = clipboard.getContent(format);
                if (value == null && formats.size() == 1 && virtualFileIndex(format) == 0) {
                    // Several ZIP shell extensions expose a single FILECONTENTS stream with
                    // lindex=-1. OpenJFX synthesizes an index=0 MIME and consequently receives
                    // DV_E_FORMATETC. Ask Glass for the provider's advertised index explicitly.
                    String fallbackId = WINDOWS_VIRTUAL_FILE_MIME
                            + ";access-type=clipboard;index=-1";
                    DataFormat fallback = DataFormat.lookupMimeType(fallbackId);
                    if (fallback == null) fallback = new DataFormat(fallbackId);
                    value = clipboard.getContent(fallback);
                }
                byte[] bytes;
                if (value instanceof ByteBuffer buffer) {
                    ByteBuffer copy = buffer.slice();
                    bytes = new byte[copy.remaining()];
                    copy.get(bytes);
                } else if (value instanceof byte[] array) {
                    bytes = array;
                } else {
                    continue;
                }
                String name = virtualFileName(format);
                Path target = resolveVirtualFileTarget(root, name, virtualFileIndex(format));
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                Path uploadRoot = firstPathSegment(root, target);
                File uploadFile = uploadRoot.toFile();
                if (!files.contains(uploadFile)) files.add(uploadFile);
            }
            if (files.isEmpty()) {
                deleteUploadTempDirectory(root);
            }
            return files;
        } catch (IOException | RuntimeException ex) {
            deleteUploadTempDirectory(root);
            throw ex;
        }
    }

    private int virtualFileIndex(DataFormat format) {
        String id = virtualFileIdentifier(format);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|;)index=(\\d+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(id);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private String virtualFileName(DataFormat format) {
        String id = virtualFileIdentifier(format);
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("(?:^|;)name=\"([^\"]*)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(id);
        String rawName = quoted.find() ? quoted.group(1) : "file-" + virtualFileIndex(format);
        rawName = rawName.replace('\\', '/').trim();
        return rawName.isEmpty() ? "file-" + virtualFileIndex(format) : rawName;
    }

    private Path resolveVirtualFileTarget(Path root, String descriptorName, int index) {
        String relativeName = descriptorName.replace('\\', '/');
        // ZIP providers may expose an absolute-looking name. It is still archive-relative here.
        relativeName = relativeName.replaceFirst("^[A-Za-z]:", "");
        while (relativeName.startsWith("/")) relativeName = relativeName.substring(1);
        Path target = root.resolve(relativeName).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            target = root.resolve("file-" + index);
        }
        return target;
    }

    private Path firstPathSegment(Path root, Path target) {
        Path relative = root.relativize(target);
        return relative.getNameCount() > 1 ? root.resolve(relative.getName(0)) : target;
    }

    private String virtualFileIdentifier(DataFormat format) {
        return format.getIdentifiers().stream()
                .filter(id -> id.toLowerCase(java.util.Locale.ROOT).startsWith(WINDOWS_VIRTUAL_FILE_MIME))
                .findFirst().orElse("");
    }

    /**
     * 上传文件列表到当前远程目录：基类统一负责进度对话框、后台线程、逐文件调用
     * {@link #doUploadSingle(File)}，子类只需实现单文件同步上传（失败抛异常）。
     * <p>支持取消：点击取消按钮或关闭进度窗口会停止后续文件上传。
     */
    protected void doUpload(List<File> files) {
        if (files == null || files.isEmpty()) return;
        String err = preUploadCheck();
        if (err != null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText(err);
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        if (!isConnected()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("未连接，无法上传");
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        final UploadFiles uploadFiles;
        try {
            // Windows Explorer exposes entries copied/dragged directly from a ZIP as temporary
            // files.  Explorer may remove them as soon as this event handler returns, while the
            // actual upload below deliberately runs on a background thread.  Take ownership of
            // those files before returning from the drop/paste callback.
            uploadFiles = stabilizeTemporaryUploadFiles(files);
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("上传失败");
            alert.setHeaderText("无法读取压缩包中的文件");
            alert.setContentText(ex.getMessage());
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        final List<File> stableFiles = uploadFiles.files();
        final List<UploadEntry> uploadEntries;
        try {
            uploadEntries = buildUploadEntries(stableFiles);
        } catch (IOException ex) {
            deleteUploadTempDirectory(uploadFiles.tempDirectory());
            setStatus("读取上传目录失败: " + ex.getMessage());
            return;
        }
        final int total = uploadEntries.size();
        uploadCancelled.set(false);
        showUploadProgressDialog(total);
        setStatus("上传中... (0/" + total + ")");
        new Thread(() -> {
            int success = 0, failed = 0;
            String lastError = null;
            for (int i = 0; i < total; i++) {
                if (uploadCancelled.get()) break;
                UploadEntry entry = uploadEntries.get(i);
                File file = entry.file();
                try {
                    if (entry.directory()) {
                        doUploadDirectory(entry.relativePath(), entry.emptyDirectory());
                    } else {
                        doUploadSingle(file, entry.relativePath());
                    }
                    success++;
                } catch (Exception ex) {
                    failed++;
                    lastError = ex.getMessage();
                    if (lastError == null) lastError = ex.toString();
                }
                final int done = success + failed;
                final String name = entry.relativePath();
                final long size = file.length();
                Platform.runLater(() -> updateUploadProgress(done, total, name, size));
            }
            final int okCount = success;
            final int failCount = failed;
            final String err2 = lastError;
            final boolean cancelled = uploadCancelled.get();
            Platform.runLater(() -> {
                hideUploadProgressDialog();
                if (cancelled && (okCount + failCount) < total) {
                    setStatus("上传已取消: 已完成 " + okCount + " 个");
                } else if (failCount == 0) {
                    setStatus("上传完成: 成功 " + okCount + " 个");
                } else {
                    setStatus("上传结束: 成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("部分上传失败");
                    alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    alert.setContentText(err2 != null ? err2 : "");
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                }
                refresh();
            });
            deleteUploadTempDirectory(uploadFiles.tempDirectory());
        }, "Upload").start();
    }

    private List<UploadEntry> buildUploadEntries(List<File> roots) throws IOException {
        List<UploadEntry> entries = new ArrayList<>();
        for (File rootFile : roots) {
            if (rootFile == null || !rootFile.exists()) continue;
            Path root = rootFile.toPath();
            if (Files.isDirectory(root)) {
                Path parent = root.getParent();
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.toList()) {
                        String relative = parent.relativize(path).toString().replace(File.separatorChar, '/');
                        boolean directory = Files.isDirectory(path);
                        boolean emptyDirectory = false;
                        if (directory) {
                            try (var children = Files.list(path)) {
                                emptyDirectory = children.findAny().isEmpty();
                            }
                        }
                        entries.add(new UploadEntry(path.toFile(), relative, directory, emptyDirectory));
                    }
                }
            } else {
                entries.add(new UploadEntry(rootFile, rootFile.getName(), false, false));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Copies files supplied from the operating-system temp directory to a directory owned by
     * this upload.  In particular this keeps Windows ZIP-folder virtual files alive after the
     * drag/drop or clipboard operation has completed.  Ordinary files are uploaded in place.
     */
    private UploadFiles stabilizeTemporaryUploadFiles(List<File> files) throws IOException {
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path uploadTemp = null;
        List<File> stableFiles = new ArrayList<>(files.size());
        try {
            for (int i = 0; i < files.size(); i++) {
                File file = files.get(i);
                if (file == null) continue;
                Path source = file.toPath().toAbsolutePath().normalize();
                Path ownedRoot = findOwnedUploadRoot(source, systemTemp);
                if (ownedRoot != null) {
                    if (uploadTemp == null) uploadTemp = ownedRoot;
                    stableFiles.add(file);
                } else if ((Files.isRegularFile(source) || Files.isDirectory(source))
                        && source.startsWith(systemTemp)) {
                    if (uploadTemp == null) {
                        uploadTemp = Files.createTempDirectory("tomato-upload-");
                    }
                    Path itemDirectory = Files.createDirectory(uploadTemp.resolve(Integer.toString(i)));
                    Path stable = itemDirectory.resolve(file.getName());
                    if (Files.isDirectory(source)) {
                        copyDirectoryTree(source, stable);
                    } else {
                        Files.copy(source, stable, StandardCopyOption.REPLACE_EXISTING);
                    }
                    stableFiles.add(stable.toFile());
                } else {
                    stableFiles.add(file);
                }
            }
            return new UploadFiles(List.copyOf(stableFiles), uploadTemp);
        } catch (IOException | RuntimeException ex) {
            deleteUploadTempDirectory(uploadTemp);
            throw ex;
        }
    }

    private void copyDirectoryTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path findOwnedUploadRoot(Path source, Path systemTemp) {
        Path current = source.getParent();
        while (current != null && current.startsWith(systemTemp)) {
            Path name = current.getFileName();
            if (name != null && name.toString().startsWith("tomato-upload-")) return current;
            if (current.equals(systemTemp)) break;
            current = current.getParent();
        }
        return null;
    }

    private void deleteUploadTempDirectory(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }

    private record UploadFiles(List<File> files, Path tempDirectory) {}
    private record UploadEntry(File file, String relativePath, boolean directory, boolean emptyDirectory) {}

    // ==================== 上传进度对话框 ====================
    private void showUploadProgressDialog(int total) {
        uploadProgressStage = new Stage();
        uploadProgressStage.setTitle(total > 0 ? "上传文件 (" + total + " 个)" : "上传文件");
        uploadProgressStage.setWidth(500);
        uploadProgressStage.setHeight(190);
        uploadProgressStage.setResizable(false);
        uploadProgressStage.initOwner(getStage());

        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(15));
        vbox.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label("正在上传...");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        uploadProgressLabel = new Label("准备上传...");
        uploadProgressLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
        uploadProgressLabel.setMaxWidth(470);
        uploadProgressLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        uploadProgressBar = new ProgressBar(0);
        uploadProgressBar.setMaxWidth(Double.MAX_VALUE);
        uploadProgressBar.setStyle("-fx-accent: #07c160;");

        uploadProgressDetailLabel = new Label("");
        uploadProgressDetailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        uploadProgressDetailLabel.setMaxWidth(470);
        uploadProgressDetailLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd;");
        cancelBtn.setOnAction(e -> uploadCancelled.set(true));

        HBox buttonBox = new HBox();
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getChildren().add(cancelBtn);

        vbox.getChildren().addAll(titleLabel, uploadProgressLabel, uploadProgressBar, uploadProgressDetailLabel, buttonBox);
        uploadProgressStage.setScene(new Scene(vbox));
        uploadProgressStage.setOnCloseRequest(e -> {
            uploadCancelled.set(true);
            e.consume();
        });
        DialogPositionUtil.centerOnOwner(uploadProgressStage, this);
        uploadProgressStage.show();
    }

    private void hideUploadProgressDialog() {
        if (uploadProgressStage != null) {
            uploadProgressStage.close();
            uploadProgressStage = null;
        }
    }

    private void updateUploadProgress(int done, int total, String fileName, long fileSize) {
        if (uploadProgressBar == null || uploadProgressLabel == null) return;
        double progress = total > 0 ? (double) done / total : 0;
        uploadProgressBar.setProgress(progress);
        uploadProgressLabel.setText(String.format("正在上传: %s (%d/%d)", fileName, done, total));
        if (uploadProgressDetailLabel != null) {
            uploadProgressDetailLabel.setText(String.format("%s · 大小 %s",
                    done >= total ? "完成" : "传输中...", formatFileSize(fileSize)));
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 注册键盘快捷键：Ctrl+C 复制（子类可覆盖 handleCopy）、Ctrl+V 粘贴（上传系统剪贴板中的本地文件）。
     * 在文本输入控件聚焦时由其自行处理，accelerator 不会被触发。
     */
    protected void setupKeyboardShortcuts() {
        // Do not register these on Scene.getAccelerators(): every open S3/SFTP tab uses the
        // same key combinations, so the last-created (possibly hidden) tab overwrites the active
        // tab's handler.  A capture filter on this pane only runs when the key event belongs to
        // the visible file browser.
        shortcutKeyFilter = event -> {
            if (!event.isShortcutDown() || event.isAltDown()) return;
            if (!isActuallyVisible()) return;
            Node focusOwner = getScene() != null ? getScene().getFocusOwner() : null;
            if (focusOwner instanceof TextInputControl) return;
            if (event.getCode() == KeyCode.C) {
                handleCopy();
                event.consume();
            } else if (event.getCode() == KeyCode.V) {
                handlePaste();
                event.consume();
            }
        };
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (shortcutScene != null) {
                shortcutScene.removeEventFilter(KeyEvent.KEY_PRESSED, shortcutKeyFilter);
            }
            shortcutScene = newScene;
            if (newScene != null) {
                newScene.addEventFilter(KeyEvent.KEY_PRESSED, shortcutKeyFilter);
            }
        });
        if (getScene() != null) {
            shortcutScene = getScene();
            shortcutScene.addEventFilter(KeyEvent.KEY_PRESSED, shortcutKeyFilter);
        }
    }

    private boolean isActuallyVisible() {
        Node node = this;
        while (node != null) {
            if (!node.isVisible()) return false;
            node = node.getParent();
        }
        return getScene() != null && getScene().getWindow() != null && getScene().getWindow().isShowing();
    }

    /**
     * 下载文件：选择保存目录后委托子类下载。
     */
    protected void handleDownload(FileItem item) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择保存目录");
        File dir = chooser.showDialog(getStage());
        if (dir == null) return;
        File localFile = new File(dir, item.getName());
        statusLabel.setText("下载中: " + item.getName());
        doDownload(item, localFile);
    }

    /**
     * 复制访问地址到系统剪贴板。
     */
    protected void handleCopyAccessUrl(FileItem item) {
        String url = getAccessUrl(item);
        if (url == null || url.trim().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("尚未配置访问URL，请在连接配置中设置访问URL字段");
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(url);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("已复制访问地址: " + url);
    }

    /**
     * 删除选中项：收集所有选中项，一次确认后逐项委托子类执行。
     */
    protected void handleDeleteSelected() {
        List<FileItem> selected = getSelectedItems();
        if (selected.isEmpty()) return;
        String msg = selected.size() == 1
                ? "确定要删除 \"" + selected.get(0).getName() + "\" 吗？"
                : "确定要删除选中的 " + selected.size() + " 个文件/目录吗？";
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("删除确认");
        alert.setHeaderText(msg);
        DialogPositionUtil.centerOnOwner(alert, this);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                for (FileItem item : selected) {
                    doDelete(item);
                }
            }
        });
    }

    /**
     * 删除单个文件项：确认后委托子类执行。
     */
    protected void handleDelete(FileItem item) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("删除确认");
        alert.setHeaderText("确定要删除 \"" + item.getName() + "\" 吗？");
        DialogPositionUtil.centerOnOwner(alert, this);
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                doDelete(item);
            }
        });
    }

    /**
     * 新建目录：输入名称后委托子类创建。
     * 计算完整路径（currentPath + name），子类据此执行后端操作。
     */
    protected void handleMkdir() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建目录");
        dialog.setHeaderText("输入目录名称:");
        DialogPositionUtil.centerOnOwner(dialog, this);
        dialog.showAndWait().ifPresent(name -> {
            String dirName = name.trim();
            if (dirName.isEmpty()) return;
            String fullPath = currentPath.endsWith("/") ? currentPath + dirName : currentPath + "/" + dirName;
            doMkdir(fullPath);
        });
    }

    // ==================== 工具方法 ====================
    protected Stage getStage() {
        return (Stage) getScene().getWindow();
    }

    /**
     * 设置当前路径并同步路径输入框。
     */
    protected void setCurrentPath(String path) {
        this.currentPath = path;
        if (currentPathField != null) {
            currentPathField.setText(path);
        }
    }

    /**
     * 设置文件列表数据并按当前视图模式重建图标/列视图。子类在获取到目录数据后调用。
     */
    protected void setFileList(List<FileItem> items) {
        fileData.setAll(items);
        if (currentViewMode == ViewMode.ICON) {
            rebuildIconView();
        } else if (currentViewMode == ViewMode.COLUMN) {
            rebuildColumnView();
        }
    }

    /**
     * 设置状态栏文本。
     */
    protected void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    protected ObservableList<FileItem> getFileData() {
        return fileData;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public ViewMode getCurrentViewMode() {
        return currentViewMode;
    }

    /**
     * 判断文件名是否为图片
     */
    protected boolean isImageFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 判断文件名是否为 Markdown 文件
     */
    protected boolean isMarkdownFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return MARKDOWN_EXTENSIONS.contains(ext);
    }

    // ==================== 图标加载 ====================
    protected Image loadIcon(String path) {
        try {
            return new Image(getClass().getResourceAsStream(path));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据扩展名获取系统文件图标（通过创建临时文件获取）
     */
    protected Image getSystemFileIcon(String ext) {
        if (ext == null || ext.isEmpty()) return null;
        ext = ext.toLowerCase();
        // 先查缓存
        if (systemIconCache.containsKey(ext)) return systemIconCache.get(ext);
        try {
            File tmp = new File(iconTempDir, "icon." + ext);
            if (!tmp.exists()) tmp.createNewFile();
            javax.swing.Icon icon = FileSystemView.getFileSystemView().getSystemIcon(tmp);
            Image fxImage = swingIconToImage(icon);
            if (fxImage != null) {
                systemIconCache.put(ext, fxImage);
                return fxImage;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 获取系统大尺寸文件图标（按扩展名）
     */
    protected Image getLargeSystemFileIcon(String ext) {
        if (ext == null || ext.isEmpty()) return null;
        ext = ext.toLowerCase();
        if (systemLargeIconCache.containsKey(ext)) return systemLargeIconCache.get(ext);
        try {
            File tmp = new File(iconTempDir, "icon." + ext);
            if (!tmp.exists()) tmp.createNewFile();
            Image big = getLargeShellFolderIcon(tmp);
            if (big != null) {
                systemLargeIconCache.put(ext, big);
                return big;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 获取系统大尺寸文件夹图标（Windows ShellFolder 大图标）
     */
    protected Image getLargeSystemFolderIcon() {
        return getLargeShellFolderIcon(iconTempDir);
    }

    /**
     * 获取大尺寸系统图标（48x48）。
     * 通过反射调用 sun.awt.shell.ShellFolder 获取系统大图标（避免编译期依赖内部 API）；
     * 回退：FileSystemView 小图标高清重采样到 48px。
     */
    protected Image getLargeShellFolderIcon(File f) {
        // 方案1：反射调用 ShellFolder.getShellFolder(file).getIcon(true) 获取系统大图标
        try {
            Class<?> shellFolderClass = Class.forName("sun.awt.shell.ShellFolder");
            java.lang.reflect.Method getShellFolder = shellFolderClass.getMethod("getShellFolder", File.class);
            getShellFolder.setAccessible(true);
            Object sf = getShellFolder.invoke(null, f);
            java.lang.reflect.Method getIcon = shellFolderClass.getMethod("getIcon", boolean.class);
            getIcon.setAccessible(true);
            java.awt.Image awtImg = (java.awt.Image) getIcon.invoke(sf, Boolean.TRUE);
            if (awtImg != null) {
                return scaleToHighDpi(awtImg, 48);
            }
        } catch (Throwable ignored) {}
        // 方案2：FileSystemView 小图标高清重采样到 48px
        try {
            javax.swing.Icon icon = FileSystemView.getFileSystemView().getSystemIcon(f);
            if (icon != null) {
                BufferedImage bi = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = bi.createGraphics();
                icon.paintIcon(null, g, 0, 0);
                g.dispose();
                return scaleToHighDpi(bi, 48);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 将 AWT Image 高质量缩放到指定尺寸，返回 JavaFX Image
     */
    protected Image scaleToHighDpi(java.awt.Image awtImg, int targetSize) {
        try {
            int srcW = awtImg.getWidth(null);
            int srcH = awtImg.getHeight(null);
            if (srcW <= 0 || srcH <= 0) {
                srcW = targetSize;
                srcH = targetSize;
            }
            BufferedImage bi = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = bi.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(awtImg, 0, 0, targetSize, targetSize, null);
            g.dispose();
            return SwingFXUtils.toFXImage(bi, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Swing Icon 转 JavaFX Image
     */
    protected Image swingIconToImage(javax.swing.Icon icon) {
        if (icon == null) return null;
        try {
            BufferedImage bi = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = bi.createGraphics();
            icon.paintIcon(null, g, 0, 0);
            g.dispose();
            return SwingFXUtils.toFXImage(bi, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 代码生成文件类型图标（后备方案）：圆角矩形背景 + 扩展名文字
     */
    protected Image createFileTypeIcon(String label, String bgColor) {
        int size = 16;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.valueOf(bgColor));
        gc.fillRoundRect(0, 0, size, size, 3, 3);
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("SansSerif", 7));
        Text text = new Text(label);
        text.setFont(Font.font("SansSerif", 7));
        double tw = text.getLayoutBounds().getWidth();
        gc.fillText(label, (size - tw) / 2, size / 2 + 3);
        return canvas.snapshot(null, null);
    }

    /**
     * 从 /images/connect/fileTypes/ 目录加载文件类型图标
     */
    protected Image loadFileTypeIcon(String ext, boolean large) {
        if (ext == null || ext.isEmpty()) return null;
        String key = ext.toLowerCase();
        Map<String, Image> cache = large ? fileTypeLargeIconCache : fileTypeIconCache;
        if (cache.containsKey(key)) return cache.get(key);
        Image img = null;
        try {
            String path = "/images/connect/fileTypes/" + key.toUpperCase() + ".png";
            java.io.InputStream is = getClass().getResourceAsStream(path);
            if (is != null) {
                if (large) {
                    img = new Image(is, 48, 48, true, true);
                } else {
                    img = new Image(is);
                }
                is.close();
            }
        } catch (Exception e) {
            // 加载失败返回 null，后续走系统图标回退
        }
        cache.put(key, img);
        return img;
    }

    /**
     * 获取文件小图标（优先 fileTypes 目录，回退系统图标，最后默认图标）
     */
    protected Image getFileIcon(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            String ext = fileName.substring(dotIdx + 1).toLowerCase();
            // 优先使用 fileTypes 目录下的类型图标
            Image typeIcon = loadFileTypeIcon(ext, false);
            if (typeIcon != null) return typeIcon;
            // 回退到系统图标
            Image sysIcon = getSystemFileIcon(ext);
            if (sysIcon != null) return sysIcon;
        }
        return defaultFileIcon;
    }

    /**
     * 获取大尺寸文件图标（优先 fileTypes 目录，回退到系统大图标）
     */
    protected Image getLargeFileIcon(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            String ext = fileName.substring(dotIdx + 1).toLowerCase();
            // 优先使用 fileTypes 目录下的类型图标（大尺寸）
            Image typeIcon = loadFileTypeIcon(ext, true);
            if (typeIcon != null) return typeIcon;
            // 回退到系统大图标
            Image sysLarge = getLargeSystemFileIcon(ext);
            if (sysLarge != null) return sysLarge;
        }
        Image defaultLarge = loadFileTypeIcon("bin", true);
        return defaultLarge != null ? defaultLarge : defaultFileIcon;
    }

    /**
     * 获取文件项对应的图标
     * @param item 文件项
     * @param large 是否为大图标（图标视图用大尺寸，列表/列视图用小尺寸）
     */
    protected Image getIconForItem(FileItem item, boolean large) {
        if (item.isDirectory()) {
            // 图标视图使用 S3 风格的 folder.png 大图标
            if (large && folderLargeIcon != null) {
                return folderLargeIcon;
            }
            return folderIcon;
        }
        if (large) {
            Image big = getLargeFileIcon(item.getDisplayName());
            if (big != null) return big;
        }
        return getFileIcon(item.getDisplayName());
    }

    // ==================== 抽象后端方法（子类必须实现） ====================
    /**
     * 刷新当前目录（子类负责获取数据并调用 {@link #setFileList} / {@link #setCurrentPath}）。
     */
    protected abstract void doRefresh();

    /**
     * 导航到指定路径（子类负责获取数据并更新 UI）。
     */
    protected abstract void doNavigateTo(String path);

    /**
     * 重命名：子类根据 newName 自行计算完整路径/key 并执行后端重命名。
     * @throws Exception 后端重命名失败时抛出
     */
    protected abstract void doRename(FileItem item, String newName) throws Exception;

    /**
     * 删除文件项（子类可内部异步执行，完成后调用 refresh）。
     */
    protected abstract void doDelete(FileItem item);

    /**
     * 新建目录。fullPath 由基类按 currentPath 计算得出，子类据此执行后端操作。
     */
    protected abstract void doMkdir(String fullPath);

    /**
     * 上传单个文件到当前远程目录（同步阻塞，失败抛出异常）。
     * 子类根据自身 currentPath / currentBucket 计算远程路径并执行后端上传。
     * 进度对话框、线程调度、刷新由基类 {@link #doUpload(List)} 负责。
     */
    protected abstract void doUploadSingle(File localFile) throws Exception;

    /** Upload a file using its path relative to the dropped/copied root. */
    protected void doUploadSingle(File localFile, String relativePath) throws Exception {
        doUploadSingle(localFile);
    }

    /** Create one directory from a dropped/copied directory tree; default backends ignore it. */
    protected void doUploadDirectory(String relativePath) throws Exception {}

    /** Directory hook with empty-directory information (relevant to object stores). */
    protected void doUploadDirectory(String relativePath, boolean emptyDirectory) throws Exception {
        doUploadDirectory(relativePath);
    }

    /**
     * 下载文件项到本地指定文件（子类负责内部异步执行，完成后更新状态栏）。
     */
    protected abstract void doDownload(FileItem item, File localFile);

    /**
     * 下载文件项到临时目录（用于拖拽下载），返回临时文件。
     */
    protected abstract File doDownloadToTemp(FileItem item);

    /**
     * 是否已连接。
     */
    protected abstract boolean isConnected();

    // ==================== 钩子方法（子类按需覆盖） ====================
    /** 是否支持 Bucket 概念（S3/OSS 返回 true，SFTP/FTP 返回 false）。 */
    protected boolean supportsBuckets() { return false; }
    /** 是否支持列视图（FTP 返回 false，其余默认 true）。 */
    protected boolean supportsColumnView() { return true; }
    /** 是否支持 Markdown 编辑器（S3/SFTP 返回 true）。 */
    protected boolean supportsMarkdownEditor() { return false; }
    /** 是否支持图片预览（S3/SFTP 返回 true）。 */
    protected boolean supportsImagePreview() { return false; }
    /** 是否支持缩略图加载（S3/SFTP 返回 true）。 */
    protected boolean supportsThumbnails() { return false; }
    /** 是否支持复制/粘贴（S3 返回 true）。 */
    protected boolean supportsCopyPaste() { return false; }
    /** 是否支持创建文件（S3/SFTP 返回 true）。 */
    protected boolean supportsCreateFile() { return false; }

    /** 打开 Markdown 编辑器。 */
    protected void openMarkdownEditor(FileItem item) {}
    /** 预览当前选中图片。 */
    protected void handlePreview() {}
    /** 获取文件项的访问 URL（用于复制访问地址）。 */
    protected String getAccessUrl(FileItem item) { return null; }
    /** 剪贴板是否有可粘贴的复制数据。 */
    protected boolean hasCopyData() { return false; }
    /** 复制选中项到剪贴板。 */
    protected void handleCopy() {}
    /**
     * 从剪贴板粘贴到当前位置：检测系统剪贴板中的本地文件并上传，
     * 否则由支持内部复制粘贴的子类（如 S3）覆盖处理自身复制格式。
     */
    protected void handlePaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasFiles() && clipboard.getFiles() != null && !clipboard.getFiles().isEmpty()) {
            doUpload(clipboard.getFiles());
            return;
        }
        // Do not call JavaFX getContent() for virtual clipboard entries here. Some ZIP shell
        // extensions block or return DV_E_FORMATETC. The OLE helper handles their real FORMATETC.
        uploadWindowsShellClipboard();
    }

    /** Extracts indexed Windows OLE FILECONTENTS streams when JavaFX cannot expose them. */
    protected boolean uploadWindowsShellClipboard() {
        return uploadWindowsVirtualFiles(null);
    }

    private boolean uploadWindowsVirtualFiles(byte[] shellIdList) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return false;
        }
        final Path root;
        try {
            root = Files.createTempDirectory("tomato-upload-");
        } catch (IOException ex) {
            return false;
        }
        setStatus("正在读取剪贴板文件...");
        new Thread(() -> {
            Path helperScript = null;
            Path shellIdListFile = null;
            try {
                helperScript = Files.createTempFile("tomato-virtual-files-", ".ps1");
                try (var resource = AbstractFileBrowserPane.class.getResourceAsStream(
                        "/scripts/extract-windows-virtual-files.ps1")) {
                    if (resource == null) throw new IOException("缺少虚拟文件提取组件");
                    Files.copy(resource, helperScript, StandardCopyOption.REPLACE_EXISTING);
                }
                List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile",
                        "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-File",
                        helperScript.toString(), "-Destination", root.toString()));
                if (shellIdList != null) {
                    shellIdListFile = Files.createTempFile("tomato-shell-id-list-", ".bin");
                    Files.write(shellIdListFile, shellIdList);
                    command.add("-ShellIdListFile");
                    command.add(shellIdListFile.toString());
                }
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                try (var input = process.getInputStream()) {
                    input.readAllBytes(); // Drain output; errors are reported in Chinese below.
                }
                int exit = process.waitFor();
                List<File> files;
                try (var paths = Files.list(root)) {
                    files = paths.map(Path::toFile).toList();
                }
                if (exit == 0 && !files.isEmpty()) {
                    Platform.runLater(() -> doUpload(files));
                } else {
                    deleteUploadTempDirectory(root);
                    Platform.runLater(() -> setStatus("无法读取 ZIP 中的剪贴板文件，请重新复制后再试"));
                }
            } catch (Exception ex) {
                deleteUploadTempDirectory(root);
                Platform.runLater(() -> setStatus("读取剪贴板失败: " + ex.getMessage()));
            } finally {
                if (helperScript != null) {
                    try { Files.deleteIfExists(helperScript); } catch (IOException ignored) {
                        helperScript.toFile().deleteOnExit();
                    }
                }
                if (shellIdListFile != null) {
                    try { Files.deleteIfExists(shellIdListFile); } catch (IOException ignored) {
                        shellIdListFile.toFile().deleteOnExit();
                    }
                }
            }
        }, "Windows-Shell-Clipboard").start();
        return true;
    }
    /** 上传前检查：返回非 null 表示不可上传（给出错误提示），返回 null 表示通过。 */
    protected String preUploadCheck() { return null; }
    /** 加载缩略图（图标视图模式下调用）。 */
    protected void loadThumbnails() {}
    /** 创建文件（supportsCreateFile 返回 true 的子类覆盖实现）。 */
    protected void handleCreateFile() {}

    // ==================== 文件列表数据模型 ====================
    /**
     * 统一的文件项数据模型。字段可变（带 setter），子类可按需设置字段。
     * path：S3 为 key，其余为完整路径；modifyTime 为毫秒。
     */
    public static class FileItem {
        private String name;
        private String path;       // S3: key; 其余: 完整路径
        private boolean directory;
        private boolean bucket;    // 仅 S3 使用，默认 false
        private long size;
        private long modifyTime;   // 毫秒

        public FileItem() {}

        public FileItem(String name, String path, boolean directory, long size, long modifyTime) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.modifyTime = modifyTime;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public boolean isDirectory() { return directory; }
        public void setDirectory(boolean directory) { this.directory = directory; }

        public boolean isBucket() { return bucket; }
        public void setBucket(boolean bucket) { this.bucket = bucket; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public long getModifyTime() { return modifyTime; }
        public void setModifyTime(long modifyTime) { this.modifyTime = modifyTime; }

        public String getDisplayName() {
            return directory ? name + "/" : name;
        }

        public String getDisplaySize() {
            if (directory) return "<DIR>";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }

        public String getDisplayTime() {
            if (modifyTime <= 0) return "";
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(modifyTime));
        }

        public String getDisplayType() {
            if (bucket) return "Bucket";
            return directory ? "目录" : "文件";
        }

        public String getFormattedSize() {
            if (directory) return "";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return (size / 1024) + " KB";
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }

        @Override
        public String toString() {
            return getDisplayName();
        }
    }
}
