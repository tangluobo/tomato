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
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片格式转换工具面板
 * 布局与 DatasetConverterPane 完全一致
 */
public class ImageFormatConverterPane extends VBox {

    private TextField sourcePathField;
    private TextField targetPathField;
    private Label statusLabel;
    private Button convertButton;

    private List<File> svgFiles = new ArrayList<>();
    private boolean isDirMode = true;

    public ImageFormatConverterPane() {
        initializeUI();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        // 自定义标题栏
        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 20, 14, 20));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");
        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);
        Label titleLabel = new Label("图片格式转换");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitleLabel = new Label("SVG → PNG");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        titleBar.getChildren().addAll(titleIcon, titleLabel, titleSpacer, subtitleLabel);

        // 内容区域（带padding）
        VBox contentBox = new VBox(20);
        contentBox.setPadding(new Insets(20, 25, 25, 25));
        contentBox.setFillWidth(true);
        contentBox.setMaxWidth(Double.MAX_VALUE);

        // 转换类型
        VBox typeBox = createSection("转换类型");
        HBox typeContent = new HBox(10);
        typeContent.setAlignment(Pos.CENTER_LEFT);
        typeContent.setMaxWidth(Double.MAX_VALUE);
        typeContent.setPadding(new Insets(10, 15, 10, 15));
        typeContent.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");
        Label typeLabel = new Label("SVG → PNG");
        typeLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #555;");
        typeContent.getChildren().add(typeLabel);
        typeBox.getChildren().add(typeContent);

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

        contentBox.getChildren().addAll(typeBox, sourceBox, targetBox, buttonBox);

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
        pathField.setPromptText("选择 SVG 文件或目录");
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
        chooser.setTitle("选择SVG文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG文件", "*.svg"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            isDirMode = false;
            sourcePathField.setText(file.getAbsolutePath());
            svgFiles.clear();
            svgFiles.add(file);
        }
    }

    private void chooseSourceDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择SVG文件目录");
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            isDirMode = true;
            sourcePathField.setText(dir.getAbsolutePath());
            svgFiles.clear();
            try {
                Files.walk(dir.toPath())
                        .filter(p -> p.toString().toLowerCase().endsWith(".svg"))
                        .forEach(p -> svgFiles.add(p.toFile()));
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

    private void startConversion() {
        String sourcePath = sourcePathField.getText().trim();
        String targetPath = targetPathField.getText().trim();

        if (sourcePath.isEmpty()) {
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
            statusLabel.setText("请选择源文件或目录");
            return;
        }
        if (targetPath.isEmpty()) {
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
            statusLabel.setText("请选择输出目录");
            return;
        }

        // 如果是目录模式，扫描文件
        if (isDirMode && svgFiles.isEmpty()) {
            File sourceDir = new File(sourcePath);
            try {
                Files.walk(sourceDir.toPath())
                        .filter(p -> p.toString().toLowerCase().endsWith(".svg"))
                        .forEach(p -> svgFiles.add(p.toFile()));
            } catch (IOException e) {
                // ignore
            }
        }

        if (svgFiles.isEmpty()) {
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
            statusLabel.setText("未找到 SVG 文件");
            return;
        }

        convertButton.setDisable(true);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #1976D2;");
        statusLabel.setText("正在转换...");

        final int total = svgFiles.size();

        new Thread(() -> {
            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < svgFiles.size(); i++) {
                File svgFile = svgFiles.get(i);
                String pngFileName = svgFile.getName().replaceAll("\\.svg$", ".png");
                File pngFile;

                if (isDirMode) {
                    String relativePath = getRelativePath(svgFile, new File(sourcePath));
                    pngFile = new File(targetPath, relativePath.replaceAll("\\.svg$", ".png"));
                    File parentDir = pngFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                } else {
                    pngFile = new File(targetPath, pngFileName);
                }

                try {
                    convertSvgToPng(svgFile, pngFile);
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
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
                    statusLabel.setText(String.format("转换完成，有失败项！总计: %d, 成功: %d, 失败: %d", total, s, f));
                } else {
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #388E3C;");
                    statusLabel.setText(String.format("转换完成！总计: %d, 成功: %d", total, s));
                }
            });
        }).start();
    }

    private String getRelativePath(File file, File baseDir) {
        try {
            return baseDir.toPath().relativize(file.toPath()).toString();
        } catch (Exception e) {
            return file.getName();
        }
    }

    private void convertSvgToPng(File svgFile, File pngFile) throws Exception {
        PNGTranscoder transcoder = new PNGTranscoder();
        TranscoderInput input = new TranscoderInput(svgFile.toURI().toString());
        try (OutputStream os = new FileOutputStream(pngFile)) {
            TranscoderOutput output = new TranscoderOutput(os);
            transcoder.transcode(input, output);
            os.flush();
        }
    }
}
