package com.tangluobo.tomato.module.tools;

import com.tangluobo.tomato.module.Module;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具模块
 * 左侧sidebar为类似微信消息列表的工具列表
 * 右侧content为对应的工具界面
 */
public class ToolsModule implements Module {

    // 工具列表项数据
    private static class ToolItem {
        String id;
        String name;
        String description;
        Node icon;

        ToolItem(String id, String name, String description, Node icon) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.icon = icon;
        }
    }

    private List<ToolItem> toolItems = new ArrayList<>();
    private HBox currentSelectedBox = null;

    // 保存loadContent传入的contentArea引用
    private VBox contentArea;

    @Override
    public String getName() {
        return "工具";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
        sidebarContainer.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e5e5; -fx-border-width: 0 1 0 0;");

        // 搜索栏
        HBox searchBar = new HBox(8);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPadding(new Insets(12, 10, 8, 10));
        searchBar.setStyle("-fx-background-color: #f7f7f7; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        Label searchIcon = new Label("🔍");
        searchIcon.setStyle("-fx-font-size: 14px;");
        Label searchHint = new Label("搜索工具...");
        searchHint.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        searchBar.getChildren().addAll(searchIcon, searchHint);

        // 工具列表
        VBox toolList = new VBox(0);
        toolList.setPadding(new Insets(0));
        toolList.setStyle("-fx-background-color: #ffffff;");

        // 初始化工具项
        initToolItems();

        for (ToolItem item : toolItems) {
            VBox itemBox = createToolItemBox(item);
            toolList.getChildren().add(itemBox);
        }

        ScrollPane scrollPane = new ScrollPane(toolList);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        sidebarContainer.getChildren().addAll(searchBar, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    @Override
    public void loadContent(VBox contentArea) {
        // 保存引用，后续点击工具时使用
        this.contentArea = contentArea;
        contentArea.getChildren().clear();

        // 默认显示欢迎界面
        VBox welcomeBox = new VBox(20);
        welcomeBox.setAlignment(Pos.CENTER);
        welcomeBox.setPadding(new Insets(40));

        Label welcomeLabel = new Label("选择一个工具开始使用");
        welcomeLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: #999;");

        Label hintLabel = new Label("从左侧工具列表中选择需要的功能");
        hintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #bbb;");

        welcomeBox.getChildren().addAll(welcomeLabel, hintLabel);
        contentArea.getChildren().add(welcomeBox);
        VBox.setVgrow(welcomeBox, Priority.ALWAYS);
    }

    private void initToolItems() {
        toolItems.clear();

        // 图片格式置换工具
        Node imageConvertIcon = createImageConvertIcon();
        toolItems.add(new ToolItem("image_format_converter", "图片格式置换", "SVG转PNG等图片格式转换", imageConvertIcon));
    }

    private Node createImageConvertIcon() {
        Label iconLabel = new Label("🖼");
        iconLabel.setStyle("-fx-font-size: 22px;");
        return iconLabel;
    }

    private VBox createToolItemBox(ToolItem item) {
        VBox itemBox = new VBox(0);
        itemBox.setPadding(new Insets(0));

        // 每个列表项是微信风格的横向布局：图标 + 名称/描述
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-background-radius: 0;");

        // 左侧图标容器
        VBox iconContainer = new VBox();
        iconContainer.setAlignment(Pos.CENTER);
        iconContainer.setPrefSize(40, 40);
        iconContainer.setStyle("-fx-background-color: #e8f4ff; -fx-background-radius: 8;");
        iconContainer.setPadding(new Insets(6));

        if (item.icon instanceof Label labelIcon) {
            Label clonedIcon = new Label(labelIcon.getText());
            clonedIcon.setStyle(labelIcon.getStyle());
            iconContainer.getChildren().add(clonedIcon);
        } else {
            iconContainer.getChildren().add(item.icon);
        }

        // 右侧文字
        VBox textContainer = new VBox(2);
        Label nameLabel = new Label(item.name);
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Label descLabel = new Label(item.description);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        textContainer.getChildren().addAll(nameLabel, descLabel);

        row.getChildren().addAll(iconContainer, textContainer);

        // 底部分隔线
        Region separator = new Region();
        separator.setStyle("-fx-background-color: #f0f0f0; -fx-pref-height: 1px;");
        separator.setPrefHeight(1);

        itemBox.getChildren().addAll(row, separator);

        // 选中状态
        row.setOnMouseClicked(e -> {
            handleToolClick(item, row);
        });

        row.setOnMouseEntered(e -> {
            if (currentSelectedBox != row) {
                row.setStyle("-fx-background-color: #f5f5f5; -fx-cursor: hand; -fx-background-radius: 0;");
            }
        });

        row.setOnMouseExited(e -> {
            if (currentSelectedBox != row) {
                row.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-background-radius: 0;");
            }
        });

        return itemBox;
    }

    private void handleToolClick(ToolItem item, HBox row) {
        // 清除之前选中状态
        if (currentSelectedBox != null) {
            currentSelectedBox.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-background-radius: 0;");
        }
        // 设置当前选中
        currentSelectedBox = row;
        row.setStyle("-fx-background-color: #e8f4ff; -fx-cursor: hand; -fx-background-radius: 0;");

        // 使用保存的contentArea引用来更新内容
        if (contentArea == null) return;

        contentArea.getChildren().clear();

        switch (item.id) {
            case "image_format_converter":
                ImageFormatConverterPane converterPane = new ImageFormatConverterPane();
                contentArea.getChildren().add(converterPane);
                VBox.setVgrow(converterPane, Priority.ALWAYS);
                break;
            default:
                Label placeholder = new Label("工具开发中...");
                placeholder.setStyle("-fx-font-size: 16px; -fx-text-fill: #999;");
                contentArea.getChildren().add(placeholder);
                VBox.setVgrow(placeholder, Priority.ALWAYS);
                break;
        }
    }
}
