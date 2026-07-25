package com.tangluobo.tomato.ssh;

/**
 * VT100终端模拟器
 * 解析ANSI转义序列，维护字符缓冲区和光标状态
 */
public class TerminalEmulator {

    public static final int DEFAULT_COLS = 80;
    public static final int DEFAULT_ROWS = 24;

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
    // ANSI解析状态
    private ParseState parseState = ParseState.NORMAL;
    private StringBuilder escapeSeq = new StringBuilder();
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
        clearBuffer();
    }

    private void clearBuffer() {
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                buffer[y][x] = ' ';
                attrs[y][x] = makeAttr(7, 0, false, false, false);
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
    public char getChar(int x, int y) { return buffer[y][x]; }
    public int getAttr(int x, int y) { return attrs[y][x]; }

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
    }

    /**
     * 处理输入的字节流（UTF-8解码后处理）
     */
    public void process(byte[] data, int offset, int len) {
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
            cursorY++;
            if (cursorY >= rows) {
                scrollUp(1);
                cursorY = rows - 1;
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
            boolean wideChar = isWideChar(ch);
            int charWidth = wideChar ? 2 : 1;

            if (cursorX + charWidth > cols) {
                if (autoWrap) {
                    cursorX = 0;
                    cursorY++;
                    if (cursorY >= rows) {
                        scrollUp(1);
                        cursorY = rows - 1;
                    }
                } else {
                    cursorX = cols - charWidth;
                }
            }
            buffer[cursorY][cursorX] = ch;
            attrs[cursorY][cursorX] = makeAttr(currentFg, currentBg, bold, underline, reverse);
            if (wideChar && cursorX + 1 < cols) {
                // 宽字符占2格，第二格标记为0表示属于前一个字符
                buffer[cursorY][cursorX + 1] = 0;
                attrs[cursorY][cursorX + 1] = attrs[cursorY][cursorX];
            }
            cursorX += charWidth;
        }
    }

    /**
     * 判断是否为宽字符（CJK等双宽度字符）
     */
    private boolean isWideChar(char ch) {
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
        } else if (ch == '7') {
            // 保存光标
            savedCursorX = cursorX;
            savedCursorY = cursorY;
            parseState = ParseState.NORMAL;
        } else if (ch == '8') {
            // 恢复光标
            cursorX = savedCursorX;
            cursorY = savedCursorY;
            parseState = ParseState.NORMAL;
        } else if (ch == 'D') {
            // 索引（下移一行，必要时滚动）
            cursorY++;
            if (cursorY >= rows) {
                scrollUp(1);
                cursorY = rows - 1;
            }
            parseState = ParseState.NORMAL;
        } else if (ch == 'M') {
            // 反向索引（上移一行，必要时滚动）
            if (cursorY == 0) {
                scrollDown(1);
            } else {
                cursorY--;
            }
            parseState = ParseState.NORMAL;
        } else if (ch == 'c') {
            // 重置终端
            reset();
            parseState = ParseState.NORMAL;
        } else if (ch == '(') {
            // 设计G0字符集 - 跳过下一个字符
            parseState = ParseState.CHARSET;
        } else if (ch == ')') {
            // 设计G1字符集 - 跳过下一个字符
            parseState = ParseState.CHARSET;
        } else {
            // 未知ESC序列，忽略
            parseState = ParseState.NORMAL;
        }
    }

    private void processCsiChar(char ch) {
        if ((ch >= '0' && ch <= '9') || ch == ';' || ch == '?' || ch == ' ' || ch == ':') {
            escapeSeq.append(ch);
        } else {
            // CSI序列结束，执行命令
            executeCsiCommand(ch);
            parseState = ParseState.NORMAL;
        }
    }

    private void processOscChar(char ch) {
        if (ch == 0x1b) {
            parseState = ParseState.ESC;
        } else if (ch == 7) { // BEL结束OSC
            parseState = ParseState.NORMAL;
        } else if (ch == '\033') {
            parseState = ParseState.ESC;
        }
        // 忽略OSC内容（标题设置等）
    }

    private void executeCsiCommand(char cmd) {
        String seq = escapeSeq.toString();
        boolean privateMode = false;
        if (seq.startsWith("?")) {
            privateMode = true;
            seq = seq.substring(1);
        }

        int[] params = parseParams(seq);

        switch (cmd) {
            case 'H': // 光标位置
            case 'f':
                cursorY = (params.length > 0 ? params[0] : 1) - 1;
                cursorX = (params.length > 1 ? params[1] : 1) - 1;
                clampCursor();
                break;
            case 'A': // 光标上移
                cursorY -= (params.length > 0 ? params[0] : 1);
                if (cursorY < 0) cursorY = 0;
                break;
            case 'B': // 光标下移
                cursorY += (params.length > 0 ? params[0] : 1);
                if (cursorY >= rows) cursorY = rows - 1;
                break;
            case 'C': // 光标右移
                cursorX += (params.length > 0 ? params[0] : 1);
                if (cursorX >= cols) cursorX = cols - 1;
                break;
            case 'D': // 光标左移
                cursorX -= (params.length > 0 ? params[0] : 1);
                if (cursorX < 0) cursorX = 0;
                break;
            case 'G': // 光标水平绝对位置
                cursorX = (params.length > 0 ? params[0] : 1) - 1;
                clampCursor();
                break;
            case 'd': // 光标垂直绝对位置
                cursorY = (params.length > 0 ? params[0] : 1) - 1;
                clampCursor();
                break;
            case 'J': // 清屏
                clearScreen(params.length > 0 ? params[0] : 0);
                break;
            case 'K': // 清行
                clearLine(params.length > 0 ? params[0] : 0);
                break;
            case 'm': // 设置属性
                setAttribute(params);
                break;
            case 'h': // 设置模式
                if (privateMode) {
                    if (params.length > 0 && params[0] == 25) {
                        cursorVisible = true;
                    }
                } else if (params.length > 0 && params[0] == 7) {
                    autoWrap = true;
                }
                break;
            case 'l': // 重置模式
                if (privateMode) {
                    if (params.length > 0 && params[0] == 25) {
                        cursorVisible = false;
                    }
                } else if (params.length > 0 && params[0] == 7) {
                    autoWrap = false;
                }
                break;
            case 'r': // 设置滚动区域
                // 简化实现，忽略
                break;
            case 'S': // 向上滚动
                scrollUp(params.length > 0 ? params[0] : 1);
                break;
            case 'T': // 向下滚动
                scrollDown(params.length > 0 ? params[0] : 1);
                break;
            case 'L': // 插入行
                insertLines(params.length > 0 ? params[0] : 1);
                break;
            case 'M': // 删除行
                deleteLines(params.length > 0 ? params[0] : 1);
                break;
            case 'P': // 删除字符
                deleteChars(params.length > 0 ? params[0] : 1);
                break;
            case '@': // 插入字符
                insertChars(params.length > 0 ? params[0] : 1);
                break;
            case 'X': // 删除字符（用空格替换）
                eraseChars(params.length > 0 ? params[0] : 1);
                break;
            default:
                // 忽略不支持的命令
                break;
        }
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
                clearBuffer();
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

    private void scrollUp(int n) {
        for (int i = 0; i < n; i++) {
            for (int y = 0; y < rows - 1; y++) {
                System.arraycopy(buffer[y + 1], 0, buffer[y], 0, cols);
                System.arraycopy(attrs[y + 1], 0, attrs[y], 0, cols);
            }
            for (int x = 0; x < cols; x++) {
                buffer[rows - 1][x] = ' ';
                attrs[rows - 1][x] = makeAttr(7, 0, false, false, false);
            }
        }
    }

    private void scrollDown(int n) {
        for (int i = 0; i < n; i++) {
            for (int y = rows - 1; y > 0; y--) {
                System.arraycopy(buffer[y - 1], 0, buffer[y], 0, cols);
                System.arraycopy(attrs[y - 1], 0, attrs[y], 0, cols);
            }
            for (int x = 0; x < cols; x++) {
                buffer[0][x] = ' ';
                attrs[0][x] = makeAttr(7, 0, false, false, false);
            }
        }
    }

    private void insertLines(int n) {
        for (int i = 0; i < n; i++) {
            if (cursorY < rows - 1) {
                for (int y = rows - 1; y > cursorY; y--) {
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
        for (int i = 0; i < n; i++) {
            for (int y = cursorY; y < rows - 1; y++) {
                System.arraycopy(buffer[y + 1], 0, buffer[y], 0, cols);
                System.arraycopy(attrs[y + 1], 0, attrs[y], 0, cols);
            }
            for (int x = 0; x < cols; x++) {
                buffer[rows - 1][x] = ' ';
                attrs[rows - 1][x] = makeAttr(7, 0, false, false, false);
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
        clearBuffer();
        cursorX = 0;
        cursorY = 0;
        currentFg = 7;
        currentBg = 0;
        bold = false;
        underline = false;
        reverse = false;
        cursorVisible = true;
        autoWrap = true;
    }
}
