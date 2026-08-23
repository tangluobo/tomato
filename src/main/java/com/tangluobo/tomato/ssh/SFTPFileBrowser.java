package com.tangluobo.tomato.ssh;

import com.tangluobo.tomato.module.connect.AbstractFileBrowserPane;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SFTP文件浏览器组件（SSH终端侧边栏 / 独立标签模式）。
 * <p>
 * 通用 UI（视图模式、选中、重命名、右键菜单、图标）由 {@link AbstractFileBrowserPane} 提供，
 * 本类只实现 SFTP 后端操作、侧边栏布局、跟随终端目录、本地编辑缓存等扩展能力。
 */
public class SFTPFileBrowser extends AbstractFileBrowserPane {

    private final SFTPClient sftpClient;
    private final SSHSession sshSession;

    /** 独立标签模式：全宽、支持图标/列视图、无跟随终端 */
    private final boolean standalone;

    // 跟随终端目录
    private final BooleanProperty followTerminal = new SimpleBooleanProperty(true);
    private CheckBox followTerminalCheck;

    // 本地编辑缓存：远程路径 -> 本地文件 / 已知最后修改时间
    private final Map<String, File> editCache = new ConcurrentHashMap<>();
    private final Map<String, Long> editLastModified = new ConcurrentHashMap<>();

    // 侧边栏模式的路径输入框（独立于基类 currentPathField，样式更紧凑）
    private TextField sidebarPathField;

    // ==================== 构造 ====================

    public SFTPFileBrowser(SSHSession sshSession, SFTPClient sftpClient) {
        this(sshSession, sftpClient, false);
    }

    /**
     * @param standalone 独立标签模式：全宽、表格自滚动、无跟随终端、显示修改时间列
     */
    public SFTPFileBrowser(SSHSession sshSession, SFTPClient sftpClient, boolean standalone) {
        super();  // 基类构造期间调用 initializeUI()（本类覆写为 no-op，延迟到构造体）
        this.sshSession = sshSession;
        this.sftpClient = sftpClient;
        this.standalone = standalone;

        if (!standalone) {
            setPrefWidth(280);
            setMinWidth(200);
        }

        if (standalone) {
            // 独立模式：使用基类标准 UI（路径栏、视图切换、图标/列表/列视图、状态栏）
            super.initializeUI();
        } else {
            // 侧边栏模式：紧凑布局（跟随终端、仅列表视图）
            createSidebarUI();
        }
    }

    /**
     * 覆写为 no-op：基类构造期间调用时 standalone 尚未赋值，
     * 实际 UI 初始化延迟到子类构造体（见上方构造函数）。
     */
    @Override
    protected void initializeUI() {
        // no-op — 由构造体按 standalone 标志分派
    }

    // ==================== 钩子 ====================

    @Override
    protected boolean supportsColumnView() {
        return standalone;  // 侧边栏模式不支持列视图
    }

    @Override
    protected boolean supportsCreateFile() {
        return true;
    }

    @Override
    protected boolean isConnected() {
        return sftpClient.isConnected();
    }

    /**
     * 侧边栏模式禁用视图切换（仅列表视图）。
     */
    @Override
    protected void switchViewMode(ViewMode mode) {
        if (!standalone) return;
        super.switchViewMode(mode);
    }

    /**
     * 侧边栏模式使用简化的右键菜单（无视图子菜单）。
     */
    @Override
    protected ContextMenu createContextMenu() {
        if (standalone) {
            return super.createContextMenu();
        }
        return createSidebarContextMenu();
    }

    // ==================== 侧边栏 UI ====================

