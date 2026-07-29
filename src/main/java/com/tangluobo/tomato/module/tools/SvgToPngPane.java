package com.tangluobo.tomato.module.tools;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SVG转PNG转换面板
 * 使用JavaFX Swing互操作性将SVG渲染为PNG
 * 支持选择单个文件或整个目录进行批量转换
 */
public class SvgToPngPane extends VBox {

    // 源路径选择
    private TextField sourceField;

    // 输出目录选择
    private TextField outputField;

    // 转换选项
    private TextField widthField;
    private TextField heightField;

    // 转换按钮
    private Button convertBtn;
    private Label statusLabel;

    // 进度
    private ProgressBar progressBar;
    private Label progressLabel;

    // 文件列表
    private ObservableList<String> fileData = FXCollections.observableArrayList();
    private ListView<String> fileListView;

    // 是否选择的是目录模式
    private boolean isDirMode = true;

    // 找到的SVG文件列表
    private List<File> svgFiles = new ArrayList<>();

    public SvgToPngPane() {
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
        Label titleIcon = new Label("🖼");
        titleIcon.setStyle("-fx-font-size: 18px;");
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

        // 源选择区域
        VBox sourceBox = createSection("选择源文件/目录");
        HBox sourceRow = new HBox(8);
        sourceRow.setAlignment(Pos.CENTER_LEFT);
        sourceRow.setMaxWidth(Double.MAX_VALUE);

        sourceField = new TextField();
        sourceField.setPromptText("请选择SVG文件或包含SVG文件的目录...");
        sourceField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        HBox.setHgrow(sourceField, Priority.ALWAYS);

        Button sourceFileBtn = new Button("选择文件");
        sourceFileBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        sourceFileBtn.setOnAction(e -> chooseSourceFile());

        Button sourceDirBtn = new Button("浏览");
        sourceDirBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        sourceDirBtn.setOnAction(e -> chooseSourceDir());

        sourceRow.getChildren().addAll(sourceField, sourceFileBtn, sourceDirBtn);
        sourceBox.getChildren().add(sourceRow);

        // 输出目录选择
        VBox outputBox = createDirectorySection("输出目录", "请选择PNG输出目录...");

        // 转换选项
        VBox optionsBox = createSection("转换选项");
        HBox optionsRow = new HBox(12);
        optionsRow.setAlignment(Pos.CENTER_LEFT);
        optionsRow.setMaxWidth(Double.MAX_VALUE);

        Label widthLabel = new Label("宽度:");
        widthLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        widthField = new TextField();
        widthField.setPromptText("自动");
        widthField.setPrefWidth(100);
        widthField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");

        Label heightLabel = new Label("高度:");
        heightLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        heightField = new TextField();
        heightField.setPromptText("自动");
        heightField.setPrefWidth(100);
        heightField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");

        optionsRow.getChildren().addAll(widthLabel, widthField, heightLabel, heightField);
        optionsBox.getChildren().add(optionsRow);

        // 文件列表
        VBox fileBox = createSection("待转换文件列表");
        fileListView = new ListView<>(fileData);
        fileListView.setStyle("-fx-font-size: 12px; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        fileListView.setPrefHeight(120);
        VBox.setVgrow(fileListView, Priority.ALWAYS);
        fileBox.getChildren().add(fileListView);
        VBox.setVgrow(fileBox, Priority.ALWAYS);

        // 进度区域
        progressBar = new ProgressBar();
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setProgress(0);
        progressBar.setVisible(false);

        progressLabel = new Label("");
        progressLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        // 转换按钮
        convertBtn = new Button("开始转换");
        convertBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 30; -fx-background-radius: 4; -fx-cursor: hand;");
        convertBtn.setOnAction(e -> startConvert());

        // 状态标签
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        statusLabel.setWrapText(true);

        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER_LEFT);
        buttonBox.setMaxWidth(Double.MAX_VALUE);
        buttonBox.getChildren().addAll(convertBtn, statusLabel);

        contentBox.getChildren().addAll(sourceBox, outputBox, optionsBox, fileBox, progressBar, progressLabel, buttonBox);

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

        outputField = new TextField();
        outputField.setPromptText(placeholder);
        outputField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        HBox.setHgrow(outputField, Priority.ALWAYS);

        Button browseButton = new Button("浏览");
        browseButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        browseButton.setOnAction(e -> chooseOutputDir());

        pathRow.getChildren().addAll(outputField, browseButton);
        box.getChildren().add(pathRow);
        return box;
    }

