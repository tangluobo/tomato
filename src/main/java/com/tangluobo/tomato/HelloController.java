package com.tangluobo.tomato;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

import java.nio.charset.Charset;

public class HelloController {
    @FXML
    private Label welcomeText;

    @FXML
    protected void onHelloButtonClick() {
        welcomeText.setText("Welcome to JavaFX Application!");
        Charset.availableCharsets().forEach((s, charset) -> {
            System.out.println(charset);
        });

        System.out.println("----------------");
        System.out.println("默认编码：" + Charset.defaultCharset());
    }
}
