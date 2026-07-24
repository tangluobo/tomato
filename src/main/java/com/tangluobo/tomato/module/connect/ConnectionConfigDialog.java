package com.tangluobo.tomato.module.connect;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class ConnectionConfigDialog {
    private Stage dialogStage;
    private TextField nameField;
    private TextField hostField;
    private TextField portField;
    private TextField usernameField;
    private PasswordField passwordField;
    private TextField databaseField;
    private TextField descriptionField;
    private ConnectionConfig config;
    private boolean confirmed = false;

    public ConnectionConfigDialog(Stage parent, ConnectType type) {
        this(parent, type, null);
    }

    public ConnectionConfigDialog(Stage parent, ConnectType type, ConnectionConfig existingConfig) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle(existingConfig == null ? "新建连接" : "编辑连接");
        dialogStage.setResizable(false);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setMinWidth(400);

        Label title = new Label(existingConfig == null ? "新建连接" : "编辑连接");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;

        grid.add(new Label("名称："), 0, row);
        nameField = new TextField();
        nameField.setPromptText("连接名称");
        nameField.setPrefWidth(250);
        grid.add(nameField, 1, row++);

        grid.add(new Label("主机："), 0, row);
        hostField = new TextField();
        hostField.setPromptText("主机地址");
        hostField.setPrefWidth(250);
        grid.add(hostField, 1, row++);

        grid.add(new Label("端口："), 0, row);
        portField = new TextField();
        portField.setPrefWidth(100);
        int defaultPort = getDefaultPort(type);
        portField.setText(String.valueOf(defaultPort));
        grid.add(portField, 1, row++);

        grid.add(new Label("用户名："), 0, row);
        usernameField = new TextField();
        usernameField.setPromptText("用户名");
        usernameField.setPrefWidth(250);
        grid.add(usernameField, 1, row++);

        grid.add(new Label("密码："), 0, row);
        passwordField = new PasswordField();
        passwordField.setPromptText("密码");
        passwordField.setPrefWidth(250);
        grid.add(passwordField, 1, row++);

        if (type == ConnectType.MYSQL || type == ConnectType.POSTGRESQL) {
            grid.add(new Label("数据库："), 0, row);
            databaseField = new TextField();
            databaseField.setPromptText("数据库名称");
            databaseField.setPrefWidth(250);
            grid.add(databaseField, 1, row++);
        }

        grid.add(new Label("备注："), 0, row);
        descriptionField = new TextField();
        descriptionField.setPromptText("备注信息");
        descriptionField.setPrefWidth(250);
        grid.add(descriptionField, 1, row);

        if (existingConfig != null) {
            nameField.setText(existingConfig.getName());
            hostField.setText(existingConfig.getHost());
            portField.setText(String.valueOf(existingConfig.getPort()));
            usernameField.setText(existingConfig.getUsername());
            passwordField.setText(existingConfig.getPassword());
            if (databaseField != null) {
                databaseField.setText(existingConfig.getDatabase());
            }
            descriptionField.setText(existingConfig.getDescription());
        }

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        cancelBtn.setOnAction(e -> dialogStage.close());

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
                config.setType(type);
                config.setHost(hostField.getText().trim());
                config.setPort(Integer.parseInt(portField.getText().trim()));
                config.setUsername(usernameField.getText().trim());
                config.setPassword(passwordField.getText());
                if (databaseField != null) {
                    config.setDatabase(databaseField.getText().trim());
                }
                config.setDescription(descriptionField.getText().trim());
                confirmed = true;
                dialogStage.close();
            }
        });

        buttons.getChildren().addAll(cancelBtn, okBtn);
        root.getChildren().addAll(title, grid, buttons);

        Scene scene = new Scene(root);
        dialogStage.setScene(scene);
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