package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 端口视图面板
 * 列出服务器当前监听的端口，支持停止进程、加入防火墙白名单等操作
 */
public class PortPanel extends BorderPane {

    private final SSHSession sshSession;
    private Timer timer;

    private final TableView<PortItem> portTable;
    private final ObservableList<PortItem> portList = FXCollections.observableArrayList();
    private final List<PortItem> allPorts = new ArrayList<>();
    private final Label statusLabel;
    private final CheckBox tcpCheck;
    private final CheckBox udpCheck;

    public PortPanel(SSHSession sshSession) {
        this.sshSession = sshSession;

        setStyle("-fx-background-color: #FFFFFF;");
        setPrefHeight(350);

        HBox topBar = new HBox();
        topBar.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 4 8; -fx-alignment: center-left;");

        Label titleLabel = new Label("端口监听");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label updateLabel = new Label("每3秒更新");
        updateLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        topBar.getChildren().addAll(titleLabel, spacer, updateLabel);

        // 协议过滤栏
        HBox filterBar = new HBox(8);
        filterBar.setStyle("-fx-background-color: #fafafa; -fx-padding: 3 8; -fx-alignment: center-left; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        Label filterLabel = new Label("协议:");
        filterLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        tcpCheck = new CheckBox("TCP");
        tcpCheck.setStyle("-fx-font-size: 11px;");
        tcpCheck.setSelected(true);

        udpCheck = new CheckBox("UDP");
        udpCheck.setStyle("-fx-font-size: 11px;");
        udpCheck.setSelected(false);

        filterBar.getChildren().addAll(filterLabel, tcpCheck, udpCheck);

        javafx.scene.layout.VBox topBox = new javafx.scene.layout.VBox();
        topBox.getChildren().addAll(topBar, filterBar);
        setTop(topBox);

        portTable = new TableView<>();
        portTable.setItems(portList);
        portTable.setStyle("-fx-font-size: 11px; -fx-background-color: #FFFFFF;");

        TableColumn<PortItem, String> protoCol = new TableColumn<>("协议");
        protoCol.setCellValueFactory(c -> c.getValue().protocolProperty());
        protoCol.setMinWidth(40);
        protoCol.setPrefWidth(60);

        TableColumn<PortItem, String> addrCol = new TableColumn<>("本地地址");
        addrCol.setCellValueFactory(c -> c.getValue().addressProperty());
        addrCol.setMinWidth(60);
        addrCol.setPrefWidth(140);

        TableColumn<PortItem, String> portCol = new TableColumn<>("端口");
        portCol.setCellValueFactory(c -> c.getValue().portProperty());
        portCol.setMinWidth(40);
        portCol.setPrefWidth(60);

        TableColumn<PortItem, String> pidCol = new TableColumn<>("PID");
        pidCol.setMinWidth(40);
        pidCol.setPrefWidth(70);

        TableColumn<PortItem, String> procCol = new TableColumn<>("进程");
        procCol.setCellValueFactory(c -> c.getValue().processProperty());
        procCol.setMinWidth(60);
        procCol.setPrefWidth(180);

        portTable.getColumns().addAll(protoCol, addrCol, portCol, pidCol, procCol);
        // 所有列压缩到可见范围内，确保全部列默认可见
        portTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // 右键菜单
        ContextMenu contextMenu = new ContextMenu();

        MenuItem stopItem = new MenuItem("停止");
        stopItem.setOnAction(e -> {
            PortItem item = portTable.getSelectionModel().getSelectedItem();
            if (item != null && !item.getPid().isEmpty() && !item.getPid().equals("-")) {
                stopProcess(item.getPid(), item.getProcess());
            }
        });

        MenuItem firewallItem = new MenuItem("加入防火墙白名单");
        firewallItem.setOnAction(e -> {
            PortItem item = portTable.getSelectionModel().getSelectedItem();
            if (item != null) {
                addToFirewall(item.getPort(), item.getProtocol());
            }
        });

        MenuItem copyItem = new MenuItem("复制端口");
        copyItem.setOnAction(e -> {
            PortItem item = portTable.getSelectionModel().getSelectedItem();
            if (item != null) {
                javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(item.getPort());
                clipboard.setContent(content);
            }
        });

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> new Thread(this::refresh, "Port-ManualRefresh").start());

        contextMenu.getItems().addAll(stopItem, firewallItem, new SeparatorMenuItem(), copyItem, refreshItem);

        portTable.setRowFactory(tv -> {
            TableRow<PortItem> row = new TableRow<>();
            // 选中行始终高亮（即使表格失去焦点，右键菜单弹出时仍可见）
            row.styleProperty().bind(javafx.beans.binding.Bindings.when(row.selectedProperty())
                    .then("-fx-background-color: #cfe8fc; -fx-text-fill: #000;")
                    .otherwise(""));
            row.emptyProperty().addListener((obs, wasEmpty, isNowEmpty) -> {
                if (isNowEmpty) {
                    row.setContextMenu(null);
                } else {
                    row.setContextMenu(contextMenu);
                    PortItem item = row.getItem();
                    // 无PID时禁用停止
                    stopItem.setDisable(item == null || item.getPid().isEmpty() || item.getPid().equals("-"));
                }
            });
            return row;
        });

        setCenter(portTable);

        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888; -fx-padding: 2 8; -fx-background-color: #f5f5f5;");
        setBottom(statusLabel);

        // 协议过滤变化时重新过滤
        tcpCheck.selectedProperty().addListener((obs, old, val) -> applyFilter());
        udpCheck.selectedProperty().addListener((obs, old, val) -> applyFilter());
    }

