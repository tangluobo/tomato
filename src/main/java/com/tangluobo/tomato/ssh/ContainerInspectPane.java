package com.tangluobo.tomato.ssh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.control.Alert;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Docker 容器属性面板（docker inspect）
 * 以独立标签页形式展示容器详细参数：
 * - 基本信息（只读）：名称/ID/镜像/状态/创建时间/启动命令
 * - 重启策略（可修改）：docker update --restart=no|on-failure[:N]|unless-stopped|always
 * - 资源限制（可修改）：docker update --memory / --cpus / --cpu-shares
 * - 端口映射 / 环境变量 / 挂载 / 网络（只读）
 * 持有独立的 SSH 会话，由标签页关闭时统一断开。
 */
public class ContainerInspectPane extends BorderPane {

    private final SSHSession sshSession;
    private final String containerName;
    private final String dockerPrefix;

    private final Label statusLabel;

    // 基本信息（只读）
    private final GridPane infoGrid;

    // 重启策略（可修改）
    private final ComboBox<String> restartPolicyCombo;
    private final Spinner<Integer> retryCountSpinner;
    private final Button applyRestartBtn;

    // 资源限制（可修改）
    private final TextField memoryField;
    private final TextField cpusField;
    private final TextField cpuSharesField;
    private final Button applyResourceBtn;

    // 只读明细区
    private final TextArea portsArea;
    private final TextArea envArea;
    private final TextArea mountsArea;
    private final TextArea networkArea;

    // 最近一次 inspect 的 HostConfig（用于计算 memory-swap 调整）
    private JsonObject lastHostConfig;

