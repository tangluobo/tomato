package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.GlobalConfig;
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

        row++;
        // 默认文件视图模式（会话级覆盖）
        grid.add(new Label("默认文件视图:"), 0, row);
        HBox fileViewBox = new HBox(8);
        CheckBox fileViewUseGlobal = new CheckBox("使用全局配置");
        fileViewUseGlobal.setSelected(config.getDefaultFileViewMode() == null);
        ComboBox<String> fileViewCombo = new ComboBox<>();
        fileViewCombo.getItems().addAll("图标视图", "详细列表", "多列列表");
        String cfgViewMode = config.getDefaultFileViewMode();
        String globalViewMode = GlobalConfig.getInstance().getSshDefaultFileViewMode();
        String effective = cfgViewMode != null ? cfgViewMode : (globalViewMode != null ? globalViewMode : "LIST");
        fileViewCombo.setValue(switch (effective.toUpperCase()) {
            case "ICON" -> "图标视图";
            case "COLUMN" -> "多列列表";
            default -> "详细列表";
        });
        fileViewCombo.setDisable(fileViewUseGlobal.isSelected());
        fileViewUseGlobal.selectedProperty().addListener((obs, oldV, newV) -> {
            fileViewCombo.setDisable(newV);
            if (!newV) {
                String gvm = GlobalConfig.getInstance().getSshDefaultFileViewMode();
                String ev = gvm != null ? gvm : "LIST";
                fileViewCombo.setValue(switch (ev.toUpperCase()) {
                    case "ICON" -> "图标视图";
                    case "COLUMN" -> "多列列表";
                    default -> "详细列表";
                });
            }
        });
        fileViewBox.getChildren().addAll(fileViewCombo, fileViewUseGlobal);
        grid.add(fileViewBox, 1, row);

        // RDP全屏切换快捷键（会话级覆盖，仅RDP连接显示）
        boolean isRdp = config.getType() == com.tangluobo.tomato.module.connect.ConnectType.RDP;
        final TextField rdpShortcutField;
        final CheckBox rdpShortcutUseGlobal;
        final ComboBox<String> rdpOpenModeCombo;
        final CheckBox rdpOpenModeUseGlobal;
        if (isRdp) {
            row++;
            grid.add(new Label("打开方式:"), 0, row);
            HBox rdpOpenModeBox = new HBox(8);
            rdpOpenModeUseGlobal = new CheckBox("使用全局配置");
            String sessionOpenMode = config.getRdpOpenMode();
            boolean useGlobalOpenMode = sessionOpenMode == null || sessionOpenMode.isBlank();
            rdpOpenModeUseGlobal.setSelected(useGlobalOpenMode);
            String effectiveOpenMode = useGlobalOpenMode
                    ? GlobalConfig.getInstance().getRdpOpenMode() : sessionOpenMode;
            rdpOpenModeCombo = new ComboBox<>();
            rdpOpenModeCombo.getItems().addAll("独立窗口", "连接标签页");
            rdpOpenModeCombo.setValue("TAB".equalsIgnoreCase(effectiveOpenMode)
                    ? "连接标签页" : "独立窗口");
            rdpOpenModeCombo.setDisable(useGlobalOpenMode);
            rdpOpenModeUseGlobal.selectedProperty().addListener((obs, oldV, newV) -> {
                rdpOpenModeCombo.setDisable(newV);
                if (!newV) {
                    rdpOpenModeCombo.setValue("TAB".equalsIgnoreCase(GlobalConfig.getInstance().getRdpOpenMode())
                            ? "连接标签页" : "独立窗口");
                }
            });
            rdpOpenModeBox.getChildren().addAll(rdpOpenModeCombo, rdpOpenModeUseGlobal);
            grid.add(rdpOpenModeBox, 1, row);

            row++;
            grid.add(new Label("全屏快捷键:"), 0, row);
            HBox rdpShortcutBox = new HBox(8);
            rdpShortcutUseGlobal = new CheckBox("使用全局配置");
            String sessionShortcut = config.getRdpFullScreenShortcut();
            boolean useGlobalShortcut = sessionShortcut == null || sessionShortcut.isBlank();
            rdpShortcutUseGlobal.setSelected(useGlobalShortcut);
            String globalShortcut = GlobalConfig.getInstance().getRdpFullScreenShortcut();
            String effectiveShortcut = useGlobalShortcut
                    ? (globalShortcut != null && !globalShortcut.isBlank() ? globalShortcut : "Ctrl+Shift+Enter")
                    : sessionShortcut;
            rdpShortcutField = new TextField(effectiveShortcut);
            rdpShortcutField.setPrefWidth(160);
            rdpShortcutField.setPromptText("如 Ctrl+Alt+Enter");
            rdpShortcutField.setDisable(useGlobalShortcut);
            rdpShortcutUseGlobal.selectedProperty().addListener((obs, oldV, newV) -> {
                rdpShortcutField.setDisable(newV);
                if (!newV) {
                    String gs = GlobalConfig.getInstance().getRdpFullScreenShortcut();
                    rdpShortcutField.setText(gs != null && !gs.isBlank() ? gs : "Ctrl+Shift+Enter");
                }
            });
            rdpShortcutBox.getChildren().addAll(rdpShortcutField, rdpShortcutUseGlobal);
            grid.add(rdpShortcutBox, 1, row);
        } else {
            rdpShortcutField = null;
            rdpShortcutUseGlobal = null;
            rdpOpenModeCombo = null;
            rdpOpenModeUseGlobal = null;
        }

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
                if (fileViewUseGlobal.isSelected()) {
                    config.setDefaultFileViewMode(null);
                } else {
                    String vm = switch (fileViewCombo.getValue()) {
                        case "图标视图" -> "ICON";
                        case "多列列表" -> "COLUMN";
                        default -> "LIST";
                    };
                    config.setDefaultFileViewMode(vm);
                }
                if (isRdp) {
                    config.setRdpOpenMode(rdpOpenModeUseGlobal.isSelected() ? null
                            : ("连接标签页".equals(rdpOpenModeCombo.getValue()) ? "TAB" : "WINDOW"));
                    if (rdpShortcutUseGlobal.isSelected()) {
                        config.setRdpFullScreenShortcut(null);
                    } else {
                        String s = rdpShortcutField.getText().trim();
                        try {
                            javafx.scene.input.KeyCombination.valueOf(s);
                            config.setRdpFullScreenShortcut(s);
                        } catch (IllegalArgumentException e) {
                            new Alert(Alert.AlertType.WARNING, "无效的快捷键组合: " + s, ButtonType.OK).showAndWait();
                            return null; // 保持对话框打开以便修改
                        }
                    }
                }
                return true;
            }
            return false;
        });

        dialog.showAndWait();
    }
}
