package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.connect.service.OssService;
import com.tangluobo.tomato.module.connect.service.S3Service;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S3/OSS 文件浏览器面板
 * <p>
 * 通用 UI（视图模式、选中、重命名、右键菜单、框选、图标加载）由
 * {@link AbstractFileBrowserPane} 提供，本类只实现 S3/OSS 后端操作与
 * Bucket 列表、Markdown 编辑、图片预览、复制粘贴、自动滚动等扩展能力。
 * <p>
 * 支持浏览 Bucket 列表、进入 Bucket 浏览文件/目录；
 * 支持 S3（AWS S3/MinIO）和阿里云 OSS 两种连接类型。
 */
public class S3FileBrowserPane extends AbstractFileBrowserPane {

    private ConnectionConfig config;
    private final boolean isAliyunOSS;

    // 状态栏组件
    private Circle statusDot;
    private Label stateLabel;
    private Label connLabel;

    // 路径栏 S3 特有按钮
    private Button createBucketBtn;

    // 当前浏览状态（S3 使用 bucket/prefix 而非纯路径字符串）
    private String currentBucket = null;
    private String currentPrefix = "";
    private final List<String> pathHistory = new ArrayList<>();

    // 编辑器 Tab 页（中心区域：文件浏览 + 多个 markdown 编辑器）
    private TabPane editorTabPane;
    private Tab browseTab;

    // 列视图（每列的 bucket 与 prefix；null bucket 表示 Bucket 列表层）
    private final List<String> columnBuckets = new ArrayList<>();
    private final List<String> columnPrefixes = new ArrayList<>();

    // 自动滚动（框选时靠近边缘自动滚动 ScrollPane）
    private javafx.animation.AnimationTimer autoScrollTimer = null;
    private double autoScrollDX = 0, autoScrollDY = 0;
    private boolean autoScrollAdditive = false;

    // 剪贴板数据格式（用于 S3 文件复制粘贴）
    private static final DataFormat S3_COPY_FORMAT = new DataFormat("application/x-s3-file-copy");

    // 复制粘贴进度对话框
    private Stage copyProgressStage;
    private ProgressBar copyProgressBar;
    private Label copyProgressLabel;
    private Label copyProgressDetailLabel;
    private AtomicBoolean copyCancelled = new AtomicBoolean(false);

    // S3 特有的 Bucket 图标
    private Image bucketIcon;
    private Image bucketLargeIcon;

