package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.GlobalConfig;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class GlobalConfigDialog {

    public enum ConfigMode {
        ALL,
        SSH,
        TABLE
    }

    public static void show(Stage parent) {
        show(parent, ConfigMode.ALL);
    }

    public static void show(Stage parent, ConfigMode mode) {
        GlobalConfig config = GlobalConfig.getInstance();

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(parent);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(getTitle(mode));
        dialog.getDialogPane().setPrefWidth(mode == ConfigMode.TABLE ? 450 : 400);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        int row = 0;

        boolean showScrollback = (mode == ConfigMode.ALL || mode == ConfigMode.SSH);
        boolean showTableFont = (mode == ConfigMode.ALL || mode == ConfigMode.TABLE);

        TextField scrollbackField;
        if (showScrollback) {
            grid.add(new Label("回滚行数:"), 0, row);
            scrollbackField = new TextField(String.valueOf(config.getScrollbackLines()));
            scrollbackField.setPrefWidth(80);
            grid.add(scrollbackField, 1, row);
            grid.add(new Label("(0=无限制)"), 2, row);
            row++;
        } else {
            scrollbackField = null;
        }

        ComboBox<String> fontNameBox;
        Spinner<Integer> fontSizeSpinner;
        if (showTableFont) {
            grid.add(new Label("表格字体:"), 0, row);
            fontNameBox = new ComboBox<>();
            fontNameBox.getItems().addAll(
                "Sans Serif", "Serif", "Monospace", "Dialog", "DialogInput",
                "Arial", "Times New Roman", "Courier New", "Microsoft YaHei",
                "SimHei", "SimSun", "KaiTi", "FangSong"
            );
            fontNameBox.setValue(config.getTableFontName());
            fontNameBox.setPrefWidth(150);
            grid.add(fontNameBox, 1, row);

            fontSizeSpinner = new Spinner<>(8, 24, config.getTableFontSize());
            fontSizeSpinner.setPrefWidth(60);
            grid.add(fontSizeSpinner, 2, row);
            grid.add(new Label("px"), 3, row);
        } else {
            fontSizeSpinner = null;
            fontNameBox = null;
        }

        dialog.getDialogPane().setContent(grid);

        ButtonType okBtn = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, cancelBtn);

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) {
                if (showScrollback && scrollbackField != null) {
                    try {
                        int val = Integer.parseInt(scrollbackField.getText().trim());
                        config.setScrollbackLines(Math.max(0, val));
                    } catch (NumberFormatException e) {
                    }
                }
                if (showTableFont && fontNameBox != null && fontSizeSpinner != null) {
                    config.setTableFontName(fontNameBox.getValue());
                    config.setTableFontSize(fontSizeSpinner.getValue());
                }
                config.save();
                return true;
            }
            return false;
        });

        dialog.showAndWait();
    }

    private static String getTitle(ConfigMode mode) {
        return switch (mode) {
            case ALL -> "全局配置";
            case SSH -> "终端配置";
            case TABLE -> "表格配置";
        };
    }
}
