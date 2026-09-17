package com.tangluobo.tomato.utils;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 多屏环境下将弹窗（Alert/Dialog/Stage）定位到 owner 或最近活动窗口所在屏幕的中心，
 * 避免默认行为将其弹到主屏（Screen.getPrimary）。
 */
public final class DialogPositionUtil {
    private static final String TRACKED_WINDOW_KEY = DialogPositionUtil.class.getName() + ".trackedWindow";
    private static final String DIALOG_HANDLER_KEY = DialogPositionUtil.class.getName() + ".dialogHandler";
    private static final String OWNER_WINDOW_KEY = DialogPositionUtil.class.getName() + ".ownerWindow";
    private static final String STAGE_HANDLER_KEY = DialogPositionUtil.class.getName() + ".stageHandler";

    private static volatile Window mainWindow;
    private static WeakReference<Window> lastActiveWindow = new WeakReference<>(null);
    private static boolean automaticPositioningInstalled;

    private static final ListChangeListener<Window> WINDOW_LIST_LISTENER = change -> {
        while (change.next()) {
            if (!change.wasAdded()) {
                continue;
            }
            for (Window window : change.getAddedSubList()) {
                trackActiveWindow(window);
                if (shouldPositionAutomatically(window)) {
                    Window owner = resolveOwner(window);
                    // 先立即设置一次，尽量避免在主屏短暂闪现。
                    centerWindow(window, owner);
                    // Window.getWindows() 在窗口开始显示时才加入该窗口。延迟到下一轮，
                    // 确保 CSS 和布局已经给出了最终宽高，再修正到 owner 所在屏幕。
                    Platform.runLater(() -> {
                        if (window.isShowing()) {
                            centerWindow(window, owner);
                        }
                    });
                }
            }
        }
    };

    private DialogPositionUtil() {
    }

    /**
     * 按窗口与各屏幕的重叠面积，定位窗口当前所在屏幕。
     */
    public static Screen getScreenForWindow(Window window) {
        if (window == null) {
            return Screen.getPrimary();
        }

        List<Screen> screens = Screen.getScreens();
        List<Rectangle2D> screenBounds = screens.stream().map(Screen::getBounds).toList();
        Rectangle2D windowBounds = new Rectangle2D(
                window.getX(), window.getY(),
                Math.max(1, window.getWidth()), Math.max(1, window.getHeight()));
        int screenIndex = screenIndexForBounds(windowBounds, screenBounds);
        if (screenIndex >= 0) {
            return screens.get(screenIndex);
        }
        return Screen.getPrimary();
    }