    public ContainerInspectPane(SSHSession sshSession, String containerName, String dockerPrefix) {
        this.sshSession = sshSession;
        this.containerName = containerName;
        this.dockerPrefix = dockerPrefix != null && !dockerPrefix.isEmpty() ? dockerPrefix : "docker";

        setStyle("-fx-background-color: #FFFFFF;");

        // ===== 顶部工具栏 =====
        HBox topBar = new HBox(8);
        topBar.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 4 8; -fx-alignment: center-left;");

        Label titleLabel = new Label("容器属性 - " + containerName);
        titleLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshBtn = new Button();
        refreshBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
        ImageView refreshIcon = new ImageView(
                new Image(getClass().getResourceAsStream("/images/connect/refresh.png")));
        refreshIcon.setFitWidth(16);
        refreshIcon.setFitHeight(16);
        refreshBtn.setGraphic(refreshIcon);
        refreshBtn.setTooltip(new Tooltip("刷新"));
        refreshBtn.setOnAction(e -> {
            refreshBtn.setDisable(true);
            new Thread(() -> {
                try {
                    refresh();
                } finally {
                    Platform.runLater(() -> refreshBtn.setDisable(false));
                }
            }, "Docker-InspectRefreshBtn").start();
        });

        topBar.getChildren().addAll(titleLabel, spacer, refreshBtn);
        setTop(topBar);

        // ===== 内容区（整体滚动） =====
        VBox content = new VBox(10);
        content.setPadding(new Insets(10, 12, 10, 12));
        content.setStyle("-fx-background-color: #FFFFFF;");

        // --- 基本信息（只读） ---
        infoGrid = new GridPane();
        infoGrid.setHgap(12);
        infoGrid.setVgap(6);
        ColumnConstraints keyCol = new ColumnConstraints();
        keyCol.setMinWidth(70);
        keyCol.setPrefWidth(80);
        ColumnConstraints valCol = new ColumnConstraints();
        valCol.setHgrow(Priority.ALWAYS);
        infoGrid.getColumnConstraints().addAll(keyCol, valCol);
        content.getChildren().addAll(buildSectionTitle("基本信息"), infoGrid);

        // --- 重启策略（可修改） ---
        restartPolicyCombo = new ComboBox<>();
        restartPolicyCombo.getItems().addAll("no", "on-failure", "unless-stopped", "always");
        restartPolicyCombo.setPrefWidth(140);

        retryCountSpinner = new Spinner<>(0, 100, 0);
        retryCountSpinner.setPrefWidth(70);
        retryCountSpinner.setEditable(true);
        retryCountSpinner.setDisable(true);

        Label retryLabel = new Label("重试次数:");
        restartPolicyCombo.valueProperty().addListener((obs, oldVal, newVal) ->
                retryCountSpinner.setDisable(!"on-failure".equals(newVal)));

        applyRestartBtn = new Button("应用");
        applyRestartBtn.setOnAction(e -> applyRestartPolicy());

        HBox restartRow = new HBox(8,
                new Label("策略:"), restartPolicyCombo,
                retryLabel, retryCountSpinner, applyRestartBtn);
        restartRow.setAlignment(Pos.CENTER_LEFT);

        Label restartHint = new Label("等效命令: " + this.dockerPrefix + " update --restart=<策略>[:重试次数] "
                + containerName + "   （如 --restart=unless-stopped）");
        restartHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");

        VBox restartBox = new VBox(6, restartRow, restartHint);
        content.getChildren().addAll(buildSectionTitle("重启策略"), restartBox);

        // --- 资源限制（可修改） ---
        memoryField = new TextField();
        memoryField.setPrefWidth(90);
        memoryField.setPromptText("如 512m");

        cpusField = new TextField();
        cpusField.setPrefWidth(70);
        cpusField.setPromptText("如 1.5");

        cpuSharesField = new TextField();
        cpuSharesField.setPrefWidth(80);
        cpuSharesField.setPromptText("如 1024");

        applyResourceBtn = new Button("应用");
        applyResourceBtn.setOnAction(e -> applyResourceLimits());

        HBox resourceRow = new HBox(8,
                new Label("内存:"), memoryField,
                new Label("CPU数:"), cpusField,
                new Label("CPU份额:"), cpuSharesField,
                applyResourceBtn);
        resourceRow.setAlignment(Pos.CENTER_LEFT);

        Label resourceHint = new Label("等效命令: " + this.dockerPrefix + " update --memory=512m --cpus=1.5 --cpu-shares=1024 "
                + containerName + "   （留空/清零表示不修改该项；内存支持 b/k/m/g 后缀）");
        resourceHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        resourceHint.setWrapText(true);

        VBox resourceBox = new VBox(6, resourceRow, resourceHint);
        content.getChildren().addAll(buildSectionTitle("资源限制"), resourceBox);

        // --- 只读明细区 ---
        portsArea = buildReadOnlyArea();
        envArea = buildReadOnlyArea();
        mountsArea = buildReadOnlyArea();
        networkArea = buildReadOnlyArea();
        content.getChildren().addAll(
                buildSectionTitle("端口映射"), portsArea,
                buildSectionTitle("环境变量"), envArea,
                buildSectionTitle("挂载"), mountsArea,
                buildSectionTitle("网络"), networkArea);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #FFFFFF;");
        scroll.getStyleClass().add("edge-to-edge");
        setCenter(scroll);

        // ===== 底部状态栏 =====
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888; -fx-padding: 2 8; -fx-background-color: #f5f5f5;");
        setBottom(statusLabel);
    }

