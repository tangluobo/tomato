package com.tangluobo.tomato.module.connect;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.tangluobo.tomato.ssh.SFTPClient;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * SFTP文件浏览器面板
 * 通过SSH会话建立SFTP通道，浏览远程文件系统。
 * 通用 UI（视图模式、选中、重命名、右键菜单、图标）由 {@link AbstractFileBrowserPane} 提供，
 * 本类只实现 SFTP 后端操作与 Markdown 编辑、图片预览等扩展能力。
 */
public class SFTPFileBrowserPane extends AbstractFileBrowserPane {

    private final ConnectionConfig config;

    // SSH/SFTP
    private Session jschSession;
    private final SFTPClient sftpClient = new SFTPClient();

    // 编辑器 Tab 页（中心区域：文件浏览 + 多个 markdown 编辑器）
    private TabPane editorTabPane;
    private Tab browseTab;

    // 状态栏组件
    private Circle statusDot;
    private Label stateLabel;
    private Label connLabel;

    public SFTPFileBrowserPane(ConnectionConfig config) {
        super();
        this.config = config;
        // 基类构造期间调用 createStatusBar() 时 config 尚未赋值，此处补充连接信息
        if (connLabel != null) {
            connLabel.setText(config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ":" + config.getPort() + ")");
        }
        setInitialViewMode(ViewMode.ICON);  // SFTP 面板默认图标视图
        connectAndLoad();
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
                ? config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ":" + config.getPort() + ")"
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
            browseTab = new Tab("浏览");
            browseTab.setClosable(false);
            editorTabPane.getTabs().add(browseTab);
            setCenter(editorTabPane);
        }
        browseTab.setContent(content);
        editorTabPane.getSelectionModel().select(browseTab);
    }

    // ==================== 能力钩子 ====================
    @Override protected boolean supportsMarkdownEditor() { return true; }
    @Override protected boolean supportsImagePreview() { return true; }
    @Override protected boolean supportsThumbnails() { return true; }
    @Override protected boolean supportsCreateFile() { return true; }

    // ==================== 抽象后端方法实现 ====================
    @Override
    protected void doNavigateTo(String path) {
        new Thread(() -> {
            try {
                // 通道未连接时尝试重连
                if (!sftpClient.isConnected()) {
                    sftpClient.reconnect();
                }
                sftpClient.cd(path);
                String realPath = sftpClient.pwd();
                List<SFTPClient.FileEntry> entries = sftpClient.listFiles(realPath);
                Platform.runLater(() -> {
                    setCurrentPath(realPath);
                    List<FileItem> items = new ArrayList<>();
                    for (SFTPClient.FileEntry entry : entries) {
                        items.add(new FileItem(entry.getName(), entry.getPath(), entry.isDirectory(), entry.getSize(), entry.getModifyTime()));
                    }
                    setFileList(items);
                    selectedItem = null;
                    upBtn.setDisable("/".equals(currentPath));
                    setStatus(entries.size() + " 个条目");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("错误: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载文件列表: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "SFTP-Navigate").start();
    }

    @Override
    protected void doRefresh() {
        doNavigateTo(getCurrentPath());
    }

    /**
     * 重命名：基类 commitRename 已在后台线程中调用本方法，故此处同步执行 SFTP rename。
     */
    @Override
    protected void doRename(FileItem item, String newName) throws Exception {
        String newPath = joinPath(getCurrentPath(), newName);
        sftpClient.rename(item.getPath(), newPath);
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
                Platform.runLater(() -> {
                    setStatus("删除失败: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("删除失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
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
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("创建失败");
                    alert.setHeaderText(null);
                    alert.setContentText("创建目录失败: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "SFTP-Mkdir").start();
    }

    @Override
    protected void doUploadSingle(File localFile) throws Exception {
        doUploadSingle(localFile, localFile.getName());
    }

    @Override
    protected void doUploadSingle(File localFile, String relativePath) throws Exception {
        String remotePath = joinPath(currentPath, relativePath.replace('\\', '/'));
        sftpClient.upload(localFile.getAbsolutePath(), remotePath);
    }

    @Override
    protected void doUploadDirectory(String relativePath) throws Exception {
        String remotePath = joinPath(currentPath, relativePath.replace('\\', '/'));
        if (!sftpClient.exists(remotePath)) sftpClient.mkdir(remotePath);
    }

    @Override
    protected void doDownload(FileItem item, File localFile) {
        new Thread(() -> {
            try {
                sftpClient.download(item.getPath(), localFile.getAbsolutePath());
                Platform.runLater(() -> {
                    setStatus("下载完成: " + item.getName());
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("下载完成");
                    alert.setHeaderText(null);
                    alert.setContentText("文件已保存到: " + localFile.getAbsolutePath());
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("下载失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("下载失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "SFTP-Download").start();
    }

    @Override
    protected File doDownloadToTemp(FileItem item) {
        if (item == null || item.isDirectory()) return null;
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
    protected boolean isConnected() {
        return sftpClient.isConnected();
    }

    @Override
    protected void loadColumnAsync(String path, int colIndex) {
        new Thread(() -> {
            try {
                if (!sftpClient.isConnected()) sftpClient.reconnect();
                sftpClient.cd(path);
                String realPath = sftpClient.pwd();
                List<SFTPClient.FileEntry> entries = sftpClient.listFiles(realPath);
                List<FileItem> items = new ArrayList<>();
                for (SFTPClient.FileEntry entry : entries) {
                    items.add(new FileItem(entry.getName(), entry.getPath(), entry.isDirectory(), entry.getSize(), entry.getModifyTime()));
                }
                Platform.runLater(() -> {
                    addColumn(colIndex, realPath, items);
                    setStatus(items.size() + " 个条目");
                });
            } catch (Exception e) {
                Platform.runLater(() -> setStatus("错误: " + e.getMessage()));
            }
        }, "SFTP-ColumnLoad").start();
    }

    // ==================== SFTP 连接 ====================
    /**
     * 建立SSH连接并加载home目录
     */
    private void connectAndLoad() {
        new Thread(() -> {
            int tunnelLocalPort = -1;
            try {
                // 先建立/复用跳板隧道（引用方式，按 configId+host:port 缓存并引用计数）
                try {
                    tunnelLocalPort = SshTunnelManager.resolve(config);
                } catch (Exception te) {
                    Platform.runLater(() -> {
                        statusDot.setFill(Color.RED);
                        stateLabel.setText("连接失败");
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText(null);
                        alert.setContentText("建立SSH跳板隧道失败: " + te.getMessage());
                        alert.showAndWait();
                    });
                    te.printStackTrace();
                    return;
                }
                String host = config.getHost();
                int port = config.getPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }
                JSch jsch = new JSch();
                List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
                if (keyPaths != null && !keyPaths.isEmpty()) {
                    for (String keyPath : keyPaths) {
                        if (keyPath != null && !keyPath.isEmpty()) {
                            String pwd = config.getPassword();
                            if (pwd != null && !pwd.isEmpty()) {
                                jsch.addIdentity(keyPath, pwd);
                            } else {
                                jsch.addIdentity(keyPath);
                            }
                        }
                    }
                }
                jschSession = jsch.getSession(config.getUsername(), host, port);
                if (keyPaths == null || keyPaths.isEmpty()) {
                    jschSession.setPassword(config.getPassword());
                }
                jschSession.setConfig("StrictHostKeyChecking", "no");
                jschSession.connect(30000);

                sftpClient.connect(jschSession);

                String home = sftpClient.pwd();
                Platform.runLater(() -> {
                    statusDot.setFill(Color.GREEN);
                    stateLabel.setText("已连接");
                    navigateTo(home);
                });
            } catch (Exception e) {
                if (tunnelLocalPort != -1) {
                    SshTunnelManager.release(config);
                }
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
        }, "SFTP-Connect").start();
    }

    // ==================== Markdown 编辑器 ====================
    /**
     * 打开 Markdown 编辑器 Tab
     */
    @Override
    protected void openMarkdownEditor(FileItem item) {
        String filePath = item.getPath();
        String fileName = item.getDisplayName();

        // 复用已打开的 Tab
        for (Tab tab : editorTabPane.getTabs()) {
            if (tab.getUserData() instanceof String tabKey && tabKey.equals(filePath)) {
                editorTabPane.getSelectionModel().select(tab);
                return;
            }
        }

        // 占位 Tab
        Tab editorTab = new Tab(fileName);
        editorTab.setUserData(filePath);
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(40, 40);
        StackPane loading = new StackPane(indicator);
        loading.setStyle("-fx-background-color: white;");
        editorTab.setContent(loading);
        editorTabPane.getTabs().add(editorTab);
        editorTabPane.getSelectionModel().select(editorTab);

        // 异步下载文件内容
        new Thread(() -> {
            try {
                File tempFile = File.createTempFile("tomato-sftp-md-", ".md");
                sftpClient.download(filePath, tempFile.getAbsolutePath());
                String content = new String(Files.readAllBytes(tempFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (!tempFile.delete()) tempFile.deleteOnExit();

                Platform.runLater(() -> {
                    MarkdownEditorPane.Storage storage = (c, onSuccess, onError) -> new Thread(() -> {
                        try {
                            File tmp = File.createTempFile("tomato-sftp-save-", ".md");
                            Files.write(tmp.toPath(), c.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            sftpClient.upload(tmp.getAbsolutePath(), filePath);
                            if (!tmp.delete()) tmp.deleteOnExit();
                            Platform.runLater(onSuccess);
                        } catch (Exception e) {
                            Platform.runLater(() -> onError.accept(e.getMessage()));
                        }
                    }, "SFTP-MDSave").start();

                    MarkdownEditorPane editor = new MarkdownEditorPane(fileName, content, storage);
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
            } catch (Exception e) {
                Platform.runLater(() -> editorTab.setContent(new Label("加载失败: " + e.getMessage())));
            }
        }, "SFTP-MDLoad").start();
    }

    /**
     * 右键创建文件：弹窗输入文件名，在当前目录下新建空 markdown 并打开编辑器
     */
    @Override
    protected void handleCreateFile() {
        TextInputDialog dialog = new TextInputDialog("新文件.md");
        dialog.setTitle("创建文件");
        dialog.setHeaderText(null);
        dialog.setContentText("文件名：");
        DialogPositionUtil.centerOnOwner(dialog, this);
        dialog.showAndWait().ifPresent(name -> {
            String fileName = name.trim();
            if (fileName.isEmpty()) return;
            String filePath = joinPath(getCurrentPath(), fileName);
            new Thread(() -> {
                try {
                    // 上传空内容创建文件
                    File tmp = File.createTempFile("tomato-sftp-new-", ".tmp");
                    Files.write(tmp.toPath(), new byte[0]);
                    sftpClient.upload(tmp.getAbsolutePath(), filePath);
                    if (!tmp.delete()) tmp.deleteOnExit();
                    Platform.runLater(() -> {
                        refresh();
                        FileItem newItem = new FileItem();
                        newItem.setName(fileName);
                        newItem.setPath(filePath);
                        newItem.setDirectory(false);
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
            }, "SFTP-CreateFile").start();
        });
    }

    // ==================== 图片预览 ====================
    /**
     * 预览图片（使用当前选中项）
     */
    @Override
    protected void handlePreview() {
        FileItem selected = getSelectedItem();
        if (selected == null || selected.isDirectory() || !isImageFile(selected.getDisplayName())) return;
        handlePreview(selected);
    }

    /**
     * 预览图片：从SFTP下载并显示
     */
    private void handlePreview(FileItem item) {
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
                        File tempFile = File.createTempFile("tomato-sftp-preview-", ".img");
                        sftpClient.download(currentItem.getPath(), tempFile.getAbsolutePath());
                        byte[] imageBytes = Files.readAllBytes(tempFile.toPath());
                        if (!tempFile.delete()) tempFile.deleteOnExit();

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
                }, "SFTP-LoadImage").start();
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
                item.getDisplayName(), (int) imgWidth, (int) imgHeight, item.getFormattedSize(),
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
                try {
                    sftpClient.download(item.getPath(), saveFile.getAbsolutePath());
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
            }, "SFTP-Download").start();
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
                        sftpClient.rm(item.getPath());
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
                }, "SFTP-Delete").start();
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
        // 收集所有需要加载缩略图的图片文件
        List<FileItem> imageItems = new ArrayList<>();
        for (FileItem item : getFileData()) {
            if (!item.isDirectory() && isImageFile(item.getDisplayName())) {
                imageItems.add(item);
            }
        }
        if (imageItems.isEmpty()) return;

        // 单线程顺序下载，避免并发访问SFTP通道
        new Thread(() -> {
            for (FileItem item : imageItems) {
                try {
                    File tempFile = File.createTempFile("tomato-sftp-thumb-", ".img");
                    sftpClient.download(item.getPath(), tempFile.getAbsolutePath());
                    byte[] imageBytes = Files.readAllBytes(tempFile.toPath());
                    if (!tempFile.delete()) tempFile.deleteOnExit();

                    Platform.runLater(() -> {
                        try {
                            Image image = new Image(new java.io.ByteArrayInputStream(imageBytes));
                            if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) return;
                            updateIconBoxWithThumbnail(item, image);
                        } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            }
        }, "SFTP-Thumbnails").start();
    }

    // ==================== 断开连接 ====================
    /**
     * 断开连接，释放资源
     */
    public void disconnect() {
        new Thread(() -> {
            try {
                sftpClient.disconnect();
            } catch (Exception ignored) {}
            if (jschSession != null && jschSession.isConnected()) {
                jschSession.disconnect();
            }
            jschSession = null;
            // 释放跳板隧道引用（引用计数归零时才真正断开隧道，支持多会话共享）
            SshTunnelManager.release(config);
        }, "SFTP-Disconnect").start();
    }

    // ==================== 工具方法 ====================
    /**
     * 拼接路径：确保中间有且仅有一个 /
     */
    private static String joinPath(String base, String name) {
        if (base == null || base.isEmpty() || "/".equals(base)) return "/" + name;
        if (base.endsWith("/")) return base + name;
        return base + "/" + name;
    }
}
