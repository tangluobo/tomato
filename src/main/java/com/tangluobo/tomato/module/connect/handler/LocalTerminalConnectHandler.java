package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.GlobalConfig;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import javafx.scene.control.*;
import javafx.stage.Stage;

import com.tangluobo.tomato.ssh.LocalTerminalPane;

/**
 * 本地终端连接处理器
 */
public class LocalTerminalConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.LOCAL_TERMINAL;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        LocalTerminalPane localTerminalPane = new LocalTerminalPane();

        int scrollback = config.getScrollbackLines() != null ?
                config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        localTerminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(config.getName());
        tab.setContent(localTerminalPane);
        tab.setUserData(config.getId());

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem copySessionItem = new MenuItem("复制会话");
        copySessionItem.setOnAction(e -> handleConnect(module, config));

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = module.getStage();
            SessionConfigDialog.show(stage, config);
            int newScrollback = config.getScrollbackLines() != null ?
                    config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
            localTerminalPane.setScrollbackLines(newScrollback);
            module.saveConnections();
        });

        MenuItem globalConfigItem = new MenuItem("全局配置");
        globalConfigItem.setOnAction(e -> {
            module.openSettingsTabWithSshSelected();
        });

        tabContextMenu.getItems().addAll(copySessionItem, new SeparatorMenuItem(), sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            localTerminalPane.disconnect();
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        String terminalType = config.getTerminalType() != null ? config.getTerminalType() : "cmd";
        localTerminalPane.connect(terminalType);
    }
}
