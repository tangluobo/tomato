package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;

/**
 * 新建数据库对话框：常规标签（名称、字符集、排序规则）+ SQL预览标签
 */
public class CreateDatabaseDialog {

    private Stage dialogStage;
    private boolean confirmed = false;

    private TextField nameField;
    private ComboBox<String> charsetCombo;
    private ComboBox<String> collationCombo;
    private TextArea sqlPreviewArea;

    private final ConnectionConfig config;

    // MySQL常见字符集及其对应排序规则
    private static final Map<String, String[]> CHARSET_COLLATIONS = new LinkedHashMap<>();
    static {
        CHARSET_COLLATIONS.put("utf8mb4", new String[]{"utf8mb4_general_ci", "utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci", "utf8mb4_bin", "utf8mb4_unicode_520_ci"});
        CHARSET_COLLATIONS.put("utf8mb3", new String[]{"utf8mb3_general_ci", "utf8mb3_unicode_ci", "utf8mb3_bin"});
        CHARSET_COLLATIONS.put("utf8", new String[]{"utf8_general_ci", "utf8_unicode_ci", "utf8_bin"});
        CHARSET_COLLATIONS.put("latin1", new String[]{"latin1_swedish_ci", "latin1_general_ci", "latin1_general_cs", "latin1_bin"});
        CHARSET_COLLATIONS.put("ascii", new String[]{"ascii_general_ci", "ascii_bin"});
        CHARSET_COLLATIONS.put("gbk", new String[]{"gbk_chinese_ci", "gbk_bin"});
        CHARSET_COLLATIONS.put("gb2312", new String[]{"gb2312_chinese_ci", "gb2312_bin"});
        CHARSET_COLLATIONS.put("gb18030", new String[]{"gb18030_chinese_ci", "gb18030_bin", "gb18030_unicode_520_ci"});
        CHARSET_COLLATIONS.put("big5", new String[]{"big5_chinese_ci", "big5_bin"});
        CHARSET_COLLATIONS.put("euckr", new String[]{"euckr_korean_ci", "euckr_bin"});
        CHARSET_COLLATIONS.put("sjis", new String[]{"sjis_japanese_ci", "sjis_bin"});
        CHARSET_COLLATIONS.put("binary", new String[]{"binary"});
    }

    public CreateDatabaseDialog(Stage parent, ConnectionConfig config) {
        this.config = config;
        initUI(parent);
    }

