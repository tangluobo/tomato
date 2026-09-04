package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.Optional;

/** 从工具栏触发 AI SQL 时使用的自然语言需求输入框。 */
public final class AiSqlPromptDialog {
    private AiSqlPromptDialog() {
    }

    public static Optional<String> show(Node owner, String initialPrompt) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("AI 生成 SQL");
        dialog.setHeaderText("描述你想查询或修改的数据");
        dialog.getDialogPane().setPrefWidth(560);

        TextArea promptArea = new TextArea(initialPrompt == null ? "" : initialPrompt);
        promptArea.setPromptText("例如：查询学校标识为 10 的学生信息，按创建时间倒序");
        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(4);
        Label hint = new Label("也可以在编辑器中输入“-- ai: 需求”，将光标放在该行后按 Ctrl+I。\n生成结果只写入编辑器，不会自动执行。");
        hint.setStyle("-fx-text-fill: #777; -fx-font-size: 11px;");
        VBox content = new VBox(10, promptArea, hint);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);

        ButtonType generateButton = new ButtonType("生成", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(generateButton, cancelButton);
        Node generateNode = dialog.getDialogPane().lookupButton(generateButton);
        generateNode.disableProperty().bind(Bindings.createBooleanBinding(
                () -> promptArea.getText().isBlank(), promptArea.textProperty()));
        dialog.setResultConverter(button -> button == generateButton ? promptArea.getText().trim() : null);
        DialogPositionUtil.centerOnOwner(dialog, owner);
        dialog.setOnShown(e -> {
            promptArea.requestFocus();
            promptArea.positionCaret(promptArea.getLength());
        });
        return dialog.showAndWait();
    }
}
