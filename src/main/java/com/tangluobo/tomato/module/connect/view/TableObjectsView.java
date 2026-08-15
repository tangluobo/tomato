package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.DatabaseNodeData;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对象视图：展示当前数据库/Schema 下所有表和视图对象。
 * 支持三种视图方式：图标列表、详细列表（表格）、ER视图（实体关系图），
 * 通过右下角状态栏最右侧的切换控件切换。
 * 工具栏提供：打开表、设计表、新建表、删除表、导入向导、导出向导、刷新。
 */
public class TableObjectsView extends BorderPane {

    /** 视图类型 */
    private enum ViewType { ICON, DETAIL, ER }

    /** 对象类型 */
    private enum ObjectType { TABLE, VIEW }

    /** 对象信息 POJO（详细列表所有列） */
    private static class ObjectInfo {
        final String name;
        final ObjectType type;
        final String comment;
        final String engine;
        final String autoIncrement;
        final String updateTime;
        final String dataLength;
        final String rows;
        ObjectInfo(String name, ObjectType type, String comment,
                   String engine, String autoIncrement, String updateTime,
                   String dataLength, String rows) {
            this.name = name; this.type = type; this.comment = comment;
            this.engine = engine; this.autoIncrement = autoIncrement;
            this.updateTime = updateTime; this.dataLength = dataLength;
            this.rows = rows;
        }
    }

    /** 外键关系 POJO（用于 ER 视图） */
    private static class FkRelation {
        final String sourceTable;
        final String sourceColumns;
        final String refTable;
        final String refColumns;
        FkRelation(String sourceTable, String sourceColumns, String refTable, String refColumns) {
            this.sourceTable = sourceTable;
            this.sourceColumns = sourceColumns;
            this.refTable = refTable;
            this.refColumns = refColumns;
        }
    }

    /**
     * 对象操作回调接口：由 handler 注入实现，解耦视图与业务逻辑。
     */
    public interface ObjectOperations {
        /** 打开表/视图数据 */
        void openObject(DatabaseNodeData data);
        /** 设计表结构 */
        void designObject(DatabaseNodeData data);
        /** 新建表 */
        void createTable();
        /** 删除表/视图（视图内部已刷新，无需再刷新） */
        void deleteObject(DatabaseNodeData data);
        /** 导入向导 */
        void importWizard();
        /** 导出向导 */
        void exportWizard();
    }

    private final ConnectionConfig config;
    private final String databaseName;
    private final String schemaName;
    private final ObjectOperations operations;

    // 三种视图容器
    private ScrollPane iconScroll;
    private FlowPane iconFlowPane;
    private TableView<ObservableList<String>> detailTableView;
    private ScrollPane erScroll;
    private Pane erCanvas;

    // 状态栏组件
    private Label countLabel;
    private ToggleButton iconBtn, detailBtn, erBtn;
    private ToggleGroup viewToggleGroup;
    private ProgressIndicator loadingIndicator;

    // 工具栏按钮（按选中状态启用/禁用）
    private Button openTableBtn, designTableBtn, deleteTableBtn;

    // 数据缓存
    private List<ObjectInfo> objects = new ArrayList<>();
    private List<FkRelation> foreignKeys = new ArrayList<>();
    /** 当前选中的对象（图标视图点击或详细列表选中行） */
    private ObjectInfo selectedObject;
    /** 当前图标视图中选中的 VBox（用于高亮切换） */
    private VBox selectedIconBox;

    // 图标缓存
    private Image tableImg;
    private Image viewImg;

