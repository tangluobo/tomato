package com.tangluobo.tomato.module.tools;

import com.tangluobo.tomato.module.connect.view.SqlEditorPane;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发任务面板
 * 中间栏展示持久化的开发目录列表；右侧左列提供“目录 / 版本”标签页，
 * 右列提供代码编辑器。
 */
public class DevelopmentManagerPane extends VBox {

    private enum VersionMode {
        GIT, REALTIME
    }

    private static class DevDirectory {
        private String name;
        private final File path;
        private String projectType;
        private String jdkId;
        private String nodeId;
        private String flutterId;
        private String mavenId;
        private String mainClass;
        private String mainSourcePath;
        private final List<String> activeProfiles;
        private String runType;
        private String runTarget;

        private DevDirectory(String name, File path, String projectType,
                             String jdkId, String nodeId, String flutterId, String mavenId,
                             String mainClass, String mainSourcePath, List<String> activeProfiles,
                             String runType, String runTarget) {
            this.name = name;
            this.path = path;
            this.projectType = projectType;
            this.jdkId = jdkId;
            this.nodeId = nodeId;
            this.flutterId = flutterId;
            this.mavenId = mavenId;
            this.mainClass = mainClass;
            this.mainSourcePath = mainSourcePath;
            this.activeProfiles = new ArrayList<>(activeProfiles == null ? List.of() : activeProfiles);
            this.runType = runType;
            this.runTarget = runTarget;
        }

        public String getName() {
            return name;
        }

        public File getPath() {
            return path;
        }
    }

    private static class FileSnapshot {
        private final long size;
        private final long modifiedAt;

        private FileSnapshot(long size, long modifiedAt) {
            this.size = size;
            this.modifiedAt = modifiedAt;
        }

        private boolean changedFrom(FileSnapshot other) {
            return other == null || size != other.size || modifiedAt != other.modifiedAt;
        }
    }

    private static class ChangeItem {
        private final String name;
        private final String relativePath;
        private final String statusText;
        private final boolean directory;
        private boolean selected;
        private boolean indeterminate;

        private ChangeItem(String name, String relativePath, String statusText, boolean directory) {
            this.name = name;
            this.relativePath = relativePath;
            this.statusText = statusText;
            this.directory = directory;
            this.selected = false;
            this.indeterminate = false;
        }
    }

    private static class VersionResult {
        private final boolean isGitRepo;
        private final Map<String, String> changes;
        private final String message;

        private VersionResult(boolean isGitRepo, Map<String, String> changes, String message) {
            this.isGitRepo = isGitRepo;
            this.changes = changes;
            this.message = message;
        }
    }

    private static class CommandResult {
        private final boolean success;
        private final int exitCode;
        private final String output;

        private CommandResult(boolean success, int exitCode, String output) {
            this.success = success;
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static class AddDirectoryDialog {
        private String directoryName;
        private File directoryPath;
    }

    private static class MainLaunch {
        private final String className;
        private final File sourceFile;

        private MainLaunch(String className, File sourceFile) {
            this.className = className;
            this.sourceFile = sourceFile;
        }

        @Override
        public String toString() {
            return simpleClassName(className);
        }
    }

    private static class RunConfiguration {
        private final String type;
        private final String target;
        private final File sourceFile;

        private RunConfiguration(String type, String target, File sourceFile) {
            this.type = type;
            this.target = target;
            this.sourceFile = sourceFile;
        }

        @Override
        public String toString() {
            return "JAVA".equals(type) ? simpleClassName(target) : "npm: " + target;
        }
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) return "";
        int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        return separator >= 0 ? className.substring(separator + 1) : className;
    }

    private static final class HighlightedLogPane extends BorderPane {
        private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*m");
        private final org.fxmisc.richtext.InlineCssTextArea area =
                new org.fxmisc.richtext.InlineCssTextArea();

        private HighlightedLogPane() {
            area.setEditable(false);
            area.setWrapText(false);
            area.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; "
                    + "-fx-font-size: 12px; -fx-background-color: #fbfbfc;");
            setCenter(new org.fxmisc.flowless.VirtualizedScrollPane<>(area));
        }

        private void appendText(String text) {
            if (text == null || text.isEmpty()) return;
            String cleanText = ANSI_ESCAPE.matcher(text).replaceAll("");
            String[] lines = cleanText.split("(?<=\\n)", -1);
            for (String line : lines) {
                if (line.isEmpty()) continue;
                int start = area.getLength();
                area.appendText(line);
                area.setStyle(start, area.getLength(), styleFor(line));
            }
            area.moveTo(area.getLength());
            area.requestFollowCaret();
        }

