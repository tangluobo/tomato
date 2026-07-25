package com.tangluobo.tomato.module.connect;

import java.util.ArrayList;
import java.util.List;

public class ConnectionConfig {
    private String id;
    private String name;
    private String parentId;
    private ConnectType type;
    private String host;
    private int port;
    private String username;
    private boolean usePassword = true;
    private String password;
    private boolean savePassword = true;
    private boolean useKey = false;
    private List<String> privateKeyPaths = new ArrayList<>();
    private String database;
    private String description;
    private Integer scrollbackLines;

    public ConnectionConfig() {
    }

    public ConnectionConfig(String id, String name, String parentId, ConnectType type) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.type = type;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public ConnectType getType() { return type; }
    public void setType(ConnectType type) { this.type = type; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public boolean isUsePassword() { return usePassword; }
    public void setUsePassword(boolean usePassword) { this.usePassword = usePassword; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isSavePassword() { return savePassword; }
    public void setSavePassword(boolean savePassword) { this.savePassword = savePassword; }

    public boolean isUseKey() { return useKey; }
    public void setUseKey(boolean useKey) { this.useKey = useKey; }

    public List<String> getPrivateKeyPaths() { return privateKeyPaths; }
    public void setPrivateKeyPaths(List<String> privateKeyPaths) { this.privateKeyPaths = privateKeyPaths != null ? privateKeyPaths : new ArrayList<>(); }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getScrollbackLines() { return scrollbackLines; }
    public void setScrollbackLines(Integer scrollbackLines) { this.scrollbackLines = scrollbackLines; }
}
