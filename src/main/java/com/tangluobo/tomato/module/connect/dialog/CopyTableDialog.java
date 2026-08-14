package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
    private final String sourceTable;
    private final String sourceSchema;

    // 目标配置（用户选择）
    private ConnectionConfig targetConfig;
    private String targetDatabase;
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
    private TextField targetTableField;
    private CheckBox structureCheck;
    private CheckBox dataCheck;
    private CheckBox dropCheck;

    // 顶部显示引用
    private Text topTargetConnText;
    private Text topTargetDbText;
    private ImageView topTargetDbIcon;

    // 连接列表
    private final List<ConnectionConfig> allConnections;

    // 信息显示
    private VBox sourceInfoBox;
    private VBox targetInfoBox;

    /**
     * 构造复制表对话框
     * @param parent 父窗口
     * @param allConnections 所有连接配置列表
     * @param sourceConfig 源连接配置
     * @param sourceDatabase 源数据库名
     * @param sourceTable 源表名
     * @param sourceSchema 源schema（可为null，MySQL/Oracle用）
     */
    public CopyTableDialog(Stage parent, List<ConnectionConfig> allConnections,
                           ConnectionConfig sourceConfig, String sourceDatabase,
                           String sourceTable, String sourceSchema) {
        this.allConnections = allConnections == null ? new ArrayList<>() : allConnections;
        this.sourceConfig = sourceConfig;
        this.sourceDatabase = sourceDatabase;
        this.sourceTable = sourceTable;
        this.sourceSchema = sourceSchema;
        this.targetTable = sourceTable + "_copy";

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

        BorderPane root = new BorderPane();
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

        Label sourceTableLabel = new Label("表名:");
        sourceTableLabel.setStyle("-fx-font-size: 13px;");
        TextField sourceTableField = new TextField(sourceTable != null ? sourceTable : "");
        sourceTableField.setDisable(true);

        sourcePanel.getChildren().addAll(sourceTitle, sourceConnLabel, sourceConnCombo,
                sourceDbLabel, sourceDbCombo, sourceTableLabel, sourceTableField);

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

        Label targetConnLabel = new Label("连接:");
        targetConnLabel.setStyle("-fx-font-size: 13px;");
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

        Label targetTableLabel = new Label("新表名:");
        targetTableLabel.setStyle("-fx-font-size: 13px;");
        targetTableField = new TextField(this.targetTable);

        targetPanel.getChildren().addAll(targetTitle, targetTypeBox, targetConnLabel, targetConnCombo,
                targetDbLabelField, targetDbCombo, targetTableLabel, targetTableField);

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

        // --- 选项区域 ---
        TitledPane optionsPane = new TitledPane();
        optionsPane.setText("选项");
        optionsPane.setCollapsible(true);
        optionsPane.setExpanded(false);
        optionsPane.setStyle("-fx-font-size: 13px; -fx-background-color: white;");

        VBox optionsBox = new VBox(10);
        optionsBox.setPadding(new Insets(12, 15, 15, 15));
        optionsBox.setStyle("-fx-background-color: white;");

        structureCheck = new CheckBox("复制表结构");
        structureCheck.setSelected(copyStructure);
        dataCheck = new CheckBox("复制表数据");
        dataCheck.setSelected(copyData);
        dropCheck = new CheckBox("目标表存在时先删除");
        dropCheck.setSelected(dropIfExists);

        optionsBox.getChildren().addAll(structureCheck, dataCheck, dropCheck);
        optionsPane.setContent(optionsBox);
        centerBox.getChildren().add(optionsPane);

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
        optionsBtn.setOnAction(e -> optionsPane.setExpanded(!optionsPane.isExpanded()));

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

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        dialogStage.setScene(scene);

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
                    if (sourceDatabase != null && dbs.contains(sourceDatabase)) {
                        targetDbCombo.setValue(sourceDatabase);
                        targetDatabase = sourceDatabase;
                        if (topTargetDbText != null) topTargetDbText.setText(sourceDatabase);
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
        addInfoRow(infoBox, "连接类型:", cfg.getType() != null ? cfg.getType().getDisplayName() : "");
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

    private void handleNext() {
        targetConfig = findConfigByLabel(targetConnCombo.getValue());
        targetDatabase = targetDbCombo.getValue();
        targetTable = targetTableField.getText().trim();
        copyStructure = structureCheck.isSelected();
        copyData = dataCheck.isSelected();
        dropIfExists = dropCheck.isSelected();

        if (targetConfig == null) {
            showAlert("请选择目标连接");
            return;
        }
        if (targetDatabase == null || targetDatabase.isEmpty()) {
            showAlert("请选择目标数据库");
            return;
        }
        if (targetTable == null || targetTable.isEmpty()) {
            showAlert("请输入目标表名");
            return;
        }
        if (!copyStructure && !copyData) {
            showAlert("请至少选择复制结构或复制数据");
            return;
        }

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
    public String getSourceTable() { return sourceTable; }
    public ConnectionConfig getTargetConfig() { return targetConfig; }
    public String getTargetDatabase() { return targetDatabase; }
    public String getTargetSchema() {
        if (targetConfig != null && targetConfig.getType() == ConnectType.POSTGRESQL) {
            return targetDatabase;
        }
        return null;
    }
    public String getTargetTable() { return targetTable; }
    public boolean isCopyStructure() { return copyStructure; }
    public boolean isCopyData() { return copyData; }
    public boolean isDropIfExists() { return dropIfExists; }

    public boolean isSameConnection() {
        if (sourceConfig == null || targetConfig == null) return false;
        return Objects.equals(sourceConfig.getId(), targetConfig.getId());
    }

    public void showAndWait() {
        dialogStage.showAndWait();
    }
}
