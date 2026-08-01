package com.tangluobo.tomato.module.connect;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 阿里云单个域名的子域名（解析记录）面板。
 * 支持查看、添加、修改、删除解析记录，以及对 A 记录开启 DDNS（由全局 DdnsService 持久化与调度）。
 */
public class AliyunDomainDataView extends BorderPane {

    private static final String[] RECORD_TYPES = {
        "A", "AAAA", "CNAME", "MX", "TXT", "NS", "SRV", "CAA", "PTR", "显性URL", "隐性URL"
    };
    private static final String[] LINES = {
        "default", "telecom", "unicom", "mobile", "oversea", "hk"
    };

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
        ToolBar toolBar = new ToolBar();
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        Label titleLabel = new Label("子域名（解析记录）- " + domainName);
        titleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        Button addBtn = new Button("添加");
        addBtn.setStyle("-fx-font-size: 12px;");
        addBtn.setOnAction(e -> showRecordDialog(null));

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-font-size: 12px;");
        refreshBtn.setOnAction(e -> loadRecords());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolBar.getItems().addAll(titleLabel, spacer, addBtn, refreshBtn, new Separator(), statusLabel);

        recordTable = new TableView<>();
        recordTable.setStyle("-fx-font-size: 12px;");
        recordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<RecordItem, String> rrCol = new TableColumn<>("主机记录");
        rrCol.setCellValueFactory(new PropertyValueFactory<>("rr"));
        rrCol.setPrefWidth(120);

        TableColumn<RecordItem, String> typeCol = new TableColumn<>("记录类型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeCol.setPrefWidth(70);

        TableColumn<RecordItem, String> valueCol = new TableColumn<>("记录值");
        valueCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(200);

        TableColumn<RecordItem, String> ttlCol = new TableColumn<>("TTL");
        ttlCol.setCellValueFactory(new PropertyValueFactory<>("ttl"));
        ttlCol.setPrefWidth(55);

        TableColumn<RecordItem, String> lineCol = new TableColumn<>("解析线路");
        lineCol.setCellValueFactory(new PropertyValueFactory<>("line"));
        lineCol.setPrefWidth(80);

        TableColumn<RecordItem, String> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(55);
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

        // DDNS 开关列（仅 A 记录可开启，状态由全局 DdnsService 管理）
        TableColumn<RecordItem, Boolean> ddnsCol = new TableColumn<>("DDNS");
        ddnsCol.setPrefWidth(60);
        ddnsCol.setCellFactory(col -> new TableCell<>() {
            private final Switch sw = new Switch();
            {
                sw.setOnToggle(() -> {
                    TableRow<?> row = getTableRow();
                    if (row == null || row.getItem() == null) return;
                    RecordItem item = (RecordItem) row.getItem();
                    if (sw.isSelected()) enableDdns(item);
                    else disableDdns(item);
                });
            }
            @Override
            protected void updateItem(Boolean v, boolean empty) {
                super.updateItem(v, empty);
                TableRow<?> row = getTableRow();
                if (empty || row == null || row.getItem() == null) {
                    setGraphic(null);
                    return;
                }
                RecordItem item = (RecordItem) row.getItem();
                boolean isA = "A".equalsIgnoreCase(item.getType());
                sw.setDisable(!isA);
                sw.syncSelected(DdnsService.getInstance().isEnabled(config.getId(), item.getRecordId()));
                setGraphic(sw);
            }
        });

        // 操作列：修改 / 删除
        TableColumn<RecordItem, Void> actionCol = new TableColumn<>("操作");
        actionCol.setPrefWidth(120);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button editBtn = new Button("修改");
            private final Button delBtn = new Button("删除");
            private final HBox box = new HBox(4, editBtn, delBtn);
            {
                editBtn.setStyle("-fx-font-size: 11px;");
                delBtn.setStyle("-fx-font-size: 11px; -fx-text-fill: #F44336;");
                editBtn.setOnAction(e -> {
                    TableRow<?> row = getTableRow();
                    if (row != null && row.getItem() != null) showRecordDialog((RecordItem) row.getItem());
                });
                delBtn.setOnAction(e -> {
                    TableRow<?> row = getTableRow();
                    if (row != null && row.getItem() != null) confirmDelete((RecordItem) row.getItem());
                });
                box.setAlignment(Pos.CENTER);
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });

        recordTable.getColumns().addAll(rrCol, typeCol, valueCol, ttlCol, lineCol, statusCol, ddnsCol, actionCol);
        recordTable.setItems(recordItems);

        VBox centerBox = new VBox();
        VBox.setVgrow(recordTable, Priority.ALWAYS);
        centerBox.getChildren().add(recordTable);