    /**
     * 根据协议复选框过滤端口列表
     */
    private void applyFilter() {
        boolean showTcp = tcpCheck.isSelected();
        boolean showUdp = udpCheck.isSelected();
        List<PortItem> filtered = new ArrayList<>();
        for (PortItem item : allPorts) {
            String proto = item.getProtocol().toLowerCase();
            if ((showTcp && proto.startsWith("tcp")) || (showUdp && proto.startsWith("udp"))) {
                filtered.add(item);
            }
        }
        portList.setAll(filtered);
        statusLabel.setText("共 " + filtered.size() + " 个监听端口（总计 " + allPorts.size() + "）");
    }

    /**
     * 端口项数据模型
     */
    public static class PortItem {
        private final StringProperty protocol = new SimpleStringProperty("");
        private final StringProperty address = new SimpleStringProperty("");
        private final StringProperty port = new SimpleStringProperty("");
        private final StringProperty pid = new SimpleStringProperty("");
        private final StringProperty process = new SimpleStringProperty("");

        public PortItem(String protocol, String address, String port, String pid, String process) {
            this.protocol.set(protocol);
            this.address.set(address);
            this.port.set(port);
            this.pid.set(pid);
            this.process.set(process);
        }

        public StringProperty protocolProperty() { return protocol; }
        public StringProperty addressProperty() { return address; }
        public StringProperty portProperty() { return port; }
        public StringProperty pidProperty() { return pid; }
        public StringProperty processProperty() { return process; }

        public String getProtocol() { return protocol.get(); }
        public String getAddress() { return address.get(); }
        public String getPort() { return port.get(); }
        public String getPid() { return pid.get(); }
        public String getProcess() { return process.get(); }
    }

