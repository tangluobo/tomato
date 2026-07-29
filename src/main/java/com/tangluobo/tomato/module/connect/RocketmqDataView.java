package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class RocketmqDataView extends VBox {
    private final ConnectionConfig config;
    private final TabPane mainTabPane;

    // Topic tab
    private TableView<TopicItem> topicTable;
    private final ObservableList<TopicItem> topicData = FXCollections.observableArrayList();
    private TextArea topicDetailArea;

    // Message tab
    private ComboBox<String> msgTopicCombo;
    private TextField msgKeyField;
    private ComboBox<String> msgQueryTypeCombo;
    private DatePicker beginDatePicker;
    private TextField beginTimeField;
    private DatePicker endDatePicker;
    private TextField endTimeField;
    private HBox timeRangeBox;
    private TableView<MessageItem> messageTable;
    private final ObservableList<MessageItem> messageData = FXCollections.observableArrayList();
    private TextArea messageDetailArea;

    // Consumer tab
    private TableView<ConsumerItem> consumerTable;
    private final ObservableList<ConsumerItem> consumerData = FXCollections.observableArrayList();

    // Cluster tab
    private TableView<ClusterItem> clusterTable;
    private final ObservableList<ClusterItem> clusterData = FXCollections.observableArrayList();

    public RocketmqDataView(ConnectionConfig config) {
        this.config = config;
        this.mainTabPane = new TabPane();
        this.mainTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        setupTopicTab();
        setupMessageTab();
        setupConsumerTab();
        setupClusterTab();

        this.getChildren().add(mainTabPane);
        VBox.setVgrow(mainTabPane, Priority.ALWAYS);

        // 自动加载Topic列表
        loadTopics();
    }

    public TabPane getMainTabPane() {
        return mainTabPane;
    }

    // ==================== Topic Tab ====================

    private void setupTopicTab() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(8));

        // 工具栏
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        refreshBtn.setOnAction(e -> loadTopics());

        Button createBtn = new Button("创建主题");
        createBtn.setStyle("-fx-font-size: 12px;");
        createBtn.setOnAction(e -> showCreateTopicDialog());

        Button deleteBtn = new Button("删除主题");
        deleteBtn.setStyle("-fx-font-size: 12px; -fx-text-fill: #cc0000;");
        deleteBtn.setOnAction(e -> deleteSelectedTopic());

        Button statsBtn = new Button("查看统计");
        statsBtn.setStyle("-fx-font-size: 12px;");
        statsBtn.setOnAction(e -> showTopicStats());

        toolbar.getChildren().addAll(refreshBtn, createBtn, deleteBtn, statsBtn);

        // Topic表格
        topicTable = new TableView<>();
        topicTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<TopicItem, String> nameCol = new TableColumn<>("主题名称");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("topic"));
        nameCol.setPrefWidth(400);

        TableColumn<TopicItem, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("topicType"));
        typeCol.setPrefWidth(150);

        topicTable.getColumns().addAll(nameCol, typeCol);
        topicTable.setItems(topicData);

        // 双击查看详情
        topicTable.setRowFactory(tv -> {
            TableRow<TopicItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showTopicStats();
                }
            });
            return row;
        });

        // 详情区域
        topicDetailArea = new TextArea();
        topicDetailArea.setPromptText("选择主题后点击\"查看统计\"或双击查看统计信息");
        topicDetailArea.setPrefHeight(200);
        topicDetailArea.setEditable(false);
        topicDetailArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        content.getChildren().addAll(toolbar, topicTable, new Label("统计信息:"), topicDetailArea);
        VBox.setVgrow(topicTable, Priority.ALWAYS);

        Tab tab = new Tab("主题");
        tab.setContent(content);
        mainTabPane.getTabs().add(tab);
    }

    private void loadTopics() {
        new Thread(() -> {
            try {
                List<Map<String, Object>> topics = RocketmqService.getTopicList(config);
                Platform.runLater(() -> {
                    topicData.clear();
                    for (Map<String, Object> t : topics) {
                        String name = String.valueOf(t.getOrDefault("topic", ""));
                        // 过滤系统主题
                        if (name.startsWith("%")) continue;
                        String type = String.valueOf(t.getOrDefault("topicType", ""));
                        topicData.add(new TopicItem(name, type));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("加载主题列表失败: " + e.getMessage()));
            }
        }, "RocketMQ-LoadTopics").start();
    }

    private void showCreateTopicDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("创建主题");
        dialog.setHeaderText("创建新的RocketMQ主题");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 10, 10, 10));

        TextField topicField = new TextField();
        topicField.setPromptText("主题名称");
        TextField queueField = new TextField("8");
        queueField.setPromptText("队列数");

        grid.add(new Label("主题名称："), 0, 0);
        grid.add(topicField, 1, 0);
        grid.add(new Label("队列数："), 0, 1);
        grid.add(queueField, 1, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> btn == ButtonType.OK ? topicField.getText() + "|" + queueField.getText() : null);
        dialog.showAndWait().ifPresent(result -> {
            String[] parts = result.split("\\|");
            String topic = parts[0].trim();
            int queueNum = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 8;
            if (topic.isEmpty()) return;
            new Thread(() -> {
                try {
                    RocketmqService.createTopic(config, topic, queueNum);
                    Platform.runLater(() -> {
                        showInfo("主题 " + topic + " 创建成功");
                        loadTopics();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> showError("创建主题失败: " + e.getMessage()));
                }
            }, "RocketMQ-CreateTopic").start();
        });
    }

    private void deleteSelectedTopic() {
        TopicItem selected = topicTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning("请先选择要删除的主题");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除主题: " + selected.getTopic());
        confirm.setContentText("删除后不可恢复，确定要删除吗？");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        RocketmqService.deleteTopic(config, selected.getTopic());
                        Platform.runLater(() -> {
                            showInfo("主题 " + selected.getTopic() + " 已删除");
                            loadTopics();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("删除主题失败: " + e.getMessage()));
                    }
                }, "RocketMQ-DeleteTopic").start();
            }
        });
    }

    private void showTopicStats() {
        TopicItem selected = topicTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning("请先选择主题");
            return;
        }
        new Thread(() -> {
            try {
                Map<String, Object> stats = RocketmqService.getTopicStats(config, selected.getTopic());
                StringBuilder sb = new StringBuilder();
                sb.append("主题: ").append(selected.getTopic()).append("\n\n");

                Object offsetTable = stats.get("offsetTable");
                if (offsetTable instanceof List) {
                    List<?> list = (List<?>) offsetTable;
                    sb.append("队列偏移信息 (共").append(list.size()).append("条):\n");
                    for (Object item : list) {
                        sb.append("  ").append(item).append("\n");
                    }
                } else {
                    sb.append("暂无统计信息\n");
                }

                String result = sb.toString();
                Platform.runLater(() -> topicDetailArea.setText(result));
            } catch (Exception e) {
                Platform.runLater(() -> topicDetailArea.setText("获取统计信息失败: " + e.getMessage()));
            }
        }, "RocketMQ-TopicStats").start();
    }

    // ==================== Message Tab ====================

    private void setupMessageTab() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(8));

        // 搜索表单
        GridPane searchGrid = new GridPane();
        searchGrid.setHgap(10);
        searchGrid.setVgap(8);

        // 主题下拉框（可编辑）
        msgTopicCombo = new ComboBox<>();
        msgTopicCombo.setPromptText("选择主题");
        msgTopicCombo.setPrefWidth(220);
        msgTopicCombo.setEditable(true);

        msgKeyField = new TextField();
        msgKeyField.setPromptText("Message Key / MsgId");
        msgKeyField.setPrefWidth(200);

        msgQueryTypeCombo = new ComboBox<>();
        msgQueryTypeCombo.getItems().addAll("按Key查询", "按MsgId查询", "按时间查询");
        msgQueryTypeCombo.setValue("按Key查询");

        Button searchBtn = new Button("查询");
        searchBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        searchBtn.setOnAction(e -> queryMessages());

        // 时间选择器
        beginDatePicker = new DatePicker(LocalDate.now().minusDays(1));
        beginDatePicker.setPrefWidth(130);
        beginTimeField = new TextField("00:00:00");
        beginTimeField.setPrefWidth(70);

        endDatePicker = new DatePicker(LocalDate.now());
        endDatePicker.setPrefWidth(130);
        endTimeField = new TextField("23:59:59");
        endTimeField.setPrefWidth(70);

        timeRangeBox = new HBox(5);
        timeRangeBox.setAlignment(Pos.CENTER_LEFT);
        timeRangeBox.getChildren().addAll(
                new Label("开始:"), beginDatePicker, beginTimeField,
                new Label("结束:"), endDatePicker, endTimeField
        );
        timeRangeBox.setVisible(false);
        timeRangeBox.setManaged(false);

        // 查询方式切换时，显示/隐藏时间选择器和Key输入框
        msgQueryTypeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean isTimeQuery = "按时间查询".equals(newVal);
            timeRangeBox.setVisible(isTimeQuery);
            timeRangeBox.setManaged(isTimeQuery);
            msgKeyField.setDisable(isTimeQuery);
            if (isTimeQuery) {
                msgKeyField.setPromptText("时间查询不需要Key");
            } else {
                msgKeyField.setPromptText("Message Key / MsgId");
            }
        });

        searchGrid.add(new Label("主题："), 0, 0);
        searchGrid.add(msgTopicCombo, 1, 0);
        searchGrid.add(new Label("查询方式："), 2, 0);
        searchGrid.add(msgQueryTypeCombo, 3, 0);
        searchGrid.add(searchBtn, 4, 0);
        searchGrid.add(new Label("Key/MsgId："), 0, 1);
        searchGrid.add(msgKeyField, 1, 1);
        searchGrid.add(timeRangeBox, 2, 1, 3, 1);

        // 消息表格
        messageTable = new TableView<>();
        messageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<MessageItem, String> msgIdCol = new TableColumn<>("MsgId");
        msgIdCol.setCellValueFactory(new PropertyValueFactory<>("msgId"));
        msgIdCol.setPrefWidth(250);

        TableColumn<MessageItem, String> tagsCol = new TableColumn<>("Tags");
        tagsCol.setCellValueFactory(new PropertyValueFactory<>("tags"));
        tagsCol.setPrefWidth(150);

        TableColumn<MessageItem, String> keysCol = new TableColumn<>("Keys");
        keysCol.setCellValueFactory(new PropertyValueFactory<>("keys"));
        keysCol.setPrefWidth(150);

        TableColumn<MessageItem, String> timeCol = new TableColumn<>("存储时间");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("storeTime"));
        timeCol.setPrefWidth(180);

        TableColumn<MessageItem, String> hostCol = new TableColumn<>("BornHost");
        hostCol.setCellValueFactory(new PropertyValueFactory<>("bornHost"));
        hostCol.setPrefWidth(150);

        messageTable.getColumns().addAll(msgIdCol, tagsCol, keysCol, timeCol, hostCol);
        messageTable.setItems(messageData);

        // 消息详情
        messageDetailArea = new TextArea();
        messageDetailArea.setPromptText("选择消息查看详情");
        messageDetailArea.setPrefHeight(200);
        messageDetailArea.setEditable(false);
        messageDetailArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        messageTable.setRowFactory(tv -> {
            TableRow<MessageItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showMessageDetail(row.getItem());
                }
            });
            return row;
        });

        content.getChildren().addAll(searchGrid, messageTable, new Label("消息详情(双击查看):"), messageDetailArea);
        VBox.setVgrow(messageTable, Priority.ALWAYS);

        Tab tab = new Tab("消息");
        tab.setContent(content);
        mainTabPane.getTabs().add(tab);

        // 加载主题到下拉框
        loadTopicsForCombo();
    }

    private void loadTopicsForCombo() {
        new Thread(() -> {
            try {
                List<Map<String, Object>> topics = RocketmqService.getTopicList(config);
                Platform.runLater(() -> {
                    List<String> topicNames = new ArrayList<>();
                    for (Map<String, Object> t : topics) {
                        String name = String.valueOf(t.getOrDefault("topic", ""));
                        if (!name.startsWith("%")) topicNames.add(name);
                    }
                    Collections.sort(topicNames);
                    msgTopicCombo.getItems().setAll(topicNames);
                    if (!topicNames.isEmpty()) {
                        msgTopicCombo.setValue(topicNames.get(0));
                    }
                });
            } catch (Exception ignored) {}
        }, "RocketMQ-LoadTopicsCombo").start();
    }

    private void queryMessages() {
        String topicValue = msgTopicCombo.getValue();
        if (topicValue == null || topicValue.trim().isEmpty()) {
            showWarning("请选择或输入主题名称");
            return;
        }
        final String topic = topicValue.trim();
        String key = msgKeyField.getText().trim();
        String queryType = msgQueryTypeCombo.getValue();

        new Thread(() -> {
            try {
                List<Map<String, Object>> messages;
                if ("按MsgId查询".equals(queryType)) {
                    if (key.isEmpty()) { Platform.runLater(() -> showWarning("请输入MsgId")); return; }
                    Map<String, Object> msg = RocketmqService.queryMessageById(config, topic, key);
                    messages = new ArrayList<>();
                    if (msg != null && !msg.isEmpty()) messages.add(msg);
                } else if ("按时间查询".equals(queryType)) {
                    long begin = parseTimeFromPicker(beginDatePicker, beginTimeField);
                    long end = parseTimeFromPicker(endDatePicker, endTimeField);
                    if (begin >= end) { Platform.runLater(() -> showWarning("开始时间必须早于结束时间")); return; }
                    messages = RocketmqService.queryMessageByTime(config, topic, begin, end);
                } else {
                    if (key.isEmpty()) { Platform.runLater(() -> showWarning("请输入Key")); return; }
                    messages = RocketmqService.queryMessageByKey(config, topic, key);
                }

                List<Map<String, Object>> finalMessages = messages;
                Platform.runLater(() -> {
                    messageData.clear();
                    if (finalMessages == null || finalMessages.isEmpty()) {
                        messageDetailArea.setText("没有消息");
                    } else {
                        messageDetailArea.setText("");
                    }
                    for (Map<String, Object> m : finalMessages) {
                        String msgId = String.valueOf(m.getOrDefault("msgId", ""));
                        String tags = String.valueOf(m.getOrDefault("tags", ""));
                        String keys = String.valueOf(m.getOrDefault("keys", ""));
                        String storeTime = formatTimestamp(m.get("storeTimestamp"));
                        String bornHost = String.valueOf(m.getOrDefault("bornHost", ""));
                        messageData.add(new MessageItem(msgId, tags, keys, storeTime, bornHost, m));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    messageData.clear();
                    messageDetailArea.setText("查询消息失败: " + e.getMessage());
                });
            }
        }, "RocketMQ-QueryMessages").start();
    }

    private void showMessageDetail(MessageItem item) {
        // 优先使用缓存的消息详情（包含body）
        if (item.getDetail() != null && !item.getDetail().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> entry : item.getDetail().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if ("storeTimestamp".equals(key) || "bornTimestamp".equals(key)) {
                    value = formatTimestamp(value);
                }
                sb.append(key).append(": ").append(value).append("\n");
            }
            messageDetailArea.setText(sb.toString());
            return;
        }
        // 没有缓存时再尝试viewMessage
        String topicValue = msgTopicCombo.getValue();
        if (topicValue == null || topicValue.trim().isEmpty() || item.getMsgId().isEmpty()) return;
        final String topic = topicValue.trim();
        new Thread(() -> {
            try {
                Map<String, Object> msg = RocketmqService.queryMessageById(config, topic, item.getMsgId());
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Object> entry : msg.entrySet()) {
                    sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                Platform.runLater(() -> messageDetailArea.setText(sb.toString()));
            } catch (Exception e) {
                StringBuilder sb = new StringBuilder();
                sb.append("MsgId: ").append(item.getMsgId()).append("\n");
                sb.append("Tags: ").append(item.getTags()).append("\n");
                sb.append("Keys: ").append(item.getKeys()).append("\n");
                sb.append("StoreTime: ").append(item.getStoreTime()).append("\n");
                sb.append("BornHost: ").append(item.getBornHost()).append("\n");
                Platform.runLater(() -> messageDetailArea.setText(sb.toString()));
            }
        }, "RocketMQ-MessageDetail").start();
    }

    // ==================== Consumer Tab ====================

    private void setupConsumerTab() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(8));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        refreshBtn.setOnAction(e -> loadConsumers());

        Button deleteBtn = new Button("删除消费者组");
        deleteBtn.setStyle("-fx-font-size: 12px; -fx-text-fill: #cc0000;");
        deleteBtn.setOnAction(e -> deleteSelectedConsumer());

        toolbar.getChildren().addAll(refreshBtn, deleteBtn);

        consumerTable = new TableView<>();
        consumerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<ConsumerItem, String> groupCol = new TableColumn<>("消费者组");
        groupCol.setCellValueFactory(new PropertyValueFactory<>("group"));
        groupCol.setPrefWidth(400);

        TableColumn<ConsumerItem, String> tpsCol = new TableColumn<>("消费TPS");
        tpsCol.setCellValueFactory(new PropertyValueFactory<>("consumeTps"));
        tpsCol.setPrefWidth(150);

        TableColumn<ConsumerItem, String> diffCol = new TableColumn<>("积压量");
        diffCol.setCellValueFactory(new PropertyValueFactory<>("diffTotal"));
        diffCol.setPrefWidth(150);

        consumerTable.getColumns().addAll(groupCol, tpsCol, diffCol);
        consumerTable.setItems(consumerData);

        content.getChildren().addAll(toolbar, consumerTable);
        VBox.setVgrow(consumerTable, Priority.ALWAYS);

        Tab tab = new Tab("消费者组");
        tab.setContent(content);
        mainTabPane.getTabs().add(tab);
    }

    private void loadConsumers() {
        new Thread(() -> {
            try {
                List<Map<String, Object>> consumers = RocketmqService.getConsumerGroupList(config);
                Platform.runLater(() -> {
                    consumerData.clear();
                    for (Map<String, Object> c : consumers) {
                        String group = String.valueOf(c.getOrDefault("group", ""));
                        String tps = String.valueOf(c.getOrDefault("consumeTps", "0"));
                        String diff = String.valueOf(c.getOrDefault("diffTotal", "0"));
                        consumerData.add(new ConsumerItem(group, tps, diff));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("加载消费者组失败: " + e.getMessage()));
            }
        }, "RocketMQ-LoadConsumers").start();
    }

    private void deleteSelectedConsumer() {
        ConsumerItem selected = consumerTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning("请先选择要删除的消费者组");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除消费者组: " + selected.getGroup());
        confirm.setContentText("删除后不可恢复，确定要删除吗？");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        RocketmqService.deleteConsumerGroup(config, selected.getGroup());
                        Platform.runLater(() -> {
                            showInfo("消费者组 " + selected.getGroup() + " 已删除");
                            loadConsumers();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("删除消费者组失败: " + e.getMessage()));
                    }
                }, "RocketMQ-DeleteConsumer").start();
            }
        });
    }

    // ==================== Cluster Tab ====================

    private void setupClusterTab() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(8));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        refreshBtn.setOnAction(e -> loadCluster());

        toolbar.getChildren().add(refreshBtn);

        clusterTable = new TableView<>();
        clusterTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<ClusterItem, String> brokerNameCol = new TableColumn<>("BrokerName");
        brokerNameCol.setCellValueFactory(new PropertyValueFactory<>("brokerName"));
        brokerNameCol.setPrefWidth(200);

        TableColumn<ClusterItem, String> brokerIdCol = new TableColumn<>("BrokerId");
        brokerIdCol.setCellValueFactory(new PropertyValueFactory<>("brokerId"));
        brokerIdCol.setPrefWidth(100);

        TableColumn<ClusterItem, String> addressCol = new TableColumn<>("地址");
        addressCol.setCellValueFactory(new PropertyValueFactory<>("address"));
        addressCol.setPrefWidth(250);

        TableColumn<ClusterItem, String> roleCol = new TableColumn<>("角色");
        roleCol.setCellValueFactory(new PropertyValueFactory<>("role"));
        roleCol.setPrefWidth(100);

        clusterTable.getColumns().addAll(brokerNameCol, brokerIdCol, addressCol, roleCol);
        clusterTable.setItems(clusterData);

        content.getChildren().addAll(toolbar, clusterTable);
        VBox.setVgrow(clusterTable, Priority.ALWAYS);

        Tab tab = new Tab("集群");
        tab.setContent(content);
        mainTabPane.getTabs().add(tab);
    }

    private void loadCluster() {
        new Thread(() -> {
            try {
                List<Map<String, Object>> cluster = RocketmqService.getClusterInfo(config);
                Platform.runLater(() -> {
                    clusterData.clear();
                    for (Map<String, Object> c : cluster) {
                        String brokerName = String.valueOf(c.getOrDefault("brokerName", ""));
                        String brokerId = String.valueOf(c.getOrDefault("brokerId", ""));
                        String address = String.valueOf(c.getOrDefault("address", ""));
                        String role = String.valueOf(c.getOrDefault("role", ""));
                        clusterData.add(new ClusterItem(brokerName, brokerId, address, role));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("加载集群信息失败: " + e.getMessage()));
            }
        }, "RocketMQ-LoadCluster").start();
    }

    // ==================== 辅助方法 ====================

    private String formatTimestamp(Object ts) {
        if (ts == null) return "";
        try {
            long millis;
            if (ts instanceof Number) {
                millis = ((Number) ts).longValue();
            } else {
                millis = Long.parseLong(String.valueOf(ts));
            }
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return String.valueOf(ts);
        }
    }

    private long parseTimeFromPicker(DatePicker datePicker, TextField timeField) {
        try {
            LocalDate date = datePicker.getValue();
            if (date == null) date = LocalDate.now();
            String timeStr = timeField.getText().trim();
            if (timeStr.isEmpty()) timeStr = "00:00:00";
            LocalTime time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm:ss"));
            return LocalDateTime.of(date, time)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("错误");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void showWarning(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("成功");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    // ==================== 数据模型类 ====================

    public static class TopicItem {
        private final String topic;
        private final String topicType;
        public TopicItem(String topic, String topicType) { this.topic = topic; this.topicType = topicType; }
        public String getTopic() { return topic; }
        public String getTopicType() { return topicType; }
    }

    public static class MessageItem {
        private final String msgId;
        private final String tags;
        private final String keys;
        private final String storeTime;
        private final String bornHost;
        private final Map<String, Object> detail; // 缓存完整消息详情
        public MessageItem(String msgId, String tags, String keys, String storeTime, String bornHost, Map<String, Object> detail) {
            this.msgId = msgId; this.tags = tags; this.keys = keys; this.storeTime = storeTime; this.bornHost = bornHost; this.detail = detail;
        }
        public String getMsgId() { return msgId; }
        public String getTags() { return tags; }
        public String getKeys() { return keys; }
        public String getStoreTime() { return storeTime; }
        public String getBornHost() { return bornHost; }
        public Map<String, Object> getDetail() { return detail; }
    }

    public static class ConsumerItem {
        private final String group;
        private final String consumeTps;
        private final String diffTotal;
        public ConsumerItem(String group, String consumeTps, String diffTotal) {
            this.group = group; this.consumeTps = consumeTps; this.diffTotal = diffTotal;
        }
        public String getGroup() { return group; }
        public String getConsumeTps() { return consumeTps; }
        public String getDiffTotal() { return diffTotal; }
    }

    public static class ClusterItem {
        private final String brokerName;
        private final String brokerId;
        private final String address;
        private final String role;
        public ClusterItem(String brokerName, String brokerId, String address, String role) {
            this.brokerName = brokerName; this.brokerId = brokerId; this.address = address; this.role = role;
        }
        public String getBrokerName() { return brokerName; }
        public String getBrokerId() { return brokerId; }
        public String getAddress() { return address; }
        public String getRole() { return role; }
    }
}
