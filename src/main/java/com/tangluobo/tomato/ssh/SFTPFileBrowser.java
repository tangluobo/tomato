package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import javax.swing.filechooser.FileSystemView;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SFTP文件浏览器组件
 * 支持浏览远程文件、上传/下载、拖拽操作、跟随终端目录
 */
public class SFTPFileBrowser extends BorderPane {

    private final SFTPClient sftpClient;
    private final SSHSession sshSession;

    // UI组件
    private TextField pathField;
    private CheckBox followTerminalCheck;
    private TableView<FileItem> fileTable;
    private Label statusLabel;

    // 跟随终端目录
    private final BooleanProperty followTerminal = new SimpleBooleanProperty(true);
    private String currentPath = "/";

    // 图标
    private final Image folderIcon;
    private final Image defaultFileIcon;
    private final java.util.Map<String, Image> systemIconCache = new java.util.HashMap<>();
    private final File iconTempDir;

    public SFTPFileBrowser(SSHSession sshSession, SFTPClient sftpClient) {
        this.sshSession = sshSession;
        this.sftpClient = sftpClient;

        // 创建临时目录用于获取系统图标
        iconTempDir = new File(System.getProperty("java.io.tmpdir"), "tomato-icons");
        if (!iconTempDir.exists()) iconTempDir.mkdirs();

        // 获取系统文件夹图标
        Image sysFolderIcon = getSystemFolderIcon();
        folderIcon = sysFolderIcon != null ? sysFolderIcon : loadIcon("/images/connect/folder.png");

        // 获取系统默认文件图标
        Image sysFileIcon = getSystemFileIcon("txt");
        defaultFileIcon = sysFileIcon != null ? sysFileIcon : createFileTypeIcon("?", "#9E9E9E");

        setPrefWidth(280);
        setMinWidth(200);
        setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 0 1;");

        createUI();
    }

    private Image loadIcon(String path) {
        try {
            return new Image(getClass().getResourceAsStream(path));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取系统文件夹图标
     */
    private Image getSystemFolderIcon() {
        try {
            javax.swing.Icon icon = FileSystemView.getFileSystemView().getSystemIcon(iconTempDir);
            return swingIconToImage(icon);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据扩展名获取系统文件图标（通过创建临时文件获取）
     */
    private Image getSystemFileIcon(String ext) {
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
     * Swing Icon 转 JavaFX Image
     */
    private Image swingIconToImage(javax.swing.Icon icon) {
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
    private Image createFileTypeIcon(String label, String bgColor) {
        int size = 16;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.valueOf(bgColor));
        gc.fillRoundRect(0, 0, size, size, 3, 3);
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("SansSerif", 7));
        javafx.scene.text.Text text = new javafx.scene.text.Text(label);
        text.setFont(Font.font("SansSerif", 7));
        double tw = text.getLayoutBounds().getWidth();
        gc.fillText(label, (size - tw) / 2, size / 2 + 3);
        return canvas.snapshot(null, null);
    }

    /**
     * 根据文件名获取对应图标（优先系统图标，后备生成图标）
     */
    private Image getFileIcon(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            String ext = fileName.substring(dotIdx + 1).toLowerCase();
            Image sysIcon = getSystemFileIcon(ext);
            if (sysIcon != null) return sysIcon;
        }
        return defaultFileIcon;
    }

    private void createUI() {
        // 顶部：路径栏 + 跟随终端
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
        uploadBtn.setOnAction(e -> uploadFiles());

        topBar.getChildren().addAll(followTerminalCheck, spacer, refreshBtn, uploadBtn);

        // 路径输入框
        pathField = new TextField("/");
        pathField.setStyle("-fx-font-size: 11px; -fx-padding: 3 6; -fx-background-color: #fff; -fx-border-color: #ddd; -fx-border-radius: 3;");
        pathField.setOnAction(e -> navigateTo(pathField.getText().trim()));

        VBox topBox = new VBox(topBar, pathField);
        topBox.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        // 文件列表
        fileTable = new TableView<>();
        fileTable.setStyle("-fx-font-size: 11px; -fx-background-color: #fff;");
        fileTable.setPlaceholder(new Label("空目录"));

        TableColumn<FileItem, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("displayName"));
        nameCol.setCellFactory(col -> new FileItemCell());
        nameCol.setPrefWidth(180);

        TableColumn<FileItem, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setCellValueFactory(new PropertyValueFactory<>("displaySize"));
        sizeCol.setPrefWidth(70);
        sizeCol.setStyle("-fx-alignment: center-right;");

        fileTable.getColumns().addAll(nameCol, sizeCol);
        fileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // 双击进入目录或下载文件
        fileTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    FileItem item = row.getItem();
                    if (item.isDirectory()) {
                        navigateTo(item.getPath());
                    } else {
                        downloadFile(item);
                    }
                }
            });

