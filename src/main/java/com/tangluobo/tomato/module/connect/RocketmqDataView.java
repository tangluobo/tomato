package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class RocketmqDataView extends VBox {
    private final ConnectionConfig config;
    private final TabPane mainTabPane;
    private final String topicName;

    // Topic tab
    private TableView<TopicItem> topicTable;
    private final ObservableList<TopicItem> topicData = FXCollections.observableArrayList();
    private TextArea topicDetailArea;
    private VBox topicListSection;
    private HBox topicToolbar;
    private TableView<TopicOffsetItem> topicOffsetTable;
    private final ObservableList<TopicOffsetItem> topicOffsetData = FXCollections.observableArrayList();
    private VBox topicOffsetSection;

    // Message tab
    private String currentMessageTopic;
    private TextField msgKeyField;
    private TextField msgMsgIdField;
    private DatePicker beginDatePicker;
    private DatePicker endDatePicker;
    private TableView<MessageItem> messageTable;
    private final ObservableList<MessageItem> messageData = FXCollections.observableArrayList();
    private TextArea messageDetailArea;

    public RocketmqDataView(ConnectionConfig config, String topicName) {
        this.config = config;
        this.topicName = topicName;
        this.mainTabPane = new TabPane();
        this.mainTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        setupTopicTab();
        setupMessageTab();

        this.getChildren().add(mainTabPane);
        VBox.setVgrow(mainTabPane, Priority.ALWAYS);

        // Apply no-gap styling
        String css = getClass().getResource("/css/tab-nogap.css") != null
                ? getClass().getResource("/css/tab-nogap.css").toExternalForm()
                : null;
        if (css != null) {
            mainTabPane.getStylesheets().add(css);
            this.getStylesheets().add(css);
        }

        // Apply immediately
        Platform.runLater(this::applyNoGapStyles);

        // 自动加载数据
        loadTopics();
    }

    private void applyNoGapStyles() {
        applyNoGapToTabPane(mainTabPane);
    }

    private void applyNoGapToTabPane(TabPane tabPane) {
        tabPane.setStyle("-fx-padding: 0; -fx-border-insets: 0; -fx-background-insets: 0;");

        // Set zero padding on tab layout containers only (not input controls)
        tabPane.lookupAll(".tab-content-area").forEach(n -> n.setStyle(
                "-fx-padding: 0; -fx-background-color: transparent;"));
        tabPane.lookupAll(".tab-header-area").forEach(n -> n.setStyle(
                "-fx-padding: 0;"));

        // Recursively apply to nested TabPanes
        tabPane.lookupAll(".tab-pane").forEach(n -> {
            if (n instanceof TabPane && n != tabPane) {
                applyNoGapToTabPane((TabPane) n);
            }
        });
    }

    public TabPane getMainTabPane() {
        return mainTabPane;
    }

    public void selectTopicTab(String topicName) {
        mainTabPane.getSelectionModel().select(0);
        String t = (topicName != null && !topicName.isEmpty()) ? topicName : this.topicName;
        if (t != null && !t.isEmpty()) {
            showTopicOffsetSection(t);
            currentMessageTopic = t;
        } else {
            showTopicListSection();
        }
    }

    public void selectMessageTab(String topicName) {
        mainTabPane.getSelectionModel().select(1);
        String t = (topicName != null && !topicName.isEmpty()) ? topicName : this.topicName;
        currentMessageTopic = t;
    }

    // ==================== Topic Tab ====================

    private void setupTopicTab() {
        VBox content = new VBox(0);
        content.setPadding(Insets.EMPTY);

        // 工具栏
        topicToolbar = new HBox(8);
        topicToolbar.setAlignment(Pos.CENTER_LEFT);

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

        topicToolbar.getChildren().addAll(refreshBtn, createBtn, deleteBtn, statsBtn);

        // Topic表格
        topicTable = new TableView<>();
        topicTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        topicTable.setPlaceholder(new Label("无数据"));

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

        topicListSection = new VBox(8);
        topicListSection.getChildren().addAll(topicToolbar, topicTable, new Label("统计信息:"), topicDetailArea);
        VBox.setVgrow(topicTable, Priority.ALWAYS);

        // 偏移信息表格（用于从树节点双击打开时直接展示）
        topicOffsetTable = new TableView<>();
        topicOffsetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        topicOffsetTable.setPlaceholder(new Label("无数据"));

        TableColumn<TopicOffsetItem, String> brokerNameCol = new TableColumn<>("BrokerName");
        brokerNameCol.setCellValueFactory(new PropertyValueFactory<>("brokerName"));
        brokerNameCol.setPrefWidth(150);

        TableColumn<TopicOffsetItem, String> queueIdCol = new TableColumn<>("QueueId");
        queueIdCol.setCellValueFactory(new PropertyValueFactory<>("queueId"));
        queueIdCol.setPrefWidth(80);

        TableColumn<TopicOffsetItem, String> minOffsetCol = new TableColumn<>("MinOffset");
        minOffsetCol.setCellValueFactory(new PropertyValueFactory<>("minOffset"));
        minOffsetCol.setPrefWidth(120);

        TableColumn<TopicOffsetItem, String> maxOffsetCol = new TableColumn<>("MaxOffset");
        maxOffsetCol.setCellValueFactory(new PropertyValueFactory<>("maxOffset"));
        maxOffsetCol.setPrefWidth(120);

        TableColumn<TopicOffsetItem, String> lastUpdateCol = new TableColumn<>("最后更新时间");
        lastUpdateCol.setCellValueFactory(new PropertyValueFactory<>("lastUpdateTimestamp"));
        lastUpdateCol.setPrefWidth(180);

        topicOffsetTable.getColumns().addAll(brokerNameCol, queueIdCol, minOffsetCol, maxOffsetCol, lastUpdateCol);
        topicOffsetTable.setItems(topicOffsetData);

        Label topicTitleLabel = new Label();
        topicTitleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        HBox offsetToolbar = new HBox(10);
        offsetToolbar.setAlignment(Pos.CENTER_LEFT);
        offsetToolbar.getChildren().addAll(topicTitleLabel);

        topicOffsetSection = new VBox(8);
        topicOffsetSection.getChildren().addAll(offsetToolbar, topicOffsetTable);
        VBox.setVgrow(topicOffsetTable, Priority.ALWAYS);
        topicOffsetSection.setVisible(false);
        topicOffsetSection.setManaged(false);

        content.getChildren().addAll(topicListSection, topicOffsetSection);

        Tab tab = new Tab("主题");
        tab.setContent(content);
        mainTabPane.getTabs().add(tab);
    }

    private void showTopicListSection() {
        topicOffsetSection.setVisible(false);
        topicOffsetSection.setManaged(false);
        topicListSection.setVisible(true);
        topicListSection.setManaged(true);
    }

    private void showTopicOffsetSection(String topicName) {
        topicListSection.setVisible(false);
        topicListSection.setManaged(false);
        topicOffsetSection.setVisible(true);
        topicOffsetSection.setManaged(true);

        Label titleLabel = (Label) ((HBox) topicOffsetSection.getChildren().get(0)).getChildren().get(0);
        titleLabel.setText("主题: " + topicName + " - 队列偏移信息");

        loadTopicOffsetData(topicName);
    }

    private void loadTopicOffsetData(String topicName) {
        new Thread(() -> {
            try {
                Map<String, Object> stats = RocketmqService.getTopicStats(config, topicName);
                List<Map<String, Object>> offsetList;
                Object offsetTable = stats.get("offsetTable");
                if (offsetTable instanceof List) {
                    offsetList = (List<Map<String, Object>>) offsetTable;
                } else {
                    offsetList = null;
                }

                Platform.runLater(() -> {
                    topicOffsetData.clear();
                    if (offsetList != null) {
                        for (Map<String, Object> offset : offsetList) {
                            String brokerName = String.valueOf(offset.getOrDefault("brokerName", ""));
                            String queueId = String.valueOf(offset.getOrDefault("queueId", ""));
                            String minOffset = String.valueOf(offset.getOrDefault("minOffset", ""));
                            String maxOffset = String.valueOf(offset.getOrDefault("maxOffset", ""));
                            Object ts = offset.get("lastUpdateTimestamp");
                            String lastUpdate = ts != null ? formatTimestamp(ts) : "";
                            topicOffsetData.add(new TopicOffsetItem(brokerName, queueId, minOffset, maxOffset, lastUpdate));
                        }
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    topicOffsetData.clear();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("错误");
                    alert.setHeaderText(null);
                    alert.setContentText("获取主题统计信息失败: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "RocketMQ-TopicOffset").start();
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
        showTopicOffsetSection(selected.getTopic());
    }

    // ==================== Message Tab ====================

    private void setupMessageTab() {
        VBox content = new VBox(0);
        content.setPadding(Insets.EMPTY);

        // 查询方式子标签
        TabPane queryTabPane = new TabPane();

        // Apply no-gap CSS to nested TabPane
        String queryCss = getClass().getResource("/css/tab-nogap.css") != null
                ? getClass().getResource("/css/tab-nogap.css").toExternalForm()
                : null;
        if (queryCss != null) {
            queryTabPane.getStylesheets().add(queryCss);
        }

        // --- 按Key查询 ---
        VBox keyQueryContent = new VBox(5);
        keyQueryContent.setPadding(Insets.EMPTY);
        keyQueryContent.setAlignment(Pos.CENTER_LEFT);

        msgKeyField = new TextField();
        msgKeyField.setPromptText("请输入Message Key");
        msgKeyField.setPrefWidth(300);

        Button keySearchBtn = new Button("查询");
        keySearchBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        keySearchBtn.setOnAction(e -> queryByKey());

        HBox keyQueryBar = new HBox(10);
        keyQueryBar.setAlignment(Pos.CENTER_LEFT);
        keyQueryBar.getChildren().addAll(new Label("Key:"), msgKeyField, keySearchBtn);
        keyQueryContent.getChildren().add(keyQueryBar);

        Tab keyQueryTab = new Tab("按Key查询");
        keyQueryTab.setContent(keyQueryContent);
        keyQueryTab.setClosable(false);

        // --- 按MsgId查询 ---
        VBox msgIdQueryContent = new VBox(5);
        msgIdQueryContent.setPadding(Insets.EMPTY);
        msgIdQueryContent.setAlignment(Pos.CENTER_LEFT);

        msgMsgIdField = new TextField();
        msgMsgIdField.setPromptText("请输入MsgId");
        msgMsgIdField.setPrefWidth(400);

        Button msgIdSearchBtn = new Button("查询");
        msgIdSearchBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        msgIdSearchBtn.setOnAction(e -> queryByMsgId());

        HBox msgIdQueryBar = new HBox(10);
        msgIdQueryBar.setAlignment(Pos.CENTER_LEFT);
        msgIdQueryBar.getChildren().addAll(new Label("MsgId:"), msgMsgIdField, msgIdSearchBtn);
        msgIdQueryContent.getChildren().add(msgIdQueryBar);

        Tab msgIdQueryTab = new Tab("按MsgId查询");
        msgIdQueryTab.setContent(msgIdQueryContent);
        msgIdQueryTab.setClosable(false);

        // --- 按时间查询 ---
        VBox timeQueryContent = new VBox(5);
        timeQueryContent.setPadding(Insets.EMPTY);
        timeQueryContent.setAlignment(Pos.CENTER_LEFT);

        beginDatePicker = createDateTimePicker(LocalDate.now().minusDays(3).atStartOfDay());
        beginDatePicker.setPrefWidth(190);

        endDatePicker = createDateTimePicker(LocalDate.now().atTime(23, 59, 59));
        endDatePicker.setPrefWidth(190);

        Button timeSearchBtn = new Button("查询");
        timeSearchBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        timeSearchBtn.setOnAction(e -> queryByTime());

        // 快捷时间选择
        Button last1DayBtn = new Button("近1天");
        last1DayBtn.setStyle("-fx-font-size: 11px;");
        last1DayBtn.setOnAction(e -> setQuickRange(1));

        Button last3DaysBtn = new Button("近3天");
        last3DaysBtn.setStyle("-fx-font-size: 11px;");
        last3DaysBtn.setOnAction(e -> setQuickRange(3));

        Button last7DaysBtn = new Button("近7天");
        last7DaysBtn.setStyle("-fx-font-size: 11px;");
        last7DaysBtn.setOnAction(e -> setQuickRange(7));

        Button lastMonthBtn = new Button("近1月");
        lastMonthBtn.setStyle("-fx-font-size: 11px;");
        lastMonthBtn.setOnAction(e -> setQuickRange(30));

        HBox timeRangeBar = new HBox(5);
        timeRangeBar.setAlignment(Pos.CENTER_LEFT);
        timeRangeBar.getChildren().addAll(
                new Label("开始:"), beginDatePicker,
                new Label("结束:"), endDatePicker,
                timeSearchBtn
        );

        HBox quickRangeBar = new HBox(5);
        quickRangeBar.setAlignment(Pos.CENTER_LEFT);
        quickRangeBar.getChildren().addAll(new Label("快捷:"), last1DayBtn, last3DaysBtn, last7DaysBtn, lastMonthBtn);

        timeQueryContent.getChildren().addAll(timeRangeBar, quickRangeBar);

        Tab timeQueryTab = new Tab("按时间查询");
        timeQueryTab.setContent(timeQueryContent);
        timeQueryTab.setClosable(false);

        queryTabPane.getTabs().addAll(keyQueryTab, msgIdQueryTab, timeQueryTab);

        // Re-apply no-gap styles when sub-tabs change
        queryTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) ->
                Platform.runLater(this::applyNoGapStyles));

        // 消息表格
        messageTable = new TableView<>();
        messageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        messageTable.setPlaceholder(new Label("无数据"));

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

        VBox.setVgrow(messageTable, Priority.ALWAYS);
        VBox.setVgrow(queryTabPane, Priority.NEVER);
        queryTabPane.setMaxHeight(120);
        queryTabPane.setMinHeight(80);

        content.getChildren().addAll(queryTabPane, messageTable, new Label("消息详情(双击查看):"), messageDetailArea);

        Tab tab = new Tab("消息");
        tab.setContent(content);
        mainTabPane.getTabs().add(tab);
    }

    private String getCurrentTopic() {
        if (currentMessageTopic != null && !currentMessageTopic.isEmpty()) {
            return currentMessageTopic;
        }
        return topicName;
    }

    private void queryByKey() {
        final String topic = getCurrentTopic();
        if (topic == null || topic.isEmpty()) {
            showWarning("当前标签未关联主题");
            return;
        }
        String key = msgKeyField.getText().trim();
        if (key.isEmpty()) {
            showWarning("请输入Key");
            return;
        }

        new Thread(() -> {
            try {
                List<Map<String, Object>> messages = RocketmqService.queryMessageByKey(config, topic, key);
                displayQueryResults(messages);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    messageData.clear();
                    messageDetailArea.setText("查询消息失败: " + e.getMessage());
                });
            }
        }, "RocketMQ-QueryByKey").start();
    }

    private void queryByMsgId() {
        final String topic = getCurrentTopic();
        if (topic == null || topic.isEmpty()) {
            showWarning("当前标签未关联主题");
            return;
        }
        String msgId = msgMsgIdField.getText().trim();
        if (msgId.isEmpty()) {
            showWarning("请输入MsgId");
            return;
        }

        new Thread(() -> {
            try {
                Map<String, Object> msg = RocketmqService.queryMessageById(config, topic, msgId);
                List<Map<String, Object>> messages = new ArrayList<>();
                if (msg != null && !msg.isEmpty()) messages.add(msg);
                displayQueryResults(messages);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    messageData.clear();
                    messageDetailArea.setText("查询消息失败: " + e.getMessage());
                });
            }
        }, "RocketMQ-QueryByMsgId").start();
    }

    private void queryByTime() {
        final String topic = getCurrentTopic();
        if (topic == null || topic.isEmpty()) {
            showWarning("当前标签未关联主题");
            return;
        }
        long begin = getDateTimeFromPicker(beginDatePicker);
        long end = getDateTimeFromPicker(endDatePicker);
        if (begin == 0 || end == 0) {
            showWarning("请输入正确的时间格式 (yyyy-MM-dd HH:mm:ss)");
            return;
        }
        if (begin >= end) {
            showWarning("开始时间必须早于结束时间");
            return;
        }

        new Thread(() -> {
            try {
                List<Map<String, Object>> messages = RocketmqService.queryMessageByTime(config, topic, begin, end);
                displayQueryResults(messages);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    messageData.clear();
                    messageDetailArea.setText("查询消息失败: " + e.getMessage());
                });
            }
        }, "RocketMQ-QueryByTime").start();
    }

    private void displayQueryResults(List<Map<String, Object>> messages) {
        Platform.runLater(() -> {
            messageData.clear();
            if (messages == null || messages.isEmpty()) {
                messageDetailArea.setText("没有消息");
            } else {
                messageDetailArea.setText("");
            }
            for (Map<String, Object> m : messages) {
                String msgId = String.valueOf(m.getOrDefault("msgId", ""));
                String tags = String.valueOf(m.getOrDefault("tags", ""));
                String keys = String.valueOf(m.getOrDefault("keys", ""));
                String storeTime = formatTimestamp(m.get("storeTimestamp"));
                String bornHost = String.valueOf(m.getOrDefault("bornHost", ""));
                messageData.add(new MessageItem(msgId, tags, keys, storeTime, bornHost, m));
            }
        });
    }

    private void showMessageDetail(MessageItem item) {
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
        if (currentMessageTopic == null || currentMessageTopic.isEmpty() || item.getMsgId().isEmpty()) return;
        final String topic = currentMessageTopic;
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

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DatePicker createDateTimePicker(LocalDateTime defaultDateTime) {
        DatePicker picker = new DatePicker();
        picker.setPrefWidth(190);
        picker.setConverter(new StringConverter<LocalDate>() {
            @Override
            public String toString(LocalDate date) {
                if (date == null) return "";
                // 保留编辑框中的时分秒
                String editorText = picker.getEditor().getText();
                String timePart = " 00:00:00";
                if (editorText != null && editorText.trim().length() >= 19) {
                    timePart = " " + editorText.trim().substring(11);
                }
                return date.format(DateTimeFormatter.ISO_LOCAL_DATE) + timePart;
            }

            @Override
            public LocalDate fromString(String string) {
                if (string == null || string.trim().isEmpty()) return null;
                String trimmed = string.trim();
                try {
                    return LocalDateTime.parse(trimmed, DATETIME_FORMATTER).toLocalDate();
                } catch (Exception e) {
                    try {
                        return LocalDate.parse(trimmed);
                    } catch (Exception e2) {
                        return null;
                    }
                }
            }
        });
        picker.setValue(defaultDateTime.toLocalDate());
        picker.getEditor().setText(defaultDateTime.format(DATETIME_FORMATTER));
        return picker;
    }

    private void setDateTimePickerValue(DatePicker picker, LocalDateTime dateTime) {
        picker.setValue(dateTime.toLocalDate());
        picker.getEditor().setText(dateTime.format(DATETIME_FORMATTER));
    }

    private long getDateTimeFromPicker(DatePicker picker) {
        try {
            String text = picker.getEditor().getText();
            if (text == null || text.trim().isEmpty()) return 0;
            LocalDateTime dt = LocalDateTime.parse(text.trim(), DATETIME_FORMATTER);
            return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private void setQuickRange(int days) {
        setDateTimePickerValue(beginDatePicker, LocalDate.now().minusDays(days).atStartOfDay());
        setDateTimePickerValue(endDatePicker, LocalDate.now().atTime(23, 59, 59));
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

    public static class TopicOffsetItem {
        private final String brokerName;
        private final String queueId;
        private final String minOffset;
        private final String maxOffset;
        private final String lastUpdateTimestamp;
        public TopicOffsetItem(String brokerName, String queueId, String minOffset, String maxOffset, String lastUpdateTimestamp) {
            this.brokerName = brokerName; this.queueId = queueId; this.minOffset = minOffset; this.maxOffset = maxOffset; this.lastUpdateTimestamp = lastUpdateTimestamp;
        }
        public String getBrokerName() { return brokerName; }
        public String getQueueId() { return queueId; }
        public String getMinOffset() { return minOffset; }
        public String getMaxOffset() { return maxOffset; }
        public String getLastUpdateTimestamp() { return lastUpdateTimestamp; }
    }
}
