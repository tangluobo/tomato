package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.connect.service.OssService;
import com.tangluobo.tomato.module.connect.service.S3Service;
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
import javafx.animation.PauseTransition;

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

    private final ConnectionConfig config;
    private final boolean isAliyunOSS;
    private final String bucket;
    private final String key;
    private final String displayName;

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

    // 预览渲染防抖
    private final PauseTransition previewDebounce = new PauseTransition(new Duration(250));

    public MarkdownEditorPane(ConnectionConfig config, String bucket, String key, String displayName, String initialContent) {
        this.config = config;
        this.isAliyunOSS = config.getType() == ConnectType.ALIYUN_OSS;
        this.bucket = bucket;
        this.key = key;
        this.displayName = displayName;

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

        // 编辑器内容变化
        editor.textProperty().addListener((obs, oldVal, newVal) -> {
            applyHighlighting();
            previewDebounce.playFromStart();
            boolean nowModified = !newVal.equals(originalContent);
            if (nowModified != modified) {
                modified = nowModified;
                notifyTitleChange();
            }
        });

        previewDebounce.setOnFinished(e -> updatePreview());

        // Ctrl+S 保存
        editor.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.S) {
                e.consume();
                save();
            }
        });
        // Tab 缩进
        editor.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB) {
                e.consume();
                editor.insertText(editor.getCaretPosition(), "    ");
            }
        });

        applyMode();
    }

    // ==================== 工具栏 ====================

    private Node buildToolbar() {
        HBox toolbar = new HBox(6);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // 模式切换
        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton editBtn = new ToggleButton("编辑");
        ToggleButton splitBtn = new ToggleButton("编辑+预览");
        ToggleButton previewBtn = new ToggleButton("预览");
        editBtn.setToggleGroup(modeGroup);
        splitBtn.setToggleGroup(modeGroup);
        previewBtn.setToggleGroup(modeGroup);
        splitBtn.setSelected(true);
        editBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        splitBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        previewBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        editBtn.setOnAction(e -> { currentMode = Mode.EDIT; applyMode(); });
        splitBtn.setOnAction(e -> { currentMode = Mode.EDIT_PREVIEW; applyMode(); });
        previewBtn.setOnAction(e -> { currentMode = Mode.PREVIEW; applyMode(); });
        toolbar.getChildren().addAll(editBtn, splitBtn, previewBtn);

        toolbar.getChildren().add(createSep());

        // 插入按钮
        toolbar.getChildren().add(smallBtn("H1", () -> prefixLine("# ")));
        toolbar.getChildren().add(smallBtn("H2", () -> prefixLine("## ")));
        toolbar.getChildren().add(smallBtn("H3", () -> prefixLine("### ")));
        toolbar.getChildren().add(smallBtn("加粗", () -> wrapSelection("**", "**")));
        toolbar.getChildren().add(smallBtn("斜体", () -> wrapSelection("*", "*")));
        toolbar.getChildren().add(smallBtn("代码", () -> wrapSelection("`", "`")));
        toolbar.getChildren().add(smallBtn("链接", () -> wrapSelection("[", "](url)")));
        toolbar.getChildren().add(smallBtn("列表", () -> prefixLine("- ")));
        toolbar.getChildren().add(smallBtn("引用", () -> prefixLine("> ")));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolbar.getChildren().add(spacer);

        Button saveBtn = new Button("保存");
        saveBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 12; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-background-radius: 4; -fx-border-radius: 4;");
        saveBtn.setOnAction(e -> save());
        toolbar.getChildren().add(saveBtn);

        return toolbar;
    }

    private Separator createSep() {
        Separator s = new Separator();
        s.setOrientation(javafx.geometry.Orientation.VERTICAL);
        s.setPrefHeight(22);
        return s;
    }

    private Button smallBtn(String text, Runnable action) {
        Button b = new Button(text);
        b.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        b.setOnAction(e -> action.run());
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

    private void wrapSelection(String before, String after) {
        var sel = editor.getSelection();
        if (sel.getLength() == 0) {
            int pos = editor.getCaretPosition();
            editor.insertText(pos, before + after);
            editor.moveTo(pos + before.length());
        } else {
            String selected = editor.getSelectedText();
            editor.replaceText(sel.getStart(), sel.getEnd(), before + selected + after);
        }
        editor.requestFocus();
    }

    private void prefixLine(String prefix) {
        int caret = editor.getCaretPosition();
        String text = editor.getText();
        int lineStart = 0;
        if (caret > 0) {
            int idx = text.lastIndexOf('\n', caret - 1);
            lineStart = idx < 0 ? 0 : idx + 1;
        }
        editor.insertText(lineStart, prefix);
        editor.requestFocus();
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
            Parser parser = Parser.builder().build();
            org.commonmark.node.Node document = parser.parse(md);
            InlineStyle base = new InlineStyle();
            renderBlocks(document, base, previewBox.getChildren());
        } catch (Exception e) {
            Label err = new Label("预览渲染失败: " + e.getMessage());
            err.setStyle("-fx-text-fill: #c00; -fx-font-size: 11px;");
            previewBox.getChildren().add(err);
        }
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
            target.add(codeBlockLabel(fcb.getLiteral()));
        } else if (node instanceof IndentedCodeBlock icb) {
            target.add(codeBlockLabel(icb.getLiteral()));
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

    private Label codeBlockLabel(String literal) {
        Label l = new Label(literal);
        l.setStyle("-fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12px; " +
                "-fx-text-fill: #333; -fx-background-color: #f5f5f5; -fx-background-radius: 4; " +
                "-fx-padding: 8 12; -fx-border-color: #e0e0e0; -fx-border-radius: 4;");
        l.setWrapText(true);
        return l;
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
        new Thread(() -> {
            try {
                if (isAliyunOSS) {
                    OssService.putObject(config, bucket, key, content);
                } else {
                    S3Service.putObject(config, bucket, key, content);
                }
                Platform.runLater(() -> {
                    saving = false;
                    originalContent = content;
                    if (modified) {
                        modified = false;
                        notifyTitleChange();
                    }
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("保存成功");
                    alert.setHeaderText(null);
                    alert.setContentText("文件已保存到 " + bucket + "/" + key);
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    saving = false;
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("保存失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "MD-Save").start();
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