    /**
     * 断开 SSH 会话（由标签页关闭时调用）
     */
    public void disconnect() {
        if (sshSession != null) {
            try { sshSession.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * 后台执行 docker inspect 并刷新界面
     */
    public void refresh() {
        if (sshSession == null || !sshSession.isConnected()) {
            Platform.runLater(() -> statusLabel.setText("SSH 未连接"));
            return;
        }
        new Thread(() -> {
            try {
                String output = executeCommand(dockerPrefix + " inspect " + containerName + " 2>&1");
                JsonObject root = parseInspect(output);
                if (root == null) {
                    Platform.runLater(() -> {
                        statusLabel.setText("获取容器属性失败（容器不存在或输出无法解析）");
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("获取失败");
                        alert.setHeaderText(null);
                        alert.setContentText(output == null || output.trim().isEmpty()
                                ? "无输出" : output.trim());
                        alert.showAndWait();
                    });
                    return;
                }
                Platform.runLater(() -> updateUi(root));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("获取容器属性失败: " + e.getMessage()));
            }
        }, "Docker-InspectRefresh").start();
    }

    // ==================== 界面更新 ====================

    /**
     * 根据 docker inspect 的 JSON 刷新所有区域
     */
    private void updateUi(JsonObject root) {
        JsonObject config = safeObject(root, "Config");
        JsonObject state = safeObject(root, "State");
        JsonObject hostConfig = safeObject(root, "HostConfig");
        JsonObject networkSettings = safeObject(root, "NetworkSettings");
        lastHostConfig = hostConfig;

        // --- 基本信息 ---
        infoGrid.getChildren().clear();
        int row = 0;
        row = addInfo(row, "名称", str(root, "Name"));
        row = addInfo(row, "容器ID", str(root, "Id"));
        row = addInfo(row, "镜像", str(config, "Image"));
        String statusText = str(state, "Status");
        if (statusText.isEmpty()) statusText = str(root, "Status");
        row = addInfo(row, "状态", statusText);
        row = addInfo(row, "创建时间", str(root, "Created"));
        row = addInfo(row, "启动命令", buildCommand(config));

        // --- 重启策略 ---
        JsonObject rp = safeObject(hostConfig, "RestartPolicy");
        String policyName = str(rp, "Name");
        if (policyName.isEmpty()) policyName = "no";
        if (!restartPolicyCombo.getItems().contains(policyName)) policyName = "no";
        restartPolicyCombo.setValue(policyName);
        int retries = intVal(rp, "MaximumRetryCount");
        retryCountSpinner.getValueFactory().setValue(retries);
        retryCountSpinner.setDisable(!"on-failure".equals(policyName));

        // --- 资源限制 ---
        long memory = longVal(hostConfig, "Memory");
        memoryField.setText(memory > 0 ? formatMemory(memory) : "");
        long nanoCpus = longVal(hostConfig, "NanoCpus");
        cpusField.setText(nanoCpus > 0 ? trimDouble(nanoCpus / 1_000_000_000.0) : "");
        long cpuShares = longVal(hostConfig, "CpuShares");
        cpuSharesField.setText(cpuShares > 0 ? String.valueOf(cpuShares) : "");

        // --- 只读明细 ---
        portsArea.setText(formatPorts(hostConfig));
        envArea.setText(formatList(config, "Env"));
        mountsArea.setText(formatMounts(root));
        networkArea.setText(formatNetwork(hostConfig, networkSettings, config));

        statusLabel.setText("已刷新");
    }

    /**
     * 添加一行基本信息，返回下一行行号
     */
    private int addInfo(int row, String key, String value) {
        Label k = new Label(key);
        k.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        Label v = new Label(value == null || value.isEmpty() ? "-" : value);
        v.setWrapText(true);
        v.setStyle("-fx-text-fill: #333; -fx-font-size: 11px;");
        infoGrid.add(k, 0, row);
        infoGrid.add(v, 1, row);
        return row + 1;
    }

    // ==================== 修改操作 ====================

    /**
     * 应用重启策略修改：docker update --restart=<policy>[:<retries>]
     */
    private void applyRestartPolicy() {
        String policy = restartPolicyCombo.getValue();
        if (policy == null || policy.isEmpty()) {
            statusLabel.setText("请选择重启策略");
            return;
        }
        String value = policy;
        if ("on-failure".equals(policy)) {
            Integer retries = retryCountSpinner.getValue();
            if (retries != null && retries > 0) {
                value = "on-failure:" + retries;
            }
        }
        final String restartValue = value;
        applyRestartBtn.setDisable(true);
        new Thread(() -> {
            String cmd = dockerPrefix + " update --restart=" + restartValue + " " + containerName + " 2>&1; echo EXIT:$?";
            String result = executeCommandSafe(cmd);
            boolean ok = result != null && result.contains("EXIT:0");
            Platform.runLater(() -> {
                applyRestartBtn.setDisable(false);
                if (ok) {
                    statusLabel.setText("重启策略已更新: --restart=" + restartValue);
                    refresh();
                } else {
                    showError("修改重启策略失败", result);
                }
            });
        }, "Docker-UpdateRestart").start();
    }

    /**
     * 应用资源限制修改：docker update --memory/--cpus/--cpu-shares
     * 留空的项不修改；内存清零/清空传 --memory=0 取消限制。
     */
    private void applyResourceLimits() {
        String memStr = memoryField.getText() == null ? "" : memoryField.getText().trim();
        String cpusStr = cpusField.getText() == null ? "" : cpusField.getText().trim();
        String sharesStr = cpuSharesField.getText() == null ? "" : cpuSharesField.getText().trim();

        List<String> args = new ArrayList<>();
        try {
            if (!memStr.isEmpty()) {
                long mem = parseMemoryToBytes(memStr);
                if (mem < 0) {
                    showError("输入错误", "内存格式无效: " + memStr + "（示例: 512m / 2g / 104857600）");
                    return;
                }
                args.add("--memory=" + mem);
                // memory-swap 联动：保留原 swap 额度，避免部分系统报
                // "Minimum memoryswap limit should be larger than memory limit"
                if (mem == 0) {
                    args.add("--memory-swap=-1");
                } else {
                    long oldSwap = lastHostConfig != null ? longVal(lastHostConfig, "MemorySwap") : 0;
                    long oldMem = lastHostConfig != null ? longVal(lastHostConfig, "Memory") : 0;
                    if (oldSwap == -1) {
                        args.add("--memory-swap=-1");
                    } else if (oldSwap > 0) {
                        long swapExtra = Math.max(0, oldSwap - oldMem);
                        args.add("--memory-swap=" + (mem + swapExtra));
                    } else {
                        args.add("--memory-swap=" + mem);
                    }
                }
            }
            if (!cpusStr.isEmpty()) {
                double cpus = Double.parseDouble(cpusStr);
                if (cpus < 0) throw new NumberFormatException();
                args.add("--cpus=" + trimDouble(cpus));
            }
            if (!sharesStr.isEmpty()) {
                long shares = Long.parseLong(sharesStr);
                if (shares < 0) throw new NumberFormatException();
                args.add("--cpu-shares=" + shares);
            }
        } catch (NumberFormatException e) {
            showError("输入错误", "CPU 数量/份额需为数字；内存支持 b/k/m/g 后缀（如 512m）");
            return;
        }

        if (args.isEmpty()) {
            statusLabel.setText("未输入任何修改项（留空表示不修改）");
            return;
        }

        applyResourceBtn.setDisable(true);
        new Thread(() -> {
            String cmd = dockerPrefix + " update " + String.join(" ", args) + " " + containerName + " 2>&1; echo EXIT:$?";
            String result = executeCommandSafe(cmd);
            boolean ok = result != null && result.contains("EXIT:0");
            Platform.runLater(() -> {
                applyResourceBtn.setDisable(false);
                if (ok) {
                    statusLabel.setText("资源限制已更新: " + String.join(" ", args));
                    refresh();
                } else {
                    showError("修改资源限制失败", result);
                }
            });
        }, "Docker-UpdateResource").start();
    }

    // ==================== 命令执行 ====================

    /**
     * 通过 SSH exec 通道执行命令并返回输出（stdout + stderr），异常时返回错误描述
     */
    private String executeCommandSafe(String command) {
        try {
            return executeCommand(command);
        } catch (Exception e) {
            return "执行异常: " + e.getMessage();
        }
    }

    private String executeCommand(String command) throws Exception {
        com.jcraft.jsch.ChannelExec channel = (com.jcraft.jsch.ChannelExec) sshSession.getJschSession().openChannel("exec");
        channel.setCommand(command);
        InputStream in = channel.getInputStream();
        InputStream err = channel.getErrStream();
        channel.connect();

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String ln;
        while ((ln = reader.readLine()) != null) {
            sb.append(ln).append("\n");
        }
        BufferedReader errReader = new BufferedReader(new InputStreamReader(err));
        while ((ln = errReader.readLine()) != null) {
            sb.append(ln).append("\n");
        }

        channel.disconnect();
        return sb.toString();
    }

    // ==================== JSON 解析与格式化 ====================

    /**
     * 解析 docker inspect 输出（JSON 数组取第一个元素）
     */
    private JsonObject parseInspect(String output) {
        if (output == null || output.trim().isEmpty()) return null;
        try {
            JsonElement el = JsonParser.parseString(output);
            if (el.isJsonArray() && el.getAsJsonArray().size() > 0) {
                JsonElement first = el.getAsJsonArray().get(0);
                return first.isJsonObject() ? first.getAsJsonObject() : null;
            }
            if (el.isJsonObject()) return el.getAsJsonObject();
        } catch (Exception ignored) {}
        return null;
    }

    private JsonObject safeObject(JsonObject parent, String key) {
        if (parent != null && parent.has(key) && parent.get(key).isJsonObject()) {
            return parent.getAsJsonObject(key);
        }
        return new JsonObject();
    }

    private String str(JsonObject obj, String key) {
        if (obj == null) return "";
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsString(); } catch (Exception ignored) {}
        }
        return "";
    }

