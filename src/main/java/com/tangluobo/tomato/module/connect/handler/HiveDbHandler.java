package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.DatabaseNodeData;
import javafx.scene.control.TreeItem;

/** Apache HiveServer2 database handler. */
public class HiveDbHandler extends AbstractDbHandler {

    public HiveDbHandler(ConnectModule module) {
        super(module);
    }

    @Override
    public ConnectType getConnectType() {
        return ConnectType.HIVE;
    }

    @Override
    public void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        openDatabaseWithFolders(dbItem, data);
    }

    @Override
    public void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        module.updateHostIconGeneric(hostItem, config, connected);
    }

    @Override
    protected boolean shouldPromptForPassword(ConnectionConfig config) {
        return config.isUsePassword() && config.getPassword() == null;
    }
}
