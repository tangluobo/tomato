package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * SQL编辑器视图
 * 优先使用 RichTextFX CodeArea（语法高亮），加载失败时回退到 TextArea
 */
public class SqlEditorView extends BorderPane {

    private final SqlEditor editor;

    private ComboBox<ConnectionConfig> connectionCombo;
    private ComboBox<String> databaseCombo;
    private TableView<ObservableList<String>> resultTable;
    private Label statusLabel;

    private boolean modified = false;
    private String queryName = null;
    private String savedSql = "";
    private TreeItem<String> queryNode;

    private Consumer<String> onTitleChange;
    private Runnable onSaveRequest;

    public SqlEditorView(List<ConnectionConfig> connections, ConnectionConfig initialConfig, String initialDatabase) {
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

        // ---- 结果区域 ----
        VBox resultBox = new VBox();
        resultBox.setStyle("-fx-background-color: white;");

        resultTable = new TableView<>();
        resultTable.setStyle("-fx-font-size: 12px;");
        resultTable.setPlaceholder(new Label("无结果"));

        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888; -fx-padding: 4 8 4 8; -fx-background-color: #f8f8f8;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        resultBox.getChildren().addAll(resultTable, statusLabel);
        VBox.setVgrow(resultTable, Priority.ALWAYS);

        // ---- 主布局 ----
        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        splitPane.getItems().addAll(editor.getNode(), resultBox);
        splitPane.setDividerPositions(0.5);

        this.setTop(toolbar);
        setCenter(splitPane);

        // 初始加载数据库列表
        if (initialConfig != null) {
            refreshDatabaseList();
        }

        getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
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

    void markModified() {
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
            dialog.showAndWait().ifPresent(name -> {
                if (!name.trim().isEmpty()) doSave(name.trim());
            });
        } else {
            doSave(queryName);
        }
    }

    public void doSave(String name) {
        this.queryName = name;
        this.savedSql = editor.getText();
        this.modified = false;
        notifyTitleChange();
    }

    // ==================== 数据库列表 ====================

