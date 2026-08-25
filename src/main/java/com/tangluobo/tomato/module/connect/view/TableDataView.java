package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.GlobalConfig;
import com.tangluobo.tomato.module.connect.RowState;
import com.tangluobo.tomato.module.connect.TableRowData;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.tangluobo.tomato.utils.RowSelectorDragSelection;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

import java.util.*;

/**
 * 表格数据展示视图：包含工具栏、TableView和分页状态栏
 */
public class TableDataView extends BorderPane {

    private static final int DEFAULT_PAGE_SIZE = 1000;

    private final ConnectionConfig config;
    private final String databaseName;
    private final String schemaName;
    private final String tableName;

    private TableView<ObservableList<String>> tableView;
    private ScrollPane tableScrollPane;
    private Label pageInfoLabel;
    private Button firstPageBtn;
    private Button prevPageBtn;
    private Button nextPageBtn;
    private Button lastPageBtn;
    private TextField jumpPageField;
    private Button jumpBtn;
    private StackPane centerPane;
    private ProgressIndicator loadingIndicator;

    // 批量输入状态：选中多个单元格后直接键入，实时同步到所有选中单元格
    private boolean batchEditing = false;
    private String batchEditValue = "";
    /** 批量输入前的原始值（key: row,dataColIndex），用于 Esc 撤销 */
    private final Map<String, String> batchEditOriginals = new HashMap<>();

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
    private boolean isLoading = false;

    // 列名缓存（数据列，不含行选择器列）
    private List<String> dataColumnNames = new ArrayList<>();
    // 列类型缓存（java.sql.Types，与 dataColumnNames 一一对应，不含行选择器列）
    private List<Integer> dataColumnTypes = new ArrayList<>();

    // ---- 行状态追踪（延迟保存） ----
    /** 尚未持久化到数据库的新行 */
    private final Set<ObservableList<String>> newRows = new HashSet<>();
    /** 现有行的原始值（加载时的快照），用于检测哪些列被修改 */
    private final Map<ObservableList<String>, ObservableList<String>> originalValuesMap = new HashMap<>();

    public TableDataView(ConnectionConfig config, String databaseName, String tableName) {
        this(config, databaseName, null, tableName);
    }

    public TableDataView(ConnectionConfig config, String databaseName, String schemaName, String tableName) {
        this.config = config;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.tableName = tableName;

        initializeUI();
        loadData(1);
    }

    /**
     * 在当前线程中加载主键信息（与数据查询共用同一线程，避免JDBC连接并发使用）
     */
    private void loadPrimaryKeysInCurrentThread() {
        // 每次加载数据都重新查询主键：表结构可能被"设计表"修改，主键信息不能永久缓存；
        // 失败时也不静默置空列表，保留原值(可能为null)以便下次 loadData 能重试，避免"实际有主键却提示无主键"
        try {
            List<String> pks = DatabaseService.getPrimaryKeys(config, databaseName, schemaName, tableName);
            Platform.runLater(() -> {
                this.primaryKeyColumns = pks;
                setupRowContextMenu();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 设置表格行右键菜单：如果有主键则提供删除功能
     */
    private void setupRowContextMenu() {
        ContextMenu contextMenu = new ContextMenu();

        // 复制菜单项
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> handleCopySelectedCells());
        contextMenu.getItems().add(copyItem);

        // 删除菜单项（仅在有主键时可用）
        MenuItem deleteItem = new MenuItem();
        deleteItem.setStyle("-fx-text-fill: #c00;");
        deleteItem.setOnAction(e -> handleDeleteSelectedRows());
        boolean hasPrimaryKey = primaryKeyColumns != null && !primaryKeyColumns.isEmpty();
        deleteItem.setDisable(!hasPrimaryKey);
        contextMenu.getItems().add(new SeparatorMenuItem());
        contextMenu.getItems().add(deleteItem);

        // 刷新菜单项：重新加载当前页数据
        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refreshData());
        contextMenu.getItems().add(new SeparatorMenuItem());
        contextMenu.getItems().add(refreshItem);

        // 只在数据行区域显示右键菜单，表头区域不显示
        tableView.setOnContextMenuRequested(event -> {
            // 检查右键是否在表头区域
            Node target = event.getPickResult().getIntersectedNode();
            while (target != null && target != tableView) {
                if (target.getStyleClass().contains("column-header") ||
                    target.getStyleClass().contains("column-header-background") ||
                    target.getStyleClass().contains("filler") ||
                    target.getStyleClass().contains("nested-column-header")) {
                    event.consume();
                    return;
                }
                target = target.getParent();
            }
            int cellCount = tableView.getSelectionModel().getSelectedCells().size();
            copyItem.setText("复制" + (cellCount > 0 ? "(" + cellCount + "个单元格)" : ""));
            int count = (int) tableView.getSelectionModel().getSelectedItems().stream().distinct().count();
            deleteItem.setText("删除" + (count > 0 ? count : 1) + "条数据");
            contextMenu.show(tableView, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        // 点击其他位置时隐藏右键菜单（捕获阶段，早于行选择器列等节点 handler 的 consume）
        tableView.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });
    }

    // Shift选择锚点：记录最近一次普通点击的cell位置 [row, colIndex]
    private int[] anchorCell = {-1, -1};

    // ---- 拖拽范围选择矩形（Windows资源管理器风格） ----
    /** 范围选择矩形，从表格下方空白区域按下拖拽时显示 */
    private final Rectangle dragSelectRect = new Rectangle();
    /** 矩形起点（centerPane坐标） */
    private double dragSelectStartX = 0;
    private double dragSelectStartY = 0;
    /** 当前拖拽是否显示范围选择矩形（仅从表格下方空白区域开始时） */
    private boolean marqueeActive = false;

    /**
     * 鼠标拖拽选中多个cell + Shift点击范围选中
     */
    private void setupDragSelection() {
        // 记录拖拽起始cell
        final int[] dragStart = {-1, -1}; // [row, colIndex in tableView.getColumns()]
        final boolean[] dragging = {false};

        tableView.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            // 鼠标点击时结束批量输入（提交已输入内容）
            if (batchEditing) {
                commitBatchEdit();
            }
            // 找到点击的cell位置
            int[] cellPos = getCellPositionAt(event);
            if (cellPos == null) {
                // 点击空白区域（右侧空白或表格下方空白）：清除选中，不选中任何cell或行
                tableView.getSelectionModel().clearSelection();
                anchorCell[0] = -1;
                anchorCell[1] = -1;
                return;
            }

            if (event.isShiftDown() && anchorCell[0] >= 0) {
                // Shift+点击：从锚点到当前cell的矩形范围选中
                marqueeActive = false;
                dragSelectRect.setVisible(false);
                int minRow = Math.min(anchorCell[0], cellPos[0]);
                int maxRow = Math.max(anchorCell[0], cellPos[0]);
                int minCol = Math.min(anchorCell[1], cellPos[1]);
                int maxCol = Math.max(anchorCell[1], cellPos[1]);
                tableView.getSelectionModel().clearSelection();
                for (int r = minRow; r <= maxRow; r++) {
                    for (int c = minCol; c <= maxCol; c++) {
                        TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(c);
                        tableView.getSelectionModel().select(r, col);
                    }
                }
                event.consume();
                return;
            }

            dragStart[0] = cellPos[0];
            dragStart[1] = cellPos[1];
            dragging[0] = false;
            // 更新锚点
            anchorCell[0] = cellPos[0];
            anchorCell[1] = cellPos[1];
            // 清除已有选中，选中起始cell
            tableView.getSelectionModel().clearSelection();
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(cellPos[1]);
            tableView.getSelectionModel().select(cellPos[0], col);

            // 表格下方空白区域按下时，启用拖拽范围选择矩形（Windows资源管理器风格）
            marqueeActive = cellPos[0] >= tableView.getItems().size();
            Point2D start = centerPane.sceneToLocal(event.getSceneX(), event.getSceneY());
            dragSelectStartX = start.getX();
            dragSelectStartY = start.getY();
            if (marqueeActive) {
                updateDragSelectRect(start.getX(), start.getY());
            }
        });

        tableView.setOnMouseDragged(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (dragStart[0] < 0) return;
            // 矩形框跟随鼠标（即使经过表头等无cell区域也持续更新）
            if (marqueeActive) {
                Point2D p = centerPane.sceneToLocal(event.getSceneX(), event.getSceneY());
                updateDragSelectRect(p.getX(), p.getY());
            }
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
            marqueeActive = false;
            dragSelectRect.setVisible(false);
        });
    }

