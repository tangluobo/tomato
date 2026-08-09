package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地终端组件（PowerShell）。
 *
 * 参照 com.tangluobo.tomato.test.PowerShellTerminal 的实现：
 * 直接在 TextArea 中输入命令，通过 PowerShell 进程执行，
 * 支持 Tab 补全、上下箭头历史导航、Ctrl+C 中断等交互。
 *
 * 不再使用 ConPTY / TerminalEmulator / TerminalView，纯 TextArea 实现。
 */
public class LocalTerminalPane extends BorderPane {

    private TextArea terminalArea;
    private Process powerShellProcess;
    private BufferedWriter processWriter;
    private BufferedReader processReader;
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = 0;
    private volatile boolean historyPending = false;
    private String currentWorkingDir = System.getProperty("user.home");
    /** Tab 补全进行中标志，避免并发请求 */
    private volatile boolean completionPending = false;
    /** 命令是否正在运行（用于 Ctrl+C 判断是否需要终止进程） */
    private volatile boolean commandRunning = false;
    /** 当前 Tab 补全会话：记录候选列表、索引、触发时的输入前缀，用于循环切换 */
    private List<String> tabCandidates = new ArrayList<>();
    private int tabIndex = -1;
    private String tabPrefix = null;
    /** 提示符固定前缀，用户无法编辑该区域之前的内容 */
    private int promptStart = 0;
    private volatile boolean running = false;

    /** Ctrl+R 反向搜索状态 */
    private boolean searchMode = false;
    private int searchLineStart = 0;       // 搜索行在文本中的起始位置
    private String searchQuery = "";        // 当前搜索词
    private int searchMatchIndex = 0;       // 当前匹配在 commandHistory 中的索引，-1 表示无匹配
    private String searchMatchedCommand = "";

    // 状态栏
    private final Label stateLabel;
    private final Label shellLabel;
    private final Circle statusDot;

    /** 由连接处理器设置的最大回滚行数（TextArea 自带无限回滚，此处仅记录不强制截断） */
    private int scrollbackLines = 5000;

    public LocalTerminalPane() {
        // 状态栏
        HBox statusBar = new HBox();
        statusBar.setStyle("-fx-background-color: #FFFFFB; -fx-padding: 2 10; -fx-alignment: center-left; -fx-border-color: #e0e0e0; -fx-border-width: 1 0 0 0;");

        statusDot = new Circle(4, Color.RED);
        HBox.setMargin(statusDot, new javafx.geometry.Insets(0, 4, 0, 0));

        stateLabel = new Label("未连接");
        stateLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");
        HBox.setMargin(stateLabel, new javafx.geometry.Insets(0, 8, 0, 0));

        shellLabel = new Label("PowerShell");
        shellLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusBar.getChildren().addAll(statusDot, stateLabel, shellLabel, spacer);

        terminalArea = new TextArea();
        terminalArea.setFont(Font.font("Consolas", 14));
        terminalArea.setWrapText(true);
        terminalArea.setStyle(
            "-fx-control-inner-background: #1e1e1e; " +
            "-fx-text-fill: #d4d4d4; " +
            "-fx-border-color: transparent; " +
            "-fx-background-color: #1e1e1e;"
        );
        setCenter(terminalArea);
        setBottom(statusBar);
        setStyle("-fx-background-color: #1e1e1e; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0;");
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setPrefWidth(800);
        setPrefHeight(600);

        setupEventHandlers();
        setupContextMenu();
    }

    /**
     * 请求终端输入焦点（切换标签时调用）
     */
    public void requestTerminalFocus() {
        Platform.runLater(() -> terminalArea.requestFocus());
    }

    /**
     * 设置回滚行数（TextArea 自带无限回滚，此处仅记录配置，不强制截断）
     */
    public void setScrollbackLines(int lines) {
        this.scrollbackLines = lines;
    }

    /**
     * 连接本地 PowerShell 终端。
     * @param terminalType 兼容旧接口；新实现固定使用 PowerShell，参数仅作记录
     */
    public void connect(String terminalType) {
        initializeTerminal();
        Platform.runLater(() -> terminalArea.requestFocus());
    }