    private void refreshDatabaseList() {
        ConnectionConfig config = connectionCombo.getValue();
        if (config == null) return;
        String currentDb = databaseCombo.getValue();
        databaseCombo.getItems().clear();
        new Thread(() -> {
            try {
                if (config.getPassword() == null) return;
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    databaseCombo.getItems().addAll(databases);
                    if (currentDb != null && databases.contains(currentDb)) databaseCombo.setValue(currentDb);
                    else if (!databases.isEmpty()) databaseCombo.setValue(databases.get(0));
                });
            } catch (Exception e) { /* 静默 */ }
        }, "DB-RefreshDbList").start();
    }

    // ==================== SQL操作 ====================

    private void executeQuery() {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) {
            statusLabel.setText("请先选择连接和数据库");
            return;
        }
        String sql = getEffectiveSql();
        if (sql.isEmpty()) return;
        statusLabel.setText("执行中...");
        new Thread(() -> {
            try {
                TableRowData result = DatabaseService.executeSqlQuery(config, dbName, sql, 1000);
                Platform.runLater(() -> {
                    displayResult(result);
                    statusLabel.setText("查询完成，共 " + result.getTotalCount() + " 行，耗时 " + result.getQueryTime() + "ms");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    resultTable.getColumns().clear();
                    resultTable.getItems().clear();
                    statusLabel.setText("执行失败: " + e.getMessage());
                });
            }
        }, "DB-ExecuteQuery").start();
    }

    private void explainQuery() {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) {
            statusLabel.setText("请先选择连接和数据库");
            return;
        }
        String sql = getEffectiveSql();
        if (sql.isEmpty()) return;
        statusLabel.setText("执行解释...");
        new Thread(() -> {
            try {
                TableRowData result = DatabaseService.executeSqlQuery(config, dbName, "EXPLAIN " + sql, 1000);
                Platform.runLater(() -> {
                    displayResult(result);
                    statusLabel.setText("解释完成，耗时 " + result.getQueryTime() + "ms");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    resultTable.getColumns().clear();
                    resultTable.getItems().clear();
                    statusLabel.setText("解释失败: " + e.getMessage());
                });
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
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        return sql;
    }

    private void displayResult(TableRowData result) {
        resultTable.getColumns().clear();
        resultTable.getItems().clear();
        List<String> columns = result.getColumnNames();
        for (int i = 0; i < columns.size(); i++) {
            final int colIndex = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(columns.get(i));
            col.setPrefWidth(120);
            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                return new javafx.beans.property.SimpleStringProperty(colIndex < row.size() ? row.get(colIndex) : "");
            });
            resultTable.getColumns().add(col);
        }
        resultTable.getItems().addAll(result.getRows());
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
    }

    /**
     * 基于 RichTextFX InlineCssTextArea 的语法高亮编辑器
     * 使用内联CSS字符串，不需要外部CSS文件
     */
    private static class RichTextSqlEditor implements SqlEditor {
        private final org.fxmisc.richtext.InlineCssTextArea textArea;
        private final org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.InlineCssTextArea> scrollPane;
        private final javafx.scene.layout.HBox editorContainer;
        private final javafx.scene.layout.VBox lineNumberBox;
        private static final int MAX_LINES = 500;

        // 内联CSS样式字符串，直接应用到文本段
        private static final String STYLE_KEYWORD = "-fx-fill: #0000FF; -fx-font-weight: bold;";
        private static final String STYLE_STRING = "-fx-fill: #A31515;";
        private static final String STYLE_COMMENT = "-fx-fill: #6A9955; -fx-font-style: italic;";
        private static final String STYLE_NUMBER = "-fx-fill: #098658;";

        RichTextSqlEditor(Runnable onModified) {
            textArea = new org.fxmisc.richtext.InlineCssTextArea();
            textArea.setStyle(
                    "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                            "-fx-background-color: white; -fx-padding: 0; -fx-text-fill: #333;"
            );

            scrollPane = new org.fxmisc.flowless.VirtualizedScrollPane<>(textArea);

            lineNumberBox = new javafx.scene.layout.VBox();
            lineNumberBox.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 0;");
            lineNumberBox.setPrefWidth(40);
            lineNumberBox.setMinWidth(40);
            lineNumberBox.setMaxWidth(40);

            java.util.List<Label> lineNumberLabels = new java.util.ArrayList<>();
            for (int i = 1; i <= MAX_LINES; i++) {
                Label label = new Label(Integer.toString(i));
                label.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                        "-fx-text-fill: #888888; -fx-alignment: CENTER_RIGHT; -fx-padding: 0 8 0 4;");
                label.setVisible(false);
                label.setManaged(false);
                lineNumberLabels.add(label);
                lineNumberBox.getChildren().add(label);
            }
            javafx.scene.layout.Region filler = new javafx.scene.layout.Region();
            javafx.scene.layout.VBox.setVgrow(filler, javafx.scene.layout.Priority.ALWAYS);
            lineNumberBox.getChildren().add(filler);

            // 初始显示第1行
            updateLineNumbers(lineNumberLabels, 1);

            editorContainer = new javafx.scene.layout.HBox();
            editorContainer.getChildren().addAll(lineNumberBox, scrollPane);
            javafx.scene.layout.HBox.setHgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);

            textArea.estimatedScrollYProperty().addListener((obs, oldVal, newVal) -> {
                lineNumberBox.setTranslateY(-newVal.doubleValue());
            });

            // 内容变化
            textArea.textProperty().addListener((obs, oldVal, newVal) -> {
                onModified.run();
                applyHighlighting();
                updateLineNumbers(lineNumberLabels, textArea.getParagraphs().size());
            });

            // Tab缩进
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.TAB) {
                    e.consume();
                    textArea.insertText(textArea.getCaretPosition(), "    ");
                }
            });
            // Ctrl+Enter 运行
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() && e.getCode() == KeyCode.ENTER) e.consume();
            });
            // Ctrl+S 保存
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() && e.getCode() == KeyCode.S) e.consume();
            });

            // 初始应用一次高亮（空文本时也设置默认样式）
            applyHighlighting();
        }

        private static final String[] KEYWORDS = {
                "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
                "DELETE", "CREATE", "DROP", "ALTER", "TABLE", "INDEX", "VIEW", "DATABASE",
                "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE", "IS", "NULL",
                "JOIN", "INNER", "LEFT", "RIGHT", "OUTER", "FULL", "CROSS", "ON",
                "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL",
                "AS", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END",
                "COUNT", "SUM", "AVG", "MIN", "MAX",
                "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT",
                "DEFAULT", "CHECK", "UNIQUE", "AUTO_INCREMENT",
                "IF", "CASCADE", "RENAME", "TO",
                "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION",
                "GRANT", "REVOKE", "PRIVILEGES",
                "SHOW", "DESCRIBE", "EXPLAIN", "USE", "TRUNCATE",
                "CHARACTER", "COLLATE", "REPLACE"
        };

        // 关键词不区分大小写
        private static final String KEYWORD_PATTERN = "(?i)\\b(" + String.join("|", KEYWORDS) + ")\\b";
        private static final java.util.regex.Pattern SYNTAX_PATTERN = java.util.regex.Pattern.compile(
                "(?<KEYWORD>" + KEYWORD_PATTERN + ")" +
                        "|(?<STRING>'[^']*')" +
                        "|(?<COMMENT1>--[^\n]*)" +
                        "|(?<COMMENT2>/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/)" +
                        "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)"
        );

        private static void updateLineNumbers(java.util.List<Label> labels, int lineCount) {
            int visibleCount = Math.min(lineCount, labels.size());
            for (int i = 0; i < labels.size(); i++) {
                boolean show = i < visibleCount;
                labels.get(i).setVisible(show);
                labels.get(i).setManaged(show);
            }
        }

        private void applyHighlighting() {
            String text = textArea.getText();
            if (text.isEmpty()) return;
            try {
                java.util.regex.Matcher matcher = SYNTAX_PATTERN.matcher(text);
                int lastKwEnd = 0;
                org.fxmisc.richtext.model.StyleSpansBuilder<String> spansBuilder =
                        new org.fxmisc.richtext.model.StyleSpansBuilder<>();
                while (matcher.find()) {
                    String style;
                    if (matcher.group("KEYWORD") != null) style = STYLE_KEYWORD;
                    else if (matcher.group("STRING") != null) style = STYLE_STRING;
                    else if (matcher.group("COMMENT1") != null) style = STYLE_COMMENT;
                    else if (matcher.group("COMMENT2") != null) style = STYLE_COMMENT;
                    else if (matcher.group("NUMBER") != null) style = STYLE_NUMBER;
                    else style = "";
                    if (matcher.start() > lastKwEnd) {
                        spansBuilder.add("", matcher.start() - lastKwEnd);
                    }
                    spansBuilder.add(style, matcher.end() - matcher.start());
                    lastKwEnd = matcher.end();
                }
                if (lastKwEnd < text.length()) {
                    spansBuilder.add("", text.length() - lastKwEnd);
                }
                textArea.setStyleSpans(0, spansBuilder.create());
            } catch (Exception e) {
                System.err.println("SQL高亮异常: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public javafx.scene.Node getNode() {
            return editorContainer;
        }

        @Override
        public String getText() {
            return textArea.getText();
        }

        @Override
        public void setText(String text) {
            textArea.replaceText(text);
        }

        @Override
        public String getSelectedText() {
            return textArea.getSelectedText();
        }
    }

    /**
     * 基于 TextArea 的普通编辑器（fallback）
     */
    private static class PlainSqlEditor implements SqlEditor {
        private final TextArea textArea;

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
    }
}
