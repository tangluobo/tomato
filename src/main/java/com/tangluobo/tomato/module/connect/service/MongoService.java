package com.tangluobo.tomato.module.connect.service;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoNamespace;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.SshTunnel;
import org.bson.Document;
import org.bson.json.JsonWriterSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;

/** MongoDB 连接、文档、命令、索引与集合校验规则服务。 */
public final class MongoService {
    private static final Map<String, MongoClient> CLIENTS = new ConcurrentHashMap<>();
    private static final Map<String, SshTunnel> TUNNELS = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final JsonWriterSettings PRETTY_JSON = JsonWriterSettings.builder().indent(true).build();

    private MongoService() {
    }

    public static MongoClient getClient(ConnectionConfig config) {
        String key = config.getId();
        MongoClient existing = CLIENTS.get(key);
        if (existing != null) return existing;

        synchronized (LOCKS.computeIfAbsent(key, ignored -> new Object())) {
            existing = CLIENTS.get(key);
            if (existing != null) return existing;
            MongoClient created = MongoClients.create(buildSettings(config));
            try {
                created.getDatabase(authenticationDatabase(config)).runCommand(new Document("ping", 1));
            } catch (RuntimeException e) {
                created.close();
                closeTunnel(config.getId());
                throw e;
            }
            CLIENTS.put(key, created);
            return created;
        }
    }

    static MongoClientSettings buildSettings(ConnectionConfig config) {
        String host = config.getHost() == null ? "" : config.getHost().trim();
        String connectionString;
        if (host.startsWith("mongodb://") || host.startsWith("mongodb+srv://")) {
            if (config.isUseSshTunnel()) {
                throw new IllegalArgumentException("连接 URI 模式暂不支持 SSH 通道，请填写单个 MongoDB 主机地址");
            }
            connectionString = host;
        } else {
            int port = config.getPort();
            if (config.isUseSshTunnel()) {
                port = ensureTunnel(config);
                host = "127.0.0.1";
            }
            connectionString = "mongodb://" + host + ":" + port + "/";
        }

        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .applyToClusterSettings(settings -> settings.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(settings -> settings
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS));
        String username = config.getUsername();
        if (username != null && !username.isBlank()) {
            char[] password = config.getPassword() == null ? new char[0] : config.getPassword().toCharArray();
            builder.credential(MongoCredential.createCredential(
                    username.trim(), authenticationDatabase(config), password));
        }
        return builder.build();
    }

