package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.module.connect.view.SqlEditorPane;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 复制表/数据传输配置对话框
 * - 同连接复制：CREATE TABLE ... LIKE / CREATE TABLE ... SELECT
 * - 跨连接复制：DDL建表 + 逐行数据迁移
 */
public class CopyTableDialog {

    private Stage dialogStage;
    private boolean confirmed = false;

    // 源配置
    private final ConnectionConfig sourceConfig;
    private final String sourceDatabase;
    private final String sourceSchema;
    /** 源表名列表（支持单表/多表） */
    private final List<String> sourceTables;
    /** 目标表名列表（与 sourceTables 一一对应，用户可编辑） */
    private final List<String> targetTables = new ArrayList<>();

    // 目标配置（用户选择）
    private ConnectionConfig targetConfig;
    private String targetDatabase;
    /** 兼容旧 API：单表时的目标表名 */
    private String targetTable;

    // 选项
    private boolean copyStructure = true;
    private boolean copyData = true;
    private boolean dropIfExists = false;

    // UI组件
    private ComboBox<String> sourceConnCombo;
    private ComboBox<String> sourceDbCombo;
    private ComboBox<String> targetConnCombo;
    private ComboBox<String> targetDbCombo;

    // 顶部显示引用
    private Text topTargetConnText;
    private Text topTargetDbText;
    private ImageView topTargetDbIcon;

    // 连接列表
    private final List<ConnectionConfig> allConnections;

    // 信息显示
    private VBox sourceInfoBox;
    private VBox targetInfoBox;

    /** 是否多表模式 */
    private final boolean multiTableMode;

    /** 预设的目标数据库名（粘贴场景调用 presetTarget 后，onTargetConnChange 加载完成后优先匹配此值） */
    private String presetTargetDb;

    // === 两步向导：根容器与第一步内容引用 ===
    private BorderPane root;
    private ScrollPane stepOneCenter;
    private HBox stepOneBottom;
    private VBox stepTwoCenter;
    private HBox stepTwoBottom;

    // 第二步 UI 组件
    private final VBox tableListContainer = new VBox(4);
    private final List<CheckBox> tableCheckBoxes = new ArrayList<>();
    private SqlEditorPane sqlPreviewPane;
    private CheckBox addFilterCb;
    private CheckBox dropFilterCb;
    private CheckBox modifyFilterCb;
    private Label stepTwoStatusLabel;
    private int lastTotalSqlCount = 0;

    // 第二步用户选择（供调用方执行）
    private List<String> finalSelectedTables = new ArrayList<>();
    private boolean includeAdd = true;
    private boolean includeDrop = true;
    private boolean includeModify = true;

    /**
     * 兼容旧 API 的单表构造方法。
     */
    public CopyTableDialog(Stage parent, List<ConnectionConfig> allConnections,
                           ConnectionConfig sourceConfig, String sourceDatabase,
                           String sourceTable, String sourceSchema) {
        this(parent, allConnections, sourceConfig, sourceDatabase, sourceSchema,
                sourceTable != null ? java.util.Collections.singletonList(sourceTable) : java.util.Collections.emptyList());
    }

    /**
     * 构造复制表对话框（支持多表）。
     * @param parent 父窗口
     * @param allConnections 所有连接配置列表
     * @param sourceConfig 源连接配置
     * @param sourceDatabase 源数据库名
     * @param sourceSchema 源 schema（可为 null，MySQL/Oracle 用）
     * @param sourceTables 源表名列表
     */
    public CopyTableDialog(Stage parent, List<ConnectionConfig> allConnections,
                           ConnectionConfig sourceConfig, String sourceDatabase,
                           String sourceSchema, List<String> sourceTables) {
        this.allConnections = allConnections == null ? new ArrayList<>() : allConnections;
        this.sourceConfig = sourceConfig;
        this.sourceDatabase = sourceDatabase;
        this.sourceSchema = sourceSchema;
        this.sourceTables = sourceTables == null ? new ArrayList<>() : new ArrayList<>(sourceTables);
        this.multiTableMode = this.sourceTables.size() > 1;
        // 默认目标表名 = 源表名（用户可在 UI 中修改）
        for (String t : this.sourceTables) {
            targetTables.add(t);
        }
        if (!this.sourceTables.isEmpty()) {
            this.targetTable = targetTables.get(0);
        }

        initUI(parent);
    }

