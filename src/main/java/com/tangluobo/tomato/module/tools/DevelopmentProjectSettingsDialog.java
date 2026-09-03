package com.tangluobo.tomato.module.tools;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 统一管理 JDK、Node、Flutter SDK，并为项目选择对应引用。 */
final class DevelopmentProjectSettingsDialog {
    record SettingsResult(List<DevelopmentConfigManager.RuntimeEntry> runtimes,
                          List<DevelopmentConfigManager.BuildToolEntry> buildTools,
                          String jdkId,
                          String nodeId,
                          String flutterId,
                          String mavenId) {
    }

    private DevelopmentProjectSettingsDialog() {
    }

    static Optional<SettingsResult> show(Window owner,
                                         String projectName,
                                         String projectType,
                                         String selectedJdkId,
                                         String selectedNodeId,
                                         String selectedFlutterId,
                                         String selectedMavenId,
                                         DevelopmentConfigManager.ConfigData config) {
        ObservableList<DevelopmentConfigManager.RuntimeEntry> runtimes =
                FXCollections.observableArrayList(copyEntries(config.getRuntimes()));
        ObservableList<DevelopmentConfigManager.BuildToolEntry> buildTools =
                FXCollections.observableArrayList(copyBuildTools(config.getBuildTools()));

        ComboBox<DevelopmentConfigManager.RuntimeEntry> jdkCombo = createRuntimeCombo(
                new FilteredList<>(runtimes, item -> "JDK".equals(item.getType())), "未指定 JDK");
        ComboBox<DevelopmentConfigManager.RuntimeEntry> nodeCombo = createRuntimeCombo(
                new FilteredList<>(runtimes, item -> "NODE".equals(item.getType())), "未指定 Node");
        ComboBox<DevelopmentConfigManager.RuntimeEntry> flutterCombo = createRuntimeCombo(
                new FilteredList<>(runtimes, item -> "FLUTTER".equals(item.getType())), "未指定 Flutter");
        String requiredBuildType = requiredBuildType(projectType);
        ComboBox<DevelopmentConfigManager.BuildToolEntry> mavenCombo = createBuildToolCombo(
                new FilteredList<>(buildTools, item -> requiredBuildType.equals(item.getType())));
        if ("GRADLE".equals(requiredBuildType)) mavenCombo.setPromptText("未指定 Gradle");
        selectById(jdkCombo, selectedJdkId);
        selectById(nodeCombo, selectedNodeId);
        selectById(flutterCombo, selectedFlutterId);
        selectBuildToolById(mavenCombo, selectedMavenId);

        GridPane projectPane = new GridPane();
        projectPane.setPadding(new Insets(20));
        projectPane.setHgap(12);
        projectPane.setVgap(14);
        projectPane.add(new Label("项目："), 0, 0);
        projectPane.add(new Label(projectName), 1, 0);
        projectPane.add(new Label("识别类型："), 0, 1);
        Label typeLabel = new Label(projectTypeLabel(projectType));
        typeLabel.setStyle("-fx-text-fill: #1976D2; -fx-font-weight: bold;");
        projectPane.add(typeLabel, 1, 1);
        projectPane.add(new Label("JDK："), 0, 2);
        projectPane.add(jdkCombo, 1, 2);
        projectPane.add(new Label("Node："), 0, 3);
        projectPane.add(nodeCombo, 1, 3);
        projectPane.add(new Label("Flutter："), 0, 4);
        projectPane.add(flutterCombo, 1, 4);
        projectPane.add(new Label("构建工具："), 0, 5);
        projectPane.add(mavenCombo, 1, 5);
        boolean buildProject = !requiredBuildType.isBlank();
        mavenCombo.setDisable(!buildProject);
        if (!buildProject) mavenCombo.setPromptText("当前项目未识别构建工具");
        for (ComboBox<?> combo : List.of(jdkCombo, nodeCombo, flutterCombo)) {
            combo.setPrefWidth(360);
        }
        mavenCombo.setPrefWidth(360);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("项目配置", projectPane),
                new Tab("SDK 管理", createRuntimeManager(owner, runtimes)),
                new Tab("构建管理", createBuildToolManager(owner, buildTools))
        );
        tabs.setPrefSize(660, 460);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("项目配置 - " + projectName);
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.orElse(ButtonType.CANCEL) != ButtonType.OK) return Optional.empty();

        return Optional.of(new SettingsResult(
                new ArrayList<>(runtimes),
                new ArrayList<>(buildTools),
                selectedId(jdkCombo),
                selectedId(nodeCombo),
                selectedId(flutterCombo),
                selectedBuildToolId(mavenCombo)
        ));
    }

    private static VBox createRuntimeManager(Window owner,
                                             ObservableList<DevelopmentConfigManager.RuntimeEntry> entries) {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(14));

        ListView<DevelopmentConfigManager.RuntimeEntry> list = new ListView<>(entries);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(DevelopmentConfigManager.RuntimeEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label type = new Label(item.getType());
                type.setStyle("-fx-background-color: #e8f4ff; -fx-text-fill: #1976D2; "
                        + "-fx-background-radius: 4; -fx-padding: 2 7; -fx-font-size: 10px; -fx-font-weight: bold;");
                Label name = new Label(item.getName() + "  " + safe(item.getVersion()));
                name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
                HBox title = new HBox(8, type, name);
                title.setAlignment(Pos.CENTER_LEFT);
                Label path = new Label(item.getPath());
                path.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
                setText(null);
                setGraphic(new VBox(3, title, path));
            }
        });

        Button addButton = new Button("添加 SDK");
        Button removeButton = new Button("删除");
        removeButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        addButton.setOnAction(e -> showAddRuntimeDialog(owner).ifPresent(entries::add));
        removeButton.setOnAction(e -> {
            DevelopmentConfigManager.RuntimeEntry selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) entries.remove(selected);
        });
        HBox buttons = new HBox(8, addButton, removeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        pane.getChildren().addAll(list, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        return pane;
    }

    private static VBox createBuildToolManager(
            Window owner, ObservableList<DevelopmentConfigManager.BuildToolEntry> entries) {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(14));

        ListView<DevelopmentConfigManager.BuildToolEntry> list = new ListView<>(entries);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(DevelopmentConfigManager.BuildToolEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label type = new Label(item.getType());
                type.setStyle("-fx-background-color: #fff3e0; -fx-text-fill: #ef6c00; "
                        + "-fx-background-radius: 4; -fx-padding: 2 7; -fx-font-size: 10px; -fx-font-weight: bold;");
                Label name = new Label(item.getName() + "  " + safe(item.getVersion()));
                name.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
                HBox title = new HBox(8, type, name);
                title.setAlignment(Pos.CENTER_LEFT);
                Label home = new Label(item.getHomePath());
                home.setStyle("-fx-font-size: 11px; -fx-text-fill: #777;");
                Label settings = new Label("settings.xml: " + item.getSettingsPath());
                settings.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
                boolean hasSettings = item.getSettingsPath() != null && !item.getSettingsPath().isBlank();
                settings.setVisible(hasSettings);
                settings.setManaged(hasSettings);
                setText(null);
                setGraphic(new VBox(3, title, home, settings));
            }
        });

        Button addButton = new Button("添加构建工具");
        Button editButton = new Button("修改");
        Button removeButton = new Button("删除");
        editButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        removeButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        addButton.setOnAction(e -> showBuildToolDialog(owner, null).ifPresent(entries::add));
        editButton.setOnAction(e -> editSelectedBuildTool(owner, list, entries));
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) editSelectedBuildTool(owner, list, entries);
        });
        removeButton.setOnAction(e -> {
            DevelopmentConfigManager.BuildToolEntry selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) entries.remove(selected);
        });
        HBox buttons = new HBox(8, addButton, editButton, removeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        pane.getChildren().addAll(list, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        return pane;
    }

    private static void editSelectedBuildTool(
            Window owner,
            ListView<DevelopmentConfigManager.BuildToolEntry> list,
            ObservableList<DevelopmentConfigManager.BuildToolEntry> entries) {
        int index = list.getSelectionModel().getSelectedIndex();
        if (index < 0) return;
        DevelopmentConfigManager.BuildToolEntry selected = entries.get(index);
        showBuildToolDialog(owner, selected).ifPresent(updated -> {
            entries.set(index, updated);
            list.getSelectionModel().select(index);
        });
    }

    private static Optional<DevelopmentConfigManager.BuildToolEntry> showBuildToolDialog(
            Window owner, DevelopmentConfigManager.BuildToolEntry existing) {
        Dialog<DevelopmentConfigManager.BuildToolEntry> dialog = new Dialog<>();
        boolean editing = existing != null;
        dialog.setTitle(editing ? "修改构建工具" : "添加构建工具");
        if (owner != null) dialog.initOwner(owner);
        ButtonType saveType = new ButtonType(editing ? "保存" : "添加", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        ComboBox<String> typeField = new ComboBox<>(FXCollections.observableArrayList("MAVEN", "GRADLE"));
        typeField.getSelectionModel().selectFirst();
        TextField nameField = new TextField();
        TextField homeField = new TextField();
        TextField versionField = new TextField();
        TextField settingsField = new TextField();
        nameField.setPromptText("如：Maven 3.9");
        homeField.setPromptText("构建工具主目录");
        versionField.setPromptText("自动识别，也可以手动填写");
        settingsField.setPromptText("默认使用 Maven/conf/settings.xml");

        Button browseHomeButton = new Button("浏览");
        browseHomeButton.setOnAction(e -> {
            String buildType = typeField.getValue();
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择 " + buildType + " 主目录");
            File selected = chooser.showDialog(owner);
            if (selected == null) return;
            homeField.setText(selected.getAbsolutePath());
            String executableName = "MAVEN".equals(buildType)
                    ? (windows() ? "mvn.cmd" : "mvn")
                    : (windows() ? "gradle.bat" : "gradle");
            Path executable = selected.toPath().resolve("bin").resolve(executableName);
            String version = detectBuildVersion(buildType, executable);
            versionField.setText(version);
            settingsField.setText("MAVEN".equals(buildType)
                    ? selected.toPath().resolve("conf").resolve("settings.xml").toString() : "");
            if (nameField.getText().isBlank()) {
                nameField.setText(displayBuildType(buildType) + (version.isBlank() ? "" : " " + version));
            }
        });
        Button browseSettingsButton = new Button("浏览");
        browseSettingsButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 Maven settings.xml");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Maven settings.xml", "*.xml"));
            File selected = chooser.showOpenDialog(owner);
            if (selected != null) settingsField.setText(selected.getAbsolutePath());
        });
        typeField.valueProperty().addListener((obs, oldType, newType) -> {
            boolean maven = "MAVEN".equals(newType);
            settingsField.setDisable(!maven);
            browseSettingsButton.setDisable(!maven);
            homeField.clear();
            versionField.clear();
            settingsField.clear();
            nameField.clear();
        });
        if (editing) {
            typeField.setValue(existing.getType());
            nameField.setText(existing.getName());
            homeField.setText(existing.getHomePath());
            versionField.setText(existing.getVersion());
            settingsField.setText(existing.getSettingsPath());
        }

        HBox homeBox = new HBox(8, homeField, browseHomeButton);
        HBox settingsBox = new HBox(8, settingsField, browseSettingsButton);
        HBox.setHgrow(homeField, Priority.ALWAYS);
        HBox.setHgrow(settingsField, Priority.ALWAYS);
        GridPane form = new GridPane();
        form.setPadding(new Insets(16));
        form.setHgap(10);
        form.setVgap(12);
        form.add(new Label("类型："), 0, 0);
        form.add(typeField, 1, 0);
        form.add(new Label("名称："), 0, 1);
        form.add(nameField, 1, 1);
        form.add(new Label("工具路径："), 0, 2);
        form.add(homeBox, 1, 2);
        form.add(new Label("版本："), 0, 3);
        form.add(versionField, 1, 3);
        form.add(new Label("settings.xml："), 0, 4);
        form.add(settingsBox, 1, 4);
        homeBox.setPrefWidth(450);
        dialog.getDialogPane().setContent(form);

        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            File home = new File(homeField.getText().trim());
            File settings = new File(settingsField.getText().trim());
            boolean settingsValid = !"MAVEN".equals(typeField.getValue()) || settings.isFile();
            if (nameField.getText().isBlank() || !home.isDirectory() || !settingsValid) {
                Alert alert = new Alert(Alert.AlertType.WARNING,
                        "请填写名称并选择有效的构建工具路径"
                                + ("MAVEN".equals(typeField.getValue()) ? "和 settings.xml" : ""),
                        ButtonType.OK);
                if (owner != null) alert.initOwner(owner);
                alert.showAndWait();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == saveType
                ? new DevelopmentConfigManager.BuildToolEntry(
                        editing ? existing.getId() : UUID.randomUUID().toString(),
                        typeField.getValue(),
                        nameField.getText().trim(),
                        homeField.getText().trim(),
                        versionField.getText().trim(),
                        settingsField.getText().trim())
                : null);
        return dialog.showAndWait();
    }

    private static String detectBuildVersion(String type, Path executable) {
        try {
            String versionLine = runVersionCommand(executable,
                    "GRADLE".equals(type) ? "--version" : "-version");
            return "MAVEN".equals(type)
                    ? versionLine.replaceFirst("(?i)^Apache Maven\\s+", "").trim()
                    : versionLine.replaceFirst("(?i)^Gradle\\s+", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Optional<DevelopmentConfigManager.RuntimeEntry> showAddRuntimeDialog(Window owner) {
        Dialog<DevelopmentConfigManager.RuntimeEntry> dialog = new Dialog<>();
        dialog.setTitle("添加 SDK");
        if (owner != null) dialog.initOwner(owner);
        ButtonType addType = new ButtonType("添加", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);

        ComboBox<String> typeField = new ComboBox<>(FXCollections.observableArrayList("JDK", "NODE", "FLUTTER"));
        typeField.getSelectionModel().selectFirst();
        TextField nameField = new TextField();
        TextField pathField = new TextField();
        TextField versionField = new TextField();
        nameField.setPromptText("SDK 名称");
        pathField.setPromptText("SDK 安装路径");
        versionField.setPromptText("自动识别，也可以手动填写");

        Button browseButton = new Button("浏览");
        browseButton.setOnAction(e -> {
            String type = typeField.getValue();
            File selected;
            if ("NODE".equals(type)) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("选择 Node 可执行文件");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                        "Node 可执行文件", "node", "node.exe", "*"));
                selected = chooser.showOpenDialog(owner);
            } else {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle("选择 " + type + " 主目录");
                selected = chooser.showDialog(owner);
            }
            if (selected == null) return;
            pathField.setText(selected.getAbsolutePath());
            String version = detectRuntimeVersion(type, selected.toPath());
            versionField.setText(version);
            if (nameField.getText().isBlank()) {
                nameField.setText(displayType(type) + (version.isBlank() ? "" : " " + version));
            }
        });
        typeField.valueProperty().addListener((obs, oldType, newType) -> {
            pathField.clear();
            versionField.clear();
            nameField.clear();
        });

        HBox pathBox = new HBox(8, pathField, browseButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        GridPane form = new GridPane();
        form.setPadding(new Insets(16));
        form.setHgap(10);
        form.setVgap(12);
        form.add(new Label("类型："), 0, 0);
        form.add(typeField, 1, 0);
        form.add(new Label("名称："), 0, 1);
        form.add(nameField, 1, 1);
        form.add(new Label("路径："), 0, 2);
        form.add(pathBox, 1, 2);
        form.add(new Label("版本："), 0, 3);
        form.add(versionField, 1, 3);
        pathBox.setPrefWidth(430);
        dialog.getDialogPane().setContent(form);

        Node addButton = dialog.getDialogPane().lookupButton(addType);
        addButton.addEventFilter(ActionEvent.ACTION, event -> {
            String type = typeField.getValue();
            File path = new File(pathField.getText().trim());
            boolean validPath = "NODE".equals(type) ? path.isFile() : path.isDirectory();
            if (nameField.getText().isBlank() || !validPath) {
                Alert alert = new Alert(Alert.AlertType.WARNING,
                        "请填写名称并选择有效的 " + displayType(type) + " 路径", ButtonType.OK);
                if (owner != null) alert.initOwner(owner);
                alert.showAndWait();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == addType
                ? new DevelopmentConfigManager.RuntimeEntry(
                        UUID.randomUUID().toString(),
                        nameField.getText().trim(),
                        pathField.getText().trim(),
                        versionField.getText().trim(),
                        typeField.getValue())
                : null);
        return dialog.showAndWait();
    }

    private static String detectRuntimeVersion(String type, Path selectedPath) {
        try {
            if ("JDK".equals(type)) {
                Path releaseFile = selectedPath.resolve("release");
                if (Files.exists(releaseFile)) {
                    for (String line : Files.readAllLines(releaseFile, StandardCharsets.UTF_8)) {
                        if (line.startsWith("JAVA_VERSION=")) {
                            return line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                        }
                    }
                }
                return runVersionCommand(selectedPath.resolve("bin").resolve(windows() ? "java.exe" : "java"), "-version");
            }
            if ("FLUTTER".equals(type)) {
                return runVersionCommand(selectedPath.resolve("bin").resolve(windows() ? "flutter.bat" : "flutter"), "--version");
            }
            return runVersionCommand(selectedPath, "--version");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String runVersionCommand(Path executable, String argument) throws Exception {
        List<String> command = new ArrayList<>();
        String executableName = executable.getFileName().toString().toLowerCase();
        if (windows() && (executableName.endsWith(".cmd") || executableName.endsWith(".bat"))) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
        }
        command.add(executable.toString());
        command.add(argument);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "";
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (output.isBlank()) return "";
        String firstLine = output.lines().findFirst().orElse(output);
        int quoteStart = firstLine.indexOf('"');
        int quoteEnd = firstLine.indexOf('"', quoteStart + 1);
        String value = quoteStart >= 0 && quoteEnd > quoteStart
                ? firstLine.substring(quoteStart + 1, quoteEnd)
                : firstLine.replaceFirst("^(?i:flutter|v)", "").trim();
        return value;
    }

    private static ComboBox<DevelopmentConfigManager.RuntimeEntry> createRuntimeCombo(
            ObservableList<DevelopmentConfigManager.RuntimeEntry> entries, String prompt) {
        ComboBox<DevelopmentConfigManager.RuntimeEntry> combo = new ComboBox<>(entries);
        combo.setPromptText(prompt);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(DevelopmentConfigManager.RuntimeEntry item) {
                return item == null ? "" : item.getName() + " (" + safe(item.getVersion()) + ")";
            }

            @Override
            public DevelopmentConfigManager.RuntimeEntry fromString(String value) { return null; }
        });
        return combo;
    }

    private static ComboBox<DevelopmentConfigManager.BuildToolEntry> createBuildToolCombo(
            ObservableList<DevelopmentConfigManager.BuildToolEntry> entries) {
        ComboBox<DevelopmentConfigManager.BuildToolEntry> combo = new ComboBox<>(entries);
        combo.setPromptText("未指定 Maven");
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(DevelopmentConfigManager.BuildToolEntry item) {
                return item == null ? "" : item.getName() + " (" + safe(item.getVersion()) + ")";
            }

            @Override
            public DevelopmentConfigManager.BuildToolEntry fromString(String value) { return null; }
        });
        return combo;
    }

    private static void selectById(ComboBox<DevelopmentConfigManager.RuntimeEntry> combo, String id) {
        if (id == null) return;
        combo.getItems().stream().filter(item -> id.equals(item.getId())).findFirst()
                .ifPresent(combo.getSelectionModel()::select);
    }

    private static String selectedId(ComboBox<DevelopmentConfigManager.RuntimeEntry> combo) {
        DevelopmentConfigManager.RuntimeEntry selected = combo.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.getId();
    }

    private static void selectBuildToolById(
            ComboBox<DevelopmentConfigManager.BuildToolEntry> combo, String id) {
        if (id == null) return;
        combo.getItems().stream().filter(item -> id.equals(item.getId())).findFirst()
                .ifPresent(combo.getSelectionModel()::select);
    }

    private static String selectedBuildToolId(
            ComboBox<DevelopmentConfigManager.BuildToolEntry> combo) {
        DevelopmentConfigManager.BuildToolEntry selected = combo.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.getId();
    }

    private static List<DevelopmentConfigManager.RuntimeEntry> copyEntries(
            List<DevelopmentConfigManager.RuntimeEntry> source) {
        return source.stream().map(entry -> new DevelopmentConfigManager.RuntimeEntry(
                entry.getId(), entry.getName(), entry.getPath(), entry.getVersion(), entry.getType())).toList();
    }

    private static List<DevelopmentConfigManager.BuildToolEntry> copyBuildTools(
            List<DevelopmentConfigManager.BuildToolEntry> source) {
        return source.stream().map(entry -> new DevelopmentConfigManager.BuildToolEntry(
                entry.getId(), entry.getType(), entry.getName(), entry.getHomePath(),
                entry.getVersion(), entry.getSettingsPath())).toList();
    }

    private static String projectTypeLabel(String type) {
        return switch (type == null ? "GENERAL" : type) {
            case "MAVEN" -> "Maven 项目";
            case "NODE" -> "Node 项目";
            case "MAVEN_NODE" -> "Maven + Node 项目";
            case "GRADLE" -> "Gradle 项目";
            case "GRADLE_NODE" -> "Gradle + Node 项目";
            case "FLUTTER" -> "Flutter 项目";
            default -> "普通目录";
        };
    }

    private static String displayType(String type) {
        return switch (type) {
            case "NODE" -> "Node";
            case "FLUTTER" -> "Flutter";
            default -> "JDK";
        };
    }

    private static String requiredBuildType(String projectType) {
        if ("MAVEN".equals(projectType) || "MAVEN_NODE".equals(projectType)) return "MAVEN";
        if ("GRADLE".equals(projectType) || "GRADLE_NODE".equals(projectType)) return "GRADLE";
        return "";
    }

    private static String displayBuildType(String type) {
        return "GRADLE".equals(type) ? "Gradle" : "Maven";
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未知版本" : value;
    }
}
