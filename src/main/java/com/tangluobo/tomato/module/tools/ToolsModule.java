package com.tangluobo.tomato.module.tools;

import com.tangluobo.tomato.module.Module;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.geometry.Insets;

public class ToolsModule implements Module {
    @Override
    public String getName() {
        return "工具";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
    }

    @Override
    public void loadContent(VBox contentArea) {
        contentArea.getChildren().clear();

        Label title = new Label("工具中心");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        VBox tools = new VBox(15);
        tools.setPadding(new Insets(20, 0, 0, 0));

        Button logBtn = new Button("启动日志查看器");
        logBtn.setStyle("-fx-background-color: #1890ff; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 200px;");

        Button monitorBtn = new Button("启动性能监控");
        monitorBtn.setStyle("-fx-background-color: #1890ff; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 200px;");

        Button exportBtn = new Button("导出数据");
        exportBtn.setStyle("-fx-background-color: #1890ff; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 200px;");

        tools.getChildren().addAll(logBtn, monitorBtn, exportBtn);
        contentArea.getChildren().addAll(title, tools);
    }
}