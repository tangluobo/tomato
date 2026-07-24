package com.tangluobo.tomato.module.connect;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class ConnectTypeDialog {
    private Stage dialogStage;
    private ListView<String> typeList;
    private ConnectType selectedType;
    private boolean confirmed = false;

    public ConnectTypeDialog(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("选择连接类型");
        dialogStage.setResizable(false);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setMinWidth(300);

        Label title = new Label("选择连接类型");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        typeList = new ListView<>();
        typeList.setPrefHeight(200);
        for (ConnectType type : ConnectType.values()) {
            typeList.getItems().add(type.getDisplayName());
        }

        typeList.setOnMouseClicked((MouseEvent event) -> {
            if (event.getClickCount() == 2) {
                int index = typeList.getSelectionModel().getSelectedIndex();
                if (index >= 0) {
                    selectedType = ConnectType.values()[index];
                    confirmed = true;
                    dialogStage.close();
                }
            }
        });

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        cancelBtn.setOnAction(e -> dialogStage.close());

        Button okBtn = new Button("确定");
        okBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        okBtn.setOnAction(e -> {
            int index = typeList.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                selectedType = ConnectType.values()[index];
                confirmed = true;
                dialogStage.close();
            }
        });

        buttons.getChildren().addAll(cancelBtn, okBtn);
        root.getChildren().addAll(title, typeList, buttons);

        Scene scene = new Scene(root);
        dialogStage.setScene(scene);
    }

    public ConnectType showAndWait() {
        dialogStage.showAndWait();
        return confirmed ? selectedType : null;
    }
}