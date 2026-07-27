package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格数据展示视图：包含TableView和分页状态栏
 */
public class TableDataView extends BorderPane {

    private static final int DEFAULT_PAGE_SIZE = 1000;

    private final ConnectionConfig config;
    private final String databaseName;
    private final String tableName;

    private TableView<ObservableList<String>> tableView;
    private Label pageInfoLabel;
    private Button firstPageBtn;
    private Button prevPageBtn;
    private Button nextPageBtn;
    private Button lastPageBtn;
    private TextField jumpPageField;
    private Button jumpBtn;
    private StackPane centerPane;
    private ProgressIndicator loadingIndicator;

    private int currentPage = 1;
    private int totalPages = 0;
    private long totalCount = 0;

    // 主键列名缓存
    private List<String> primaryKeyColumns;

    public TableDataView(ConnectionConfig config, String databaseName, String tableName) {
        this.config = config;
        this.databaseName = databaseName;
        this.tableName = tableName;

        initializeUI();
        loadPrimaryKeys();
        loadData(1);
    }

    /**
     * 异步加载主键信息
     */
    private void loadPrimaryKeys() {
        new Thread(() -> {
            try {
                List<String> pks = DatabaseService.getPrimaryKeys(config, databaseName, tableName);
                Platform.runLater(() -> {
                    this.primaryKeyColumns = pks;
                    setupRowContextMenu();
                });
            } catch (Exception e) {
                // 获取主键失败时不影响正常使用，仅不提供删除功能
                this.primaryKeyColumns = new ArrayList<>();
            }
        }, "DB-LoadPrimaryKeys").start();
    }

    /**
     * 设置表格行右键菜单：如果有主键则提供删除功能
     */
    private void setupRowContextMenu() {
        if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()) return;

        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除所选行");
        deleteItem.setStyle("-fx-text-fill: #c00;");
        deleteItem.setOnAction(e -> handleDeleteSelectedRows());
        contextMenu.getItems().add(deleteItem);

        tableView.setContextMenu(contextMenu);

