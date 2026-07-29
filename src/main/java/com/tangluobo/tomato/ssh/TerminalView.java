package com.tangluobo.tomato.ssh;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * 终端视图组件，使用Canvas渲染TerminalEmulator的字符缓冲区
 * Canvas宽高由SSHTerminalPane通过绑定控制
 */
public class TerminalView extends Canvas {

    private final TerminalEmulator emulator;
    private final GraphicsContext gc;

    // 字体设置
    private double charWidth = 8;
    private double charHeight = 16;
    private double fontAscent = 12;
    private String fontFamily = "monospace";

    // 颜色缓存
    private static final Color[] FX_COLORS = new Color[16];
    static {
        for (int i = 0; i < 16; i++) {
            int rgb = TerminalEmulator.COLOR_TABLE[i];
            FX_COLORS[i] = Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }
    }

    // 默认前景色和背景色
    private Color defaultFg = FX_COLORS[7];
    private Color defaultBg = Color.rgb(0x1e, 0x1e, 0x1e);

    // 键盘输入回调
    private KeyInputHandler keyInputHandler;

    // resize回调（通知SSH服务器终端大小变化）
    private ResizeHandler resizeHandler;

    // 光标闪烁
    private boolean cursorBlinkOn = true;
    private final Timeline cursorBlinkTimer;

    // 文本选择
    private int selectionStartCol = -1;
    private int selectionStartRow = -1;
    private int selectionEndCol = -1;
    private int selectionEndRow = -1;
    private boolean isSelecting = false;

    // 滚动条回调
    public interface ScrollbarHandler {
        void onScrollChanged(int scrollbackSize, int scrollOffset, int visibleRows);
    }
    private ScrollbarHandler scrollbarHandler;

    public interface KeyInputHandler {
        void handleInput(byte[] data);
    }

    public interface ResizeHandler {
        void onResize(int cols, int rows, int width, int height);
    }

    public interface PasteHandler {
        void onPaste();
    }
    private PasteHandler pasteHandler;

    public TerminalView(TerminalEmulator emulator) {
        this.emulator = emulator;
        this.gc = getGraphicsContext2D();

        // 初始化字体度量
        updateFontMetrics();

        // 键盘事件
        setFocusTraversable(true);
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyTyped(this::handleKeyTyped);

        // 鼠标事件处理
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);
        setOnMouseClicked(this::handleMouseClicked);

        // 鼠标滚轮滚动（回滚历史）
        setOnScroll(this::handleScroll);

