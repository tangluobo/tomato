package com.tangluobo.tomato.module.connect;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public class BackupService {

    private static final String APP_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String BACKUP_DIR = "backup";
    private static final byte[] NB3_MAGIC = {'N', 'B', '3', 0};
    private static final short NB3_VERSION = 3;

    public static String createBackup(ConnectionConfig config, String databaseName,
                                       List<BackupDialog.BackupObject> objects, String comment,
                                       boolean lockTables, boolean singleTransaction,
                                       String filename, BackupDialog.BackupTask task) throws Exception {

        String sanitizedConn = sanitizeFileName(config.getName());
        String sanitizedDb = sanitizeFileName(databaseName);

        Path dir = Paths.get(APP_DIR, sanitizedConn, sanitizedDb, BACKUP_DIR);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file.toFile()))) {
            writeHeader(dos, config, databaseName, comment, objects.size());

            int processed = 0;
            long totalRecords = 0;

            for (BackupDialog.BackupObject obj : objects) {
                if (task.isCancelled()) {
                    throw new Exception("备份已取消");
                }

                task.log("处理 " + obj.getType().getDisplayName() + ": " + obj.getName());

                switch (obj.getType()) {
                    case TABLE -> {
                        writeTableBackup(dos, config, databaseName, obj, lockTables, task);
                    }
                    case VIEW -> {
                        writeViewBackup(dos, config, databaseName, obj);
                    }
                    case FUNCTION -> {
                        writeFunctionBackup(dos, config, databaseName, obj);
                    }
                    case EVENT -> {
                        writeEventBackup(dos, config, databaseName, obj);
                    }
                }

                processed++;
                task.updateProgress(processed);
            }
        }

        return file.toAbsolutePath().toString();
    }

    private static void writeHeader(DataOutputStream dos, ConnectionConfig config,
                                     String databaseName, String comment, int objectCount) throws IOException {
        dos.write(NB3_MAGIC);
        dos.writeShort(NB3_VERSION);
        dos.writeShort(0);

        byte[] serverBytes = config.getName().getBytes(StandardCharsets.UTF_8);
        dos.writeInt(serverBytes.length);
        dos.write(serverBytes);

        byte[] dbBytes = databaseName.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(dbBytes.length);
        dos.write(dbBytes);

        byte[] commentBytes = (comment != null ? comment : "").getBytes(StandardCharsets.UTF_8);
        dos.writeInt(commentBytes.length);
        dos.write(commentBytes);

        dos.writeInt(objectCount);

        long timestamp = System.currentTimeMillis();
        dos.writeLong(timestamp);

        byte[] hostBytes = config.getHost().getBytes(StandardCharsets.UTF_8);
        dos.writeInt(hostBytes.length);
        dos.write(hostBytes);
        dos.writeInt(config.getPort());
    }

    private static void writeTableBackup(DataOutputStream dos, ConnectionConfig config,
                                          String databaseName, BackupDialog.BackupObject obj,
                                          boolean lockTables, BackupDialog.BackupTask task) throws Exception {

        dos.writeByte(BackupObjectType.TABLE.getId());
        writeString(dos, obj.getName());

        String createSql = getCreateTableSQL(config, databaseName, obj.getName());
        byte[] sqlBytes = createSql.getBytes(StandardCharsets.UTF_8);
        byte[] compressedSql = compress(sqlBytes);
        dos.writeInt(compressedSql.length);
        dos.write(compressedSql);

        Connection conn = DatabaseService.getConnection(config, databaseName);
        List<String> columnNames = new ArrayList<>();
        List<String> columnTypes = new ArrayList<>();

        String countSql = buildSelectSql(config, databaseName, obj.getName());
        long rowCount = 0;

        String countQuery = switch (config.getType()) {
            case MYSQL -> "SELECT COUNT(*) FROM `" + databaseName + "`.`" + obj.getName() + "`";
            case POSTGRESQL -> "SELECT COUNT(*) FROM \"" + databaseName + "\".\"" + obj.getName() + "\"";
            case ORACLE -> "SELECT COUNT(*) FROM \"" + databaseName + "\".\"" + obj.getName() + "\"";
            default -> "SELECT COUNT(*) FROM " + obj.getName();
        };

        try (PreparedStatement countStmt = conn.prepareStatement(countQuery);
             ResultSet rs = countStmt.executeQuery()) {
            if (rs.next()) {
                rowCount = rs.getLong(1);
            }
        }

        dos.writeLong(rowCount);

        if (rowCount > 0) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(countSql)) {

                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    columnNames.add(meta.getColumnLabel(i));
                    columnTypes.add(meta.getColumnTypeName(i));
                }

                long processed = 0;
                while (rs.next()) {
                    if (task.isCancelled()) break;

                    dos.writeShort(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        boolean isNull = rs.wasNull();
                        dos.writeBoolean(isNull);

                        if (!isNull) {
                            if (val instanceof byte[] bytes) {
                                dos.writeByte(ColumnType.BINARY.getId());
                                dos.writeInt(bytes.length);
                                dos.write(bytes);
                            } else {
                                String strVal = val.toString();
                                byte[] valBytes = strVal.getBytes(StandardCharsets.UTF_8);
                                dos.writeByte(ColumnType.STRING.getId());
                                dos.writeInt(valBytes.length);
                                dos.write(valBytes);
                            }
                        } else {
                            dos.writeByte(ColumnType.NULL.getId());
                        }
                    }

                    processed++;
                    if (processed % 1000 == 0) {
                        task.incrementRecordCount(processed);
                    }
                }
                task.incrementRecordCount(processed);
            }
        }

        dos.writeInt(columnNames.size());
        for (int i = 0; i < columnNames.size(); i++) {
            writeString(dos, columnNames.get(i));
            writeString(dos, columnTypes.get(i));
        }
    }

    private static void writeViewBackup(DataOutputStream dos, ConnectionConfig config,
                                         String databaseName, BackupDialog.BackupObject obj) throws Exception {
        dos.writeByte(BackupObjectType.VIEW.getId());
        writeString(dos, obj.getName());

        String createSql = getCreateViewSQL(config, databaseName, obj.getName());
        byte[] sqlBytes = createSql.getBytes(StandardCharsets.UTF_8);
        byte[] compressedSql = compress(sqlBytes);
        dos.writeInt(compressedSql.length);
        dos.write(compressedSql);
    }

    private static void writeFunctionBackup(DataOutputStream dos, ConnectionConfig config,
                                              String databaseName, BackupDialog.BackupObject obj) throws Exception {
        dos.writeByte(BackupObjectType.FUNCTION.getId());
        writeString(dos, obj.getName());

        String createSql = getCreateFunctionSQL(config, databaseName, obj.getName());
        byte[] sqlBytes = createSql.getBytes(StandardCharsets.UTF_8);
        byte[] compressedSql = compress(sqlBytes);
        dos.writeInt(compressedSql.length);
        dos.write(compressedSql);
    }

    private static void writeEventBackup(DataOutputStream dos, ConnectionConfig config,
                                          String databaseName, BackupDialog.BackupObject obj) throws Exception {
        dos.writeByte(BackupObjectType.EVENT.getId());
        writeString(dos, obj.getName());

        String createSql = getCreateEventSQL(config, databaseName, obj.getName());
        byte[] sqlBytes = createSql.getBytes(StandardCharsets.UTF_8);
        byte[] compressedSql = compress(sqlBytes);
        dos.writeInt(compressedSql.length);
        dos.write(compressedSql);
    }

    private static void writeString(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    private static String getCreateTableSQL(ConnectionConfig config, String databaseName, String tableName) throws Exception {
        Connection conn = DatabaseService.getConnection(config, databaseName);
        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `" + tableName + "`")) {
                if (rs.next()) {
                    return rs.getString("Create Table");
                }
            }
        }
        return "";
    }

    private static String getCreateViewSQL(ConnectionConfig config, String databaseName, String viewName) throws Exception {
        Connection conn = DatabaseService.getConnection(config, databaseName);
        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE VIEW `" + viewName + "`")) {
                if (rs.next()) {
                    return rs.getString("Create View");
                }
            }
        }
        return "";
    }

    private static String getCreateFunctionSQL(ConnectionConfig config, String databaseName, String funcName) throws Exception {
        Connection conn = DatabaseService.getConnection(config, databaseName);
        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE FUNCTION `" + funcName + "`")) {
                if (rs.next()) {
                    return rs.getString("Create Function");
                }
            }
        }
        return "";
    }

    private static String getCreateEventSQL(ConnectionConfig config, String databaseName, String eventName) throws Exception {
        Connection conn = DatabaseService.getConnection(config, databaseName);
        if (config.getType() == ConnectType.MYSQL) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW CREATE EVENT `" + eventName + "`")) {
                if (rs.next()) {
                    return rs.getString("Create Event");
                }
            }
        }
        return "";
    }

    private static String buildSelectSql(ConnectionConfig config, String databaseName, String tableName) {
        return switch (config.getType()) {
            case MYSQL -> "SELECT * FROM `" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "SELECT * FROM \"" + databaseName + "\".\"" + tableName + "\"";
            case ORACLE -> "SELECT * FROM \"" + databaseName + "\".\"" + tableName + "\"";
            default -> "SELECT * FROM " + tableName;
        };
    }

    private static String quoteIdentifier(ConnectionConfig config, String name) {
        return switch (config.getType()) {
            case MYSQL -> "`" + name + "`";
            case POSTGRESQL, ORACLE -> "\"" + name + "\"";
            default -> name;
        };
    }

    private static byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(data);
        }
        return baos.toByteArray();
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                   .replaceAll("\\s+", "_")
                   .replaceAll("_{2,}", "_")
                   .replaceAll("^_|_$", "");
    }

    public static List<String> listBackups(String connectionName, String dbName) {
        String sanitizedConn = sanitizeFileName(connectionName);
        String sanitizedDb = sanitizeFileName(dbName);
        Path dir = Paths.get(APP_DIR, sanitizedConn, sanitizedDb, BACKUP_DIR);
        List<String> backups = new ArrayList<>();
        if (!Files.isDirectory(dir)) return backups;

        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".nb3"))
                  .forEach(p -> {
                      String fileName = p.getFileName().toString();
                      backups.add(fileName.substring(0, fileName.length() - 4));
                  });
        } catch (IOException e) {
            System.err.println("加载备份列表失败: " + e.getMessage());
        }
        return backups;
    }

    public static void deleteBackupFile(String connectionName, String dbName, String backupName) {
        String sanitizedConn = sanitizeFileName(connectionName);
        String sanitizedDb = sanitizeFileName(dbName);
        Path file = Paths.get(APP_DIR, sanitizedConn, sanitizedDb, BACKUP_DIR, backupName + ".nb3");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("删除备份文件失败: " + e.getMessage());
        }
    }

    public static void renameBackupFile(String connectionName, String dbName,
                                         String oldName, String newName) throws IOException {
        String sanitizedConn = sanitizeFileName(connectionName);
        String sanitizedDb = sanitizeFileName(dbName);
        String sanitizedNew = sanitizeFileName(newName);

        Path oldFile = Paths.get(APP_DIR, sanitizedConn, sanitizedDb, BACKUP_DIR, oldName + ".nb3");
        Path newFile = Paths.get(APP_DIR, sanitizedConn, sanitizedDb, BACKUP_DIR, sanitizedNew + ".nb3");

        if (Files.exists(oldFile)) {
            Files.move(oldFile, newFile);
        }
    }

    public static byte[] decompress(byte[] data) throws IOException {
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = iis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    private enum BackupObjectType {
        TABLE(1), VIEW(2), FUNCTION(3), EVENT(4);
        private final int id;
        BackupObjectType(int id) { this.id = id; }
        public int getId() { return id; }
    }

    private enum ColumnType {
        NULL(0), STRING(1), BINARY(2);
        private final int id;
        ColumnType(int id) { this.id = id; }
        public int getId() { return id; }
    }
}