package com.tangluobo.tomato.module.connect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 根据数据库类型和版本提供对应的数据类型列表
 */
public class DataTypeProvider {

    /**
     * 根据数据库类型和版本获取数据类型列表
     * @param connectType 数据库类型
     * @param version 数据库版本字符串（如 "8.0.32"、"15.2"、"19c"），可为null
     * @return 数据类型名称列表
     */
    public static List<String> getDataTypes(ConnectType connectType, String version) {
        List<String> types = switch (connectType) {
            case MYSQL -> getMySqlTypes(version);
            case POSTGRESQL -> getPostgreSqlTypes(version);
            case ORACLE -> getOracleTypes(version);
            default -> Collections.emptyList();
        };
        // 按字母排序
        types.sort(String.CASE_INSENSITIVE_ORDER);
        return types;
    }

    private static List<String> getMySqlTypes(String version) {
        List<String> types = new ArrayList<>(List.of(
                // 整数类型
                "INT", "TINYINT", "SMALLINT", "MEDIUMINT", "BIGINT",
                // 浮点/定点类型
                "FLOAT", "DOUBLE", "DECIMAL", "NUMERIC",
                // 字符串类型
                "CHAR", "VARCHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT",
                // 二进制类型
                "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BINARY", "VARBINARY",
                // 日期时间类型
                "DATE", "TIME", "DATETIME", "TIMESTAMP", "YEAR",
                // 其他类型
                "ENUM", "SET", "BIT", "BOOL", "BOOLEAN"
        ));

        // MySQL 5.7.8+ 支持 JSON
        int major = parseMajorVersion(version);
        int minor = parseMinorVersion(version);
        if (version == null || major > 5 || (major == 5 && minor >= 7)) {
            types.add("JSON");
        }

        return types;
    }

    private static List<String> getPostgreSqlTypes(String version) {
        List<String> types = new ArrayList<>(List.of(
                // 整数类型
                "smallint", "integer", "bigint", "smallserial", "serial", "bigserial",
                // 浮点/定点类型
                "decimal", "numeric", "real", "double precision",
                // 字符串类型
                "character varying", "character", "text",
                // 布尔类型
                "boolean",
                // 日期时间类型
                "date", "time", "timestamp", "timestamptz", "timetz", "interval",
                // 二进制类型
                "bytea",
                // JSON类型
                "json", "jsonb",
                // 网络地址类型
                "cidr", "inet", "macaddr", "macaddr8",
                // UUID类型
                "uuid",
                // XML类型
                "xml",
                // 货币类型
                "money",
                // 几何类型
                "point", "line", "lseg", "box", "path", "polygon", "circle",
                // 位串类型
                "bit", "bit varying",
                // 范围类型
                "int4range", "int8range", "numrange", "tsrange", "tstzrange", "daterange"
        ));

        // PostgreSQL 14+ 支持 multirange 类型
        int major = parseMajorVersion(version);
        if (version == null || major >= 14) {
            types.add("int4multirange");
            types.add("int8multirange");
            types.add("nummultirange");
            types.add("tsmultirange");
            types.add("tstzmultirange");
            types.add("datemultirange");
        }

        return types;
    }

    private static List<String> getOracleTypes(String version) {
        List<String> types = new ArrayList<>(List.of(
                // 数值类型
                "NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE",
                // 字符串类型
                "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "CLOB", "NCLOB",
                // 二进制类型
                "BLOB", "BFILE", "RAW", "LONG", "LONG RAW",
                // 日期时间类型
                "DATE", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE",
                // 间隔类型
                "INTERVAL YEAR TO MONTH", "INTERVAL DAY TO SECOND",
                // 行标识类型
                "ROWID", "UROWID",
                // XML类型
                "XMLType"
        ));

        // Oracle 21c+ 支持 JSON 类型
        int major = parseMajorVersion(version);
        if (version == null || major >= 21) {
            types.add("JSON");
        }

        return types;
    }

    /**
     * 从版本字符串中解析主版本号
     * MySQL: "8.0.32" → 8
     * PostgreSQL: "15.2" → 15
     * Oracle: "19c" 或 "19.0.0.0.0" → 19
     * @param version 版本字符串，可为null
     * @return 主版本号，解析失败返回0
     */
    private static int parseMajorVersion(String version) {
        if (version == null || version.isBlank()) return 0;
        try {
            // 去掉可能的后缀如 "c"
            String cleaned = version.replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return 0;
            String[] parts = cleaned.split("\\.");
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 从版本字符串中解析次版本号
     * @param version 版本字符串，可为null
     * @return 次版本号，解析失败返回0
     */
    private static int parseMinorVersion(String version) {
        if (version == null || version.isBlank()) return 0;
        try {
            String cleaned = version.replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return 0;
            String[] parts = cleaned.split("\\.");
            if (parts.length >= 2) {
                return Integer.parseInt(parts[1]);
            }
            return 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