        // 光标闪烁定时器，每500ms切换一次
        cursorBlinkTimer = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            cursorBlinkOn = !cursorBlinkOn;
            render();
        }));
        cursorBlinkTimer.setCycleCount(Animation.INDEFINITE);
        cursorBlinkTimer.play();

        // Canvas大小变化时重新计算行列数
        widthProperty().addListener((obs, oldVal, newVal) -> {
            int newCols = (int) (newVal.doubleValue() / charWidth);
            if (newCols > 1 && newCols != emulator.getCols()) {
                emulator.resize(newCols, emulator.getRows());
                render();
                notifyResize();
            }
        });
        heightProperty().addListener((obs, oldVal, newVal) -> {
            int newRows = (int) (newVal.doubleValue() / charHeight);
            if (newRows > 1 && newRows != emulator.getRows()) {
                emulator.resize(emulator.getCols(), newRows);
                render();
                notifyResize();
            }
        });
    }

    /**
     * 关键：让Canvas可被父容器调整大小
     */
    @Override
    public boolean isResizable() {
        return true;
    }

    /**
     * 父容器调整Canvas大小时调用
     */
    @Override
    public void resize(double width, double height) {
        if (width > 0 && height > 0) {
            setWidth(width);
            setHeight(height);
        }
    }

    private void notifyResize() {
        if (resizeHandler != null) {
            resizeHandler.onResize(
                    emulator.getCols(),
                    emulator.getRows(),
                    (int) (emulator.getCols() * charWidth),
                    (int) (emulator.getRows() * charHeight)
            );
        }
    }

    public void setKeyInputHandler(KeyInputHandler handler) {
        this.keyInputHandler = handler;
    }

    public void setResizeHandler(ResizeHandler handler) {
        this.resizeHandler = handler;
    }

    public void setScrollbarHandler(ScrollbarHandler handler) {
        this.scrollbarHandler = handler;
    }

    public void setPasteHandler(PasteHandler handler) {
        this.pasteHandler = handler;
    }

    /**
     * 由外部滚动条驱动滚动
     */
    public void setScrollOffset(int offset) {
        emulator.setScrollOffset(offset);
        render();
    }

    private void updateFontMetrics() {
        gc.setFont(javafx.scene.text.Font.font(fontFamily, 13));
        gc.save();
        javafx.scene.text.Text text = new javafx.scene.text.Text("M");
        text.setFont(javafx.scene.text.Font.font(fontFamily, 13));
        charWidth = text.getLayoutBounds().getWidth();
        charHeight = 18;
        fontAscent = 14;
        gc.restore();
    }

    private void handleKeyPressed(KeyEvent event) {
        if (keyInputHandler == null) return;

        // 键盘输入时重置光标闪烁（立即显示光标）
        resetCursorBlink();

        byte[] data = null;
        if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
            data = "\033".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
            data = "\r".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
            data = "\b".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.TAB) {
            data = "\t".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.UP) {
            data = emulator.isApplicationCursorKeys() ? "\033OA".getBytes() : "\033[A".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.DOWN) {
            data = emulator.isApplicationCursorKeys() ? "\033OB".getBytes() : "\033[B".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.RIGHT) {
            data = emulator.isApplicationCursorKeys() ? "\033OC".getBytes() : "\033[C".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.LEFT) {
            data = emulator.isApplicationCursorKeys() ? "\033OD".getBytes() : "\033[D".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.HOME) {
            data = emulator.isApplicationCursorKeys() ? "\033OH".getBytes() : "\033[H".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.END) {
            data = emulator.isApplicationCursorKeys() ? "\033OF".getBytes() : "\033[F".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.DELETE) {
            data = "\033[3~".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.PAGE_UP) {
            data = "\033[5~".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.PAGE_DOWN) {
            data = "\033[6~".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F1) {
            data = "\033OP".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F2) {
            data = "\033OQ".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F3) {
            data = "\033OR".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F4) {
            data = "\033OS".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.C && event.isControlDown() && event.isShiftDown()) {
            // Ctrl+Shift+C: 复制选中文本
            copySelection();
            event.consume();
            return;
        } else if (event.getCode() == javafx.scene.input.KeyCode.V && event.isControlDown() && event.isShiftDown()) {
            // Ctrl+Shift+V: 粘贴剪贴板内容
            if (pasteHandler != null) {
                pasteHandler.onPaste();
            }
            event.consume();
            return;
        } else if (event.getCode() == javafx.scene.input.KeyCode.C && event.isControlDown()) {
            data = "\003".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.D && event.isControlDown()) {
            data = "\004".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.Z && event.isControlDown()) {
            data = "\032".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.L && event.isControlDown()) {
            data = "\014".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.A && event.isControlDown()) {
            data = "\001".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.E && event.isControlDown()) {
            data = "\005".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.U && event.isControlDown()) {
            data = "\025".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.K && event.isControlDown()) {
            data = "\013".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.W && event.isControlDown()) {
            data = "\027".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.R && event.isControlDown()) {
            data = "\022".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.D && event.isControlDown() && event.isShiftDown()) {
            // Ctrl+Shift+D: 调试 - 导出终端缓冲区到文件
            try {
                String dump = emulator.dumpBuffer();
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("/tmp/terminal_dump.txt"),
                    dump + "\n"
                );
                System.out.println("Terminal buffer dumped to /tmp/terminal_dump.txt");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            event.consume();
            return;
        }

        if (data != null) {
            keyInputHandler.handleInput(data);
            event.consume();
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        if (keyInputHandler == null) return;

        // 键盘输入时重置光标闪烁
        resetCursorBlink();

        String ch = event.getCharacter();
        if (ch != null && !ch.isEmpty()) {
            char c = ch.charAt(0);
            if (c >= 0x20 && c != 0x7F) {
                keyInputHandler.handleInput(ch.getBytes());
                event.consume();
            }
        }
    }

    /**
     * 渲染终端
     */
    public void render() {
        int cols = emulator.getCols();
        int rows = emulator.getRows();
        int scrollOffset = emulator.getScrollOffset();
        // 交替屏幕缓冲区模式下不显示主缓冲区的scrollback
        int scrollbackSize = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollbackSize();
        double x0 = 2;
        double y0 = 2;

        gc.setFill(defaultBg);
        gc.fillRect(0, 0, getWidth(), getHeight());

        gc.setFont(javafx.scene.text.Font.font(fontFamily, 13));

        for (int y = 0; y < rows; y++) {
            double py = y0 + y * charHeight;

            int scrollbackStart = scrollbackSize - scrollOffset;
            int lineInScrollback = scrollbackStart + y;

            char[] lineChars;
            int[] lineAttrs;
            int bufY = -1;
            if (lineInScrollback >= 0 && lineInScrollback < scrollbackSize) {
                lineChars = emulator.getScrollbackLine(lineInScrollback);
                lineAttrs = emulator.getScrollbackAttrLine(lineInScrollback);
                if (lineChars == null) continue;
            } else if (lineInScrollback >= scrollbackSize) {
                bufY = lineInScrollback - scrollbackSize;
                if (bufY >= rows) continue;
                lineChars = null;
                lineAttrs = null;
            } else {
                continue;
            }

            int runStart = 0;
            int firstAttr;
            if (lineChars != null) {
                firstAttr = (lineAttrs != null && lineAttrs.length > 0) ? lineAttrs[0] : 0;
            } else {
                firstAttr = emulator.getAttr(0, bufY);
            }

            for (int x = 0; x <= cols; x++) {
                int attr;
                if (lineChars != null) {
                    attr = (x < cols && x < lineAttrs.length) ? lineAttrs[x] : -1;
                } else {
                    attr = (x < cols) ? emulator.getAttr(x, bufY) : -1;
                }

                if (attr != firstAttr || x == cols) {
                    if (x > runStart) {
                        Color bg = getAttrBg(firstAttr);
                        gc.setFill(bg);
                        gc.fillRect(x0 + runStart * charWidth, py, (x - runStart) * charWidth, charHeight);

                        Color fg = getAttrFg(firstAttr);
                        gc.setFill(fg);
                        for (int i = runStart; i < x; i++) {
                            char c;
                            if (lineChars != null) {
                                c = (i < lineChars.length) ? lineChars[i] : ' ';
                            } else {
                                c = emulator.getChar(i, bufY);
                            }
                            if (c == '\0') continue;
                            gc.fillText(String.valueOf(c), x0 + i * charWidth, py + fontAscent);
                        }
                    }
                    runStart = x;
                    firstAttr = attr;
                }
            }
        }

        // 渲染光标（仅在没有回滚偏移时显示）
        if (emulator.isCursorVisible() && cursorBlinkOn && scrollOffset == 0) {
            int curX = emulator.getCursorX();
            int curY = emulator.getCursorY();
            // 如果光标在宽字符占位符(\0)上，回退到宽字符的首列
            char cursorChar = emulator.getChar(curX, curY);
            int cursorCol = curX;
            if (cursorChar == '\0' && curX > 0) {
                cursorCol = curX - 1;
                cursorChar = emulator.getChar(cursorCol, curY);
            }
            double cx = x0 + cursorCol * charWidth;
            double cy = y0 + curY * charHeight;
            double cursorWidth = (cursorChar != '\0' && emulator.isWideChar(cursorChar)) ? 2 * charWidth : charWidth;
            gc.setFill(Color.WHITE);
            gc.fillRect(cx, cy, cursorWidth, charHeight);
            gc.setFill(Color.BLACK);
            gc.fillText(String.valueOf(cursorChar == '\0' ? ' ' : cursorChar), cx, cy + fontAscent);
        }

        // 渲染选择高亮
        if (hasSelection()) {
            int startRow, endRow, startCol, endCol;
            if (selectionStartRow < selectionEndRow ||
                (selectionStartRow == selectionEndRow && selectionStartCol <= selectionEndCol)) {
                startRow = selectionStartRow;
                startCol = selectionStartCol;
                endRow = selectionEndRow;
                endCol = selectionEndCol;
            } else {
                startRow = selectionEndRow;
                startCol = selectionEndCol;
                endRow = selectionStartRow;
                endCol = selectionStartCol;
            }

            gc.setFill(Color.rgb(0x42, 0x85, 0xF4, 0.5)); // 蓝色半透明
            for (int row = startRow; row <= endRow; row++) {
                int lineStart = (row == startRow) ? startCol : 0;
                int lineEnd = (row == endRow) ? endCol : cols - 1;
                double sx = x0 + lineStart * charWidth;
                double sy = y0 + row * charHeight;
                double sw = (lineEnd - lineStart + 1) * charWidth;
                gc.fillRect(sx, sy, sw, charHeight);
            }
        }

        // 通知滚动条更新
        notifyScrollbar();
    }

    private Color getAttrFg(int attr) {
        int fgIdx = emulator.getFg(attr);
        if (fgIdx < 0 || fgIdx >= FX_COLORS.length) return defaultFg;
        return FX_COLORS[fgIdx];
    }

    private Color getAttrBg(int attr) {
        int bgIdx = emulator.getBg(attr);
        if (bgIdx == 0) return defaultBg;
        if (bgIdx < 0 || bgIdx >= FX_COLORS.length) return defaultBg;
        return FX_COLORS[bgIdx];
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    public double getCharWidth() {
        return charWidth;
    }

    public double getCharHeight() {
        return charHeight;
    }

    /**
     * 重置光标闪烁，立即显示光标
     */
    private void resetCursorBlink() {
        cursorBlinkOn = true;
        cursorBlinkTimer.stop();
        cursorBlinkTimer.play();
    }

    /**
     * 停止光标闪烁（断开连接时调用）
     */
    public void stopBlink() {
        cursorBlinkTimer.stop();
    }

    // ==================== 文本选择功能 ====================

    /**
     * 将鼠标像素坐标转换为字符坐标
     */
    private int mouseToCol(double mouseX) {
        int col = (int) ((mouseX - 2) / charWidth);
        return Math.max(0, Math.min(col, emulator.getCols() - 1));
    }

    private int mouseToRow(double mouseY) {
        int row = (int) ((mouseY - 2) / charHeight);
        return Math.max(0, Math.min(row, emulator.getRows() - 1));
    }

    private void handleMousePressed(MouseEvent e) {
        requestFocus();
        if (e.getButton() == MouseButton.PRIMARY) {
            // 双击/三击时不在此处重置选择，由handleMouseClicked处理
            if (e.getClickCount() > 1) return;
            isSelecting = true;
            selectionStartCol = mouseToCol(e.getX());
            selectionStartRow = mouseToRow(e.getY());
            selectionEndCol = selectionStartCol;
            selectionEndRow = selectionStartRow;
            render();
        }
    }

    private void handleMouseDragged(MouseEvent e) {
        if (isSelecting && e.getButton() == MouseButton.PRIMARY) {
            selectionEndCol = mouseToCol(e.getX());
            selectionEndRow = mouseToRow(e.getY());
            render();
        }
    }

    private void handleMouseReleased(MouseEvent e) {
        if (isSelecting && e.getButton() == MouseButton.PRIMARY) {
            selectionEndCol = mouseToCol(e.getX());
            selectionEndRow = mouseToRow(e.getY());
            isSelecting = false;
            // 如果起点和终点相同，视为单击，清除选择
            if (selectionStartCol == selectionEndCol && selectionStartRow == selectionEndRow) {
                clearSelection();
            } else {
                render();
            }
        }
    }

    private void handleMouseClicked(MouseEvent e) {
        requestFocus();
        if (e.getButton() != MouseButton.PRIMARY) return;

        int col = mouseToCol(e.getX());
        int row = mouseToRow(e.getY());
        int cols = emulator.getCols();

        if (e.getClickCount() == 2) {
            // 双击选中单词（以不可见字符为分隔）
            int startCol = col;
            int endCol = col;
            // 如果点击在宽字符占位符(\0)上，回退到宽字符主cell
            if (emulator.getChar(startCol, row) == '\0' && startCol > 0) {
                startCol--;
                endCol = startCol;
            }
            // 向左查找单词边界
            while (startCol > 0 && isWordChar(emulator.getChar(startCol - 1, row))) {
                startCol--;
            }
            // 向右查找单词边界
            while (endCol < cols - 1 && isWordChar(emulator.getChar(endCol + 1, row))) {
                endCol++;
            }
            // 确保选中范围包含完整的宽字符（如果endCol停在宽字符主cell上，需要包含其占位符）
            if (emulator.isWideChar(emulator.getChar(endCol, row)) && endCol + 1 < cols) {
                endCol++;
            }
            selectionStartRow = row;
            selectionStartCol = startCol;
            selectionEndRow = row;
            selectionEndCol = endCol;
            isSelecting = false;
            render();
        } else if (e.getClickCount() == 3) {
            // 三击选中整行
            selectionStartRow = row;
            selectionStartCol = 0;
            selectionEndRow = row;
            selectionEndCol = cols - 1;
            isSelecting = false;
            render();
        }
    }

    /**
     * 判断字符是否为单词字符（用于双击选词）
     * 宽字符占位符(\0)视为单词字符（属于宽字符的延续部分）
     * 空格、制表符等空白字符和控制字符作为单词分隔符
     */
    private boolean isWordChar(char c) {
        if (c == '\0') return true; // 宽字符占位符，属于宽字符的一部分
        return !Character.isWhitespace(c) && c > 0x1F && c != 0x7F;
    }

    /**
     * 鼠标滚轮滚动回滚历史
     */
    private void handleScroll(ScrollEvent e) {
        // 交替屏幕缓冲区模式下不允许滚动scrollback
        if (emulator.isUsingAltBuffer()) return;

        int scrollbackSize = emulator.getScrollbackSize();
        if (scrollbackSize == 0) return;

        int delta = (int) e.getDeltaY();
        if (delta == 0) return;

        // 标准化滚动量
        int lines = Math.max(1, Math.abs(delta / 40));
        int oldOffset = emulator.getScrollOffset();
        int newOffset;
        if (delta > 0) {
            // 滚轮向上（回看更早的历史）
            newOffset = Math.min(oldOffset + lines, scrollbackSize);
        } else {
            // 滚轮向下（回到最新的输出）
            newOffset = Math.max(oldOffset - lines, 0);
        }

        if (newOffset != oldOffset) {
            emulator.setScrollOffset(newOffset);
            render();
            notifyScrollbar();
        }
    }

    private void notifyScrollbar() {
        if (scrollbarHandler != null) {
            // 交替屏幕缓冲区模式下报告scrollback为0，使滚动条隐藏
            int sbSize = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollbackSize();
            int sbOffset = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollOffset();
            // 只在值变化时通知，避免循环
            if (sbSize != lastNotifiedScrollbackSize || sbOffset != lastNotifiedScrollOffset) {
                lastNotifiedScrollbackSize = sbSize;
                lastNotifiedScrollOffset = sbOffset;
                scrollbarHandler.onScrollChanged(sbSize, sbOffset, emulator.getRows());
            }
        }
    }

    // 上次通知的值，避免重复通知
    private int lastNotifiedScrollbackSize = -1;
    private int lastNotifiedScrollOffset = -1;

    /**
     * 是否有选中文本
     */
    public boolean hasSelection() {
        return selectionStartCol >= 0 && selectionStartRow >= 0
                && selectionEndCol >= 0 && selectionEndRow >= 0
                && (selectionStartCol != selectionEndCol || selectionStartRow != selectionEndRow);
    }

    /**
     * 获取选中的文本
     */
    public String getSelectedText() {
        if (!hasSelection()) return "";

        int cols = emulator.getCols();
        int startRow, endRow, startCol, endCol;

        if (selectionStartRow < selectionEndRow ||
            (selectionStartRow == selectionEndRow && selectionStartCol <= selectionEndCol)) {
            startRow = selectionStartRow;
            startCol = selectionStartCol;
            endRow = selectionEndRow;
            endCol = selectionEndCol;
        } else {
            startRow = selectionEndRow;
            startCol = selectionEndCol;
            endRow = selectionStartRow;
            endCol = selectionStartCol;
        }

        StringBuilder sb = new StringBuilder();
        for (int row = startRow; row <= endRow; row++) {
            int lineStart = (row == startRow) ? startCol : 0;
            int lineEnd = (row == endRow) ? endCol : cols - 1;
            for (int col = lineStart; col <= lineEnd; col++) {
                char c = emulator.getChar(col, row);
                if (c == '\0') continue; // 跳过宽字符占位符
                sb.append(c);
            }
            if (row < endRow) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 全选
     */
    public void selectAll() {
        selectionStartRow = 0;
        selectionStartCol = 0;
        selectionEndRow = emulator.getRows() - 1;
        selectionEndCol = emulator.getCols() - 1;
        render();
    }

    /**
     * 清除选择
     */
    public void clearSelection() {
        selectionStartCol = -1;
        selectionStartRow = -1;
        selectionEndCol = -1;
        selectionEndRow = -1;
        isSelecting = false;
        render();
    }

    /**
     * 复制选中文本到系统剪贴板
     */
    public void copySelection() {
        String text = getSelectedText();
        if (!text.isEmpty()) {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            clipboard.setContent(content);
        }
    }
}
