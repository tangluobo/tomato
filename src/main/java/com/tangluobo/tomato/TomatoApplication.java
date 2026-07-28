package com.tangluobo.tomato;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;

public class TomatoApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(TomatoApplication.class.getResource("tomato-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1200, 900);
        scene.setFill(Color.WHITE);
        stage.initStyle(StageStyle.UNDECORATED);
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/images/logo.png")));
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        // 确保所有非守护线程（如JSch SSH连接线程）被清理，使JVM能正常退出
        Platform.exit();
        System.exit(0);
    }
}