    public S3FileBrowserPane(ConnectionConfig config) {
        super();  // 基类构造函数调用 initializeUI() 与 createStatusBar()
        this.config = config;
        this.isAliyunOSS = config.getType() == ConnectType.ALIYUN_OSS;

        // 加载 S3 特有 Bucket 图标（依赖 isAliyunOSS，须在赋值后加载）
        loadBucketIcons();

        // 基类构造期间调用 createStatusBar() 时 config 尚未赋值，此处补充连接信息
        if (connLabel != null) {
            connLabel.setText(config.getName() + " ("
                    + (config.getEndpoint() != null ? config.getEndpoint() : config.getRegion()) + ")");
        }

        // 路径栏追加 S3 特有的"新建 Bucket"按钮
        createBucketBtn = new Button("+ 新建Bucket");
        createBucketBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-background-radius: 4;");
        createBucketBtn.setOnAction(e -> handleCreateBucket());
        createBucketBtn.setVisible(false);
        createBucketBtn.setManaged(false);
        pathBar.getChildren().add(createBucketBtn);

        // 路径输入框：获得焦点全选、失去焦点还原显示
        currentPathField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                Platform.runLater(currentPathField::selectAll);
            } else {
                updatePathLabel();
            }
        });

        setInitialViewMode(ViewMode.ICON);  // S3 面板默认图标视图
        loadBuckets();
        setupKeyboardShortcuts();
    }

    /**
     * 更新连接配置引用（编辑保存后调用，使已打开的标签页立即生效新配置）。
     * type 不会改变（S3 还是 S3、OSS 还是 OSS），因此 isAliyunOSS 无需重新计算。
     */
    public void updateConfig(ConnectionConfig newConfig) {
        this.config = newConfig;
    }

    // ==================== 状态栏 / 中心区域 ====================
    @Override
    protected Node createStatusBar() {
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

        // 基类构造期间调用本方法时 config 可能为 null，做保护
        connLabel = new Label(config != null
                ? config.getName() + " (" + (config.getEndpoint() != null ? config.getEndpoint() : config.getRegion()) + ")"
                : "");
        connLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(connLabel);

        // 让基类 setStatus() 生效
        statusLabel = stateLabel;
        return statusBar;
    }

    @Override
    protected void applyCenter(Node content) {
        if (editorTabPane == null) {
            editorTabPane = new TabPane();
            editorTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
            browseTab = new Tab("文件浏览");
            browseTab.setClosable(false);
            editorTabPane.getTabs().add(browseTab);
            setCenter(editorTabPane);
        }
        browseTab.setContent(content);
        editorTabPane.getSelectionModel().select(browseTab);
    }

    // ==================== 图标视图：框选自动滚动（S3 特有） ====================
    /**
     * 在基类图标视图基础上追加 S3 特有的框选自动滚动逻辑。
     */
    @Override
    protected void initIconView() {
        super.initIconView();
        iconScrollPane.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!selectionRect.isVisible()) return;
            autoScrollAdditive = e.isControlDown() || e.isShiftDown();
            updateAutoScrollVelocity(e.getSceneX(), e.getSceneY());
            startAutoScrollIfNeeded();
        });
        iconScrollPane.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> stopAutoScroll());
    }

    /**
     * 计算拖拽点是否靠近 ScrollPane 边缘，得到自动滚动速度。
     */
    private void updateAutoScrollVelocity(double sceneX, double sceneY) {
        javafx.geometry.Point2D spPoint = iconScrollPane.sceneToLocal(sceneX, sceneY, true);
        if (spPoint == null) { autoScrollDX = 0; autoScrollDY = 0; return; }
        double spx = spPoint.getX();
        double spy = spPoint.getY();
        double spw = iconScrollPane.getViewportBounds().getWidth();
        double sph = iconScrollPane.getViewportBounds().getHeight();
        double edge = 25;
        double speed = 8;
        autoScrollDX = 0;
        autoScrollDY = 0;
        if (spx < edge) autoScrollDX = -speed * (1 - spx / edge);
        else if (spx > spw - edge) autoScrollDX = speed * (1 - (spw - spx) / edge);
        if (spy < edge) autoScrollDY = -speed * (1 - spy / edge);
        else if (spy > sph - edge) autoScrollDY = speed * (1 - (sph - spy) / edge);
    }

    private void startAutoScrollIfNeeded() {
        if (autoScrollTimer != null) return;
        if (Math.abs(autoScrollDX) < 0.5 && Math.abs(autoScrollDY) < 0.5) return;
        autoScrollTimer = new javafx.animation.AnimationTimer() {
            @Override public void handle(long now) {
                if (!selectionRect.isVisible()) { stop(); autoScrollTimer = null; return; }
                if (Math.abs(autoScrollDX) < 0.5 && Math.abs(autoScrollDY) < 0.5) return;
                double rx = selectionRect.getX();
                double ry = selectionRect.getY();
                double rw = selectionRect.getWidth();
                double rh = selectionRect.getHeight();
                double curX, curY;
                if (rw <= 0 || rx == selStartX) curX = rx + rw; else curX = rx;
                if (rh <= 0 || ry == selStartY) curY = ry + rh; else curY = ry;
                double dx = autoScrollDX;
                double dy = autoScrollDY;
                if (curX < selStartX) dx = -Math.abs(dx);
                else if (curX > selStartX) dx = Math.abs(dx);
                else dx = 0;
                if (curY < selStartY) dy = -Math.abs(dy);
                else if (curY > selStartY) dy = Math.abs(dy);
                else dy = 0;
                curX += dx;
                curY += dy;
                double maxX = iconFlowPane.getBoundsInLocal().getWidth();
                double maxY = iconFlowPane.getBoundsInLocal().getHeight();
                curX = Math.max(0, Math.min(maxX, curX));
                curY = Math.max(0, Math.min(maxY, curY));
                double vmax = iconScrollPane.getVmax();
                double hmax = iconScrollPane.getHmax();
                double hval = iconScrollPane.getHvalue();
                double vval = iconScrollPane.getVvalue();
                double contentWidth = maxX;
                double contentHeight = maxY;
                double viewW = iconScrollPane.getViewportBounds().getWidth();
                double viewH = iconScrollPane.getViewportBounds().getHeight();
                if (contentWidth > viewW && hmax > 0) {
                    double dh = autoScrollDX / (contentWidth - viewW);
                    iconScrollPane.setHvalue(Math.max(0, Math.min(hmax, hval + dh)));
                }
                if (contentHeight > viewH && vmax > 0) {
                    double dv = autoScrollDY / (contentHeight - viewH);
                    iconScrollPane.setVvalue(Math.max(0, Math.min(vmax, vval + dv)));
                }
                double nx = Math.min(selStartX, curX);
                double ny = Math.min(selStartY, curY);
                double nw = Math.abs(curX - selStartX);
                double nh = Math.abs(curY - selStartY);
                selectionRect.setX(nx);
                selectionRect.setY(ny);
                selectionRect.setWidth(nw);
                selectionRect.setHeight(nh);
                updateRubberBandSelection(nx, ny, nw, nh, autoScrollAdditive);
            }
        };
        autoScrollTimer.start();
    }

    private void stopAutoScroll() {
        if (autoScrollTimer != null) {
            autoScrollTimer.stop();
            autoScrollTimer = null;
        }
        autoScrollDX = 0;
        autoScrollDY = 0;
    }

    // ==================== 图标 ====================
    private void loadBucketIcons() {
        try { bucketIcon = new Image(getClass().getResourceAsStream(isAliyunOSS ? "/images/connect/aliyun_oss.png" : "/images/connect/s3.png")); } catch (Exception e) { bucketIcon = null; }
        try { bucketLargeIcon = new Image(getClass().getResourceAsStream(isAliyunOSS ? "/images/connect/aliyun_oss.png" : "/images/connect/s3.png"), 48, 48, true, true); } catch (Exception e) { bucketLargeIcon = null; }
    }

    @Override
    protected Image getIconForItem(FileItem item, boolean large) {
        if (item.isBucket()) {
            return large ? bucketLargeIcon : bucketIcon;
        }
        return super.getIconForItem(item, large);
    }

    // ==================== 能力钩子 ====================
    @Override protected boolean supportsBuckets() { return true; }
    @Override protected boolean supportsColumnView() { return true; }
    @Override protected boolean supportsMarkdownEditor() { return true; }
    @Override protected boolean supportsImagePreview() { return true; }
    @Override protected boolean supportsThumbnails() { return true; }
    @Override protected boolean supportsCopyPaste() { return true; }
    @Override protected boolean supportsCreateFile() { return true; }

    // ==================== 抽象后端方法实现 ====================
    @Override
    protected void doRefresh() {
        if (currentBucket == null) {
            loadBuckets();
        } else {
            loadObjects(currentBucket, currentPrefix);
        }
    }

    @Override
    protected void doNavigateTo(String path) {
        navigateToPath(path);
    }

    @Override
    protected void doRename(FileItem item, String newName) throws Exception {
        if (item.isBucket()) {
            throw new Exception(isAliyunOSS ? "Bucket不支持重命名，请通过管理控制台操作" : "Bucket不支持重命名（S3 Bucket名称不可修改）");
        }
        String sourceKey = item.getPath();
        if (item.isDirectory()) {
            // 目录重命名：递归复制 prefix 下所有对象到新 prefix
            String sourcePrefix = sourceKey;
            int nameLen = item.getName().length();
            String parentPrefix = sourcePrefix.length() > nameLen
                    ? sourcePrefix.substring(0, sourcePrefix.length() - nameLen - 1)
                    : "";
            String destPrefix = parentPrefix + newName + "/";
            if (isAliyunOSS) {
                OssService.renameDirectory(config, currentBucket, sourcePrefix, destPrefix);
            } else {
                S3Service.renameDirectory(config, currentBucket, sourcePrefix, destPrefix);
            }
        } else {
            // 文件重命名：复制到新 key 后删除原 key
            int lastSlash = sourceKey.lastIndexOf('/');
            String parentPrefix = lastSlash >= 0 ? sourceKey.substring(0, lastSlash + 1) : "";
            String newKey = parentPrefix + newName;
            if (isAliyunOSS) {
                OssService.renameObject(config, currentBucket, sourceKey, newKey);
            } else {
                S3Service.renameObject(config, currentBucket, sourceKey, newKey);
            }
        }
    }

    @Override
    protected void doDelete(FileItem item) {
        new Thread(() -> {
            try {
                if (item.isBucket()) {
                    if (isAliyunOSS) {
                        throw new Exception("请通过管理控制台删除Bucket");
                    } else {
                        S3Service.deleteBucket(config, item.getName());
                    }
                } else {
                    if (isAliyunOSS) {
                        OssService.deleteObject(config, currentBucket, item.getPath());
                    } else {
                        S3Service.deleteObject(config, currentBucket, item.getPath());
                    }
                }
                Platform.runLater(() -> {
                    setStatus("删除完成");
                    refresh();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("删除失败: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("删除失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "S3-Delete").start();
    }

    @Override
    protected void doMkdir(String fullPath) {
        final String dirKey = fullPath;
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
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "S3-Mkdir").start();
    }

    @Override
    protected String preUploadCheck() {
        return currentBucket == null ? "请先进入一个 Bucket 再上传文件" : null;
    }

    @Override
    protected void doUploadSingle(File localFile) throws Exception {
        String key = (currentPrefix != null ? currentPrefix : "") + localFile.getName();
        long size = localFile.length();
        String contentType = java.net.URLConnection.guessContentTypeFromName(localFile.getName());
        if (contentType == null) contentType = "application/octet-stream";
        try (java.io.FileInputStream fis = new java.io.FileInputStream(localFile)) {
            if (isAliyunOSS) {
                OssService.uploadFile(config, currentBucket, key, fis, size, contentType);
            } else {
                S3Service.uploadFile(config, currentBucket, key, fis, size, contentType);
            }
        }
    }

    @Override
    protected void doDownload(FileItem item, File localFile) {
        if (currentBucket == null || item == null || item.isDirectory()) return;
        setStatus("下载中: " + item.getDisplayName());
        new Thread(() -> {
            try (InputStream is = isAliyunOSS
                    ? OssService.getObjectStream(config, currentBucket, item.getPath())
                    : S3Service.getObjectStream(config, currentBucket, item.getPath())) {
                Files.copy(is, localFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Platform.runLater(() -> {
                    setStatus("下载完成: " + item.getDisplayName());
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("下载完成");
                    alert.setHeaderText(null);
                    alert.setContentText("文件已保存到: " + localFile.getAbsolutePath());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("下载失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("下载失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "S3-Download").start();
    }

    @Override
    protected File doDownloadToTemp(FileItem item) {
        if (currentBucket == null || item == null || item.isDirectory()) return null;
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "tomato-s3");
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = new File(tempDir, item.getDisplayName());
            try (InputStream is = isAliyunOSS
                    ? OssService.getObjectStream(config, currentBucket, item.getPath())
                    : S3Service.getObjectStream(config, currentBucket, item.getPath())) {
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (Exception e) {
            Platform.runLater(() -> setStatus("拖拽下载失败: " + e.getMessage()));
            return null;
        }
    }

    @Override
    protected boolean isConnected() {
        return config != null;
    }

    @Override
    protected void loadColumnAsync(String path, int colIndex) {
        // 解析 path 为 bucket/prefix："/" 为 Bucket 列表层，"/bucket" 为 Bucket 根，"/bucket/prefix/" 为子目录
        String bucket;
        String prefix;
        if (path == null || path.isEmpty() || path.equals("/")) {
            bucket = null;
            prefix = "";
        } else if (path.startsWith("/")) {
            String rest = path.substring(1);
            int firstSlash = rest.indexOf('/');
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
        } else {
            bucket = null;
            prefix = "";
        }

        final String fBucket = bucket;
        final String fPrefix = prefix;
        final String fPath = path;

        new Thread(() -> {
            try {
                List<FileItem> items = fetchS3Items(fBucket, fPrefix);
                Platform.runLater(() -> {
                    if (currentViewMode != ViewMode.COLUMN) return;
                    addColumn(colIndex, fPath, items);
                    columnBuckets.add(fBucket);
                    columnPrefixes.add(fPrefix != null ? fPrefix : "");
                    updatePathFromColumns();
                    setStatus(items.size() + " 个条目");
                    // 添加新列后自动滚动到最右侧
                    columnContainer.widthProperty().addListener(new ChangeListener<Number>() {
                        @Override
                        public void changed(ObservableValue<? extends Number> obs, Number oldW, Number newW) {
                            if (newW.doubleValue() > oldW.doubleValue()) {
                                columnScrollPane.setHvalue(1.0);
                                obs.removeListener(this);
                            }
                        }
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("错误: " + e.getMessage()));
            }
        }, "S3-ColumnLoad").start();
    }

    /**
     * 从 S3/OSS 获取目录数据并转为 FileItem 列表。
     * bucket 为 null 时加载 Bucket 列表，否则加载指定 Bucket 的 prefix 内容。
     */
    private List<FileItem> fetchS3Items(String bucket, String prefix) throws Exception {
        List<FileItem> items = new ArrayList<>();
        if (bucket == null) {
            List<String> buckets = isAliyunOSS ? OssService.listBuckets(config) : S3Service.listBuckets(config);
            for (String bucketName : buckets) {
                FileItem item = new FileItem();
                item.setName(bucketName);
                item.setPath(bucketName);
                item.setDirectory(true);
                item.setBucket(true);
                items.add(item);
            }
        } else {
            List<?> objects = isAliyunOSS ? OssService.listObjects(config, bucket, prefix) : S3Service.listObjects(config, bucket, prefix);
            for (Object obj : objects) {
                FileItem item = new FileItem();
                if (isAliyunOSS) {
                    OssService.OssObjectInfo o = (OssService.OssObjectInfo) obj;
                    item.setName(o.getDisplayName());
                    item.setPath(o.getKey());
                    item.setDirectory(o.isDirectory());
                    item.setSize(o.getSize());
                    Date d = o.getLastModified();
                    item.setModifyTime(d != null ? d.getTime() : 0);
                    item.setBucket(false);
                } else {
                    S3Service.S3ObjectInfo o = (S3Service.S3ObjectInfo) obj;
                    item.setName(o.getDisplayName());
                    item.setPath(o.getKey());
                    item.setDirectory(o.isDirectory());
                    item.setSize(o.getSize());
                    Instant inst = o.getLastModified();
                    item.setModifyTime(inst != null ? inst.toEpochMilli() : 0);
                    item.setBucket(false);
                }
                items.add(item);
            }
        }
        return items;
    }

    // ==================== 列视图适配（S3 使用 bucket/prefix） ====================
    @Override
    protected void rebuildColumnView() {
        if (columnContainer == null) return;
        columnContainer.getChildren().clear();
        columnListViews.clear();
        columnItems.clear();
        columnPaths.clear();
        columnBuckets.clear();
        columnPrefixes.clear();

        ObservableList<FileItem> colData = FXCollections.observableArrayList(fileData);
        String path = encodeColumnPath(currentBucket, currentPrefix);
        columnItems.add(colData);
        columnPaths.add(path);
        columnBuckets.add(currentBucket);
        columnPrefixes.add(currentPrefix != null ? currentPrefix : "");
        ListView<FileItem> lv = createColumnListView(0);
        columnListViews.add(lv);
        columnContainer.getChildren().add(lv);
    }

    @Override
    protected void onColumnItemSelected(FileItem item, int colIndex) {
        truncateColumns(colIndex + 1);
        if (item.isDirectory()) {
            if (item.isBucket()) {
                // 点击 Bucket：新列加载该 Bucket 根目录
                loadColumnAsync("/" + item.getName(), colIndex + 1);
            } else {
                // 点击文件夹：新列加载子目录（使用当前列的 bucket）
                String bucket = colIndex < columnBuckets.size() ? columnBuckets.get(colIndex) : currentBucket;
                loadColumnAsync("/" + (bucket != null ? bucket : "") + "/" + item.getPath(), colIndex + 1);
            }
        }
        updatePathFromColumns();
    }

    @Override
    protected void truncateColumns(int keepCount) {
        super.truncateColumns(keepCount);
        while (columnBuckets.size() > keepCount) columnBuckets.remove(columnBuckets.size() - 1);
        while (columnPrefixes.size() > keepCount) columnPrefixes.remove(columnPrefixes.size() - 1);
    }

    /**
     * 根据列视图状态更新路径输入框与 currentBucket/currentPrefix/currentPath。
     */
    private void updatePathFromColumns() {
        if (columnBuckets.isEmpty()) return;
        String bucket = columnBuckets.get(columnBuckets.size() - 1);
        String prefix = columnPrefixes.get(columnPrefixes.size() - 1);
        currentBucket = bucket;
        currentPrefix = prefix;
        if (bucket == null) {
            setCurrentPath("/");
        } else {
            setCurrentPath("/" + bucket + "/" + (prefix != null ? prefix : ""));
        }
    }

    /**
     * 编码 bucket/prefix 为列视图路径字符串。
     */
    private static String encodeColumnPath(String bucket, String prefix) {
        if (bucket == null) return "/";
        return "/" + bucket + "/" + (prefix != null ? prefix : "");
    }

    // ==================== 导航（S3 使用 bucket/prefix） ====================
    @Override
    protected void navigateUp() {
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

    private void updatePathLabel() {
        if (currentBucket == null) {
            setCurrentPath("/");
        } else {
            String path = "/" + currentBucket + "/" + (currentPrefix != null ? currentPrefix : "");
            setCurrentPath(path);
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
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.equals("/")) {
            pathHistory.clear();
            loadBuckets();
            return;
        }
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

    // ==================== 加载 Bucket 列表 / 对象列表 ====================
    /**
     * 加载 Bucket 列表
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

                    List<FileItem> items = new ArrayList<>();
                    for (String bucketName : buckets) {
                        FileItem item = new FileItem();
                        item.setName(bucketName);
                        item.setPath(bucketName);
                        item.setDirectory(true);
                        item.setBucket(true);
                        items.add(item);
                    }
                    setFileList(items);

                    upBtn.setDisable(true);
                    createBucketBtn.setVisible(true);
                    createBucketBtn.setManaged(true);
                    selectedItem = null;
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusDot.setFill(Color.RED);
                    stateLabel.setText("连接失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到 " + config.getName() + ": " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "S3-LoadBuckets").start();
    }

    /**
     * 加载 Bucket 中的对象列表
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

                    List<FileItem> items = new ArrayList<>();
                    for (Object obj : objects) {
                        FileItem item = new FileItem();
                        if (isAliyunOSS) {
                            OssService.OssObjectInfo ossObj = (OssService.OssObjectInfo) obj;
                            item.setName(ossObj.getDisplayName());
                            item.setPath(ossObj.getKey());
                            item.setDirectory(ossObj.isDirectory());
                            item.setSize(ossObj.getSize());
                            Date d = ossObj.getLastModified();
                            item.setModifyTime(d != null ? d.getTime() : 0);
                            item.setBucket(false);
                        } else {
                            S3Service.S3ObjectInfo s3Obj = (S3Service.S3ObjectInfo) obj;
                            item.setName(s3Obj.getDisplayName());
                            item.setPath(s3Obj.getKey());
                            item.setDirectory(s3Obj.isDirectory());
                            item.setSize(s3Obj.getSize());
                            Instant inst = s3Obj.getLastModified();
                            item.setModifyTime(inst != null ? inst.toEpochMilli() : 0);
                            item.setBucket(false);
                        }
                        items.add(item);
                    }
                    setFileList(items);

                    upBtn.setDisable(false);
                    createBucketBtn.setVisible(false);
                    createBucketBtn.setManaged(false);
                    selectedItem = null;
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载文件列表: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "S3-LoadObjects").start();
    }

    // ==================== 双击 / 打开 ====================
    @Override
    protected void handleDoubleClick(FileItem item) {
        if (item == null) return;
        if (item.isDirectory()) {
            if (item.isBucket()) {
                pathHistory.add("/");
                loadObjects(item.getName(), "");
            } else {
                pathHistory.add(currentPrefix);
                loadObjects(currentBucket, item.getPath());
            }
        } else if (isImageFile(item.getDisplayName())) {
            handlePreview(item);
        } else if (isMarkdownFile(item.getDisplayName())) {
            openMarkdownEditor(item);
        }
    }

    // ==================== 新建 Bucket / 目录 / 文件 ====================
    private void handleCreateBucket() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建Bucket");
        dialog.setHeaderText(null);
        dialog.setContentText("Bucket名称：");
        DialogPositionUtil.centerOnOwner(dialog, this);
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
                    Platform.runLater(this::loadBuckets);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("创建失败");
                        alert.setHeaderText(null);
                        alert.setContentText("创建Bucket失败: " + e.getMessage());
                        DialogPositionUtil.centerOnOwner(alert, this);
                        alert.showAndWait();
                    });
                }
            }, "S3-CreateBucket").start();
        });
    }

    /**
     * 新建目录：S3 需校验当前在 Bucket 内且名称不含 /。
     */
    @Override
    protected void handleMkdir() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再创建目录");
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建目录");
        dialog.setHeaderText(null);
        dialog.setContentText("目录名称：");
        DialogPositionUtil.centerOnOwner(dialog, this);
        dialog.showAndWait().ifPresent(name -> {
            String dirName = name.trim();
            if (dirName.isEmpty()) return;
            if (dirName.contains("/")) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("目录名称不能包含 / 字符");
                DialogPositionUtil.centerOnOwner(alert, this);
                alert.showAndWait();
                return;
            }
            String dirKey = (currentPrefix != null ? currentPrefix : "") + dirName + "/";
            doMkdir(dirKey);
        });
    }

    @Override
    protected void handleCreateFile() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再创建文件");
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        TextInputDialog dialog = new TextInputDialog("新文件.md");
        dialog.setTitle("创建文件");
        dialog.setHeaderText(null);
        dialog.setContentText("文件名：");
        DialogPositionUtil.centerOnOwner(dialog, this);
        dialog.showAndWait().ifPresent(name -> {
            String fileName = name.trim();
            if (fileName.isEmpty()) return;
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
                        FileItem newItem = new FileItem();
                        newItem.setName(fileName);
                        newItem.setPath(fileKey);
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
                        DialogPositionUtil.centerOnOwner(alert, this);
                        alert.showAndWait();
                    });
                }
            }, "S3-CreateFile").start();
        });
    }

    // ==================== Markdown 编辑器 ====================
    @Override
    protected void openMarkdownEditor(FileItem item) {
        if (currentBucket == null) return;
        String fileKey = item.getPath();
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
                    DialogPositionUtil.centerOnOwner(confirm, this);
                    confirm.showAndWait().ifPresent(resp -> {
                        if (resp == saveBtn) {
                            editor.save();
                        } else if (resp == cancelBtn) {
                            return;
                        }
                        editorTabPane.getTabs().remove(editorTab);
                    });
                }
            });
        });
    }

    // ==================== 图片预览 ====================
    @Override
    protected void handlePreview() {
        FileItem selected = getSelectedItem();
        if (selected == null || selected.isDirectory() || !isImageFile(selected.getDisplayName())) return;
        handlePreview(selected);
    }

    /**
     * 预览图片：从 S3/OSS 下载并显示
     */
    private void handlePreview(FileItem item) {
        if (currentBucket == null) return;

        // 收集当前目录中所有图片文件
        List<FileItem> imageItems = new ArrayList<>();
        int currentIndex = -1;
        for (int i = 0; i < getFileData().size(); i++) {
            FileItem fi = getFileData().get(i);
            if (!fi.isDirectory() && isImageFile(fi.getDisplayName())) {
                imageItems.add(fi);
                if (fi == item || fi.getPath().equals(item.getPath())) {
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

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(60, 60);
        StackPane loadingPane = new StackPane(loadingIndicator);
        loadingPane.setPrefSize(780, 520);
        loadingPane.setStyle("-fx-background-color: #2b2b2b;");
        previewStage.setScene(new Scene(loadingPane));

        final int[] imageIndex = {currentIndex};

        Runnable loadImage = new Runnable() {
            @Override
            public void run() {
                int idx = imageIndex[0];
                if (idx < 0 || idx >= imageItems.size()) return;
                FileItem currentItem = imageItems.get(idx);

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
                            is = OssService.getObjectStream(config, currentBucket, currentItem.getPath());
                        } else {
                            is = S3Service.getObjectStream(config, currentBucket, currentItem.getPath());
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

        loadImage.run();
        DialogPositionUtil.centerOnOwner(previewStage, this);
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

        StackPane imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: #2b2b2b;");

        double contentWidth = stage.getWidth() > 0 ? stage.getWidth() : 800;
        double contentHeight = stage.getHeight() > 0 ? stage.getHeight() - 40 : 560;
        double fitWidth = Math.min(imgWidth, contentWidth - 20);
        double fitHeight = Math.min(imgHeight, contentHeight - 60);
        double scale = Math.min(fitWidth / imgWidth, fitHeight / imgHeight);
        if (scale < 1) {
            imageView.setFitWidth(imgWidth * scale);
            imageView.setFitHeight(imgHeight * scale);
        }

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

        stage.setTitle(String.format("%s  |  %dx%d  |  %s  (%d/%d)",
                item.getDisplayName(), (int) imgWidth, (int) imgHeight, item.getDisplaySize(),
                imageIndex[0] + 1, imageItems.size()));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #3c3c3c;");

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

        Button originalBtn = new Button("1:1");
        originalBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        originalBtn.setOnAction(e -> {
            imageView.setFitWidth(imgWidth);
            imageView.setFitHeight(imgHeight);
            imageView.setTranslateX(0);
            imageView.setTranslateY(0);
        });
        toolbar.getChildren().add(originalBtn);

        Separator sep2 = new Separator();
        sep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        toolbar.getChildren().add(sep2);

        Button downloadBtn = new Button("下载");
        downloadBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        downloadBtn.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("保存文件");
            fileChooser.setInitialFileName(item.getDisplayName());
            File saveFile = fileChooser.showSaveDialog(stage);
            if (saveFile == null) return;
            new Thread(() -> {
                try (InputStream is = isAliyunOSS
                        ? OssService.getObjectStream(config, currentBucket, item.getPath())
                        : S3Service.getObjectStream(config, currentBucket, item.getPath())) {
                    Files.copy(is, saveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
                            OssService.deleteObject(config, currentBucket, item.getPath());
                        } else {
                            S3Service.deleteObject(config, currentBucket, item.getPath());
                        }
                        Platform.runLater(() -> {
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

        stage.getScene().setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.LEFT && imageIndex[0] > 0) {
                imageIndex[0]--;
                loadImage.run();
            } else if (e.getCode() == KeyCode.RIGHT && imageIndex[0] < imageItems.size() - 1) {
                imageIndex[0]++;
                loadImage.run();
            }
        });
    }

    /**
     * 更新图标视图中图片文件的缩略图
     */
    private void updateIconBoxWithThumbnail(FileItem item, Image fullImage) {
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
        params.setFill(Color.TRANSPARENT);
        Image thumbnail = thumbView.snapshot(params, null);

        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                if (box.getChildren().size() >= 2
                        && box.getChildren().get(1) instanceof Label label
                        && item.getDisplayName().equals(label.getText())) {
                    if (box.getChildren().get(0) instanceof ImageView iconView) {
                        iconView.setImage(thumbnail);
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
    @Override
    protected void loadThumbnails() {
        if (currentBucket == null) return;
        for (FileItem item : getFileData()) {
            if (!item.isDirectory() && isImageFile(item.getDisplayName())) {
                new Thread(() -> {
                    try {
                        InputStream is;
                        if (isAliyunOSS) {
                            is = OssService.getObjectStream(config, currentBucket, item.getPath());
                        } else {
                            is = S3Service.getObjectStream(config, currentBucket, item.getPath());
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

    // ==================== 访问 URL ====================
    @Override
    protected String getAccessUrl(FileItem item) {
        String baseUrl = config.getPublicAccessUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return null;
        }
        String trimmedBase = baseUrl.trim();
        while (trimmedBase.endsWith("/")) {
            trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
        }
        String key = item.getPath();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        return trimmedBase + "/" + currentBucket + "/" + key;
    }

    // ==================== 复制 / 粘贴 ====================
    @Override
    protected boolean hasCopyData() {
        return Clipboard.getSystemClipboard().hasContent(S3_COPY_FORMAT);
    }

    @Override
    protected void handleCopy() {
        List<FileItem> selected = getSelectedItems();
        if (selected.isEmpty()) return;

        // 过滤掉 Bucket 级别，只支持文件和目录
        List<FileItem> items = new ArrayList<>();
        for (FileItem item : selected) {
            if (!item.isBucket()) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            setStatus("无法复制Bucket");
            return;
        }

        // 构建剪贴板数据：每行一条记录，格式为 "TYPE|KEY"
        StringBuilder sb = new StringBuilder();
        sb.append(config.getId()).append("\n");
        sb.append(config.getType().name()).append("\n");
        sb.append(currentBucket).append("\n");
        for (FileItem item : items) {
            String type = item.isDirectory() ? "D" : "F";
            sb.append(type).append("|").append(item.getPath()).append("\n");
        }

        ClipboardContent content = new ClipboardContent();
        content.put(S3_COPY_FORMAT, sb.toString());
        int fileCount = 0;
        int dirCount = 0;
        for (FileItem item : items) {
            if (item.isDirectory()) dirCount++;
            else fileCount++;
        }
        String desc = fileCount + " 个文件" + (dirCount > 0 ? ", " + dirCount + " 个目录" : "");
        content.putString(desc);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("已复制 " + desc + " 到剪贴板");
    }

    @Override
    protected void handlePaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        // 优先处理本地文件粘贴上传
        if (clipboard.hasFiles()) {
            doUpload(clipboard.getFiles());
            return;
        }
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再粘贴");
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        if (!clipboard.hasContent(S3_COPY_FORMAT)) {
            return;
        }
        String data = (String) clipboard.getContent(S3_COPY_FORMAT);
        if (data == null || data.isEmpty()) return;
        try {
            parseAndExecuteCopy(data);
        } catch (Exception e) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("粘贴失败");
                alert.setHeaderText(null);
                alert.setContentText("解析剪贴板数据失败: " + e.getMessage());
                DialogPositionUtil.centerOnOwner(alert, this);
                alert.showAndWait();
            });
        }
    }

    /**
     * 复制项数据模型（记录元信息）
     */
    private static class CopyItem {
        String key;
        boolean isDirectory;

        CopyItem(String key, boolean isDirectory) {
            this.key = key;
            this.isDirectory = isDirectory;
        }

        String getDisplayName() {
            if (key == null) return "";
            String displayKey = key;
            if (displayKey.endsWith("/")) {
                displayKey = displayKey.substring(0, displayKey.length() - 1);
            }
            int lastSlash = displayKey.lastIndexOf('/');
            if (lastSlash >= 0) {
                return displayKey.substring(lastSlash + 1);
            }
            return displayKey;
        }
    }

    /**
     * 复制任务数据模型
     */
    private static class CopyTask {
        String sourceKey;
        String destKey;
        String displayName;
        boolean fromDirectory;

        CopyTask(String sourceKey, String destKey, String displayName, boolean fromDirectory) {
            this.sourceKey = sourceKey;
            this.destKey = destKey;
            this.displayName = displayName;
            this.fromDirectory = fromDirectory;
        }
    }

    /**
     * 进度追踪接口
     */
    private interface ProgressTracker {
        void onEnumerate(String currentItem, int totalFound);
    }

    /**
     * 解析剪贴板数据并执行复制
     */
    private void parseAndExecuteCopy(String data) throws Exception {
        String[] lines = data.split("\n");
        if (lines.length < 3) {
            throw new Exception("无效的复制数据");
        }
        String sourceConfigId = lines[0];
        String sourceTypeName = lines[1];
        String sourceBucket = lines[2];
        ConnectionConfig sourceConfig = findConfigById(sourceConfigId);
        if (sourceConfig == null) {
            throw new Exception("找不到源连接配置: " + sourceConfigId);
        }
        List<CopyItem> copyItems = new ArrayList<>();
        for (int i = 3; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] parts = lines[i].split("\\|", 2);
            if (parts.length == 2) {
                boolean isDir = "D".equals(parts[0]);
                copyItems.add(new CopyItem(parts[1], isDir));
            }
        }
        if (copyItems.isEmpty()) {
            throw new Exception("没有要复制的项");
        }
        boolean sameConnection = config.getId().equals(sourceConfigId);
        boolean sameType = config.getType().name().equals(sourceTypeName);
        showCopyProgressDialog(0);
        if (sameConnection && sameType) {
            executeServerSideCopy(sourceConfig, sourceBucket, copyItems);
        } else {
            executeCrossConnectionCopy(sourceConfig, sourceBucket, copyItems);
        }
    }

    /**
     * 展开复制项：递归遍历目录，生成最终的文件复制任务列表
     */
    private List<CopyTask> expandCopyItems(ConnectionConfig sourceConfig, String sourceBucket,
                                            List<CopyItem> copyItems, ProgressTracker tracker) throws Exception {
        List<CopyTask> tasks = new ArrayList<>();
        boolean sourceIsOSS = sourceConfig.getType() == ConnectType.ALIYUN_OSS;
        for (CopyItem item : copyItems) {
            if (copyCancelled.get()) break;
            if (!item.isDirectory) {
                String destKey = buildDestKey(item.key);
                tasks.add(new CopyTask(item.key, destKey, item.getDisplayName(), false));
                if (tracker != null) {
                    tracker.onEnumerate(item.getDisplayName(), tasks.size());
                }
            } else {
                String dirPrefix = item.key.endsWith("/") ? item.key : item.key + "/";
                String dirName = item.getDisplayName();
                String destDirPrefix = buildDestKey(dirPrefix);
                if (tracker != null) {
                    tracker.onEnumerate("扫描目录: " + dirName + "/...", tasks.size());
                }
                List<? extends Object> objects;
                if (sourceIsOSS) {
                    objects = OssService.listObjectsRecursive(sourceConfig, sourceBucket, dirPrefix);
                } else {
                    objects = S3Service.listObjectsRecursive(sourceConfig, sourceBucket, dirPrefix);
                }
                for (Object obj : objects) {
                    if (copyCancelled.get()) break;
                    String srcKey;
                    long size;
                    if (sourceIsOSS) {
                        OssService.OssObjectInfo o = (OssService.OssObjectInfo) obj;
                        srcKey = o.getKey();
                        size = o.getSize();
                    } else {
                        S3Service.S3ObjectInfo o = (S3Service.S3ObjectInfo) obj;
                        srcKey = o.getKey();
                        size = o.getSize();
                    }
                    String relativeKey = srcKey.substring(dirPrefix.length());
                    String destKey = destDirPrefix + relativeKey;
                    String fileName = relativeKey;
                    int lastSlash = relativeKey.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        fileName = relativeKey.substring(lastSlash + 1);
                    }
                    tasks.add(new CopyTask(srcKey, destKey, dirName + "/" + fileName, true));
                    if (tracker != null) {
                        tracker.onEnumerate(dirName + "/" + fileName, tasks.size());
                    }
                }
            }
        }
        return tasks;
    }

    /**
     * 构建目标 key：将源项放到当前 prefix 下
     */
    private String buildDestKey(String sourceKey) {
        String base = currentPrefix != null ? currentPrefix : "";
        String displayKey = sourceKey;
        if (displayKey.endsWith("/")) {
            displayKey = displayKey.substring(0, displayKey.length() - 1);
        }
        int lastSlash = displayKey.lastIndexOf('/');
        String name = lastSlash >= 0 ? displayKey.substring(lastSlash + 1) : displayKey;
        if (sourceKey.endsWith("/")) {
            return base + name + "/";
        }
        return base + name;
    }

    /**
     * 服务端复制（同连接同类型）
     */
    private void executeServerSideCopy(ConnectionConfig sourceConfig, String sourceBucket, List<CopyItem> copyItems) {
        copyCancelled.set(false);
        new Thread(() -> {
            final List<CopyTask>[] taskHolder = new List[1];
            try {
                taskHolder[0] = expandCopyItems(sourceConfig, sourceBucket, copyItems, (item, total) -> {
                    Platform.runLater(() -> {
                        if (copyProgressLabel != null) {
                            copyProgressLabel.setText("正在扫描: " + item);
                        }
                        if (copyProgressDetailLabel != null) {
                            copyProgressDetailLabel.setText("已发现 " + total + " 个文件");
                        }
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("扫描失败");
                    alert.setHeaderText(null);
                    alert.setContentText("扫描目录失败: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
                return;
            }
            final List<CopyTask> tasks = taskHolder[0];
            if (tasks.isEmpty()) {
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    setStatus("没有文件可复制");
                });
                return;
            }
            Platform.runLater(() -> {
                if (copyProgressLabel != null) {
                    copyProgressLabel.setText("准备复制 " + tasks.size() + " 个文件...");
                }
                if (copyProgressBar != null) {
                    copyProgressBar.setProgress(0);
                }
            });
            int success = 0;
            int failed = 0;
            String lastError = null;
            for (int i = 0; i < tasks.size(); i++) {
                if (copyCancelled.get()) break;
                final int index = i;
                CopyTask task = tasks.get(i);
                try {
                    if (isAliyunOSS) {
                        OssService.copyAcrossOSS(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, null);
                    } else {
                        S3Service.copyObjectAcrossBucket(sourceConfig, sourceBucket, task.sourceKey,
                                currentBucket, task.destKey);
                    }
                    success++;
                } catch (Throwable e) {
                    failed++;
                    lastError = String.valueOf(e.getMessage());
                    if (lastError == null || lastError.isEmpty()) {
                        lastError = e.getClass().getSimpleName();
                    }
                }
                final int done = success + failed;
                Platform.runLater(() -> updateCopyProgress(index, task.displayName, done, tasks.size()));
            }
            final int okCount = success;
            final int failCount = failed;
            final String err = lastError;
            Platform.runLater(() -> {
                hideCopyProgressDialog();
                if (failCount == 0) {
                    setStatus("粘贴完成: 成功 " + okCount + " 个");
                } else {
                    setStatus("粘贴结束: 成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("部分粘贴失败");
                    alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    alert.setContentText(err != null ? err : "");
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                }
                refresh();
            });
        }, "S3-Paste-Server").start();
    }

    /**
     * 跨连接/跨类型复制
     */
    private void executeCrossConnectionCopy(ConnectionConfig sourceConfig, String sourceBucket, List<CopyItem> copyItems) {
        copyCancelled.set(false);
        new Thread(() -> {
            final List<CopyTask>[] taskHolder = new List[1];
            try {
                taskHolder[0] = expandCopyItems(sourceConfig, sourceBucket, copyItems, (item, total) -> {
                    Platform.runLater(() -> {
                        if (copyProgressLabel != null) {
                            copyProgressLabel.setText("正在扫描: " + item);
                        }
                        if (copyProgressDetailLabel != null) {
                            copyProgressDetailLabel.setText("已发现 " + total + " 个文件");
                        }
                    });
                });
            } catch (Throwable e) {
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("扫描失败");
                    alert.setHeaderText(null);
                    alert.setContentText("扫描目录失败: " + e);
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
                return;
            }
            final List<CopyTask> tasks = taskHolder[0];
            if (tasks.isEmpty()) {
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    setStatus("没有文件可复制");
                });
                return;
            }
            Platform.runLater(() -> {
                if (copyProgressLabel != null) {
                    copyProgressLabel.setText("准备复制 " + tasks.size() + " 个文件...");
                }
                if (copyProgressBar != null) {
                    copyProgressBar.setProgress(0);
                }
            });
            // 目标连接预检
            try {
                if (isAliyunOSS) {
                    OssService.listBuckets(config);
                } else {
                    S3Service.listBuckets(config);
                }
            } catch (Throwable t) {
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("目标连接不可用");
                    alert.setHeaderText("无法连接目标 " + config.getName());
                    alert.setContentText(String.valueOf(t.getMessage()));
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
                return;
            }
            // 看门狗：60 秒无字节级进展则 dump 线程堆栈
            final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
            final AtomicBoolean copyFinished = new AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                int dumped = 0;
                while (!copyFinished.get() && dumped < 3) {
                    try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                    if (copyFinished.get()) return;
                    long idle = System.currentTimeMillis() - lastActivity.get();
                    if (idle > 60000) {
                        dumped++;
                        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                            Thread t = e.getKey();
                            String n = t.getName();
                            if (n.contains("S3") || n.contains("OkHttp") || n.contains("MINIO")
                                    || n.contains("Paste") || n.contains("Copy") || n.contains("OSS")) {
                                for (StackTraceElement el : e.getValue()) {
                                    // 仅打印堆栈，避免日志噪声
                                }
                            }
                        }
                    }
                }
            }, "S3-Paste-Watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            // 逐个复制文件
            int success = 0;
            int failed = 0;
            String lastError = null;
            LinkedHashMap<String, Integer> errorCounts = new LinkedHashMap<>();
            for (int i = 0; i < tasks.size(); i++) {
                if (copyCancelled.get()) break;
                final int index = i;
                CopyTask task = tasks.get(i);
                try {
                    boolean sourceIsOSS = sourceConfig.getType() == ConnectType.ALIYUN_OSS;
                    boolean destIsOSS = isAliyunOSS;
                    S3Service.ProgressCallback progressCallback = new S3Service.ProgressCallback() {
                        private volatile String currentPhase = "";
                        @Override
                        public void onPhase(String phase) {
                            currentPhase = phase;
                            lastActivity.set(System.currentTimeMillis());
                            Platform.runLater(() -> {
                                if (copyProgressDetailLabel != null) {
                                    copyProgressDetailLabel.setText(phase + "中...");
                                }
                            });
                        }
                        @Override
                        public void onProgress(long transferred, long totalSize) {
                            lastActivity.set(System.currentTimeMillis());
                            String phase = currentPhase;
                            Platform.runLater(() -> updateCopyTransferProgress(index, task.displayName, phase, transferred, totalSize));
                        }
                    };
                    if (sourceIsOSS && destIsOSS) {
                        OssService.copyAcrossOSS(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, progressCallback);
                    } else if (sourceIsOSS) {
                        copyFromOSStoS3(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, progressCallback);
                    } else if (destIsOSS) {
                        copyFromS3toOSS(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, progressCallback);
                    } else {
                        S3Service.copyAcrossS3(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, progressCallback);
                    }
                    success++;
                } catch (Throwable e) {
                    failed++;
                    String msg = String.valueOf(e.getMessage());
                    if (msg == null || msg.isEmpty() || "null".equals(msg)) {
                        msg = e.getClass().getSimpleName();
                    }
                    lastError = msg;
                    errorCounts.merge(msg, 1, Integer::sum);
                }
                lastActivity.set(System.currentTimeMillis());
                final int done = success + failed;
                Platform.runLater(() -> updateCopyProgress(index, task.displayName, done, tasks.size()));
            }
            copyFinished.set(true);
            final int okCount = success;
            final int failCount = failed;
            final LinkedHashMap<String, Integer> errs = errorCounts;
            Platform.runLater(() -> {
                hideCopyProgressDialog();
                if (failCount == 0) {
                    setStatus("粘贴完成: 成功 " + okCount + " 个");
                } else {
                    setStatus("粘贴结束: 成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    StringBuilder sb = new StringBuilder();
                    int shown = 0;
                    for (Map.Entry<String, Integer> en : errs.entrySet()) {
                        if (shown >= 5) { sb.append("...等共 ").append(errs.size()).append(" 种错误"); break; }
                        if (shown > 0) sb.append("\n");
                        sb.append("[").append(en.getValue()).append("次] ").append(en.getKey());
                        shown++;
                    }
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("部分粘贴失败");
                    alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    alert.setContentText(sb.toString());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                }
                refresh();
            });
        }, "S3-Paste-Cross").start();
    }

    /**
     * 从 OSS 复制到 S3（临时文件两阶段）
     */
    private void copyFromOSStoS3(ConnectionConfig sourceConfig, String sourceBucket, String sourceKey,
                                  ConnectionConfig destConfig, String destBucket, String destKey,
                                  S3Service.ProgressCallback callback) throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("o2s-", ".part");
        try {
            java.io.InputStream sourceStream = OssService.getObjectStream(sourceConfig, sourceBucket, sourceKey);
            if (callback != null) callback.onPhase("下载");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                long transferred = 0;
                long lastReportTime = 0;
                while ((len = sourceStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    transferred += len;
                    long now = System.currentTimeMillis();
                    if (callback != null && now - lastReportTime > 200) {
                        lastReportTime = now;
                        callback.onProgress(transferred, -1);
                    }
                }
            } finally {
                try { sourceStream.close(); } catch (Exception e) { /* ignore */ }
            }
            long fileSize = java.nio.file.Files.size(tempFile);
            if (callback != null) {
                callback.onPhase("上传");
                callback.onProgress(0, fileSize);
            }
            S3Service.uploadFileDirect(destConfig, destBucket, destKey, tempFile.toFile(), fileSize, "application/octet-stream", callback);
        } finally {
            try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception e) { /* ignore */ }
        }
        if (callback != null) callback.onComplete();
    }

    /**
     * 从 S3 复制到 OSS（临时文件两阶段）
     */
    private void copyFromS3toOSS(ConnectionConfig sourceConfig, String sourceBucket, String sourceKey,
                                  ConnectionConfig destConfig, String destBucket, String destKey,
                                  S3Service.ProgressCallback callback) throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("s2o-", ".part");
        try {
            java.io.InputStream sourceStream = S3Service.getObjectStream(sourceConfig, sourceBucket, sourceKey);
            if (callback != null) callback.onPhase("下载");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                long transferred = 0;
                long lastReportTime = 0;
                while ((len = sourceStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    transferred += len;
                    long now = System.currentTimeMillis();
                    if (callback != null && now - lastReportTime > 200) {
                        lastReportTime = now;
                        callback.onProgress(transferred, -1);
                    }
                }
            } finally {
                try { sourceStream.close(); } catch (Exception e) { /* ignore */ }
            }
            long fileSize = java.nio.file.Files.size(tempFile);
            if (callback != null) {
                callback.onPhase("上传");
                callback.onProgress(0, fileSize);
            }
            try (java.io.InputStream upStream = new java.io.FileInputStream(tempFile.toFile())) {
                OssService.uploadFile(destConfig, destBucket, destKey, upStream, fileSize, "application/octet-stream");
            }
        } finally {
            try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception e) { /* ignore */ }
        }
        if (callback != null) callback.onComplete();
    }

    private void showCopyProgressDialog(int totalFiles) {
        copyProgressStage = new Stage();
        copyProgressStage.setTitle("粘贴文件...");
        copyProgressStage.setWidth(500);
        copyProgressStage.setHeight(200);
        copyProgressStage.setResizable(false);
        copyProgressStage.initOwner(getStage());

        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(15));
        vbox.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label("正在粘贴...");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        copyProgressLabel = new Label("正在扫描目录结构...");
        copyProgressLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
        copyProgressLabel.setMaxWidth(470);
        copyProgressLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        copyProgressBar = new ProgressBar(0);
        copyProgressBar.setMaxWidth(Double.MAX_VALUE);
        copyProgressBar.setStyle("-fx-accent: #3592CB;");

        copyProgressDetailLabel = new Label("");
        copyProgressDetailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        copyProgressDetailLabel.setMaxWidth(470);
        copyProgressDetailLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd;");
        cancelBtn.setOnAction(e -> copyCancelled.set(true));

        HBox buttonBox = new HBox();
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getChildren().add(cancelBtn);

        vbox.getChildren().addAll(titleLabel, copyProgressLabel, copyProgressBar, copyProgressDetailLabel, buttonBox);
        copyProgressStage.setScene(new Scene(vbox));
        DialogPositionUtil.centerOnOwner(copyProgressStage, this);
        copyProgressStage.show();
    }

    private void hideCopyProgressDialog() {
        if (copyProgressStage != null) {
            copyProgressStage.close();
            copyProgressStage = null;
        }
    }

    private void updateCopyProgress(int currentIndex, String fileName, int done, int total) {
        if (copyProgressBar == null || copyProgressLabel == null) return;
        double progress = (double) done / total;
        copyProgressBar.setProgress(progress);
        copyProgressLabel.setText(String.format("正在复制: %s (%d/%d)", fileName, done, total));
    }

    private void updateCopyTransferProgress(int currentIndex, String fileName, String phase, long transferred, long totalSize) {
        if (copyProgressDetailLabel == null) return;
        String phaseStr = (phase == null || phase.isEmpty()) ? "传输" : phase;
        String transferredStr = formatFileSize(transferred);
        if (totalSize > 0) {
            String totalStr = formatFileSize(totalSize);
            double percent = (double) transferred / totalSize * 100;
            copyProgressDetailLabel.setText(String.format("%s中: %s / %s (%.0f%%)", phaseStr, transferredStr, totalStr, percent));
        } else {
            copyProgressDetailLabel.setText(String.format("%s中: %s", phaseStr, transferredStr));
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 根据 ID 查找连接配置
     */
    private ConnectionConfig findConfigById(String id) {
        try {
            List<ConnectionConfig> all = ConfigManager.loadConnections();
            for (ConnectionConfig c : all) {
                if (id.equals(c.getId())) return c;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // ==================== 键盘快捷键 ====================
    /**
     * 设置全局键盘快捷键（Ctrl+C 复制、Ctrl+V 粘贴）
     */
    public void setupKeyboardShortcuts() {
        // 仅注册 Ctrl+C 复制；Ctrl+V 粘贴由基类 setupKeyboardShortcuts 统一注册
        KeyCodeCombination copyCombo = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        this.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.getAccelerators().put(copyCombo, this::handleCopy);
            }
        });
    }
}