    /**
     * 返回与窗口重叠面积最大的屏幕。窗口跨屏时，这比只判断中心点更符合用户当前所见。
     */
    static int screenIndexForBounds(Rectangle2D windowBounds, List<Rectangle2D> screenBounds) {
        if (windowBounds == null || screenBounds == null || screenBounds.isEmpty()) {
            return -1;
        }

        int bestIndex = -1;
        double bestArea = 0;
        for (int i = 0; i < screenBounds.size(); i++) {
            Rectangle2D screen = screenBounds.get(i);
            double overlapWidth = Math.max(0,
                    Math.min(windowBounds.getMaxX(), screen.getMaxX())
                            - Math.max(windowBounds.getMinX(), screen.getMinX()));
            double overlapHeight = Math.max(0,
                    Math.min(windowBounds.getMaxY(), screen.getMaxY())
                            - Math.max(windowBounds.getMinY(), screen.getMinY()));
            double area = overlapWidth * overlapHeight;
            if (area > bestArea) {
                bestArea = area;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    // ---------------- Dialog ----------------

    /** 将 Dialog 居中到 ownerNode 所在屏幕的中心。 */
    public static void centerOnOwner(Dialog<?> dlg, Node ownerNode) {
        if (dlg == null) {
            return;
        }
        if (ownerNode == null || ownerNode.getScene() == null || ownerNode.getScene().getWindow() == null) {
            centerOnOwner(dlg, (Window) null);
            return;
        }
        centerOnOwner(dlg, ownerNode.getScene().getWindow());
    }

    /**
     * 将 Dialog 居中到 owner 所在屏幕的中心。
     * 通过 initOwner 让 Dialog 默认先出现在 owner 附近（与 owner 同屏，不闪），
     * 再在 onShown 时把坐标修正为该屏幕 visualBounds 的中心。
     */
    public static void centerOnOwner(Dialog<?> dlg, Window owner) {
        if (dlg == null) {
            return;
        }
        if (owner != null) {
            try {
                dlg.initOwner(owner);
            } catch (IllegalStateException ignored) {
                // owner 已设置时忽略
            }
        }
        Window ownerRef = owner != null ? owner : resolveFallbackOwner(null);
        if (ownerRef != null) {
            dlg.getDialogPane().getProperties().put(OWNER_WINDOW_KEY, ownerRef);
        }
        if (!dlg.getDialogPane().getProperties().containsKey(DIALOG_HANDLER_KEY)) {
            dlg.getDialogPane().getProperties().put(DIALOG_HANDLER_KEY, Boolean.TRUE);
            dlg.addEventHandler(DialogEvent.DIALOG_SHOWN, e -> {
                Object storedOwner = dlg.getDialogPane().getProperties().get(OWNER_WINDOW_KEY);
                Window anchor = storedOwner instanceof Window ? (Window) storedOwner : resolveFallbackOwner(null);
                centerDialog(dlg, anchor);
            });
        }
    }

    // ---------------- Stage ----------------

    /** 将 Stage 居中到 ownerNode 所在屏幕的中心。 */
    public static void centerOnOwner(Stage stage, Node ownerNode) {
        if (stage == null) {
            return;
        }
        if (ownerNode == null || ownerNode.getScene() == null || ownerNode.getScene().getWindow() == null) {
            centerOnOwner(stage, (Window) null);
            return;
        }
        centerOnOwner(stage, ownerNode.getScene().getWindow());
    }

    /** 将 Stage 居中到 owner 所在屏幕的中心。 */
    public static void centerOnOwner(Stage stage, Window owner) {
        if (stage == null) {
            return;
        }
        if (owner != null) {
            try {
                stage.initOwner(owner);
            } catch (IllegalStateException ignored) {
                // owner 已设置时忽略
            }
        }
        Window ownerRef = owner != null ? owner : resolveFallbackOwner(stage);
        if (ownerRef != null) {
            stage.getProperties().put(OWNER_WINDOW_KEY, ownerRef);
        }
        if (!stage.getProperties().containsKey(STAGE_HANDLER_KEY)) {
            stage.getProperties().put(STAGE_HANDLER_KEY, Boolean.TRUE);
            stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> centerWindow(stage, resolveOwner(stage)));
        }
        if (stage.isShowing()) {
            centerWindow(stage, resolveOwner(stage));
        }
    }

    /**
     * 在 show 之前直接计算并设置位置，避免 Stage 闪到默认位置再跳回。
     * 适用于 Stage 已通过 setScene + sizeToScene() 设置好尺寸的场景。
     */
    public static void preLocateOnOwner(Stage stage, Window owner) {
        if (stage == null) {
            return;
        }
        if (owner != null) {
            try {
                stage.initOwner(owner);
            } catch (IllegalStateException ignored) {
                // owner 已设置时忽略
            }
        }
        Window ownerRef = owner != null ? owner : resolveFallbackOwner(stage);
        if (ownerRef != null) {
            stage.getProperties().put(OWNER_WINDOW_KEY, ownerRef);
        }
        centerWindow(stage, ownerRef);
    }

    /** 主窗口引用，由 TomatoApplication 在启动时设置。 */
    public static void setMainWindow(Window window) {
        mainWindow = window;
        if (window != null) {
            lastActiveWindow = new WeakReference<>(window);
            trackActiveWindow(window);
        }
        installAutomaticPositioning();
    }

    public static Window getMainWindow() {
        return mainWindow;
    }

    private static void installAutomaticPositioning() {
        if (automaticPositioningInstalled) {
            return;
        }
        automaticPositioningInstalled = true;
        Window.getWindows().addListener(WINDOW_LIST_LISTENER);
        for (Window window : Window.getWindows()) {
            trackActiveWindow(window);
        }
    }

    private static void trackActiveWindow(Window window) {
        if (window == null || window.getProperties().containsKey(TRACKED_WINDOW_KEY)) {
            return;
        }
        window.getProperties().put(TRACKED_WINDOW_KEY, Boolean.TRUE);
        window.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (isFocused && isRegularWindow(window)) {
                lastActiveWindow = new WeakReference<>(window);
            }
        });
    }