    private void chooseSourceFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择SVG文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG文件", "*.svg"));
        File file = chooser.showOpenDialog(getStage());
        if (file != null) {
            isDirMode = false;
            sourceField.setText(file.getAbsolutePath());
            svgFiles.clear();
            svgFiles.add(file);
            refreshFileList();
        }
    }

    private void chooseSourceDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择SVG文件目录");
        File dir = chooser.showDialog(getStage());
        if (dir != null) {
            isDirMode = true;
            sourceField.setText(dir.getAbsolutePath());
            svgFiles.clear();
            collectSvgFiles(dir);
            refreshFileList();
        }
    }

    private void chooseOutputDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择输出目录");
        File dir = chooser.showDialog(getStage());
        if (dir != null) {
            outputField.setText(dir.getAbsolutePath());
        }
    }

    private void collectSvgFiles(File dir) {
        try {
            Files.walk(dir.toPath())
                .filter(p -> p.toString().toLowerCase().endsWith(".svg"))
                .forEach(p -> svgFiles.add(p.toFile()));
        } catch (IOException e) {
            updateStatus("扫描目录失败: " + e.getMessage(), true);
        }
    }

    private void refreshFileList() {
        fileData.clear();
        for (File f : svgFiles) {
            fileData.add(f.getAbsolutePath());
        }
        if (svgFiles.isEmpty()) {
            updateStatus("未找到SVG文件", true);
        } else {
            updateStatus("找到 " + svgFiles.size() + " 个SVG文件", false);
        }
    }

    private void startConvert() {
        if (svgFiles.isEmpty()) {
            updateStatus("请先选择源文件或目录", true);
            return;
        }

        String outputPath = outputField.getText().trim();
        if (outputPath.isEmpty()) {
            if (isDirMode) {
                outputPath = sourceField.getText().trim();
            } else {
                outputPath = new File(sourceField.getText().trim()).getParent();
            }
        }

        File outputDir = new File(outputPath);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // 解析尺寸选项
        int w = 0;
        int h = 0;
        try {
            String wText = widthField.getText().trim();
            String hText = heightField.getText().trim();
            if (!wText.isEmpty()) w = Integer.parseInt(wText);
            if (!hText.isEmpty()) h = Integer.parseInt(hText);
        } catch (NumberFormatException e) {
            updateStatus("宽度/高度请输入有效数字", true);
            return;
        }

        final int width = w;
        final int height = h;

        convertBtn.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #1976D2;");
        statusLabel.setText("正在转换...");

        int total = svgFiles.size();
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger(0);

        new Thread(() -> {
            for (int i = 0; i < svgFiles.size(); i++) {
                File svgFile = svgFiles.get(i);
                String pngFileName = svgFile.getName().replaceAll("\\.svg$", ".png");
                File pngFile;

                if (isDirMode) {
                    // 保持相对目录结构
                    String sourceDir = sourceField.getText().trim();
                    String relativePath = getRelativePath(svgFile, new File(sourceDir));
                    pngFile = new File(outputDir, relativePath.replaceAll("\\.svg$", ".png"));
                    // 确保子目录存在
                    File parentDir = pngFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                } else {
                    pngFile = new File(outputDir, pngFileName);
                }

                try {
                    convertSvgToPng(svgFile, pngFile, width, height);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }

                int idx = i + 1;
                int s = successCount.get();
                int f = failCount.get();
                double progress = idx / (double) total;
                Platform.runLater(() -> {
                    progressBar.setProgress(progress);
                    progressLabel.setText(String.format("进度: %d/%d (成功: %d, 失败: %d)", idx, total, s, f));
                });
            }

            int finalSuccess = successCount.get();
            int finalFail = failCount.get();
            Platform.runLater(() -> {
                convertBtn.setDisable(false);
                if (finalFail > 0) {
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
                    statusLabel.setText(String.format("转换完成，有失败项！总计: %d, 成功: %d, 失败: %d", total, finalSuccess, finalFail));
                } else {
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #388E3C;");
                    statusLabel.setText(String.format("转换完成！总计: %d, 成功: %d", total, finalSuccess));
                }
                progressLabel.setText(String.format("进度: %d/%d (成功: %d, 失败: %d)", total, total, finalSuccess, finalFail));
            });
        }, "SVG-Convert").start();
    }

    private String getRelativePath(File file, File baseDir) {
        try {
            return baseDir.toPath().relativize(file.toPath()).toString();
        } catch (Exception e) {
            return file.getName();
        }
    }

    private void convertSvgToPng(File svgFile, File pngFile, int width, int height) throws Exception {
        PNGTranscoder transcoder = new PNGTranscoder();

        if (width > 0) {
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) width);
        }
        if (height > 0) {
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) height);
        }

        TranscoderInput input = new TranscoderInput(svgFile.toURI().toString());
        OutputStream os = new FileOutputStream(pngFile);
        TranscoderOutput output = new TranscoderOutput(os);

        try {
            transcoder.transcode(input, output);
        } finally {
            os.flush();
            os.close();
        }
    }

    private void updateStatus(String message, boolean isError) {
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + (isError ? "#e53935" : "#666") + ";");
        statusLabel.setText(message);
    }

    private Stage getStage() {
        return (Stage) getScene().getWindow();
    }
}