        setTop(toolBar);
        setCenter(centerBox);
    }

    private void loadRecords() {
        statusLabel.setText("加载中...");
        recordItems.clear();
        new Thread(() -> {
            try {
                List<Map<String, Object>> records = AliyunService.getDomainRecords(config, domainName);
                Platform.runLater(() -> {
                    for (Map<String, Object> record : records) {
                        recordItems.add(new RecordItem(
                                String.valueOf(record.getOrDefault("recordId", "")),
                                String.valueOf(record.getOrDefault("rr", "")),
                                String.valueOf(record.getOrDefault("type", "")),
                                String.valueOf(record.getOrDefault("value", "")),
                                String.valueOf(record.getOrDefault("ttl", "")),
                                String.valueOf(record.getOrDefault("status", "")),
                                String.valueOf(record.getOrDefault("priority", "")),
                                String.valueOf(record.getOrDefault("line", ""))));
                    }
                    statusLabel.setText("共 " + records.size() + " 条");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("加载失败");
                    errorAlert("无法加载子域名解析记录", e);
                });
            }
        }, "Aliyun-LoadDomainRecords").start();
    }

    // ==================== DDNS（委托全局 DdnsService） ====================

    private void enableDdns(RecordItem item) {
        // 持久化到全局服务，应用启动后即使不打开本面板也会自动更新
        DdnsService.getInstance().enable(config, domainName, item.getRecordId(),
                item.getRr(), item.getType(), item.getTtl(), item.getLine());
        // 立即获取公网IP并更新一次，给用户即时反馈
        statusLabel.setText("DDNS 开启中，获取公网IP...");
        new Thread(() -> {
            String ip;
            try {
                ip = DdnsService.getInstance().fetchPublicIp();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("DDNS: 获取公网IP失败");
                    errorAlert("获取公网IP失败", e);
                });
                return;
            }
            final String finalIp = ip;
            try {
                updateRecordValue(item, finalIp);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("DDNS: 更新解析记录失败");
                    errorAlert("更新解析记录失败\n记录: " + item.getRr() + "." + domainName
                            + " (id=" + item.getRecordId() + ")", e);
                });
                return;
            }
            Platform.runLater(() -> {
                statusLabel.setText("DDNS 已更新 " + item.getRr() + " → " + finalIp);
                loadRecords();
            });
        }, "DDNS-Init").start();
    }

    private void disableDdns(RecordItem item) {
        DdnsService.getInstance().disable(item.getRecordId());
        statusLabel.setText("已关闭 " + item.getRr() + " 的 DDNS");
    }

    private void updateRecordValue(RecordItem item, String newValue) throws Exception {
        AliyunService.updateDomainRecord(config, item.getRecordId(),
                item.getRr(), item.getType(), newValue,
                parseLongSafe(item.getTtl()),
                (item.getLine() == null || item.getLine().isEmpty()) ? null : item.getLine(),
                null);
    }

    private static Long parseLongSafe(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    // ==================== 增删改 ====================

    private void showRecordDialog(RecordItem record) {
        boolean isEdit = record != null;
        Dialog<RecordForm> dialog = new Dialog<>();
        dialog.setTitle(isEdit ? "修改解析记录" : "添加解析记录");
        dialog.setHeaderText((isEdit ? "修改" : "添加") + " " + domainName + " 的解析记录");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        TextField rrField = new TextField(isEdit ? record.getRr() : "");
        rrField.setPromptText("如 www、@、*");

        ComboBox<String> typeBox = new ComboBox<>(FXCollections.observableArrayList(RECORD_TYPES));
        typeBox.setValue(isEdit ? record.getType() : "A");
        typeBox.setEditable(false);

        TextField valueField = new TextField(isEdit ? record.getValue() : "");
        valueField.setPromptText("记录值，如 IP 或域名");

        TextField ttlField = new TextField(isEdit ? record.getTtl() : "600");
        ttlField.setPromptText("默认 600");

        ComboBox<String> lineBox = new ComboBox<>(FXCollections.observableArrayList(LINES));
        lineBox.setValue(isEdit && record.getLine() != null && !record.getLine().isEmpty()
                ? record.getLine() : "default");

        TextField priorityField = new TextField(isEdit ? record.getPriority() : "");
        priorityField.setPromptText("MX 记录需填，如 10");

        grid.add(new Label("主机记录:"), 0, 0); grid.add(rrField, 1, 0);
        grid.add(new Label("记录类型:"), 0, 1); grid.add(typeBox, 1, 1);
        grid.add(new Label("记录值:"), 0, 2); grid.add(valueField, 1, 2);
        grid.add(new Label("TTL:"), 0, 3); grid.add(ttlField, 1, 3);
        grid.add(new Label("解析线路:"), 0, 4); grid.add(lineBox, 1, 4);
        grid.add(new Label("优先级:"), 0, 5); grid.add(priorityField, 1, 5);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
        Runnable validator = () -> {
            boolean ok = !rrField.getText().trim().isEmpty()
                    && !valueField.getText().trim().isEmpty()
                    && typeBox.getValue() != null;
            dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(!ok);
        };
        rrField.textProperty().addListener((o, a, b) -> validator.run());
        valueField.textProperty().addListener((o, a, b) -> validator.run());
        typeBox.valueProperty().addListener((o, a, b) -> validator.run());
        validator.run();

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return new RecordForm(rrField.getText().trim(), typeBox.getValue(),
                        valueField.getText().trim(), parseLongSafe(ttlField.getText()),
                        lineBox.getValue(), parseLongSafe(priorityField.getText()));
            }
            return null;
        });

        Optional<RecordForm> result = dialog.showAndWait();
        result.ifPresent(form -> {
            if (isEdit) doUpdate(record, form);
            else doAdd(form);
        });
    }

    private void doAdd(RecordForm form) {
        statusLabel.setText("添加中...");
        new Thread(() -> {
            try {
                AliyunService.addDomainRecord(config, domainName, form.rr, form.type, form.value,
                        form.ttl, form.line, form.priority);
                Platform.runLater(() -> { statusLabel.setText("添加成功"); loadRecords(); });
            } catch (Exception e) {
                Platform.runLater(() -> { statusLabel.setText("添加失败"); errorAlert("添加解析记录失败", e); });
            }
        }, "Aliyun-AddRecord").start();
    }

    private void doUpdate(RecordItem item, RecordForm form) {
        statusLabel.setText("修改中...");
        new Thread(() -> {
            try {
                AliyunService.updateDomainRecord(config, item.getRecordId(), form.rr, form.type, form.value,
                        form.ttl, form.line, form.priority);
                Platform.runLater(() -> { statusLabel.setText("修改成功"); loadRecords(); });
            } catch (Exception e) {
                Platform.runLater(() -> { statusLabel.setText("修改失败"); errorAlert("修改解析记录失败", e); });
            }
        }, "Aliyun-UpdateRecord").start();
    }

    private void confirmDelete(RecordItem item) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除确认");
        confirm.setHeaderText("删除解析记录");
        confirm.setContentText("确定删除主机记录 \"" + item.getRr() + "\" (" + item.getType() + ") 吗？此操作不可撤销。");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) doDelete(item);
        });
    }

    private void doDelete(RecordItem item) {
        statusLabel.setText("删除中...");
        disableDdns(item); // 删除时同步关闭其 DDNS
        new Thread(() -> {
            try {
                AliyunService.deleteDomainRecord(config, item.getRecordId());
                Platform.runLater(() -> { statusLabel.setText("删除成功"); loadRecords(); });
            } catch (Exception e) {
                Platform.runLater(() -> { statusLabel.setText("删除失败"); errorAlert("删除解析记录失败", e); });
            }
        }, "Aliyun-DeleteRecord").start();
    }

    private void errorAlert(String header, Throwable e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("操作失败");
        alert.setHeaderText(header);
        String msg = e.getMessage();
        alert.setContentText(msg == null || msg.isEmpty() ? e.toString() : msg);
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        TextArea textArea = new TextArea(sw.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefSize(580, 220);
        alert.getDialogPane().setExpandableContent(textArea);
        alert.showAndWait();
    }

    /** 添加/修改表单数据 */
    private static class RecordForm {
        final String rr, type, value, line;
        final Long ttl;
        final Long priority;
        RecordForm(String rr, String type, String value, Long ttl, String line, Long priority) {
            this.rr = rr; this.type = type; this.value = value; this.ttl = ttl;
            this.line = line; this.priority = priority;
        }
    }

    /** 子域名（解析记录）表格数据项 */
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

    /** iOS 风格的开关控件（纯代码实现，无需 CSS 文件） */
    private static class Switch extends StackPane {
        private static final double W = 38, H = 20, THUMB = 16;
        private final Region track = new Region();
        private final Circle thumb = new Circle(THUMB / 2.0);
        private boolean selected = false;
        private Runnable onToggle;

        Switch() {
            setPrefSize(W, H);
            setMinSize(W, H);
            setMaxSize(W, H);

            track.setPrefSize(W, H);
            track.setStyle("-fx-background-radius: 10;");

            thumb.setFill(Color.WHITE);
            thumb.setEffect(new DropShadow(4, 0, 1, Color.rgb(0, 0, 0, 0.25)));
            thumb.setTranslateX(-9);

            getChildren().addAll(track, thumb);
            updateVisual(false);

            disabledProperty().addListener((o, a, d) -> updateVisual(false));

            setOnMouseClicked(e -> {
                if (isDisabled()) return;
                e.consume();
                toggle();
            });
        }

        private void toggle() {
            selected = !selected;
            updateVisual(true);
            if (onToggle != null) onToggle.run();
        }

        void syncSelected(boolean s) {
            this.selected = s;
            updateVisual(false);
        }

        boolean isSelected() { return selected; }

        void setOnToggle(Runnable r) { this.onToggle = r; }

        private void updateVisual(boolean animate) {
            String bg = selected ? "#4CAF50" : "#bdbdbd";
            if (isDisabled()) bg = "#e0e0e0";
            track.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 10;");
            double tx = selected ? 9 : -9;
            if (animate) {
                Timeline tl = new Timeline(new KeyFrame(javafx.util.Duration.millis(150),
                        new KeyValue(thumb.translateXProperty(), tx)));
                tl.play();
            } else {
                thumb.setTranslateX(tx);
            }
        }
    }
}
