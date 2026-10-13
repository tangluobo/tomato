package com.tangluobo.tomato;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.DatabaseLogoLoader;
import com.tangluobo.tomato.module.connect.ToolType;
import com.tangluobo.tomato.module.connect.dialog.ConnectionConfigDialog;
import com.tangluobo.tomato.module.tools.DatasetConverterPane;
import com.tangluobo.tomato.module.tools.ImageFormatConverterPane;
import com.tangluobo.tomato.module.tools.JsonToolPane;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TabPane;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.fxmisc.richtext.InlineCssTextArea;

/** Opt-in native UI regression checks; uses an isolated profile and never starts network services. */
public final class AotUiSelfCheck extends Application {
    @Override public void start(Stage stage) {
        Platform.runLater(() -> {
            try {
                if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
                    System.clearProperty("java.home");
                }
                for (ConnectType type : ConnectType.values()) checkIcon(type.getIconPath());
                for (ToolType type : ToolType.values()) checkIcon(type.getIconPath());
                show(stage, new VBox());
                checkNewConnectionClients(stage);
                var dataset = new DatasetConverterPane();
                show(stage, dataset);
                var images = new ImageFormatConverterPane();
                show(stage, images);
                var json = new JsonToolPane();
                show(stage, json);
                var input = json.lookupAll(".text-area").stream()
                        .filter(node -> node instanceof TextArea area && "请输入JSON数据".equals(area.getPromptText()))
                        .map(TextArea.class::cast).findFirst().orElseThrow();
                input.setText("{\"answer\":42}");
                var format = json.lookupAll(".button").stream()
                        .filter(node -> node instanceof Button button && "格式化".equals(button.getText()))
                        .map(Button.class::cast).findFirst().orElseThrow();
                format.fire();
                Platform.runLater(() -> {
                    try {
                        boolean formatted = json.lookupAll(".styled-text-area").stream()
                                .filter(InlineCssTextArea.class::isInstance).map(InlineCssTextArea.class::cast)
                                .anyMatch(area -> area.getText().contains("\"answer\"") && area.getText().contains("42"));
                        if (!formatted) throw new IllegalStateException("JSON format button produced no output");
                        System.out.println("AOT_UI_SELF_CHECK_PASS: icons, new connection clients and settings, dataset, images, JSON format button");
                        System.exit(0);
                    } catch (Throwable error) { fail(error); }
                });
            } catch (Throwable error) { fail(error); }
        });
    }

    private static void checkNewConnectionClients(Stage owner) {
        var dialog = new ConnectionConfigDialog(owner);
        Platform.runLater(() -> {
            Stage window = Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                    .filter(stage -> "新建连接".equals(stage.getTitle())).findFirst().orElseThrow();
            try {
                var root = window.getScene().getRoot();
                root.applyCss();
                var tabs = root.lookupAll(".tab-pane").stream().filter(TabPane.class::isInstance)
                        .map(TabPane.class::cast).filter(pane -> pane.getTabs().stream()
                                .anyMatch(tab -> "客户端".equals(tab.getText()))).findFirst().orElseThrow();
                var clients = tabs.getTabs().stream().filter(tab -> "客户端".equals(tab.getText())).findFirst().orElseThrow();
                tabs.getSelectionModel().select(clients);
                var tiles = (FlowPane) clients.getContent();
                long expected = java.util.Arrays.stream(ConnectType.values())
                        .filter(type -> type.getCategory() == ConnectType.Category.OTHERS)
                        .filter(type -> type != ConnectType.HTTP_SERVER && type != ConnectType.FTP_SERVER
                                && type != ConnectType.SMB_SERVER).count();
                if (tiles.getChildren().size() != expected) throw new IllegalStateException("Client tiles missing");
                for (var item : tiles.getChildren()) {
                    var tile = (VBox) item;
                    var image = tile.getChildren().stream().filter(ImageView.class::isInstance)
                            .map(ImageView.class::cast).findFirst().orElseThrow().getImage();
                    if (image == null || image.isError()) throw new IllegalStateException("Client icon missing");
                }
                for (ConnectType type : new ConnectType[]{ConnectType.SSH, ConnectType.RDP, ConnectType.ADB,
                        ConnectType.VNC, ConnectType.SPICE}) {
                    var tile = tiles.getChildren().stream().map(VBox.class::cast)
                            .filter(box -> box.getChildren().stream().anyMatch(node -> node instanceof Label label
                                    && type.getDisplayName().equals(label.getText()))).findFirst().orElseThrow();
                    tile.getOnMouseClicked().handle(new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                            javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                            false, false, false, false, false, true, null));
                    if (!window.getTitle().equals("新建" + type.getDisplayName() + "连接")) {
                        throw new IllegalStateException("Client settings failed: " + type);
                    }
                    root.applyCss();
                    root.lookupAll(".button").stream().filter(node -> node instanceof Button button
                            && "返回".equals(button.getText())).map(Button.class::cast).findFirst().orElseThrow().fire();
                }
                System.out.println("AOT_CLIENT_SELF_CHECK_PASS: " + expected + " client tiles, SSH/RDP/ADB/VNC/SPICE settings");
            } catch (Throwable error) { fail(error); }
            finally { window.close(); }
        });
        dialog.showAndWait();
    }

    private static void checkIcon(String path) {
        var icon = DatabaseLogoLoader.load(path, 32, 32);
        if (icon == null || icon.isError()) throw new IllegalStateException("Icon failed: " + path);
    }

    private static void show(Stage stage, VBox content) {
        stage.setScene(new Scene(content, 1000, 750));
        stage.show();
        content.applyCss();
        content.layout();
    }

    private static void fail(Throwable error) {
        System.err.println("AOT_UI_SELF_CHECK_FAIL");
        error.printStackTrace(System.err);
        System.exit(1);
    }
}
