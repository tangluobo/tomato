package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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
 * 表结构展示视图：以表格形式显示表的列信息（字段名、类型、长度、是否可空、是否主键、自增、默认值、注释）
 * "类型"列支持可编辑ComboBox，下拉项根据数据库类型和版本动态加载
 */
public class TableStructureView extends BorderPane {

    private static final String ROW_SELECTOR_COL = "__ROW_SELECTOR__";

    private final ConnectionConfig config;
    private final String databaseName;
    private final String tableName;

    private TableView<ObservableList<String>> tableView;
    private ProgressIndicator loadingIndicator;
    private Label statusLabel;

    /** 索引/外键/触发器/SQL预览 各标签页的组件 */
    private TableView<ObservableList<String>> indexesTableView;
    private ProgressIndicator indexesLoadingIndicator;
    private TableView<ObservableList<String>> foreignKeysTableView;
    private ProgressIndicator foreignKeysLoadingIndicator;
    private TableView<ObservableList<String>> triggersTableView;
    private ProgressIndicator triggersLoadingIndicator;

    /** 选项标签页组件 */
    private ComboBox<String> engineComboBox;
    private ComboBox<String> charsetComboBox;
    private ComboBox<String> collationComboBox;
    private TextField autoIncrementField;
    private Label autoIncrementLabel;
    private ComboBox<String> rowFormatComboBox;
    private TextField avgRowLengthField;
    private Label rowFormatLabel;
    private Label avgRowLengthLabel;
    private ProgressIndicator optionsLoadingIndicator;

    /** 注释标签页 */
    private TextArea commentTextArea;

    /** SQL预览标签页 */
    private TextArea sqlPreviewArea;

    /** 缓存的字符集->排序规则映射（用于选项标签页字符集联动，避免在FX线程查询数据库） */
    private Map<String, List<String>> cachedCharsets;

    /** 已加载标签页状态标记，避免重复加载 */
    private boolean indexesLoaded = false;
    private boolean foreignKeysLoaded = false;
    private boolean triggersLoaded = false;
    private boolean optionsLoaded = false;
    private boolean commentLoaded = false;
    private boolean sqlPreviewLoaded = false;

    /** 数据列数量（不含行选择器列） */
    private int dataColumnCount;

    /** 字段表列标题（字段名、类型、长度、非空、主键、自增、默认值、注释） */
    private List<String> columnTitles;

    /** 列注释原始值缓存（字段名 → 原始注释），用于检测变更 */
    private Map<String, String> originalColumnComments = new HashMap<>();

    /** 缓存的数据类型列表（基于当前连接的数据库类型和版本） */
    private List<String> cachedDataTypes;
    /** 缓存的数据库版本字符串 */
    private String cachedDbVersion;

    public TableStructureView(ConnectionConfig config, String databaseName, String tableName) {
        this.config = config;
        this.databaseName = databaseName;
        this.tableName = tableName;

        initializeUI();
        loadStructure();
    }

    private void initializeUI() {
        // 工具栏：保存、添加字段、插入字段、主键、上移、下移、刷新（图标+名称）
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(4, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);

        Button saveBtn = createToolBarButton("保存", createSaveIcon());
        saveBtn.setOnAction(e -> handleSave());

        Button addFieldBtn = createToolBarButton("添加字段", createAddIcon());
        addFieldBtn.setOnAction(e -> handleAddField());

        Button insertFieldBtn = createToolBarButton("插入字段", createInsertIcon());
        insertFieldBtn.setOnAction(e -> handleInsertField());

        Button deleteFieldBtn = createToolBarButton("删除", createDeleteIcon());
        deleteFieldBtn.setOnAction(e -> handleDeleteField());

        Button primaryKeyBtn = createToolBarButton("主键", createPrimaryKeyIcon());
        primaryKeyBtn.setOnAction(e -> handleTogglePrimaryKey());

        Button moveUpBtn = createToolBarButton("上移", createMoveUpIcon());
        moveUpBtn.setOnAction(e -> handleMoveUp());

        Button moveDownBtn = createToolBarButton("下移", createMoveDownIcon());
        moveDownBtn.setOnAction(e -> handleMoveDown());

        Separator separator = new Separator();
        separator.setOrientation(javafx.geometry.Orientation.VERTICAL);
        separator.setPadding(new Insets(2, 4, 2, 4));

        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> loadStructure());

        toolBar.getChildren().addAll(
                saveBtn, addFieldBtn, insertFieldBtn, deleteFieldBtn, primaryKeyBtn,
                moveUpBtn, moveDownBtn, separator, refreshBtn);

        // TableView
        tableView = new TableView<>();
        tableView.setEditable(true);
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        int rowHeight = globalConfig.getTableFontSize() + 18;
        tableView.setFixedCellSize(rowHeight);
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tableView.getSelectionModel().setCellSelectionEnabled(false);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
        tableView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        // 加载指示器
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(40, 40);
        loadingIndicator.setVisible(false);

        // 字段标签页内容：表格 + 加载指示器
        StackPane fieldsPane = new StackPane(tableView, loadingIndicator);

        // 多标签页：字段、索引、外键、触发器、选项、注释、SQL预览
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        Tab fieldsTab = new Tab("字段");
        fieldsTab.setContent(fieldsPane);

        Tab indexesTab = new Tab("索引");
        indexesTab.setContent(createIndexesPane());

        Tab foreignKeysTab = new Tab("外键");
        foreignKeysTab.setContent(createForeignKeysPane());

        Tab triggersTab = new Tab("触发器");
        triggersTab.setContent(createTriggersPane());

        Tab optionsTab = new Tab("选项");
        optionsTab.setContent(createOptionsPane());

        Tab commentTab = new Tab("注释");
        commentTab.setContent(createCommentPane());

        Tab sqlPreviewTab = new Tab("SQL预览");
        sqlPreviewTab.setContent(createSqlPreviewPane());

        tabPane.getTabs().addAll(fieldsTab, indexesTab, foreignKeysTab,
                triggersTab, optionsTab, commentTab, sqlPreviewTab);

