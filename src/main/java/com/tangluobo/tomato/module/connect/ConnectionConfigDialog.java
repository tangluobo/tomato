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

    // 类型选择页
    private VBox typeSelectionPage;
    private ConnectType selectedType;

    // 配置页
    private VBox configPage;
    private Label configTitle;
    private TextField nameField;
    private TextField hostField;
    private TextField portField;
    private TextField usernameField;
    private CheckBox usePasswordCheckBox;
    private PasswordField passwordField;
    private CheckBox savePasswordCheckBox;
    private CheckBox useKeyCheckBox;
    private TextField databaseField;
    private Label dbLabel;
    private TextField descriptionField;

    // 认证区域容器
    private VBox authSection;
    private VBox passwordSection;
    private VBox keySection;

    // 密钥列表
    private VBox keyListContainer;
    private List<KeyEntry> keyEntries = new ArrayList<>();

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

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setMinWidth(480);
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

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        nameField = new TextField();
        nameField.setPromptText("连接名称");
        nameField.setPrefWidth(280);
        grid.add(nameField, 1, row++);

        grid.add(new Label("主机："), 0, row);
        hostField = new TextField();
        hostField.setPromptText("主机地址");
        hostField.setPrefWidth(280);
        grid.add(hostField, 1, row++);

        grid.add(new Label("端口："), 0, row);
        portField = new TextField();
        portField.setPrefWidth(100);
        grid.add(portField, 1, row++);

        grid.add(new Label("用户名："), 0, row);
        usernameField = new TextField();
        usernameField.setPromptText("用户名");
        usernameField.setPrefWidth(280);
        grid.add(usernameField, 1, row++);

        // ===== 认证区域 =====
        authSection = new VBox(8);
        authSection.setPadding(new Insets(8, 0, 8, 0));
        authSection.setStyle("-fx-border-color: #e0e0e0; -fx-border-radius: 6; -fx-padding: 10;");

        // 密码认证
        passwordSection = new VBox(4);
        usePasswordCheckBox = new CheckBox("密码认证");
        usePasswordCheckBox.setSelected(true);
        usePasswordCheckBox.setStyle("-fx-font-weight: bold;");

        VBox passwordFields = new VBox(4);
        passwordFields.setPadding(new Insets(2, 0, 0, 24));
        passwordField = new PasswordField();
        passwordField.setPromptText("密码");
        passwordField.setPrefWidth(260);
        savePasswordCheckBox = new CheckBox("保存密码");
        savePasswordCheckBox.setSelected(true);
        savePasswordCheckBox.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        passwordFields.getChildren().addAll(passwordField, savePasswordCheckBox);
        passwordSection.getChildren().addAll(usePasswordCheckBox, passwordFields);

        // 分隔线
        Separator sep = new Separator();
        sep.setPadding(new Insets(4, 0, 4, 0));

        // 密钥认证 - 列表形式
        keySection = new VBox(4);
        useKeyCheckBox = new CheckBox("密钥认证");
        useKeyCheckBox.setStyle("-fx-font-weight: bold;");

        VBox keyFields = new VBox(4);
        keyFields.setPadding(new Insets(2, 0, 0, 24));

        keyListContainer = new VBox(4);

        // 添加密钥按钮
        Button addKeyBtn = new Button("+ 添加密钥");
        addKeyBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-background-radius: 4;");
        addKeyBtn.setOnAction(e -> addKeyEntry("", true));

        Label keyHint = new Label("勾选启用密钥，密钥有口令时填写上方密码作为 Passphrase");
        keyHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        keyHint.setWrapText(true);

        keyFields.getChildren().addAll(keyListContainer, addKeyBtn, keyHint);
        keySection.getChildren().addAll(useKeyCheckBox, keyFields);

        authSection.getChildren().addAll(passwordSection, sep, keySection);

        grid.add(new Label("认证："), 0, row);
        grid.add(authSection, 1, row++);

        // 数据库行
        dbLabel = new Label("数据库：");
        grid.add(dbLabel, 0, row);
        databaseField = new TextField();
        databaseField.setPromptText("数据库名称");
        databaseField.setPrefWidth(280);
        grid.add(databaseField, 1, row++);

        grid.add(new Label("备注："), 0, row);
        descriptionField = new TextField();
        descriptionField.setPromptText("备注信息");
        descriptionField.setPrefWidth(280);
        grid.add(descriptionField, 1, row);

        // 认证方式切换时控制子控件
        usePasswordCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            passwordField.setDisable(!newVal);
            savePasswordCheckBox.setDisable(!newVal);
            // 至少选一种认证方式
            if (!newVal && !useKeyCheckBox.isSelected()) {
                useKeyCheckBox.setSelected(true);
            }
        });

        useKeyCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            setKeyEntriesEnabled(newVal);
            // 至少选一种认证方式
            if (!newVal && !usePasswordCheckBox.isSelected()) {
                usePasswordCheckBox.setSelected(true);
            }
        });

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
        okBtn.setOnAction(e -> {
            if (validateInput()) {
                config = new ConnectionConfig();
                if (existingConfig != null) {
                    config.setId(existingConfig.getId());
                    config.setParentId(existingConfig.getParentId());
                }
                config.setName(nameField.getText().trim());
                config.setType(selectedType);
                config.setHost(hostField.getText().trim());
                config.setPort(Integer.parseInt(portField.getText().trim()));
                config.setUsername(usernameField.getText().trim());

                config.setUsePassword(usePasswordCheckBox.isSelected());
                config.setUseKey(useKeyCheckBox.isSelected());

                // 保存密钥列表（仅启用勾选的）
                List<String> enabledKeyPaths = new ArrayList<>();
                for (KeyEntry entry : keyEntries) {
                    if (entry.checkBox.isSelected() && !entry.pathField.getText().trim().isEmpty()) {
                        enabledKeyPaths.add(entry.pathField.getText().trim());
                    }
                }
                config.setPrivateKeyPaths(enabledKeyPaths);

                if (usePasswordCheckBox.isSelected()) {
                    config.setSavePassword(savePasswordCheckBox.isSelected());
                    if (savePasswordCheckBox.isSelected()) {
                        config.setPassword(passwordField.getText());
                    } else {
                        config.setPassword(null);
                    }
                } else {
                    config.setSavePassword(false);
                    // 仅密钥认证时，密码作为 passphrase
                    if (useKeyCheckBox.isSelected() && passwordField.getText() != null && !passwordField.getText().isEmpty()) {
                        config.setPassword(passwordField.getText());
                    } else {
                        config.setPassword(null);
                    }
                }

                if (databaseField.isVisible()) {
                    config.setDatabase(databaseField.getText().trim());
                }
                config.setDescription(descriptionField.getText().trim());
                confirmed = true;
                dialogStage.close();
            }
        });

        configButtons.getChildren().addAll(backBtn, cancelBtn2, okBtn);
        configPage.getChildren().addAll(configTitle, grid, configButtons);

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
     * 添加一条密钥条目
     */
    private void addKeyEntry(String path, boolean selected) {
        KeyEntry entry = new KeyEntry(path, selected);
        int index = keyEntries.size();

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
            keyEntries.remove(entry);
            keyListContainer.getChildren().remove(entry.row);
        });

        entry.pathField.setDisable(!useKeyCheckBox.isSelected());
        entry.browseBtn.setDisable(!useKeyCheckBox.isSelected());
        entry.checkBox.setDisable(!useKeyCheckBox.isSelected());

        keyEntries.add(entry);
        keyListContainer.getChildren().add(entry.row);
    }

    /**
     * 设置所有密钥条目的启用/禁用状态
     */
    private void setKeyEntriesEnabled(boolean enabled) {
        for (KeyEntry entry : keyEntries) {
            entry.checkBox.setDisable(!enabled);
            entry.pathField.setDisable(!enabled);
            entry.browseBtn.setDisable(!enabled);
            entry.removeBtn.setDisable(!enabled);
        }
    }

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

        // 数据库字段根据类型显示
        boolean needDb = selectedType == ConnectType.MYSQL || selectedType == ConnectType.POSTGRESQL;
        databaseField.setVisible(needDb);
        databaseField.setManaged(needDb);
        dbLabel.setVisible(needDb);
        dbLabel.setManaged(needDb);

        // 认证方式区域：仅SSH/SFTP类型显示
        boolean isSSH = selectedType == ConnectType.SSH || selectedType == ConnectType.SFTP;
        authSection.setVisible(isSSH);
        authSection.setManaged(isSSH);
        // 非SSH类型只显示简单密码框
        if (!isSSH) {
            usePasswordCheckBox.setSelected(true);
            useKeyCheckBox.setSelected(false);
        }

        // 设置默认端口
        if (portField.getText().isEmpty()) {
            portField.setText(String.valueOf(getDefaultPort(selectedType)));
        }
    }

    private void fillExistingConfig() {
        nameField.setText(existingConfig.getName());
        hostField.setText(existingConfig.getHost());
        portField.setText(String.valueOf(existingConfig.getPort()));
        usernameField.setText(existingConfig.getUsername());

        usePasswordCheckBox.setSelected(existingConfig.isUsePassword());
        useKeyCheckBox.setSelected(existingConfig.isUseKey());

        if (existingConfig.getPassword() != null) {
            passwordField.setText(existingConfig.getPassword());
        }
        savePasswordCheckBox.setSelected(existingConfig.isSavePassword());

        // 填充密钥列表
        keyEntries.clear();
        keyListContainer.getChildren().clear();
        List<String> paths = existingConfig.getPrivateKeyPaths();
        if (paths != null && !paths.isEmpty()) {
            for (String path : paths) {
                addKeyEntry(path, true);
            }
        }

        if (existingConfig.getDatabase() != null) {
            databaseField.setText(existingConfig.getDatabase());
        }
        descriptionField.setText(existingConfig.getDescription());
    }

    private int getDefaultPort(ConnectType type) {
        return switch (type) {
            case SSH, SFTP -> 22;
            case RDP -> 3389;
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
            case FTP -> 21;
            case ORACLE -> 1521;
        };
    }

    private boolean validateInput() {
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
        // 至少选择一种认证方式
        if (!usePasswordCheckBox.isSelected() && !useKeyCheckBox.isSelected()) {
            showAlert("请至少选择一种认证方式");
            return false;
        }
        // 密码认证时密码必填
        if (usePasswordCheckBox.isSelected() && passwordField.getText().trim().isEmpty()) {
            showAlert("请输入密码");
            return false;
        }
        // 密钥认证时至少添加一个密钥并勾选启用
        if (useKeyCheckBox.isSelected()) {
            boolean hasEnabledKey = false;
            for (KeyEntry entry : keyEntries) {
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
