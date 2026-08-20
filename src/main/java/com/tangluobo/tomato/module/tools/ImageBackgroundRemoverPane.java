package com.tangluobo.tomato.module.tools;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片背景透明化工具面板
 * 布局与 ImageFormatConverterPane 一致
 * 通过亮度阈值将白/灰背景像素转为透明，并保留彩色主体；
 * 边缘过渡区按亮度线性映射 alpha 以消除锯齿。
 */
public class ImageBackgroundRemoverPane extends VBox {

    private TextField sourcePathField;
    private TextField targetPathField;
    private Slider thresholdSlider;
    private Label thresholdValueLabel;
    private Label statusLabel;
    private Button convertButton;

    private List<File> sourceFiles = new ArrayList<>();
    private boolean isDirMode = true;

    // 支持 jpg、jpeg、png（png 也会重新去背输出）
    private static final String[] SUPPORTED_EXT = {".jpg", ".jpeg", ".png"};

    public ImageBackgroundRemoverPane() {
        initializeUI();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        // 自定义标题栏（#f7f8fa 与其它工具一致，便于 ToolPane.stripTitleBar 移除）
        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 20, 14, 20));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");
        SVGPath titleIcon = new SVGPath();
        // Material Icons: layers（层叠，代表透明图层）
        titleIcon.setContent("M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);
        Label titleLabel = new Label("图片背景透明化");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitleLabel = new Label("JPG/PNG → 透明PNG");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        titleBar.getChildren().addAll(titleIcon, titleLabel, titleSpacer, subtitleLabel);

        // 内容区域
        VBox contentBox = new VBox(20);
        contentBox.setPadding(new Insets(20, 25, 25, 25));
        contentBox.setFillWidth(true);
        contentBox.setMaxWidth(Double.MAX_VALUE);

        // 转换类型
        VBox typeBox = createSection("转换说明");
        HBox typeContent = new HBox(10);
        typeContent.setAlignment(Pos.CENTER_LEFT);
        typeContent.setMaxWidth(Double.MAX_VALUE);
        typeContent.setPadding(new Insets(10, 15, 10, 15));
        typeContent.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");
        Label typeLabel = new Label("亮度阈值法：≥阈值 → 透明；< 阈值-40 → 不透明；过渡区线性 alpha 抗锯齿");
        typeLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        typeLabel.setWrapText(true);
        typeContent.getChildren().add(typeLabel);
        typeBox.getChildren().add(typeContent);

        // 阈值滑块
        VBox thresholdBox = createSection("背景亮度阈值");
        HBox thresholdRow = new HBox(10);
        thresholdRow.setAlignment(Pos.CENTER_LEFT);
        thresholdRow.setMaxWidth(Double.MAX_VALUE);
        thresholdRow.setPadding(new Insets(10, 15, 10, 15));
        thresholdRow.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");
        thresholdSlider = new Slider(180, 254, 235);
        thresholdSlider.setShowTickLabels(true);
        thresholdSlider.setShowTickMarks(true);
        thresholdSlider.setMajorTickUnit(10);
        thresholdSlider.setMinorTickCount(1);
        thresholdSlider.setSnapToTicks(false);
        HBox.setHgrow(thresholdSlider, Priority.ALWAYS);
        thresholdValueLabel = new Label("235");
        thresholdValueLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #333; -fx-font-weight: bold;");
        thresholdValueLabel.setPrefWidth(30);
        thresholdValueLabel.setAlignment(Pos.CENTER_RIGHT);
        thresholdSlider.valueProperty().addListener((obs, o, n) ->
                thresholdValueLabel.setText(String.valueOf((int) n.doubleValue())));
        thresholdRow.getChildren().addAll(thresholdSlider, thresholdValueLabel);
        thresholdBox.getChildren().add(thresholdRow);

        // 源文件/目录
        VBox sourceBox = createSourceSection();
        sourcePathField = (TextField) ((HBox) sourceBox.getChildren().get(1)).getChildren().get(0);

        // 输出目录
        VBox targetBox = createDirectorySection("输出目录", "选择 PNG 输出目录");
        targetPathField = (TextField) ((HBox) targetBox.getChildren().get(1)).getChildren().get(0);

        // 转换按钮
        convertButton = new Button("开始转换");
        convertButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 30; -fx-background-radius: 4; -fx-cursor: hand;");
        convertButton.setOnAction(e -> startConversion());

        // 状态标签
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        statusLabel.setWrapText(true);

        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER_LEFT);
        buttonBox.setMaxWidth(Double.MAX_VALUE);
        buttonBox.getChildren().addAll(convertButton, statusLabel);

        contentBox.getChildren().addAll(typeBox, thresholdBox, sourceBox, targetBox, buttonBox);

        getChildren().addAll(titleBar, contentBox);
        VBox.setVgrow(contentBox, Priority.ALWAYS);
    }

    private VBox createSection(String title) {
        VBox box = new VBox(8);
        box.setFillWidth(true);
        box.setMaxWidth(Double.MAX_VALUE);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #333;");
        box.getChildren().add(titleLabel);
        return box;
    }

    private VBox createDirectorySection(String title, String placeholder) {
        VBox box = createSection(title);

        HBox pathRow = new HBox(8);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        pathRow.setMaxWidth(Double.MAX_VALUE);

        TextField pathField = new TextField();
        pathField.setPromptText(placeholder);
        pathField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button browseButton = new Button("浏览");
        browseButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        browseButton.setOnAction(e -> chooseOutputDir());

        pathRow.getChildren().addAll(pathField, browseButton);
        box.getChildren().add(pathRow);
        return box;
    }

    private VBox createSourceSection() {
        VBox box = createSection("选择源文件/目录");

        HBox pathRow = new HBox(8);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        pathRow.setMaxWidth(Double.MAX_VALUE);

        TextField pathField = new TextField();
        pathField.setPromptText("选择 JPG/JPEG/PNG 文件或目录");
        pathField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button fileBtn = new Button("选择文件");
        fileBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        fileBtn.setOnAction(e -> chooseSourceFile());

        Button browseButton = new Button("浏览");
        browseButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        browseButton.setOnAction(e -> chooseSourceDir());

        pathRow.getChildren().addAll(pathField, fileBtn, browseButton);
        box.getChildren().add(pathRow);
        return box;
    }

    // ===================== 交互逻辑 =====================

    private void chooseSourceFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择图片文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片文件", "*.jpg", "*.jpeg", "*.png"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            isDirMode = false;
            sourcePathField.setText(file.getAbsolutePath());
            sourceFiles.clear();
            sourceFiles.add(file);
        }
    }

    private void chooseSourceDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择图片目录");
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            isDirMode = true;
            sourcePathField.setText(dir.getAbsolutePath());
            sourceFiles.clear();
            try {
                Files.walk(dir.toPath())
                        .filter(p -> isSupported(p.toString()))
                        .forEach(p -> sourceFiles.add(p.toFile()));
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void chooseOutputDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择输出目录");
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            targetPathField.setText(dir.getAbsolutePath());
        }
    }

    private boolean isSupported(String name) {
        String lower = name.toLowerCase();
        for (String ext : SUPPORTED_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private void startConversion() {
        String sourcePath = sourcePathField.getText().trim();
        String targetPath = targetPathField.getText().trim();

        if (sourcePath.isEmpty()) {
            setStatus("请选择源文件或目录", true);
            return;
        }
        if (targetPath.isEmpty()) {
            setStatus("请选择输出目录", true);
            return;
        }

        // 目录模式：重新扫描
        if (isDirMode && sourceFiles.isEmpty()) {
            File sourceDir = new File(sourcePath);
            try {
                Files.walk(sourceDir.toPath())
                        .filter(p -> isSupported(p.toString()))
                        .forEach(p -> sourceFiles.add(p.toFile()));
            } catch (IOException e) {
                // ignore
            }
        }

        if (sourceFiles.isEmpty()) {
            setStatus("未找到支持的图片文件", true);
            return;
        }

        File targetDir = new File(targetPath);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            setStatus("输出目录创建失败", true);
            return;
        }

        final int threshold = (int) thresholdSlider.getValue();
        final int total = sourceFiles.size();

        convertButton.setDisable(true);
        setStatus("正在转换...", false, "#1976D2");

        new Thread(() -> {
            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < sourceFiles.size(); i++) {
                File srcFile = sourceFiles.get(i);
                String outName = srcFile.getName().replaceAll("(?i)\\.(jpg|jpeg|png)$", ".png");
                File outFile;
                if (isDirMode) {
                    String rel = getRelativePath(srcFile, new File(sourcePath))
                            .replaceAll("(?i)\\.(jpg|jpeg|png)$", ".png");
                    outFile = new File(targetPath, rel);
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        failCount++;
                        continue;
                    }
                } else {
                    outFile = new File(targetPath, outName);
                }
                try {
                    removeBackground(srcFile, outFile, threshold);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                }
            }

            int s = successCount;
            int f = failCount;
            Platform.runLater(() -> {
                convertButton.setDisable(false);
                if (f > 0) {
                    setStatus(String.format("转换完成，有失败项！总计: %d, 成功: %d, 失败: %d", total, s, f), true);
                } else {
                    setStatus(String.format("转换完成！总计: %d, 成功: %d", total, s), false, "#388E3C");
                }
            });
        }).start();
    }

    private void setStatus(String text, boolean error) {
        setStatus(text, error, error ? "#e53935" : "#666");
    }

    private void setStatus(String text, boolean error, String color) {
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + color + ";");
        statusLabel.setText(text);
    }

    private String getRelativePath(File file, File baseDir) {
        try {
            return baseDir.toPath().relativize(file.toPath()).toString();
        } catch (Exception e) {
            return file.getName();
        }
    }

    /**
     * 用亮度阈值法将白/灰背景转为透明，保留彩色主体。
     * lum >= threshold → 完全透明
     * lum < threshold - 40 → 完全不透明
     * 过渡区 (threshold-40..threshold) → 按 lum 线性映射 alpha，消除边缘锯齿
     */
    private void removeBackground(File srcFile, File outFile, int threshold) throws Exception {
        // 使用 ImageIO 读取（支持 JPG/PNG，且能拿到原始 RGB），避免 JavaFX PixelReader 不支持 JPEG
        BufferedImage src = ImageIO.read(srcFile);
        if (src == null) {
            throw new IOException("无法读取图片: " + srcFile.getName());
        }
        int w = src.getWidth();
        int h = src.getHeight();

        // 输出 ARGB 缓存
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int lowBound = threshold - 40;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                int outArgb;
                if (lum >= threshold) {
                    outArgb = 0x00000000; // 完全透明
                } else if (lum > lowBound) {
                    // 过渡区：alpha 从 255 线性降到 0
                    float a = 1f - (float) (lum - lowBound) / 40f;
                    int alpha = Math.max(0, Math.min(255, (int) (a * 255)));
                    outArgb = (alpha << 24) | (r << 16) | (g << 8) | b;
                } else {
                    outArgb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                }
                dst.setRGB(x, y, outArgb);
            }
        }

        if (!ImageIO.write(dst, "png", outFile)) {
            throw new IOException("无法写入 PNG: " + outFile.getName());
        }
    }
}
