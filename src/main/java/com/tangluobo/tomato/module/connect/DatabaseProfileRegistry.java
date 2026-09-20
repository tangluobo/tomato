package com.tangluobo.tomato.module.connect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.tangluobo.tomato.module.connect.DatabaseProfile.JdbcUrlStyle.MARIADB;
import static com.tangluobo.tomato.module.connect.DatabaseProfile.JdbcUrlStyle.MYSQL;
import static com.tangluobo.tomato.module.connect.DatabaseProfile.JdbcUrlStyle.ORACLE_SID_OR_SERVICE;
import static com.tangluobo.tomato.module.connect.DatabaseProfile.JdbcUrlStyle.POSTGRESQL;
import static com.tangluobo.tomato.module.connect.DatabaseProfile.JdbcUrlStyle.HIVE;

/** Registry of relational database products supported through compatible protocols. */
public final class DatabaseProfileRegistry {

    private static final String LOGO_DIR = "/images/connect/database-logos/";
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String MARIADB_DRIVER = "org.mariadb.jdbc.Driver";
    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";
    private static final String ORACLE_DRIVER = "oracle.jdbc.OracleDriver";
    private static final String HIVE_DRIVER = "org.apache.hive.jdbc.HiveDriver";

    private static final List<DatabaseProfile> PROFILES = List.of(
            profile("mysql", "MySQL", ConnectType.MYSQL, 3306, MYSQL_DRIVER, MYSQL, logo("mysql")),
            profile("mariadb", "MariaDB", ConnectType.MYSQL, 3306, MARIADB_DRIVER, MARIADB, logo("mariadb")),
            profile("tidb", "TiDB", ConnectType.MYSQL, 4000, MYSQL_DRIVER, MYSQL, logo("tidb")),
            profile("oceanbase-mysql", "OceanBase (MySQL)", ConnectType.MYSQL, 2881, MYSQL_DRIVER, MYSQL, logo("oceanbase")),
            profile("doris", "Apache Doris", ConnectType.MYSQL, 9030, MYSQL_DRIVER, MYSQL, logo("doris")),
            profile("starrocks", "StarRocks", ConnectType.MYSQL, 9030, MYSQL_DRIVER, MYSQL, logo("starrocks")),
            profile("goldendb", "GoldenDB", ConnectType.MYSQL, 3306, MYSQL_DRIVER, MYSQL, logo("goldendb")),

            profile("postgresql", "PostgreSQL", ConnectType.POSTGRESQL, 5432,
                    POSTGRESQL_DRIVER, POSTGRESQL, logo("postgresql")),
            // KingbaseES/openGauss family products expose PostgreSQL-compatible
            // wire protocols. Their standard mode can therefore use the bundled
            // PostgreSQL JDBC driver and the complete PostgreSQL workbench path.
            profile("kingbase", "人大金仓 KingbaseES", ConnectType.POSTGRESQL, 54321,
                    POSTGRESQL_DRIVER, POSTGRESQL, logo("kingbase")),
            profile("opengauss", "openGauss", ConnectType.POSTGRESQL, 5432,
                    POSTGRESQL_DRIVER, POSTGRESQL, logo("opengauss")),
            profile("gaussdb", "GaussDB", ConnectType.POSTGRESQL, 8000,
                    POSTGRESQL_DRIVER, POSTGRESQL, logo("gaussdb")),
            profile("highgo", "瀚高 HighGo", ConnectType.POSTGRESQL, 5866,
                    POSTGRESQL_DRIVER, POSTGRESQL, logo("highgo")),
            profile("vastbase", "海量 Vastbase", ConnectType.POSTGRESQL, 5432,
                    POSTGRESQL_DRIVER, POSTGRESQL, logo("vastbase")),
            profile("cockroachdb", "CockroachDB", ConnectType.POSTGRESQL, 26257,
                    POSTGRESQL_DRIVER, POSTGRESQL, logo("cockroachdb")),
            profile("redshift", "Amazon Redshift", ConnectType.POSTGRESQL, 5439,
                    POSTGRESQL_DRIVER, POSTGRESQL, logo("redshift")),

            profile("oracle", "Oracle", ConnectType.ORACLE, 1521,
                    ORACLE_DRIVER, ORACLE_SID_OR_SERVICE, logo("oracle")),

            profile("hive", "Apache Hive", ConnectType.HIVE, 10000,
                    HIVE_DRIVER, HIVE, logo("hive"))
    );

    private static final Map<String, DatabaseProfile> BY_ID = indexProfiles();

    private DatabaseProfileRegistry() {
    }

    public static List<DatabaseProfile> profiles() {
        return PROFILES;
    }

    public static DatabaseProfile find(String id) {
        if (id == null || id.isBlank()) return null;
        return BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public static DatabaseProfile resolve(ConnectionConfig config) {
        if (config == null) return null;
        DatabaseProfile configured = find(config.getDatabaseProfileId());
        if (configured != null) return configured;
        return find(defaultProfileId(config.getType()));
    }

    public static String defaultProfileId(ConnectType type) {
        if (type == null) return null;
        return switch (type) {
            case MYSQL -> "mysql";
            case POSTGRESQL -> "postgresql";
            case ORACLE -> "oracle";
            case HIVE -> "hive";
            default -> null;
        };
    }

    private static DatabaseProfile profile(String id, String displayName, ConnectType dialectType,
                                           int defaultPort, String driverClass,
                                           DatabaseProfile.JdbcUrlStyle urlStyle, String iconPath) {
        return new DatabaseProfile(id, displayName, dialectType, defaultPort, driverClass, urlStyle, iconPath);
    }

    private static String logo(String name) {
        return LOGO_DIR + name + ".svg";
    }

    private static Map<String, DatabaseProfile> indexProfiles() {
        Map<String, DatabaseProfile> profiles = new LinkedHashMap<>();
        for (DatabaseProfile profile : PROFILES) {
            DatabaseProfile previous = profiles.put(profile.id(), profile);
            if (previous != null) {
                throw new IllegalStateException("Duplicate database profile id: " + profile.id());
            }
        }
        return Map.copyOf(profiles);
    }
}
