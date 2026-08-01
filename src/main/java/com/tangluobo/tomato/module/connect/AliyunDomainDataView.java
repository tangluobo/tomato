package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Map;

/**
 * 阿里云单个域名的子域名（解析记录）面板。
 * 双击树节点中的域名时直接展示该域名下的解析记录，不再重复展示一级域名列表。
 */
public class AliyunDomainDataView extends BorderPane {

    private final ConnectionConfig config;
    private final String domainName;

    private TableView<RecordItem> recordTable;
    private final ObservableList<RecordItem> recordItems = FXCollections.observableArrayList();
    private Label statusLabel;

    public AliyunDomainDataView(ConnectionConfig config, String domainName) {
        this.config = config;
        this.domainName = domainName;
        initializeUI();
        loadRecords();
    }

    private void initializeUI() {
        // 顶部工具栏
        ToolBar toolBar = new ToolBar();
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        Label titleLabel = new Label("子域名（解析记录）- " + domainName);
        titleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-font-size: 12px;");
        refreshBtn.setOnAction(e -> loadRecords());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolBar.getItems().addAll(titleLabel, spacer, refreshBtn, new Separator(), statusLabel);

        // 解析记录表格
        recordTable = new TableView<>();
        recordTable.setStyle("-fx-font-size: 12px;");
        recordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<RecordItem, String> rrCol = new TableColumn<>("主机记录");
        rrCol.setCellValueFactory(new PropertyValueFactory<>("rr"));
        rrCol.setPrefWidth(140);

        TableColumn<RecordItem, String> typeCol = new TableColumn<>("记录类型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeCol.setPrefWidth(90);

        TableColumn<RecordItem, String> valueCol = new TableColumn<>("记录值");
        valueCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(240);

        TableColumn<RecordItem, String> ttlCol = new TableColumn<>("TTL");
        ttlCol.setCellValueFactory(new PropertyValueFactory<>("ttl"));
        ttlCol.setPrefWidth(70);

        TableColumn<RecordItem, String> lineCol = new TableColumn<>("解析线路");
        lineCol.setCellValueFactory(new PropertyValueFactory<>("line"));
        lineCol.setPrefWidth(100);

        TableColumn<RecordItem, String> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(70);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(status);
                    String color = "Enable".equalsIgnoreCase(status) ? "#4CAF50" : "#9E9E9E";
                    setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold; -fx-font-size: 12px;");
                }
            }
        });

        recordTable.getColumns().addAll(rrCol, typeCol, valueCol, ttlCol, lineCol, statusCol);
        recordTable.setItems(recordItems);

        VBox centerBox = new VBox();
        centerBox.setPadding(new Insets(0));
        VBox.setVgrow(recordTable, Priority.ALWAYS);
        centerBox.getChildren().add(recordTable);

        setTop(toolBar);
        setCenter(centerBox);
    }

    /**
     * 加载当前域名下的解析记录（子域名）
     */
    private void loadRecords() {
        statusLabel.setText("加载中...");
        recordItems.clear();
        new Thread(() -> {
            try {
                List<Map<String, Object>> records = AliyunService.getDomainRecords(config, domainName);
                Platform.runLater(() -> {
                    for (Map<String, Object> record : records) {
                        String rr = String.valueOf(record.getOrDefault("rr", ""));
                        String type = String.valueOf(record.getOrDefault("type", ""));
                        String value = String.valueOf(record.getOrDefault("value", ""));
                        String ttl = String.valueOf(record.getOrDefault("ttl", ""));
                        String status = String.valueOf(record.getOrDefault("status", ""));
                        String priority = String.valueOf(record.getOrDefault("priority", ""));
                        String line = String.valueOf(record.getOrDefault("line", ""));
                        String recordId = String.valueOf(record.getOrDefault("recordId", ""));
                        recordItems.add(new RecordItem(recordId, rr, type, value, ttl, status, priority, line));
                    }
                    statusLabel.setText("共 " + records.size() + " 条");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("加载失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载子域名解析记录: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "Aliyun-LoadDomainRecords").start();
    }

    /**
     * 子域名（解析记录）表格数据项
     */
    public static class RecordItem {
        private final String recordId;
        private final String rr;
        private final String type;
        private final String value;
        private final String ttl;
        private final String status;
        private final String priority;
        private final String line;

        public RecordItem(String recordId, String rr, String type, String value,
                          String ttl, String status, String priority, String line) {
            this.recordId = recordId;
            this.rr = rr;
            this.type = type;
            this.value = value;
            this.ttl = ttl;
            this.status = status;
            this.priority = priority;
            this.line = line;
        }

        public String getRecordId() { return recordId; }
        public String getRr() { return rr; }
        public String getType() { return type; }
        public String getValue() { return value; }
        public String getTtl() { return ttl; }
        public String getStatus() { return status; }
        public String getPriority() { return priority; }
        public String getLine() { return line; }
    }
}
