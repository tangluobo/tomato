package com.tangluobo.tomato.module.tools;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

/**
 * 图片格式置换工具主面板
 * 包含标签页，目前有"SVG转PNG"标签页
 */
public class ImageFormatConverterPane extends BorderPane {

    private TabPane tabPane;

    public ImageFormatConverterPane() {
        initializeUI();
    }

    private void initializeUI() {
        tabPane = new TabPane();
        tabPane.setStyle("-fx-font-size: 13px;");

        // SVG转PNG标签页
        Tab svgToPngTab = new Tab("SVG转PNG");
        svgToPngTab.setClosable(false);
        svgToPngTab.setContent(new SvgToPngPane());

        tabPane.getTabs().add(svgToPngTab);

        setCenter(tabPane);
    }
}
