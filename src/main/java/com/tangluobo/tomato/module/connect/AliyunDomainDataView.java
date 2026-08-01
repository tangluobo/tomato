package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Map;

/**
 * 阿里云域名列表面板
 */
public class AliyunDomainDataView extends BorderPane {

    private final ConnectionConfig config;

    private TableView<DomainItem> domainTable;
    private final ObservableList<DomainItem> domainItems = FXCollections.observableArrayList();
    private Label statusLabel;

    // 详情面板
    private VBox detailPane;

    public AliyunDomainDataView(ConnectionConfig config) {
        this.config = config;
        initializeUI();
        loadDomains();
    }

    private void initializeUI() {
        // 顶部工具栏
        ToolBar toolBar = new ToolBar();
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-font-size: 12px;");
        refreshBtn.setOnAction(e -> loadDomains());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        toolBar.getItems().addAll(refreshBtn, new Separator(), statusLabel);

        // 域名表格
        domainTable = new TableView<>();
        domainTable.setStyle("-fx-font-size: 12px;");
        domainTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<DomainItem, String> domainNameCol = new TableColumn<>("域名");
        domainNameCol.setCellValueFactory(new PropertyValueFactory<>("domainName"));
        domainNameCol.setPrefWidth(220);

        TableColumn<DomainItem, String> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("domainStatus"));
        statusCol.setPrefWidth(80);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(status);
                    String color = switch (status) {
                        case "SUCCEED" -> "#4CAF50";
                        case "AUDITING" -> "#FF9800";
                        case "FAILED" -> "#F44336";
                        case "NONAUDIT" -> "#9E9E9E";
                        default -> "#333";
                    };
                    setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold; -fx-font-size: 12px;");
                }
            }
        });

        TableColumn<DomainItem, String> registrationDateCol = new TableColumn<>("注册时间");
        registrationDateCol.setCellValueFactory(new PropertyValueFactory<>("registrationDate"));
        registrationDateCol.setPrefWidth(160);

        TableColumn<DomainItem, String> expirationDateCol = new TableColumn<>("到期时间");
        expirationDateCol.setCellValueFactory(new PropertyValueFactory<>("expirationDate"));
        expirationDateCol.setPrefWidth(160);

        TableColumn<DomainItem, String> remainingDaysCol = new TableColumn<>("剩余天数");
        remainingDaysCol.setCellValueFactory(new PropertyValueFactory<>("remainingDays"));
        remainingDaysCol.setPrefWidth(80);
        remainingDaysCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String days, boolean empty) {
                super.updateItem(days, empty);
                if (empty || days == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(days);
                    try {
                        int d = Integer.parseInt(days);
                        String color;
                        if (d <= 30) color = "#F44336";
                        else if (d <= 90) color = "#FF9800";
                        else color = "#4CAF50";
                        setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold; -fx-font-size: 12px;");
                    } catch (NumberFormatException e) {
                        setStyle("-fx-font-size: 12px;");
                    }
                }
            }
        });

        TableColumn<DomainItem, String> groupNameCol = new TableColumn<>("分组");
        groupNameCol.setCellValueFactory(new PropertyValueFactory<>("domainGroupName"));
        groupNameCol.setPrefWidth(100);

        domainTable.getColumns().addAll(domainNameCol, statusCol, registrationDateCol, expirationDateCol, remainingDaysCol, groupNameCol);
        domainTable.setItems(domainItems);

        // 双击域名显示详情
        domainTable.setRowFactory(tv -> {
            TableRow<DomainItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showDomainDetail(row.getItem());
                }
            });
            return row;
        });

        // 右侧详情面板
        detailPane = new VBox(8);
        detailPane.setPadding(new Insets(10));
        detailPane.setPrefWidth(280);
        detailPane.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 0 1 0;");

        Label detailTitle = new Label("域名详情");
        detailTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        detailPane.getChildren().add(detailTitle);

        // 主布局：左表格 + 右详情
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(domainTable, detailPane);
        splitPane.setDividerPositions(0.7);

        setTop(toolBar);
        setCenter(splitPane);
    }

    private void loadDomains() {
        statusLabel.setText("加载中...");
        new Thread(() -> {
            try {
                List<Map<String, Object>> domains = AliyunService.getDomainList(config);
                Platform.runLater(() -> {
                    domainItems.clear();
                    for (Map<String, Object> domain : domains) {
                        String domainName = String.valueOf(domain.getOrDefault("domainName", ""));
                        String instanceId = String.valueOf(domain.getOrDefault("instanceId", ""));
                        String registrationDate = String.valueOf(domain.getOrDefault("registrationDate", ""));
                        String expirationDate = String.valueOf(domain.getOrDefault("expirationDate", ""));
                        String domainStatus = String.valueOf(domain.getOrDefault("domainStatus", ""));
                        String groupName = String.valueOf(domain.getOrDefault("domainGroupName", ""));
                        String remark = String.valueOf(domain.getOrDefault("remark", ""));
                        String domainType = String.valueOf(domain.getOrDefault("domainType", ""));
                        String registrar = String.valueOf(domain.getOrDefault("registrar", ""));

                        // 使用SDK提供的剩余天数差值
                        String remainingDays = "";
                        Object diffObj = domain.get("expirationCurrDateDiff");
                        if (diffObj instanceof Integer diff) {
                            remainingDays = String.valueOf(diff);
                        }

                        domainItems.add(new DomainItem(domainName, instanceId, registrationDate,
                                expirationDate, domainStatus, groupName, remark, remainingDays,
                                domainType, registrar));
                    }
                    statusLabel.setText("共 " + domains.size() + " 个域名");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("加载失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载域名列表: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "Aliyun-LoadDomains").start();
    }

    private void showDomainDetail(DomainItem item) {
        detailPane.getChildren().clear();

        Label title = new Label("域名详情");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        detailPane.getChildren().add(title);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(5, 0, 0, 0));

        int row = 0;
        addDetailRow(grid, row++, "域名", item.getDomainName());
        addDetailRow(grid, row++, "状态", item.getDomainStatus());
        addDetailRow(grid, row++, "注册时间", item.getRegistrationDate());
        addDetailRow(grid, row++, "到期时间", item.getExpirationDate());
        addDetailRow(grid, row++, "剩余天数", item.getRemainingDays());
        addDetailRow(grid, row++, "域名类型", item.getDomainType());
        addDetailRow(grid, row++, "分组", item.getDomainGroupName());
        addDetailRow(grid, row++, "注册商", item.getRegistrar());
        addDetailRow(grid, row++, "备注", item.getRemark());
        addDetailRow(grid, row, "实例ID", item.getInstanceId());

        detailPane.getChildren().add(grid);
    }

    private void addDetailRow(GridPane grid, int row, String label, String value) {
        Label keyLabel = new Label(label + ":");
        keyLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #555;");
        Label valLabel = new Label(value == null || "null".equals(value) ? "" : value);
        valLabel.setStyle("-fx-font-size: 12px;");
        valLabel.setWrapText(true);
        valLabel.setMaxWidth(200);
        grid.add(keyLabel, 0, row);
        grid.add(valLabel, 1, row);
    }

    /**
     * 域名表格数据项
     */
    public static class DomainItem {
        private final String domainName;
        private final String instanceId;
        private final String registrationDate;
        private final String expirationDate;
        private final String domainStatus;
        private final String domainGroupName;
        private final String remark;
        private final String remainingDays;
        private final String domainType;
        private final String registrar;

        public DomainItem(String domainName, String instanceId, String registrationDate,
                          String expirationDate, String domainStatus, String domainGroupName,
                          String remark, String remainingDays, String domainType, String registrar) {
            this.domainName = domainName;
            this.instanceId = instanceId;
            this.registrationDate = registrationDate;
            this.expirationDate = expirationDate;
            this.domainStatus = domainStatus;
            this.domainGroupName = domainGroupName;
            this.remark = remark;
            this.remainingDays = remainingDays;
            this.domainType = domainType;
            this.registrar = registrar;
        }

        public String getDomainName() { return domainName; }
        public String getInstanceId() { return instanceId; }
        public String getRegistrationDate() { return registrationDate; }
        public String getExpirationDate() { return expirationDate; }
        public String getDomainStatus() { return domainStatus; }
        public String getDomainGroupName() { return domainGroupName; }
        public String getRemark() { return remark; }
        public String getRemainingDays() { return remainingDays; }
        public String getDomainType() { return domainType; }
        public String getRegistrar() { return registrar; }
    }
}