    public TableObjectsView(ConnectionConfig config, String databaseName, String schemaName,
                            ObjectOperations operations) {
        this.config = config;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.operations = operations;

        try { tableImg = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { tableImg = null; }
        try { viewImg = new Image(getClass().getResourceAsStream("/images/connect/view.png")); } catch (Exception e) { viewImg = null; }

        setPadding(Insets.EMPTY);
        setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0; -fx-background-color: #ffffff;");
        getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        setTop(createToolBar());

        // 中间容器：StackPane 容纳三种视图 + 加载指示器
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(40, 40);
        loadingIndicator.setVisible(false);

        StackPane centerStack = new StackPane();
        centerStack.setStyle("-fx-background-color: #ffffff; -fx-padding: 0; -fx-background-insets: 0;");
        centerStack.getChildren().addAll(createIconView(), createDetailView(), createErView(), loadingIndicator);
        setCenter(centerStack);

        setBottom(createStatusBar());

        switchView(ViewType.ICON);
        updateButtonStates();
        loadData();
    }

    // ==================== 顶部工具栏 ====================

    private HBox createToolBar() {
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(4, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);

        openTableBtn = createToolBarButton("打开表", createImageIcon("/images/connect/table.png", 16));
        openTableBtn.setOnAction(e -> handleOpenTable());

        designTableBtn = createToolBarButton("设计表", createImageIcon("/images/connect/table_edit.png", 16));
        designTableBtn.setOnAction(e -> handleDesignTable());

        Button createTableBtn = createToolBarButton("新建表", createImageIcon("/images/connect/table_add.png", 16));
        createTableBtn.setOnAction(e -> {
            if (operations != null) operations.createTable();
        });

        deleteTableBtn = createToolBarButton("删除表", createImageIcon("/images/connect/table_drop.png", 16));
        deleteTableBtn.setOnAction(e -> handleDeleteTable());

        Separator sep1 = new Separator();
        sep1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep1.setPadding(new Insets(2, 4, 2, 4));

        Button importBtn = createToolBarButton("导入向导", createImageIcon("/images/connect/backup1.png", 16));
        importBtn.setOnAction(e -> {
            if (operations != null) operations.importWizard();
        });

        Button exportBtn = createToolBarButton("导出向导", createImageIcon("/images/connect/backup.png", 16));
        exportBtn.setOnAction(e -> {
            if (operations != null) operations.exportWizard();
        });

        Separator sep2 = new Separator();
        sep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep2.setPadding(new Insets(2, 4, 2, 4));

        Button refreshBtn = createToolBarButton("刷新", createImageIcon("/images/connect/refresh.png", 16));
        refreshBtn.setOnAction(e -> loadData());

        toolBar.getChildren().addAll(
                openTableBtn, designTableBtn, createTableBtn, deleteTableBtn,
                sep1, importBtn, exportBtn, sep2, refreshBtn);
        return toolBar;
    }

    /** 创建工具栏按钮（图标+文字），样式参考 TableStructureView.createToolBarButton */
    private Button createToolBarButton(String text, Node icon) {
        Button btn = new Button(text);
        btn.getStyleClass().add("toolbar-button");
        btn.setStyle("-fx-font-size: 12px; -fx-padding: 4 8; -fx-content-display: LEFT; -fx-graphic-text-gap: 4;");
        if (icon != null) {
            btn.setGraphic(icon);
        }
        return btn;
    }

    /** 从资源目录加载图标图片，返回指定尺寸的 ImageView */
    private Node createImageIcon(String resourcePath, int size) {
        try {
            Image img = new Image(getClass().getResourceAsStream(resourcePath));
            ImageView iv = new ImageView(img);
            iv.setFitWidth(size);
            iv.setFitHeight(size);
            iv.setPreserveRatio(true);
            return iv;
        } catch (Exception e) {
            return new Label("");
        }
    }

    // ==================== 工具栏按钮动作 ====================

    private void handleOpenTable() {
        if (selectedObject == null || operations == null) return;
        operations.openObject(buildNodeData(selectedObject));
    }

    private void handleDesignTable() {
        if (selectedObject == null || operations == null) return;
        operations.designObject(buildNodeData(selectedObject));
    }

    private void handleDeleteTable() {
        if (selectedObject == null || operations == null) return;
        operations.deleteObject(buildNodeData(selectedObject));
    }

    private DatabaseNodeData buildNodeData(ObjectInfo obj) {
        return new DatabaseNodeData(
                obj.type == ObjectType.TABLE ? DatabaseNodeData.NodeType.TABLE : DatabaseNodeData.NodeType.VIEW,
                obj.name, config, databaseName, schemaName);
    }

    /** 选中对象变化时更新按钮启用状态 */
    private void updateButtonStates() {
        boolean hasSelection = selectedObject != null;
        if (openTableBtn != null) openTableBtn.setDisable(!hasSelection);
        if (designTableBtn != null) designTableBtn.setDisable(!hasSelection);
        if (deleteTableBtn != null) deleteTableBtn.setDisable(!hasSelection);
    }

    // ==================== 图标列表视图 ====================

    private Node createIconView() {
        iconFlowPane = new FlowPane();
        iconFlowPane.setPadding(new Insets(12, 12, 12, 12));
        iconFlowPane.setHgap(16);
        iconFlowPane.setVgap(16);
        iconFlowPane.setStyle("-fx-background-color: #ffffff;");

        iconScroll = new ScrollPane(iconFlowPane);
        iconScroll.setFitToWidth(true);
        iconScroll.setFitToHeight(true);
        iconScroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-background-insets: 0; -fx-padding: 0; -fx-border-insets: 0;");
        iconScroll.getStyleClass().add("session-scroll-pane");
        return iconScroll;
    }

    private void populateIconView() {
        iconFlowPane.getChildren().clear();
        selectedIconBox = null;
        for (ObjectInfo obj : objects) {
            VBox item = new VBox(4);
            item.setAlignment(Pos.CENTER);
            item.setPrefWidth(80);
            item.setCursor(Cursor.HAND);

            Image img = obj.type == ObjectType.TABLE ? tableImg : viewImg;
            if (img != null) {
                ImageView iv = new ImageView(img);
                iv.setFitWidth(32);
                iv.setFitHeight(32);
                item.getChildren().add(iv);
            }

            Label name = new Label(obj.name);
            name.setStyle("-fx-font-size: 12px;");
            name.setWrapText(true);
            name.setMaxWidth(76);
            name.setAlignment(Pos.CENTER);
            name.setTextAlignment(TextAlignment.CENTER);
            item.getChildren().add(name);

            item.setOnMouseClicked(e -> {
                selectObject(obj, item);
                if (e.getClickCount() == 2) {
                    handleOpenTable();
                }
            });
            iconFlowPane.getChildren().add(item);
        }
    }

    /** 选中某个对象（图标视图）：更新 selectedObject、高亮、同步详细列表选中 */
    private void selectObject(ObjectInfo obj, VBox iconBox) {
        // 清除上一个高亮
        if (selectedIconBox != null) {
            selectedIconBox.setStyle("");
        }
        this.selectedObject = obj;
        this.selectedIconBox = iconBox;
        if (iconBox != null) {
            iconBox.setStyle("-fx-background-color: #d4edda; -fx-background-radius: 4; -fx-border-color: #07c160; -fx-border-radius: 4;");
        }
        // 同步详细列表选中（使用 COL_KEY_NAME + COL_KEY_TYPE 双重匹配避免同名混淆）
        if (obj != null) {
            String expectedType = obj.type == ObjectType.TABLE ? "TABLE" : "VIEW";
            for (ObservableList<String> row : detailTableView.getItems()) {
                if (row.size() > COL_KEY_TYPE
                        && obj.name.equals(row.get(COL_KEY_NAME))
                        && expectedType.equals(row.get(COL_KEY_TYPE))) {
                    detailTableView.getSelectionModel().select(row);
                    break;
                }
            }
        }
        updateButtonStates();
    }

    // ==================== 详细列表视图 ====================

    /** 详细列表行数据：0=名(显示用), 1=自动递增值, 2=修改日期, 3=数据长度, 4=引擎, 5=行, 6=注释, 7=名(原始key,选中同步用), 8=类型(原始key,选中同步用) */
    private static final int COL_NAME = 0;
    private static final int COL_AUTOINC = 1;
    private static final int COL_UPDATETIME = 2;
    private static final int COL_DATALEN = 3;
    private static final int COL_ENGINE = 4;
    private static final int COL_ROWS = 5;
    private static final int COL_COMMENT = 6;
    private static final int COL_KEY_NAME = 7;
    private static final int COL_KEY_TYPE = 8;

    private Node createDetailView() {
        detailTableView = new TableView<>();
        detailTableView.setEditable(false);
        detailTableView.getStyleClass().add("objects-detail-table");
        detailTableView.setStyle("-fx-padding: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-background-insets: 0;");
        detailTableView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        detailTableView.setPlaceholder(new Label("暂无对象"));

        // 列定义：名、自动递增值、修改日期、数据长度、引擎、行、注释
        // 列宽自适应：初始给默认值避免首帧闪烁，数据加载后由 autoFitColumns 按内容+表头计算
        String[] titles = {"名", "自动递增值", "修改日期", "数据长度", "引擎", "行", "注释"};
        final double colMin = 60;
        final double colMax = 400;

        // 0. 名列（图标 + 名，自定义单元格）
        {
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(titles[0]);
            col.setMinWidth(colMin);
            col.setMaxWidth(colMax);
            col.setPrefWidth(160); // 初始值，autoFitColumns 会覆盖
            col.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get(COL_NAME)));
            col.setCellFactory(tc -> new TableCell<ObservableList<String>, String>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null);
                        setText(null);
                        return;
                    }
                    ObservableList<String> row = getTableView().getItems().get(getIndex());
                    String typeKey = row.size() > COL_KEY_TYPE ? row.get(COL_KEY_TYPE) : "TABLE";
                    boolean isView = "VIEW".equals(typeKey);
                    Image img = isView ? viewImg : tableImg;
                    HBox box = new HBox(6);
                    box.setAlignment(Pos.CENTER_LEFT);
                    if (img != null) {
                        ImageView iv = new ImageView(img);
                        iv.setFitWidth(16);
                        iv.setFitHeight(16);
                        iv.setPreserveRatio(true);
                        box.getChildren().add(iv);
                    }
                    Label nameLbl = new Label(item);
                    nameLbl.setStyle("-fx-font-size: 12px;");
                    box.getChildren().add(nameLbl);
                    setGraphic(box);
                    setText(null);
                }
            });
            detailTableView.getColumns().add(col);
        }

        // 1-6. 其余列（普通文本）
        for (int i = 1; i <= 6; i++) {
            final int colIndex = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(titles[i]);
            col.setMinWidth(colMin);
            col.setMaxWidth(colMax);
            col.setPrefWidth(100); // 初始值，autoFitColumns 会覆盖
            col.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get(colIndex)));
            detailTableView.getColumns().add(col);
        }

        // 选中行时同步 selectedObject：使用隐藏列 COL_KEY_NAME / COL_KEY_TYPE
        detailTableView.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            if (newRow != null && newRow.size() > COL_KEY_TYPE) {
                String name = newRow.get(COL_KEY_NAME);
                String typeKey = newRow.get(COL_KEY_TYPE);
                ObjectType type = "VIEW".equals(typeKey) ? ObjectType.VIEW : ObjectType.TABLE;
                selectedObject = findObjectInfo(name, type);
                if (selectedIconBox != null) {
                    selectedIconBox.setStyle("");
                    selectedIconBox = null;
                }
                updateButtonStates();
            } else {
                selectedObject = null;
                updateButtonStates();
            }
        });

        detailTableView.setRowFactory(tv -> {
            TableRow<ObservableList<String>> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    ObservableList<String> rowData = row.getItem();
                    if (rowData.size() > COL_KEY_TYPE) {
                        String name = rowData.get(COL_KEY_NAME);
                        String typeKey = rowData.get(COL_KEY_TYPE);
                        ObjectType type = "VIEW".equals(typeKey) ? ObjectType.VIEW : ObjectType.TABLE;
                        ObjectInfo obj = findObjectInfo(name, type);
                        if (obj != null) {
                            selectedObject = obj;
                            if (operations != null) operations.openObject(buildNodeData(obj));
                        }
                    }
                }
            });
            return row;
        });
        return detailTableView;
    }

    private ObjectInfo findObjectInfo(String name, ObjectType type) {
        for (ObjectInfo obj : objects) {
            if (obj.name.equals(name) && obj.type == type) {
                return obj;
            }
        }
        return null;
    }

    private void populateDetailView() {
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (ObjectInfo obj : objects) {
            rows.add(FXCollections.observableArrayList(
                    obj.name,                                                // 0 名(显示)
                    obj.autoIncrement != null ? obj.autoIncrement : "",      // 1 自动递增值
                    obj.updateTime != null ? obj.updateTime : "",            // 2 修改日期
                    obj.dataLength != null ? obj.dataLength : "",            // 3 数据长度
                    obj.engine != null ? obj.engine : "",                    // 4 引擎
                    obj.rows != null ? obj.rows : "",                        // 5 行
                    obj.comment != null ? obj.comment : "",                  // 6 注释
                    obj.name,                                                // 7 名(key,选中同步用)
                    obj.type == ObjectType.TABLE ? "TABLE" : "VIEW"          // 8 类型(key,选中同步用)
            ));
        }
        detailTableView.setItems(rows);
        autoFitColumns();
    }

    /**
     * 根据表头文字和实际数据内容自适应每列宽度。
     * 取 max(表头宽度, 数据最大宽度)，并限制在 [colMin, colMax] 范围内，
     * 避免列太宽或太窄。
     */
    private void autoFitColumns() {
        if (detailTableView.getColumns().isEmpty()) return;

        // CSS: .table-cell padding = 4 8 4 8 -> 左右各 8px = 16
        // .column-header .label padding = 4 8 4 8 -> 左右各 8px = 16
        // 表头额外空间：排序图标/箭头约 20px
        final double cellHPad = 16;
        final double headerExtra = 20;
        final double minColWidth = 60;
        final double maxColWidth = 400;
        final double nameIconExtra = 22; // 第一列图标(16) + HBox gap(6)

        Font dataFont = Font.font("System", 12);

        for (int colIdx = 0; colIdx < detailTableView.getColumns().size(); colIdx++) {
            TableColumn<ObservableList<String>, ?> column = detailTableView.getColumns().get(colIdx);

            // 表头宽度
            double headerWidth = measureTextWidth(column.getText(), dataFont) + cellHPad + headerExtra;

            // 数据最大宽度
            double maxDataWidth = 0;
            for (ObservableList<String> row : detailTableView.getItems()) {
                if (colIdx < row.size() && row.get(colIdx) != null) {
                    double w = measureTextWidth(row.get(colIdx), dataFont) + cellHPad;
                    if (colIdx == COL_NAME) w += nameIconExtra;
                    if (w > maxDataWidth) maxDataWidth = w;
                }
            }

            double prefWidth = Math.max(headerWidth, maxDataWidth);
            prefWidth = Math.max(minColWidth, Math.min(maxColWidth, prefWidth));

            column.setPrefWidth(prefWidth);
        }
    }

    /** 测量文本在指定字体下的渲染宽度（基于字体 metrics，无需加入 scene graph） */
    private double measureTextWidth(String text, Font font) {
        if (text == null || text.isEmpty()) return 0;
        Text textNode = new Text(text);
        textNode.setFont(font);
        return textNode.getLayoutBounds().getWidth();
    }

    // ==================== ER 视图 ====================

    private Node createErView() {
        erCanvas = new Pane();
        erCanvas.setStyle("-fx-background-color: #fafafa;");
        erCanvas.setPrefSize(2000, 1500);

        erScroll = new ScrollPane(erCanvas);
        erScroll.setFitToWidth(false);
        erScroll.setFitToHeight(false);
        erScroll.setPannable(true);
        erScroll.setStyle("-fx-background-color: #fafafa; -fx-border-color: transparent; -fx-background-insets: 0; -fx-padding: 0; -fx-border-insets: 0;");
        erScroll.getStyleClass().add("session-scroll-pane");
        return erScroll;
    }

    private void populateErView() {
        erCanvas.getChildren().clear();
        if (objects.isEmpty()) {
            return;
        }

        // 仅表参与 ER（视图不参与）
        List<ObjectInfo> tables = new ArrayList<>();
        for (ObjectInfo obj : objects) {
            if (obj.type == ObjectType.TABLE) {
                tables.add(obj);
            }
        }
        if (tables.isEmpty()) {
            return;
        }

        // 网格布局
        Map<String, VBox> tableBoxes = new HashMap<>();
        int colSize = (int) Math.ceil(Math.sqrt(tables.size()));
        int colSpacing = 280;
        int rowSpacing = 180;
        int col = 0, row = 0;

        for (ObjectInfo obj : tables) {
            VBox tableBox = createErTableBox(obj);
            double x = col * colSpacing + 20;
            double y = row * rowSpacing + 20;
            tableBox.setLayoutX(x);
            tableBox.setLayoutY(y);
            tableBoxes.put(obj.name, tableBox);
            erCanvas.getChildren().add(tableBox);

            col++;
            if (col >= colSize) {
                col = 0;
                row++;
            }
        }

        // 绘制外键连线（布局生效后读取坐标）
        Platform.runLater(() -> {
            for (FkRelation fk : foreignKeys) {
                VBox srcBox = tableBoxes.get(fk.sourceTable);
                VBox refBox = tableBoxes.get(fk.refTable);
                if (srcBox == null || refBox == null) {
                    continue;
                }
                double sx = srcBox.getLayoutX() + srcBox.getWidth() / 2;
                double sy = srcBox.getLayoutY() + srcBox.getHeight() / 2;
                double rx = refBox.getLayoutX() + refBox.getWidth() / 2;
                double ry = refBox.getLayoutY() + refBox.getHeight() / 2;

                CubicCurve curve = new CubicCurve(sx, sy, sx, (sy + ry) / 2, rx, (sy + ry) / 2, rx, ry);
                curve.setStroke(Color.valueOf("#07c160"));
                curve.setStrokeWidth(1.5);
                curve.setFill(null);
                curve.setCursor(Cursor.HAND);
                Tooltip tooltip = new Tooltip(fk.sourceTable + "." + fk.sourceColumns
                        + "  →  " + fk.refTable + "." + fk.refColumns);
                Tooltip.install(curve, tooltip);
                curve.setOnMouseEntered(e -> curve.setStrokeWidth(2.5));
                curve.setOnMouseExited(e -> curve.setStrokeWidth(1.5));

                erCanvas.getChildren().add(curve);
                curve.toBack();
            }
        });
    }

    private VBox createErTableBox(ObjectInfo obj) {
        VBox box = new VBox();
        box.setStyle("-fx-border-color: #888; -fx-border-width: 1; -fx-background-color: white; -fx-border-radius: 4; -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, #ccc, 3, 0, 1, 1);");

        Label title = new Label(obj.name);
        title.setStyle("-fx-font-weight: bold; -fx-background-color: #07c160; -fx-text-fill: white; -fx-padding: 4 8; -fx-background-radius: 4 4 0 0;");
        title.setPrefWidth(180);
        title.setMaxWidth(180);
        title.setCursor(Cursor.HAND);
        title.setOnMouseClicked(e -> {
            selectedObject = obj;
            updateButtonStates();
            if (e.getClickCount() == 2) {
                handleOpenTable();
            }
        });
        box.getChildren().add(title);
        return box;
    }

    // ==================== 底部状态栏 ====================

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        countLabel = new Label("加载中...");
        countLabel.setStyle("-fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toggleBox = new HBox(2);
        toggleBox.setStyle("-fx-border-color: #ccc; -fx-border-width: 1; -fx-border-radius: 4; -fx-background-radius: 4; -fx-background-color: white;");

        viewToggleGroup = new ToggleGroup();
        iconBtn = createViewToggleButton(createListIconSvg(), "图标视图");
        detailBtn = createViewToggleButton(createDetailIconSvg(), "详细列表");
        erBtn = createViewToggleButton(createErIconSvg(), "ER视图");

        viewToggleGroup.getToggles().addAll(iconBtn, detailBtn, erBtn);
        iconBtn.setSelected(true);

        // 强制保持选中（避免全部取消选中）
        viewToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null && oldToggle != null) {
                oldToggle.setSelected(true);
            }
        });

        iconBtn.setOnAction(e -> { if (iconBtn.isSelected()) switchView(ViewType.ICON); });
        detailBtn.setOnAction(e -> { if (detailBtn.isSelected()) switchView(ViewType.DETAIL); });
        erBtn.setOnAction(e -> { if (erBtn.isSelected()) switchView(ViewType.ER); });

        toggleBox.getChildren().addAll(iconBtn, detailBtn, erBtn);
        statusBar.getChildren().addAll(countLabel, spacer, toggleBox);
        return statusBar;
    }

    private ToggleButton createViewToggleButton(Node icon, String tooltip) {
        ToggleButton btn = new ToggleButton();
        btn.setGraphic(icon);
        btn.setTooltip(new Tooltip(tooltip));
        btn.setStyle("-fx-background-color: transparent; -fx-padding: 4 10; -fx-border-width: 0; -fx-background-radius: 0;");
        btn.setCursor(Cursor.HAND);
        btn.selectedProperty().addListener((obs, wasSel, isSel) -> {
            if (isSel) {
                btn.setStyle("-fx-background-color: #07c160; -fx-padding: 4 10; -fx-border-width: 0; -fx-background-radius: 0;");
                applyIconColor(icon, Color.WHITE);
            } else {
                btn.setStyle("-fx-background-color: transparent; -fx-padding: 4 10; -fx-border-width: 0; -fx-background-radius: 0;");
                applyIconColor(icon, Color.valueOf("#555"));
            }
        });
        return btn;
    }

    /** 递归设置 SVGPath 的 stroke 颜色 */
    private void applyIconColor(Node icon, Color color) {
        if (icon instanceof SVGPath svg) {
            svg.setStroke(color);
        }
    }

    // ==================== SVGPath 图标 ====================

    private SVGPath createListIconSvg() {
        SVGPath p = new SVGPath();
        p.setContent("M3 5h18M3 12h18M3 19h18");
        p.setStroke(Color.valueOf("#555"));
        p.setStrokeWidth(2);
        p.setFill(null);
        return p;
    }

    private SVGPath createDetailIconSvg() {
        SVGPath p = new SVGPath();
        p.setContent("M3 3h18v18H3z M3 9h18 M3 15h18 M9 3v18 M15 3v18");
        p.setStroke(Color.valueOf("#555"));
        p.setStrokeWidth(1.5);
        p.setFill(null);
        return p;
    }

    private SVGPath createErIconSvg() {
        SVGPath p = new SVGPath();
        p.setContent("M4 4h5v5H4z M15 14h5v5h-5z M9 6.5h6 M6.5 9v5");
        p.setStroke(Color.valueOf("#555"));
        p.setStrokeWidth(1.5);
        p.setFill(null);
        return p;
    }

    // ==================== 视图切换 ====================

    private void switchView(ViewType type) {
        iconScroll.setVisible(type == ViewType.ICON);
        detailTableView.setVisible(type == ViewType.DETAIL);
        erScroll.setVisible(type == ViewType.ER);
    }

    // ==================== 数据加载 ====================

    public void refreshData() {
        loadData();
    }

    /** 删除对象后由 handler 调用，刷新列表 */
    public void notifyObjectDeleted() {
        loadData();
    }

    private void loadData() {
        loadingIndicator.setVisible(true);
        countLabel.setText("加载中...");

        new Thread(() -> {
            ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                // 批量获取所有表/视图元数据（代替逐表 getTableComment）
                List<Map<String, String>> info = DatabaseService.getTablesInfo(config, databaseName, schemaName);
                List<ObjectInfo> objs = new ArrayList<>();
                for (Map<String, String> m : info) {
                    String name = m.get("name");
                    String typeKey = m.get("type");
                    ObjectType type = "VIEW".equals(typeKey) ? ObjectType.VIEW : ObjectType.TABLE;
                    objs.add(new ObjectInfo(
                            name,
                            type,
                            m.get("comment") != null ? m.get("comment") : "",
                            m.get("engine") != null ? m.get("engine") : "",
                            m.get("autoIncrement") != null ? m.get("autoIncrement") : "",
                            m.get("updateTime") != null ? m.get("updateTime") : "",
                            m.get("dataLength") != null ? m.get("dataLength") : "",
                            m.get("rows") != null ? m.get("rows") : ""
                    ));
                }

                List<FkRelation> fks = new ArrayList<>();
                try {
                    List<Map<String, String>> fkMaps = DatabaseService.getDatabaseForeignKeys(config, databaseName, schemaName);
                    for (Map<String, String> fk : fkMaps) {
                        fks.add(new FkRelation(
                                fk.get("表名"),
                                fk.get("字段"),
                                fk.get("参考表"),
                                fk.get("参考字段")
                        ));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                final List<ObjectInfo> finalObjs = objs;
                final List<FkRelation> finalFks = fks;
                Platform.runLater(() -> {
                    this.objects = finalObjs;
                    this.foreignKeys = finalFks;
                    // 若已删除选中对象，清除选中状态
                    if (selectedObject != null && findObjectInfo(selectedObject.name, selectedObject.type) == null) {
                        selectedObject = null;
                        selectedIconBox = null;
                    }
                    populateIconView();
                    populateDetailView();
                    populateErView();

                    long tableCount = finalObjs.stream().filter(o -> o.type == ObjectType.TABLE).count();
                    long viewCount = finalObjs.size() - tableCount;
                    countLabel.setText("共 " + finalObjs.size() + " 个对象（表 " + tableCount + "，视图 " + viewCount + "）"
                            + (finalFks.isEmpty() ? "" : "  |  " + finalFks.size() + " 个外键关系"));
                    loadingIndicator.setVisible(false);
                    updateButtonStates();
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    countLabel.setText("加载失败: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("加载对象列表失败: " + e.getMessage());
                    alert.showAndWait();
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadObjects").start();
    }
}
