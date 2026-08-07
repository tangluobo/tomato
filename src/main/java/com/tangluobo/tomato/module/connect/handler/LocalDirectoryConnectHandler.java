package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.*;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 本地目录连接处理器
 * 以树形浏览指定本地目录，双击 Markdown 文件可在线编辑并保存回本地。
 */
public class LocalDirectoryConnectHandler implements ConnectHandler {

    /** 支持的 Markdown 扩展名集合 */
    private static final Set<String> MARKDOWN_EXTENSIONS = new HashSet<>();
    static {
        MARKDOWN_EXTENSIONS.add("md");
        MARKDOWN_EXTENSIONS.add("markdown");
        MARKDOWN_EXTENSIONS.add("mdown");
        MARKDOWN_EXTENSIONS.add("mkd");
    }

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.LOCAL_DIRECTORY;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            handleHostDoubleClick(module, hostItem, config);
        }
    }

    @Override
    public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }
        String path = config.getLocalDirectoryPath();
        if (path == null || path.trim().isEmpty()) {
            alert("目录路径未配置", "请在连接配置中设置本地目录路径");
            return;
        }
        Path dir = Path.of(path.trim());
        if (!Files.isDirectory(dir)) {
            alert("目录不存在", "目录不存在或不可访问：" + path);
            return;
        }
        loadDirectoryContents(module, hostItem, dir, config);
    }

    /**
     * 异步加载目录内容到 parentItem：目录在前、文件在后，按名称排序，过滤隐藏文件。
     * 子节点的绝对路径存入 DatabaseNodeData.databaseName 字段。
     */
    public void loadDirectoryContents(ConnectModule module, TreeItem<String> parentItem, Path dir, ConnectionConfig config) {
        new Thread(() -> {
            List<Path> entries = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.forEach(entries::add);
            } catch (Exception e) {
                Platform.runLater(() -> alert("加载失败", "无法读取目录 " + dir + ": " + e.getMessage()));
                return;
            }

            // 排序：目录优先，再按名称（忽略大小写）
            entries.sort(Comparator
                    .comparing((Path p) -> !Files.isDirectory(p))
                    .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

            Platform.runLater(() -> {
                parentItem.getChildren().clear();
                for (Path entry : entries) {
                    File f = entry.toFile();
                    if (f.isHidden()) continue;
                    String name = entry.getFileName().toString();
                    boolean isDir = Files.isDirectory(entry);
                    DatabaseNodeData.NodeType type = isDir
                            ? DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER
                            : DatabaseNodeData.NodeType.LOCAL_DIR_FILE;
                    DatabaseNodeData data = new DatabaseNodeData(type, name, config, entry.toAbsolutePath().toString());
                    TreeItem<String> child = new TreeItem<>(name);
                    child.setGraphic(module.getDbNodeIcon(data));
                    module.getDbNodeDataMap().put(child, data);
                    parentItem.getChildren().add(child);
                }
                parentItem.setExpanded(true);
            });
        }, "LocalDir-Load").start();
    }

    /** 双击目录节点：已加载则切换展开状态，否则加载子目录 */
    public void handleFolderDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        if (!item.getChildren().isEmpty()) {
            item.setExpanded(!item.isExpanded());
            return;
        }
        Path dir = Path.of(data.getDatabaseName());
        if (!Files.isDirectory(dir)) {
            alert("目录不存在", "目录不存在或不可访问：" + dir);
            return;
        }
        loadDirectoryContents(module, item, dir, data.getConnectionConfig());
    }

    /** 双击文件节点：Markdown 文件打开编辑器 Tab，其他文件忽略 */
    public void handleFileDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        String absolutePath = data.getDatabaseName();
        if (!isMarkdownFile(data.getName())) {
            return;
        }
        TabPane terminalTabPane = module.getTerminalTabPane();
        if (terminalTabPane == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        // 复用已打开的 Tab（以绝对路径作为 userData）
        for (Tab tab : terminalTabPane.getTabs()) {
            if (tab.getUserData() instanceof String tabPath && tabPath.equals(absolutePath)) {
                terminalTabPane.getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        String displayName = data.getName();
        Tab editorTab = new Tab(displayName);
        editorTab.setUserData(absolutePath);

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(40, 40);
        StackPane loading = new StackPane(indicator);
        loading.setStyle("-fx-background-color: white;");
        editorTab.setContent(loading);
        terminalTabPane.getTabs().add(editorTab);
        terminalTabPane.getSelectionModel().select(editorTab);

        new Thread(() -> {
            String content;
            try {
                content = Files.readString(Path.of(absolutePath));
            } catch (Exception e) {
                Platform.runLater(() -> editorTab.setContent(new Label("加载失败: " + e.getMessage())));
                return;
            }
            Platform.runLater(() -> {
                MarkdownEditorPane editor = new MarkdownEditorPane(displayName, content, (c, onSuccess, onError) ->
                        new Thread(() -> {
                            try {
                                Files.writeString(Path.of(absolutePath), c);
                                Platform.runLater(() -> {
                                    onSuccess.run();
                                    // 保存后刷新文件所在目录的树节点，保持树与文件系统同步
                                    refreshParentFolderAfterSave(module, absolutePath, data.getConnectionConfig());
                                });
                            } catch (Exception e) {
                                Platform.runLater(() -> onError.accept(e.getMessage()));
                            }
                        }, "MD-SaveLocal").start());
                editorTab.setContent(editor);
                editor.setOnTitleChange(title -> editorTab.setText(title));
                editorTab.setText(editor.getDisplayTitle());
                // 关闭前检查未保存
                editorTab.setOnCloseRequest(ev -> {
                    if (editor.isModified()) {
                        ev.consume();
                        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                        confirm.setTitle("未保存");
                        confirm.setHeaderText("文件 \"" + displayName + "\" 已修改未保存，是否保存？");
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
                            terminalTabPane.getTabs().remove(editorTab);
                        });
                    }
                });
            });
        }, "LocalDir-LoadMd").start();
    }

    /** 刷新节点：仅目录节点支持，清空子节点后重新加载 */
    public void refreshDbNode(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        if (data.getType() != DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER) return;
        for (TreeItem<String> child : item.getChildren()) {
            module.removeDbNodeDataRecursive(child);
        }
        item.getChildren().clear();
        Path dir = Path.of(data.getDatabaseName());
        if (Files.isDirectory(dir)) {
            loadDirectoryContents(module, item, dir, data.getConnectionConfig());
        }
    }

    /** 在主机节点（连接根目录）下新建 Markdown 文档 */
    public void handleCreateMarkdownAtHost(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        String path = config.getLocalDirectoryPath();
        if (path == null || path.trim().isEmpty()) {
            alert("目录路径未配置", "请在连接配置中设置本地目录路径");
            return;
        }
        Path dir = Path.of(path.trim());
        if (!Files.isDirectory(dir)) {
            alert("目录不存在", "目录不存在或不可访问：" + path);
            return;
        }
        createMarkdownFile(module, dir, config, () -> {
            // 已加载子节点时刷新，使其出现在树中
            if (!hostItem.getChildren().isEmpty()) {
                module.refreshDbHost(hostItem, config);
            }
        });
    }

    /** 在子目录节点下新建 Markdown 文档 */
    public void handleCreateMarkdownInFolder(ConnectModule module, TreeItem<String> folderItem, DatabaseNodeData data) {
        Path dir = Path.of(data.getDatabaseName());
        if (!Files.isDirectory(dir)) {
            alert("目录不存在", "目录不存在或不可访问：" + dir);
            return;
        }
        createMarkdownFile(module, dir, data.getConnectionConfig(), () -> refreshDbNode(module, folderItem, data));
    }

    /**
     * 弹窗输入文件名，在指定目录下创建空 Markdown 文件并打开编辑器 Tab。
     * 创建完成后（JavaFX 线程）执行 onCreated。
     */
    public void createMarkdownFile(ConnectModule module, Path dir, ConnectionConfig config, Runnable onCreated) {
        TextInputDialog dialog = new TextInputDialog("新文档.md");
        dialog.setTitle("新建 Markdown 文档");
        dialog.setHeaderText(null);
        dialog.setContentText("文件名：");
        String input = dialog.showAndWait().orElse(null);
        if (input == null) return;
        String fileName = input.trim();
        if (fileName.isEmpty()) return;
        // 禁止路径分隔符，避免越出目标目录
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains(":")
                || fileName.equals(".") || fileName.equals("..")) {
            alert("文件名无效", "文件名不能包含路径分隔符");
            return;
        }
        // 确保是 Markdown 扩展名
        if (!isMarkdownFile(fileName)) {
            fileName = fileName + ".md";
        }
        final String finalName = fileName;
        Path target = dir.resolve(finalName);
        if (Files.exists(target)) {
            alert("文件已存在", "文件已存在：" + target);
            return;
        }
        new Thread(() -> {
            try {
                Files.writeString(target, "");
                Platform.runLater(() -> {
                    DatabaseNodeData fileData = new DatabaseNodeData(
                            DatabaseNodeData.NodeType.LOCAL_DIR_FILE,
                            finalName,
                            config,
                            target.toAbsolutePath().toString());
                    handleFileDoubleClick(module, null, fileData);
                    if (onCreated != null) onCreated.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> alert("创建失败", "创建文件失败: " + e.getMessage()));
            }
        }, "LocalDir-CreateMd").start();
    }

    /** 在主机节点（连接根目录）下新建子目录 */
    public void handleCreateSubdirectoryAtHost(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        String path = config.getLocalDirectoryPath();
        if (path == null || path.trim().isEmpty()) {
            alert("目录路径未配置", "请在连接配置中设置本地目录路径");
            return;
        }
        Path dir = Path.of(path.trim());
        if (!Files.isDirectory(dir)) {
            alert("目录不存在", "目录不存在或不可访问：" + path);
            return;
        }
        createSubdirectory(dir, () -> {
            if (!hostItem.getChildren().isEmpty()) {
                module.refreshDbHost(hostItem, config);
            }
        });
    }

    /** 在子目录节点下新建子目录 */
    public void handleCreateSubdirectoryInFolder(ConnectModule module, TreeItem<String> folderItem, DatabaseNodeData data) {
        Path dir = Path.of(data.getDatabaseName());
        if (!Files.isDirectory(dir)) {
            alert("目录不存在", "目录不存在或不可访问：" + dir);
            return;
        }
        createSubdirectory(dir, () -> refreshDbNode(module, folderItem, data));
    }

    /**
     * 弹窗输入目录名，在指定目录下创建子目录。创建完成后（JavaFX 线程）执行 onCreated。
     */
    public void createSubdirectory(Path parentDir, Runnable onCreated) {
        TextInputDialog dialog = new TextInputDialog("新建目录");
        dialog.setTitle("新建目录");
        dialog.setHeaderText(null);
        dialog.setContentText("目录名：");
        String input = dialog.showAndWait().orElse(null);
        if (input == null) return;
        String dirName = input.trim();
        if (dirName.isEmpty()) return;
        if (dirName.contains("/") || dirName.contains("\\") || dirName.contains(":")
                || dirName.equals(".") || dirName.equals("..")) {
            alert("目录名无效", "目录名不能包含路径分隔符");
            return;
        }
        Path target = parentDir.resolve(dirName);
        if (Files.exists(target)) {
            alert("目录已存在", "目录已存在：" + target);
            return;
        }
        new Thread(() -> {
            try {
                Files.createDirectory(target);
                Platform.runLater(() -> {
                    if (onCreated != null) onCreated.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> alert("创建失败", "创建目录失败: " + e.getMessage()));
            }
        }, "LocalDir-CreateDir").start();
    }

    /** 构建节点右键菜单：目录/文件节点均提供"重命名"和"删除"（删除支持多选） */
    @Override
    public void populateNodeContextMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        if (data.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER) {
            MenuItem createDirItem = new MenuItem("新建目录");
            createDirItem.setGraphic(module.createMenuIcon("folder.png"));
            createDirItem.setOnAction(e -> handleCreateSubdirectoryInFolder(module, item, data));
            contextMenu.getItems().add(createDirItem);

            MenuItem createMdItem = new MenuItem("新建 Markdown 文档");
            createMdItem.setGraphic(module.createMenuIcon("md_add.png"));
            createMdItem.setOnAction(e -> handleCreateMarkdownInFolder(module, item, data));
            contextMenu.getItems().add(createMdItem);

            contextMenu.getItems().add(new SeparatorMenuItem());

            MenuItem renameItem = new MenuItem("重命名");
            renameItem.setOnAction(e -> module.startRenameEdit(item));
            contextMenu.getItems().add(renameItem);

            MenuItem refreshItem = new MenuItem("刷新");
            refreshItem.setOnAction(e -> module.refreshDbNode(item, data));
            contextMenu.getItems().add(refreshItem);

            contextMenu.getItems().add(new SeparatorMenuItem());

            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> module.deleteLocalDirNodes());
            contextMenu.getItems().add(deleteItem);
        } else if (data.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FILE) {
            MenuItem renameItem = new MenuItem("重命名");
            renameItem.setOnAction(e -> module.startRenameEdit(item));
            contextMenu.getItems().add(renameItem);

            contextMenu.getItems().add(new SeparatorMenuItem());

            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> module.deleteLocalDirNodes());
            contextMenu.getItems().add(deleteItem);
        }
    }

    /**
     * 重命名本地文件/目录：磁盘改名（Files.move）并更新树节点名称与存储的绝对路径。
     * 目录改名后其子节点路径失效，需清空子节点。
     */
    public void renameNode(ConnectModule module, TreeItem<String> item, DatabaseNodeData data, String oldName, String newName) {
        if (newName.contains("/") || newName.contains("\\") || newName.contains(":")
                || newName.equals(".") || newName.equals("..")) {
            alert("名称无效", "名称不能包含路径分隔符");
            return;
        }
        Path oldPath = Path.of(data.getDatabaseName());
        Path newPath = oldPath.resolveSibling(newName);
        new Thread(() -> {
            try {
                if (Files.exists(newPath)) {
                    Platform.runLater(() -> alert("已存在", "已存在同名项：" + newPath));
                    return;
                }
                Files.move(oldPath, newPath);
                Platform.runLater(() -> {
                    item.setValue(newName);
                    module.getDbNodeDataMap().put(item, new DatabaseNodeData(
                            data.getType(), newName, data.getConnectionConfig(),
                            newPath.toAbsolutePath().toString()));
                    // 目录改名后子节点路径失效，清空待重新展开加载
                    if (data.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER) {
                        for (TreeItem<String> child : item.getChildren()) {
                            module.removeDbNodeDataRecursive(child);
                        }
                        item.getChildren().clear();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> alert("重命名失败", "重命名失败: " + e.getMessage()));
            }
        }, "LocalDir-Rename").start();
    }

    /**
     * 多选删除本地文件/目录：读取树当前选中项，过滤出 LOCAL_DIR 节点，
     * 确认后从磁盘删除（目录递归），并从树中移除；同时关闭被删文件对应的编辑器 Tab。
     * 已选中项中互为祖先/后代关系的，仅删除祖先。
     */
    public void handleDeleteNodes(ConnectModule module) {
        ObservableList<TreeItem<String>> selectedItems = module.getTreeView().getSelectionModel().getSelectedItems();
        List<TreeItem<String>> candidates = new ArrayList<>();
        for (TreeItem<String> item : selectedItems) {
            DatabaseNodeData data = module.getDbNodeDataMap().get(item);
            if (data != null && (data.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER
                    || data.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FILE)) {
                candidates.add(item);
            }
        }
        if (candidates.isEmpty()) return;

        // 过滤掉作为其他选中项后代的项（删除祖先时会一并删除）
        List<TreeItem<String>> toDelete = new ArrayList<>();
        for (TreeItem<String> item : candidates) {
            boolean descendant = false;
            for (TreeItem<String> other : candidates) {
                if (item != other && isDescendant(item, other)) {
                    descendant = true;
                    break;
                }
            }
            if (!descendant) toDelete.add(item);
        }
        if (toDelete.isEmpty()) return;

        // 构建确认信息
        StringBuilder msg = new StringBuilder("确定要删除以下项目吗？此操作不可恢复！\n\n");
        for (TreeItem<String> item : toDelete) {
            DatabaseNodeData d = module.getDbNodeDataMap().get(item);
            String kind = (d.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER) ? "目录" : "文件";
            msg.append(kind).append("：").append(item.getValue()).append("\n");
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText(null);
        confirm.setContentText(msg.toString());
        ButtonType deleteBtn = new ButtonType("确认删除");
        confirm.getButtonTypes().setAll(deleteBtn, ButtonType.CANCEL);
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != deleteBtn) return;

        // 记录待删除路径（用于关闭相关编辑器 Tab）
        List<String> pathsToDelete = new ArrayList<>();
        for (TreeItem<String> item : toDelete) {
            DatabaseNodeData d = module.getDbNodeDataMap().get(item);
            pathsToDelete.add(d.getDatabaseName());
        }

        new Thread(() -> {
            List<TreeItem<String>> removed = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (TreeItem<String> item : toDelete) {
                DatabaseNodeData d = module.getDbNodeDataMap().get(item);
                if (d == null) continue;
                Path path = Path.of(d.getDatabaseName());
                try {
                    deleteOnDisk(path);
                    removed.add(item);
                } catch (Exception e) {
                    failed.add(item.getValue() + ": " + e.getMessage());
                }
            }
            Platform.runLater(() -> {
                // 关闭被删文件（及被删目录下文件）的编辑器 Tab
                closeEditorTabsForPaths(module, pathsToDelete);
                // 从树中移除已成功删除的节点
                for (TreeItem<String> item : removed) {
                    module.removeDbNodeDataRecursive(item);
                    TreeItem<String> parent = item.getParent();
                    if (parent != null) parent.getChildren().remove(item);
                }
                if (!failed.isEmpty()) {
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.setTitle("部分删除失败");
                    err.setHeaderText(null);
                    err.setContentText(String.join("\n", failed));
                    err.showAndWait();
                }
            });
        }, "LocalDir-Delete").start();
    }

    /** 递归删除目录或单个文件 */
    private void deleteOnDisk(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.delete(path);
        }
    }

    /** 关闭 userData 为被删文件路径、或位于被删目录之下的编辑器 Tab */
    private void closeEditorTabsForPaths(ConnectModule module, List<String> deletedPaths) {
        TabPane tabPane = module.getTerminalTabPane();
        if (tabPane == null) return;
        List<Tab> toClose = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            Object ud = tab.getUserData();
            if (!(ud instanceof String tabPath)) continue;
            Path tp = Path.of(tabPath).toAbsolutePath();
            for (String deleted : deletedPaths) {
                Path dp = Path.of(deleted).toAbsolutePath();
                if (tp.equals(dp) || tp.startsWith(dp)) {
                    toClose.add(tab);
                    break;
                }
            }
        }
        if (!toClose.isEmpty()) tabPane.getTabs().removeAll(toClose);
    }

    /** 判断 candidate 是否为 ancestor 的后代（树层级） */
    private boolean isDescendant(TreeItem<String> candidate, TreeItem<String> ancestor) {
        TreeItem<String> p = candidate.getParent();
        while (p != null) {
            if (p == ancestor) return true;
            p = p.getParent();
        }
        return false;
    }

    /**
     * 拖动移动本地文件/目录到新父目录：
     * 1. Files.move 磁盘迁移（目录整体移动，无需递归）
     * 2. 更新节点存储的绝对路径
     * 3. 目录移动后清空子节点（路径失效，重新展开加载）
     * 4. 树中将节点从原父节点迁移到新父节点并展开
     * 5. 关闭被移动文件/目录下文件的编辑器 Tab（路径已变）
     */
    public void moveNode(ConnectModule module, TreeItem<String> item, TreeItem<String> newParent,
                         Path sourcePath, Path destPath) {
        new Thread(() -> {
            try {
                if (Files.exists(destPath)) {
                    Platform.runLater(() -> alert("移动失败", "目标目录已存在同名项：" + destPath.getFileName()));
                    return;
                }
                Files.move(sourcePath, destPath);
                Platform.runLater(() -> {
                    DatabaseNodeData d = module.getDbNodeDataMap().get(item);
                    if (d == null) return;
                    // 更新节点存储的绝对路径
                    module.getDbNodeDataMap().put(item, new DatabaseNodeData(
                            d.getType(), item.getValue(), d.getConnectionConfig(),
                            destPath.toAbsolutePath().toString()));
                    // 目录移动后子节点路径失效，清空待重新加载
                    if (d.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER) {
                        for (TreeItem<String> child : item.getChildren()) {
                            module.removeDbNodeDataRecursive(child);
                        }
                        item.getChildren().clear();
                    }
                    // 树中迁移节点
                    TreeItem<String> oldParent = item.getParent();
                    if (oldParent != null) oldParent.getChildren().remove(item);
                    newParent.getChildren().add(item);
                    newParent.setExpanded(true);
                    // 关闭被移动文件/目录下文件的旧编辑器 Tab
                    closeEditorTabsForPaths(module, List.of(sourcePath.toAbsolutePath().toString()));
                });
            } catch (Exception e) {
                Platform.runLater(() -> alert("移动失败", "移动失败: " + e.getMessage()));
            }
        }, "LocalDir-Move").start();
    }

    // ==================== 工具方法 ====================

    /**
     * 保存后刷新文件所在目录的树节点：先在树中查找代表该目录的 LOCAL_DIR_FOLDER 节点；
     * 未找到则按连接根目录处理，刷新主机节点（使新建文件即时出现在树中）。
     */
    private void refreshParentFolderAfterSave(ConnectModule module, String filePath, ConnectionConfig config) {
        Path parentDir = Path.of(filePath).toAbsolutePath().getParent();
        if (parentDir == null) return;
        String parentDirStr = parentDir.toString();
        Map<TreeItem<String>, DatabaseNodeData> map = module.getDbNodeDataMap();
        TreeItem<String> folderItem = findFolderNode(module.getRoot(), parentDirStr, map);
        if (folderItem != null) {
            DatabaseNodeData folderData = map.get(folderItem);
            if (folderData != null) {
                module.refreshDbNode(folderItem, folderData);
            }
            return;
        }
        // 未找到子目录节点：可能是连接根目录
        String rootPath = config.getLocalDirectoryPath();
        if (rootPath == null || rootPath.trim().isEmpty()) return;
        String rootAbs = Path.of(rootPath.trim()).toAbsolutePath().toString();
        if (rootAbs.equals(parentDirStr)) {
            TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
            if (hostItem != null) {
                module.refreshDbHost(hostItem, config);
            }
        }
    }

    /** 递归查找 databaseName 等于 dirPath 的 LOCAL_DIR_FOLDER 节点 */
    private TreeItem<String> findFolderNode(TreeItem<String> node, String dirPath, Map<TreeItem<String>, DatabaseNodeData> map) {
        DatabaseNodeData data = map.get(node);
        if (data != null && data.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER
                && dirPath.equals(data.getDatabaseName())) {
            return node;
        }
        for (TreeItem<String> child : node.getChildren()) {
            TreeItem<String> found = findFolderNode(child, dirPath, map);
            if (found != null) return found;
        }
        return null;
    }

    private boolean isMarkdownFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return MARKDOWN_EXTENSIONS.contains(ext);
    }

    private void alert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