    private void createSidebarUI() {
        // 顶部：跟随终端 + 刷新 + 上传
        HBox topBar = new HBox(4);
        topBar.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 4 6; -fx-alignment: center-left; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        followTerminalCheck = new CheckBox("跟随终端");
        followTerminalCheck.setSelected(true);
        followTerminalCheck.setStyle("-fx-font-size: 11px; -fx-text-fill: #333;");
        followTerminal.bind(followTerminalCheck.selectedProperty());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshBtn = new Button("⟳");
        refreshBtn.setStyle("-fx-font-size: 12px; -fx-padding: 2 6; -fx-background-color: transparent; -fx-border-color: #ccc; -fx-border-radius: 3;");
        refreshBtn.setOnAction(e -> refresh());

        Button uploadBtn = new Button("↑");
        uploadBtn.setStyle("-fx-font-size: 12px; -fx-padding: 2 6; -fx-background-color: transparent; -fx-border-color: #ccc; -fx-border-radius: 3;");
        uploadBtn.setOnAction(e -> handleUpload());

        topBar.getChildren().addAll(followTerminalCheck, spacer, refreshBtn, uploadBtn);

        // 路径栏：根目录按钮 + 上级目录按钮 + 路径输入框
        HBox pathBar = new HBox(2);
        pathBar.setStyle("-fx-alignment: center-left; -fx-padding: 2 4;");

        Button rootBtn = new Button();
        rootBtn.setGraphic(createNavIcon("/", "#78909C", "#546E7A"));
        rootBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2; -fx-border-color: transparent; -fx-cursor: hand; -fx-border-radius: 3;");
        rootBtn.setTooltip(new Tooltip("根目录"));
        rootBtn.setOnAction(e -> navigateTo("/"));

        Button parentBtn = new Button();
        parentBtn.setGraphic(createNavIcon("↑", "#78909C", "#546E7A"));
        parentBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2; -fx-border-color: transparent; -fx-cursor: hand; -fx-border-radius: 3;");
        parentBtn.setTooltip(new Tooltip("上级目录"));
        parentBtn.setOnAction(e -> navigateUp());

        sidebarPathField = new TextField("/");
        sidebarPathField.setStyle("-fx-font-size: 11px; -fx-padding: 3 6; -fx-background-color: #fff; -fx-border-color: #ddd; -fx-border-radius: 3;");
        HBox.setHgrow(sidebarPathField, Priority.ALWAYS);
        sidebarPathField.setOnAction(e -> navigateTo(sidebarPathField.getText().trim()));
        // 让基类 setCurrentPath() 同步更新侧边栏路径框
        currentPathField = sidebarPathField;

        pathBar.getChildren().addAll(rootBtn, parentBtn, sidebarPathField);

        VBox topBox = new VBox(topBar, pathBar);
        topBox.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");
        setTop(topBox);

        // 文件列表（仅名称 + 大小两列，高度随内容增长）
        fileTable = new TableView<>();
        fileTable.setStyle("-fx-font-size: 12px; -fx-background-color: #fff;");
        fileTable.getStyleClass().add("sftp-file-table");
        fileTable.setPlaceholder(new Label("空目录"));
        fileTable.setFixedCellSize(26);
        fileTable.prefHeightProperty().bind(Bindings.size(fileData).multiply(24).add(30));
        fileTable.setMinHeight(80);
        fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fileTable.setItems(fileData);

        TableColumn<FileItem, String> nameCol = new TableColumn<>("名称");
        nameCol.setEditable(true);
        nameCol.setCellValueFactory(new PropertyValueFactory<>("displayName"));
        nameCol.setCellFactory(createEditableNameCellFactory());
        nameCol.setPrefWidth(180);

        TableColumn<FileItem, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setCellValueFactory(new PropertyValueFactory<>("displaySize"));
        sizeCol.setPrefWidth(70);
        sizeCol.setStyle("-fx-alignment: center-right;");

        fileTable.getColumns().addAll(nameCol, sizeCol);
        fileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        setupTableRowFactory();
        fileTable.setContextMenu(createContextMenu());

        setCenter(fileTable);

        // 底部状态栏
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888; -fx-padding: 2 6;");
        setBottom(statusLabel);
    }