    public void startMonitoring() {
        if (timer != null) {
            timer.cancel();
        }
        timer = new Timer("Port-Timer", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                refresh();
            }
        }, 0, 3000);
    }

    public void stopMonitoring() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /**
     * 手动刷新（在后台线程中执行，避免阻塞 UI）
     */
    public void refresh() {
        if (sshSession == null || !sshSession.isConnected()) {
            return;
        }
        try {
            // 优先使用 ss（-H 去表头，-l 监听，-n 数字，-p 进程，TCP+UDP 均显示保留 Netid 列）
            String output = executeCommand("ss -lnpH 2>/dev/null");
            List<PortItem> items = parsePorts(output);
            // ss 无输出则回退 netstat
            if (items.isEmpty()) {
                output = executeCommand("netstat -tlnp 2>/dev/null");
                items = parsePorts(output);
            }
            List<PortItem> finalItems = items;
            Platform.runLater(() -> {
                allPorts.clear();
                allPorts.addAll(finalItems);
                applyFilter();
            });
        } catch (Exception e) {
            Platform.runLater(() -> statusLabel.setText("获取端口失败: " + e.getMessage()));
        }
    }

    /**
     * 停止进程
     */
    private void stopProcess(String pid, String processName) {
        new Thread(() -> {
            try {
                String result = executeCommand("kill -9 " + pid + " 2>&1; echo EXIT:$?");
                Platform.runLater(() -> {
                    statusLabel.setText("已发送停止命令: " + processName + "(PID:" + pid + ")");
                    refresh();
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("停止失败: " + e.getMessage()));
            }
        }, "Port-Stop").start();
    }

    /**
     * 加入防火墙白名单
     * 尝试 firewall-cmd（firewalld），失败则尝试 iptables
     */
    private void addToFirewall(String port, String protocol) {
        new Thread(() -> {
            try {
                String proto = protocol.toLowerCase().startsWith("udp") ? "udp" : "tcp";
                String cmd = "firewall-cmd --add-port=" + port + "/" + proto + " --permanent 2>/dev/null && " +
                        "firewall-cmd --reload 2>/dev/null; echo EXIT:$?";
                String result = executeCommand(cmd);
                String msg;
                if (result.contains("EXIT:0")) {
                    msg = "已将端口 " + port + "/" + proto + " 加入防火墙白名单";
                } else {
                    // 回退 iptables
                    String iptResult = executeCommand("iptables -I INPUT -p " + proto + " --dport " + port + " -j ACCEPT 2>&1; echo EXIT:$?");
                    msg = iptResult.contains("EXIT:0") ? "已通过 iptables 放行端口 " + port + "/" + proto :
                            "放行失败，可能需要 root 权限或防火墙未安装";
                }
                String finalMsg = msg;
                Platform.runLater(() -> statusLabel.setText(finalMsg));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("防火墙操作失败: " + e.getMessage()));
            }
        }, "Port-Firewall").start();
    }

    /**
     * 解析端口列表输出
     * 兼容 ss -lnpH 和 netstat -tlnp 格式
     */
    private List<PortItem> parsePorts(String output) {
        List<PortItem> items = new ArrayList<>();
        if (output == null || output.trim().isEmpty()) {
            return items;
        }
        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // 跳过表头行
            if (line.startsWith("Netid") || line.startsWith("Proto") ||
                line.startsWith("Active") || line.startsWith("State")) {
                continue;
            }
            PortItem item = parseLine(line);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * 解析单行，自动识别 ss 或 netstat 格式
     *
     * ss -lnpH 格式:
     *   tcp  LISTEN  0  128  0.0.0.0:22  0.0.0.0:*  users:(("sshd",pid=928,fd=3))
     *   tcp  LISTEN  0  128  [::]:22     [::]:*     users:(("sshd",pid=928,fd=4))
     *
     * netstat -tlnp 格式:
     *   tcp   0  0  0.0.0.0:22  0.0.0.0:*  LISTEN  928/sshd
     *   tcp6  0  0  :::22       :::*       LISTEN  928/sshd
     */
    private PortItem parseLine(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length < 4) return null;

        // 判断格式：ss 行的第二列是状态(LISTEN/UNCONN)，netstat 行的第一列是协议(tcp/tcp6/udp)
        boolean isSs = parts[1].equalsIgnoreCase("LISTEN") || parts[1].equalsIgnoreCase("UNCONN");

        String protocol;
        String localAddr;
        String pid = "-";
        String process = "-";

        if (isSs) {
            // ss 格式: Netid State Recv-Q Send-Q Local-Addr Peer-Addr Process
            protocol = parts[0];
            localAddr = parts.length > 4 ? parts[4] : "";
            // 解析进程 users:(("sshd",pid=928,fd=3))
            for (String part : parts) {
                if (part.contains("pid=")) {
                    int pidIdx = part.indexOf("pid=");
                    String pidStr = part.substring(pidIdx + 4).replaceAll("[^0-9].*", "");
                    if (!pidStr.isEmpty()) pid = pidStr;
                    int nameIdx = part.indexOf("((\"");
                    if (nameIdx >= 0) {
                        int nameEnd = part.indexOf("\"", nameIdx + 3);
                        if (nameEnd > nameIdx) process = part.substring(nameIdx + 3, nameEnd);
                    }
                }
            }
        } else {
            // netstat 格式: Proto Recv-Q Send-Q Local-Addr Foreign-Addr State PID/Program
            protocol = parts[0];
            localAddr = parts.length > 3 ? parts[3] : "";
            // PID/Program 在最后一列，格式: 928/sshd
            String lastPart = parts[parts.length - 1];
            int slashIdx = lastPart.indexOf("/");
            if (slashIdx > 0) {
                String pidStr = lastPart.substring(0, slashIdx);
                if (pidStr.matches("\\d+")) {
                    pid = pidStr;
                    process = lastPart.substring(slashIdx + 1);
                }
            }
        }

        if (localAddr.isEmpty()) return null;

        // 拆分地址和端口（取最后一个冒号）
        int colonIdx = localAddr.lastIndexOf(":");
        if (colonIdx < 0) return null;
        String address = localAddr.substring(0, colonIdx);
        String port = localAddr.substring(colonIdx + 1);

        // 端口必须是数字
        if (!port.matches("\\d+")) return null;

        // 规范化协议名
        if (protocol.startsWith("tcp")) protocol = "tcp";
        else if (protocol.startsWith("udp")) protocol = "udp";

        return new PortItem(protocol, address, port, pid, process);
    }

    private String executeCommand(String command) throws Exception {
        com.jcraft.jsch.ChannelExec channel = (com.jcraft.jsch.ChannelExec) sshSession.getJschSession().openChannel("exec");
        channel.setCommand(command);
        InputStream in = channel.getInputStream();
        InputStream err = channel.getErrStream();
        channel.connect();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String ln;
        while ((ln = reader.readLine()) != null) {
            sb.append(ln).append("\n");
        }

        BufferedReader errReader = new BufferedReader(new InputStreamReader(err));
        while ((ln = errReader.readLine()) != null) {
            sb.append(ln).append("\n");
        }

        channel.disconnect();
        return sb.toString();
    }
}
