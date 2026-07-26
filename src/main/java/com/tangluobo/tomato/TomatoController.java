package com.tangluobo.tomato;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.settings.SettingsModule;
import com.tangluobo.tomato.module.tools.ToolsModule;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.Cursor;
import javafx.stage.Stage;

import java.nio.charset.Charset;

public class TomatoController {
    @FXML
    private HBox rootPane;
    @FXML
    private VBox navPane;
    @FXML
    private VBox sidebarPane;
    @FXML
    private Region divider2;
    @FXML
    private VBox contentPane;
    @FXML
    private Label chatTitle;
    @FXML
    private VBox chatContent;
    @FXML
    private ScrollPane chatScrollPane;
    @FXML
    private Button minimizeBtn;
    @FXML
    private Button maximizeBtn;
    @FXML
    private Button closeBtn;

    private double xOffset = 0;
    private double yOffset = 0;
    private double startWidth = 0;
    private double startHeight = 0;
    private double startX = 0;
    private double startY = 0;
    private double startWindowX = 0;
    private double startWindowY = 0;

    private double dividerStartX = 0;
    private double dividerStartWidth = 0;

    private boolean resizingLeft = false;
    private boolean resizingRight = false;
    private boolean resizingTop = false;
    private boolean resizingBottom = false;
    private boolean resizingDivider2 = false;

    private static final int EDGE_THRESHOLD = 10;

    @FXML
    protected void onHelloButtonClick() {
        Charset.availableCharsets().forEach((s, charset) -> {
            System.out.println(charset);
        });

        System.out.println("----------------");
        System.out.println("默认编码：" + Charset.defaultCharset());
    }

    @FXML
    protected void onModuleClick(javafx.event.ActionEvent event) {
        Button source = (Button) event.getSource();
        String moduleId = (String) source.getUserData();

        com.tangluobo.tomato.module.Module module = null;
        switch (moduleId) {
            case "connect":
                module = new ConnectModule();
                break;
            case "tools":
                module = new ToolsModule();
                break;
            case "settings":
                module = new SettingsModule();
                break;
        }

        if (module != null) {
            loadModule(module);
        }
    }

    private void loadModule(Module module) {
        chatTitle.setText(module.getName());
        
        sidebarPane.getChildren().clear();
        module.loadSidebar(sidebarPane);

        if (sidebarPane.getChildren().isEmpty()) {
            sidebarPane.setVisible(false);
            sidebarPane.setManaged(false);
            divider2.setVisible(false);
            divider2.setManaged(false);
        } else {
            sidebarPane.setVisible(true);
            sidebarPane.setManaged(true);
            divider2.setVisible(true);
            divider2.setManaged(true);
        }

        chatContent.getChildren().clear();
        module.loadContent(chatContent);
    }

    @FXML
    protected void onMinimize() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        stage.setIconified(true);
    }

    @FXML
    protected void onMaximize() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        stage.setMaximized(!stage.isMaximized());
    }

    @FXML
    protected void onClose() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        stage.close();
    }

    @FXML
    public void initialize() {
        rootPane.setOnMousePressed(this::onMousePressed);
        rootPane.setOnMouseDragged(this::onMouseDragged);
        rootPane.setOnMouseMoved(this::onMouseMoved);
        rootPane.setOnMouseExited(this::onMouseExited);
        rootPane.setOnMouseReleased(this::onMouseReleased);

        divider2.setViewOrder(-1);
        divider2.setMouseTransparent(false);

        setupDivider(divider2);

        rootPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.windowProperty().addListener((o, oldWindow, newWindow) -> {
                    if (newWindow != null) {
                        Stage stage = (Stage) newWindow;
                        stage.maximizedProperty().addListener((prop, oldVal, newVal) -> {
                            if (newVal) {
                                rootPane.setStyle("-fx-border-color: transparent; -fx-border-width: 0;");
                            } else {
                                rootPane.setStyle("-fx-border-color: #D9D9D7; -fx-border-width: 1px;");
                            }
                        });
                    }
                });
            }
        });

        loadModule(new ConnectModule());
    }

    private void setupDivider(Region divider) {
        // 初始化时锁定sidebar最小宽度，防止被内容区压缩
        sidebarPane.setMinWidth(sidebarPane.getPrefWidth());

        divider.setOnMouseEntered(e -> divider.setCursor(Cursor.H_RESIZE));
        divider.setOnMouseExited(e -> divider.setCursor(Cursor.DEFAULT));

        divider.setOnMousePressed(e -> {
            dividerStartX = e.getScreenX();
            dividerStartWidth = sidebarPane.getWidth();
            resizingDivider2 = true;
        });

        divider.setOnMouseDragged(e -> {
            double deltaX = e.getScreenX() - dividerStartX;
            double newWidth = dividerStartWidth + deltaX;
            if (newWidth >= 60 && newWidth <= 500) {
                sidebarPane.setPrefWidth(newWidth);
                sidebarPane.setMinWidth(newWidth);
            }
        });

        divider.setOnMouseReleased(e -> {
            resizingDivider2 = false;
        });
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

        if (resizingDivider2) {
            return;
        }

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