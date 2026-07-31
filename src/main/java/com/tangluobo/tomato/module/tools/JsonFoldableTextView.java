package com.tangluobo.tomato.module.tools;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可复用的 JSON 富文本视图组件，支持行号、折叠、复制、语法高亮。
 */
public class JsonFoldableTextView extends VBox {

    private static final String STYLE_KEY = "-fx-fill: #0451A5; -fx-font-weight: bold;";
    private static final String STYLE_STRING = "-fx-fill: #067D17;";
    private static final String STYLE_NUMBER = "-fx-fill: #098658; -fx-font-weight: bold;";
    private static final String STYLE_BOOLEAN = "-fx-fill: #0000FF; -fx-font-weight: bold;";
    private static final String STYLE_NULL = "-fx-fill: #808080; -fx-font-weight: bold;";
    private static final String STYLE_BRACE = "-fx-fill: #000000; -fx-font-weight: bold;";
    private static final String STYLE_DEFAULT = "-fx-fill: #000000;";
    private static final String STYLE_FOLDED = "-fx-fill: #808080; -fx-font-style: italic;";
    private static final String STYLE_COMMA = "-fx-fill: #000000;";
    private static final String STYLE_ERROR = "-fx-fill: #cc0000;";

    private static final Pattern HIGHLIGHT_PATTERN = Pattern.compile(
            "__KEY__" + "(.*?)" + "__KEY__" +
            "|__STR__" + "(.*?)" + "__STR__" +
            "|__NUM__" + "(-?\\d+\\.?\\d*(?:[eE][+-]?\\d+)?)" + "__NUM__" +
            "|__BOOL__" + "(true|false)" + "__BOOL__" +
            "|__NULL__" + "(null)" + "__NULL__" +
            "|__FOLDED__" + "(.*?)" + "__FOLDED__"
    );

    private static final int UNIQUE_SALT_BASE = 1_000_000;

    private final InlineCssTextArea jsonRichArea;
    private final VirtualizedScrollPane<InlineCssTextArea> jsonScrollPane;
    private final ParagraphGraphicFactory graphicFactory;
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    private final Gson compactGson = new Gson();

    // Fold state
    private final Set<String> foldedIds = new HashSet<>();
    private List<LineMeta> lineMetaList = new ArrayList<>();
    private final Map<Integer, ParagraphGraphicInfo> paragraphGraphics = new HashMap<>();

    // Current parsed root
    private JsonElement currentRoot;
    private String currentRawJson;

    public JsonFoldableTextView() {
        jsonRichArea = new InlineCssTextArea();
        jsonRichArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; " +
                "-fx-background-color: white; -fx-padding: 0; -fx-text-fill: #000;");
        jsonRichArea.setEditable(false);

        graphicFactory = new ParagraphGraphicFactory();
        jsonRichArea.setParagraphGraphicFactory(graphicFactory);