        // 切换标签页时懒加载对应数据
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null) return;
            if (newTab == indexesTab && !indexesLoaded) {
                loadIndexes();
            } else if (newTab == foreignKeysTab && !foreignKeysLoaded) {
                loadForeignKeys();
            } else if (newTab == triggersTab && !triggersLoaded) {
                loadTriggers();
            } else if (newTab == optionsTab && !optionsLoaded) {
                loadOptions();
            } else if (newTab == commentTab && !commentLoaded) {
                loadComment();
            } else if (newTab == sqlPreviewTab && !sqlPreviewLoaded) {
                loadSqlPreview();
            }
        });

        // 状态栏
        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 12px;");
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        this.setTop(toolBar);
        this.setCenter(tabPane);
        this.setBottom(statusBar);
        this.setPadding(Insets.EMPTY);
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

    /** 添加字段图标：绿色加号 */
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

    /** 插入字段图标：蓝色向右插入箭头 */
    private Node createInsertIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Rectangle bg = new Rectangle(14, 14);
        bg.setFill(Color.valueOf("#FB8C00"));
        bg.setArcWidth(3);
        bg.setArcHeight(3);
        Polygon arrow = new Polygon(3, 7, 9, 3, 9, 11);
        arrow.setFill(Color.WHITE);
        Line bar = new Line(11, 3, 11, 11);
        bar.setStroke(Color.WHITE);
        bar.setStrokeWidth(2);
        g.getChildren().addAll(bg, arrow, bar);
        return g;
    }

    /** 主键图标：金黄色钥匙 */
    private Node createPrimaryKeyIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Rectangle bg = new Rectangle(14, 14);
        bg.setFill(Color.valueOf("#FDD835"));
        bg.setArcWidth(3);
        bg.setArcHeight(3);
        // 钥匙圆环
        javafx.scene.shape.Circle ring = new javafx.scene.shape.Circle(5, 5, 2.2);
        ring.setFill(null);
        ring.setStroke(Color.valueOf("#5D4037"));
        ring.setStrokeWidth(1.4);
        // 钥匙杆
        Line stem = new Line(6.2, 6.2, 11, 11);
        stem.setStroke(Color.valueOf("#5D4037"));
        stem.setStrokeWidth(1.4);
        // 齿
        Line tooth = new Line(9, 10, 11, 8);
        tooth.setStroke(Color.valueOf("#5D4037"));
        tooth.setStrokeWidth(1.4);
        g.getChildren().addAll(bg, ring, stem, tooth);
        return g;
    }

    /** 上移图标：蓝色向上箭头 */
    private Node createMoveUpIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Rectangle bg = new Rectangle(14, 14);
        bg.setFill(Color.valueOf("#1E88E5"));
        bg.setArcWidth(3);
        bg.setArcHeight(3);
        Polygon arrow = new Polygon(7, 2, 12, 9, 2, 9);
        arrow.setFill(Color.WHITE);
        g.getChildren().addAll(bg, arrow);
        return g;
    }

    /** 下移图标：蓝色向下箭头 */
    private Node createMoveDownIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Rectangle bg = new Rectangle(14, 14);
        bg.setFill(Color.valueOf("#1E88E5"));
        bg.setArcWidth(3);
        bg.setArcHeight(3);
        Polygon arrow = new Polygon(7, 12, 2, 5, 12, 5);
        arrow.setFill(Color.WHITE);
        g.getChildren().addAll(bg, arrow);
        return g;
    }

    /** 删除图标：红色X */
    private Node createDeleteIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Rectangle bg = new Rectangle(14, 14);
        bg.setFill(Color.valueOf("#F44336"));
        bg.setArcWidth(3);
        bg.setArcHeight(3);
        Line l1 = new Line(3, 3, 11, 11);
        l1.setStroke(Color.WHITE);
        l1.setStrokeWidth(2);
        Line l2 = new Line(11, 3, 3, 11);
        l2.setStroke(Color.WHITE);
        l2.setStrokeWidth(2);
        g.getChildren().addAll(bg, l1, l2);
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
     * 创建只读信息表格的通用方法（用于索引/外键/触发器标签页）
     */
    private TableView<ObservableList<String>> createInfoTableView() {
        TableView<ObservableList<String>> tv = new TableView<>();
        tv.setEditable(false);
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        int rowHeight = globalConfig.getTableFontSize() + 18;
        tv.setFixedCellSize(rowHeight);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tv.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
        tv.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        tv.setPlaceholder(new Label("暂无数据"));
        return tv;
    }

    /**
     * 创建小型工具栏（添加/删除/刷新）
     */
    private HBox createInfoToolBar(Button addBtn, Button deleteBtn, Button refreshBtn) {
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(4, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);
        if (addBtn != null) toolBar.getChildren().add(addBtn);
        if (deleteBtn != null) toolBar.getChildren().add(deleteBtn);
        if (refreshBtn != null) toolBar.getChildren().add(refreshBtn);
        return toolBar;
    }

    /**
     * 索引标签页：工具栏 + 表格 + 加载指示器
     */
    private BorderPane createIndexesPane() {
        Button addBtn = createToolBarButton("添加索引", createAddIcon());
        addBtn.setOnAction(e -> statusLabel.setText("添加索引功能待实现"));
        Button deleteBtn = createToolBarButton("删除", createDeleteIcon());
        deleteBtn.setOnAction(e -> statusLabel.setText("删除索引功能待实现"));
        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> { indexesLoaded = false; loadIndexes(); });

        indexesTableView = createInfoTableView();
        indexesLoadingIndicator = new ProgressIndicator();
        indexesLoadingIndicator.setMaxSize(40, 40);
        indexesLoadingIndicator.setVisible(false);
        StackPane center = new StackPane(indexesTableView, indexesLoadingIndicator);

        BorderPane pane = new BorderPane();
        pane.setTop(createInfoToolBar(addBtn, deleteBtn, refreshBtn));
        pane.setCenter(center);
        return pane;
    }

    /**
     * 外键标签页：工具栏 + 表格 + 加载指示器
     */
    private BorderPane createForeignKeysPane() {
        Button addBtn = createToolBarButton("添加外键", createAddIcon());
        addBtn.setOnAction(e -> statusLabel.setText("添加外键功能待实现"));
        Button deleteBtn = createToolBarButton("删除", createDeleteIcon());
        deleteBtn.setOnAction(e -> statusLabel.setText("删除外键功能待实现"));
        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> { foreignKeysLoaded = false; loadForeignKeys(); });

        foreignKeysTableView = createInfoTableView();
        foreignKeysLoadingIndicator = new ProgressIndicator();
        foreignKeysLoadingIndicator.setMaxSize(40, 40);
        foreignKeysLoadingIndicator.setVisible(false);
        StackPane center = new StackPane(foreignKeysTableView, foreignKeysLoadingIndicator);

        BorderPane pane = new BorderPane();
        pane.setTop(createInfoToolBar(addBtn, deleteBtn, refreshBtn));
        pane.setCenter(center);
        return pane;
    }

    /**
     * 触发器标签页：工具栏 + 表格 + 加载指示器
     */
    private BorderPane createTriggersPane() {
        Button addBtn = createToolBarButton("添加触发器", createAddIcon());
        addBtn.setOnAction(e -> statusLabel.setText("添加触发器功能待实现"));
        Button deleteBtn = createToolBarButton("删除", createDeleteIcon());
        deleteBtn.setOnAction(e -> statusLabel.setText("删除触发器功能待实现"));
        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> { triggersLoaded = false; loadTriggers(); });

        triggersTableView = createInfoTableView();
        triggersLoadingIndicator = new ProgressIndicator();
        triggersLoadingIndicator.setMaxSize(40, 40);
        triggersLoadingIndicator.setVisible(false);
        StackPane center = new StackPane(triggersTableView, triggersLoadingIndicator);

        BorderPane pane = new BorderPane();
        pane.setTop(createInfoToolBar(addBtn, deleteBtn, refreshBtn));
        pane.setCenter(center);
        return pane;
    }

    /**
     * 选项标签页：表选项表单（引擎、字符集、排序规则、自增值、行格式等）
     */
    private StackPane createOptionsPane() {
        VBox formBox = new VBox(8);
        formBox.setPadding(new Insets(12));
        formBox.setStyle("-fx-background-color: white;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        // 引擎
        Label engineLabel = new Label("引擎:");
        engineLabel.setStyle("-fx-font-size: 12px;");
        engineComboBox = new ComboBox<>();
        engineComboBox.setEditable(true);
        engineComboBox.setPrefWidth(220);
        engineComboBox.setVisibleRowCount(15);

        // 字符集
        Label charsetLabel = new Label("字符集:");
        charsetLabel.setStyle("-fx-font-size: 12px;");
        charsetComboBox = new ComboBox<>();
        charsetComboBox.setEditable(true);
        charsetComboBox.setPrefWidth(220);
        charsetComboBox.setVisibleRowCount(15);
        // 字符集变化时联动更新排序规则下拉项
        charsetComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            String currentCollation = collationComboBox.getValue();
            collationComboBox.getItems().clear();
            if (cachedCharsets != null) {
                List<String> collations = cachedCharsets.get(newVal);
                if (collations != null) {
                    collationComboBox.getItems().addAll(collations);
                }
                if (currentCollation != null && collationComboBox.getItems().contains(currentCollation)) {
                    collationComboBox.setValue(currentCollation);
                } else if (!collationComboBox.getItems().isEmpty()) {
                    collationComboBox.setValue(collationComboBox.getItems().get(0));
                }
            }
        });

        // 排序规则
        Label collationLabel = new Label("排序规则:");
        collationLabel.setStyle("-fx-font-size: 12px;");
        collationComboBox = new ComboBox<>();
        collationComboBox.setEditable(true);
        collationComboBox.setPrefWidth(220);
        collationComboBox.setVisibleRowCount(15);

        // 自增值
        autoIncrementLabel = new Label("自增值:");
        autoIncrementLabel.setStyle("-fx-font-size: 12px;");
        autoIncrementField = new TextField();
        autoIncrementField.setPrefWidth(220);
        autoIncrementField.setStyle("-fx-font-size: 12px;");

        // 行格式
        rowFormatLabel = new Label("行格式:");
        rowFormatLabel.setStyle("-fx-font-size: 12px;");
        rowFormatComboBox = new ComboBox<>();
        rowFormatComboBox.setEditable(true);
        rowFormatComboBox.setPrefWidth(220);
        rowFormatComboBox.getItems().addAll("Compact", "Dynamic", "Fixed", "Compressed", "Redundant", "Default");

        // 平均行长
        avgRowLengthLabel = new Label("平均行长:");
        avgRowLengthLabel.setStyle("-fx-font-size: 12px;");
        avgRowLengthField = new TextField();
        avgRowLengthField.setPrefWidth(220);
        avgRowLengthField.setStyle("-fx-font-size: 12px;");

        int row = 0;
        grid.add(engineLabel, 0, row);
        grid.add(engineComboBox, 1, row++);
        grid.add(charsetLabel, 0, row);
        grid.add(charsetComboBox, 1, row++);
        grid.add(collationLabel, 0, row);
        grid.add(collationComboBox, 1, row++);
        grid.add(autoIncrementLabel, 0, row);
        grid.add(autoIncrementField, 1, row++);
        grid.add(rowFormatLabel, 0, row);
        grid.add(rowFormatComboBox, 1, row++);
        grid.add(avgRowLengthLabel, 0, row);
        grid.add(avgRowLengthField, 1, row++);

        formBox.getChildren().add(grid);

        // 加载指示器
        optionsLoadingIndicator = new ProgressIndicator();
        optionsLoadingIndicator.setMaxSize(40, 40);
        optionsLoadingIndicator.setVisible(false);

        return new StackPane(formBox, optionsLoadingIndicator);
    }

    /**
     * 注释标签页：可编辑文本区域
     */
    private VBox createCommentPane() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: white;");
        Label header = new Label("表注释：");
        header.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
        commentTextArea = new TextArea();
        commentTextArea.setPromptText("请输入表注释");
        commentTextArea.setWrapText(true);
        commentTextArea.setStyle("-fx-font-size: 13px;");
        VBox.setVgrow(commentTextArea, javafx.scene.layout.Priority.ALWAYS);
        box.getChildren().addAll(header, commentTextArea);
        return box;
    }

    /**
     * SQL预览标签页：展示生成SQL的只读文本区域
     */
    private VBox createSqlPreviewPane() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: white;");
        Label header = new Label("CREATE TABLE / ALTER TABLE 预览：");
        header.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
        sqlPreviewArea = new TextArea();
        sqlPreviewArea.setEditable(false);
        sqlPreviewArea.setWrapText(true);
        sqlPreviewArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        sqlPreviewArea.setText("-- 加载中...");
        VBox.setVgrow(sqlPreviewArea, javafx.scene.layout.Priority.ALWAYS);
        box.getChildren().addAll(header, sqlPreviewArea);
        return box;
    }

    // ====== 各标签页数据加载 ======

    /**
     * 加载索引数据并填充表格
     */
    private void loadIndexes() {
        indexesLoadingIndicator.setVisible(true);
        indexesTableView.setDisable(true);
        new Thread(() -> {
            try {
                List<Map<String, String>> indexes = DatabaseService.getTableIndexes(config, databaseName, tableName);
                Platform.runLater(() -> {
                    populateInfoTable(indexesTableView, indexes, java.util.List.of("名称", "字段", "类型", "方法", "唯一", "注释"),
                            java.util.Map.of("名称", 180, "字段", 200, "类型", 100, "方法", 100, "唯一", 60, "注释", 200));
                    indexesLoaded = true;
                    indexesLoadingIndicator.setVisible(false);
                    indexesTableView.setDisable(false);
                    statusLabel.setText("共 " + indexes.size() + " 个索引");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    indexesLoadingIndicator.setVisible(false);
                    indexesTableView.setDisable(false);
                    statusLabel.setText("加载索引失败: " + e.getMessage());
                });
            }
        }, "DB-LoadIndexes").start();
    }

    /**
     * 加载外键数据并填充表格
     */
    private void loadForeignKeys() {
        foreignKeysLoadingIndicator.setVisible(true);
        foreignKeysTableView.setDisable(true);
        new Thread(() -> {
            try {
                List<Map<String, String>> fks = DatabaseService.getTableForeignKeys(config, databaseName, tableName);
                Platform.runLater(() -> {
                    populateInfoTable(foreignKeysTableView, fks,
                            java.util.List.of("名称", "字段", "参考数据库", "参考表", "参考字段", "删除时", "更新时"),
                            java.util.Map.of("名称", 160, "字段", 150, "参考数据库", 120, "参考表", 150, "参考字段", 150, "删除时", 100, "更新时", 100));
                    foreignKeysLoaded = true;
                    foreignKeysLoadingIndicator.setVisible(false);
                    foreignKeysTableView.setDisable(false);
                    statusLabel.setText("共 " + fks.size() + " 个外键");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    foreignKeysLoadingIndicator.setVisible(false);
                    foreignKeysTableView.setDisable(false);
                    statusLabel.setText("加载外键失败: " + e.getMessage());
                });
            }
        }, "DB-LoadForeignKeys").start();
    }

    /**
     * 加载触发器数据并填充表格
     */
    private void loadTriggers() {
        triggersLoadingIndicator.setVisible(true);
        triggersTableView.setDisable(true);
        new Thread(() -> {
            try {
                List<Map<String, String>> triggers = DatabaseService.getTableTriggers(config, databaseName, tableName);
                Platform.runLater(() -> {
                    populateInfoTable(triggersTableView, triggers,
                            java.util.List.of("名称", "时机", "事件", "语句"),
                            java.util.Map.of("名称", 180, "时机", 100, "事件", 100, "语句", 500));
                    triggersLoaded = true;
                    triggersLoadingIndicator.setVisible(false);
                    triggersTableView.setDisable(false);
                    statusLabel.setText("共 " + triggers.size() + " 个触发器");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    triggersLoadingIndicator.setVisible(false);
                    triggersTableView.setDisable(false);
                    statusLabel.setText("加载触发器失败: " + e.getMessage());
                });
            }
        }, "DB-LoadTriggers").start();
    }

    /**
     * 加载表选项并填充表单控件
     */
    private void loadOptions() {
        optionsLoadingIndicator.setVisible(true);
        engineComboBox.setDisable(true);
        charsetComboBox.setDisable(true);
        collationComboBox.setDisable(true);
        new Thread(() -> {
            try {
                Map<String, String> options = DatabaseService.getTableOptions(config, databaseName, tableName);
                // 加载可用引擎列表
                List<String> engines = DatabaseService.getEngines(config);
                // 加载字符集列表
                Map<String, List<String>> charsets = DatabaseService.getCharsets(config);
                Platform.runLater(() -> {
                    // 先缓存字符集映射，供字符集联动监听器使用
                    cachedCharsets = charsets;

                    // 引擎
                    engineComboBox.getItems().setAll(engines);
                    engineComboBox.setValue(options.getOrDefault("引擎", ""));

                    // 字符集（设置value会触发监听器自动填充排序规则下拉项）
                    charsetComboBox.getItems().setAll(charsets.keySet());
                    String charset = options.getOrDefault("字符集", "");
                    if (!charset.isEmpty()) {
                        charsetComboBox.setValue(charset);
                    }

                    // 排序规则（监听器已填充下拉项，这里仅设置当前值）
                    String collation = options.getOrDefault("排序规则", "");
                    if (!collation.isEmpty()) {
                        collationComboBox.setValue(collation);
                    }

                    // 自增值
                    autoIncrementField.setText(options.getOrDefault("自增值", ""));

                    // 行格式
                    rowFormatComboBox.setValue(options.getOrDefault("行格式", ""));

                    // 平均行长
                    avgRowLengthField.setText(options.getOrDefault("平均行长", ""));

                    optionsLoaded = true;
                    optionsLoadingIndicator.setVisible(false);
                    engineComboBox.setDisable(false);
                    charsetComboBox.setDisable(false);
                    collationComboBox.setDisable(false);
                    statusLabel.setText("表选项已加载");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    optionsLoadingIndicator.setVisible(false);
                    engineComboBox.setDisable(false);
                    charsetComboBox.setDisable(false);
                    collationComboBox.setDisable(false);
                    statusLabel.setText("加载表选项失败: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        }, "DB-LoadOptions").start();
    }

    /**
     * 加载表注释
     */
    private void loadComment() {
        new Thread(() -> {
            try {
                String comment = DatabaseService.getTableComment(config, databaseName, tableName);
                Platform.runLater(() -> {
                    commentTextArea.setText(comment != null ? comment : "");
                    commentLoaded = true;
                    statusLabel.setText("表注释已加载");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("加载表注释失败: " + e.getMessage()));
            }
        }, "DB-LoadComment").start();
    }

    /**
     * 加载SQL预览（SHOW CREATE TABLE）
     */
    private void loadSqlPreview() {
        sqlPreviewArea.setText("-- 加载中...");
        new Thread(() -> {
            try {
                String ddl = DatabaseService.getTableDdl(config, databaseName, tableName);
                Platform.runLater(() -> {
                    sqlPreviewArea.setText(ddl != null ? ddl : "-- 无法获取DDL");
                    sqlPreviewLoaded = true;
                    statusLabel.setText("SQL预览已加载");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sqlPreviewArea.setText("-- 加载失败: " + e.getMessage());
                    statusLabel.setText("加载SQL预览失败: " + e.getMessage());
                });
            }
        }, "DB-LoadSqlPreview").start();
    }

    /**
     * 填充只读信息表格（索引/外键/触发器通用方法）
     * @param tv 目标TableView
     * @param data 数据列表（每个元素为属性Map）
     * @param columnTitles 列标题顺序
     * @param columnWidths 列宽映射
     */
    private void populateInfoTable(TableView<ObservableList<String>> tv, List<Map<String, String>> data,
                                    List<String> columnTitles, Map<String, Integer> columnWidths) {
        tv.getColumns().clear();
        tv.getItems().clear();
        if (data.isEmpty()) return;

        for (int i = 0; i < columnTitles.size(); i++) {
            final int colIndex = i;
            String title = columnTitles.get(i);
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(title);
            col.setPrefWidth(columnWidths.getOrDefault(title, 100));
            col.setMinWidth(50);
            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                if (colIndex < row.size()) {
                    return new SimpleStringProperty(row.get(colIndex));
                }
                return new SimpleStringProperty("");
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
                        setStyle("-fx-alignment: CENTER_LEFT;");
                    }
                }
            });
            tv.getColumns().add(col);
        }

        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (Map<String, String> map : data) {
            ObservableList<String> row = FXCollections.observableArrayList();
            for (String title : columnTitles) {
                row.add(map.getOrDefault(title, ""));
            }
            rows.add(row);
        }
        tv.setItems(rows);
    }

    // ====== 工具栏动作处理（占位实现，后续对接业务逻辑） ======

    private void handleSave() {
        // 收集变更的列注释
        List<ObservableList<String>> changedColumns = new ArrayList<>();
        if (columnTitles != null && tableView.getItems() != null) {
            int commentIdx = columnTitles.indexOf("注释");
            int nameIdx = columnTitles.indexOf("字段名");
            if (commentIdx >= 0 && nameIdx >= 0) {
                for (ObservableList<String> row : tableView.getItems()) {
                    String colName = nameIdx < row.size() ? row.get(nameIdx) : "";
                    String comment = commentIdx < row.size() ? row.get(commentIdx) : "";
                    String original = originalColumnComments.getOrDefault(colName, "");
                    if (!original.equals(comment != null ? comment : "")) {
                        changedColumns.add(row);
                    }
                }
            }
        }

        // 表注释（仅当注释标签页已加载时才保存，避免空值覆盖）
        String tableComment = commentLoaded ? commentTextArea.getText() : null;
        boolean tableCommentChanged = tableComment != null;

        if (changedColumns.isEmpty() && !tableCommentChanged) {
            statusLabel.setText("没有需要保存的注释变更");
            return;
        }

        statusLabel.setText("正在保存注释...");
        new Thread(() -> {
            List<String> errors = new ArrayList<>();
            int nameIdx = columnTitles != null ? columnTitles.indexOf("字段名") : -1;

            // 保存表注释
            if (tableCommentChanged) {
                try {
                    DatabaseService.updateTableComment(config, databaseName, tableName, tableComment);
                } catch (Exception e) {
                    errors.add("表注释: " + e.getMessage());
                }
            }

            // 保存列注释
            for (ObservableList<String> row : changedColumns) {
                try {
                    DatabaseService.updateColumnComment(config, databaseName, tableName, columnTitles, row);
                } catch (Exception e) {
                    String colName = nameIdx >= 0 && nameIdx < row.size() ? row.get(nameIdx) : "?";
                    errors.add(colName + ": " + e.getMessage());
                }
            }

            Platform.runLater(() -> {
                if (errors.isEmpty()) {
                    // 更新原始注释缓存
                    int commentIdx = columnTitles != null ? columnTitles.indexOf("注释") : -1;
                    if (commentIdx >= 0 && nameIdx >= 0) {
                        for (ObservableList<String> row : tableView.getItems()) {
                            String colName = nameIdx < row.size() ? row.get(nameIdx) : "";
                            String comment = commentIdx < row.size() ? row.get(commentIdx) : "";
                            originalColumnComments.put(colName, comment != null ? comment : "");
                        }
                    }
                    statusLabel.setText("注释已保存");
                } else {
                    statusLabel.setText("保存部分失败: " + String.join("; ", errors));
                }
            });
        }, "DB-SaveComments").start();
    }

    private void handleAddField() {
        // 在表格末尾追加一个空字段行
        ObservableList<ObservableList<String>> items = tableView.getItems();
        if (items.isEmpty()) {
            statusLabel.setText("请先加载表结构");
            return;
        }
        ObservableList<String> newRow = FXCollections.observableArrayList();
        for (int i = 0; i < dataColumnCount; i++) {
            newRow.add("");
        }
        items.add(newRow);
        int newIndex = items.size() - 1;
        tableView.getSelectionModel().clearSelection();
        tableView.getSelectionModel().select(newIndex);
        statusLabel.setText("已添加字段行（未保存）");
    }

    private void handleInsertField() {
        // 在选中行之前插入一个空字段行
        ObservableList<ObservableList<String>> items = tableView.getItems();
        if (items.isEmpty()) {
            handleAddField();
            return;
        }
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        int insertIndex = selected != null ? items.indexOf(selected) : items.size();
        ObservableList<String> newRow = FXCollections.observableArrayList();
        for (int i = 0; i < dataColumnCount; i++) {
            newRow.add("");
        }
        items.add(insertIndex, newRow);
        tableView.getSelectionModel().clearSelection();
        tableView.getSelectionModel().select(insertIndex);
        statusLabel.setText("已插入字段行（未保存）");
    }

    private void handleTogglePrimaryKey() {
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择一个字段");
            return;
        }
        int pkColIndex = findColumnIndexByTitle("主键");
        if (pkColIndex < 0 || pkColIndex >= selected.size()) {
            statusLabel.setText("未找到主键列");
            return;
        }
        String current = selected.get(pkColIndex);
        selected.set(pkColIndex, "是".equals(current) ? "否" : "是");
        tableView.refresh();
        statusLabel.setText("已切换主键（未保存）");
    }

    private void handleMoveUp() {
        ObservableList<ObservableList<String>> items = tableView.getItems();
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择一个字段");
            return;
        }
        int index = items.indexOf(selected);
        if (index <= 0) {
            statusLabel.setText("已在顶部");
            return;
        }
        items.remove(index);
        items.add(index - 1, selected);
        tableView.getSelectionModel().clearSelection();
        tableView.getSelectionModel().select(index - 1);
        statusLabel.setText("已上移字段（未保存）");
    }

    private void handleMoveDown() {
        ObservableList<ObservableList<String>> items = tableView.getItems();
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择一个字段");
            return;
        }
        int index = items.indexOf(selected);
        if (index < 0 || index >= items.size() - 1) {
            statusLabel.setText("已在底部");
            return;
        }
        items.remove(index);
        items.add(index + 1, selected);
        tableView.getSelectionModel().clearSelection();
        tableView.getSelectionModel().select(index + 1);
        statusLabel.setText("已下移字段（未保存）");
    }

    private void handleDeleteField() {
        ObservableList<ObservableList<String>> items = tableView.getItems();
        if (items.isEmpty()) {
            statusLabel.setText("请先加载表结构");
            return;
        }
        List<Integer> selectedIndices = new ArrayList<>(tableView.getSelectionModel().getSelectedIndices());
        if (selectedIndices.isEmpty()) {
            statusLabel.setText("请先选择要删除的字段");
            return;
        }
        selectedIndices.sort(Collections.reverseOrder());
        for (int index : selectedIndices) {
            items.remove(index);
        }
        int count = selectedIndices.size();
        tableView.getSelectionModel().clearSelection();
        statusLabel.setText("已删除 " + count + " 个字段（未保存）");
    }

    /**
     * 根据列标题查找列在数据模型中的索引（跳过行选择器列）
     */
    private int findColumnIndexByTitle(String title) {
        for (TableColumn<ObservableList<String>, ?> col : tableView.getColumns()) {
            if (ROW_SELECTOR_COL.equals(col.getUserData())) continue;
            if (title.equals(col.getText())) {
                Integer idx = (Integer) col.getUserData();
                return idx != null ? idx : -1;
            }
        }
        return -1;
    }

    public void loadStructure() {
        loadingIndicator.setVisible(true);
        tableView.setDisable(true);

        // 重置各标签页加载状态，刷新后需重新加载
        indexesLoaded = false;
        foreignKeysLoaded = false;
        triggersLoaded = false;
        optionsLoaded = false;
        commentLoaded = false;
        sqlPreviewLoaded = false;

        new Thread(() -> {
            try {
                // 首次加载时获取数据库版本和数据类型列表
                if (cachedDataTypes == null) {
                    try {
                        cachedDbVersion = DatabaseService.getDatabaseProductVersion(config);
                    } catch (Exception e) {
                        cachedDbVersion = null; // 获取失败时使用默认列表
                    }
                    cachedDataTypes = DataTypeProvider.getDataTypes(config.getType(), cachedDbVersion);
                }

                List<Map<String, String>> columns = DatabaseService.getTableColumns(config, databaseName, tableName);
                Platform.runLater(() -> {
                    updateTableView(columns);
                    String versionInfo = cachedDbVersion != null ? " | 版本: " + cachedDbVersion : "";
                    statusLabel.setText("共 " + columns.size() + " 个字段" + versionInfo);
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
        originalColumnComments.clear();

        if (columns.isEmpty()) return;

        // 列标题名（从第一行的key集合获取，保持LinkedHashMap的插入顺序）
        this.columnTitles = new ArrayList<>(columns.get(0).keySet());
        dataColumnCount = this.columnTitles.size();

        // 创建行选择器列：选中行显示黑色实心三角箭头
        TableColumn<ObservableList<String>, String> selectorCol = new TableColumn<>();
        selectorCol.setPrefWidth(15);
        selectorCol.setMaxWidth(15);
        selectorCol.setMinWidth(15);
        selectorCol.setSortable(false);
        selectorCol.setReorderable(false);
        selectorCol.setStyle("-fx-alignment: CENTER;");
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
                setStyle("-fx-border-color: transparent #BEBEBC transparent #BEBEBC; -fx-border-width: 0 1 0 1;");
                addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        int row = getTableRow().getIndex();
                        if (event.isControlDown()) {
                            if (tableView.getSelectionModel().isSelected(row)) {
                                tableView.getSelectionModel().clearSelection(row);
                            } else {
                                tableView.getSelectionModel().select(row);
                            }
                        } else if (event.isShiftDown()) {
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
                        }
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
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
                setStyle("-fx-border-color: transparent #BEBEBC #BEBEBC #BEBEBC; -fx-border-width: 0 1 1 1;");
                arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                selectionListener = obs -> {
                    if (getTableRow() != null) {
                        arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                    }
                };
                tableView.getSelectionModel().getSelectedCells().addListener(selectionListener);
            }
        });
        tableView.getColumns().add(selectorCol);

        // 创建数据列
        for (int i = 0; i < columnTitles.size(); i++) {
            final int dataColIndex = i;
            String title = columnTitles.get(i);
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(title);

            // 存储数据列索引到userData，避免行选择器列导致的索引偏移
            col.setUserData(dataColIndex);

            // 根据标题设置列宽
            int prefWidth = switch (title) {
                case "字段名" -> 150;
                case "类型" -> 120;
                case "长度" -> 60;
                case "可为空", "非空", "主键", "自增" -> 60;
                case "默认值" -> 120;
                case "注释" -> 200;
                default -> 80;
            };
            col.setPrefWidth(prefWidth);
            col.setMinWidth(50);

            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                if (dataColIndex < row.size()) {
                    return new SimpleStringProperty(row.get(dataColIndex));
                }
                return new SimpleStringProperty("");
            });

            if ("类型".equals(title)) {
                // "类型"列使用可编辑ComboBox单元格
                List<String> dataTypes = cachedDataTypes != null ? cachedDataTypes : Collections.emptyList();
                col.setCellFactory(tc -> new DataTypeComboBoxTableCell(dataTypes, columnTitles));
                col.setOnEditCommit(event -> {
                    ObservableList<String> row = event.getRowValue();
                    String oldValue = row.get(dataColIndex);
                    String newValue = event.getNewValue();
                    if (!oldValue.equals(newValue)) {
                        row.set(dataColIndex, newValue);
                    }
                });
            } else if ("主键".equals(title) || "非空".equals(title)) {
                // "主键"/"非空"列使用复选框，点击直接切换
                col.setCellFactory(tc -> new PrimaryKeyCheckBoxTableCell());
            } else if ("字段名".equals(title) || "长度".equals(title) || "注释".equals(title)) {
                // "字段名"/"长度"/"注释"列使用可编辑TextField单元格
                col.setCellFactory(tc -> new EditableTextFieldTableCell(columnTitles));
                col.setOnEditCommit(event -> {
                    ObservableList<String> row = event.getRowValue();
                    String oldValue = row.get(dataColIndex);
                    String newValue = event.getNewValue();
                    if (!oldValue.equals(newValue)) {
                        row.set(dataColIndex, newValue);
                    }
                });
            } else {
                // 其他列保持只读
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
            }

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

        // 缓存列注释原始值（用于检测变更）
        int commentIdx = columnTitles.indexOf("注释");
        int nameIdx = columnTitles.indexOf("字段名");
        if (commentIdx >= 0 && nameIdx >= 0) {
            for (ObservableList<String> row : rows) {
                String colName = nameIdx < row.size() ? row.get(nameIdx) : "";
                String comment = commentIdx < row.size() ? row.get(commentIdx) : "";
                originalColumnComments.put(colName, comment != null ? comment : "");
            }
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

    public void applyTableConfig(GlobalConfig config) {
        int rowHeight = config.getTableFontSize() + 18;
        tableView.setFixedCellSize(rowHeight);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                config.getTableFontName(), config.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
    }

    /**
     * "主键"列的复选框单元格，点击直接切换，无需先进入编辑模式
     */
    private class PrimaryKeyCheckBoxTableCell extends TableCell<ObservableList<String>, String> {
        private final CheckBox checkBox;

        public PrimaryKeyCheckBoxTableCell() {
            this.checkBox = new CheckBox();
            checkBox.setStyle("-fx-padding: 0; -fx-alignment: center;");
            // 点击复选框时先选中行
            checkBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                tableView.getSelectionModel().select(getIndex());
            });
            // 点击复选框直接更新数据模型
            checkBox.setOnAction(e -> {
                TableRow<?> tableRow = getTableRow();
                if (tableRow == null || tableRow.getItem() == null) return;
                @SuppressWarnings("unchecked")
                ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
                Integer dataColIndex = (Integer) getTableColumn().getUserData();
                if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                    row.set(dataColIndex, checkBox.isSelected() ? "是" : "否");
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setStyle("-fx-border-color: transparent; -fx-padding: 0;");
            } else {
                checkBox.setSelected("是".equals(item));
                setGraphic(checkBox);
                setText(null);
                setStyle("-fx-alignment: center; -fx-border-color: transparent #e0e0e0 #e0e0e0 transparent; -fx-border-width: 0 1 1 0; -fx-padding: 0;");
            }
        }
    }

    /**
     * "类型"列的可编辑ComboBox单元格（始终显示ComboBox，点击即展开下拉）
     */
    private class DataTypeComboBoxTableCell extends TableCell<ObservableList<String>, String> {
        private ComboBox<String> comboBox;
        private FilteredList<String> filteredItems;
        private final List<String> dataTypes;
        private final List<String> columnTitles;
        /** 标记用户是否按下了Escape键（真正取消编辑） */
        private boolean escapePressed = false;
        /** 选中状态监听器，用于在行选中/取消选中时更新样式 */
        private javafx.beans.InvalidationListener selectionListener;

        public DataTypeComboBoxTableCell(List<String> dataTypes, List<String> columnTitles) {
            this.dataTypes = dataTypes;
            this.columnTitles = columnTitles;
            setStyle("-fx-padding: 0; -fx-border-color: transparent;");
        }

        @Override
        public void startEdit() {
            escapePressed = false;
            super.startEdit();
            if (comboBox == null) {
                createComboBox();
            }
            // 重置过滤，显示全部类型
            filteredItems.setPredicate(p -> true);
            comboBox.setValue(getItem() != null ? getItem() : "");
            setText(null);
            setGraphic(comboBox);
            // 编辑状态：白色背景+蓝色边框
            comboBox.setStyle(
                "-fx-background-radius: 0; -fx-border-radius: 0; " +
                "-fx-border-color: transparent; " +
                "-fx-padding: 0; " +
                "-fx-pref-height: 24px;"
            );
            setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-padding: 0; -fx-text-fill: black;");
            // 延迟聚焦编辑器并自动展开下拉
            Platform.runLater(() -> {
                comboBox.getEditor().requestFocus();
                comboBox.getEditor().selectAll();
                comboBox.show();
            });
        }

        @Override
        public void cancelEdit() {
            // 非Escape触发的cancel（如点击其他cell导致失焦），保留编辑值到数据模型
            if (!escapePressed && comboBox != null) {
                String newValue = comboBox.getValue();
                String currentValue = getItem() != null ? getItem() : "";
                if (newValue != null && !newValue.equals(currentValue)) {
                    updateCellData(newValue);
                }
            }
            escapePressed = false;
            super.cancelEdit();
            // cancelEdit后getItem()返回原值，但数据模型可能已更新，需从数据模型读取显示值
            String displayValue = getCellData();
            if (comboBox != null) {
                comboBox.setValue(displayValue != null ? displayValue : "");
            }
            setText(null);
            setGraphic(comboBox);
            applyRowStateStyle();
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            // 清理旧的选中状态监听器
            if (selectionListener != null) {
                tableView.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                selectionListener = null;
            }
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-border-color: transparent; -fx-padding: 0;");
            } else {
                if (comboBox == null) {
                    createComboBox();
                }
                String value = item != null ? item : "";
                comboBox.setValue(value);
                setText(null);
                setGraphic(comboBox);
                if (isEditing()) {
                    setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: CENTER_LEFT;");
                } else {
                    // 非编辑状态：ComboBox看起来像普通文本
                    comboBox.setStyle(
                        "-fx-background-radius: 0; -fx-border-radius: 0; " +
                        "-fx-border-color: transparent; " +
                        "-fx-padding: 0; " +
                        "-fx-pref-height: 24px;"
                    );
                    applyRowStateStyle();
                    // 注册选中状态监听器，选中/取消选中时更新样式
                    selectionListener = obs -> {
                        if (!isEditing()) {
                            applyRowStateStyle();
                        }
                    };
                    tableView.getSelectionModel().getSelectedCells().addListener(selectionListener);
                }
            }
        }

        private void createComboBox() {
            comboBox = new ComboBox<>();
            comboBox.setEditable(true);
            // 使用FilteredList包装，底层列表始终保持全部类型，通过predicate控制下拉显示
            ObservableList<String> sourceItems = FXCollections.observableArrayList(dataTypes);
            filteredItems = new FilteredList<>(sourceItems, p -> true);
            comboBox.setItems(filteredItems);
            comboBox.setVisibleRowCount(20);
            comboBox.getStyleClass().add("combo-box-table-cell");
            comboBox.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

            // 紧凑样式：透明边框，适配表格行高
            comboBox.setStyle(
                "-fx-background-radius: 0; -fx-border-radius: 0; " +
                "-fx-border-color: transparent; " +
                "-fx-padding: 0; " +
                "-fx-pref-height: 24px;"
            );

            // 点击ComboBox时先选中行，再进入编辑模式并展开下拉
            comboBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                if (!isEditing()) {
                    TableView<ObservableList<String>> tv = getTableView();
                    if (tv != null) {
                        tv.getSelectionModel().select(getIndex());
                        tv.edit(getIndex(), getTableColumn());
                        e.consume();
                    }
                }
            });

            // 输入时过滤下拉项（仅当编辑器有焦点时，即用户主动输入）
            comboBox.getEditor().textProperty().addListener((obs, oldValue, newValue) -> {
                if (!comboBox.getEditor().isFocused()) return;
                String input = newValue != null ? newValue.trim() : "";
                if (input.isEmpty()) {
                    filteredItems.setPredicate(p -> true);
                } else {
                    String lowerInput = input.toLowerCase();
                    filteredItems.setPredicate(t -> t.toLowerCase().contains(lowerInput));
                }
                // 延迟确保过滤后下拉列表保持显示
                Platform.runLater(() -> {
                    if (comboBox.getEditor().isFocused() && !comboBox.isShowing()) {
                        comboBox.show();
                    }
                });
            });

            // 记录Escape按键，用于区分用户主动取消和失焦导致的取消
            comboBox.setOnKeyPressed(event -> {
                escapePressed = (event.getCode() == KeyCode.ESCAPE);
            });

            // 选择下拉项或回车时提交编辑
            comboBox.setOnAction(e -> {
                if (comboBox.getValue() != null) {
                    commitEdit(comboBox.getValue());
                }
            });

            // 失焦时提交编辑（弹窗显示时不提交，用户可能在选择项）
            comboBox.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused && !escapePressed && !comboBox.isShowing()) {
                    commitEdit(comboBox.getValue() != null ? comboBox.getValue() : "");
                }
            });
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
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
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
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                return row.get(dataColIndex);
            }
            return getItem();
        }

        /**
         * 根据行状态应用视觉样式（主键行高亮、选中行蓝色背景白色文字）
         */
        private void applyRowStateStyle() {
            int pkColIndex = columnTitles.indexOf("主键");
            if (pkColIndex >= 0) {
                TableRow<?> currentRow = getTableRow();
                if (currentRow != null && currentRow.getItem() instanceof ObservableList row) {
                    String isPk = pkColIndex < row.size() ? (String) row.get(pkColIndex) : "";
                    if ("是".equals(isPk)) {
                        if (currentRow.isSelected()) {
                            setStyle("-fx-background-color: #3592CB; -fx-text-fill: white; -fx-font-weight: bold;");
                            if (comboBox != null) {
                                comboBox.setStyle(
                                    "-fx-background-radius: 0; -fx-border-radius: 0; " +
                                    "-fx-border-color: transparent; -fx-padding: 0; " +
                                    "-fx-pref-height: 24px; -fx-background-color: #3592CB;"
                                );
                                comboBox.getEditor().setStyle("-fx-text-fill: white; -fx-background-color: #3592CB; -fx-padding: 0 4; -fx-border-color: transparent;");
                            }
                        } else {
                            setStyle("-fx-font-weight: bold; -fx-text-fill: #1E88E5;");
                            if (comboBox != null) {
                                comboBox.setStyle(
                                    "-fx-background-radius: 0; -fx-border-radius: 0; " +
                                    "-fx-border-color: transparent; -fx-padding: 0; " +
                                    "-fx-pref-height: 24px;"
                                );
                                comboBox.getEditor().setStyle("-fx-text-fill: #1E88E5; -fx-padding: 0 4; -fx-border-color: transparent; -fx-background-color: transparent;");
                            }
                        }
                        return;
                    }
                }
            }
            // 非主键行：检查是否选中
            TableRow<?> currentRow = getTableRow();
            if (currentRow != null && currentRow.isSelected()) {
                setStyle("-fx-background-color: #3592CB; -fx-text-fill: white;");
                if (comboBox != null) {
                    comboBox.setStyle(
                        "-fx-background-radius: 0; -fx-border-radius: 0; " +
                        "-fx-border-color: transparent; -fx-padding: 0; " +
                        "-fx-pref-height: 24px; -fx-background-color: #3592CB;"
                    );
                    comboBox.getEditor().setStyle("-fx-text-fill: white; -fx-background-color: #3592CB; -fx-padding: 0 4; -fx-border-color: transparent;");
                }
            } else {
                setStyle("");
                if (comboBox != null) {
                    comboBox.setStyle(
                        "-fx-background-radius: 0; -fx-border-radius: 0; " +
                        "-fx-border-color: transparent; -fx-padding: 0; " +
                        "-fx-pref-height: 24px;"
                    );
                    comboBox.getEditor().setStyle("-fx-padding: 0 4; -fx-border-color: transparent; -fx-background-color: transparent;");
                }
            }
        }
    }

    /**
     * "字段名"/"长度"列的可编辑TextField单元格（双击进入编辑模式）
     */
    private class EditableTextFieldTableCell extends TableCell<ObservableList<String>, String> {
        private TextField textField;
        private final List<String> columnTitles;
        /** 标记用户是否按下了Escape键（真正取消编辑） */
        private boolean escapePressed = false;
        /** 选中状态监听器，用于在行选中/取消选中时更新样式 */
        private javafx.beans.InvalidationListener selectionListener;

        public EditableTextFieldTableCell(List<String> columnTitles) {
            this.columnTitles = columnTitles;
            setStyle("-fx-padding: 0; -fx-border-color: transparent;");
        }

        @Override
        public void startEdit() {
            escapePressed = false;
            super.startEdit();
            if (textField == null) {
                createTextField();
            }
            setText(null);
            setGraphic(textField);
            textField.setText(getItem() != null ? getItem() : "");
            // 编辑状态：白色背景+蓝色边框，内容垂直居中、水平左对齐
            setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: CENTER_LEFT;");
            Platform.runLater(() -> {
                textField.requestFocus();
                textField.selectAll();
            });
        }

        @Override
        public void cancelEdit() {
            // 非Escape触发的cancel（如点击其他cell导致失焦），保留编辑值到数据模型
            if (!escapePressed && textField != null) {
                String newValue = textField.getText();
                String currentValue = getItem() != null ? getItem() : "";
                if (!newValue.equals(currentValue)) {
                    updateCellData(newValue);
                }
            }
            escapePressed = false;
            super.cancelEdit();
            String displayValue = getCellData();
            setText(displayValue != null ? displayValue : "");
            setGraphic(null);
            applyRowStateStyle();
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            // 清理旧的选中状态监听器
            if (selectionListener != null) {
                tableView.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                selectionListener = null;
            }
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
                    setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: CENTER_LEFT;");
                } else {
                    setText(item != null ? item : "");
                    setGraphic(null);
                    applyRowStateStyle();
                    // 注册选中状态监听器，选中/取消选中时更新样式
                    selectionListener = obs -> {
                        if (!isEditing()) {
                            applyRowStateStyle();
                        }
                    };
                    tableView.getSelectionModel().getSelectedCells().addListener(selectionListener);
                }
            }
        }

        private void createTextField() {
            textField = new TextField(getItem() != null ? getItem() : "");
            textField.setMinWidth(this.getWidth() - this.getGraphicTextGap() * 2);
            textField.setStyle("-fx-background-color: white; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0 4; -fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-text-fill: black; -fx-alignment: CENTER_LEFT;");
            textField.setOnKeyPressed(event -> {
                escapePressed = (event.getCode() == KeyCode.ESCAPE);
            });
            textField.setOnAction(e -> commitEdit(textField.getText()));
            textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused && !escapePressed) {
                    commitEdit(textField.getText());
                }
            });
        }

        private void updateCellData(String newValue) {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return;
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return;
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                row.set(dataColIndex, newValue);
            }
        }

        private String getCellData() {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return getItem();
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return getItem();
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                return row.get(dataColIndex);
            }
            return getItem();
        }

        private void applyRowStateStyle() {
            int pkColIndex = columnTitles.indexOf("主键");
            if (pkColIndex >= 0) {
                TableRow<?> currentRow = getTableRow();
                if (currentRow != null && currentRow.getItem() instanceof ObservableList row) {
                    String isPk = pkColIndex < row.size() ? (String) row.get(pkColIndex) : "";
                    if ("是".equals(isPk)) {
                        if (currentRow.isSelected()) {
                            setStyle("-fx-background-color: #3592CB; -fx-text-fill: white; -fx-font-weight: bold;");
                        } else {
                            setStyle("-fx-font-weight: bold; -fx-text-fill: #1E88E5;");
                        }
                        return;
                    }
                }
            }
            // 非主键行：检查是否选中
            TableRow<?> currentRow = getTableRow();
            if (currentRow != null && currentRow.isSelected()) {
                setStyle("-fx-background-color: #3592CB; -fx-text-fill: white;");
            } else {
                setStyle("");
            }
        }
    }
}
