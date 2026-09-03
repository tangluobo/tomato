package com.tangluobo.tomato.module.tools;

import com.tangluobo.tomato.module.Module;
import javafx.scene.layout.VBox;

public class DevelopmentModule implements Module {
    private final DevelopmentManagerPane managerPane = new DevelopmentManagerPane();

    @Override
    public String getName() {
        return "开发";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
        sidebarContainer.getChildren().clear();
        sidebarContainer.getChildren().add(managerPane.createSidebarPane());
        VBox.setVgrow(sidebarContainer.getChildren().get(0), javafx.scene.layout.Priority.ALWAYS);
    }

    @Override
    public void loadContent(VBox contentArea) {
        contentArea.getChildren().clear();
        contentArea.setFillWidth(true);
        contentArea.setMaxWidth(Double.MAX_VALUE);
        contentArea.setMaxHeight(Double.MAX_VALUE);
        contentArea.getChildren().add(managerPane);
        VBox.setVgrow(managerPane, javafx.scene.layout.Priority.ALWAYS);
    }
}
