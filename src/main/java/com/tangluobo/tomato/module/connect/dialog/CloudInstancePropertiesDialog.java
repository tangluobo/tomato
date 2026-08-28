package com.tangluobo.tomato.module.connect.dialog;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 云服务器实例只读属性窗口。 */
public final class CloudInstancePropertiesDialog {
    private CloudInstancePropertiesDialog() {}

    public static void show(String title, Map<String, Object> properties) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText("实例属性");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        GridPane grid = new GridPane();
        grid.setHgap(16); grid.setVgap(9); grid.setPadding(new Insets(12));
        int row = 0;
        for (String propertyKey : orderedKeys(properties)) {
            Object propertyValue = properties.get(propertyKey);
            String text = String.valueOf(propertyValue == null ? "" : propertyValue);
            Label key = new Label(displayName(propertyKey) + "：");
            key.setStyle("-fx-font-weight: bold; -fx-text-fill: #555;");
            Label value = new Label(text);
            value.setWrapText(true); value.setMaxWidth(Double.MAX_VALUE);
            value.setStyle("-fx-text-fill: #222; -fx-padding: 4 0 4 0;");
            HBox.setHgrow(value, Priority.ALWAYS);
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
            Button copy = copyButton(text);
            HBox valueBox = new HBox(8, value, spacer, copy);
            valueBox.setPrefWidth(390);
            grid.add(key, 0, row); grid.add(valueBox, 1, row++);
        }
        ScrollPane scroll = new ScrollPane(grid); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(430);
        dialog.getDialogPane().setContent(scroll); dialog.getDialogPane().setPrefWidth(560);
        dialog.showAndWait();
    }

    private static List<String> orderedKeys(Map<String, Object> properties) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        // IP 是最常用信息，固定置顶；其余常用属性按稳定顺序展示。
        keys.addAll(List.of("publicIp", "privateIp", "instanceName", "instanceId", "status",
                "region", "regionId", "zone", "instanceType", "cpu", "memory", "osName",
                "createdTime", "expiredTime"));
        keys.retainAll(properties.keySet());
        keys.addAll(properties.keySet());
        return new ArrayList<>(keys);
    }

    private static Button copyButton(String value) {
        Button button = new Button("⧉");
        button.setTooltip(new Tooltip("复制"));
        button.setFocusTraversable(false);
        button.setStyle("-fx-background-color: transparent; -fx-padding: 3 6; -fx-cursor: hand;");
        try {
            ImageView icon = new ImageView(new Image(CloudInstancePropertiesDialog.class
                    .getResourceAsStream("/images/connect/copy_tables.png")));
            icon.setFitWidth(14); icon.setFitHeight(14); button.setText(null); button.setGraphic(icon);
        } catch (Exception ignored) {}
        button.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent(); content.putString(value);
            Clipboard.getSystemClipboard().setContent(content);
        });
        return button;
    }

    private static String displayName(String key) {
        return switch (key) {
            case "instanceId" -> "实例 ID"; case "instanceName" -> "实例名称";
            case "region", "regionId" -> "地域"; case "zone" -> "可用区";
            case "status" -> "状态"; case "instanceType" -> "实例规格";
            case "cpu" -> "CPU"; case "memory" -> "内存";
            case "privateIp" -> "内网 IP"; case "publicIp" -> "公网 IP";
            case "osName" -> "操作系统"; case "createdTime" -> "创建时间";
            case "expiredTime" -> "到期时间"; default -> key;
        };
    }
}
