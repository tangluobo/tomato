package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 端口视图面板
 * 列出服务器当前监听的端口，支持停止进程、加入防火墙白名单等操作
 */
public class PortPanel extends BorderPane {

    private static final String ROW_SELECTOR_COL = "__ROW_SELECTOR__";

    private final SSHSession sshSession;

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

        Button refreshBtn = new Button();
        refreshBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
        javafx.scene.image.ImageView refreshIcon = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(getClass().getResourceAsStream("/images/connect/refresh.png")));
        refreshIcon.setFitWidth(16);
        refreshIcon.setFitHeight(16);
        refreshBtn.setGraphic(refreshIcon);
        refreshBtn.setTooltip(new javafx.scene.control.Tooltip("刷新"));
        refreshBtn.setOnAction(e -> {
            refreshBtn.setDisable(true);
            new Thread(() -> {
                try {
                    refresh();
                } finally {
                    Platform.runLater(() -> refreshBtn.setDisable(false));
                }
            }, "Port-ManualRefresh").start();
        });

        topBar.getChildren().addAll(titleLabel, spacer, refreshBtn);

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
        // 使用与应用内其他表格相同的样式表（connect-tree.css），选中行样式一致且失焦不消失
        portTable.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        // 支持多选（行选择器可选中多行）
        portTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // 行选择器列（参考 TableDataView 的实现）
        TableColumn<PortItem, String> selectorCol = new TableColumn<>();
        selectorCol.setPrefWidth(15);
        selectorCol.setMaxWidth(15);
        selectorCol.setMinWidth(15);
        selectorCol.setSortable(false);
        selectorCol.setReorderable(false);
        selectorCol.setResizable(false);
        selectorCol.setStyle("-fx-alignment: CENTER;");
        selectorCol.setUserData(ROW_SELECTOR_COL);
        selectorCol.setCellFactory(col -> new TableCell<>() {
            private final Polygon arrow = new Polygon(0, -0.5, 5, 4.5, 0, 9.5);
            private javafx.beans.InvalidationListener selectionListener;

            {
                arrow.setFill(Color.BLACK);
                setGraphic(arrow);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER);
                arrow.setVisible(false);
                setStyle("-fx-border-color: transparent #BEBEBC transparent #BEBEBC; -fx-border-width: 0 1 0 1;");
                // 点击行选择器列时选中整行（Ctrl/Shift 支持多选）
                addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        int row = getTableRow().getIndex();
                        if (event.isControlDown()) {
                            if (isRowSelected(row)) {
                                portTable.getSelectionModel().clearSelection(row);
                            } else {
                                portTable.getSelectionModel().select(row);
                            }
                        } else if (event.isShiftDown()) {
                            int anchor = portTable.getSelectionModel().getFocusedIndex();
                            if (anchor >= 0) {
                                int start = Math.min(row, anchor);
                                int end = Math.max(row, anchor);
                                portTable.getSelectionModel().clearSelection();
                                portTable.getSelectionModel().selectRange(start, end + 1);
                            } else {
                                portTable.getSelectionModel().clearSelection();
                                portTable.getSelectionModel().select(row);
                            }
                        } else {
                            portTable.getSelectionModel().clearSelection();
                            portTable.getSelectionModel().select(row);
                        }
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                if (selectionListener != null) {
                    portTable.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                    selectionListener = null;
                }
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    arrow.setVisible(false);
                    setStyle("-fx-border-color: transparent; -fx-border-width: 0;");
                    return;
                }
                setStyle("-fx-border-color: transparent #BEBEBC #BEBEBC #BEBEBC; -fx-border-width: 0 1 1 1;");
                arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                selectionListener = obs -> {
                    if (getTableRow() != null) {
                        arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                    }
                };
                portTable.getSelectionModel().getSelectedCells().addListener(selectionListener);
            }
        });
        portTable.getColumns().add(selectorCol);

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
            List<PortItem> selected = portTable.getSelectionModel().getSelectedItems();
            for (PortItem item : selected) {
                if (item != null && !item.getPid().isEmpty() && !item.getPid().equals("-")) {
                    stopProcess(item.getPid(), item.getProcess());
                }
            }
        });

        MenuItem firewallItem = new MenuItem("加入防火墙白名单");
        firewallItem.setOnAction(e -> {
            List<PortItem> selected = portTable.getSelectionModel().getSelectedItems();
            for (PortItem item : selected) {
                if (item != null) {
                    addToFirewall(item.getPort(), item.getProtocol());
                }
            }
        });

        MenuItem copyItem = new MenuItem("复制端口");
        copyItem.setOnAction(e -> {
            List<PortItem> selected = portTable.getSelectionModel().getSelectedItems();
            if (!selected.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (PortItem item : selected) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(item.getPort());
                }
                javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(sb.toString());
                clipboard.setContent(content);
            }
        });

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> new Thread(this::refresh, "Port-ManualRefresh").start());

        contextMenu.getItems().addAll(stopItem, firewallItem, new SeparatorMenuItem(), copyItem, refreshItem);

        portTable.setRowFactory(tv -> {
            TableRow<PortItem> row = new TableRow<>();
            // 右键时保持已选中行不变：若右键点击的行已选中，不改变选择；否则只选中该行
            row.setOnMousePressed(event -> {
                if (event.getButton() == MouseButton.SECONDARY && !row.isEmpty()) {
                    int index = row.getIndex();
                    if (!portTable.getSelectionModel().isSelected(index)) {
                        portTable.getSelectionModel().clearSelection();
                        portTable.getSelectionModel().select(index);
                    }
                    updateMenuState(stopItem, firewallItem, copyItem);
                    contextMenu.show(row, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
            return row;
        });

        // 点击空白区域时隐藏右键菜单并清除选择
        portTable.setOnMousePressed(event -> {
            if (isClickOnEmptyArea(event.getPickResult().getIntersectedNode())) {
                contextMenu.hide();
                portTable.getSelectionModel().clearSelection();
            }
        });

        // 键盘 ContextMenu 键或右键空白区域
        portTable.setOnContextMenuRequested(event -> {
            if (isClickOnEmptyArea(event.getPickResult().getIntersectedNode())) {
                contextMenu.hide();
                event.consume();
                return;
            }
            if (portTable.getSelectionModel().getSelectedItems().isEmpty()) {
                event.consume();
                return;
            }
            updateMenuState(stopItem, firewallItem, copyItem);
            contextMenu.show(portTable, event.getScreenX(), event.getScreenY());
            event.consume();
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
     * 判断指定行是否处于选中状态
     */
    private boolean isRowSelected(int rowIndex) {
        for (TablePosition<?, ?> pos : portTable.getSelectionModel().getSelectedCells()) {
            if (pos.getRow() == rowIndex) return true;
        }
        return false;
    }

    /**
     * 判断点击位置是否在表格空白区域（空行或表格背景）
     */
    private boolean isClickOnEmptyArea(Node node) {
        while (node != null && node != portTable) {
            if (node instanceof TableRow) {
                return ((TableRow<?>) node).isEmpty();
            }
            node = node.getParent();
        }
        return true;
    }

    /**
     * 根据当前选中项更新右键菜单状态（禁用/文本）
     */
    private void updateMenuState(MenuItem stopItem, MenuItem firewallItem, MenuItem copyItem) {
        List<PortItem> selected = portTable.getSelectionModel().getSelectedItems();
        boolean hasStoppable = false;
        for (PortItem item : selected) {
            if (item != null && !item.getPid().isEmpty() && !item.getPid().equals("-")) {
                hasStoppable = true;
                break;
            }
        }
        stopItem.setDisable(!hasStoppable);
        int count = selected.size();
        stopItem.setText("停止" + (count > 1 ? "(" + count + "个)" : ""));
        firewallItem.setText("加入防火墙白名单" + (count > 1 ? "(" + count + "个)" : ""));
        copyItem.setText("复制端口" + (count > 1 ? "(" + count + "个)" : ""));
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

    /**
     * 刷新端口列表（在后台线程中执行，避免阻塞 UI）
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
