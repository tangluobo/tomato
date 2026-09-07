package com.tangluobo.tomato.module.tools;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/** Download-list UI for the embedded HTTP, BitTorrent and ED2K engines. */
public class DownloaderPane extends VBox {

    private static final String DEFAULT_THREADS_KEY = "downloader.defaultThreads";
    private static final String CONCURRENT_TASKS_KEY = "downloader.concurrentTasks";
    private static final String DEFAULT_DIRECTORY_KEY = "downloader.defaultDirectory";
    private static final int MIN_CONCURRENT_TASKS = 1;
    private static final int MAX_CONCURRENT_TASKS = 8;
    private static final int DEFAULT_CONCURRENT_TASKS = 2;
    private static final long TASK_STORE_INTERVAL_NANOS = 1_000_000_000L;
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(DownloaderPane.class);
    private static final ObservableList<DownloadRow> SHARED_ROWS = FXCollections.observableArrayList();
    private static final Deque<DownloadRow> SHARED_WAITING_QUEUE = new ArrayDeque<>();
    private static final List<WeakReference<DownloaderPane>> OPEN_VIEWS = new ArrayList<>();
    private static boolean sessionLoaded;
    private static int sharedRunningTasks;
    private static int sharedConcurrentTaskLimit = clampConcurrentTasks(PREFERENCES.getInt(
            CONCURRENT_TASKS_KEY, DEFAULT_CONCURRENT_TASKS));
    private static long lastTaskStoreNanos;
    private static String taskStoreError;

    private final Button addTaskButton = new Button("添加下载");
    private final Button settingsButton = secondaryButton("设置");
    private final Button removeButton = secondaryButton("移除记录");
    private final Button clearFinishedButton = secondaryButton("清空已结束");
    private final Label summaryLabel = new Label("暂无下载任务");
    private final ObservableList<DownloadRow> rows = SHARED_ROWS;
    private final Deque<DownloadRow> waitingQueue = SHARED_WAITING_QUEUE;
    private final TableView<DownloadRow> table = new TableView<>(rows);

    public DownloaderPane() {
        ensureSessionLoaded();
        initializeUi();
        OPEN_VIEWS.add(new WeakReference<>(this));
        refreshAllViews();
        startWaitingTasks();
    }

