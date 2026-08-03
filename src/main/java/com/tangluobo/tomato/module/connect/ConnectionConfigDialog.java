package com.tangluobo.tomato.module.connect;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ConnectionConfigDialog {
    private Stage dialogStage;
    private boolean confirmed = false;
    private ConnectionConfig config;
    private ConnectionConfig existingConfig;
    private VBox root;

    // 类型选择页
    private VBox typeSelectionPage;
    private ConnectType selectedType;

    // 配置页
    private VBox configPage;
    private Label configTitle;
    private ImageView configTitleGraphic;

    // ===== 数据库类型专用 TabPane =====
    private TabPane dbTabPane;
    private Tab dbGeneralTab;
    private Tab sshTunnelTab;

    // 数据库常规字段
    private TextField nameField;
    private TextField hostField;
    private TextField portField;
    private TextField usernameField;
    private PasswordField passwordField;
    private CheckBox savePasswordCheckBox;
    private TextField descriptionField;

    // SSH通道字段
    private CheckBox useSshTunnelCheckBox;
    private VBox sshTunnelContent;
    private TextField sshTunnelHostField;
    private TextField sshTunnelPortField;
    private TextField sshTunnelUsernameField;
    private CheckBox sshTunnelUsePasswordCheckBox;
    private PasswordField sshTunnelPasswordField;
    private CheckBox sshTunnelSavePasswordCheckBox;
    private CheckBox sshTunnelUseKeyCheckBox;
    private VBox sshTunnelKeyListContainer;
    private List<KeyEntry> sshTunnelKeyEntries = new ArrayList<>();

    // ===== SSH/SFTP类型专用 =====
    private VBox sshConfigContent;
    private TextField sshNameField;
    private TextField sshHostField;
    private TextField sshPortField;
    private TextField sshUsernameField;
    private CheckBox sshUsePasswordCheckBox;
    private PasswordField sshPasswordField;
    private CheckBox sshSavePasswordCheckBox;
    private CheckBox sshUseKeyCheckBox;
    private VBox sshKeyListContainer;
    private List<KeyEntry> sshKeyEntries = new ArrayList<>();
    private TextField sshDescriptionField;

    // ===== 其他类型(RDP/FTP/Oracle)专用 =====
    private VBox simpleConfigContent;
    private TextField simpleNameField;
    private TextField simpleHostField;
    private TextField simplePortField;
    private TextField simpleUsernameField;
    private PasswordField simplePasswordField;
    private CheckBox simpleSavePasswordCheckBox;
    private TextField simpleDescriptionField;

    // ===== RDP专属字段 =====
    private TextField rdpDomainField;
    private ComboBox<String> rdpResolutionCombo;
    private ComboBox<String> rdpColorDepthCombo;
    private CheckBox rdpUseSslCheck;

    // ===== 本地终端专属字段 =====
    private VBox localTerminalConfigContent;
    private TextField localTerminalNameField;
    private ComboBox<String> localTerminalTypeCombo;
    private TextField localTerminalDescriptionField;

    // ===== S3/阿里云OSS专属字段 =====
    private VBox s3ConfigContent;
    private TextField s3NameField;
    private TextField s3EndpointField;
    private TextField s3RegionField;
    private TextField s3AccessKeyField;
    private PasswordField s3SecretKeyField;
    private CheckBox s3SaveSecretKeyCheckBox;
    private CheckBox s3PathStyleAccessCheckBox;
    private TextField s3DescriptionField;

    // ===== Redis专属字段 =====
    private VBox redisConfigContent;
    private TextField redisNameField;
    private TextField redisHostField;
    private TextField redisPortField;
    private TextField redisUsernameField;
    private PasswordField redisPasswordField;
    private CheckBox redisSavePasswordCheckBox;
    private CheckBox redisClusterCheckBox;
    private VBox redisClusterContent;
    private TextField redisClusterNodesField;
    private TextField redisDatabaseField;
    private TextField redisDescriptionField;

    // ===== RocketMQ专属字段 =====
    private VBox rocketmqConfigContent;
    private TextField rocketmqNameField;
    private TextField rocketmqHostField;
    private TextField rocketmqPortField;
    private TextField rocketmqDescriptionField;

    // ===== 阿里云专属字段 =====
    private VBox aliyunConfigContent;
    private TextField aliyunNameField;
    private TextField aliyunAccessKeyField;
    private PasswordField aliyunSecretKeyField;
    private CheckBox aliyunSaveSecretKeyCheckBox;
    private TextField aliyunDescriptionField;

    /**
     * 密钥条目：每行一个密钥文件（复选框 + 路径 + 浏览 + 删除）
     */
    private static class KeyEntry {
        CheckBox checkBox;
        TextField pathField;
        Button browseBtn;
        Button removeBtn;
        HBox row;

        KeyEntry(String path, boolean selected) {
            checkBox = new CheckBox();
            checkBox.setSelected(selected);
            checkBox.setTooltip(new Tooltip("启用此密钥"));

            pathField = new TextField(path);
            pathField.setPromptText("选择私钥文件（如 ~/.ssh/id_rsa）");
            pathField.setPrefWidth(180);

            browseBtn = new Button("浏览");
            browseBtn.setStyle("-fx-font-size: 10px; -fx-padding: 2 6;");
            browseBtn.setTooltip(new Tooltip("选择密钥文件"));

            removeBtn = new Button("✕");
            removeBtn.setStyle("-fx-font-size: 10px; -fx-padding: 2 6; -fx-text-fill: #cc0000;");
            removeBtn.setTooltip(new Tooltip("移除此密钥"));

            row = new HBox(4);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(checkBox, pathField, browseBtn, removeBtn);
        }
    }

    public ConnectionConfigDialog(Stage parent) {
        this(parent, null);
    }

    public ConnectionConfigDialog(Stage parent, ConnectionConfig existingConfig) {
        this.existingConfig = existingConfig;
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle(existingConfig == null ? "新建连接" : "编辑连接");
        dialogStage.setResizable(true);

        root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setMinWidth(520);
        root.setMinHeight(620);

        // ===== 类型选择页 =====
        typeSelectionPage = new VBox(15);

        Label title = new Label("选择连接类型");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        FlowPane tilePane = new FlowPane();
        tilePane.setHgap(10);
        tilePane.setVgap(10);
        tilePane.setPadding(new Insets(5, 0, 5, 0));
        tilePane.setAlignment(Pos.CENTER);

        for (ConnectType type : ConnectType.values()) {
            VBox tile = new VBox(8);
            tile.setAlignment(Pos.CENTER);
            tile.setPadding(new Insets(14, 18, 14, 18));
            tile.setPrefWidth(120);
            tile.setPrefHeight(90);
            tile.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8; -fx-border-color: #e0e0e0; -fx-border-radius: 8; -fx-cursor: hand;");

            ImageView icon = new ImageView();
            icon.setFitWidth(32);
            icon.setFitHeight(32);
            try {
                Image img = new Image(getClass().getResourceAsStream(type.getIconPath()));
                icon.setImage(img);
            } catch (Exception ignored) {}

            Label nameLabel = new Label(type.getDisplayName());
            nameLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #333; -fx-font-weight: bold;");

            tile.getChildren().addAll(icon, nameLabel);

            tile.setOnMouseEntered(e ->
                tile.setStyle("-fx-background-color: #e8f5e9; -fx-background-radius: 8; -fx-border-color: #07c160; -fx-border-radius: 8; -fx-cursor: hand; -fx-border-width: 2;")
            );
            tile.setOnMouseExited(e ->
                tile.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8; -fx-border-color: #e0e0e0; -fx-border-radius: 8; -fx-cursor: hand;")
            );
            tile.setOnMousePressed(e ->
                tile.setStyle("-fx-background-color: #c8e6c9; -fx-background-radius: 8; -fx-border-color: #07c160; -fx-border-radius: 8; -fx-cursor: hand; -fx-border-width: 2;")
            );
            tile.setOnMouseReleased(e ->
                tile.setStyle("-fx-background-color: #e8f5e9; -fx-background-radius: 8; -fx-border-color: #07c160; -fx-border-radius: 8; -fx-cursor: hand; -fx-border-width: 2;")
            );

            tile.setOnMouseClicked(e -> {
                selectedType = type;
                showConfigPage();
            });

            tilePane.getChildren().add(tile);
        }

        HBox typeButtons = new HBox(10);
        typeButtons.setAlignment(Pos.CENTER_RIGHT);
        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        cancelBtn.setOnAction(e -> dialogStage.close());
        typeButtons.getChildren().add(cancelBtn);

        typeSelectionPage.getChildren().addAll(title, tilePane, typeButtons);

        // ===== 配置页 =====
        configPage = new VBox(15);
        configPage.setVisible(false);
        configPage.setManaged(false);

        configTitle = new Label("新建连接");
        configTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        configTitleGraphic = new ImageView();
        configTitleGraphic.setFitWidth(20);
        configTitleGraphic.setFitHeight(20);
        configTitle.setGraphic(configTitleGraphic);

        // ---- 构建数据库类型的 TabPane ----
        buildDbTabPane();

        // ---- 构建SSH/SFTP类型的配置 ----
        buildSshConfigContent();

        // ---- 构建其他类型的简单配置 ----
        buildSimpleConfigContent();

        // ---- 构建本地终端类型的配置 ----
        buildLocalTerminalConfigContent();

        // ---- 构建S3/阿里云OSS类型的配置 ----
        buildS3ConfigContent();

        // ---- 构建Redis类型的配置 ----
        buildRedisConfigContent();

        // ---- 构建RocketMQ类型的配置 ----
        buildRocketmqConfigContent();

        // ---- 构建阿里云类型的配置 ----
        buildAliyunConfigContent();

        // 按钮区域
        HBox configButtons = new HBox(10);
        configButtons.setAlignment(Pos.CENTER_RIGHT);

        Button backBtn = new Button("返回");
        backBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        backBtn.setOnAction(e -> showTypeSelectionPage());

        Button cancelBtn2 = new Button("取消");
        cancelBtn2.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        cancelBtn2.setOnAction(e -> dialogStage.close());

        Button okBtn = new Button("确定");
        okBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        okBtn.setOnAction(e -> handleConfirm());

        configButtons.getChildren().addAll(backBtn, cancelBtn2, okBtn);

        configPage.getChildren().addAll(configTitle, dbTabPane, sshConfigContent, simpleConfigContent, localTerminalConfigContent, s3ConfigContent, redisConfigContent, rocketmqConfigContent, aliyunConfigContent, configButtons);

        // 编辑已有连接时直接显示配置页
        if (existingConfig != null) {
            selectedType = existingConfig.getType();
            root.getChildren().add(configPage);
            showConfigPage();
            fillExistingConfig();
        } else {
            root.getChildren().addAll(typeSelectionPage, configPage);
        }

        Scene scene = new Scene(root);
        dialogStage.setScene(scene);
    }

    /**
     * 构建数据库类型的 TabPane（常规 + SSH通道）
     */
    private void buildDbTabPane() {
        dbTabPane = new TabPane();
        dbTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        dbTabPane.setVisible(false);
        dbTabPane.setManaged(false);
        // 使用与终端标签页相同的样式
        dbTabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        // ===== 常规 Tab =====
        VBox generalContent = new VBox(10);
        generalContent.setPadding(new Insets(10, 0, 0, 20));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        nameField = new TextField();
        nameField.setPromptText("连接名称");
        nameField.setPrefWidth(280);
        grid.add(nameField, 1, row++);

        grid.add(new Label("主机："), 0, row);
        hostField = new TextField();
        hostField.setPromptText("数据库主机地址");
        hostField.setPrefWidth(280);
        grid.add(hostField, 1, row++);

        grid.add(new Label("端口："), 0, row);
        portField = new TextField();
        portField.setPrefWidth(100);
        grid.add(portField, 1, row++);

        grid.add(new Label("用户名："), 0, row);
        usernameField = new TextField();
        usernameField.setPromptText("数据库用户名");
        usernameField.setPrefWidth(280);
        grid.add(usernameField, 1, row++);

        // 密码
        grid.add(new Label("密码："), 0, row);
        VBox pwdBox = new VBox(4);
        passwordField = new PasswordField();
        passwordField.setPromptText("数据库密码");
        passwordField.setPrefWidth(260);
        savePasswordCheckBox = new CheckBox("保存密码");
        savePasswordCheckBox.setSelected(true);
        savePasswordCheckBox.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        pwdBox.getChildren().addAll(passwordField, savePasswordCheckBox);
        grid.add(pwdBox, 1, row++);

        grid.add(new Label("备注："), 0, row);
        descriptionField = new TextField();
        descriptionField.setPromptText("备注信息");
        descriptionField.setPrefWidth(280);
        grid.add(descriptionField, 1, row);

        generalContent.getChildren().add(grid);

        dbGeneralTab = new Tab("常规");
        dbGeneralTab.setContent(generalContent);

        // ===== SSH通道 Tab =====
        VBox tunnelContent = new VBox(10);
        tunnelContent.setPadding(new Insets(10, 0, 0, 20));

        useSshTunnelCheckBox = new CheckBox("启用SSH通道");
        useSshTunnelCheckBox.setStyle("-fx-font-weight: bold;");

        sshTunnelContent = new VBox(10);
        sshTunnelContent.setPadding(new Insets(5, 0, 0, 0));
        sshTunnelContent.setDisable(true);

        useSshTunnelCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            sshTunnelContent.setDisable(!newVal);
        });

        GridPane tunnelGrid = new GridPane();
        tunnelGrid.setHgap(10);
        tunnelGrid.setVgap(10);

        int tRow = 0;

        tunnelGrid.add(new Label("SSH主机："), 0, tRow);
        sshTunnelHostField = new TextField();
        sshTunnelHostField.setPromptText("SSH跳板机地址");
        sshTunnelHostField.setPrefWidth(280);
        tunnelGrid.add(sshTunnelHostField, 1, tRow++);

        tunnelGrid.add(new Label("SSH端口："), 0, tRow);
        sshTunnelPortField = new TextField();
        sshTunnelPortField.setPromptText("22");
        sshTunnelPortField.setPrefWidth(100);
        tunnelGrid.add(sshTunnelPortField, 1, tRow++);

        tunnelGrid.add(new Label("SSH用户名："), 0, tRow);
        sshTunnelUsernameField = new TextField();
        sshTunnelUsernameField.setPromptText("SSH用户名");
        sshTunnelUsernameField.setPrefWidth(280);
        tunnelGrid.add(sshTunnelUsernameField, 1, tRow++);

        // SSH认证区域
        VBox sshTunnelAuthSection = new VBox(8);
        sshTunnelAuthSection.setPadding(new Insets(8, 0, 8, 0));
        sshTunnelAuthSection.setStyle("-fx-border-color: #e0e0e0; -fx-border-radius: 6; -fx-padding: 10;");

        // SSH密码认证
        VBox sshTunnelPwdSection = new VBox(4);
        sshTunnelUsePasswordCheckBox = new CheckBox("密码认证");
        sshTunnelUsePasswordCheckBox.setSelected(true);
        sshTunnelUsePasswordCheckBox.setStyle("-fx-font-weight: bold;");

        VBox sshTunnelPwdFields = new VBox(4);
        sshTunnelPwdFields.setPadding(new Insets(2, 0, 0, 24));
        sshTunnelPasswordField = new PasswordField();
        sshTunnelPasswordField.setPromptText("SSH密码");
        sshTunnelPasswordField.setPrefWidth(260);
        sshTunnelSavePasswordCheckBox = new CheckBox("保存密码");
        sshTunnelSavePasswordCheckBox.setSelected(true);
        sshTunnelSavePasswordCheckBox.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        sshTunnelPwdFields.getChildren().addAll(sshTunnelPasswordField, sshTunnelSavePasswordCheckBox);
        sshTunnelPwdSection.getChildren().addAll(sshTunnelUsePasswordCheckBox, sshTunnelPwdFields);

        Separator tunnelSep = new Separator();
        tunnelSep.setPadding(new Insets(4, 0, 4, 0));

        // SSH密钥认证
        VBox sshTunnelKeySection = new VBox(4);
        sshTunnelUseKeyCheckBox = new CheckBox("密钥认证");
        sshTunnelUseKeyCheckBox.setStyle("-fx-font-weight: bold;");

        VBox sshTunnelKeyFields = new VBox(4);
        sshTunnelKeyFields.setPadding(new Insets(2, 0, 0, 24));

        sshTunnelKeyListContainer = new VBox(4);

        Button addSshTunnelKeyBtn = new Button("+ 添加密钥");
        addSshTunnelKeyBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-background-radius: 4;");
        addSshTunnelKeyBtn.setOnAction(e -> addSshTunnelKeyEntry("", true));

        Label tunnelKeyHint = new Label("勾选启用密钥，密钥有口令时填写上方密码作为 Passphrase");
        tunnelKeyHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        tunnelKeyHint.setWrapText(true);

        sshTunnelKeyFields.getChildren().addAll(sshTunnelKeyListContainer, addSshTunnelKeyBtn, tunnelKeyHint);
        sshTunnelKeySection.getChildren().addAll(sshTunnelUseKeyCheckBox, sshTunnelKeyFields);

        sshTunnelAuthSection.getChildren().addAll(sshTunnelPwdSection, tunnelSep, sshTunnelKeySection);

        tunnelGrid.add(new Label("SSH认证："), 0, tRow);
        tunnelGrid.add(sshTunnelAuthSection, 1, tRow++);

        // SSH通道说明
        Label tunnelHint = new Label("启用后，将通过SSH跳板机建立安全通道连接数据库");
        tunnelHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        tunnelHint.setWrapText(true);

        sshTunnelContent.getChildren().addAll(tunnelGrid, tunnelHint);

        tunnelContent.getChildren().addAll(useSshTunnelCheckBox, sshTunnelContent);

        sshTunnelTab = new Tab("SSH通道");
        sshTunnelTab.setContent(tunnelContent);

        // SSH认证方式切换逻辑
        sshTunnelUsePasswordCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            sshTunnelPasswordField.setDisable(!newVal);
            sshTunnelSavePasswordCheckBox.setDisable(!newVal);
            if (!newVal && !sshTunnelUseKeyCheckBox.isSelected()) {
                sshTunnelUseKeyCheckBox.setSelected(true);
            }
        });

        sshTunnelUseKeyCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            setSshTunnelKeyEntriesEnabled(newVal);
            if (!newVal && !sshTunnelUsePasswordCheckBox.isSelected()) {
                sshTunnelUsePasswordCheckBox.setSelected(true);
            }
        });

        dbTabPane.getTabs().addAll(dbGeneralTab, sshTunnelTab);
    }

    /**
     * 构建SSH/SFTP类型的配置内容
     */
    private void buildSshConfigContent() {
        sshConfigContent = new VBox(15);
        sshConfigContent.setVisible(false);
        sshConfigContent.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        sshNameField = new TextField();
        sshNameField.setPromptText("连接名称");
        sshNameField.setPrefWidth(280);
        grid.add(sshNameField, 1, row++);

        grid.add(new Label("主机："), 0, row);
        sshHostField = new TextField();
        sshHostField.setPromptText("主机地址");
        sshHostField.setPrefWidth(280);
        grid.add(sshHostField, 1, row++);

        grid.add(new Label("端口："), 0, row);
        sshPortField = new TextField();
        sshPortField.setPrefWidth(100);
        grid.add(sshPortField, 1, row++);

        grid.add(new Label("用户名："), 0, row);
        sshUsernameField = new TextField();
        sshUsernameField.setPromptText("用户名");
        sshUsernameField.setPrefWidth(280);
        grid.add(sshUsernameField, 1, row++);

        // 认证区域
        VBox authSection = new VBox(8);
        authSection.setPadding(new Insets(8, 0, 8, 0));
        authSection.setStyle("-fx-border-color: #e0e0e0; -fx-border-radius: 6; -fx-padding: 10;");

        // 密码认证
        VBox pwdSection = new VBox(4);
        sshUsePasswordCheckBox = new CheckBox("密码认证");
        sshUsePasswordCheckBox.setSelected(true);
        sshUsePasswordCheckBox.setStyle("-fx-font-weight: bold;");

        VBox pwdFields = new VBox(4);
        pwdFields.setPadding(new Insets(2, 0, 0, 24));
        sshPasswordField = new PasswordField();
        sshPasswordField.setPromptText("密码");
        sshPasswordField.setPrefWidth(260);
        sshSavePasswordCheckBox = new CheckBox("保存密码");
        sshSavePasswordCheckBox.setSelected(true);
        sshSavePasswordCheckBox.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        pwdFields.getChildren().addAll(sshPasswordField, sshSavePasswordCheckBox);
        pwdSection.getChildren().addAll(sshUsePasswordCheckBox, pwdFields);

        Separator sep = new Separator();
        sep.setPadding(new Insets(4, 0, 4, 0));

        // 密钥认证
        VBox keySection = new VBox(4);
        sshUseKeyCheckBox = new CheckBox("密钥认证");
        sshUseKeyCheckBox.setStyle("-fx-font-weight: bold;");

        VBox keyFields = new VBox(4);
        keyFields.setPadding(new Insets(2, 0, 0, 24));

        sshKeyListContainer = new VBox(4);

        Button addKeyBtn = new Button("+ 添加密钥");
        addKeyBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-background-radius: 4;");
        addKeyBtn.setOnAction(e -> addSshKeyEntry("", true));

        Label keyHint = new Label("勾选启用密钥，密钥有口令时填写上方密码作为 Passphrase");
        keyHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        keyHint.setWrapText(true);

        keyFields.getChildren().addAll(sshKeyListContainer, addKeyBtn, keyHint);
        keySection.getChildren().addAll(sshUseKeyCheckBox, keyFields);

        authSection.getChildren().addAll(pwdSection, sep, keySection);

        grid.add(new Label("认证："), 0, row);
        grid.add(authSection, 1, row++);

        grid.add(new Label("备注："), 0, row);
        sshDescriptionField = new TextField();
        sshDescriptionField.setPromptText("备注信息");
        sshDescriptionField.setPrefWidth(280);
        grid.add(sshDescriptionField, 1, row);

        sshConfigContent.getChildren().add(grid);

        // 认证方式切换逻辑
        sshUsePasswordCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            sshPasswordField.setDisable(!newVal);
            sshSavePasswordCheckBox.setDisable(!newVal);
            if (!newVal && !sshUseKeyCheckBox.isSelected()) {
                sshUseKeyCheckBox.setSelected(true);
            }
        });

        sshUseKeyCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            setSshKeyEntriesEnabled(newVal);
            if (!newVal && !sshUsePasswordCheckBox.isSelected()) {
                sshUsePasswordCheckBox.setSelected(true);
            }
        });
    }

    /**
     * 构建其他类型(RDP/FTP/Oracle)的简单配置内容
     */
    private void buildSimpleConfigContent() {
        simpleConfigContent = new VBox(15);
        simpleConfigContent.setVisible(false);
        simpleConfigContent.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        simpleNameField = new TextField();
        simpleNameField.setPromptText("连接名称");
        simpleNameField.setPrefWidth(280);
        grid.add(simpleNameField, 1, row++);

        grid.add(new Label("主机："), 0, row);
        simpleHostField = new TextField();
        simpleHostField.setPromptText("主机地址");
        simpleHostField.setPrefWidth(280);
        grid.add(simpleHostField, 1, row++);

        grid.add(new Label("端口："), 0, row);
        simplePortField = new TextField();
        simplePortField.setPrefWidth(100);
        grid.add(simplePortField, 1, row++);

        grid.add(new Label("用户名："), 0, row);
        simpleUsernameField = new TextField();
        simpleUsernameField.setPromptText("用户名");
        simpleUsernameField.setPrefWidth(280);
        grid.add(simpleUsernameField, 1, row++);

        grid.add(new Label("密码："), 0, row);
        VBox simplePwdBox = new VBox(4);
        simplePasswordField = new PasswordField();
        simplePasswordField.setPromptText("密码");
        simplePasswordField.setPrefWidth(260);
        simpleSavePasswordCheckBox = new CheckBox("保存密码");
        simpleSavePasswordCheckBox.setSelected(true);
        simpleSavePasswordCheckBox.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        simplePwdBox.getChildren().addAll(simplePasswordField, simpleSavePasswordCheckBox);
        grid.add(simplePwdBox, 1, row++);

        // RDP专属字段：域
        grid.add(new Label("域："), 0, row);
        rdpDomainField = new TextField();
        rdpDomainField.setPromptText("Windows域（可选）");
        rdpDomainField.setPrefWidth(280);
        grid.add(rdpDomainField, 1, row++);

        // RDP专属字段：分辨率
        grid.add(new Label("分辨率："), 0, row);
        rdpResolutionCombo = new ComboBox<>();
        rdpResolutionCombo.getItems().addAll("800x600", "1024x768", "1280x720", "1280x1024", "1920x1080");
        rdpResolutionCombo.setEditable(true);
        rdpResolutionCombo.setPromptText("宽x高");
        rdpResolutionCombo.setPrefWidth(150);
        rdpResolutionCombo.setValue("1024x768");
        grid.add(rdpResolutionCombo, 1, row++);

        // RDP专属字段：色深
        grid.add(new Label("色深："), 0, row);
        rdpColorDepthCombo = new ComboBox<>();
        rdpColorDepthCombo.getItems().addAll("16位", "24位");
        rdpColorDepthCombo.setPrefWidth(100);
        rdpColorDepthCombo.setValue("24位");
        grid.add(rdpColorDepthCombo, 1, row++);

        // RDP专属字段：SSL/TLS加密
        rdpUseSslCheck = new CheckBox("使用SSL/TLS加密");
        rdpUseSslCheck.setSelected(true);
        rdpUseSslCheck.setTooltip(new Tooltip("关闭后使用Standard RDP Security（无TLS），适用于未启用TLS的RDP服务器"));
        grid.add(rdpUseSslCheck, 1, row++);

        grid.add(new Label("备注："), 0, row);
        simpleDescriptionField = new TextField();
        simpleDescriptionField.setPromptText("备注信息");
        simpleDescriptionField.setPrefWidth(280);
        grid.add(simpleDescriptionField, 1, row);

        simpleConfigContent.getChildren().add(grid);
    }

    /**
     * 构建本地终端类型的配置内容
     */
    private void buildLocalTerminalConfigContent() {
        localTerminalConfigContent = new VBox(15);
        localTerminalConfigContent.setVisible(false);
        localTerminalConfigContent.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        localTerminalNameField = new TextField();
        localTerminalNameField.setPromptText("终端名称");
        localTerminalNameField.setPrefWidth(280);
        grid.add(localTerminalNameField, 1, row++);

        grid.add(new Label("终端类型："), 0, row);
        localTerminalTypeCombo = new ComboBox<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            localTerminalTypeCombo.getItems().addAll("cmd", "powershell");
            localTerminalTypeCombo.setValue("cmd");
        } else {
            localTerminalTypeCombo.getItems().addAll("系统默认终端");
            localTerminalTypeCombo.setValue("系统默认终端");
            localTerminalTypeCombo.setDisable(true);
        }
        localTerminalTypeCombo.setPrefWidth(150);
        grid.add(localTerminalTypeCombo, 1, row++);

        grid.add(new Label("备注："), 0, row);
        localTerminalDescriptionField = new TextField();
        localTerminalDescriptionField.setPromptText("备注信息");
        localTerminalDescriptionField.setPrefWidth(280);
        grid.add(localTerminalDescriptionField, 1, row);

        // 提示信息
        Label hint;
        if (os.contains("win")) {
            hint = new Label("双击时将打开本地命令行窗口，可选择CMD或PowerShell");
        } else if (os.contains("mac")) {
            hint = new Label("双击时将打开Terminal.app");
        } else {
            hint = new Label("双击时将打开系统默认终端");
        }
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        hint.setWrapText(true);

        localTerminalConfigContent.getChildren().addAll(grid, hint);
    }

    /**
     * 构建S3/阿里云OSS类型的配置内容
     */
    private void buildS3ConfigContent() {
        s3ConfigContent = new VBox(15);
        s3ConfigContent.setVisible(false);
        s3ConfigContent.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        s3NameField = new TextField();
        s3NameField.setPromptText("连接名称");
        s3NameField.setPrefWidth(280);
        grid.add(s3NameField, 1, row++);

        grid.add(new Label("端点："), 0, row);
        s3EndpointField = new TextField();
        s3EndpointField.setPromptText("S3端点URL，如 https://s3.amazonaws.com 或 http://127.0.0.1:9000");
        s3EndpointField.setPrefWidth(280);
        grid.add(s3EndpointField, 1, row++);

        grid.add(new Label("区域："), 0, row);
        s3RegionField = new TextField();
        s3RegionField.setPromptText("区域，如 us-east-1、cn-hangzhou");
        s3RegionField.setPrefWidth(280);
        grid.add(s3RegionField, 1, row++);

        grid.add(new Label("Access Key："), 0, row);
        s3AccessKeyField = new TextField();
        s3AccessKeyField.setPromptText("访问密钥ID");
        s3AccessKeyField.setPrefWidth(280);
        grid.add(s3AccessKeyField, 1, row++);

        grid.add(new Label("Secret Key："), 0, row);
        VBox s3SecretBox = new VBox(4);
        s3SecretKeyField = new PasswordField();
        s3SecretKeyField.setPromptText("访问密钥密码");
        s3SecretKeyField.setPrefWidth(260);
        s3SaveSecretKeyCheckBox = new CheckBox("保存密钥");
        s3SaveSecretKeyCheckBox.setSelected(true);
        s3SaveSecretKeyCheckBox.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        s3SecretBox.getChildren().addAll(s3SecretKeyField, s3SaveSecretKeyCheckBox);
        grid.add(s3SecretBox, 1, row++);

        // 路径风格访问选项（MinIO等需要）
        s3PathStyleAccessCheckBox = new CheckBox("路径风格访问（Path-Style Access）");
        s3PathStyleAccessCheckBox.setStyle("-fx-font-size: 11px;");
        grid.add(new Label(""), 0, row);
        grid.add(s3PathStyleAccessCheckBox, 1, row++);

        grid.add(new Label("备注："), 0, row);
        s3DescriptionField = new TextField();
        s3DescriptionField.setPromptText("备注信息");
        s3DescriptionField.setPrefWidth(280);
        grid.add(s3DescriptionField, 1, row);

        // 提示信息
        Label hint = new Label("S3类型支持AWS S3、MinIO等S3兼容存储；阿里云OSS使用专用连接类型");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        hint.setWrapText(true);

        s3ConfigContent.getChildren().addAll(grid, hint);
    }

    /**
     * 构建Redis类型的配置内容
     */
    private void buildRedisConfigContent() {
        redisConfigContent = new VBox(15);
        redisConfigContent.setVisible(false);
        redisConfigContent.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        redisNameField = new TextField();
        redisNameField.setPromptText("连接名称");
        redisNameField.setPrefWidth(280);
        grid.add(redisNameField, 1, row++);

        grid.add(new Label("主机："), 0, row);
        redisHostField = new TextField();
        redisHostField.setPromptText("Redis服务器地址");
        redisHostField.setPrefWidth(280);
        grid.add(redisHostField, 1, row++);

        grid.add(new Label("端口："), 0, row);
        redisPortField = new TextField();
        redisPortField.setPromptText("6379");
        redisPortField.setPrefWidth(100);
        grid.add(redisPortField, 1, row++);

        grid.add(new Label("用户名："), 0, row);
        redisUsernameField = new TextField();
        redisUsernameField.setPromptText("ACL用户名（可选，留空使用默认）");
        redisUsernameField.setPrefWidth(280);
        grid.add(redisUsernameField, 1, row++);

        // 密码
        grid.add(new Label("密码："), 0, row);
        VBox redisPwdBox = new VBox(4);
        redisPasswordField = new PasswordField();
        redisPasswordField.setPromptText("Redis密码");
        redisPasswordField.setPrefWidth(260);
        redisSavePasswordCheckBox = new CheckBox("保存密码");
        redisSavePasswordCheckBox.setSelected(true);
        redisSavePasswordCheckBox.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        redisPwdBox.getChildren().addAll(redisPasswordField, redisSavePasswordCheckBox);
        grid.add(redisPwdBox, 1, row++);

        // 集群模式
        redisClusterCheckBox = new CheckBox("集群模式（Cluster）");
        redisClusterCheckBox.setStyle("-fx-font-weight: bold;");

        // 集群节点内容（勾选后显示）
        redisClusterContent = new VBox(8);
        redisClusterContent.setPadding(new Insets(5, 0, 0, 24));
        redisClusterContent.setVisible(false);
        redisClusterContent.setManaged(false);

        Label clusterNodesLabel = new Label("集群节点：");
        clusterNodesLabel.setStyle("-fx-font-size: 12px;");
        redisClusterNodesField = new TextField();
        redisClusterNodesField.setPromptText("host1:port1,host2:port2,host3:port3");
        redisClusterNodesField.setPrefWidth(280);
        Label clusterNodesHint = new Label("格式: host1:port1,host2:port2,... 多个节点用逗号分隔");
        clusterNodesHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        clusterNodesHint.setWrapText(true);
        redisClusterContent.getChildren().addAll(clusterNodesLabel, redisClusterNodesField, clusterNodesHint);

        redisClusterCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            redisClusterContent.setVisible(newVal);
            redisClusterContent.setManaged(newVal);
            // 集群模式下禁用数据库选择
            if (newVal) {
                redisDatabaseField.setDisable(true);
                redisDatabaseField.setText("0");
            } else {
                redisDatabaseField.setDisable(false);
            }
        });

        VBox clusterBox = new VBox(6);
        clusterBox.getChildren().addAll(redisClusterCheckBox, redisClusterContent);
        grid.add(new Label("模式："), 0, row);
        grid.add(clusterBox, 1, row++);

        // 默认数据库编号
        grid.add(new Label("数据库："), 0, row);
        redisDatabaseField = new TextField();
        redisDatabaseField.setPromptText("0");
        redisDatabaseField.setPrefWidth(60);
        grid.add(redisDatabaseField, 1, row++);

        grid.add(new Label("备注："), 0, row);
        redisDescriptionField = new TextField();
        redisDescriptionField.setPromptText("备注信息");
        redisDescriptionField.setPrefWidth(280);
        grid.add(redisDescriptionField, 1, row);

        // 提示信息
        Label hint = new Label("集群模式下不支持选择数据库，默认使用0号数据库");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        hint.setWrapText(true);

        redisConfigContent.getChildren().addAll(grid, hint);
    }

    /**
     * 构建RocketMQ类型的配置内容
     */
    private void buildRocketmqConfigContent() {
        rocketmqConfigContent = new VBox(15);
        rocketmqConfigContent.setVisible(false);
        rocketmqConfigContent.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        rocketmqNameField = new TextField();
        rocketmqNameField.setPromptText("连接名称");
        rocketmqNameField.setPrefWidth(280);
        grid.add(rocketmqNameField, 1, row++);

        grid.add(new Label("NameServer主机："), 0, row);
        rocketmqHostField = new TextField();
        rocketmqHostField.setPromptText("NameServer地址");
        rocketmqHostField.setPrefWidth(280);
        grid.add(rocketmqHostField, 1, row++);

        grid.add(new Label("NameServer端口："), 0, row);
        rocketmqPortField = new TextField();
        rocketmqPortField.setPromptText("9876");
        rocketmqPortField.setPrefWidth(100);
        grid.add(rocketmqPortField, 1, row++);

        grid.add(new Label("备注："), 0, row);
        rocketmqDescriptionField = new TextField();
        rocketmqDescriptionField.setPromptText("备注信息");
        rocketmqDescriptionField.setPrefWidth(280);
        grid.add(rocketmqDescriptionField, 1, row);

        Label hint = new Label("填写RocketMQ NameServer地址，直接连接管理主题、消息、消费者组等");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        hint.setWrapText(true);

        rocketmqConfigContent.getChildren().addAll(grid, hint);
    }

    /**
     * 构建阿里云类型的配置内容
     */
    private void buildAliyunConfigContent() {
        aliyunConfigContent = new VBox(15);
        aliyunConfigContent.setVisible(false);
        aliyunConfigContent.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        aliyunNameField = new TextField();
        aliyunNameField.setPromptText("连接名称");
        aliyunNameField.setPrefWidth(280);
        grid.add(aliyunNameField, 1, row++);

        grid.add(new Label("Access Key："), 0, row);
        aliyunAccessKeyField = new TextField();
        aliyunAccessKeyField.setPromptText("阿里云AccessKey ID");
        aliyunAccessKeyField.setPrefWidth(280);
        grid.add(aliyunAccessKeyField, 1, row++);

        grid.add(new Label("Secret Key："), 0, row);
        VBox aliyunSecretBox = new VBox(4);
        aliyunSecretKeyField = new PasswordField();
        aliyunSecretKeyField.setPromptText("阿里云AccessKey Secret");
        aliyunSecretKeyField.setPrefWidth(260);
        aliyunSaveSecretKeyCheckBox = new CheckBox("保存密钥");
        aliyunSaveSecretKeyCheckBox.setSelected(true);
        aliyunSaveSecretKeyCheckBox.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        aliyunSecretBox.getChildren().addAll(aliyunSecretKeyField, aliyunSaveSecretKeyCheckBox);
        grid.add(aliyunSecretBox, 1, row++);

        grid.add(new Label("备注："), 0, row);
        aliyunDescriptionField = new TextField();
        aliyunDescriptionField.setPromptText("备注信息");
        aliyunDescriptionField.setPrefWidth(280);
        grid.add(aliyunDescriptionField, 1, row);

        Label hint = new Label("双击连接时将通过AK/SK进行OAuth2认证，认证通过后加载可访问的云服务列表");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        hint.setWrapText(true);

        aliyunConfigContent.getChildren().addAll(grid, hint);
    }

    // ==================== 密钥条目管理 ====================

    /**
     * 添加SSH通道密钥条目
     */
    private void addSshTunnelKeyEntry(String path, boolean selected) {
        KeyEntry entry = new KeyEntry(path, selected);

        entry.browseBtn.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("选择私钥文件");
            fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("所有文件", "*.*"),
                new FileChooser.ExtensionFilter("私钥文件", "*.pem", "*.key", "id_rsa", "id_ed25519", "id_ecdsa")
            );
            File sshDir = new File(System.getProperty("user.home"), ".ssh");
            if (sshDir.exists()) {
                fileChooser.setInitialDirectory(sshDir);
            }
            File file = fileChooser.showOpenDialog(dialogStage);
            if (file != null) {
                entry.pathField.setText(file.getAbsolutePath());
            }
        });

        entry.removeBtn.setOnAction(e -> {
            sshTunnelKeyEntries.remove(entry);
            sshTunnelKeyListContainer.getChildren().remove(entry.row);
        });

        entry.pathField.setDisable(!sshTunnelUseKeyCheckBox.isSelected());
        entry.browseBtn.setDisable(!sshTunnelUseKeyCheckBox.isSelected());
        entry.checkBox.setDisable(!sshTunnelUseKeyCheckBox.isSelected());

        sshTunnelKeyEntries.add(entry);
        sshTunnelKeyListContainer.getChildren().add(entry.row);
    }

    private void setSshTunnelKeyEntriesEnabled(boolean enabled) {
        for (KeyEntry entry : sshTunnelKeyEntries) {
            entry.checkBox.setDisable(!enabled);
            entry.pathField.setDisable(!enabled);
            entry.browseBtn.setDisable(!enabled);
            entry.removeBtn.setDisable(!enabled);
        }
    }

    /**
     * 添加SSH/SFTP密钥条目
     */
    private void addSshKeyEntry(String path, boolean selected) {
        KeyEntry entry = new KeyEntry(path, selected);

        entry.browseBtn.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("选择私钥文件");
            fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("所有文件", "*.*"),
                new FileChooser.ExtensionFilter("私钥文件", "*.pem", "*.key", "id_rsa", "id_ed25519", "id_ecdsa")
            );
            File sshDir = new File(System.getProperty("user.home"), ".ssh");
            if (sshDir.exists()) {
                fileChooser.setInitialDirectory(sshDir);
            }
            File file = fileChooser.showOpenDialog(dialogStage);
            if (file != null) {
                entry.pathField.setText(file.getAbsolutePath());
            }
        });

        entry.removeBtn.setOnAction(e -> {
            sshKeyEntries.remove(entry);
            sshKeyListContainer.getChildren().remove(entry.row);
        });

        entry.pathField.setDisable(!sshUseKeyCheckBox.isSelected());
        entry.browseBtn.setDisable(!sshUseKeyCheckBox.isSelected());
        entry.checkBox.setDisable(!sshUseKeyCheckBox.isSelected());

        sshKeyEntries.add(entry);
        sshKeyListContainer.getChildren().add(entry.row);
    }

    private void setSshKeyEntriesEnabled(boolean enabled) {
        for (KeyEntry entry : sshKeyEntries) {
            entry.checkBox.setDisable(!enabled);
            entry.pathField.setDisable(!enabled);
            entry.browseBtn.setDisable(!enabled);
            entry.removeBtn.setDisable(!enabled);
        }
    }

    // ==================== 页面切换 ====================

    private void showTypeSelectionPage() {
        typeSelectionPage.setVisible(true);
        typeSelectionPage.setManaged(true);
        configPage.setVisible(false);
        configPage.setManaged(false);
        dialogStage.setTitle("新建连接");
    }

    private void showConfigPage() {
        typeSelectionPage.setVisible(false);
        typeSelectionPage.setManaged(false);
        configPage.setVisible(true);
        configPage.setManaged(true);
        dialogStage.setTitle("新建" + selectedType.getDisplayName() + "连接");
        configTitle.setText(selectedType.getDisplayName() + "连接配置");
        // 根据类型设置标题图标
        try {
            Image typeIcon = new Image(getClass().getResourceAsStream(selectedType.getIconPath()));
            configTitleGraphic.setImage(typeIcon);
        } catch (Exception ignored) {
            configTitleGraphic.setImage(null);
        }

        // 根据类型切换布局
        boolean isDatabase = isDatabaseType(selectedType);
        boolean isSSH = selectedType == ConnectType.SSH || selectedType == ConnectType.SFTP;
        boolean isLocalTerminal = selectedType == ConnectType.LOCAL_TERMINAL;
        boolean isS3orOSS = selectedType == ConnectType.S3 || selectedType == ConnectType.ALIYUN_OSS;
        boolean isRedis = selectedType == ConnectType.REDIS;
        boolean isRocketmq = selectedType == ConnectType.ROCKETMQ;
        boolean isAliyun = selectedType == ConnectType.ALIYUN;

        // 数据库类型TabPane已有标签标题，隐藏顶部标题避免重复
        configTitle.setVisible(!isDatabase);
        configTitle.setManaged(!isDatabase);
        // 数据库类型时去掉顶部和左侧padding，让标签顶到最上面和最左边
        if (isDatabase) {
            root.setPadding(new Insets(0, 20, 20, 0));
        } else {
            root.setPadding(new Insets(20));
        }

        dbTabPane.setVisible(isDatabase);
        dbTabPane.setManaged(isDatabase);
        sshConfigContent.setVisible(isSSH);
        sshConfigContent.setManaged(isSSH);
        simpleConfigContent.setVisible(!isDatabase && !isSSH && !isLocalTerminal && !isS3orOSS && !isRedis && !isRocketmq && !isAliyun);
        simpleConfigContent.setManaged(!isDatabase && !isSSH && !isLocalTerminal && !isS3orOSS && !isRedis && !isRocketmq && !isAliyun);
        localTerminalConfigContent.setVisible(isLocalTerminal);
        localTerminalConfigContent.setManaged(isLocalTerminal);
        s3ConfigContent.setVisible(isS3orOSS);
        s3ConfigContent.setManaged(isS3orOSS);
        redisConfigContent.setVisible(isRedis);
        redisConfigContent.setManaged(isRedis);
        rocketmqConfigContent.setVisible(isRocketmq);
        rocketmqConfigContent.setManaged(isRocketmq);
        aliyunConfigContent.setVisible(isAliyun);
        aliyunConfigContent.setManaged(isAliyun);

        if (isDatabase) {
            // 设置默认端口
            if (portField.getText().isEmpty()) {
                portField.setText(String.valueOf(getDefaultPort(selectedType)));
            }
            if (sshTunnelPortField.getText().isEmpty()) {
                sshTunnelPortField.setText("22");
            }
        } else if (isSSH) {
            if (sshPortField.getText().isEmpty()) {
                sshPortField.setText("22");
            }
        } else if (isS3orOSS) {
            if (s3RegionField.getText().isEmpty()) {
                s3RegionField.setText(selectedType == ConnectType.ALIYUN_OSS ? "cn-hangzhou" : "us-east-1");
            }
            // S3类型默认勾选路径风格访问
            if (selectedType == ConnectType.S3 && !s3PathStyleAccessCheckBox.isSelected()) {
                // 不自动勾选，让用户根据实际情况选择
            }
        } else if (isRedis) {
            if (redisPortField.getText().isEmpty()) {
                redisPortField.setText(String.valueOf(getDefaultPort(selectedType)));
            }
            if (redisDatabaseField.getText().isEmpty()) {
                redisDatabaseField.setText("0");
            }
        } else if (isRocketmq) {
            if (rocketmqPortField.getText().isEmpty()) {
                rocketmqPortField.setText("9876");
            }
        } else {
            if (simplePortField.getText().isEmpty()) {
                simplePortField.setText(String.valueOf(getDefaultPort(selectedType)));
            }
        }
    }

    private boolean isDatabaseType(ConnectType type) {
        return type == ConnectType.MYSQL || type == ConnectType.POSTGRESQL || type == ConnectType.ORACLE;
    }

    // ==================== 填充已有配置 ====================

    private void fillExistingConfig() {
        if (existingConfig == null) return;

        boolean isDatabase = isDatabaseType(selectedType);
        boolean isSSH = selectedType == ConnectType.SSH || selectedType == ConnectType.SFTP;
        boolean isLocalTerminal = selectedType == ConnectType.LOCAL_TERMINAL;
        boolean isS3orOSS = selectedType == ConnectType.S3 || selectedType == ConnectType.ALIYUN_OSS;
        boolean isRedis = selectedType == ConnectType.REDIS;
        boolean isRocketmq = selectedType == ConnectType.ROCKETMQ;
        boolean isAliyun = selectedType == ConnectType.ALIYUN;

        if (isLocalTerminal) {
            localTerminalNameField.setText(existingConfig.getName());
            if (existingConfig.getTerminalType() != null) {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    localTerminalTypeCombo.setValue(existingConfig.getTerminalType());
                }
            }
            localTerminalDescriptionField.setText(existingConfig.getDescription() != null ? existingConfig.getDescription() : "");
        } else if (isDatabase) {
            nameField.setText(existingConfig.getName());
            hostField.setText(existingConfig.getHost());
            portField.setText(String.valueOf(existingConfig.getPort()));
            usernameField.setText(existingConfig.getUsername());

            if (existingConfig.getPassword() != null) {
                passwordField.setText(existingConfig.getPassword());
            }
            savePasswordCheckBox.setSelected(existingConfig.isSavePassword());

            descriptionField.setText(existingConfig.getDescription());

            // SSH通道配置
            useSshTunnelCheckBox.setSelected(existingConfig.isUseSshTunnel());
            if (existingConfig.getSshTunnelHost() != null) {
                sshTunnelHostField.setText(existingConfig.getSshTunnelHost());
            }
            sshTunnelPortField.setText(String.valueOf(existingConfig.getSshTunnelPort()));
            if (existingConfig.getSshTunnelUsername() != null) {
                sshTunnelUsernameField.setText(existingConfig.getSshTunnelUsername());
            }

            sshTunnelUsePasswordCheckBox.setSelected(existingConfig.isSshTunnelUsePassword());
            if (existingConfig.getSshTunnelPassword() != null) {
                sshTunnelPasswordField.setText(existingConfig.getSshTunnelPassword());
            }
            sshTunnelSavePasswordCheckBox.setSelected(existingConfig.isSshTunnelSavePassword());
            sshTunnelUseKeyCheckBox.setSelected(existingConfig.isSshTunnelUseKey());

            // SSH通道密钥列表
            sshTunnelKeyEntries.clear();
            sshTunnelKeyListContainer.getChildren().clear();
            List<String> tunnelKeyPaths = existingConfig.getSshTunnelPrivateKeyPaths();
            if (tunnelKeyPaths != null && !tunnelKeyPaths.isEmpty()) {
                for (String path : tunnelKeyPaths) {
                    addSshTunnelKeyEntry(path, true);
                }
            }

        } else if (isSSH) {
            sshNameField.setText(existingConfig.getName());
            sshHostField.setText(existingConfig.getHost());
            sshPortField.setText(String.valueOf(existingConfig.getPort()));
            sshUsernameField.setText(existingConfig.getUsername());

            sshUsePasswordCheckBox.setSelected(existingConfig.isUsePassword());
            sshUseKeyCheckBox.setSelected(existingConfig.isUseKey());

            if (existingConfig.getPassword() != null) {
                sshPasswordField.setText(existingConfig.getPassword());
            }
            sshSavePasswordCheckBox.setSelected(existingConfig.isSavePassword());

            // 密钥列表
            sshKeyEntries.clear();
            sshKeyListContainer.getChildren().clear();
            List<String> paths = existingConfig.getPrivateKeyPaths();
            if (paths != null && !paths.isEmpty()) {
                for (String path : paths) {
                    addSshKeyEntry(path, true);
                }
            }

            sshDescriptionField.setText(existingConfig.getDescription());

        } else if (isS3orOSS) {
            s3NameField.setText(existingConfig.getName());
            if (existingConfig.getEndpoint() != null) {
                s3EndpointField.setText(existingConfig.getEndpoint());
            }
            if (existingConfig.getRegion() != null) {
                s3RegionField.setText(existingConfig.getRegion());
            }
            s3AccessKeyField.setText(existingConfig.getUsername() != null ? existingConfig.getUsername() : "");
            if (existingConfig.getPassword() != null) {
                s3SecretKeyField.setText(existingConfig.getPassword());
            }
            s3SaveSecretKeyCheckBox.setSelected(existingConfig.isSavePassword());
            s3PathStyleAccessCheckBox.setSelected(existingConfig.isPathStyleAccess());
            s3DescriptionField.setText(existingConfig.getDescription() != null ? existingConfig.getDescription() : "");
        } else if (isRedis) {
            redisNameField.setText(existingConfig.getName());
            redisHostField.setText(existingConfig.getHost());
            redisPortField.setText(String.valueOf(existingConfig.getPort()));
            redisUsernameField.setText(existingConfig.getUsername() != null ? existingConfig.getUsername() : "");
            if (existingConfig.getPassword() != null) {
                redisPasswordField.setText(existingConfig.getPassword());
            }
            redisSavePasswordCheckBox.setSelected(existingConfig.isSavePassword());
            redisClusterCheckBox.setSelected(existingConfig.isRedisCluster());
            if (existingConfig.isRedisCluster()) {
                redisClusterContent.setVisible(true);
                redisClusterContent.setManaged(true);
                redisDatabaseField.setDisable(true);
            }
            if (existingConfig.getRedisClusterNodes() != null) {
                redisClusterNodesField.setText(existingConfig.getRedisClusterNodes());
            }
            redisDatabaseField.setText(String.valueOf(existingConfig.getRedisDatabase()));
            redisDescriptionField.setText(existingConfig.getDescription() != null ? existingConfig.getDescription() : "");
        } else if (isRocketmq) {
            rocketmqNameField.setText(existingConfig.getName());
            rocketmqHostField.setText(existingConfig.getHost());
            rocketmqPortField.setText(String.valueOf(existingConfig.getPort()));
            rocketmqDescriptionField.setText(existingConfig.getDescription() != null ? existingConfig.getDescription() : "");
        } else if (isAliyun) {
            aliyunNameField.setText(existingConfig.getName());
            aliyunAccessKeyField.setText(existingConfig.getUsername() != null ? existingConfig.getUsername() : "");
            if (existingConfig.getPassword() != null) {
                aliyunSecretKeyField.setText(existingConfig.getPassword());
            }
            aliyunSaveSecretKeyCheckBox.setSelected(existingConfig.isSavePassword());
            aliyunDescriptionField.setText(existingConfig.getDescription() != null ? existingConfig.getDescription() : "");
        } else {
            simpleNameField.setText(existingConfig.getName());
            simpleHostField.setText(existingConfig.getHost());
            simplePortField.setText(String.valueOf(existingConfig.getPort()));
            simpleUsernameField.setText(existingConfig.getUsername());

            if (existingConfig.getPassword() != null) {
                simplePasswordField.setText(existingConfig.getPassword());
            }
            simpleSavePasswordCheckBox.setSelected(existingConfig.isSavePassword());
            simpleDescriptionField.setText(existingConfig.getDescription());

            // RDP专属配置回填
            if (selectedType == ConnectType.RDP) {
                if (existingConfig.getDomain() != null) {
                    rdpDomainField.setText(existingConfig.getDomain());
                }
                int w = existingConfig.getScreenWidth();
                int h = existingConfig.getScreenHeight();
                if (w > 0 && h > 0) {
                    rdpResolutionCombo.setValue(w + "x" + h);
                }
                int bpp = existingConfig.getColorDepth();
                if (bpp > 0) {
                    rdpColorDepthCombo.setValue(bpp + "位");
                }
                rdpUseSslCheck.setSelected(existingConfig.isUseSsl());
            }
        }
    }

    // ==================== 确认保存 ====================

    private void handleConfirm() {
        if (!validateInput()) return;

        config = new ConnectionConfig();
        if (existingConfig != null) {
            config.setId(existingConfig.getId());
            config.setParentId(existingConfig.getParentId());
        }

        boolean isDatabase = isDatabaseType(selectedType);
        boolean isSSH = selectedType == ConnectType.SSH || selectedType == ConnectType.SFTP;
        boolean isLocalTerminal = selectedType == ConnectType.LOCAL_TERMINAL;

        config.setType(selectedType);

        boolean isS3orOSS = selectedType == ConnectType.S3 || selectedType == ConnectType.ALIYUN_OSS;
        boolean isRedis = selectedType == ConnectType.REDIS;
        boolean isRocketmq = selectedType == ConnectType.ROCKETMQ;
        boolean isAliyun = selectedType == ConnectType.ALIYUN;

        if (isLocalTerminal) {
            config.setName(localTerminalNameField.getText().trim());
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                config.setTerminalType(localTerminalTypeCombo.getValue());
            } else {
                config.setTerminalType("system");
            }
            config.setDescription(localTerminalDescriptionField.getText().trim());
        } else if (isDatabase) {
            config.setName(nameField.getText().trim());
            config.setHost(hostField.getText().trim());
            config.setPort(Integer.parseInt(portField.getText().trim()));
            config.setUsername(usernameField.getText().trim());
            config.setUsePassword(true);
            config.setUseKey(false);
            config.setSavePassword(savePasswordCheckBox.isSelected());
            if (savePasswordCheckBox.isSelected()) {
                config.setPassword(passwordField.getText());
            } else {
                config.setPassword(null);
            }
            config.setDescription(descriptionField.getText().trim());

            // SSH通道配置
            config.setUseSshTunnel(useSshTunnelCheckBox.isSelected());
            if (useSshTunnelCheckBox.isSelected()) {
                config.setSshTunnelHost(sshTunnelHostField.getText().trim());
                config.setSshTunnelPort(Integer.parseInt(sshTunnelPortField.getText().trim()));
                config.setSshTunnelUsername(sshTunnelUsernameField.getText().trim());

                config.setSshTunnelUsePassword(sshTunnelUsePasswordCheckBox.isSelected());
                config.setSshTunnelUseKey(sshTunnelUseKeyCheckBox.isSelected());

                // SSH通道密钥列表
                List<String> tunnelKeyPaths = new ArrayList<>();
                for (KeyEntry entry : sshTunnelKeyEntries) {
                    if (entry.checkBox.isSelected() && !entry.pathField.getText().trim().isEmpty()) {
                        tunnelKeyPaths.add(entry.pathField.getText().trim());
                    }
                }
                config.setSshTunnelPrivateKeyPaths(tunnelKeyPaths);

                if (sshTunnelUsePasswordCheckBox.isSelected()) {
                    config.setSshTunnelSavePassword(sshTunnelSavePasswordCheckBox.isSelected());
                    if (sshTunnelSavePasswordCheckBox.isSelected()) {
                        config.setSshTunnelPassword(sshTunnelPasswordField.getText());
                    } else {
                        config.setSshTunnelPassword(null);
                    }
                } else {
                    config.setSshTunnelSavePassword(false);
                    // 仅密钥认证时，密码作为 passphrase
                    if (sshTunnelUseKeyCheckBox.isSelected() && sshTunnelPasswordField.getText() != null && !sshTunnelPasswordField.getText().isEmpty()) {
                        config.setSshTunnelPassword(sshTunnelPasswordField.getText());
                    } else {
                        config.setSshTunnelPassword(null);
                    }
                }
            }

        } else if (isSSH) {
            config.setName(sshNameField.getText().trim());
            config.setHost(sshHostField.getText().trim());
            config.setPort(Integer.parseInt(sshPortField.getText().trim()));
            config.setUsername(sshUsernameField.getText().trim());

            config.setUsePassword(sshUsePasswordCheckBox.isSelected());
            config.setUseKey(sshUseKeyCheckBox.isSelected());

            // 密钥列表
            List<String> enabledKeyPaths = new ArrayList<>();
            for (KeyEntry entry : sshKeyEntries) {
                if (entry.checkBox.isSelected() && !entry.pathField.getText().trim().isEmpty()) {
                    enabledKeyPaths.add(entry.pathField.getText().trim());
                }
            }
            config.setPrivateKeyPaths(enabledKeyPaths);

            if (sshUsePasswordCheckBox.isSelected()) {
                config.setSavePassword(sshSavePasswordCheckBox.isSelected());
                if (sshSavePasswordCheckBox.isSelected()) {
                    config.setPassword(sshPasswordField.getText());
                } else {
                    config.setPassword(null);
                }
            } else {
                config.setSavePassword(false);
                if (sshUseKeyCheckBox.isSelected() && sshPasswordField.getText() != null && !sshPasswordField.getText().isEmpty()) {
                    config.setPassword(sshPasswordField.getText());
                } else {
                    config.setPassword(null);
                }
            }

            config.setDescription(sshDescriptionField.getText().trim());

        } else if (isS3orOSS) {
            config.setName(s3NameField.getText().trim());
            config.setEndpoint(s3EndpointField.getText().trim());
            config.setRegion(s3RegionField.getText().trim());
            config.setUsername(s3AccessKeyField.getText().trim());
            config.setUsePassword(true);
            config.setUseKey(false);
            config.setSavePassword(s3SaveSecretKeyCheckBox.isSelected());
            config.setPathStyleAccess(s3PathStyleAccessCheckBox.isSelected());
            if (s3SaveSecretKeyCheckBox.isSelected()) {
                config.setPassword(s3SecretKeyField.getText());
            } else {
                config.setPassword(null);
            }
            config.setDescription(s3DescriptionField.getText().trim());

        } else if (isRedis) {
            config.setName(redisNameField.getText().trim());
            config.setHost(redisHostField.getText().trim());
            config.setPort(Integer.parseInt(redisPortField.getText().trim()));
            config.setUsername(redisUsernameField.getText().trim().isEmpty() ? null : redisUsernameField.getText().trim());
            config.setUsePassword(true);
            config.setUseKey(false);
            config.setSavePassword(redisSavePasswordCheckBox.isSelected());
            if (redisSavePasswordCheckBox.isSelected()) {
                config.setPassword(redisPasswordField.getText());
            } else {
                config.setPassword(null);
            }
            config.setRedisCluster(redisClusterCheckBox.isSelected());
            config.setRedisClusterNodes(redisClusterNodesField.getText().trim());
            config.setRedisDatabase(Integer.parseInt(redisDatabaseField.getText().trim()));
            config.setDescription(redisDescriptionField.getText().trim());

        } else if (isRocketmq) {
            config.setName(rocketmqNameField.getText().trim());
            config.setHost(rocketmqHostField.getText().trim());
            config.setPort(Integer.parseInt(rocketmqPortField.getText().trim()));
            config.setDescription(rocketmqDescriptionField.getText().trim());

        } else if (isAliyun) {
            config.setName(aliyunNameField.getText().trim());
            config.setUsername(aliyunAccessKeyField.getText().trim());
            config.setUsePassword(true);
            config.setUseKey(false);
            config.setSavePassword(aliyunSaveSecretKeyCheckBox.isSelected());
            if (aliyunSaveSecretKeyCheckBox.isSelected()) {
                config.setPassword(aliyunSecretKeyField.getText());
            } else {
                config.setPassword(null);
            }
            config.setDescription(aliyunDescriptionField.getText().trim());

        } else {
            config.setName(simpleNameField.getText().trim());
            config.setHost(simpleHostField.getText().trim());
            config.setPort(Integer.parseInt(simplePortField.getText().trim()));
            config.setUsername(simpleUsernameField.getText().trim());
            config.setUsePassword(true);
            config.setUseKey(false);
            config.setSavePassword(simpleSavePasswordCheckBox.isSelected());
            if (simpleSavePasswordCheckBox.isSelected()) {
                config.setPassword(simplePasswordField.getText());
            } else {
                config.setPassword(null);
            }
            config.setDescription(simpleDescriptionField.getText().trim());

            // RDP专属配置
            if (selectedType == ConnectType.RDP) {
                config.setDomain(rdpDomainField.getText().trim());
                // 解析分辨率
                String resolution = rdpResolutionCombo.getValue();
                if (resolution != null && resolution.contains("x")) {
                    try {
                        String[] parts = resolution.split("x");
                        config.setScreenWidth(Integer.parseInt(parts[0].trim()));
                        config.setScreenHeight(Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException ignored) {}
                }
                // 解析色深
                String colorDepth = rdpColorDepthCombo.getValue();
                if (colorDepth != null) {
                    config.setColorDepth(Integer.parseInt(colorDepth.replace("位", "").trim()));
                }
                // SSL/TLS加密
                config.setUseSsl(rdpUseSslCheck.isSelected());
            }
        }

        confirmed = true;
        dialogStage.close();
    }

    // ==================== 输入验证 ====================

    private boolean validateInput() {
        boolean isDatabase = isDatabaseType(selectedType);
        boolean isSSH = selectedType == ConnectType.SSH || selectedType == ConnectType.SFTP;
        boolean isLocalTerminal = selectedType == ConnectType.LOCAL_TERMINAL;
        boolean isS3orOSS = selectedType == ConnectType.S3 || selectedType == ConnectType.ALIYUN_OSS;
        boolean isRedis = selectedType == ConnectType.REDIS;
        boolean isRocketmq = selectedType == ConnectType.ROCKETMQ;
        boolean isAliyun = selectedType == ConnectType.ALIYUN;

        if (isLocalTerminal) {
            return validateLocalTerminalInput();
        } else if (isDatabase) {
            return validateDatabaseInput();
        } else if (isSSH) {
            return validateSshInput();
        } else if (isS3orOSS) {
            return validateS3Input();
        } else if (isRedis) {
            return validateRedisInput();
        } else if (isRocketmq) {
            return validateRocketmqInput();
        } else if (isAliyun) {
            return validateAliyunInput();
        } else {
            return validateSimpleInput();
        }
    }

    private boolean validateLocalTerminalInput() {
        if (localTerminalNameField.getText().trim().isEmpty()) {
            showAlert("请输入终端名称");
            return false;
        }
        return true;
    }

    private boolean validateDatabaseInput() {
        if (nameField.getText().trim().isEmpty()) {
            showAlert("请输入连接名称");
            return false;
        }
        if (hostField.getText().trim().isEmpty()) {
            showAlert("请输入主机地址");
            return false;
        }
        try {
            Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showAlert("端口号必须是数字");
            return false;
        }
        if (usernameField.getText().trim().isEmpty()) {
            showAlert("请输入用户名");
            return false;
        }
        if (passwordField.getText().trim().isEmpty()) {
            showAlert("请输入密码");
            return false;
        }

        // SSH通道验证
        if (useSshTunnelCheckBox.isSelected()) {
            if (sshTunnelHostField.getText().trim().isEmpty()) {
                showAlert("请输入SSH主机地址");
                return false;
            }
            try {
                Integer.parseInt(sshTunnelPortField.getText().trim());
            } catch (NumberFormatException e) {
                showAlert("SSH端口号必须是数字");
                return false;
            }
            if (sshTunnelUsernameField.getText().trim().isEmpty()) {
                showAlert("请输入SSH用户名");
                return false;
            }
            // 至少选择一种SSH认证方式
            if (!sshTunnelUsePasswordCheckBox.isSelected() && !sshTunnelUseKeyCheckBox.isSelected()) {
                showAlert("请至少选择一种SSH认证方式");
                return false;
            }
            // SSH密码认证时密码必填
            if (sshTunnelUsePasswordCheckBox.isSelected() && sshTunnelPasswordField.getText().trim().isEmpty()) {
                showAlert("请输入SSH密码");
                return false;
            }
            // SSH密钥认证时至少添加一个密钥并勾选启用
            if (sshTunnelUseKeyCheckBox.isSelected()) {
                boolean hasEnabledKey = false;
                for (KeyEntry entry : sshTunnelKeyEntries) {
                    if (entry.checkBox.isSelected() && !entry.pathField.getText().trim().isEmpty()) {
                        hasEnabledKey = true;
                        break;
                    }
                }
                if (!hasEnabledKey) {
                    showAlert("请至少添加并启用一个SSH密钥文件");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean validateSshInput() {
        if (sshNameField.getText().trim().isEmpty()) {
            showAlert("请输入连接名称");
            return false;
        }
        if (sshHostField.getText().trim().isEmpty()) {
            showAlert("请输入主机地址");
            return false;
        }
        try {
            Integer.parseInt(sshPortField.getText().trim());
        } catch (NumberFormatException e) {
            showAlert("端口号必须是数字");
            return false;
        }
        if (sshUsernameField.getText().trim().isEmpty()) {
            showAlert("请输入用户名");
            return false;
        }
        if (!sshUsePasswordCheckBox.isSelected() && !sshUseKeyCheckBox.isSelected()) {
            showAlert("请至少选择一种认证方式");
            return false;
        }
        if (sshUsePasswordCheckBox.isSelected() && sshPasswordField.getText().trim().isEmpty()) {
            showAlert("请输入密码");
            return false;
        }
        if (sshUseKeyCheckBox.isSelected()) {
            boolean hasEnabledKey = false;
            for (KeyEntry entry : sshKeyEntries) {
                if (entry.checkBox.isSelected() && !entry.pathField.getText().trim().isEmpty()) {
                    hasEnabledKey = true;
                    break;
                }
            }
            if (!hasEnabledKey) {
                showAlert("请至少添加并启用一个密钥文件");
                return false;
            }
        }
        return true;
    }

    private boolean validateS3Input() {
        if (s3NameField.getText().trim().isEmpty()) {
            showAlert("请输入连接名称");
            return false;
        }
        if (s3EndpointField.getText().trim().isEmpty()) {
            showAlert("请输入端点URL");
            return false;
        }
        if (s3RegionField.getText().trim().isEmpty()) {
            showAlert("请输入区域");
            return false;
        }
        if (s3AccessKeyField.getText().trim().isEmpty()) {
            showAlert("请输入Access Key");
            return false;
        }
        if (s3SecretKeyField.getText().trim().isEmpty()) {
            showAlert("请输入Secret Key");
            return false;
        }
        return true;
    }

    private boolean validateRedisInput() {
        if (redisNameField.getText().trim().isEmpty()) {
            showAlert("请输入连接名称");
            return false;
        }
        if (redisHostField.getText().trim().isEmpty()) {
            showAlert("请输入主机地址");
            return false;
        }
        try {
            Integer.parseInt(redisPortField.getText().trim());
        } catch (NumberFormatException e) {
            showAlert("端口号必须是数字");
            return false;
        }
        try {
            int db = Integer.parseInt(redisDatabaseField.getText().trim());
            if (db < 0 || db > 15) {
                showAlert("数据库编号必须在0-15之间");
                return false;
            }
        } catch (NumberFormatException e) {
            showAlert("数据库编号必须是数字");
            return false;
        }
        if (redisClusterCheckBox.isSelected()) {
            if (redisClusterNodesField.getText().trim().isEmpty()) {
                showAlert("集群模式下请输入集群节点");
                return false;
            }
        }
        return true;
    }

    private boolean validateRocketmqInput() {
        if (rocketmqNameField.getText().trim().isEmpty()) {
            showAlert("请输入连接名称");
            return false;
        }
        if (rocketmqHostField.getText().trim().isEmpty()) {
            showAlert("请输入NameServer主机地址");
            return false;
        }
        return true;
    }

    private boolean validateAliyunInput() {
        if (aliyunNameField.getText().trim().isEmpty()) {
            showAlert("请输入连接名称");
            return false;
        }
        if (aliyunAccessKeyField.getText().trim().isEmpty()) {
            showAlert("请输入Access Key");
            return false;
        }
        if (aliyunSecretKeyField.getText().trim().isEmpty()) {
            showAlert("请输入Secret Key");
            return false;
        }
        return true;
    }

    private boolean validateSimpleInput() {
        if (simpleNameField.getText().trim().isEmpty()) {
            showAlert("请输入连接名称");
            return false;
        }
        if (simpleHostField.getText().trim().isEmpty()) {
            showAlert("请输入主机地址");
            return false;
        }
        try {
            Integer.parseInt(simplePortField.getText().trim());
        } catch (NumberFormatException e) {
            showAlert("端口号必须是数字");
            return false;
        }
        if (simpleUsernameField.getText().trim().isEmpty()) {
            showAlert("请输入用户名");
            return false;
        }
        if (simplePasswordField.getText().trim().isEmpty()) {
            showAlert("请输入密码");
            return false;
        }
        return true;
    }

    private int getDefaultPort(ConnectType type) {
        return switch (type) {
            case SSH, SFTP -> 22;
            case RDP -> 3389;
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
            case FTP -> 21;
            case ORACLE -> 1521;
            case S3 -> 9000;
            case ALIYUN -> 0;
            case ALIYUN_OSS -> 443;
            case REDIS -> 6379;
            case ROCKETMQ -> 8080;
            case LOCAL_TERMINAL -> 0;
        };
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public ConnectionConfig showAndWait() {
        dialogStage.showAndWait();
        return confirmed ? config : null;
    }
}
