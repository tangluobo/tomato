package com.tangluobo.tomato.module.connect.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;

/**
 * 颜色转置游戏面板（列栈模型，像真实容器从顶部取放珠子）：
 * 设置维度 n，n 个容器（列）排成一行，每个容量 n。
 * 初始：前 n-1 个容器各装满 n 个珠子 [0,1,...,n-1]（底到顶），第 n 个容器空（缓冲）。
 *       这样每个水平层（行）同色：第 i 层全是颜色 i（"每行颜色一样"）。
 * 珠子总数 n*(n-1)，空一个容器（"空一列"）。
 * 目标：每个容器内珠子同色（"相同列的颜色相同"，即转置：行同色→列同色）。
 * 规则：只能从容器顶部取一个珠子，放到另一容器顶部（"从上面取放"，天然同方向、不间隔取）。
 */
public class ColorTransposeGamePane extends VBox {

    private int n = 3;
    /** 每列是一个栈：索引 0=底部，size-1=顶部。 */
    private List<List<Integer>> columns;
    private int moveCount = 0;

    /** 当前选中（取珠）的列索引，-1 表示未选中。 */
    private int selectedCol = -1;

    /** 撤销栈，每项 {fromCol, toCol}。 */
    private final List<int[]> history = new ArrayList<>();

    private TextField dimField;
    private Label statusLabel;
    private HBox boardBox;
    private HBox goalPreview;

    private static final int MAX_N = 12;

    public ColorTransposeGamePane() {
        setSpacing(12);
        setPadding(new Insets(15));
        setStyle("-fx-background-color: #ffffff;");

        // ===== 控制栏 =====
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER);
        controls.getChildren().add(new Label("维度 n："));

        dimField = new TextField("3");
        dimField.setPrefWidth(60);
        controls.getChildren().add(dimField);

        Button startBtn = new Button("开始");
        startBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4; -fx-background-radius: 4;");
        startBtn.setOnAction(e -> startGame());
        controls.getChildren().add(startBtn);

        Button undoBtn = new Button("撤销");
        undoBtn.setStyle("-fx-border-radius: 4; -fx-background-radius: 4;");
        undoBtn.setOnAction(e -> undo());
        controls.getChildren().add(undoBtn);

        Button resetBtn = new Button("重置");
        resetBtn.setStyle("-fx-border-radius: 4; -fx-background-radius: 4;");
        resetBtn.setOnAction(e -> startGame());
        controls.getChildren().add(resetBtn);

        getChildren().add(controls);

        // ===== 目标预览 =====
        HBox goalBox = new HBox(8);
        goalBox.setAlignment(Pos.CENTER);
        Label goalLabel = new Label("目标（每个容器同色）：");
        goalLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
        goalBox.getChildren().add(goalLabel);
        goalPreview = new HBox(6);
        goalPreview.setAlignment(Pos.CENTER);
        goalBox.getChildren().add(goalPreview);
        getChildren().add(goalBox);

        // ===== 棋盘：容器水平排列，每个容器垂直堆叠珠子 =====
        boardBox = new HBox(10);
        boardBox.setAlignment(Pos.CENTER);
        // 不拉伸子节点高度，让柱子保持与维度一致的精确高度，并实现垂直居中
        boardBox.setFillHeight(false);
        VBox.setVgrow(boardBox, Priority.ALWAYS);
        getChildren().add(boardBox);

        // ===== 状态栏 =====
        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setAlignment(Pos.CENTER);
        getChildren().add(statusLabel);

