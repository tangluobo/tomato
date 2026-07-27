package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.util.*;

/**
 * 表结构展示视图：以表格形式显示表的列信息（字段名、类型、长度、是否可空、是否主键、自增、默认值、注释）
 */
public class TableStructureView extends BorderPane {

    private final ConnectionConfig config;
    private final String databaseName;
    private final String tableName;

    private TableView<ObservableList<String>> tableView;
    private ProgressIndicator loadingIndicator;
    private Label statusLabel;

    public TableStructureView(ConnectionConfig config, String databaseName, String tableName) {
        this.config = config;
        this.databaseName = databaseName;
        this.tableName = tableName;

        initializeUI();
        loadStructure();
    }

    private void initializeUI() {
        // 工具栏
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(4, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-font-size: 12px; -fx-padding: 4 8; -fx-content-display: LEFT;");
        refreshBtn.setOnAction(e -> loadStructure());
        toolBar.getChildren().add(refreshBtn);

        // TableView
        tableView = new TableView<>();
        tableView.setEditable(false);
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0;");
        tableView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        // 加载指示器
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(40, 40);
        loadingIndicator.setVisible(false);

        // 状态栏
        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 12px;");
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        StackPane centerPane = new StackPane(tableView, loadingIndicator);

        this.setTop(toolBar);
        this.setCenter(centerPane);
        this.setBottom(statusBar);
        this.setPadding(Insets.EMPTY);
    }

    public void loadStructure() {
        loadingIndicator.setVisible(true);
        tableView.setDisable(true);

        new Thread(() -> {
            try {
                List<Map<String, String>> columns = DatabaseService.getTableColumns(config, databaseName, tableName);
                Platform.runLater(() -> {
                    updateTableView(columns);
                    statusLabel.setText("共 " + columns.size() + " 个字段");
                    loadingIndicator.setVisible(false);
                    tableView.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("加载失败: " + e.getMessage());
                    loadingIndicator.setVisible(false);
                    tableView.setDisable(false);
                });
            }
        }, "DB-LoadTableStructure").start();
    }

    private void updateTableView(List<Map<String, String>> columns) {
        tableView.getColumns().clear();
        tableView.getItems().clear();

        if (columns.isEmpty()) return;

        // 列标题名（从第一行的key集合获取，保持LinkedHashMap的插入顺序）
        List<String> columnTitles = new ArrayList<>(columns.get(0).keySet());

        // 创建列
        for (int i = 0; i < columnTitles.size(); i++) {
            final int colIndex = i;
            String title = columnTitles.get(i);
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(title);

            // 根据标题设置列宽
            int prefWidth = switch (title) {
                case "字段名" -> 150;
                case "类型" -> 100;
                case "长度" -> 60;
                case "可为空", "主键", "自增" -> 60;
                case "默认值" -> 120;
                case "注释" -> 200;
                default -> 80;
            };
            col.setPrefWidth(prefWidth);
            col.setMinWidth(50);

            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                if (colIndex < row.size()) {
                    return new javafx.beans.property.SimpleStringProperty(row.get(colIndex));
                }
                return new javafx.beans.property.SimpleStringProperty("");
            });

            col.setCellFactory(tc -> new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setStyle("");
                    } else {
                        setText(item);
                        // 主键列高亮
                        int pkColIndex = columnTitles.indexOf("主键");
                        if (pkColIndex >= 0) {
                            TableRow<?> currentRow = getTableRow();
                            if (currentRow != null && currentRow.getItem() instanceof ObservableList row) {
                                String isPk = pkColIndex < row.size() ? (String) row.get(pkColIndex) : "";
                                if ("是".equals(isPk)) {
                                    setStyle("-fx-font-weight: bold; -fx-text-fill: #1E88E5;");
                                    return;
                                }
                            }
                        }
                        setStyle("");
                    }
                }
            });

            tableView.getColumns().add(col);
        }

        // 填充数据行
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (Map<String, String> colMap : columns) {
            ObservableList<String> row = FXCollections.observableArrayList();
            for (String title : columnTitles) {
                row.add(colMap.getOrDefault(title, ""));
            }
            rows.add(row);
        }
        tableView.setItems(rows);
    }

    public void applyTableConfig(GlobalConfig config) {
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                config.getTableFontName(), config.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0;");
    }
}