    private static boolean isRegularWindow(Window window) {
        return !(window instanceof Stage stage) || stage.getModality() == Modality.NONE;
    }

    private static boolean shouldPositionAutomatically(Window window) {
        if (!(window instanceof Stage stage) || window == mainWindow) {
            return false;
        }
        // Dialog/Alert 的内部窗口默认是 APPLICATION_MODAL；自定义弹窗通常是
        // WINDOW_MODAL。非模态工具窗口由调用方保留自己的定位策略。
        return stage.getOwner() != null || stage.getModality() != Modality.NONE;
    }

    private static Window resolveOwner(Window window) {
        if (window instanceof Stage stage) {
            Object configuredOwner = stage.getProperties().get(OWNER_WINDOW_KEY);
            if (configuredOwner instanceof Window owner && owner != window) {
                return owner;
            }
            if (stage.getOwner() != null && stage.getOwner() != window) {
                return stage.getOwner();
            }
        }
        return resolveFallbackOwner(window);
    }

    private static Window resolveFallbackOwner(Window windowToExclude) {
        Window active = lastActiveWindow.get();
        if (active != null && active != windowToExclude && active.isShowing()) {
            return active;
        }
        return mainWindow != windowToExclude ? mainWindow : null;
    }

    private static void centerDialog(Dialog<?> dialog, Window owner) {
        Screen screen = getScreenForWindow(owner);
        Rectangle2D bounds = screen.getVisualBounds();
        dialog.setX(centerCoordinate(bounds.getMinX(), bounds.getWidth(), dialog.getWidth()));
        dialog.setY(centerCoordinate(bounds.getMinY(), bounds.getHeight(), dialog.getHeight()));
    }

    private static void centerWindow(Window window, Window owner) {
        Screen screen = getScreenForWindow(owner);
        Rectangle2D bounds = screen.getVisualBounds();
        double width = preferredWidth(window);
        double height = preferredHeight(window);
        window.setX(centerCoordinate(bounds.getMinX(), bounds.getWidth(), width));
        window.setY(centerCoordinate(bounds.getMinY(), bounds.getHeight(), height));
    }

    private static double preferredWidth(Window window) {
        if (window.getWidth() > 0) {
            return window.getWidth();
        }
        return window.getScene() == null ? 0 : window.getScene().getRoot().prefWidth(-1);
    }

    private static double preferredHeight(Window window) {
        if (window.getHeight() > 0) {
            return window.getHeight();
        }
        return window.getScene() == null ? 0 : window.getScene().getRoot().prefHeight(-1);
    }

    private static double centerCoordinate(double screenStart, double screenSize, double windowSize) {
        if (!Double.isFinite(windowSize) || windowSize <= 0) {
            return screenStart + screenSize / 2.0;
        }
        // 弹窗大于当前屏幕工作区时，至少保证其左上角仍然可操作。
        return Math.max(screenStart, screenStart + (screenSize - windowSize) / 2.0);
    }
}
