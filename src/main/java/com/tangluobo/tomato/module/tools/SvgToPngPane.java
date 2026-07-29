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
    private Button sourceFileBtn;
    private Button sourceDirBtn;

    // 输出目录选择
    private TextField outputField;
    private Button outputDirBtn;

    // 转换选项
    private TextField widthField;
    private TextField heightField;

    // 转换按钮
    private Button convertBtn;
    private Button clearBtn;

    // 进度和结果
    private ProgressBar progressBar;
    private Label progressLabel;
    private TextArea logArea;

    // 文件列表
    private ObservableList<String> fileData = FXCollections.observableArrayList();
    private ListView<String> fileListView;

    // 是否选择的是目录模式
    private boolean isDirMode = true;

    // 找到的SVG文件列表
    private List<File> svgFiles = new ArrayList<>();

    public SvgToPngPane() {
        setSpacing(12);
        setPadding(new Insets(15));
        setStyle("-fx-background-color: #ffffff;");

        initializeUI();
    }

    private void initializeUI() {
        // 源选择区域
        Label sourceTitle = new Label("选择源文件/目录");
        sourceTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        HBox sourceRow = new HBox(8);
        sourceRow.setAlignment(Pos.CENTER_LEFT);

        sourceField = new TextField();
        sourceField.setPromptText("请选择SVG文件或包含SVG文件的目录...");
        sourceField.setStyle("-fx-font-size: 12px;");
        sourceField.setPrefWidth(400);
        HBox.setHgrow(sourceField, Priority.ALWAYS);

        sourceFileBtn = new Button("选择文件");
        sourceFileBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 12; -fx-background-color: #1890ff; -fx-text-fill: white; -fx-background-radius: 4;");
        sourceFileBtn.setOnAction(e -> chooseSourceFile());

        sourceDirBtn = new Button("选择目录");
        sourceDirBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 12; -fx-background-color: #07c160; -fx-text-fill: white; -fx-background-radius: 4;");
        sourceDirBtn.setOnAction(e -> chooseSourceDir());

        sourceRow.getChildren().addAll(sourceField, sourceFileBtn, sourceDirBtn);

        VBox sourceBox = new VBox(6);
        sourceBox.getChildren().addAll(sourceTitle, sourceRow);

        // 输出目录选择
        Label outputTitle = new Label("输出目录");
        outputTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        HBox outputRow = new HBox(8);
        outputRow.setAlignment(Pos.CENTER_LEFT);

        outputField = new TextField();
        outputField.setPromptText("请选择PNG输出目录...");
        outputField.setStyle("-fx-font-size: 12px;");
        outputField.setPrefWidth(400);
        HBox.setHgrow(outputField, Priority.ALWAYS);

        outputDirBtn = new Button("选择目录");
        outputDirBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 12; -fx-background-color: #07c160; -fx-text-fill: white; -fx-background-radius: 4;");
        outputDirBtn.setOnAction(e -> chooseOutputDir());

        outputRow.getChildren().addAll(outputField, outputDirBtn);

        VBox outputBox = new VBox(6);
        outputBox.getChildren().addAll(outputTitle, outputRow);

        // 转换选项
        Label optionsTitle = new Label("转换选项");
        optionsTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        HBox optionsRow = new HBox(12);
        optionsRow.setAlignment(Pos.CENTER_LEFT);

        Label widthLabel = new Label("宽度:");
        widthLabel.setStyle("-fx-font-size: 12px;");
        widthField = new TextField();
        widthField.setPromptText("自动");
        widthField.setPrefWidth(80);
        widthField.setStyle("-fx-font-size: 12px;");

        Label heightLabel = new Label("高度:");
        heightLabel.setStyle("-fx-font-size: 12px;");
        heightField = new TextField();
        heightField.setPromptText("自动");
        heightField.setPrefWidth(80);
        heightField.setStyle("-fx-font-size: 12px;");

        optionsRow.getChildren().addAll(widthLabel, widthField, heightLabel, heightField);

        VBox optionsBox = new VBox(6);
        optionsBox.getChildren().addAll(optionsTitle, optionsRow);

        // 文件列表
        Label fileTitle = new Label("待转换文件列表");
        fileTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        fileListView = new ListView<>(fileData);
        fileListView.setStyle("-fx-font-size: 11px;");
        fileListView.setPrefHeight(150);
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        VBox fileBox = new VBox(6);
        fileBox.getChildren().addAll(fileTitle, fileListView);
        VBox.setVgrow(fileBox, Priority.ALWAYS);

        // 操作按钮
        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        convertBtn = new Button("开始转换");
        convertBtn.setStyle("-fx-font-size: 13px; -fx-padding: 8 24; -fx-background-color: #1890ff; -fx-text-fill: white; -fx-background-radius: 6;");
        convertBtn.setOnAction(e -> startConvert());

        clearBtn = new Button("清空");
        clearBtn.setStyle("-fx-font-size: 13px; -fx-padding: 8 24; -fx-background-color: #ff4d4f; -fx-text-fill: white; -fx-background-radius: 6;");
        clearBtn.setOnAction(e -> clearAll());

        actionRow.getChildren().addAll(convertBtn, clearBtn);

        // 进度区域
        progressBar = new ProgressBar();
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setProgress(0);
        progressBar.setVisible(false);

        progressLabel = new Label("");
        progressLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        // 日志区域
        Label logTitle = new Label("转换日志");
        logTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-font-size: 11px; -fx-control-inner-background: #fafafa;");
        logArea.setPrefRowCount(6);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox logBox = new VBox(6);
        logBox.getChildren().addAll(logTitle, logArea);
        VBox.setVgrow(logBox, Priority.ALWAYS);

        // 组合布局
        getChildren().addAll(sourceBox, outputBox, optionsBox, fileBox, actionRow, progressBar, progressLabel, logBox);
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
            log("扫描目录失败: " + e.getMessage());
        }
    }

    private void refreshFileList() {
        fileData.clear();
        for (File f : svgFiles) {
            fileData.add(f.getAbsolutePath());
        }
        if (svgFiles.isEmpty()) {
            log("未找到SVG文件");
        } else {
            log("找到 " + svgFiles.size() + " 个SVG文件");
        }
    }

    private void startConvert() {
        if (svgFiles.isEmpty()) {
            log("请先选择源文件或目录");
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
            log("宽度/高度请输入有效数字");
            return;
        }

        final int width = w;
        final int height = h;

        convertBtn.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(0);

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
                    String svgName = svgFile.getName();
                    String pngName = pngFile.getName();
                    Platform.runLater(() -> log("成功: " + svgName + " -> " + pngName));
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    String svgName = svgFile.getName();
                    String errMsg = e.getMessage();
                    Platform.runLater(() -> log("失败: " + svgName + " - " + errMsg));
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
                progressLabel.setText(String.format("转换完成! 总计: %d, 成功: %d, 失败: %d", total, finalSuccess, finalFail));
                log(String.format("========== 转换完成 ========== 总计: %d, 成功: %d, 失败: %d", total, finalSuccess, finalFail));
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

    private void clearAll() {
        sourceField.clear();
        outputField.clear();
        widthField.clear();
        heightField.clear();
        svgFiles.clear();
        fileData.clear();
        logArea.clear();
        progressBar.setProgress(0);
        progressBar.setVisible(false);
        progressLabel.setText("");
    }

    private void log(String message) {
        logArea.appendText(message + "\n");
    }

    private Stage getStage() {
        return (Stage) getScene().getWindow();
    }
}
