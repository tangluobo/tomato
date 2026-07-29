package com.tangluobo.tomato.module.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据集转换工具面板
 * 支持 PASCAL VOC → COCO 格式转换
 */
public class DatasetConverterPane extends VBox {

    private TextField sourcePathField;
    private TextField targetPathField;
    private Label statusLabel;
    private Button convertButton;

    public DatasetConverterPane() {
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
        titleIcon.setContent("M22 2v14H8V2h14zm0-2H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V2c0-1.1-.9-2-2-2zm-9 17v-2h2v-2h-2v-2h2V9h-2V7h2V5H13v14zm-7 0H4V5H2v14c0 1.1.9 2 2 2h14v-2H6z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);
        Label titleLabel = new Label("数据集格式转换");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitleLabel = new Label("PASCAL VOC → COCO");
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
        Label typeLabel = new Label("PASCAL VOC (XML) → COCO (JSON)");
        typeLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #555;");
        typeContent.getChildren().add(typeLabel);
        typeBox.getChildren().add(typeContent);

        // 源数据集目录
        VBox sourceBox = createDirectorySection("源数据集目录", "选择包含 VOC XML 标注文件的目录", true);
        sourcePathField = (TextField) ((HBox) sourceBox.getChildren().get(1)).getChildren().get(0);

        // 目标数据集目录
        VBox targetBox = createDirectorySection("目标数据集目录", "选择 COCO JSON 输出目录", false);
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

    private VBox createDirectorySection(String title, String placeholder, boolean isSource) {
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
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(isSource ? "选择源数据集目录" : "选择目标数据集目录");
            File selected = chooser.showDialog(getScene().getWindow());
            if (selected != null) {
                pathField.setText(selected.getAbsolutePath());
            }
        });

        pathRow.getChildren().addAll(pathField, browseButton);
        box.getChildren().add(pathRow);
        return box;
    }

    private void startConversion() {
        String sourcePath = sourcePathField.getText().trim();
        String targetPath = targetPathField.getText().trim();

        if (sourcePath.isEmpty()) {
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
            statusLabel.setText("请选择源数据集目录");
            return;
        }
        if (targetPath.isEmpty()) {
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
            statusLabel.setText("请选择目标数据集目录");
            return;
        }

        convertButton.setDisable(true);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #1976D2;");
        statusLabel.setText("正在转换...");

        new Thread(() -> {
            try {
                convertVOCtoCOCO(sourcePath, targetPath);
                Platform.runLater(() -> {
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #388E3C;");
                    statusLabel.setText("转换完成！");
                    convertButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
                    statusLabel.setText("转换失败：" + e.getMessage());
                    convertButton.setDisable(false);
                });
            }
        }).start();
    }

