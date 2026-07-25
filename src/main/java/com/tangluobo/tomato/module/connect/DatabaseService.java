package com.tangluobo.tomato.module.connect;

import java.sql.*;
import java.util.*;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

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
     * 分页查询表/视图数据
     * @return TableRowData 包含列名列表和数据行
     */
    public static TableRowData queryTableData(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize) throws Exception {
        Connection conn = getConnection(config, databaseName);
        TableRowData result = new TableRowData();

        // 获取总行数
        long totalCount;
        String countSql = buildCountSql(config, databaseName, tableName);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(countSql)) {
            rs.next();
            totalCount = rs.getLong(1);
        }

        result.setTotalCount(totalCount);
        result.setPage(page);
        result.setPageSize(pageSize);
        result.setTotalPages((int) Math.ceil((double) totalCount / pageSize));

        // 分页查询数据
        String dataSql = buildPageSql(config, databaseName, tableName, page, pageSize);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(dataSql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // 获取列名
            List<String> columnNames = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columnNames.add(metaData.getColumnLabel(i));
            }
            result.setColumnNames(columnNames);

            // 获取数据行
            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= columnCount; i++) {
                    String value = rs.getString(i);
                    row.add(value != null ? value : "NULL");
                }
                rows.add(row);
            }
            result.setRows(rows);
        }

        return result;
    }

    /**
     * 获取指定数据库的JDBC连接
     */
    private static Connection getConnection(ConnectionConfig config, String databaseName) throws Exception {
        String key = config.getId() + "_" + databaseName;
        Connection existing = connectionCache.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        String host = config.getHost();
        int port = config.getPort();

        if (config.isUseSshTunnel()) {
            String tunnelKey = config.getId();
            SshTunnel oldTunnel = tunnelCache.get(tunnelKey);
            if (oldTunnel != null && oldTunnel.isActive()) {
                port = oldTunnel.getForwardedLocalPort();
                host = "127.0.0.1";
            } else {
                SshTunnel tunnel = SshTunnel.fromConfig(config);
                int localPort = tunnel.connect();
                tunnelCache.put(tunnelKey, tunnel);
                host = "127.0.0.1";
                port = localPort;
            }
        }

        String url = buildJdbcUrl(config, host, port, databaseName);
        Connection conn = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
        connectionCache.put(key, conn);
        return conn;
    }

    /**
     * 构建计数SQL
     */
    private static String buildCountSql(ConnectionConfig config, String databaseName, String tableName) {
        return switch (config.getType()) {
            case MYSQL -> "SELECT COUNT(*) FROM `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "SELECT COUNT(*) FROM \"" + databaseName + "\".\"" + tableName + "\"";
            case ORACLE -> "SELECT COUNT(*) FROM \"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 构建分页查询SQL
     */
    private static String buildPageSql(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return switch (config.getType()) {
            case MYSQL -> "SELECT * FROM `" + databaseName + "`.`" + tableName + "` LIMIT " + pageSize + " OFFSET " + offset;
            case POSTGRESQL -> "SELECT * FROM \"" + databaseName + "\".\"" + tableName + "\" LIMIT " + pageSize + " OFFSET " + offset;
            case ORACLE -> "SELECT * FROM (SELECT a.*, ROWNUM rn FROM \"" + databaseName + "\".\"" + tableName + "\" a WHERE ROWNUM <= " + (offset + pageSize) + ") WHERE rn > " + offset;
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 删除多个表
     * @return 成功删除的表名列表
     */
    public static List<String> dropTables(ConnectionConfig config, String databaseName, List<String> tableNames) throws Exception {
        Connection conn = getConnection(config, databaseName);
        List<String> dropped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String tableName : tableNames) {
            String sql = buildDropTableSql(config, databaseName, tableName);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                dropped.add(tableName);
            } catch (Exception e) {
                errors.add(tableName + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException("部分表删除失败:\n" + String.join("\n", errors));
        }
        return dropped;
    }

    /**
     * 删除多个视图
     * @return 成功删除的视图名列表
     */
    public static List<String> dropViews(ConnectionConfig config, String databaseName, List<String> viewNames) throws Exception {
        Connection conn = getConnection(config, databaseName);
        List<String> dropped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String viewName : viewNames) {
            String sql = buildDropViewSql(config, databaseName, viewName);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                dropped.add(viewName);
            } catch (Exception e) {
                errors.add(viewName + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException("部分视图删除失败:\n" + String.join("\n", errors));
        }
        return dropped;
    }

    /**
     * 构建删除表SQL
     */
    private static String buildDropTableSql(ConnectionConfig config, String databaseName, String tableName) {
        return switch (config.getType()) {
            case MYSQL -> "DROP TABLE `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "DROP TABLE \"" + databaseName + "\".\"" + tableName + "\"";
            case ORACLE -> "DROP TABLE \"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 构建删除视图SQL
     */
    private static String buildDropViewSql(ConnectionConfig config, String databaseName, String viewName) {
        return switch (config.getType()) {
            case MYSQL -> "DROP VIEW `" + databaseName + "`.`" + viewName + "`";
            case POSTGRESQL -> "DROP VIEW \"" + databaseName + "\".\"" + viewName + "\"";
            case ORACLE -> "DROP VIEW \"" + databaseName + "\".\"" + viewName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 创建数据库
     */
    public static void createDatabase(ConnectionConfig config, String databaseName, String charset, String collation) throws Exception {
        String sql = buildCreateDatabaseSql(config, databaseName, charset, collation);
        Connection conn = getConnection(config);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 获取服务器支持的字符集及对应排序规则
     */
    public static Map<String, List<String>> getCharsets(ConnectionConfig config) throws Exception {
        Connection conn = getConnection(config);
        Map<String, List<String>> result = new LinkedHashMap<>();

        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLLATIONS ORDER BY CHARACTER_SET_NAME, COLLATION_NAME")) {
                while (rs.next()) {
                    String charsetName = rs.getString("CHARACTER_SET_NAME");
                    String collationName = rs.getString("COLLATION_NAME");
                    result.computeIfAbsent(charsetName, k -> new ArrayList<>()).add(collationName);
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT DISTINCT encodingname, collation_name FROM pg_collation c JOIN pg_encodings e ON c.collencoding = e.encoding ORDER BY encodingname, collation_name")) {
                while (rs.next()) {
                    String charsetName = rs.getString("encodingname");
                    String collationName = rs.getString("collation_name");
                    result.computeIfAbsent(charsetName, k -> new ArrayList<>()).add(collationName);
                }
            }
        }

        return result;
    }

    /**
     * 构建创建数据库SQL
     */
    private static String buildCreateDatabaseSql(ConnectionConfig config, String databaseName, String charset, String collation) {
        StringBuilder sql = new StringBuilder();
        if (config.getType() == ConnectType.MYSQL) {
            sql.append("CREATE DATABASE `").append(databaseName).append("`");
            if (charset != null && !charset.isEmpty()) {
                sql.append(" CHARACTER SET ").append(charset);
            }
            if (collation != null && !collation.isEmpty()) {
                sql.append(" COLLATE ").append(collation);
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            sql.append("CREATE DATABASE \"").append(databaseName).append("\"");
            if (charset != null && !charset.isEmpty()) {
                sql.append(" ENCODING '").append(charset).append("'");
            }
            if (collation != null && !collation.isEmpty()) {
                sql.append(" LC_COLLATE '").append(collation).append("'");
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            sql.append("CREATE USER \"").append(databaseName).append("\" IDENTIFIED BY \"").append(databaseName).append("\"");
        }
        return sql.toString();
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
