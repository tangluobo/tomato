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
    // 直到收到新的可打印字符为止（说明shell已开始输出）
    private boolean suppressScreenModify = false;

    // 回滚历史缓冲区
    private java.util.LinkedList<char[]> scrollbackLines = new java.util.LinkedList<>();
    private java.util.LinkedList<int[]> scrollbackAttrs = new java.util.LinkedList<>();
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
    // 当前属性
    private int currentFg = 7; // 默认白色
    private int currentBg = 0; // 默认黑色
    private boolean bold = false;
    private boolean underline = false;
    private boolean reverse = false;

    private enum ParseState {
        NORMAL, ESC, CSI, OSC, CHARSET
    }

    // ANSI颜色表
    public static final int[] COLOR_TABLE = {
            0x000000, // 0: 黑
            0xcd0000, // 1: 红
            0x00cd00, // 2: 绿
            0xcdcd00, // 3: 黄
            0x0000ee, // 4: 蓝
            0xcd00cd, // 5: 洋红
            0x00cdcd, // 6: 青
            0xe5e5e5, // 7: 白
            0x7f7f7f, // 8: 亮黑
            0xff0000, // 9: 亮红
            0x00ff00, // 10: 亮绿
            0xffff00, // 11: 亮黄
            0x5c5cff, // 12: 亮蓝
            0xff00ff, // 13: 亮洋红
            0x00ffff, // 14: 亮青
            0xffffff  // 15: 亮白
    };

    public TerminalEmulator() {
        this(DEFAULT_COLS, DEFAULT_ROWS);
    }

    public TerminalEmulator(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        this.buffer = new char[rows][cols];
        this.attrs = new int[rows][cols];
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
            }
        }
    }

    private int makeAttr(int fg, int bg, boolean bold, boolean underline, boolean reverse) {
        return (fg & 0xFF) | ((bg & 0xFF) << 8) | (bold ? 0x10000 : 0) | (underline ? 0x20000 : 0) | (reverse ? 0x40000 : 0);
    }

    public int getFg(int attr) { return attr & 0xFF; }
    public int getBg(int attr) { return (attr >> 8) & 0xFF; }
    public boolean isBold(int attr) { return (attr & 0x10000) != 0; }
    public boolean isUnderline(int attr) { return (attr & 0x20000) != 0; }
    public boolean isReverse(int attr) { return (attr & 0x40000) != 0; }

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

    public void setCwdChangeListener(CwdChangeListener listener) {
        this.cwdChangeListener = listener;
    }

    private void sendResponse(String data) {
        if (responseHandler != null) {
            responseHandler.sendResponse(data.getBytes());
        }
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
        for (int y = 0; y < newRows; y++) {
            for (int x = 0; x < newCols; x++) {
                if (y < rows && x < cols) {
                    newBuffer[y][x] = buffer[y][x];
                    newAttrs[y][x] = attrs[y][x];
                } else {
                    newBuffer[y][x] = ' ';
                    newAttrs[y][x] = makeAttr(7, 0, false, false, false);
                }
            }
        }
        this.buffer = newBuffer;
        this.attrs = newAttrs;
        this.cols = newCols;
        this.rows = newRows;
        if (cursorX >= newCols) cursorX = newCols - 1;
        if (cursorY >= newRows) cursorY = newRows - 1;
        // 重置滚动区域
        scrollTop = 0;
        scrollBottom = 0;
        // 重建交替缓冲区
        if (usingAltBuffer) {
            // 在alt buffer模式下，altBuffer保存着主缓冲区的内容，需要一并调整大小
            char[][] newAltBuffer = new char[newRows][newCols];
            int[][] newAltAttrs = new int[newRows][newCols];
            for (int y = 0; y < newRows; y++) {
                for (int x = 0; x < newCols; x++) {
                    if (y < rows && x < cols) {
                        newAltBuffer[y][x] = altBuffer[y][x];
                        newAltAttrs[y][x] = altAttrs[y][x];
                    } else {
                        newAltBuffer[y][x] = ' ';
                        newAltAttrs[y][x] = makeAttr(7, 0, false, false, false);
                    }
                }
            }
            altBuffer = newAltBuffer;
            altAttrs = newAltAttrs;
        } else {
            initAltBuffer();
        }
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
            cursorX = 0;
        } else if (ch == '\n') {
            // 抑制交替缓冲区切回后的换行（top退出时发送的清理序列）
            if (suppressScreenModify) {
                System.err.println("[Terminal] SUPPRESS \\n after alt buffer switch back");
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
            // 收到可打印字符时重置抑制标志（说明shell已开始输出新内容）
            if (suppressScreenModify) {
                suppressScreenModify = false;
                System.err.println("[Terminal] Printable char received, suppressScreenModify=false");
            }
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
                }
            }
            buffer[cursorY][cursorX] = ch;
            attrs[cursorY][cursorX] = makeAttr(currentFg, currentBg, bold, underline, reverse);
            if (wideChar && cursorX + 1 < cols) {
                buffer[cursorY][cursorX + 1] = 0;
                attrs[cursorY][cursorX + 1] = attrs[cursorY][cursorX];
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
            parseState = ParseState.NORMAL;
        } else if (ch == '8') {
            // 抑制交替缓冲区切回后的光标恢复（top退出时可能发送ESC 8）
            if (suppressScreenModify) {
                System.err.println("[Terminal] SUPPRESS ESC 8 after alt buffer switch back");
            } else {
                cursorX = savedCursorX;
                cursorY = savedCursorY;
            }
            parseState = ParseState.NORMAL;
        } else if (ch == 'D') {
            // 抑制交替缓冲区切回后的索引下移（top退出时可能发送ESC D）
            if (suppressScreenModify) {
                System.err.println("[Terminal] SUPPRESS ESC D after alt buffer switch back");
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
        boolean privateMode = false;
        boolean greaterThan = false;
        if (seq.startsWith("?")) {
            privateMode = true;
            seq = seq.substring(1);
        } else if (seq.startsWith(">")) {
            greaterThan = true;
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
                if (suppressScreenModify) {
                    System.err.println("[Terminal] SUPPRESS CSI H after alt buffer switch back");
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
                break;
            case 'A': // 光标上移
                if (suppressScreenModify) break;
                cursorY -= (params.length > 0 ? params[0] : 1);
                if (cursorY < getScrollTop()) cursorY = getScrollTop();
                break;
            case 'B': // 光标下移
                if (suppressScreenModify) break;
                cursorY += (params.length > 0 ? params[0] : 1);
                if (cursorY > getScrollBottom()) cursorY = getScrollBottom();
                break;
            case 'C': // 光标右移
                if (suppressScreenModify) break;
                cursorX += (params.length > 0 ? params[0] : 1);
                if (cursorX >= cols) cursorX = cols - 1;
                break;
            case 'D': // 光标左移
                if (suppressScreenModify) break;
                cursorX -= (params.length > 0 ? params[0] : 1);
                if (cursorX < 0) cursorX = 0;
                break;
            case 'G': // 光标水平绝对位置
                if (suppressScreenModify) break;
                cursorX = (params.length > 0 ? params[0] : 1) - 1;
                clampCursor();
                break;
            case 'd': // 光标垂直绝对位置
                if (suppressScreenModify) break;
                cursorY = (params.length > 0 ? params[0] : 1) - 1;
                clampCursor();
                break;
            case 'J': // 清屏
                int clearMode = params.length > 0 ? params[0] : 0;
                System.err.println("[Terminal] CSI " + clearMode + "J (clear screen) altBuf=" + usingAltBuffer + " autoAlt=" + autoAltBuffer + " appCursor=" + applicationCursorKeys + " suppress=" + suppressScreenModify);
                if (suppressScreenModify) {
                    // 从交替缓冲区切回后抑制所有清屏命令（top退出时的清理序列）
                    System.err.println("[Terminal] SUPPRESS CSI " + clearMode + "J after alt buffer switch back");
                    break;
                }
                if (clearMode == 2 && !usingAltBuffer) {
                    // CSI 2J: 清除整个屏幕
                    // 如果当前处于应用光标键模式（全屏程序如top的标志），
                    // 且不在交替缓冲区，说明该程序没有发送标准的交替缓冲区切换序列，
                    // 自动切换到交替缓冲区以保护主缓冲区内容
                    if (applicationCursorKeys) {
                        System.err.println("[Terminal] AUTO SWITCH TO ALT BUFFER (detected CSI 2J + appCursorKeys)");
                        // 保存CSI ?1h时记录的光标位置，防止被switchToAltBuffer()覆盖
                        // 因为在CSI ?1h和CSI 2J之间，top可能已经移动了光标（如CSI H）
                        int savedX = altBufferSavedCursorX;
                        int savedY = altBufferSavedCursorY;
                        switchToAltBuffer();
                        // 恢复CSI ?1h时保存的正确光标位置
                        altBufferSavedCursorX = savedX;
                        altBufferSavedCursorY = savedY;
                        autoAltBuffer = true;
                    }
                }
                clearScreen(clearMode);
                break;
            case 's': // ANSI保存光标位置
                ansiSavedCursorX = cursorX;
                ansiSavedCursorY = cursorY;
                break;
            case 'u': // ANSI恢复光标位置
                if (suppressScreenModify) {
                    System.err.println("[Terminal] SUPPRESS CSI u after alt buffer switch back");
                    break;
                }
                cursorX = ansiSavedCursorX;
                cursorY = ansiSavedCursorY;
                break;
            case 'K': // 清行
                if (suppressScreenModify) {
                    System.err.println("[Terminal] SUPPRESS CSI K after alt buffer switch back");
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
                                System.err.println("[Terminal] CSI ?1h (appCursorKeys ON) altBuf=" + usingAltBuffer + " autoAlt=" + autoAltBuffer + " savedCursor=(" + altBufferSavedCursorX + "," + altBufferSavedCursorY + ")");
                                break;
                            case 6: originMode = true; break;
                            case 1049: // 切换到交替屏幕缓冲区
                                System.err.println("[Terminal] CSI ?1049h (switch to alt buffer) alreadyInAlt=" + usingAltBuffer);
                                if (!usingAltBuffer) switchToAltBuffer();
                                break;
                            case 1047: // 交替屏幕缓冲区（xterm变体）
                                System.err.println("[Terminal] CSI ?1047h (switch to alt buffer) alreadyInAlt=" + usingAltBuffer);
                                if (!usingAltBuffer) switchToAltBuffer();
                                break;
                            case 47: // 交替屏幕缓冲区（旧版）
                                System.err.println("[Terminal] CSI ?47h (switch to alt buffer) alreadyInAlt=" + usingAltBuffer);
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
                                System.err.println("[Terminal] CSI ?1l (appCursorKeys OFF) altBuf=" + usingAltBuffer + " autoAlt=" + autoAltBuffer);
                                // 自动交替缓冲区：当应用光标键模式重置时，说明全屏程序退出
                                if (autoAltBuffer && usingAltBuffer) {
                                    System.err.println("[Terminal] AUTO SWITCH TO MAIN BUFFER (appCursorKeys OFF)");
                                    switchToMainBuffer();
                                    autoAltBuffer = false;
                                }
                                break;
                            case 6: originMode = false; break;
                            case 1049: // 切回主屏幕缓冲区
                                System.err.println("[Terminal] CSI ?1049l (switch to main buffer) inAlt=" + usingAltBuffer + " autoAlt=" + autoAltBuffer);
                                if (usingAltBuffer) {
                                    switchToMainBuffer();
                                    // 抑制切回后的屏幕修改序列（全屏程序退出时的清理序列）
                                    suppressScreenModify = true;
                                }
                                autoAltBuffer = false;
                                break;
                            case 1047: // 交替屏幕缓冲区（xterm变体）
                                System.err.println("[Terminal] CSI ?1047l (switch to main buffer) inAlt=" + usingAltBuffer + " autoAlt=" + autoAltBuffer);
                                if (usingAltBuffer) {
                                    switchToMainBuffer();
                                    suppressScreenModify = true;
                                }
                                autoAltBuffer = false;
                                break;
                            case 47: // 交替屏幕缓冲区（旧版）
                                System.err.println("[Terminal] CSI ?47l (switch to main buffer) inAlt=" + usingAltBuffer + " autoAlt=" + autoAltBuffer);
                                if (usingAltBuffer) {
                                    switchToMainBuffer();
                                    suppressScreenModify = true;
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
                if (suppressScreenModify) {
                    System.err.println("[Terminal] SUPPRESS CSI r after alt buffer switch back");
                    break;
                }
                scrollTop = (params.length > 0 && params[0] > 0) ? params[0] : 0;
                scrollBottom = (params.length > 1 && params[1] > 0) ? params[1] : 0;
                // 光标移到滚动区域起始位置
                cursorX = 0;
                cursorY = getScrollTop();
                break;
            case 'S': // 向上滚动
                if (suppressScreenModify) break;
                scrollUp(params.length > 0 ? params[0] : 1);
                break;
            case 'T': // 向下滚动
                if (suppressScreenModify) break;
                scrollDown(params.length > 0 ? params[0] : 1);
                break;
            case 'L': // 插入行
                if (suppressScreenModify) break;
                insertLines(params.length > 0 ? params[0] : 1);
                break;
            case 'M': // 删除行
                if (suppressScreenModify) break;
                deleteLines(params.length > 0 ? params[0] : 1);
                break;
            case 'P': // 删除字符
                if (suppressScreenModify) break;
                deleteChars(params.length > 0 ? params[0] : 1);
                break;
            case '@': // 插入字符
                if (suppressScreenModify) break;
                insertChars(params.length > 0 ? params[0] : 1);
                break;
            case 'X': // 删除字符（用空格替换）
                if (suppressScreenModify) break;
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
        System.err.println("[Terminal] SWITCH TO ALT BUFFER (autoAlt=" + autoAltBuffer + ")");
        // 保存当前缓冲区（主缓冲区内容）
        char[][] tmpBuf = buffer;
        int[][] tmpAttrs = attrs;
        // 切换到交替缓冲区
        buffer = altBuffer;
        attrs = altAttrs;
        altBuffer = tmpBuf;
        altAttrs = tmpAttrs;
        // 保存DECSC光标位置（ESC 7/8使用的savedCursorX/Y）
        altSavedCursorX = savedCursorX;
        altSavedCursorY = savedCursorY;
        // 保存光标到专用字段（用于切回时恢复，不被ESC 7/8覆盖）
        altBufferSavedCursorX = cursorX;
        altBufferSavedCursorY = cursorY;
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
    }

    /**
     * 切回主屏幕缓冲区
     */
    private void switchToMainBuffer() {
        System.err.println("[Terminal] SWITCH TO MAIN BUFFER (autoAlt=" + autoAltBuffer + ")");
        // 保存交替缓冲区内容
        char[][] tmpBuf = buffer;
        int[][] tmpAttrs = attrs;
        // 切回主缓冲区
        buffer = altBuffer;
        attrs = altAttrs;
        altBuffer = tmpBuf;
        altAttrs = tmpAttrs;
        // 恢复DECSC保存的光标位置
        savedCursorX = altSavedCursorX;
        savedCursorY = altSavedCursorY;

        if (autoAltBuffer) {
            // 自动交替缓冲区模式：恢复到top启动前的光标位置
            // 使用CSI ?1h时保存的altBufferSavedCursorX/Y（已防止被switchToAltBuffer覆盖）
            cursorX = altBufferSavedCursorX;
            cursorY = altBufferSavedCursorY;
            // 设置抑制屏幕修改标志：top退出时可能发送清理序列，需要抑制
            suppressScreenModify = true;
            System.err.println("[Terminal] AUTO ALT: cursor restored to (" + cursorX + "," + cursorY + ") suppressScreenModify=true");
        } else {
            // 标准交替缓冲区模式：使用保存的光标位置
            cursorX = altBufferSavedCursorX;
            cursorY = altBufferSavedCursorY;
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
                }
                for (int y = cursorY + 1; y < rows; y++) {
                    for (int x = 0; x < cols; x++) {
                        buffer[y][x] = ' ';
                        attrs[y][x] = makeAttr(currentFg, currentBg, false, false, false);
                    }
                }
                break;
            case 1: // 从屏幕开头到光标
                for (int y = 0; y < cursorY; y++) {
                    for (int x = 0; x < cols; x++) {
                        buffer[y][x] = ' ';
                        attrs[y][x] = makeAttr(currentFg, currentBg, false, false, false);
                    }
                }
                for (int x = 0; x <= cursorX; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(currentFg, currentBg, false, false, false);
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
                }
                break;
            case 1: // 从行首到光标
                for (int x = 0; x <= cursorX; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(currentFg, currentBg, false, false, false);
                }
                break;
            case 2: // 整行
                for (int x = 0; x < cols; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(currentFg, currentBg, false, false, false);
                }
                break;
        }
    }

    private void setAttribute(int[] params) {
        if (params.length == 0) {
            params = new int[]{0};
        }
        for (int p : params) {
            switch (p) {
                case 0: // 重置
                    currentFg = 7;
                    currentBg = 0;
                    bold = false;
                    underline = false;
                    reverse = false;
                    break;
                case 1: bold = true; break;
                case 4: underline = true; break;
                case 7: reverse = true; break;
                case 22: bold = false; break;
                case 24: underline = false; break;
                case 27: reverse = false; break;
                default:
                    if (p >= 30 && p <= 37) {
                        currentFg = p - 30;
                    } else if (p >= 40 && p <= 47) {
                        currentBg = p - 40;
                    } else if (p >= 90 && p <= 97) {
                        currentFg = p - 90 + 8;
                    } else if (p >= 100 && p <= 107) {
                        currentBg = p - 100 + 8;
                    } else if (p == 39) {
                        currentFg = 7;
                    } else if (p == 49) {
                        currentBg = 0;
                    }
                    break;
            }
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
                System.arraycopy(buffer[0], 0, savedLine, 0, cols);
                System.arraycopy(attrs[0], 0, savedAttr, 0, cols);
                scrollbackLines.addLast(savedLine);
                scrollbackAttrs.addLast(savedAttr);
                while (scrollbackLines.size() > maxScrollback) {
                    scrollbackLines.removeFirst();
                    scrollbackAttrs.removeFirst();
                }
            }
            for (int y = top; y < bottom; y++) {
                System.arraycopy(buffer[y + 1], 0, buffer[y], 0, cols);
                System.arraycopy(attrs[y + 1], 0, attrs[y], 0, cols);
            }
            for (int x = 0; x < cols; x++) {
                buffer[bottom][x] = ' ';
                attrs[bottom][x] = makeAttr(7, 0, false, false, false);
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
            }
            for (int x = 0; x < cols; x++) {
                buffer[top][x] = ' ';
                attrs[top][x] = makeAttr(7, 0, false, false, false);
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
                }
                for (int x = 0; x < cols; x++) {
                    buffer[cursorY][x] = ' ';
                    attrs[cursorY][x] = makeAttr(7, 0, false, false, false);
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
            }
            for (int x = 0; x < cols; x++) {
                buffer[bottom][x] = ' ';
                attrs[bottom][x] = makeAttr(7, 0, false, false, false);
            }
        }
    }

    private void deleteChars(int n) {
        for (int x = cursorX; x < cols; x++) {
            if (x + n < cols) {
                buffer[cursorY][x] = buffer[cursorY][x + n];
                attrs[cursorY][x] = attrs[cursorY][x + n];
            } else {
                buffer[cursorY][x] = ' ';
                attrs[cursorY][x] = makeAttr(7, 0, false, false, false);
            }
        }
    }

    private void insertChars(int n) {
        for (int x = cols - 1; x >= cursorX; x--) {
            if (x - n >= cursorX) {
                buffer[cursorY][x] = buffer[cursorY][x - n];
                attrs[cursorY][x] = attrs[cursorY][x - n];
            } else {
                buffer[cursorY][x] = ' ';
                attrs[cursorY][x] = makeAttr(7, 0, false, false, false);
            }
        }
    }

    private void eraseChars(int n) {
        for (int i = 0; i < n && cursorX + i < cols; i++) {
            buffer[cursorY][cursorX + i] = ' ';
            attrs[cursorY][cursorX + i] = makeAttr(7, 0, false, false, false);
        }
    }

    private void reset() {
        clearBuffer(buffer, attrs);
        cursorX = 0;
        cursorY = 0;
        currentFg = 7;
        currentBg = 0;
        bold = false;
        underline = false;
        reverse = false;
        cursorVisible = true;
        autoWrap = true;
        insertMode = false;
        applicationCursorKeys = false;
        originMode = false;
        scrollTop = 0;
        scrollBottom = 0;
    }
}
