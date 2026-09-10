package com.tangluobo.tomato.module.connect.view;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Popup;

/** Lightweight, mouse-transparent preview shown while copying editor text by drag. */
final class SqlEditorDragGhost {

    private static final int MAX_LINES = 8;
    private static final int MAX_COLUMNS = 96;
    private static final double OFFSET_X = 14;
    private static final double OFFSET_Y = 18;
    private static final double PADDING_X = 7;
    private static final double PADDING_Y = 5;
    private static final double LINE_HEIGHT = 17;
    private static final Font PREVIEW_FONT = Font.font("Consolas", 12);

    private final Popup popup = new Popup();
    private final ImageView preview = new ImageView();

    SqlEditorDragGhost() {
        preview.setMouseTransparent(true);
        preview.setOpacity(0.72);
        preview.setEffect(new DropShadow(8, 0, 2, Color.rgb(0, 0, 0, 0.25)));
        popup.getContent().add(preview);
        // Keep the preview at a fixed offset from the pointer. Auto-fixing at a
        // screen edge could place the popup under the pointer and steal the drag.
        popup.setAutoFix(false);
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);
    }

    void show(Node owner, String selectedText, double screenX, double screenY) {
        hide();
        preview.setImage(renderPreview(selectedText));
        popup.show(owner, screenX + OFFSET_X, screenY + OFFSET_Y);
    }

    void move(double screenX, double screenY) {
        if (!popup.isShowing()) return;
        popup.setX(screenX + OFFSET_X);
        popup.setY(screenY + OFFSET_Y);
    }

    void hide() {
        if (popup.isShowing()) popup.hide();
    }

    private static javafx.scene.image.Image renderPreview(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int displayedLines = Math.min(lines.length, MAX_LINES);
        String[] visibleLines = new String[displayedLines + (lines.length > MAX_LINES ? 1 : 0)];
        double textWidth = 1;
        for (int i = 0; i < displayedLines; i++) {
            String line = lines[i].replace("\t", "    ");
            if (line.length() > MAX_COLUMNS) {
                line = line.substring(0, MAX_COLUMNS - 1) + '…';
            }
            visibleLines[i] = line;
            Text measure = new Text(line.isEmpty() ? " " : line);
            measure.setFont(PREVIEW_FONT);
            textWidth = Math.max(textWidth, measure.getLayoutBounds().getWidth());
        }
        if (lines.length > MAX_LINES) {
            visibleLines[visibleLines.length - 1] = "…";
        }

        double width = Math.ceil(textWidth + PADDING_X * 2);
        double height = Math.ceil(visibleLines.length * LINE_HEIGHT + PADDING_Y * 2);
        Canvas canvas = new Canvas(width, height);
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.rgb(94, 153, 219, 0.24));
        graphics.fillRoundRect(0.5, 0.5, width - 1, height - 1, 4, 4);
        graphics.setStroke(Color.rgb(74, 144, 226, 0.78));
        graphics.setLineWidth(1);
        graphics.strokeRoundRect(0.5, 0.5, width - 1, height - 1, 4, 4);
        graphics.setFont(PREVIEW_FONT);
        graphics.setFill(Color.rgb(31, 41, 55, 0.92));
        for (int i = 0; i < visibleLines.length; i++) {
            graphics.fillText(visibleLines[i], PADDING_X, PADDING_Y + 13 + i * LINE_HEIGHT);
        }

        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null);
    }
}
