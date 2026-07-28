package com.tangluobo.tomato.module.connect;

public enum ConnectType {
    SSH("SSH", "SSH连接", "/images/connect/linux.png"),
    RDP("RDP", "远程桌面", "/images/connect/windows.png"),
    MYSQL("MySQL", "MySQL数据库", "/images/connect/mysql.png"),
    POSTGRESQL("PostgreSQL", "PostgreSQL数据库", "/images/connect/postgresql.png"),
    FTP("FTP", "FTP服务器", "/images/connect/ftp.png"),
    SFTP("SFTP", "SFTP服务器", "/images/connect/sftp.png"),
    ORACLE("Oracle", "Oracle数据库", "/images/connect/oracle.png"),
    S3("S3", "S3存储", "/images/connect/s3.png"),
    ALIYUN_OSS("AliyunOSS", "阿里云OSS", "/images/connect/aliyun_oss.png"),
    REDIS("Redis", "Redis", "/images/connect/redis.png"),
    LOCAL_TERMINAL("LocalTerminal", "本地终端", "/images/connect/monitor.png");

    private final String code;
    private final String displayName;
    private final String iconPath;

    ConnectType(String code, String displayName, String iconPath) {
        this.code = code;
        this.displayName = displayName;
        this.iconPath = iconPath;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIconPath() {
        return iconPath;
    }

    public static ConnectType fromCode(String code) {
        for (ConnectType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return SSH;
    }
}