        // 右键时自动选中行
        tableView.setOnContextMenuRequested(event -> {
            // 如果右键的行未被选中，则只选中该行
            if (tableView.getSelectionModel().getSelectedItems().isEmpty()) {
                // 不做额外处理，JavaFX默认行为会选中
            }
        });
    }

    /**
     * 处理删除选中行
     */
    private void handleDeleteSelectedRows() {
        if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()) return;

        ObservableList<ObservableList<String>> selectedRows = tableView.getSelectionModel().getSelectedItems();
        if (selectedRows.isEmpty()) return;

        int count = selectedRows.size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除行");
        confirm.setHeaderText(null);
        confirm.setContentText("确定要删除选中的 " + count + " 行吗？此操作不可撤销！");
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;

            // 复制选中行数据（避免在删除过程中ObservableList变化）
            List<ObservableList<String>> rowsToDelete = new ArrayList<>(selectedRows);
            // 获取当前列名（跳过行号列#）
            List<String> dataColumns = new ArrayList<>();
            for (TableColumn<?, ?> col : tableView.getColumns()) {
                String colName = col.getText();
                if (!"#".equals(colName)) {
                    dataColumns.add(colName);
                }
            }

            new Thread(() -> {
                try {
                    int deleted = DatabaseService.deleteRowsByPrimaryKeys(
                            config, databaseName, tableName,
                            primaryKeyColumns, dataColumns, rowsToDelete);
                    Platform.runLater(() -> {
                        // 从TableView中移除已删除的行
                        tableView.getItems().removeAll(rowsToDelete);
                        // 更新总行数
                        totalCount -= deleted;
                        totalPages = (int) Math.ceil((double) totalCount / DEFAULT_PAGE_SIZE);
                        if (totalPages < 1) totalPages = 1;
                        if (currentPage > totalPages) currentPage = totalPages;
                        updateStatusBar();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert err = new Alert(Alert.AlertType.ERROR);
                        err.setTitle("删除失败");
                        err.setHeaderText(null);
                        err.setContentText("删除行失败: " + e.getMessage());
                        err.showAndWait();
                    });
                }
            }, "DB-DeleteRows").start();
        });
    }

    private void initializeUI() {
        // TableView
        tableView = new TableView<>();
        tableView.setStyle("-fx-font-size: 12px;");
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // 加载指示器
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(40, 40);

        centerPane = new StackPane(tableView, loadingIndicator);
        loadingIndicator.setVisible(false);

        // 分页状态栏
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        pageInfoLabel = new Label();
        pageInfoLabel.setStyle("-fx-font-size: 12px;");

        firstPageBtn = new Button("首页");
        firstPageBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        prevPageBtn = new Button("上一页");
        prevPageBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        nextPageBtn = new Button("下一页");
        nextPageBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        lastPageBtn = new Button("尾页");
        lastPageBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");

        Label jumpLabel = new Label("跳到");
        jumpLabel.setStyle("-fx-font-size: 12px;");
        jumpPageField = new TextField();
        jumpPageField.setPrefWidth(50);
        jumpPageField.setStyle("-fx-font-size: 12px; -fx-padding: 3 5;");
        Label pageLabel = new Label("页");
        pageLabel.setStyle("-fx-font-size: 12px;");
        jumpBtn = new Button("跳转");
        jumpBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");

        // 事件绑定
        firstPageBtn.setOnAction(e -> loadData(1));
        prevPageBtn.setOnAction(e -> { if (currentPage > 1) loadData(currentPage - 1); });
        nextPageBtn.setOnAction(e -> { if (currentPage < totalPages) loadData(currentPage + 1); });
        lastPageBtn.setOnAction(e -> loadData(totalPages));
        jumpBtn.setOnAction(e -> {
            try {
                int page = Integer.parseInt(jumpPageField.getText().trim());
                if (page >= 1 && page <= totalPages) {
                    loadData(page);
                }
            } catch (NumberFormatException ignored) {}
        });
        jumpPageField.setOnAction(e -> jumpBtn.fire());

        statusBar.getChildren().addAll(
            pageInfoLabel,
            firstPageBtn, prevPageBtn, nextPageBtn, lastPageBtn,
            jumpLabel, jumpPageField, pageLabel, jumpBtn
        );

        this.setCenter(centerPane);
        this.setBottom(statusBar);
    }

    private void loadData(int page) {
        loadingIndicator.setVisible(true);
        tableView.setDisable(true);

        new Thread(() -> {
            try {
                TableRowData data = DatabaseService.queryTableData(config, databaseName, tableName, page, DEFAULT_PAGE_SIZE);
                Platform.runLater(() -> {
                    currentPage = data.getPage();
                    totalPages = data.getTotalPages();
                    totalCount = data.getTotalCount();
                    updateTableView(data);
                    updateStatusBar();
                    loadingIndicator.setVisible(false);
                    tableView.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    tableView.setDisable(false);
                    pageInfoLabel.setText("加载失败: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        }, "DB-LoadTableData").start();
    }

    private void updateTableView(TableRowData data) {
        tableView.getColumns().clear();
        tableView.getItems().clear();

        // 创建行号列
        TableColumn<ObservableList<String>, String> rownumCol = new TableColumn<>("#");
        rownumCol.setPrefWidth(50);
        rownumCol.setMaxWidth(60);
        rownumCol.setStyle("-fx-alignment: CENTER;");
        rownumCol.setCellValueFactory(param -> {
            int index = tableView.getItems().indexOf(param.getValue()) + 1 + (currentPage - 1) * DEFAULT_PAGE_SIZE;
            return new javafx.beans.property.SimpleStringProperty(String.valueOf(index));
        });
        tableView.getColumns().add(rownumCol);

        // 创建数据列
        List<String> columnNames = data.getColumnNames();
        for (int i = 0; i < columnNames.size(); i++) {
            final int colIndex = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(columnNames.get(i));
            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                if (colIndex < row.size()) {
                    return new javafx.beans.property.SimpleStringProperty(row.get(colIndex));
                }
                return new javafx.beans.property.SimpleStringProperty("");
            });
            tableView.getColumns().add(col);
        }

        tableView.setItems(data.getRows());
    }

    private void updateStatusBar() {
        if (totalCount == 0) {
            pageInfoLabel.setText("无数据");
        } else {
            pageInfoLabel.setText(String.format("第 %d / %d 页  |  共 %d 条", currentPage, totalPages, totalCount));
        }

        firstPageBtn.setDisable(currentPage <= 1);
        prevPageBtn.setDisable(currentPage <= 1);
        nextPageBtn.setDisable(currentPage >= totalPages);
        lastPageBtn.setDisable(currentPage >= totalPages);
        jumpBtn.setDisable(totalPages <= 1);
    }
}
