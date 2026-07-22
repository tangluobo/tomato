package com.tangluobo.tomato;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.scene.Cursor;
import javafx.scene.control.TreeView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.Text;

import java.nio.charset.Charset;

public class TomatoController {
    @FXML
    private Label welcomeText;
    @FXML
    private HBox rootPane;
    @FXML
    private TreeView<String> treeView;
    @FXML
    private TextField searchField;
    @FXML
    private Label chatTitle;
    @FXML
    private VBox chatContent;
    @FXML
    private ScrollPane chatScrollPane;
    @FXML
    private TextField messageField;
    
    private double xOffset = 0;
    private double yOffset = 0;
    private double startWidth = 0;
    private double startHeight = 0;
    private double startX = 0;
    private double startY = 0;
    private double startWindowX = 0;
    private double startWindowY = 0;
    
    private boolean resizingLeft = false;
    private boolean resizingRight = false;
    private boolean resizingTop = false;
    private boolean resizingBottom = false;
    
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
        initTreeView();
        welcomeText.setText("已连接");
        System.out.println("连接按钮被点击");
    }

    @FXML
    protected void onSendMessage() {
        String message = messageField.getText().trim();
        if (!message.isEmpty()) {
            addMessage(message, true);
            messageField.clear();
        }
    }

    private void addMessage(String text, boolean isSelf) {
        HBox messageBox = new HBox();
        messageBox.setSpacing(10);
        
        if (isSelf) {
            messageBox.setStyle("-fx-alignment: CENTER-RIGHT;");
        } else {
            messageBox.setStyle("-fx-alignment: CENTER-LEFT;");
        }
        
        Text messageText = new Text(text);
        messageText.setStyle(isSelf 
            ? "-fx-fill: white; -fx-font-size: 14px;" 
            : "-fx-fill: #333; -fx-font-size: 14px;");
        
        HBox bubble = new HBox(messageText);
        bubble.setPadding(new javafx.geometry.Insets(8, 12, 8, 12));
        bubble.setStyle(isSelf
            ? "-fx-background-color: #07c160; -fx-background-radius: 8px;"
            : "-fx-background-color: white; -fx-background-radius: 8px; -fx-border-color: #e5e5e5; -fx-border-width: 1px;");
        
        messageBox.getChildren().add(bubble);
        chatContent.getChildren().add(messageBox);
        
        javafx.application.Platform.runLater(() -> {
            chatScrollPane.setVvalue(1.0);
        });
    }

    private void initTreeView() {
        TreeItem<String> root = new TreeItem<>("设备列表");
        root.setExpanded(true);

        TreeItem<String> group1 = new TreeItem<>("设备组1");
        group1.getChildren().add(new TreeItem<>("设备A"));
        group1.getChildren().add(new TreeItem<>("设备B"));
        group1.getChildren().add(new TreeItem<>("设备C"));
        
        TreeItem<String> group2 = new TreeItem<>("设备组2");
        group2.getChildren().add(new TreeItem<>("设备D"));
        group2.getChildren().add(new TreeItem<>("设备E"));
        
        TreeItem<String> group3 = new TreeItem<>("设备组3");
        TreeItem<String> subgroup = new TreeItem<>("子设备组");
        subgroup.getChildren().add(new TreeItem<>("设备F"));
        subgroup.getChildren().add(new TreeItem<>("设备G"));
        group3.getChildren().add(subgroup);
        
        root.getChildren().add(group1);
        root.getChildren().add(group2);
        root.getChildren().add(group3);
        
        treeView.setRoot(root);
        treeView.setShowRoot(false);
        
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.isLeaf()) {
                chatTitle.setText(newVal.getValue());
                chatContent.getChildren().clear();
                addMessage("已选择: " + newVal.getValue(), false);
            }
        });
    }

    @FXML
    public void initialize() {
        rootPane.setOnMousePressed(this::onMousePressed);
        rootPane.setOnMouseDragged(this::onMouseDragged);
        rootPane.setOnMouseMoved(this::onMouseMoved);
        rootPane.setOnMouseExited(this::onMouseExited);
        rootPane.setOnMouseReleased(this::onMouseReleased);
        
        initTreeView();
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

        resizingLeft = sceneX <= EDGE_THRESHOLD;
        resizingRight = sceneX >= width - EDGE_THRESHOLD;
        resizingTop = sceneY <= EDGE_THRESHOLD;
        resizingBottom = sceneY >= height - EDGE_THRESHOLD;

        if (resizingLeft || resizingRight || resizingTop || resizingBottom) {
            startWidth = width;
            startHeight = height;
            startX = event.getScreenX();
            startY = event.getScreenY();
            startWindowX = stage.getX();
            startWindowY = stage.getY();
        } else {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        }
    }

    private void onMouseDragged(MouseEvent event) {
        Stage stage = (Stage) rootPane.getScene().getWindow();

        if (resizingLeft || resizingRight || resizingTop || resizingBottom) {
            double deltaX = event.getScreenX() - startX;
            double deltaY = event.getScreenY() - startY;

            double newWidth = startWidth;
            double newHeight = startHeight;
            double newX = startWindowX;
            double newY = startWindowY;

            if (resizingRight) {
                newWidth = startWidth + deltaX;
            } else if (resizingLeft) {
                newWidth = startWidth - deltaX;
                newX = startWindowX + deltaX;
            }

            if (resizingBottom) {
                newHeight = startHeight + deltaY;
            } else if (resizingTop) {
                newHeight = startHeight - deltaY;
                newY = startWindowY + deltaY;
            }

            if (newWidth >= 400) stage.setWidth(newWidth);
            if (newHeight >= 300) stage.setHeight(newHeight);
            if (resizingLeft) stage.setX(newX);
            if (resizingTop) stage.setY(newY);
        } else {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        }
    }

    private void onMouseReleased(MouseEvent event) {
        resizingLeft = false;
        resizingRight = false;
        resizingTop = false;
        resizingBottom = false;
    }
}