            // 拖拽：从远程拖到本地（下载到临时目录后拖出）
            row.setOnDragDetected(e -> {
                if (row.isEmpty()) return;
                FileItem item = row.getItem();
                if (!item.isDirectory()) {
                    // 先下载到临时目录
                    File tempFile = downloadToTemp(item);
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
                if (db.hasFiles()) {
                    e.acceptTransferModes(TransferMode.COPY);
                }
                e.consume();
            });

            row.setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                boolean success = false;
                if (db.hasFiles()) {
                    uploadLocalFiles(db.getFiles());
                    success = true;
                }
                e.setDropCompleted(success);
                e.consume();
            });

            return row;
        });

        // 整个表格也支持拖拽上传
        fileTable.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });

        fileTable.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                uploadLocalFiles(db.getFiles());
                success = true;
            }
            e.setDropCompleted(success);
            e.consume();
        });

        // 右键菜单
        fileTable.setContextMenu(createContextMenu());

        setTop(topBox);
        setCenter(fileTable);

        // 底部状态栏
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888; -fx-padding: 2 6;");
        setBottom(statusLabel);
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem uploadItem = new MenuItem("上传文件...");
        uploadItem.setOnAction(e -> uploadFiles());

        MenuItem uploadDirItem = new MenuItem("上传到当前目录...");
        uploadDirItem.setOnAction(e -> uploadFiles());

        MenuItem downloadItem = new MenuItem("下载");
        downloadItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.isDirectory()) {
                downloadFile(selected);
            }
        });

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> {
            FileItem selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                deleteEntry(selected);
            }
        });

        MenuItem mkdirItem = new MenuItem("新建目录");
        mkdirItem.setOnAction(e -> mkdir());

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refresh());

        menu.getItems().addAll(uploadItem, downloadItem, new SeparatorMenuItem(), mkdirItem, deleteItem, new SeparatorMenuItem(), refreshItem);
        return menu;
    }

    /**
     * 导航到指定路径
     */
    public void navigateTo(String path) {
        if (!sftpClient.isConnected()) {
            statusLabel.setText("SFTP未连接");
            return;
        }
        new Thread(() -> {
            try {
                sftpClient.cd(path);
                String realPath = sftpClient.pwd();
                List<SFTPClient.FileEntry> entries = sftpClient.listFiles(realPath);
                Platform.runLater(() -> {
                    currentPath = realPath;
                    pathField.setText(realPath);
                    populateTable(entries);
                    statusLabel.setText(entries.size() + " 个条目");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("错误: " + e.getMessage()));
            }
        }, "SFTP-Navigate").start();
    }

    /**
     * 刷新当前目录
     */
    public void refresh() {
        navigateTo(currentPath);
    }

    /**
     * 跟随终端目录变化
     */
    public void onTerminalCwdChanged(String newPath) {
        if (followTerminal.get()) {
            navigateTo(newPath);
        }
    }

    /**
     * 初始化连接并导航到home目录
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
                Platform.runLater(() -> statusLabel.setText("SFTP连接失败: " + e.getMessage()));
            }
        }, "SFTP-Init").start();
    }

    private void populateTable(List<SFTPClient.FileEntry> entries) {
        ObservableList<FileItem> items = FXCollections.observableArrayList();
        for (SFTPClient.FileEntry entry : entries) {
            items.add(new FileItem(entry.getName(), entry.getPath(), entry.isDirectory(), entry.getSize(), entry.getModifyTime()));
        }
        fileTable.setItems(items);
    }

    /**
     * 下载文件到本地
     */
    private void downloadFile(FileItem item) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择保存目录");
        File dir = chooser.showDialog(getStage());
        if (dir == null) return;

        statusLabel.setText("下载中: " + item.getName());
        new Thread(() -> {
            try {
                String localPath = new File(dir, item.getName()).getAbsolutePath();
                sftpClient.download(item.getPath(), localPath);
                Platform.runLater(() -> statusLabel.setText("下载完成: " + item.getName()));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("下载失败: " + e.getMessage()));
            }
        }, "SFTP-Download").start();
    }

    /**
     * 下载文件到临时目录（用于拖拽下载）
     */
    private File downloadToTemp(FileItem item) {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "tomato-sftp");
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = new File(tempDir, item.getName());
            sftpClient.download(item.getPath(), tempFile.getAbsolutePath());
            return tempFile;
        } catch (Exception e) {
            Platform.runLater(() -> statusLabel.setText("拖拽下载失败: " + e.getMessage()));
            return null;
        }
    }

    /**
     * 上传本地文件
     */
    private void uploadFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要上传的文件");
        List<File> files = chooser.showOpenMultipleDialog(getStage());
        if (files == null || files.isEmpty()) return;
        uploadLocalFiles(files);
    }

    private void uploadLocalFiles(List<File> files) {
        statusLabel.setText("上传中...");
        new Thread(() -> {
            try {
                for (File file : files) {
                    String remotePath = currentPath.endsWith("/") ? currentPath + file.getName() : currentPath + "/" + file.getName();
                    sftpClient.upload(file.getAbsolutePath(), remotePath);
                }
                Platform.runLater(() -> {
                    statusLabel.setText("上传完成");
                    refresh();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("上传失败: " + e.getMessage());
                    refresh();
                });
            }
        }, "SFTP-Upload").start();
    }

    private void deleteEntry(FileItem item) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("删除确认");
        alert.setHeaderText("确定要删除 \"" + item.getName() + "\" 吗？");
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        if (item.isDirectory()) {
                            sftpClient.rmdir(item.getPath());
                        } else {
                            sftpClient.rm(item.getPath());
                        }
                        Platform.runLater(this::refresh);
                    } catch (Exception e) {
                        Platform.runLater(() -> statusLabel.setText("删除失败: " + e.getMessage()));
                    }
                }, "SFTP-Delete").start();
            }
        });
    }

    private void mkdir() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建目录");
        dialog.setHeaderText("输入目录名称:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                new Thread(() -> {
                    try {
                        String path = currentPath.endsWith("/") ? currentPath + name.trim() : currentPath + "/" + name.trim();
                        sftpClient.mkdir(path);
                        Platform.runLater(this::refresh);
                    } catch (Exception e) {
                        Platform.runLater(() -> statusLabel.setText("创建目录失败: " + e.getMessage()));
                    }
                }, "SFTP-Mkdir").start();
            }
        });
    }

    private Stage getStage() {
        return (Stage) getScene().getWindow();
    }

    public BooleanProperty followTerminalProperty() {
        return followTerminal;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    /**
     * 文件列表数据模型
     */
    public static class FileItem {
        private final String name;
        private final String path;
        private final boolean directory;
        private final long size;
        private final long modifyTime;

        public FileItem(String name, String path, boolean directory, long size, long modifyTime) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.modifyTime = modifyTime;
        }

        public String getName() { return name; }
        public String getPath() { return path; }
        public boolean isDirectory() { return directory; }
        public long getSize() { return size; }
        public long getModifyTime() { return modifyTime; }

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
    }

    /**
     * 带图标的表格单元格
     */
    private class FileItemCell extends TableCell<FileItem, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(item);
                FileItem fileItem = getTableView().getItems().get(getIndex());
                ImageView icon = new ImageView();
                icon.setFitWidth(14);
                icon.setFitHeight(14);
                if (fileItem.isDirectory()) {
                    if (folderIcon != null) icon.setImage(folderIcon);
                } else {
                    icon.setImage(getFileIcon(fileItem.getName()));
                }
                setGraphic(icon);
            }
        }
    }
}
