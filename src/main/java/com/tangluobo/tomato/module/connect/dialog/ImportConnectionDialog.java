package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 导入前选择连接；重复配置仍可由用户手动勾选。 */
public class ImportConnectionDialog {

    private final Stage dialogStage;
    private final List<ConnectionConfig> connections;
    private final Set<ConnectionConfig> duplicates;
    private final CheckBoxTreeItem<String> rootItem = new CheckBoxTreeItem<>();
    private final Map<ConnectionConfig, CheckBoxTreeItem<String>> configItems = new IdentityHashMap<>();
    private boolean confirmed;

    public ImportConnectionDialog(
            Stage parent, List<ConnectionConfig> connections, Set<ConnectionConfig> duplicates) {
        this.connections = connections;
        this.duplicates = duplicates;
        this.dialogStage = createUi(parent);
        buildTree();
    }

    private Stage createUi(Stage parent) {
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(parent);
        stage.setTitle("导入连接");
        stage.setResizable(true);
        stage.setMinWidth(560);
        stage.setMinHeight(430);
        if (parent != null && !parent.getIcons().isEmpty()) {
            stage.getIcons().add(parent.getIcons().get(0));
        }

        Label title = new Label("选择要导入的项目：");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        Label hint = new Label("共 " + connections.size() + " 项，检测到 " + duplicates.size()
                + " 个重复项；重复项默认不勾选，仍可手动选择。");
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: #777777;");

        TreeView<String> tree = new TreeView<>(rootItem);
        tree.setShowRoot(false);
        tree.setFixedCellSize(30);
        tree.setCellFactory(CheckBoxTreeCell.forTreeView());
        tree.setStyle("-fx-background-color: white; -fx-border-color: #d9d9d9;");
        VBox.setVgrow(tree, Priority.ALWAYS);

        Button selectAll = new Button("全选");
        selectAll.setOnAction(event -> setAllSelected(true, false));
        Button selectNonDuplicates = new Button("仅选择未重复项");
        selectNonDuplicates.setOnAction(event -> setAllSelected(true, true));
        Button deselectAll = new Button("取消全选");
        deselectAll.setOnAction(event -> setAllSelected(false, false));
        HBox selectionButtons = new HBox(10, selectAll, selectNonDuplicates, deselectAll);
        selectionButtons.setAlignment(Pos.CENTER_LEFT);

        Button cancel = new Button("取消");
        cancel.setPrefWidth(80);
        cancel.setOnAction(event -> stage.close());
        Button importButton = new Button("导入");
        importButton.setPrefWidth(80);
        importButton.setStyle("-fx-background-color: #07c160; -fx-text-fill: white;");
        importButton.setOnAction(event -> {
            if (getSelectedConfigs().isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("请至少选择一个要导入的项目");
                DialogPositionUtil.centerOnOwner(alert, stage);
                alert.showAndWait();
                return;
            }
            confirmed = true;
            stage.close();
        });
        HBox actions = new HBox(10, cancel, importButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(8, title, hint, tree, selectionButtons, actions);
        content.setPadding(new Insets(15));
        content.setStyle("-fx-background-color: #f4f4f4;");
        stage.setScene(new Scene(content, 580, 500));
        DialogPositionUtil.centerOnOwner(stage, parent);
        return stage;
    }

    private void buildTree() {
        rootItem.setExpanded(true);
        Map<String, CheckBoxTreeItem<String>> itemById = new HashMap<>();
        for (ConnectionConfig config : connections) {
            boolean duplicate = duplicates.contains(config);
            String name = config.getName() == null || config.getName().isBlank() ? "未命名连接" : config.getName();
            CheckBoxTreeItem<String> item = new CheckBoxTreeItem<>(
                    duplicate ? name + "  （配置已存在）" : name,
                    iconFor(config));
            // 各项可独立选择，避免勾选目录时把默认未选的重复子项重新选中。
            item.setIndependent(true);
            item.setSelected(!duplicate);
            configItems.put(config, item);
            if (config.getId() != null) {
                itemById.putIfAbsent(config.getId(), item);
            }
        }

        for (ConnectionConfig config : connections) {
            CheckBoxTreeItem<String> item = configItems.get(config);
            CheckBoxTreeItem<String> parent = config.getParentId() == null
                    ? null : itemById.get(config.getParentId());
            (parent == null ? rootItem : parent).getChildren().add(item);
        }
        expandAll(rootItem);
    }

    private ImageView iconFor(ConnectionConfig config) {
        ImageView icon = new ImageView();
        icon.setFitWidth(16);
        icon.setFitHeight(16);
        String path = config.getType() == null
                ? "/images/connect/folder.png" : config.getType().getIconPath();
        try {
            icon.setImage(new Image(getClass().getResourceAsStream(path)));
        } catch (Exception ignored) {
        }
        return icon;
    }

    private void setAllSelected(boolean selected, boolean excludeDuplicates) {
        for (ConnectionConfig config : connections) {
            CheckBoxTreeItem<String> item = configItems.get(config);
            if (item != null) {
                item.setSelected(selected && (!excludeDuplicates || !duplicates.contains(config)));
            }
        }
    }

    private void expandAll(TreeItem<String> item) {
        item.setExpanded(true);
        for (TreeItem<String> child : item.getChildren()) {
            expandAll(child);
        }
    }

    public List<ConnectionConfig> getSelectedConfigs() {
        List<ConnectionConfig> selected = new ArrayList<>();
        for (ConnectionConfig config : connections) {
            CheckBoxTreeItem<String> item = configItems.get(config);
            if (item != null && item.isSelected()) {
                selected.add(config);
            }
        }
        return Collections.unmodifiableList(selected);
    }

    public boolean showAndWait() {
        dialogStage.showAndWait();
        return confirmed;
    }
}
