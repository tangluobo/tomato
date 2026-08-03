package com.tangluobo.tomato.ssh;

/**
 * VT100终端模拟器
 * 解析ANSI转义序列，维护字符缓冲区和光标状态
 * 支持滚动区域、交替屏幕缓冲区
 */
public class TerminalEmulator {

    public static final int DEFAULT_COLS = 80;
    public static final int DEFAULT_ROWS = 24;
    public static final int DEFAULT_SCROLLBACK = 1000;

    // 字符缓冲区
    private char[][] buffer;
    // 字符属性（前景色、背景色等）
    private int[][] attrs;
    // 缓冲区大小
    private int cols;
    private int rows;
    // 光标位置
    private int cursorX = 0;
    private int cursorY = 0;
    // 已保存的光标位置
    private int savedCursorX = 0;
    private int savedCursorY = 0;
    // 是否显示光标
    private boolean cursorVisible = true;
    // 是否换行模式
    private boolean autoWrap = true;
    // 是否插入模式(IRM) - 插入字符而非替换
    private boolean insertMode = false;
    // 是否应用光标键模式(DECCKM) - 方向键发送\033OA而非\033[A
    private boolean applicationCursorKeys = false;
    // 是否原点模式(DECOM) - 光标定位相对于滚动区域
    private boolean originMode = false;
    // ANSI.SYS保存的光标位置（不同于DECSC的savedCursorX/Y）
    private int ansiSavedCursorX = 0;
    private int ansiSavedCursorY = 0;
    // 滚动区域（1-based，0表示整屏）
    private int scrollTop = 0;
    private int scrollBottom = 0;

    // 交替屏幕缓冲区
    private char[][] altBuffer;
    private int[][] altAttrs;
    private int altCursorX = 0;
    private int altCursorY = 0;
    private int altSavedCursorX = 0;
    private int altSavedCursorY = 0;
    private boolean usingAltBuffer = false;
    // 自动交替缓冲区标志：当检测到全屏程序（如top）未发送标准交替缓冲区序列时，
    // 通过CSI 2J+应用光标键模式自动切换到交替缓冲区
    private boolean autoAltBuffer = false;
    // 交替缓冲区专用光标保存（不受DECSC/DECRC即ESC 7/8影响）
    private int altBufferSavedCursorX = 0;
    private int altBufferSavedCursorY = 0;
    // 抑制屏幕修改标志：从交替缓冲区切回主缓冲区后，全屏程序（如top）退出时
    // 可能发送清屏、换行、清除行等序列，该标志用于抑制这些修改命令，
    // 直到确认shell已开始输出为止
    private boolean suppressScreenModify = false;
    // 抑制到期时间戳（毫秒）：在此时间之前持续抑制屏幕修改，
    // 防止用户按键或网络延迟导致抑制被过早清除
    private long suppressUntilTime = 0;
    // 抑制最短到期时间戳：在此时间之前，即使用户按键也不清除抑制
    // 确保最短抑制时间（200ms），防止用户Ctrl+C退出程序时过早清除抑制
    private long suppressMinUntilTime = 0;
    // 光标位置守卫：交替缓冲区切回后，如果清理序列将光标移到第0行，
    // 强制恢复到正确位置。这是suppressScreenModify的安全网，
    // 处理suppress超时后仍有延迟序列到达的情况
    private boolean cursorGuardActive = false;
    private long cursorGuardUntilTime = 0;
    private int guardTargetCursorX = 0;
    private int guardTargetCursorY = 0;

    // 回滚历史缓冲区
    private java.util.LinkedList<char[]> scrollbackLines = new java.util.LinkedList<>();
    private java.util.LinkedList<int[]> scrollbackAttrs = new java.util.LinkedList<>();
    private java.util.LinkedList<int[]> scrollbackFgTc = new java.util.LinkedList<>();
    private java.util.LinkedList<int[]> scrollbackBgTc = new java.util.LinkedList<>();
    private int maxScrollback = DEFAULT_SCROLLBACK;
    // 用户滚动偏移（0=底部，>0=向上回看）
    private int scrollOffset = 0;
    // 切换到交替缓冲区时保存主缓冲区的滚动偏移
    private int mainBufferScrollOffset = 0;

    // ANSI解析状态
    private ParseState parseState = ParseState.NORMAL;
    private StringBuilder escapeSeq = new StringBuilder();
    private StringBuilder oscSeq = new StringBuilder();
    // 响应回调（发送数据回SSH服务器）
    private ResponseHandler responseHandler;

    // 工作目录变化回调
    public interface CwdChangeListener {
        void onCwdChanged(String path);
    }
    private CwdChangeListener cwdChangeListener;
    // 调试输出
    private java.util.function.Consumer<String> debugWriter;
    // 统一文件日志（同时记录RECV/CSI/CURSOR）
    private java.io.PrintWriter fileLogger;
    // 当前属性
    private int currentFg = 7; // 默认白色
    private int currentBg = 0; // 默认黑色
    private boolean bold = false;
    private boolean underline = false;
    private boolean reverse = false;

    // 交替缓冲区颜色状态保存（切换到alt buffer时保存，切回时恢复）
    private int savedFg = 7;
    private int savedBg = 0;
    private boolean savedBold = false;
    private boolean savedUnderline = false;
    private boolean savedReverse = false;

    // 真彩色存储（24-bit RGB值，-1表示未使用真彩色）
    private int[] fgTrueColor;
    private int[] bgTrueColor;
    private int[] altFgTrueColor;
    private int[] altBgTrueColor;

    // 当前颜色是否为真彩色
    private boolean currentFgTrueColor = false;
    private boolean currentBgTrueColor = false;
    private int currentFgRgb = 0;
    private int currentBgRgb = 0;

    // 保存的真彩色状态（用于alt buffer切换）
    private boolean savedFgTrueColor = false;
    private boolean savedBgTrueColor = false;
    private int savedFgRgb = 0;
    private int savedBgRgb = 0;

    // 真彩色标志位（存在attr int的bit19/bit20）
    private static final int FG_TRUE_COLOR_FLAG = 0x80000;
    private static final int BG_TRUE_COLOR_FLAG = 0x100000;

    private enum ParseState {
        NORMAL, ESC, CSI, OSC, CHARSET
    }

