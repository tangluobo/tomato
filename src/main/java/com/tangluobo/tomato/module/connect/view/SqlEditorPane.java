package com.tangluobo.tomato.module.connect.view;

import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可复用的 SQL 编辑器组件。
 *
 * 基于 RichTextFX {@link InlineCssTextArea}，内置：
 * <ul>
 *   <li>行号显示（固定 500 行，随滚动同步）</li>
 *   <li>SQL 语法高亮（关键字蓝色粗体 / 字符串红色 / 注释绿色斜体 / 数字绿色）</li>
 *   <li>Tab 缩进（4 空格）</li>
 *   <li>Ctrl+Enter 触发 {@code onRunRequest}、Ctrl+S 触发 {@code onSaveRequest}</li>
 * </ul>
 *
 * 可编辑（默认）或只读（用于 SQL 预览场景）。
 */
public class SqlEditorPane extends HBox {

    private static final int MAX_LINES = 500;

    // 内联 CSS 样式，直接应用到文本段
    private static final String STYLE_KEYWORD = "-fx-fill: #0000FF; -fx-font-weight: bold;";
    private static final String STYLE_STRING = "-fx-fill: #A31515;";
    private static final String STYLE_COMMENT = "-fx-fill: #6A9955; -fx-font-style: italic;";
    private static final String STYLE_NUMBER = "-fx-fill: #098658;";

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
    private static final Pattern SYNTAX_PATTERN = Pattern.compile(
            "(?<KEYWORD>" + KEYWORD_PATTERN + ")" +
                    "|(?<STRING>'[^']*')" +
                    "|(?<COMMENT1>--[^\n]*)" +
                    "|(?<COMMENT2>/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/)" +
                    "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)"
    );

    private final InlineCssTextArea textArea;
    private final VirtualizedScrollPane<InlineCssTextArea> scrollPane;
    private final VBox lineNumberBox;
    private final List<Label> lineNumberLabels;
    private final boolean syntaxHighlighting;

    private Consumer<String> onModified;
    private Runnable onRunRequest;
    private Runnable onSaveRequest;

    /** 默认可编辑 */
    public SqlEditorPane() {
        this(true, true);
    }

    public SqlEditorPane(boolean editable) {
        this(editable, true);
    }

    /**
     * @param editable 是否可编辑
     * @param syntaxHighlighting 是否启用 SQL 语法高亮；日志输出场景应传 false，
     *                            以便按级别着色的样式不被 SQL 高亮覆盖
     */
    public SqlEditorPane(boolean editable, boolean syntaxHighlighting) {
        this.syntaxHighlighting = syntaxHighlighting;
        textArea = new InlineCssTextArea();
        textArea.setEditable(editable);
        textArea.setStyle(
                "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                        "-fx-background-color: white; -fx-padding: 0; -fx-text-fill: #333;"
        );

        scrollPane = new VirtualizedScrollPane<>(textArea);

        lineNumberBox = new VBox();
        lineNumberBox.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 0;");
        lineNumberBox.setPrefWidth(40);
        lineNumberBox.setMinWidth(40);
        lineNumberBox.setMaxWidth(40);
        // 不驱动父布局高度，由父容器(HBox)分配空间后被动填充
        lineNumberBox.setMinHeight(0);
        lineNumberBox.setPrefHeight(0);

        lineNumberLabels = new ArrayList<>(MAX_LINES);
        for (int i = 1; i <= MAX_LINES; i++) {
            Label label = new Label(Integer.toString(i));
            label.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                    "-fx-text-fill: #888888; -fx-alignment: CENTER_RIGHT; -fx-padding: 0 8 0 4;");
            label.setVisible(false);
            label.setManaged(false);
            lineNumberLabels.add(label);
            lineNumberBox.getChildren().add(label);
        }
        Region filler = new Region();
        VBox.setVgrow(filler, Priority.ALWAYS);
        lineNumberBox.getChildren().add(filler);

        // 初始显示第 1 行
        updateLineNumbers(1);

        getChildren().addAll(lineNumberBox, scrollPane);
        HBox.setHgrow(scrollPane, Priority.ALWAYS);
        // 不驱动 SplitPane 分配，被动接受父容器给的空间
        setMinHeight(0);
        setPrefHeight(200);

        // 滚动同步行号
        textArea.estimatedScrollYProperty().addListener((obs, oldVal, newVal) ->
                lineNumberBox.setTranslateY(-newVal.doubleValue()));