        private String styleFor(String line) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.contains("[ERROR]") || upper.contains("EXCEPTION")
                    || upper.contains("CAUSED BY:") || line.contains("失败")) {
                return "-fx-fill: #c62828; -fx-font-weight: bold;";
            }
            if (upper.contains("[WARNING]") || upper.contains("[WARN]")
                    || upper.startsWith("WARNING:") || line.contains("警告")) {
                return "-fx-fill: #d97706;";
            }
            if (upper.contains("BUILD SUCCESS") || line.contains("启动成功")
                    || line.contains("编译成功") || line.contains("运行中")) {
                return "-fx-fill: #15803d; -fx-font-weight: bold;";
            }
            if (upper.contains("命令：") || upper.startsWith("CMD.EXE ")
                    || upper.contains("JAVA.EXE ") || upper.contains("MVN.CMD ")
                    || upper.contains("MVNW.CMD ") || upper.contains("GRADLE.BAT ")) {
                return "-fx-fill: #0f766e; -fx-font-weight: bold;";
            }
            if (upper.startsWith("[MAVEN]") || upper.startsWith("[GRADLE]")
                    || upper.startsWith("[JAVA]") || upper.startsWith("[NPM]")) {
                return "-fx-fill: #2563eb; -fx-font-weight: bold;";
            }
            if (upper.contains(" DEBUG ") || upper.startsWith("[DEBUG]")) {
                return "-fx-fill: #94a3b8;";
            }
            return "-fx-fill: #374151;";
        }

        private void clear() {
            area.clear();
        }

        private void setText(String text) {
            area.clear();
            appendText(text);
        }

        private int getLength() {
            return area.getLength();
        }

        private void positionCaret(int position) {
            area.moveTo(Math.max(0, Math.min(position, area.getLength())));
            area.requestFollowCaret();
        }

        private void setEditable(boolean editable) {
            area.setEditable(editable);
        }

        private void setWrapText(boolean wrapText) {
            area.setWrapText(wrapText);
        }
    }

    private final ObservableList<DevDirectory> directoryList = FXCollections.observableArrayList();
    private DevelopmentConfigManager.ConfigData developmentConfig = new DevelopmentConfigManager.ConfigData();

    private final VBox directoryListBox = new VBox();
    private final TreeView<File> fileTree = new TreeView<>();
    private final TreeView<ChangeItem> versionTree = new TreeView<>();
    private final Label directoryPlaceholderLabel = new Label("暂无目录，右键点击添加");
    private final Label filePlaceholderLabel = new Label("请先从中间栏选择目录");
    private final Label versionPlaceholderLabel = new Label("暂未选择目录");
    private final StackPane directoryTreeContainer = new StackPane();
    private final StackPane fileTreeContainer = new StackPane();
    private final StackPane versionTreeContainer = new StackPane();
    private final Label directoryPathLabel = new Label("暂未选择目录");

    private final Label versionHintLabel = new Label("请先在左侧选择目录");
    private final Button commitButton = new Button("提交选中文件");
    private final ToggleButton gitModeBtn = new ToggleButton("git仓库");
    private final ToggleButton realModeBtn = new ToggleButton("实时");
    private final SqlEditorPane codeEditor = new SqlEditorPane(true, false);
    private final Label editorFileLabel = new Label("请选择目录中的文件");
    private final Button saveEditorButton = new Button("保存");
    private final TreeView<String> dependencyTree = new TreeView<>();
    private final Label dependencyStatusLabel = new Label("请选择 Maven 项目");
    private final HighlightedLogPane runOutputArea = new HighlightedLogPane();
    private static final int MAX_PROJECT_LOG_CHARS = 2_000_000;
    private final Map<String, StringBuilder> projectLogs = new HashMap<>();
    private final ThreadLocal<DevDirectory> projectLogContext = new ThreadLocal<>();
    private final Button stopRunButton = new Button("停止");
    private final Button rerunButton = new Button("重新运行");
    private final MenuButton profilesMenuButton = new MenuButton("Profiles");
    private final ComboBox<RunConfiguration> runConfigurationCombo = new ComboBox<>();
    private final Button startProjectButton = new Button();
    private final Button stopProjectButton = new Button("停止");
    private final ListView<String> npmScriptsList = new ListView<>();
    private final Map<String, String> npmScriptCommands = new LinkedHashMap<>();
    private TabPane navigationTabPane;
    private Tab dependencyTab;
    private Tab runTab;

    private VersionMode currentMode = VersionMode.GIT;
    private DevDirectory currentDirectory;
    private boolean currentDirectoryGitRepo = false;
    private TreeItem<ChangeItem> versionRoot;
    private File currentEditingFile;
    private boolean editorLoading;
    private boolean editorDirty;
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private DevDirectory lastRunProject;
    private File lastRunSourceFile;
    private RunConfiguration lastRunConfiguration;
    private final Map<String, Process> buildProcesses = new ConcurrentHashMap<>();
    private final Set<String> launchingProjects = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelledProjects = ConcurrentHashMap.newKeySet();
    private boolean projectControlsLoading;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "development-versions-watch");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> realtimeTask;
    private Map<String, FileSnapshot> realtimeBaseSnapshot = Map.of();

    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "development-async-exec");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService buildExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "development-build-exec");
        t.setDaemon(true);
        return t;
    });

    public DevelopmentManagerPane() {
        loadDirectoryConfigs();
        initializeUI();
        bindEvents();
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroyManagedProcessesNow,
                "development-process-cleanup"));
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setPadding(Insets.EMPTY);

        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.38);

        splitPane.getItems().addAll(createNavigationTabs(), createEditorPanel());
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        getChildren().add(splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
    }

    private Region createNavigationTabs() {
        navigationTabPane = new TabPane();
        navigationTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab directoryTab = new Tab("目录", createFilesPanel());
        Tab versionTab = new Tab("版本", createVersionPanel());
        dependencyTab = new Tab("依赖", createDependencyPanel());
        runTab = new Tab("日志", createRunPanel());
        navigationTabPane.getTabs().addAll(directoryTab, versionTab, dependencyTab, runTab);
        navigationTabPane.setMinWidth(260);
        VBox wrapper = new VBox(createProjectControlBar(), navigationTabPane);
        VBox.setVgrow(navigationTabPane, Priority.ALWAYS);
        return wrapper;
    }

    private Region createProjectControlBar() {
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(7, 9, 7, 9));
        toolbar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e2e2e2; -fx-border-width: 0 0 1 0;");

        profilesMenuButton.setDisable(true);
        profilesMenuButton.setPrefWidth(140);
        runConfigurationCombo.setPromptText("选择运行项");
        runConfigurationCombo.setPrefWidth(230);
        runConfigurationCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(runConfigurationCombo, Priority.ALWAYS);
        runConfigurationCombo.setOnAction(e -> saveSelectedRunConfiguration());

        startProjectButton.setDisable(true);
        javafx.scene.image.ImageView runIcon = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(getClass().getResourceAsStream("/images/connect/execute.png")));
        runIcon.setFitWidth(16);
        runIcon.setFitHeight(16);
        startProjectButton.setGraphic(runIcon);
        startProjectButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        startProjectButton.setTooltip(new Tooltip("启动"));
        startProjectButton.getStyleClass().add("toolbar-button");
        startProjectButton.setOnAction(e -> startSelectedRunConfiguration());
        stopProjectButton.setDisable(true);
        stopProjectButton.setOnAction(e -> stopRunningProcess());
        toolbar.getChildren().addAll(profilesMenuButton, runConfigurationCombo,
                startProjectButton, stopProjectButton);
        return toolbar;
    }

    private Region createNpmScriptsPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));
        Label hint = new Label("双击 npm script 启动");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        npmScriptsList.setStyle("-fx-background-color: transparent;");
        npmScriptsList.setMinHeight(140);
        npmScriptsList.setPlaceholder(new Label("package.json 中没有可运行的 scripts"));
        npmScriptsList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String script, boolean empty) {
                super.updateItem(script, empty);
                if (empty || script == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(script);
                name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #263238;");
                Label command = new Label(npmScriptCommands.getOrDefault(script, ""));
                command.setStyle("-fx-font-size: 11px; -fx-text-fill: #78909c;");
                command.setWrapText(false);
                setText(null);
                setGraphic(new VBox(3, name, command));
                setPrefHeight(48);
            }
        });
        npmScriptsList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                String script = npmScriptsList.getSelectionModel().getSelectedItem();
                if (script != null && currentDirectory != null) {
                    runConfigurationCombo.getItems().stream()
                            .filter(item -> "NPM".equals(item.type) && script.equals(item.target))
                            .findFirst().ifPresent(runConfigurationCombo.getSelectionModel()::select);
                    runNpmScript(currentDirectory, script);
                }
            }
        });
        panel.getChildren().addAll(hint, npmScriptsList);
        VBox.setVgrow(npmScriptsList, Priority.ALWAYS);
        return panel;
    }

    private Region createDependencyPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));
        Button reloadButton = new Button("重新加载");
        reloadButton.setOnAction(e -> refreshDependenciesAsync());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        dependencyStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        HBox toolbar = new HBox(8, dependencyStatusLabel, spacer, reloadButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        dependencyTree.setShowRoot(true);
        dependencyTree.setFixedCellSize(35);
        dependencyTree.setStyle("-fx-background-color: transparent; -fx-cell-size: 35px;");
        dependencyTree.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        dependencyTree.setRoot(new TreeItem<>("暂无依赖"));
        panel.getChildren().addAll(toolbar, dependencyTree);
        VBox.setVgrow(dependencyTree, Priority.ALWAYS);
        return panel;
    }

    private Region createRunPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));
        Label title = new Label("项目日志");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        rerunButton.setDisable(true);
        rerunButton.setOnAction(e -> {
            if (lastRunProject != null && lastRunConfiguration != null
                    && "NPM".equals(lastRunConfiguration.type)) {
                runNpmScript(lastRunProject, lastRunConfiguration.target);
            } else if (lastRunProject != null && lastRunSourceFile != null) {
                runMainClass(lastRunProject, lastRunSourceFile);
            }
        });
        stopRunButton.setDisable(true);
        stopRunButton.setOnAction(e -> stopRunningProcess());
        HBox toolbar = new HBox(8, title, spacer, rerunButton, stopRunButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        runOutputArea.setEditable(false);
        runOutputArea.setWrapText(false);
        runOutputArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        panel.getChildren().addAll(toolbar, runOutputArea);
        VBox.setVgrow(runOutputArea, Priority.ALWAYS);
        return panel;
    }

    private Region createEditorPanel() {
        VBox panel = new VBox();
        panel.setStyle("-fx-background-color: #ffffff;");

        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(9, 12, 9, 12));
        toolbar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");

        Label title = new Label("代码编辑器");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        editorFileLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        saveEditorButton.setDisable(true);
        saveEditorButton.setStyle("-fx-background-color: #1976D2; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 5 14;");
        saveEditorButton.setOnAction(e -> saveCurrentFile());
        toolbar.getChildren().addAll(title, editorFileLabel, spacer, saveEditorButton);

        codeEditor.setEditable(false);
        codeEditor.setOnModified(text -> {
            if (!editorLoading && currentEditingFile != null) {
                editorDirty = true;
                updateEditorHeader();
            }
        });
        codeEditor.setOnSaveRequest(this::saveCurrentFile);

        panel.getChildren().addAll(toolbar, codeEditor);
        VBox.setVgrow(codeEditor, Priority.ALWAYS);
        return panel;
    }

    public Region createSidebarPane() {
        VBox panel = new VBox();
        panel.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e5e5; -fx-border-width: 0 1 0 0;");

        HBox titleBar = new HBox(8);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPrefHeight(52);
        titleBar.setMinHeight(52);
        titleBar.setMaxHeight(52);
        titleBar.setPadding(new Insets(10, 15, 10, 15));
        titleBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #D9D9D7; -fx-border-width: 0 0 1 0;");

        javafx.scene.shape.SVGPath titleIcon = new javafx.scene.shape.SVGPath();
        titleIcon.setContent("M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z");
        titleIcon.setFill(javafx.scene.paint.Color.web("#1976D2"));
        titleIcon.setScaleX(0.85);
        titleIcon.setScaleY(0.85);

        Label title = new Label("开发目录");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        titleBar.getChildren().addAll(titleIcon, title);

        configureDirectoryList();
        directoryPlaceholderLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 12px;");
        directoryPlaceholderLabel.setMouseTransparent(true);
        StackPane.setAlignment(directoryPlaceholderLabel, Pos.CENTER);

        ScrollPane scrollPane = new ScrollPane(directoryListBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-background-insets: 0;");
        scrollPane.getStyleClass().add("session-scroll-pane");
        scrollPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        directoryTreeContainer.getChildren().setAll(scrollPane, directoryPlaceholderLabel);
        VBox.setVgrow(directoryTreeContainer, Priority.ALWAYS);

        panel.getChildren().addAll(titleBar, directoryTreeContainer);

        return panel;
    }

    private void configureDirectoryList() {
        directoryListBox.setSpacing(0);
        directoryListBox.setPadding(Insets.EMPTY);
        directoryListBox.setStyle("-fx-background-color: #ffffff;");
        ContextMenu contextMenu = new ContextMenu();
        MenuItem addDir = new MenuItem("添加目录");
        addDir.setOnAction(e -> showAddDirectoryDialog());
        contextMenu.getItems().add(addDir);
        directoryListBox.setOnContextMenuRequested(e -> contextMenu.show(
                directoryListBox, e.getScreenX(), e.getScreenY()));
        directoryTreeContainer.setOnContextMenuRequested(e -> contextMenu.show(
                directoryTreeContainer, e.getScreenX(), e.getScreenY()));
        refreshDirectoryTree();
    }

    private VBox createDirectoryItemBox(DevDirectory directory) {
        VBox itemBox = new VBox();
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setCursor(javafx.scene.Cursor.HAND);

        VBox iconContainer = new VBox();
        iconContainer.setAlignment(Pos.CENTER);
        iconContainer.setPrefSize(40, 40);
        iconContainer.setMinSize(40, 40);
        iconContainer.setMaxSize(40, 40);
        iconContainer.setPadding(new Insets(6));
        iconContainer.setStyle("-fx-background-color: #e8f4ff; -fx-background-radius: 8;");

        javafx.scene.shape.SVGPath folderIcon = new javafx.scene.shape.SVGPath();
        folderIcon.setContent("M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z");
        folderIcon.setFill(javafx.scene.paint.Color.web("#1976D2"));
        folderIcon.setScaleX(0.9);
        folderIcon.setScaleY(0.9);
        iconContainer.getChildren().add(folderIcon);

        Label nameLabel = new Label(directory.getName());
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Label pathLabel = new Label(projectTypeLabel(directory.projectType) + " · "
                + directory.getPath().getAbsolutePath());
        pathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        pathLabel.setMaxWidth(180);
        pathLabel.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        pathLabel.setTooltip(new Tooltip(directory.getPath().getAbsolutePath()));
        VBox textBox = new VBox(2, nameLabel, pathLabel);

        Region rowSpacer = new Region();
        HBox.setHgrow(rowSpacer, Priority.ALWAYS);
        boolean running = isProjectRunning(directory);
        boolean launching = isProjectLaunching(directory);
        Label statusDot = new Label("●");
        Label statusText = new Label(running ? "运行中" : launching ? "启动中" : "未运行");
        String statusColor = running ? "#07c160" : launching ? "#f59e0b" : "#aaaaaa";
        statusDot.setStyle("-fx-text-fill: " + statusColor + "; -fx-font-size: 10px;");
        statusText.setStyle("-fx-text-fill: " + statusColor + "; -fx-font-size: 10px;");
        HBox statusBox = new HBox(3, statusDot, statusText);
        statusBox.setAlignment(Pos.CENTER_RIGHT);

        row.getChildren().addAll(iconContainer, textBox, rowSpacer, statusBox);
        HBox.setHgrow(textBox, Priority.ALWAYS);
        updateDirectoryRowStyle(row, directory == currentDirectory);

        Region separator = new Region();
        separator.setPrefHeight(1);
        separator.setStyle("-fx-background-color: #f0f0f0;");
        itemBox.getChildren().addAll(row, separator);

        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && directory.getPath().isDirectory()) {
                selectDirectory(directory);
            }
        });
        row.setOnMouseEntered(e -> {
            if (directory != currentDirectory) {
                row.setStyle("-fx-background-color: #f5f5f5; -fx-cursor: hand;");
            }
        });
        row.setOnMouseExited(e -> updateDirectoryRowStyle(row, directory == currentDirectory));
        ContextMenu projectMenu = new ContextMenu();
        MenuItem configureItem = new MenuItem("配置");
        configureItem.setOnAction(e -> showProjectSettings(directory));
        projectMenu.getItems().add(configureItem);
        row.setOnContextMenuRequested(e -> {
            projectMenu.show(row, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return itemBox;
    }

    private void updateDirectoryRowStyle(HBox row, boolean selected) {
        row.setStyle(selected
                ? "-fx-background-color: #e8f4ff; -fx-cursor: hand;"
                : "-fx-background-color: transparent; -fx-cursor: hand;");
    }

    private Region createFilesPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(12, 10, 12, 12));
        panel.setStyle("-fx-background-color: #ffffff;");

        Label title = new Label("目录");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        directoryPathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");
        directoryPathLabel.setWrapText(true);

        fileTree.setShowRoot(true);
        fileTree.setFixedCellSize(35);
        fileTree.setStyle("-fx-background-color: transparent; -fx-cell-size: 35px;");
        fileTree.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        fileTree.setCellFactory(tv -> new TreeCell<>() {
            private final javafx.scene.shape.SVGPath itemIcon = new javafx.scene.shape.SVGPath();

            {
                setOnContextMenuRequested(event -> {
                    File file = getItem();
                    if (file == null || !file.isFile() || !file.getName().endsWith(".java")
                            || !containsMainMethod(file)) {
                        return;
                    }
                    MenuItem runItem = new MenuItem("运行 " + file.getName().replaceFirst("\\.java$", "") + ".main()");
                    runItem.setOnAction(e -> runMainClass(file));
                    new ContextMenu(runItem).show(this, event.getScreenX(), event.getScreenY());
                    event.consume();
                });
            }

            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                itemIcon.setContent(item.isDirectory()
                        ? "M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
                        : "M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm0 2.5L17.5 8H14V4.5z");
                itemIcon.setFill(javafx.scene.paint.Color.web(item.isDirectory() ? "#E0A800" : "#78909C"));
                itemIcon.setScaleX(0.68);
                itemIcon.setScaleY(0.68);
                setText(item.getName());
                setGraphic(itemIcon);
                setTooltip(new Tooltip(item.getAbsolutePath()));
            }
        });
        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null && newItem.getValue() != null && newItem.getValue().isFile()) {
                openCodeFile(newItem.getValue());
            }
        });

        filePlaceholderLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 12px;");
        filePlaceholderLabel.setMouseTransparent(true);
        StackPane.setAlignment(filePlaceholderLabel, Pos.CENTER);
        fileTreeContainer.getChildren().setAll(fileTree, filePlaceholderLabel);

        panel.getChildren().addAll(title, directoryPathLabel, fileTreeContainer);
        VBox.setVgrow(fileTreeContainer, Priority.ALWAYS);
        return panel;
    }

    private TreeItem<File> createFileItem(File file) {
        TreeItem<File> item = new TreeItem<>(file);
        if (file.isDirectory()) {
            item.getChildren().add(new TreeItem<>());
            item.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                if (isExpanded && item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null) {
                    loadFileChildren(item);
                }
            });
        }
        return item;
    }

    private void loadFileChildren(TreeItem<File> parent) {
        File[] children = parent.getValue().listFiles();
        parent.getChildren().clear();
        if (children == null) {
            return;
        }
        Arrays.sort(children, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) {
                return left.isDirectory() ? -1 : 1;
            }
            return left.getName().compareToIgnoreCase(right.getName());
        });
        for (File child : children) {
            parent.getChildren().add(createFileItem(child));
        }
    }

    private void refreshFileTree() {
        if (currentDirectory == null) {
            fileTree.setRoot(null);
            filePlaceholderLabel.setVisible(true);
            filePlaceholderLabel.setManaged(true);
            directoryPathLabel.setText("暂未选择目录");
            return;
        }
        TreeItem<File> root = createFileItem(currentDirectory.getPath());
        loadFileChildren(root);
        root.setExpanded(true);
        fileTree.setRoot(root);
        directoryPathLabel.setText(currentDirectory.getPath().getAbsolutePath());
        filePlaceholderLabel.setVisible(false);
        filePlaceholderLabel.setManaged(false);
    }

    private void openCodeFile(File file) {
        try {
            if (Files.size(file.toPath()) > 5 * 1024 * 1024) {
                showEditorError("文件超过 5 MB，暂不在编辑器中打开");
                return;
            }
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            currentEditingFile = file;
            editorLoading = true;
            codeEditor.setText(content);
            editorLoading = false;
            editorDirty = false;
            codeEditor.setEditable(true);
            updateEditorHeader();
        } catch (Exception e) {
            editorLoading = false;
            showEditorError("无法打开文件：" + e.getMessage());
        }
    }

    private void saveCurrentFile() {
        if (currentEditingFile == null) {
            return;
        }
        try {
            Files.writeString(currentEditingFile.toPath(), codeEditor.getText(), StandardCharsets.UTF_8);
            editorDirty = false;
            updateEditorHeader();
            refreshVersionListAsync();
        } catch (Exception e) {
            showEditorError("保存文件失败：" + e.getMessage());
        }
    }

    private void clearEditor() {
        currentEditingFile = null;
        editorLoading = true;
        codeEditor.clear();
        editorLoading = false;
        editorDirty = false;
        codeEditor.setEditable(false);
        updateEditorHeader();
    }

    private void updateEditorHeader() {
        if (currentEditingFile == null) {
            editorFileLabel.setText("请选择目录中的文件");
            saveEditorButton.setDisable(true);
            return;
        }
        editorFileLabel.setText(currentEditingFile.getName() + (editorDirty ? " *" : ""));
        editorFileLabel.setTooltip(new Tooltip(currentEditingFile.getAbsolutePath()));
        saveEditorButton.setDisable(!editorDirty);
    }

    private void showEditorError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        DialogPositionUtil.centerOnOwner(alert, this);
        alert.showAndWait();
    }

    private Region createVersionPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(12, 12, 12, 8));
        panel.setStyle("-fx-background-color: #fefefe;");

        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("版本");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        ToggleGroup modeGroup = new ToggleGroup();
        gitModeBtn.setToggleGroup(modeGroup);
        realModeBtn.setToggleGroup(modeGroup);
        gitModeBtn.setSelected(true);

        HBox modeBox = new HBox(4, gitModeBtn, realModeBtn);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        commitButton.setStyle("-fx-background-color: #07c160; -fx-text-fill: #fff; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12px;");
        commitButton.setDisable(true);
        commitButton.setOnAction(e -> commitSelectedFiles());
        commitButton.setCursor(javafx.scene.Cursor.HAND);

        versionHintLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
        versionHintLabel.setTextAlignment(TextAlignment.LEFT);

        titleBar.getChildren().addAll(title, modeBox, spacer, commitButton);

        versionRoot = new TreeItem<>(new ChangeItem("变更文件", "", "root", true));
        versionRoot.setExpanded(true);
        versionTree.setRoot(versionRoot);
        versionTree.setShowRoot(false);
        versionTree.setPrefHeight(650);
        versionTree.setFixedCellSize(35);
        versionTree.setStyle("-fx-background-color: transparent; -fx-cell-size: 35px;");
        versionTree.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        versionPlaceholderLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 12px;");
        versionPlaceholderLabel.setMouseTransparent(true);
        StackPane.setAlignment(versionPlaceholderLabel, Pos.CENTER);
        versionTreeContainer.getChildren().setAll(versionTree, versionPlaceholderLabel);

        versionTree.setCellFactory(tv -> new TreeCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final Label iconLabel = new Label();
            private final Label textLabel = new Label();
            private final HBox box = new HBox(6, checkBox, iconLabel, textLabel);

            @Override
            protected void updateItem(ChangeItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                boolean canChoose = currentMode == VersionMode.GIT && currentDirectoryGitRepo;
                checkBox.setOnAction(null);
                checkBox.setVisible(canChoose);
                checkBox.setManaged(canChoose);
                checkBox.setDisable(!canChoose);
                checkBox.setAllowIndeterminate(false);
                checkBox.setSelected(item.selected);
                checkBox.setIndeterminate(item.indeterminate);
                checkBox.setOnAction(ev -> {
                    TreeItem<ChangeItem> treeItem = getTreeItem();
                    if (treeItem == null) return;
                    setVersionNodeSelected(treeItem, checkBox.isSelected());
                    updateVersionParentSelection(treeItem.getParent());
                    versionTree.refresh();
                });
                iconLabel.setText(item.directory ? "📁" : "📄");
                textLabel.setText(item.name + (item.directory || item.statusText == null ? "" : "  [" + item.statusText + "]"));
                setGraphic(box);
                setText(null);
            }
        });
        panel.getChildren().addAll(titleBar, versionHintLabel, versionTreeContainer);
        VBox.setVgrow(versionTreeContainer, Priority.ALWAYS);
        VBox.setVgrow(panel, Priority.ALWAYS);

        return panel;
    }

    private void setVersionNodeSelected(TreeItem<ChangeItem> node, boolean selected) {
        ChangeItem item = node.getValue();
        if (item != null) {
            item.selected = selected;
            item.indeterminate = false;
        }
        for (TreeItem<ChangeItem> child : node.getChildren()) {
            setVersionNodeSelected(child, selected);
        }
    }

    private void updateVersionParentSelection(TreeItem<ChangeItem> parent) {
        while (parent != null) {
            ChangeItem item = parent.getValue();
            if (item != null && !parent.getChildren().isEmpty()) {
                boolean allSelected = true;
                boolean anySelected = false;
                for (TreeItem<ChangeItem> child : parent.getChildren()) {
                    ChangeItem childItem = child.getValue();
                    if (childItem == null) continue;
                    allSelected &= childItem.selected && !childItem.indeterminate;
                    anySelected |= childItem.selected || childItem.indeterminate;
                }
                item.selected = allSelected;
                item.indeterminate = anySelected && !allSelected;
            }
            parent = parent.getParent();
        }
    }

    private void bindEvents() {
        gitModeBtn.setOnAction(e -> {
            currentMode = VersionMode.GIT;
            stopRealtimeMonitor();
            refreshVersionListAsync();
        });
        realModeBtn.setOnAction(e -> {
            currentMode = VersionMode.REALTIME;
            startRealtimeMonitor();
            refreshVersionListAsync();
        });

        commitButton.setTooltip(new Tooltip("提交已选中的文件"));
        realModeBtn.selectedProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue) {
                currentMode = VersionMode.REALTIME;
                startRealtimeMonitor();
            }
        });
    }

    private void selectDirectory(DevDirectory dir) {
        currentDirectory = dir;
        showProjectLog(dir);
        clearEditor();
        refreshFileTree();
        versionHintLabel.setText("已选择目录：" + dir.getName() + "（" + dir.getPath().getAbsolutePath() + "）");
        if (currentMode == VersionMode.REALTIME) {
            startRealtimeMonitor();
        }
        refreshVersionListAsync();
        refreshProjectControlsAsync();
        refreshDependenciesAsync();
        refreshDirectoryTree();
    }

    private void refreshDirectoryTree() {
        directoryListBox.getChildren().clear();
        for (DevDirectory dir : directoryList) {
            directoryListBox.getChildren().add(createDirectoryItemBox(dir));
        }
        updateDirectoryPlaceholder();
    }

    private void updateDirectoryPlaceholder() {
        boolean hasData = !directoryList.isEmpty();
        directoryPlaceholderLabel.setVisible(!hasData);
        directoryPlaceholderLabel.setManaged(!hasData);
    }

    private void loadDirectoryConfigs() {
        developmentConfig = DevelopmentConfigManager.load();
        for (DevelopmentConfigManager.Entry entry : developmentConfig.getProjects()) {
            if (entry.getName() != null && !entry.getName().isBlank()
                    && entry.getPath() != null && !entry.getPath().isBlank()) {
                File path = new File(entry.getPath());
                String projectType = entry.getProjectType();
                if (projectType == null || projectType.isBlank()) {
                    projectType = detectProjectType(path);
                }
                directoryList.add(new DevDirectory(entry.getName(), path, projectType,
                        entry.getJdkId(), entry.getNodeId(), entry.getFlutterId(), entry.getMavenId(),
                        entry.getMainClass(), entry.getMainSourcePath(), entry.getActiveProfiles(),
                        entry.getRunType(), entry.getRunTarget()));
            }
        }
    }

    private void saveDirectoryConfigs() throws java.io.IOException {
        List<DevelopmentConfigManager.Entry> entries = directoryList.stream()
                .map(directory -> new DevelopmentConfigManager.Entry(
                        directory.getName(), directory.getPath().getAbsolutePath(),
                        directory.projectType, directory.jdkId, directory.nodeId,
                        directory.flutterId, directory.mavenId,
                        directory.mainClass, directory.mainSourcePath,
                        directory.activeProfiles, directory.runType, directory.runTarget))
                .toList();
        developmentConfig = new DevelopmentConfigManager.ConfigData(
                entries, developmentConfig.getRuntimes(), developmentConfig.getBuildTools());
        DevelopmentConfigManager.save(developmentConfig);
    }

    private void showProjectSettings(DevDirectory directory) {
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        DevelopmentProjectSettingsDialog.show(owner, directory.getName(), directory.projectType,
                directory.jdkId, directory.nodeId, directory.flutterId,
                directory.mavenId, developmentConfig).ifPresent(settings -> {
            developmentConfig.getRuntimes().clear();
            developmentConfig.getRuntimes().addAll(settings.runtimes());
            developmentConfig.getBuildTools().clear();
            developmentConfig.getBuildTools().addAll(settings.buildTools());
            directory.jdkId = settings.jdkId();
            directory.nodeId = settings.nodeId();
            directory.flutterId = settings.flutterId();
            directory.mavenId = settings.mavenId();
            try {
                saveDirectoryConfigs();
                refreshDirectoryTree();
                if (currentDirectory == directory) {
                    refreshProjectControlsAsync();
                    refreshDependenciesAsync();
                }
            } catch (Exception e) {
                Alert alert = new Alert(Alert.AlertType.ERROR,
                        "保存项目配置失败：" + e.getMessage(), ButtonType.OK);
                DialogPositionUtil.centerOnOwner(alert, this);
                alert.showAndWait();
            }
        });
    }

    private String detectProjectType(File directory) {
        boolean maven = new File(directory, "pom.xml").isFile()
                || new File(directory, "mvnw").isFile()
                || new File(directory, "mvnw.cmd").isFile();
        boolean gradle = new File(directory, "build.gradle").isFile()
                || new File(directory, "build.gradle.kts").isFile()
                || new File(directory, "gradlew").isFile()
                || new File(directory, "gradlew.bat").isFile();
        boolean node = new File(directory, "package.json").isFile();
        boolean flutter = new File(directory, "pubspec.yaml").isFile();
        if (flutter) return "FLUTTER";
        if (maven && node) return "MAVEN_NODE";
        if (maven) return "MAVEN";
        if (gradle && node) return "GRADLE_NODE";
        if (gradle) return "GRADLE";
        if (node) return "NODE";
        return "GENERAL";
    }

    private String projectTypeLabel(String projectType) {
        return switch (projectType == null ? "GENERAL" : projectType) {
            case "MAVEN" -> "Maven";
            case "NODE" -> "Node";
            case "MAVEN_NODE" -> "Maven + Node";
            case "GRADLE" -> "Gradle";
            case "GRADLE_NODE" -> "Gradle + Node";
            case "FLUTTER" -> "Flutter";
            default -> "普通目录";
        };
    }

    private void showAddDirectoryDialog() {
        Stage dlg = new Stage();
        dlg.initModality(Modality.WINDOW_MODAL);
        if (this.getScene() != null) dlg.initOwner((Stage) this.getScene().getWindow());
        dlg.setTitle("添加目录");
        dlg.setResizable(false);

        VBox root = new VBox(14);
        root.setPadding(new Insets(18));
        root.setMinWidth(460);

        Label title = new Label("添加开发目录");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        AddDirectoryDialog value = new AddDirectoryDialog();

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(12);

        form.add(new Label("目录名称："), 0, 0);
        TextField nameField = new TextField();
        nameField.setPromptText("如：backend");
        nameField.setPrefWidth(320);
        form.add(nameField, 1, 0);

        form.add(new Label("目录路径："), 0, 1);
        TextField pathField = new TextField();
        pathField.setPromptText("选择一个本地目录路径");
        pathField.setPrefWidth(280);
        Label detectedTypeLabel = new Label("普通目录");
        detectedTypeLabel.setStyle("-fx-text-fill: #1976D2; -fx-font-weight: bold;");
        Button pickBtn = new Button("浏览");
        pickBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        pickBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择目录");
            String current = pathField.getText();
            if (current != null && !current.isBlank()) {
                File currentDir = new File(current);
                if (currentDir.exists() && currentDir.isDirectory()) {
                    chooser.setInitialDirectory(currentDir);
                }
            }
            File selected = chooser.showDialog(dlg);
            if (selected != null) {
                pathField.setText(selected.getAbsolutePath());
                detectedTypeLabel.setText(projectTypeLabel(detectProjectType(selected)));
            }
        });
        HBox pathBox = new HBox(6, pathField, pickBtn);
        form.add(pathBox, 1, 1);
        form.add(new Label("项目类型："), 0, 2);
        form.add(detectedTypeLabel, 1, 2);

        Label tip = new Label("提示：目录仅记录路径信息，默认不做任何自动发布动作。");
        tip.setStyle("-fx-text-fill: #999; -fx-font-size: 11px;");
        tip.setWrapText(true);

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_RIGHT);
        Button cancel = new Button("取消");
        cancel.setOnAction(e -> dlg.close());
        Button confirm = new Button("确定");
        confirm.setStyle("-fx-background-color: #07c160; -fx-text-fill: #fff; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-weight: bold;");
        confirm.setOnAction(e -> {
            String dirName = nameField.getText().trim();
            String dirPath = pathField.getText().trim();
            if (dirName.isBlank() || dirPath.isBlank()) {
                Alert a = new Alert(Alert.AlertType.WARNING, "目录名称和路径不能为空", ButtonType.OK);
                DialogPositionUtil.centerOnOwner(a, this);
                a.showAndWait();
                return;
            }
            File file = new File(dirPath);
            if (!file.exists() || !file.isDirectory()) {
                Alert a = new Alert(Alert.AlertType.WARNING, "请选择一个有效的本地目录", ButtonType.OK);
                DialogPositionUtil.centerOnOwner(a, this);
                a.showAndWait();
                return;
            }
            boolean duplicated = directoryList.stream().anyMatch(d -> d.getName().equalsIgnoreCase(dirName));
            if (duplicated) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION, "目录名称已存在，仍要添加吗？", ButtonType.YES, ButtonType.NO);
                a.setHeaderText(null);
                DialogPositionUtil.centerOnOwner(a, this);
                if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
            }
            value.directoryName = dirName;
            value.directoryPath = file;
            String detectedProjectType = detectProjectType(file);
            DevDirectory newDirectory = new DevDirectory(value.directoryName, value.directoryPath,
                    detectedProjectType, null, null, null, null, null, null,
                    List.of(), null, null);
            directoryList.add(newDirectory);
            try {
                saveDirectoryConfigs();
            } catch (Exception saveError) {
                directoryList.remove(newDirectory);
                Alert a = new Alert(Alert.AlertType.ERROR,
                        "保存开发目录配置失败：" + saveError.getMessage(), ButtonType.OK);
                DialogPositionUtil.centerOnOwner(a, this);
                a.showAndWait();
                return;
            }
            refreshDirectoryTree();
            if (currentDirectory == null) {
                selectDirectory(newDirectory);
            }
            dlg.close();
        });
        btns.getChildren().addAll(cancel, confirm);

        root.getChildren().addAll(title, form, tip, btns);
        dlg.setScene(new Scene(root));
        DialogPositionUtil.centerOnOwner(dlg, this);
        dlg.showAndWait();
    }

    private void refreshProjectControlsAsync() {
        DevDirectory project = currentDirectory;
        projectControlsLoading = true;
        runConfigurationCombo.getItems().clear();
        runConfigurationCombo.setValue(null);
        npmScriptsList.getItems().clear();
        npmScriptCommands.clear();
        profilesMenuButton.getItems().clear();
        profilesMenuButton.setText("Spring Profiles");
        profilesMenuButton.setDisable(true);
        startProjectButton.setDisable(true);
        projectControlsLoading = false;
        if (project == null) return;

        Map<String, String> npmScripts = readNpmScripts(project.getPath());
        if (!isJavaBuildProject(project)) {
            applyProjectControls(project, List.of(), npmScripts, List.of());
            return;
        }

        asyncExecutor.execute(() -> {
            List<MainLaunch> mainClasses = findMainClasses(project);
            List<String> profiles = loadSpringProfiles(project);
            Platform.runLater(() -> {
                if (currentDirectory != project) return;
                applyProjectControls(project, mainClasses, npmScripts, profiles);
            });
        });
    }

    private void applyProjectControls(DevDirectory project,
                                      List<MainLaunch> mainClasses,
                                      Map<String, String> npmScripts,
                                      List<String> profiles) {
        projectControlsLoading = true;
        List<RunConfiguration> configurations = new ArrayList<>();
        for (MainLaunch main : mainClasses) {
            configurations.add(new RunConfiguration("JAVA", main.className, main.sourceFile));
        }
        for (String script : npmScripts.keySet()) {
            configurations.add(new RunConfiguration("NPM", script, null));
        }
        runConfigurationCombo.getItems().setAll(configurations);
        configurations.stream()
                .filter(item -> Objects.equals(item.type, project.runType)
                        && Objects.equals(item.target, project.runTarget))
                .findFirst().ifPresent(runConfigurationCombo.getSelectionModel()::select);
        if (runConfigurationCombo.getValue() == null && configurations.size() == 1) {
            runConfigurationCombo.getSelectionModel().selectFirst();
        }

        npmScriptCommands.clear();
        npmScriptCommands.putAll(npmScripts);
        npmScriptsList.getItems().setAll(npmScripts.keySet());
        npmScriptsList.refresh();
        dependencyTab.setDisable(!isJavaBuildProject(project));

        boolean hasJava = !mainClasses.isEmpty();
        boolean hasNpm = !npmScripts.isEmpty();
        if (hasJava && hasNpm) {
            runConfigurationCombo.setPromptText("选择 Java main 或 npm script");
        } else if (hasNpm) {
            runConfigurationCombo.setPromptText("选择 npm script");
        } else if (hasJava) {
            runConfigurationCombo.setPromptText("选择 Java main");
        } else {
            runConfigurationCombo.setPromptText("当前项目没有可运行项");
        }

        profilesMenuButton.getItems().clear();
        for (String profile : profiles) {
            CheckMenuItem item = new CheckMenuItem(profile);
            item.setSelected(project.activeProfiles.contains(profile));
            item.setOnAction(e -> {
                if (item.isSelected()) {
                    if (!project.activeProfiles.contains(profile)) project.activeProfiles.add(profile);
                } else {
                    project.activeProfiles.remove(profile);
                }
                updateProfilesButtonText(project);
                persistProjectRunSettings();
                refreshDependenciesAsync();
            });
            profilesMenuButton.getItems().add(item);
        }
        profilesMenuButton.setDisable(profiles.isEmpty());
        updateProfilesButtonText(project);
        projectControlsLoading = false;
        updateRunControlState();
    }

    private Map<String, String> readNpmScripts(File projectRoot) {
        Map<String, String> scripts = new LinkedHashMap<>();
        Path packageFile = projectRoot.toPath().resolve("package.json");
        if (!Files.isRegularFile(packageFile)) return scripts;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(packageFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject scriptsObject = root.has("scripts") && root.get("scripts").isJsonObject()
                    ? root.getAsJsonObject("scripts") : null;
            if (scriptsObject != null) {
                for (Map.Entry<String, JsonElement> entry : scriptsObject.entrySet()) {
                    scripts.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            appendRunOutput("读取 package.json scripts 失败：" + e.getMessage() + System.lineSeparator());
        }
        return scripts;
    }

    private List<String> loadSpringProfiles(DevDirectory project) {
        Set<String> profiles = new TreeSet<>();
        Pattern filePattern = Pattern.compile("application-(.+)\\.(?:yml|yaml|properties)",
                Pattern.CASE_INSENSITIVE);
        Pattern configPattern = Pattern.compile(
                "(?m)^\\s*(?:spring\\.profiles\\.(?:active|include)|spring\\.config\\.activate\\.on-profile|on-profile)"
                        + "\\s*[:=]\\s*([^#\\r\\n]+)");
        try (var paths = Files.walk(project.getPath().toPath())) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String normalized = path.toString().replace('\\', '/');
                        return normalized.contains("/src/main/resources/")
                                || normalized.contains("/src/test/resources/");
                    })
                    .filter(path -> path.getFileName().toString().matches(
                            "(?i)application(?:-.+)?\\.(yml|yaml|properties)"))
                    .limit(200)
                    .forEach(path -> {
                        Matcher fileMatcher = filePattern.matcher(path.getFileName().toString());
                        if (fileMatcher.matches()) addProfileValues(fileMatcher.group(1), profiles);
                        try {
                            Matcher configMatcher = configPattern.matcher(
                                    Files.readString(path, StandardCharsets.UTF_8));
                            while (configMatcher.find()) addProfileValues(configMatcher.group(1), profiles);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
        return new ArrayList<>(profiles);
    }

    private void addProfileValues(String rawValue, Set<String> target) {
        if (rawValue == null || rawValue.contains("${")) return;
        for (String value : rawValue.replace("[", "").replace("]", "").split("[,|]")) {
            String profile = value.trim().replace("\"", "").replace("'", "");
            if (!profile.isBlank()) target.add(profile);
        }
    }

    private void updateProfilesButtonText(DevDirectory project) {
        profilesMenuButton.setText(project.activeProfiles.isEmpty()
                ? "Spring Profiles" : "Spring Profiles (" + project.activeProfiles.size() + ")");
    }

    private void saveSelectedRunConfiguration() {
        if (projectControlsLoading || currentDirectory == null) return;
        RunConfiguration selected = runConfigurationCombo.getValue();
        currentDirectory.runType = selected == null ? null : selected.type;
        currentDirectory.runTarget = selected == null ? null : selected.target;
        if (selected != null && "JAVA".equals(selected.type) && selected.sourceFile != null) {
            rememberMainClass(currentDirectory,
                    new MainLaunch(selected.target, selected.sourceFile));
        } else {
            persistProjectRunSettings();
        }
        updateRunControlState();
    }

    private void persistProjectRunSettings() {
        try {
            saveDirectoryConfigs();
        } catch (Exception e) {
            appendRunOutput("保存项目运行配置失败：" + e.getMessage() + System.lineSeparator());
        }
    }

    private void startSelectedRunConfiguration() {
        if (currentDirectory == null) return;
        RunConfiguration selected = runConfigurationCombo.getValue();
        if (selected == null) {
            showEditorError("请先选择 Java main 或 npm script");
            return;
        }
        if ("NPM".equals(selected.type)) {
            runNpmScript(currentDirectory, selected.target);
        } else if (selected.sourceFile != null) {
            runMainClass(currentDirectory, selected.sourceFile);
        }
    }

    private void updateRunControlState() {
        boolean busy = isProjectBusy(currentDirectory);
        startProjectButton.setDisable(currentDirectory == null
                || runConfigurationCombo.getValue() == null || busy);
        stopProjectButton.setDisable(!busy);
        stopRunButton.setDisable(!busy);
    }

    private void refreshDependenciesAsync() {
        DevDirectory project = currentDirectory;
        if (project == null || !isJavaBuildProject(project)) {
            dependencyStatusLabel.setText("当前项目不是 Maven/Gradle 项目");
            dependencyTree.setRoot(new TreeItem<>("暂无构建依赖"));
            return;
        }
        if (resolveBuildTool(project.mavenId) == null || resolveRuntime(project.jdkId, "JDK") == null) {
            dependencyStatusLabel.setText("请先在项目配置中选择 JDK 和构建工具");
            dependencyTree.setRoot(new TreeItem<>("尚未配置构建环境"));
            return;
        }
        dependencyStatusLabel.setText("正在加载构建依赖...");
        dependencyTree.setRoot(new TreeItem<>("加载中..."));
        buildExecutor.execute(() -> {
            CommandResult result = isMavenProject(project)
                    ? runMavenCommand(project, project.getPath(), List.of("dependency:tree"), 180)
                    : runGradleCommand(project, project.getPath(), List.of("dependencies", "--configuration", "runtimeClasspath"), 180);
            Platform.runLater(() -> {
                if (currentDirectory != project) return;
                applyDependencyOutput(project, result);
            });
        });
    }

    private void applyDependencyOutput(DevDirectory project, CommandResult result) {
        TreeItem<String> root = new TreeItem<>(project.getName());
        root.setExpanded(true);
        List<TreeItem<String>> stack = new ArrayList<>();
        stack.add(root);
        Pattern dependencyLine = isMavenProject(project)
                ? Pattern.compile("^\\[INFO\\] ((?:\\|  |   )*)(?:\\+-|\\\\-)\\s+(.+)$")
                : Pattern.compile("^((?:\\|    |     )*)(?:\\+---|\\\\---)\\s+(.+)$");
        for (String line : result.output.split("\\R")) {
            Matcher matcher = dependencyLine.matcher(line);
            if (!matcher.matches()) continue;
            int level = matcher.group(1).length() / 3 + 1;
            while (stack.size() > level) stack.remove(stack.size() - 1);
            TreeItem<String> parent = stack.get(Math.max(0, level - 1));
            TreeItem<String> item = new TreeItem<>(matcher.group(2).trim());
            parent.getChildren().add(item);
            if (stack.size() == level) stack.add(item);
            else stack.set(level, item);
        }
        dependencyTree.setRoot(root);
        if (result.success) {
            dependencyStatusLabel.setText(root.getChildren().isEmpty()
                    ? "依赖加载完成，当前项目没有外部依赖"
                    : "依赖加载完成");
        } else {
            dependencyStatusLabel.setText("依赖加载失败");
            root.getChildren().add(new TreeItem<>(lastMeaningfulLine(result.output)));
        }
    }

    private DevelopmentConfigManager.RuntimeEntry resolveRuntime(String id, String type) {
        if (id == null) return null;
        return developmentConfig.getRuntimes().stream()
                .filter(item -> id.equals(item.getId()) && type.equals(item.getType()))
                .findFirst().orElse(null);
    }

    private DevelopmentConfigManager.BuildToolEntry resolveBuildTool(String id) {
        if (id == null) return null;
        return developmentConfig.getBuildTools().stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst().orElse(null);
    }

    private boolean isMavenProject(DevDirectory project) {
        return project != null && ("MAVEN".equals(project.projectType)
                || "MAVEN_NODE".equals(project.projectType));
    }

    private boolean isGradleProject(DevDirectory project) {
        return project != null && ("GRADLE".equals(project.projectType)
                || "GRADLE_NODE".equals(project.projectType));
    }

    private boolean isJavaBuildProject(DevDirectory project) {
        return isMavenProject(project) || isGradleProject(project);
    }

    private boolean containsMainMethod(File file) {
        try {
            if (Files.size(file.toPath()) > 1024 * 1024) return false;
            String source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return Pattern.compile("\\bpublic\\s+static\\s+void\\s+main\\s*\\(").matcher(source).find();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void runProject(DevDirectory project) {
        if (currentDirectory != project) selectDirectory(project);
        if (project.mainSourcePath != null && !project.mainSourcePath.isBlank()) {
            File configuredSource = new File(project.getPath(), project.mainSourcePath);
            if (configuredSource.isFile() && containsMainMethod(configuredSource)) {
                runMainClass(project, configuredSource);
                return;
            }
        }
        navigationTabPane.getSelectionModel().select(runTab);
        clearRunOutput(project);
        appendRunOutput("正在查找项目 main 类..." + System.lineSeparator());
        buildExecutor.execute(projectLogTask(project, () -> {
            List<MainLaunch> mainClasses = findMainClasses(project);
            Platform.runLater(() -> chooseAndRunMainClass(project, mainClasses));
        }));
    }

    private List<MainLaunch> findMainClasses(DevDirectory project) {
        List<MainLaunch> result = new ArrayList<>();
        Path root = project.getPath().toPath().toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> {
                        String normalized = path.toString().replace('\\', '/');
                        return normalized.contains("/src/main/java/") || normalized.contains("/src/test/java/");
                    })
                    .filter(path -> containsMainMethod(path.toFile()))
                    .limit(100)
                    .forEach(path -> {
                        String className = resolveMainClassName(path.toFile());
                        if (className != null) result.add(new MainLaunch(className, path.toFile()));
                    });
        } catch (Exception e) {
            appendRunOutput("查找 main 类失败：" + e.getMessage() + System.lineSeparator());
        }
        result.sort(Comparator.comparing(item -> item.className));
        return result;
    }

    private void chooseAndRunMainClass(DevDirectory project, List<MainLaunch> mainClasses) {
        if (mainClasses.isEmpty()) {
            appendRunOutput("项目中未找到 public static void main(...)" + System.lineSeparator());
            return;
        }
        MainLaunch selected;
        if (mainClasses.size() == 1) {
            selected = mainClasses.get(0);
        } else {
            ChoiceDialog<MainLaunch> dialog = new ChoiceDialog<>(mainClasses.get(0), mainClasses);
            dialog.setTitle("选择启动类");
            dialog.setHeaderText("项目中找到多个 main 类");
            dialog.setContentText("启动类：");
            if (getScene() != null) dialog.initOwner(getScene().getWindow());
            Optional<MainLaunch> selection = dialog.showAndWait();
            if (selection.isEmpty()) return;
            selected = selection.get();
        }
        rememberMainClass(project, selected);
        runMainClass(project, selected.sourceFile);
    }

    private void rememberMainClass(DevDirectory project, MainLaunch launch) {
        project.mainClass = launch.className;
        project.runType = "JAVA";
        project.runTarget = launch.className;
        project.mainSourcePath = project.getPath().toPath().toAbsolutePath().normalize()
                .relativize(launch.sourceFile.toPath().toAbsolutePath().normalize())
                .toString();
        try {
            saveDirectoryConfigs();
        } catch (Exception e) {
            appendRunOutput("保存项目启动配置失败：" + e.getMessage() + System.lineSeparator());
        }
    }

    private void runMainClass(File sourceFile) {
        if (currentDirectory == null) return;
        String className = resolveMainClassName(sourceFile);
        if (className != null) rememberMainClass(currentDirectory, new MainLaunch(className, sourceFile));
        runMainClass(currentDirectory, sourceFile);
    }

    private void runMainClass(DevDirectory project, File sourceFile) {
        if (currentDirectory != project) selectDirectory(project);
        if (project == null || !isJavaBuildProject(project)) {
            showEditorError("运行 main 方法需要 Maven 或 Gradle 项目");
            return;
        }
        DevelopmentConfigManager.RuntimeEntry jdk = resolveRuntime(project.jdkId, "JDK");
        DevelopmentConfigManager.BuildToolEntry maven = resolveBuildTool(project.mavenId);
        if (jdk == null || maven == null) {
            showEditorError("请先右键项目打开“配置”，选择 JDK 和构建工具版本");
            return;
        }
        if (isProjectBusy(project)) {
            showEditorError("当前项目已有程序正在运行，请先停止");
            return;
        }
        if (sourceFile.equals(currentEditingFile) && editorDirty) saveCurrentFile();

        String mainClass = resolveMainClassName(sourceFile);
        if (mainClass == null) {
            showEditorError("无法识别 main 类的包名");
            return;
        }
        File moduleRoot = isGradleProject(project)
                ? findGradleModuleRoot(sourceFile, project.getPath())
                : findMavenModuleRoot(sourceFile, project.getPath());
        boolean testSource = sourceFile.toPath().toAbsolutePath().normalize().toString()
                .replace('\\', '/').contains("/src/test/");

        navigationTabPane.getSelectionModel().select(runTab);
        clearRunOutput(project);
        lastRunProject = project;
        lastRunSourceFile = sourceFile;
        lastRunConfiguration = new RunConfiguration("JAVA", mainClass, sourceFile);
        rerunButton.setDisable(false);
        cancelledProjects.remove(projectProcessKey(project));
        launchingProjects.add(projectProcessKey(project));
        refreshDirectoryTree();
        updateRunControlState();
        appendRunOutput("准备运行 " + mainClass + System.lineSeparator());
        buildExecutor.execute(projectLogTask(project,
                () -> executeMainClass(project, jdk, moduleRoot, mainClass, testSource)));
    }

    private void runNpmScript(DevDirectory project, String script) {
        if (currentDirectory != project) selectDirectory(project);
        DevelopmentConfigManager.RuntimeEntry node = resolveRuntime(project.nodeId, "NODE");
        if (node == null) {
            showEditorError("请先右键项目打开“配置”，选择 Node 版本");
            return;
        }
        if (isProjectBusy(project)) {
            showEditorError("当前项目已有程序正在运行，请先停止");
            return;
        }
        File configuredNode = new File(node.getPath());
        File nodeExecutable = configuredNode.isDirectory()
                ? new File(configuredNode, windows() ? "node.exe" : "node")
                : configuredNode;
        if (!nodeExecutable.isFile()) {
            showEditorError("未找到 Node 可执行文件：" + nodeExecutable.getAbsolutePath());
            return;
        }
        File npmExecutable = new File(nodeExecutable.getParentFile(), windows() ? "npm.cmd" : "npm");
        if (!npmExecutable.isFile()) {
            showEditorError("未找到 npm：" + npmExecutable.getAbsolutePath());
            return;
        }

        project.runType = "NPM";
        project.runTarget = script;
        persistProjectRunSettings();
        lastRunProject = project;
        lastRunSourceFile = null;
        lastRunConfiguration = new RunConfiguration("NPM", script, null);
        rerunButton.setDisable(false);
        cancelledProjects.remove(projectProcessKey(project));
        launchingProjects.add(projectProcessKey(project));
        navigationTabPane.getSelectionModel().select(runTab);
        clearRunOutput(project);
        appendRunOutput("准备运行 npm script: " + script + System.lineSeparator());
        refreshDirectoryTree();
        updateRunControlState();

        buildExecutor.execute(projectLogTask(project, () -> {
            Process launchedProcess = null;
            try {
                List<String> command = new ArrayList<>();
                if (windows()) {
                    command.add("cmd.exe");
                    command.add("/d");
                    command.add("/c");
                }
                command.add(npmExecutable.getAbsolutePath());
                command.add("run");
                command.add(script);
                appendRunOutput("[NPM] 启动命令：" + System.lineSeparator()
                        + formatCommand(command) + System.lineSeparator());
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(project.getPath());
                builder.redirectErrorStream(true);
                String oldPath = builder.environment().getOrDefault("PATH",
                        builder.environment().getOrDefault("Path", ""));
                builder.environment().put("PATH", nodeExecutable.getParent()
                        + File.pathSeparator + oldPath);
                if (cancelledProjects.contains(projectProcessKey(project))) return;
                launchedProcess = builder.start();
                registerManagedProcess(project, launchedProcess);
                Platform.runLater(() -> {
                    refreshDirectoryTree();
                    updateRunControlState();
                });
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(launchedProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendRunOutput(line + System.lineSeparator());
                    }
                }
                int exitCode = launchedProcess.waitFor();
                appendRunOutput("进程结束，退出码：" + exitCode + System.lineSeparator());
            } catch (Exception e) {
                appendRunOutput("npm 启动失败：" + e.getMessage() + System.lineSeparator());
            } finally {
                releaseManagedProcess(project, launchedProcess);
                Platform.runLater(() -> {
                    refreshDirectoryTree();
                    updateRunControlState();
                });
            }
        }));
    }

    private void executeMainClass(DevDirectory project,
                                  DevelopmentConfigManager.RuntimeEntry jdk,
                                  File moduleRoot,
                                  String mainClass,
                                  boolean testSource) {
        if (isGradleProject(project)) {
            executeGradleMainClass(project, jdk, moduleRoot, mainClass, testSource);
            return;
        }
        Path classpathFile = null;
        Process launchedProcess = null;
        try {
            appendRunOutput("[Maven] 正在编译项目..." + System.lineSeparator());
            CommandResult compile = runMavenCommand(project, project.getPath(),
                    List.of(testSource ? "test-compile" : "compile"), 300);
            appendRunOutput(compile.output);
            boolean legacyLocalFallback = false;
            if (!compile.success && isCachedResolutionFailure(compile.output)) {
                appendRunOutput("[Maven] 检测到依赖失败缓存，使用 -U 强制刷新后重试..."
                        + System.lineSeparator());
                compile = runMavenCommand(project, project.getPath(),
                        List.of("-U", testSource ? "test-compile" : "compile"), 300);
                appendRunOutput(compile.output);
            }
            if (!compile.success && isCachedResolutionFailure(compile.output)) {
                appendRunOutput("[Maven] 远程仓库仍不可用，尝试使用本地已有 JAR..."
                        + System.lineSeparator());
                compile = runMavenCommand(project, project.getPath(),
                        List.of("-o", "-Daether.artifactResolver.simpleLrmInterop=true",
                                testSource ? "test-compile" : "compile"), 300);
                appendRunOutput(compile.output);
                legacyLocalFallback = compile.success;
            }
            if (!compile.success) {
                appendRunOutput("[Maven] 编译失败" + System.lineSeparator());
                return;
            }

            classpathFile = Files.createTempFile("tomato-maven-classpath-", ".txt");
            List<String> repositoryGoals = new ArrayList<>();
            if (legacyLocalFallback) {
                repositoryGoals.add("-o");
                repositoryGoals.add("-Daether.artifactResolver.simpleLrmInterop=true");
            }
            repositoryGoals.addAll(List.of("help:evaluate", "-Dexpression=settings.localRepository",
                    "-q", "-DforceStdout"));
            CommandResult repositoryResult = runMavenCommand(project, moduleRoot, repositoryGoals, 120);
            if (repositoryResult.success) {
                appendRunOutput("[Maven] 本地仓库：" + lastMeaningfulLine(repositoryResult.output)
                        + System.lineSeparator());
            }
            List<String> dependencyGoals = new ArrayList<>();
            if (legacyLocalFallback) {
                dependencyGoals.add("-o");
                dependencyGoals.add("-Daether.artifactResolver.simpleLrmInterop=true");
            }
            dependencyGoals.add("dependency:build-classpath");
            dependencyGoals.add("-Dmdep.outputFile=" + classpathFile.toAbsolutePath());
            dependencyGoals.add("-Dmdep.includeScope=" + (testSource ? "test" : "runtime"));
            CommandResult classpathResult = runMavenCommand(project, moduleRoot, dependencyGoals, 180);
            if (!classpathResult.success) {
                appendRunOutput(classpathResult.output);
                appendRunOutput("[Maven] 无法生成依赖类路径" + System.lineSeparator());
                return;
            }

            String dependencies = Files.exists(classpathFile)
                    ? Files.readString(classpathFile, StandardCharsets.UTF_8).trim() : "";
            List<String> classpathParts = new ArrayList<>();
            if (testSource) classpathParts.add(new File(moduleRoot, "target/test-classes").getAbsolutePath());
            classpathParts.add(new File(moduleRoot, "target/classes").getAbsolutePath());
            if (!dependencies.isBlank()) classpathParts.add(dependencies);

            String javaExecutable = Path.of(jdk.getPath(), "bin", windows() ? "java.exe" : "java").toString();
            List<String> command = new ArrayList<>();
            command.add(javaExecutable);
            command.add("-XX:TieredStopAtLevel=1");
            command.add("-Dspring.output.ansi.enabled=always");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dsun.stdout.encoding=UTF-8");
            command.add("-Dsun.stderr.encoding=UTF-8");
            if (!project.activeProfiles.isEmpty()) {
                command.add("-Dspring.profiles.active=" + String.join(",", project.activeProfiles));
            }
            command.add("-classpath");
            command.add(String.join(File.pathSeparator, classpathParts));
            command.add(mainClass);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(moduleRoot);
            processBuilder.redirectErrorStream(true);
            replaceRunOutput("准备运行 " + mainClass + System.lineSeparator()
                    + "[Java] 启动命令：" + System.lineSeparator()
                    + formatCommand(command) + System.lineSeparator());
            launchedProcess = processBuilder.start();
            registerManagedProcess(project, launchedProcess);
            Platform.runLater(() -> {
                refreshDirectoryTree();
                updateRunControlState();
            });
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(launchedProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendRunOutput(line + System.lineSeparator());
                }
            }
            int exitCode = launchedProcess.waitFor();
            appendRunOutput("进程结束，退出码：" + exitCode + System.lineSeparator());
        } catch (Exception e) {
            appendRunOutput("运行失败：" + e.getMessage() + System.lineSeparator());
        } finally {
            releaseManagedProcess(project, launchedProcess);
            buildProcesses.remove(projectProcessKey(project));
            Platform.runLater(() -> {
                refreshDirectoryTree();
                updateRunControlState();
            });
            if (classpathFile != null) {
                try { Files.deleteIfExists(classpathFile); } catch (Exception ignored) {}
            }
        }
    }

    private void executeGradleMainClass(DevDirectory project,
                                        DevelopmentConfigManager.RuntimeEntry jdk,
                                        File moduleRoot,
                                        String mainClass,
                                        boolean testSource) {
        Path initScript = null;
        Process launchedProcess = null;
        try {
            initScript = Files.createTempFile("tomato-gradle-runtime-", ".gradle");
            String script = "allprojects { p ->\n"
                    + "  p.plugins.withId('java') {\n"
                    + "    if (p.tasks.findByName('tomatoRuntimeClasspath') == null) {\n"
                    + "      p.tasks.register('tomatoRuntimeClasspath') {\n"
                    + "        doLast {\n"
                    + "          def n = System.getProperty('tomato.sourceSet', 'main')\n"
                    + "          println 'TOMATO_CLASSPATH=' + p.sourceSets.getByName(n).runtimeClasspath.asPath\n"
                    + "        }\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n";
            Files.writeString(initScript, script, StandardCharsets.UTF_8);
            appendRunOutput("[Gradle] 正在重新编译并解析 runtimeClasspath..." + System.lineSeparator());
            List<String> goals = new ArrayList<>();
            goals.add("--console=plain");
            goals.add("-I");
            goals.add(initScript.toAbsolutePath().toString());
            goals.add("-Dtomato.sourceSet=" + (testSource ? "test" : "main"));
            goals.add(testSource ? "testClasses" : "classes");
            goals.add("tomatoRuntimeClasspath");
            CommandResult build = runGradleCommand(project, moduleRoot, goals, 300);
            appendRunOutput(build.output);
            if (!build.success) {
                appendRunOutput("[Gradle] 编译或依赖解析失败" + System.lineSeparator());
                return;
            }
            String classpath = extractMarkedValue(build.output, "TOMATO_CLASSPATH=");
            if (classpath == null || classpath.isBlank()) {
                appendRunOutput("[Gradle] 未获取到 runtimeClasspath" + System.lineSeparator());
                return;
            }

            String javaExecutable = Path.of(jdk.getPath(), "bin", windows() ? "java.exe" : "java").toString();
            List<String> command = new ArrayList<>();
            command.add(javaExecutable);
            command.add("-XX:TieredStopAtLevel=1");
            command.add("-Dspring.output.ansi.enabled=always");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dsun.stdout.encoding=UTF-8");
            command.add("-Dsun.stderr.encoding=UTF-8");
            if (!project.activeProfiles.isEmpty()) {
                command.add("-Dspring.profiles.active=" + String.join(",", project.activeProfiles));
            }
            command.add("-classpath");
            command.add(classpath);
            command.add(mainClass);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(moduleRoot);
            processBuilder.redirectErrorStream(true);
            replaceRunOutput("准备运行 " + mainClass + System.lineSeparator()
                    + "[Java] 启动命令：" + System.lineSeparator()
                    + formatCommand(command) + System.lineSeparator());
            launchedProcess = processBuilder.start();
            registerManagedProcess(project, launchedProcess);
            Platform.runLater(() -> {
                refreshDirectoryTree();
                updateRunControlState();
            });
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(launchedProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) appendRunOutput(line + System.lineSeparator());
            }
            int exitCode = launchedProcess.waitFor();
            appendRunOutput("进程结束，退出码：" + exitCode + System.lineSeparator());
        } catch (Exception e) {
            appendRunOutput("Gradle 启动失败：" + e.getMessage() + System.lineSeparator());
        } finally {
            releaseManagedProcess(project, launchedProcess);
            buildProcesses.remove(projectProcessKey(project));
            Platform.runLater(() -> {
                refreshDirectoryTree();
                updateRunControlState();
            });
            if (initScript != null) {
                try { Files.deleteIfExists(initScript); } catch (Exception ignored) {}
            }
        }
    }

    private CommandResult runGradleCommand(DevDirectory project, File workDir,
                                           List<String> goals, long timeoutSeconds) {
        DevelopmentConfigManager.BuildToolEntry gradle = resolveBuildTool(project.mavenId);
        DevelopmentConfigManager.RuntimeEntry jdk = resolveRuntime(project.jdkId, "JDK");
        if (gradle == null || jdk == null || !"GRADLE".equals(gradle.getType())) {
            return new CommandResult(false, -1, "未配置 JDK 或 Gradle");
        }
        String executable = Path.of(gradle.getHomePath(), "bin",
                windows() ? "gradle.bat" : "gradle").toString();
        List<String> command = new ArrayList<>();
        if (windows()) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
        }
        command.add(executable);
        command.addAll(goals);
        return runBuildCommand(project, workDir, command, jdk, timeoutSeconds, "Gradle");
    }

    private CommandResult runMavenCommand(DevDirectory project, File workDir,
                                          List<String> goals, long timeoutSeconds) {
        DevelopmentConfigManager.BuildToolEntry maven = resolveBuildTool(project.mavenId);
        DevelopmentConfigManager.RuntimeEntry jdk = resolveRuntime(project.jdkId, "JDK");
        if (maven == null || jdk == null || !"MAVEN".equals(maven.getType())) {
            return new CommandResult(false, -1, "未配置 JDK 或 Maven");
        }
        File wrapper = new File(project.getPath(), windows() ? "mvnw.cmd" : "mvnw");
        String executable = wrapper.isFile()
                ? wrapper.getAbsolutePath()
                : Path.of(maven.getHomePath(), "bin", windows() ? "mvn.cmd" : "mvn").toString();
        List<String> command = new ArrayList<>();
        if (windows()) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
        }
        command.add(executable);
        String settingsPath = resolveMavenSettingsPath(project, maven.getSettingsPath());
        if (maven.getSettingsPath() != null && !maven.getSettingsPath().isBlank()
                && settingsPath == null) {
            return new CommandResult(false, -1,
                    "Maven settings.xml 不存在：" + maven.getSettingsPath());
        }
        if (settingsPath != null) {
            command.add("-s");
            command.add(settingsPath);
        }
        command.add("-Djansi.passthrough=true");
        command.add("-Dstyle.color=always");
        command.addAll(goals);
        return runBuildCommand(project, workDir, command, jdk, timeoutSeconds, "Maven");
    }

    private String resolveMavenSettingsPath(DevDirectory project, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return null;
        Path configured = Path.of(configuredPath);
        if (Files.isRegularFile(configured)) return configured.toAbsolutePath().normalize().toString();
        Path userSettings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
        if (Files.isRegularFile(userSettings)) {
            if (isProjectLaunching(project)) {
                appendRunOutput("[Maven] 配置的 settings.xml 不存在，改用："
                        + userSettings + System.lineSeparator());
            }
            return userSettings.toAbsolutePath().normalize().toString();
        }
        return null;
    }

    private CommandResult runBuildCommand(DevDirectory project, File workDir,
                                          List<String> command,
                                          DevelopmentConfigManager.RuntimeEntry jdk,
                                          long timeoutSeconds,
                                          String toolName) {
        try {
            if (isProjectLaunching(project)) {
                appendRunOutput("[" + toolName + "] 命令：" + System.lineSeparator()
                        + formatCommand(command) + System.lineSeparator());
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workDir);
            builder.redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("JAVA_HOME", jdk.getPath());
            String oldPath = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
            environment.put("PATH", Path.of(jdk.getPath(), "bin") + File.pathSeparator + oldPath);
            if (cancelledProjects.contains(projectProcessKey(project))) {
                return new CommandResult(false, -1, toolName + " 启动已取消");
            }
            Process process = builder.start();
            if (isProjectLaunching(project)) buildProcesses.put(projectProcessKey(project), process);
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                buildProcesses.remove(projectProcessKey(project), process);
                return new CommandResult(false, -1, toolName + " 命令执行超时");
            }
            String output = outputFuture.get(10, TimeUnit.SECONDS);
            buildProcesses.remove(projectProcessKey(project), process);
            return new CommandResult(process.exitValue() == 0, process.exitValue(), output);
        } catch (Exception e) {
            buildProcesses.remove(projectProcessKey(project));
            return new CommandResult(false, -1, e.getMessage());
        }
    }

    private String resolveMainClassName(File sourceFile) {
        try {
            String source = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);
            Matcher packageMatcher = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source);
            String className = sourceFile.getName().replaceFirst("\\.java$", "");
            return packageMatcher.find() ? packageMatcher.group(1) + "." + className : className;
        } catch (Exception e) {
            return null;
        }
    }

    private File findMavenModuleRoot(File sourceFile, File projectRoot) {
        Path root = projectRoot.toPath().toAbsolutePath().normalize();
        Path cursor = sourceFile.toPath().toAbsolutePath().normalize().getParent();
        while (cursor != null && cursor.startsWith(root)) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))) return cursor.toFile();
            if (cursor.equals(root)) break;
            cursor = cursor.getParent();
        }
        return projectRoot;
    }

    private File findGradleModuleRoot(File sourceFile, File projectRoot) {
        Path root = projectRoot.toPath().toAbsolutePath().normalize();
        Path cursor = sourceFile.toPath().toAbsolutePath().normalize().getParent();
        while (cursor != null && cursor.startsWith(root)) {
            if (Files.isRegularFile(cursor.resolve("build.gradle"))
                    || Files.isRegularFile(cursor.resolve("build.gradle.kts"))) {
                return cursor.toFile();
            }
            if (cursor.equals(root)) break;
            cursor = cursor.getParent();
        }
        return projectRoot;
    }

    private String extractMarkedValue(String output, String marker) {
        if (output == null) return null;
        for (String line : output.split("\\R")) {
            int index = line.indexOf(marker);
            if (index >= 0) return line.substring(index + marker.length()).trim();
        }
        return null;
    }

    private String formatCommand(List<String> command) {
        return command.stream().map(argument -> {
            if (argument == null) return "";
            boolean quote = argument.isBlank() || argument.matches(".*[\\s;].*");
            String escaped = argument.replace("\"", "\\\"");
            return quote ? "\"" + escaped + "\"" : escaped;
        }).collect(java.util.stream.Collectors.joining(" "));
    }

    private void stopRunningProcess() {
        DevDirectory project = currentDirectory;
        if (project == null) return;
        String key = projectProcessKey(project);
        Process process = runningProcesses.get(key);
        Process buildProcess = buildProcesses.get(key);
        boolean hasManagedProcess = process != null && process.isAlive();
        boolean hasBuildProcess = buildProcess != null && buildProcess.isAlive();
        if (!hasManagedProcess && !hasBuildProcess && !launchingProjects.contains(key)) return;
        cancelledProjects.add(key);
        appendRunOutput("正在停止进程..." + System.lineSeparator());
        if (hasManagedProcess) terminateProcessTree(process);
        if (hasBuildProcess) terminateProcessTree(buildProcess);
    }

    private synchronized void registerManagedProcess(DevDirectory project, Process process) {
        String key = projectProcessKey(project);
        runningProcesses.put(key, process);
        launchingProjects.remove(key);
        appendRunOutput("[进程] 已接管 PID " + process.pid() + System.lineSeparator());
    }

    private synchronized void releaseManagedProcess(DevDirectory project, Process process) {
        String key = projectProcessKey(project);
        if (process != null) runningProcesses.remove(key, process);
        launchingProjects.remove(key);
        cancelledProjects.remove(key);
    }

    private void terminateProcessTree(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        Collections.reverse(descendants);
        descendants.forEach(handle -> {
            if (handle.isAlive()) handle.destroy();
        });
        if (process.isAlive()) process.destroy();
        CompletableFuture.runAsync(() -> {
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            descendants.forEach(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
            if (process.isAlive()) process.destroyForcibly();
        });
    }

    private void destroyManagedProcessesNow() {
        Set<Process> processes = new HashSet<>(runningProcesses.values());
        processes.addAll(buildProcesses.values());
        for (Process process : processes) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private boolean isProjectRunning(DevDirectory project) {
        if (project == null) return false;
        Process process = runningProcesses.get(projectProcessKey(project));
        return process != null && process.isAlive();
    }

    private boolean isProjectLaunching(DevDirectory project) {
        return project != null && launchingProjects.contains(projectProcessKey(project));
    }

    private boolean isProjectBusy(DevDirectory project) {
        return isProjectLaunching(project) || isProjectRunning(project);
    }

    private String projectProcessKey(DevDirectory project) {
        return project.getPath().toPath().toAbsolutePath().normalize().toString();
    }

    private void appendRunOutput(String text) {
        appendRunOutput(resolveLogProject(), text);
    }

    private void appendRunOutput(DevDirectory project, String text) {
        if (text == null || text.isEmpty()) return;
        Platform.runLater(() -> {
            StringBuilder log = projectLogs.computeIfAbsent(projectLogKey(project), key -> new StringBuilder());
            log.append(text);
            boolean truncated = trimProjectLog(log);
            if (project == currentDirectory) {
                if (truncated) runOutputArea.setText(log.toString());
                else runOutputArea.appendText(text);
                runOutputArea.positionCaret(runOutputArea.getLength());
            }
        });
    }

    private void replaceRunOutput(String text) {
        DevDirectory project = resolveLogProject();
        Platform.runLater(() -> {
            StringBuilder log = projectLogs.computeIfAbsent(projectLogKey(project), key -> new StringBuilder());
            log.setLength(0);
            if (text != null && !text.isEmpty()) log.append(text);
            if (project == currentDirectory) {
                runOutputArea.setText(log.toString());
                runOutputArea.positionCaret(runOutputArea.getLength());
            }
        });
    }

    private void clearRunOutput(DevDirectory project) {
        projectLogs.computeIfAbsent(projectLogKey(project), key -> new StringBuilder()).setLength(0);
        if (project == currentDirectory) runOutputArea.clear();
    }

    private void showProjectLog(DevDirectory project) {
        StringBuilder log = projectLogs.get(projectLogKey(project));
        runOutputArea.setText(log == null ? "" : log.toString());
        runOutputArea.positionCaret(runOutputArea.getLength());
    }

    private Runnable projectLogTask(DevDirectory project, Runnable task) {
        return () -> {
            projectLogContext.set(project);
            try {
                task.run();
            } finally {
                projectLogContext.remove();
            }
        };
    }

    private DevDirectory resolveLogProject() {
        DevDirectory project = projectLogContext.get();
        return project == null ? currentDirectory : project;
    }

    private String projectLogKey(DevDirectory project) {
        return project == null ? "__development_global__"
                : project.getPath().toPath().toAbsolutePath().normalize().toString();
    }

    private boolean trimProjectLog(StringBuilder log) {
        int overflow = log.length() - MAX_PROJECT_LOG_CHARS;
        if (overflow <= 0) return false;
        int nextLine = log.indexOf("\n", overflow);
        log.delete(0, nextLine >= 0 ? nextLine + 1 : overflow);
        return true;
    }

    private String lastMeaningfulLine(String output) {
        if (output == null || output.isBlank()) return "未知错误";
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i];
        }
        return "未知错误";
    }

    private boolean isCachedResolutionFailure(String output) {
        if (output == null) return false;
        return output.contains("cached in the local repository")
                || output.contains("resolution is not reattempted")
                || output.contains("present, but unavailable");
    }

    private boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private void refreshVersionListAsync() {
        stopRealtimeMonitor();
        if (currentDirectory == null) {
            applyVersionResult(new VersionResult(false, Map.of(), "请先在左侧选择目录"));
            return;
        }
        if (currentMode == VersionMode.GIT) {
            asyncExecutor.execute(() -> {
                VersionResult result = queryGitChanges(currentDirectory.getPath());
                Platform.runLater(() -> applyVersionResult(result));
            });
        } else {
            asyncExecutor.execute(() -> {
                VersionResult result = queryRealtimeChanges(currentDirectory.getPath());
                Platform.runLater(() -> applyVersionResult(result));
            });
        }
    }

    private VersionResult queryGitChanges(File directory) {
        CommandResult repoResult = runGitCommand(directory, "rev-parse", "--is-inside-work-tree");
        if (!repoResult.success) {
            return new VersionResult(false, Map.of(), "当前目录不是 git 仓库（或 git 命令不可用）");
        }
        CommandResult statusResult = runGitCommand(directory, "status", "--short", "--untracked-files=normal");
        if (!statusResult.success) {
            return new VersionResult(true, Map.of(), "读取 git 状态失败：" + statusResult.output);
        }
        Map<String, String> changes = parseGitStatus(statusResult.output);
        if (changes.isEmpty()) {
            return new VersionResult(true, Map.of(), "当前 git 仓库无待提交文件");
        }
        return new VersionResult(true, changes, "已读取 git 变更列表");
    }

    private VersionResult queryRealtimeChanges(File directory) {
        try {
            Map<String, FileSnapshot> current = scanFiles(directory);
            Map<String, String> changes = new LinkedHashMap<>();

            for (Map.Entry<String, FileSnapshot> entry : current.entrySet()) {
                String rel = entry.getKey();
                FileSnapshot currentSnap = entry.getValue();
                FileSnapshot oldSnap = realtimeBaseSnapshot.get(rel);
                if (oldSnap == null || currentSnap.changedFrom(oldSnap)) {
                    changes.put(rel, oldSnap == null ? "新增" : "修改");
                }
            }
            for (String rel : realtimeBaseSnapshot.keySet()) {
                if (!current.containsKey(rel)) {
                    changes.put(rel, "删除");
                }
            }
            if (!changes.isEmpty()) {
                Map<String, String> sorted = new LinkedHashMap<>();
                changes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEachOrdered(e -> sorted.put(e.getKey(), e.getValue()));
                return new VersionResult(false, sorted, "实时检测到变更，按路径显示");
            }
            return new VersionResult(false, Map.of(), "当前目录无文件变更");
        } catch (Exception e) {
            return new VersionResult(false, Map.of(), "实时扫描失败：" + e.getMessage());
        }
    }

    private void applyVersionResult(VersionResult result) {
        currentDirectoryGitRepo = result.isGitRepo;
        versionHintLabel.setText(result.message);
        versionPlaceholderLabel.setText(result.message);
        boolean hasChanges = !result.changes.isEmpty();
        versionPlaceholderLabel.setVisible(!hasChanges);
        versionPlaceholderLabel.setManaged(!hasChanges);
        if (result.changes.isEmpty()) {
            TreeItem<ChangeItem> emptyRoot = new TreeItem<>(new ChangeItem("变更文件", "", "root", true));
            versionTree.setRoot(emptyRoot);
            commitButton.setDisable(true);
            return;
        }

        TreeItem<ChangeItem> root = new TreeItem<>(new ChangeItem("变更文件", "", "root", true));
        for (Map.Entry<String, String> e : result.changes.entrySet()) {
            addPathToVersionTree(root, e.getKey(), e.getValue());
        }
        sortVersionTree(root);
        root.setExpanded(true);
        versionTree.setRoot(root);
        commitButton.setDisable(!(currentMode == VersionMode.GIT && result.isGitRepo));
    }

    private void addPathToVersionTree(TreeItem<ChangeItem> root, String rawPath, String statusText) {
        String normalized = rawPath.replace('\\', '/');
        String[] parts = normalized.split("/");
        TreeItem<ChangeItem> parent = root;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isBlank()) continue;
            if (current.length() > 0) {
                current.append('/');
            }
            current.append(p);

            boolean isLeaf = (i == parts.length - 1);
            TreeItem<ChangeItem> child = findVersionChild(parent, p, !isLeaf);
            if (child == null) {
                ChangeItem item = new ChangeItem(p, current.toString(), isLeaf ? statusText : null, !isLeaf);
                child = new TreeItem<>(item);
                parent.getChildren().add(child);
            }
            if (isLeaf && child.getValue().directory) {
                child.setValue(new ChangeItem(p, current.toString(), statusText, false));
            }
            parent = child;
        }
    }

    private TreeItem<ChangeItem> findVersionChild(TreeItem<ChangeItem> parent, String name, boolean expectDir) {
        for (TreeItem<ChangeItem> child : parent.getChildren()) {
            ChangeItem item = child.getValue();
            if (item != null && item.name.equals(name) && item.directory == expectDir) {
                return child;
            }
        }
        return null;
    }

    private void sortVersionTree(TreeItem<ChangeItem> node) {
        node.getChildren().sort((a, b) -> {
            ChangeItem aItem = a.getValue();
            ChangeItem bItem = b.getValue();
            if (aItem.directory != bItem.directory) {
                return aItem.directory ? -1 : 1;
            }
            return aItem.name.compareToIgnoreCase(bItem.name);
        });
        for (TreeItem<ChangeItem> child : node.getChildren()) {
            sortVersionTree(child);
        }
    }

    private void startRealtimeMonitor() {
        stopRealtimeMonitor();
        if (currentDirectory == null) {
            return;
        }
        try {
            realtimeBaseSnapshot = scanFiles(currentDirectory.getPath());
        } catch (Exception ignored) {
            realtimeBaseSnapshot = Map.of();
        }
        realtimeTask = scheduler.scheduleAtFixedRate(() -> {
            if (currentDirectory != null && currentMode == VersionMode.REALTIME) {
                VersionResult result = queryRealtimeChanges(currentDirectory.getPath());
                Platform.runLater(() -> applyVersionResult(result));
            }
        }, 0, 4, TimeUnit.SECONDS);
    }

    private void stopRealtimeMonitor() {
        if (realtimeTask != null && !realtimeTask.isDone()) {
            realtimeTask.cancel(true);
        }
        realtimeTask = null;
    }

    private void commitSelectedFiles() {
        if (currentDirectory == null || !currentDirectoryGitRepo || currentMode != VersionMode.GIT) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "请先在 git 仓库目录下切到“git仓库”模式后再提交", ButtonType.OK);
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        List<String> selectedFiles = collectSelectedFiles();
        if (selectedFiles.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "请先在“版本”列表中勾选需要提交的文件", ButtonType.OK);
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("提交说明");
        dialog.setHeaderText("请填写提交信息");
        dialog.setContentText("提交内容：");
        dialog.initModality(Modality.WINDOW_MODAL);
        if (this.getScene() != null) dialog.initOwner((Stage) this.getScene().getWindow());
        Optional<String> msgOpt = dialog.showAndWait();
        if (msgOpt.isEmpty()) {
            return;
        }
        String msg = msgOpt.get().trim();
        if (msg.isBlank()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "提交信息不能为空", ButtonType.OK);
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }

        commitButton.setDisable(true);
        asyncExecutor.execute(() -> {
            String err = doGitCommit(selectedFiles, msg);
            Platform.runLater(() -> {
                if (err == null) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION, "提交成功", ButtonType.OK);
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                    refreshVersionListAsync();
                } else {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "提交失败：\n" + err, ButtonType.OK);
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                    commitButton.setDisable(!(currentMode == VersionMode.GIT && currentDirectoryGitRepo));
                }
            });
        });
    }

    private String doGitCommit(List<String> files, String message) {
        List<String> addCmd = new ArrayList<>();
        addCmd.add("add");
        addCmd.add("--");
        addCmd.addAll(files);
        CommandResult addResult = runGitCommand(currentDirectory.getPath(), addCmd);
        if (!addResult.success) {
            return "git add 失败：" + addResult.output;
        }
        CommandResult commitResult = runGitCommand(currentDirectory.getPath(), List.of("commit", "-m", message));
        if (!commitResult.success) {
            return "git commit 失败：" + commitResult.output;
        }
        return null;
    }

    private List<String> collectSelectedFiles() {
        TreeItem<ChangeItem> root = versionTree.getRoot();
        if (root == null) {
            return Collections.emptyList();
        }
        List<String> files = new ArrayList<>();
        collectSelectedFiles(root, files);
        return files;
    }

    private void collectSelectedFiles(TreeItem<ChangeItem> node, List<String> result) {
        ChangeItem item = node.getValue();
        if (item != null && !item.directory && item.selected) {
            result.add(item.relativePath);
        }
        for (TreeItem<ChangeItem> child : node.getChildren()) {
            collectSelectedFiles(child, result);
        }
    }

    private Map<String, String> parseGitStatus(String output) {
        if (output == null || output.isBlank()) {
            return Map.of();
        }
        Map<String, String> raw = new LinkedHashMap<>();
        String[] lines = output.split("\\R");
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (line.length() < 3) continue;
            String statusCode = line.substring(0, 2).trim();
            String path = line.substring(3).trim();
            if (path.isBlank()) continue;
            if (path.contains(" -> ")) {
                path = path.substring(path.lastIndexOf(" -> ") + 4).trim();
            }
            String statusText = normalizeGitStatus(statusCode);
            raw.put(path, statusText);
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        raw.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEachOrdered(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private String normalizeGitStatus(String code) {
        if (code.contains("U")) return "冲突";
        if (code.contains("A")) return "新增";
        if (code.contains("M")) return "修改";
        if (code.contains("D")) return "删除";
        if (code.contains("R")) return "重命名";
        if (code.contains("C")) return "拷贝";
        if (code.contains("?")) return "未跟踪";
        return code.isBlank() ? "变更" : code;
    }

    private Map<String, FileSnapshot> scanFiles(File rootDir) throws Exception {
        if (!rootDir.isDirectory()) {
            return Map.of();
        }
        Path rootPath = rootDir.toPath();
        Map<String, FileSnapshot> result = new HashMap<>();
        try (var stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
                    String relative = rootPath.relativize(p).toString().replace('\\', '/');
                    result.put(relative, new FileSnapshot(attr.size(), attr.lastModifiedTime().toMillis()));
                } catch (Exception ignored) {
                }
            });
        }
        return result;
    }

    private CommandResult runGitCommand(File workDir, String... args) {
        return runGitCommand(workDir, Arrays.asList(args));
    }

    private CommandResult runGitCommand(File workDir, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = readAll(p.getInputStream());
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new CommandResult(false, -1, "命令执行超时");
            }
            return new CommandResult(p.exitValue() == 0, p.exitValue(), output);
        } catch (Exception e) {
            return new CommandResult(false, -1, e.getMessage());
        }
    }

    private String readAll(java.io.InputStream inputStream) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private String nowLogPrefix() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
