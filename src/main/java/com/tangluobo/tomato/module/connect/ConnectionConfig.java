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

    // RDP专属配置
    private String domain;
    private int screenWidth = 1024;
    private int screenHeight = 768;
    private int colorDepth = 24;

    // 本地终端配置
    private String terminalType; // Windows: "cmd" 或 "powershell"; Linux/macOS: "system"

    // S3/OSS专属配置
    private String region;
    private boolean pathStyleAccess = false; // S3路径风格访问（MinIO需要）
    private String endpoint; // 自定义端点URL（MinIO等S3兼容服务）

    // SSH通道配置
    private boolean useSshTunnel = false;
    private String sshTunnelHost;
    private int sshTunnelPort = 22;
    private String sshTunnelUsername;
    private boolean sshTunnelUsePassword = true;
    private String sshTunnelPassword;
    private boolean sshTunnelSavePassword = true;
    private boolean sshTunnelUseKey = false;
    private List<String> sshTunnelPrivateKeyPaths = new ArrayList<>();

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

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public int getScreenWidth() { return screenWidth; }
    public void setScreenWidth(int screenWidth) { this.screenWidth = screenWidth; }

    public int getScreenHeight() { return screenHeight; }
    public void setScreenHeight(int screenHeight) { this.screenHeight = screenHeight; }

    public int getColorDepth() { return colorDepth; }
    public void setColorDepth(int colorDepth) { this.colorDepth = colorDepth; }

    public String getTerminalType() { return terminalType; }
    public void setTerminalType(String terminalType) { this.terminalType = terminalType; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public boolean isUseSshTunnel() { return useSshTunnel; }
    public void setUseSshTunnel(boolean useSshTunnel) { this.useSshTunnel = useSshTunnel; }

    public String getSshTunnelHost() { return sshTunnelHost; }
    public void setSshTunnelHost(String sshTunnelHost) { this.sshTunnelHost = sshTunnelHost; }

    public int getSshTunnelPort() { return sshTunnelPort; }
    public void setSshTunnelPort(int sshTunnelPort) { this.sshTunnelPort = sshTunnelPort; }

    public String getSshTunnelUsername() { return sshTunnelUsername; }
    public void setSshTunnelUsername(String sshTunnelUsername) { this.sshTunnelUsername = sshTunnelUsername; }

    public boolean isSshTunnelUsePassword() { return sshTunnelUsePassword; }
    public void setSshTunnelUsePassword(boolean sshTunnelUsePassword) { this.sshTunnelUsePassword = sshTunnelUsePassword; }

    public String getSshTunnelPassword() { return sshTunnelPassword; }
    public void setSshTunnelPassword(String sshTunnelPassword) { this.sshTunnelPassword = sshTunnelPassword; }

    public boolean isSshTunnelSavePassword() { return sshTunnelSavePassword; }
    public void setSshTunnelSavePassword(boolean sshTunnelSavePassword) { this.sshTunnelSavePassword = sshTunnelSavePassword; }

    public boolean isSshTunnelUseKey() { return sshTunnelUseKey; }
    public void setSshTunnelUseKey(boolean sshTunnelUseKey) { this.sshTunnelUseKey = sshTunnelUseKey; }

    public List<String> getSshTunnelPrivateKeyPaths() { return sshTunnelPrivateKeyPaths; }
    public void setSshTunnelPrivateKeyPaths(List<String> sshTunnelPrivateKeyPaths) { this.sshTunnelPrivateKeyPaths = sshTunnelPrivateKeyPaths != null ? sshTunnelPrivateKeyPaths : new ArrayList<>(); }
}
