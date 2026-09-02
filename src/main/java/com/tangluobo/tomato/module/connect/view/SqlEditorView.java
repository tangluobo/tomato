package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.tangluobo.tomato.utils.RowSelectorDragSelection;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * SQL编辑器视图
 * 优先使用 RichTextFX CodeArea（语法高亮），加载失败时回退到 TextArea
 */
public class SqlEditorView extends BorderPane {

    /** 行选择器列的标识名，用于在获取数据列名时跳过 */
    private static final String ROW_SELECTOR_COL = "__ROW_SELECTOR__";

    private final SqlEditor editor;

    private ComboBox<ConnectionConfig> connectionCombo;
    private ComboBox<String> databaseCombo;
    private TabPane resultTabPane;

    private boolean modified = false;
    private String queryName = null;
    private String savedSql = "";
    private TreeItem<String> queryNode;
    // 当前查询所属目录的相对路径（相对于 query 根目录），""表示根目录
    private String path = "";

    private Consumer<String> onTitleChange;
    private Runnable onSaveRequest;
    private long completionLoadVersion = 0;
    private final ConnectionConfig initialConfig;
    private final String initialDatabase;
    private final String initialSchema;
    private ContextMenu activeResultContextMenu;

    public SqlEditorView(List<ConnectionConfig> connections, ConnectionConfig initialConfig, String initialDatabase) {
        this(connections, initialConfig, initialDatabase, null);
    }

    public SqlEditorView(List<ConnectionConfig> connections, ConnectionConfig initialConfig,
                         String initialDatabase, String initialSchema) {
        this.initialConfig = initialConfig;
        this.initialDatabase = initialDatabase;
        this.initialSchema = initialSchema;
        // ---- 顶部工具栏 ----
        HBox toolbar = new HBox(6);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button saveBtn = createToolbarButton("保存", "/images/connect/save.png");
        saveBtn.setOnAction(e -> handleSave());

        Button beautifyBtn = createToolbarButton("美化", "/images/connect/beautiful.png");
        beautifyBtn.setOnAction(e -> beautifySql());

        Button createQueryToolBtn = createToolbarButton("创建查询工具", "/images/connect/create_query_tool.png");
        createQueryToolBtn.setOnAction(e -> createQueryTool());

        Button runBtn = createToolbarButton("运行", "/images/connect/execute.png");
        runBtn.setOnAction(e -> executeQuery());

        Button explainBtn = createToolbarButton("解释", "/images/connect/code.png");
        explainBtn.setOnAction(e -> explainQuery());

        Separator sep1 = new Separator();
        sep1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep1.setPrefHeight(22);

        connectionCombo = new ComboBox<>();
        connectionCombo.setPrefWidth(140);
        connectionCombo.setEditable(true);
        connectionCombo.setStyle("-fx-background-radius: 0; -fx-border-radius: 0;");
        connectionCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ConnectionConfig c) {
                return c == null ? "" : c.getName();
            }

