package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.connect.service.OssService;
import com.tangluobo.tomato.module.connect.service.S3Service;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import javafx.util.Duration;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.utils.FontAwesomeIconFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Markdown 编辑器面板
 * 基于 RichTextFX InlineCssTextArea 编辑 + commonmark 解析 + TextFlow/VBox 预览
 * 支持三种模式：编辑 / 编辑+预览 / 预览
 * 保存到 S3/OSS
 */
public class MarkdownEditorPane extends BorderPane {

    /**
     * 可插拔存储：保存 Markdown 内容到任意后端（S3/OSS/本地文件等）。
     * onSuccess 在 JavaFX 线程之外执行完成后由实现负责切回 JavaFX 线程调用；
     * onError 接收错误消息。
     */
    @FunctionalInterface
    public interface Storage {
        void save(String content, Runnable onSuccess, Consumer<String> onError);
    }

    private final String displayName;
    private final Storage storage;

    private final InlineCssTextArea editor;
    private final VirtualizedScrollPane<InlineCssTextArea> editorScroll;
    private final VBox previewBox;
    private final ScrollPane previewScroll;
    private final StackPane centerContainer;
    private final SplitPane splitPane;

    private String originalContent = "";
    private boolean modified = false;
    private boolean saving = false;
    private Consumer<String> onTitleChange;

    private enum Mode { EDIT, EDIT_PREVIEW, PREVIEW }
    private Mode currentMode = Mode.EDIT_PREVIEW;

    // 编辑器语法高亮样式
    private static final String STYLE_HEADING = "-fx-fill: #1163a6; -fx-font-weight: bold;";
    private static final String STYLE_CODE = "-fx-fill: #c7254e; -fx-font-family: 'Consolas','Courier New',monospace;";
    private static final String STYLE_LINK = "-fx-fill: #1a73e8;";
    private static final String STYLE_BOLD = "-fx-font-weight: bold;";
    private static final String STYLE_ITALIC = "-fx-font-posture: italic;";
    private static final String STYLE_LISTMARK = "-fx-fill: #d14; -fx-font-weight: bold;";
    private static final String STYLE_QUOTEMARK = "-fx-fill: #1a73e8; -fx-font-weight: bold;";

    // 预览解析器：解析非表格片段的 Markdown（Parser 线程安全，构建一次复用）。
    // 表格不依赖 commonmark 扩展，由 renderMarkdown 自行检测并渲染。
    private static final Parser PREVIEW_PARSER = Parser.builder().build();

    private static final java.util.regex.Pattern MD_PATTERN = java.util.regex.Pattern.compile(
            "(?<HEADING>^#{1,6}\\s.*$)" +
            "|(?<CODE>`[^`\\n]+`)" +
            "|(?<LINK>\\[[^\\]\\n]*\\]\\([^)\\n]+\\))" +
            "|(?<BOLD>\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__)" +
            "|(?<ITALIC>\\*[^*\\n]+\\*|_[^_\\n]+_)" +
            "|(?<LISTMARK>^\\s*[-*+]\\s)" +
            "|(?<QUOTEMARK>^>\\s)",
            java.util.regex.Pattern.MULTILINE
    );

    // 自动续行匹配：前缀（列表/引用/任务标记）+ 行内容
    private static final java.util.regex.Pattern AUTO_INDENT_PATTERN = java.util.regex.Pattern.compile(
            "^(?<prefix>\\s*(?:[-*+]|\\d+\\.)\\s+(?:\\[[ xX]]\\s+)?|\\s*(?:>\\s*)+)(?<content>.*)$"
    );
    private static final java.util.regex.Pattern ORDERED_PREFIX_PATTERN =
            java.util.regex.Pattern.compile("^(\\s*)(\\d+)\\.(.*)$");

    // 预览渲染防抖
    private final PauseTransition previewDebounce = new PauseTransition(new Duration(250));

