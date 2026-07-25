package com.tangluobo.tomato.module.connect;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 全局配置对话框
 */
public class GlobalConfigDialog {

    public static void show(Stage parent) {
        GlobalConfig config = GlobalConfig.getInstance();

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(parent);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("全局配置");
        dialog.getDialogPane().setPrefWidth(380);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        int row = 0;

        grid.add(new Label("回滚行数:"), 0, row);
        TextField scrollbackField = new TextField(String.valueOf(config.getScrollbackLines()));
        scrollbackField.setPrefWidth(80);
        grid.add(scrollbackField, 1, row);
        grid.add(new Label("(0=无限制)"), 2, row);

        dialog.getDialogPane().setContent(grid);

        ButtonType okBtn = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, cancelBtn);

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) {
                try {
                    int val = Integer.parseInt(scrollbackField.getText().trim());
                    config.setScrollbackLines(Math.max(0, val));
                    config.save();
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
                return true;
            }
            return false;
        });

        dialog.showAndWait();
    }
}
