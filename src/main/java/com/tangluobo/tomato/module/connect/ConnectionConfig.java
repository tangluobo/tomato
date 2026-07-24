package com.tangluobo.tomato.module.connect;

public class ConnectionConfig {
    private String id;
    private String name;
    private String parentId;
    private ConnectType type;
    private String host;
    private int port;
    private String username;
    private String password;
    private String database;
    private String description;

    public ConnectionConfig() {
    }

    public ConnectionConfig(String id, String name, String parentId, ConnectType type) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public ConnectType getType() {
        return type;
    }

    public void setType(ConnectType type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}