        // 内容变化：高亮 + 行号 + 修改回调
        textArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (syntaxHighlighting) applyHighlighting();
            updateLineNumbers(textArea.getParagraphs().size());
            if (onModified != null) onModified.accept(newVal);
        });

        // Tab 缩进
        textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB) {
                e.consume();
                textArea.insertText(textArea.getCaretPosition(), "    ");
            }
        });
        // Ctrl+Enter 运行
        textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.ENTER) {
                e.consume();
                if (onRunRequest != null) onRunRequest.run();
            }
        });
        // Ctrl+S 保存
        textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.S) {
                e.consume();
                if (onSaveRequest != null) onSaveRequest.run();
            }
        });

        // 裁剪子节点超出边界的视觉：行号 lineNumberBox 用 translateY 同步滚动，
        // 不裁剪的话行号会向上溢出 SqlEditorPane 区域，覆盖上方工具栏/选项
        javafx.scene.shape.Rectangle clipRect = new javafx.scene.shape.Rectangle();
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        // 初始应用一次高亮（空文本时也设置默认样式）
        if (syntaxHighlighting) applyHighlighting();
    }

    private void updateLineNumbers(int lineCount) {
        int visibleCount = Math.min(lineCount, lineNumberLabels.size());
        for (int i = 0; i < lineNumberLabels.size(); i++) {
            boolean show = i < visibleCount;
            lineNumberLabels.get(i).setVisible(show);
            lineNumberLabels.get(i).setManaged(show);
        }
    }

    private void applyHighlighting() {
        String text = textArea.getText();
        if (text.isEmpty()) return;
        try {
            Matcher matcher = SYNTAX_PATTERN.matcher(text);
            int lastKwEnd = 0;
            StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
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
        }
    }

    // ==================== 公开 API ====================

    public String getText() {
        return textArea.getText();
    }

    public void setText(String text) {
        textArea.replaceText(text == null ? "" : text);
    }

    public String getSelectedText() {
        return textArea.getSelectedText();
    }

    public void setEditable(boolean editable) {
        textArea.setEditable(editable);
    }

    public boolean isEditable() {
        return textArea.isEditable();
    }

    /** 文本变化回调，参数为新文本 */
    public void setOnModified(Consumer<String> onModified) {
        this.onModified = onModified;
    }

    /** Ctrl+Enter 回调（运行） */
    public void setOnRunRequest(Runnable onRunRequest) {
        this.onRunRequest = onRunRequest;
    }

    /** Ctrl+S 回调（保存） */
    public void setOnSaveRequest(Runnable onSaveRequest) {
        this.onSaveRequest = onSaveRequest;
    }

    /** 全选 */
    public void selectAll() {
        textArea.selectAll();
    }

    /** 聚焦编辑器 */
    public void requestFocus() {
        textArea.requestFocus();
    }

    /**
     * 在末尾追加文本，并自动滚动到底部（用于日志输出场景）。
     * 调用方应在 JavaFX 应用线程调用；若不在，会被调度到 JavaFX 线程异步追加。
     */
    public void appendText(String text) {
        appendText(text, null);
    }

    /**
     * 在末尾追加文本，并对追加范围套用指定 CSS 样式（如 {@code "-fx-fill: #c62828;"}）。
     * 样式仅在该 SqlEditorPane 未启用 SQL 语法高亮时才会生效（高亮会覆盖手动样式）。
     *
     * @param text  要追加的文本
     * @param style InlineCssTextArea 的 CSS 样式串，传 null 或空串则不套样式
     */
    public void appendText(String text, String style) {
        if (text == null || text.isEmpty()) return;
        if (!javafx.application.Platform.isFxApplicationThread()) {
            final String t = text;
            final String s = style;
            javafx.application.Platform.runLater(() -> doAppend(t, s));
            return;
        }
        doAppend(text, style);
    }

    private void doAppend(String text, String style) {
        int oldLen = textArea.getLength();
        textArea.insertText(oldLen, text);
        if (style != null && !style.isEmpty()) {
            textArea.setStyle(oldLen, oldLen + text.length(), style);
        }
        // 移动光标到末尾并请求跟随光标（自动滚到底部）
        textArea.moveTo(oldLen + text.length());
        textArea.requestFollowCaret();
    }

    /** 清空全部内容 */
    public void clear() {
        textArea.replaceText("");
    }
}
