package com.tangluobo.tomato;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.scene.Cursor;

import java.nio.charset.Charset;

public class TomatoController {
    @FXML
    private Label welcomeText;
    @FXML
    private HBox rootPane;
    
    private double xOffset = 0;
    private double yOffset = 0;
    private double startWidth = 0;
    private double startHeight = 0;
    private double startX = 0;
    private double startY = 0;
    
    private static final int EDGE_THRESHOLD = 10;

    @FXML
    protected void onHelloButtonClick() {
        welcomeText.setText("Welcome to JavaFX Application!");
        Charset.availableCharsets().forEach((s, charset) -> {
            System.out.println(charset);
        });

        System.out.println("----------------");
        System.out.println("默认编码：" + Charset.defaultCharset());
    }

    @FXML
    protected void onConnectClick() {
        welcomeText.setText("已连接");
        System.out.println("连接按钮被点击");
    }

    @FXML
    public void initialize() {
        rootPane.setOnMousePressed(this::onMousePressed);
        rootPane.setOnMouseDragged(this::onMouseDragged);
        rootPane.setOnMouseMoved(this::onMouseMoved);
        rootPane.setOnMouseExited(this::onMouseExited);
    }

    private void onMouseMoved(MouseEvent event) {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        double width = stage.getWidth();
        double height = stage.getHeight();

        Cursor cursor = determineCursor(sceneX, sceneY, width, height);
        rootPane.setCursor(cursor);
    }

    private void onMouseExited(MouseEvent event) {
        rootPane.setCursor(Cursor.DEFAULT);
    }

    private Cursor determineCursor(double x, double y, double width, double height) {
        boolean nearLeft = x <= EDGE_THRESHOLD;
        boolean nearRight = x >= width - EDGE_THRESHOLD;
        boolean nearTop = y <= EDGE_THRESHOLD;
        boolean nearBottom = y >= height - EDGE_THRESHOLD;

        if (nearLeft && nearTop) return Cursor.NW_RESIZE;
        if (nearRight && nearTop) return Cursor.NE_RESIZE;
        if (nearLeft && nearBottom) return Cursor.SW_RESIZE;
        if (nearRight && nearBottom) return Cursor.SE_RESIZE;
        if (nearLeft || nearRight) return Cursor.E_RESIZE;
        if (nearTop || nearBottom) return Cursor.N_RESIZE;
        
        return Cursor.DEFAULT;
    }

    private void onMousePressed(MouseEvent event) {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        double width = stage.getWidth();
        double height = stage.getHeight();

        boolean nearLeft = sceneX <= EDGE_THRESHOLD;
        boolean nearRight = sceneX >= width - EDGE_THRESHOLD;
        boolean nearTop = sceneY <= EDGE_THRESHOLD;
        boolean nearBottom = sceneY >= height - EDGE_THRESHOLD;

        if (nearLeft || nearRight || nearTop || nearBottom) {
            startWidth = width;
            startHeight = height;
            startX = event.getScreenX();
            startY = event.getScreenY();
        } else {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        }
    }

    private void onMouseDragged(MouseEvent event) {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        double width = stage.getWidth();
        double height = stage.getHeight();

        boolean nearLeft = sceneX <= EDGE_THRESHOLD;
        boolean nearRight = sceneX >= width - EDGE_THRESHOLD;
        boolean nearTop = sceneY <= EDGE_THRESHOLD;
        boolean nearBottom = sceneY >= height - EDGE_THRESHOLD;

        if (nearLeft || nearRight || nearTop || nearBottom) {
            double deltaX = event.getScreenX() - startX;
            double deltaY = event.getScreenY() - startY;

            double newWidth = startWidth;
            double newHeight = startHeight;
            double newX = stage.getX();
            double newY = stage.getY();

            if (nearRight) {
                newWidth = startWidth + deltaX;
            } else if (nearLeft) {
                newWidth = startWidth - deltaX;
                newX = startX + deltaX;
            }

            if (nearBottom) {
                newHeight = startHeight + deltaY;
            } else if (nearTop) {
                newHeight = startHeight - deltaY;
                newY = startY + deltaY;
            }

            if (newWidth >= 400) stage.setWidth(newWidth);
            if (newHeight >= 300) stage.setHeight(newHeight);
            if (nearLeft) stage.setX(newX);
            if (nearTop) stage.setY(newY);
        } else {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        }
    }
}