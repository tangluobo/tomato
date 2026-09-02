package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.DatabaseNodeData;
import com.tangluobo.tomato.module.connect.GlobalConfig;
import com.tangluobo.tomato.module.connect.SshTunnelManager;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import com.tangluobo.tomato.ssh.SFTPClient;
import com.tangluobo.tomato.module.connect.AbstractFileBrowserPane;
import com.tangluobo.tomato.ssh.ContainerInspectPane;
import com.tangluobo.tomato.ssh.SFTPFileBrowser;
import com.tangluobo.tomato.ssh.SSHSession;
import com.tangluobo.tomato.ssh.SSHTerminalPane;
import com.tangluobo.tomato.ssh.PortPanel;
import com.tangluobo.tomato.ssh.StoragePanel;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSH 终端连接处理器（默认分支）。
 * 完整封装 SSH 终端 tab 创建、密码输入、连接建立逻辑。
 */
public class SshTerminalConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.SSH;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        createSshTerminalTab(module, config);
    }

    /**
     * 双击 SSH 主机节点：
     * - 已展开（有子节点）则切换展开/收起状态；
     * - 否则打开 SSH 终端 tab，并在开启服务管理时在节点下添加
     *   终端/容器/服务/端口/存储/文件子节点。
     */
    @Override
    public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }
        createSshTerminalTab(module, config);
        if (GlobalConfig.getInstance().isSshServiceManagementEnabled()) {
            addServiceManagementNodes(module, hostItem, config);
        }
    }

    /**
     * 在 SSH 主机节点下添加服务管理子节点：终端/容器/服务/端口/存储/文件
     */
    private void addServiceManagementNodes(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        Platform.runLater(() -> {
            hostItem.getChildren().clear();
            addServiceChild(module, hostItem, config, "终端", DatabaseNodeData.NodeType.SSH_SERVICE_TERMINAL);
            addServiceChild(module, hostItem, config, "容器", DatabaseNodeData.NodeType.SSH_SERVICE_CONTAINER);
            addServiceChild(module, hostItem, config, "服务", DatabaseNodeData.NodeType.SSH_SERVICE_SERVICE);
            addServiceChild(module, hostItem, config, "端口", DatabaseNodeData.NodeType.SSH_SERVICE_PORT);
            addServiceChild(module, hostItem, config, "存储", DatabaseNodeData.NodeType.SSH_SERVICE_STORAGE);
            addServiceChild(module, hostItem, config, "文件", DatabaseNodeData.NodeType.SSH_SERVICE_FILE);
            hostItem.setExpanded(true);
            module.updateHostIcon(hostItem, config, true);
        });
    }

    private void addServiceChild(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config,
                                String name, DatabaseNodeData.NodeType type) {
        TreeItem<String> item = new TreeItem<>(name);
        DatabaseNodeData data = new DatabaseNodeData(type, name, config, null);
        item.setGraphic(module.getDbNodeIcon(data));
        module.getDbNodeDataMap().put(item, data);
        hostItem.getChildren().add(item);
    }

    public void handleStorageNodeDoubleClick(ConnectModule module, ConnectionConfig config) {
        if (!module.ensureTabPaneInstalled()) return;
        String tabId = "storage_" + config.getId();
        for (Tab existing : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(existing.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(existing);
                module.showTerminalView();
                return;
            }
        }
        String password = ensurePasswordAvailable(module, config);
        if (password == null) return;
        StoragePanel panel = new StoragePanel(command ->
                withTempSession(config, password, session -> executeCommand(session, command)));
        Tab tab = new Tab(config.getName() + " - 存储", panel);
        tab.setUserData(tabId);
        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();
        panel.refresh();
    }

    /**
     * 双击 SSH 主机下“端口”节点：在独立标签页中打开与终端右下角一致的端口面板。
     * 标签页持有独立 SSH 会话和跳板隧道租约，关闭时统一释放。
     */
    public void handlePortNodeDoubleClick(ConnectModule module, ConnectionConfig config) {
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = "ports_" + config.getId();
        for (Tab existing : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(existing.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(existing);
                module.showTerminalView();
                return;
            }
        }

        String password = ensurePasswordAvailable(module, config);
        if (password == null) return;

        Tab tab = new Tab(config.getName() + " - 端口");
        tab.setUserData(tabId);
        tab.setGraphic(ConnectModule.createFixedSizeGraphic(module.getDbNodeIcon(
                new DatabaseNodeData(DatabaseNodeData.NodeType.SSH_SERVICE_PORT,
                        "端口", config, null))));
        tab.setContent(new StackPane(new ProgressIndicator()));

        AtomicBoolean tabClosed = new AtomicBoolean(false);
        AtomicReference<SshTunnelManager.TunnelLease> tunnelLease = new AtomicReference<>();
        AtomicReference<SSHSession> sessionHolder = new AtomicReference<>();

        ContextMenu tabContextMenu = new ContextMenu();
        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            module.saveConnections();
        });
        MenuItem globalConfigItem = new MenuItem("全局配置");
        globalConfigItem.setOnAction(e -> module.openSettingsTabWithSshSelected());
        tabContextMenu.getItems().addAll(sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            tabClosed.set(true);
            SSHSession session = sessionHolder.getAndSet(null);
            if (session != null) {
                session.disconnect();
            }
            closeTunnelLease(tunnelLease);
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        new Thread(() -> {
            SSHSession sshSession = null;
            try {
                SshTunnelManager.TunnelLease acquiredLease = SshTunnelManager.acquire(config);
                if (tabClosed.get() || !tunnelLease.compareAndSet(null, acquiredLease)) {
                    acquiredLease.close();
                    return;
                }

                String host = config.getHost();
                int port = config.getPort();
                int tunnelLocalPort = acquiredLease.getLocalPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }

                List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
                sshSession = new SSHSession(host, port, config.getUsername(), password, keyPaths);
                sessionHolder.set(sshSession);
                sshSession.connect();

                SSHSession connectedSession = sshSession;
                Platform.runLater(() -> {
                    if (tabClosed.get()) {
                        connectedSession.disconnect();
                        closeTunnelLease(tunnelLease);
                        return;
                    }
                    PortPanel panel = new PortPanel(connectedSession, true);
                    tab.setContent(panel);
                    Thread refreshThread = new Thread(panel::refresh, "SSH-PortRefresh");
                    refreshThread.setDaemon(true);
                    refreshThread.start();
                });
            } catch (Exception e) {
                if (sshSession != null) {
                    try { sshSession.disconnect(); } catch (Exception ignored) {}
                }
                closeTunnelLease(tunnelLease);
                if (!tabClosed.get()) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText(null);
                        alert.setContentText("打开端口列表失败: " + e.getMessage());
                        alert.showAndWait();
                        module.getTerminalTabPane().getTabs().remove(tab);
                        if (module.getTerminalTabPane().getTabs().isEmpty()) {
                            module.showWelcomeView();
                        }
                    });
                }
                e.printStackTrace();
            }
        }, "SSH-PortConnect").start();
    }

    /**
     * 双击 SSH 主机下"文件"子节点：打开独立文件浏览器标签页。
     * 建立独立的 SSH 会话（复用跳板隧道引用计数），打开 SFTP 通道，
     * 以 SFTPFileBrowser（standalone 模式）作为标签页内容。
     */
    public void handleFileNodeDoubleClick(ConnectModule module, ConnectionConfig config) {
        if (!module.ensureTabPaneInstalled()) return;

        // 文件浏览器标签唯一标识，避免重复打开
        String tabId = "sftp_" + config.getId();
        for (Tab t : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(t.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(t);
                module.showTerminalView();
                return;
            }
        }

        Tab tab = new Tab(config.getName() + " - 文件");
        tab.setUserData(tabId);

        try {
            Image icon = new Image(getClass().getResourceAsStream("/images/connect/folder.png"));
            if (icon != null) {
                ImageView iv = new ImageView(icon);
                iv.setFitWidth(16);
                iv.setFitHeight(16);
                tab.setGraphic(ConnectModule.createFixedSizeGraphic(iv));
            }
        } catch (Exception ignored) {}

        // 占位内容，连接成功后替换
        StackPane loading = new StackPane(new ProgressIndicator());
        tab.setContent(loading);

        // 持有 browser 引用，供右键菜单使用
        final SFTPFileBrowser[] browserHolder = new SFTPFileBrowser[1];
        AtomicBoolean tabClosed = new AtomicBoolean(false);
        AtomicReference<SshTunnelManager.TunnelLease> tunnelLease = new AtomicReference<>();
        AtomicReference<SSHSession> sessionHolder = new AtomicReference<>();
        AtomicReference<SFTPClient> sftpHolder = new AtomicReference<>();

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            // 应用会话级视图模式（会话配置覆盖优先，否则用全局）
            SFTPFileBrowser browser = browserHolder[0];
            if (browser != null) {
                String modeStr = config.getDefaultFileViewMode();
                if (modeStr == null || modeStr.isEmpty()) {
                    modeStr = GlobalConfig.getInstance().getSshDefaultFileViewMode();
                }
                if (modeStr != null) {
                    try {
                        browser.setInitialViewMode(AbstractFileBrowserPane.ViewMode.valueOf(modeStr.toUpperCase()));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            module.saveConnections();
        });

        MenuItem globalConfigItem = new MenuItem("全局配置");
        globalConfigItem.setOnAction(e -> module.openSettingsTabWithSshSelected());

        tabContextMenu.getItems().addAll(sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);
        tab.setOnClosed(e -> {
            tabClosed.set(true);
            SFTPFileBrowser browser = browserHolder[0];
            if (browser != null) {
                browser.disconnect();
            } else {
                SFTPClient sftp = sftpHolder.getAndSet(null);
                if (sftp != null) try { sftp.disconnect(); } catch (Exception ignored) {}
                SSHSession session = sessionHolder.getAndSet(null);
                if (session != null) try { session.disconnect(); } catch (Exception ignored) {}
            }
            closeTunnelLease(tunnelLease);
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        // 后台建立 SSH 会话 + SFTP 通道
        new Thread(() -> {
            SSHSession sshSession = null;
            SFTPClient sftpClient = null;
            try {
                SshTunnelManager.TunnelLease acquiredLease = SshTunnelManager.acquire(config);
                if (tabClosed.get() || !tunnelLease.compareAndSet(null, acquiredLease)) {
                    acquiredLease.close();
                    return;
                }
                int tunnelLocalPort = acquiredLease.getLocalPort();

                String host = config.getHost();
                int port = config.getPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }

                List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
                sshSession = new SSHSession(host, port, config.getUsername(), config.getPassword(), keyPaths);
                sessionHolder.set(sshSession);
                sshSession.connect();

                sftpClient = new SFTPClient();
                sftpHolder.set(sftpClient);
                sftpClient.connect(sshSession.getJschSession());

                final SSHSession session = sshSession;
                final SFTPClient sftp = sftpClient;

                Platform.runLater(() -> {
                    if (tabClosed.get()) {
                        try { sftp.disconnect(); } catch (Exception ignored) {}
                        try { session.disconnect(); } catch (Exception ignored) {}
                        closeTunnelLease(tunnelLease);
                        return;
                    }
                    SFTPFileBrowser browser = new SFTPFileBrowser(session, sftp, true);
                    browserHolder[0] = browser;
                    // 设置初始视图模式：会话级覆盖优先，否则用全局配置
                    String modeStr = config.getDefaultFileViewMode();
                    if (modeStr == null || modeStr.isEmpty()) {
                        modeStr = GlobalConfig.getInstance().getSshDefaultFileViewMode();
                    }
                    if (modeStr != null) {
                        try {
                            browser.setInitialViewMode(AbstractFileBrowserPane.ViewMode.valueOf(modeStr.toUpperCase()));
                        } catch (IllegalArgumentException ignored) {
                            // 非法值时使用默认 LIST
                        }
                    }
                    tab.setContent(browser);
                    browser.initConnection();
                });
            } catch (Exception e) {
                // 连接失败：释放资源
                if (sftpClient != null) try { sftpClient.disconnect(); } catch (Exception ignored) {}
                if (sshSession != null) try { sshSession.disconnect(); } catch (Exception ignored) {}
                closeTunnelLease(tunnelLease);
                if (!tabClosed.get()) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText(null);
                        alert.setContentText("建立SFTP文件浏览器失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
                e.printStackTrace();
            }
        }, "SFTP-Connect").start();
    }

    // ==================== Docker 容器管理（树节点） ====================

    /**
     * 双击 SSH 主机下"容器"文件夹节点：
     * - 已加载（有子节点）则切换展开/收起状态；
     * - 否则建立临时 SSH 会话加载容器列表，运行中显示彩色图标，已停止显示灰色图标。
     */
    public void handleContainerFolderDoubleClick(ConnectModule module, TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadContainerList(module, folderItem, data.getConnectionConfig());
    }

    /**
     * 双击单个容器节点：
     * - 运行中：进入容器终端（新 SSH 终端标签页执行 docker exec）；
     * - 已停止：启动容器。
     */
    public void handleContainerNodeDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        if (data.isOpened()) {
            openContainerTerminalTab(module, data.getConnectionConfig(), data.getName(), data.getPath());
        } else {
            execDockerAction(module, item, data, "start", "启动");
        }
    }

    /**
     * 构建节点右键菜单：
     * - "容器"文件夹节点：刷新；
     * - 容器节点：已停止→启动；运行中→停止/重启/查看实时日志/进入终端。
     */
    @Override
    public void populateNodeContextMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case SSH_SERVICE_CONTAINER -> {
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> loadContainerList(module, item, data.getConnectionConfig()));
                contextMenu.getItems().add(refreshItem);
            }
            case SSH_CONTAINER -> populateContainerNodeMenu(module, contextMenu, item, data);
            default -> { /* 其他 SSH 服务节点暂无菜单 */ }
        }
    }

    /**
     * 单个容器节点右键菜单
     */
    private void populateContainerNodeMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        // 属性（docker inspect 详情查看与参数修改），运行中/已停止均可用
        MenuItem propsItem = new MenuItem("属性");
        propsItem.setOnAction(e -> openContainerInspectTab(module, config, data.getName(), data.getPath()));

        if (!data.isOpened()) {
            // 已停止：启动 | 属性
            MenuItem startItem = new MenuItem("启动");
            startItem.setOnAction(e -> execDockerAction(module, item, data, "start", "启动"));
            contextMenu.getItems().addAll(startItem, new SeparatorMenuItem(), propsItem);
            return;
        }
        // 运行中：停止/重启 | 查看实时日志/进入终端 | 属性
        MenuItem stopItem = new MenuItem("停止");
        stopItem.setOnAction(e -> execDockerAction(module, item, data, "stop", "停止"));

        MenuItem restartItem = new MenuItem("重启");
        restartItem.setOnAction(e -> execDockerAction(module, item, data, "restart", "重启"));

        MenuItem logsItem = new MenuItem("查看实时日志");
        logsItem.setOnAction(e -> openContainerLogsTab(module, config, data.getName(), data.getPath()));

        MenuItem terminalItem = new MenuItem("进入终端");
        terminalItem.setOnAction(e -> openContainerTerminalTab(module, config, data.getName(), data.getPath()));

        contextMenu.getItems().addAll(stopItem, restartItem,
                new SeparatorMenuItem(), logsItem, terminalItem,
                new SeparatorMenuItem(), propsItem);
    }

    /**
     * 后台建立临时 SSH 会话加载容器列表（docker ps -a），
     * 自动探测 docker 命令前缀（sudo -n 免密 / 普通用户），加载后按运行状态显示彩色/灰色图标。
     */
    public void loadContainerList(ConnectModule module, TreeItem<String> folderItem, ConnectionConfig config) {
        String password = ensurePasswordAvailable(module, config);
        if (password == null) return;

        new Thread(() -> {
            try {
                String output = withTempSession(config, password, session -> {
                    // 探测 docker 命令前缀：sudo 免密优先，其次普通用户，均不可用返回标记
                    String probe = executeCommand(session,
                            "sudo -n docker version >/dev/null 2>&1 && echo PREFIX_SUDO"
                                    + " || { docker version >/dev/null 2>&1 && echo PREFIX_PLAIN || echo PREFIX_NONE; }");
                    if (probe != null && probe.contains("PREFIX_NONE")) {
                        return "DOCKER_UNAVAILABLE";
                    }
                    String prefix = (probe != null && probe.contains("PREFIX_SUDO")) ? "sudo -n docker" : "docker";
                    return prefix + "\n" + executeCommand(session,
                            prefix + " ps -a --format '{{.Names}}|{{.Status}}' 2>/dev/null");
                });

                final boolean dockerUnavailable = "DOCKER_UNAVAILABLE".equals(output == null ? null : output.trim());
                final String dockerPrefix;
                final List<ContainerInfo> containers = new ArrayList<>();
                if (!dockerUnavailable && output != null) {
                    String[] lines = output.split("\n");
                    dockerPrefix = lines.length > 0 ? lines[0].trim() : "docker";
                    for (int i = 1; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.isEmpty()) continue;
                        String lower = line.toLowerCase();
                        // 跳过错误信息（如无法连接 Docker daemon）
                        if (lower.contains("cannot connect") || lower.contains("permission denied")
                                || lower.contains("not found")) {
                            continue;
                        }
                        int idx = line.indexOf('|');
                        if (idx <= 0) continue;
                        String name = line.substring(0, idx).trim();
                        String status = line.substring(idx + 1).trim();
                        if (name.isEmpty()) continue;
                        containers.add(new ContainerInfo(name, status.toLowerCase().startsWith("up")));
                    }
                } else {
                    dockerPrefix = "docker";
                }

                Platform.runLater(() -> {
                    // 清理旧子节点的元数据映射后重建容器节点
                    for (TreeItem<String> child : folderItem.getChildren()) {
                        module.getDbNodeDataMap().remove(child);
                    }
                    folderItem.getChildren().clear();
                    for (ContainerInfo c : containers) {
                        TreeItem<String> ci = new TreeItem<>(c.name);
                        // path 字段复用为 docker 命令前缀，供启动/停止/终端等操作使用
                        DatabaseNodeData nd = new DatabaseNodeData(
                                DatabaseNodeData.NodeType.SSH_CONTAINER, c.name, config, null, null, dockerPrefix);
                        nd.setOpened(c.running);
                        ci.setGraphic(module.getDbNodeIcon(nd));
                        module.getDbNodeDataMap().put(ci, nd);
                        folderItem.getChildren().add(ci);
                    }
                    folderItem.setExpanded(true);
                });

                if (dockerUnavailable) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("Docker 不可用");
                        alert.setHeaderText(null);
                        alert.setContentText("主机 " + config.getName() + " 上 Docker 不可用或当前用户无权限访问。");
                        alert.showAndWait();
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("获取容器列表失败: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "Docker-LoadContainers").start();
    }

    /**
     * 对容器执行 docker 命令（start/stop/restart），成功后更新节点运行状态与图标
     */
    private void execDockerAction(ConnectModule module, TreeItem<String> item, DatabaseNodeData data,
                                  String action, String label) {
        ConnectionConfig config = data.getConnectionConfig();
        String containerName = data.getName();
        String dockerPrefix = (data.getPath() != null && !data.getPath().isEmpty()) ? data.getPath() : "docker";
        String password = ensurePasswordAvailable(module, config);
        if (password == null) return;

        new Thread(() -> {
            try {
                String cmd = dockerPrefix + " " + action + " " + containerName + " 2>&1; echo EXIT:$?";
                String result = withTempSession(config, password, s -> executeCommand(s, cmd));
                boolean success = result != null && result.contains("EXIT:0");
                Platform.runLater(() -> {
                    if (success) {
                        // 更新节点运行状态与图标：start/restart 后运行中（彩色），stop 后已停止（灰色）
                        data.setOpened(!"stop".equals(action));
                        item.setGraphic(module.getDbNodeIcon(data));
                    } else {
                        String detail = result == null ? "无输出" : result.replaceAll("EXIT:\\d+", "").trim();
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("操作失败");
                        alert.setHeaderText(label + "容器失败: " + containerName);
                        alert.setContentText(detail.isEmpty() ? "无输出" : detail);
                        alert.showAndWait();
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("操作失败");
                    alert.setHeaderText(label + "容器失败: " + containerName);
                    alert.setContentText(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    alert.showAndWait();
                });
            }
        }, "Docker-" + label).start();
    }

    /**
     * 进入容器终端：打开新 SSH 终端标签页，连接成功后自动执行 docker exec -it。
     * 优先使用 bash，镜像内无 bash 时回退 sh。
     */
    public void openContainerTerminalTab(ConnectModule module, ConnectionConfig config, String containerName, String dockerPrefix) {
        String prefix = (dockerPrefix != null && !dockerPrefix.isEmpty()) ? dockerPrefix : "docker";
        String execCmd = prefix + " exec -it " + containerName
                + " sh -c 'command -v bash >/dev/null 2>&1 && exec bash || exec sh'";
        createSshTerminalTab(module, config, containerName, execCmd);
    }

    /**
     * 查看容器实时日志：打开新 SSH 终端标签页，连接成功后自动执行 docker logs -f --tail 100。
     */
    public void openContainerLogsTab(ConnectModule module, ConnectionConfig config, String containerName, String dockerPrefix) {
        String prefix = (dockerPrefix != null && !dockerPrefix.isEmpty()) ? dockerPrefix : "docker";
        String logsCmd = prefix + " logs -f --tail 100 " + containerName;
        createSshTerminalTab(module, config, containerName + " 日志", logsCmd);
    }

    /**
     * 容器属性标签页：以独立标签页展示 docker inspect 详情。
     * 建立 SSH 会话（复用跳板隧道引用计数）创建 ContainerInspectPane，
     * 支持修改重启策略（--restart）与资源限制（--memory/--cpus/--cpu-shares）。
     * 同一容器重复打开时激活已有标签页。
     */
    public void openContainerInspectTab(ConnectModule module, ConnectionConfig config, String containerName, String dockerPrefix) {
        if (!module.ensureTabPaneInstalled()) return;

        // 标签页唯一标识，避免重复打开
        String tabId = "docker_inspect_" + config.getId() + "_" + containerName;
        for (Tab t : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(t.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(t);
                module.showTerminalView();
                return;
            }
        }

        // 密码缺失时先弹出输入框（需在 FX 线程）
        String password = ensurePasswordAvailable(module, config);
        if (password == null) return;

        Tab tab = new Tab(containerName + " 属性");
        tab.setUserData(tabId);

        try {
            Image icon = new Image(getClass().getResourceAsStream("/images/connect/docker.png"));
            if (icon != null) {
                ImageView iv = new ImageView(icon);
                iv.setFitWidth(16);
                iv.setFitHeight(16);
                tab.setGraphic(ConnectModule.createFixedSizeGraphic(iv));
            }
        } catch (Exception ignored) {}

        // 占位内容，连接成功后替换
        StackPane loading = new StackPane(new ProgressIndicator());
        tab.setContent(loading);

        ContextMenu tabContextMenu = new ContextMenu();
        AtomicBoolean tabClosed = new AtomicBoolean(false);
        AtomicReference<SshTunnelManager.TunnelLease> tunnelLease = new AtomicReference<>();
        AtomicReference<SSHSession> inspectSession = new AtomicReference<>();
        AtomicReference<ContainerInspectPane> inspectPane = new AtomicReference<>();
        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            module.saveConnections();
        });
        MenuItem globalConfigItem = new MenuItem("全局配置");
        globalConfigItem.setOnAction(e -> module.openSettingsTabWithSshSelected());
        tabContextMenu.getItems().addAll(sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);
        tab.setOnClosed(e -> {
            tabClosed.set(true);
            ContainerInspectPane pane = inspectPane.getAndSet(null);
            if (pane != null) {
                pane.disconnect();
            } else {
                SSHSession session = inspectSession.getAndSet(null);
                if (session != null) try { session.disconnect(); } catch (Exception ignored) {}
            }
            closeTunnelLease(tunnelLease);
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        // 后台建立 SSH 会话 + 创建属性面板
        new Thread(() -> {
            SSHSession sshSession = null;
            try {
                SshTunnelManager.TunnelLease acquiredLease = SshTunnelManager.acquire(config);
                if (tabClosed.get() || !tunnelLease.compareAndSet(null, acquiredLease)) {
                    acquiredLease.close();
                    return;
                }
                int tunnelLocalPort = acquiredLease.getLocalPort();

                String host = config.getHost();
                int port = config.getPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }

                List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
                sshSession = new SSHSession(host, port, config.getUsername(), password, keyPaths);
                inspectSession.set(sshSession);
                sshSession.connect();

                final SSHSession session = sshSession;

                Platform.runLater(() -> {
                    if (tabClosed.get()) {
                        session.disconnect();
                        closeTunnelLease(tunnelLease);
                        return;
                    }
                    ContainerInspectPane pane = new ContainerInspectPane(session, containerName, dockerPrefix);
                    inspectPane.set(pane);
                    tab.setContent(pane);
                    pane.refresh();
                });
            } catch (Exception e) {
                // 连接失败：释放资源
                if (sshSession != null) try { sshSession.disconnect(); } catch (Exception ignored) {}
                closeTunnelLease(tunnelLease);
                if (!tabClosed.get()) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText(null);
                        alert.setContentText("打开容器属性失败: " + e.getMessage());
                        alert.showAndWait();
                        // 移除加载占位标签页
                        module.getTerminalTabPane().getTabs().remove(tab);
                        if (module.getTerminalTabPane().getTabs().isEmpty()) {
                            module.showWelcomeView();
                        }
                    });
                }
                e.printStackTrace();
            }
        }, "Docker-InspectConnect").start();
    }

    /**
     * 若配置使用密码认证且密码缺失，弹出密码输入框；返回可用密码，用户取消返回 null。
     */
    private String ensurePasswordAvailable(ConnectModule module, ConnectionConfig config) {
        if (config.isUsePassword() && (config.getPassword() == null || config.getPassword().isEmpty())) {
            PasswordPromptDialog.Result r = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (r == null || r.getPassword() == null || r.getPassword().isEmpty()) {
                return null;
            }
            config.setPassword(r.getPassword());
            if (r.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
            return r.getPassword();
        }
        return config.getPassword() != null ? config.getPassword() : "";
    }

    /** 在临时 SSH 会话上执行工作块（复用跳板隧道引用计数），结束后断开会话并释放隧道 */
    private interface SshWork {
        String run(SSHSession session) throws Exception;
    }

    private String withTempSession(ConnectionConfig config, String password, SshWork work) throws Exception {
        SSHSession sshSession = null;
        try (SshTunnelManager.TunnelLease tunnelLease = SshTunnelManager.acquire(config)) {
            int tunnelLocalPort = tunnelLease.getLocalPort();
            String host = config.getHost();
            int port = config.getPort();
            if (tunnelLocalPort != -1) {
                host = "localhost";
                port = tunnelLocalPort;
            }
            List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
            sshSession = new SSHSession(host, port, config.getUsername(), password, keyPaths);
            sshSession.connect();
            return work.run(sshSession);
        } finally {
            if (sshSession != null) try { sshSession.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * 通过 SSH exec 通道执行命令并返回输出（stdout + stderr）
     */
    private static String executeCommand(SSHSession session, String command) throws Exception {
        com.jcraft.jsch.ChannelExec channel = (com.jcraft.jsch.ChannelExec) session.getJschSession().openChannel("exec");
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

    /** 容器信息（树节点展示所需的最小字段） */
    private static class ContainerInfo {
        final String name;
        final boolean running;

        ContainerInfo(String name, boolean running) {
            this.name = name;
            this.running = running;
        }
    }

    /**
     * 创建 SSH 终端 tab 并发起连接
     */
    private void createSshTerminalTab(ConnectModule module, ConnectionConfig config) {
        createSshTerminalTab(module, config, null, null);
    }

    /**
     * 创建 SSH 终端 tab 并发起连接
     * @param tabTitle       标签页标题（null 时使用连接名）
     * @param initialCommand 连接成功后自动执行的命令（如 docker exec 进入容器终端；null 不执行）
     */
    private void createSshTerminalTab(ConnectModule module, ConnectionConfig config, String tabTitle, String initialCommand) {
        SSHTerminalPane terminalPane = new SSHTerminalPane();
        AtomicBoolean tabClosed = new AtomicBoolean(false);
        AtomicReference<SshTunnelManager.TunnelLease> tunnelLease = new AtomicReference<>();

        int scrollback = config.getScrollbackLines() != null ?
                config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        terminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(tabTitle != null ? tabTitle : config.getName());
        tab.setContent(terminalPane);
        tab.setUserData(config.getId());

        // 容器相关标签页（自定义标题）使用 docker 图标
        if (tabTitle != null) {
            try {
                Image icon = new Image(getClass().getResourceAsStream("/images/connect/docker.png"));
                if (icon != null) {
                    ImageView iv = new ImageView(icon);
                    iv.setFitWidth(16);
                    iv.setFitHeight(16);
                    tab.setGraphic(ConnectModule.createFixedSizeGraphic(iv));
                }
            } catch (Exception ignored) {}
        }

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem copySessionItem = new MenuItem("复制会话");
        copySessionItem.setOnAction(e -> module.triggerConnect(config));

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            int newScrollback = config.getScrollbackLines() != null ?
                    config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
            terminalPane.setScrollbackLines(newScrollback);
            module.saveConnections();
        });

        MenuItem globalConfigItem = new MenuItem("全局配置");
        globalConfigItem.setOnAction(e -> {
            module.openSettingsTabWithSshSelected();
        });

        tabContextMenu.getItems().addAll(copySessionItem, new SeparatorMenuItem(), sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            tabClosed.set(true);
            terminalPane.disconnect();
            closeTunnelLease(tunnelLease);
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        // 重连始终刷新本标签实际持有的租约。租约按隧道实例释放，旧会话不会误断
        // 其他会话已经重建的新隧道。
        terminalPane.setTunnelResolver(() -> {
            SshTunnelManager.TunnelLease lease = tunnelLease.get();
            if (lease == null) {
                throw new IllegalStateException("当前 SSH 会话未持有跳板隧道");
            }
            return lease.refresh();
        });

        doConnect(module, terminalPane, config, initialCommand, tunnelLease, tabClosed);
    }

    /**
     * 处理密码输入并触发连接
     */
    private void doConnect(ConnectModule module, SSHTerminalPane terminalPane, ConnectionConfig config,
                           String initialCommand,
                           AtomicReference<SshTunnelManager.TunnelLease> tunnelLease,
                           AtomicBoolean tabClosed) {
        if (config.isUsePassword() && config.getPassword() == null) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
            connectWithAuth(terminalPane, config, pwdResult.getPassword(), initialCommand,
                    tunnelLease, tabClosed);
        } else {
            connectWithAuth(terminalPane, config, config.getPassword(), initialCommand,
                    tunnelLease, tabClosed);
        }
    }

    /**
     * 后台建立 SSH 连接（若配置了SSH跳板隧道则先建立隧道，再连接 localhost:转发端口）
     */
    private void connectWithAuth(SSHTerminalPane terminalPane, ConnectionConfig config, String password,
                                 String initialCommand,
                                 AtomicReference<SshTunnelManager.TunnelLease> tunnelLease,
                                 AtomicBoolean tabClosed) {
        List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
        new Thread(() -> {
            SshTunnelManager.TunnelLease acquiredLease;
            try {
                acquiredLease = SshTunnelManager.acquire(config);
            } catch (Exception te) {
                if (!tabClosed.get()) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText(null);
                        alert.setContentText("建立SSH跳板隧道失败: " + te.getMessage());
                        alert.showAndWait();
                        terminalPane.disconnect();
                    });
                }
                te.printStackTrace();
                return;
            }
            // 标签可能在后台建隧道期间已被关闭；此时立即归还刚取得的租约。
            if (tabClosed.get() || !tunnelLease.compareAndSet(null, acquiredLease)) {
                acquiredLease.close();
                return;
            }
            try {
                String host = config.getHost();
                int port = config.getPort();
                int tunnelLocalPort = acquiredLease.getLocalPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }
                terminalPane.connect(host, port, config.getUsername(), password, keyPaths);
                if (tabClosed.get()) {
                    terminalPane.disconnect();
                    closeTunnelLease(tunnelLease);
                    return;
                }
                // 连接成功后自动执行初始命令（如 docker exec 进入容器终端、docker logs -f 跟踪日志）
                if (initialCommand != null && !initialCommand.isEmpty()) {
                    terminalPane.sendCommand(initialCommand);
                }
            } catch (Exception e) {
                closeTunnelLease(tunnelLease);
                if (!tabClosed.get()) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText(null);
                        alert.setContentText("SSH连接失败: " + e.getMessage());
                        alert.showAndWait();
                        terminalPane.disconnect();
                    });
                }
                e.printStackTrace();
            }
        }, "SSH-Connect").start();
    }

    private static void closeTunnelLease(
            AtomicReference<SshTunnelManager.TunnelLease> tunnelLease) {
        SshTunnelManager.TunnelLease lease = tunnelLease.getAndSet(null);
        if (lease != null) {
            lease.close();
        }
    }
}
