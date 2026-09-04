package com.tangluobo.tomato.module.connect.view;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 将行选择器和数据表分开布局，使行选择器不参与横向滚动。
 *
 * <p>数据表仍保留一个宽度为 0 的逻辑选择器列，以保持现有的列索引、整行选择和
 * 复制/粘贴语义；真正可见的选择器由左侧的小型 TableView 渲染。两个 TableView
 * 共享数据并同步垂直滚动，数据表自己的水平、垂直滚动条因此始终留在可视区域内。</p>
 */
final class FrozenRowSelectorPane<S> extends HBox {

    private final TableView<S> dataTable;
    private final TableView<S> rowSelectorTable = new TableView<>();
    private final Region horizontalBarSpacer = new Region();

    private ScrollBar dataVerticalBar;
    private ScrollBar selectorVerticalBar;
    private ScrollBar dataHorizontalBar;
    private boolean syncingVerticalScroll;

    private final ChangeListener<Number> dataVerticalListener =
            (observable, oldValue, newValue) -> syncVertical(dataVerticalBar, selectorVerticalBar);
    private final ChangeListener<Number> selectorVerticalListener =
            (observable, oldValue, newValue) -> syncVertical(selectorVerticalBar, dataVerticalBar);
    private final ChangeListener<Boolean> horizontalVisibilityListener =
            (observable, oldValue, newValue) -> updateHorizontalBarSpacer();
    private final ChangeListener<Number> horizontalHeightListener =
            (observable, oldValue, newValue) -> updateHorizontalBarSpacer();

    FrozenRowSelectorPane(TableView<S> dataTable, double selectorWidth) {
        this.dataTable = dataTable;

        rowSelectorTable.getStyleClass().add("frozen-row-selector-table");
        rowSelectorTable.setFocusTraversable(false);
        rowSelectorTable.setEditable(false);
        rowSelectorTable.setMinWidth(selectorWidth);
        rowSelectorTable.setPrefWidth(selectorWidth);
        rowSelectorTable.setMaxWidth(selectorWidth);
        rowSelectorTable.itemsProperty().bind(dataTable.itemsProperty());
        rowSelectorTable.fixedCellSizeProperty().bind(dataTable.fixedCellSizeProperty());
        rowSelectorTable.styleProperty().bind(dataTable.styleProperty());
        rowSelectorTable.disableProperty().bind(dataTable.disableProperty());

        VBox selectorArea = new VBox(rowSelectorTable, horizontalBarSpacer);
        selectorArea.setMinWidth(selectorWidth);
        selectorArea.setPrefWidth(selectorWidth);
        selectorArea.setMaxWidth(selectorWidth);
        VBox.setVgrow(rowSelectorTable, Priority.ALWAYS);

        horizontalBarSpacer.getStyleClass().add("frozen-row-selector-scrollbar-spacer");
        setSpacerHeight(0);

        getChildren().addAll(selectorArea, dataTable);
        HBox.setHgrow(dataTable, Priority.ALWAYS);
        setMinSize(0, 0);
        dataTable.setMinSize(0, 0);

        dataTable.skinProperty().addListener((observable, oldSkin, newSkin) -> scheduleScrollBarLink());
        rowSelectorTable.skinProperty().addListener((observable, oldSkin, newSkin) -> scheduleScrollBarLink());
        sceneProperty().addListener((observable, oldScene, newScene) -> scheduleScrollBarLink());
        scheduleScrollBarLink();
    }