    /**
     * 侧边栏模式的简化右键菜单（无视图子菜单）。
     */
    private ContextMenu createSidebarContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem("打开");
        openItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null) handleDoubleClick(selected);
        });

        MenuItem downloadItem = new MenuItem("下载...");
        downloadItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null && !selected.isDirectory()) {
                handleDownload(selected);
            }
        });

        MenuItem mkdirItem = new MenuItem("新建目录");
        mkdirItem.setOnAction(e -> handleMkdir());

        MenuItem createFileItem = new MenuItem("创建文件");
        createFileItem.setOnAction(e -> handleCreateFile());

        MenuItem uploadItem = new MenuItem("上传文件...");
        uploadItem.setOnAction(e -> handleUpload());

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> handleDeleteSelected());

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> handleRename());

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refresh());

        menu.getItems().addAll(openItem, downloadItem, new SeparatorMenuItem(),
                mkdirItem, createFileItem, uploadItem, deleteItem, renameItem, new SeparatorMenuItem(), refreshItem);

        menu.setOnShowing(e -> {
            List<FileItem> selected = getSelectedItems();
            boolean single = selected.size() == 1;
            FileItem first = selected.isEmpty() ? null : selected.get(0);
            openItem.setVisible(single && first != null);
            downloadItem.setVisible(single && first != null && !first.isDirectory());
            deleteItem.setVisible(!selected.isEmpty());
            deleteItem.setText(selected.size() > 1 ? "删除(" + selected.size() + "项)" : "删除");
            renameItem.setVisible(single && first != null);
            createFileItem.setVisible(supportsCreateFile());
        });

        return menu;
    }

    /**
     * 创建导航按钮图标（侧边栏根目录/上级目录按钮用）。
     */
    private ImageView createNavIcon(String type, String bgColor, String fgColor) {
        int size = 18;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        if ("/".equals(type)) {
            gc.setFill(Color.valueOf(bgColor));
            gc.fillPolygon(new double[]{9, 2, 16}, new double[]{2, 9, 9}, 3);
            gc.fillRect(4, 9, 10, 7);
            gc.setFill(Color.valueOf(fgColor));
            gc.fillRect(7, 11, 4, 5);
        } else {
            gc.setFill(Color.valueOf(bgColor));
            gc.fillRoundRect(1, 5, 14, 9, 2, 2);
            gc.fillRoundRect(1, 2, 6, 4, 2, 2);
            gc.setFill(Color.valueOf(fgColor));
            gc.fillPolygon(new double[]{8, 11.5, 14}, new double[]{3, 0, 3}, 3);
            gc.fillRect(10.5, 3, 1.5, 5);
        }

        Image img = canvas.snapshot(null, null);
        ImageView iv = new ImageView(img);
        iv.setFitWidth(16);
        iv.setFitHeight(16);
        return iv;
    }

    // ==================== 抽象后端方法实现 ====================

    @Override
    protected void doRefresh() {
        doNavigateTo(getCurrentPath());
    }

    @Override
    protected void doNavigateTo(String path) {
        new Thread(() -> {
            try {
                sftpClient.cd(path);
                String realPath = sftpClient.pwd();
                List<SFTPClient.FileEntry> entries = sftpClient.listFiles(realPath);
                List<FileItem> items = new ArrayList<>();
                for (SFTPClient.FileEntry entry : entries) {
                    items.add(new FileItem(entry.getName(), entry.getPath(),
                            entry.isDirectory(), entry.getSize(), entry.getModifyTime()));
                }
                Platform.runLater(() -> {
                    setCurrentPath(realPath);
                    setFileList(items);
                    if (upBtn != null) upBtn.setDisable("/".equals(realPath));
                    setStatus(entries.size() + " 个条目");
                });
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("错误: " + e.getMessage()));
            }
        }, "SFTP-Navigate").start();
    }

    @Override
    protected void doRename(FileItem item, String newName) throws Exception {
        String oldPath = item.getPath();
        int lastSlash = oldPath.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "" : oldPath.substring(0, lastSlash);
        String newPath = parent.isEmpty() ? "/" + newName : parent + "/" + newName;
        sftpClient.rename(oldPath, newPath);
    }

    @Override
    protected void doDelete(FileItem item) {
        new Thread(() -> {
            try {
                if (item.isDirectory()) {
                    sftpClient.rmdir(item.getPath());
                } else {
                    sftpClient.rm(item.getPath());
                }
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("删除失败: " + e.getMessage()));
            }
        }, "SFTP-Delete").start();
    }

    @Override
    protected void doMkdir(String fullPath) {
        new Thread(() -> {
            try {
                sftpClient.mkdir(fullPath);
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("创建目录失败: " + e.getMessage()));
            }
        }, "SFTP-Mkdir").start();
    }

    @Override
    protected void doUpload(List<File> files) {
        setStatus("上传中...");
        new Thread(() -> {
            try {
                String cwd = getCurrentPath();
                for (File file : files) {
                    String remotePath = cwd.endsWith("/") ? cwd + file.getName() : cwd + "/" + file.getName();
                    sftpClient.upload(file.getAbsolutePath(), remotePath);
                }
                Platform.runLater(() -> {
                    setStatus("上传完成");
                    refresh();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("上传失败: " + e.getMessage());
                    refresh();
                });
            }
        }, "SFTP-Upload").start();
    }

    @Override
    protected void doDownload(FileItem item, File localFile) {
        setStatus("下载中: " + item.getName());
        new Thread(() -> {
            try {
                sftpClient.download(item.getPath(), localFile.getAbsolutePath());
                Platform.runLater(() -> setStatus("下载完成: " + item.getName()));
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("下载失败: " + e.getMessage()));
            }
        }, "SFTP-Download").start();
    }

    @Override
    protected File doDownloadToTemp(FileItem item) {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "tomato-sftp");
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = new File(tempDir, item.getName());
            sftpClient.download(item.getPath(), tempFile.getAbsolutePath());
            return tempFile;
        } catch (Exception e) {
            Platform.runLater(() -> setStatus("拖拽下载失败: " + e.getMessage()));
            return null;
        }
    }

    @Override
    protected void loadColumnAsync(String path, int colIndex) {
        new Thread(() -> {
            try {
                sftpClient.cd(path);
                String realPath = sftpClient.pwd();
                List<SFTPClient.FileEntry> entries = sftpClient.listFiles(realPath);
                List<FileItem> items = new ArrayList<>();
                for (SFTPClient.FileEntry entry : entries) {
                    items.add(new FileItem(entry.getName(), entry.getPath(),
                            entry.isDirectory(), entry.getSize(), entry.getModifyTime()));
                }
                Platform.runLater(() -> addColumn(colIndex, realPath, items));
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("列视图加载失败: " + e.getMessage()));
            }
        }, "SFTP-Column").start();
    }

    @Override
    protected void handleCreateFile() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("创建文件");
        dialog.setHeaderText("输入文件名称:");
        DialogPositionUtil.centerOnOwner(dialog, this);
        dialog.showAndWait().ifPresent(name -> {
            String fileName = name.trim();
            if (fileName.isEmpty()) return;
            String cwd = getCurrentPath();
            String fullPath = cwd.endsWith("/") ? cwd + fileName : cwd + "/" + fileName;
            new Thread(() -> {
                try {
                    sftpClient.createEmptyFile(fullPath);
                    Platform.runLater(this::refresh);
                } catch (Exception e) {
                    Platform.runLater(() -> setStatus("创建文件失败: " + e.getMessage()));
                }
            }, "SFTP-CreateFile").start();
        });
    }

    // ==================== 本地编辑（覆盖基类空实现） ====================

    /**
     * 用本地应用打开文件：下载到临时目录后调用系统默认应用打开。
     * 文件修改后会自动上传到远程（直接保存或关闭时保存均生效）。
     */
    @Override
    protected void openFileLocally(FileItem item) {
        String remotePath = item.getPath();
        File cachedFile = editCache.get(remotePath);

        if (cachedFile != null && cachedFile.exists()) {
            File localFile = cachedFile;
            new Thread(() -> {
                openLocalFile(localFile);
                Platform.runLater(() -> setStatus("已用本地应用打开: " + item.getName()));
            }, "SFTP-Open").start();
            return;
        }

        setStatus("下载中: " + item.getName());
        new Thread(() -> {
            try {
                File editRoot = new File(System.getProperty("java.io.tmpdir"), "tomato-edit");
                File editDir = new File(editRoot, Integer.toHexString(remotePath.hashCode() & 0x7FFFFFFF));
                if (!editDir.exists()) editDir.mkdirs();
                File localFile = new File(editDir, item.getName());
                sftpClient.download(remotePath, localFile.getAbsolutePath());
                long initialModified = localFile.lastModified();
                editCache.put(remotePath, localFile);
                editLastModified.put(remotePath, initialModified);

                openLocalFile(localFile);
                Platform.runLater(() -> setStatus("已用本地应用打开: " + item.getName()));

                startEditMonitor(item, localFile);
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("打开失败: " + e.getMessage()));
            }
        }, "SFTP-Edit").start();
    }

    /**
     * 调用系统默认应用打开本地文件
     */
    private void openLocalFile(File localFile) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(localFile);
            } else {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", localFile.getAbsolutePath()});
                } else if (os.contains("mac")) {
                    Runtime.getRuntime().exec(new String[]{"open", localFile.getAbsolutePath()});
                } else {
                    Runtime.getRuntime().exec(new String[]{"xdg-open", localFile.getAbsolutePath()});
                }
            }
        } catch (Exception e) {
            Platform.runLater(() -> setStatus("无法打开本地应用: " + e.getMessage()));
        }
    }

    /**
     * 监控本地文件修改，修改后自动上传到远程。
     * 每次在本地应用中保存（Ctrl+S 或关闭时保存）都会触发上传。
     */
    private void startEditMonitor(FileItem item, File localFile) {
        String remotePath = item.getPath();
        Thread monitor = new Thread(() -> {
            long lastKnown = editLastModified.getOrDefault(remotePath, 0L);
            long lastActivity = System.currentTimeMillis();
            long pollInterval = 2000;
            long maxIdleTime = 30 * 60 * 1000; // 30分钟无修改则停止监控

            while (true) {
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException e) {
                    break;
                }
                if (!localFile.exists()) {
                    break;
                }
                long current = localFile.lastModified();
                if (current != lastKnown) {
                    lastKnown = current;
                    editLastModified.put(remotePath, lastKnown);
                    lastActivity = System.currentTimeMillis();
                    uploadEditToRemote(item, localFile);
                }
                if (System.currentTimeMillis() - lastActivity > maxIdleTime) {
                    break;
                }
            }
            editCache.remove(remotePath);
            editLastModified.remove(remotePath);
        }, "SFTP-Monitor-" + item.getName());
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * 上传本地修改后的文件到远程
     */
    private void uploadEditToRemote(FileItem item, File localFile) {
        try {
            if (!sftpClient.isConnected()) {
                Platform.runLater(() -> setStatus("SFTP未连接，无法保存远程: " + item.getName()));
                return;
            }
            sftpClient.upload(localFile.getAbsolutePath(), item.getPath());
            Platform.runLater(() -> setStatus("已保存到远程: " + item.getName()));
        } catch (Exception e) {
            Platform.runLater(() -> setStatus("保存远程失败: " + e.getMessage()));
        }
    }

    // ==================== 连接管理（公共 API） ====================

    /**
     * 初始化连接并导航到 home 目录
     */
    public void initConnection() {
        new Thread(() -> {
            try {
                if (!sftpClient.isConnected()) {
                    sftpClient.connect(sshSession.getJschSession());
                }
                String home = sftpClient.pwd();
                Platform.runLater(() -> navigateTo(home));
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("SFTP连接失败: " + e.getMessage()));
            }
        }, "SFTP-Init").start();
    }

    /**
     * 断开 SFTP 与 SSH 连接（标签关闭时调用）
     */
    public void disconnect() {
        try {
            if (sftpClient != null) sftpClient.disconnect();
        } catch (Exception ignored) {}
        try {
            if (sshSession != null) sshSession.disconnect();
        } catch (Exception ignored) {}
    }

    /**
     * 跟随终端目录变化
     */
    public void onTerminalCwdChanged(String newPath) {
        if (followTerminal.get()) {
            navigateTo(newPath);
        }
    }

    public BooleanProperty followTerminalProperty() {
        return followTerminal;
    }
}
