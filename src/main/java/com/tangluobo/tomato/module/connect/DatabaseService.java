package com.tangluobo.tomato.module.connect;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库服务：管理JDBC连接，查询数据库/表/视图列表
 */
public class DatabaseService {

    // 缓存：ConnectionConfig.id -> JDBC Connection
    private static final Map<String, Connection> connectionCache = new HashMap<>();
    // 缓存：ConnectionConfig.id -> SshTunnel
    private static final Map<String, SshTunnel> tunnelCache = new HashMap<>();

    /**
     * 获取或创建JDBC连接（带SSH隧道支持）
     */
    public static Connection getConnection(ConnectionConfig config) throws Exception {
        String key = config.getId();
        Connection existing = connectionCache.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        // 建立SSH隧道（如果启用）
        String host = config.getHost();
        int port = config.getPort();

        if (config.isUseSshTunnel()) {
            SshTunnel oldTunnel = tunnelCache.get(key);
            if (oldTunnel != null && oldTunnel.isActive()) {
                port = oldTunnel.getForwardedLocalPort();
                host = "127.0.0.1";
            } else {
                SshTunnel tunnel = SshTunnel.fromConfig(config);
                int localPort = tunnel.connect();
                tunnelCache.put(key, tunnel);
                host = "127.0.0.1";
                port = localPort;
            }
        }

        String url = buildJdbcUrl(config, host, port, null);
        Connection conn = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
        connectionCache.put(key, conn);
        return conn;
    }

    /**
     * 获取有权限的数据库列表
     */
    public static List<String> getDatabases(ConnectionConfig config) throws Exception {
        Connection conn = getConnection(config);
        List<String> databases = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            // MySQL: SHOW DATABASES
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    String dbName = rs.getString(1);
                    // 过滤掉系统库
                    if (!"information_schema".equals(dbName)
                        && !"performance_schema".equals(dbName)
                        && !"sys".equals(dbName)
                        && !"mysql".equals(dbName)) {
                        databases.add(dbName);
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            // PostgreSQL: 查询有权限的数据库
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT datname FROM pg_database WHERE datistemplate = false AND has_database_privilege(current_user, datname, 'CONNECT') ORDER BY datname")) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            // Oracle: 查询有权限的schema
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT username FROM all_users ORDER BY username")) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            }
        }

        return databases;
    }

    /**
     * 获取表列表
     */
    public static List<String> getTables(ConnectionConfig config, String databaseName) throws Exception {
        Connection conn = getConnection(config);
        List<String> tables = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW TABLES FROM `" + databaseName + "`")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT table_name FROM all_tables WHERE owner = ? ORDER BY table_name")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
            }
        }

        return tables;
    }

    /**
     * 获取视图列表
     */
    public static List<String> getViews(ConnectionConfig config, String databaseName) throws Exception {
        Connection conn = getConnection(config);
        List<String> views = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT TABLE_NAME FROM information_schema.VIEWS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        views.add(rs.getString(1));
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT table_name FROM information_schema.views WHERE table_schema = ? ORDER BY table_name")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        views.add(rs.getString(1));
                    }
                }
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT view_name FROM all_views WHERE owner = ? ORDER BY view_name")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        views.add(rs.getString(1));
                    }
                }
            }
        }

        return views;
    }

    /**
     * 关闭指定连接配置的JDBC连接和SSH隧道
     */
    public static void closeConnection(String configId) {
        Connection conn = connectionCache.remove(configId);
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) {}
        }
        SshTunnel tunnel = tunnelCache.remove(configId);
        if (tunnel != null) {
            tunnel.disconnect();
        }
    }

    /**
     * 检查连接是否活跃
     */
    public static boolean isConnectionActive(String configId) {
        Connection conn = connectionCache.get(configId);
        if (conn != null) {
            try {
                return !conn.isClosed() && conn.isValid(3);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 构建JDBC URL
     */
    private static String buildJdbcUrl(ConnectionConfig config, String host, int port, String database) {
        String db = (database != null && !database.isEmpty()) ? database : "";
        return switch (config.getType()) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + db;
            case ORACLE -> "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }
}
