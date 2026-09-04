package com.tangluobo.tomato.module.connect.view;

import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private static final String STYLE_COMMENT = "-fx-fill: #2F6234; -fx-font-style: italic;";
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
    private final javafx.scene.layout.Pane changeMarkerPane;
    private List<ChangeHighlight> changeHighlights = List.of();
    private final boolean syntaxHighlighting;

    private Consumer<String> onModified;
    private Runnable onRunRequest;
    private Runnable onRunSelectedRequest;
    private Runnable onAiSqlRequest;
    private Runnable onSaveRequest;
    private ContextMenu editorContextMenu;
    private final Popup completionPopup = new Popup();
    private final ListView<CompletionItem> completionList = new ListView<>();
    private final List<CompletionItem> completionItems = new ArrayList<>();
    private int completionWordStart = -1;

    public enum ChangeKind {
        ADDED, MODIFIED, DELETED
    }

    public record ChangeHighlight(int line, ChangeKind kind) {}

    /** 自动补全候选项。kind 用于标识关键字、数据库、表或字段。 */
    public static final class CompletionItem {
        private final String text;
        private final String kind;

        public CompletionItem(String text, String kind) {
            this.text = text;
            this.kind = kind;
        }

        public String getText() { return text; }
        public String getKind() { return kind; }
    }

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
        textArea.getStyleClass().add("sql-editor-area");
        textArea.setEditable(editable);
        textArea.setStyle(
                "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                        "-fx-background-color: white; -fx-padding: 0; -fx-text-fill: #333;"
        );

        scrollPane = new VirtualizedScrollPane<>(textArea);

        changeMarkerPane = new javafx.scene.layout.Pane();
        changeMarkerPane.setMinWidth(12);
        changeMarkerPane.setPrefWidth(12);
        changeMarkerPane.setMaxWidth(12);
        changeMarkerPane.setStyle("-fx-background-color: #f3f4f6; -fx-border-color: #d7d9dc; "
                + "-fx-border-width: 0 0 0 1;");
        changeMarkerPane.heightProperty().addListener((obs, oldHeight, newHeight) -> renderChangeMarkers());
        changeMarkerPane.setOnMouseClicked(event -> scrollToNearestChange(event.getY()));

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

        getChildren().addAll(lineNumberBox, scrollPane, changeMarkerPane);
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
            if (!changeHighlights.isEmpty()) renderChangeMarkers();
            if (onModified != null) onModified.accept(newVal);
            if (textArea.isFocused() && textArea.isEditable()) {
                javafx.application.Platform.runLater(this::refreshCompletionPopup);
            }
        });

        configureCompletionPopup();

        // 光标通过鼠标、方向键或输入发生移动时，重新过滤候选并让弹层跟随光标。
        textArea.caretPositionProperty().addListener((obs, oldVal, newVal) -> {
            if (textArea.isFocused() && textArea.isEditable()) {
                javafx.application.Platform.runLater(this::refreshCompletionPopup);
            }
        });
        // RichTextFX 的 caretBounds 是屏幕坐标，滚动编辑器时也会变化。
        textArea.caretBoundsProperty().addListener((obs, oldVal, newVal) -> {
            if (completionPopup.isShowing()) {
                newVal.ifPresent(bounds -> positionCompletionPopup(bounds, completionList.getItems().size()));
            }
        });

        textArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleCompletionKeyPressed);

        // Tab 缩进
        textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            // 自动补全已消费 Tab 时只上屏候选，不再插入缩进空格。
            if (!e.isConsumed() && e.getCode() == KeyCode.TAB) {
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
        setMetadataCompletions(List.of(), List.of(), List.of());
        if (syntaxHighlighting) applyHighlighting();
    }

    private void configureCompletionPopup() {
        completionList.setPrefWidth(300);
        completionList.setMaxHeight(220);
        completionList.setFixedCellSize(26);
        // 候选列表只负责展示和选择，键盘输入焦点始终留在 SQL 编辑器。
        completionList.setFocusTraversable(false);
        completionList.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        completionList.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(CompletionItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getText() + "    " + item.getKind());
            }
        });
        completionList.setOnMouseClicked(e -> acceptSelectedCompletion());
        // 某些 JavaFX/系统组合下 Popup 仍可能收到按键，双侧监听保证回车可补全。
        completionList.addEventFilter(KeyEvent.KEY_PRESSED, this::handleCompletionKeyPressed);
        completionPopup.getContent().add(completionList);
        completionPopup.setAutoHide(true);
        completionPopup.setHideOnEscape(true);
        completionPopup.setOnShown(e -> textArea.requestFocus());
    }

    private void handleCompletionKeyPressed(KeyEvent e) {
        if (!completionPopup.isShowing() || completionList.getItems().isEmpty()) return;
        if (e.getCode() == KeyCode.DOWN) {
            int index = completionList.getSelectionModel().getSelectedIndex();
            completionList.getSelectionModel().select(
                    index >= completionList.getItems().size() - 1 ? 0 : index + 1);
            centerSelectedCompletion();
            e.consume();
        } else if (e.getCode() == KeyCode.UP) {
            int index = completionList.getSelectionModel().getSelectedIndex();
            completionList.getSelectionModel().select(
                    index <= 0 ? completionList.getItems().size() - 1 : index - 1);
            centerSelectedCompletion();
            e.consume();
        } else if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.TAB) {
            e.consume();
            acceptSelectedCompletion();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            completionPopup.hide();
            e.consume();
        }
    }

    /** 让中间位置的选中项尽量处于下拉框中央，首尾区域自然贴边。 */
    private void centerSelectedCompletion() {
        int selectedIndex = completionList.getSelectionModel().getSelectedIndex();
        int itemCount = completionList.getItems().size();
        int visibleRows = Math.min(itemCount, 8);
        int maxTopIndex = Math.max(0, itemCount - visibleRows);
        int desiredTopIndex = Math.max(0,
                Math.min(maxTopIndex, selectedIndex - visibleRows / 2));

        // ListView.scrollTo 只保证可见，不能居中；固定行高下按目标首行映射滚动条位置。
        javafx.application.Platform.runLater(() -> {
            for (Node node : completionList.lookupAll(".scroll-bar")) {
                if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                    if (maxTopIndex == 0) {
                        bar.setValue(bar.getMin());
                    } else {
                        double ratio = (double) desiredTopIndex / maxTopIndex;
                        bar.setValue(bar.getMin() + ratio * (bar.getMax() - bar.getMin()));
                    }
                    return;
                }
            }
            completionList.scrollTo(selectedIndex);
        });
    }

    private void refreshCompletionPopup() {
        int caret = textArea.getCaretPosition();
        String text = textArea.getText();
        if (caret <= 0 || caret > text.length()) { completionPopup.hide(); return; }
        int start = caret;
        while (start > 0 && isIdentifierPart(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, caret);
        if (prefix.isEmpty()) { completionPopup.hide(); return; }

        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<CompletionItem> matches = completionItems.stream()
                .filter(item -> item.getText().toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .filter(item -> !item.getText().equalsIgnoreCase(prefix))
                .sorted(Comparator.comparingInt((CompletionItem item) -> kindOrder(item.getKind()))
                        .thenComparing(CompletionItem::getText, String.CASE_INSENSITIVE_ORDER))
                .limit(100).toList();
        if (matches.isEmpty()) { completionPopup.hide(); return; }

        completionWordStart = start;
        completionList.getItems().setAll(matches);
        completionList.getSelectionModel().selectFirst();
        textArea.getCaretBounds().ifPresent(bounds -> positionCompletionPopup(bounds, matches.size()));
    }

    private void positionCompletionPopup(Bounds screen, int itemCount) {
        completionList.setPrefHeight(Math.min(itemCount, 8) * 26.0 + 2);
        if (completionPopup.isShowing()) {
            completionPopup.setX(screen.getMinX());
            completionPopup.setY(screen.getMaxY());
        } else {
            completionPopup.show(textArea, screen.getMinX(), screen.getMaxY());
        }
    }

    private void acceptSelectedCompletion() {
        CompletionItem selected = completionList.getSelectionModel().getSelectedItem();
        if (selected == null || completionWordStart < 0) return;
        int caret = textArea.getCaretPosition();
        completionPopup.hide();
        textArea.replaceText(completionWordStart, caret, selected.getText());
        textArea.requestFocus();
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int kindOrder(String kind) {
        return switch (kind) {
            case "关键字" -> 0;
            case "数据库" -> 1;
            case "表" -> 2;
            case "字段" -> 3;
            default -> 4;
        };
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

    public void setChangeHighlights(List<ChangeHighlight> highlights) {
        clearChangeHighlights();
        if (highlights == null || highlights.isEmpty()) return;
        int paragraphCount = textArea.getParagraphs().size();
        changeHighlights = highlights.stream()
                .filter(change -> change != null && change.kind() != null
                        && change.line() > 0 && change.line() <= paragraphCount)
                .sorted(Comparator.comparingInt(ChangeHighlight::line))
                .toList();
        for (ChangeHighlight change : changeHighlights) {
            textArea.setParagraphStyle(change.line() - 1, paragraphStyle(change.kind()));
        }
        renderChangeMarkers();
    }

    public void clearChangeHighlights() {
        for (int i = 0; i < textArea.getParagraphs().size(); i++) {
            textArea.setParagraphStyle(i, "");
        }
        changeHighlights = List.of();
        changeMarkerPane.getChildren().clear();
        changeMarkerPane.setCursor(javafx.scene.Cursor.DEFAULT);
    }

    private String paragraphStyle(ChangeKind kind) {
        return switch (kind) {
            case ADDED -> "-fx-background-color: #e4f2df;";
            case MODIFIED -> "-fx-background-color: #dcecff;";
            case DELETED -> "-fx-background-color: #f7d9dc;";
        };
    }

    private String markerColor(ChangeKind kind) {
        return switch (kind) {
            case ADDED -> "#62a852";
            case MODIFIED -> "#3574b9";
            case DELETED -> "#c75450";
        };
    }

    private String changeKindText(ChangeKind kind) {
        return switch (kind) {
            case ADDED -> "新增";
            case MODIFIED -> "修改";
            case DELETED -> "删除";
        };
    }

    private void renderChangeMarkers() {
        changeMarkerPane.getChildren().clear();
        if (changeHighlights.isEmpty()) {
            changeMarkerPane.setCursor(javafx.scene.Cursor.DEFAULT);
            return;
        }
        changeMarkerPane.setCursor(javafx.scene.Cursor.HAND);
        int lineCount = Math.max(1, textArea.getParagraphs().size());
        double height = Math.max(1, changeMarkerPane.getHeight());
        double markerHeight = Math.max(4, Math.min(10, height / lineCount));
        double travel = Math.max(0, height - markerHeight);
        for (ChangeHighlight change : changeHighlights) {
            Region marker = new Region();
            marker.setManaged(false);
            marker.setPrefSize(9, markerHeight);
            marker.resize(9, markerHeight);
            marker.setLayoutX(2);
            marker.setLayoutY(lineCount == 1 ? 0
                    : ((double) (change.line() - 1) / (lineCount - 1)) * travel);
            marker.setStyle("-fx-background-color: " + markerColor(change.kind())
                    + "; -fx-background-radius: 1;");
            javafx.scene.control.Tooltip.install(marker,
                    new javafx.scene.control.Tooltip("第 " + change.line() + " 行：" + changeKindText(change.kind())));
            marker.setOnMouseClicked(event -> {
                scrollToChange(change);
                event.consume();
            });
            changeMarkerPane.getChildren().add(marker);
        }
    }

    private void scrollToNearestChange(double y) {
        if (changeHighlights.isEmpty()) return;
        int lineCount = Math.max(1, textArea.getParagraphs().size());
        double ratio = Math.max(0, Math.min(1, y / Math.max(1, changeMarkerPane.getHeight())));
        ChangeHighlight nearest = changeHighlights.stream()
                .min(Comparator.comparingDouble(change ->
                        Math.abs(((double) (change.line() - 1) / Math.max(1, lineCount - 1)) - ratio)))
                .orElse(changeHighlights.get(0));
        scrollToChange(nearest);
    }

    private void scrollToChange(ChangeHighlight change) {
        int paragraph = Math.max(0, Math.min(textArea.getParagraphs().size() - 1, change.line() - 1));
        textArea.showParagraphAtCenter(paragraph);
        textArea.requestFocus();
    }

    public String getText() {
        return textArea.getText();
    }

    public void setText(String text) {
        textArea.replaceText(text == null ? "" : text);
        clearChangeHighlights();
    }

    public String getSelectedText() {
        return textArea.getSelectedText();
    }

    public int getCaretPosition() {
        return textArea.getCaretPosition();
    }

    public int getSelectionStart() {
        return textArea.getSelection().getStart();
    }

    public int getSelectionEnd() {
        return textArea.getSelection().getEnd();
    }

    public void replaceText(int start, int end, String text) {
        textArea.replaceText(start, end, text == null ? "" : text);
    }

    public void selectRange(int anchor, int caretPosition) {
        textArea.selectRange(anchor, caretPosition);
    }

    /** 当前选区（优先）或光标的屏幕坐标，用于贴近编辑内容放置提示。 */
    public Optional<Bounds> getSelectionOrCaretBounds() {
        if (getSelectionStart() != getSelectionEnd()) {
            Optional<Bounds> selectionBounds = textArea.getSelectionBounds();
            if (selectionBounds.isPresent()) return selectionBounds;
        }
        return textArea.getCaretBounds();
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

    /** 设置编辑器右键菜单动作，同时提供运行、AI 生成及常用编辑菜单。 */
    public void setContextMenuActions(Runnable onRunSelectedRequest, Runnable onAiSqlRequest) {
        this.onRunSelectedRequest = onRunSelectedRequest;
        this.onAiSqlRequest = onAiSqlRequest;
        if (editorContextMenu != null) return;

        MenuItem runSelectedItem = new MenuItem("运行已选择");
        runSelectedItem.setOnAction(e -> {
            if (this.onRunSelectedRequest != null && !getSelectedText().isBlank()) {
                this.onRunSelectedRequest.run();
            }
        });
        MenuItem aiSqlItem = new MenuItem("AI 生成 SQL");
        aiSqlItem.setOnAction(e -> {
            if (this.onAiSqlRequest != null) this.onAiSqlRequest.run();
        });
        MenuItem cutItem = new MenuItem("剪切");
        cutItem.setOnAction(e -> textArea.cut());
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> textArea.copy());
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> textArea.paste());
        MenuItem selectAllItem = new MenuItem("全选");
        selectAllItem.setOnAction(e -> textArea.selectAll());

        editorContextMenu = new ContextMenu(runSelectedItem, aiSqlItem, new SeparatorMenuItem(),
                cutItem, copyItem, pasteItem, new SeparatorMenuItem(), selectAllItem);
        editorContextMenu.setAutoHide(true);
        editorContextMenu.setHideOnEscape(true);
        editorContextMenu.setOnShowing(e -> {
            boolean noSelection = getSelectedText().isBlank();
            runSelectedItem.setDisable(noSelection);
            cutItem.setDisable(noSelection || !textArea.isEditable());
            copyItem.setDisable(noSelection);
            pasteItem.setDisable(!textArea.isEditable() || !Clipboard.getSystemClipboard().hasString());
        });
        textArea.setOnContextMenuRequested(e -> {
            editorContextMenu.show(textArea, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /** 隐藏当前编辑器右键菜单。 */
    public void hideContextMenu() {
        if (editorContextMenu != null && editorContextMenu.isShowing()) {
            editorContextMenu.hide();
        }
    }

    /** Ctrl+S 回调（保存） */
    public void setOnSaveRequest(Runnable onSaveRequest) {
        this.onSaveRequest = onSaveRequest;
    }

    /** 设置数据库元数据候选；SQL 关键字始终保留，名称不区分大小写去重。 */
    public void setMetadataCompletions(Collection<String> databases,
                                       Collection<String> tables,
                                       Collection<String> columns) {
        Map<String, CompletionItem> unique = new LinkedHashMap<>();
        for (String keyword : KEYWORDS) addCompletion(unique, keyword, "关键字");
        if (databases != null) for (String name : databases) addCompletion(unique, name, "数据库");
        if (tables != null) for (String name : tables) addCompletion(unique, name, "表");
        if (columns != null) for (String name : columns) addCompletion(unique, name, "字段");
        completionItems.clear();
        completionItems.addAll(unique.values());
    }

    private static void addCompletion(Map<String, CompletionItem> target, String text, String kind) {
        if (text == null || text.isBlank()) return;
        target.putIfAbsent(text.toLowerCase(Locale.ROOT), new CompletionItem(text, kind));
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
        setText("");
    }
}