    public static List<String> getDatabases(ConnectionConfig config) {
        List<String> names = new ArrayList<>();
        getClient(config).listDatabaseNames().into(names);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static List<String> getCollections(ConnectionConfig config, String databaseName) {
        List<String> names = new ArrayList<>();
        database(config, databaseName).listCollectionNames().into(names);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static List<String> findDocuments(ConnectionConfig config, String databaseName,
                                             String collectionName, String filterJson,
                                             int skip, int limit) {
        Document filter = parseObject(filterJson, "查询条件");
        FindIterable<Document> query = collection(config, databaseName, collectionName)
                .find(filter).skip(Math.max(0, skip)).limit(Math.max(1, limit));
        List<String> result = new ArrayList<>();
        for (Document document : query) {
            result.add(document.toJson(PRETTY_JSON));
        }
        return result;
    }

    public static String insertDocument(ConnectionConfig config, String databaseName,
                                        String collectionName, String json) {
        Document document = parseObject(json, "文档");
        collection(config, databaseName, collectionName).insertOne(document);
        return document.toJson(PRETTY_JSON);
    }

    public static String replaceDocument(ConnectionConfig config, String databaseName,
                                         String collectionName, String originalJson, String newJson) {
        Document original = parseObject(originalJson, "原文档");
        Object id = original.get("_id");
        if (id == null) throw new IllegalArgumentException("文档缺少 _id，无法安全定位并保存");
        Document replacement = parseObject(newJson, "文档");
        Object replacementId = replacement.get("_id");
        if (replacementId == null) {
            replacement.put("_id", id);
        } else if (!replacementId.equals(id)) {
            throw new IllegalArgumentException("MongoDB 不允许修改文档的 _id");
        }
        long matched = collection(config, databaseName, collectionName)
                .replaceOne(eq("_id", id), replacement).getMatchedCount();
        if (matched == 0) throw new IllegalStateException("原文档已不存在，请刷新后重试");
        return replacement.toJson(PRETTY_JSON);
    }

    public static boolean deleteDocument(ConnectionConfig config, String databaseName,
                                         String collectionName, String json) {
        Object id = parseObject(json, "文档").get("_id");
        if (id == null) throw new IllegalArgumentException("文档缺少 _id，无法安全删除");
        return collection(config, databaseName, collectionName).deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }

    public static String runCommand(ConnectionConfig config, String databaseName, String commandJson) {
        return database(config, databaseName)
                .runCommand(parseObject(commandJson, "命令"))
                .toJson(PRETTY_JSON);
    }

    public static void createCollection(ConnectionConfig config, String databaseName, String collectionName) {
        database(config, databaseName).createCollection(requireName(collectionName, "集合名"));
    }

    public static void renameCollection(ConnectionConfig config, String databaseName,
                                        String oldName, String newName) {
        collection(config, databaseName, oldName).renameCollection(
                new MongoNamespace(databaseName, requireName(newName, "新集合名")));
    }

    public static void dropCollection(ConnectionConfig config, String databaseName, String collectionName) {
        collection(config, databaseName, collectionName).drop();
    }

    public static List<String> getIndexes(ConnectionConfig config, String databaseName, String collectionName) {
        List<Document> indexes = new ArrayList<>();
        collection(config, databaseName, collectionName).listIndexes().into(indexes);
        indexes.sort(Comparator.comparing(index -> index.getString("name"),
                Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)));
        return indexes.stream().map(index -> index.toJson(PRETTY_JSON)).toList();
    }

    public static String createIndex(ConnectionConfig config, String databaseName, String collectionName,
                                     String keysJson, String name, boolean unique) {
        IndexOptions options = new IndexOptions().unique(unique);
        if (name != null && !name.isBlank()) options.name(name.trim());
        return collection(config, databaseName, collectionName)
                .createIndex(parseObject(keysJson, "索引键"), options);
    }

    public static void dropIndex(ConnectionConfig config, String databaseName,
                                 String collectionName, String indexName) {
        if ("_id_".equals(indexName)) throw new IllegalArgumentException("不能删除 MongoDB 的 _id_ 索引");
        collection(config, databaseName, collectionName).dropIndex(requireName(indexName, "索引名"));
    }

    public static String getValidator(ConnectionConfig config, String databaseName, String collectionName) {
        Document metadata = database(config, databaseName).listCollections()
                .filter(eq("name", collectionName)).first();
        if (metadata == null) return "{}";
        Document options = metadata.get("options", Document.class);
        if (options == null) return "{}";
        Document validator = options.get("validator", Document.class);
        return validator == null ? "{}" : validator.toJson(PRETTY_JSON);
    }

    public static void updateValidator(ConnectionConfig config, String databaseName,
                                       String collectionName, String validatorJson) {
        Document command = new Document("collMod", requireName(collectionName, "集合名"))
                .append("validator", parseObject(validatorJson, "校验规则"))
                .append("validationLevel", "strict")
                .append("validationAction", "error");
        database(config, databaseName).runCommand(command);
    }

    public static void close(ConnectionConfig config) {
        if (config == null) return;
        MongoClient client = CLIENTS.remove(config.getId());
        if (client != null) client.close();
        closeTunnel(config.getId());
        LOCKS.remove(config.getId());
    }

    static String authenticationDatabase(ConnectionConfig config) {
        return config.getDatabase() == null || config.getDatabase().isBlank()
                ? "admin" : config.getDatabase().trim();
    }

    private static MongoDatabase database(ConnectionConfig config, String databaseName) {
        return getClient(config).getDatabase(requireName(databaseName, "数据库名"));
    }

    private static MongoCollection<Document> collection(ConnectionConfig config, String databaseName,
                                                         String collectionName) {
        return database(config, databaseName).getCollection(requireName(collectionName, "集合名"));
    }

    private static Document parseObject(String json, String label) {
        String value = json == null || json.isBlank() ? "{}" : json.trim();
        try {
            return Document.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(label + "必须是有效的 JSON 对象: " + e.getMessage(), e);
        }
    }

    private static String requireName(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static synchronized int ensureTunnel(ConnectionConfig config) {
        SshTunnel existing = TUNNELS.get(config.getId());
        if (existing != null && existing.isActive()) return existing.getForwardedLocalPort();
        if (existing != null) existing.disconnect();
        SshTunnel created = SshTunnel.fromConfig(config);
        try {
            int localPort = created.connect();
            TUNNELS.put(config.getId(), created);
            return localPort;
        } catch (Exception e) {
            created.disconnect();
            throw new IllegalStateException("建立 MongoDB SSH 通道失败: " + e.getMessage(), e);
        }
    }

    private static synchronized void closeTunnel(String configId) {
        SshTunnel tunnel = TUNNELS.remove(configId);
        if (tunnel != null) tunnel.disconnect();
    }
}
