package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.service.MongoService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.bson.Document;

import java.util.List;
import java.util.function.Consumer;

/** MongoDB 集合工作区：文档 CRUD、JSON 查询、数据库命令、索引和校验规则。 */
public class MongoCollectionView extends BorderPane {
    private final ConnectionConfig config;
    private final String databaseName;
    private final String collectionName;

    private final ListView<String> documents = jsonListView();
    private final TextArea documentEditor = jsonEditor();
    private final TextField filterField = new TextField("{}");
    private final Spinner<Integer> skipSpinner = new Spinner<>(0, Integer.MAX_VALUE, 0, 50);
    private final Spinner<Integer> limitSpinner = new Spinner<>(1, 1000, 100, 50);
    private final Label status = new Label();
    private String selectedOriginalJson;

    private final ListView<String> indexes = jsonListView();
    private final TextField indexKeysField = new TextField("{\"field\": 1}");
    private final TextField indexNameField = new TextField();
    private final CheckBox uniqueIndex = new CheckBox("唯一索引");

    private final TextArea validatorEditor = jsonEditor();
    private final TextArea commandEditor = jsonEditor();
    private final TextArea commandResult = jsonEditor();

    public MongoCollectionView(ConnectionConfig config, String databaseName, String collectionName) {
        this.config = config;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        setPadding(new Insets(8));

        TabPane tabs = new TabPane(
                tab("文档", buildDocumentsPane()),
                tab("命令", buildCommandPane()),
                tab("索引", buildIndexesPane()),
                tab("校验规则", buildValidatorPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);
        setBottom(status);
        BorderPane.setMargin(status, new Insets(6, 4, 0, 4));

        documents.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            selectedOriginalJson = selected;
            documentEditor.setText(selected == null ? "{}" : selected);
        });
        loadDocuments();
        loadIndexes();
        loadValidator();
    }

    private BorderPane buildDocumentsPane() {
        filterField.setPromptText("MongoDB JSON 查询条件，例如 {\"status\": \"active\"}");
        HBox.setHgrow(filterField, Priority.ALWAYS);

        Button query = new Button("查询");
        query.setOnAction(event -> loadDocuments());
        filterField.setOnAction(event -> loadDocuments());
        Button previous = new Button("上一页");
        previous.setOnAction(event -> {
            skipSpinner.getValueFactory().setValue(Math.max(0,
                    skipSpinner.getValue() - limitSpinner.getValue()));
            loadDocuments();
        });
        Button next = new Button("下一页");
        next.setOnAction(event -> {
            skipSpinner.getValueFactory().setValue(skipSpinner.getValue() + limitSpinner.getValue());
            loadDocuments();
        });
        HBox toolbar = new HBox(7, new Label("筛选："), filterField, new Label("跳过"), skipSpinner,
                new Label("条数"), limitSpinner, query, previous, next);
        toolbar.setPadding(new Insets(0, 0, 7, 0));

        Button newDocument = new Button("新建文档");
        newDocument.setOnAction(event -> {
            documents.getSelectionModel().clearSelection();
            selectedOriginalJson = null;
            documentEditor.setText("{\n  \n}");
            documentEditor.requestFocus();
        });
        Button save = new Button("保存");
        save.setOnAction(event -> saveDocument());
        Button delete = new Button("删除");
        delete.setOnAction(event -> deleteDocument());
        Button format = new Button("格式化 JSON");
        format.setOnAction(event -> formatEditor(documentEditor));
        HBox editorButtons = new HBox(7, newDocument, save, delete, format);

        VBox editorBox = new VBox(7, editorButtons, documentEditor);
        VBox.setVgrow(documentEditor, Priority.ALWAYS);
        SplitPane split = new SplitPane(documents, editorBox);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.42);

        BorderPane pane = new BorderPane(split);
        pane.setTop(toolbar);
        return pane;
    }

    private VBox buildCommandPane() {
        commandEditor.setText("{\n  \"dbStats\": 1\n}");
        commandEditor.setPrefRowCount(9);
        commandResult.setEditable(false);
        Button execute = new Button("执行数据库命令");
        execute.setOnAction(event -> runAsync("MongoDB-Command",
                () -> MongoService.runCommand(config, databaseName, commandEditor.getText()),
                result -> {
                    commandResult.setText(result);
                    setStatus("命令执行成功");
                }));
        VBox box = new VBox(8, new Label("数据库命令（JSON）"), commandEditor, execute,
                new Label("结果"), commandResult);
        VBox.setVgrow(commandResult, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        return box;
    }

    private VBox buildIndexesPane() {
        indexKeysField.setPromptText("索引键，例如 {\"created_at\": -1}");
        HBox.setHgrow(indexKeysField, Priority.ALWAYS);
        indexNameField.setPromptText("索引名（可选）");
        Button create = new Button("创建索引");
        create.setOnAction(event -> runAsync("MongoDB-CreateIndex", () ->
                MongoService.createIndex(config, databaseName, collectionName,
                        indexKeysField.getText(), indexNameField.getText(), uniqueIndex.isSelected()),
                name -> {
                    setStatus("已创建索引 " + name);
                    loadIndexes();
                }));
        Button drop = new Button("删除选中索引");
        drop.setOnAction(event -> dropSelectedIndex());
        Button refresh = new Button("刷新");
        refresh.setOnAction(event -> loadIndexes());
        HBox controls = new HBox(7, indexKeysField, indexNameField, uniqueIndex, create, drop, refresh);
        VBox box = new VBox(8, controls, indexes);
        VBox.setVgrow(indexes, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        return box;
    }

    private VBox buildValidatorPane() {
        Label hint = new Label("集合设计使用 MongoDB validator / $jsonSchema；保存后以 strict + error 模式生效。");
        Button save = new Button("保存校验规则");
        save.setOnAction(event -> runAsync("MongoDB-UpdateValidator", () -> {
            MongoService.updateValidator(config, databaseName, collectionName, validatorEditor.getText());
            return Boolean.TRUE;
        }, ignored -> setStatus("校验规则已保存")));
        Button reload = new Button("重新加载");
        reload.setOnAction(event -> loadValidator());
        Button format = new Button("格式化 JSON");
        format.setOnAction(event -> formatEditor(validatorEditor));
        VBox box = new VBox(8, hint, new HBox(7, save, reload, format), validatorEditor);
        VBox.setVgrow(validatorEditor, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        return box;
    }

    private void loadDocuments() {
        setStatus("正在查询文档…");
        runAsync("MongoDB-FindDocuments", () -> MongoService.findDocuments(
                config, databaseName, collectionName, filterField.getText(),
                skipSpinner.getValue(), limitSpinner.getValue()), result -> {
            documents.setItems(FXCollections.observableArrayList(result));
            selectedOriginalJson = null;
            documentEditor.setText(result.isEmpty() ? "{}" : result.getFirst());
            if (!result.isEmpty()) documents.getSelectionModel().selectFirst();
            setStatus("已加载 " + result.size() + " 条文档");
        });
    }

    private void saveDocument() {
        String json = documentEditor.getText();
        if (selectedOriginalJson == null) {
            runAsync("MongoDB-InsertDocument", () -> MongoService.insertDocument(
                    config, databaseName, collectionName, json), saved -> {
                setStatus("文档已新增");
                loadDocuments();
            });
        } else {
            String original = selectedOriginalJson;
            runAsync("MongoDB-ReplaceDocument", () -> MongoService.replaceDocument(
                    config, databaseName, collectionName, original, json), saved -> {
                selectedOriginalJson = saved;
                documentEditor.setText(saved);
                setStatus("文档已保存");
                loadDocuments();
            });
        }
    }

    private void deleteDocument() {
        String original = selectedOriginalJson;
        if (original == null) {
            showError(new IllegalStateException("请先选择要删除的文档"));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除选中的文档吗？此操作不可撤销。", ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("删除 MongoDB 文档");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        runAsync("MongoDB-DeleteDocument", () -> MongoService.deleteDocument(
                config, databaseName, collectionName, original), deleted -> {
            setStatus(deleted ? "文档已删除" : "未找到文档");
            loadDocuments();
        });
    }

    private void loadIndexes() {
        runAsync("MongoDB-LoadIndexes", () -> MongoService.getIndexes(
                config, databaseName, collectionName), result ->
                indexes.setItems(FXCollections.observableArrayList(result)));
    }

    private void dropSelectedIndex() {
        String selected = indexes.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError(new IllegalStateException("请先选择要删除的索引"));
            return;
        }
        String name;
        try {
            name = Document.parse(selected).getString("name");
        } catch (RuntimeException error) {
            showError(error);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除索引 “" + name + "” 吗？", ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("删除 MongoDB 索引");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        runAsync("MongoDB-DropIndex", () -> {
            MongoService.dropIndex(config, databaseName, collectionName, name);
            return Boolean.TRUE;
        }, ignored -> {
            setStatus("索引已删除");
            loadIndexes();
        });
    }

    private void loadValidator() {
        runAsync("MongoDB-LoadValidator", () -> MongoService.getValidator(
                config, databaseName, collectionName), validatorEditor::setText);
    }

    private void formatEditor(TextArea editor) {
        try {
            editor.setText(Document.parse(editor.getText()).toJson(
                    org.bson.json.JsonWriterSettings.builder().indent(true).build()));
        } catch (RuntimeException error) {
            showError(error);
        }
    }

    private <T> void runAsync(String name, ThrowingSupplier<T> task, Consumer<T> success) {
        Thread thread = new Thread(() -> {
            try {
                T result = task.get();
                Platform.runLater(() -> success.accept(result));
            } catch (Throwable error) {
                Platform.runLater(() -> showError(error));
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void showError(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("MongoDB 操作失败");
        alert.setHeaderText(null);
        alert.setContentText(root.getMessage() == null ? root.toString() : root.getMessage());
        alert.showAndWait();
        setStatus("操作失败");
    }

    private static Tab tab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private static TextArea jsonEditor() {
        TextArea editor = new TextArea();
        editor.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;");
        editor.setWrapText(false);
        return editor;
    }

    private static ListView<String> jsonListView() {
        ListView<String> list = new ListView<>();
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : compact(value));
                setTooltip(empty || value == null ? null : new javafx.scene.control.Tooltip(value));
                setStyle("-fx-font-family: 'Consolas', 'Monospaced';");
            }
        });
        return list;
    }

    private static String compact(String json) {
        String value = json.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return value.length() > 240 ? value.substring(0, 237) + "…" : value;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
