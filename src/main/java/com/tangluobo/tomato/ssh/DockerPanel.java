package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 容器管理面板
 * 列出远程服务器上的 Docker 容器，支持启动/停止/重启/删除/查看日志等操作
 * 结构与 PortPanel 一致：顶部工具栏 + 容器表格 + 右键菜单 + 底部状态栏
 */
public class DockerPanel extends BorderPane {

    private final SSHSession sshSession;
    private final TableView<ContainerItem> containerTable;
    private final ObservableList<ContainerItem> containerList = FXCollections.observableArrayList();
    private final Label statusLabel;

    public DockerPanel(SSHSession sshSession) {
        this.sshSession = sshSession;

        setStyle("-fx-background-color: #FFFFFF;");
        setMinHeight(200);

        HBox topBar = new HBox();
        topBar.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 4 8; -fx-alignment: center-left;");

        Label titleLabel = new Label("Docker 容器");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshBtn = new Button();
        refreshBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
        ImageView refreshIcon = new ImageView(
                new Image(getClass().getResourceAsStream("/images/connect/refresh.png")));
        refreshIcon.setFitWidth(16);
        refreshIcon.setFitHeight(16);
        refreshBtn.setGraphic(refreshIcon);
        refreshBtn.setTooltip(new Tooltip("刷新"));
        refreshBtn.setOnAction(e -> {
            refreshBtn.setDisable(true);
            new Thread(() -> {
                try {
                    refresh();
                } finally {
                    Platform.runLater(() -> refreshBtn.setDisable(false));
                }
            }, "Docker-ManualRefresh").start();
        });

        topBar.getChildren().addAll(titleLabel, spacer, refreshBtn);
        setTop(topBar);

        containerTable = new TableView<>();
        containerTable.setItems(containerList);
        containerTable.setStyle("-fx-font-size: 11px; -fx-background-color: #FFFFFF;");
        containerTable.setFixedCellSize(24);
        // 高度随内容增长，不产生内部滚动条，由外层 rightPanelScroll 整体滚动
        containerTable.prefHeightProperty().bind(
                javafx.beans.binding.Bindings.size(containerList).multiply(24).add(30));
        containerTable.setMinHeight(80);
        containerTable.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        containerTable.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);

        // 容器ID列
        TableColumn<ContainerItem, String> idCol = new TableColumn<>("容器ID");
        idCol.setCellValueFactory(c -> c.getValue().idProperty());
        idCol.setMinWidth(70);
        idCol.setPrefWidth(90);

        // 镜像列
        TableColumn<ContainerItem, String> imageCol = new TableColumn<>("镜像");
        imageCol.setCellValueFactory(c -> c.getValue().imageProperty());
        imageCol.setMinWidth(80);
        imageCol.setPrefWidth(130);

        // 名称列
        TableColumn<ContainerItem, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        nameCol.setMinWidth(60);
        nameCol.setPrefWidth(110);

