package com.tangluobo.tomato.ssh;

import com.tangluobo.tomato.utils.RowSelectorDragSelection;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 容器管理面板
 * 列出远程服务器上的 Docker 容器，支持启动/停止/重启/删除/强制删除/查看日志等操作
 * 结构与 PortPanel 一致：顶部工具栏 + 容器表格（含行选择器列）+ 右键菜单 + 底部状态栏
 */
public class DockerPanel extends BorderPane {

    private static final String ROW_SELECTOR_COL = "__ROW_SELECTOR__";

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
        containerTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // 行选择器列（参考 PortPanel 实现）
        TableColumn<ContainerItem, String> selectorCol = new TableColumn<>();
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
                setStyle("-fx-border-color: transparent #e0e0e0 transparent #e0e0e0; -fx-border-width: 0 1 0 1;");
                // 点击行选择器列时选中整行（Ctrl/Shift 支持多选）
                final int[] dragStart = RowSelectorDragSelection.install(containerTable, this);
                addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    // 右键不处理选中逻辑，由 setOnContextMenuRequested 统一处理右键菜单
                    if (event.getButton() == MouseButton.SECONDARY) {
                        return;
                    }
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        int row = getTableRow().getIndex();
                        if (event.isControlDown()) {
                            dragStart[0] = -1;
                            if (isRowSelected(row)) {
                                containerTable.getSelectionModel().clearSelection(row);
                            } else {
                                containerTable.getSelectionModel().select(row);
                            }
                        } else if (event.isShiftDown()) {
                            dragStart[0] = -1;
                            int anchor = containerTable.getSelectionModel().getFocusedIndex();
                            if (anchor >= 0) {
                                int start = Math.min(row, anchor);
                                int end = Math.max(row, anchor);
                                containerTable.getSelectionModel().clearSelection();
                                containerTable.getSelectionModel().selectRange(start, end + 1);
                            } else {
                                containerTable.getSelectionModel().clearSelection();
                                containerTable.getSelectionModel().select(row);
                            }
                        } else {
                            containerTable.getSelectionModel().clearSelection();
                            containerTable.getSelectionModel().select(row);
                            dragStart[0] = row;
                        }
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                if (selectionListener != null) {
                    containerTable.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                    selectionListener = null;
                }
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    arrow.setVisible(false);
                    setStyle("-fx-border-color: transparent; -fx-border-width: 0;");
                    return;
                }
                setStyle("-fx-border-color: transparent #e0e0e0 #e0e0e0 #e0e0e0; -fx-border-width: 0 1 1 1;");
                arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                selectionListener = obs -> {
                    if (getTableRow() != null) {
                        arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                    }
                };
                containerTable.getSelectionModel().getSelectedCells().addListener(selectionListener);
            }
        });
        containerTable.getColumns().add(selectorCol);

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
        deleteItem.setOnAction(e -> execForSelected("rm", "删除"));

        MenuItem forceDeleteItem = new MenuItem("强制删除");
        forceDeleteItem.setOnAction(e -> execForSelected("rm -f", "强制删除"));

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

        contextMenu.getItems().addAll(startItem, stopItem, restartItem,
                new SeparatorMenuItem(), deleteItem, forceDeleteItem,
                new SeparatorMenuItem(), logItem, copyIdItem,
                new SeparatorMenuItem(), refreshItem);
        contextMenu.setAutoHide(true);

        // 行工厂：左键点击时隐藏已有菜单
        containerTable.setRowFactory(tv -> {
            TableRow<ContainerItem> row = new TableRow<>();
            row.setOnMousePressed(event -> {
                if (event.getButton() == MouseButton.PRIMARY && contextMenu.isShowing()) {
                    contextMenu.hide();
                }
            });
            return row;
        });

        // 统一在 DockerPanel 上处理右键菜单（ContextMenuEvent 在右键释放时触发）
        // consume() 阻止事件冒泡到 SSHTerminalPane，避免弹出终端的复制/粘贴菜单
        setOnContextMenuRequested(event -> {
            Node node = event.getPickResult().getIntersectedNode();
            Integer rowIndex = findRowIndex(node);
            if (rowIndex == null) {
                // 点击空白区域：不弹出菜单，消费事件阻止冒泡
                contextMenu.hide();
                event.consume();
                return;
            }
            // 右键点击的行未选中时，清除多选并只选中该行
            if (!containerTable.getSelectionModel().isSelected(rowIndex)) {
                containerTable.getSelectionModel().clearSelection();
                containerTable.getSelectionModel().select(rowIndex);
            }
            updateMenuState(startItem, stopItem, restartItem, deleteItem, forceDeleteItem, logItem, copyIdItem);
            contextMenu.show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        // 左键点击空白区域清除选择
        containerTable.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY && contextMenu.isShowing()) {
                contextMenu.hide();
            }
            if (event.getButton() == MouseButton.PRIMARY
                    && isClickOnEmptyArea(event.getPickResult().getIntersectedNode())) {
                containerTable.getSelectionModel().clearSelection();
            }
        });
    }

    /**
     * 从点击的节点向上查找所属 TableRow 的索引
     * @return 行索引，点击空白区域返回 null
     */
    private Integer findRowIndex(Node node) {
        while (node != null && node != containerTable) {
            if (node instanceof TableRow) {
                TableRow<?> row = (TableRow<?>) node;
                return row.isEmpty() ? null : row.getIndex();
            }
            node = node.getParent();
        }
        return null;
    }

    /**
     * 判断指定行是否处于选中状态
     */
    private boolean isRowSelected(int rowIndex) {
        for (TablePosition<?, ?> pos : containerTable.getSelectionModel().getSelectedCells()) {
            if (pos.getRow() == rowIndex) return true;
        }
        return false;
    }

    /**
     * 判断点击位置是否在表格空白区域（空行或表格背景）
     */
    private boolean isClickOnEmptyArea(Node node) {
        while (node != null && node != containerTable) {
            if (node instanceof TableRow) {
                return ((TableRow<?>) node).isEmpty();
            }
            node = node.getParent();
        }
        return true;
    }

    /**
     * 根据当前选中项更新右键菜单状态（禁用/文本）
     * - 启动：选中有已停止容器才可用
     * - 停止：选中有运行中容器才可用
     * - 重启：选中任何容器都可用
     * - 删除：选中容器中无运行中的才可用（docker rm 不能删除运行中的容器）
     * - 强制删除：选中任何容器都可用（docker rm -f 会先停止再删除）
     * - 查看日志：只选中1个才可用
     * - 复制容器ID：选中任何容器都可用
     */
    private void updateMenuState(MenuItem startItem, MenuItem stopItem, MenuItem restartItem,
                                  MenuItem deleteItem, MenuItem forceDeleteItem,
                                  MenuItem logItem, MenuItem copyIdItem) {
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
        String suffix = count > 1 ? "(" + count + "个)" : "";
        startItem.setText("启动" + suffix);
        startItem.setDisable(!hasStopped);
        stopItem.setText("停止" + suffix);
        stopItem.setDisable(!hasRunning);
        restartItem.setText("重启" + suffix);
        restartItem.setDisable(count == 0);
        // 删除：有运行中的容器时不可用（灰色），docker rm 不能删除运行中的容器
        deleteItem.setText("删除" + suffix);
        deleteItem.setDisable(hasRunning || count == 0);
        // 强制删除：始终可用，docker rm -f 会先停止再删除
        forceDeleteItem.setText("强制删除" + suffix);
        forceDeleteItem.setDisable(count == 0);
        logItem.setDisable(count != 1);
        copyIdItem.setText("复制容器ID" + suffix);
        copyIdItem.setDisable(count == 0);
    }

    /**
     * 对选中容器执行 docker 命令（start/stop/restart/rm/rm -f）
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
