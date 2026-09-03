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
import javafx.scene.Node;
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
    private final TextArea commitMessageArea = new TextArea();
    private final Button commitButton = new Button("提交");
    private final Button commitAndPushButton = new Button("提交并推送");
    private final ToggleButton gitModeBtn = new ToggleButton("git仓库");
    private final ToggleButton realModeBtn = new ToggleButton("实时");
    private final SqlEditorPane codeEditor = new SqlEditorPane(true, false);
    private final Label editorFileLabel = new Label("请选择目录中的文件");
    private final Button saveEditorButton = new Button("保存");
    private final TreeView<String> dependencyTree = new TreeView<>();
    private final Label dependencyStatusLabel = new Label("请选择 Maven 项目");
    private final HighlightedLogPane runOutputArea = new HighlightedLogPane();
    private static final int MAX_PROJECT_LOG_CHARS = 2_000_000;
    private static final int MAX_INCREMENTAL_JAVA_FILES = 24;
    private final Map<String, StringBuilder> projectLogs = new HashMap<>();
    private final Map<String, MavenIncrementalState> mavenIncrementalStates = new ConcurrentHashMap<>();
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
    private boolean editorReadOnly;
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private DevDirectory lastRunProject;
    private File lastRunSourceFile;
    private RunConfiguration lastRunConfiguration;
    private final Map<String, Process> buildProcesses = new ConcurrentHashMap<>();
    private final Set<String> launchingProjects = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelledProjects = ConcurrentHashMap.newKeySet();
    private boolean projectControlsLoading;

    private static final class MavenIncrementalState {
        private long configurationStamp;
        private String profileKey;
        private String dependencies;
        private Map<String, Long> sources = Map.of();
        private Map<String, Long> mainResources = Map.of();
        private Map<String, Long> testResources = Map.of();
    }

    private record MavenBuildPreparation(boolean success, String dependencies) {}

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
            private final javafx.scene.image.ImageView itemIcon = new javafx.scene.image.ImageView();
            private final javafx.scene.shape.Path arrowPath = createTreeDisclosureArrow();
            private final StackPane disclosurePane = createTreeDisclosurePane(arrowPath);
            private TreeItem<File> disclosureTreeItem;
            private javafx.beans.value.ChangeListener<Boolean> expandedListener;

            {
                setAlignment(Pos.CENTER_LEFT);
                setDisclosureNode(disclosurePane);
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
                updateDisclosureArrow();
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                itemIcon.setImage(getDevelopmentItemIcon(item.getName(), item.isDirectory()));
                itemIcon.setFitWidth(16);
                itemIcon.setFitHeight(16);
                itemIcon.setPreserveRatio(true);
                itemIcon.setSmooth(true);
                setText(item.getName());
                setGraphic(itemIcon);
                setTooltip(new Tooltip(item.getAbsolutePath()));
            }

            private void updateDisclosureArrow() {
                if (disclosureTreeItem != null && expandedListener != null) {
                    disclosureTreeItem.expandedProperty().removeListener(expandedListener);
                }
                disclosureTreeItem = getTreeItem();
                expandedListener = null;
                if (disclosureTreeItem == null) return;
                arrowPath.setRotate(disclosureTreeItem.isExpanded() ? 90 : 0);
                expandedListener = (obs, wasExpanded, isExpanded) ->
                        arrowPath.setRotate(isExpanded ? 90 : 0);
                disclosureTreeItem.expandedProperty().addListener(expandedListener);
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
            editorReadOnly = false;
            editorLoading = true;
            codeEditor.setText(content);
            editorLoading = false;
            editorDirty = false;
            codeEditor.setEditable(true);
            updateEditorHeader();
            loadEditorGitChangesAsync(file);
        } catch (Exception e) {
            editorLoading = false;
            showEditorError("无法打开文件：" + e.getMessage());
        }
    }

    private void saveCurrentFile() {
        if (currentEditingFile == null || editorReadOnly) {
            return;
        }
        try {
            Files.writeString(currentEditingFile.toPath(), codeEditor.getText(), StandardCharsets.UTF_8);
            editorDirty = false;
            updateEditorHeader();
            refreshVersionListAsync();
            loadEditorGitChangesAsync(currentEditingFile);
        } catch (Exception e) {
            showEditorError("保存文件失败：" + e.getMessage());
        }
    }

    private void clearEditor() {
        currentEditingFile = null;
        editorReadOnly = false;
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
        editorFileLabel.setText(currentEditingFile.getName()
                + (editorReadOnly ? "  [已删除，只读]" : "")
                + (editorDirty ? " *" : ""));
        editorFileLabel.setTooltip(new Tooltip(currentEditingFile.getAbsolutePath()));
        saveEditorButton.setDisable(editorReadOnly || !editorDirty);
    }

    private void openVersionFile(ChangeItem item) {
        if (currentDirectory == null || item == null || item.directory) return;
        File file = new File(currentDirectory.getPath(), item.relativePath);
        if (file.isFile()) {
            openCodeFile(file);
        } else if ("已删除".equals(item.statusText)) {
            openDeletedVersionFile(currentDirectory, file);
        }
    }

    private void openDeletedVersionFile(DevDirectory project, File file) {
        asyncExecutor.execute(() -> {
            java.nio.file.Path repositoryRoot = findGitRepositoryRoot(project);
            if (repositoryRoot == null) {
                Platform.runLater(() -> showEditorError("无法读取 Git 仓库根目录"));
                return;
            }
            java.nio.file.Path target = file.toPath().toAbsolutePath().normalize();
            if (!target.startsWith(repositoryRoot)) return;
            String relativePath = repositoryRoot.relativize(target).toString().replace('\\', '/');
            CommandResult result = runGitCommand(repositoryRoot.toFile(), List.of("show", "HEAD:" + relativePath));
            Platform.runLater(() -> {
                if (currentDirectory != project) return;
                if (!result.success) {
                    showEditorError("无法读取已删除文件：" + result.output);
                    return;
                }
                currentEditingFile = file;
                editorReadOnly = true;
                editorLoading = true;
                codeEditor.setText(result.output);
                editorLoading = false;
                editorDirty = false;
                codeEditor.setEditable(false);
                int lineCount = countEditorLines(result.output);
                List<SqlEditorPane.ChangeHighlight> highlights = new ArrayList<>(lineCount);
                for (int line = 1; line <= lineCount; line++) {
                    highlights.add(new SqlEditorPane.ChangeHighlight(line, SqlEditorPane.ChangeKind.DELETED));
                }
                codeEditor.setChangeHighlights(highlights);
                updateEditorHeader();
            });
        });
    }

    private void loadEditorGitChangesAsync(File file) {
        DevDirectory project = currentDirectory;
        codeEditor.clearChangeHighlights();
        if (project == null || file == null || !file.isFile()) return;
        asyncExecutor.execute(() -> {
            List<SqlEditorPane.ChangeHighlight> highlights = readGitChangeHighlights(project, file);
            Platform.runLater(() -> {
                if (file.equals(currentEditingFile)) codeEditor.setChangeHighlights(highlights);
            });
        });
    }

    private List<SqlEditorPane.ChangeHighlight> readGitChangeHighlights(DevDirectory project, File file) {
        java.nio.file.Path repositoryRoot = findGitRepositoryRoot(project);
        if (repositoryRoot == null) return List.of();
        java.nio.file.Path target = file.toPath().toAbsolutePath().normalize();
        if (!target.startsWith(repositoryRoot)) return List.of();
        String relativePath = repositoryRoot.relativize(target).toString().replace('\\', '/');
        CommandResult status = runGitCommand(repositoryRoot.toFile(),
                List.of("status", "--porcelain=v1", "--untracked-files=all", "--", relativePath));
        if (!status.success || status.output.isBlank()) return List.of();

        String statusCode = status.output.length() >= 2 ? status.output.substring(0, 2) : status.output;
        boolean newFile = statusCode.contains("?") || statusCode.contains("A");
        CommandResult diff = runGitCommand(repositoryRoot.toFile(),
                List.of("diff", "--no-ext-diff", "--no-color", "--unified=0", "HEAD", "--", relativePath));
        if (!diff.success || diff.output.isBlank()) {
            if (!newFile) return List.of();
            int lineCount;
            try {
                lineCount = countEditorLines(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                return List.of();
            }
            List<SqlEditorPane.ChangeHighlight> added = new ArrayList<>(lineCount);
            for (int line = 1; line <= lineCount; line++) {
                added.add(new SqlEditorPane.ChangeHighlight(line, SqlEditorPane.ChangeKind.ADDED));
            }
            return added;
        }
        return parseGitDiffHighlights(diff.output);
    }

    private java.nio.file.Path findGitRepositoryRoot(DevDirectory project) {
        CommandResult root = runGitCommand(project.getPath(), List.of("rev-parse", "--show-toplevel"));
        if (!root.success) return null;
        try {
            return java.nio.file.Path.of(lastMeaningfulLine(root.output)).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<SqlEditorPane.ChangeHighlight> parseGitDiffHighlights(String diffOutput) {
        Pattern hunkPattern = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
        List<SqlEditorPane.ChangeHighlight> highlights = new ArrayList<>();
        for (String line : diffOutput.split("\\R")) {
            java.util.regex.Matcher matcher = hunkPattern.matcher(line);
            if (!matcher.matches()) continue;
            int oldCount = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            int newStart = Integer.parseInt(matcher.group(3));
            int newCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
            if (newCount == 0) {
                highlights.add(new SqlEditorPane.ChangeHighlight(
                        Math.max(1, newStart), SqlEditorPane.ChangeKind.DELETED));
                continue;
            }
            SqlEditorPane.ChangeKind commonKind = oldCount == 0
                    ? SqlEditorPane.ChangeKind.ADDED
                    : SqlEditorPane.ChangeKind.MODIFIED;
            int commonCount = oldCount == 0 ? newCount : Math.min(oldCount, newCount);
            for (int offset = 0; offset < commonCount; offset++) {
                highlights.add(new SqlEditorPane.ChangeHighlight(newStart + offset, commonKind));
            }
            for (int offset = commonCount; offset < newCount; offset++) {
                highlights.add(new SqlEditorPane.ChangeHighlight(
                        newStart + offset, SqlEditorPane.ChangeKind.ADDED));
            }
        }
        return highlights;
    }

    private int countEditorLines(String content) {
        return content == null || content.isEmpty() ? 1 : content.split("\\R", -1).length;
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

        commitButton.setStyle("-fx-background-color: #475569; -fx-text-fill: #fff; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12px;");
        commitAndPushButton.setStyle("-fx-background-color: #2563eb; -fx-text-fill: #fff; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12px;");
        commitButton.setDisable(true);
        commitAndPushButton.setDisable(true);
        commitButton.setOnAction(e -> commitSelectedFiles(false));
        commitAndPushButton.setOnAction(e -> commitSelectedFiles(true));
        commitButton.setCursor(javafx.scene.Cursor.HAND);
        commitAndPushButton.setCursor(javafx.scene.Cursor.HAND);

        versionHintLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
        versionHintLabel.setTextAlignment(TextAlignment.LEFT);

        titleBar.getChildren().addAll(title, modeBox, spacer);

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
            private final javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView();
            private final Label textLabel = new Label();
            private final HBox box = new HBox(6, checkBox, iconView, textLabel);
            private final javafx.scene.shape.Path arrowPath = createTreeDisclosureArrow();
            private final StackPane disclosurePane = createTreeDisclosurePane(arrowPath);
            private TreeItem<ChangeItem> disclosureTreeItem;
            private javafx.beans.value.ChangeListener<Boolean> expandedListener;
            private final MenuItem rollbackItem = new MenuItem("回滚变更");
            private final MenuItem ignoreItem = new MenuItem("添加到 .gitignore");
            private final ContextMenu contextMenu = new ContextMenu(
                    rollbackItem,
                    new SeparatorMenuItem(),
                    ignoreItem
            );

            {
                box.setAlignment(Pos.CENTER_LEFT);
                setAlignment(Pos.CENTER_LEFT);
                setDisclosureNode(disclosurePane);
                rollbackItem.setOnAction(event -> rollbackVersionNode(getTreeItem()));
                ignoreItem.setOnAction(event -> addVersionNodeToGitIgnore(getTreeItem()));
            }

            @Override
            protected void updateItem(ChangeItem item, boolean empty) {
                super.updateItem(item, empty);
                updateDisclosureArrow();
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
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
                    updateCommitButtons();
                });
                iconView.setImage(getVersionItemIcon(item));
                iconView.setFitWidth(16);
                iconView.setFitHeight(16);
                iconView.setPreserveRatio(true);
                iconView.setSmooth(true);
                textLabel.setText(item.name + (item.directory || item.statusText == null ? "" : "  [" + item.statusText + "]"));
                textLabel.setStyle("-fx-text-fill: " + versionStatusColor(item) + ";");
                setContextMenu(currentMode == VersionMode.GIT && currentDirectoryGitRepo ? contextMenu : null);
                setGraphic(box);
                setText(null);
            }

            private void updateDisclosureArrow() {
                if (disclosureTreeItem != null && expandedListener != null) {
                    disclosureTreeItem.expandedProperty().removeListener(expandedListener);
                }
                disclosureTreeItem = getTreeItem();
                expandedListener = null;
                if (disclosureTreeItem == null) return;
                arrowPath.setRotate(disclosureTreeItem.isExpanded() ? 90 : 0);
                expandedListener = (obs, wasExpanded, isExpanded) ->
                        arrowPath.setRotate(isExpanded ? 90 : 0);
                disclosureTreeItem.expandedProperty().addListener(expandedListener);
            }
        });
        versionTree.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) return;
            TreeItem<ChangeItem> selected = versionTree.getSelectionModel().getSelectedItem();
            if (selected == null || selected.getValue() == null || selected.getValue().directory) return;
            openVersionFile(selected.getValue());
            event.consume();
        });
        Label messageTitle = new Label("提交说明");
        messageTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #475569;");
        commitMessageArea.setPromptText("填写本次提交说明...");
        commitMessageArea.setWrapText(true);
        commitMessageArea.setPrefRowCount(5);
        commitMessageArea.setMinHeight(100);
        commitMessageArea.setMaxHeight(180);
        commitMessageArea.textProperty().addListener((obs, oldValue, newValue) -> updateCommitButtons());
        HBox commitActions = new HBox(8, commitButton, commitAndPushButton);
        commitActions.setAlignment(Pos.CENTER_RIGHT);
        Separator commitSeparator = new Separator();
        panel.getChildren().addAll(versionHintLabel, versionTreeContainer,
                commitSeparator, messageTitle, commitMessageArea, commitActions);
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

    private String versionStatusColor(ChangeItem item) {
        if (item == null || item.directory || item.statusText == null) return "#3c3f41";
        return switch (item.statusText) {
            case "未添加" -> "#c75450";
            case "已添加" -> "#57965c";
            case "变更" -> "#0a65bf";
            case "已删除" -> "#7a7e85";
            case "重命名", "拷贝" -> "#7e57c2";
            case "冲突" -> "#d32f2f";
            default -> "#3c3f41";
        };
    }

    private javafx.scene.image.Image getVersionItemIcon(ChangeItem item) {
        return getDevelopmentItemIcon(item.name, item.directory);
    }

    private javafx.scene.shape.Path createTreeDisclosureArrow() {
        javafx.scene.shape.Path arrow = new javafx.scene.shape.Path(
                new javafx.scene.shape.MoveTo(2, 0),
                new javafx.scene.shape.LineTo(7, 5),
                new javafx.scene.shape.LineTo(2, 10)
        );
        arrow.setStroke(javafx.scene.paint.Color.valueOf("#888888"));
        arrow.setStrokeWidth(1.8);
        arrow.setFill(null);
        arrow.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        arrow.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        return arrow;
    }

    private StackPane createTreeDisclosurePane(javafx.scene.shape.Path arrow) {
        StackPane pane = new StackPane(arrow);
        pane.setAlignment(Pos.CENTER);
        pane.setPrefSize(16, 35);
        pane.setMinSize(16, 35);
        return pane;
    }

    private javafx.scene.image.Image getDevelopmentItemIcon(String fileName, boolean directory) {
        String resource = directory
                ? "/images/connect/folder.png"
                : "/images/connect/fileTypes/" + getVersionFileIconType(fileName) + ".png";
        String cacheKey = "version-tree-icon:" + resource;
        Object cached = getProperties().get(cacheKey);
        if (cached instanceof javafx.scene.image.Image image) return image;

        javafx.scene.image.Image image = loadVersionItemIcon(resource);
        if (image == null && !directory) {
            image = loadVersionItemIcon("/images/connect/fileTypes/TXT.png");
        }
        if (image != null) getProperties().put(cacheKey, image);
        return image;
    }

    private javafx.scene.image.Image loadVersionItemIcon(String resource) {
        try (java.io.InputStream stream = getClass().getResourceAsStream(resource)) {
            return stream == null ? null : new javafx.scene.image.Image(stream);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getVersionFileIconType(String fileName) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.equals("dockerfile") || lowerName.startsWith("dockerfile.")) return "DOCKERFILE";
        if (lowerName.equals("makefile") || lowerName.startsWith("makefile.")) return "MAKEFILE";
        if (lowerName.equals(".gitignore") || lowerName.equals(".gitattributes")
                || lowerName.equals(".editorconfig")) return "CONF";

        int dotIndex = lowerName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == lowerName.length() - 1) return "TXT";
        String extension = lowerName.substring(dotIndex + 1);
        return switch (extension) {
            case "yml" -> "YAML";
            case "jpeg", "jfif" -> "JPG";
            case "htm" -> "HTML";
            case "kts" -> "KT";
            case "cc", "cxx" -> "CPP";
            case "hpp", "hxx" -> "H";
            case "bash", "zsh" -> "SH";
            case "properties", "config" -> "CONF";
            case "ai" -> "Ai";
            default -> extension.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private void rollbackVersionNode(TreeItem<ChangeItem> node) {
        if (node == null || currentDirectory == null || currentMode != VersionMode.GIT
                || !currentDirectoryGitRepo) return;
        List<ChangeItem> changes = new ArrayList<>();
        collectVersionLeafItems(node, changes);
        if (changes.isEmpty()) return;

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "确定回滚选中的 " + changes.size() + " 个文件吗？未添加的文件将被删除，此操作不可撤销。",
                ButtonType.OK, ButtonType.CANCEL);
        confirmation.setTitle("回滚变更");
        confirmation.setHeaderText(node.getValue() == null ? null : node.getValue().name);
        DialogPositionUtil.centerOnOwner(confirmation, this);
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        DevDirectory project = currentDirectory;
        asyncExecutor.execute(() -> {
            String error = doGitRollback(project, changes);
            Platform.runLater(() -> {
                if (error != null) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "回滚失败：\n" + error, ButtonType.OK);
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                }
                if (currentDirectory == project) refreshVersionListAsync();
            });
        });
    }

    private void collectVersionLeafItems(TreeItem<ChangeItem> node, List<ChangeItem> result) {
        ChangeItem item = node.getValue();
        if (item != null && !item.directory) result.add(item);
        for (TreeItem<ChangeItem> child : node.getChildren()) collectVersionLeafItems(child, result);
    }

    private String doGitRollback(DevDirectory project, List<ChangeItem> changes) {
        for (ChangeItem item : changes) {
            if ("未添加".equals(item.statusText)) {
                CommandResult clean = runGitCommand(project.getPath(), List.of("clean", "-fd", "--", item.relativePath));
                if (!clean.success) return clean.output;
                continue;
            }
            if ("已添加".equals(item.statusText)) {
                runGitCommand(project.getPath(), List.of("restore", "--staged", "--", item.relativePath));
                CommandResult clean = runGitCommand(project.getPath(), List.of("clean", "-fd", "--", item.relativePath));
                if (!clean.success) return clean.output;
                continue;
            }
            CommandResult restore = runGitCommand(project.getPath(),
                    List.of("restore", "--staged", "--worktree", "--", item.relativePath));
            if (!restore.success) return restore.output;
        }
        return null;
    }

    private void addVersionNodeToGitIgnore(TreeItem<ChangeItem> node) {
        if (node == null || node.getValue() == null || currentDirectory == null
                || currentMode != VersionMode.GIT || !currentDirectoryGitRepo) return;

        DevDirectory project = currentDirectory;
        ChangeItem item = node.getValue();
        asyncExecutor.execute(() -> {
            String result = doAddToGitIgnore(project, item);
            Platform.runLater(() -> {
                if (result.startsWith("ERROR:")) {
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            result.substring("ERROR:".length()).trim(), ButtonType.OK);
                    alert.setTitle("添加失败");
                    alert.setHeaderText("无法添加到 .gitignore");
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                    return;
                }
                versionHintLabel.setText(result);
                if (currentDirectory == project) refreshVersionListAsync();
            });
        });
    }

    private String doAddToGitIgnore(DevDirectory project, ChangeItem item) {
        CommandResult rootResult = runGitCommand(project.getPath(), List.of("rev-parse", "--show-toplevel"));
        if (!rootResult.success) return "ERROR:" + rootResult.output;

        try {
            java.nio.file.Path repositoryRoot = java.nio.file.Path.of(lastMeaningfulLine(rootResult.output))
                    .toAbsolutePath().normalize();
            java.nio.file.Path target = project.getPath().toPath().resolve(item.relativePath)
                    .toAbsolutePath().normalize();
            if (!target.startsWith(repositoryRoot)) {
                return "ERROR:所选路径不在当前 Git 仓库中";
            }

            String relativePath = repositoryRoot.relativize(target).toString().replace('\\', '/');
            if (relativePath.isBlank() || ".gitignore".equals(relativePath)) {
                return "ERROR:不能将该路径添加到 .gitignore";
            }

            boolean directory = item.directory || java.nio.file.Files.isDirectory(target);
            String rule = "/" + relativePath + (directory ? "/" : "");
            java.nio.file.Path ignoreFile = repositoryRoot.resolve(".gitignore");
            String existing = java.nio.file.Files.isRegularFile(ignoreFile)
                    ? java.nio.file.Files.readString(ignoreFile, java.nio.charset.StandardCharsets.UTF_8)
                    : "";
            boolean exists = existing.lines().map(String::trim).anyMatch(rule::equals);
            if (!exists) {
                String separator = existing.isEmpty() || existing.endsWith("\n") || existing.endsWith("\r")
                        ? ""
                        : System.lineSeparator();
                java.nio.file.Files.writeString(
                        ignoreFile,
                        separator + rule + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND
                );
            }

            String trackedHint = "未添加".equals(item.statusText)
                    ? ""
                    : "；已跟踪的文件需先取消 Git 跟踪才会被忽略";
            return (exists ? "忽略规则已存在：" : "已添加忽略规则：") + rule + trackedHint;
        } catch (Exception ex) {
            return "ERROR:" + ex.getMessage();
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
        Process launchedProcess = null;
        try {
            MavenBuildPreparation preparation = prepareMavenBuild(
                    project, jdk, moduleRoot, testSource);
            if (!preparation.success) return;
            String dependencies = preparation.dependencies;
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
        }
    }

    private MavenBuildPreparation prepareMavenBuild(DevDirectory project,
                                                     DevelopmentConfigManager.RuntimeEntry jdk,
                                                     File moduleRoot,
                                                     boolean testSource) {
        String stateKey = moduleRoot.toPath().toAbsolutePath().normalize()
                + "|test=" + testSource;
        long configurationStamp = getMavenConfigurationStamp(project.getPath());
        String profileKey = project.mavenId + "|" + String.join(",", project.activeProfiles);
        Map<String, Long> sources = snapshotJavaSources(moduleRoot, testSource);
        Map<String, Long> mainResources = snapshotFiles(
                new File(moduleRoot, "src/main/resources").toPath(), false);
        Map<String, Long> testResources = testSource
                ? snapshotFiles(new File(moduleRoot, "src/test/resources").toPath(), false)
                : Map.of();
        MavenIncrementalState state = mavenIncrementalStates.get(stateKey);

        boolean outputReady = containsClassFile(new File(moduleRoot, "target/classes").toPath())
                && (!testSource || Files.isDirectory(new File(moduleRoot, "target/test-classes").toPath()));
        boolean canIncrement = state != null
                && state.configurationStamp == configurationStamp
                && profileKey.equals(state.profileKey)
                && state.dependencies != null
                && outputReady
                && !containsDeletedPath(state.sources, sources);

        if (canIncrement) {
            List<Path> changedSources = changedPaths(state.sources, sources);
            if (changedSources.size() <= MAX_INCREMENTAL_JAVA_FILES) {
                appendRunOutput(changedSources.isEmpty()
                        ? "[Javac] 未发现 Java 类变更，跳过编译" + System.lineSeparator()
                        : "[Javac] 增量编译 " + changedSources.size() + " 个变更文件..."
                                + System.lineSeparator());
                CommandResult incremental = compileChangedJavaFiles(
                        project, jdk, moduleRoot, testSource, state.dependencies, changedSources);
                String resourceError = incremental.success
                        ? copyChangedResources(state.mainResources, mainResources,
                                new File(moduleRoot, "src/main/resources").toPath(),
                                new File(moduleRoot, "target/classes").toPath())
                        : null;
                if (incremental.success && resourceError == null && testSource) {
                    resourceError = copyChangedResources(state.testResources, testResources,
                            new File(moduleRoot, "src/test/resources").toPath(),
                            new File(moduleRoot, "target/test-classes").toPath());
                }
                if (incremental.success && resourceError == null) {
                    if (!incremental.output.isBlank()) appendRunOutput(incremental.output);
                    state.sources = sources;
                    state.mainResources = mainResources;
                    state.testResources = testResources;
                    return new MavenBuildPreparation(true, state.dependencies);
                }
                if (!incremental.output.isBlank()) appendRunOutput(incremental.output);
                if (resourceError != null) {
                    appendRunOutput("[资源] 增量复制失败：" + resourceError + System.lineSeparator());
                }
                appendRunOutput("[Javac] 增量编译不可用，回退 Maven 完整编译..."
                        + System.lineSeparator());
            } else {
                appendRunOutput("[Javac] 变更文件较多，回退 Maven 完整编译..."
                        + System.lineSeparator());
            }
        }

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
            return new MavenBuildPreparation(false, "");
        }

        Path classpathFile = null;
        try {
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
                return new MavenBuildPreparation(false, "");
            }

            String dependencies = Files.exists(classpathFile)
                    ? Files.readString(classpathFile, StandardCharsets.UTF_8).trim() : "";
            MavenIncrementalState newState = new MavenIncrementalState();
            newState.configurationStamp = configurationStamp;
            newState.profileKey = profileKey;
            newState.dependencies = dependencies;
            newState.sources = snapshotJavaSources(moduleRoot, testSource);
            newState.mainResources = snapshotFiles(
                    new File(moduleRoot, "src/main/resources").toPath(), false);
            newState.testResources = testSource
                    ? snapshotFiles(new File(moduleRoot, "src/test/resources").toPath(), false)
                    : Map.of();
            mavenIncrementalStates.put(stateKey, newState);
            return new MavenBuildPreparation(true, dependencies);
        } catch (Exception e) {
            appendRunOutput("[Maven] 无法准备启动类路径：" + e.getMessage() + System.lineSeparator());
            return new MavenBuildPreparation(false, "");
        } finally {
            if (classpathFile != null) {
                try { Files.deleteIfExists(classpathFile); } catch (Exception ignored) {}
            }
        }
    }

    private CommandResult compileChangedJavaFiles(DevDirectory project,
                                                  DevelopmentConfigManager.RuntimeEntry jdk,
                                                  File moduleRoot,
                                                  boolean testSource,
                                                  String dependencies,
                                                  List<Path> changedSources) {
        if (changedSources.isEmpty()) return new CommandResult(true, 0, "");
        List<Path> mainSources = changedSources.stream()
                .filter(path -> path.startsWith(new File(moduleRoot, "src/main/java").toPath()
                        .toAbsolutePath().normalize()))
                .toList();
        List<Path> testSources = testSource
                ? changedSources.stream()
                        .filter(path -> path.startsWith(new File(moduleRoot, "src/test/java").toPath()
                                .toAbsolutePath().normalize()))
                        .toList()
                : List.of();

        if (!mainSources.isEmpty()) {
            CommandResult result = runJavac(project, jdk, moduleRoot, false, dependencies, mainSources);
            if (!result.success) return result;
        }
        if (!testSources.isEmpty()) {
            return runJavac(project, jdk, moduleRoot, true, dependencies, testSources);
        }
        return new CommandResult(true, 0, "");
    }

    private CommandResult runJavac(DevDirectory project,
                                   DevelopmentConfigManager.RuntimeEntry jdk,
                                   File moduleRoot,
                                   boolean testSource,
                                   String dependencies,
                                   List<Path> sourceFiles) {
        Path output = new File(moduleRoot, testSource
                ? "target/test-classes" : "target/classes").toPath().toAbsolutePath().normalize();
        try {
            Files.createDirectories(output);
        } catch (Exception e) {
            return new CommandResult(false, -1, e.getMessage());
        }

        List<String> classpathParts = new ArrayList<>();
        if (testSource) {
            classpathParts.add(new File(moduleRoot, "target/test-classes").getAbsolutePath());
        }
        classpathParts.add(new File(moduleRoot, "target/classes").getAbsolutePath());
        if (!dependencies.isBlank()) classpathParts.add(dependencies);

        List<String> command = new ArrayList<>();
        command.add(Path.of(jdk.getPath(), "bin", windows() ? "javac.exe" : "javac").toString());
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-parameters");
        command.add("-proc:full");
        command.add("-classpath");
        command.add(String.join(File.pathSeparator, classpathParts));
        command.add("-d");
        command.add(output.toString());
        List<String> sourceRoots = new ArrayList<>();
        sourceRoots.add(new File(moduleRoot, "src/main/java").getAbsolutePath());
        if (testSource) sourceRoots.add(new File(moduleRoot, "src/test/java").getAbsolutePath());
        command.add("-sourcepath");
        command.add(String.join(File.pathSeparator, sourceRoots));
        sourceFiles.stream().map(Path::toString).sorted().forEach(command::add);
        return runBuildCommand(project, moduleRoot, command, jdk, 180, "Javac");
    }

    private Map<String, Long> snapshotJavaSources(File moduleRoot, boolean testSource) {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.putAll(snapshotFiles(new File(moduleRoot, "src/main/java").toPath(), true));
        if (testSource) {
            snapshot.putAll(snapshotFiles(new File(moduleRoot, "src/test/java").toPath(), true));
        }
        return snapshot;
    }

    private Map<String, Long> snapshotFiles(Path root, boolean javaOnly) {
        if (!Files.isDirectory(root)) return Map.of();
        Map<String, Long> snapshot = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> javaOnly == path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            snapshot.put(path.toAbsolutePath().normalize().toString(),
                                    Files.getLastModifiedTime(path).toMillis());
                        } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
        return snapshot;
    }

    private List<Path> changedPaths(Map<String, Long> previous, Map<String, Long> current) {
        List<Path> changed = new ArrayList<>();
        for (Map.Entry<String, Long> entry : current.entrySet()) {
            Long oldTime = previous.get(entry.getKey());
            if (oldTime == null || oldTime.longValue() != entry.getValue()) {
                changed.add(Path.of(entry.getKey()));
            }
        }
        return changed;
    }

    private boolean containsDeletedPath(Map<String, Long> previous, Map<String, Long> current) {
        return previous.keySet().stream().anyMatch(path -> !current.containsKey(path));
    }

    private String copyChangedResources(Map<String, Long> previous,
                                        Map<String, Long> current,
                                        Path sourceRoot,
                                        Path outputRoot) {
        try {
            for (String oldPath : previous.keySet()) {
                if (!current.containsKey(oldPath)) {
                    Files.deleteIfExists(outputRoot.resolve(sourceRoot.relativize(Path.of(oldPath))));
                }
            }
            for (Path changed : changedPaths(previous, current)) {
                Path target = outputRoot.resolve(sourceRoot.relativize(changed));
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.copy(changed, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private long getMavenConfigurationStamp(File projectRoot) {
        if (projectRoot == null || !projectRoot.isDirectory()) return 0;
        final long[] latest = {0};
        try (java.util.stream.Stream<Path> paths = Files.walk(projectRoot.toPath())) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> "pom.xml".equalsIgnoreCase(path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            latest[0] = Math.max(latest[0], Files.getLastModifiedTime(path).toMillis());
                        } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
        return latest[0];
    }

    private boolean containsClassFile(Path outputRoot) {
        if (!Files.isDirectory(outputRoot)) return false;
        try (java.util.stream.Stream<Path> paths = Files.walk(outputRoot)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".class"));
        } catch (Exception ignored) {
            return false;
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
            updateCommitButtons();
            return;
        }

        TreeItem<ChangeItem> root = new TreeItem<>(new ChangeItem("变更文件", "", "root", true));
        for (Map.Entry<String, String> e : result.changes.entrySet()) {
            addPathToVersionTree(root, e.getKey(), e.getValue());
        }
        sortVersionTree(root);
        root.setExpanded(true);
        versionTree.setRoot(root);
        updateCommitButtons();
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

    private void updateCommitButtons() {
        boolean disabled = currentMode != VersionMode.GIT || !currentDirectoryGitRepo
                || commitMessageArea.getText().isBlank() || collectSelectedFiles().isEmpty();
        commitButton.setDisable(disabled);
        commitAndPushButton.setDisable(disabled);
    }

    private void commitSelectedFiles(boolean pushAfterCommit) {
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
        String msg = commitMessageArea.getText().trim();
        if (msg.isBlank()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "提交信息不能为空", ButtonType.OK);
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }

        DevDirectory project = currentDirectory;
        PushTarget pushTarget = null;
        if (pushAfterCommit) {
            Optional<PushTarget> target = showPushDialog(project, selectedFiles);
            if (target.isEmpty()) return;
            pushTarget = target.get();
        }

        commitButton.setDisable(true);
        commitAndPushButton.setDisable(true);
        PushTarget finalPushTarget = pushTarget;
        asyncExecutor.execute(() -> {
            String commitError = doGitCommit(project, selectedFiles, msg);
            String pushError = commitError == null && finalPushTarget != null
                    ? doGitPush(project, finalPushTarget) : null;
            Platform.runLater(() -> {
                if (commitError == null && pushError == null) {
                    commitMessageArea.clear();
                    Alert alert = new Alert(Alert.AlertType.INFORMATION,
                            finalPushTarget == null ? "提交成功" : "提交并推送成功", ButtonType.OK);
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                    if (currentDirectory == project) refreshVersionListAsync();
                } else {
                    String message = commitError != null
                            ? "提交失败：\n" + commitError
                            : "提交成功，但推送失败：\n" + pushError;
                    Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                    updateCommitButtons();
                }
            });
        });
    }

    private record PushTarget(String remote, String branch) {
    }

    private Optional<PushTarget> showPushDialog(DevDirectory project, List<String> files) {
        List<String> remotes = commandLines(runGitCommand(project.getPath(), "remote"));
        if (remotes.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "当前仓库没有可用的 Git remote", ButtonType.OK);
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return Optional.empty();
        }
        String currentBranch = lastMeaningfulLine(
                runGitCommand(project.getPath(), "branch", "--show-current").output);
        List<String> branches = commandLines(runGitCommand(project.getPath(),
                "branch", "--format=%(refname:short)"));

        ComboBox<String> remoteCombo = new ComboBox<>(FXCollections.observableArrayList(remotes));
        remoteCombo.setMaxWidth(Double.MAX_VALUE);
        remoteCombo.getSelectionModel().select(remotes.contains("origin") ? "origin" : remotes.get(0));
        ComboBox<String> branchCombo = new ComboBox<>(FXCollections.observableArrayList(branches));
        branchCombo.setEditable(true);
        branchCombo.setMaxWidth(Double.MAX_VALUE);
        branchCombo.setValue(currentBranch);

        GridPane targetForm = new GridPane();
        targetForm.setHgap(10);
        targetForm.setVgap(12);
        targetForm.setPadding(new Insets(14));
        targetForm.add(new Label("Git remote："), 0, 0);
        targetForm.add(remoteCombo, 1, 0);
        targetForm.add(new Label("目标分支："), 0, 1);
        targetForm.add(branchCombo, 1, 1);
        GridPane.setHgrow(remoteCombo, Priority.ALWAYS);
        GridPane.setHgrow(branchCombo, Priority.ALWAYS);

        TreeView<String> filesTree = new TreeView<>(createSelectedFilesTree(project.getName(), files));
        filesTree.getRoot().setExpanded(true);
        filesTree.setShowRoot(true);
        filesTree.setStyle("-fx-background-color: transparent;");
        filesTree.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        VBox filePanel = new VBox(8, new Label("提交文件（" + files.size() + "）"), filesTree);
        filePanel.setPadding(new Insets(14));
        VBox.setVgrow(filesTree, Priority.ALWAYS);

        SplitPane split = new SplitPane(targetForm, filePanel);
        split.setDividerPositions(0.38);
        split.setPrefSize(760, 460);

        Dialog<PushTarget> dialog = new Dialog<>();
        dialog.setTitle("提交并推送 - " + project.getName());
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        ButtonType pushType = new ButtonType("提交并推送", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(pushType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(split);
        Node pushButton = dialog.getDialogPane().lookupButton(pushType);
        pushButton.disableProperty().bind(remoteCombo.valueProperty().isNull()
                .or(branchCombo.getEditor().textProperty().isEmpty()));
        dialog.setResultConverter(button -> button == pushType
                ? new PushTarget(remoteCombo.getValue(), branchCombo.getEditor().getText().trim()) : null);
        return dialog.showAndWait();
    }

    private TreeItem<String> createSelectedFilesTree(String projectName, List<String> files) {
        TreeItem<String> root = new TreeItem<>(projectName);
        for (String file : files) {
            TreeItem<String> parent = root;
            for (String part : file.replace('\\', '/').split("/")) {
                if (part.isBlank()) continue;
                TreeItem<String> next = parent.getChildren().stream()
                        .filter(item -> part.equals(item.getValue())).findFirst().orElse(null);
                if (next == null) {
                    next = new TreeItem<>(part);
                    parent.getChildren().add(next);
                }
                parent = next;
                parent.setExpanded(true);
            }
        }
        return root;
    }

    private List<String> commandLines(CommandResult result) {
        if (result == null || !result.success || result.output == null) return List.of();
        return result.output.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    private String doGitCommit(DevDirectory project, List<String> files, String message) {
        List<String> addCmd = new ArrayList<>();
        addCmd.add("add");
        addCmd.add("--");
        addCmd.addAll(files);
        CommandResult addResult = runGitCommand(project.getPath(), addCmd);
        if (!addResult.success) {
            return "git add 失败：" + addResult.output;
        }
        CommandResult commitResult = runGitCommand(project.getPath(), List.of("commit", "-m", message));
        if (!commitResult.success) {
            return "git commit 失败：" + commitResult.output;
        }
        return null;
    }

    private String doGitPush(DevDirectory project, PushTarget target) {
        CommandResult result = runGitCommand(project.getPath(), List.of(
                "push", "-u", target.remote(), "HEAD:refs/heads/" + target.branch()));
        return result.success ? null : result.output;
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
        if (code.contains("A")) return "已添加";
        if (code.contains("M")) return "变更";
        if (code.contains("D")) return "已删除";
        if (code.contains("R")) return "重命名";
        if (code.contains("C")) return "拷贝";
        if (code.contains("?")) return "未添加";
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
