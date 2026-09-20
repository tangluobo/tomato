package com.tangluobo.tomato.module.connect;

import java.util.Objects;

/**
 * A concrete database product exposed by the connection picker.
 *
 * <p>{@code dialectType} identifies the SQL/metadata compatibility family used
 * by the existing relational workbench. Products are listed separately in the
 * UI while compatible engines reuse the proven query, table-data and table-
 * designer paths of MySQL, PostgreSQL or Oracle.</p>
 */
public record DatabaseProfile(
        String id,
        String displayName,
        ConnectType dialectType,
        int defaultPort,
        String driverClass,
        JdbcUrlStyle jdbcUrlStyle,
        String iconPath) {

    public DatabaseProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(dialectType, "dialectType");
        Objects.requireNonNull(driverClass, "driverClass");
        Objects.requireNonNull(jdbcUrlStyle, "jdbcUrlStyle");
        Objects.requireNonNull(iconPath, "iconPath");
    }

    public String buildJdbcUrl(String host, int port, String database) {
        String db = database == null ? "" : database;
        return switch (jdbcUrlStyle) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                    + "&useLocalSessionState=true&cacheDefaultDatabase=true&cacheServerConfiguration=true"
                    + "&maintainTimeStats=false&prepStmtCacheEnabled=true&prepStmtCacheSize=250"
                    + "&useServerPrepStmts=true&rewriteBatchedStatements=true"
                    + "&tinyInt1isBit=false&connectTimeout=5000&socketTimeout=30000";
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + db
                    + "?useSsl=false&connectTimeout=5000&socketTimeout=30000"
                    + "&useServerPrepStmts=true&rewriteBatchedStatements=true";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + db
                    + "?connectTimeout=5&socketTimeout=300";
            case ORACLE_SID_OR_SERVICE -> db.startsWith("/")
                    ? "jdbc:oracle:thin:@//" + host + ":" + port + db
                    : "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
            case HIVE -> "jdbc:hive2://" + host + ":" + port + "/"
                    + (db.isBlank() ? "default" : db);
        };
    }

    public enum JdbcUrlStyle {
        MYSQL,
        MARIADB,
        POSTGRESQL,
        ORACLE_SID_OR_SERVICE,
        HIVE
    }
}
