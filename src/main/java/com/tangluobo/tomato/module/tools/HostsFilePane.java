package com.tangluobo.tomato.module.tools;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Hosts 文件管理工具
 * 支持分组管理、启用/禁用环境配置、编辑和保存系统 hosts 文件
 */
public class HostsFilePane extends VBox {

    // ==================== 数据模型 ====================

    /**
     * Hosts 条目（IP-域名映射）
     */
    public static class HostsEntry {
        private String ip;
        private String domain;

        public HostsEntry(String ip, String domain) {
            this.ip = ip;
            this.domain = domain;
        }

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }

        @Override
        public String toString() {
            return ip + " " + domain;
        }
    }

    /**
     * Hosts 分组
     */
    public static class HostsGroup {
        private String name;
        private boolean enabled;
        private List<HostsEntry> entries;

        public HostsGroup(String name, boolean enabled, List<HostsEntry> entries) {
            this.name = name;
            this.enabled = enabled;
            this.entries = entries;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<HostsEntry> getEntries() { return entries; }
        public void setEntries(List<HostsEntry> entries) { this.entries = entries; }
    }

    // ==================== 成员变量 ====================

    // 分组列表
    private List<HostsGroup> hostsGroups = new ArrayList<>();

    // 当前选中的组
    private HostsGroup selectedGroup;

    // 是否正在查看系统 Hosts 文件
    private boolean viewingSystemHosts = false;

    // 分组列表 UI
    private VBox groupListContainer;
    private ScrollPane groupScrollPane;

    // 编辑器
    private NumberedTextArea editorArea;
    private Label titleLabel;
    private HBox buttonBar;

    // 条目编辑区域
    private VBox entriesContainer;
    private ScrollPane entriesScrollPane;
    private Label entriesLabel;
    private HBox addEntryBox;

    // 状态标签
    private Label statusLabel;

    // 当前选中分组的条目编辑列表
    private List<HBox> entryRows = new ArrayList<>();

    // 持久化文件路径
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.tomata";
    private static final String CONFIG_FILE = CONFIG_DIR + "/hosts_groups.json";

    // ==================== 构造函数 ====================

    public HostsFilePane() {
        initializeUI();
        loadGroups();
        if (hostsGroups.isEmpty()) {
            initDefaultGroups();
        }
        refreshGroupList();
    }

    // ==================== UI 初始化 ====================

    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        // 自定义标题栏
        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 20, 14, 20));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");

        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);

        Label titleText = new Label("Hosts 文件管理");
        titleText.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        Label subtitleLabel = new Label("管理和切换不同环境的 Hosts 配置");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");

        titleBar.getChildren().addAll(titleIcon, titleText, titleSpacer, subtitleLabel);

        // 主体内容 - 分割面板
        SplitPane splitPane = new SplitPane();
        splitPane.setPadding(new Insets(10, 20, 10, 20));
        splitPane.setDividerPositions(0.25);

        // 左侧分组列表
        VBox leftPanel = createLeftPanel();
        // 右侧编辑区
        VBox rightPanel = createRightPanel();

        splitPane.getItems().addAll(leftPanel, rightPanel);

        // 状态标签
        statusLabel = new Label("");
        statusLabel.setPadding(new Insets(5, 20, 10, 20));
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        getChildren().addAll(titleBar, splitPane, statusLabel);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
    }

    /**
     * 创建左侧分组列表面板
     */
    private VBox createLeftPanel() {
        VBox panel = new VBox(10);
        panel.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e8e8e8; -fx-border-width: 0 1 0 0;");

        // 标题
        Label listTitle = new Label("环境列表");
        listTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333; -fx-padding: 10 15 5 15;");

        // 添加分组按钮
        Button addGroupBtn = new Button("+ 新建分组");
        addGroupBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 12; -fx-background-radius: 4; -fx-cursor: hand;");
        addGroupBtn.setMaxWidth(Double.MAX_VALUE);
        addGroupBtn.setOnAction(e -> addNewGroup());

        HBox btnBox = new HBox();
        btnBox.setPadding(new Insets(5, 15, 10, 15));
        btnBox.getChildren().add(addGroupBtn);

        // 分组列表容器
        groupListContainer = new VBox(0);
        groupListContainer.setPadding(new Insets(0));
        groupListContainer.setStyle("-fx-background-color: #ffffff;");

        groupScrollPane = new ScrollPane(groupListContainer);
        groupScrollPane.setFitToWidth(true);
        groupScrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        groupScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        groupScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        panel.getChildren().addAll(listTitle, btnBox, groupScrollPane);
        VBox.setVgrow(groupScrollPane, Priority.ALWAYS);

        return panel;
    }

    /**
     * 创建右侧编辑面板
     */
    private VBox createRightPanel() {
        VBox panel = new VBox(10);
        panel.setStyle("-fx-background-color: #ffffff;");

        // 当前分组标题
        titleLabel = new Label("请选择或创建一个分组");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        titleLabel.setPadding(new Insets(5, 0, 5, 0));

        // 条目编辑区域
        entriesLabel = new Label("Hosts 条目");
        entriesLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #555;");
        entriesLabel.setPadding(new Insets(5, 0, 0, 0));

        entriesContainer = new VBox(5);
        entriesContainer.setPadding(new Insets(5));
        entriesContainer.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");

        entriesScrollPane = new ScrollPane(entriesContainer);
        entriesScrollPane.setFitToWidth(true);
        entriesScrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(entriesScrollPane, Priority.ALWAYS);

        // 添加条目按钮
        Button addEntryBtn = new Button("+ 添加条目");
        addEntryBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 12; -fx-background-radius: 4; -fx-cursor: hand;");
        addEntryBtn.setMaxWidth(Double.MAX_VALUE);
        addEntryBtn.setOnAction(e -> addNewEntry());

        addEntryBox = new HBox();
        addEntryBox.setPadding(new Insets(5, 0, 0, 0));
        addEntryBox.getChildren().add(addEntryBtn);

        // 带行号的编辑器
        editorArea = new NumberedTextArea();
        editorArea.setEditable(false);
        VBox.setVgrow(editorArea, Priority.ALWAYS);

        // 按钮区
        buttonBar = new HBox(10);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(5, 0, 0, 0));

        Button saveBtn = new Button("保存到系统");
        saveBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        saveBtn.setOnAction(e -> saveToHostsFile());

        Button refreshBtn = new Button("刷新预览");
        refreshBtn.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        refreshBtn.setOnAction(e -> refreshEditorPreview());

        buttonBar.getChildren().addAll(refreshBtn, saveBtn);

        panel.getChildren().addAll(
                titleLabel,
                entriesLabel,
                entriesScrollPane,
                addEntryBox,
                editorArea,
                buttonBar
        );

        return panel;
    }

    // ==================== 默认数据 ====================

    private void initDefaultGroups() {
        hostsGroups.add(new HostsGroup("开发环境", false, Arrays.asList(
                new HostsEntry("127.0.0.1", "localhost"),
                new HostsEntry("127.0.0.1", "dev.example.com")
        )));
        hostsGroups.add(new HostsGroup("测试环境", false, Arrays.asList(
                new HostsEntry("192.168.1.100", "test.example.com"),
                new HostsEntry("192.168.1.101", "test-api.example.com")
        )));
    }

    // ==================== 分组列表渲染 ====================

    private void refreshGroupList() {
        groupListContainer.getChildren().clear();

        // 系统 Hosts 选项
        VBox systemItem = createSystemHostsItem();
        groupListContainer.getChildren().add(systemItem);

        // 分隔线
        Region separator = new Region();
        separator.setStyle("-fx-background-color: #e0e0e0; -fx-pref-height: 1px;");
        separator.setPrefHeight(1);
        groupListContainer.getChildren().add(separator);

        // 普通分组
        for (HostsGroup group : hostsGroups) {
            VBox itemBox = createGroupItemBox(group);
            groupListContainer.getChildren().add(itemBox);
        }
    }

    /**
     * 创建系统 Hosts 列表项
     */
    private VBox createSystemHostsItem() {
        VBox itemBox = new VBox(0);

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");

        // 图标
        Label iconLabel = new Label("📄");
        iconLabel.setStyle("-fx-font-size: 16px;");

        Label nameLabel = new Label("系统Hosts");
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        row.getChildren().addAll(iconLabel, nameLabel);

        Region separator = new Region();
        separator.setStyle("-fx-background-color: #f0f0f0; -fx-pref-height: 1px;");
        separator.setPrefHeight(1);

        itemBox.getChildren().addAll(row, separator);

        row.setOnMouseClicked(e -> showSystemHosts());
        row.setOnMouseEntered(e -> row.setStyle("-fx-background-color: #f5f5f5; -fx-cursor: hand;"));
        row.setOnMouseExited(e -> row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;"));

        return itemBox;
    }

    /**
     * 创建分组列表项
     */
    private VBox createGroupItemBox(HostsGroup group) {
        VBox itemBox = new VBox(0);

        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 12, 12, 12));
        row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");

        // 选中样式
        if (selectedGroup == group) {
            row.setStyle("-fx-background-color: #e8f4ff; -fx-cursor: hand;");
        }

        // 左侧：名称 + 条目数（上下结构）
        VBox leftContent = new VBox(2);
        leftContent.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(group.getName());
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Label countLabel = new Label(group.getEntries().size() + " 个条目");
        countLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");

        leftContent.getChildren().addAll(nameLabel, countLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 启用开关（iOS 风格）
        Switch enableSwitch = new Switch();
        enableSwitch.syncSelected(group.isEnabled());
        enableSwitch.setOnToggle(() -> {
            group.setEnabled(enableSwitch.isSelected());
            toggleGroupEnabled(group);
        });

        row.getChildren().addAll(leftContent, spacer, enableSwitch);

        // 右键菜单
        ContextMenu contextMenu = new ContextMenu();
        MenuItem editItem = new MenuItem("编辑分组名");
        editItem.setOnAction(e -> editGroupName(group));
        MenuItem deleteItem = new MenuItem("删除分组");
        deleteItem.setStyle("-fx-text-fill: #e53935;");
        deleteItem.setOnAction(e -> {
            if (showConfirm("删除确认", "确定要删除分组 \"" + group.getName() + "\" 吗？")) {
                deleteGroup(group);
            }
        });
        contextMenu.getItems().addAll(editItem, deleteItem);

        row.setOnContextMenuRequested(e -> contextMenu.show(row, e.getScreenX(), e.getScreenY()));

        Region separator = new Region();
        separator.setStyle("-fx-background-color: #f0f0f0; -fx-pref-height: 1px;");
        separator.setPrefHeight(1);

        itemBox.getChildren().addAll(row, separator);

        row.setOnMouseClicked(e -> selectGroup(group));
        row.setOnMouseEntered(e -> {
            if (selectedGroup != group) {
                row.setStyle("-fx-background-color: #f5f5f5; -fx-cursor: hand;");
            }
        });
        row.setOnMouseExited(e -> {
            if (selectedGroup != group) {
                row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
            }
        });

        return itemBox;
    }

    // ==================== 分组操作 ====================

    private void selectGroup(HostsGroup group) {
        // 切换前保存当前编辑器内容
        saveCurrentEditorToGroup();
        selectedGroup = group;
        refreshGroupList();
        updateRightPanel();
    }

    private void deleteGroup(HostsGroup group) {
        // 如果分组已启用，先禁用它
        if (group.isEnabled()) {
            group.setEnabled(false);
            toggleGroupEnabled(group);
        }

        hostsGroups.remove(group);
        if (selectedGroup == group) {
            selectedGroup = null;
            updateRightPanel();
        }
        saveGroups();
        refreshGroupList();
        showSuccess("分组已删除");
    }

    private void addNewGroup() {
        String newName = "新分组" + (hostsGroups.size() + 1);
        HostsGroup newGroup = new HostsGroup(newName, false, new ArrayList<>());
        hostsGroups.add(newGroup);
        selectedGroup = newGroup;
        saveGroups();
        refreshGroupList();
        updateRightPanel();

        // 自动编辑名称
        Platform.runLater(() -> editGroupName(newGroup));
        showSuccess("新分组已创建");
    }

    private void editGroupName(HostsGroup group) {
        TextInputDialog dialog = new TextInputDialog(group.getName());
        dialog.setTitle("编辑分组名");
        dialog.setHeaderText("修改分组名称");
        dialog.setContentText("分组名称:");

        dialog.showAndWait().ifPresent(name -> {
            String trimmedName = name.trim();
            if (!trimmedName.isEmpty()) {
                group.setName(trimmedName);
                saveGroups();
                refreshGroupList();
                if (selectedGroup == group) {
                    titleLabel.setText(trimmedName + " 配置");
                }
                showSuccess("分组名已更新");
            }
        });
    }

    private void toggleGroupEnabled(HostsGroup group) {
        // 先保存当前编辑器内容（如果有选中的分组正在编辑）
        if (selectedGroup == group) {
            saveCurrentEditorToGroup();
        }
        saveGroups();
        boolean success = updateHostsFile();

        if (!success) {
            // 写入失败，回滚开关状态
            group.setEnabled(!group.isEnabled());
            refreshGroupList();
            if (selectedGroup == group) {
                editorArea.setText(buildGroupContent(group));
            }
            return;
        }

        // 刷新编辑器显示
        if (selectedGroup == group) {
            editorArea.setText(buildGroupContent(group));
        }
        refreshGroupList();
        showSuccess(group.isEnabled() ? "分组已启用" : "分组已禁用");
    }

    // ==================== 右侧面板更新 ====================

    private void updateRightPanel() {
        if (selectedGroup == null) {
            viewingSystemHosts = false;
            titleLabel.setText("请选择或创建一个分组");
            setEntriesVisible(false);
            editorArea.clear();
            editorArea.setEditable(false);
            editorArea.setDisable(true);
            return;
        }

        viewingSystemHosts = false;
        titleLabel.setText(selectedGroup.getName() + " 配置");
        setEntriesVisible(false);

        // 显示当前分组的条目内容（可编辑）
        editorArea.setEditable(true);
        editorArea.setDisable(false);
        editorArea.setText(buildGroupContent(selectedGroup));
    }

    /**
     * 渲染条目列表
     */
    private void renderEntries() {
        entryRows.clear();
        entriesContainer.getChildren().clear();

        if (selectedGroup == null) return;

        List<HostsEntry> entries = selectedGroup.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            HostsEntry entry = entries.get(i);
            HBox row = createEntryRow(entry, i);
            entryRows.add(row);
            entriesContainer.getChildren().add(row);
        }

        if (entries.isEmpty()) {
            Label emptyLabel = new Label("暂无条目，点击\"+ 添加条目\"开始");
            emptyLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999; -fx-padding: 10;");
            entriesContainer.getChildren().add(emptyLabel);
        }
    }

    /**
     * 创建条目行
     */
    private HBox createEntryRow(HostsEntry entry, int index) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5));
        row.setStyle("-fx-background-color: white; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");

        // 序号
        Label indexLabel = new Label(String.valueOf(index + 1));
        indexLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #888; -fx-min-width: 25;");

        // IP 输入
        TextField ipField = new TextField(entry.getIp());
        ipField.setPromptText("IP 地址");
        ipField.setStyle("-fx-font-size: 12px; -fx-padding: 4 8;");
        ipField.setPrefWidth(150);
        ipField.textProperty().addListener((obs, oldVal, newVal) -> {
            entry.setIp(newVal);
            refreshEditorPreview();
        });

        // 域名输入
        TextField domainField = new TextField(entry.getDomain());
        domainField.setPromptText("域名");
        domainField.setStyle("-fx-font-size: 12px; -fx-padding: 4 8;");
        domainField.setPrefWidth(200);
        domainField.textProperty().addListener((obs, oldVal, newVal) -> {
            entry.setDomain(newVal);
            refreshEditorPreview();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 删除按钮
        Button deleteBtn = new Button("删除");
        deleteBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 3 10; -fx-background-radius: 3; -fx-cursor: hand;");
        deleteBtn.setOnAction(e -> deleteEntry(index));

        row.getChildren().addAll(indexLabel, ipField, domainField, spacer, deleteBtn);
        return row;
    }

    private void addNewEntry() {
        if (selectedGroup == null) {
            showError("请先选择或创建一个分组");
            return;
        }
        selectedGroup.getEntries().add(new HostsEntry("", ""));
        saveGroups();
        renderEntries();
        refreshEditorPreview();
    }

    private void deleteEntry(int index) {
        if (selectedGroup == null) return;
        selectedGroup.getEntries().remove(index);
        saveGroups();
        renderEntries();
        refreshEditorPreview();
    }

    // ==================== Hosts 文件操作 ====================

    /**
     * 获取系统 hosts 文件路径
     */
    private String getHostsFilePath() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "C:\\Windows\\System32\\drivers\\etc\\hosts";
        } else {
            return "/etc/hosts";
        }
    }

    /**
     * 读取系统 hosts 文件
     */
    private String readHostsFile() {
        try {
            Path path = Paths.get(getHostsFilePath());
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        } catch (Exception e) {
            System.err.println("读取 hosts 文件失败: " + e.getMessage());
        }
        return "";
    }

    /**
     * 写入系统 hosts 文件（权限不足时提示 sudo 密码）
     * @return true 表示写入成功
     */
    private boolean writeHostsFile(String content) {
        // 先尝试直接写入
        try {
            Path path = Paths.get(getHostsFilePath());
            Files.writeString(path, content);
            return true;
        } catch (Exception e) {
            // 直接写入失败，尝试 sudo
        }

        // 非 Linux 系统直接报错
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("nix") && !os.contains("nux") && !os.contains("mac")) {
            showError("写入 hosts 文件失败，可能需要管理员权限");
            return false;
        }

        // 弹出 sudo 密码输入框
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("需要管理员权限");
        dialog.setHeaderText("写入系统 hosts 文件需要管理员权限");

        ButtonType okButtonType = new ButtonType("确认", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("sudo 密码");
        passwordField.setStyle("-fx-font-size: 13px;");

        VBox dialogContent = new VBox(8);
        dialogContent.setPadding(new Insets(10));
        Label label = new Label("请输入 sudo 密码:");
        label.setStyle("-fx-font-size: 13px;");
        dialogContent.getChildren().addAll(label, passwordField);
        dialog.getDialogPane().setContent(dialogContent);

        dialog.setResultConverter(button -> {
            if (button == okButtonType) {
                return passwordField.getText();
            }
            return null;
        });

        return dialog.showAndWait().map(password -> {
            try {
                // 写入临时文件
                Path tempFile = Files.createTempFile("hosts_tmp_", ".txt");
                Files.writeString(tempFile, content);

                // 用 sudo cp 覆盖 hosts 文件
                ProcessBuilder pb = new ProcessBuilder("sudo", "-S", "cp", tempFile.toString(), getHostsFilePath());
                Process process = pb.start();
                process.getOutputStream().write((password + "\n").getBytes());
                process.getOutputStream().flush();
                process.getOutputStream().close();

                int exitCode = process.waitFor();
                Files.deleteIfExists(tempFile);

                if (exitCode == 0) {
                    return true;
                } else {
                    String errorMsg = new String(process.getErrorStream().readAllBytes()).trim();
                    showError("写入失败: " + (errorMsg.isEmpty() ? "密码错误或权限不足" : errorMsg));
                    return false;
                }
            } catch (Exception ex) {
                showError("写入 hosts 文件失败: " + ex.getMessage());
                return false;
            }
        }).orElse(false);
    }

    /**
     * 显示系统 Hosts 文件
     */
    private void showSystemHosts() {
        // 切换前保存当前编辑器内容
        saveCurrentEditorToGroup();
        selectedGroup = null;
        viewingSystemHosts = true;
        titleLabel.setText("系统 Hosts 文件");

        // 隐藏条目编辑区域
        setEntriesVisible(false);

        // 显示完整系统文件（带行号）
        editorArea.setEditable(true);
        editorArea.setDisable(false);
        String content = readHostsFile();
        editorArea.setText(content);
    }

    /**
     * 切换条目编辑区域的可见性
     */
    private void setEntriesVisible(boolean visible) {
        entriesLabel.setVisible(visible);
        entriesLabel.setManaged(visible);
        entriesScrollPane.setVisible(visible);
        entriesScrollPane.setManaged(visible);
        addEntryBox.setVisible(visible);
        addEntryBox.setManaged(visible);

        buttonBar.setVisible(visible);
        buttonBar.setManaged(visible);
    }

    /**
     * 刷新编辑器预览
     */
    private void refreshEditorPreview() {
        if (viewingSystemHosts) {
            editorArea.setText(readHostsFile());
        } else if (selectedGroup != null) {
            editorArea.setText(buildGroupContent(selectedGroup));
        }
    }

    /**
     * 构建单个分组的 hosts 内容
     */
    private String buildGroupContent(HostsGroup group) {
        StringBuilder content = new StringBuilder();
        content.append("# ").append(group.getName()).append("\n");
        for (HostsEntry entry : group.getEntries()) {
            content.append(entry.getIp()).append(" ").append(entry.getDomain()).append("\n");
        }
        content.append("# End ").append(group.getName());
        return content.toString();
    }

    /**
     * 从文本解析条目（忽略注释行和空行）
     */
    private List<HostsEntry> parseEntriesFromText(String text) {
        List<HostsEntry> entries = new ArrayList<>();
        if (text == null || text.isEmpty()) return entries;

        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\s+", 2);
            if (parts.length >= 2) {
                entries.add(new HostsEntry(parts[0].trim(), parts[1].trim()));
            }
        }
        return entries;
    }

    /**
     * 保存当前编辑器内容到分组
     */
    private void saveCurrentEditorToGroup() {
        if (selectedGroup == null || viewingSystemHosts) return;
        selectedGroup.setEntries(parseEntriesFromText(editorArea.getText()));
        saveGroups();
    }

    /**
     * 更新 hosts 文件（在启用/禁用分组时调用）
     * @return true 表示写入成功
     */
    private boolean updateHostsFile() {
        String newContent = readHostsFile();

        // 移除旧的分组内容
        for (HostsGroup group : hostsGroups) {
            String groupComment = "# " + group.getName();
            String endComment = "# End " + group.getName();

            int startIndex = newContent.indexOf(groupComment);
            if (startIndex != -1) {
                int endIndex = newContent.indexOf(endComment, startIndex);
                if (endIndex != -1) {
                    newContent = newContent.substring(0, startIndex) + newContent.substring(endIndex + endComment.length());
                }
            }
        }

        // 添加启用的分组
        StringBuilder enabledContent = new StringBuilder();
        for (HostsGroup group : hostsGroups) {
            if (group.isEnabled() && !group.getEntries().isEmpty()) {
                enabledContent.append("\n# ").append(group.getName()).append("\n");
                for (HostsEntry entry : group.getEntries()) {
                    enabledContent.append(entry.getIp()).append(" ").append(entry.getDomain()).append("\n");
                }
                enabledContent.append("# End ").append(group.getName()).append("\n");
            }
        }

        newContent = newContent.trim() + enabledContent;
        boolean success = writeHostsFile(newContent);

        // 根据当前视图刷新显示
        if (viewingSystemHosts) {
            editorArea.setText(readHostsFile());
        } else if (selectedGroup != null) {
            refreshEditorPreview();
        }

        return success;
    }

    /**
     * 保存到系统 hosts 文件
     */
    private void saveToHostsFile() {
        if (viewingSystemHosts || selectedGroup == null) {
            // 保存系统 hosts 编辑
            if (writeHostsFile(editorArea.getText())) {
                showSuccess("Hosts 文件已保存");
            }
        } else {
            // 保存当前分组内容并更新系统
            saveCurrentEditorToGroup();
            if (updateHostsFile()) {
                showSuccess("配置已保存到系统 Hosts 文件");
            }
        }
    }

    // ==================== 持久化 ====================

    private void saveGroups() {
        try {
            File dir = new File(CONFIG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File configFile = new File(CONFIG_FILE);
            StringBuilder json = new StringBuilder();
            json.append("{\"groups\":[");

            for (int i = 0; i < hostsGroups.size(); i++) {
                HostsGroup group = hostsGroups.get(i);
                if (i > 0) json.append(",");
                json.append("{\"name\":\"").append(escapeJson(group.getName())).append("\",");
                json.append("\"enabled\":").append(group.isEnabled()).append(",");
                json.append("\"entries\":[");

                for (int j = 0; j < group.getEntries().size(); j++) {
                    HostsEntry entry = group.getEntries().get(j);
                    if (j > 0) json.append(",");
                    json.append("{\"ip\":\"").append(escapeJson(entry.getIp())).append("\",");
                    json.append("\"domain\":\"").append(escapeJson(entry.getDomain())).append("\"}");
                }
                json.append("]}");
            }
            json.append("]}");

            Files.writeString(configFile.toPath(), json.toString());
        } catch (Exception e) {
            System.err.println("保存分组配置失败: " + e.getMessage());
        }
    }

    private void loadGroups() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (!configFile.exists()) return;

            String content = Files.readString(configFile.toPath());
            hostsGroups = parseGroups(content);
        } catch (Exception e) {
            System.err.println("加载分组配置失败: " + e.getMessage());
            hostsGroups = new ArrayList<>();
        }
    }

    private List<HostsGroup> parseGroups(String json) {
        List<HostsGroup> groups = new ArrayList<>();
        try {
            // 简单的 JSON 解析（避免引入额外依赖）
            json = json.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) return groups;

            // 提取 groups 数组
            int groupsStart = json.indexOf("\"groups\":[");
            if (groupsStart == -1) return groups;

            int arrayStart = json.indexOf("[", groupsStart);
            int arrayEnd = findMatchingBracket(json, arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) return groups;

            String groupsArray = json.substring(arrayStart + 1, arrayEnd);

            // 解析每个分组对象
            List<String> groupStrs = splitJsonObjects(groupsArray);
            for (String groupStr : groupStrs) {
                HostsGroup group = parseGroup(groupStr);
                if (group != null) {
                    groups.add(group);
                }
            }
        } catch (Exception e) {
            System.err.println("解析分组数据失败: " + e.getMessage());
        }
        return groups;
    }

    private HostsGroup parseGroup(String json) {
        try {
            String name = extractString(json, "name");
            boolean enabled = extractBoolean(json, "enabled");

            List<HostsEntry> entries = new ArrayList<>();
            int entriesStart = json.indexOf("\"entries\":[");
            if (entriesStart != -1) {
                int arrayStart = json.indexOf("[", entriesStart);
                int arrayEnd = findMatchingBracket(json, arrayStart);
                if (arrayStart != -1 && arrayEnd != -1) {
                    String entriesArray = json.substring(arrayStart + 1, arrayEnd);
                    List<String> entryStrs = splitJsonObjects(entriesArray);
                    for (String entryStr : entryStrs) {
                        String ip = extractString(entryStr, "ip");
                        String domain = extractString(entryStr, "domain");
                        if (ip != null || domain != null) {
                            entries.add(new HostsEntry(ip != null ? ip : "", domain != null ? domain : ""));
                        }
                    }
                }
            }

            if (name != null) {
                return new HostsGroup(name, enabled, entries);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private String extractString(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) return null;

        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;

        return unescapeJson(json.substring(start, end));
    }

    private boolean extractBoolean(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int start = json.indexOf(searchKey);
        if (start == -1) return false;

        start += searchKey.length();
        int end = start;
        while (end < json.length() && (Character.isLetter(json.charAt(end)) || json.charAt(end) == ' ')) {
            end++;
        }

        String value = json.substring(start, end).trim();
        return "true".equalsIgnoreCase(value);
    }

    private int findMatchingBracket(String json, int start) {
        if (start < 0 || start >= json.length()) return -1;

        char openChar = json.charAt(start);
        char closeChar = openChar == '[' ? ']' : '}';

        int count = 0;
        boolean inString = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == openChar) count++;
                if (c == closeChar) {
                    count--;
                    if (count == 0) return i;
                }
            }
        }
        return -1;
    }

    private List<String> splitJsonObjects(String json) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && start != -1) {
                        result.add(json.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return result;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    // ==================== 提示信息 ====================

    private boolean showConfirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        return alert.showAndWait().filter(response -> response == ButtonType.OK).isPresent();
    }

    private void showError(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
    }

    private void showSuccess(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #388E3C;");
        PauseTransition pause = new PauseTransition(Duration.millis(3000));
        pause.setOnFinished(e -> {
            if (statusLabel.getText().equals(msg)) {
                Platform.runLater(() -> {
                    statusLabel.setText("");
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
                });
            }
        });
        pause.play();
    }

    // ==================== 自定义控件 ====================

    /**
     * 带行号的文本编辑器
     */
    private static class NumberedTextArea extends BorderPane {
        private final TextArea textArea;
        private final VBox lineNumbersBox;

        NumberedTextArea() {
            textArea = new TextArea();
            textArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; -fx-padding: 8; -fx-background-color: transparent; -fx-border-color: transparent; -fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
            HBox.setHgrow(textArea, Priority.ALWAYS);

            lineNumbersBox = new VBox(2);
            lineNumbersBox.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 8 5 8 5;");
            lineNumbersBox.setMinWidth(45);
            lineNumbersBox.setPrefWidth(45);
            lineNumbersBox.setMaxWidth(45);

            HBox container = new HBox();
            container.getChildren().addAll(lineNumbersBox, textArea);
            container.setStyle("-fx-border-color: #c4d8ee; -fx-border-width: 1; -fx-border-radius: 4; -fx-background-color: #ffffff;");

            ScrollPane scrollPane = new ScrollPane(container);
            scrollPane.setFitToWidth(true);
            scrollPane.setFitToHeight(true);
            scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
            VBox.setVgrow(scrollPane, Priority.ALWAYS);
            HBox.setHgrow(scrollPane, Priority.ALWAYS);

            setCenter(scrollPane);
            setStyle("-fx-background-color: #ffffff;");
            setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

            updateLineNumbers();
            textArea.textProperty().addListener((obs, oldVal, newVal) -> updateLineNumbers());
        }

        private void updateLineNumbers() {
            String text = textArea.getText();
            int lineCount = text.isEmpty() ? 1 : text.split("\n", -1).length;
            lineNumbersBox.getChildren().clear();
            for (int i = 1; i <= lineCount; i++) {
                Label label = new Label(String.valueOf(i));
                label.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; -fx-text-fill: #999;");
                lineNumbersBox.getChildren().add(label);
            }
        }

        void setText(String text) { textArea.setText(text); }
        String getText() { return textArea.getText(); }
        void clear() { textArea.clear(); }
        void setEditable(boolean b) { textArea.setEditable(b); }
        javafx.beans.value.ObservableValue<String> textProperty() { return textArea.textProperty(); }
    }

    /**
     * iOS 风格的开关控件（参考域名列表中的 DDNS 开关样式）
     */
    private static class Switch extends StackPane {
        private static final double W = 38, H = 20, THUMB = 16;
        private final Region track = new Region();
        private final Circle thumb = new Circle(THUMB / 2.0);
        private boolean selected = false;
        private Runnable onToggle;

        Switch() {
            setPrefSize(W, H);
            setMinSize(W, H);
            setMaxSize(W, H);

            track.setPrefSize(W, H);
            track.setStyle("-fx-background-radius: 10;");

            thumb.setFill(Color.WHITE);
            thumb.setEffect(new DropShadow(4, 0, 1, Color.rgb(0, 0, 0, 0.25)));
            thumb.setTranslateX(-9);

            getChildren().addAll(track, thumb);
            updateVisual(false);

            disabledProperty().addListener((o, a, d) -> updateVisual(false));

            setOnMouseClicked(e -> {
                if (isDisabled()) return;
                e.consume();
                toggle();
            });
        }

        private void toggle() {
            selected = !selected;
            updateVisual(true);
            if (onToggle != null) onToggle.run();
        }

        void syncSelected(boolean s) {
            this.selected = s;
            updateVisual(false);
        }

        boolean isSelected() { return selected; }

        void setOnToggle(Runnable r) { this.onToggle = r; }

        private void updateVisual(boolean animate) {
            String bg = selected ? "#4CAF50" : "#bdbdbd";
            if (isDisabled()) bg = "#e0e0e0";
            track.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 10;");
            double tx = selected ? 9 : -9;
            if (animate) {
                Timeline tl = new Timeline(new KeyFrame(javafx.util.Duration.millis(150),
                        new KeyValue(thumb.translateXProperty(), tx)));
                tl.play();
            } else {
                thumb.setTranslateX(tx);
            }
        }
    }
}