    /**
     * 更新拖拽范围选择矩形：从按下起点到当前鼠标位置（clamp在视口范围内）
     */
    private void updateDragSelectRect(double x, double y) {
        double viewW = centerPane.getWidth();
        double viewH = centerPane.getHeight();
        x = Math.max(0, Math.min(x, viewW));
        y = Math.max(0, Math.min(y, viewH));
        dragSelectRect.setX(Math.min(x, dragSelectStartX));
        dragSelectRect.setY(Math.min(y, dragSelectStartY));
        dragSelectRect.setWidth(Math.abs(x - dragSelectStartX));
        dragSelectRect.setHeight(Math.abs(y - dragSelectStartY));
        dragSelectRect.setVisible(true);
    }

    /**
     * 根据鼠标事件位置获取对应的cell坐标 [row, colIndex]
     * 点击右侧空白区域（TableRow 但非 TableCell）时返回该行和最后一列
     */
    private int[] getCellPositionAt(javafx.scene.input.MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        TableRow<?> clickedRow = null;
        // 向上查找TableCell 或 TableRow
        while (target != null && target != tableView) {
            if (clickedRow == null && target instanceof TableRow<?> row) {
                clickedRow = row;
            }
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
        // 点击的不是TableCell（如右侧空白区域），即使命中TableRow也不选中任何cell
        return null;
    }

    /**
     * 键盘快捷键：Ctrl+C复制；选中多个单元格时直接键入可批量同步输入到所有选中单元格
     */
    private void setupKeyboardShortcuts() {
        tableView.setOnKeyPressed(event -> {
            // Ctrl+C 复制选中单元格（整行选中时即行复制）
            if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.C) {
                handleCopySelectedCells();
                event.consume();
                return;
            }
            // Ctrl+V 粘贴剪贴板内容为新行（行粘贴）
            if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.V) {
                handlePasteRows();
                event.consume();
                return;
            }

            // 批量输入状态下的控制键处理
            if (batchEditing) {
                switch (event.getCode()) {
                    case ENTER -> {
                        commitBatchEdit();
                        event.consume();
                        return;
                    }
                    case ESCAPE -> {
                        revertBatchEdit();
                        event.consume();
                        return;
                    }
                    case BACK_SPACE -> {
                        if (!batchEditValue.isEmpty()) {
                            batchEditValue = batchEditValue.substring(0, batchEditValue.length() - 1);
                            applyBatchValue();
                        }
                        event.consume();
                        return;
                    }
                    case TAB, UP, DOWN, LEFT, RIGHT -> {
                        // 导航键结束批量输入，继续往下处理导航
                        commitBatchEdit();
                    }
                    default -> {
                        // 可打印字符由 keyTyped 处理
                        return;
                    }
                }
            }

            // 最后一行按 Down 新增空行
            if (event.getCode() == javafx.scene.input.KeyCode.DOWN
                    && !event.isControlDown() && !event.isShiftDown() && !event.isAltDown()) {
                int focusedRow = tableView.getFocusModel().getFocusedIndex();
                int lastRow = tableView.getItems().size() - 1;
                if (lastRow >= 0 && focusedRow >= lastRow) {
                    addEmptyNewRow();
                    selectRowAtColumn(tableView.getItems().size() - 1);
                    event.consume();
                }
            }
        });