        startGame();
    }

    /** 根据颜色索引生成稳定且可区分的颜色。 */
    private Color colorFor(int idx) {
        return Color.hsb((idx * 360.0 / Math.max(n, 1)) % 360, 0.62, 0.92);
    }

    private void startGame() {
        int parsed;
        try {
            parsed = Integer.parseInt(dimField.getText().trim());
        } catch (Exception e) {
            parsed = n;
        }
        if (parsed < 2) parsed = 2;
        if (parsed > MAX_N) parsed = MAX_N;
        n = parsed;
        dimField.setText(String.valueOf(n));

        // n 个容器，每个容量 n。前 n-1 个各装满 [0,1,...,n-1]（底到顶），第 n 个空。
        // 珠子共 n*(n-1) 个，每色 n-1 个；第 i 层（行）全是颜色 i（行同色）。
        columns = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            List<Integer> col = new ArrayList<>();
            if (c < n - 1) {
                for (int i = 0; i < n; i++) col.add(i); // 底(0)到顶(n-1)
            }
            columns.add(col);
        }

        moveCount = 0;
        selectedCol = -1;
        history.clear();

        renderGoal();
        renderBoard();
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        statusLabel.setText("规则：点击一个容器取其顶部珠子，再点击另一容器放至顶部。让每个容器内同色即胜利。");
    }

    /** 目标预览：每个容器最终应同色，颜色依次为 0,1,...,n-1。 */
    private void renderGoal() {
        goalPreview.getChildren().clear();
        for (int i = 0; i < n; i++) {
            Circle c = new Circle(11, colorFor(i));
            c.setStroke(Color.WHITE);
            c.setStrokeWidth(1.2);
            goalPreview.getChildren().add(c);
        }
    }

    private void renderBoard() {
        boardBox.getChildren().clear();
        if (columns == null) return;

        for (int c = 0; c < columns.size(); c++) {
            VBox tube = new VBox(4);
            tube.setAlignment(Pos.BOTTOM_CENTER);
            tube.setPrefWidth(44);
            // 柱高 = n 个珠子高度：每珠 28px + spacing 4px（末珠无 spacing）
            tube.setPrefHeight(n * 32 - 4 + 8); // 8 = padding(4*2)
            tube.setStyle("-fx-background-color: #fafafa; -fx-background-radius: 8; -fx-border-color: #eeeeee; -fx-border-radius: 8; -fx-padding: 4;");
            tube.setUserData(c);
            int finalC = c;
            tube.setOnMouseClicked(e -> onColumnClick(finalC));

            List<Integer> col = columns.get(c);
            // VBox 从上到下排列：先顶部空位，再从栈顶到栈底的珠子（栈底在最下方）
            int empty = n - col.size();
            for (int i = 0; i < empty; i++) {
                Circle slot = new Circle(14, Color.TRANSPARENT);
                slot.setStroke(Color.web("#e0e0e0"));
                slot.setStrokeWidth(1);
                slot.getStrokeDashArray().addAll(2d, 3d);
                tube.getChildren().add(slot);
            }
            // 从栈顶到栈底（倒序），栈顶在最上、栈底在最下
            for (int i = col.size() - 1; i >= 0; i--) {
                int color = col.get(i);
                Circle bead = new Circle(14, colorFor(color));
                bead.setStroke(Color.WHITE);
                bead.setStrokeWidth(1.5);
                // 高亮选中容器的顶部珠子
                if (c == selectedCol && i == col.size() - 1) {
                    bead.setStroke(Color.web("#f1c40f"));
                    bead.setStrokeWidth(3);
                    bead.setTranslateY(-6);
                }
                tube.getChildren().add(bead);
            }
            boardBox.getChildren().add(tube);
        }
    }

    private void onColumnClick(int c) {
        if (columns == null) return;
        List<Integer> col = columns.get(c);

        if (selectedCol == -1) {
            // 取珠：该容器必须有珠子
            if (col.isEmpty()) {
                statusLabel.setText("该容器为空，无可取珠子。");
                return;
            }
            selectedCol = c;
            renderBoard();
            statusLabel.setText("已取第 " + (c + 1) + " 个容器顶部珠子，点击另一容器放置。");
        } else {
            // 同一容器：取消
            if (c == selectedCol) {
                selectedCol = -1;
                renderBoard();
                statusLabel.setText("已取消选择。");
                return;
            }
            // 放置：目标容器不能满
            if (col.size() >= n) {
                statusLabel.setText("该容器已满（容量 " + n + "），无法放入。");
                return;
            }
            int from = selectedCol, to = c;
            int color = columns.get(from).remove(columns.get(from).size() - 1); // 取源顶
            columns.get(to).add(color); // 放到目标顶
            history.add(new int[]{from, to});
            moveCount++;
            selectedCol = -1;
            renderBoard();
            if (isWin()) {
                statusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #07c160; -fx-font-weight: bold;");
                statusLabel.setText("胜利！共用 " + moveCount + " 步完成转置。点击「重置」再玩一次。");
            } else {
                statusLabel.setText("已移动 " + moveCount + " 步。");
            }
        }
    }

    private void undo() {
        if (history.isEmpty() || columns == null) {
            statusLabel.setText("无可撤销的操作。");
            return;
        }
        int[] last = history.remove(history.size() - 1);
        // 反向：把 to 顶部珠子还回 from 顶部
        int color = columns.get(last[1]).remove(columns.get(last[1]).size() - 1);
        columns.get(last[0]).add(color);
        moveCount = Math.max(0, moveCount - 1);
        selectedCol = -1;
        renderBoard();
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        statusLabel.setText("已撤销，当前 " + moveCount + " 步。");
    }

    /**
     * 胜利判定：每个非空容器内珠子全部同色。
     * 总珠子数 n*(n-1)，每色 n-1 个，目标每个容器装 n-1 个同色珠子，共 n 个容器。
     */
    private boolean isWin() {
        if (columns == null) return false;
        for (List<Integer> col : columns) {
            if (col.isEmpty()) continue;
            int first = col.get(0);
            for (int v : col) {
                if (v != first) return false;
            }
        }
        return true;
    }
}
