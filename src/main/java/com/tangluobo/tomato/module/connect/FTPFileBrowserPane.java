package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FTP文件浏览器面板
 * 通过原生Socket实现FTP协议，连接FTP服务器并浏览远程文件系统
 * UI 逻辑由 {@link AbstractFileBrowserPane} 提供，本类只实现 FTP 后端操作。
 */
public class FTPFileBrowserPane extends AbstractFileBrowserPane {

    private final ConnectionConfig config;

    // FTP 客户端
    private final SimpleFTPClient ftpClient = new SimpleFTPClient();

    // 状态栏组件（FTP 特有：状态指示灯 + 连接信息）
    private Circle statusDot;
    private Label stateLabel;
    private Label connLabel;

    // ==================== 构造 ====================
    public FTPFileBrowserPane(ConnectionConfig config) {
        super();
        this.config = config;
        connectAndLoad();
    }

    // ==================== 状态栏（覆盖基类） ====================
    @Override
    protected javafx.scene.Node createStatusBar() {
        HBox statusBar = new HBox(8);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 10, 4, 10));
        statusBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;");

        statusDot = new Circle(5);
        statusDot.setFill(Color.GRAY);
        statusBar.getChildren().add(statusDot);

        stateLabel = new Label("连接中...");
        stateLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(stateLabel);

        Label sep1 = new Label("|");
        sep1.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        statusBar.getChildren().add(sep1);

        connLabel = new Label(config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ":" + config.getPort() + ")");
        connLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(connLabel);

        // 基类的 statusLabel 指向 stateLabel，使基类的 setStatus() 能更新状态文本
        statusLabel = stateLabel;
        return statusBar;
    }

    // ==================== 钩子 ====================
    @Override
    protected boolean supportsColumnView() {
        return false; // FTP 原本仅列表视图
    }

    // ==================== 抽象后端方法实现 ====================
    @Override
    protected boolean isConnected() {
        return ftpClient.isConnected();
    }

    @Override
    protected void doRefresh() {
        doNavigateTo(getCurrentPath());
    }

    @Override
    protected void doNavigateTo(String path) {
        new Thread(() -> {
            try {
                if (!ftpClient.isConnected()) {
                    ftpClient.connect(config.getHost(), config.getPort());
                    ftpClient.login(config.getUsername(), config.getPassword());
                }
                ftpClient.cwd(path);
                String realPath = ftpClient.pwd();
                List<FileItem> entries = ftpClient.listFiles();
                Platform.runLater(() -> {
                    setCurrentPath(realPath);
                    setFileList(entries);
                    if (upBtn != null) upBtn.setDisable("/".equals(realPath));
                    setStatus(entries.size() + " 个条目");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("错误: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载文件列表: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "FTP-Navigate").start();
    }

    @Override
    protected void doRename(FileItem item, String newName) throws Exception {
        String newPath = joinPath(getCurrentPath(), newName);
        ftpClient.rename(item.getPath(), newPath);
    }

    @Override
    protected void doDelete(FileItem item) {
        new Thread(() -> {
            try {
                if (item.isDirectory()) {
                    ftpClient.rmd(item.getPath());
                } else {
                    ftpClient.dele(item.getPath());
                }
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("删除失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法删除: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "FTP-Delete").start();
    }

    @Override
    protected void doMkdir(String fullPath) {
        new Thread(() -> {
            try {
                ftpClient.mkd(fullPath);
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("创建失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法创建目录: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "FTP-Mkdir").start();
    }

    @Override
    protected void doUploadSingle(File localFile) throws Exception {
        // FTP STOR upload not yet implemented
        throw new UnsupportedOperationException("FTP 上传功能暂未实现");
    }

    @Override
    protected void doDownload(FileItem item, File localFile) {
        // FTP RETR download not yet implemented
        setStatus("下载功能暂未实现");
    }

    @Override
    protected File doDownloadToTemp(FileItem item) {
        return null;
    }

    @Override
    protected void loadColumnAsync(String path, int colIndex) {
        // FTP 不支持列视图，空实现
    }

    // ==================== FTP 连接 ====================
    private void connectAndLoad() {
        new Thread(() -> {
            try {
                ftpClient.connect(config.getHost(), config.getPort());
                ftpClient.login(config.getUsername(), config.getPassword());
                String home = ftpClient.pwd();
                Platform.runLater(() -> {
                    if (statusDot != null) statusDot.setFill(Color.GREEN);
                    if (stateLabel != null) stateLabel.setText("已连接");
                    doNavigateTo(home);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (statusDot != null) statusDot.setFill(Color.RED);
                    if (stateLabel != null) stateLabel.setText("连接失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到 " + config.getName() + ": " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "FTP-Connect").start();
    }

    public void disconnect() {
        new Thread(() -> {
            try {
                ftpClient.disconnect();
            } catch (Exception ignored) {}
        }, "FTP-Disconnect").start();
    }

    // ==================== 工具方法 ====================
    private static String joinPath(String base, String name) {
        if (base == null || base.isEmpty() || "/".equals(base)) return "/" + name;
        if (base.endsWith("/")) return base + name;
        return base + "/" + name;
    }

    // ==================== SimpleFTPClient（FTP 协议实现，保持不变） ====================
    private static class SimpleFTPClient {
        private Socket controlSocket;
        private BufferedReader controlReader;
        private Writer controlWriter;
        private volatile boolean connected = false;

        public boolean isConnected() {
            return connected && controlSocket != null && controlSocket.isConnected() && !controlSocket.isClosed();
        }

        public void connect(String host, int port) throws IOException {
            controlSocket = new Socket();
            controlSocket.connect(new InetSocketAddress(host, port), 15000);
            controlSocket.setSoTimeout(30000);
            controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
            controlWriter = new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8);

            String welcome = readResponse();
            if (!welcome.startsWith("220")) {
                throw new IOException("FTP服务器拒绝连接: " + welcome);
            }
            connected = true;
        }

        public void login(String user, String pass) throws IOException {
            sendCommand("USER " + (user == null ? "anonymous" : user));
            String resp = readResponse();
            if (resp.startsWith("230")) {
                return; // 无需密码
            }
            if (!resp.startsWith("331")) {
                throw new IOException("用户名错误: " + resp);
            }
            sendCommand("PASS " + (pass == null ? "" : pass));
            String passResp = readResponse();
            if (!passResp.startsWith("230")) {
                throw new IOException("登录失败: " + passResp);
            }
            try { sendCommand("TYPE I"); readResponse(); } catch (Exception ignored) {}
            try { sendCommand("OPTS UTF8 ON"); readResponse(); } catch (Exception ignored) {}
        }

        public String pwd() throws IOException {
            sendCommand("PWD");
            String resp = readResponse();
            if (!resp.startsWith("257")) {
                throw new IOException("PWD失败: " + resp);
            }
            int firstQuote = resp.indexOf('"');
            int lastQuote = resp.lastIndexOf('"');
            if (firstQuote >= 0 && lastQuote > firstQuote) {
                return resp.substring(firstQuote + 1, lastQuote);
            }
            return "/";
        }

        public void cwd(String path) throws IOException {
            sendCommand("CWD " + path);
            String resp = readResponse();
            if (!resp.startsWith("250")) {
                throw new IOException("CWD失败: " + resp);
            }
        }

        public void mkd(String path) throws IOException {
            sendCommand("MKD " + path);
            String resp = readResponse();
            if (!resp.startsWith("257")) {
                throw new IOException("MKD失败: " + resp);
            }
        }

        public void rmd(String path) throws IOException {
            sendCommand("RMD " + path);
            String resp = readResponse();
            if (!resp.startsWith("250")) {
                throw new IOException("RMD失败: " + resp);
            }
        }

        public void dele(String path) throws IOException {
            sendCommand("DELE " + path);
            String resp = readResponse();
            if (!resp.startsWith("250")) {
                throw new IOException("DELE失败: " + resp);
            }
        }

        public void rename(String fromPath, String toPath) throws IOException {
            sendCommand("RNFR " + fromPath);
            String resp1 = readResponse();
            if (!resp1.startsWith("350")) {
                throw new IOException("RNFR失败: " + resp1);
            }
            sendCommand("RNTO " + toPath);
            String resp2 = readResponse();
            if (!resp2.startsWith("250")) {
                throw new IOException("RNTO失败: " + resp2);
            }
        }

        public List<FileItem> listFiles() throws IOException {
            InetSocketAddress dataAddr = enterPassiveMode();
            sendCommand("LIST");

            String resp150 = readResponse();
            if (!resp150.startsWith("150") && !resp150.startsWith("125")) {
                throw new IOException("LIST失败: " + resp150);
            }

            Socket dataSocket = new Socket();
            dataSocket.connect(dataAddr, 15000);
            dataSocket.setSoTimeout(30000);

            List<String> lines = new ArrayList<>();
            try (BufferedReader dataReader = new BufferedReader(new InputStreamReader(dataSocket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = dataReader.readLine()) != null) {
                    if (!line.isEmpty()) lines.add(line);
                }
            } finally {
                try { dataSocket.close(); } catch (Exception ignored) {}
            }

            readResponse();

            return parseListLines(lines);
        }

        private InetSocketAddress enterPassiveMode() throws IOException {
            sendCommand("PASV");
            String resp = readResponse();
            if (!resp.startsWith("227")) {
                throw new IOException("PASV失败: " + resp);
            }
            int start = resp.indexOf('(');
            int end = resp.indexOf(')');
            if (start < 0 || end <= start) {
                throw new IOException("PASV响应格式错误: " + resp);
            }
            String[] parts = resp.substring(start + 1, end).split(",");
            if (parts.length != 6) {
                throw new IOException("PASV响应格式错误: " + resp);
            }
            String host = parts[0].trim() + "." + parts[1].trim() + "." + parts[2].trim() + "." + parts[3].trim();
            int port = (Integer.parseInt(parts[4].trim()) << 8) | Integer.parseInt(parts[5].trim());
            return new InetSocketAddress(host, port);
        }

        private List<FileItem> parseListLines(List<String> lines) {
            List<FileItem> items = new ArrayList<>();
            String currentDir;
            try {
                currentDir = pwd();
            } catch (Exception e) {
                currentDir = "/";
            }

            for (String line : lines) {
                FileItem item = parseListLine(line, currentDir);
                if (item != null) {
                    items.add(item);
                }
            }
            items.sort((a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            return items;
        }

        private FileItem parseListLine(String line, String currentDir) {
            if (line == null || line.isEmpty()) return null;
            line = line.trim();

            if (line.length() > 10 && (line.charAt(0) == 'd' || line.charAt(0) == '-' || line.charAt(0) == 'l')) {
                return parseUnixListLine(line, currentDir);
            }
            return parseWindowsListLine(line, currentDir);
        }

        private FileItem parseUnixListLine(String line, String currentDir) {
            Pattern p = Pattern.compile(
                    "^([dl-][rwxst-]{9})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+" +
                    "(\\w{3}\\s+\\d{1,2}\\s+\\d{1,2}:\\d{2}|\\w{3}\\s+\\d{1,2}\\s+\\d{4})\\s+(.*)$"
            );
            Matcher m = p.matcher(line);
            if (!m.matches()) {
                String[] tokens = line.split("\\s+", 2);
                if (tokens.length < 2) return null;
                boolean isDir = tokens[0].startsWith("d");
                String name = line.substring(line.lastIndexOf(' ') + 1).trim();
                if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
                FileItem item = new FileItem();
                item.setName(name);
                item.setPath(joinPath(currentDir, name));
                item.setDirectory(isDir);
                item.setSize(0);
                item.setModifyTime(0);
                return item;
            }

            String perm = m.group(1);
            long size = 0;
            try { size = Long.parseLong(m.group(2)); } catch (Exception ignored) {}
            String dateStr = m.group(3);
            String name = m.group(4).trim();

            int arrow = name.indexOf(" -> ");
            if (arrow > 0) {
                name = name.substring(0, arrow);
            }

            if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;

            FileItem item = new FileItem();
            item.setName(name);
            item.setPath(joinPath(currentDir, name));
            item.setDirectory(perm.startsWith("d") || perm.startsWith("l"));
            item.setSize(size);
            item.setModifyTime(parseUnixDate(dateStr));
            return item;
        }

        private long parseUnixDate(String dateStr) {
            try {
                String year;
                String time = null;
                String[] parts = dateStr.split("\\s+");
                if (parts.length >= 3) {
                    if (parts[2].contains(":")) {
                        year = String.valueOf(java.time.Year.now().getValue());
                        time = parts[2];
                    } else {
                        year = parts[2];
                    }
                    String fmt = time != null ? "MMM d yyyy H:mm" : "MMM d yyyy";
                    SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.ENGLISH);
                    String toParse = time != null
                            ? parts[0] + " " + parts[1] + " " + year + " " + time
                            : parts[0] + " " + parts[1] + " " + year;
                    return sdf.parse(toParse).getTime();
                }
            } catch (Exception ignored) {}
            return 0;
        }

        private FileItem parseWindowsListLine(String line, String currentDir) {
            Pattern p = Pattern.compile("(\\d{2}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}[AP]M)\\s+(<DIR>|\\d+)\\s+(.*)");
            Matcher m = p.matcher(line);
            if (!m.matches()) return null;
            String name = m.group(4).trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
            FileItem item = new FileItem();
            item.setName(name);
            item.setPath(joinPath(currentDir, name));
            item.setDirectory("<DIR>".equals(m.group(3)));
            if (!item.isDirectory()) {
                try { item.setSize(Long.parseLong(m.group(3))); } catch (Exception ignored) {}
            }
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yy hh:mma", Locale.ENGLISH);
                item.setModifyTime(sdf.parse(m.group(1) + " " + m.group(2)).getTime());
            } catch (Exception ignored) {}
            return item;
        }

        private void sendCommand(String cmd) throws IOException {
            controlWriter.write(cmd + "\r\n");
            controlWriter.flush();
        }

        private String readResponse() throws IOException {
            String line = controlReader.readLine();
            if (line == null) {
                throw new IOException("FTP连接已关闭");
            }
            if (line.length() >= 4 && line.charAt(3) == '-') {
                String expectedCode = line.substring(0, 3);
                StringBuilder sb = new StringBuilder(line);
                while (true) {
                    String next = controlReader.readLine();
                    if (next == null) break;
                    sb.append("\n").append(next);
                    if (next.length() >= 4 && next.substring(0, 3).equals(expectedCode) && next.charAt(3) == ' ') {
                        break;
                    }
                }
                return sb.toString();
            }
            return line;
        }

        public void disconnect() {
            connected = false;
            try {
                if (controlSocket != null && !controlSocket.isClosed()) {
                    try { sendCommand("QUIT"); } catch (Exception ignored) {}
                    controlSocket.close();
                }
            } catch (Exception ignored) {}
            controlSocket = null;
            controlReader = null;
            controlWriter = null;
        }
    }
}
