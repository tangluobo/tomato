package com.tangluobo.tomato.ssh;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyEvent;
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

    public interface KeyInputHandler {
        void handleInput(byte[] data);
    }

    public interface ResizeHandler {
        void onResize(int cols, int rows, int width, int height);
    }

    public TerminalView(TerminalEmulator emulator) {
        this.emulator = emulator;
        this.gc = getGraphicsContext2D();

        // 初始化字体度量
        updateFontMetrics();

        // 键盘事件
        setFocusTraversable(true);
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyTyped(this::handleKeyTyped);

        // 鼠标点击聚焦
        setOnMouseClicked(e -> requestFocus());

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

            int runStart = 0;
            int runAttr = emulator.getAttr(0, y);

            for (int x = 0; x <= cols; x++) {
                int attr = (x < cols) ? emulator.getAttr(x, y) : -1;
                if (attr != runAttr || x == cols) {
                    if (x > runStart) {
                        Color bg = getAttrBg(runAttr);
                        gc.setFill(bg);
                        gc.fillRect(x0 + runStart * charWidth, py, (x - runStart) * charWidth, charHeight);

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

        // 渲染光标（闪烁）
        if (emulator.isCursorVisible() && cursorBlinkOn) {
            double cx = x0 + emulator.getCursorX() * charWidth;
            double cy = y0 + emulator.getCursorY() * charHeight;
            gc.setFill(Color.WHITE);
            gc.fillRect(cx, cy, charWidth, charHeight);
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
}