    private long longVal(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsLong(); } catch (Exception e) { return 0; }
    }

    private int intVal(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }

    /**
     * 字节数转 docker 风格内存字符串（如 536870912 → 512m）
     */
    private String formatMemory(long bytes) {
        if (bytes <= 0) return "";
        long g = 1024L * 1024 * 1024;
        long m = 1024L * 1024;
        long k = 1024L;
        if (bytes % g == 0) return (bytes / g) + "g";
        if (bytes % m == 0) return (bytes / m) + "m";
        if (bytes % k == 0) return (bytes / k) + "k";
        return String.valueOf(bytes);
    }

    /**
     * 内存字符串转字节数（支持 b/k/m/g 后缀与纯字节数），非法返回 -1
     */
    private long parseMemoryToBytes(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase();
        if (s.isEmpty()) return -1;
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        if (Character.isLetter(last)) {
            switch (last) {
                case 'k' -> { mult = 1024L; s = s.substring(0, s.length() - 1); }
                case 'm' -> { mult = 1024L * 1024; s = s.substring(0, s.length() - 1); }
                case 'g' -> { mult = 1024L * 1024 * 1024; s = s.substring(0, s.length() - 1); }
                case 'b' -> { mult = 1L; s = s.substring(0, s.length() - 1); }
                default -> { return -1; }
            }
        }
        try {
            return Long.parseLong(s.trim()) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 去掉小数尾部多余的 0（如 1.0 → 1, 1.50 → 1.5） */
    private String trimDouble(double d) {
        if (d == (long) d) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    /**
     * 启动命令：Config.Path + Config.Args
     */
    private String buildCommand(JsonObject config) {
        String path = str(config, "Path");
        StringBuilder sb = new StringBuilder(path);
        if (config.has("Args") && config.get("Args").isJsonArray()) {
            for (JsonElement a : config.getAsJsonArray("Args")) {
                if (!a.isJsonNull()) {
                    sb.append(" ").append(a.getAsString());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 端口映射：HostConfig.PortBindings → "0.0.0.0:8080 -> 80/tcp"
     */
    private String formatPorts(JsonObject hostConfig) {
        StringBuilder sb = new StringBuilder();
        JsonObject pb = safeObject(hostConfig, "PortBindings");
        for (Map.Entry<String, JsonElement> e : pb.entrySet()) {
            String containerPort = e.getKey();
            if (e.getValue().isJsonArray()) {
                for (JsonElement b : e.getValue().getAsJsonArray()) {
                    if (b.isJsonObject()) {
                        JsonObject binding = b.getAsJsonObject();
                        String ip = str(binding, "HostIp");
                        if (ip.isEmpty()) ip = "0.0.0.0";
                        String port = str(binding, "HostPort");
                        if (port.isEmpty()) port = "?";
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(ip).append(":").append(port).append(" -> ").append(containerPort);
                    }
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "（无端口映射）";
    }

    /**
     * 字符串数组（如 Config.Env）逐行拼接
     */
    private String formatList(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : obj.getAsJsonArray(key)) {
            if (!el.isJsonNull()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(el.getAsString());
            }
        }
        return sb.length() > 0 ? sb.toString() : "（无）";
    }

    /**
     * 挂载：Mounts 数组 → "Source -> Destination (Mode)"
     */
    private String formatMounts(JsonObject root) {
        if (!root.has("Mounts") || !root.get("Mounts").isJsonArray()) {
            return "（无挂载）";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : root.getAsJsonArray("Mounts")) {
            if (!el.isJsonObject()) continue;
            JsonObject m = el.getAsJsonObject();
            String src = str(m, "Source");
            String dst = str(m, "Destination");
            String mode = str(m, "Mode");
            if (mode.isEmpty()) mode = boolVal(m, "RW") ? "rw" : "ro";
            String type = str(m, "Type");
            if (sb.length() > 0) sb.append("\n");
            sb.append(src).append(" -> ").append(dst)
              .append(" (").append(mode).append(type.isEmpty() ? "" : ", " + type).append(")");
        }
        return sb.length() > 0 ? sb.toString() : "（无挂载）";
    }

    private boolean boolVal(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return false;
        try { return obj.get(key).getAsBoolean(); } catch (Exception e) { return false; }
    }

    /**
     * 网络：网络模式 / 主机名 / 各网络 IP
     */
    private String formatNetwork(JsonObject hostConfig, JsonObject networkSettings, JsonObject config) {
        StringBuilder sb = new StringBuilder();
        String mode = str(hostConfig, "NetworkMode");
        if (!mode.isEmpty()) sb.append("网络模式: ").append(mode).append("\n");
        String hostname = str(config, "Hostname");
        if (!hostname.isEmpty()) sb.append("主机名: ").append(hostname).append("\n");
        String ip = str(networkSettings, "IPAddress");
        if (!ip.isEmpty()) sb.append("IP地址: ").append(ip).append("\n");
        JsonObject networks = safeObject(networkSettings, "Networks");
        for (Map.Entry<String, JsonElement> e : networks.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject n = e.getValue().getAsJsonObject();
            String nip = str(n, "IPAddress");
            if (!nip.isEmpty()) {
                sb.append("网络[").append(e.getKey()).append("] IP: ").append(nip).append("\n");
            }
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? "（无网络信息）" : text;
    }

    // ==================== UI 辅助 ====================

    /**
     * 分组标题（加粗 + 底部分隔线）
     */
    private VBox buildSectionTitle(String title) {
        VBox box = new VBox(2);
        Label l = new Label(title);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Separator sep = new Separator();
        box.getChildren().addAll(l, sep);
        return box;
    }

    /**
     * 只读明细文本区（等宽字体，自动换行，高度自适应）
     */
    private TextArea buildReadOnlyArea() {
        TextArea ta = new TextArea();
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(2);
        ta.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 11px; "
                + "-fx-text-fill: #333; -fx-background-color: #fafafa; -fx-control-inner-background: #fafafa;");
        return ta;
    }

    /**
     * 弹出错误对话框
     */
    private void showError(String title, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        String text = detail == null ? "无输出"
                : detail.replaceAll("EXIT:\\d+", "").trim();
        alert.setContentText(text.isEmpty() ? "无输出" : text);
        alert.showAndWait();
    }
}