    // ANSI 256色表（0-15: 标准色; 16-231: 6x6x6立方体; 232-255: 灰度）
    public static final int[] COLOR_TABLE_256;
    static {
        COLOR_TABLE_256 = new int[256];
        // 0-15: 标准16色
        int[] base16 = {
            0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff
        };
        System.arraycopy(base16, 0, COLOR_TABLE_256, 0, 16);
        // 16-231: 6x6x6颜色立方体
        int[] cubeValues = {0, 95, 135, 175, 215, 255};
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    int idx = 16 + r * 36 + g * 6 + b;
                    COLOR_TABLE_256[idx] = (cubeValues[r] << 16) | (cubeValues[g] << 8) | cubeValues[b];
                }
            }
        }
        // 232-255: 灰度
        for (int i = 0; i < 24; i++) {
            int gray = 8 + i * 10;
            COLOR_TABLE_256[232 + i] = (gray << 16) | (gray << 8) | gray;
        }
    }

    @Deprecated
    public static final int[] COLOR_TABLE = COLOR_TABLE_256;

    public TerminalEmulator() {
        this(DEFAULT_COLS, DEFAULT_ROWS);
    }

    public TerminalEmulator(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        this.buffer = new char[rows][cols];
        this.attrs = new int[rows][cols];
        this.fgTrueColor = new int[rows * cols];
        this.bgTrueColor = new int[rows * cols];
        this.altFgTrueColor = new int[rows * cols];
        this.altBgTrueColor = new int[rows * cols];
        java.util.Arrays.fill(fgTrueColor, -1);
        java.util.Arrays.fill(bgTrueColor, -1);
        java.util.Arrays.fill(altFgTrueColor, -1);
        java.util.Arrays.fill(altBgTrueColor, -1);
        clearBuffer(buffer, attrs);
        initAltBuffer();
    }

    private void initAltBuffer() {
        altBuffer = new char[rows][cols];
        altAttrs = new int[rows][cols];
        clearBuffer(altBuffer, altAttrs);
    }

    private void clearBuffer(char[][] buf, int[][] att) {
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                buf[y][x] = ' ';
                att[y][x] = makeAttr(7, 0, false, false, false);
                int idx = y * cols + x;
                fgTrueColor[idx] = -1;
                bgTrueColor[idx] = -1;
            }
        }
    }

    private int makeAttr(int fg, int bg, boolean bold, boolean underline, boolean reverse) {
        return (fg & 0xFF) | ((bg & 0xFF) << 8)
                | (bold ? 0x10000 : 0)
                | (underline ? 0x20000 : 0)
                | (reverse ? 0x40000 : 0);
    }

    public int getFg(int attr) { return attr & 0xFF; }
    public int getBg(int attr) { return (attr >> 8) & 0xFF; }
    public boolean isBold(int attr) { return (attr & 0x10000) != 0; }
    public boolean isUnderline(int attr) { return (attr & 0x20000) != 0; }
    public boolean isReverse(int attr) { return (attr & 0x40000) != 0; }
    public boolean isFgTrueColor(int attr) { return (attr & FG_TRUE_COLOR_FLAG) != 0; }
    public boolean isBgTrueColor(int attr) { return (attr & BG_TRUE_COLOR_FLAG) != 0; }

    /**
     * 获取指定单元格的前景真彩色RGB值（-1表示未使用真彩色）
     */
    public int getFgTrueColor(int x, int y) {
        return fgTrueColor[y * cols + x];
    }

    /**
     * 获取指定单元格的背景真彩色RGB值（-1表示未使用真彩色）
     */
    public int getBgTrueColor(int x, int y) {
        return bgTrueColor[y * cols + x];
    }

    /**
     * 设置指定单元格的前景真彩色
     */
    public void setFgTrueColor(int x, int y, int rgb) {
        fgTrueColor[y * cols + x] = rgb;
    }

    /**
     * 设置指定单元格的背景真彩色
     */
    public void setBgTrueColor(int x, int y, int rgb) {
        bgTrueColor[y * cols + x] = rgb;
    }

    /**
     * 清除指定单元格的真彩色标记
     */
    public void clearTrueColor(int x, int y) {
        fgTrueColor[y * cols + x] = -1;
        bgTrueColor[y * cols + x] = -1;
    }

    public int getCols() { return cols; }
    public int getRows() { return rows; }
    public int getCursorX() { return cursorX; }
    public int getCursorY() { return cursorY; }
    public boolean isCursorVisible() { return cursorVisible; }
    public boolean isApplicationCursorKeys() { return applicationCursorKeys; }
    public char getChar(int x, int y) { return buffer[y][x]; }
    public int getAttr(int x, int y) { return attrs[y][x]; }

    public interface ResponseHandler {
        void sendResponse(byte[] data);
    }

    public void setResponseHandler(ResponseHandler handler) {
        this.responseHandler = handler;
    }

    public void setDebugWriter(java.util.function.Consumer<String> writer) {
        this.debugWriter = writer;
    }

    /**
     * 设置文件日志，统一记录所有调试信息
     */
    public void setFileLogger(java.io.PrintWriter logger) {
        this.fileLogger = logger;
    }

    /**
     * 统一日志输出（同时写System.err和文件日志）
     */
    private void log(String msg) {
        System.err.println(msg);
        if (fileLogger != null) {
            fileLogger.println(msg);
            fileLogger.flush();
        }
    }

    public void setCwdChangeListener(CwdChangeListener listener) {
        this.cwdChangeListener = listener;
    }

    private void sendResponse(String data) {
        if (responseHandler != null) {
            responseHandler.sendResponse(data.getBytes());
        }
    }

    /**
     * 调试日志：输出完整的光标/缓冲区状态快照
     */
    private void debugLogCursorState(String event) {
        log("[Terminal-CURSOR] " + event
            + " | cursor=(" + cursorX + "," + cursorY + ")"
            + " saved=(" + savedCursorX + "," + savedCursorY + ")"
            + " ansiSaved=(" + ansiSavedCursorX + "," + ansiSavedCursorY + ")"
            + " altBufSaved=(" + altBufferSavedCursorX + "," + altBufferSavedCursorY + ")"
            + " altSaved=(" + altSavedCursorX + "," + altSavedCursorY + ")"
            + " altBuf=" + usingAltBuffer
            + " autoAlt=" + autoAltBuffer
            + " appCursor=" + applicationCursorKeys
            + " suppress=" + suppressScreenModify
            + " guard=" + cursorGuardActive
            + " origin=" + originMode
            + " scroll=[" + scrollTop + "," + scrollBottom + "]");
    }

    private static String bytesToHex(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + len && i < data.length; i++) {
            int b = data[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                sb.append(String.format("<%02X>", b));
            } else if (b == 0x20) {
                sb.append(' ');
            } else {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    /**
     * 导出当前缓冲区内容（调试用）
     */
    public String dumpBuffer() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                char c = buffer[y][x];
                sb.append(c == '\0' ? '·' : c);
            }
            sb.append('\n');
        }
        sb.append("cursor=(").append(cursorX).append(",").append(cursorY)
          .append(") scrollRegion=[").append(scrollTop).append(",").append(scrollBottom)
          .append("] originMode=").append(originMode)
          .append(" insertMode=").append(insertMode)
          .append(" appCursor=").append(applicationCursorKeys)
          .append(" altBuf=").append(usingAltBuffer);
        return sb.toString();
    }

    // === 回滚/滚动相关方法 ===

    public int getScrollbackSize() {
        return scrollbackLines.size();
    }

    public int getMaxScrollback() {
        return maxScrollback;
    }

    public void setMaxScrollback(int max) {
        this.maxScrollback = Math.max(0, max);
        while (scrollbackLines.size() > maxScrollback) {
            scrollbackLines.removeFirst();
            scrollbackAttrs.removeFirst();
        }
        if (scrollOffset > scrollbackLines.size()) {
            scrollOffset = scrollbackLines.size();
        }
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    /**
     * 是否正在使用交替屏幕缓冲区
     */
    public boolean isUsingAltBuffer() {
        return usingAltBuffer;
    }

    /**
     * 用户按键时调用
     * 如果最短抑制时间已过，清除抑制标志，让shell输出正常显示
     */
    public void onUserInput() {
        if (suppressScreenModify && System.currentTimeMillis() >= suppressMinUntilTime) {
            suppressScreenModify = false;
            debugLogCursorState("suppressScreenModify cleared by user input (after min duration)");
        }
    }

    /**
     * 检查是否应该抑制屏幕修改
     * suppressScreenModify为true且当前时间未超过suppressUntilTime时返回true
     */
    private boolean shouldSuppressScreenModify() {
        if (suppressScreenModify) {
            if (System.currentTimeMillis() < suppressUntilTime) {
                return true;
            }
            // 时间戳已过期，清除抑制标志
            suppressScreenModify = false;
            debugLogCursorState("suppressScreenModify expired (time-based)");
        }
        return false;
    }

    /**
     * 激活屏幕修改抑制
     * 最短抑制200ms（此期间即使用户按键也不清除），最长抑制1000ms（自动过期）
     */
    private void activateScreenSuppress() {
        long now = System.currentTimeMillis();
        suppressScreenModify = true;
        suppressMinUntilTime = now + 200;
        suppressUntilTime = now + 1000;
        debugLogCursorState("activateScreenSuppress (min=200ms, max=1000ms)");
    }

    /**
     * 激活光标位置守卫（使用当前光标位置作为目标）
     */
    private void activateCursorGuard() {
        activateCursorGuard(cursorX, cursorY);
    }

    /**
     * 激活光标位置守卫
     * 在交替缓冲区切回后，如果清理序列将光标移到第0行（光标跳到第一行），
     * 强制恢复到正确的光标位置。持续2000ms作为安全网
     * @param targetX 守卫目标光标X
     * @param targetY 守卫目标光标Y
     */
    private void activateCursorGuard(int targetX, int targetY) {
        cursorGuardActive = true;
        cursorGuardUntilTime = System.currentTimeMillis() + 2000;
        guardTargetCursorX = targetX;
        guardTargetCursorY = targetY;
        debugLogCursorState("activateCursorGuard (target=(" + guardTargetCursorX + "," + guardTargetCursorY + "), 2000ms)");
    }

    /**
     * 检查并执行光标位置守卫
     * 如果光标被清理序列移到了第0行（而原始位置不在第0行），强制恢复
     */
    private void checkCursorGuard() {
        if (!cursorGuardActive) return;
        if (System.currentTimeMillis() > cursorGuardUntilTime) {
            cursorGuardActive = false;
            return;
        }
        // 光标被移到第0行，但原始位置不在第0行 → 强制恢复
        if (cursorY == 0 && guardTargetCursorY > 0) {
            debugLogCursorState("CURSOR GUARD: cursor at row 0 but should be at row " + guardTargetCursorY + ", restoring");
            cursorX = guardTargetCursorX;
            cursorY = guardTargetCursorY;
        }
    }

    /**
     * 设置滚动偏移（由滚动条驱动）
     * @param offset 0=底部（最新），max=顶部（最旧）
     */
    public void setScrollOffset(int offset) {
        this.scrollOffset = Math.max(0, Math.min(offset, scrollbackLines.size()));
    }

    /**
     * 获取指定行的字符和属性（考虑scrollback偏移）
     * @param screenRow 屏幕行号（0~rows-1）
     * @return char[] 和 int[] 的数据，null表示空行
     */
    public char[] getScrollbackLine(int index) {
        if (index < 0 || index >= scrollbackLines.size()) return null;
        return scrollbackLines.get(index);
    }

    public int[] getScrollbackAttrLine(int index) {
        if (index < 0 || index >= scrollbackAttrs.size()) return null;
        return scrollbackAttrs.get(index);
    }

    public int[] getScrollbackFgTcLine(int index) {
        if (index < 0 || index >= scrollbackFgTc.size()) return null;
        return scrollbackFgTc.get(index);
    }

    public int[] getScrollbackBgTcLine(int index) {
        if (index < 0 || index >= scrollbackBgTc.size()) return null;
        return scrollbackBgTc.get(index);
    }

    /**
     * 获取滚动区域（0-based）
     */
    private int getScrollTop() {
        return scrollTop > 0 ? scrollTop - 1 : 0;
    }

    private int getScrollBottom() {
        return scrollBottom > 0 ? scrollBottom - 1 : rows - 1;
    }

    /**
     * 调整终端大小
     */
    public void resize(int newCols, int newRows) {
        char[][] newBuffer = new char[newRows][newCols];
        int[][] newAttrs = new int[newRows][newCols];
        int[] newFgTc = new int[newRows * newCols];
        int[] newBgTc = new int[newRows * newCols];
        java.util.Arrays.fill(newFgTc, -1);
        java.util.Arrays.fill(newBgTc, -1);

        for (int y = 0; y < newRows; y++) {
            for (int x = 0; x < newCols; x++) {
                int newIdx = y * newCols + x;
                if (y < rows && x < cols) {
                    newBuffer[y][x] = buffer[y][x];
                    newAttrs[y][x] = attrs[y][x];
                    int oldIdx = y * cols + x;
                    newFgTc[newIdx] = fgTrueColor[oldIdx];
                    newBgTc[newIdx] = bgTrueColor[oldIdx];
                } else {
                    newBuffer[y][x] = ' ';
                    newAttrs[y][x] = makeAttr(7, 0, false, false, false);
                }
            }
        }

        // 交替缓冲区也需要调整
        int[] newAltFgTc = new int[newRows * newCols];
        int[] newAltBgTc = new int[newRows * newCols];
        java.util.Arrays.fill(newAltFgTc, -1);
        java.util.Arrays.fill(newAltBgTc, -1);

        if (usingAltBuffer) {
            char[][] newAltBuffer = new char[newRows][newCols];
            int[][] newAltAttrs = new int[newRows][newCols];
            for (int y = 0; y < newRows; y++) {
                for (int x = 0; x < newCols; x++) {
                    int newIdx = y * newCols + x;
                    if (y < rows && x < cols) {
                        newAltBuffer[y][x] = altBuffer[y][x];
                        newAltAttrs[y][x] = altAttrs[y][x];
                        int oldIdx = y * cols + x;
                        newAltFgTc[newIdx] = altFgTrueColor[oldIdx];
                        newAltBgTc[newIdx] = altBgTrueColor[oldIdx];
                    } else {
                        newAltBuffer[y][x] = ' ';
                        newAltAttrs[y][x] = makeAttr(7, 0, false, false, false);
                    }
                }
            }
            altBuffer = newAltBuffer;
            altAttrs = newAltAttrs;
        } else {
            // 未使用alt buffer，重建alt buffer（与原大小相同的空缓冲区）
            altBuffer = new char[newRows][newCols];
            altAttrs = new int[newRows][newCols];
            java.util.Arrays.fill(newAltFgTc, -1);
            java.util.Arrays.fill(newAltBgTc, -1);
            for (int y = 0; y < newRows; y++) {
                for (int x = 0; x < newCols; x++) {
                    altBuffer[y][x] = ' ';
                    altAttrs[y][x] = makeAttr(7, 0, false, false, false);
                }
            }
        }

        this.buffer = newBuffer;
        this.attrs = newAttrs;
        this.fgTrueColor = newFgTc;
        this.bgTrueColor = newBgTc;
        this.altFgTrueColor = newAltFgTc;
        this.altBgTrueColor = newAltBgTc;
        this.cols = newCols;
        this.rows = newRows;
        if (cursorX >= newCols) cursorX = newCols - 1;
        if (cursorY >= newRows) cursorY = newRows - 1;
        scrollTop = 0;
        scrollBottom = 0;
    }

    /**
     * 处理输入的字节流（UTF-8解码后处理）
     */
    public void process(byte[] data, int offset, int len) {
        // 调试：记录收到的原始数据
        if (debugWriter != null) {
            String hex = bytesToHex(data, offset, len);
            debugWriter.accept("[RECV " + len + "] " + hex + "\n");
        }
        String text = new String(data, offset, len, java.nio.charset.StandardCharsets.UTF_8);
        processString(text);
    }

    public void process(byte[] data) {
        process(data, 0, data.length);
    }

    /**
     * 处理UTF-8字符串
     */
    public void processString(String text) {
        for (int i = 0; i < text.length(); i++) {
            processChar(text.charAt(i));
        }
        // 处理完所有字符后，检查光标位置守卫
        // 如果清理序列将光标移到了第0行，强制恢复到正确位置
        checkCursorGuard();
    }

    private void processChar(char ch) {
        switch (parseState) {
            case NORMAL:
                processNormalChar(ch);
                break;
            case ESC:
                processEscChar(ch);
                break;
            case CSI:
                processCsiChar(ch);
                break;
            case OSC:
                processOscChar(ch);
                break;
            case CHARSET:
                // ESC ( 后的一个字符，跳过字符集设置
                parseState = ParseState.NORMAL;
                break;
        }
    }

    private void processNormalChar(char ch) {
        if (ch == 0x1b) { // ESC
            parseState = ParseState.ESC;
            escapeSeq.setLength(0);
        } else if (ch == '\r') {
            // CR: 光标移到行首
            // 抑制交替缓冲区切回后的CR（全屏程序退出清理序列中可能包含\r）
            if (shouldSuppressScreenModify()) {
                debugLogCursorState("SUPPRESS \\r (CR)");
            } else {
                if (cursorX != 0) {
                    debugLogCursorState("\\r (CR)");
                }
                cursorX = 0;
            }
        } else if (ch == '\n') {
            // 换行：抑制交替缓冲区切回后的换行（清理序列中可能包含\n）
            if (shouldSuppressScreenModify()) {
                debugLogCursorState("SUPPRESS \\n (LF)");
            } else {
                cursorY++;
                if (cursorY > getScrollBottom()) {
                    cursorY = getScrollBottom();
                    scrollUp(1);
                }
            }
        } else if (ch == '\t') {
            cursorX = (cursorX / 8 + 1) * 8;
            if (cursorX >= cols) cursorX = cols - 1;
        } else if (ch == '\b') {
            if (cursorX > 0) cursorX--;
        } else if (ch == 7) { // BEL
            // 忽略响铃
        } else if (ch >= 0x20 || Character.isISOControl(ch) == false) {
            // 可打印字符（包括CJK等Unicode字符）
            // 注意：不在此处清除suppressScreenModify
            // 退出程序可能在CSI ?1049l之后输出文本（如退出消息），这些可打印字符
            // 会提前清除抑制标志，导致后续清理序列(CSI H/CSI 2J)不再被抑制，
            // 光标跳到(0,0)且屏幕被清空。正确做法是在用户按键时清除抑制标志。
            boolean wideChar = isWideChar(ch);
            int charWidth = wideChar ? 2 : 1;

            if (cursorX + charWidth > cols) {
                if (autoWrap) {
                    cursorX = 0;
                    cursorY++;
                    if (cursorY > getScrollBottom()) {
                        cursorY = getScrollBottom();
                        scrollUp(1);
                    }
                } else {
                    cursorX = cols - charWidth;
                }
            }
            if (insertMode) {
                // 插入模式：字符插入到光标位置，右侧字符右移
                for (int x = cols - 1; x > cursorX; x--) {
                    buffer[cursorY][x] = buffer[cursorY][x - charWidth];
                    attrs[cursorY][x] = attrs[cursorY][x - charWidth];
                    fgTrueColor[cursorY * cols + x] = fgTrueColor[cursorY * cols + x - charWidth];
                    bgTrueColor[cursorY * cols + x] = bgTrueColor[cursorY * cols + x - charWidth];
                }
            }
            buffer[cursorY][cursorX] = ch;
            int cellAttr = makeAttr(currentFg, currentBg, bold, underline, reverse);
            if (currentFgTrueColor) cellAttr |= FG_TRUE_COLOR_FLAG;
            if (currentBgTrueColor) cellAttr |= BG_TRUE_COLOR_FLAG;
            attrs[cursorY][cursorX] = cellAttr;
            int idx = cursorY * cols + cursorX;
            fgTrueColor[idx] = currentFgTrueColor ? currentFgRgb : -1;
            bgTrueColor[idx] = currentBgTrueColor ? currentBgRgb : -1;
            if (wideChar && cursorX + 1 < cols) {
                buffer[cursorY][cursorX + 1] = 0;
                attrs[cursorY][cursorX + 1] = cellAttr;
                int idx2 = cursorY * cols + cursorX + 1;
                fgTrueColor[idx2] = fgTrueColor[idx];
                bgTrueColor[idx2] = bgTrueColor[idx];
            }
            cursorX += charWidth;
        }
    }

    /**
     * 判断是否为宽字符（CJK等双宽度字符）
     */
    public boolean isWideChar(char ch) {
        if (ch >= 0x1100 && ch <= 0x115F) return true;
        if (ch >= 0x2E80 && ch <= 0x303E) return true;
        if (ch >= 0x3040 && ch <= 0x3247) return true;
        if (ch >= 0x3250 && ch <= 0x4DBF) return true;
        if (ch >= 0x4E00 && ch <= 0x9FFF) return true;
        if (ch >= 0xA960 && ch <= 0xA97C) return true;
        if (ch >= 0xAC00 && ch <= 0xD7A3) return true;
        if (ch >= 0xF900 && ch <= 0xFAFF) return true;
        if (ch >= 0xFE10 && ch <= 0xFE19) return true;
        if (ch >= 0xFE30 && ch <= 0xFE6B) return true;
        if (ch >= 0xFF01 && ch <= 0xFF60) return true;
        if (ch >= 0xFFE0 && ch <= 0xFFE6) return true;
        if (ch >= 0x1F300 && ch <= 0x1F9FF) return true;
        return false;
    }

    private void processEscChar(char ch) {
        if (ch == '[') {
            parseState = ParseState.CSI;
            escapeSeq.setLength(0);
        } else if (ch == ']') {
            parseState = ParseState.OSC;
            escapeSeq.setLength(0);
            oscSeq.setLength(0);
        } else if (ch == '7') {
            savedCursorX = cursorX;
            savedCursorY = cursorY;
            debugLogCursorState("ESC 7 (DECSC save cursor)");
            parseState = ParseState.NORMAL;
        } else if (ch == '8') {
            // 抑制交替缓冲区切回后的光标恢复（top退出时可能发送ESC 8）
            if (shouldSuppressScreenModify()) {
                debugLogCursorState("SUPPRESS ESC 8 (DECRC restore cursor)");
            } else {
                cursorX = savedCursorX;
                cursorY = savedCursorY;
                debugLogCursorState("ESC 8 (DECRC restore cursor)");
            }
            parseState = ParseState.NORMAL;
        } else if (ch == 'D') {
            // ESC D (IND): 索引下移，与\n同理，也需抑制
            if (shouldSuppressScreenModify()) {
                debugLogCursorState("SUPPRESS ESC D (IND)");
            } else {
                cursorY++;
                if (cursorY > getScrollBottom()) {
                    cursorY = getScrollBottom();
                    scrollUp(1);
                }
            }
            parseState = ParseState.NORMAL;
        } else if (ch == 'M') {
            if (cursorY == getScrollTop()) {
                scrollDown(1);
            } else {
                cursorY--;
            }
            parseState = ParseState.NORMAL;
        } else if (ch == 'c') {
            reset();
            parseState = ParseState.NORMAL;
        } else if (ch == '(') {
            parseState = ParseState.CHARSET;
        } else if (ch == ')') {
            parseState = ParseState.CHARSET;
        } else {
            parseState = ParseState.NORMAL;
        }
    }

    private void processCsiChar(char ch) {
        // CSI参数字节: 0x30-0x3F (0-9, ;, <, =, >, ?)
        // CSI中间字节: 0x20-0x2F (space, !, ", #, $, %, &, ', (, ), *, +, -, ., /)
        if ((ch >= 0x30 && ch <= 0x3F) || (ch >= 0x20 && ch <= 0x2F)) {
            escapeSeq.append(ch);
        } else {
            executeCsiCommand(ch);
            parseState = ParseState.NORMAL;
        }
    }

    private void processOscChar(char ch) {
        if (ch == 7) { // BEL结束OSC
            processOscSequence(oscSeq.toString());
            parseState = ParseState.NORMAL;
        } else if (ch == 0x1b || ch == '\033') {
            // ST = ESC \ 结束OSC
            if (oscSeq.length() > 0 && oscSeq.charAt(oscSeq.length() - 1) == '\\') {
                processOscSequence(oscSeq.substring(0, oscSeq.length() - 1));
            }
            parseState = ParseState.ESC;
        } else {
            oscSeq.append(ch);
        }
    }

    private void processOscSequence(String seq) {
        // OSC 7: 设置工作目录  \e]7;file://HOST/PATH\a
        if (seq.startsWith("7;")) {
            String url = seq.substring(2);
            if (url.startsWith("file://")) {
                // 解析 file://HOST/PATH
                String pathPart = url.substring(7);
                int slashIdx = pathPart.indexOf('/');
                if (slashIdx >= 0) {
                    String path = pathPart.substring(slashIdx);
                    if (cwdChangeListener != null) {
                        cwdChangeListener.onCwdChanged(path);
                    }
                } else {
                    // file://PATH (无HOST)
                    if (cwdChangeListener != null) {
                        cwdChangeListener.onCwdChanged("/" + pathPart);
                    }
                }
            } else if (!url.isEmpty()) {
                // 纯路径
                if (cwdChangeListener != null) {
                    cwdChangeListener.onCwdChanged(url);
                }
            }
        }
    }

    private void executeCsiCommand(char cmd) {
        String seq = escapeSeq.toString();
        if (cmd != 'm') {
            log("[Terminal-CSI] CSI " + seq + cmd + " | cursor=(" + cursorX + "," + cursorY + ") altBuf=" + usingAltBuffer + " autoAlt=" + autoAltBuffer + " suppress=" + suppressScreenModify + " guard=" + cursorGuardActive);
        }
        boolean privateMode = false;
        boolean greaterThan = false;
        boolean lessThan = false;
        if (seq.startsWith("?")) {
            privateMode = true;
            seq = seq.substring(1);
        } else if (seq.startsWith(">")) {
            greaterThan = true;
            seq = seq.substring(1);
        } else if (seq.startsWith("<")) {
            // CSI < 用于鼠标报告等私有序列，不应被当作标准命令处理
            lessThan = true;
            seq = seq.substring(1);
        }

        // 去掉中间字节（0x20-0x2F范围）
        String cleanSeq = "";
        for (int i = 0; i < seq.length(); i++) {
            char c = seq.charAt(i);
            if (c >= 0x30 && c <= 0x3F) {
                cleanSeq += c;
            }
        }

        int[] params = parseParams(cleanSeq);

        switch (cmd) {
            case 'H': // 光标位置
            case 'f':
                if (shouldSuppressScreenModify()) {
                    debugLogCursorState("SUPPRESS CSI H");
                    break;
                }
                int row = (params.length > 0 ? params[0] : 1) - 1;
                int col = (params.length > 1 ? params[1] : 1) - 1;
                if (originMode) {
                    // DECOM: 坐标相对于滚动区域
                    cursorY = getScrollTop() + row;
                    cursorX = col;
                } else {
                    cursorY = row;
                    cursorX = col;
                }
                clampCursor();
                debugLogCursorState("CSI " + (params.length > 0 ? params[0] : 1) + ";" + (params.length > 1 ? params[1] : 1) + "H");
                break;
            case 'A': // 光标上移
                if (shouldSuppressScreenModify()) { debugLogCursorState("SUPPRESS CSI " + (params.length > 0 ? params[0] : 1) + "A"); break; }
                cursorY -= (params.length > 0 ? params[0] : 1);
                if (cursorY < getScrollTop()) cursorY = getScrollTop();
                debugLogCursorState("CSI " + (params.length > 0 ? params[0] : 1) + "A");
                break;
            case 'B': // 光标下移
                if (shouldSuppressScreenModify()) { debugLogCursorState("SUPPRESS CSI " + (params.length > 0 ? params[0] : 1) + "B"); break; }
                cursorY += (params.length > 0 ? params[0] : 1);
                if (cursorY > getScrollBottom()) cursorY = getScrollBottom();
                debugLogCursorState("CSI " + (params.length > 0 ? params[0] : 1) + "B");
                break;
            case 'C': // 光标右移
                if (shouldSuppressScreenModify()) { debugLogCursorState("SUPPRESS CSI " + (params.length > 0 ? params[0] : 1) + "C"); break; }
                cursorX += (params.length > 0 ? params[0] : 1);
                if (cursorX >= cols) cursorX = cols - 1;
                debugLogCursorState("CSI " + (params.length > 0 ? params[0] : 1) + "C");
                break;
            case 'D': // 光标左移
                if (shouldSuppressScreenModify()) { debugLogCursorState("SUPPRESS CSI " + (params.length > 0 ? params[0] : 1) + "D"); break; }
                cursorX -= (params.length > 0 ? params[0] : 1);
                if (cursorX < 0) cursorX = 0;
                debugLogCursorState("CSI " + (params.length > 0 ? params[0] : 1) + "D");
                break;
            case 'G': // 光标水平绝对位置
                if (shouldSuppressScreenModify()) { debugLogCursorState("SUPPRESS CSI " + (params.length > 0 ? params[0] : 1) + "G"); break; }
                cursorX = (params.length > 0 ? params[0] : 1) - 1;
                clampCursor();
                debugLogCursorState("CSI " + (params.length > 0 ? params[0] : 1) + "G");
                break;
            case 'd': // 光标垂直绝对位置
                if (shouldSuppressScreenModify()) { debugLogCursorState("SUPPRESS CSI " + (params.length > 0 ? params[0] : 1) + "d"); break; }
                cursorY = (params.length > 0 ? params[0] : 1) - 1;
                clampCursor();
                debugLogCursorState("CSI " + (params.length > 0 ? params[0] : 1) + "d");
                break;
            case 'J': // 清屏
                int clearMode = params.length > 0 ? params[0] : 0;
                if (shouldSuppressScreenModify()) {
                    // 从交替缓冲区切回后抑制所有清屏命令（top退出时的清理序列）
                    debugLogCursorState("SUPPRESS CSI " + clearMode + "J");
                    break;
                }
                if (clearMode == 2 && !usingAltBuffer) {
                    // CSI 2J: 清除整个屏幕
                    // 如果当前处于应用光标键模式（全屏程序如top的标志），
                    // 且不在交替缓冲区，说明该程序没有发送标准的交替缓冲区切换序列，
                    // 自动切换到交替缓冲区以保护主缓冲区内容
                    if (applicationCursorKeys) {
                        debugLogCursorState("AUTO SWITCH TO ALT BUFFER (detected CSI 2J + appCursorKeys)");
                        // 保存CSI ?1h时记录的光标位置，防止被switchToAltBuffer()覆盖
                        // 因为在CSI ?1h和CSI 2J之间，top可能已经移动了光标（如CSI H）
                        int savedX = altBufferSavedCursorX;
                        int savedY = altBufferSavedCursorY;
                        switchToAltBuffer();
                        // 恢复CSI ?1h时保存的正确光标位置
                        altBufferSavedCursorX = savedX;
                        altBufferSavedCursorY = savedY;
                        autoAltBuffer = true;
                        debugLogCursorState("AFTER auto switchToAltBuffer (restored altBufSaved)");
                    }
                }
                clearScreen(clearMode);
                break;
            case 's': // ANSI保存光标位置
                ansiSavedCursorX = cursorX;
                ansiSavedCursorY = cursorY;
                debugLogCursorState("CSI s (save cursor)");
                break;
            case 'u': // ANSI恢复光标位置
                if (lessThan) {
                    // CSI <u 是私有序列，不是ANSI恢复光标，忽略
                    debugLogCursorState("CSI <u (private sequence, ignored)");
                    break;
                }
                if (shouldSuppressScreenModify()) {
                    debugLogCursorState("SUPPRESS CSI u");
                    break;
                }
                cursorX = ansiSavedCursorX;
                cursorY = ansiSavedCursorY;
                debugLogCursorState("CSI u (restore cursor)");
                break;
            case 'K': // 清行
                if (shouldSuppressScreenModify()) {
                    debugLogCursorState("SUPPRESS CSI K");
                    break;
                }
                clearLine(params.length > 0 ? params[0] : 0);
                break;
            case 'm': // 设置属性
                setAttribute(params);
                break;
            case 'h': // 设置模式
                if (privateMode) {
                    for (int p : params) {
                        switch (p) {
                            case 25: cursorVisible = true; break;
                            case 1:
                                applicationCursorKeys = true;
                                // 保存当前光标位置到专用字段，用于自动交替缓冲区恢复
                                // 注意：不能用savedCursorX/Y，因为top运行期间ESC 7/8会覆盖它
                                if (!usingAltBuffer && !autoAltBuffer) {
                                    altBufferSavedCursorX = cursorX;
                                    altBufferSavedCursorY = cursorY;
                                }
                                debugLogCursorState("CSI ?1h (appCursorKeys ON)");
                                break;
                            case 6: originMode = true; break;
                            case 1049: // 切换到交替屏幕缓冲区
                                debugLogCursorState("CSI ?1049h (switch to alt buffer)");
                                if (!usingAltBuffer) switchToAltBuffer();
                                break;
                            case 1047: // 交替屏幕缓冲区（xterm变体）
                                debugLogCursorState("CSI ?1047h (switch to alt buffer)");
                                if (!usingAltBuffer) switchToAltBuffer();
                                break;
                            case 47: // 交替屏幕缓冲区（旧版）
                                debugLogCursorState("CSI ?47h (switch to alt buffer)");
                                if (!usingAltBuffer) switchToAltBuffer();
                                break;
                            case 7: autoWrap = true; break;
                        }
                    }
                } else {
                    for (int p : params) {
                        switch (p) {
                            case 4: insertMode = true; break;
                            case 7: autoWrap = true; break;
                        }
                    }
                }
                break;
            case 'l': // 重置模式
                if (privateMode) {
                    for (int p : params) {
                        switch (p) {
                            case 25: cursorVisible = false; break;
                            case 1:
                                applicationCursorKeys = false;
                                debugLogCursorState("CSI ?1l (appCursorKeys OFF)");
                                // 自动交替缓冲区：当应用光标键模式重置时，说明全屏程序退出
                                if (autoAltBuffer && usingAltBuffer) {
                                    switchToMainBuffer();
                                    autoAltBuffer = false;
                                    debugLogCursorState("AFTER auto switchToMainBuffer (appCursorKeys OFF)");
                                }
                                break;
                            case 6: originMode = false; break;
                            case 1049: // 切回主屏幕缓冲区
                                debugLogCursorState("CSI ?1049l (switch to main buffer)");
                                if (usingAltBuffer) {
                                    switchToMainBuffer();
                                    // 抑制切回后的屏幕修改序列（全屏程序退出时的清理序列）
                                    activateScreenSuppress();
                                    debugLogCursorState("AFTER CSI ?1049l + suppress=true");
                                }
                                autoAltBuffer = false;
                                break;
                            case 1047: // 交替屏幕缓冲区（xterm变体）
                                debugLogCursorState("CSI ?1047l (switch to main buffer)");
                                if (usingAltBuffer) {
                                    switchToMainBuffer();
                                    activateScreenSuppress();
                                    debugLogCursorState("AFTER CSI ?1047l + suppress=true");
                                }
                                autoAltBuffer = false;
                                break;
                            case 47: // 交替屏幕缓冲区（旧版）
                                debugLogCursorState("CSI ?47l (switch to main buffer)");
                                if (usingAltBuffer) {
                                    switchToMainBuffer();
                                    activateScreenSuppress();
                                    debugLogCursorState("AFTER CSI ?47l + suppress=true");
                                }
                                autoAltBuffer = false;
                                break;
                            case 7: autoWrap = false; break;
                        }
                    }
                } else {
                    for (int p : params) {
                        switch (p) {
                            case 4: insertMode = false; break;
                            case 7: autoWrap = false; break;
                        }
                    }
                }
                break;
            case 'r': // 设置滚动区域（DECSTBM）
                if (shouldSuppressScreenModify()) {
                    debugLogCursorState("SUPPRESS CSI r");
                    break;
                }
                scrollTop = (params.length > 0 && params[0] > 0) ? params[0] : 0;
                scrollBottom = (params.length > 1 && params[1] > 0) ? params[1] : 0;
                // 光标移到滚动区域起始位置
                cursorX = 0;
                cursorY = getScrollTop();
                debugLogCursorState("CSI " + (params.length > 0 ? params[0] : 0) + ";" + (params.length > 1 ? params[1] : 0) + "r (set scroll region)");
                break;
            case 'S': // 向上滚动
                if (shouldSuppressScreenModify()) break;
                scrollUp(params.length > 0 ? params[0] : 1);
                break;
            case 'T': // 向下滚动
                if (shouldSuppressScreenModify()) break;
                scrollDown(params.length > 0 ? params[0] : 1);
                break;
            case 'L': // 插入行
                if (shouldSuppressScreenModify()) break;
                insertLines(params.length > 0 ? params[0] : 1);
                break;
            case 'M': // 删除行
                if (shouldSuppressScreenModify()) break;
                deleteLines(params.length > 0 ? params[0] : 1);
                break;
            case 'P': // 删除字符
                if (shouldSuppressScreenModify()) break;
                deleteChars(params.length > 0 ? params[0] : 1);
                break;
            case '@': // 插入字符
                if (shouldSuppressScreenModify()) break;
                insertChars(params.length > 0 ? params[0] : 1);
                break;
            case 'X': // 删除字符（用空格替换）
                if (shouldSuppressScreenModify()) break;
                eraseChars(params.length > 0 ? params[0] : 1);
                break;
            case 'c': // DA - Device Attributes
                if (greaterThan) {
                    // DA2: \033[>0;0;0c (VT220, firmware 0, ROM 0)
                    sendResponse("\033[>0;0;0c");
                } else if (!privateMode) {
                    // DA1: \033[?64;1;2;6;9c (xterm VT220)
                    sendResponse("\033[?64;1;2;6;9c");
                }
                break;
            case 'n': // DSR - Device Status Report
                if (params.length > 0 && params[0] == 6) {
                    // 报告光标位置: \033[row;colR
                    sendResponse("\033[" + (cursorY + 1) + ";" + (cursorX + 1) + "R");
                }
                break;
            default:
                // 忽略不支持的命令
                break;
        }
    }

    /**
     * 切换到交替屏幕缓冲区
     */
    private void switchToAltBuffer() {
        debugLogCursorState("BEFORE switchToAltBuffer");
        // 保存当前颜色状态（关键：防止切换后颜色泄漏到shell输出）
        savedFg = currentFg;
        savedBg = currentBg;
        savedBold = bold;
        savedUnderline = underline;
        savedReverse = reverse;
        savedFgTrueColor = currentFgTrueColor;
        savedBgTrueColor = currentBgTrueColor;
        savedFgRgb = currentFgRgb;
        savedBgRgb = currentBgRgb;

        // 保存当前缓冲区（主缓冲区内容）
        char[][] tmpBuf = buffer;
        int[][] tmpAttrs = attrs;
        int[] tmpFgTc = fgTrueColor;
        int[] tmpBgTc = bgTrueColor;
        // 切换到交替缓冲区
        buffer = altBuffer;
        attrs = altAttrs;
        fgTrueColor = altFgTrueColor;
        bgTrueColor = altBgTrueColor;
        altBuffer = tmpBuf;
        altAttrs = tmpAttrs;
        altFgTrueColor = tmpFgTc;
        altBgTrueColor = tmpBgTc;
        // 保存DECSC光标位置（ESC 7/8使用的savedCursorX/Y）
        altSavedCursorX = savedCursorX;
        altSavedCursorY = savedCursorY;
        // 保存光标到专用字段（用于切回时恢复，不被ESC 7/8覆盖）
        if (!applicationCursorKeys) {
            altBufferSavedCursorX = cursorX;
            altBufferSavedCursorY = cursorY;
        }
        // 保存主缓冲区的滚动偏移，重置为0
        mainBufferScrollOffset = scrollOffset;
        scrollOffset = 0;
        // 清除交替缓冲区
        clearBuffer(buffer, attrs);
        cursorX = 0;
        cursorY = 0;
        usingAltBuffer = true;
        scrollTop = 0;
        scrollBottom = 0;
        debugLogCursorState("AFTER switchToAltBuffer");
    }

    /**
     * 切回主屏幕缓冲区
     */
    private void switchToMainBuffer() {
        debugLogCursorState("BEFORE switchToMainBuffer");
        // 保存交替缓冲区内容
        char[][] tmpBuf = buffer;
        int[][] tmpAttrs = attrs;
        int[] tmpFgTc = fgTrueColor;
        int[] tmpBgTc = bgTrueColor;
        // 切回主缓冲区
        buffer = altBuffer;
        attrs = altAttrs;
        fgTrueColor = altFgTrueColor;
        bgTrueColor = altBgTrueColor;
        altBuffer = tmpBuf;
        altAttrs = tmpAttrs;
        altFgTrueColor = tmpFgTc;
        altBgTrueColor = tmpBgTc;

        // 恢复颜色状态（关键：确保shell输出使用正确的颜色）
        currentFg = savedFg;
        currentBg = savedBg;
        bold = savedBold;
        underline = savedUnderline;
        reverse = savedReverse;
        currentFgTrueColor = savedFgTrueColor;
        currentBgTrueColor = savedBgTrueColor;
        currentFgRgb = savedFgRgb;
        currentBgRgb = savedBgRgb;

        // 恢复DECSC保存的光标位置
        savedCursorX = altSavedCursorX;
        savedCursorY = altSavedCursorY;

        if (autoAltBuffer) {
            cursorX = altBufferSavedCursorX;
            cursorY = altBufferSavedCursorY;
            activateScreenSuppress();
            activateCursorGuard();
            debugLogCursorState("AFTER switchToMainBuffer (autoAlt, suppress=true)");
        } else {
            cursorX = altBufferSavedCursorX;
            cursorY = altBufferSavedCursorY;
            activateCursorGuard();
            debugLogCursorState("AFTER switchToMainBuffer (standard)");
        }

        // 恢复主缓冲区的滚动偏移
        scrollOffset = mainBufferScrollOffset;
        mainBufferScrollOffset = 0;
        usingAltBuffer = false;
        autoAltBuffer = false;
        scrollTop = 0;
        scrollBottom = 0;
    }

    /**
     * 查找当前缓冲区中最后一个有非空格内容的行
     * @return 最后一个有内容的行号，如果全部为空则返回-1
     */
    private int findLastContentRow() {
        for (int y = rows - 1; y >= 0; y--) {
            for (int x = 0; x < cols; x++) {
                if (buffer[y][x] != ' ' && buffer[y][x] != '\0') {
                    return y;
                }
            }
        }
        return -1;
    }

    private int[] parseParams(String seq) {
        if (seq.isEmpty()) return new int[0];
        String[] parts = seq.split(";");
        int[] params = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                params[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                params[i] = 0;
            }
        }
        return params;
    }

    private void clampCursor() {
        if (cursorX < 0) cursorX = 0;
        if (cursorX >= cols) cursorX = cols - 1;
        if (cursorY < 0) cursorY = 0;
        if (cursorY >= rows) cursorY = rows - 1;
    }

    private void clearScreen(int mode) {
        switch (mode) {
            case 0: // 从光标到屏幕末尾
                for (int x = cursorX; x < cols; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(currentFg, currentBg, false, false, false);
                    int idx = cursorY * cols + x;
                    fgTrueColor[idx] = -1;
                    bgTrueColor[idx] = -1;
                }
                for (int y = cursorY + 1; y < rows; y++) {
                    for (int x = 0; x < cols; x++) {
                        buffer[y][x] = ' ';
                        attrs[y][x] = makeAttr(currentFg, currentBg, false, false, false);
                        int idx = y * cols + x;
                        fgTrueColor[idx] = -1;
                        bgTrueColor[idx] = -1;
                    }
                }
                break;
            case 1: // 从屏幕开头到光标
                for (int y = 0; y < cursorY; y++) {
                    for (int x = 0; x < cols; x++) {
                        buffer[y][x] = ' ';
                        attrs[y][x] = makeAttr(currentFg, currentBg, false, false, false);
                        int idx = y * cols + x;
                        fgTrueColor[idx] = -1;
                        bgTrueColor[idx] = -1;
                    }
                }
                for (int x = 0; x <= cursorX; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(currentFg, currentBg, false, false, false);
                    int idx = cursorY * cols + x;
                    fgTrueColor[idx] = -1;
                    bgTrueColor[idx] = -1;
                }
                break;
            case 2: // 整个屏幕
                clearBuffer(buffer, attrs);
                break;
            case 3: // 清除回滚缓冲（我们没有回滚缓冲，忽略）
                break;
        }
    }

    private void clearLine(int mode) {
        switch (mode) {
            case 0: // 从光标到行尾
                for (int x = cursorX; x < cols; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(currentFg, currentBg, false, false, false);
                    int idx = cursorY * cols + x;
                    fgTrueColor[idx] = -1;
                    bgTrueColor[idx] = -1;
                }
                break;
            case 1: // 从行首到光标
                for (int x = 0; x <= cursorX; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(currentFg, currentBg, false, false, false);
                    int idx = cursorY * cols + x;
                    fgTrueColor[idx] = -1;
                    bgTrueColor[idx] = -1;
                }
                break;
            case 2: // 整行
                for (int x = 0; x < cols; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(currentFg, currentBg, false, false, false);
                    int idx = cursorY * cols + x;
                    fgTrueColor[idx] = -1;
                    bgTrueColor[idx] = -1;
                }
                break;
        }
    }

    private void setAttribute(int[] params) {
        if (params.length == 0) {
            params = new int[]{0};
        }
        int i = 0;
        while (i < params.length) {
            int p = params[i];
            switch (p) {
                case 0: // 重置
                    currentFg = 7;
                    currentBg = 0;
                    bold = false;
                    underline = false;
                    reverse = false;
                    currentFgTrueColor = false;
                    currentBgTrueColor = false;
                    currentFgRgb = 0;
                    currentBgRgb = 0;
                    break;
                case 1: bold = true; break;
                case 4: underline = true; break;
                case 7: reverse = true; break;
                case 22: bold = false; break;
                case 24: underline = false; break;
                case 27: reverse = false; break;
                case 38: {
                    // 扩展前景色: 38;5;N (256色) 或 38;2;R;G;B (真彩色)
                    if (i + 1 < params.length) {
                        int mode = params[i + 1];
                        if (mode == 5 && i + 2 < params.length) {
                            // 256色模式: 38;5;N
                            int idx = params[i + 2];
                            currentFg = Math.max(0, Math.min(255, idx));
                            currentFgTrueColor = false;
                            i += 2;
                        } else if (mode == 2 && i + 4 < params.length) {
                            // 真彩色模式: 38;2;R;G;B
                            int r = Math.max(0, Math.min(255, params[i + 2]));
                            int g = Math.max(0, Math.min(255, params[i + 3]));
                            int b = Math.max(0, Math.min(255, params[i + 4]));
                            currentFgRgb = (r << 16) | (g << 8) | b;
                            currentFgTrueColor = true;
                            i += 4;
                        } else if (mode == 3 && i + 2 < params.length) {
                            // Linux控制台256色模式: 38;3;N
                            int idx = params[i + 2];
                            currentFg = Math.max(0, Math.min(255, idx));
                            currentFgTrueColor = false;
                            i += 2;
                        } else {
                            // 未知模式，跳过mode参数
                            i += 1;
                        }
                    }
                    break;
                }
                case 48: {
                    // 扩展背景色: 48;5;N (256色) 或 48;2;R;G;B (真彩色)
                    if (i + 1 < params.length) {
                        int mode = params[i + 1];
                        if (mode == 5 && i + 2 < params.length) {
                            int idx = params[i + 2];
                            currentBg = Math.max(0, Math.min(255, idx));
                            currentBgTrueColor = false;
                            i += 2;
                        } else if (mode == 2 && i + 4 < params.length) {
                            int r = Math.max(0, Math.min(255, params[i + 2]));
                            int g = Math.max(0, Math.min(255, params[i + 3]));
                            int b = Math.max(0, Math.min(255, params[i + 4]));
                            currentBgRgb = (r << 16) | (g << 8) | b;
                            currentBgTrueColor = true;
                            i += 4;
                        } else if (mode == 3 && i + 2 < params.length) {
                            int idx = params[i + 2];
                            currentBg = Math.max(0, Math.min(255, idx));
                            currentBgTrueColor = false;
                            i += 2;
                        } else {
                            i += 1;
                        }
                    }
                    break;
                }
                case 39:
                    currentFg = 7;
                    currentFgTrueColor = false;
                    break;
                case 49:
                    currentBg = 0;
                    currentBgTrueColor = false;
                    break;
                default:
                    if (p >= 30 && p <= 37) {
                        currentFg = p - 30;
                        currentFgTrueColor = false;
                    } else if (p >= 40 && p <= 47) {
                        currentBg = p - 40;
                        currentBgTrueColor = false;
                    } else if (p >= 90 && p <= 97) {
                        currentFg = p - 90 + 8;
                        currentFgTrueColor = false;
                    } else if (p >= 100 && p <= 107) {
                        currentBg = p - 100 + 8;
                        currentBgTrueColor = false;
                    }
                    break;
            }
            i++;
        }
    }

    /**
     * 在滚动区域内向上滚动n行
     */
    private void scrollUp(int n) {
        int top = getScrollTop();
        int bottom = getScrollBottom();
        for (int i = 0; i < n; i++) {
            if (top == 0 && !usingAltBuffer) {
                char[] savedLine = new char[cols];
                int[] savedAttr = new int[cols];
                int[] savedFgTc = new int[cols];
                int[] savedBgTc = new int[cols];
                System.arraycopy(buffer[0], 0, savedLine, 0, cols);
                System.arraycopy(attrs[0], 0, savedAttr, 0, cols);
                for (int x = 0; x < cols; x++) {
                    savedFgTc[x] = fgTrueColor[x];
                    savedBgTc[x] = bgTrueColor[x];
                }
                scrollbackLines.addLast(savedLine);
                scrollbackAttrs.addLast(savedAttr);
                scrollbackFgTc.addLast(savedFgTc);
                scrollbackBgTc.addLast(savedBgTc);
                while (scrollbackLines.size() > maxScrollback) {
                    scrollbackLines.removeFirst();
                    scrollbackAttrs.removeFirst();
                    scrollbackFgTc.removeFirst();
                    scrollbackBgTc.removeFirst();
                }
            }
            for (int y = top; y < bottom; y++) {
                System.arraycopy(buffer[y + 1], 0, buffer[y], 0, cols);
                System.arraycopy(attrs[y + 1], 0, attrs[y], 0, cols);
                int srcIdx = (y + 1) * cols;
                int dstIdx = y * cols;
                System.arraycopy(fgTrueColor, srcIdx, fgTrueColor, dstIdx, cols);
                System.arraycopy(bgTrueColor, srcIdx, bgTrueColor, dstIdx, cols);
            }
            for (int x = 0; x < cols; x++) {
                buffer[bottom][x] = ' ';
                attrs[bottom][x] = makeAttr(7, 0, false, false, false);
                int idx = bottom * cols + x;
                fgTrueColor[idx] = -1;
                bgTrueColor[idx] = -1;
            }
        }
        if (scrollOffset > 0) {
            scrollOffset = Math.min(scrollOffset + n, scrollbackLines.size());
        }
    }

    /**
     * 在滚动区域内向下滚动n行
     */
    private void scrollDown(int n) {
        int top = getScrollTop();
        int bottom = getScrollBottom();
        for (int i = 0; i < n; i++) {
            for (int y = bottom; y > top; y--) {
                System.arraycopy(buffer[y - 1], 0, buffer[y], 0, cols);
                System.arraycopy(attrs[y - 1], 0, attrs[y], 0, cols);
                int srcIdx = (y - 1) * cols;
                int dstIdx = y * cols;
                System.arraycopy(fgTrueColor, srcIdx, fgTrueColor, dstIdx, cols);
                System.arraycopy(bgTrueColor, srcIdx, bgTrueColor, dstIdx, cols);
            }
            for (int x = 0; x < cols; x++) {
                buffer[top][x] = ' ';
                attrs[top][x] = makeAttr(7, 0, false, false, false);
                int idx = top * cols + x;
                fgTrueColor[idx] = -1;
                bgTrueColor[idx] = -1;
            }
        }
    }

    private void insertLines(int n) {
        int bottom = getScrollBottom();
        for (int i = 0; i < n; i++) {
            if (cursorY < bottom) {
                for (int y = bottom; y > cursorY; y--) {
                    System.arraycopy(buffer[y - 1], 0, buffer[y], 0, cols);
                    System.arraycopy(attrs[y - 1], 0, attrs[y], 0, cols);
                    int srcIdx = (y - 1) * cols;
                    int dstIdx = y * cols;
                    System.arraycopy(fgTrueColor, srcIdx, fgTrueColor, dstIdx, cols);
                    System.arraycopy(bgTrueColor, srcIdx, bgTrueColor, dstIdx, cols);
                }
                for (int x = 0; x < cols; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(7, 0, false, false, false);
                    int idx = cursorY * cols + x;
                    fgTrueColor[idx] = -1;
                    bgTrueColor[idx] = -1;
                }
            }
        }
    }

    private void deleteLines(int n) {
        int bottom = getScrollBottom();
        for (int i = 0; i < n; i++) {
            for (int y = cursorY; y < bottom; y++) {
                System.arraycopy(buffer[y + 1], 0, buffer[y], 0, cols);
                System.arraycopy(attrs[y + 1], 0, attrs[y], 0, cols);
                int srcIdx = (y + 1) * cols;
                int dstIdx = y * cols;
                System.arraycopy(fgTrueColor, srcIdx, fgTrueColor, dstIdx, cols);
                System.arraycopy(bgTrueColor, srcIdx, bgTrueColor, dstIdx, cols);
            }
            for (int x = 0; x < cols; x++) {
                buffer[bottom][x] = ' ';
                attrs[bottom][x] = makeAttr(7, 0, false, false, false);
                int idx = bottom * cols + x;
                fgTrueColor[idx] = -1;
                bgTrueColor[idx] = -1;
            }
        }
    }

    private void deleteChars(int n) {
        for (int x = cursorX; x < cols; x++) {
            int idx = cursorY * cols + x;
            if (x + n < cols) {
                buffer[cursorY][x] = buffer[cursorY][x + n];
                attrs[cursorY][x] = attrs[cursorY][x + n];
                int srcIdx = cursorY * cols + x + n;
                fgTrueColor[idx] = fgTrueColor[srcIdx];
                bgTrueColor[idx] = bgTrueColor[srcIdx];
            } else {
                buffer[cursorY][x] = ' ';
                attrs[cursorY][x] = makeAttr(7, 0, false, false, false);
                fgTrueColor[idx] = -1;
                bgTrueColor[idx] = -1;
            }
        }
    }

    private void insertChars(int n) {
        for (int x = cols - 1; x >= cursorX; x--) {
            int idx = cursorY * cols + x;
            if (x - n >= cursorX) {
                buffer[cursorY][x] = buffer[cursorY][x - n];
                attrs[cursorY][x] = attrs[cursorY][x - n];
                int srcIdx = cursorY * cols + x - n;
                fgTrueColor[idx] = fgTrueColor[srcIdx];
                bgTrueColor[idx] = bgTrueColor[srcIdx];
            } else {
                buffer[cursorY][x] = ' ';
                attrs[cursorY][x] = makeAttr(7, 0, false, false, false);
                fgTrueColor[idx] = -1;
                bgTrueColor[idx] = -1;
            }
        }
    }

    private void eraseChars(int n) {
        for (int i = 0; i < n && cursorX + i < cols; i++) {
            buffer[cursorY][cursorX + i] = ' ';
            attrs[cursorY][cursorX + i] = makeAttr(7, 0, false, false, false);
            int idx = cursorY * cols + cursorX + i;
            fgTrueColor[idx] = -1;
            bgTrueColor[idx] = -1;
        }
    }

    private void reset() {
        // 如果在交替缓冲区中，先切回主缓冲区
        if (usingAltBuffer) {
            switchToMainBuffer();
        }
        clearBuffer(buffer, attrs);
        cursorX = 0;
        cursorY = 0;
        currentFg = 7;
        currentBg = 0;
        bold = false;
        underline = false;
        reverse = false;
        currentFgTrueColor = false;
        currentBgTrueColor = false;
        currentFgRgb = 0;
        currentBgRgb = 0;
        cursorVisible = true;
        autoWrap = true;
        insertMode = false;
        applicationCursorKeys = false;
        originMode = false;
        scrollTop = 0;
        scrollBottom = 0;
        suppressScreenModify = false;
        suppressUntilTime = 0;
        suppressMinUntilTime = 0;
        cursorGuardActive = false;
        cursorGuardUntilTime = 0;
    }
}
