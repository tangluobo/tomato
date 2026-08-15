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

    /** 对象信息 POJO */
    private static class ObjectInfo {
        final String name;
        final ObjectType type;
        final String comment;
        ObjectInfo(String name, ObjectType type, String comment) {
            this.name = name; this.type = type; this.comment = comment;
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
        // 同步详细列表选中
        if (obj != null) {
            for (ObservableList<String> row : detailTableView.getItems()) {
                if (row.size() >= 1 && obj.name.equals(row.get(0))) {
                    detailTableView.getSelectionModel().select(row);
                    break;
                }
            }
        }
        updateButtonStates();
    }

    // ==================== 详细列表视图 ====================

    private Node createDetailView() {
        detailTableView = new TableView<>();
        detailTableView.setEditable(false);
        detailTableView.setStyle("-fx-padding: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-background-insets: 0;");
        detailTableView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        detailTableView.setPlaceholder(new Label("暂无对象"));

        String[] titles = {"名称", "类型", "注释"};
        double[] widths = {200, 80, 300};
        for (int i = 0; i < titles.length; i++) {
            final int colIndex = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(titles[i]);
            col.setPrefWidth(widths[i]);
            col.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get(colIndex)));
            detailTableView.getColumns().add(col);
        }

        // 选中行时同步 selectedObject
        detailTableView.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            if (newRow != null && newRow.size() >= 2) {
                String name = newRow.get(0);
                String typeStr = newRow.get(1);
                ObjectType type = "视图".equals(typeStr) ? ObjectType.VIEW : ObjectType.TABLE;
                selectedObject = findObjectInfo(name, type);
                // 清除图标视图高亮（详细列表选中时不维护图标高亮）
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
                    String name = rowData.get(0);
                    String typeStr = rowData.get(1);
                    ObjectType type = "视图".equals(typeStr) ? ObjectType.VIEW : ObjectType.TABLE;
                    ObjectInfo obj = findObjectInfo(name, type);
                    if (obj != null) {
                        selectedObject = obj;
                        if (operations != null) operations.openObject(buildNodeData(obj));
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
                    obj.name,
                    obj.type == ObjectType.TABLE ? "表" : "视图",
                    obj.comment != null ? obj.comment : ""
            ));
        }
        detailTableView.setItems(rows);
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
                List<String> tables = DatabaseService.getTables(config, databaseName, schemaName);
                List<String> views = DatabaseService.getViews(config, databaseName, schemaName);

                List<ObjectInfo> objs = new ArrayList<>();
                for (String t : tables) {
                    String comment = "";
                    try {
                        comment = DatabaseService.getTableComment(config, databaseName, schemaName, t);
                    } catch (Exception ignored) {}
                    objs.add(new ObjectInfo(t, ObjectType.TABLE, comment));
                }
                for (String v : views) {
                    String comment = "";
                    try {
                        comment = DatabaseService.getTableComment(config, databaseName, schemaName, v);
                    } catch (Exception ignored) {}
                    objs.add(new ObjectInfo(v, ObjectType.VIEW, comment));
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