    /** 初始化 PowerShell 进程，欢迎信息和提示符由 PowerShell 自身输出（交互模式） */
    private void initializeTerminal() {
        try {
            // 交互模式启动：不加 -Command -，让 PowerShell 输出欢迎信息和提示符
            // -NoProfile 避免 PSReadLine 干扰 stdin 输入处理
            ProcessBuilder processBuilder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile"
            );
            processBuilder.redirectErrorStream(true);
            processBuilder.directory(new File(currentWorkingDir));

            powerShellProcess = processBuilder.start();
            // Windows 中文系统 PowerShell 默认输出 GBK，需用系统编码收发
            String jnuEncoding = System.getProperty("sun.jnu.encoding", "GBK");
            processWriter = new BufferedWriter(
                new OutputStreamWriter(powerShellProcess.getOutputStream(), jnuEncoding)
            );
            processReader = new BufferedReader(
                new InputStreamReader(powerShellProcess.getInputStream(), jnuEncoding)
            );
            running = true;

            Thread readerThread = new Thread(this::readProcessOutput);
            readerThread.setDaemon(true);
            readerThread.start();

            updateStatusBar("已连接");
        } catch (IOException e) {
            terminalArea.appendText("无法启动 PowerShell: " + e.getMessage() + "\n");
            terminalArea.appendText("请确保 PowerShell 已安装并在系统路径中。\n");
            updateStatusBar("启动失败");
        }
    }

    /** 显示新的命令提示符，并锁定光标位置 */
    private void showPrompt() {
        terminalArea.appendText("\nPS " + currentWorkingDir + "> ");
        promptStart = terminalArea.getText().length();
        terminalArea.positionCaret(promptStart);
    }

    /** 读取 PowerShell 输出并追加到终端，交互模式下 PowerShell 自带提示符 */
    private void readProcessOutput() {
        try {
            char[] buf = new char[1024];
            int n;
            while (running && (n = processReader.read(buf)) != -1) {
                // 规范化换行符：PowerShell 输出 \r\n，TextArea 只需 \n，避免空行
                String output = new String(buf, 0, n).replace("\r\n", "\n").replace("\r", "\n");
                Platform.runLater(() -> {
                    terminalArea.appendText(output);
                    // 跟踪 PowerShell 输出末尾作为输入起始位置（PowerShell 自己输出提示符）
                    promptStart = terminalArea.getText().length();
                    terminalArea.positionCaret(promptStart);
                    commandRunning = false;
                });
            }
        } catch (IOException e) {
            if (running) {
                Platform.runLater(() -> terminalArea.appendText("\n[PowerShell 进程已终止: " + e.getMessage() + "]\n"));
            }
        }
    }

    private void setupEventHandlers() {
        // 用事件过滤器在捕获阶段拦截所有特殊键，避免 TextArea 执行默认行为
        terminalArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();

            // Ctrl+R 反向搜索模式：优先处理，拦截其他键
            if (searchMode) {
                if (code == KeyCode.ENTER) {
                    event.consume();
                    executeSearchMatch();
                } else if (code == KeyCode.ESCAPE) {
                    event.consume();
                    exitSearchKeepCommand();
                } else if (code == KeyCode.C && event.isControlDown()) {
                    event.consume();
                    exitSearchCancel();
                } else if (code == KeyCode.R && event.isControlDown()) {
                    event.consume();
                    nextReverseSearch();
                } else if (code == KeyCode.BACK_SPACE || (code == KeyCode.H && event.isControlDown())) {
                    event.consume();
                    backspaceSearchQuery();
                } else if (code == KeyCode.LEFT || code == KeyCode.RIGHT) {
                    // 左右方向键：退出搜索，保留匹配命令作为输入，光标定位到对应端
                    event.consume();
                    exitSearchKeepCommand(code == KeyCode.LEFT);
                } else if (code == KeyCode.UP || code == KeyCode.DOWN
                        || code == KeyCode.HOME || code == KeyCode.END
                        || code == KeyCode.TAB) {
                    // 搜索模式下禁用历史导航和其他键
                    event.consume();
                }
                return;
            }

            // Ctrl+R 进入反向搜索模式
            if (code == KeyCode.R && event.isControlDown()) {
                event.consume();
                startReverseSearch();
                return;
            }

            if (code == KeyCode.TAB) {
                event.consume();
                requestTabCompletion();
            } else if (code == KeyCode.UP) {
                event.consume();
                navigateHistory(-1);
            } else if (code == KeyCode.DOWN) {
                event.consume();
                navigateHistory(1);
            } else if (code == KeyCode.ENTER) {
                event.consume();
                handleEnter();
            } else if (code == KeyCode.C && event.isControlDown() && !event.isShiftDown()) {
                // Ctrl+C：中断命令
                event.consume();
                handleCtrlC();
            } else if (code == KeyCode.C && event.isControlDown() && event.isShiftDown()) {
                // Ctrl+Shift+C：复制选中文本
                event.consume();
                doCopy();
            } else if (code == KeyCode.V && event.isControlDown() && event.isShiftDown()) {
                // Ctrl+Shift+V：粘贴
                event.consume();
                doPaste();
            } else if (code == KeyCode.A && event.isControlDown()) {
                // Ctrl+A：光标移到输入行最前面（不跨越提示符）
                event.consume();
                terminalArea.positionCaret(promptStart);
            } else if (code == KeyCode.E && event.isControlDown()) {
                // Ctrl+E：光标移到输入行最后
                event.consume();
                terminalArea.positionCaret(terminalArea.getText().length());
            } else if (code == KeyCode.BACK_SPACE) {
                if (terminalArea.getCaretPosition() <= promptStart) {
                    event.consume();
                }
            } else if (code == KeyCode.DELETE) {
                if (terminalArea.getCaretPosition() < promptStart) {
                    event.consume();
                }
            } else if (code == KeyCode.LEFT || code == KeyCode.HOME) {
                if (terminalArea.getCaretPosition() <= promptStart) {
                    event.consume();
                    terminalArea.positionCaret(promptStart);
                }
            } else if (terminalArea.getCaretPosition() < promptStart && !event.isControlDown()) {
                event.consume();
                terminalArea.positionCaret(promptStart);
            }
        });
        terminalArea.setOnKeyTyped(this::handleKeyTyped);
    }

    /** 自定义右键菜单：复制 / 粘贴 / 清屏（直接绑定到 terminalArea） */
    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> doCopy());
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> doPaste());
        MenuItem clearItem = new MenuItem("清屏");
        clearItem.setOnAction(e -> {
            terminalArea.clear();
            showPrompt();
        });
        MenuItem copyPasteItem = new MenuItem("复制并粘贴");
        copyPasteItem.setOnAction(e -> {
            doCopy();
            doPaste();
        });
        contextMenu.getItems().addAll(copyItem, pasteItem, copyPasteItem, new SeparatorMenuItem(), clearItem);

        // 直接绑定到 terminalArea，右键时动态更新按钮启用状态
        terminalArea.setContextMenu(contextMenu);
        contextMenu.setOnShowing(e -> {
            boolean hasSelection = terminalArea.getSelection().getLength() > 0;
            Clipboard clipboard = Clipboard.getSystemClipboard();
            boolean hasClipboard = clipboard.hasString();
            copyItem.setDisable(!hasSelection);
            pasteItem.setDisable(!hasClipboard);
            // 复制并粘贴：需要有选中文本
            copyPasteItem.setDisable(!hasSelection);
        });
    }

    /** 回车处理：提取命令并执行 */
    private void handleEnter() {
        String text = terminalArea.getText();
        String command = text.substring(promptStart).replace("\n", "").trim();
        // 清除用户输入的命令文本：交互模式下 PowerShell 会自行回显，避免重复
        replaceCurrentInput("");
        if (!command.isEmpty()) {
            executeCommand(command);
        } else {
            // 空命令：发送空行给 PowerShell，让它自己输出新提示符（格式一致）
            sendToPowerShell("");
        }
    }

    /** 拦截普通字符输入，禁止编辑提示符之前的内容 */
    private void handleKeyTyped(KeyEvent event) {
        // 拦截 Tab 字符，避免插入缩进
        if ("\t".equals(event.getCharacter())) {
            event.consume();
            return;
        }
        // 搜索模式：字符追加到搜索词
        if (searchMode) {
            String ch = event.getCharacter();
            if (ch.length() == 1 && ch.charAt(0) >= 32 && ch.charAt(0) != 127) {
                updateSearchQuery(ch.charAt(0));
            }
            event.consume();
            return;
        }
        int caret = terminalArea.getCaretPosition();
        if (caret < promptStart) {
            // 不允许在提示符之前输入
            event.consume();
            terminalArea.positionCaret(promptStart);
        }
    }

    /** Ctrl+C 处理：取消正在运行的命令，或清除当前输入行 */
    private void handleCtrlC() {
        if (commandRunning) {
            // 有命令在运行：杀掉 PowerShell 进程并重新启动
            if (powerShellProcess != null && powerShellProcess.isAlive()) {
                powerShellProcess.destroyForcibly();
            }
            commandRunning = false;
            terminalArea.appendText("^C\n");
            // 重新初始化 PowerShell 进程
            initializeTerminal();
        } else {
            // 无命令运行：清除当前输入行，发送空行让 PowerShell 输出新提示符
            replaceCurrentInput("");
            terminalArea.appendText("^C\n");
            // 清空当前 Tab 补全会话
            tabCandidates.clear();
            tabIndex = -1;
            tabPrefix = null;
            sendToPowerShell("");
        }
    }

    private void executeCommand(String command) {
        // 保存到历史
        commandHistory.add(command);
        historyIndex = commandHistory.size();

        // 处理内部命令
        if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit")) {
            terminalArea.appendText("正在退出...\n");
            disconnect();
            return;
        }

        if (command.equalsIgnoreCase("clear") || command.equalsIgnoreCase("cls")) {
            terminalArea.clear();
            terminalArea.appendText("PS " + currentWorkingDir + "> ");
            promptStart = terminalArea.getText().length();
            terminalArea.positionCaret(promptStart);
            return;
        }

        if (command.toLowerCase().startsWith("cd ")) {
            String path = command.substring(3).trim();
            if (path.startsWith("\"") && path.endsWith("\"")) {
                path = path.substring(1, path.length() - 1);
            }
            File newDir = new File(path);
            if (!newDir.isAbsolute()) {
                newDir = new File(currentWorkingDir, path);
            }
            if (newDir.exists() && newDir.isDirectory()) {
                currentWorkingDir = newDir.getAbsolutePath();
                if (powerShellProcess != null) {
                    powerShellProcess.destroyForcibly();
                    initializeTerminal();
                }
            } else {
                terminalArea.appendText("找不到路径 '" + path + "'，请确认路径是否正确。\n");
                showPrompt();
            }
            return;
        }

        // 发送命令到 PowerShell
        if (sendToPowerShell(command)) {
            commandRunning = true;
            // 追加到 PSReadLine 历史文件，与真实 PowerShell 控制台共享历史
            appendToHistoryFile(command);
        }
        // 提示符由 PowerShell 在命令完成后自行输出
    }

    /** 发送一行命令到 PowerShell 进程，返回是否成功 */
    private boolean sendToPowerShell(String command) {
        try {
            if (processWriter != null) {
                processWriter.write(command);
                processWriter.newLine();
                processWriter.flush();
                return true;
            }
        } catch (IOException e) {
            terminalArea.appendText("执行命令时出错: " + e.getMessage() + "\n");
        }
        return false;
    }

    /** 把命令追加到 PSReadLine 历史文件，与真实 PowerShell 控制台共享历史 */
    private void appendToHistoryFile(String command) {
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null) {
                return;
            }
            File histDir = new File(appData,
                "Microsoft\\Windows\\PowerShell\\PSReadLine");
            if (!histDir.exists()) {
                histDir.mkdirs();
            }
            File histFile = new File(histDir, "ConsoleHost_history.txt");
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(histFile, true), "UTF-8")) {
                w.write(command);
                w.write("\r\n");
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /** Tab 补全：若当前输入命中已有候选会话则循环切换，否则发起新请求 */
    private void requestTabCompletion() {
        if (completionPending) {
            return; // 已有补全在进行中
        }
        String currentInput = terminalArea.getText().substring(promptStart);
        if (currentInput.isEmpty()) {
            return;
        }
        // 若当前输入正好是上次候选之一，说明是循环切换
        if (tabCandidates != null && !tabCandidates.isEmpty()
                && tabCandidates.contains(currentInput)) {
            int idx = tabCandidates.indexOf(currentInput);
            int next = (idx + 1) % tabCandidates.size();
            replaceCurrentInput(tabCandidates.get(next));
            tabIndex = next;
            return;
        }
        // 否则发起新的补全请求
        tabCandidates.clear();
        tabIndex = -1;
        tabPrefix = null;

        int caretInInput = terminalArea.getCaretPosition() - promptStart;
        if (caretInInput < 0) caretInInput = 0;
        if (caretInInput > currentInput.length()) caretInInput = currentInput.length();

        completionPending = true;
        final String inputSnapshot = currentInput;
        final int cursorSnapshot = caretInInput;

        Thread t = new Thread(() -> {
            List<String> completions = fetchCompletions(inputSnapshot, cursorSnapshot);
            Platform.runLater(() -> {
                completionPending = false;
                startTabSession(completions, inputSnapshot);
            });
        });
        t.setDaemon(true);
        t.start();
    }

    /** 开始新的 Tab 补全会话：单候选直接替换；多候选切到第一个并记录会话 */
    private void startTabSession(List<String> completions, String originalInput) {
        if (completions.isEmpty()) {
            return;
        }
        // 读取当前输入，若用户在等待期间改动了输入导致不再以原输入开头，则放弃
        String currentInput = terminalArea.getText().substring(promptStart);
        if (!currentInput.startsWith(originalInput)) {
            return;
        }
        tabCandidates = new ArrayList<>(completions);
        tabPrefix = originalInput;
        tabIndex = 0;
        replaceCurrentInput(completions.get(0));
    }

    /** 启动独立 PowerShell 进程获取补全候选列表（用临时脚本文件避免命令行转义问题） */
    private List<String> fetchCompletions(String input, int cursorPos) {
        List<String> completions = new ArrayList<>();
        File scriptFile = null;
        try {
            // 单引号转义：PowerShell 单引号字符串中 ' 写成 ''
            String escapedInput = input.replace("'", "''");
            String script =
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n" +
                "$r = TabExpansion2 -inputScript '" + escapedInput + "' -cursorColumn " + cursorPos + "\r\n" +
                "\"__TAB_START__\"\r\n" +
                "if ($r) { $r.CompletionMatches | ForEach-Object { $_.CompletionText } }\r\n" +
                "\"__TAB_END__\"\r\n";

            // 写入临时脚本文件，UTF-8 无 BOM
            scriptFile = File.createTempFile("tomato_tab_", ".ps1");
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(scriptFile), "UTF-8")) {
                w.write(script);
            }

            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.getAbsolutePath()
            );
            pb.directory(new File(currentWorkingDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 用 UTF-8 读取，与脚本中设置的输出编码一致
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), "UTF-8")
            );

            boolean inBlock = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("__TAB_START__")) {
                    inBlock = true;
                    continue;
                }
                if (line.equals("__TAB_END__")) {
                    break;
                }
                if (inBlock && !line.isEmpty()) {
                    completions.add(line);
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
            p.destroyForcibly();
        } catch (Exception e) {
            // 补全失败时静默忽略
        } finally {
            if (scriptFile != null) {
                scriptFile.delete();
            }
        }
        return completions;
    }

    /** 上下箭头切换历史：读取 PowerShell 的 PSReadLine 历史文件（真实 PowerShell 控制台使用的历史） */
    private void navigateHistory(int direction) {
        if (historyPending) {
            return;
        }
        // 在历史中间导航时，使用缓存快速切换
        if (!commandHistory.isEmpty() && historyIndex < commandHistory.size()) {
            int newIndex = historyIndex + direction;
            if (newIndex >= 0 && newIndex < commandHistory.size()) {
                historyIndex = newIndex;
                replaceCurrentInput(commandHistory.get(historyIndex));
                return;
            } else if (direction > 0 && newIndex >= commandHistory.size()) {
                // 回到底部：清空输入
                historyIndex = commandHistory.size();
                replaceCurrentInput("");
                return;
            } else if (direction < 0 && newIndex < 0) {
                // 已经到顶部
                return;
            }
        }
        // 处于历史底部（historyIndex >= size）或缓存为空时：
        // 按上箭头则从文件刷新历史再切换；按下箭头到底部则清空
        if (direction > 0) {
            // 已经在底部，继续按向下没意义
            replaceCurrentInput("");
            return;
        }
        // 按上箭头：异步从 PSReadLine 历史文件刷新
        historyPending = true;
        Thread t = new Thread(() -> {
            List<String> realHistory = fetchPowerShellHistory();
            Platform.runLater(() -> {
                historyPending = false;
                if (realHistory != null && !realHistory.isEmpty()) {
                    commandHistory.clear();
                    commandHistory.addAll(realHistory);
                    historyIndex = commandHistory.size();
                    // 切换到最后一条（上箭头 = 上一条命令）
                    int newIndex = historyIndex + direction;
                    if (newIndex >= 0 && newIndex < commandHistory.size()) {
                        historyIndex = newIndex;
                        replaceCurrentInput(commandHistory.get(historyIndex));
                    }
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    /** 读取 PowerShell 的 PSReadLine 历史文件（真实 PowerShell 控制台上下箭头使用的历史） */
    private List<String> fetchPowerShellHistory() {
        List<String> history = new ArrayList<>();
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null) {
                return history;
            }
            File histFile = new File(appData,
                "Microsoft\\Windows\\PowerShell\\PSReadLine\\ConsoleHost_history.txt");
            if (!histFile.exists()) {
                return history;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(histFile), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        history.add(line);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return history;
    }

    /** 替换当前提示符后的输入内容 */
    private void replaceCurrentInput(String text) {
        String fullText = terminalArea.getText();
        String beforePrompt = fullText.substring(0, promptStart);
        terminalArea.setText(beforePrompt + text);
        terminalArea.positionCaret(terminalArea.getText().length());
    }

    /** 粘贴剪贴板内容到当前输入 */
    private void doPaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            String text = clipboard.getString();
            if (text != null && !text.isEmpty()) {
                // TextArea 粘贴时换行符保持原样，命令行内换行会被回车处理逻辑忽略
                text = text.replace("\r\n", "").replace("\n", "");
                int caret = terminalArea.getCaretPosition();
                if (caret < promptStart) {
                    caret = promptStart;
                    terminalArea.positionCaret(promptStart);
                }
                terminalArea.insertText(caret, text);
                terminalArea.positionCaret(caret + text.length());
            }
        }
    }

    /** 复制选中文本到剪贴板（保留换行） */
    private void doCopy() {
        String selected = terminalArea.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(selected);
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    private void updateStatusBar(String state) {
        Platform.runLater(() -> {
            boolean connected = "已连接".equals(state);
            statusDot.setFill(connected ? Color.valueOf("#4CAF50") : Color.RED);
            stateLabel.setText(state);
        });
    }

    // ===================== Ctrl+R 反向搜索历史命令 =====================

    /** 进入反向搜索模式：先确保历史已加载，再显示搜索 UI */
    private void startReverseSearch() {
        if (commandHistory.isEmpty()) {
            // 异步从 PSReadLine 历史文件加载
            historyPending = true;
            Thread t = new Thread(() -> {
                List<String> realHistory = fetchPowerShellHistory();
                Platform.runLater(() -> {
                    historyPending = false;
                    if (realHistory != null && !realHistory.isEmpty()) {
                        commandHistory.clear();
                        commandHistory.addAll(realHistory);
                    }
                    enterSearchMode();
                });
            });
            t.setDaemon(true);
            t.start();
        } else {
            enterSearchMode();
        }
    }

    /** 实际进入搜索模式：清除当前输入，显示搜索提示行 */
    private void enterSearchMode() {
        searchMode = true;
        searchQuery = "";
        searchMatchIndex = commandHistory.size(); // 从最新开始向上搜索
        searchMatchedCommand = "";
        // 清除当前输入行内容（保留提示符之前的内容）
        String fullText = terminalArea.getText();
        terminalArea.setText(fullText.substring(0, promptStart));
        // 追加搜索提示行
        terminalArea.appendText("(reverse-i-search)`': ");
        searchLineStart = promptStart;
        terminalArea.positionCaret(terminalArea.getText().length());
    }

    /** 重绘搜索行：query 和匹配命令 */
    private void renderSearchLine() {
        String fullText = terminalArea.getText();
        String before = fullText.substring(0, searchLineStart);
        String line = "(reverse-i-search)`" + searchQuery + "': " + searchMatchedCommand;
        terminalArea.setText(before + line);
        // 光标定位到 query 末尾
        int caretPos = searchLineStart + "(reverse-i-search)`".length() + searchQuery.length();
        terminalArea.positionCaret(caretPos);
    }

    /** 输入字符时更新搜索词并重新搜索 */
    private void updateSearchQuery(char c) {
        searchQuery += c;
        searchMatchIndex = commandHistory.size();
        findNextMatch();
        renderSearchLine();
    }

    /** Backspace 删除搜索词最后一个字符 */
    private void backspaceSearchQuery() {
        if (searchQuery.isEmpty()) return;
        searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
        searchMatchIndex = commandHistory.size();
        findNextMatch();
        renderSearchLine();
    }

    /** 从 searchMatchIndex-1 向旧搜索包含 searchQuery 的命令 */
    private void findNextMatch() {
        if (searchQuery.isEmpty()) {
            searchMatchedCommand = "";
            return;
        }
        for (int i = searchMatchIndex - 1; i >= 0; i--) {
            if (commandHistory.get(i).contains(searchQuery)) {
                searchMatchIndex = i;
                searchMatchedCommand = commandHistory.get(i);
                return;
            }
        }
        searchMatchedCommand = "";
    }

    /** Ctrl+R 再次按下：继续向上搜索下一个匹配 */
    private void nextReverseSearch() {
        if (searchQuery.isEmpty()) return;
        findNextMatch();
        renderSearchLine();
    }

    /** Esc：退出搜索模式，保留匹配命令作为当前输入，用户可继续编辑 */
    private void exitSearchKeepCommand() {
        exitSearchKeepCommand(false);
    }

    /**
     * 退出搜索模式，保留匹配命令作为当前输入。
     * @param caretToStart true=光标定位到命令最前；false=光标定位到命令最后
     */
    private void exitSearchKeepCommand(boolean caretToStart) {
        String cmd = searchMatchedCommand;
        resetSearchState();
        // 清除搜索行
        String fullText = terminalArea.getText();
        terminalArea.setText(fullText.substring(0, searchLineStart));
        // 显示新提示符并填入匹配命令
        terminalArea.appendText("PS " + currentWorkingDir + "> ");
        promptStart = terminalArea.getText().length();
        if (!cmd.isEmpty()) {
            terminalArea.appendText(cmd);
        }
        // 光标定位：左键→命令最前；右键或 Esc→命令最后
        if (caretToStart) {
            terminalArea.positionCaret(promptStart);
        } else {
            terminalArea.positionCaret(terminalArea.getText().length());
        }
    }

    /** Ctrl+C：退出搜索模式，清空输入 */
    private void exitSearchCancel() {
        resetSearchState();
        String fullText = terminalArea.getText();
        terminalArea.setText(fullText.substring(0, searchLineStart));
        terminalArea.appendText("^C\n");
        sendToPowerShell("");
    }

    /** Enter：执行匹配的命令 */
    private void executeSearchMatch() {
        String cmd = searchMatchedCommand;
        resetSearchState();
        String fullText = terminalArea.getText();
        terminalArea.setText(fullText.substring(0, searchLineStart));
        if (cmd.isEmpty()) {
            // 无匹配：发送空行
            sendToPowerShell("");
        } else {
            // 执行匹配命令（PowerShell 会自行回显）
            executeCommand(cmd);
        }
    }

    /** 重置搜索状态 */
    private void resetSearchState() {
        searchMode = false;
        searchQuery = "";
        searchMatchIndex = 0;
        searchMatchedCommand = "";
    }

    /** 断开连接：终止 PowerShell 进程 */
    public void disconnect() {
        running = false;
        commandRunning = false;
        if (powerShellProcess != null && powerShellProcess.isAlive()) {
            powerShellProcess.destroyForcibly();
        }
        powerShellProcess = null;
        processWriter = null;
        processReader = null;
        updateStatusBar("已断开");
    }
}
