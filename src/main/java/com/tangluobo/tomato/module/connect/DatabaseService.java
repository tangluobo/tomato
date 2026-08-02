package com.tangluobo.tomato.module.connect;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 数据库服务：管理JDBC连接，查询数据库/表/视图列表
 */
public class DatabaseService {

    // 缓存：ConnectionConfig.id -> JDBC Connection（主连接，不绑定具体数据库）
    private static final Map<String, Connection> connectionCache = new ConcurrentHashMap<>();
    // 缓存：ConnectionConfig.id + "_" + databaseName -> JDBC Connection（仅用于必须绑定数据库的场景，如用户自定义SQL）
    private static final Map<String, Connection> databaseConnectionCache = new ConcurrentHashMap<>();
    // 缓存：ConnectionConfig.id -> SshTunnel
    private static final Map<String, SshTunnel> tunnelCache = new ConcurrentHashMap<>();
    // 连接建立锁：保证同一 key 的连接建立互斥，避免并发重复建连
    private static final Map<String, Object> connectionLocks = new ConcurrentHashMap<>();

    /**
     * 获取或创建JDBC主连接（不绑定具体数据库，带SSH隧道支持）。
     * 多数数据库操作（SHOW DATABASES、使用全限定名的SQL、JDBC元数据API）均复用此主连接，
     * 避免为每个数据库建立独立连接导致的握手开销。
     */
    public static Connection getConnection(ConnectionConfig config) throws Exception {
        String key = config.getId();
        Connection existing = connectionCache.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        // 同一 key 互斥建连，避免并发线程重复建立连接
        Object lock = connectionLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 双重检查
            existing = connectionCache.get(key);
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

    public static List<String> getFunctions(ConnectionConfig config, String databaseName) throws Exception {
        Connection conn = getConnection(config);
        List<String> functions = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT ROUTINE_NAME FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'FUNCTION' ORDER BY ROUTINE_NAME")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        functions.add(rs.getString(1));
                    }
                }
            }
        }

        return functions;
    }

    public static List<String> getEvents(ConnectionConfig config, String databaseName) throws Exception {
        Connection conn = getConnection(config);
        List<String> events = new ArrayList<>();

        if (config.getType() == ConnectType.MYSQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT EVENT_NAME FROM information_schema.EVENTS WHERE EVENT_SCHEMA = ? ORDER BY EVENT_NAME")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        events.add(rs.getString(1));
                    }
                }
            }
        }

        return events;
    }

    /**
     * 分页查询表/视图数据
     * @return TableRowData 包含列名列表和数据行
     */
    public static TableRowData queryTableData(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize) throws Exception {
        return queryTableData(config, databaseName, tableName, page, pageSize, null, false);
    }

    public static TableRowData queryTableData(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize, String sortColumn, boolean sortDescending) throws Exception {
        // SQL 使用全限定名（database.table），复用主连接避免为每个数据库建立独立连接
        Connection conn = getConnection(config);
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
        String dataSql = buildPageSql(config, databaseName, tableName, page, pageSize, sortColumn, sortDescending);
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
     * 获取指定数据库的JDBC连接（绑定到具体数据库，用于用户自定义SQL等需要当前数据库上下文的场景）。
     * 注意：表数据查询、表结构查询、主键查询等使用全限定名或JDBC元数据API的操作应改用
     * {@link #getConnection(ConnectionConfig)} 复用主连接，避免每个数据库都建立独立连接。
     */
    public static Connection getConnection(ConnectionConfig config, String databaseName) throws Exception {
        String key = config.getId() + "_" + databaseName;
        Connection existing = databaseConnectionCache.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        Object lock = connectionLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            existing = databaseConnectionCache.get(key);
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
            databaseConnectionCache.put(key, conn);
            return conn;
        }
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
     * 执行自定义SQL查询（SELECT语句），返回结果
     */
    public static TableRowData executeSqlQuery(ConnectionConfig config, String databaseName, String sql, int pageSize) throws Exception {
        Connection conn = getConnection(config, databaseName);
        long startTime = System.currentTimeMillis();

        TableRowData result = new TableRowData();

        try (Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(pageSize);
            boolean hasResultSet = stmt.execute(sql);

            long queryTime = System.currentTimeMillis() - startTime;

            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    List<String> columnNames = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        columnNames.add(metaData.getColumnLabel(i));
                    }
                    result.setColumnNames(columnNames);

                    ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
                    long count = 0;
                    while (rs.next() && count < pageSize) {
                        ObservableList<String> row = FXCollections.observableArrayList();
                        for (int i = 1; i <= columnCount; i++) {
                            String val = rs.getString(i);
                            row.add(val != null ? val : "");
                        }
                        rows.add(row);
                        count++;
                    }
                    result.setRows(rows);
                    result.setTotalCount(count);
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                result.setColumnNames(List.of("结果"));
                ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
                ObservableList<String> row = FXCollections.observableArrayList();
                row.add(updateCount >= 0 ? updateCount + " 行受影响" : "执行成功");
                rows.add(row);
                result.setRows(rows);
                result.setTotalCount(1);
            }

            result.setPage(1);
            result.setPageSize(pageSize);
            result.setTotalPages(1);
            result.setQueryTime(queryTime);
        }

        return result;
    }

    /**
     * 构建分页查询SQL
     */
    private static String buildPageSql(ConnectionConfig config, String databaseName, String tableName, int page, int pageSize, String sortColumn, boolean sortDescending) {
        int offset = (page - 1) * pageSize;
        String orderBy = "";
        if (sortColumn != null && !sortColumn.isEmpty()) {
            String quotedCol = switch (config.getType()) {
                case MYSQL -> "`" + sortColumn + "`";
                case POSTGRESQL, ORACLE -> "\"" + sortColumn + "\"";
                default -> sortColumn;
            };
            orderBy = " ORDER BY " + quotedCol + (sortDescending ? " DESC" : " ASC");
        }
        final String order = orderBy;
        return switch (config.getType()) {
            case MYSQL -> "SELECT * FROM `" + databaseName + "`.`" + tableName + "`" + order + " LIMIT " + pageSize + " OFFSET " + offset;
            case POSTGRESQL -> "SELECT * FROM \"" + databaseName + "\".\"" + tableName + "\"" + order + " LIMIT " + pageSize + " OFFSET " + offset;
            case ORACLE -> "SELECT * FROM (SELECT a.*, ROWNUM rn FROM \"" + databaseName + "\".\"" + tableName + "\" a WHERE ROWNUM <= " + (offset + pageSize) + order + ") WHERE rn > " + offset;
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 删除多个表
     * @return 成功删除的表名列表
     */
    public static List<String> dropTables(ConnectionConfig config, String databaseName, List<String> tableNames) throws Exception {
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);
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
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);
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
     * 重命名表
     */
    public static void renameTable(ConnectionConfig config, String databaseName, String oldTableName, String newTableName) throws Exception {
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);
        String sql = switch (config.getType()) {
            case MYSQL -> "RENAME TABLE `" + databaseName + "`.`" + oldTableName + "` TO `" + databaseName + "`.`" + newTableName + "`";
            case POSTGRESQL -> "ALTER TABLE \"" + databaseName + "\".\"" + oldTableName + "\" RENAME TO \"" + newTableName + "\"";
            case ORACLE -> "ALTER TABLE \"" + databaseName + "\".\"" + oldTableName + "\" RENAME TO \"" + newTableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 重命名视图
     */
    public static void renameView(ConnectionConfig config, String databaseName, String oldViewName, String newViewName) throws Exception {
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);
        String sql = switch (config.getType()) {
            case MYSQL -> "RENAME TABLE `" + databaseName + "`.`" + oldViewName + "` TO `" + databaseName + "`.`" + newViewName + "`";
            case POSTGRESQL -> "ALTER VIEW \"" + databaseName + "\".\"" + oldViewName + "\" RENAME TO \"" + newViewName + "\"";
            case ORACLE -> "RENAME \"" + oldViewName + "\" TO \"" + newViewName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
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
     * 修改数据库字符集/排序规则
     */
    public static void alterDatabase(ConnectionConfig config, String databaseName, String charset, String collation) throws Exception {
        StringBuilder sql = new StringBuilder();
        if (config.getType() == ConnectType.MYSQL) {
            sql.append("ALTER DATABASE `").append(databaseName).append("`");
            if (charset != null && !charset.isEmpty()) {
                sql.append(" CHARACTER SET ").append(charset);
            }
            if (collation != null && !collation.isEmpty()) {
                sql.append(" COLLATE ").append(collation);
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            sql.append("ALTER DATABASE \"").append(databaseName).append("\"");
            if (charset != null && !charset.isEmpty()) {
                sql.append(" SET encoding = '").append(charset).append("'");
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            // Oracle不支持ALTER DATABASE修改字符集，跳过
            return;
        }
        if (sql.length() > 0) {
            Connection conn = getConnection(config);
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql.toString());
            }
        }
    }

    /**
     * 删除数据库
     */
    public static void dropDatabase(ConnectionConfig config, String databaseName) throws Exception {
        String sql = buildDropDatabaseSql(config, databaseName);
        Connection conn = getConnection(config);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /**
     * 获取数据库的当前字符集和排序规则
     */
    public static String[] getDatabaseCharsetCollation(ConnectionConfig config, String databaseName) throws Exception {
        // 查询 information_schema 并通过参数传入 databaseName，复用主连接
        Connection conn = getConnection(config);
        String charset = null;
        String collation = null;

        if (config.getType() == ConnectType.MYSQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        charset = rs.getString("DEFAULT_CHARACTER_SET_NAME");
                        collation = rs.getString("DEFAULT_COLLATION_NAME");
                    }
                }
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT pg_encoding_to_char(encoding) AS encoding, datcollate FROM pg_database WHERE datname = ?")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        charset = rs.getString("encoding");
                        collation = rs.getString("datcollate");
                    }
                }
            }
        }

        return new String[]{charset, collation};
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
     * 构建删除数据库SQL
     */
    private static String buildDropDatabaseSql(ConnectionConfig config, String databaseName) {
        return switch (config.getType()) {
            case MYSQL -> "DROP DATABASE `" + databaseName + "`";
            case POSTGRESQL -> "DROP DATABASE \"" + databaseName + "\"";
            case ORACLE -> "DROP USER \"" + databaseName + "\" CASCADE";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }

    /**
     * 关闭指定连接配置的JDBC连接和SSH隧道。
     * 会同时关闭主连接、所有数据库专用连接，以及SSH隧道。
     */
    public static void closeConnection(String configId) {
        // 关闭主连接
        Connection conn = connectionCache.remove(configId);
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) {}
        }
        // 关闭该配置下所有数据库专用连接（key 形如 configId_databaseName）
        String prefix = configId + "_";
        Iterator<Map.Entry<String, Connection>> it = databaseConnectionCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Connection> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                try { entry.getValue().close(); } catch (Exception ignored) {}
                it.remove();
            }
        }
        // 关闭SSH隧道
        SshTunnel tunnel = tunnelCache.remove(configId);
        if (tunnel != null) {
            tunnel.disconnect();
        }
        // 清理锁
        connectionLocks.remove(configId);
    }

    /**
     * 检查连接是否活跃（基于主连接判断）
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
     * 执行多条SQL语句，每条独立执行，一条失败不中断后续
     */
    public static MultiStatementResult executeMultiSqlQuery(ConnectionConfig config, String databaseName, String sql, int pageSize) throws Exception {
        List<String> statements = SqlSplitter.split(sql);
        MultiStatementResult multiResult = new MultiStatementResult();
        List<SqlStatementResult> results = new ArrayList<>();

        long totalStart = System.currentTimeMillis();
        Connection conn = getConnection(config, databaseName);

        for (String stmt : statements) {
            SqlStatementResult sr = new SqlStatementResult();
            sr.setSql(stmt);
            sr.setSelect(SqlSplitter.isSelectStatement(stmt));

            // 尝试从SELECT语句中提取源表名，用于右键删除行
            if (sr.isSelect()) {
                sr.setSourceTableName(SqlSplitter.extractTableName(stmt));
            }

            long start = System.currentTimeMillis();
            try (Statement jdbcStmt = conn.createStatement()) {
                jdbcStmt.setMaxRows(pageSize);
                boolean hasResultSet = jdbcStmt.execute(stmt);
                long queryTime = System.currentTimeMillis() - start;

                sr.setSuccess(true);
                sr.setQueryTime(queryTime);
                sr.setHasResultSet(hasResultSet);

                if (hasResultSet) {
                    try (ResultSet rs = jdbcStmt.getResultSet()) {
                        TableRowData result = new TableRowData();
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();

                        List<String> columnNames = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            columnNames.add(metaData.getColumnLabel(i));
                        }
                        result.setColumnNames(columnNames);

                        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
                        long count = 0;
                        while (rs.next() && count < pageSize) {
                            ObservableList<String> row = FXCollections.observableArrayList();
                            for (int i = 1; i <= columnCount; i++) {
                                String val = rs.getString(i);
                                row.add(val != null ? val : "");
                            }
                            rows.add(row);
                            count++;
                        }
                        result.setRows(rows);
                        result.setTotalCount(count);
                        result.setQueryTime(queryTime);
                        sr.setResultData(result);
                    }
                } else {
                    int updateCount = jdbcStmt.getUpdateCount();
                    sr.setUpdateCount(updateCount);
                }
            } catch (Exception e) {
                long queryTime = System.currentTimeMillis() - start;
                sr.setSuccess(false);
                sr.setQueryTime(queryTime);
                sr.setErrorMessage(e.getMessage());
            }

            results.add(sr);
        }

        multiResult.setResults(results);
        multiResult.setTotalTime(System.currentTimeMillis() - totalStart);
        return multiResult;
    }

    /**
     * 执行EXPLAIN查询，返回执行计划
     */
    public static TableRowData executeExplainQuery(ConnectionConfig config, String databaseName, String sql) {
        try {
            return executeSqlQuery(config, databaseName, "EXPLAIN " + sql, 1000);
        } catch (Exception e) {
            TableRowData result = new TableRowData();
            result.setColumnNames(List.of("错误"));
            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            rows.add(FXCollections.observableArrayList(e.getMessage()));
            result.setRows(rows);
            result.setTotalCount(1);
            return result;
        }
    }

    /**
     * 获取服务器状态信息
     */
    public static TableRowData executeStatusQuery(ConnectionConfig config, String databaseName) {
        try {
            Connection conn = getConnection(config, databaseName);
            if (config.getType() == ConnectType.MYSQL) {
                return executeSqlQuery(config, databaseName, "SHOW STATUS", 1000);
            } else if (config.getType() == ConnectType.POSTGRESQL) {
                return executeSqlQuery(config, databaseName, "SELECT name, setting, short_desc AS \"Description\", category FROM pg_settings ORDER BY category, name", 1000);
            } else {
                TableRowData result = new TableRowData();
                result.setColumnNames(List.of("信息"));
                ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
                rows.add(FXCollections.observableArrayList("当前数据库类型不支持状态查询"));
                result.setRows(rows);
                result.setTotalCount(1);
                return result;
            }
        } catch (Exception e) {
            TableRowData result = new TableRowData();
            result.setColumnNames(List.of("错误"));
            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            rows.add(FXCollections.observableArrayList(e.getMessage()));
            result.setRows(rows);
            result.setTotalCount(1);
            return result;
        }
    }

    /**
     * 获取表的主键列名列表
     * @return 主键列名列表，若无主键返回空列表
     */
    public static List<String> getPrimaryKeys(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        // JDBC 元数据 API 通过参数传入 databaseName，复用主连接
        Connection conn = getConnection(config);
        List<String> primaryKeys = new ArrayList<>();

        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(databaseName, null, tableName)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        return primaryKeys;
    }

    /**
     * 根据主键删除指定行
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param primaryKeyColumns 主键列名列表
     * @param columnNames 所有列名列表
     * @param rows 要删除的行数据（每行为列值列表，顺序与columnNames对应）
     * @return 删除的行数
     */
    public static int deleteRowsByPrimaryKeys(ConnectionConfig config, String databaseName, String tableName,
                                               List<String> primaryKeyColumns, List<String> columnNames,
                                               List<ObservableList<String>> rows) throws Exception {
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);
        int totalDeleted = 0;

        // 构建主键列在columnNames中的索引
        List<Integer> pkIndexes = new ArrayList<>();
        for (String pkCol : primaryKeyColumns) {
            int idx = -1;
            for (int i = 0; i < columnNames.size(); i++) {
                if (columnNames.get(i).equalsIgnoreCase(pkCol)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new RuntimeException("主键列 " + pkCol + " 在结果集中未找到");
            }
            pkIndexes.add(idx);
        }

        // 构建DELETE语句的WHERE部分：WHERE pk1=? AND pk2=?
        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL, ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        StringBuilder whereClause = new StringBuilder();
        for (int i = 0; i < primaryKeyColumns.size(); i++) {
            if (i > 0) whereClause.append(" AND ");
            String pkCol = primaryKeyColumns.get(i);
            String quotedCol = switch (config.getType()) {
                case MYSQL -> "`" + pkCol + "`";
                case POSTGRESQL, ORACLE -> "\"" + pkCol + "\"";
                default -> pkCol;
            };
            whereClause.append(quotedCol).append(" = ?");
        }

        String deleteSql = "DELETE FROM " + qualifiedTable + " WHERE " + whereClause;

        try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
            for (ObservableList<String> row : rows) {
                for (int i = 0; i < pkIndexes.size(); i++) {
                    String value = row.get(pkIndexes.get(i));
                    if ("NULL".equals(value)) {
                        pstmt.setNull(i + 1, Types.VARCHAR);
                    } else {
                        pstmt.setString(i + 1, value);
                    }
                }
                totalDeleted += pstmt.executeUpdate();
            }
        }

        return totalDeleted;
    }

    /**
     * 根据主键更新指定单元格
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param primaryKeyColumns 主键列名列表
     * @param columnNames 所有列名列表
     * @param row 行数据（主键值在row中按columnNames顺序取）
     * @param columnIndex 要更新的列在columnNames中的索引
     * @param newValue 新值
     * @return 受影响行数
     */
    public static int updateCell(ConnectionConfig config, String databaseName, String tableName,
                                 List<String> primaryKeyColumns, List<String> columnNames,
                                 ObservableList<String> row, int columnIndex, String newValue) throws Exception {
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);

        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL, ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        String updateCol = columnNames.get(columnIndex);
        String quotedUpdateCol = switch (config.getType()) {
            case MYSQL -> "`" + updateCol + "`";
            case POSTGRESQL, ORACLE -> "\"" + updateCol + "\"";
            default -> updateCol;
        };

        StringBuilder whereClause = new StringBuilder();
        List<Integer> pkIndexes = new ArrayList<>();
        for (int i = 0; i < primaryKeyColumns.size(); i++) {
            if (i > 0) whereClause.append(" AND ");
            String pkCol = primaryKeyColumns.get(i);
            String quotedCol = switch (config.getType()) {
                case MYSQL -> "`" + pkCol + "`";
                case POSTGRESQL, ORACLE -> "\"" + pkCol + "\"";
                default -> pkCol;
            };
            whereClause.append(quotedCol).append(" = ?");
            for (int j = 0; j < columnNames.size(); j++) {
                if (columnNames.get(j).equalsIgnoreCase(pkCol)) {
                    pkIndexes.add(j);
                    break;
                }
            }
        }

        String sql = "UPDATE " + qualifiedTable + " SET " + quotedUpdateCol + " = ? WHERE " + whereClause;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            if ("NULL".equals(newValue)) {
                pstmt.setNull(1, Types.VARCHAR);
            } else {
                pstmt.setString(1, newValue);
            }
            for (int i = 0; i < pkIndexes.size(); i++) {
                String pkValue = row.get(pkIndexes.get(i));
                if ("NULL".equals(pkValue)) {
                    pstmt.setNull(i + 2, Types.VARCHAR);
                } else {
                    pstmt.setString(i + 2, pkValue);
                }
            }
            return pstmt.executeUpdate();
        }
    }

    /**
     * 插入一行空数据（所有列设为DEFAULT/NULL）
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param columnNames 列名列表
     */
    public static void insertEmptyRow(ConnectionConfig config, String databaseName, String tableName,
                                      List<String> columnNames) throws Exception {
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);

        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL, ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) { cols.append(", "); vals.append(", "); }
            String quotedCol = switch (config.getType()) {
                case MYSQL -> "`" + columnNames.get(i) + "`";
                case POSTGRESQL, ORACLE -> "\"" + columnNames.get(i) + "\"";
                default -> columnNames.get(i);
            };
            cols.append(quotedCol);
            vals.append("DEFAULT");
        }

        String sql = "INSERT INTO " + qualifiedTable + " (" + cols + ") VALUES (" + vals + ")";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.executeUpdate();
        }
    }

    /**
     * 插入多行数据（使用具体值而非DEFAULT）
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param columnNames 列名列表
     * @param rows 要插入的行数据
     * @param primaryKeyColumns 主键列名列表（空值的自增PK列跳过，让DB自动生成）
     * @return 插入的行数
     */
    public static int insertRows(ConnectionConfig config, String databaseName, String tableName,
                                 List<String> columnNames, List<ObservableList<String>> rows,
                                 List<String> primaryKeyColumns) throws Exception {
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);
        int totalInserted = 0;

        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL, ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        for (ObservableList<String> row : rows) {
            // 确定要包含的列（空值的自增PK列跳过）
            List<Integer> includeIndexes = new ArrayList<>();
            StringBuilder cols = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();

            for (int i = 0; i < columnNames.size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                // 空值的主键列跳过（让DB自动生成）
                if (isNullOrEmpty(value) && isPrimaryKeyColumn(columnNames.get(i), primaryKeyColumns)) {
                    continue;
                }
                if (!includeIndexes.isEmpty()) { cols.append(", "); placeholders.append(", "); }
                String quotedCol = switch (config.getType()) {
                    case MYSQL -> "`" + columnNames.get(i) + "`";
                    case POSTGRESQL, ORACLE -> "\"" + columnNames.get(i) + "\"";
                    default -> columnNames.get(i);
                };
                cols.append(quotedCol);
                placeholders.append("?");
                includeIndexes.add(i);
            }

            String sql = "INSERT INTO " + qualifiedTable + " (" + cols + ") VALUES (" + placeholders + ")";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (int j = 0; j < includeIndexes.size(); j++) {
                    int colIdx = includeIndexes.get(j);
                    String value = colIdx < row.size() ? row.get(colIdx) : "";
                    if (isNullOrEmpty(value) || "NULL".equals(value)) {
                        pstmt.setNull(j + 1, Types.VARCHAR);
                    } else {
                        pstmt.setString(j + 1, value);
                    }
                }
                totalInserted += pstmt.executeUpdate();
            }
        }

        return totalInserted;
    }

    /**
     * 更新多行数据（仅更新修改过的列）
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @param primaryKeyColumns 主键列名列表
     * @param columnNames 所有列名列表
     * @param currentRows 当前行值
     * @param originalRows 原始行值（WHERE子句使用原始主键值）
     * @param modifiedColumnsPerRow 每行修改过的列索引集合
     * @return 总受影响行数
     */
    public static int updateRows(ConnectionConfig config, String databaseName, String tableName,
                                 List<String> primaryKeyColumns, List<String> columnNames,
                                 List<ObservableList<String>> currentRows,
                                 List<ObservableList<String>> originalRows,
                                 List<java.util.Set<Integer>> modifiedColumnsPerRow) throws Exception {
        // SQL 使用全限定名，复用主连接
        Connection conn = getConnection(config);
        int totalUpdated = 0;

        String qualifiedTable = switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL, ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };

        // 构建主键列索引映射
        List<Integer> pkIndexes = new ArrayList<>();
        for (String pkCol : primaryKeyColumns) {
            for (int i = 0; i < columnNames.size(); i++) {
                if (columnNames.get(i).equalsIgnoreCase(pkCol)) {
                    pkIndexes.add(i);
                    break;
                }
            }
        }

        for (int rowIdx = 0; rowIdx < currentRows.size(); rowIdx++) {
            ObservableList<String> currentRow = currentRows.get(rowIdx);
            ObservableList<String> originalRow = originalRows.get(rowIdx);
            java.util.Set<Integer> modifiedCols = modifiedColumnsPerRow.get(rowIdx);

            // SET子句仅包含修改过的列
            StringBuilder setClause = new StringBuilder();
            List<Integer> setColIndexes = new ArrayList<>();
            for (int colIdx : modifiedCols) {
                if (!setColIndexes.isEmpty()) setClause.append(", ");
                String colName = columnNames.get(colIdx);
                String quotedCol = switch (config.getType()) {
                    case MYSQL -> "`" + colName + "`";
                    case POSTGRESQL, ORACLE -> "\"" + colName + "\"";
                    default -> colName;
                };
                setClause.append(quotedCol).append(" = ?");
                setColIndexes.add(colIdx);
            }

            // WHERE子句使用原始主键值
            StringBuilder whereClause = new StringBuilder();
            for (int i = 0; i < primaryKeyColumns.size(); i++) {
                if (i > 0) whereClause.append(" AND ");
                String pkCol = primaryKeyColumns.get(i);
                String quotedCol = switch (config.getType()) {
                    case MYSQL -> "`" + pkCol + "`";
                    case POSTGRESQL, ORACLE -> "\"" + pkCol + "\"";
                    default -> pkCol;
                };
                whereClause.append(quotedCol).append(" = ?");
            }

            String sql = "UPDATE " + qualifiedTable + " SET " + setClause + " WHERE " + whereClause;

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int paramIdx = 1;
                // SET参数（新值）
                for (int colIdx : setColIndexes) {
                    String newValue = currentRow.get(colIdx);
                    if ("NULL".equals(newValue) || isNullOrEmpty(newValue)) {
                        pstmt.setNull(paramIdx++, Types.VARCHAR);
                    } else {
                        pstmt.setString(paramIdx++, newValue);
                    }
                }
                // WHERE参数（原始主键值）
                for (int pkIdx : pkIndexes) {
                    String pkValue = originalRow.get(pkIdx);
                    if ("NULL".equals(pkValue)) {
                        pstmt.setNull(paramIdx++, Types.VARCHAR);
                    } else {
                        pstmt.setString(paramIdx++, pkValue);
                    }
                }
                totalUpdated += pstmt.executeUpdate();
            }
        }

        return totalUpdated;
    }

    private static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean isPrimaryKeyColumn(String columnName, List<String> primaryKeyColumns) {
        if (primaryKeyColumns == null) return false;
        for (String pk : primaryKeyColumns) {
            if (pk.equalsIgnoreCase(columnName)) return true;
        }
        return false;
    }

    /**
     * 获取表的列信息列表
     * @param config 连接配置
     * @param databaseName 数据库名
     * @param tableName 表名
     * @return 列信息列表，每个元素为一个列的属性Map
     */
    public static List<Map<String, String>> getTableColumns(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        // JDBC 元数据 API 通过参数传入 databaseName，复用主连接
        Connection conn = getConnection(config);
        List<Map<String, String>> columns = new ArrayList<>();

        // 获取主键列表
        List<String> primaryKeys = new ArrayList<>();
        try (ResultSet pkRs = conn.getMetaData().getPrimaryKeys(databaseName, null, tableName)) {
            while (pkRs.next()) {
                primaryKeys.add(pkRs.getString("COLUMN_NAME"));
            }
        }

        // 获取列信息
        try (ResultSet rs = conn.getMetaData().getColumns(databaseName, null, tableName, null)) {
            while (rs.next()) {
                Map<String, String> col = new LinkedHashMap<>();
                String colName = rs.getString("COLUMN_NAME");
                col.put("字段名", colName);
                col.put("类型", rs.getString("TYPE_NAME"));
                col.put("长度", rs.getString("COLUMN_SIZE"));
                col.put("非空", "NO".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? "是" : "否");
                col.put("主键", primaryKeys.contains(colName) ? "是" : "否");
                String autoIncrement = rs.getString("IS_AUTOINCREMENT");
                col.put("自增", "YES".equalsIgnoreCase(autoIncrement) ? "是" : "否");
                String defaultValue = rs.getString("COLUMN_DEF");
                col.put("默认值", defaultValue != null ? defaultValue : "");
                String remarks = rs.getString("REMARKS");
                col.put("注释", remarks != null ? remarks : "");
                columns.add(col);
            }
        }

        return columns;
    }

    /**
     * 获取数据库产品版本字符串
     * @param config 连接配置
     * @return 版本字符串，如 "8.0.32"、"15.2"、"19c"
     */
    public static String getDatabaseProductVersion(ConnectionConfig config) throws Exception {
        Connection conn = getConnection(config);
        return conn.getMetaData().getDatabaseProductVersion();
    }

    /**
     * 构建JDBC URL
     */
    private static String buildJdbcUrl(ConnectionConfig config, String host, int port, String database) {
        String db = (database != null && !database.isEmpty()) ? database : "";
        return switch (config.getType()) {
            // MySQL 性能优化参数：
            //   useLocalSessionState=true      避免每次执行前查询 @@session.tx_* 等变量
            //   cacheDefaultDatabase=true      缓存当前数据库，避免每次查询 currentDatabase()
            //   cacheServerConfiguration=true  缓存服务器配置，避免重复查询系统变量
            //   maintainTimeStats=false        不维护时间统计，减少额外查询
            //   prepStmtCacheEnabled=true      启用服务端预编译语句缓存
            //   prepStmtCacheSize=250          预编译语句缓存大小
            //   useServerPrepStmts=true        使用服务端预编译
            //   rewriteBatchedStatements=true  批量语句重写优化
            //   connectTimeout=5000            连接握手超时 5s
            //   socketTimeout=30000            socket 读超时 30s
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&useLocalSessionState=true&cacheDefaultDatabase=true&cacheServerConfiguration=true"
                    + "&maintainTimeStats=false&prepStmtCacheEnabled=true&prepStmtCacheSize=250"
                    + "&useServerPrepStmts=true&rewriteBatchedStatements=true"
                    + "&connectTimeout=5000&socketTimeout=30000";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?connectTimeout=5&socketTimeout=30";
            case ORACLE -> "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
            default -> throw new IllegalArgumentException("Unsupported database type: " + config.getType());
        };
    }
}
