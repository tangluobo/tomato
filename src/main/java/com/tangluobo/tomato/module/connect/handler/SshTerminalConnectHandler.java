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
import com.tangluobo.tomato.ssh.SFTPFileBrowser;
import com.tangluobo.tomato.ssh.SSHSession;
import com.tangluobo.tomato.ssh.SSHTerminalPane;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.List;

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
     *   终端/容器/服务/端口/文件 五个子节点。
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
     * 在 SSH 主机节点下添加服务管理子节点：终端/容器/服务/端口/文件
     */
    private void addServiceManagementNodes(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        Platform.runLater(() -> {
            hostItem.getChildren().clear();
            addServiceChild(module, hostItem, config, "终端", DatabaseNodeData.NodeType.SSH_SERVICE_TERMINAL);
            addServiceChild(module, hostItem, config, "容器", DatabaseNodeData.NodeType.SSH_SERVICE_CONTAINER);
            addServiceChild(module, hostItem, config, "服务", DatabaseNodeData.NodeType.SSH_SERVICE_SERVICE);
            addServiceChild(module, hostItem, config, "端口", DatabaseNodeData.NodeType.SSH_SERVICE_PORT);
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

        ContextMenu tabContextMenu = new ContextMenu();
        tabContextMenu.getItems().addAll();
        tab.setContextMenu(tabContextMenu);

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        // 后台建立 SSH 会话 + SFTP 通道
        new Thread(() -> {
            int tunnelLocalPort = -1;
            SSHSession sshSession = null;
            SFTPClient sftpClient = null;
            try {
                // 复用跳板隧道（引用计数 +1）
                tunnelLocalPort = SshTunnelManager.resolve(config);

                String host = config.getHost();
                int port = config.getPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }

                List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
                sshSession = new SSHSession(host, port, config.getUsername(), config.getPassword(), keyPaths);
                sshSession.connect();

                sftpClient = new SFTPClient();
                sftpClient.connect(sshSession.getJschSession());

                final SSHSession session = sshSession;
                final SFTPClient sftp = sftpClient;
                final int tunnelPort = tunnelLocalPort;

                Platform.runLater(() -> {
                    SFTPFileBrowser browser = new SFTPFileBrowser(session, sftp, true);
                    tab.setContent(browser);
                    browser.initConnection();

                    tab.setOnClosed(e -> {
                        browser.disconnect();
                        if (tunnelPort != -1) {
                            SshTunnelManager.release(config);
                        }
                        if (module.getTerminalTabPane().getTabs().isEmpty()) {
                            module.showWelcomeView();
                        }
                    });
                });
            } catch (Exception e) {
                // 连接失败：释放资源
                if (sftpClient != null) try { sftpClient.disconnect(); } catch (Exception ignored) {}
                if (sshSession != null) try { sshSession.disconnect(); } catch (Exception ignored) {}
                if (tunnelLocalPort != -1) {
                    SshTunnelManager.release(config);
                }
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("建立SFTP文件浏览器失败: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "SFTP-Connect").start();
    }

    /**
     * 创建 SSH 终端 tab 并发起连接
     */
    private void createSshTerminalTab(ConnectModule module, ConnectionConfig config) {
        SSHTerminalPane terminalPane = new SSHTerminalPane();

        int scrollback = config.getScrollbackLines() != null ?
                config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        terminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(config.getName());
        tab.setContent(terminalPane);
        tab.setUserData(config.getId());

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
            terminalPane.disconnect();
            SshTunnelManager.release(config);
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        // 注入跳板隧道解析回调：重连时判断是否重建隧道。
        // 先 peek 复用活跃隧道（不改变引用计数，避免误断共享隧道）；
        // 隧道已失效则 release 旧引用并 resolve 重建（引用计数保持平衡，未使用隧道时两步均为 -1）。
        terminalPane.setTunnelResolver(() -> {
            int p = SshTunnelManager.peek(config);
            if (p != -1) {
                return p;
            }
            SshTunnelManager.release(config);
            return SshTunnelManager.resolve(config);
        });

        doConnect(module, terminalPane, config);
    }

    /**
     * 处理密码输入并触发连接
     */
    private void doConnect(ConnectModule module, SSHTerminalPane terminalPane, ConnectionConfig config) {
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
            connectWithAuth(terminalPane, config, pwdResult.getPassword());
        } else {
            connectWithAuth(terminalPane, config, config.getPassword());
        }
    }

    /**
     * 后台建立 SSH 连接（若配置了SSH跳板隧道则先建立隧道，再连接 localhost:转发端口）
     */
    private void connectWithAuth(SSHTerminalPane terminalPane, ConnectionConfig config, String password) {
        List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
        new Thread(() -> {
            // 先建立/复用跳板隧道（引用方式，按 configId+host:port 缓存并引用计数）
            int tunnelLocalPort = -1;
            try {
                tunnelLocalPort = SshTunnelManager.resolve(config);
            } catch (Exception te) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("建立SSH跳板隧道失败: " + te.getMessage());
                    alert.showAndWait();
                    terminalPane.disconnect();
                });
                te.printStackTrace();
                return;
            }
            try {
                String host = config.getHost();
                int port = config.getPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }
                terminalPane.connect(host, port, config.getUsername(), password, keyPaths);
            } catch (Exception e) {
                if (tunnelLocalPort != -1) {
                    SshTunnelManager.release(config);
                }
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("SSH连接失败: " + e.getMessage());
                    alert.showAndWait();
                    terminalPane.disconnect();
                });
                e.printStackTrace();
            }
        }, "SSH-Connect").start();
    }
}