        jsonScrollPane = new VirtualizedScrollPane<>(jsonRichArea);
        VBox.setVgrow(jsonScrollPane, Priority.ALWAYS);
        getChildren().add(jsonScrollPane);
    }

    /**
     * Set JSON text to display. If text is valid JSON, renders with highlighting and folding.
     * If not valid JSON, displays as plain text.
     */
    public void setText(String text) {
        if (text == null || text.trim().isEmpty()) {
            clear();
            return;
        }
        currentRawJson = text;
        try {
            currentRoot = JsonParser.parseString(text.trim());
            foldedIds.clear();
            renderFoldableJson(currentRoot);
        } catch (Exception e) {
            currentRoot = null;
            renderPlainText(text);
        }
    }

    /**
     * Display an error message.
     */
    public void setError(String msg) {
        currentRoot = null;
        lineMetaList.clear();
        paragraphGraphics.clear();
        jsonRichArea.replaceText(msg);
        jsonRichArea.setStyle(0, msg.length(), STYLE_ERROR);
    }

    /**
     * Clear the view.
     */
    public void clear() {
        currentRoot = null;
        currentRawJson = null;
        lineMetaList.clear();
        paragraphGraphics.clear();
        foldedIds.clear();
        jsonRichArea.replaceText("");
    }

    /**
     * Expand all folded nodes.
     */
    public void expandAll() {
        if (currentRoot == null) return;
        foldedIds.clear();
        renderFoldableJson(currentRoot);
    }

    /**
     * Collapse all foldable nodes.
     */
    public void collapseAll() {
        if (currentRoot == null) return;
        foldedIds.clear();
        collectAllContainerIds(currentRoot, new int[]{UNIQUE_SALT_BASE});
        renderFoldableJson(currentRoot);
    }

    /**
     * Copy all text to clipboard.
     */
    public void copyAll() {
        String text = jsonRichArea.getText();
        if (text == null || text.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Get the raw JSON text.
     */
    public String getRawJson() {
        return currentRawJson;
    }

    // ==================== Rendering ====================

    private void renderFoldableJson(JsonElement root) {
        lineMetaList.clear();
        paragraphGraphics.clear();

        StringBuilder sb = new StringBuilder();
        buildLines(root, "", null, 0, sb, true, true, new int[]{UNIQUE_SALT_BASE});

        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }

        jsonRichArea.replaceText(sb.toString());
        applySyntaxHighlighting();
        graphicFactory.setLineMetaList(lineMetaList);
    }

    private void renderPlainText(String text) {
        lineMetaList.clear();
        paragraphGraphics.clear();
        // Add one meta per line for line numbers
        String[] lines = text.split("\n", -1);
        for (String ignored : lines) {
            LineMeta meta = new LineMeta();
            meta.isContainerStart = false;
            lineMetaList.add(meta);
        }
        jsonRichArea.replaceText(text);
        jsonRichArea.setStyle(0, text.length(), STYLE_DEFAULT);
        graphicFactory.setLineMetaList(lineMetaList);
    }

    // ==================== Line Building ====================

    private static class LineMeta {
        boolean isContainerStart;
        String nodeId;
        JsonElement element;
        String keyName;
        boolean isFolded;
        String containerType;
        int childCount;
    }

    private String buildLines(JsonElement element, String keyPrefix, String explicitKey,
                              int depth, StringBuilder sb, boolean isLast, boolean isRootOrValue,
                              int[] idCounter) {
        String indent = "  ".repeat(depth);

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String nodeId = "o_" + (++idCounter[0]);
            boolean isFolded = foldedIds.contains(nodeId);
            int childCount = obj.size();

            sb.append(indent);
            LineMeta meta = new LineMeta();
            meta.isContainerStart = true;
            meta.nodeId = nodeId;
            meta.element = element;
            meta.keyName = explicitKey;
            meta.isFolded = isFolded;
            meta.containerType = "object";
            meta.childCount = childCount;
            lineMetaList.add(meta);

            if (!keyPrefix.isEmpty()) {
                sb.append("__KEY__").append(jsonEscapeKey(explicitKey)).append("__KEY__").append(": ");
            }
            sb.append("{");
            if (isFolded) {
                sb.append(" ").append("__FOLDED__").append("/* ").append(childCount)
                  .append(childCount == 1 ? " field */" : " fields */").append("__FOLDED__").append(" }");
                if (!isLast) sb.append(",");
                sb.append("\n");
            } else {
                sb.append("\n");
                int idx = 0;
                List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(obj.entrySet());
                for (Map.Entry<String, JsonElement> e : entries) {
                    boolean childLast = (idx == entries.size() - 1);
                    idx++;
                    buildLines(e.getValue(), "__KEY__" + jsonEscapeKey(e.getKey()) + "__KEY__: ",
                            e.getKey(), depth + 1, sb, childLast, false, idCounter);
                }
                LineMeta closeMeta = new LineMeta();
                closeMeta.isContainerStart = false;
                lineMetaList.add(closeMeta);
                sb.append(indent).append("}");
                if (!isLast) sb.append(",");
                sb.append("\n");
            }
            return nodeId;
        } else if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            String nodeId = "a_" + (++idCounter[0]);
            boolean isFolded = foldedIds.contains(nodeId);
            int childCount = arr.size();

            sb.append(indent);
            LineMeta meta = new LineMeta();
            meta.isContainerStart = true;
            meta.nodeId = nodeId;
            meta.element = element;
            meta.keyName = explicitKey;
            meta.isFolded = isFolded;
            meta.containerType = "array";
            meta.childCount = childCount;
            lineMetaList.add(meta);

            if (!keyPrefix.isEmpty()) {
                sb.append("__KEY__").append(jsonEscapeKey(explicitKey)).append("__KEY__").append(": ");
            }
            sb.append("[");
            if (isFolded) {
                sb.append(" ").append("__FOLDED__").append("/* ").append(childCount)
                  .append(childCount == 1 ? " item */" : " items */").append("__FOLDED__").append(" ]");
                if (!isLast) sb.append(",");
                sb.append("\n");
            } else {
                sb.append("\n");
                for (int i = 0; i < arr.size(); i++) {
                    boolean childLast = (i == arr.size() - 1);
                    buildLines(arr.get(i), "", "[" + i + "]", depth + 1, sb, childLast, false, idCounter);
                }
                LineMeta closeMeta = new LineMeta();
                closeMeta.isContainerStart = false;
                lineMetaList.add(closeMeta);
                sb.append(indent).append("]");
                if (!isLast) sb.append(",");
                sb.append("\n");
            }
            return nodeId;
        } else {
            LineMeta meta = new LineMeta();
            meta.isContainerStart = false;
            meta.element = element;
            meta.keyName = explicitKey;
            lineMetaList.add(meta);

            sb.append(indent);
            if (!keyPrefix.isEmpty()) {
                sb.append("__KEY__").append(jsonEscapeKey(explicitKey)).append("__KEY__").append(": ");
            }
            if (element.isJsonPrimitive()) {
                JsonPrimitive p = element.getAsJsonPrimitive();
                if (p.isString()) {
                    sb.append("__STR__\"").append(jsonEscapeString(p.getAsString())).append("\"__STR__");
                } else if (p.isBoolean()) {
                    sb.append("__BOOL__").append(p.getAsString()).append("__BOOL__");
                } else if (p.isNumber()) {
                    sb.append("__NUM__").append(p.getAsString()).append("__NUM__");
                } else {
                    sb.append(p.getAsString());
                }
            } else if (element.isJsonNull()) {
                sb.append("__NULL__").append("null").append("__NULL__");
            }
            if (!isLast) sb.append(",");
            sb.append("\n");
            return null;
        }
    }

    private static String jsonEscapeKey(String s) {
        return "\"" + jsonEscapeString(s) + "\"";
    }

    private static String jsonEscapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    // ==================== Paragraph Graphics ====================

    private static class ParagraphGraphicInfo {
        final HBox container;
        final Label lineNoLabel;
        final Label arrowLabel;
        final Label copyLabel;
        final Region arrowPlaceholder;
        final Region copyPlaceholder;
        LineMeta meta;
        int lineNumber;

        ParagraphGraphicInfo(HBox container, Label lineNoLabel, Label arrowLabel,
                             Label copyLabel, Region arrowPlaceholder, Region copyPlaceholder) {
            this.container = container;
            this.lineNoLabel = lineNoLabel;
            this.arrowLabel = arrowLabel;
            this.copyLabel = copyLabel;
            this.arrowPlaceholder = arrowPlaceholder;
            this.copyPlaceholder = copyPlaceholder;
        }
    }

    private class ParagraphGraphicFactory implements java.util.function.IntFunction<Node> {
        private List<LineMeta> metaList = new ArrayList<>();

        void setLineMetaList(List<LineMeta> list) {
            this.metaList = list;
        }

        @Override
        public Node apply(int paragraphIndex) {
            return createGraphic(paragraphIndex);
        }

        Node createGraphic(int paragraphIndex) {
            ParagraphGraphicInfo info = paragraphGraphics.get(paragraphIndex);
            if (info != null) {
                updateGraphic(info);
                return info.container;
            }

            HBox box = new HBox(2);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPrefWidth(70);
            box.setMinWidth(70);
            box.setMaxWidth(70);
            box.setStyle("-fx-background-color: transparent;");

            Label lineNo = new Label();
            lineNo.setPrefWidth(38);
            lineNo.setMinWidth(38);
            lineNo.setMaxWidth(38);
            lineNo.setStyle("-fx-text-fill: #aaaaaa; -fx-alignment: CENTER_RIGHT; -fx-padding: 0 4 0 0;");
            lineNo.setAlignment(Pos.CENTER_RIGHT);

            Label arrow = new Label();
            arrow.setPrefWidth(16);
            arrow.setMinWidth(16);
            arrow.setMaxWidth(16);

            Label copy = new Label();
            copy.setPrefWidth(18);
            copy.setMinWidth(18);
            copy.setMaxWidth(18);

            Region arrowPh = new Region();
            arrowPh.setPrefWidth(16);
            arrowPh.setMinWidth(16);
            arrowPh.setMaxWidth(16);

            Region copyPh = new Region();
            copyPh.setPrefWidth(18);
            copyPh.setMinWidth(18);
            copyPh.setMaxWidth(18);

            info = new ParagraphGraphicInfo(box, lineNo, arrow, copy, arrowPh, copyPh);
            info.lineNumber = paragraphIndex + 1;
            paragraphGraphics.put(paragraphIndex, info);

            updateGraphic(info);
            return box;
        }

        void updateGraphic(ParagraphGraphicInfo info) {
            int idx = info.lineNumber - 1;
            if (idx >= 0 && idx < metaList.size()) {
                info.meta = metaList.get(idx);
            }
            info.lineNoLabel.setText(String.valueOf(info.lineNumber));

            if (info.meta != null && info.meta.isContainerStart) {
                info.arrowLabel.setText(info.meta.isFolded ? "▶" : "▼");
                info.arrowLabel.setStyle("-fx-text-fill: #808080; -fx-cursor: hand; -fx-alignment: CENTER;");
                info.arrowLabel.setOnMouseClicked(ev -> toggleFold(info));
                info.arrowLabel.setOnMouseEntered(ev -> info.arrowLabel.setStyle("-fx-text-fill: #1976D2; -fx-cursor: hand; -fx-alignment: CENTER;"));
                info.arrowLabel.setOnMouseExited(ev -> info.arrowLabel.setStyle("-fx-text-fill: #808080; -fx-cursor: hand; -fx-alignment: CENTER;"));

                info.copyLabel.setText("📋");
                info.copyLabel.setStyle("-fx-text-fill: #909090; -fx-cursor: hand; -fx-alignment: CENTER;");
                info.copyLabel.setTooltip(new Tooltip("复制节点为JSON"));
                info.copyLabel.setOnMouseClicked(ev -> copyNodeToClipboard(info.meta));
                info.copyLabel.setOnMouseEntered(ev -> info.copyLabel.setStyle("-fx-text-fill: #1976D2; -fx-cursor: hand; -fx-alignment: CENTER;"));
                info.copyLabel.setOnMouseExited(ev -> info.copyLabel.setStyle("-fx-text-fill: #909090; -fx-cursor: hand; -fx-alignment: CENTER;"));

                info.container.getChildren().clear();
                info.container.getChildren().addAll(info.lineNoLabel, info.arrowLabel, info.copyLabel);
            } else {
                info.container.getChildren().clear();
                info.container.getChildren().addAll(info.lineNoLabel, info.arrowPlaceholder, info.copyPlaceholder);
            }
        }
    }

    private void toggleFold(ParagraphGraphicInfo info) {
        if (info.meta == null || info.meta.nodeId == null) return;
        if (info.meta.isFolded) {
            foldedIds.remove(info.meta.nodeId);
        } else {
            foldedIds.add(info.meta.nodeId);
        }
        if (currentRoot != null) {
            renderFoldableJson(currentRoot);
        }
    }

    private void copyNodeToClipboard(LineMeta meta) {
        if (meta.element == null) return;
        try {
            String json;
            if (meta.element.isJsonObject() || meta.element.isJsonArray()) {
                json = prettyGson.toJson(meta.element);
            } else {
                json = compactGson.toJson(meta.element);
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(json);
            Clipboard.getSystemClipboard().setContent(content);
        } catch (Exception ignored) {}
    }

    private void collectAllContainerIds(JsonElement e, int[] idCounter) {
        if (e.isJsonObject()) {
            String id = "o_" + (++idCounter[0]);
            foldedIds.add(id);
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                collectAllContainerIds(entry.getValue(), idCounter);
            }
        } else if (e.isJsonArray()) {
            String id = "a_" + (++idCounter[0]);
            foldedIds.add(id);
            for (JsonElement child : e.getAsJsonArray()) {
                collectAllContainerIds(child, idCounter);
            }
        }
    }

    // ==================== Syntax Highlighting ====================

    private void applySyntaxHighlighting() {
        String text = jsonRichArea.getText();
        if (text == null || text.isEmpty()) return;

        StyleSpansBuilder<String> spans = new StyleSpansBuilder<>();
        Matcher m = HIGHLIGHT_PATTERN.matcher(text);
        int lastEnd = 0;

        try {
            while (m.find()) {
                int start = m.start();
                if (start > lastEnd) {
                    String middle = text.substring(lastEnd, start);
                    applySpanForPlain(middle, spans);
                }

                if (m.group(1) != null) {
                    String keyContent = m.group(1);
                    spans.add(STYLE_DEFAULT, "__KEY__".length());
                    spans.add(STYLE_KEY, keyContent.length());
                    spans.add(STYLE_DEFAULT, "__KEY__".length());
                } else if (m.group(2) != null) {
                    String val = m.group(2);
                    spans.add(STYLE_DEFAULT, "__STR__".length());
                    spans.add(STYLE_STRING, val.length());
                    spans.add(STYLE_DEFAULT, "__STR__".length());
                } else if (m.group(3) != null) {
                    String val = m.group(3);
                    spans.add(STYLE_DEFAULT, "__NUM__".length());
                    spans.add(STYLE_NUMBER, val.length());
                    spans.add(STYLE_DEFAULT, "__NUM__".length());
                } else if (m.group(4) != null) {
                    String val = m.group(4);
                    spans.add(STYLE_DEFAULT, "__BOOL__".length());
                    spans.add(STYLE_BOOLEAN, val.length());
                    spans.add(STYLE_DEFAULT, "__BOOL__".length());
                } else if (m.group(5) != null) {
                    String val = m.group(5);
                    spans.add(STYLE_DEFAULT, "__NULL__".length());
                    spans.add(STYLE_NULL, val.length());
                    spans.add(STYLE_DEFAULT, "__NULL__".length());
                } else if (m.group(6) != null) {
                    String val = m.group(6);
                    spans.add(STYLE_FOLDED, "__FOLDED__".length());
                    spans.add(STYLE_FOLDED, val.length());
                    spans.add(STYLE_FOLDED, "__FOLDED__".length());
                }

                lastEnd = m.end();
            }
            if (lastEnd < text.length()) {
                applySpanForPlain(text.substring(lastEnd), spans);
            }

            jsonRichArea.setStyleSpans(0, spans.create());
            stripPlaceholdersAndRebuild();

        } catch (Exception e) {
            System.err.println("JSON 高亮异常: " + e.getMessage());
        }
    }

    private void applySpanForPlain(String plain, StyleSpansBuilder<String> spans) {
        String currentStyle = STYLE_DEFAULT;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            String style;
            if (c == '{' || c == '}' || c == '[' || c == ']' || c == ':') {
                style = STYLE_BRACE;
            } else if (c == ',') {
                style = STYLE_COMMA;
            } else {
                style = STYLE_DEFAULT;
            }
            if (!style.equals(currentStyle) || sb.length() == 0) {
                if (sb.length() > 0) {
                    spans.add(currentStyle, sb.length());
                    sb.setLength(0);
                }
                currentStyle = style;
            }
            sb.append(c);
        }
        if (sb.length() > 0) {
            spans.add(currentStyle, sb.length());
        }
    }

    private void stripPlaceholdersAndRebuild() {
        try {
            String original = jsonRichArea.getText();
            List<String> perCharStyle = new ArrayList<>(original.length());
            for (int i = 0; i < original.length(); i++) perCharStyle.add(STYLE_DEFAULT);

            try {
                var styleSpans = jsonRichArea.getStyleSpans(0, original.length());
                int cursor = 0;
                for (var span : styleSpans) {
                    String style = span.getStyle();
                    int len = span.getLength();
                    for (int i = 0; i < len; i++) {
                        if (cursor + i < perCharStyle.size()) {
                            perCharStyle.set(cursor + i, style);
                        }
                    }
                    cursor += len;
                }
            } catch (Exception ignore) {}

            StringBuilder newText = new StringBuilder();
            List<String> newStyle = new ArrayList<>();

            Matcher m = HIGHLIGHT_PATTERN.matcher(original);
            int lastEnd = 0;
            while (m.find()) {
                int s = m.start();
                for (int i = lastEnd; i < s; i++) {
                    newText.append(original.charAt(i));
                    newStyle.add(perCharStyle.get(i));
                }
                if (m.group(1) != null) {
                    String content = m.group(1);
                    int startIdx = s + "__KEY__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(2) != null) {
                    String content = m.group(2);
                    int startIdx = s + "__STR__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(3) != null) {
                    String content = m.group(3);
                    int startIdx = s + "__NUM__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(4) != null) {
                    String content = m.group(4);
                    int startIdx = s + "__BOOL__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(5) != null) {
                    String content = m.group(5);
                    int startIdx = s + "__NULL__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(6) != null) {
                    String content = m.group(6);
                    int startIdx = s + "__FOLDED__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                }
                lastEnd = m.end();
            }
            for (int i = lastEnd; i < original.length(); i++) {
                newText.append(original.charAt(i));
                newStyle.add(perCharStyle.get(i));
            }

            String finalText = newText.toString();
            jsonRichArea.replaceText(finalText);

            StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
            if (!newStyle.isEmpty()) {
                String current = newStyle.get(0);
                int count = 1;
                for (int i = 1; i < newStyle.size(); i++) {
                    if (Objects.equals(current, newStyle.get(i))) {
                        count++;
                    } else {
                        spansBuilder.add(current, count);
                        current = newStyle.get(i);
                        count = 1;
                    }
                }
                spansBuilder.add(current, count);
            }
            jsonRichArea.setStyleSpans(0, spansBuilder.create());

        } catch (Exception e) {
            System.err.println("JSON strip placeholders 异常: " + e.getMessage());
        }
    }
}
