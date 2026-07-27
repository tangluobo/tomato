package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格数据展示视图：包含工具栏、TableView和分页状态栏
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

    // 排序状态
    private String sortColumn;
    private boolean sortDescending = false;
    // 表头事件过滤器是否已安装
    private boolean headerEventFilterInstalled = false;

    // 主键列名缓存
    private List<String> primaryKeyColumns;

    // 列名缓存（数据列，不含行选择器列）
    private List<String> dataColumnNames = new ArrayList<>();

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
        MenuItem deleteItem = new MenuItem();
        deleteItem.setStyle("-fx-text-fill: #c00;");
        deleteItem.setOnAction(e -> handleDeleteSelectedRows());
        contextMenu.getItems().add(deleteItem);

        // 只在数据行区域显示右键菜单，表头区域不显示
        tableView.setOnContextMenuRequested(event -> {
            // 检查右键是否在表头区域
            Node target = event.getPickResult().getIntersectedNode();
            while (target != null && target != tableView) {
                if (target.getStyleClass().contains("column-header") ||
                    target.getStyleClass().contains("column-header-background") ||
                    target.getStyleClass().contains("filler") ||
                    target.getStyleClass().contains("nested-column-header")) {
                    // 在表头区域，不显示行右键菜单
                    event.consume();
                    return;
                }
                target = target.getParent();
            }
            int count = (int) tableView.getSelectionModel().getSelectedItems().stream().distinct().count();
            deleteItem.setText("删除" + (count > 0 ? count : 1) + "条数据");
            contextMenu.show(tableView, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    /**
     * 鼠标拖拽选中多个cell
     */
    private void setupDragSelection() {
        // 记录拖拽起始cell
        final int[] dragStart = {-1, -1}; // [row, colIndex in tableView.getColumns()]
        final boolean[] dragging = {false};

        tableView.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            // 找到点击的cell位置
            int[] cellPos = getCellPositionAt(event);
            if (cellPos == null) return;
            dragStart[0] = cellPos[0];
            dragStart[1] = cellPos[1];
            dragging[0] = false;
            // 清除已有选中，选中起始cell
            tableView.getSelectionModel().clearSelection();
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(cellPos[1]);
            tableView.getSelectionModel().select(cellPos[0], col);
        });

        tableView.setOnMouseDragged(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (dragStart[0] < 0) return;
            int[] cellPos = getCellPositionAt(event);
            if (cellPos == null) return;
            dragging[0] = true;
            int endRow = cellPos[0];
            int endCol = cellPos[1];
            // 范围选中
            int minRow = Math.min(dragStart[0], endRow);
            int maxRow = Math.max(dragStart[0], endRow);
            int minCol = Math.min(dragStart[1], endCol);
            int maxCol = Math.max(dragStart[1], endCol);
            tableView.getSelectionModel().clearSelection();
            for (int r = minRow; r <= maxRow; r++) {
                for (int c = minCol; c <= maxCol; c++) {
                    TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(c);
                    tableView.getSelectionModel().select(r, col);
                }
            }
        });

        tableView.setOnMouseReleased(event -> {
            dragStart[0] = -1;
            dragging[0] = false;
        });
    }

    /**
     * 根据鼠标事件位置获取对应的cell坐标 [row, colIndex]
     */
    private int[] getCellPositionAt(javafx.scene.input.MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        // 向上查找TableCell
        while (target != null && target != tableView) {
            if (target instanceof TableCell<?, ?> cell) {
                if (cell.getTableColumn() != null && cell.getTableRow() != null) {
                    int row = cell.getTableRow().getIndex();
                    int col = tableView.getColumns().indexOf(cell.getTableColumn());
                    if (col >= 0) {
                        return new int[]{row, col};
                    }
                }
            }
            target = target.getParent();
        }
        return null;
    }

    /**
     * 处理删除选中行
     */
    private void handleDeleteSelectedRows() {
        if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()) return;

        // cell selection模式下getSelectedItems可能包含重复行，需去重
        List<ObservableList<String>> selectedRows = tableView.getSelectionModel().getSelectedItems()
                .stream().distinct().toList();
        if (selectedRows.isEmpty()) return;

        int count = selectedRows.size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除行");
        confirm.setHeaderText(null);
        confirm.setContentText("确定删除" + count + "条数据？此操作不可撤销！");
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;

            // 复制选中行数据（避免在删除过程中ObservableList变化）
            List<ObservableList<String>> rowsToDelete = new ArrayList<>(selectedRows);
            List<String> dataColumns = getDataColumnNames();

            new Thread(() -> {
                try {
                    int deleted = DatabaseService.deleteRowsByPrimaryKeys(
                            config, databaseName, tableName,
                            primaryKeyColumns, dataColumns, rowsToDelete);
                    Platform.runLater(() -> {
                        tableView.getItems().removeAll(rowsToDelete);
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

    /**
     * 获取数据列名（跳过行选择器列）
     */
    private List<String> getDataColumnNames() {
        List<String> cols = new ArrayList<>();
        for (TableColumn<?, ?> col : tableView.getColumns()) {
            if (!ROW_SELECTOR_COL.equals(col.getUserData())) {
                cols.add(col.getText());
            }
        }
        return cols;
    }

    private void initializeUI() {
        // 工具栏
        HBox toolBar = createToolBar();

        // TableView
        tableView = new TableView<>();
        tableView.setEditable(true);
        // 禁用默认排序，排序由右键菜单控制
        tableView.setSortPolicy(param -> false);
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0;");
        tableView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tableView.getSelectionModel().setCellSelectionEnabled(true);

        // 鼠标拖拽选中多个cell
        setupDragSelection();
        // 布局后移除内部节点的默认padding/border，消除左侧间隔
        tableView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                stripPaddingRecursive(tableView);
            }
        });

        // 加载指示器
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(40, 40);
        loadingIndicator.setVisible(false);

        // ScrollPane包裹TableView：提供全宽水平滚动条，TableView自身只负责垂直滚动
        ScrollPane tableScrollPane = new ScrollPane(tableView);
        tableScrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        tableScrollPane.setFitToHeight(true);
        tableScrollPane.setFitToWidth(false);
        tableScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        tableScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        centerPane = new StackPane(tableScrollPane, loadingIndicator);
        centerPane.setPadding(Insets.EMPTY);

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

        this.setTop(toolBar);
        this.setCenter(centerPane);
        this.setBottom(statusBar);
        this.setPadding(Insets.EMPTY);
    }

    /**
     * 创建工具栏：添加、删除、更新、刷新按钮（图标+名称）
     */
    private HBox createToolBar() {
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(4, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);

        // 添加按钮：绿色加号
        Button addBtn = createToolBarButton("添加", createAddIcon());
        addBtn.setOnAction(e -> handleAddRow());

        // 删除按钮：红色减号/叉号
        Button deleteBtn = createToolBarButton("删除", createDeleteIcon());
        deleteBtn.setOnAction(e -> handleDeleteSelectedRows());

        // 更新按钮：蓝色上箭头/提交
        Button updateBtn = createToolBarButton("更新", createUpdateIcon());
        updateBtn.setOnAction(e -> handleUpdateSubmit());

        // 分隔符
        Separator separator = new Separator();
        separator.setOrientation(javafx.geometry.Orientation.VERTICAL);
        separator.setPadding(new Insets(2, 4, 2, 4));

        // 刷新按钮：环形箭头
        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> refreshData());

        toolBar.getChildren().addAll(addBtn, deleteBtn, updateBtn, separator, refreshBtn);
        return toolBar;
    }

    /**
     * 创建工具栏按钮（图标+文字）
     */
    private Button createToolBarButton(String text, Node icon) {
        Button btn = new Button(text);
        btn.getStyleClass().add("toolbar-button");
        btn.setStyle("-fx-font-size: 12px; -fx-padding: 4 8; -fx-content-display: LEFT; -fx-graphic-text-gap: 4;");
        if (icon != null) {
            btn.setGraphic(icon);
        }
        return btn;
    }

    /** 添加图标：绿色加号 */
    private Node createAddIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Rectangle bg = new Rectangle(14, 14);
        bg.setFill(Color.valueOf("#4CAF50"));
        bg.setArcWidth(3);
        bg.setArcHeight(3);
        Line h = new Line(3, 7, 11, 7);
        h.setStroke(Color.WHITE);
        h.setStrokeWidth(2);
        Line v = new Line(7, 3, 7, 11);
        v.setStroke(Color.WHITE);
        v.setStrokeWidth(2);
        g.getChildren().addAll(bg, h, v);
        return g;
    }

    /** 删除图标：红色减号 */
    private Node createDeleteIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Rectangle bg = new Rectangle(14, 14);
        bg.setFill(Color.valueOf("#E53935"));
        bg.setArcWidth(3);
        bg.setArcHeight(3);
        Line h = new Line(3, 7, 11, 7);
        h.setStroke(Color.WHITE);
        h.setStrokeWidth(2);
        g.getChildren().addAll(bg, h);
        return g;
    }

    /** 更新图标：蓝色上箭头（提交） */
    private Node createUpdateIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Rectangle bg = new Rectangle(14, 14);
        bg.setFill(Color.valueOf("#1E88E5"));
        bg.setArcWidth(3);
        bg.setArcHeight(3);
        Polygon arrow = new Polygon(7, 2, 12, 8, 9, 8, 9, 12, 5, 12, 5, 8, 2, 8);
        arrow.setFill(Color.WHITE);
        g.getChildren().addAll(bg, arrow);
        return g;
    }

    /** 刷新图标：灰色环形箭头 */
    private Node createRefreshIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Arc arc = new Arc(7, 7, 6, 6, 45, 270);
        arc.setType(ArcType.OPEN);
        arc.setStroke(Color.valueOf("#666666"));
        arc.setStrokeWidth(2);
        arc.setFill(null);
        Polygon arrowHead = new Polygon(12, 3, 14, 7, 10, 6);
        arrowHead.setFill(Color.valueOf("#666666"));
        g.getChildren().addAll(arc, arrowHead);
        return g;
    }

    /**
     * 处理添加行：插入一行DEFAULT值后刷新
     */
    private void handleAddRow() {
        List<String> columns = getDataColumnNames();
        if (columns.isEmpty()) return;

        new Thread(() -> {
            try {
                DatabaseService.insertEmptyRow(config, databaseName, tableName, columns);
                Platform.runLater(() -> refreshData());
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.setTitle("添加失败");
                    err.setHeaderText(null);
                    err.setContentText("添加行失败: " + e.getMessage());
                    err.showAndWait();
                });
            }
        }, "DB-InsertRow").start();
    }

    /**
     * 处理更新提交：提交所有编辑中的单元格
     */
    private void handleUpdateSubmit() {
        if (tableView.getEditingCell() != null) {
            tableView.edit(-1, null);
        }
    }

    /**
     * 递归移除 TableView 内部节点的默认 padding，消除左侧间隔
     */
    private void stripPaddingRecursive(Node node) {
        if (node instanceof Region region) {
            // 不修改 table-cell 和 column-header 的 padding（它们需要内容间距）
            if (!region.getStyleClass().contains("table-cell")
                    && !region.getStyleClass().contains("column-header")
                    && !region.getStyleClass().contains("table-row-cell")) {
                region.setPadding(Insets.EMPTY);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                stripPaddingRecursive(child);
            }
        }
    }

    private void loadData(int page) {
        loadingIndicator.setVisible(true);
        tableView.setDisable(true);

        new Thread(() -> {
            try {
                TableRowData data = DatabaseService.queryTableData(config, databaseName, tableName, page, DEFAULT_PAGE_SIZE, sortColumn, sortDescending);
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

    /** 行选择器列的标识名，用于在获取数据列名时跳过 */
    private static final String ROW_SELECTOR_COL = "__ROW_SELECTOR__";

    private void updateTableView(TableRowData data) {
        tableView.getColumns().clear();
        tableView.getItems().clear();

        // 缓存数据列名
        dataColumnNames = new ArrayList<>(data.getColumnNames());

        // 创建行选择器列：选中行显示黑色实心三角箭头
        TableColumn<ObservableList<String>, String> selectorCol = new TableColumn<>();
        selectorCol.setPrefWidth(15);
        selectorCol.setMaxWidth(15);
        selectorCol.setMinWidth(15);
        selectorCol.setSortable(false);
        selectorCol.setReorderable(false);
        selectorCol.setStyle("-fx-alignment: CENTER;");
        // 用userData标记此列，删除时跳过
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
                // 左侧加网格线
                setStyle("-fx-border-color: transparent #BEBEBC transparent #BEBEBC; -fx-border-width: 0 1 0 1;");
                // 点击行选择器列时选中整行
                setOnMousePressed(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        int row = getTableRow().getIndex();
                        if (event.isControlDown()) {
                            if (isRowSelected(row)) {
                                tableView.getSelectionModel().clearSelection(row);
                            } else {
                                tableView.getSelectionModel().select(row);
                            }
                        } else if (event.isShiftDown()) {
                            tableView.getSelectionModel().selectRange(row, tableView.getSelectionModel().getFocusedIndex());
                        } else {
                            tableView.getSelectionModel().clearSelection();
                            tableView.getSelectionModel().select(row);
                        }
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                // 清理旧监听
                if (selectionListener != null) {
                    tableView.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                    selectionListener = null;
                }

                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    arrow.setVisible(false);
                    return;
                }

                // 初始状态
                arrow.setVisible(isRowSelected(getTableRow().getIndex()));

                // 监听选中cells变化
                selectionListener = obs -> {
                    if (getTableRow() != null) {
                        arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                    }
                };
                tableView.getSelectionModel().getSelectedCells().addListener(selectionListener);
            }
        });
        tableView.getColumns().add(selectorCol);

        // 创建数据列（可编辑）
        List<String> columnNames = data.getColumnNames();
        for (int i = 0; i < columnNames.size(); i++) {
            final int colIndex = i;
            final String colName = columnNames.get(i);
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(columnNames.get(i));
            // 根据表头文字长度动态设置列宽（每个字符约8px，加padding）
            int headerLen = columnNames.get(i).length();
            col.setPrefWidth(Math.max(headerLen * 8 + 16, 60));
            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                if (colIndex < row.size()) {
                    return new javafx.beans.property.SimpleStringProperty(row.get(colIndex));
                }
                return new javafx.beans.property.SimpleStringProperty("");
            });
            col.setCellFactory(tc -> new EditableTableCell());
            col.setOnEditCommit(event -> {
                ObservableList<String> row = event.getRowValue();
                String oldValue = row.get(colIndex);
                String newValue = event.getNewValue();
                if (oldValue.equals(newValue)) return;

                // 更新数据模型
                row.set(colIndex, newValue);

                // 提交到数据库
                commitCellUpdate(row, colIndex, oldValue, newValue);
            });

            tableView.getColumns().add(col);
        }

        tableView.setItems(data.getRows());
        // 布局完成后绑定表头点击事件和排序箭头
        bindColumnHeaderEvents();
    }

    /**
     * 绑定所有表头点击事件（在布局完成后调用）
     */
    private void bindColumnHeaderEvents() {
        Platform.runLater(() -> {
            // 只安装一次事件过滤器
            if (!headerEventFilterInstalled) {
                headerEventFilterInstalled = true;
                // 使用事件过滤器在捕获阶段处理，避免被子节点拦截
                tableView.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                    if (event.getButton() != MouseButton.PRIMARY && event.getButton() != MouseButton.SECONDARY) return;

                    // 查找点击的表头
                    Node target = event.getPickResult().getIntersectedNode();
                    TableColumnHeader header = null;
                    while (target != null && target != tableView) {
                        if (target instanceof TableColumnHeader tch) {
                            header = tch;
                            break;
                        }
                        target = target.getParent();
                    }
                    if (header == null) return;

                    var colBase = header.getTableColumn();
                    if (colBase == null) return;

                    // 查找匹配的TableColumn
                    @SuppressWarnings("unchecked")
                    TableColumn<ObservableList<String>, ?> matchedCol = null;
                    int tableColIndex = -1;
                    for (int i = 0; i < tableView.getColumns().size(); i++) {
                        TableColumn<ObservableList<String>, ?> tc = tableView.getColumns().get(i);
                        if (tc == colBase) {
                            matchedCol = tc;
                            tableColIndex = i;
                            break;
                        }
                    }
                    if (matchedCol == null || ROW_SELECTOR_COL.equals(matchedCol.getUserData())) return;

                    String colName = matchedCol.getText();
                    event.consume();

                    if (event.getButton() == MouseButton.SECONDARY) {
                        showSortMenu(header, colName);
                    } else if (event.getButton() == MouseButton.PRIMARY) {
                        selectColumnByTableIndex(tableColIndex);
                    }
                });
            }

            // 每次数据刷新后更新排序箭头
            tableView.lookupAll(".column-header").forEach(headerNode -> {
                if (headerNode instanceof TableColumnHeader header) {
                    var colBase = header.getTableColumn();
                    if (colBase == null) return;
                    @SuppressWarnings("unchecked")
                    TableColumn<ObservableList<String>, ?> matchedCol = null;
                    for (int i = 0; i < tableView.getColumns().size(); i++) {
                        if (tableView.getColumns().get(i) == colBase) {
                            matchedCol = tableView.getColumns().get(i);
                            break;
                        }
                    }
                    if (matchedCol != null && !ROW_SELECTOR_COL.equals(matchedCol.getUserData())) {
                        updateSortArrow(header, matchedCol.getText());
                    }
                }
            });
        });
    }

    /**
     * 在表头节点中显示/隐藏排序箭头
     */
    private void updateSortArrow(TableColumnHeader header, String colName) {
        // 查找表头中的label
        Label headerLabel = null;
        for (Node child : header.getChildrenUnmodifiable()) {
            if (child instanceof Label) {
                headerLabel = (Label) child;
                break;
            }
        }
        if (headerLabel == null) return;

        if (colName.equals(sortColumn)) {
            Node arrow = createSortArrow(sortDescending);
            // 设置为label的graphic
            headerLabel.setGraphic(arrow);
            headerLabel.setContentDisplay(ContentDisplay.RIGHT);
        } else {
            headerLabel.setGraphic(null);
        }
    }

    /**
     * 判断指定行是否有任何cell被选中
     */
    private boolean isRowSelected(int rowIndex) {
        for (TablePosition<?, ?> pos : tableView.getSelectionModel().getSelectedCells()) {
            if (pos.getRow() == rowIndex) return true;
        }
        return false;
    }

    /**
     * 选中整列
     */
    private void selectColumnByTableIndex(int tableColIndex) {
        tableView.getSelectionModel().clearSelection();
        TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(tableColIndex);
        for (int row = 0; row < tableView.getItems().size(); row++) {
            tableView.getSelectionModel().select(row, col);
        }
    }

    /**
     * 弹出排序菜单
     */
    private void showSortMenu(Node anchor, String colName) {
        ContextMenu sortMenu = new ContextMenu();
        MenuItem ascItem = new MenuItem("正序排列");
        ascItem.setOnAction(e -> {
            sortColumn = colName;
            sortDescending = false;
            loadData(1);
        });
        MenuItem descItem = new MenuItem("倒序排列");
        descItem.setOnAction(e -> {
            sortColumn = colName;
            sortDescending = true;
            loadData(1);
        });
        MenuItem clearSortItem = new MenuItem("取消排序");
        clearSortItem.setOnAction(e -> {
            sortColumn = null;
            sortDescending = false;
            loadData(1);
        });
        sortMenu.getItems().addAll(ascItem, descItem, new SeparatorMenuItem(), clearSortItem);
        sortMenu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /**
     * 创建排序箭头图标
     */
    private Node createSortArrow(boolean descending) {
        Polygon arrow = new Polygon();
        if (descending) {
            // 下箭头（放大）
            arrow.getPoints().addAll(2.0, 0.0, 10.0, 0.0, 6.0, 7.0);
        } else {
            // 上箭头（放大）
            arrow.getPoints().addAll(6.0, 0.0, 10.0, 7.0, 2.0, 7.0);
        }
        arrow.setFill(Color.valueOf("#3592CB"));
        return arrow;
    }

    /**
     * 提交单元格更新到数据库
     */
    private void commitCellUpdate(ObservableList<String> row, int colIndex, String oldValue, String newValue) {
        if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()) {
            // 无主键，无法定位行，回滚
            Platform.runLater(() -> {
                row.set(colIndex, oldValue);
                Alert warn = new Alert(Alert.AlertType.WARNING);
                warn.setTitle("无法更新");
                warn.setHeaderText(null);
                warn.setContentText("该表无主键，无法定位行进行更新");
                warn.showAndWait();
            });
            return;
        }

        List<String> dataColumns = getDataColumnNames();
        new Thread(() -> {
            try {
                int affected = DatabaseService.updateCell(config, databaseName, tableName,
                        primaryKeyColumns, dataColumns, row, colIndex, newValue);
                if (affected == 0) {
                    Platform.runLater(() -> {
                        row.set(colIndex, oldValue);
                        Alert warn = new Alert(Alert.AlertType.WARNING);
                        warn.setTitle("更新失败");
                        warn.setHeaderText(null);
                        warn.setContentText("未找到匹配行，数据可能已被其他操作修改，请刷新后重试");
                        warn.showAndWait();
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    row.set(colIndex, oldValue);
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.setTitle("更新失败");
                    err.setHeaderText(null);
                    err.setContentText("更新单元格失败: " + e.getMessage());
                    err.showAndWait();
                });
            }
        }, "DB-UpdateCell").start();
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

    public void applyTableConfig(GlobalConfig config) {
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                config.getTableFontName(), config.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0;");
    }

    public void refreshData() {
        loadData(currentPage);
    }

    /**
     * 可编辑单元格：选中时蓝色背景，进入编辑时白色背景+蓝色边框
     */
    private static class EditableTableCell extends TableCell<ObservableList<String>, String> {
        private TextField textField;

        public EditableTableCell() {
            super();
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (textField == null) {
                createTextField();
            }
            setText(null);
            setGraphic(textField);
            textField.setText(getItem() != null ? getItem() : "");
            textField.selectAll();
            textField.requestFocus();
            // 编辑状态：白色背景+蓝色边框覆盖表格线
            setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 2; -fx-padding: 0; -fx-text-fill: black;");
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(getItem() != null ? getItem() : "");
            setGraphic(null);
            setStyle("");
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-border-color: transparent; -fx-padding: 0;");
            } else {
                if (isEditing()) {
                    if (textField != null) {
                        textField.setText(getItem() != null ? getItem() : "");
                    }
                    setText(null);
                    setGraphic(textField);
                    setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 2; -fx-padding: 0; -fx-text-fill: black;");
                } else {
                    setText(item != null ? item : "");
                    setGraphic(null);
                    setStyle("");
                }
            }
        }

        private void createTextField() {
            textField = new TextField(getItem() != null ? getItem() : "");
            textField.setMinWidth(this.getWidth() - this.getGraphicTextGap() * 2);
            // 白色背景，无边框，看起来是cell本身在编辑
            textField.setStyle("-fx-background-color: white; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0 4; -fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-text-fill: black;");
            textField.setOnAction(e -> commitEdit(textField.getText()));
            textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused) {
                    commitEdit(textField.getText());
                }
            });
        }
    }
}