        // 状态列：状态前显示颜色圆点（运行绿色/停止灰色/其他黄色）
        TableColumn<ContainerItem, String> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setMinWidth(60);
        statusCol.setPrefWidth(100);
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final Circle dot = new Circle(3);
            private final Label statusLabel = new Label();
            private final HBox box = new HBox(4, dot, statusLabel);
            {
                box.setAlignment(Pos.CENTER_LEFT);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    statusLabel.setText(item);
                    String lower = item.toLowerCase();
                    if (lower.startsWith("up")) {
                        dot.setFill(Color.valueOf("#4CAF50"));
                    } else if (lower.startsWith("exited")) {
                        dot.setFill(Color.valueOf("#999999"));
                    } else if (lower.startsWith("created")) {
                        dot.setFill(Color.valueOf("#FFA726"));
                    } else {
                        dot.setFill(Color.valueOf("#999999"));
                    }
                    setGraphic(box);
                }
            }
        });

        // 端口列
        TableColumn<ContainerItem, String> portsCol = new TableColumn<>("端口");
        portsCol.setCellValueFactory(c -> c.getValue().portsProperty());
        portsCol.setMinWidth(60);
        portsCol.setPrefWidth(140);

        // 创建时间列
        TableColumn<ContainerItem, String> createdCol = new TableColumn<>("创建时间");
        createdCol.setCellValueFactory(c -> c.getValue().createdProperty());
        createdCol.setMinWidth(80);
        createdCol.setPrefWidth(120);

        containerTable.getColumns().addAll(idCol, imageCol, nameCol, statusCol, portsCol, createdCol);
        containerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        setCenter(containerTable);

        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888; -fx-padding: 2 8; -fx-background-color: #f5f5f5;");
        setBottom(statusLabel);

        // 右键菜单
        ContextMenu contextMenu = new ContextMenu();

        MenuItem startItem = new MenuItem("启动");
        startItem.setOnAction(e -> execForSelected("start", "启动"));

        MenuItem stopItem = new MenuItem("停止");
        stopItem.setOnAction(e -> execForSelected("stop", "停止"));

        MenuItem restartItem = new MenuItem("重启");
        restartItem.setOnAction(e -> execForSelected("restart", "重启"));

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> execForSelected("rm -f", "删除"));

        MenuItem logItem = new MenuItem("查看日志");
        logItem.setOnAction(e -> {
            List<ContainerItem> selected = containerTable.getSelectionModel().getSelectedItems();
            if (selected.size() == 1 && selected.get(0) != null) {
                showLogs(selected.get(0));
            }
        });

        MenuItem copyIdItem = new MenuItem("复制容器ID");
        copyIdItem.setOnAction(e -> {
            List<ContainerItem> selected = containerTable.getSelectionModel().getSelectedItems();
            if (!selected.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ContainerItem item : selected) {
                    if (item != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(item.getId());
                    }
                }
                ClipboardContent content = new ClipboardContent();
                content.putString(sb.toString());
                Clipboard.getSystemClipboard().setContent(content);
                statusLabel.setText("已复制 " + selected.size() + " 个容器ID");
            }
        });

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> new Thread(this::refresh, "Docker-ManualRefresh").start());

        contextMenu.getItems().addAll(startItem, stopItem, restartItem, deleteItem,
                new SeparatorMenuItem(), logItem, copyIdItem,
                new SeparatorMenuItem(), refreshItem);
        contextMenu.setAutoHide(true);

        // 右键弹出菜单时更新菜单项状态
        containerTable.setOnContextMenuRequested(event -> {
            List<ContainerItem> selected = containerTable.getSelectionModel().getSelectedItems();
            int count = selected.size();
            boolean hasRunning = false;
            boolean hasStopped = false;
            for (ContainerItem item : selected) {
                if (item == null) continue;
                String lower = item.getStatus().toLowerCase();
                if (lower.startsWith("up")) hasRunning = true;
                else if (lower.startsWith("exited")) hasStopped = true;
            }
            startItem.setDisable(!hasStopped);
            stopItem.setDisable(!hasRunning);
            restartItem.setDisable(count == 0);
            deleteItem.setDisable(count == 0);
            logItem.setDisable(count != 1);
            copyIdItem.setDisable(count == 0);

            String suffix = count > 1 ? "(" + count + "个)" : "";
            startItem.setText("启动" + suffix);
            stopItem.setText("停止" + suffix);
            restartItem.setText("重启" + suffix);
            deleteItem.setText("删除" + suffix);
            copyIdItem.setText("复制容器ID" + suffix);

            if (count == 0) {
                contextMenu.hide();
                event.consume();
                return;
            }
            contextMenu.show(containerTable, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        // 左键点击时隐藏菜单
        containerTable.setOnMousePressed(event -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });
    }

    /**
     * 对选中容器执行 docker 命令（start/stop/restart/rm -f）
     */
    private void execForSelected(String action, String label) {
        List<ContainerItem> selected = containerTable.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) return;
        new Thread(() -> {
            try {
                StringBuilder names = new StringBuilder();
                for (ContainerItem item : selected) {
                    if (item == null) continue;
                    if (names.length() > 0) names.append(" ");
                    names.append(item.getName());
                }
                if (names.length() == 0) return;
                String cmd = "docker " + action + " " + names + " 2>&1; echo EXIT:$?";
                String result = executeCommand(cmd);
                boolean success = result != null && result.contains("EXIT:0");
                String msg = success ? label + "成功: " + names
                        : label + "失败: " + result.replaceAll("EXIT:\\d+", "").trim();
                Platform.runLater(() -> {
                    statusLabel.setText(msg);
                    refresh();
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText(label + "异常: " + e.getMessage()));
            }
        }, "Docker-" + label).start();
    }

    /**
     * 弹出新窗口显示容器日志（docker logs --tail 200）
     */
    private void showLogs(ContainerItem item) {
        new Thread(() -> {
            String logContent;
            try {
                logContent = executeCommand("docker logs --tail 200 " + item.getName() + " 2>&1");
                if (logContent == null || logContent.trim().isEmpty()) {
                    logContent = "（无日志输出）";
                }
            } catch (Exception e) {
                logContent = "获取日志失败: " + e.getMessage();
            }
            final String content = logContent;
            Platform.runLater(() -> {
                Stage stage = new Stage();
                stage.setTitle("Docker 日志 - " + item.getName());
                stage.setWidth(800);
                stage.setHeight(500);

                TextArea textArea = new TextArea(content);
                textArea.setEditable(false);
                textArea.setWrapText(true);
                textArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; "
                        + "-fx-text-fill: #e0e0e0; -fx-background-color: #1e1e1e; -fx-padding: 8;");

                VBox box = new VBox(textArea);
                VBox.setVgrow(textArea, Priority.ALWAYS);
                box.setPadding(new Insets(0));
                stage.setScene(new Scene(box));
                stage.getIcons().add(new Image(getClass().getResourceAsStream("/images/logo.png")));
                stage.show();
            });
        }, "Docker-Logs").start();
    }

    /**
     * 刷新容器列表（在后台线程中执行，避免阻塞 UI）
     * 使用 docker ps -a --format 输出容器信息，用 | 作为字段分隔符
     */
    public void refresh() {
        if (sshSession == null || !sshSession.isConnected()) {
            return;
        }
        try {
            // sudo -n 非交互式：有免密则用 root 权限执行 docker，无免密快速失败回退普通用户
            String cmd = "sudo -n docker ps -a --format '{{.ID}}|{{.Image}}|{{.Names}}|{{.Status}}|{{.Ports}}|{{.CreatedAt}}' 2>/dev/null "
                    + "|| docker ps -a --format '{{.ID}}|{{.Image}}|{{.Names}}|{{.Status}}|{{.Ports}}|{{.CreatedAt}}' 2>/dev/null";
            String output = executeCommand(cmd);
            List<ContainerItem> items = parseContainers(output);
            Platform.runLater(() -> {
                containerList.setAll(items);
                statusLabel.setText("共 " + items.size() + " 个容器");
                containerTable.refresh();
            });
        } catch (Exception e) {
            Platform.runLater(() -> statusLabel.setText("获取容器列表失败: " + e.getMessage()));
        }
    }

    /**
     * 解析 docker ps --format 输出，每行一个容器，字段用 | 分隔
     */
    private List<ContainerItem> parseContainers(String output) {
        List<ContainerItem> items = new ArrayList<>();
        if (output == null || output.trim().isEmpty()) {
            return items;
        }
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // 跳过错误信息（如 "Cannot connect to the Docker daemon" 或 "command not found"）
            String lower = line.toLowerCase();
            if (lower.contains("cannot connect") || lower.contains("not found")
                    || lower.contains("permission denied") || lower.contains("error")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 4) continue;
            String id = parts[0].trim();
            String image = parts.length > 1 ? parts[1].trim() : "";
            String name = parts.length > 2 ? parts[2].trim() : "";
            String status = parts.length > 3 ? parts[3].trim() : "";
            String ports = parts.length > 4 ? parts[4].trim() : "";
            String created = parts.length > 5 ? parts[5].trim() : "";
            if (id.isEmpty()) continue;
            items.add(new ContainerItem(id, image, name, status, ports, created));
        }
        return items;
    }

    /**
     * 通过 SSH exec 通道执行命令并返回输出
     */
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

    /**
     * 容器数据模型
     */
    public static class ContainerItem {
        private final StringProperty id = new SimpleStringProperty("");
        private final StringProperty image = new SimpleStringProperty("");
        private final StringProperty name = new SimpleStringProperty("");
        private final StringProperty status = new SimpleStringProperty("");
        private final StringProperty ports = new SimpleStringProperty("");
        private final StringProperty created = new SimpleStringProperty("");

        public ContainerItem(String id, String image, String name, String status, String ports, String created) {
            this.id.set(id);
            this.image.set(image);
            this.name.set(name);
            this.status.set(status);
            this.ports.set(ports);
            this.created.set(created);
        }

        public StringProperty idProperty() { return id; }
        public StringProperty imageProperty() { return image; }
        public StringProperty nameProperty() { return name; }
        public StringProperty statusProperty() { return status; }
        public StringProperty portsProperty() { return ports; }
        public StringProperty createdProperty() { return created; }

        public String getId() { return id.get(); }
        public String getImage() { return image.get(); }
        public String getName() { return name.get(); }
        public String getStatus() { return status.get(); }
        public String getPorts() { return ports.get(); }
        public String getCreated() { return created.get(); }
    }
}