    /**
     * 安装可见的行选择器列，并在数据表首位放入零宽度逻辑列。
     */
    <T> void setRowSelectorColumn(TableColumn<S, T> selectorColumn) {
        selectorColumn.setResizable(false);
        if (!selectorColumn.getStyleClass().contains("row-selector-col")) {
            selectorColumn.getStyleClass().add("row-selector-col");
        }
        rowSelectorTable.getColumns().clear();
        rowSelectorTable.getColumns().add(selectorColumn);

        TableColumn<S, Object> logicalSelectorColumn = new TableColumn<>();
        logicalSelectorColumn.setUserData(selectorColumn.getUserData());
        logicalSelectorColumn.setSortable(false);
        logicalSelectorColumn.setReorderable(false);
        logicalSelectorColumn.setResizable(false);
        logicalSelectorColumn.setMinWidth(0);
        logicalSelectorColumn.setPrefWidth(0);
        logicalSelectorColumn.setMaxWidth(0);
        dataTable.getColumns().add(0, logicalSelectorColumn);
    }

    private void scheduleScrollBarLink() {
        Platform.runLater(this::linkScrollBars);
    }

    private void linkScrollBars() {
        ScrollBar newDataVerticalBar = findScrollBar(dataTable, Orientation.VERTICAL);
        ScrollBar newSelectorVerticalBar = findScrollBar(rowSelectorTable, Orientation.VERTICAL);
        ScrollBar newDataHorizontalBar = findScrollBar(dataTable, Orientation.HORIZONTAL);

        if (dataVerticalBar != newDataVerticalBar || selectorVerticalBar != newSelectorVerticalBar) {
            if (dataVerticalBar != null) {
                dataVerticalBar.valueProperty().removeListener(dataVerticalListener);
            }
            if (selectorVerticalBar != null) {
                selectorVerticalBar.valueProperty().removeListener(selectorVerticalListener);
            }
            dataVerticalBar = newDataVerticalBar;
            selectorVerticalBar = newSelectorVerticalBar;
            if (dataVerticalBar != null && selectorVerticalBar != null) {
                dataVerticalBar.valueProperty().addListener(dataVerticalListener);
                selectorVerticalBar.valueProperty().addListener(selectorVerticalListener);
                syncVertical(dataVerticalBar, selectorVerticalBar);
            }
        }

        if (dataHorizontalBar != newDataHorizontalBar) {
            if (dataHorizontalBar != null) {
                dataHorizontalBar.visibleProperty().removeListener(horizontalVisibilityListener);
                dataHorizontalBar.heightProperty().removeListener(horizontalHeightListener);
            }
            dataHorizontalBar = newDataHorizontalBar;
            if (dataHorizontalBar != null) {
                dataHorizontalBar.visibleProperty().addListener(horizontalVisibilityListener);
                dataHorizontalBar.heightProperty().addListener(horizontalHeightListener);
            }
        }
        updateHorizontalBarSpacer();
    }

    private ScrollBar findScrollBar(TableView<?> table, Orientation orientation) {
        for (javafx.scene.Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar scrollBar && scrollBar.getOrientation() == orientation) {
                return scrollBar;
            }
        }
        return null;
    }

    private void syncVertical(ScrollBar source, ScrollBar target) {
        if (syncingVerticalScroll || source == null || target == null) return;
        syncingVerticalScroll = true;
        try {
            double sourceRange = source.getMax() - source.getMin();
            double fraction = sourceRange <= 0
                    ? 0
                    : (source.getValue() - source.getMin()) / sourceRange;
            double targetValue = target.getMin() + fraction * (target.getMax() - target.getMin());
            target.setValue(targetValue);
        } finally {
            syncingVerticalScroll = false;
        }
    }

    private void updateHorizontalBarSpacer() {
        double height = 0;
        if (dataHorizontalBar != null && dataHorizontalBar.isVisible()) {
            height = Math.max(dataHorizontalBar.getHeight(), dataHorizontalBar.prefHeight(-1));
        }
        setSpacerHeight(height);
    }

    private void setSpacerHeight(double height) {
        horizontalBarSpacer.setMinHeight(height);
        horizontalBarSpacer.setPrefHeight(height);
        horizontalBarSpacer.setMaxHeight(height);
        horizontalBarSpacer.setVisible(height > 0);
        horizontalBarSpacer.setManaged(height > 0);
    }
}