    private void initUI(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("数据传输");
        dialogStage.setResizable(true);
        dialogStage.setMinWidth(800);
        dialogStage.setMinHeight(600);
        try {
            dialogStage.getIcons().add(new Image(getClass().getResourceAsStream("/images/connect/copy_tables.png")));
        } catch (Exception ignored) {
        }

        root = new BorderPane();
        root.setPadding(new Insets(0));
        root.setStyle("-fx-background-color: white;");

        // ========= 顶部：源 → 目标 显示 =========
        HBox topBar = new HBox(10);
        topBar.setPadding(new Insets(15, 20, 10, 20));
        topBar.setAlignment(Pos.CENTER);
        topBar.setStyle("-fx-background-color: white; -fx-border-color: #E5E5E5; -fx-border-width: 0 0 1 0;");

        VBox sourceTop = new VBox(4);
        sourceTop.setAlignment(Pos.CENTER);
        Text sourceConnText = new Text(sourceConfig != null ? buildConnLabel(sourceConfig) : "未选择");
        sourceConnText.setFont(Font.font("System", FontWeight.NORMAL, 14));
        Text sourceDbText = new Text(sourceDatabase != null ? sourceDatabase : "");
        sourceDbText.setFont(Font.font("System", FontWeight.NORMAL, 12));
        sourceDbText.setFill(javafx.scene.paint.Color.valueOf("#888"));
        ImageView sourceDbIcon = createDbIcon(sourceConfig);
        sourceTop.getChildren().addAll(sourceConnText, new HBox(4, sourceDbIcon, sourceDbText));

        Text arrowText = new Text("→");
        arrowText.setFont(Font.font("System", FontWeight.BOLD, 20));
        arrowText.setFill(javafx.scene.paint.Color.valueOf("#999"));
        StackPane arrowPane = new StackPane(arrowText);
        arrowPane.setPrefWidth(50);

        VBox targetTop = new VBox(4);
        targetTop.setAlignment(Pos.CENTER);
        topTargetConnText = new Text("选择目标连接");
        topTargetConnText.setFont(Font.font("System", FontWeight.NORMAL, 14));
        topTargetDbText = new Text("");
        topTargetDbText.setFont(Font.font("System", FontWeight.NORMAL, 12));
        topTargetDbText.setFill(javafx.scene.paint.Color.valueOf("#888"));
        topTargetDbIcon = createDbIcon(null);
        targetTop.getChildren().addAll(topTargetConnText, new HBox(4, topTargetDbIcon, topTargetDbText));

        topBar.getChildren().addAll(sourceTop, arrowPane, targetTop);
        root.setTop(topBar);

        // ========= 中间：配置区域 =========
        ScrollPane centerScroll = new ScrollPane();
        centerScroll.setFitToWidth(true);
        centerScroll.setStyle("-fx-background-color: white; -fx-background: white;");
        centerScroll.getStyleClass().add("session-scroll-pane");

        VBox centerBox = new VBox(20);
        centerBox.setPadding(new Insets(20, 25, 20, 25));
        centerBox.setStyle("-fx-background-color: white;");

        // 源和目标并排
        HBox configRow = new HBox(15);
        configRow.setAlignment(Pos.TOP_CENTER);

        // --- 源配置面板 ---
        VBox sourcePanel = new VBox(12);
        sourcePanel.setPadding(new Insets(0));
        HBox.setHgrow(sourcePanel, Priority.ALWAYS);
        sourcePanel.setPrefWidth(360);

        Label sourceTitle = new Label("源");
        sourceTitle.setFont(Font.font("System", FontWeight.NORMAL, 16));
        sourceTitle.setTextFill(javafx.scene.paint.Color.valueOf("#1890FF"));

        Label sourceConnLabel = new Label("连接:");
        sourceConnLabel.setStyle("-fx-font-size: 13px;");
        sourceConnCombo = new ComboBox<>();
        sourceConnCombo.setMaxWidth(Double.MAX_VALUE);
        loadDbConnectionsToCombo(sourceConnCombo);
        if (sourceConfig != null) {
            sourceConnCombo.setValue(buildConnLabel(sourceConfig));
        }
        sourceConnCombo.setDisable(true);

        Label sourceDbLabel = new Label("数据库:");
        sourceDbLabel.setStyle("-fx-font-size: 13px;");
        sourceDbCombo = new ComboBox<>();
        sourceDbCombo.setMaxWidth(Double.MAX_VALUE);
        if (sourceDatabase != null) {
            sourceDbCombo.getItems().add(sourceDatabase);
            sourceDbCombo.setValue(sourceDatabase);
        }
        sourceDbCombo.setDisable(true);

        if (multiTableMode) {
            // 多表模式：不显示表名列表，源面板只展示连接和数据库
            sourcePanel.getChildren().addAll(sourceTitle, sourceConnLabel, sourceConnCombo,
                    sourceDbLabel, sourceDbCombo);
        } else {
            // 单表模式：同样只展示连接和数据库
            sourcePanel.getChildren().addAll(sourceTitle, sourceConnLabel, sourceConnCombo,
                    sourceDbLabel, sourceDbCombo);
        }

        // --- 中间交换按钮 ---
        VBox swapBox = new VBox(10);
        swapBox.setAlignment(Pos.CENTER);
        swapBox.setPrefWidth(40);
        swapBox.setPadding(new Insets(60, 0, 0, 0));

        Button swapBtn = new Button("⇄");
        swapBtn.setStyle("-fx-font-size: 16px; -fx-pref-width: 36px; -fx-pref-height: 36px; -fx-border-radius: 4px; -fx-background-radius: 4px;");
        swapBtn.setDisable(true);
        swapBox.getChildren().add(swapBtn);

        // --- 目标配置面板 ---
        VBox targetPanel = new VBox(12);
        targetPanel.setPadding(new Insets(0));
        HBox.setHgrow(targetPanel, Priority.ALWAYS);
        targetPanel.setPrefWidth(360);

        Label targetTitle = new Label("目标");
        targetTitle.setFont(Font.font("System", FontWeight.NORMAL, 16));
        targetTitle.setTextFill(javafx.scene.paint.Color.valueOf("#1890FF"));

        HBox targetTypeBox = new HBox(20);
        ToggleGroup targetTypeGroup = new ToggleGroup();
        RadioButton targetConnRadio = new RadioButton("连接");
        targetConnRadio.setToggleGroup(targetTypeGroup);
        targetConnRadio.setSelected(true);
        RadioButton targetFileRadio = new RadioButton("文件");
        targetFileRadio.setToggleGroup(targetTypeGroup);
        targetFileRadio.setDisable(true);
        targetTypeBox.getChildren().addAll(targetConnRadio, targetFileRadio);

        targetConnCombo = new ComboBox<>();
        targetConnCombo.setMaxWidth(Double.MAX_VALUE);
        loadDbConnectionsToCombo(targetConnCombo);
        if (sourceConfig != null) {
            targetConnCombo.setValue(buildConnLabel(sourceConfig));
        }
        targetConnCombo.valueProperty().addListener((obs, oldVal, newVal) -> onTargetConnChange(newVal));

        Label targetDbLabelField = new Label("数据库:");
        targetDbLabelField.setStyle("-fx-font-size: 13px;");
        targetDbCombo = new ComboBox<>();
        targetDbCombo.setMaxWidth(Double.MAX_VALUE);
        if (sourceDatabase != null) {
            targetDbCombo.getItems().add(sourceDatabase);
            targetDbCombo.setValue(sourceDatabase);
        }
        targetDbCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                targetDatabase = newVal;
                topTargetDbText.setText(newVal);
            }
        });

        if (multiTableMode) {
            // 多表模式：不显示新表名列表，目标表名默认与源表名相同
            targetPanel.getChildren().addAll(targetTitle, targetTypeBox, targetConnCombo,
                    targetDbLabelField, targetDbCombo);
        } else {
            // 单表模式：同样不显示新表名输入框
            targetPanel.getChildren().addAll(targetTitle, targetTypeBox, targetConnCombo,
                    targetDbLabelField, targetDbCombo);
        }

        configRow.getChildren().addAll(sourcePanel, swapBox, targetPanel);
        centerBox.getChildren().add(configRow);

        // --- 源和目标信息区 ---
        HBox infoRow = new HBox(15);
        infoRow.setAlignment(Pos.TOP_CENTER);
        infoRow.setPadding(new Insets(5, 0, 0, 0));

        Separator sep1 = new Separator(Orientation.VERTICAL);
        sep1.setPrefWidth(1);
        sep1.setStyle("-fx-background-color: #E5E5E5; -fx-border-color: #E5E5E5;");

        sourceInfoBox = new VBox(8);
        sourceInfoBox.setPadding(new Insets(15));
        sourceInfoBox.setStyle("-fx-background-color: #FAFAFA; -fx-background-radius: 4px; -fx-border-color: #E5E5E5; -fx-border-radius: 4px; -fx-border-width: 1px;");
        HBox.setHgrow(sourceInfoBox, Priority.ALWAYS);

        Label sourceInfoTitle = new Label("信息");
        sourceInfoTitle.setFont(Font.font("System", FontWeight.NORMAL, 16));
        sourceInfoTitle.setTextFill(javafx.scene.paint.Color.valueOf("#1890FF"));
        sourceInfoBox.getChildren().add(sourceInfoTitle);
        refreshInfoBox(sourceInfoBox, sourceConfig);

        targetInfoBox = new VBox(8);
        targetInfoBox.setPadding(new Insets(15));
        targetInfoBox.setStyle("-fx-background-color: #FAFAFA; -fx-background-radius: 4px; -fx-border-color: #E5E5E5; -fx-border-radius: 4px; -fx-border-width: 1px;");
        HBox.setHgrow(targetInfoBox, Priority.ALWAYS);

        Label targetInfoTitle = new Label("信息");
        targetInfoTitle.setFont(Font.font("System", FontWeight.NORMAL, 16));
        targetInfoTitle.setTextFill(javafx.scene.paint.Color.valueOf("#1890FF"));
        targetInfoBox.getChildren().add(targetInfoTitle);
        refreshInfoBox(targetInfoBox, sourceConfig);

        infoRow.getChildren().addAll(sourceInfoBox, sep1, targetInfoBox);
        centerBox.getChildren().add(infoRow);

        centerScroll.setContent(centerBox);
        root.setCenter(centerScroll);

        // ========= 底部：按钮栏 =========
        HBox bottomBar = new HBox(10);
        bottomBar.setPadding(new Insets(12, 20, 12, 20));
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setStyle("-fx-background-color: #F5F5F5; -fx-border-color: #E5E5E5; -fx-border-width: 1 0 0 0;");

        Button saveConfigBtn = new Button("保存配置文件");
        saveConfigBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 32px;");
        saveConfigBtn.setDisable(true);

        MenuButton loadConfigMenu = new MenuButton("加载配置文件");
        loadConfigMenu.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 32px;");
        loadConfigMenu.setDisable(true);

        Button optionsBtn = new Button("选项");
        optionsBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 32px;");
        optionsBtn.setOnAction(e -> showOptionsDialog());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button nextBtn = new Button("下一步");
        nextBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 32px; -fx-pref-width: 100px;");
        nextBtn.setOnAction(e -> handleNext());

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 32px; -fx-pref-width: 100px;");
        cancelBtn.setOnAction(e -> dialogStage.close());

        bottomBar.getChildren().addAll(saveConfigBtn, loadConfigMenu, optionsBtn, spacer, cancelBtn, nextBtn);
        root.setBottom(bottomBar);

        // 保存第一步引用，便于第二步切回
        stepOneCenter = centerScroll;
        stepOneBottom = bottomBar;

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        dialogStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(dialogStage, parent);

        Platform.runLater(() -> onTargetConnChange(targetConnCombo.getValue()));
    }

    /** 加载所有数据库类型连接到ComboBox */
    private void loadDbConnectionsToCombo(ComboBox<String> combo) {
        List<String> dbConnections = allConnections.stream()
                .filter(cfg -> cfg.getType() != null
                        && (cfg.getType() == ConnectType.MYSQL
                            || cfg.getType() == ConnectType.POSTGRESQL
                            || cfg.getType() == ConnectType.ORACLE))
                .map(this::buildConnLabel)
                .collect(Collectors.toList());
        combo.getItems().addAll(dbConnections);
    }

    private String buildConnLabel(ConnectionConfig cfg) {
        if (cfg == null) return "";
        return (cfg.getName() != null ? cfg.getName() : "") + "  (" + (cfg.getHost() != null ? cfg.getHost() : "") + ":" + cfg.getPort() + ")";
    }

    /** 从combo的显示文本反查ConnectionConfig */
    private ConnectionConfig findConfigByLabel(String label) {
        if (label == null) return null;
        for (ConnectionConfig cfg : allConnections) {
            if (label.equals(buildConnLabel(cfg))) {
                return cfg;
            }
        }
        return null;
    }

    /** 目标连接变化：更新顶部显示 + 加载数据库列表 + 更新信息面板 */
    private void onTargetConnChange(String label) {
        // 更新顶部显示
        if (topTargetConnText != null) {
            topTargetConnText.setText(label != null ? label : "未选择");
        }
        ConnectionConfig cfg = findConfigByLabel(label);
        targetConfig = cfg;

        // 更新图标
        updateDbIcon(cfg, topTargetDbIcon);

        // 更新信息面板
        refreshInfoBox(targetInfoBox, cfg);

        // 加载目标连接的数据库列表
        targetDbCombo.getItems().clear();
        if (cfg == null) {
            return;
        }

        new Thread(() -> {
            try {
                List<String> dbs = DatabaseService.getDatabases(cfg);
                Platform.runLater(() -> {
                    targetDbCombo.getItems().addAll(dbs);
                    // 优先匹配 presetTargetDb（粘贴场景），其次匹配 sourceDatabase，最后取第一个
                    String preferred = presetTargetDb != null ? presetTargetDb : sourceDatabase;
                    if (preferred != null && dbs.contains(preferred)) {
                        targetDbCombo.setValue(preferred);
                        targetDatabase = preferred;
                        if (topTargetDbText != null) topTargetDbText.setText(preferred);
                    } else if (!dbs.isEmpty()) {
                        String first = dbs.get(0);
                        targetDbCombo.setValue(first);
                        targetDatabase = first;
                        if (topTargetDbText != null) topTargetDbText.setText(first);
                    }
                });
            } catch (Exception ex) {
                // ignore
            }
        }, "DB-LoadDbs").start();
    }

    /** 刷新信息面板 */
    private void refreshInfoBox(VBox infoBox, ConnectionConfig cfg) {
        while (infoBox.getChildren().size() > 1) {
            infoBox.getChildren().remove(1);
        }
        if (cfg == null) {
            return;
        }
        addInfoRow(infoBox, "项目名:", "我的连接");
        addInfoRow(infoBox, "连接类型:", cfg.getType() != null ? cfg.getType().getCode() : "");
        addInfoRow(infoBox, "连接名:", cfg.getName() != null ? cfg.getName() : "");
        addInfoRow(infoBox, "主机:", cfg.getHost() != null ? cfg.getHost() : "");
        addInfoRow(infoBox, "端口:", String.valueOf(cfg.getPort()));
        addInfoRow(infoBox, "服务器版本:", "");
    }

    private void addInfoRow(VBox parent, String key, String value) {
        HBox row = new HBox(10);
        Label keyLabel = new Label(key);
        keyLabel.setPrefWidth(80);
        keyLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        Label valLabel = new Label(value != null ? value : "");
        valLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
        row.getChildren().addAll(keyLabel, valLabel);
        parent.getChildren().add(row);
    }

    private ImageView createDbIcon(ConnectionConfig cfg) {
        ImageView iv = new ImageView();
        iv.setFitWidth(16);
        iv.setFitHeight(16);
        updateDbIcon(cfg, iv);
        return iv;
    }

    private void updateDbIcon(ConnectionConfig cfg, ImageView iv) {
        String iconPath = "/images/connect/db.png";
        if (cfg != null && cfg.getType() != null) {
            if (cfg.getType() == ConnectType.MYSQL) iconPath = "/images/connect/mysql.png";
            else if (cfg.getType() == ConnectType.POSTGRESQL) iconPath = "/images/connect/postgresql.png";
            else if (cfg.getType() == ConnectType.ORACLE) iconPath = "/images/connect/oracle.png";
        }
        try {
            Image img = new Image(getClass().getResourceAsStream(iconPath));
            if (img != null) iv.setImage(img);
        } catch (Exception ignore) {}
    }

    /** 显示选项弹窗：复制结构、复制数据、目标已存在时删除 */
    private void showOptionsDialog() {
        Stage optStage = new Stage();
        optStage.initModality(Modality.WINDOW_MODAL);
        optStage.initOwner(dialogStage);
        optStage.setTitle("选项");
        optStage.setResizable(false);

        VBox root = new VBox(14);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: white;");

        Label title = new Label("复制选项");
        title.setFont(Font.font("System", FontWeight.NORMAL, 14));
        title.setTextFill(javafx.scene.paint.Color.valueOf("#1890FF"));

        CheckBox copyStructureCb = new CheckBox("复制表结构 (CREATE TABLE)");
        copyStructureCb.setSelected(copyStructure);

        CheckBox copyDataCb = new CheckBox("复制表数据 (INSERT)");
        copyDataCb.setSelected(copyData);

        CheckBox dropIfExistsCb = new CheckBox("目标已存在时删除 (DROP TABLE IF EXISTS)");
        dropIfExistsCb.setSelected(dropIfExists);

        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setPadding(new Insets(10, 0, 0, 0));

        Button okBtn = new Button("确定");
        okBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 30px; -fx-pref-width: 80px;");
        okBtn.setOnAction(e -> {
            copyStructure = copyStructureCb.isSelected();
            copyData = copyDataCb.isSelected();
            dropIfExists = dropIfExistsCb.isSelected();
            optStage.close();
        });

        Button cancelOptBtn = new Button("取消");
        cancelOptBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 30px; -fx-pref-width: 80px;");
        cancelOptBtn.setOnAction(e -> optStage.close());

        btnRow.getChildren().addAll(cancelOptBtn, okBtn);
        root.getChildren().addAll(title, copyStructureCb, copyDataCb, dropIfExistsCb, btnRow);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        optStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(optStage, dialogStage);
        optStage.showAndWait();
    }

    private void handleNext() {
        targetConfig = findConfigByLabel(targetConnCombo.getValue());
        targetDatabase = targetDbCombo.getValue();

        if (targetConfig == null) {
            showAlert("请选择目标连接");
            return;
        }
        if (targetDatabase == null || targetDatabase.isEmpty()) {
            showAlert("请选择目标数据库");
            return;
        }

        // 目标表名默认与源表名相同（不提供编辑UI），targetTables 已在构造时初始化
        if (targetTables.isEmpty()) {
            showAlert("没有可复制的表");
            return;
        }
        targetTable = targetTables.get(0);

        // 切换到第二步（结构同步预览）
        showStepTwo();
    }

    // ==================== 第二步：结构同步预览 ====================

    /** 切换到第二步：构建左右分栏 + 异步加载源库所有表 */
    private void showStepTwo() {
        if (stepTwoCenter == null) {
            stepTwoCenter = buildStepTwoCenter();
            stepTwoBottom = buildStepTwoBottom();
        }
        root.setCenter(stepTwoCenter);
        root.setBottom(stepTwoBottom);
        loadSourceTables();
    }

    /** 切回第一步 */
    private void handlePrev() {
        root.setCenter(stepOneCenter);
        root.setBottom(stepOneBottom);
    }

    /** 构建第二步主体：左右分栏（左：表勾选；右：SQL 预览 + 过滤 + 复制） */
    private VBox buildStepTwoCenter() {
        SplitPane split = new SplitPane();
        split.setDividerPositions(0.35);
        split.setStyle("-fx-background-color: white;");

        // ===== 左侧：表勾选列表 =====
        VBox leftBox = new VBox(8);
        leftBox.setPadding(new Insets(15));
        leftBox.setStyle("-fx-background-color: white;");
        HBox leftHeader = new HBox(8);
        leftHeader.setAlignment(Pos.CENTER_LEFT);
        Label leftTitle = new Label("选择要同步的表");
        leftTitle.setFont(Font.font("System", FontWeight.NORMAL, 14));
        leftTitle.setTextFill(javafx.scene.paint.Color.valueOf("#1890FF"));
        Button selectAllBtn = new Button("全选");
        selectAllBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 26px; -fx-pref-width: 60px;");
        selectAllBtn.setOnAction(e -> {
            for (CheckBox cb : tableCheckBoxes) cb.setSelected(true);
        });
        Button selectNoneBtn = new Button("全不选");
        selectNoneBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 26px; -fx-pref-width: 60px;");
        selectNoneBtn.setOnAction(e -> {
            for (CheckBox cb : tableCheckBoxes) cb.setSelected(false);
        });
        leftHeader.getChildren().addAll(leftTitle, new Region(), selectAllBtn, selectNoneBtn);
        HBox.setHgrow(leftHeader.getChildren().get(1), Priority.ALWAYS);

        ScrollPane tableScroll = new ScrollPane(tableListContainer);
        tableScroll.setFitToWidth(true);
        tableScroll.setStyle("-fx-background-color: white; -fx-background: white;");
        tableScroll.getStyleClass().add("session-scroll-pane");
        VBox.setVgrow(tableScroll, Priority.ALWAYS);

        leftBox.getChildren().addAll(leftHeader, tableScroll);

        // ===== 右侧：SQL 预览 + 工具栏 =====
        VBox rightBox = new VBox(8);
        rightBox.setPadding(new Insets(15));
        rightBox.setStyle("-fx-background-color: white;");

        Label rightTitle = new Label("结构同步 SQL 预览");
        rightTitle.setFont(Font.font("System", FontWeight.NORMAL, 14));
        rightTitle.setTextFill(javafx.scene.paint.Color.valueOf("#1890FF"));

        // 工具栏：复制 + 过滤复选框
        HBox toolbar = new HBox(15);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Button copyBtn = new Button("复制 SQL");
        copyBtn.setStyle("-fx-background-color: #1890FF; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 28px; -fx-pref-width: 90px;");
        copyBtn.setOnAction(e -> {
            String sql = sqlPreviewPane.getText();
            if (sql == null || sql.isEmpty()) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(sql);
            Clipboard.getSystemClipboard().setContent(content);
        });

        Separator vSep = new Separator(Orientation.VERTICAL);
        vSep.setPrefWidth(1);
        vSep.setStyle("-fx-background-color: #E5E5E5;");

        dropFilterCb = new CheckBox("删除字段");
        dropFilterCb.setSelected(includeDrop);
        dropFilterCb.selectedProperty().addListener((obs, o, n) -> { includeDrop = n; regeneratePreviewSql(); });
        addFilterCb = new CheckBox("增加字段");
        addFilterCb.setSelected(includeAdd);
        addFilterCb.selectedProperty().addListener((obs, o, n) -> { includeAdd = n; regeneratePreviewSql(); });
        modifyFilterCb = new CheckBox("修改字段类型");
        modifyFilterCb.setSelected(includeModify);
        modifyFilterCb.selectedProperty().addListener((obs, o, n) -> { includeModify = n; regeneratePreviewSql(); });

        toolbar.getChildren().addAll(copyBtn, vSep,
                new Label("过滤:"), dropFilterCb, addFilterCb, modifyFilterCb);
        HBox.setHgrow(toolbar, Priority.ALWAYS);

        // SQL 预览区（只读，带行号 + 语法高亮）
        sqlPreviewPane = new SqlEditorPane(false);
        VBox.setVgrow(sqlPreviewPane, Priority.ALWAYS);

        stepTwoStatusLabel = new Label("加载中...");
        stepTwoStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        rightBox.getChildren().addAll(rightTitle, toolbar, sqlPreviewPane, stepTwoStatusLabel);

        split.getItems().addAll(leftBox, rightBox);
        SplitPane.setResizableWithParent(leftBox, false);

        VBox wrapper = new VBox(split);
        VBox.setVgrow(split, Priority.ALWAYS);
        wrapper.setStyle("-fx-background-color: white;");
        return wrapper;
    }

    /** 构建第二步底部按钮栏：取消 / 上一步 / 下一步 */
    private HBox buildStepTwoBottom() {
        HBox bottom = new HBox(10);
        bottom.setPadding(new Insets(12, 20, 12, 20));
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setStyle("-fx-background-color: #F5F5F5; -fx-border-color: #E5E5E5; -fx-border-width: 1 0 0 0;");

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 32px; -fx-pref-width: 100px;");
        cancelBtn.setOnAction(e -> { confirmed = false; dialogStage.close(); });

        Button prevBtn = new Button("上一步");
        prevBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 32px; -fx-pref-width: 100px;");
        prevBtn.setOnAction(e -> handlePrev());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button nextBtn = new Button("下一步");
        nextBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-height: 32px; -fx-pref-width: 100px;");
        nextBtn.setOnAction(e -> handleStepTwoNext());

        bottom.getChildren().addAll(cancelBtn, prevBtn, spacer, nextBtn);
        return bottom;
    }

    /** 异步加载源库所有表，填充左侧勾选列表（刚选中的表勾选，其他不勾选） */
    private void loadSourceTables() {
        tableListContainer.getChildren().clear();
        tableCheckBoxes.clear();
        Label loading = new Label("加载中...");
        loading.setStyle("-fx-text-fill: #999; -fx-padding: 10 0 0 0;");
        tableListContainer.getChildren().add(loading);

        new Thread(() -> {
            List<String> tables;
            try {
                tables = DatabaseService.getTables(sourceConfig, sourceDatabase, sourceSchema);
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    tableListContainer.getChildren().clear();
                    tableListContainer.getChildren().add(new Label("加载失败: " + msg));
                });
                return;
            }
            Platform.runLater(() -> {
                tableListContainer.getChildren().clear();
                for (String t : tables) {
                    CheckBox cb = new CheckBox(t);
                    cb.setSelected(sourceTables.contains(t));
                    cb.setStyle("-fx-font-size: 13px;");
                    cb.selectedProperty().addListener((obs, o, n) -> regeneratePreviewSql());
                    tableListContainer.getChildren().add(cb);
                    tableCheckBoxes.add(cb);
                }
                regeneratePreviewSql();
            });
        }, "DB-LoadSrcTables").start();
    }

    /** 重新生成右侧 SQL 预览（合并所有选中表的结构同步 SQL） */
    private void regeneratePreviewSql() {
        // 收集选中的表
        List<String> selected = new ArrayList<>();
        for (CheckBox cb : tableCheckBoxes) {
            if (cb.isSelected()) selected.add(cb.getText());
        }
        finalSelectedTables = selected;

        if (selected.isEmpty()) {
            sqlPreviewPane.setText("-- 未选择表");
            stepTwoStatusLabel.setText("0 条 SQL，0 张表");
            lastTotalSqlCount = 0;
            return;
        }

        sqlPreviewPane.setText("-- 生成中...");
        stepTwoStatusLabel.setText("生成中...");

        final boolean fAdd = includeAdd;
        final boolean fDrop = includeDrop;
        final boolean fMod = includeModify;

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            int total = 0;
            try {
                for (String table : selected) {
                    List<String> sqls = DatabaseService.generateStructureSyncSql(
                            sourceConfig, sourceDatabase, sourceSchema, table,
                            targetConfig, targetDatabase, getTargetSchema(), table,
                            fAdd, fDrop, fMod, dropIfExists);
                    sb.append("-- 表 ").append(table);
                    if (sqls.isEmpty()) {
                        sb.append("：无结构差异\n\n");
                    } else {
                        sb.append("\n");
                        for (String sql : sqls) {
                            sb.append(sql).append(";\n");
                            total++;
                        }
                        sb.append("\n");
                    }
                }
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    sqlPreviewPane.setText("-- 生成 SQL 失败: " + msg);
                    stepTwoStatusLabel.setText("生成失败");
                });
                return;
            }
            final int finalTotal = total;
            final String result = sb.toString();
            Platform.runLater(() -> {
                sqlPreviewPane.setText(result.isEmpty() ? "-- 无 SQL" : result);
                lastTotalSqlCount = finalTotal;
                stepTwoStatusLabel.setText("共 " + finalTotal + " 条 SQL，" + selected.size() + " 张表");
            });
        }, "DB-GenSyncSql").start();
    }

    /** 第二步的「下一步」：弹执行确认，确认后 confirmed=true 关闭（实际执行由调用方做） */
    private void handleStepTwoNext() {
        List<String> selected = new ArrayList<>();
        for (CheckBox cb : tableCheckBoxes) {
            if (cb.isSelected()) selected.add(cb.getText());
        }
        finalSelectedTables = selected;

        if (selected.isEmpty()) {
            showAlert("请至少选择一张表");
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "将执行 " + lastTotalSqlCount + " 条 SQL，影响 " + selected.size() + " 张表，是否继续？",
                ButtonType.YES, ButtonType.NO);
        alert.setTitle("执行确认");
        alert.setHeaderText(null);
        alert.initOwner(dialogStage);
        alert.showAndWait();
        if (alert.getResult() != ButtonType.YES) return;

        confirmed = true;
        dialogStage.close();
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg);
        alert.initOwner(dialogStage);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    public boolean isConfirmed() { return confirmed; }
    public ConnectionConfig getSourceConfig() { return sourceConfig; }
    public String getSourceDatabase() { return sourceDatabase; }
    public String getSourceSchema() { return sourceSchema; }
    /** 兼容旧 API：单表时的源表名；多表时返回第一张 */
    public String getSourceTable() { return sourceTables.isEmpty() ? null : sourceTables.get(0); }
    /** 源表名列表（单表/多表统一接口） */
    public List<String> getSourceTables() { return new ArrayList<>(sourceTables); }
    /** 目标表名列表（与 getSourceTables 一一对应） */
    public List<String> getTargetTables() { return new ArrayList<>(targetTables); }
    public ConnectionConfig getTargetConfig() { return targetConfig; }
    public String getTargetDatabase() { return targetDatabase; }
    public String getTargetSchema() {
        if (targetConfig != null && targetConfig.getType() == ConnectType.POSTGRESQL) {
            return targetDatabase;
        }
        return null;
    }
    /** 兼容旧 API：单表时的目标表名；多表时返回第一张 */
    public String getTargetTable() { return targetTable; }
    public boolean isCopyStructure() { return copyStructure; }
    public boolean isCopyData() { return copyData; }
    public boolean isDropIfExists() { return dropIfExists; }

    /** 第二步用户最终选中的表列表（结构同步的实际目标） */
    public List<String> getFinalSelectedTables() {
        return finalSelectedTables != null && !finalSelectedTables.isEmpty()
                ? new ArrayList<>(finalSelectedTables)
                : new ArrayList<>(sourceTables);
    }
    /** ALTER diff 是否包含 ADD COLUMN（新增字段） */
    public boolean isIncludeAdd() { return includeAdd; }
    /** ALTER diff 是否包含 DROP COLUMN（删除字段） */
    public boolean isIncludeDrop() { return includeDrop; }
    /** ALTER diff 是否包含 MODIFY COLUMN（修改字段类型） */
    public boolean isIncludeModify() { return includeModify; }

    /**
     * 预设目标连接/数据库（粘贴表场景：用户在目标数据库节点右键粘贴时调用）。
     * @param targetCfg 目标连接配置
     * @param targetDb 目标数据库名
     * @param targetSchema 目标 schema（可为 null）
     */
    public void presetTarget(ConnectionConfig targetCfg, String targetDb, String targetSchema) {
        if (targetCfg == null || targetDb == null) return;
        presetTargetDb = targetDb;
        // setValue 会触发 onTargetConnChange，加载数据库列表完成后会优先匹配 presetTargetDb
        if (targetConnCombo != null) {
            targetConnCombo.setValue(buildConnLabel(targetCfg));
        }
    }

    public boolean isSameConnection() {
        if (sourceConfig == null || targetConfig == null) return false;
        return Objects.equals(sourceConfig.getId(), targetConfig.getId());
    }

    public void showAndWait() {
        dialogStage.showAndWait();
    }
}
