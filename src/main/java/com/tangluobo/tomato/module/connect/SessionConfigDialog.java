package com.tangluobo.tomato.module.connect;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 会话配置对话框（覆盖全局设置）
 */
public class SessionConfigDialog {

    public static void show(Stage parent, ConnectionConfig config) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(parent);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("会话配置 - " + config.getName());
        dialog.getDialogPane().setPrefWidth(380);

        // 内容
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        int row = 0;

        grid.add(new Label("回滚行数:"), 0, row);
        HBox scrollbackBox = new HBox(8);
        CheckBox useGlobal = new CheckBox("使用全局配置");
        useGlobal.setSelected(config.getScrollbackLines() == null);
        TextField scrollbackField = new TextField(
            config.getScrollbackLines() != null ? String.valueOf(config.getScrollbackLines()) : String.valueOf(GlobalConfig.getInstance().getScrollbackLines())
        );
        scrollbackField.setPrefWidth(80);
        scrollbackField.setDisable(useGlobal.isSelected());
        useGlobal.selectedProperty().addListener((obs, oldV, newV) -> {
            scrollbackField.setDisable(newV);
            if (!newV) scrollbackField.setText(String.valueOf(GlobalConfig.getInstance().getScrollbackLines()));
        });
        scrollbackBox.getChildren().addAll(scrollbackField, useGlobal);
        grid.add(scrollbackBox, 1, row);

        dialog.getDialogPane().setContent(grid);

        // 按钮
        ButtonType okBtn = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, cancelBtn);

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) {
                if (useGlobal.isSelected()) {
                    config.setScrollbackLines(null);
                } else {
                    try {
                        int val = Integer.parseInt(scrollbackField.getText().trim());
                        config.setScrollbackLines(Math.max(0, val));
                    } catch (NumberFormatException e) {
                        config.setScrollbackLines(null);
                    }
                }
                return true;
            }
            return false;
        });

        dialog.showAndWait();
    }
}
