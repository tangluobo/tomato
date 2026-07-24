package com.tangluobo.tomato.module;

import javafx.scene.layout.VBox;

public interface Module {
    String getName();

    void loadSidebar(VBox sidebarContainer);

    void loadContent(VBox contentArea);
}