    public static void prepareForApplicationExit() {
        if (!sessionLoaded) return;
        persistTasksNow();
        List<DownloadEngine> active = SHARED_ROWS.stream()
                .map(row -> row.engine)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        active.forEach(DownloadEngine::cancel);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        for (DownloadEngine engine : active) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                engine.awaitStopped(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        persistTasksNow();
    }

    private void initializeUi() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setSpacing(0);

        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 16, 14, 16));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; "
                + "-fx-border-width: 0 0 1 0;");
        SVGPath titleIcon = downloadIcon();
        Label title = new Label("下载器");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitle = new Label("HTTP 多线程 · BT 磁力/种子 · ED2K，全部由 Java 内置引擎下载");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        titleBar.getChildren().addAll(titleIcon, title, titleSpacer, subtitle);

        VBox content = new VBox(12);
        content.setPadding(new Insets(14));
        content.setFillWidth(true);
        VBox.setVgrow(content, Priority.ALWAYS);

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        addTaskButton.setStyle("-fx-background-color: #1976D2; -fx-text-fill: white; "
                + "-fx-font-weight: bold; -fx-background-radius: 4; -fx-padding: 6 18; -fx-cursor: hand;");
        addTaskButton.setOnAction(event -> showAddDownloadDialog());
        settingsButton.setOnAction(event -> showSettingsDialog());
        removeButton.setDisable(true);
        clearFinishedButton.setDisable(true);
        removeButton.setOnAction(event -> removeSelectedRows());
        clearFinishedButton.setOnAction(event -> clearFinishedRows());
        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        summaryLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        toolbar.getChildren().addAll(addTaskButton, settingsButton, removeButton, clearFinishedButton,
                toolbarSpacer, summaryLabel);

        configureTable();
        content.getChildren().addAll(toolbar, table);
        getChildren().addAll(titleBar, content);
        getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
    }

    private void configureTable() {
        table.setPlaceholder(new Label("暂无下载任务，请点击“添加下载”创建任务"));
        table.setStyle("-fx-font-size: 12px; -fx-background-color: #ffffff;");
        table.setFixedCellSize(42);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<DownloadRow, String> nameColumn = new TableColumn<>("文件名");
        nameColumn.setCellValueFactory(cell -> cell.getValue().fileName);
        nameColumn.setPrefWidth(235);
        nameColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                DownloadRow row = !empty && getTableRow() != null ? getTableRow().getItem() : null;
                if (row == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(value);
                    setTooltip(new Tooltip(row.url));
                }
            }
        });

        TableColumn<DownloadRow, String> sizeColumn = new TableColumn<>("大小");
        sizeColumn.setCellValueFactory(cell -> cell.getValue().sizeText);
        sizeColumn.setPrefWidth(90);

        TableColumn<DownloadRow, Number> progressColumn = new TableColumn<>("进度");
        progressColumn.setCellValueFactory(cell -> cell.getValue().progress);
        progressColumn.setPrefWidth(190);
        progressColumn.setCellFactory(column -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label label = new Label();
            private final StackPane graphic = new StackPane(bar, label);
            {
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.setPrefHeight(18);
                bar.setStyle("-fx-accent: #1976D2;");
                label.setStyle("-fx-font-size: 10px; -fx-text-fill: #333;");
                graphic.setMaxWidth(Double.MAX_VALUE);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                DownloadRow row = !empty && getTableRow() != null ? getTableRow().getItem() : null;
                if (row == null || value == null) {
                    setGraphic(null);
                } else {
                    double progress = value.doubleValue();
                    bar.setProgress(progress);
                    label.setText(progress < 0 ? "获取信息..." : String.format("%.1f%%", progress * 100));
                    setGraphic(graphic);
                }
            }
        });

        TableColumn<DownloadRow, String> amountColumn = new TableColumn<>("已下载");
        amountColumn.setCellValueFactory(cell -> cell.getValue().amountText);
        amountColumn.setPrefWidth(105);

        TableColumn<DownloadRow, String> speedColumn = new TableColumn<>("速度");
        speedColumn.setCellValueFactory(cell -> cell.getValue().speedText);
        speedColumn.setPrefWidth(90);

        TableColumn<DownloadRow, String> threadColumn = new TableColumn<>("线程");
        threadColumn.setCellValueFactory(cell -> cell.getValue().threadText);
        threadColumn.setPrefWidth(105);

        TableColumn<DownloadRow, String> statusColumn = new TableColumn<>("状态");
        statusColumn.setCellValueFactory(cell -> cell.getValue().statusText);
        statusColumn.setPrefWidth(120);

        TableColumn<DownloadRow, TaskState> actionColumn = new TableColumn<>("操作");
        actionColumn.setCellValueFactory(cell -> cell.getValue().state);
        actionColumn.setPrefWidth(80);
        actionColumn.setCellFactory(column -> new TableCell<>() {
            private final Button action = secondaryButton("");
            {
                action.setStyle(action.getStyle() + " -fx-font-size: 11px; -fx-padding: 3 10;");
                action.setOnAction(event -> {
                    DownloadRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null) handleRowAction(row);
                });
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            @Override
            protected void updateItem(TaskState state, boolean empty) {
                super.updateItem(state, empty);
                if (empty || state == null) {
                    setGraphic(null);
                    return;
                }
                action.setText(switch (state) {
                    case WAITING, DOWNLOADING -> "取消";
                    case CANCELLING -> "取消中";
                    case COMPLETED -> "打开";
                    case FAILED, CANCELLED -> "重试";
                });
                action.setDisable(state == TaskState.CANCELLING);
                setGraphic(action);
            }
        });

        table.getColumns().addAll(nameColumn, sizeColumn, progressColumn, amountColumn,
                speedColumn, threadColumn, statusColumn, actionColumn);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) ->
                removeButton.setDisable(table.getSelectionModel().getSelectedItems().isEmpty()));
        table.setRowFactory(view -> {
            TableRow<DownloadRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()
                        && row.getItem().state.get() == TaskState.COMPLETED) {
                    openTaskFolder(row.getItem());
                }
            });
            return row;
        });
    }

    private void showAddDownloadDialog() {
        Dialog<DownloadRequest> dialog = new Dialog<>();
        dialog.setTitle("添加下载");
        dialog.setHeaderText("添加新的下载任务");
        initializeDialog(dialog);

        TextField urlInput = new TextField();
        urlInput.setPromptText("HTTP/HTTPS、magnet、ED2K 或 .torrent 文件");
        urlInput.setPrefWidth(430);
        String clipboardUrl = clipboardDownloadSource();
        if (clipboardUrl != null) urlInput.setText(clipboardUrl);
        TextField directoryInput = new TextField(preferredDownloadDirectory().toString());
        Spinner<Integer> threadsInput = integerSpinner(MultiThreadDownloader.MIN_THREADS,
                MultiThreadDownloader.MAX_THREADS, defaultThreadCount(), 90);
        threadsInput.setTooltip(new Tooltip("HTTP 使用分段线程数；BT/ED2K 使用并行活跃节点数（1-32）"));
        Label errorLabel = dialogErrorLabel();

        Button browseButton = secondaryButton("浏览...");
        browseButton.setOnAction(event -> chooseDirectory(directoryInput,
                dialog.getDialogPane().getScene() == null ? null
                        : dialog.getDialogPane().getScene().getWindow()));
        Button torrentButton = secondaryButton("选择种子...");
        torrentButton.setOnAction(event -> chooseTorrentFile(urlInput,
                dialog.getDialogPane().getScene() == null ? null
                        : dialog.getDialogPane().getScene().getWindow()));

        GridPane form = dialogForm();
        form.add(new Label("下载地址"), 0, 0);
        form.add(urlInput, 1, 0);
        form.add(torrentButton, 2, 0);
        form.add(new Label("保存目录"), 0, 1);
        form.add(directoryInput, 1, 1);
        form.add(browseButton, 2, 1);
        form.add(new Label("并行数"), 0, 2);
        form.add(threadsInput, 1, 2);
        form.add(errorLabel, 1, 3, 2, 1);
        GridPane.setHgrow(urlInput, Priority.ALWAYS);
        GridPane.setHgrow(directoryInput, Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);

        ButtonType addType = new ButtonType("添加下载", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);
        Node addButton = dialog.getDialogPane().lookupButton(addType);
        stylePrimaryButton(addButton);
        addButton.addEventFilter(ActionEvent.ACTION, event -> {
            String error = validateDownloadInput(urlInput, directoryInput);
            if (error != null) {
                errorLabel.setText(error);
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == addType
                ? new DownloadRequest(urlInput.getText().trim(), pathFromField(directoryInput),
                MultiThreadDownloader.clampThreads(readSpinnerValue(threadsInput,
                        defaultThreadCount())))
                : null);
        dialog.setOnShown(event -> Platform.runLater(urlInput::requestFocus));
        dialog.showAndWait().ifPresent(this::addTask);
    }

    private void showSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("下载器设置");
        dialog.setHeaderText("设置下载器默认值");
        initializeDialog(dialog);

        TextField directoryInput = new TextField(preferredDownloadDirectory().toString());
        Spinner<Integer> threadsInput = integerSpinner(MultiThreadDownloader.MIN_THREADS,
                MultiThreadDownloader.MAX_THREADS, defaultThreadCount(), 90);
        Spinner<Integer> concurrentInput = integerSpinner(MIN_CONCURRENT_TASKS,
                MAX_CONCURRENT_TASKS, sharedConcurrentTaskLimit, 90);
        Label errorLabel = dialogErrorLabel();
        Label note = new Label("同时下载任务数修改后会立即应用，正在运行的任务不会被中断。");
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #777;");

        Button browseButton = secondaryButton("浏览...");
        browseButton.setOnAction(event -> chooseDirectory(directoryInput,
                dialog.getDialogPane().getScene() == null ? null
                        : dialog.getDialogPane().getScene().getWindow()));

        GridPane form = dialogForm();
        form.add(new Label("默认下载目录"), 0, 0);
        form.add(directoryInput, 1, 0);
        form.add(browseButton, 2, 0);
        form.add(new Label("默认任务线程"), 0, 1);
        form.add(threadsInput, 1, 1);
        form.add(new Label("同时下载任务"), 0, 2);
        form.add(concurrentInput, 1, 2);
        form.add(note, 1, 3, 2, 1);
        form.add(errorLabel, 1, 4, 2, 1);
        GridPane.setHgrow(directoryInput, Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);

        ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        stylePrimaryButton(saveButton);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            String error = validateDirectory(directoryInput);
            if (error != null) {
                errorLabel.setText(error);
                event.consume();
            }
        });

        dialog.showAndWait().filter(saveType::equals).ifPresent(button -> {
            Path directory = pathFromField(directoryInput);
            int threads = MultiThreadDownloader.clampThreads(readSpinnerValue(threadsInput,
                    defaultThreadCount()));
            int concurrent = clampConcurrentTasks(readSpinnerValue(concurrentInput,
                    sharedConcurrentTaskLimit));
            PREFERENCES.put(DEFAULT_DIRECTORY_KEY, directory.toString());
            PREFERENCES.putInt(DEFAULT_THREADS_KEY, threads);
            PREFERENCES.putInt(CONCURRENT_TASKS_KEY, concurrent);
            sharedConcurrentTaskLimit = concurrent;
            startWaitingTasks();
            refreshSummary();
        });
    }

    private void initializeDialog(Dialog<?> dialog) {
        Window owner = getScene() == null ? null : getScene().getWindow();
        if (owner != null) dialog.initOwner(owner);
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefWidth(590);
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/css/connect-tree.css").toExternalForm());
    }

    private static GridPane dialogForm() {
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(12);
        form.setPadding(new Insets(8, 2, 4, 2));
        return form;
    }

    private static Spinner<Integer> integerSpinner(int minimum, int maximum, int value,
                                                   double width) {
        Spinner<Integer> spinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                minimum, maximum, Math.max(minimum, Math.min(maximum, value))));
        spinner.setEditable(true);
        spinner.setPrefWidth(width);
        return spinner;
    }

    private static Label dialogErrorLabel() {
        Label label = new Label();
        label.setStyle("-fx-font-size: 11px; -fx-text-fill: #d32f2f;");
        label.setWrapText(true);
        return label;
    }

    private static void stylePrimaryButton(Node button) {
        if (button != null) {
            button.setStyle("-fx-background-color: #1976D2; -fx-text-fill: white; "
                    + "-fx-font-weight: bold; -fx-background-radius: 4; -fx-cursor: hand;");
        }
    }

    private static String validateDownloadInput(TextField urlInput, TextField directoryInput) {
        String url = urlInput.getText() == null ? "" : urlInput.getText().trim();
        if (url.isEmpty()) return "请输入下载地址";
        try {
            DownloadProtocol protocol = DownloadProtocol.detect(url);
            if (protocol == DownloadProtocol.BITTORRENT
                    && url.regionMatches(true, 0, "file:", 0, 5)) {
                Path torrent = Path.of(URI.create(url));
                if (!Files.isRegularFile(torrent)) return "选择的 .torrent 文件不存在";
            }
        } catch (Exception ex) {
            return "下载地址格式不正确，仅支持 HTTP/HTTPS、磁力链、.torrent 和 ED2K 文件链接";
        }
        return validateDirectory(directoryInput);
    }

    private static String clipboardDownloadSource() {
        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (!clipboard.hasString()) return null;
            String value = clipboard.getString();
            if (value == null || value.isBlank()) return null;
            value = value.trim();
            DownloadProtocol.detect(value);
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String validateDirectory(TextField directoryInput) {
        Path directory = pathFromField(directoryInput);
        if (directory == null) return "请选择有效的保存目录";
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
                return "保存目录不可写";
            }
            directoryInput.setText(directory.toString());
            return null;
        } catch (Exception ex) {
            return "无法使用保存目录: " + messageOf(ex);
        }
    }

    private void addTask(DownloadRequest request) {
        DownloadProtocol protocol = DownloadProtocol.detect(request.url());
        DownloadRow row = new DownloadRow(request.url(), request.directory(), request.threads(),
                guessFileName(request.url()), protocol);
        rows.add(row);
        waitingQueue.addLast(row);
        refreshSummary();
        startWaitingTasks();
    }

    private void startWaitingTasks() {
        int limit = sharedConcurrentTaskLimit;
        while (sharedRunningTasks < limit && !waitingQueue.isEmpty()) {
            DownloadRow row = waitingQueue.removeFirst();
            if (row.state.get() != TaskState.WAITING || !rows.contains(row)) continue;
            startTask(row);
        }
    }

    private void startTask(DownloadRow row) {
        sharedRunningTasks++;
        long attempt = ++row.attempt;
        DownloadEngine engine = switch (row.protocol) {
            case HTTP -> new HttpDownloadEngine();
            case BITTORRENT -> new TorrentDownloadEngine();
            case ED2K -> new Ed2kDownloadEngine();
        };
        row.engine = engine;
        row.state.set(TaskState.DOWNLOADING);
        boolean resuming = row.targetFile != null && row.downloadedBytes > 0;
        row.statusText.set(resuming ? "正在读取下载记录" : "正在连接");
        row.progress.set(resuming && row.totalBytes > 0
                ? (double) row.downloadedBytes / row.totalBytes : -1);
        row.speedText.set("0 B/s");
        if (!resuming) row.amountText.set("0 B");
        row.threadText.set(row.protocol.label() + " · 计划 " + row.requestedThreads);
        row.actualThreads = row.requestedThreads;
        refreshSummary();

        Thread task = new Thread(() -> runTask(row, engine, attempt),
                "Download-Task-" + attempt);
        task.setDaemon(true);
        task.start();
    }

    private void runTask(DownloadRow row, DownloadEngine engine, long attempt) {
        DownloadEngine.Result result = null;
        Throwable failure = null;
        try {
            result = engine.download(row.url, row.directory, row.requestedThreads,
                    createDownloadListener(row, attempt), row.targetFile);
        } catch (Throwable ex) {
            failure = ex;
        }
        DownloadEngine.Result finalResult = result;
        Throwable finalFailure = failure;
        Platform.runLater(() -> finishTask(row, engine, attempt, finalResult, finalFailure));
    }

    private DownloadEngine.Listener createDownloadListener(DownloadRow row, long attempt) {
        return new DownloadEngine.Listener() {
            private final DownloadSpeedEstimator speedEstimator = new DownloadSpeedEstimator();

            @Override
            public void onStatus(String message) {
                Platform.runLater(() -> {
                    if (isCurrentAttempt(row, attempt)) row.statusText.set(message);
                });
            }

            @Override
            public void onPrepared(DownloadEngine.Metadata metadata) {
                Platform.runLater(() -> {
                    if (!isCurrentAttempt(row, attempt)) return;
                    row.fileName.set(metadata.fileName());
                    row.targetFile = metadata.target();
                    row.totalBytes = metadata.totalBytes();
                    row.sizeText.set(metadata.totalBytes() >= 0
                            ? formatBytes(metadata.totalBytes()) : "未知");
                    row.progress.set(metadata.totalBytes() > 0
                            ? Math.min(1.0, (double) row.downloadedBytes / metadata.totalBytes()) : -1);
                    row.threadText.set(metadata.mode());
                    persistTasksNow();
                });
            }

            @Override
            public synchronized void onProgress(DownloadEngine.Progress progress) {
                long now = System.nanoTime();
                long speed = speedEstimator.update(progress.transferredBytes(), now);
                Platform.runLater(() -> {
                    if (!isCurrentAttempt(row, attempt)) return;
                    row.downloadedBytes = progress.downloadedBytes();
                    row.totalBytes = progress.totalBytes();
                    row.amountText.set(formatBytes(progress.downloadedBytes()));
                    row.speedText.set(formatBytes(speed) + "/s");
                    if (progress.totalBytes() > 0) {
                        row.progress.set(Math.min(1.0,
                                (double) progress.downloadedBytes() / progress.totalBytes()));
                    }
                    row.threadText.set(progress.detail());
                    persistTasksThrottled();
                });
            }
        };
    }

    private void finishTask(DownloadRow row, DownloadEngine engine, long attempt,
                            DownloadEngine.Result result, Throwable failure) {
        if (!isCurrentAttempt(row, attempt) || row.engine != engine) return;
        row.engine = null;
        sharedRunningTasks = Math.max(0, sharedRunningTasks - 1);
        row.speedText.set("—");
        if (result != null) {
            row.completedFile = result.file();
            row.targetFile = result.file();
            row.downloadedBytes = result.downloadedBytes();
            row.totalBytes = result.downloadedBytes();
            row.progress.set(1.0);
            row.amountText.set(formatBytes(result.downloadedBytes()));
            row.sizeText.set(formatBytes(result.downloadedBytes()));
            row.threadText.set(result.detail());
            row.statusText.set("已完成");
            row.state.set(TaskState.COMPLETED);
        } else if (failure instanceof CancellationException
                || row.state.get() == TaskState.CANCELLING) {
            row.statusText.set("已取消");
            row.state.set(TaskState.CANCELLED);
        } else {
            row.statusText.set("失败: " + messageOf(failure));
            row.state.set(TaskState.FAILED);
        }
        if (row.removeWhenFinished) {
            discardDownloadRecord(row);
            rows.remove(row);
        }
        refreshSummary();
        startWaitingTasks();
    }

    private void handleRowAction(DownloadRow row) {
        switch (row.state.get()) {
            case WAITING -> cancelWaitingTask(row);
            case DOWNLOADING -> {
                row.state.set(TaskState.CANCELLING);
                row.statusText.set("正在取消");
                DownloadEngine engine = row.engine;
                if (engine != null) engine.cancel();
            }
            case COMPLETED -> openTaskFolder(row);
            case FAILED, CANCELLED -> retryTask(row);
            case CANCELLING -> { }
        }
        refreshSummary();
    }

    private void cancelWaitingTask(DownloadRow row) {
        waitingQueue.remove(row);
        row.state.set(TaskState.CANCELLED);
        row.statusText.set("已取消");
        if (row.targetFile == null) row.progress.set(0);
        startWaitingTasks();
    }

    private void retryTask(DownloadRow row) {
        if (row.engine != null || waitingQueue.contains(row)) return;
        resetRow(row);
        waitingQueue.addLast(row);
        startWaitingTasks();
    }

    private void resetRow(DownloadRow row) {
        row.state.set(TaskState.WAITING);
        boolean resuming = row.targetFile != null && row.downloadedBytes > 0;
        row.statusText.set(resuming ? "等待续传" : "等待中");
        if (!resuming) {
            row.progress.set(0);
            row.amountText.set("0 B");
            row.downloadedBytes = 0;
            row.totalBytes = -1;
        }
        row.speedText.set("—");
        row.threadText.set(row.protocol.label() + " · 计划 " + row.requestedThreads);
        row.actualThreads = row.requestedThreads;
        row.completedFile = null;
        row.removeWhenFinished = false;
    }

    private void removeSelectedRows() {
        for (DownloadRow row : java.util.List.copyOf(table.getSelectionModel().getSelectedItems())) {
            waitingQueue.remove(row);
            DownloadEngine engine = row.engine;
            if (engine != null) {
                row.removeWhenFinished = true;
                row.state.set(TaskState.CANCELLING);
                row.statusText.set("正在取消并移除");
                engine.cancel();
            } else {
                discardDownloadRecord(row);
                rows.remove(row);
            }
        }
        refreshSummary();
    }

    private void clearFinishedRows() {
        rows.stream().filter(DownloadRow::isFinished).forEach(this::discardDownloadRecord);
        rows.removeIf(DownloadRow::isFinished);
        refreshSummary();
    }

    private void discardDownloadRecord(DownloadRow row) {
        if (row.state.get() != TaskState.COMPLETED && row.targetFile != null) {
            if (row.protocol == DownloadProtocol.HTTP) HttpDownloadEngine.discardRecord(row.targetFile);
            else if (row.protocol == DownloadProtocol.ED2K) Ed2kDownloadEngine.discardRecord(row.targetFile);
        }
    }

    private void refreshSummary() {
        persistTasksNow();
        refreshAllViews();
    }

    private void refreshOwnSummary() {
        if (rows.isEmpty()) {
            summaryLabel.setText("暂无下载任务");
        } else {
            long waiting = rows.stream().filter(row -> row.state.get() == TaskState.WAITING).count();
            long completed = rows.stream().filter(row -> row.state.get() == TaskState.COMPLETED).count();
            long failed = rows.stream().filter(row -> row.state.get() == TaskState.FAILED).count();
            summaryLabel.setText("下载中 " + sharedRunningTasks + " / " + sharedConcurrentTaskLimit
                    + " · 等待 " + waiting + " · 完成 " + completed + " · 失败 " + failed
                    + (taskStoreError == null ? "" : " · 记录保存失败"));
        }
        clearFinishedButton.setDisable(rows.stream().noneMatch(DownloadRow::isFinished));
    }

    private void openTaskFolder(DownloadRow row) {
        Path file = row.completedFile;
        if (file == null || !Desktop.isDesktopSupported()) return;
        try {
            Path folder = Files.isDirectory(file) ? file : file.getParent();
            if (folder != null) Desktop.getDesktop().open(folder.toFile());
        } catch (Exception ex) {
            row.statusText.set("无法打开目录: " + messageOf(ex));
        }
    }

    private static void chooseDirectory(TextField directoryInput, Window owner) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择下载保存目录");
        Path current = pathFromField(directoryInput);
        if (current != null && Files.isDirectory(current)) chooser.setInitialDirectory(current.toFile());
        File selected = chooser.showDialog(owner);
        if (selected != null) directoryInput.setText(selected.getAbsolutePath());
    }

    private static void chooseTorrentFile(TextField sourceInput, Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 BT 种子文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("BT 种子文件", "*.torrent"));
        File selected = chooser.showOpenDialog(owner);
        if (selected != null) sourceInput.setText(selected.toURI().toString());
    }

    private static int defaultThreadCount() {
        return MultiThreadDownloader.clampThreads(PREFERENCES.getInt(
                DEFAULT_THREADS_KEY, MultiThreadDownloader.DEFAULT_THREADS));
    }

    private static void ensureSessionLoaded() {
        if (sessionLoaded) return;
        sessionLoaded = true;
        for (DownloadTaskStore.TaskRecord record : DownloadTaskStore.load()) {
            DownloadRow row = restoreRow(record);
            if (row == null) continue;
            SHARED_ROWS.add(row);
            if (row.state.get() == TaskState.WAITING) SHARED_WAITING_QUEUE.addLast(row);
        }
    }

    private static DownloadRow restoreRow(DownloadTaskStore.TaskRecord record) {
        try {
            if (record == null || record.url() == null || record.url().isBlank()
                    || record.directory() == null || record.directory().isBlank()) return null;
            DownloadProtocol protocol;
            try {
                protocol = record.protocol() == null || record.protocol().isBlank()
                        ? DownloadProtocol.detect(record.url())
                        : DownloadProtocol.valueOf(record.protocol());
            } catch (Exception ignored) {
                return null;
            }
            Path directory = Path.of(record.directory()).toAbsolutePath().normalize();
            String fileName = record.fileName() == null || record.fileName().isBlank()
                    ? guessFileName(record.url()) : record.fileName();
            DownloadRow row = new DownloadRow(record.url(), directory,
                    MultiThreadDownloader.clampThreads(record.requestedThreads()), fileName, protocol);
            row.downloadedBytes = Math.max(0L, record.downloadedBytes());
            row.totalBytes = record.totalBytes();
            row.targetFile = pathOrNull(record.targetFile());
            row.completedFile = pathOrNull(record.completedFile());
            row.actualThreads = Math.max(1, record.actualThreads());
            row.rebalanceCount = Math.max(0, record.rebalanceCount());

            TaskState savedState;
            try {
                savedState = TaskState.valueOf(record.state());
            } catch (Exception ignored) {
                savedState = TaskState.CANCELLED;
            }
            if (savedState == TaskState.DOWNLOADING || savedState == TaskState.WAITING) {
                row.state.set(TaskState.WAITING);
                row.statusText.set(row.targetFile != null ? "等待续传" : "等待中");
            } else if (savedState == TaskState.CANCELLING) {
                row.state.set(TaskState.CANCELLED);
                row.statusText.set("已取消");
            } else if (savedState == TaskState.COMPLETED
                    && row.completedFile != null && Files.exists(row.completedFile)) {
                row.state.set(TaskState.COMPLETED);
                row.statusText.set("已完成");
                row.progress.set(1.0);
            } else if (savedState == TaskState.COMPLETED) {
                row.state.set(TaskState.FAILED);
                row.statusText.set("已完成文件不存在");
                row.completedFile = null;
            } else {
                row.state.set(savedState);
                row.statusText.set(record.status() == null || record.status().isBlank()
                        ? (savedState == TaskState.FAILED ? "下载失败" : "已取消")
                        : record.status());
            }

            row.sizeText.set(row.totalBytes >= 0 ? formatBytes(row.totalBytes) : "未知");
            row.amountText.set(formatBytes(row.downloadedBytes));
            if (row.state.get() != TaskState.COMPLETED) {
                row.progress.set(row.totalBytes > 0
                        ? Math.min(1.0, (double) row.downloadedBytes / row.totalBytes) : 0);
            }
            row.speedText.set("—");
            row.threadText.set(row.state.get() == TaskState.WAITING
                    ? row.protocol.label() + " · 计划 " + row.requestedThreads
                    : row.protocol == DownloadProtocol.HTTP
                    ? row.actualThreads + "线程 · 分配" + row.rebalanceCount
                    : row.protocol.label() + " · " + row.actualThreads + "连接");
            return row;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void persistTasksThrottled() {
        long now = System.nanoTime();
        if (lastTaskStoreNanos != 0L
                && now - lastTaskStoreNanos < TASK_STORE_INTERVAL_NANOS) return;
        persistTasksNow();
    }

    private static void persistTasksNow() {
        List<DownloadTaskStore.TaskRecord> records = SHARED_ROWS.stream()
                .filter(row -> !row.removeWhenFinished)
                .map(DownloadRow::toRecord)
                .toList();
        try {
            DownloadTaskStore.save(records);
            lastTaskStoreNanos = System.nanoTime();
            taskStoreError = null;
        } catch (Exception ex) {
            taskStoreError = messageOf(ex);
        }
    }

    private static void refreshAllViews() {
        Iterator<WeakReference<DownloaderPane>> iterator = OPEN_VIEWS.iterator();
        while (iterator.hasNext()) {
            DownloaderPane view = iterator.next().get();
            if (view == null) iterator.remove();
            else view.refreshOwnSummary();
        }
    }

    private static int readSpinnerValue(Spinner<Integer> spinner, int fallback) {
        try {
            return Integer.parseInt(spinner.getEditor().getText().trim());
        } catch (Exception ignored) {
            Integer value = spinner.getValue();
            return value != null ? value : fallback;
        }
    }

    private static Path pathFromField(TextField field) {
        try {
            String value = field.getText();
            return value == null || value.isBlank() ? null : Path.of(value.trim()).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isCurrentAttempt(DownloadRow row, long attempt) {
        TaskState state = row.state.get();
        return row.attempt == attempt
                && (state == TaskState.DOWNLOADING || state == TaskState.CANCELLING);
    }

    private static int clampConcurrentTasks(int value) {
        return Math.max(MIN_CONCURRENT_TASKS, Math.min(MAX_CONCURRENT_TASKS, value));
    }

    private static String guessFileName(String url) {
        try {
            DownloadProtocol protocol = DownloadProtocol.detect(url);
            if (protocol == DownloadProtocol.ED2K) {
                String[] parts = url.split("\\|");
                if (parts.length >= 3 && !parts[2].isBlank()) {
                    return URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
                }
            }
            if (url.regionMatches(true, 0, "magnet:", 0, 7)) {
                String query = URI.create(url).getRawQuery();
                if (query != null) {
                    for (String part : query.split("&")) {
                        if (part.regionMatches(true, 0, "dn=", 0, 3)) {
                            return URLDecoder.decode(part.substring(3), StandardCharsets.UTF_8);
                        }
                    }
                }
                return "正在获取种子信息...";
            }
            String path = URI.create(url).getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                String name = slash + 1 < path.length() ? path.substring(slash + 1) : "";
                if (!name.isBlank()) return URLDecoder.decode(name, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return "正在获取文件名...";
    }

    private static Path defaultDownloadDirectory() {
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        return Files.isDirectory(downloads) ? downloads : Path.of(System.getProperty("user.home"));
    }

    private static Path preferredDownloadDirectory() {
        String saved = PREFERENCES.get(DEFAULT_DIRECTORY_KEY, "");
        if (!saved.isBlank()) {
            try {
                Path directory = Path.of(saved).toAbsolutePath().normalize();
                if (Files.isDirectory(directory) && Files.isWritable(directory)) return directory;
            } catch (Exception ignored) {
            }
        }
        return defaultDownloadDirectory();
    }

    private static Button secondaryButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #d0d0d0; "
                + "-fx-background-radius: 4; -fx-border-radius: 4; -fx-cursor: hand;");
        return button;
    }

    private static SVGPath downloadIcon() {
        SVGPath path = new SVGPath();
        path.setContent("M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.8);
        path.setScaleY(0.8);
        return path;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String messageOf(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return message != null && !message.isBlank() ? message : error.getClass().getSimpleName();
    }

    private enum TaskState {
        WAITING, DOWNLOADING, CANCELLING, COMPLETED, FAILED, CANCELLED
    }

    private record DownloadRequest(String url, Path directory, int threads) {
    }

    private static final class DownloadRow {
        private final String url;
        private final Path directory;
        private final int requestedThreads;
        private final StringProperty fileName;
        private final StringProperty sizeText = new SimpleStringProperty("未知");
        private final DoubleProperty progress = new SimpleDoubleProperty(0);
        private final StringProperty amountText = new SimpleStringProperty("0 B");
        private final StringProperty speedText = new SimpleStringProperty("—");
        private final StringProperty threadText;
        private final StringProperty statusText = new SimpleStringProperty("等待中");
        private final ObjectProperty<TaskState> state = new SimpleObjectProperty<>(TaskState.WAITING);
        private final DownloadProtocol protocol;
        private volatile DownloadEngine engine;
        private volatile Path completedFile;
        private volatile Path targetFile;
        private volatile long attempt;
        private volatile long downloadedBytes;
        private volatile long totalBytes = -1;
        private volatile int actualThreads;
        private volatile int rebalanceCount;
        private volatile boolean removeWhenFinished;

        private DownloadRow(String url, Path directory, int requestedThreads, String fileName,
                            DownloadProtocol protocol) {
            this.url = url;
            this.directory = directory;
            this.requestedThreads = requestedThreads;
            this.actualThreads = requestedThreads;
            this.protocol = protocol;
            this.fileName = new SimpleStringProperty(fileName);
            this.threadText = new SimpleStringProperty(protocol.label() + " · 计划 " + requestedThreads);
        }

        private boolean isFinished() {
            TaskState current = state.get();
            return current == TaskState.COMPLETED || current == TaskState.FAILED
                    || current == TaskState.CANCELLED;
        }

        private DownloadTaskStore.TaskRecord toRecord() {
            return new DownloadTaskStore.TaskRecord(url, protocol.name(), directory.toString(), requestedThreads,
                    fileName.get(), downloadedBytes, totalBytes,
                    targetFile == null ? null : targetFile.toString(),
                    completedFile == null ? null : completedFile.toString(),
                    state.get().name(), statusText.get(), actualThreads, rebalanceCount);
        }
    }
}