            @Override
            public ConnectionConfig fromString(String s) {
                if (s == null || s.trim().isEmpty()) return null;
                return connections.stream()
                        .filter(c -> c.getName().equals(s.trim()))
                        .findFirst()
                        .orElse(null);
            }
        });

        Image connectionIcon = new Image(getClass().getResourceAsStream("/images/connect/mysql_open.png"));
        if (connectionIcon != null) {
            connectionCombo.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(ConnectionConfig item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText("");
                        setGraphic(null);
                    } else {
                        setText(item.getName());
                        ImageView cellIcon = new ImageView(connectionIcon);
                        cellIcon.setFitWidth(16);
                        cellIcon.setFitHeight(16);
                        setGraphic(cellIcon);
                        setContentDisplay(ContentDisplay.LEFT);
                        setGraphicTextGap(4);
                    }
                }
            });
        }

        if (connections != null) connectionCombo.getItems().addAll(connections);
        if (initialConfig != null) connectionCombo.setValue(initialConfig);

        connectionCombo.getStyleClass().add("combo-box-connection");
        connectionCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) refreshDatabaseList();
        });

        databaseCombo = new ComboBox<>();
        databaseCombo.setPrefWidth(120);
        databaseCombo.setEditable(true);
        databaseCombo.setStyle("-fx-background-radius: 0; -fx-border-radius: 0;");

        Image databaseIcon = new Image(getClass().getResourceAsStream("/images/connect/database.png"));
        if (databaseIcon != null) {
            databaseCombo.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText("");
                        setGraphic(null);
                    } else {
                        setText(item);
                        ImageView cellIcon = new ImageView(databaseIcon);
                        cellIcon.setFitWidth(16);
                        cellIcon.setFitHeight(16);
                        setGraphic(cellIcon);
                        setContentDisplay(ContentDisplay.LEFT);
                        setGraphicTextGap(4);
                    }
                }
            });
        }

        if (initialDatabase != null) {
            databaseCombo.getItems().add(initialDatabase);
            databaseCombo.setValue(initialDatabase);
        }

        databaseCombo.getStyleClass().add("combo-box-database");
        databaseCombo.valueProperty().addListener((obs, oldVal, newVal) -> refreshCompletionMetadata());

        toolbar.getChildren().addAll(connectionCombo, databaseCombo, sep1, saveBtn, createQueryToolBtn, beautifyBtn, runBtn, explainBtn);

        // ---- 编辑器区域 ----
        // 优先尝试 RichTextFX CodeArea，失败回退到 TextArea
        SqlEditor createdEditor = null;
        try {
            createdEditor = new RichTextSqlEditor(this::markModified);
            System.out.println("SQL编辑器: RichTextFX CodeArea 加载成功");
        } catch (Throwable t) {
            // RichTextFX 加载失败，使用普通 TextArea
            System.err.println("RichTextFX 不可用，使用普通编辑器: " + t.getMessage());
            t.printStackTrace();
            createdEditor = new PlainSqlEditor(this::markModified);
        }
        editor = createdEditor;
        editor.installRunSelectedContextMenu(this::executeQuery);

        // 接入编辑器快捷键回调（Ctrl+Enter 运行 / Ctrl+S 保存）
        if (editor instanceof RichTextSqlEditor) {
            RichTextSqlEditor rte = (RichTextSqlEditor) editor;
            rte.getPane().setOnRunRequest(this::executeQuery);
            rte.getPane().setOnSaveRequest(this::handleSave);
            rte.getPane().setMetadataCompletions(databaseCombo.getItems(), List.of(), List.of());
        }

        // ---- 结果区域 ----
        resultTabPane = new TabPane();
        resultTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        resultTabPane.getStyleClass().add("query-result-tab-pane");
        resultTabPane.setStyle("-fx-font-size: 12px;");
        resultTabPane.setMinHeight(0);

        // 初始占位标签
        Tab placeholderTab = new Tab("信息");
        Label placeholder = new Label("执行查询以查看结果");
        placeholder.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
        placeholderTab.setContent(placeholder);
        resultTabPane.getTabs().add(placeholderTab);

        // ---- 主布局 ----
        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        splitPane.getItems().addAll(editor.getNode(), resultTabPane);
        splitPane.setDividerPositions(0.6);

        this.setTop(toolbar);
        setCenter(splitPane);
        // 在查询页内点击任意其他位置时，统一收起编辑器或结果表的右键菜单。
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> hideOwnedContextMenus());

        // 初始加载数据库列表
        if (initialConfig != null) {
            refreshDatabaseList();
            refreshCompletionMetadata();
        }

        getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
    }

    private void hideOwnedContextMenus() {
        editor.hideContextMenu();
        if (activeResultContextMenu != null && activeResultContextMenu.isShowing()) {
            activeResultContextMenu.hide();
        }
    }

    // ==================== 工具栏按钮创建 ====================

    private Button createToolbarButton(String text, String iconPath) {
        Button button = new Button(text);
        button.getStyleClass().add("toolbar-button");

        Image icon = new Image(getClass().getResourceAsStream(iconPath));
        if (icon != null) {
            ImageView iconView = new ImageView(icon);
            iconView.setFitWidth(16);
            iconView.setFitHeight(16);
            button.setGraphic(iconView);
        }

        return button;
    }

    // ==================== 保存逻辑 ====================

    public void markModified() {
        String current = editor.getText();
        boolean nowModified = !current.equals(savedSql);
        if (nowModified != this.modified) {
            this.modified = nowModified;
            notifyTitleChange();
        }
    }

    private void notifyTitleChange() {
        if (onTitleChange != null) onTitleChange.accept(getDisplayTitle());
    }

    private String getDisplayTitle() {
        String name = queryName != null ? queryName : "未保存查询";
        return (modified ? "*" : "") + name;
    }

    private void handleSave() {
        if (queryName == null) {
            if (onSaveRequest != null) {
                onSaveRequest.run();
                return;
            }
            TextInputDialog dialog = new TextInputDialog("查询1");
            dialog.setTitle("保存查询");
            dialog.setHeaderText(null);
            dialog.setContentText("查询名称：");
            DialogPositionUtil.centerOnOwner(dialog, this);
            dialog.showAndWait().ifPresent(name -> {
                if (!name.trim().isEmpty()) doSave(name.trim());
            });
        } else {
            doSave(queryName);
        }
    }

    private static final String APP_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String QUERY_DIR = "query";

    public void doSave(String name) {
        this.queryName = name;
        this.savedSql = editor.getText();
        this.modified = false;
        notifyTitleChange();

        persistToFile(name, this.path);
    }

    /** 设置当前查询所属目录的相对路径（相对于 query 根目录） */
    public void setPath(String path) { this.path = path == null ? "" : path; }
    public String getPath() { return this.path; }

    /** 解析查询目录：~/.tomato/<conn>/<db>/query/<path> */
    public static Path resolveQueryDir(String connectionName, String dbName, String path) {
        String sanitizedConn = sanitizeFileName(connectionName);
        String sanitizedDb = sanitizeFileName(dbName);
        Path dir = Paths.get(APP_DIR, sanitizedConn, sanitizedDb, QUERY_DIR);
        if (path != null && !path.isEmpty()) {
            for (String part : path.split("/")) {
                dir = dir.resolve(sanitizeFileName(part));
            }
        }
        return dir;
    }

    private void persistToFile(String name, String path) {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) return;

        String sanitizedQuery = sanitizeFileName(name);

        Path dir = resolveQueryDir(config.getName(), dbName, path);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(sanitizedQuery + ".sql");
            Files.writeString(file, savedSql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("保存查询文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                   .replaceAll("\\s+", "_")
                   .replaceAll("_{2,}", "_")
                   .replaceAll("^_|_$", "");
    }

    public void loadFromFile(String connectionName, String dbName, String queryName, String path) {
        this.path = path == null ? "" : path;
        String sanitizedQuery = sanitizeFileName(queryName);

        Path file = resolveQueryDir(connectionName, dbName, this.path).resolve(sanitizedQuery + ".sql");
        if (Files.exists(file)) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                setSqlText(content);
                this.savedSql = content;
                this.modified = false;
            } catch (IOException e) {
                System.err.println("加载查询文件失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void deleteQueryFile(String connectionName, String dbName, String queryName, String path) {
        String sanitizedQuery = sanitizeFileName(queryName);
        Path file = resolveQueryDir(connectionName, dbName, path).resolve(sanitizedQuery + ".sql");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("删除查询文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void cleanupQueryFile(String connectionName, String dbName, String queryName, String path) {
        String sanitizedQuery = sanitizeFileName(queryName);
        Path file = resolveQueryDir(connectionName, dbName, path).resolve(sanitizedQuery + ".sql");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("删除查询文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 列出查询目录下的查询名（.sql 文件，去掉扩展名） */
    public static List<String> listQueries(String connectionName, String dbName, String path) {
        Path dir = resolveQueryDir(connectionName, dbName, path);
        List<String> queries = new ArrayList<>();
        if (!Files.isDirectory(dir)) return queries;

        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".sql"))
                  .forEach(p -> {
                      String fileName = p.getFileName().toString();
                      queries.add(fileName.substring(0, fileName.length() - 4));
                  });
        } catch (IOException e) {
            System.err.println("加载查询列表失败: " + e.getMessage());
        }
        return queries;
    }

    /** 列出查询目录下的子目录名 */
    public static List<String> listQueryDirs(String connectionName, String dbName, String path) {
        Path dir = resolveQueryDir(connectionName, dbName, path);
        List<String> dirs = new ArrayList<>();
        if (!Files.isDirectory(dir)) return dirs;

        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                  .forEach(p -> dirs.add(p.getFileName().toString()));
        } catch (IOException e) {
            System.err.println("加载查询子目录失败: " + e.getMessage());
        }
        return dirs;
    }

    /** 递归删除查询目录（磁盘上的子目录及其所有内容） */
    public static void deleteQueryDir(String connectionName, String dbName, String path) {
        Path dir = resolveQueryDir(connectionName, dbName, path);
        if (!Files.isDirectory(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            System.err.println("删除查询目录失败: " + e.getMessage());
        }
    }

    // ==================== 数据库列表 ====================

    private void refreshDatabaseList() {
        ConnectionConfig config = connectionCombo.getValue();
        if (config == null) return;
        String currentDb = databaseCombo.getValue();
        databaseCombo.getItems().clear();
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, null);
            connLock.lock();
            try {
                try {
                    if (config.getPassword() == null) return;
                    List<String> databases = DatabaseService.getDatabases(config);
                    Platform.runLater(() -> {
                        databaseCombo.getItems().addAll(databases);
                        updateEditorDatabaseCompletions(databases);
                        if (currentDb != null && databases.contains(currentDb)) databaseCombo.setValue(currentDb);
                        else if (!databases.isEmpty()) databaseCombo.setValue(databases.get(0));
                    });
                } catch (Exception e) { /* 静默 */ }
            } finally {
                connLock.unlock();
            }
        }, "DB-RefreshDbList").start();
    }

    /** 后台加载当前库的表和字段，加载期间关键字和数据库名补全仍然可用。 */
    private void refreshCompletionMetadata() {
        if (!(editor instanceof RichTextSqlEditor rte)) return;
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        long version = ++completionLoadVersion;
        List<String> databases = new ArrayList<>(databaseCombo.getItems());
        rte.getPane().setMetadataCompletions(databases, List.of(), List.of());
        if (config == null || dbName == null || dbName.isBlank() || config.getPassword() == null) return;

        Thread loader = new Thread(() -> {
            Set<String> tables = new LinkedHashSet<>();
            Set<String> columns = new LinkedHashSet<>();
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
                tables.addAll(DatabaseService.getTables(config, dbName));
                for (String table : tables) {
                    for (Map<String, String> column : DatabaseService.getTableColumns(config, dbName, table)) {
                        String name = column.get("字段名");
                        if (name != null && !name.isBlank()) columns.add(name);
                    }
                }
            } catch (Exception e) {
                System.err.println("加载 SQL 自动补全元数据失败: " + e.getMessage());
            } finally {
                connLock.unlock();
            }
            Platform.runLater(() -> {
                if (version == completionLoadVersion
                        && config == connectionCombo.getValue()
                        && dbName.equals(databaseCombo.getValue())) {
                    rte.getPane().setMetadataCompletions(databases, tables, columns);
                }
            });
        }, "SQL-CompletionMetadata");
        loader.setDaemon(true);
        loader.start();
    }

    private void updateEditorDatabaseCompletions(List<String> databases) {
        if (editor instanceof RichTextSqlEditor rte) {
            rte.getPane().setMetadataCompletions(databases, List.of(), List.of());
        }
    }

    // ==================== SQL操作 ====================

    private void executeQuery() {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) {
            showInfo("请先选择连接和数据库");
            return;
        }
        String sql = getEffectiveSql();
        if (sql.isEmpty()) return;

        // 显示执行中状态
        resultTabPane.getTabs().clear();
        Tab loadingTab = new Tab("信息");
        Label loadingLabel = new Label("执行中...");
        loadingLabel.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
        loadingTab.setContent(loadingLabel);
        resultTabPane.getTabs().add(loadingTab);

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
                try {
                    MultiStatementResult multiResult = DatabaseService.executeMultiSqlQuery(config, dbName, sql, 1000);

                    // 收集剖析结果（对SELECT语句执行EXPLAIN）
                    List<TableRowData> explainResults = new java.util.ArrayList<>();
                    List<String> explainSqls = new java.util.ArrayList<>();
                    for (SqlStatementResult sr : multiResult.getResults()) {
                        if (sr.isSuccess() && sr.isSelect() && sr.isHasResultSet()) {
                            explainSqls.add(sr.getSql());
                            explainResults.add(DatabaseService.executeExplainQuery(config, dbName, sr.getSql()));
                        }
                    }

                    // 获取服务器状态
                    TableRowData statusResult = DatabaseService.executeStatusQuery(config, dbName);

                    Platform.runLater(() -> buildResultTabs(config, dbName, multiResult,
                            explainResults, explainSqls, statusResult));
                } catch (Exception e) {
                    Platform.runLater(() -> showInfo("执行失败: " + e.getMessage()));
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-ExecuteQuery").start();
    }

    private void explainQuery() {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) {
            showInfo("请先选择连接和数据库");
            return;
        }
        String sql = getEffectiveSql();
        if (sql.isEmpty()) return;

        resultTabPane.getTabs().clear();
        Tab loadingTab = new Tab("剖析");
        Label loadingLabel = new Label("执行解释...");
        loadingLabel.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
        loadingTab.setContent(loadingLabel);
        resultTabPane.getTabs().add(loadingTab);

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
                try {
                    List<String> statements = SqlSplitter.split(sql);
                    List<TableRowData> explainResults = new java.util.ArrayList<>();
                    List<String> explainSqls = new java.util.ArrayList<>();
                    for (String stmt : statements) {
                        if (SqlSplitter.isSelectStatement(stmt)) {
                            explainSqls.add(stmt);
                            explainResults.add(DatabaseService.executeExplainQuery(config, dbName, stmt));
                        }
                    }

                    Platform.runLater(() -> {
                        resultTabPane.getTabs().clear();
                        if (explainResults.isEmpty()) {
                            showInfo("没有可解释的SELECT语句");
                        } else {
                            resultTabPane.getTabs().add(buildExplainTab(explainResults, explainSqls));
                            resultTabPane.getSelectionModel().select(0);
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> showInfo("解释失败: " + e.getMessage()));
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-ExplainQuery").start();
    }

    private void beautifySql() {
        String sql = editor.getText().trim();
        if (sql.isEmpty()) return;
        editor.setText(formatSql(sql));
    }

    private void createQueryTool() {
        String sql = editor.getText().trim();
        if (sql.isEmpty()) return;
        editor.setText(formatSql(sql));
    }

    private String formatSql(String sql) {
        sql = sql.replaceAll("\\s+", " ").trim();
        String[] lineBreakBefore = {
                " SELECT ", " FROM ", " WHERE ", " INNER JOIN ", " LEFT JOIN ",
                " RIGHT JOIN ", " CROSS JOIN ", " FULL JOIN ", " ON ",
                " GROUP BY ", " ORDER BY ", " HAVING ", " LIMIT ", " OFFSET ",
                " UNION ", " INSERT INTO ", " VALUES ", " UPDATE ", " SET ",
                " DELETE FROM ", " CREATE TABLE ", " DROP TABLE ", " ALTER TABLE "
        };
        for (String keyword : lineBreakBefore) {
            String upper = keyword.toUpperCase();
            String lower = keyword.toLowerCase();
            sql = sql.replace(keyword, "\n" + keyword.trim() + "\n    ");
            sql = sql.replace(upper, "\n" + upper.trim() + "\n    ");
            sql = sql.replace(lower, "\n" + lower.trim() + "\n    ");
        }
        sql = sql.replaceAll("\n\\s*\n", "\n").trim();
        if (sql.startsWith("    ")) sql = sql.substring(4);
        return sql;
    }

    private String getEffectiveSql() {
        String sql = editor.getText().trim();
        if (sql.isEmpty()) return "";
        String selected = editor.getSelectedText();
        if (selected != null && !selected.trim().isEmpty()) sql = selected.trim();
        return sql;
    }

    // ==================== 结果标签页构建 ====================

    private void showInfo(String message) {
        resultTabPane.getTabs().clear();
        Tab tab = new Tab("信息");
        Label label = new Label(message);
        label.setStyle("-fx-text-fill: #c00; -fx-padding: 16;");
        label.setWrapText(true);
        tab.setContent(label);
        resultTabPane.getTabs().add(tab);
    }

    private void buildResultTabs(ConnectionConfig config,
                                  String databaseName,
                                  MultiStatementResult multiResult,
                                  List<TableRowData> explainResults,
                                  List<String> explainSqls,
                                  TableRowData statusResult) {
        resultTabPane.getTabs().clear();

        List<SqlStatementResult> selectResults = multiResult.getSelectResults();

        // 1. 信息标签
        resultTabPane.getTabs().add(buildInfoTab(multiResult));

        // 2. 结果标签（每个有结果集的语句一个）
        for (int i = 0; i < selectResults.size(); i++) {
            String tabName = selectResults.size() == 1 ? "结果" : "结果" + (i + 1);
            resultTabPane.getTabs().add(buildResultTab(tabName, selectResults.get(i), config, databaseName));
        }

        // 3. 剖析标签（有SELECT语句时生成）
        if (!explainResults.isEmpty()) {
            resultTabPane.getTabs().add(buildExplainTab(explainResults, explainSqls));
        }

        // 4. 状态标签
        resultTabPane.getTabs().add(buildStatusTab(statusResult));

        // 默认选中策略
        if (multiResult.getFailCount() > 0) {
            resultTabPane.getSelectionModel().select(0); // 有错误选信息
        } else if (!selectResults.isEmpty()) {
            resultTabPane.getSelectionModel().select(1); // 选第一个结果
        } else {
            resultTabPane.getSelectionModel().select(0); // 选信息
        }
    }

    private Tab buildInfoTab(MultiStatementResult multiResult) {
        Tab tab = new Tab("信息");
        TextArea infoArea = new TextArea();
        infoArea.setEditable(false);
        infoArea.setWrapText(true);
        infoArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; " +
                "-fx-control-inner-background: white; -fx-padding: 8; -fx-background-color: white;");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < multiResult.getResults().size(); i++) {
            SqlStatementResult sr = multiResult.getResults().get(i);
            if (i > 0) sb.append("\n");

            // 显示SQL文本
            sb.append(sr.getSql()).append("\n");

            // 显示执行状态
            if (sr.isSuccess()) {
                sb.append(" > OK\n");
                sb.append(" > 时间: ").append(String.format("%.3fs", sr.getQueryTime() / 1000.0)).append("\n");
                if (sr.isHasResultSet() && sr.getResultData() != null) {
                    sb.append(" > 行数: ").append(sr.getResultData().getTotalCount()).append("\n");
                } else if (!sr.isHasResultSet()) {
                    int updateCount = sr.getUpdateCount();
                    if (updateCount >= 0) {
                        sb.append(" > 影响: ").append(updateCount).append(" 行\n");
                    }
                }
            } else {
                sb.append(" > 错误\n");
                sb.append(" > ").append(sr.getErrorMessage()).append("\n");
            }
        }

        // 汇总
        sb.append("\n--- 汇总 ---\n");
        sb.append("总耗时: ").append(String.format("%.3fs", multiResult.getTotalTime() / 1000.0)).append("\n");
        sb.append("成功: ").append(multiResult.getSuccessCount());
        sb.append("  失败: ").append(multiResult.getFailCount());

        infoArea.setText(sb.toString());
        tab.setContent(infoArea);
        return tab;
    }

    private Tab buildResultTab(String tabName, SqlStatementResult stmtResult,
                               ConnectionConfig config, String databaseName) {
        Tab tab = new Tab(tabName);
        if (stmtResult.getResultData() != null) {
            tab.setContent(createTableView(stmtResult.getResultData(), stmtResult.getSourceTableName(),
                    config, databaseName));
        } else {
            Label label = new Label("无结果集");
            label.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
            tab.setContent(label);
        }
        return tab;
    }

    private Tab buildExplainTab(List<TableRowData> explainResults, List<String> explainSqls) {
        Tab tab = new Tab("剖析");
        if (explainResults.size() == 1) {
            tab.setContent(createTableView(explainResults.get(0)));
        } else {
            VBox vbox = new VBox(8);
            vbox.setStyle("-fx-padding: 4; -fx-background-color: white;");
            for (int i = 0; i < explainResults.size(); i++) {
                if (i > 0) {
                    Separator sep = new Separator();
                    vbox.getChildren().add(sep);
                }
                // 显示对应的SQL片段
                String sqlSnippet = explainSqls.get(i);
                if (sqlSnippet.length() > 80) sqlSnippet = sqlSnippet.substring(0, 80) + "...";
                Label sqlLabel = new Label("SQL: " + sqlSnippet);
                sqlLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666; -fx-padding: 2 0;");
                vbox.getChildren().add(sqlLabel);

                Node tableView = createTableView(explainResults.get(i));
                VBox.setVgrow(tableView, Priority.ALWAYS);
                vbox.getChildren().add(tableView);
            }
            tab.setContent(vbox);
        }
        return tab;
    }

    private Tab buildStatusTab(TableRowData statusResult) {
        Tab tab = new Tab("状态");
        if (statusResult != null) {
            tab.setContent(createTableView(statusResult));
        } else {
            Label label = new Label("无法获取状态信息");
            label.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
            tab.setContent(label);
        }
        return tab;
    }

    /**
     * 从TableRowData创建TableView（复用逻辑）
     */
    private Node createTableView(TableRowData result) {
        return createTableView(result, null, null, null);
    }

    /**
     * 从TableRowData创建TableView，可指定源表名以支持右键删除行
     * @param result 表格数据
     * @param sourceTableName 源表名，若非null且有主键则启用右键删除
     */
    private Node createTableView(TableRowData result, String sourceTableName,
                                 ConnectionConfig resultConfig, String resultDatabase) {
        TableView<ObservableList<String>> tableView = new TableView<>();
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        // 固定行高（读取全局配置 tableFontSize 派生）：避免内容多的行把整行撑得过高
        int rowHeight = globalConfig.getTableFontSize() + 18;
        tableView.setFixedCellSize(rowHeight);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
        tableView.setPlaceholder(new Label("无数据"));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tableView.getSelectionModel().setCellSelectionEnabled(true);
        // 布局后移除内部节点的默认padding/border，消除左侧间隔
        tableView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                stripPaddingRecursive(tableView);
            }
        });

        List<String> columns = result.getColumnNames();
        List<Integer> columnTypes = result.getColumnTypes() != null
                ? new ArrayList<>(result.getColumnTypes())
                : new ArrayList<>();
        QueryResultEditContext editContext = null;
        if (sourceTableName != null && resultConfig != null && resultDatabase != null) {
            QueryTableTarget target = resolveQueryTableTarget(sourceTableName, resultConfig, resultDatabase);
            if (target != null) {
                editContext = new QueryResultEditContext(tableView, resultConfig, target, columns);
            }
        }
        final QueryResultEditContext finalEditContext = editContext;

        // 创建行选择器列：选中行显示黑色实心三角箭头
        TableColumn<ObservableList<String>, String> selectorCol = new TableColumn<>();
        selectorCol.setPrefWidth(15);
        selectorCol.setMaxWidth(15);
        selectorCol.setMinWidth(15);
        selectorCol.setSortable(false);
        selectorCol.setReorderable(false);
        selectorCol.setStyle("-fx-alignment: CENTER;");
        selectorCol.setUserData(ROW_SELECTOR_COL);
        selectorCol.setCellFactory(col -> new TableCell<>() {
            private final Polygon arrow = new Polygon(0, -0.5, 5, 4.5, 0, 9.5);
            private javafx.beans.InvalidationListener selectionListener;

            {
                arrow.setFill(Color.BLACK);
                setGraphic(arrow);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER);
                arrow.setVisible(false);
                setStyle("-fx-border-color: transparent #BEBEBC transparent #BEBEBC; -fx-border-width: 0 1 0 1;");
                // 行选择器列拖拽多行选中的起始行（-1 表示未从行选择器发起拖拽）
                final int[] dragStart = RowSelectorDragSelection.install(tableView, this);
                addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        int row = getTableRow().getIndex();
                        if (event.isControlDown()) {
                            dragStart[0] = -1;
                            if (tableView.getSelectionModel().isSelected(row)) {
                                tableView.getSelectionModel().clearSelection(row);
                            } else {
                                tableView.getSelectionModel().select(row);
                            }
                        } else if (event.isShiftDown()) {
                            dragStart[0] = -1;
                            int anchor = tableView.getSelectionModel().getFocusedIndex();
                            if (anchor >= 0) {
                                int start = Math.min(row, anchor);
                                int end = Math.max(row, anchor);
                                tableView.getSelectionModel().clearSelection();
                                tableView.getSelectionModel().selectRange(start, end + 1);
                            } else {
                                tableView.getSelectionModel().clearSelection();
                                tableView.getSelectionModel().select(row);
                            }
                        } else {
                            tableView.getSelectionModel().clearSelection();
                            tableView.getSelectionModel().select(row);
                            dragStart[0] = row;
                        }
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                if (selectionListener != null) {
                    tableView.getSelectionModel().getSelectedItems().removeListener(selectionListener);
                    selectionListener = null;
                }
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    arrow.setVisible(false);
                    setStyle("-fx-border-color: transparent; -fx-border-width: 0;");
                    return;
                }
                setStyle("-fx-border-color: transparent #BEBEBC #BEBEBC #BEBEBC; -fx-border-width: 0 1 1 1;");
                arrow.setVisible(tableView.getSelectionModel().getSelectedIndices().contains(getTableRow().getIndex()));
                selectionListener = obs -> {
                    if (getTableRow() != null) {
                        arrow.setVisible(tableView.getSelectionModel().getSelectedIndices().contains(getTableRow().getIndex()));
                    }
                };
                tableView.getSelectionModel().getSelectedItems().addListener(selectionListener);
            }
        });
        tableView.getColumns().add(selectorCol);
        for (int i = 0; i < columns.size(); i++) {
            final int colIndex = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(columns.get(i));
            // 根据表头文字长度动态设置列宽
            int headerLen = columns.get(i).length();
            col.setPrefWidth(Math.max(headerLen * 8 + 16, 60));
            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                return new javafx.beans.property.SimpleStringProperty(colIndex < row.size() ? row.get(colIndex) : "");
            });
            final int colType = (colIndex < columnTypes.size()) ? columnTypes.get(colIndex) : java.sql.Types.OTHER;
            col.setCellFactory(tc -> new QueryResultEditableTableCell(colType));
            if (finalEditContext != null) {
                col.setEditable(false);
                col.setOnEditCommit(event -> {
                    if (!finalEditContext.editableColumnIndexes.contains(colIndex)) return;
                    ObservableList<String> row = event.getRowValue();
                    String newValue = event.getNewValue() == null ? "" : event.getNewValue();
                    if (colIndex < row.size() && !Objects.equals(row.get(colIndex), newValue)) {
                        row.set(colIndex, newValue);
                        updateQueryResultButtons(finalEditContext);
                    }
                });
            }
            tableView.getColumns().add(col);
        }
        if (result.getRows() != null) {
            tableView.getItems().addAll(result.getRows());
        }
        if (finalEditContext != null) {
            for (ObservableList<String> row : tableView.getItems()) {
                finalEditContext.originalRows.put(row, javafx.collections.FXCollections.observableArrayList(row));
            }
        }

        ScrollPane scrollPane = new ScrollPane(tableView);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToHeight(true);
        scrollPane.setFitToWidth(false);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // TableView宽度跟随视口（让垂直滚动条位于面板最右，右侧空白属于表格）
        tableView.minWidthProperty().bind(scrollPane.widthProperty());
        // 鼠标拖拽选中多个cell
        setupDragSelection(tableView);
        // Ctrl+C 复制选中cell
        setupKeyboardShortcuts(tableView, finalEditContext);
        setupQueryResultContextMenu(tableView, finalEditContext, sourceTableName);

        if (finalEditContext == null) return scrollPane;

        Button saveButton = new Button("保存修改");
        saveButton.getStyleClass().add("toolbar-button");
        saveButton.setOnAction(e -> saveQueryResultChanges(finalEditContext));
        Button deleteButton = new Button("删除");
        deleteButton.getStyleClass().add("toolbar-button");
        deleteButton.setOnAction(e -> handleQueryResultDeleteRows(finalEditContext));
        Label editHint = new Label("双击单元格编辑，Ctrl+V 粘贴修改");
        editHint.setStyle("-fx-text-fill: #777; -fx-padding: 0 0 0 6;");
        HBox editToolbar = new HBox(6, saveButton, deleteButton, editHint);
        editToolbar.setAlignment(Pos.CENTER_LEFT);
        editToolbar.setPadding(new Insets(4, 8, 4, 8));
        editToolbar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        editToolbar.setVisible(false);
        editToolbar.setManaged(false);
        finalEditContext.saveButton = saveButton;
        finalEditContext.deleteButton = deleteButton;
        finalEditContext.toolbar = editToolbar;
        tableView.getSelectionModel().getSelectedCells().addListener(
                (javafx.beans.InvalidationListener) observable -> updateQueryResultButtons(finalEditContext));

        BorderPane wrapper = new BorderPane(scrollPane);
        wrapper.setTop(editToolbar);
        loadQueryResultEditMetadata(finalEditContext);
        return wrapper;
    }

    /**
     * 查询结果可编辑单元格：非编辑时用 Text 显示，编辑时支持文本编辑/日期时间弹层。
     * 与打开表视图的编辑行为保持一致。
     */
    private class QueryResultEditableTableCell extends TableCell<ObservableList<String>, String> {
        private TextField textField;
        private boolean escapePressed = false;
        private final int sqlType;
        private final javafx.scene.text.Text displayText;
        private String editingOriginalValue = "";

        QueryResultEditableTableCell(int sqlType) {
            this.sqlType = sqlType;
            getStyleClass().add("data-cell");
            setAlignment(Pos.CENTER_LEFT);
            displayText = new javafx.scene.text.Text();
            displayText.setTextOrigin(javafx.geometry.VPos.CENTER);
            displayText.boundsTypeProperty().set(javafx.scene.text.TextBoundsType.VISUAL);
            displayText.fontProperty().bind(fontProperty());
            displayText.fillProperty().bind(textFillProperty());
        }

        private boolean isDateColumn() {
            return sqlType == java.sql.Types.DATE;
        }

        private boolean isTimeColumn() {
            return sqlType == java.sql.Types.TIME || sqlType == java.sql.Types.TIME_WITH_TIMEZONE;
        }

        private boolean isDateTimeColumn() {
            return sqlType == java.sql.Types.TIMESTAMP || sqlType == java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
        }

        private boolean isTemporalColumn() {
            return isDateColumn() || isTimeColumn() || isDateTimeColumn();
        }

        @Override
        public void startEdit() {
            escapePressed = false;
            super.startEdit();
            editingOriginalValue = getItem();
            if (textField == null) {
                createTextField();
            }
            setText(null);
            if (isTemporalColumn()) {
                setGraphic(createTemporalEditor());
            } else {
                setGraphic(textField);
            }
            textField.setText(toEditorText(editingOriginalValue));
            textField.selectAll();
            textField.requestFocus();
            setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 2; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: center-left;");
        }

        @Override
        public void cancelEdit() {
            if (!escapePressed && textField != null) {
                String currentValue = getItem() != null ? getItem() : "";
                String newValue = normalizeEditValueForModel(textField.getText(), currentValue);
                if (!newValue.equals(currentValue)) {
                    updateCellData(newValue);
                }
            }
            escapePressed = false;
            super.cancelEdit();
            String displayValue = getCellData();
            updateDisplayText(displayValue);
            setGraphic(displayText);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setText(null);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-border-color: transparent; -fx-padding: 0; -fx-alignment: center-left;");
            } else {
            if (isEditing()) {
                if (textField != null) {
                    textField.setText(toEditorText(getItem()));
                }
                setText(null);
                setGraphic(textField);
                setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 2; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: center-left;");
            } else {
                    updateDisplayText(item);
                    setGraphic(displayText);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    setText(null);
                }
            }
        }

        private void updateCellData(String newValue) {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return;
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return;
            int tableViewColIndex = getTableView().getColumns().indexOf(getTableColumn());
            int dataColIndex = tableViewColIndex - 1;
            if (dataColIndex >= 0 && dataColIndex < row.size()) {
                row.set(dataColIndex, newValue);
            }
        }

        private String getCellData() {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return getItem();
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return getItem();
            int tableViewColIndex = getTableView().getColumns().indexOf(getTableColumn());
            int dataColIndex = tableViewColIndex - 1;
            if (dataColIndex >= 0 && dataColIndex < row.size()) {
                return row.get(dataColIndex);
            }
            return getItem();
        }

        private void createTextField() {
            textField = new TextField();
            textField.setMinWidth(this.getWidth() - this.getGraphicTextGap() * 2);
            textField.setStyle("-fx-background-color: white; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0 4; -fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-text-fill: black;");
            textField.setOnKeyPressed(event -> escapePressed = (event.getCode() == javafx.scene.input.KeyCode.ESCAPE));
            textField.setOnAction(e -> commitEdit(normalizeEditValueForModel(textField.getText(), editingOriginalValue)));
            textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused) {
                    commitEdit(normalizeEditValueForModel(textField.getText(), editingOriginalValue));
                }
            });
        }

        private void updateDisplayText(String value) {
            displayText.setText(value == null ? "NULL" : value);
            setStyle("-fx-text-fill: " + (value == null ? "#999999" : "-fx-text-base-color")
                    + "; -fx-alignment: center-left;");
        }

        private String toEditorText(String raw) {
            return raw != null ? raw : "";
        }

        private String normalizeEditValueForModel(String editedValue, String originalValue) {
            String edited = editedValue != null ? editedValue : "";
            if (originalValue == null && edited.isEmpty()) return null;
            return edited;
        }

        private javafx.scene.layout.StackPane createTemporalEditor() {
            Button pickerBtn = new Button();
            pickerBtn.setGraphic(createCalendarIcon());
            pickerBtn.setTooltip(new Tooltip("选择" + buttonLabelForTemporal()));
            pickerBtn.setFocusTraversable(false);
            pickerBtn.setPrefWidth(26);
            pickerBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent transparent transparent #ccc; -fx-border-width: 0 0 0 1; -fx-background-radius: 0; -fx-padding: 0; -fx-cursor: hand;");
            pickerBtn.setOnAction(e -> showTemporalPopup());
            textField.setMinWidth(0);
            textField.setMaxWidth(Double.MAX_VALUE);
            textField.setPadding(new javafx.geometry.Insets(0, 30, 0, 4));
            javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(textField, pickerBtn);
            stack.setStyle("-fx-background-color: #fff;");
            javafx.scene.layout.StackPane.setAlignment(pickerBtn, Pos.CENTER_RIGHT);
            javafx.scene.layout.StackPane.setAlignment(textField, Pos.CENTER_LEFT);
            return stack;
        }

        private javafx.scene.shape.SVGPath createCalendarIcon() {
            javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
            icon.setContent("M20 3h-1V1h-2v2H7V1H5v2H4c-1.11 0-1.99.9-1.99 2L2 19c0 1.1.88 2 2 2h16c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H4V8h16v11z");
            icon.setFill(javafx.scene.paint.Color.valueOf("#555"));
            icon.setScaleX(0.62);
            icon.setScaleY(0.62);
            return icon;
        }

        private String buttonLabelForTemporal() {
            if (isDateColumn()) return "日期";
            if (isTimeColumn()) return "时间";
            return "日期时间";
        }

        private void showTemporalPopup() {
            javafx.stage.Popup popup = new javafx.stage.Popup();
            javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(8);
            content.setStyle("-fx-background-color: #fff; -fx-border-color: #999; -fx-border-width: 1; -fx-padding: 10; -fx-background-radius: 4; -fx-font-size: 12px;");
            content.setPrefWidth(260);

            java.time.LocalDate initDate = java.time.LocalDate.now();
            int initH = 0, initM = 0, initS = 0;
            String cur = textField.getText();
            if (cur != null && !cur.trim().isEmpty() && !"NULL".equals(cur)) {
                try {
                    if (isDateColumn()) {
                        initDate = java.time.LocalDate.parse(cur.trim());
                    } else if (isTimeColumn()) {
                        String[] p = cur.trim().split(":");
                        if (p.length >= 1) initH = clamp(parseSafeInt(p[0]), 0, 23);
                        if (p.length >= 2) initM = clamp(parseSafeInt(p[1]), 0, 59);
                        if (p.length >= 3) initS = clamp(parseSafeInt(p[2]), 0, 59);
                    } else if (isDateTimeColumn()) {
                        java.time.LocalDateTime ldt = parseLenientDateTime(cur.trim());
                        if (ldt != null) {
                            initDate = ldt.toLocalDate();
                            initH = ldt.getHour();
                            initM = ldt.getMinute();
                            initS = ldt.getSecond();
                        }
                    }
                } catch (Exception ignore) {
                }
            }

            final java.time.LocalDate[] selectedDate = { isTimeColumn() ? null : initDate };
            Spinner<Integer> hourSp = null, minSp = null, secSp = null;
            if (isDateColumn() || isDateTimeColumn()) {
                content.getChildren().add(buildInlineCalendar(initDate, selectedDate));
            }
            if (isTimeColumn() || isDateTimeColumn()) {
                hourSp = new Spinner<>(0, 23, initH);
                minSp = new Spinner<>(0, 59, initM);
                secSp = new Spinner<>(0, 59, initS);
                for (Spinner<Integer> sp : java.util.List.of(hourSp, minSp, secSp)) {
                    sp.setEditable(true);
                    sp.setPrefWidth(56);
                    sp.setPrefHeight(30);
                    sp.setMinHeight(30);
                    sp.getEditor().setStyle("-fx-padding: 0 2; -fx-alignment: CENTER; -fx-font-size: 13px;");
                }
                HBox timeBox = new HBox(4, new Label("时"), hourSp, new Label("分"), minSp, new Label("秒"), secSp);
                timeBox.setAlignment(Pos.CENTER_LEFT);
                content.getChildren().add(timeBox);
            }

            Button applyBtn = new Button("确定");
            applyBtn.setStyle("-fx-background-color: #3592CB; -fx-text-fill: white; -fx-cursor: hand;");
            final Spinner<Integer> fH = hourSp;
            final Spinner<Integer> fM = minSp;
            final Spinner<Integer> fS = secSp;
            applyBtn.setOnAction(e -> {
                String result = formatTemporal(selectedDate[0], fH, fM, fS);
                if (!result.isEmpty()) {
                    textField.setText(result);
                    textField.positionCaret(result.length());
                }
                popup.hide();
                textField.requestFocus();
            });
            HBox btnBox = new HBox(applyBtn);
            btnBox.setAlignment(Pos.CENTER_RIGHT);
            content.getChildren().add(btnBox);

            popup.getContent().add(content);
            popup.setAutoHide(true);
            popup.setHideOnEscape(true);
            javafx.geometry.Point2D anchor = textField.localToScreen(0, textField.getHeight());
            if (anchor != null) {
                popup.show(textField, anchor.getX(), anchor.getY() + 5);
            } else {
                popup.show(textField.getScene().getWindow());
            }
        }

        private javafx.scene.layout.VBox buildInlineCalendar(java.time.LocalDate initial, final java.time.LocalDate[] selected) {
            final java.time.LocalDate[] cursor = { initial.withDayOfMonth(1) };
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(4);
            box.setStyle("-fx-background-color: white; -fx-alignment: center;");
            Label monthLabel = new Label();
            monthLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
            Button prev = new Button("<");
            Button next = new Button(">");
            for (Button b : java.util.List.of(prev, next)) {
                b.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-cursor: hand; -fx-padding: 0 6;");
            }
            HBox header = new HBox(8, prev, monthLabel, next);
            header.setAlignment(Pos.CENTER);

            javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
            grid.setHgap(2);
            grid.setVgap(2);
            grid.setAlignment(Pos.CENTER);

            final Runnable[] render = new Runnable[1];
            render[0] = () -> {
                monthLabel.setText(cursor[0].getYear() + "-" + String.format("%02d", cursor[0].getMonthValue()));
                grid.getChildren().clear();
                String[] hs = {"日", "一", "二", "三", "四", "五", "六"};
                for (int i = 0; i < 7; i++) {
                    Label l = new Label(hs[i]);
                    l.setPrefWidth(30);
                    l.setAlignment(Pos.CENTER);
                    l.setStyle("-fx-text-fill: #888;");
                    grid.add(l, i, 0);
                }
                int startDay = cursor[0].getDayOfWeek().getValue() % 7;
                int days = cursor[0].lengthOfMonth();
                for (int d = 1; d <= days; d++) {
                    final java.time.LocalDate date = cursor[0].withDayOfMonth(d);
                    Button b = new Button(String.valueOf(d));
                    b.setPrefSize(30, 24);
                    boolean isSel = date.equals(selected[0]);
                    b.setStyle(isSel
                            ? "-fx-background-color: #3592CB; -fx-text-fill: white; -fx-border-color: #3592CB; -fx-cursor: hand; -fx-font-size: 11px;"
                            : "-fx-background-color: white; -fx-text-fill: #333; -fx-border-color: #e0e0e0; -fx-cursor: hand; -fx-font-size: 11px;");
                    b.setOnAction(ev -> {
                        selected[0] = date;
                        render[0].run();
                    });
                    grid.add(b, (startDay + d - 1) % 7, (startDay + d - 1) / 7 + 1);
                }
            };
            prev.setOnAction(e -> {
                cursor[0] = cursor[0].minusMonths(1);
                render[0].run();
            });
            next.setOnAction(e -> {
                cursor[0] = cursor[0].plusMonths(1);
                render[0].run();
            });
            render[0].run();
            box.getChildren().addAll(header, grid);
            return box;
        }

        private String formatTemporal(java.time.LocalDate date, Spinner<Integer> h, Spinner<Integer> m, Spinner<Integer> s) {
            if (isDateColumn()) {
                return date != null ? date.toString() : "";
            }
            if (isTimeColumn()) {
                return String.format("%02d:%02d:%02d", h.getValue(), m.getValue(), s.getValue());
            }
            String ds = date != null ? date.toString() : "";
            return ds + " " + String.format("%02d:%02d:%02d", h.getValue(), m.getValue(), s.getValue());
        }

        private int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }

        private int parseSafeInt(String s) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (Exception e) {
                return 0;
            }
        }

        private java.time.LocalDateTime parseLenientDateTime(String s) {
            try {
                return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
            }
            try {
                return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"));
            } catch (Exception e) {
            }
            try {
                return java.time.LocalDateTime.parse(s.replace(' ', 'T'));
            } catch (Exception e) {
            }
            return null;
        }
    }

    /**
     * 查询结果只有在能定位单一源表、结果包含完整主键时才开放修改和删除。
     */
    private void loadQueryResultEditMetadata(QueryResultEditContext context) {
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(
                    context.config, context.target.databaseName);
            connLock.lock();
            try {
                try {
                    List<String> pks = DatabaseService.getPrimaryKeys(context.config,
                            context.target.databaseName, context.target.schemaName, context.target.tableName);
                    if (pks.isEmpty() || !containsAllColumns(context.columnNames, pks)) return;

                    List<Map<String, String>> tableColumns = DatabaseService.getTableColumns(context.config,
                            context.target.databaseName, context.target.schemaName, context.target.tableName);
                    Set<String> actualColumns = new HashSet<>();
                    for (Map<String, String> column : tableColumns) {
                        String name = column.get("字段名");
                        if (name != null) actualColumns.add(name.toLowerCase(java.util.Locale.ROOT));
                    }
                    Set<Integer> editableIndexes = new LinkedHashSet<>();
                    for (int i = 0; i < context.columnNames.size(); i++) {
                        if (actualColumns.contains(context.columnNames.get(i).toLowerCase(java.util.Locale.ROOT))) {
                            editableIndexes.add(i);
                        }
                    }
                    if (editableIndexes.isEmpty()) return;

                    Platform.runLater(() -> {
                        context.primaryKeyColumns = List.copyOf(pks);
                        context.editableColumnIndexes.clear();
                        context.editableColumnIndexes.addAll(editableIndexes);
                        context.tableView.setEditable(true);
                        for (int i = 0; i < context.columnNames.size(); i++) {
                            context.tableView.getColumns().get(i + 1).setEditable(editableIndexes.contains(i));
                        }
                        context.toolbar.setManaged(true);
                        context.toolbar.setVisible(true);
                        updateQueryResultButtons(context);
                    });
                } catch (Exception e) {
                    // 无法可靠定位源表时保持只读，复制仍然可用。
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadPrimaryKeys-Query").start();
    }

    /**
     * 查询结果右键菜单：复制始终可用；粘贴、保存和删除仅在主键校验通过后启用。
     */
    private void setupQueryResultContextMenu(TableView<ObservableList<String>> tableView,
                                             QueryResultEditContext context,
                                             String sourceTableName) {
        ContextMenu menu = new ContextMenu();
        menu.setAutoHide(true);
        menu.setHideOnEscape(true);
        menu.setOnHidden(e -> {
            if (activeResultContextMenu == menu) activeResultContextMenu = null;
        });
        MenuItem setEmptyItem = new MenuItem("设置为空字符串");
        setEmptyItem.setOnAction(e -> {
            if (context != null) setQueryResultCellsToEmptyString(context);
        });
        MenuItem setNullItem = new MenuItem("设置为 NULL");
        setNullItem.setOnAction(e -> {
            if (context != null) setQueryResultCellsToNull(context);
        });
        MenuItem deleteItem = new MenuItem("删除 记录");
        deleteItem.setStyle("-fx-font-weight: bold;");
        deleteItem.setOnAction(e -> {
            if (context != null) handleQueryResultDeleteRows(context);
        });
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> handleCopySelectedCells(tableView));
        Menu copyAsMenu = TableCellContextMenuUtils.createCopyAsMenu(
                tableView, 1, () -> sourceTableName, java.util.List::of);
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> {
            if (context != null) pasteIntoQueryResult(context);
        });
        MenuItem saveAsItem = TableCellContextMenuUtils.createSaveDataAsItem(tableView, 1);
        Menu sortMenu = TableCellContextMenuUtils.createSortMenu(tableView, 1);
        Menu filterMenu = TableCellContextMenuUtils.createFilterMenu(tableView, 1);
        MenuItem clearSortFilterItem = new MenuItem("移除全部排序及筛选");
        clearSortFilterItem.setOnAction(e -> TableCellContextMenuUtils.clearSortAndFilter(tableView));
        Menu displayMenu = TableCellContextMenuUtils.createDisplayMenu(tableView, 1);
        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> tableView.refresh());

        menu.getItems().setAll(
                setEmptyItem,
                setNullItem,
                new SeparatorMenuItem(),
                deleteItem,
                copyItem,
                copyAsMenu,
                pasteItem,
                saveAsItem,
                new SeparatorMenuItem(),
                sortMenu,
                filterMenu,
                clearSortFilterItem,
                new SeparatorMenuItem(),
                displayMenu,
                refreshItem
        );
        tableView.setOnContextMenuRequested(event -> {
            Node target = event.getPickResult().getIntersectedNode();
            while (target != null && target != tableView) {
                if (target.getStyleClass().contains("column-header")
                        || target.getStyleClass().contains("column-header-background")
                        || target.getStyleClass().contains("nested-column-header")) {
                    event.consume();
                    return;
                }
                target = target.getParent();
            }
            int cellCount = tableView.getSelectionModel().getSelectedCells().size();
            int editableCellCount = context == null ? 0 : countEditableQueryResultSelectedCells(context);
            copyItem.setDisable(cellCount == 0);
            copyAsMenu.setDisable(cellCount == 0);
            boolean editable = context != null && context.isEditable();
            setEmptyItem.setDisable(!editable || editableCellCount == 0);
            setNullItem.setDisable(!editable || editableCellCount == 0);
            pasteItem.setDisable(!editable
                    || !javafx.scene.input.Clipboard.getSystemClipboard().hasString());
            int rowCount = (int) tableView.getSelectionModel().getSelectedItems().stream().distinct().count();
            deleteItem.setDisable(!editable || context.saving || rowCount == 0);
            if (activeResultContextMenu != null && activeResultContextMenu != menu) {
                activeResultContextMenu.hide();
            }
            activeResultContextMenu = menu;
            menu.show(tableView, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    /**
     * 将查询结果中当前选中的可编辑数据单元格设置为空字符串。
     */
    private void setQueryResultCellsToEmptyString(QueryResultEditContext context) {
        if (context == null || !context.isEditable()) return;
        for (TablePosition<ObservableList<String>, ?> position : context.tableView.getSelectionModel().getSelectedCells()) {
            int tableColumnIndex = context.tableView.getColumns().indexOf(position.getTableColumn());
            int dataColumnIndex = tableColumnIndex - 1;
            int rowIndex = position.getRow();
            if (position.getTableColumn() == null
                    || rowIndex < 0
                    || rowIndex >= context.tableView.getItems().size()
                    || dataColumnIndex < 0
                    || !context.editableColumnIndexes.contains(dataColumnIndex)) {
                continue;
            }
            ObservableList<String> row = context.tableView.getItems().get(rowIndex);
            if (dataColumnIndex < row.size()) row.set(dataColumnIndex, "");
        }
        context.tableView.refresh();
        updateQueryResultButtons(context);
    }

    /**
     * 将查询结果中当前选中的可编辑数据单元格设置为 NULL。
     */
    private void setQueryResultCellsToNull(QueryResultEditContext context) {
        if (context == null || !context.isEditable()) return;
        for (TablePosition<ObservableList<String>, ?> position : context.tableView.getSelectionModel().getSelectedCells()) {
            int tableColumnIndex = context.tableView.getColumns().indexOf(position.getTableColumn());
            int dataColumnIndex = tableColumnIndex - 1;
            int rowIndex = position.getRow();
            if (position.getTableColumn() == null
                    || rowIndex < 0
                    || rowIndex >= context.tableView.getItems().size()
                    || dataColumnIndex < 0
                    || !context.editableColumnIndexes.contains(dataColumnIndex)) {
                continue;
            }
            ObservableList<String> row = context.tableView.getItems().get(rowIndex);
            if (dataColumnIndex >= row.size()) continue;
            row.set(dataColumnIndex, null);
        }
        context.tableView.refresh();
        updateQueryResultButtons(context);
    }

    private int countEditableQueryResultSelectedCells(QueryResultEditContext context) {
        if (context == null) return 0;
        int count = 0;
        for (TablePosition<ObservableList<String>, ?> position : context.tableView.getSelectionModel().getSelectedCells()) {
            if (position.getTableColumn() == null) continue;
            int tableColumnIndex = context.tableView.getColumns().indexOf(position.getTableColumn());
            int dataColumnIndex = tableColumnIndex - 1;
            if (position.getRow() >= 0 && dataColumnIndex >= 0
                    && context.editableColumnIndexes.contains(dataColumnIndex)) {
                count++;
            }
        }
        return count;
    }

    /** 保存查询结果中通过编辑或粘贴产生的修改。 */
    private void saveQueryResultChanges(QueryResultEditContext context) {
        if (!context.isEditable() || context.saving) return;

        List<ObservableList<String>> uiRows = new ArrayList<>();
        List<ObservableList<String>> currentRows = new ArrayList<>();
        List<ObservableList<String>> originalRows = new ArrayList<>();
        List<Set<Integer>> modifiedColumns = new ArrayList<>();
        for (ObservableList<String> row : context.tableView.getItems()) {
            ObservableList<String> original = context.originalRows.get(row);
            if (original == null) continue;
            Set<Integer> changed = new LinkedHashSet<>();
            for (int index : context.editableColumnIndexes) {
                String currentValue = index < row.size() ? row.get(index) : "";
                String originalValue = index < original.size() ? original.get(index) : "";
                if (!Objects.equals(currentValue, originalValue)) changed.add(index);
            }
            if (!changed.isEmpty()) {
                uiRows.add(row);
                currentRows.add(javafx.collections.FXCollections.observableArrayList(row));
                originalRows.add(javafx.collections.FXCollections.observableArrayList(original));
                modifiedColumns.add(changed);
            }
        }
        if (currentRows.isEmpty()) return;

        context.saving = true;
        context.saveButton.setDisable(true);
        context.saveButton.setText("保存中...");
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(
                    context.config, context.target.databaseName);
            connLock.lock();
            try {
                try {
                    DatabaseService.updateRows(context.config, context.target.databaseName,
                            context.target.schemaName, context.target.tableName,
                            context.primaryKeyColumns, context.columnNames,
                            currentRows, originalRows, modifiedColumns);
                    Platform.runLater(() -> {
                        for (int i = 0; i < uiRows.size(); i++) {
                            context.originalRows.put(uiRows.get(i),
                                    javafx.collections.FXCollections.observableArrayList(currentRows.get(i)));
                        }
                        context.tableView.refresh();
                        context.saving = false;
                        context.saveButton.setText("保存修改");
                        updateQueryResultButtons(context);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        context.saving = false;
                        context.saveButton.setText("保存修改");
                        updateQueryResultButtons(context);
                        showQueryResultError("保存失败", "保存修改失败: " + e.getMessage());
                    });
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-SaveQueryResult").start();
    }

    /** 处理查询结果表格中的行删除。 */
    private void handleQueryResultDeleteRows(QueryResultEditContext context) {
        if (!context.isEditable() || context.saving) return;
        TableView<ObservableList<String>> tableView = context.tableView;

        List<ObservableList<String>> selectedRows = tableView.getSelectionModel().getSelectedItems()
                .stream().distinct().toList();
        if (selectedRows.isEmpty()) return;

        int count = selectedRows.size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除行");
        confirm.setHeaderText(null);
        confirm.setContentText("确定要从表 " + context.target.displayName() + " 中删除选中的 "
                + count + " 行吗？此操作不可撤销！");
        DialogPositionUtil.centerOnOwner(confirm, this);
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;

            List<ObservableList<String>> keysToDelete = new ArrayList<>();
            for (ObservableList<String> row : selectedRows) {
                ObservableList<String> original = context.originalRows.get(row);
                keysToDelete.add(javafx.collections.FXCollections.observableArrayList(
                        original != null ? original : row));
            }

            new Thread(() -> {
                java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(
                        context.config, context.target.databaseName);
                connLock.lock();
                try {
                    try {
                        DatabaseService.deleteRowsByPrimaryKeys(context.config,
                                context.target.databaseName, context.target.schemaName, context.target.tableName,
                                context.primaryKeyColumns, context.columnNames, keysToDelete);
                        Platform.runLater(() -> {
                            for (ObservableList<String> row : selectedRows) context.originalRows.remove(row);
                            tableView.getItems().removeAll(selectedRows);
                            updateQueryResultButtons(context);
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showQueryResultError("删除失败",
                                "删除行失败: " + e.getMessage()));
                    }
                } finally {
                    connLock.unlock();
                }
            }, "DB-DeleteRows-Query").start();
        });
    }

    /** Excel 式粘贴：从所选区域左上角开始覆盖，只修改源表真实列，不新增查询结果行。 */
    private void pasteIntoQueryResult(QueryResultEditContext context) {
        if (!context.isEditable()) return;
        String text = javafx.scene.input.Clipboard.getSystemClipboard().getString();
        if (text == null || text.isEmpty()) return;

        int startRow = Integer.MAX_VALUE;
        int startColumn = Integer.MAX_VALUE;
        for (TablePosition<ObservableList<String>, ?> position
                : context.tableView.getSelectionModel().getSelectedCells()) {
            int tableColumn = context.tableView.getColumns().indexOf(position.getTableColumn());
            int dataColumn = tableColumn - 1;
            if (position.getRow() >= 0 && dataColumn >= 0) {
                startRow = Math.min(startRow, position.getRow());
                startColumn = Math.min(startColumn, dataColumn);
            }
        }
        if (startRow == Integer.MAX_VALUE || startColumn == Integer.MAX_VALUE) return;

        List<String[]> values = parseClipboardRows(text);
        while (values.size() > 1 && values.get(values.size() - 1).length == 1
                && values.get(values.size() - 1)[0].isEmpty()) {
            values.remove(values.size() - 1);
        }
        context.tableView.getSelectionModel().clearSelection();
        for (int r = 0; r < values.size() && startRow + r < context.tableView.getItems().size(); r++) {
            ObservableList<String> row = context.tableView.getItems().get(startRow + r);
            for (int c = 0; c < values.get(r).length && startColumn + c < context.columnNames.size(); c++) {
                int dataColumn = startColumn + c;
                if (!context.editableColumnIndexes.contains(dataColumn)) continue;
                row.set(dataColumn, values.get(r)[c]);
                context.tableView.getSelectionModel().select(startRow + r,
                        context.tableView.getColumns().get(dataColumn + 1));
            }
        }
        context.tableView.refresh();
        context.tableView.scrollTo(startRow);
        updateQueryResultButtons(context);
    }

    private List<String[]> parseClipboardRows(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String cleanLine = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            rows.add(cleanLine.split("\t", -1));
        }
        return rows;
    }

    private boolean hasQueryResultChanges(QueryResultEditContext context) {
        for (ObservableList<String> row : context.tableView.getItems()) {
            ObservableList<String> original = context.originalRows.get(row);
            if (original == null) continue;
            for (int index : context.editableColumnIndexes) {
                if (!Objects.equals(row.get(index), original.get(index))) return true;
            }
        }
        return false;
    }

    private void updateQueryResultButtons(QueryResultEditContext context) {
        if (context.saveButton == null || context.deleteButton == null) return;
        context.saveButton.setDisable(!context.isEditable() || context.saving || !hasQueryResultChanges(context));
        context.deleteButton.setDisable(!context.isEditable() || context.saving
                || context.tableView.getSelectionModel().getSelectedItems().isEmpty());
    }

    private void showQueryResultError(String title, String message) {
        Alert err = new Alert(Alert.AlertType.ERROR);
        err.setTitle(title);
        err.setHeaderText(null);
        err.setContentText(message);
        DialogPositionUtil.centerOnOwner(err, this);
        err.showAndWait();
    }

    private boolean containsAllColumns(List<String> available, List<String> required) {
        for (String requiredColumn : required) {
            boolean found = available.stream().anyMatch(c -> c.equalsIgnoreCase(requiredColumn));
            if (!found) return false;
        }
        return true;
    }

    private QueryTableTarget resolveQueryTableTarget(String sourceTableName,
                                                     ConnectionConfig config,
                                                     String databaseName) {
        String clean = sourceTableName.trim()
                .replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        if (clean.isEmpty()) return null;
        String[] parts = clean.split("\\.");
        String tableName = parts[parts.length - 1].trim();
        if (tableName.isEmpty()) return null;

        String targetDatabase = databaseName;
        String targetSchema = null;
        if (parts.length > 1) {
            String qualifier = parts[parts.length - 2].trim();
            if (config.getType() == ConnectType.POSTGRESQL) targetSchema = qualifier;
            else targetDatabase = qualifier;
        } else if (initialSchema != null
                && Objects.equals(initialConfig != null ? initialConfig.getId() : null, config.getId())
                && Objects.equals(initialDatabase, databaseName)) {
            targetSchema = initialSchema;
        }
        return new QueryTableTarget(targetDatabase, targetSchema, tableName);
    }

    private static final class QueryTableTarget {
        final String databaseName;
        final String schemaName;
        final String tableName;

        QueryTableTarget(String databaseName, String schemaName, String tableName) {
            this.databaseName = databaseName;
            this.schemaName = schemaName;
            this.tableName = tableName;
        }

        String displayName() {
            return schemaName == null || schemaName.isBlank() ? tableName : schemaName + "." + tableName;
        }
    }

    private static final class QueryResultEditContext {
        final TableView<ObservableList<String>> tableView;
        final ConnectionConfig config;
        final QueryTableTarget target;
        final List<String> columnNames;
        final Map<ObservableList<String>, ObservableList<String>> originalRows = new IdentityHashMap<>();
        final Set<Integer> editableColumnIndexes = new LinkedHashSet<>();
        List<String> primaryKeyColumns = List.of();
        Button saveButton;
        Button deleteButton;
        HBox toolbar;
        boolean saving;

        QueryResultEditContext(TableView<ObservableList<String>> tableView, ConnectionConfig config,
                               QueryTableTarget target, List<String> columnNames) {
            this.tableView = tableView;
            this.config = config;
            this.target = target;
            this.columnNames = List.copyOf(columnNames);
        }

        boolean isEditable() {
            return !primaryKeyColumns.isEmpty() && !editableColumnIndexes.isEmpty();
        }
    }

    // ==================== Getter/Setter ====================

    public String getSqlText() {
        return editor.getText();
    }

    public void setSqlText(String sql) {
        editor.setText(sql);
    }

    public String getQueryName() {
        return queryName;
    }

    public void setQueryName(String name) {
        this.queryName = name;
        notifyTitleChange();
    }

    public boolean isModified() {
        return modified;
    }

    public boolean isNamed() {
        return queryName != null;
    }

    public TreeItem<String> getQueryNode() {
        return queryNode;
    }

    public void setQueryNode(TreeItem<String> node) {
        this.queryNode = node;
    }

    public ConnectionConfig getSelectedConnection() {
        return connectionCombo.getValue();
    }

    public String getSelectedDatabase() {
        return databaseCombo.getValue();
    }

    public void setOnTitleChange(Consumer<String> callback) {
        this.onTitleChange = callback;
    }

    public void setOnSaveRequest(Runnable callback) {
        this.onSaveRequest = callback;
    }

    // ==================== 编辑器接口 ====================

    private interface SqlEditor {
        javafx.scene.Node getNode();

        String getText();

        void setText(String text);

        String getSelectedText();

        void installRunSelectedContextMenu(Runnable action);

        void hideContextMenu();
    }

    /**
     * 基于 {@link SqlEditorPane}（RichTextFX InlineCssTextArea + 行号 + 语法高亮）的编辑器实现。
     * 实际能力委托给 {@link SqlEditorPane}，这里只做 {@link SqlEditor} 接口适配。
     */
    private static final class RichTextSqlEditor implements SqlEditor {
        private final SqlEditorPane pane;

        RichTextSqlEditor(Runnable onModified) {
            pane = new SqlEditorPane(true);
            pane.setOnModified(t -> onModified.run());
        }

        SqlEditorPane getPane() {
            return pane;
        }

        @Override
        public javafx.scene.Node getNode() {
            return pane;
        }

        @Override
        public String getText() {
            return pane.getText();
        }

        @Override
        public void setText(String text) {
            pane.setText(text);
        }

        @Override
        public String getSelectedText() {
            return pane.getSelectedText();
        }

        @Override
        public void installRunSelectedContextMenu(Runnable action) {
            pane.setOnRunSelectedRequest(action);
        }

        @Override
        public void hideContextMenu() {
            pane.hideContextMenu();
        }
    }

    /**
     * 基于 TextArea 的普通编辑器（fallback）
     */
    private static class PlainSqlEditor implements SqlEditor {
        private final TextArea textArea;
        private ContextMenu contextMenu;

        PlainSqlEditor(Runnable onModified) {
            textArea = new TextArea();
            textArea.setStyle(
                    "-fx-background-color: white; " +
                            "-fx-text-fill: #333; " +
                            "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                            "-fx-padding: 8; " +
                            "-fx-border-color: transparent; " +
                            "-fx-focus-color: transparent; " +
                            "-fx-faint-focus-color: transparent;"
            );
            textArea.setWrapText(false);
            textArea.setPromptText("输入SQL语句...");

            textArea.textProperty().addListener((obs, oldVal, newVal) -> onModified.run());

            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.TAB) {
                    e.consume();
                    textArea.insertText(textArea.getCaretPosition(), "    ");
                }
            });
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() && e.getCode() == KeyCode.ENTER) e.consume();
            });
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() && e.getCode() == KeyCode.S) e.consume();
            });
        }

        @Override
        public javafx.scene.Node getNode() {
            return textArea;
        }

        @Override
        public String getText() {
            return textArea.getText();
        }

        @Override
        public void setText(String text) {
            textArea.setText(text);
        }

        @Override
        public String getSelectedText() {
            return textArea.getSelectedText();
        }

        @Override
        public void installRunSelectedContextMenu(Runnable action) {
            MenuItem runSelectedItem = new MenuItem("运行已选择");
            runSelectedItem.setOnAction(e -> {
                if (!textArea.getSelectedText().isBlank()) action.run();
            });
            MenuItem cutItem = new MenuItem("剪切");
            cutItem.setOnAction(e -> textArea.cut());
            MenuItem copyItem = new MenuItem("复制");
            copyItem.setOnAction(e -> textArea.copy());
            MenuItem pasteItem = new MenuItem("粘贴");
            pasteItem.setOnAction(e -> textArea.paste());
            MenuItem selectAllItem = new MenuItem("全选");
            selectAllItem.setOnAction(e -> textArea.selectAll());
            contextMenu = new ContextMenu(runSelectedItem, new SeparatorMenuItem(),
                    cutItem, copyItem, pasteItem, new SeparatorMenuItem(), selectAllItem);
            contextMenu.setAutoHide(true);
            contextMenu.setHideOnEscape(true);
            contextMenu.setOnShowing(e -> {
                boolean noSelection = textArea.getSelectedText().isBlank();
                runSelectedItem.setDisable(noSelection);
                cutItem.setDisable(noSelection);
                copyItem.setDisable(noSelection);
                pasteItem.setDisable(!javafx.scene.input.Clipboard.getSystemClipboard().hasString());
            });
            textArea.setContextMenu(contextMenu);
        }

        @Override
        public void hideContextMenu() {
            if (contextMenu != null && contextMenu.isShowing()) contextMenu.hide();
        }
    }

    /**
     * 鼠标拖拽选中多个cell + Shift点击范围选中
     */
    private void setupDragSelection(TableView<ObservableList<String>> tableView) {
        final int[] dragStart = {-1, -1};
        final int[] anchorCell = {-1, -1};

        tableView.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            int[] cellPos = getCellPositionAt(tableView, event);
            if (cellPos == null) return;

            if (event.isShiftDown() && anchorCell[0] >= 0) {
                int minRow = Math.min(anchorCell[0], cellPos[0]);
                int maxRow = Math.max(anchorCell[0], cellPos[0]);
                int minCol = Math.min(anchorCell[1], cellPos[1]);
                int maxCol = Math.max(anchorCell[1], cellPos[1]);
                tableView.getSelectionModel().clearSelection();
                for (int r = minRow; r <= maxRow; r++) {
                    for (int c = minCol; c <= maxCol; c++) {
                        TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(c);
                        tableView.getSelectionModel().select(r, col);
                    }
                }
                event.consume();
                return;
            }

            dragStart[0] = cellPos[0];
            dragStart[1] = cellPos[1];
            anchorCell[0] = cellPos[0];
            anchorCell[1] = cellPos[1];
            tableView.getSelectionModel().clearSelection();
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(cellPos[1]);
            tableView.getSelectionModel().select(cellPos[0], col);
        });

        tableView.setOnMouseDragged(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (dragStart[0] < 0) return;
            int[] cellPos = getCellPositionAt(tableView, event);
            if (cellPos == null) return;
            int endRow = cellPos[0];
            int endCol = cellPos[1];
            int minRow = Math.min(dragStart[0], endRow);
            int maxRow = Math.max(dragStart[0], endRow);
            int minCol = Math.min(dragStart[1], endCol);
            int maxCol = Math.max(dragStart[1], endCol);
            tableView.getSelectionModel().clearSelection();
            for (int r = minRow; r <= maxRow; r++) {
                for (int c = minCol; c <= maxCol; c++) {
                    TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(c);
                    tableView.getSelectionModel().select(r, col);
                }
            }
        });

        tableView.setOnMouseReleased(event -> {
            dragStart[0] = -1;
        });
    }

    /**
     * 根据鼠标事件位置获取对应的cell坐标 [row, colIndex]
     * 点击右侧空白区域（TableRow 但非 TableCell）时返回该行和最后一列
     */
    private int[] getCellPositionAt(TableView<ObservableList<String>> tableView, javafx.scene.input.MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        TableRow<?> clickedRow = null;
        while (target != null && target != tableView) {
            if (clickedRow == null && target instanceof TableRow<?> row) {
                clickedRow = row;
            }
            if (target instanceof TableCell<?, ?> cell) {
                if (cell.getTableColumn() != null && cell.getTableRow() != null) {
                    int row = cell.getTableRow().getIndex();
                    int col = tableView.getColumns().indexOf(cell.getTableColumn());
                    if (col >= 0) {
                        return new int[]{row, col};
                    }
                }
            }
            target = target.getParent();
        }
        if (clickedRow != null) {
            int rowIndex = clickedRow.getIndex();
            int lastCol = getLastVisibleDataColumnIndex(tableView);
            if (lastCol >= 0) {
                return new int[]{rowIndex, lastCol};
            }
        }
        return null;
    }

    /**
     * 获取最后一个可见数据列在 tableView.getColumns() 中的索引
     */
    private int getLastVisibleDataColumnIndex(TableView<ObservableList<String>> tableView) {
        for (int i = tableView.getColumns().size() - 1; i >= 0; i--) {
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(i);
            if (col.isVisible() && !ROW_SELECTOR_COL.equals(col.getUserData())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 键盘快捷键：Ctrl+C复制
     */
    private void setupKeyboardShortcuts(TableView<ObservableList<String>> tableView,
                                        QueryResultEditContext editContext) {
        tableView.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.C) {
                handleCopySelectedCells(tableView);
                event.consume();
            } else if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.V
                    && editContext != null && editContext.isEditable()) {
                pasteIntoQueryResult(editContext);
                event.consume();
            }
        });
    }

    /**
     * 复制选中的cell到剪贴板，按行列排列，Tab分隔列，换行分隔行
     */
    private void handleCopySelectedCells(TableView<ObservableList<String>> tableView) {
        @SuppressWarnings("unchecked")
        ObservableList<TablePosition<ObservableList<String>, ?>> selectedCells =
                (ObservableList<TablePosition<ObservableList<String>, ?>>) (ObservableList<?>) tableView.getSelectionModel().getSelectedCells();
        if (selectedCells.isEmpty()) return;

        int minRow = Integer.MAX_VALUE, maxRow = -1;
        int minCol = Integer.MAX_VALUE, maxCol = -1;
        for (TablePosition<?, ?> pos : selectedCells) {
            int row = pos.getRow();
            int col = tableView.getColumns().indexOf(pos.getTableColumn());
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }

        java.util.Set<String> selectedSet = new java.util.HashSet<>();
        for (TablePosition<?, ?> pos : selectedCells) {
            int col = tableView.getColumns().indexOf(pos.getTableColumn());
            selectedSet.add(pos.getRow() + "," + col);
        }

        StringBuilder sb = new StringBuilder();
        for (int r = minRow; r <= maxRow; r++) {
            ObservableList<String> rowData = tableView.getItems().get(r);
            boolean firstCol = true;
            for (int c = minCol; c <= maxCol; c++) {
                if (!selectedSet.contains(r + "," + c)) continue;
                if (!firstCol) sb.append('\t');
                firstCol = false;
                int dataColIndex = c - 1; // 减去行选择器列
                if (dataColIndex >= 0 && dataColIndex < rowData.size()) {
                    String value = rowData.get(dataColIndex);
                    sb.append(value != null ? value : "");
                }
            }
            if (r < maxRow) sb.append('\n');
        }

        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(sb.toString());
        clipboard.setContent(content);
    }

    private void stripPaddingRecursive(Node node) {
        if (node instanceof Region region) {
            if (!region.getStyleClass().contains("table-cell")
                    && !region.getStyleClass().contains("column-header")
                    && !region.getStyleClass().contains("table-row-cell")) {
                region.setPadding(Insets.EMPTY);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                stripPaddingRecursive(child);
            }
        }
    }
}