    private void initUI(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("新建数据库");
        dialogStage.setResizable(false);

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.setMinWidth(450);

        // 标签页
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ---- 常规标签 ----
        Tab generalTab = new Tab("常规");
        VBox generalBox = new VBox(12);
        generalBox.setPadding(new Insets(15));

        // 数据库名称
        Label nameLabel = new Label("数据库名称：");
        nameField = new TextField();
        nameField.setPromptText("请输入数据库名称");
        nameField.textProperty().addListener((obs, oldVal, newVal) -> updateSqlPreview());

        // 字符集
        Label charsetLabel = new Label("字符集：");
        charsetCombo = new ComboBox<>();
        charsetCombo.getItems().add("(默认)");
        charsetCombo.getItems().addAll(CHARSET_COLLATIONS.keySet());
        charsetCombo.setValue("(默认)");
        charsetCombo.setPrefWidth(Double.MAX_VALUE);

        // 排序规则
        Label collationLabel = new Label("排序规则：");
        collationCombo = new ComboBox<>();
        collationCombo.getItems().add("(默认)");
        collationCombo.setValue("(默认)");
        collationCombo.setPrefWidth(Double.MAX_VALUE);

        // 字符集变化时更新排序规则列表
        charsetCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            collationCombo.getItems().clear();
            collationCombo.getItems().add("(默认)");
            if (newVal != null && CHARSET_COLLATIONS.containsKey(newVal)) {
                collationCombo.getItems().addAll(CHARSET_COLLATIONS.get(newVal));
            }
            collationCombo.setValue("(默认)");
            updateSqlPreview();
        });

        collationCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateSqlPreview());

        // 异步加载服务器端字符集和排序规则
        loadServerCharsets();

        generalBox.getChildren().addAll(nameLabel, nameField, charsetLabel, charsetCombo, collationLabel, collationCombo);
        generalTab.setContent(generalBox);

        // ---- SQL预览标签 ----
        Tab sqlTab = new Tab("SQL预览");
        VBox sqlBox = new VBox(10);
        sqlBox.setPadding(new Insets(15));

        sqlPreviewArea = new TextArea();
        sqlPreviewArea.setEditable(false);
        sqlPreviewArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        sqlPreviewArea.setPrefRowCount(6);

        sqlBox.getChildren().add(sqlPreviewArea);
        sqlTab.setContent(sqlBox);

        tabPane.getTabs().addAll(generalTab, sqlTab);

        // ---- 按钮 ----
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(5, 0, 0, 0));

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        cancelBtn.setOnAction(e -> dialogStage.close());

        Button okBtn = new Button("确定");
        okBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        okBtn.setOnAction(e -> {
            if (nameField.getText().trim().isEmpty()) {
                return;
            }
            confirmed = true;
            dialogStage.close();
        });

        buttons.getChildren().addAll(cancelBtn, okBtn);

        root.getChildren().addAll(tabPane, buttons);

        Scene scene = new Scene(root);
        dialogStage.setScene(scene);

        // 初始SQL预览
        updateSqlPreview();
    }

    /**
     * 异步从服务器加载可用的字符集和排序规则
     */
    private void loadServerCharsets() {
        new Thread(() -> {
            try {
                Map<String, List<String>> serverCharsets = DatabaseService.getCharsets(config);
                Platform.runLater(() -> {
                    charsetCombo.getItems().clear();
                    charsetCombo.getItems().add("(默认)");
                    collationCombo.getItems().clear();
                    collationCombo.getItems().add("(默认)");

                    for (Map.Entry<String, List<String>> entry : serverCharsets.entrySet()) {
                        charsetCombo.getItems().add(entry.getKey());
                        CHARSET_COLLATIONS.put(entry.getKey(), entry.getValue().toArray(new String[0]));
                    }
                    charsetCombo.setValue("(默认)");
                    collationCombo.setValue("(默认)");
                });
            } catch (Exception e) {
                // 加载失败使用本地默认列表，不做额外处理
            }
        }, "DB-LoadCharsets").start();
    }

    private void updateSqlPreview() {
        String sql = generateSql();
        if (sqlPreviewArea != null) {
            sqlPreviewArea.setText(sql);
        }
    }

    private String generateSql() {
        String name = nameField != null ? nameField.getText().trim() : "";
        if (name.isEmpty()) {
            return "-- 请输入数据库名称";
        }

        String charset = charsetCombo != null ? charsetCombo.getValue() : "(默认)";
        String collation = collationCombo != null ? collationCombo.getValue() : "(默认)";

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE DATABASE ");

        if (config.getType() == ConnectType.MYSQL) {
            sql.append("`").append(name).append("`");
            if (!"(默认)".equals(charset)) {
                sql.append("\n  CHARACTER SET ").append(charset);
            }
            if (!"(默认)".equals(collation)) {
                sql.append("\n  COLLATE ").append(collation);
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            sql.append("\"").append(name).append("\"");
            if (!"(默认)".equals(charset)) {
                sql.append("\n  ENCODING '").append(charset).append("'");
            }
            if (!"(默认)".equals(collation)) {
                sql.append("\n  LC_COLLATE '").append(collation).append("'");
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            sql.append("\"").append(name).append("\"");
        }

        sql.append(";");
        return sql.toString();
    }

    /**
     * 获取生成的SQL语句
     */
    public String getSql() {
        return generateSql();
    }

    /**
     * 获取数据库名称
     */
    public String getDatabaseName() {
        return confirmed && nameField != null ? nameField.getText().trim() : null;
    }

    /**
     * 获取选中的字符集
     */
    public String getCharset() {
        String val = charsetCombo != null ? charsetCombo.getValue() : null;
        return "(默认)".equals(val) ? null : val;
    }

    /**
     * 获取选中的排序规则
     */
    public String getCollation() {
        String val = collationCombo != null ? collationCombo.getValue() : null;
        return "(默认)".equals(val) ? null : val;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void showAndWait() {
        dialogStage.showAndWait();
    }
}
