package com.tangluobo.tomato;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.settings.SettingsModule;
import com.tangluobo.tomato.module.tools.ToolsModule;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private HBox titleBar;
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
    @FXML
    private ImageView logoView;
    @FXML
    private ToggleButton sidebarToggleBtn;
    @FXML
    private ImageView sidebarToggleIcon;

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
    private boolean windowManagementActive = false;
    private boolean customMaximized = false;
    private boolean draggingFromMaximized = false;

    private double savedX = 0;
    private double savedY = 0;
    private double savedWidth = 0;
    private double savedHeight = 0;

    private double dragStartX = 0;
    private double dragStartY = 0;

    private static final int EDGE_THRESHOLD = 10;
    private static final int MAXIMIZE_THRESHOLD = 5;

    // 模块缓存：保留每个模块的实例及其侧边栏子节点/内容容器，切换模块时复用，保留原有窗口状态
    private final Map<String, Module> moduleCache = new HashMap<>();
    private final Map<String, List<Node>> moduleSidebarChildrenCache = new HashMap<>();
    private final Map<String, String> moduleSidebarStyleCache = new HashMap<>();
    private final Map<String, VBox> moduleContentCache = new HashMap<>();
    private String currentModuleId = null;

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
        loadModule(moduleId);
    }

    private Module getOrCreateModule(String moduleId) {
        return moduleCache.computeIfAbsent(moduleId, id -> {
            switch (id) {
                case "connect":
                    return new ConnectModule();
                case "tools":
                    return new ToolsModule();
                case "settings":
                    return new SettingsModule();
                default:
                    return null;
            }
        });
    }

    private void loadModule(String moduleId) {
        if (moduleId.equals(currentModuleId)) {
            return;
        }

        Module module = getOrCreateModule(moduleId);
        if (module == null) {
            return;
        }

        chatTitle.setText(module.getName());

        // 移除当前模块的侧边栏子节点（保留节点到缓存，不销毁状态）
        sidebarPane.getChildren().clear();
        contentPane.getChildren().removeIf(n -> n != titleBar && n != chatScrollPane);

        // 隐藏 ScrollPane，直接使用模块内容容器占满右侧
        chatScrollPane.setManaged(false);
        chatScrollPane.setVisible(false);
        contentPane.setFillWidth(true);

        // 获取或创建该模块缓存的侧边栏子节点/内容容器
        List<Node> sidebarChildren = moduleSidebarChildrenCache.get(moduleId);
        VBox moduleContent = moduleContentCache.get(moduleId);

        if (sidebarChildren == null) {
            // 首次加载该模块：构建其 UI 并缓存
            sidebarPane.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e5e5; -fx-border-width: 0 1 0 0;");
            module.loadSidebar(sidebarPane);
            moduleSidebarChildrenCache.put(moduleId, new ArrayList<>(sidebarPane.getChildren()));
            moduleSidebarStyleCache.put(moduleId, sidebarPane.getStyle());

            moduleContent = new VBox();
            moduleContent.setStyle("-fx-background-color: #ffffff;");
            moduleContent.setFillWidth(true);
            moduleContent.setMaxWidth(Double.MAX_VALUE);
            moduleContent.setMaxHeight(Double.MAX_VALUE);
            VBox.setVgrow(moduleContent, Priority.ALWAYS);
            module.loadContent(moduleContent);
            moduleContentCache.put(moduleId, moduleContent);
        } else {
            // 恢复缓存的样式和子节点到 sidebarPane
            sidebarPane.setStyle(moduleSidebarStyleCache.getOrDefault(moduleId, "-fx-background-color: #ffffff; -fx-border-color: #e5e5e5; -fx-border-width: 0 1 0 0;"));
            sidebarPane.getChildren().addAll(sidebarChildren);
        }

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

        contentPane.getChildren().add(moduleContent);

        currentModuleId = moduleId;
    }

    @FXML
    protected void onMinimize() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        stage.setIconified(true);
    }

    @FXML
    protected void onSidebarToggle() {
        boolean show = sidebarToggleBtn.isSelected();
        navPane.setVisible(show);
        navPane.setManaged(show);
        if (show) {
            sidebarToggleBtn.setStyle("-fx-background-color: #e0e0e0; -fx-pref-width: 20px; -fx-pref-height: 20px; -fx-padding: 0 6px 0 6px; -fx-background-radius: 4px;");
        } else {
            sidebarToggleBtn.setStyle("-fx-background-color: transparent; -fx-pref-width: 20px; -fx-pref-height: 20px; -fx-padding: 0 6px 0 6px; -fx-background-radius: 4px;");
        }
    }

    @FXML
    protected void onMaximize() {
        Stage stage = (Stage) rootPane.getScene().getWindow();

        if (customMaximized) {
            restoreWindow(stage);
        } else {
            maximizeWindow(stage);
        }
    }

    private void maximizeWindow(Stage stage) {
        savedX = stage.getX();
        savedY = stage.getY();
        savedWidth = stage.getWidth();
        savedHeight = stage.getHeight();

        Screen screen = Screen.getPrimary();
        Rectangle2D visualBounds = screen.getVisualBounds();

        stage.setX(visualBounds.getMinX());
        stage.setY(visualBounds.getMinY());
        stage.setWidth(visualBounds.getWidth());
        stage.setHeight(visualBounds.getHeight());

        customMaximized = true;
        rootPane.setStyle("-fx-border-color: transparent; -fx-border-width: 0;");
    }

    private void restoreWindow(Stage stage) {
        stage.setX(savedX);
        stage.setY(savedY);
        stage.setWidth(savedWidth);
        stage.setHeight(savedHeight);
        customMaximized = false;
        rootPane.setStyle("-fx-border-color: #D9D9D7; -fx-border-width: 1px;");
    }

    @FXML
    protected void onClose() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        stage.close();
    }

    @FXML
    public void initialize() {
        Image logoImage = new Image(getClass().getResourceAsStream("/images/logo.png"));
        if (logoImage != null) {
            logoView.setImage(logoImage);
        }

        Image sideBarImage = new Image(getClass().getResourceAsStream("/images/side_bar.png"));
        if (sideBarImage != null) {
            sidebarToggleIcon.setImage(sideBarImage);
        }

        // 侧边栏开关默认关闭，隐藏最左侧导航栏
        navPane.setVisible(false);
        navPane.setManaged(false);

        divider2.setViewOrder(-1);
        divider2.setMouseTransparent(false);

        setupDivider(divider2);

        titleBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Stage stage = (Stage) titleBar.getScene().getWindow();
                if (customMaximized) {
                    restoreWindow(stage);
                } else {
                    maximizeWindow(stage);
                }
                event.consume();
            }
        });

        rootPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
                newScene.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
                newScene.addEventFilter(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
                newScene.addEventFilter(MouseEvent.MOUSE_EXITED, this::onMouseExited);
                newScene.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
            }
        });

        loadModule("connect");
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
        if (resizingDivider2) {
            return;
        }

        if (customMaximized) {
            rootPane.setCursor(Cursor.DEFAULT);
            return;
        }

        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        Stage stage = (Stage) rootPane.getScene().getWindow();
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
        if (resizingDivider2) {
            return;
        }

        if (event.getTarget() instanceof javafx.scene.control.TextInputControl ||
            event.getTarget() instanceof javafx.scene.control.ButtonBase ||
            event.getTarget() instanceof javafx.scene.control.ListCell) {
            windowManagementActive = false;
            return;
        }

        Stage stage = (Stage) rootPane.getScene().getWindow();

        if (customMaximized) {
            if (isInTitleBar(event)) {
                draggingFromMaximized = true;
                dragStartX = event.getScreenX();
                dragStartY = event.getScreenY();
                windowManagementActive = true;
                resizingLeft = false;
                resizingRight = false;
                resizingTop = false;
                resizingBottom = false;
                xOffset = dragStartX - savedX;
                yOffset = dragStartY - savedY;
            } else {
                windowManagementActive = false;
            }
            return;
        }

        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        double width = stage.getWidth();
        double height = stage.getHeight();

        resizingLeft = sceneX <= EDGE_THRESHOLD;
        resizingRight = sceneX >= width - EDGE_THRESHOLD;
        resizingTop = sceneY <= EDGE_THRESHOLD;
        resizingBottom = sceneY >= height - EDGE_THRESHOLD;

        if (resizingLeft || resizingRight || resizingTop || resizingBottom) {
            windowManagementActive = true;
            draggingFromMaximized = false;
            startWidth = width;
            startHeight = height;
            startX = event.getScreenX();
            startY = event.getScreenY();
            startWindowX = stage.getX();
            startWindowY = stage.getY();
        } else if (isInTitleBar(event)) {
            windowManagementActive = true;
            draggingFromMaximized = false;
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        } else {
            windowManagementActive = false;
        }
    }

    private boolean isInTitleBar(MouseEvent event) {
        if (titleBar == null) return false;
        Object target = event.getTarget();
        if (target == titleBar) return true;
        if (target instanceof Node) {
            Node node = (Node) target;
            while (node != null) {
                if (node == titleBar) return true;
                node = node.getParent();
            }
        }
        return false;
    }

    private void onMouseDragged(MouseEvent event) {
        if (resizingDivider2) {
            return;
        }

        if (!windowManagementActive) {
            return;
        }

        Stage stage = (Stage) rootPane.getScene().getWindow();

        if (draggingFromMaximized) {
            double currentY = event.getScreenY();
            if (currentY > MAXIMIZE_THRESHOLD) {
                restoreWindow(stage);
                double deltaX = event.getScreenX() - dragStartX;
                double deltaY = event.getScreenY() - dragStartY;
                stage.setX(savedX + deltaX);
                stage.setY(savedY + deltaY);
                draggingFromMaximized = false;
                windowManagementActive = false;
            }
            return;
        }

        if (customMaximized) {
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
            double newX = event.getScreenX() - xOffset;
            double newY = event.getScreenY() - yOffset;

            Screen screen = Screen.getPrimary();
            double screenTop = screen.getVisualBounds().getMinY();

            if (newY <= MAXIMIZE_THRESHOLD && newX >= screen.getVisualBounds().getMinX()
                && newX + stage.getWidth() <= screen.getVisualBounds().getMaxX()) {
                maximizeWindow(stage);
                windowManagementActive = false;
            } else {
                stage.setX(newX);
                stage.setY(newY);
            }
        }
    }

    private void onMouseReleased(MouseEvent event) {
        if (resizingDivider2) {
            return;
        }

        windowManagementActive = false;
        draggingFromMaximized = false;
        resizingLeft = false;
        resizingRight = false;
        resizingTop = false;
        resizingBottom = false;
        rootPane.setCursor(Cursor.DEFAULT);
    }
}