        // 选中多个数据单元格时，输入可打印字符直接同步到所有选中单元格
        tableView.setOnKeyTyped(event -> {
            if (event.isControlDown() || event.isMetaDown() || event.isAltDown()) return;
            String ch = event.getCharacter();
            if (ch == null || ch.isEmpty()) return;
            // 只处理可打印字符（排除 Enter、Backspace 等控制字符）
            if (ch.length() != 1 || ch.charAt(0) < ' ') return;

            List<TablePosition<ObservableList<String>, ?>> dataCells = getSelectedDataCells();
            if (dataCells.isEmpty()) return;

            if (!batchEditing) {
                if (dataCells.size() < 2) return; // 单个单元格不触发批量输入
                startBatchEdit(dataCells);
            }

            batchEditValue += ch;
            applyBatchValue();
            event.consume();
        });
    }

    /**
     * 方向键在单元格间切换（选中单元格而非行）。
     * 捕获阶段拦截，消费事件防止默认行选择行为。
     * 批量输入状态下先提交批量编辑再导航；最后一行按 DOWN 新增空行。
     */
    private void setupArrowKeyNavigation() {
        tableView.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (tableView.getEditingCell() != null) return;
            javafx.scene.input.KeyCode code = event.getCode();
            if (code != javafx.scene.input.KeyCode.UP && code != javafx.scene.input.KeyCode.DOWN
                    && code != javafx.scene.input.KeyCode.LEFT && code != javafx.scene.input.KeyCode.RIGHT) return;
            if (event.isControlDown() || event.isShiftDown() || event.isAltDown()) return;

            // 批量输入状态下，先提交批量编辑再导航
            if (batchEditing) {
                commitBatchEdit();
            }

            ObservableList<ObservableList<String>> items = tableView.getItems();
            if (items == null || items.isEmpty()) return;
            int rowCount = items.size();

            // 获取当前焦点单元格位置
            TablePosition<ObservableList<String>, ?> focusedCell = tableView.getFocusModel().getFocusedCell();
            int curRow = focusedCell != null && focusedCell.getRow() >= 0 ? focusedCell.getRow() : 0;
            int curCol = focusedCell != null && focusedCell.getColumn() >= 0
                    ? focusedCell.getColumn() : findFirstNavigableColumn();

            int newRow = curRow;
            int newCol = curCol;
            if (code == javafx.scene.input.KeyCode.UP) newRow = curRow - 1;
            else if (code == javafx.scene.input.KeyCode.DOWN) newRow = curRow + 1;
            else if (code == javafx.scene.input.KeyCode.LEFT) newCol = findNavigableColumn(curCol, -1);
            else if (code == javafx.scene.input.KeyCode.RIGHT) newCol = findNavigableColumn(curCol, 1);

            // 最后一行按 DOWN 新增空行
            if (code == javafx.scene.input.KeyCode.DOWN && newRow >= rowCount) {
                event.consume();
                addEmptyNewRow();
                selectRowAtColumn(tableView.getItems().size() - 1);
                return;
            }

            // 边界检查
            if (newRow < 0) newRow = 0;
            if (newRow >= rowCount) newRow = rowCount - 1;

            // 消费事件防止默认行选择行为
            event.consume();
            if (newRow != curRow || newCol != curCol) {
                tableView.getSelectionModel().clearSelection();
                TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(newCol);
                tableView.getSelectionModel().select(newRow, col);
                tableView.getFocusModel().focus(newRow, col);
                tableView.scrollTo(newRow);
            }
        });
    }

    /**
     * 查找指定方向上的下一个可导航列（跳过行选择器列和不可见列）
     * @param startCol 起始列索引
     * @param direction 方向（-1 向左，1 向右）
     * @return 下一个可导航列索引，未找到则返回 startCol
     */
    private int findNavigableColumn(int startCol, int direction) {
        for (int i = startCol + direction; i >= 0 && i < tableView.getColumns().size(); i += direction) {
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(i);
            if (col.isVisible() && !ROW_SELECTOR_COL.equals(col.getUserData())) {
                return i;
            }
        }
        return startCol;
    }

    /**
     * 查找第一个可导航列（跳过行选择器列和不可见列）
     */
    private int findFirstNavigableColumn() {
        for (int i = 0; i < tableView.getColumns().size(); i++) {
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(i);
            if (col.isVisible() && !ROW_SELECTOR_COL.equals(col.getUserData())) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 粘贴剪贴板内容：行选择器整行选中时插入新行；选中数据单元格时从焦点单元格开始覆盖替换
     */
    private void handlePasteRows() {
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        String text = clipboard.getString();
        if (text == null || text.isEmpty()) return;

        List<String> columns = getDataColumnNames();
        if (columns.isEmpty()) return;

        // 选中包含行选择器列 → 通过行选择器整行选中 → 插入新行模式
        boolean rowSelectorSelected = false;
        for (TablePosition<ObservableList<String>, ?> pos : tableView.getSelectionModel().getSelectedCells()) {
            if (pos.getTableColumn() != null && ROW_SELECTOR_COL.equals(pos.getTableColumn().getUserData())) {
                rowSelectorSelected = true;
                break;
            }
        }

        if (rowSelectorSelected) {
            pasteAsNewRows(text, columns);
        } else {
            pasteIntoCells(text, columns);
        }
    }

    /**
     * 插入新行模式：每行一条记录，Tab 分隔列值，插入到当前焦点行下方
     */
    private void pasteAsNewRows(String text, List<String> columns) {
        List<String[]> valueRows = parseClipboardRows(text);
        if (valueRows.isEmpty()) return;

        List<ObservableList<String>> pastedRows = new ArrayList<>();
        for (String[] values : valueRows) {
            ObservableList<String> row = FXCollections.observableArrayList();
            for (int i = 0; i < columns.size(); i++) {
                row.add(i < values.length ? values[i] : "");
            }
            pastedRows.add(row);
        }

        // 插入位置：当前焦点行下方，否则追加到末尾
        int insertIndex = tableView.getItems().size();
        TablePosition<ObservableList<String>, ?> focusedCell = tableView.getFocusModel().getFocusedCell();
        if (focusedCell != null && focusedCell.getRow() >= 0 && focusedCell.getRow() < tableView.getItems().size()) {
            insertIndex = focusedCell.getRow() + 1;
        }

        for (ObservableList<String> row : pastedRows) {
            newRows.add(row);
            tableView.getItems().add(insertIndex, row);
            insertIndex++;
        }

        // 选中新粘贴的第一行
        selectRowAtColumn(insertIndex - pastedRows.size());
    }

    /**
     * 单元格替换模式：从选中区域最左上角单元格开始，用剪贴板内容覆盖对应位置的单元格（Excel式粘贴）
     * 修改由 originalValuesMap 快照追踪，保存时走 UPDATE；目标行不足时自动追加新行
     */
    private void pasteIntoCells(String text, List<String> columns) {
        // 起点：选中数据单元格的最左上角（最小行+最小列，与焦点位置无关）
        int startRow = -1, startCol = -1;
        for (TablePosition<ObservableList<String>, ?> pos : tableView.getSelectionModel().getSelectedCells()) {
            if (pos.getTableColumn() == null || ROW_SELECTOR_COL.equals(pos.getTableColumn().getUserData())) continue;
            int dataCol = getDataColIndex(pos);
            if (dataCol < 0 || dataCol >= columns.size() || pos.getRow() < 0) continue;
            if (startRow < 0 || pos.getRow() < startRow) startRow = pos.getRow();
            if (startCol < 0 || dataCol < startCol) startCol = dataCol;
        }
        if (startRow < 0) {
            // 无选中数据单元格时用焦点数据单元格
            TablePosition<ObservableList<String>, ?> focusedCell = tableView.getFocusModel().getFocusedCell();
            if (focusedCell != null && focusedCell.getTableColumn() != null
                    && !ROW_SELECTOR_COL.equals(focusedCell.getTableColumn().getUserData())
                    && focusedCell.getRow() >= 0) {
                startRow = focusedCell.getRow();
                startCol = getDataColIndex(focusedCell);
            }
        }
        if (startRow < 0 || startCol < 0) {
            pasteAsNewRows(text, columns);
            return;
        }

        List<String[]> valueRows = parseClipboardRows(text);
        // 去掉末尾空行（兼容 Excel 等外部来源复制时的尾随换行）
        while (valueRows.size() > 1
                && valueRows.get(valueRows.size() - 1).length == 1
                && valueRows.get(valueRows.size() - 1)[0].isEmpty()) {
            valueRows.remove(valueRows.size() - 1);
        }
        if (valueRows.isEmpty()) return;

        // 覆盖写入：目标行超出现有行数时自动追加新行
        for (int r = 0; r < valueRows.size(); r++) {
            int targetRowIdx = startRow + r;
            while (targetRowIdx >= tableView.getItems().size()) {
                addEmptyNewRow();
            }
            ObservableList<String> row = tableView.getItems().get(targetRowIdx);
            String[] values = valueRows.get(r);
            for (int c = 0; c < values.length && startCol + c < columns.size(); c++) {
                row.set(startCol + c, values[c]);
            }
        }

        // 重绘单元格状态（修改/新行标记）
        tableView.refresh();

        // 选中新粘贴的区域并滚动到起始行
        tableView.getSelectionModel().clearSelection();
        for (int r = 0; r < valueRows.size() && startRow + r < tableView.getItems().size(); r++) {
            String[] values = valueRows.get(r);
            for (int c = 0; c < values.length && startCol + c < columns.size(); c++) {
                tableView.getSelectionModel().select(startRow + r, tableView.getColumns().get(startCol + c + 1));
            }
        }
        tableView.scrollTo(startRow);
    }

    /**
     * 解析剪贴板文本为二维数据：按换行分割行，按 Tab 分割列
     */
    private List<String[]> parseClipboardRows(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            String cleanLine = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            rows.add(cleanLine.split("\t", -1));
        }
        return rows;
    }

    /**
     * 选中指定行的数据单元格（保持当前焦点列，否则用第一数据列），并滚动到可视区域
     */
    private void selectRowAtColumn(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= tableView.getItems().size()) return;
        TableColumn<ObservableList<String>, ?> col = null;
        TablePosition<ObservableList<String>, ?> focusedCell = tableView.getFocusModel().getFocusedCell();
        if (focusedCell != null && focusedCell.getTableColumn() != null
                && !ROW_SELECTOR_COL.equals(focusedCell.getTableColumn().getUserData())) {
            col = focusedCell.getTableColumn();
        }
        if (col == null && tableView.getColumns().size() > 1) {
            col = tableView.getColumns().get(1); // 第一数据列
        }
        tableView.scrollTo(rowIndex);
        tableView.getSelectionModel().clearAndSelect(rowIndex, col);
        tableView.getFocusModel().focus(rowIndex, col);
    }

    /**
     * 获取选中的数据单元格（跳过行选择器列）
     */
    @SuppressWarnings("unchecked")
    private List<TablePosition<ObservableList<String>, ?>> getSelectedDataCells() {
        ObservableList<TablePosition<ObservableList<String>, ?>> selectedCells =
                (ObservableList<TablePosition<ObservableList<String>, ?>>) (ObservableList<?>) tableView.getSelectionModel().getSelectedCells();
        List<TablePosition<ObservableList<String>, ?>> result = new ArrayList<>();
        for (TablePosition<ObservableList<String>, ?> pos : selectedCells) {
            if (pos.getTableColumn() != null && !ROW_SELECTOR_COL.equals(pos.getTableColumn().getUserData())) {
                result.add(pos);
            }
        }
        return result;
    }

    /**
     * 开始批量输入：记录选中单元格的原始值（用于 Esc 撤销）
     */
    private void startBatchEdit(List<TablePosition<ObservableList<String>, ?>> cells) {
        // 如果正在编辑某个单元格，先取消编辑
        if (tableView.getEditingCell() != null) {
            tableView.edit(-1, null);
        }
        batchEditing = true;
        batchEditValue = "";
        batchEditOriginals.clear();
        for (TablePosition<ObservableList<String>, ?> pos : cells) {
            int row = pos.getRow();
            int dataColIndex = getDataColIndex(pos);
            if (row < 0 || row >= tableView.getItems().size()) continue;
            ObservableList<String> rowData = tableView.getItems().get(row);
            String orig = (dataColIndex >= 0 && dataColIndex < rowData.size()) ? rowData.get(dataColIndex) : "";
            batchEditOriginals.put(row + "," + dataColIndex, orig);
        }
    }

    /**
     * 将当前输入值实时同步到所有选中单元格
     */
    private void applyBatchValue() {
        for (String key : batchEditOriginals.keySet()) {
            String[] parts = key.split(",");
            int row = Integer.parseInt(parts[0]);
            int dataColIndex = Integer.parseInt(parts[1]);
            if (row < 0 || row >= tableView.getItems().size()) continue;
            ObservableList<String> rowData = tableView.getItems().get(row);
            if (dataColIndex >= 0 && dataColIndex < rowData.size()) {
                rowData.set(dataColIndex, batchEditValue);
            }
        }
        tableView.refresh();
    }

    /**
     * 提交批量输入：保留已输入内容，结束编辑状态
     */
    private void commitBatchEdit() {
        batchEditing = false;
        batchEditValue = "";
        batchEditOriginals.clear();
        tableView.refresh();
    }

    /**
     * 撤销批量输入：恢复所有选中单元格到编辑前的原始值
     */
    private void revertBatchEdit() {
        for (String key : batchEditOriginals.keySet()) {
            String[] parts = key.split(",");
            int row = Integer.parseInt(parts[0]);
            int dataColIndex = Integer.parseInt(parts[1]);
            if (row < 0 || row >= tableView.getItems().size()) continue;
            ObservableList<String> rowData = tableView.getItems().get(row);
            if (dataColIndex >= 0 && dataColIndex < rowData.size()) {
                rowData.set(dataColIndex, batchEditOriginals.get(key));
            }
        }
        batchEditing = false;
        batchEditValue = "";
        batchEditOriginals.clear();
        tableView.refresh();
    }

    /**
     * 获取数据列索引（tableView 列索引减去行选择器列）
     */
    private int getDataColIndex(TablePosition<ObservableList<String>, ?> pos) {
        int tableViewColIndex = tableView.getColumns().indexOf(pos.getTableColumn());
        return tableViewColIndex - 1;
    }

    /**
     * 复制选中的cell到剪贴板，按行列排列，Tab分隔列，换行分隔行
     */
    private void handleCopySelectedCells() {
        @SuppressWarnings("unchecked")
        ObservableList<TablePosition<ObservableList<String>, ?>> selectedCells =
                (ObservableList<TablePosition<ObservableList<String>, ?>>) (ObservableList<?>) tableView.getSelectionModel().getSelectedCells();
        if (selectedCells.isEmpty()) return;

        // 收集选中cell的行列范围（排除行选择器列，避免复制内容开头多出Tab导致粘贴错位）
        int minRow = Integer.MAX_VALUE, maxRow = -1;
        int minCol = Integer.MAX_VALUE, maxCol = -1;
        for (TablePosition<?, ?> pos : selectedCells) {
            if (pos.getTableColumn() == null || ROW_SELECTOR_COL.equals(pos.getTableColumn().getUserData())) continue;
            int row = pos.getRow();
            int col = tableView.getColumns().indexOf(pos.getTableColumn());
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }
        if (maxRow < 0 || maxCol < 0) return;

        // 构建选中区域数据，用Set快速判断是否选中
        java.util.Set<String> selectedSet = new java.util.HashSet<>();
        for (TablePosition<?, ?> pos : selectedCells) {
            if (pos.getTableColumn() == null || ROW_SELECTOR_COL.equals(pos.getTableColumn().getUserData())) continue;
            int col = tableView.getColumns().indexOf(pos.getTableColumn());
            selectedSet.add(pos.getRow() + "," + col);
        }

        StringBuilder sb = new StringBuilder();
        for (int r = minRow; r <= maxRow; r++) {
            ObservableList<String> rowData = tableView.getItems().get(r);
            boolean firstCol = true;
            for (int c = minCol; c <= maxCol; c++) {
                if (!selectedSet.contains(r + "," + c)) continue;
                if (!firstCol) sb.append('\t');
                firstCol = false;
                int dataColIndex = c - 1; // 减去行选择器列
                if (dataColIndex >= 0 && dataColIndex < rowData.size()) {
                    String value = rowData.get(dataColIndex);
                    sb.append(value != null ? value : "");
                }
            }
            if (r < maxRow) sb.append('\n');
        }

        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(sb.toString());
        clipboard.setContent(content);
    }

    /**
     * 处理删除选中行
     */
    private void handleDeleteSelectedRows() {
        // cell selection模式下getSelectedItems可能包含重复行，需去重
        List<ObservableList<String>> selectedRows = tableView.getSelectionModel().getSelectedItems()
                .stream().distinct().toList();
        if (selectedRows.isEmpty()) return;

        // 分离新行和现有行
        List<ObservableList<String>> newRowsToDelete = new ArrayList<>();
        List<ObservableList<String>> existingRowsToDelete = new ArrayList<>();
        for (ObservableList<String> row : selectedRows) {
            if (newRows.contains(row)) {
                newRowsToDelete.add(row);
            } else {
                existingRowsToDelete.add(row);
            }
        }

        int count = selectedRows.size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除行");
        confirm.setHeaderText(null);
        confirm.setContentText("确定删除" + count + "条数据？此操作不可撤销！");
        DialogPositionUtil.centerOnOwner(confirm, this);
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;

            // 新行：仅从UI和追踪集合中移除，不调用DB
            if (!newRowsToDelete.isEmpty()) {
                for (ObservableList<String> row : newRowsToDelete) {
                    newRows.remove(row);
                    originalValuesMap.remove(row);
                }
                tableView.getItems().removeAll(newRowsToDelete);
            }

            // 现有行：从DB删除
            if (!existingRowsToDelete.isEmpty()) {
                if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()) {
                    Alert warn = new Alert(Alert.AlertType.WARNING);
                    warn.setTitle("无法删除");
                    warn.setHeaderText(null);
                    warn.setContentText("该表无主键，无法从数据库删除行");
                    DialogPositionUtil.centerOnOwner(warn, this);
                    warn.showAndWait();
                    return;
                }

                // 复制选中行数据（避免在删除过程中ObservableList变化）
                List<ObservableList<String>> rowsToDelete = new ArrayList<>(existingRowsToDelete);
                List<String> dataColumns = getDataColumnNames();

                new Thread(() -> {
                    java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
                    connLock.lock();
                    try {
                        try {
                            int deleted = DatabaseService.deleteRowsByPrimaryKeys(
                                    config, databaseName, schemaName, tableName,
                                    primaryKeyColumns, dataColumns, rowsToDelete);
                            Platform.runLater(() -> {
                                for (ObservableList<String> row : rowsToDelete) {
                                    originalValuesMap.remove(row);
                                }
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
                                DialogPositionUtil.centerOnOwner(err, this);
                                err.showAndWait();
                            });
                        }
                    } finally {
                        connLock.unlock();
                    }
                }, "DB-DeleteRows").start();
            }
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

    /**
     * 判断行是否完全为空（所有单元格都是空字符串）
     */
    private boolean isRowEmpty(ObservableList<String> row) {
        for (String val : row) {
            if (val != null && !val.isEmpty()) return false;
        }
        return true;
    }

    /**
     * 判断是否有未保存的更改
     */
    private boolean hasUnsavedChanges() {
        // 检查新行是否有非空内容
        for (ObservableList<String> row : newRows) {
            if (!isRowEmpty(row)) return true;
        }
        // 检查现有行是否有值变化
        for (Map.Entry<ObservableList<String>, ObservableList<String>> entry : originalValuesMap.entrySet()) {
            ObservableList<String> current = entry.getKey();
            ObservableList<String> original = entry.getValue();
            // 跳过新行
            if (newRows.contains(current)) continue;
            for (int i = 0; i < current.size(); i++) {
                String orig = i < original.size() ? original.get(i) : "";
                if (!current.get(i).equals(orig)) return true;
            }
        }
        return false;
    }

    /**
     * 获取指定行的状态
     */
    private RowState getRowState(ObservableList<String> row) {
        if (row == null) return RowState.EXISTING;
        if (newRows.contains(row)) return RowState.NEW;
        ObservableList<String> original = originalValuesMap.get(row);
        if (original != null) {
            for (int i = 0; i < row.size(); i++) {
                String orig = i < original.size() ? original.get(i) : "";
                if (!row.get(i).equals(orig)) return RowState.EXISTING_DIRTY;
            }
        }
        return RowState.EXISTING;
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
        // 固定行高（读取全局配置 tableFontSize 派生）：避免内容多的行把整行撑得过高
        int rowHeight = globalConfig.getTableFontSize() + 18;
        tableView.setFixedCellSize(rowHeight);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
        tableView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        // 打开表使用 TableView 内部水平滚动条（ScrollPane 仅填充视口），需恢复被全局规则隐藏的内部水平滚动条
        tableView.getStyleClass().add("data-table-view");
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tableView.getSelectionModel().setCellSelectionEnabled(true);

        // 鼠标拖拽选中多个cell
        setupDragSelection();
        // Ctrl+C 复制选中cell
        setupKeyboardShortcuts();
        // 方向键在单元格间切换（选中单元格而非行）
        setupArrowKeyNavigation();
        // 布局后移除内部节点的默认padding/border，消除左侧间隔
        tableView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                stripPaddingRecursive(tableView);
                // filler（表头右侧空白）点击时清除选择，行为与点击空白区域一致
                Node filler = tableView.lookup(".column-header-background .filler");
                if (filler != null) {
                    filler.setOnMousePressed(event -> {
                        if (event.getButton() == MouseButton.PRIMARY) {
                            tableView.getSelectionModel().clearSelection();
                            anchorCell[0] = -1;
                            anchorCell[1] = -1;
                            event.consume();
                        }
                    });
                }
            }
        });

        // 加载指示器
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(40, 40);
        loadingIndicator.setVisible(false);

        // ScrollPane包裹TableView：仅用于填充视口，滚动由TableView内部处理
        // TableView内部水平滚动条滚动内容，垂直滚动条始终在视口右侧（不被水平滚动移出视野）
        tableScrollPane = new ScrollPane(tableView);
        tableScrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-background-insets: 0; -fx-padding: 0; -fx-border-insets: 0;");
        tableScrollPane.getStyleClass().add("session-scroll-pane");
        tableScrollPane.setFitToHeight(true);
        tableScrollPane.setFitToWidth(true);
        tableScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        tableScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // 拖拽范围选择矩形：半透明蓝色（与选中色#3592CB一致），鼠标透明，置于最上层
        dragSelectRect.setFill(Color.rgb(53, 146, 203, 0.15));
        dragSelectRect.setStroke(Color.rgb(53, 146, 203, 0.7));
        dragSelectRect.setStrokeWidth(1);
        dragSelectRect.setMouseTransparent(true);
        dragSelectRect.setManaged(false);
        dragSelectRect.setVisible(false);

        centerPane = new StackPane(tableScrollPane, loadingIndicator, dragSelectRect);
        centerPane.setPadding(Insets.EMPTY);
        centerPane.setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");

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
        this.setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
        // 加载统一样式表：使 session-scroll-pane 等规则对 tableScrollPane 的 .viewport 生效
        this.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
    }

    /**
     * 创建工具栏：添加、删除、保存、刷新按钮（图标+名称）
     */
    private HBox createToolBar() {
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(0, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);

        // 添加按钮：绿色加号（仅在UI添加空行，不触发DB插入）
        Button addBtn = createToolBarButton("添加", createAddIcon());
        addBtn.setOnAction(e -> handleAddNewRow());

        // 删除按钮：红色减号/叉号
        Button deleteBtn = createToolBarButton("删除", createDeleteIcon());
        deleteBtn.setOnAction(e -> handleDeleteSelectedRows());

        // 保存按钮：蓝色上箭头（提交所有更改）
        Button saveBtn = createToolBarButton("保存", createSaveIcon());
        saveBtn.setOnAction(e -> handleSave());

        // 分隔符
        Separator separator = new Separator();
        separator.setOrientation(javafx.geometry.Orientation.VERTICAL);
        separator.setPadding(new Insets(2, 4, 2, 4));

        // 刷新按钮：环形箭头
        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> refreshData());

        toolBar.getChildren().addAll(addBtn, deleteBtn, saveBtn, separator, refreshBtn);
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

    /** 保存图标：蓝色上箭头 */
    private Node createSaveIcon() {
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
     * 在表格底部添加一行空白行（仅UI，不触发DB插入）
     */
    private void handleAddNewRow() {
        addEmptyNewRow();
    }

    /**
     * 添加空白新行到tableView底部
     */
    private void addEmptyNewRow() {
        List<String> columns = getDataColumnNames();
        if (columns.isEmpty()) return;

        ObservableList<String> emptyRow = FXCollections.observableArrayList();
        for (int i = 0; i < columns.size(); i++) {
            emptyRow.add("");  // 空字符串，不是"NULL"
        }
        newRows.add(emptyRow);
        tableView.getItems().add(emptyRow);
    }

    /**
     * 保存所有更改：INSERT新行，UPDATE修改的现有行
     */
    private void handleSave() {
        // 先提交当前正在编辑的单元格
        if (tableView.getEditingCell() != null) {
            tableView.edit(-1, null);
        }

        List<String> dataColumns = getDataColumnNames();
        if (dataColumns.isEmpty()) return;

        // 收集非空新行（跳过完全空白的新行）
        List<ObservableList<String>> rowsToInsert = new ArrayList<>();
        for (ObservableList<String> row : newRows) {
            if (!isRowEmpty(row)) {
                rowsToInsert.add(row);
            }
        }

        // 收集有修改的现有行
        List<ObservableList<String>> rowsToUpdate = new ArrayList<>();
        List<ObservableList<String>> originalValuesForUpdate = new ArrayList<>();
        List<Set<Integer>> modifiedColumnsPerRow = new ArrayList<>();
        for (Map.Entry<ObservableList<String>, ObservableList<String>> entry : originalValuesMap.entrySet()) {
            ObservableList<String> currentRow = entry.getKey();
            // 跳过新行
            if (newRows.contains(currentRow)) continue;
            ObservableList<String> originalRow = entry.getValue();
            Set<Integer> modifiedCols = new LinkedHashSet<>();
            for (int i = 0; i < currentRow.size(); i++) {
                String current = currentRow.get(i);
                String original = i < originalRow.size() ? originalRow.get(i) : "";
                if (!current.equals(original)) {
                    modifiedCols.add(i);
                }
            }
            if (!modifiedCols.isEmpty()) {
                rowsToUpdate.add(currentRow);
                originalValuesForUpdate.add(originalRow);
                modifiedColumnsPerRow.add(modifiedCols);
            }
        }

        if (rowsToInsert.isEmpty() && rowsToUpdate.isEmpty()) {
            // 没有需要保存的更改
            return;
        }

        // 检查更新操作是否需要主键
        if (!rowsToUpdate.isEmpty() && (primaryKeyColumns == null || primaryKeyColumns.isEmpty())) {
            Alert warn = new Alert(Alert.AlertType.WARNING);
            warn.setTitle("无法更新");
            warn.setHeaderText(null);
            warn.setContentText("该表无主键，无法更新现有行。只有新行会被插入。");
            DialogPositionUtil.centerOnOwner(warn, this);
            warn.showAndWait();
            // 继续插入新行，跳过更新
            rowsToUpdate.clear();
            originalValuesForUpdate.clear();
            modifiedColumnsPerRow.clear();
        }

        final List<ObservableList<String>> finalRowsToInsert = new ArrayList<>(rowsToInsert);
        final List<ObservableList<String>> finalRowsToUpdate = new ArrayList<>(rowsToUpdate);
        final List<ObservableList<String>> finalOriginalValues = new ArrayList<>(originalValuesForUpdate);
        final List<Set<Integer>> finalModifiedColumns = new ArrayList<>(modifiedColumnsPerRow);

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                try {
                    // INSERT 新行
                    if (!finalRowsToInsert.isEmpty()) {
                        DatabaseService.insertRows(config, databaseName, schemaName, tableName,
                                dataColumns, finalRowsToInsert, primaryKeyColumns);
                    }

                    // UPDATE 修改的现有行
                    if (!finalRowsToUpdate.isEmpty()) {
                        DatabaseService.updateRows(config, databaseName, schemaName, tableName,
                                primaryKeyColumns, dataColumns,
                                finalRowsToUpdate, finalOriginalValues, finalModifiedColumns);
                    }

                    Platform.runLater(() -> {
                        // 保存成功后刷新数据，获取DB生成的值（如自增主键、默认值、触发器结果）
                        refreshData();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert err = new Alert(Alert.AlertType.ERROR);
                        err.setTitle("保存失败");
                        err.setHeaderText(null);
                        err.setContentText("保存失败: " + e.getMessage());
                        DialogPositionUtil.centerOnOwner(err, this);
                        err.showAndWait();
                    });
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-SaveChanges").start();
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
        // 防止并发加载（用户快速翻页时可能触发）
        if (isLoading) return;
        isLoading = true;

        // 检查是否有未保存的更改
        if (hasUnsavedChanges() && page != currentPage) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("切换页面");
            confirm.setHeaderText(null);
            confirm.setContentText("有未保存的更改，切换页面将丢失这些更改。确定切换？");
            DialogPositionUtil.centerOnOwner(confirm, this);
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                isLoading = false;
                return;
            }
        }

        loadingIndicator.setVisible(true);
        tableView.setDisable(true);

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                // 首次加载时获取主键（与数据查询共用同一线程，避免JDBC连接并发使用）
                loadPrimaryKeysInCurrentThread();

                TableRowData data = DatabaseService.queryTableData(config, databaseName, schemaName, tableName, page, DEFAULT_PAGE_SIZE, sortColumn, sortDescending);
                Platform.runLater(() -> {
                    currentPage = data.getPage();
                    totalPages = data.getTotalPages();
                    totalCount = data.getTotalCount();
                    updateTableView(data);
                    updateStatusBar();
                    loadingIndicator.setVisible(false);
                    tableView.setDisable(false);
                    isLoading = false;
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    tableView.setDisable(false);
                    pageInfoLabel.setText("加载失败: " + e.getMessage());
                    isLoading = false;
                    e.printStackTrace();
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadTableData").start();
    }

    /** 行选择器列的标识名，用于在获取数据列名时跳过 */
    private static final String ROW_SELECTOR_COL = "__ROW_SELECTOR__";

    private void updateTableView(TableRowData data) {
        tableView.getColumns().clear();
        tableView.getItems().clear();

        // 初始化行状态追踪
        newRows.clear();
        originalValuesMap.clear();

        // 缓存数据列名与列类型
        dataColumnNames = new ArrayList<>(data.getColumnNames());
        dataColumnTypes = (data.getColumnTypes() != null) ? new ArrayList<>(data.getColumnTypes()) : new ArrayList<>();

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
                // 左右两侧均加网格线
                setStyle("-fx-border-color: transparent #BEBEBC transparent #BEBEBC; -fx-border-width: 0 1 0 1; -fx-alignment: center;");
                // 行选择器列拖拽多行选中的起始行（-1 表示未从行选择器发起拖拽）
                final int[] dragStart = RowSelectorDragSelection.install(tableView, this);
                // 点击行选择器列时选中整行（使用addEventFilter在捕获阶段处理，避免被TableView的拖拽选择处理器覆盖）
                addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        int row = getTableRow().getIndex();
                        if (event.isControlDown()) {
                            dragStart[0] = -1;
                            if (isRowSelected(row)) {
                                tableView.getSelectionModel().clearSelection(row);
                            } else {
                                tableView.getSelectionModel().select(row);
                            }
                        } else if (event.isShiftDown()) {
                            dragStart[0] = -1;
                            int anchor = tableView.getSelectionModel().getFocusedIndex();
                            if (anchor >= 0) {
                                int start = Math.min(row, anchor);
                                int end = Math.max(row, anchor);
                                tableView.getSelectionModel().clearSelection();
                                tableView.getSelectionModel().selectRange(start, end + 1);
                            } else {
                                tableView.getSelectionModel().clearSelection();
                                tableView.getSelectionModel().select(row);
                            }
                        } else {
                            tableView.getSelectionModel().clearSelection();
                            tableView.getSelectionModel().select(row);
                            dragStart[0] = row;
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
                    setStyle("-fx-border-color: transparent; -fx-border-width: 0;");
                    return;
                }

                // 左右两侧及底部加网格线
                setStyle("-fx-border-color: transparent #BEBEBC #BEBEBC #BEBEBC; -fx-border-width: 0 1 1 1;");
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
            final int colSqlType = (colIndex < dataColumnTypes.size()) ? dataColumnTypes.get(colIndex) : java.sql.Types.OTHER;
            col.setCellFactory(tc -> new EditableTableCell(colSqlType));
            col.setOnEditCommit(event -> {
                ObservableList<String> row = event.getRowValue();
                String oldValue = row.get(colIndex);
                String newValue = event.getNewValue();
                if (oldValue.equals(newValue)) return;

                // 仅更新数据模型（延迟保存，不立即提交到数据库）
                row.set(colIndex, newValue);
            });

            tableView.getColumns().add(col);
        }

        // 保存现有行的原始值快照
        for (ObservableList<String> row : data.getRows()) {
            ObservableList<String> original = FXCollections.observableArrayList(row);
            originalValuesMap.put(row, original);
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
                    if (matchedCol == null) return;
                    if (ROW_SELECTOR_COL.equals(matchedCol.getUserData())) {
                        // 行选择器列表头：与下方选择器单元格一致的网格线（左右及底部）
                        header.setStyle("-fx-border-color: transparent #BEBEBC #BEBEBC #BEBEBC; -fx-border-width: 0 1 1 1;");
                    } else {
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
        int rowHeight = config.getTableFontSize() + 18;
        tableView.setFixedCellSize(rowHeight);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                config.getTableFontName(), config.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
    }

    public void refreshData() {
        if (hasUnsavedChanges()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("刷新");
            confirm.setHeaderText(null);
            confirm.setContentText("有未保存的更改，刷新将丢失这些更改。确定刷新？");
            DialogPositionUtil.centerOnOwner(confirm, this);
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) return;
        }
        loadData(currentPage);
    }

    /**
     * 可编辑单元格：选中时蓝色背景，进入编辑时白色背景+蓝色边框
     * 新行显示浅黄背景+斜体，修改行显示浅蓝背景
     * 失去焦点时保留编辑值（仅按Escape时才真正取消编辑）
     */
    private class EditableTableCell extends TableCell<ObservableList<String>, String> {
        private TextField textField;
        /** 标记用户是否按下了Escape键（真正取消编辑） */
        private boolean escapePressed = false;
        /** 当前列的 java.sql.Types 类型，用于按类型渲染日期/时间/日期时间选择器 */
        private final int sqlType;
        /**
         * 非编辑模式下用于显示文本的 Text 节点。
         * Labeled 的 setText 用 LOGICAL bounds（ascent+descent）居中，ascent > descent 导致视觉偏上；
         * Text 节点配合 VISUAL bounds + VPos.CENTER 实现真正的视觉垂直居中，与行高无关。
         */
        private final javafx.scene.text.Text displayText;

        public EditableTableCell() {
            this(java.sql.Types.OTHER);
        }

        public EditableTableCell(int sqlType) {
            super();
            this.sqlType = sqlType;
            getStyleClass().add("data-cell");
            setAlignment(Pos.CENTER_LEFT);
            displayText = new javafx.scene.text.Text();
            displayText.setTextOrigin(javafx.geometry.VPos.CENTER);
            displayText.setBoundsType(javafx.scene.text.TextBoundsType.VISUAL);
            displayText.fontProperty().bind(fontProperty());
            displayText.fillProperty().bind(textFillProperty());
        }

        /** 日期列：仅选日期 */
        private boolean isDateColumn() { return sqlType == java.sql.Types.DATE; }
        /** 时间列：仅选时间 */
        private boolean isTimeColumn() { return sqlType == java.sql.Types.TIME || sqlType == java.sql.Types.TIME_WITH_TIMEZONE; }
        /** 日期时间列：日期+时间 */
        private boolean isDateTimeColumn() { return sqlType == java.sql.Types.TIMESTAMP || sqlType == java.sql.Types.TIMESTAMP_WITH_TIMEZONE; }
        /** 临时类型列（日期/时间/日期时间） */
        private boolean isTemporalColumn() { return isDateColumn() || isTimeColumn() || isDateTimeColumn(); }

        @Override
        public void startEdit() {
            escapePressed = false;
            super.startEdit();
            if (textField == null) {
                createTextField();
            }
            setText(null);
            // 日期/时间/日期时间列：文本框 + 右侧选择按钮（点击弹一层选择器）
            if (isTemporalColumn()) {
                setGraphic(createTemporalEditor());
            } else {
                setGraphic(textField);
            }
            textField.setText(getItem() != null ? getItem() : "");
            textField.selectAll();
            textField.requestFocus();
            // 编辑状态：白色背景+蓝色边框覆盖表格线
            setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 2; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: center-left;");
        }

        @Override
        public void cancelEdit() {
            // 非Escape触发的cancel（如点击其他cell），保留编辑值到数据模型
            if (!escapePressed && textField != null) {
                String newValue = textField.getText();
                String currentValue = getItem() != null ? getItem() : "";
                if (!newValue.equals(currentValue)) {
                    updateCellData(newValue);
                }
            }
            escapePressed = false;
            super.cancelEdit();
            // cancelEdit后getItem()返回的是原值，但数据模型可能已更新，
            // 需要重新从数据模型读取显示值
            String displayValue = getCellData();
            displayText.setText(displayValue != null ? displayValue : "");
            setGraphic(displayText);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setText(null);
            applyRowStateStyle();
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-border-color: transparent; -fx-padding: 0; -fx-alignment: center-left;");
            } else {
                if (isEditing()) {
                    if (textField != null) {
                        textField.setText(getItem() != null ? getItem() : "");
                    }
                    setText(null);
                    setGraphic(textField);
                    setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 2; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: center-left;");
                } else {
                    displayText.setText(item != null ? item : "");
                    setGraphic(displayText);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    setText(null);
                    applyRowStateStyle();
                }
            }
        }

        /**
         * 更新当前单元格对应的数据模型值
         */
        private void updateCellData(String newValue) {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return;
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return;
            int tableViewColIndex = getTableView().getColumns().indexOf(getTableColumn());
            int dataColIndex = tableViewColIndex - 1; // 减去行选择器列
            if (dataColIndex >= 0 && dataColIndex < row.size()) {
                row.set(dataColIndex, newValue);
            }
        }

        /**
         * 从数据模型获取当前单元格的值
         */
        private String getCellData() {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return getItem();
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return getItem();
            int tableViewColIndex = getTableView().getColumns().indexOf(getTableColumn());
            int dataColIndex = tableViewColIndex - 1;
            if (dataColIndex >= 0 && dataColIndex < row.size()) {
                return row.get(dataColIndex);
            }
            return getItem();
        }

        /**
         * 根据行状态应用视觉样式
         */
        private void applyRowStateStyle() {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) {
                setStyle("");
                return;
            }
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            RowState state = getRowState(row);
            switch (state) {
                case NEW ->
                    setStyle("-fx-background-color: #FFFFF0; -fx-font-style: italic; -fx-text-fill: #666; -fx-alignment: center-left;");
                case EXISTING_DIRTY ->
                    setStyle("-fx-background-color: #E8F4FD; -fx-alignment: center-left;");
                default ->
                    setStyle("-fx-alignment: center-left;");
            }
        }

        private void createTextField() {
            textField = new TextField(getItem() != null ? getItem() : "");
            textField.setMinWidth(this.getWidth() - this.getGraphicTextGap() * 2);
            // 白色背景，无边框，看起来是cell本身在编辑
            textField.setStyle("-fx-background-color: white; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0 4; -fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-text-fill: black;");
            // 记录Escape按键，用于区分用户主动取消和失焦导致的取消
            textField.setOnKeyPressed(event -> {
                escapePressed = (event.getCode() == javafx.scene.input.KeyCode.ESCAPE);
            });
            textField.setOnAction(e -> commitEdit(textField.getText()));
            textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused) {
                    commitEdit(textField.getText());
                }
            });
        }

        /** 临时类型列编辑控件：文本框 + 右侧日历图标按钮（点击弹一层日期/时间选择器） */
        private javafx.scene.layout.StackPane createTemporalEditor() {
            Button pickerBtn = new Button();
            pickerBtn.setGraphic(createCalendarIcon());
            pickerBtn.setTooltip(new Tooltip("选择" + buttonLabelForTemporal()));
            pickerBtn.setFocusTraversable(false);
            pickerBtn.setPrefWidth(26);
            pickerBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: transparent transparent transparent #ccc; -fx-border-width: 0 0 0 1; -fx-background-radius: 0; -fx-padding: 0; -fx-cursor: hand;");
            pickerBtn.setOnAction(e -> showTemporalPopup());
            textField.setMinWidth(0);
            textField.setMaxWidth(Double.MAX_VALUE);
            textField.setPadding(new javafx.geometry.Insets(0, 30, 0, 4));
            javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(textField, pickerBtn);
            stack.setStyle("-fx-background-color: #fff;");
            javafx.scene.layout.StackPane.setAlignment(pickerBtn, Pos.CENTER_RIGHT);
            javafx.scene.layout.StackPane.setAlignment(textField, Pos.CENTER_LEFT);
            return stack;
        }

        /** 日历图标（用于选择按钮，替代文字） */
        private javafx.scene.shape.SVGPath createCalendarIcon() {
            javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
            icon.setContent("M20 3h-1V1h-2v2H7V1H5v2H4c-1.11 0-1.99.9-1.99 2L2 19c0 1.1.88 2 2 2h16c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H4V8h16v11z");
            icon.setFill(javafx.scene.paint.Color.valueOf("#555"));
            icon.setScaleX(0.62);
            icon.setScaleY(0.62);
            return icon;
        }

        private String buttonLabelForTemporal() {
            if (isDateColumn()) return "日期";
            if (isTimeColumn()) return "时间";
            return "日期时间";
        }

        /** 弹一层日期/时间/日期时间选择器：直接内联日历+时间（无二级弹出），确定后填入文本框 */
        private void showTemporalPopup() {
            javafx.stage.Popup popup = new javafx.stage.Popup();
            VBox content = new VBox(8);
            content.setStyle("-fx-background-color: #fff; -fx-border-color: #999; -fx-border-width: 1; -fx-padding: 10; -fx-background-radius: 4; -fx-font-size: 12px;");
            content.setPrefWidth(260);

            // 解析初始值
            java.time.LocalDate initDate = java.time.LocalDate.now();
            int initH = 0, initM = 0, initS = 0;
            String cur = textField.getText();
            if (cur != null && !cur.trim().isEmpty() && !"NULL".equals(cur)) {
                try {
                    if (isDateColumn()) {
                        initDate = java.time.LocalDate.parse(cur.trim());
                    } else if (isTimeColumn()) {
                        String[] p = cur.trim().split(":");
                        if (p.length >= 1) initH = clamp(parseSafeInt(p[0]), 0, 23);
                        if (p.length >= 2) initM = clamp(parseSafeInt(p[1]), 0, 59);
                        if (p.length >= 3) initS = clamp(parseSafeInt(p[2]), 0, 59);
                    } else if (isDateTimeColumn()) {
                        java.time.LocalDateTime ldt = parseLenientDateTime(cur.trim());
                        if (ldt != null) {
                            initDate = ldt.toLocalDate();
                            initH = ldt.getHour();
                            initM = ldt.getMinute();
                            initS = ldt.getSecond();
                        }
                    }
                } catch (Exception ignore) {}
            }

            final java.time.LocalDate[] selectedDate = { isTimeColumn() ? null : initDate };
            Spinner<Integer> hourSp = null, minSp = null, secSp = null;
            if (isDateColumn() || isDateTimeColumn()) {
                content.getChildren().add(buildInlineCalendar(initDate, selectedDate));
            }
            if (isTimeColumn() || isDateTimeColumn()) {
                hourSp = new Spinner<>(0, 23, initH);
                minSp = new Spinner<>(0, 59, initM);
                secSp = new Spinner<>(0, 59, initS);
                for (Spinner<Integer> sp : java.util.List.of(hourSp, minSp, secSp)) {
                    sp.setEditable(true);
                    sp.setPrefWidth(56);
                    sp.setPrefHeight(30);
                    sp.setMinHeight(30);
                    sp.getEditor().setStyle("-fx-padding: 0 2; -fx-alignment: CENTER; -fx-font-size: 13px;");
                }
                HBox timeBox = new HBox(4, new Label("时"), hourSp, new Label("分"), minSp, new Label("秒"), secSp);
                timeBox.setAlignment(Pos.CENTER_LEFT);
                content.getChildren().add(timeBox);
            }

            Button applyBtn = new Button("确定");
            applyBtn.setStyle("-fx-background-color: #3592CB; -fx-text-fill: white; -fx-cursor: hand;");
            final Spinner<Integer> fH = hourSp, fM = minSp, fS = secSp;
            applyBtn.setOnAction(e -> {
                String result = formatTemporal(selectedDate[0], fH, fM, fS);
                if (!result.isEmpty()) {
                    textField.setText(result);
                    textField.positionCaret(result.length());
                }
                popup.hide();
                textField.requestFocus();
            });
            HBox btnBox = new HBox(applyBtn);
            btnBox.setAlignment(Pos.CENTER_RIGHT);
            content.getChildren().add(btnBox);

            popup.getContent().add(content);
            popup.setAutoHide(true);
            popup.setHideOnEscape(true);
            Point2D anchor = textField.localToScreen(0, textField.getHeight());
            if (anchor != null) {
                popup.show(textField, anchor.getX(), anchor.getY() + 5);
            } else {
                popup.show(textField.getScene().getWindow());
            }
        }

        /** 内联日历面板（一级弹窗内直接显示，可点日期、切月，不再触发二级弹出） */
        private VBox buildInlineCalendar(java.time.LocalDate initial, final java.time.LocalDate[] selected) {
            final java.time.LocalDate[] cursor = { initial.withDayOfMonth(1) };
            VBox box = new VBox(4);
            box.setStyle("-fx-background-color: white; -fx-alignment: center;");
            Label monthLabel = new Label();
            monthLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
            Button prev = new Button("<");
            Button next = new Button(">");
            for (Button b : java.util.List.of(prev, next)) {
                b.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-cursor: hand; -fx-padding: 0 6;");
            }
            HBox header = new HBox(8, prev, monthLabel, next);
            header.setAlignment(Pos.CENTER);

            javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
            grid.setHgap(2);
            grid.setVgap(2);
            grid.setAlignment(Pos.CENTER);

            final Runnable[] render = new Runnable[1];
            render[0] = () -> {
                monthLabel.setText(cursor[0].getYear() + "-" + String.format("%02d", cursor[0].getMonthValue()));
                grid.getChildren().clear();
                String[] hs = {"日","一","二","三","四","五","六"};
                for (int i = 0; i < 7; i++) {
                    Label l = new Label(hs[i]);
                    l.setPrefWidth(30);
                    l.setAlignment(Pos.CENTER);
                    l.setStyle("-fx-text-fill: #888;");
                    grid.add(l, i, 0);
                }
                int startDay = cursor[0].getDayOfWeek().getValue() % 7;
                int days = cursor[0].lengthOfMonth();
                for (int d = 1; d <= days; d++) {
                    final java.time.LocalDate date = cursor[0].withDayOfMonth(d);
                    Button b = new Button(String.valueOf(d));
                    b.setPrefSize(30, 24);
                    boolean isSel = date.equals(selected[0]);
                    b.setStyle(isSel
                            ? "-fx-background-color: #3592CB; -fx-text-fill: white; -fx-border-color: #3592CB; -fx-cursor: hand; -fx-font-size: 11px;"
                            : "-fx-background-color: white; -fx-text-fill: #333; -fx-border-color: #e0e0e0; -fx-cursor: hand; -fx-font-size: 11px;");
                    b.setOnAction(ev -> { selected[0] = date; render[0].run(); });
                    grid.add(b, (startDay + d - 1) % 7, (startDay + d - 1) / 7 + 1);
                }
            };
            prev.setOnAction(e -> { cursor[0] = cursor[0].minusMonths(1); render[0].run(); });
            next.setOnAction(e -> { cursor[0] = cursor[0].plusMonths(1); render[0].run(); });
            render[0].run();
            box.getChildren().addAll(header, grid);
            return box;
        }

        /** 按列类型格式化选择器结果 */
        private String formatTemporal(java.time.LocalDate date, Spinner<Integer> h, Spinner<Integer> m, Spinner<Integer> s) {
            if (isDateColumn()) {
                return date != null ? date.toString() : "";
            }
            if (isTimeColumn()) {
                return String.format("%02d:%02d:%02d", h.getValue(), m.getValue(), s.getValue());
            }
            // 日期时间
            String ds = date != null ? date.toString() : "";
            return ds + " " + String.format("%02d:%02d:%02d", h.getValue(), m.getValue(), s.getValue());
        }

        private int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }

        private int parseSafeInt(String s) {
            try { return (int) Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
        }

        private java.time.LocalDateTime parseLenientDateTime(String s) {
            try { return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); } catch (Exception e) {}
            try { return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")); } catch (Exception e) {}
            try { return java.time.LocalDateTime.parse(s.replace(' ', 'T')); } catch (Exception e) {}
            return null;
        }
    }
}