    private void convertVOCtoCOCO(String sourcePath, String targetPath) throws Exception {
        File sourceDir = new File(sourcePath);
        if (!sourceDir.isDirectory()) {
            throw new RuntimeException("源数据集目录不存在");
        }

        // 查找所有 XML 文件
        List<File> xmlFiles;
        try (Stream<Path> paths = Files.walk(sourceDir.toPath())) {
            xmlFiles = paths
                    .filter(p -> p.toString().endsWith(".xml"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        }

        if (xmlFiles.isEmpty()) {
            throw new RuntimeException("源数据集目录中没有找到 XML 文件");
        }

        // 构建 COCO 数据结构
        Map<String, Object> cocoData = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("description", "Converted from PASCAL VOC");
        info.put("version", "1.0");
        info.put("year", 2026);
        info.put("contributor", "Tomato");
        info.put("date_created", LocalDateTime.now().toString());
        cocoData.put("info", info);

        List<Map<String, Object>> licenses = new ArrayList<>();
        Map<String, Object> license = new LinkedHashMap<>();
        license.put("id", 1);
        license.put("name", "Unknown");
        license.put("url", "http://unknown.org");
        licenses.add(license);
        cocoData.put("licenses", licenses);

        List<Map<String, Object>> images = new ArrayList<>();
        List<Map<String, Object>> annotations = new ArrayList<>();
        List<Map<String, Object>> categories = new ArrayList<>();

        Map<String, Integer> categoryMap = new LinkedHashMap<>();
        int categoryId = 1;
        int imageId = 1;
        int annotationId = 1;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 禁用外部实体以防止XXE攻击
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();

        for (File xmlFile : xmlFiles) {
            try {
                Document doc = builder.parse(xmlFile);
                doc.getDocumentElement().normalize();

                // 提取文件名
                String fileName = getTextContent(doc, "filename");
                if (fileName == null) continue;

                // 提取图像尺寸
                Integer width = getIntContent(doc, "width");
                Integer height = getIntContent(doc, "height");
                if (width == null || height == null) continue;

                // 添加图像信息
                Map<String, Object> imageInfo = new LinkedHashMap<>();
                imageInfo.put("id", imageId);
                imageInfo.put("file_name", fileName);
                imageInfo.put("width", width);
                imageInfo.put("height", height);
                imageInfo.put("license", 1);
                imageInfo.put("flickr_url", "");
                imageInfo.put("coco_url", "");
                imageInfo.put("date_captured", LocalDateTime.now().toString());
                images.add(imageInfo);

                // 处理每个对象
                NodeList objectNodes = doc.getElementsByTagName("object");
                for (int i = 0; i < objectNodes.getLength(); i++) {
                    Element objElement = (Element) objectNodes.item(i);
                    String className = getTextContent(objElement, "name");
                    if (className == null) continue;

                    Element bndbox = getFirstElement(objElement, "bndbox");
                    if (bndbox == null) continue;

                    Integer xmin = getIntContent(bndbox, "xmin");
                    Integer ymin = getIntContent(bndbox, "ymin");
                    Integer xmax = getIntContent(bndbox, "xmax");
                    Integer ymax = getIntContent(bndbox, "ymax");
                    if (xmin == null || ymin == null || xmax == null || ymax == null) continue;

                    // 添加类别
                    if (!categoryMap.containsKey(className)) {
                        categoryMap.put(className, categoryId);
                        Map<String, Object> cat = new LinkedHashMap<>();
                        cat.put("id", categoryId);
                        cat.put("name", className);
                        cat.put("supercategory", "object");
                        categories.add(cat);
                        categoryId++;
                    }

                    // 计算 bbox [x, y, width, height]
                    int bboxWidth = xmax - xmin;
                    int bboxHeight = ymax - ymin;
                    int area = bboxWidth * bboxHeight;

                    Map<String, Object> annotation = new LinkedHashMap<>();
                    annotation.put("id", annotationId);
                    annotation.put("image_id", imageId);
                    annotation.put("category_id", categoryMap.get(className));
                    annotation.put("bbox", Arrays.asList(xmin, ymin, bboxWidth, bboxHeight));
                    annotation.put("area", area);
                    annotation.put("segmentation", new ArrayList<>());
                    annotation.put("iscrowd", 0);
                    annotations.add(annotation);

                    annotationId++;
                }

                imageId++;
            } catch (Exception e) {
                // 跳过解析失败的文件
                continue;
            }
        }

        if (images.isEmpty()) {
            throw new RuntimeException("没有成功解析任何 XML 文件");
        }

        cocoData.put("images", images);
        cocoData.put("annotations", annotations);
        cocoData.put("categories", categories);

        // 保存到目标目录
        File targetDir = new File(targetPath);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File outputFile = new File(targetDir, "coco_annotations.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(outputFile)) {
            gson.toJson(cocoData, writer);
        }
    }

    private String getTextContent(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private String getTextContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private Integer getIntContent(Document doc, String tagName) {
        String text = getTextContent(doc, tagName);
        if (text == null) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer getIntContent(Element parent, String tagName) {
        String text = getTextContent(parent, tagName);
        if (text == null) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Element getFirstElement(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return (Element) nodes.item(0);
        }
        return null;
    }
}
