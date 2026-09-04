package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.GlobalConfig;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/** AI SQL 的 OpenAI 兼容接口配置。 */
public final class AiSqlConfigDialog {
    private AiSqlConfigDialog() {
    }

    public static boolean show(Node owner) {
        GlobalConfig config = GlobalConfig.getInstance();
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("AI SQL 设置");
        dialog.setHeaderText("配置 OpenAI 兼容的模型接口");
        dialog.getDialogPane().setPrefWidth(560);

        TextField baseUrlField = new TextField(config.getAiApiBaseUrl());
        baseUrlField.setPromptText("例如：https://api.openai.com/v1 或 http://localhost:11434/v1");
        baseUrlField.setPrefColumnCount(38);

        TextField modelField = new TextField(config.getAiModel());
        modelField.setPromptText("模型名称");

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setText(config.getAiApiKey());
        apiKeyField.setPromptText("本地无鉴权服务可以留空");

        CheckBox includeSchemaBox = new CheckBox("生成时发送当前库的表结构（不发送数据行）");
        includeSchemaBox.setSelected(config.isAiIncludeSchema());

        Spinner<Integer> timeoutSpinner = new Spinner<>(5, 300,
                config.getAiRequestTimeoutSeconds(), 5);
        timeoutSpinner.setEditable(true);
        timeoutSpinner.setPrefWidth(95);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(8, 8, 4, 8));
        grid.add(new Label("接口地址："), 0, 0);
        grid.add(baseUrlField, 1, 0, 2, 1);
        grid.add(new Label("模型："), 0, 1);
        grid.add(modelField, 1, 1, 2, 1);
        grid.add(new Label("API Key："), 0, 2);
        grid.add(apiKeyField, 1, 2, 2, 1);
        grid.add(new Label("超时："), 0, 3);
        grid.add(timeoutSpinner, 1, 3);
        grid.add(new Label("秒"), 2, 3);
        grid.add(includeSchemaBox, 1, 4, 2, 1);
        Label privacyHint = new Label("API Key 加密保存在本机；数据库密码和查询结果数据行不会发送。"
                + "用户主动选中的 SQL 会作为生成上下文发送。");
        privacyHint.setWrapText(true);
        privacyHint.setStyle("-fx-text-fill: #777; -fx-font-size: 11px;");
        grid.add(privacyHint, 1, 5, 2, 1);
        dialog.getDialogPane().setContent(grid);

        ButtonType saveButton = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, cancelButton);
        Node saveNode = dialog.getDialogPane().lookupButton(saveButton);
        saveNode.disableProperty().bind(Bindings.createBooleanBinding(
                () -> baseUrlField.getText().isBlank() || modelField.getText().isBlank(),
                baseUrlField.textProperty(), modelField.textProperty()));

        dialog.setResultConverter(button -> {
            if (button != saveButton) return false;
            config.setAiApiBaseUrl(baseUrlField.getText());
            config.setAiModel(modelField.getText());
            config.setAiApiKey(apiKeyField.getText());
            config.setAiIncludeSchema(includeSchemaBox.isSelected());
            int timeout = timeoutSpinner.getValue();
            try {
                timeout = Integer.parseInt(timeoutSpinner.getEditor().getText().trim());
            } catch (NumberFormatException ignored) {
            }
            config.setAiRequestTimeoutSeconds(timeout);
            config.save();
            return true;
        });
        DialogPositionUtil.centerOnOwner(dialog, owner);
        return dialog.showAndWait().orElse(false);
    }
}
