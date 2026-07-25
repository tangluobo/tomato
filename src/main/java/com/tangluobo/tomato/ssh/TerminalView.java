package com.tangluobo.tomato.ssh;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;

/**
 * 终端视图组件，使用Canvas渲染TerminalEmulator的字符缓冲区
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

    public interface KeyInputHandler {
        void handleInput(byte[] data);
    }

    public TerminalView(TerminalEmulator emulator) {
        this.emulator = emulator;
        this.gc = getGraphicsContext2D();

        // 初始化字体度量
        updateFontMetrics();

        // 设置初始大小
        double width = emulator.getCols() * charWidth + 4;
        double height = emulator.getRows() * charHeight + 4;
        setWidth(width);
        setHeight(height);

        // 键盘事件
        setFocusTraversable(true);
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyTyped(this::handleKeyTyped);

        // 鼠标点击聚焦
        setOnMouseClicked(e -> requestFocus());

        // 宽度变化时重新计算列数
        widthProperty().addListener((obs, oldVal, newVal) -> {
            int newCols = (int) ((newVal.doubleValue() - 4) / charWidth);
            if (newCols > 0 && newCols != emulator.getCols()) {
                emulator.resize(newCols, emulator.getRows());
                render();
            }
        });
        heightProperty().addListener((obs, oldVal, newVal) -> {
            int newRows = (int) ((newVal.doubleValue() - 4) / charHeight);
            if (newRows > 0 && newRows != emulator.getRows()) {
                emulator.resize(emulator.getCols(), newRows);
                render();
            }
        });

        render();
    }

    public void setKeyInputHandler(KeyInputHandler handler) {
        this.keyInputHandler = handler;
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

        byte[] data = null;
        if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
            data = "\r".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
            data = "\b".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.TAB) {
            data = "\t".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.UP) {
            data = "\033[A".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.DOWN) {
            data = "\033[B".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.RIGHT) {
            data = "\033[C".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.LEFT) {
            data = "\033[D".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.HOME) {
            data = "\033[H".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.END) {
            data = "\033[F".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.DELETE) {
            data = "\033[3~".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.PAGE_UP) {
            data = "\033[5~".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.PAGE_DOWN) {
            data = "\033[6~".getBytes();
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
        }

        if (data != null) {
            keyInputHandler.handleInput(data);
            event.consume();
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        if (keyInputHandler == null) return;

        String ch = event.getCharacter();
        if (ch != null && !ch.isEmpty()) {
            char c = ch.charAt(0);
            // 只发送可打印字符（不包括控制字符，已由keyPressed处理）
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
        double x0 = 2;
        double y0 = 2;

        // 清除背景
        gc.setFill(defaultBg);
        gc.fillRect(0, 0, getWidth(), getHeight());

        // 设置字体
        gc.setFont(javafx.scene.text.Font.font(fontFamily, 13));

        // 逐行渲染
        for (int y = 0; y < rows; y++) {
            double py = y0 + y * charHeight;

            // 逐字符渲染（为性能优化，合并相同属性的连续字符）
            int runStart = 0;
            int runAttr = emulator.getAttr(0, y);

            for (int x = 0; x <= cols; x++) {
                int attr = (x < cols) ? emulator.getAttr(x, y) : -1;
                if (attr != runAttr || x == cols) {
                    // 渲染从runStart到x的连续字符
                    if (x > runStart) {
                        // 渲染背景
                        Color bg = getAttrBg(runAttr);
                        gc.setFill(bg);
                        gc.fillRect(x0 + runStart * charWidth, py, (x - runStart) * charWidth, charHeight);

                        // 渲染前景文本
                        Color fg = getAttrFg(runAttr);
                        gc.setFill(fg);

                        StringBuilder sb = new StringBuilder();
                        for (int i = runStart; i < x; i++) {
                            char c = emulator.getChar(i, y);
                            sb.append(c == '\0' ? ' ' : c);
                        }
                        gc.fillText(sb.toString(), x0 + runStart * charWidth, py + fontAscent);
                    }
                    runStart = x;
                    runAttr = attr;
                }
            }
        }

        // 渲染光标
        if (emulator.isCursorVisible()) {
            double cx = x0 + emulator.getCursorX() * charWidth;
            double cy = y0 + emulator.getCursorY() * charHeight;
            gc.setFill(Color.WHITE);
            gc.fillRect(cx, cy, charWidth, charHeight);
            // 反色显示光标位置的字符
            gc.setFill(Color.BLACK);
            char c = emulator.getChar(emulator.getCursorX(), emulator.getCursorY());
            gc.fillText(String.valueOf(c == '\0' ? ' ' : c), cx, cy + fontAscent);
        }
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
}
