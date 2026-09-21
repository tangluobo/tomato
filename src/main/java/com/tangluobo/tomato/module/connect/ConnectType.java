package com.tangluobo.tomato.module.connect;

public enum ConnectType {
    SSH("SSH", "SSH连接", "/images/connect/connection-icons/ssh.svg", Category.OTHERS),
    RDP("RDP", "远程桌面", "/images/connect/windows.png", Category.OTHERS),
    VNC("VNC", "VNC远程桌面", "/images/connect/vnc.svg", Category.OTHERS),
    SPICE("SPICE", "SPICE虚拟机", "/images/connect/spice.svg", Category.OTHERS),
    MYSQL("MySQL", "MySQL数据库", "/images/connect/mysql.png", Category.DATABASE),
    POSTGRESQL("PostgreSQL", "PostgreSQL数据库", "/images/connect/postgresql.png", Category.DATABASE),
    FTP("FTP", "FTP客户端", "/images/connect/connection-icons/ftp-client.svg", Category.OTHERS),
    SFTP("SFTP", "SFTP服务器", "/images/connect/connection-icons/sftp.svg", Category.OTHERS),
    ORACLE("Oracle", "Oracle数据库", "/images/connect/oracle.png", Category.DATABASE),
    S3("S3", "S3存储", "/images/connect/s3.png", Category.OTHERS),
    ALIYUN("Aliyun", "阿里云", "/images/connect/connection-icons/aliyun.svg", Category.OTHERS),
    TENCENT_CLOUD("TencentCloud", "腾讯云", "/images/connect/connection-icons/tencent-cloud.svg", Category.OTHERS),
    ALIYUN_OSS("AliyunOSS", "阿里云OSS", "/images/connect/connection-icons/aliyun-oss.svg", Category.OTHERS),
    REDIS("Redis", "Redis", "/images/connect/redis.png", Category.DATABASE),
    MONGODB("MongoDB", "MongoDB", "/images/connect/database-logos/mongodb.svg", Category.DATABASE),
    HIVE("Hive", "Apache Hive", "/images/connect/database-logos/hive.svg", Category.DATABASE),
    ROCKETMQ("RocketMQ", "RocketMQ", "/images/connect/rocketmq.png", Category.MESSAGE_QUEUE),
    KAFKA("Kafka", "Kafka", "/images/connect/kafka.png", Category.MESSAGE_QUEUE),
    LOCAL_TERMINAL("LocalTerminal", "本地终端", "/images/connect/shell.png", Category.OTHERS),
    LOCAL_DIRECTORY("LocalDirectory", "本地目录", "/images/connect/folder.png", Category.OTHERS),
    TOOL("Tool", "工具", "/images/connect/beautiful.png", Category.TOOL),
    HTTP_SERVER("HttpServer", "HTTP服务器", "/images/connect/connection-icons/http-server.svg", Category.OTHERS),
    FTP_SERVER("FtpServer", "FTP服务器", "/images/connect/connection-icons/ftp-server.svg", Category.OTHERS),
    SMB_SERVER("SmbServer", "SMB文件共享", "/images/connect/connection-icons/smb-server.svg", Category.OTHERS);

    public enum Category {
        DATABASE,
        MESSAGE_QUEUE,
        OTHERS,
        TOOL
    }

    private final String code;
    private final String displayName;
    private final String iconPath;
    private final Category category;

    ConnectType(String code, String displayName, String iconPath, Category category) {
        this.code = code;
        this.displayName = displayName;
        this.iconPath = iconPath;
        this.category = category;
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

    public Category getCategory() {
        return category;
    }

    /**
     * 是否支持在没有主键时，用修改前的完整行值安全定位并只更新一条记录。
     */
    public boolean supportsUpdateByOriginalRow() {
        return this == MYSQL || this == POSTGRESQL || this == ORACLE;
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