    /**
     * S3/OSS 编辑器构造：通过 config/bucket/key 保存到对象存储。
     * 委托给通用 {@link #MarkdownEditorPane(String, String, Storage)}。
     */
    public MarkdownEditorPane(ConnectionConfig config, String bucket, String key, String displayName, String initialContent) {
        this(displayName, initialContent, (content, onSuccess, onError) -> new Thread(() -> {
            try {
                boolean isOSS = config.getType() == ConnectType.ALIYUN_OSS;
                if (isOSS) {
                    OssService.putObject(config, bucket, key, content);
                } else {
                    S3Service.putObject(config, bucket, key, content);
                }
                Platform.runLater(onSuccess);
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e.getMessage()));
            }
        }, "MD-Save").start());
    }

    /**
     * 通用 Markdown 编辑器构造：保存逻辑由 {@link Storage} 注入，
     * 可对接 S3/OSS/本地文件等任意后端。
     */
    public MarkdownEditorPane(String displayName, String initialContent, Storage storage) {
        this.displayName = displayName;
        this.storage = storage;

        this.editor = new InlineCssTextArea();
        this.editor.setStyle(
                "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                "-fx-background-color: white; -fx-padding: 8; -fx-text-fill: #333;"
        );
        this.editorScroll = new VirtualizedScrollPane<>(editor);

        this.previewBox = new VBox(6);
        this.previewBox.setPadding(new Insets(12, 16, 12, 16));
        this.previewBox.setStyle("-fx-background-color: white;");
        this.previewScroll = new ScrollPane(previewBox);
        this.previewScroll.setFitToWidth(true);
        this.previewScroll.setFitToHeight(true);
        this.previewScroll.setStyle("-fx-background-color: white;");

        this.splitPane = new SplitPane();
        this.splitPane.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        this.splitPane.getItems().addAll(editorScroll, previewScroll);
        this.splitPane.setDividerPositions(0.5);

        this.centerContainer = new StackPane();
        setCenter(centerContainer);

        // 顶部工具栏
        setTop(buildToolbar());

        // 初始化编辑器内容与高亮
        if (initialContent == null) initialContent = "";
        this.originalContent = initialContent;
        this.editor.replaceText(initialContent);
        applyHighlighting();
        updatePreview();

        // 编辑器内容变化：实时更新高亮与预览（纯编辑模式下不渲染预览以省开销）
        editor.textProperty().addListener((obs, oldVal, newVal) -> {
            applyHighlighting();
            if (currentMode != Mode.EDIT) {
                updatePreview();
            }
            boolean nowModified = !newVal.equals(originalContent);
            if (nowModified != modified) {
                modified = nowModified;
                notifyTitleChange();
            }
        });

        // 快捷键（参考 markdown-writer-fx）
        editor.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown()) {
                boolean shift = e.isShiftDown();
                switch (e.getCode()) {
                    case S:             e.consume(); save();                          return;
                    case B:             e.consume(); toggleWrap("**", "**");          return;
                    case I:             e.consume(); toggleWrap("*", "*");            return;
                    case T:             e.consume(); toggleWrap("~~", "~~");          return;
                    case BACK_QUOTE:    e.consume();
                        if (shift) insertCodeBlock(); else toggleWrap("`", "`");
                        return;
                    case L:             e.consume(); insertLink();                    return;
                    case G:             e.consume(); insertImage();                   return;
                    case Q:             e.consume(); togglePrefix("> ");             return;
                    case U:             e.consume(); togglePrefix("- ");             return;
                    case DIGIT1:        e.consume(); togglePrefix("# ");             return;
                    case DIGIT2:        e.consume(); togglePrefix("## ");            return;
                    case DIGIT3:        e.consume(); togglePrefix("### ");           return;
                    default: break;
                }
            }
            if (e.getCode() == KeyCode.TAB) {
                e.consume();
                handleTab(e.isShiftDown());
                return;
            }
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                if (handleEnter()) {
                    e.consume();
                }
            }
        });

        applyMode();
    }

    // ==================== 工具栏 ====================
    // 参考 markdown-writer-fx：原生 ToolBar + FontAwesome 图标 + 透明/hover/selected 样式
    // ActionUtils.createToolBarButton 同款实现：graphic=图标、tooltip=文字+快捷键、focusTraversable=false

    private Node buildToolbar() {
        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("markdown-tool-bar");
        toolBar.getStylesheets().add(getClass().getResource("/css/markdown-editor-toolbar.css").toExternalForm());

        // 撤销 / 重做
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.UNDO, "撤销", "Ctrl+Z", editor::undo),
                iconBtn(FontAwesomeIcon.REPEAT, "重做", "Ctrl+Y", editor::redo),
                new Separator());

        // 行内格式
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.BOLD, "加粗", "Ctrl+B", () -> toggleWrap("**", "**")),
                iconBtn(FontAwesomeIcon.ITALIC, "斜体", "Ctrl+I", () -> toggleWrap("*", "*")),
                iconBtn(FontAwesomeIcon.STRIKETHROUGH, "删除线", "Ctrl+T", () -> toggleWrap("~~", "~~")),
                iconBtn(FontAwesomeIcon.CODE, "行内代码", "Ctrl+`", () -> toggleWrap("`", "`")),
                new Separator());

        // 链接 / 图片
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.LINK, "链接", "Ctrl+L", this::insertLink),
                iconBtn(FontAwesomeIcon.PICTURE_ALT, "图片", "Ctrl+G", this::insertImage),
                new Separator());

        // 标题（图标相同，用 tooltip 区分级别）
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.HEADER, "标题1", "Ctrl+1", () -> togglePrefix("# ")),
                iconBtn(FontAwesomeIcon.HEADER, "标题2", "Ctrl+2", () -> togglePrefix("## ")),
                iconBtn(FontAwesomeIcon.HEADER, "标题3", "Ctrl+3", () -> togglePrefix("### ")),
                new Separator());

        // 块级
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.LIST_UL, "无序列表", "Ctrl+U", () -> togglePrefix("- ")),
                iconBtn(FontAwesomeIcon.LIST_OL, "有序列表", null, () -> togglePrefix("1. ")),
                iconBtn(FontAwesomeIcon.CHECK_SQUARE, "任务列表", null, () -> togglePrefix("- [ ] ")),
                iconBtn(FontAwesomeIcon.QUOTE_LEFT, "引用", "Ctrl+Q", () -> togglePrefix("> ")),
                iconBtn(FontAwesomeIcon.FILE_CODE_ALT, "代码块", "Ctrl+Shift+`", this::insertCodeBlock),
                iconBtn(FontAwesomeIcon.MINUS, "分隔线", null, this::insertHr),
                iconBtn(FontAwesomeIcon.TABLE, "表格", null, this::insertTable));

        // 弹性空白（ToolBar 内部为 HBox，HBox.setHgrow 有效）
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolBar.getItems().add(spacer);

        // 模式切换
        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton editBtn = modeToggle(modeGroup, FontAwesomeIcon.PENCIL, "编辑", Mode.EDIT);
        ToggleButton splitBtn = modeToggle(modeGroup, FontAwesomeIcon.COLUMNS, "编辑+预览", Mode.EDIT_PREVIEW);
        ToggleButton previewBtn = modeToggle(modeGroup, FontAwesomeIcon.EYE, "预览", Mode.PREVIEW);
        splitBtn.setSelected(true);
        toolBar.getItems().addAll(editBtn, splitBtn, previewBtn, new Separator());

        // 保存
        Button saveBtn = iconBtn(FontAwesomeIcon.FLOPPY_ALT, "保存", "Ctrl+S", this::save);
        saveBtn.getStyleClass().add("save-button");
        toolBar.getItems().add(saveBtn);

        return toolBar;
    }

    /** 工具栏图标按钮：FontAwesome 图标 + tooltip(文字+快捷键) + 不抢焦点 */
    private Button iconBtn(FontAwesomeIcon icon, String text, String shortcut, Runnable action) {
        Button b = new Button();
        b.setGraphic(FontAwesomeIconFactory.get().createIcon(icon, "1.2em"));
        b.setTooltip(new Tooltip(shortcut != null ? text + " (" + shortcut + ")" : text));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private ToggleButton modeToggle(ToggleGroup group, FontAwesomeIcon icon, String text, Mode mode) {
        ToggleButton b = new ToggleButton();
        b.setToggleGroup(group);
        b.setGraphic(FontAwesomeIconFactory.get().createIcon(icon, "1.2em"));
        b.setTooltip(new Tooltip(text));
        b.setFocusTraversable(false);
        b.setOnAction(e -> { currentMode = mode; applyMode(); });
        return b;
    }

    private void applyMode() {
        centerContainer.getChildren().clear();
        switch (currentMode) {
            case EDIT -> centerContainer.getChildren().setAll(editorScroll);
            case EDIT_PREVIEW -> {
                centerContainer.getChildren().setAll(splitPane);
                updatePreview();
            }
            case PREVIEW -> {
                centerContainer.getChildren().setAll(previewScroll);
                updatePreview();
            }
        }
    }

    // ==================== 编辑器操作 ====================
    // 参考 markdown-writer-fx SmartEdit：智能切换（已包裹/已有前缀则取消）

    /** 行内包裹：选区已包裹 before/after 则去除，否则包裹。无选区时插入占位并选中占位文本。 */
    private void toggleWrap(String before, String after) {
        var sel = editor.getSelection();
        if (sel.getLength() == 0) {
            int pos = editor.getCaretPosition();
            editor.insertText(pos, before + after);
            editor.moveTo(pos + before.length());
        } else {
            String selected = editor.getSelectedText();
            int s = sel.getStart(), e = sel.getEnd();
            String text = editor.getText();
            boolean wrapped =
                    s + before.length() <= e - after.length() + after.length()
                    && s + before.length() <= text.length()
                    && e - after.length() >= 0
                    && text.startsWith(before, s)
                    && text.startsWith(after, e - after.length())
                    && (e - after.length()) >= (s + before.length());
            if (wrapped) {
                String inner = text.substring(s + before.length(), e - after.length());
                editor.replaceText(s, e, inner);
                editor.selectRange(s, s + inner.length());
            } else {
                editor.replaceText(s, e, before + selected + after);
                editor.selectRange(s + before.length(), s + before.length() + selected.length());
            }
        }
        editor.requestFocus();
    }

    /** 行首前缀：当前行已有该前缀则去除，否则添加。支持多行选区逐行切换。 */
    private void togglePrefix(String prefix) {
        var sel = editor.getSelection();
        String text = editor.getText();
        int start = sel.getStart();
        int end = sel.getEnd();
        if (start == end) {
            // 单行：以光标所在行为准
            start = lineStart(text, start);
            end = (text.indexOf('\n', start) < 0 ? text.length() : text.indexOf('\n', start));
        } else {
            start = lineStart(text, start);
            if (end < text.length() && text.charAt(end) != '\n') {
                end = (text.indexOf('\n', end) < 0 ? text.length() : text.indexOf('\n', end));
            }
        }
        String block = text.substring(start, end);
        boolean allPrefixed = true;
        for (String ln : block.split("\n", -1)) {
            if (!ln.startsWith(prefix)) { allPrefixed = false; break; }
        }
        StringBuilder nb = new StringBuilder();
        for (String ln : block.split("\n", -1)) {
            if (nb.length() > 0) nb.append('\n');
            if (allPrefixed) {
                if (ln.startsWith(prefix)) nb.append(ln.substring(prefix.length()));
                else nb.append(ln);
            } else {
                nb.append(prefix).append(ln);
            }
        }
        editor.replaceText(start, end, nb.toString());
        editor.selectRange(start, start + nb.length());
        editor.requestFocus();
    }

    private void insertLink() {
        var sel = editor.getSelection();
        String selected = sel.getLength() == 0 ? "链接文本" : editor.getSelectedText();
        int s = sel.getStart(), e = sel.getEnd();
        String url = "url";
        String md = "[" + selected + "](" + url + ")";
        editor.replaceText(s, e, md);
        // 选中 url 便于直接输入
        int urlStart = s + selected.length() + 3;
        editor.selectRange(urlStart, urlStart + url.length());
        editor.requestFocus();
    }

    private void insertImage() {
        var sel = editor.getSelection();
        String selected = sel.getLength() == 0 ? "替代文本" : editor.getSelectedText();
        int s = sel.getStart(), e = sel.getEnd();
        String url = "url";
        String md = "![" + selected + "](" + url + ")";
        editor.replaceText(s, e, md);
        int urlStart = s + selected.length() + 4;
        editor.selectRange(urlStart, urlStart + url.length());
        editor.requestFocus();
    }

    private void insertCodeBlock() {
        String block = "\n```\n\n```\n";
        int pos = editor.getCaretPosition();
        editor.insertText(pos, block);
        // 光标放到代码块中间空行
        editor.moveTo(pos + 5);
        editor.requestFocus();
    }

    private void insertHr() {
        int pos = editor.getCaretPosition();
        String text = editor.getText();
        String ins = (pos > 0 && text.charAt(pos - 1) != '\n' ? "\n\n" : "\n") + "---\n";
        editor.insertText(pos, ins);
        editor.moveTo(pos + ins.length());
        editor.requestFocus();
    }

    private void insertTable() {
        String tbl = "\n| 列1 | 列2 | 列3 |\n|---|---|---|\n|  |  |  |\n|  |  |  |\n";
        int pos = editor.getCaretPosition();
        editor.insertText(pos, tbl);
        editor.moveTo(pos + tbl.length());
        editor.requestFocus();
    }

    private static int lineStart(String text, int pos) {
        if (pos <= 0) return 0;
        int idx = text.lastIndexOf('\n', pos - 1);
        return idx < 0 ? 0 : idx + 1;
    }

    /** ENTER 自动续行：列表/引用/任务标记行续上相同前缀，有序列表数字递增；空标记行清空标记。 */
    private boolean handleEnter() {
        int caret = editor.getCaretPosition();
        String text = editor.getText();
        int ls = lineStart(text, caret);
        int nl = text.indexOf('\n', caret);
        String line = nl < 0 ? text.substring(ls) : text.substring(ls, nl);

        java.util.regex.Matcher m = AUTO_INDENT_PATTERN.matcher(line);
        if (!m.matches()) return false;
        String prefix = m.group("prefix");
        String content = m.group("content");

        if (content.isEmpty()) {
            // 空标记行：清除当前行标记
            int end = nl < 0 ? text.length() : nl;
            editor.replaceText(ls, end, "");
            editor.moveTo(ls);
            return true;
        }
        String newPrefix = incrementPrefix(prefix);
        String insert = "\n" + newPrefix;
        editor.insertText(caret, insert);
        editor.moveTo(caret + insert.length());
        return true;
    }

    private static String incrementPrefix(String prefix) {
        java.util.regex.Matcher m = ORDERED_PREFIX_PATTERN.matcher(prefix);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(2)) + 1;
            return m.group(1) + n + "." + m.group(3);
        }
        return prefix;
    }

    /** Tab：无选区插入 4 空格；多行选区逐行加 4 空格。Shift+Tab：逐行去前导最多 4 空格。 */
    private void handleTab(boolean shift) {
        var sel = editor.getSelection();
        String text = editor.getText();
        int s = sel.getStart(), e = sel.getEnd();
        if (s == e) {
            if (shift) {
                int ls = lineStart(text, s);
                int max = Math.min(4, text.length() - ls);
                int cnt = 0;
                while (cnt < max && text.charAt(ls + cnt) == ' ') cnt++;
                if (cnt > 0) {
                    editor.replaceText(ls, ls + cnt, "");
                    editor.moveTo(s - cnt);
                }
            } else {
                editor.insertText(s, "    ");
            }
            return;
        }
        int start = lineStart(text, s);
        int end = e;
        if (end < text.length() && text.charAt(end) != '\n') {
            int n = text.indexOf('\n', end);
            end = n < 0 ? text.length() : n;
        }
        String block = text.substring(start, end);
        StringBuilder nb = new StringBuilder();
        for (String ln : block.split("\n", -1)) {
            if (nb.length() > 0) nb.append('\n');
            if (shift) {
                int cnt = 0;
                int max = Math.min(4, ln.length());
                while (cnt < max && ln.charAt(cnt) == ' ') cnt++;
                nb.append(ln.substring(cnt));
            } else {
                nb.append("    ").append(ln);
            }
        }
        editor.replaceText(start, end, nb.toString());
        editor.selectRange(start, start + nb.length());
    }

    // ==================== 语法高亮 ====================

    private void applyHighlighting() {
        String text = editor.getText();
        if (text.isEmpty()) return;
        try {
            java.util.regex.Matcher m = MD_PATTERN.matcher(text);
            StyleSpansBuilder<String> b = new StyleSpansBuilder<>();
            int last = 0;
            while (m.find()) {
                String style;
                if (m.group("HEADING") != null) style = STYLE_HEADING;
                else if (m.group("CODE") != null) style = STYLE_CODE;
                else if (m.group("LINK") != null) style = STYLE_LINK;
                else if (m.group("BOLD") != null) style = STYLE_BOLD;
                else if (m.group("ITALIC") != null) style = STYLE_ITALIC;
                else if (m.group("LISTMARK") != null) style = STYLE_LISTMARK;
                else if (m.group("QUOTEMARK") != null) style = STYLE_QUOTEMARK;
                else style = "";
                if (m.start() > last) b.add("", m.start() - last);
                b.add(style, m.end() - m.start());
                last = m.end();
            }
            if (last < text.length()) b.add("", text.length() - last);
            editor.setStyleSpans(0, b.create());
        } catch (Exception e) {
            System.err.println("Markdown高亮异常: " + e.getMessage());
        }
    }

    // ==================== 预览渲染 ====================

    private void updatePreview() {
        String md = editor.getText();
        previewBox.getChildren().clear();
        try {
            InlineStyle base = new InlineStyle();
            renderMarkdown(md, base, previewBox.getChildren());
        } catch (Exception e) {
            Label err = new Label("预览渲染失败: " + e.getMessage());
            err.setStyle("-fx-text-fill: #c00; -fx-font-size: 11px;");
            previewBox.getChildren().add(err);
        }
    }

    /**
     * 分段渲染 Markdown：自行检测 GFM 表格块并渲染为表格，其余文本交给 commonmark 解析。
     * 不依赖 commonmark-ext-gfm-tables 扩展。
     */
    private void renderMarkdown(String md, InlineStyle base, ObservableList<Node> target) {
        String[] lines = md.split("\n", -1);
        int i = 0;
        StringBuilder textBuf = new StringBuilder();
        while (i < lines.length) {
            // 表格块：第 i 行为表头行，第 i+1 行为分隔行，且分隔行有效
            if (i + 1 < lines.length && isTableRow(lines[i]) && isDelimiterRow(lines[i + 1])) {
                // 先把累积的文本片段渲染出来
                flushText(textBuf, base, target);
                // 收集表格块：表头 + 分隔行 + 连续的数据行
                java.util.List<String> tableLines = new ArrayList<>();
                tableLines.add(lines[i]);
                tableLines.add(lines[i + 1]);
                int j = i + 2;
                while (j < lines.length && isTableRow(lines[j])) {
                    tableLines.add(lines[j]);
                    j++;
                }
                renderTableLines(tableLines, base, target);
                i = j;
            } else {
                if (textBuf.length() > 0) textBuf.append('\n');
                textBuf.append(lines[i]);
                i++;
            }
        }
        flushText(textBuf, base, target);
    }

    /** 将累积的文本片段交给 commonmark 解析并渲染为块 */
    private void flushText(StringBuilder textBuf, InlineStyle base, ObservableList<Node> target) {
        if (textBuf.length() == 0) return;
        String text = textBuf.toString();
        textBuf.setLength(0);
        org.commonmark.node.Node document = PREVIEW_PARSER.parse(text);
        renderBlocks(document, base, target);
    }

    /** 是否为表格行：非空且包含 | */
    private boolean isTableRow(String line) {
        String t = line.trim();
        return !t.isEmpty() && t.indexOf('|') >= 0;
    }

    /** 是否为表格分隔行：形如 |---|:---:|---:| 或 ---|--- ，每段至少一个 - */
    private static final java.util.regex.Pattern DELIM_ROW =
            java.util.regex.Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");

    private boolean isDelimiterRow(String line) {
        if (line == null || !line.contains("-")) return false;
        if (!DELIM_ROW.matcher(line).matches()) return false;
        // 至少有一个 | 或本身就是 ---...（单列无 | 也允许）
        return line.contains("|") || line.contains("-");
    }

    /** 拆分表格行为单元格：去掉首尾 | 后按 | 切分，保留转义 \\| */
    private java.util.List<String> splitTableRow(String line) {
        String t = line.trim();
        // 去掉首尾的 |（仅当两端都有时）
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
        java.util.List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '\\' && k + 1 < t.length() && t.charAt(k + 1) == '|') {
                cur.append('|');
                k++;
            } else if (c == '|') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    /** 单元格对齐方式 */
    private enum CellAlign { LEFT, CENTER, RIGHT }

    private CellAlign parseAlign(String delimCell) {
        String c = delimCell.trim();
        boolean left = c.startsWith(":");
        boolean right = c.endsWith(":");
        if (left && right) return CellAlign.CENTER;
        if (right) return CellAlign.RIGHT;
        return CellAlign.LEFT; // 默认/仅左冒号都按左
    }

    private void renderBlocks(org.commonmark.node.Node parent, InlineStyle base, ObservableList<Node> target) {
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            renderBlock(child, base, target);
        }
    }

    private void renderBlock(org.commonmark.node.Node node, InlineStyle base, ObservableList<Node> target) {
        if (node instanceof Paragraph p) {
            List<Text> inlines = new ArrayList<>();
            renderInline(p, base, inlines);
            target.add(new TextFlow(inlines.toArray(new Text[0])));
        } else if (node instanceof Heading h) {
            List<Text> inlines = new ArrayList<>();
            renderInline(h, base, inlines);
            int size = headingSize(h.getLevel());
            for (Text t : inlines) {
                String s = t.getStyle() == null ? "" : t.getStyle();
                t.setStyle(s + " -fx-font-size: " + size + "px; -fx-font-weight: bold;");
            }
            TextFlow flow = new TextFlow(inlines.toArray(new Text[0]));
            flow.setPadding(new Insets(6, 0, 4, 0));
            target.add(flow);
        } else if (node instanceof BlockQuote bq) {
            VBox quoteBox = new VBox(4);
            quoteBox.setPadding(new Insets(4, 0, 4, 12));
            quoteBox.setStyle("-fx-border-color: #1a73e8; -fx-border-width: 0 0 0 3; -fx-background-color: #f8f9fa; -fx-background-radius: 0;");
            InlineStyle qs = base.copy();
            qs.quote = true;
            renderBlocks(bq, qs, quoteBox.getChildren());
            target.add(quoteBox);
        } else if (node instanceof BulletList bl) {
            renderList(bl, base, target, false, 1);
        } else if (node instanceof OrderedList ol) {
            renderList(ol, base, target, true, ol.getStartNumber());
        } else if (node instanceof FencedCodeBlock fcb) {
            target.add(renderCodeBlock(fcb.getLiteral(), fcb.getInfo()));
        } else if (node instanceof IndentedCodeBlock icb) {
            target.add(renderCodeBlock(icb.getLiteral(), ""));
        } else if (node instanceof ThematicBreak) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(8, 0, 8, 0));
            target.add(sep);
        } else if (node instanceof HtmlBlock hb) {
            Label l = new Label(hb.getLiteral());
            l.setStyle("-fx-font-family: 'Consolas',monospace; -fx-font-size: 11px; -fx-text-fill: #888;");
            l.setWrapText(true);
            target.add(l);
        } else {
            renderBlocks(node, base, target);
        }
    }

    /** 渲染代码块：按语言做轻量语法高亮，放入带背景的容器 */
    private Node renderCodeBlock(String literal, String info) {
        String lang = info == null ? "" : info.trim().toLowerCase();
        List<Text> parts = new ArrayList<>();
        highlightCode(literal, lang, parts);
        if (parts.isEmpty()) {
            Text t = new Text(literal);
            t.setStyle("-fx-fill: #333; -fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12px;");
            parts.add(t);
        }
        TextFlow flow = new TextFlow(parts.toArray(new Text[0]));
        flow.setPadding(new Insets(8, 12, 8, 12));
        StackPane pane = new StackPane(flow);
        pane.setStyle("-fx-background-color: #f6f8fa; -fx-background-radius: 4; " +
                "-fx-border-color: #e0e0e0; -fx-border-radius: 4;");
        return pane;
    }

    // 代码高亮配色
    private static final String HL_KEYWORD = "-fx-fill: #d73a49;";   // 关键字 红
    private static final String HL_STRING = "-fx-fill: #032f62;";   // 字符串 深蓝
    private static final String HL_COMMENT = "-fx-fill: #6a737d;";  // 注释 灰
    private static final String HL_NUMBER = "-fx-fill: #005cc5;";    // 数字 蓝
    private static final String HL_ANNOT = "-fx-fill: #6f42c1;";    // 注解/装饰器 紫
    private static final String HL_BASE = "-fx-fill: #24292e;";     // 默认文本
    private static final String HL_FUNC = "-fx-fill: #6f42c1;";     // 函数名 紫

    /** 通用关键字集合（覆盖 Java/JS/TS/Python/SQL/Go/C/C++/PHP/Shell 等常见词）。
     *  用 HashSet 容纳，各语言区段可能有重复词，去重后存入。 */
    private static final java.util.Set<String> KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            // 通用
            "if","else","for","while","do","return","break","continue","switch","case","default",
            "true","false","null","none","nil","undefined","and","or","not","in","is","as","lambda",
            "import","from","package","include","require","export","class","struct","enum","interface",
            "extends","implements","public","private","protected","static","final","const","let","var",
            "def","func","fun","function","fn","void","new","this","super","self","try","catch","finally",
            "throw","throws","raise","yield","async","await","with","using","namespace","typedef",
            // Java
            "abstract","boolean","byte","char","double","float","int","long","short","instanceof","synchronized","volatile","transient","native",
            // SQL
            "select","where","insert","update","delete","create","table","drop","alter","into","values","set","join","left","right","inner","outer","group","by","order","having","limit","distinct","primary","key","foreign","references","index","unique","between","like","exists","union","all",
            // Python
            "elif","endif","endfor","print","assert","global","nonlocal","del","pass",
            // Go/Rust/C/C++
            "go","defer","chan","range","map","make","len","ptr","ref","mut","pub","crate","mod","impl","trait","unsafe","move","sizeof",
            // Shell
            "echo","exit","then","fi","done","esac","local","declare"
    ));

    /** 行注释前缀（按语言）：`//` 用于 C 系，`#` 用于脚本/配置类，`--` 用于 SQL */
    private static String lineCommentPrefix(String lang) {
        return switch (lang) {
            case "", "python", "py", "ruby", "rb", "perl", "pl", "shell", "sh", "bash", "zsh",
                 "yaml", "yml", "toml", "ini", "properties", "conf", "dockerfile", "makefile",
                 "ps1", "powershell", "r", "plaintext" -> "#";
            case "sql" -> "--";
            default -> "//"; // java, js, ts, go, rust, c, cpp, php, css, json, kotlin, scala, swift...
        };
    }

    /** 轻量正则语法高亮：按 token 切分并着色，结果追加到 out。非线程安全（仅 JavaFX 线程调用）。 */
    private void highlightCode(String code, String lang, List<Text> out) {
        if (code == null || code.isEmpty()) return;
        String linePrefix = lineCommentPrefix(lang);
        // token 顺序：块注释 → 字符串(含模板/原始) → 行注释 → 数字 → 注解 → 标识符(关键字/函数)
        String blockComment = "/\\*[\\s\\S]*?\\*/";
        String stringPat = "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`";
        String lineComment = java.util.regex.Pattern.quote(linePrefix) + "[^\\n]*";
        String number = "\\b\\d[\\d_]*\\.?\\d*([eE][+-]?\\d+)?[fFdDuUlL]?\\b|0[xX][0-9a-fA-F_]+|0[bB][01_]+";
        String annotation = "@[A-Za-z_][A-Za-z0-9_]*";
        String ident = "[A-Za-z_$][A-Za-z0-9_$]*";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?<BLOCK>" + blockComment + ")" +
                "|(?<STRING>" + stringPat + ")" +
                "|(?<LINE>" + lineComment + ")" +
                "|(?<NUMBER>" + number + ")" +
                "|(?<ANNOT>" + annotation + ")" +
                "|(?<IDENT>" + ident + ")"
        );
        java.util.regex.Matcher m = p.matcher(code);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(codeText(code.substring(last, m.start()), HL_BASE));
            }
            String style;
            if (m.group("BLOCK") != null || m.group("LINE") != null) {
                style = HL_COMMENT;
            } else if (m.group("STRING") != null) {
                style = HL_STRING;
            } else if (m.group("NUMBER") != null) {
                style = HL_NUMBER;
            } else if (m.group("ANNOT") != null) {
                style = HL_ANNOT;
            } else {
                String word = m.group("IDENT");
                if (KEYWORDS.contains(word)) {
                    style = HL_KEYWORD;
                } else {
                    // 函数调用：标识符后跟空白*(
                    int end = m.end();
                    int j = end;
                    while (j < code.length() && (code.charAt(j) == ' ' || code.charAt(j) == '\t')) j++;
                    style = (j < code.length() && code.charAt(j) == '(') ? HL_FUNC : HL_BASE;
                }
            }
            out.add(codeText(m.group(), style));
            last = m.end();
        }
        if (last < code.length()) {
            out.add(codeText(code.substring(last), HL_BASE));
        }
    }

    private Text codeText(String content, String style) {
        Text t = new Text(content);
        t.setStyle(style + " -fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12px;");
        return t;
    }

    /**
     * 由原始表格行（表头行、分隔行、数据行）渲染为 JavaFX GridPane。
     * 表头加粗+浅灰底，单元格按分隔行声明的对齐方式排版，带边框。
     */
    private void renderTableLines(java.util.List<String> tableLines, InlineStyle base, ObservableList<Node> target) {
        if (tableLines.size() < 2) return;
        // 第 0 行：表头；第 1 行：分隔（决定对齐）；其余：数据行
        java.util.List<String> headerCells = splitTableRow(tableLines.get(0));
        java.util.List<String> delimCells = splitTableRow(tableLines.get(1));
        int colCount = headerCells.size();
        // 解析每列对齐
        CellAlign[] aligns = new CellAlign[colCount];
        for (int c = 0; c < colCount; c++) {
            aligns[c] = (c < delimCells.size()) ? parseAlign(delimCells.get(c)) : CellAlign.LEFT;
        }

        GridPane grid = new GridPane();
        grid.setHgap(0);
        grid.setVgap(0);
        grid.setStyle("-fx-border-color: #dfe2e5; -fx-border-width: 1 1 0 0; -fx-background-color: white;");

        int row = 0;
        // 表头
        for (int c = 0; c < colCount; c++) {
            grid.add(tableCell(headerCells.get(c), aligns[c], true, base), c, row);
        }
        row++;
        // 数据行
        for (int r = 2; r < tableLines.size(); r++) {
            java.util.List<String> cells = splitTableRow(tableLines.get(r));
            for (int c = 0; c < colCount; c++) {
                String content = c < cells.size() ? cells.get(c) : "";
                grid.add(tableCell(content, aligns[c], false, base), c, row);
            }
            row++;
        }
        target.add(grid);
    }

    /** 渲染单个表格单元格为带边框/背景的 StackPane，内部为解析行内格式的 TextFlow */
    private StackPane tableCell(String content, CellAlign align, boolean header, InlineStyle base) {
        List<Text> inlines = new ArrayList<>();
        if (content.isEmpty()) {
            inlines.add(styledText("", base));
        } else {
            // 用 commonmark 解析单元格内的行内格式（粗体/代码/链接等）
            org.commonmark.node.Node cellDoc = PREVIEW_PARSER.parse(content);
            org.commonmark.node.Node first = cellDoc.getFirstChild();
            if (first instanceof Paragraph p) {
                renderInline(p, base, inlines);
            } else {
                inlines.add(styledText(content, base));
            }
        }
        if (header) {
            for (Text t : inlines) {
                String s = t.getStyle() == null ? "" : t.getStyle();
                t.setStyle(s + " -fx-font-weight: bold;");
            }
        }
        TextFlow flow = new TextFlow(inlines.toArray(new Text[0]));
        StackPane pane = new StackPane(flow);
        pane.setPadding(new Insets(6, 10, 6, 10));
        String bg = header ? "#f6f8fa" : "white";
        String alignCss = switch (align) {
            case CENTER -> "-fx-alignment: center; -fx-text-alignment: center;";
            case RIGHT -> "-fx-alignment: center-right; -fx-text-alignment: right;";
            default -> "-fx-alignment: center-left; -fx-text-alignment: left;";
        };
        pane.setStyle("-fx-border-color: #dfe2e5; -fx-border-width: 0 0 1 1; " +
                "-fx-background-color: " + bg + "; " + alignCss);
        return pane;
    }

    private void renderList(org.commonmark.node.Node list, InlineStyle base, ObservableList<Node> target, boolean ordered, int start) {
        VBox listBox = new VBox(2);
        int index = start;
        for (org.commonmark.node.Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) continue;
            String marker = ordered ? (index + ". ") : "• ";
            org.commonmark.node.Node firstChild = item.getFirstChild();

            List<Text> inlines = new ArrayList<>();
            inlines.add(styledText(marker, base));
            if (firstChild instanceof Paragraph p) {
                renderInline(p, base, inlines);
            } else {
                renderInline(item, base, inlines);
            }
            TextFlow flow = new TextFlow(inlines.toArray(new Text[0]));
            HBox itemBox = new HBox(flow);
            itemBox.setPadding(new Insets(0, 0, 0, 16));
            listBox.getChildren().add(itemBox);

            // 处理 list item 中的后续子块（嵌套列表/段落等）
            org.commonmark.node.Node child = firstChild != null ? firstChild.getNext() : null;
            while (child != null) {
                if (child instanceof BulletList) {
                    renderList(child, base, listBox.getChildren(), false, 1);
                } else if (child instanceof OrderedList ol2) {
                    renderList(ol2, base, listBox.getChildren(), true, ol2.getStartNumber());
                } else if (child instanceof Paragraph p2) {
                    List<Text> sub = new ArrayList<>();
                    renderInline(p2, base, sub);
                    TextFlow subFlow = new TextFlow(sub.toArray(new Text[0]));
                    subFlow.setPadding(new Insets(0, 0, 0, 16));
                    listBox.getChildren().add(subFlow);
                } else {
                    renderBlock(child, base, listBox.getChildren());
                }
                child = child.getNext();
            }
            index++;
        }
        target.add(listBox);
    }

    private void renderInline(org.commonmark.node.Node node, InlineStyle style, List<Text> out) {
        for (org.commonmark.node.Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text textNode) {
                out.add(styledText(textNode.getLiteral(), style));
            } else if (child instanceof Code codeNode) {
                InlineStyle cs = style.copy();
                cs.code = true;
                out.add(styledText(codeNode.getLiteral(), cs));
            } else if (child instanceof Emphasis) {
                InlineStyle cs = style.copy();
                cs.italic = true;
                renderInline(child, cs, out);
            } else if (child instanceof StrongEmphasis) {
                InlineStyle cs = style.copy();
                cs.bold = true;
                renderInline(child, cs, out);
            } else if (child instanceof Link link) {
                InlineStyle cs = style.copy();
                cs.linkUrl = link.getDestination();
                renderInline(child, cs, out);
            } else if (child instanceof Image) {
                InlineStyle cs = style.copy();
                cs.italic = true;
                out.add(styledText("[图片]", cs));
                renderInline(child, cs, out);
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                out.add(new Text("\n"));
            } else if (child instanceof HtmlInline html) {
                out.add(styledText(html.getLiteral(), style));
            } else {
                renderInline(child, style, out);
            }
        }
    }

    private Text styledText(String content, InlineStyle style) {
        Text t = new Text(content);
        StringBuilder sb = new StringBuilder("-fx-font-size: 13px;");
        if (style.bold) sb.append(" -fx-font-weight: bold;");
        if (style.italic) sb.append(" -fx-font-posture: italic;");
        if (style.code) {
            sb.append(" -fx-font-family: 'Consolas','Courier New',monospace;");
            sb.append(" -fx-fill: #c7254e;");
        } else if (style.linkUrl != null) {
            sb.append(" -fx-fill: #1a73e8; -fx-underline: true;");
        } else if (style.quote) {
            sb.append(" -fx-fill: #666;");
        } else {
            sb.append(" -fx-fill: #333;");
        }
        t.setStyle(sb.toString());
        return t;
    }

    private int headingSize(int level) {
        return switch (level) {
            case 1 -> 24;
            case 2 -> 20;
            case 3 -> 17;
            case 4 -> 15;
            case 5 -> 13;
            case 6 -> 12;
            default -> 13;
        };
    }

    private static class InlineStyle {
        boolean bold = false;
        boolean italic = false;
        boolean code = false;
        String linkUrl = null;
        boolean quote = false;

        InlineStyle copy() {
            InlineStyle c = new InlineStyle();
            c.bold = bold;
            c.italic = italic;
            c.code = code;
            c.linkUrl = linkUrl;
            c.quote = quote;
            return c;
        }
    }

    // ==================== 保存 ====================

    public void save() {
        if (saving) return;
        saving = true;
        final String content = editor.getText();
        storage.save(content, () -> {
            // 成功回调（已在 JavaFX 线程）
            saving = false;
            originalContent = content;
            if (modified) {
                modified = false;
                notifyTitleChange();
            }
            // 保存成功不再弹窗，标题栏的 * 消失即为成功反馈
        }, (errMsg) -> {
            // 错误回调（已在 JavaFX 线程）
            saving = false;
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("保存失败");
            alert.setHeaderText(null);
            alert.setContentText(errMsg);
            alert.showAndWait();
        });
    }

    // ==================== 状态回调 ====================

    public boolean isModified() {
        return modified;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDisplayTitle() {
        return (modified ? "*" : "") + displayName;
    }

    public void setOnTitleChange(Consumer<String> callback) {
        this.onTitleChange = callback;
    }

    private void notifyTitleChange() {
        if (onTitleChange != null) onTitleChange.accept(getDisplayTitle());
    }

    // ==================== 静态工具：下载文件内容 ====================

    /**
     * 异步下载 markdown 文件内容
     * @param onLoaded 回调（在 JavaFX 线程），传入内容或异常 message（第二个参数非null表示失败）
     */
    public static void loadMarkdownContent(ConnectionConfig config, String bucket, String key,
                                            java.util.function.BiConsumer<String, String> onLoaded) {
        new Thread(() -> {
            try {
                boolean isOSS = config.getType() == ConnectType.ALIYUN_OSS;
                InputStream is = isOSS
                        ? OssService.getObjectStream(config, bucket, key)
                        : S3Service.getObjectStream(config, bucket, key);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                is.close();
                String content = baos.toString(StandardCharsets.UTF_8);
                Platform.runLater(() -> onLoaded.accept(content, null));
            } catch (Exception e) {
                Platform.runLater(() -> onLoaded.accept(null, e.getMessage()));
            }
        }, "MD-Load").start();
    }
}
