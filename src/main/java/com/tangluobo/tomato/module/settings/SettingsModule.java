package com.tangluobo.tomato.module.settings;

import com.tangluobo.tomato.module.Module;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.geometry.Insets;

public class SettingsModule implements Module {
    @Override
    public String getName() {
        return "设置";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
    }

    @Override
    public void loadContent(VBox contentArea) {
        contentArea.getChildren().clear();

        Label title = new Label("系统设置");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        VBox settings = new VBox(15);
        settings.setPadding(new Insets(20, 0, 0, 0));

        CheckBox autoStart = new CheckBox("开机自动启动");
        autoStart.setStyle("-fx-font-size: 14px;");

        CheckBox autoUpdate = new CheckBox("自动检查更新");
        autoUpdate.setStyle("-fx-font-size: 14px;");

        TextField themeField = new TextField();
        themeField.setPromptText("主题颜色");
        themeField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 8 12;");

        Button saveBtn = new Button("保存设置");
        saveBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 100px;");

        settings.getChildren().addAll(autoStart, autoUpdate, themeField, saveBtn);
        contentArea.getChildren().addAll(title, settings);
    }
}