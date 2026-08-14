package com.tangluobo.tomato.module.connect.service;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.gson.JsonParseException;
import com.tangluobo.tomato.module.connect.ConfigManager;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.SshTunnel;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.*;
import io.minio.messages.Bucket;
import io.minio.messages.Item;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3存储服务：基于MinIO SDK，支持AWS S3、MinIO等S3兼容存储
 */
public class S3Service {

    // SSH隧道缓存：configId + "_" + targetHost:targetPort -> SshTunnel
    private static final Map<String, SshTunnel> tunnelCache = new ConcurrentHashMap<>();

    /**
     * 创建MinIO客户端连接
     */
    public static MinioClient createClient(ConnectionConfig config) {
        // region 为空时回退到默认值（MinIO等S3兼容服务通常不需要真实region）
        String region = config.getRegion();
        if (region == null || region.trim().isEmpty()) {
            region = "us-east-1";
        }

        // 构造端点：优先使用自定义端点，否则按region构造AWS S3端点
        String originalEndpoint = config.getEndpoint();
        if (originalEndpoint == null || originalEndpoint.isEmpty()) {
            originalEndpoint = "https://s3." + region + ".amazonaws.com";
        } else if (!originalEndpoint.startsWith("http://") && !originalEndpoint.startsWith("https://")) {
            originalEndpoint = "http://" + originalEndpoint;
        }

        // 解析原始端点，用于判断 SSH 隧道场景下的 HTTPS 证书处理
        URI originalUri = URI.create(originalEndpoint);
        String originalScheme = originalUri.getScheme() != null ? originalUri.getScheme() : "http";

        String endpoint = originalEndpoint;
        boolean viaSshTunnel = false;

        // SSH隧道（引用方式：根据 sshTunnelHostId 查找SSH主机配置建立端口转发）
        if (config.isUseSshTunnel() && config.getSshTunnelHostId() != null) {
            try {
                endpoint = setupSshTunnel(config, originalEndpoint);
                viaSshTunnel = true;
            } catch (Exception e) {
                throw new RuntimeException("建立SSH隧道失败: " + e.getMessage(), e);
            }
        }

        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(config.getUsername(), config.getPassword())
                .region(region)
                .build();

        // SSH隧道场景下：
        // - HTTP：无需替换，MinIO 直接以 localhost:隧道端口 作为端点，签名与 Host 头一致
        // - HTTPS：需替换 OkHttpClient 跳过主机名验证（证书域名与 localhost 不匹配）
        if (viaSshTunnel && "https".equalsIgnoreCase(originalScheme)) {
            replaceOkHttpClient(client);
        }

        return client;
    }

    /**
     * 通过反射替换 MinioClient 内部的 OkHttpClient（HTTPS SSH 隧道场景）。
     * 隧道端点为 localhost:端口，与服务器证书域名不匹配，需基于现有 OkHttpClient
     * 的 newBuilder() 创建副本并跳过主机名验证。全程使用反射，避免 tomato 模块
     * 直接引用 unnamed module 上的 OkHttp 类。
     *
     * 注意：不覆盖 Host 头。MinIO 在 S3Base.executeAsync() 中对请求签名（SigV4
     * 包含 Host 头），签名发生在请求进入 OkHttp 拦截器链之前；若在拦截器中改写
     * Host 头，服务器计算的签名会与请求中的 Authorization 不匹配。隧道端点
     * localhost:port 同时用于签名和实际 Host 头，二者一致即可通过校验，
     * SSH 隧道负责将 TCP 连接转发到真实目标。
     */
    private static void replaceOkHttpClient(MinioClient client) {
        try {
            ClassLoader cl = client.getClass().getClassLoader();

            // 1. 查找 MinioClient 中的 OkHttpClient 字段（位于 S3Base.httpClient）
            FieldRef ref = findOkHttpClientField(client);
            if (ref == null) {
                throw new RuntimeException("SSH隧道: MinioClient及其嵌套对象中未找到OkHttpClient");
            }
            Object existingClient = ref.field.get(ref.owner);

            // 2. 基于现有 OkHttpClient 创建 Builder（保留超时、Dispatcher 等配置）
            Class<?> okHttpClientClass = Class.forName("okhttp3.OkHttpClient", true, cl);
            Class<?> builderClass = Class.forName("okhttp3.OkHttpClient$Builder", true, cl);
            Method newBuilderMethod = okHttpClientClass.getMethod("newBuilder");
            Object builder = newBuilderMethod.invoke(existingClient);

            // 3. 跳过主机名验证（隧道端点 localhost:port 与证书域名不匹配）
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            final X509TrustManager trustManager = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            sslContext.init(null, new TrustManager[]{trustManager}, new java.security.SecureRandom());

            Method sslMethod = builderClass.getMethod("sslSocketFactory", SSLSocketFactory.class, X509TrustManager.class);
            sslMethod.invoke(builder, sslContext.getSocketFactory(), trustManager);

            Method hvMethod = builderClass.getMethod("hostnameVerifier", HostnameVerifier.class);
            hvMethod.invoke(builder, (HostnameVerifier) (hostname, session) -> true);

            // 4. 构建新的 OkHttpClient 并替换
            Method buildMethod = builderClass.getMethod("build");
            Object newOkHttpClient = buildMethod.invoke(builder);
            ref.field.set(ref.owner, newOkHttpClient);
        } catch (Exception e) {
            throw new RuntimeException("SSH隧道: 替换OkHttpClient失败: " + e.getMessage(), e);
        }
    }

    /** OkHttpClient 字段引用：持有字段所在对象和字段本身，用于读取原值/设置新值 */
    private static final class FieldRef {
        final Object owner;
        final Field field;
        FieldRef(Object owner, Field field) { this.owner = owner; this.field = field; }
    }

    /**
     * 递归查找对象中的 OkHttpClient 字段。
     * 遍历对象所属类及其所有父类的字段（MinioClient → MinioAsyncClient → S3Base，
     * httpClient 字段声明在 S3Base 上）。
     */
    private static FieldRef findOkHttpClientField(Object obj) {
        return findOkHttpClientField(obj, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static FieldRef findOkHttpClientField(Object obj, java.util.Set<Object> visited) {
        if (obj == null || visited.contains(obj)) return null;
        visited.add(obj);

        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;

                    String valClassName = val.getClass().getName();
                    if (valClassName.startsWith("okhttp3.OkHttpClient")) {
                        return new FieldRef(obj, f);
                    }
                    // 递归进入 MinIO / OkHttp 嵌套对象继续查找
                    if (valClassName.startsWith("io.minio.") || valClassName.startsWith("okhttp3.")) {
                        FieldRef found = findOkHttpClientField(val, visited);
                        if (found != null) return found;
                    }
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * 建立/复用 SSH 隧道，返回指向本地转发端口的 endpoint。
     * 隧道通过引用的 SSH 主机（sshTunnelHostId）建立，目标为 S3 endpoint 解析出的 host:port。
     */
    private static String setupSshTunnel(ConnectionConfig config, String originalEndpoint) throws Exception {
        URI uri = URI.create(originalEndpoint);
        String targetHost = uri.getHost();
        int targetPort = uri.getPort();
        if (targetPort < 0) {
            targetPort = "https".equals(uri.getScheme()) ? 443 : 80;
        }
        String scheme = uri.getScheme() != null ? uri.getScheme() : "http";

        String tunnelKey = config.getId() + "_" + targetHost + ":" + targetPort;
        SshTunnel tunnel = tunnelCache.get(tunnelKey);
        if (tunnel != null && tunnel.isActive()) {
            return scheme + "://localhost:" + tunnel.getForwardedLocalPort();
        }

        // 查找引用的 SSH 主机配置
        ConnectionConfig sshHost = findSshHostConfig(config.getSshTunnelHostId());
        if (sshHost == null) {
            throw new RuntimeException("找不到引用的SSH主机配置(ID: " + config.getSshTunnelHostId() + ")");
        }

        // 用 SSH 主机的认证信息建立隧道，目标为 S3 endpoint 的 host:port
        List<String> keyPaths = sshHost.isUseKey() ? sshHost.getPrivateKeyPaths() : null;
        String password = sshHost.isUsePassword() ? sshHost.getPassword() : null;
        if (!sshHost.isUsePassword() && sshHost.isUseKey() && sshHost.getPassword() != null) {
            password = sshHost.getPassword();
        }

        tunnel = new SshTunnel(
            sshHost.getHost(),
            sshHost.getPort(),
            sshHost.getUsername(),
            password,
            keyPaths,
            targetHost,
            targetPort
        );
        int localPort = tunnel.connect();
        tunnelCache.put(tunnelKey, tunnel);

        return scheme + "://localhost:" + localPort;
    }

    /**
     * 根据 sshTunnelHostId 查找引用的 SSH 主机配置
     */
    private static ConnectionConfig findSshHostConfig(String hostId) {
        if (hostId == null) return null;
        try {
            List<ConnectionConfig> all = ConfigManager.loadConnections();
            for (ConnectionConfig c : all) {
                if (hostId.equals(c.getId())) return c;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 关闭指定连接的所有 SSH 隧道（关闭S3标签页时调用）
     */
    public static void closeSshTunnel(String configId) {
        tunnelCache.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(configId + "_")) {
                entry.getValue().disconnect();
                return true;
            }
            return false;
        });
    }

    /**
     * 获取Bucket列表
     */
    public static List<String> listBuckets(ConnectionConfig config) throws Exception {
        MinioClient client = createClient(config);
        List<String> bucketNames = new ArrayList<>();
        for (Bucket bucket : client.listBuckets()) {
            bucketNames.add(bucket.name());
        }
        return bucketNames;
    }

    /**
     * 获取Bucket中的对象列表
     */
    public static List<S3ObjectInfo> listObjects(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        MinioClient client = createClient(config);
        try {
            ListObjectsArgs.Builder argsBuilder = ListObjectsArgs.builder()
                    .bucket(bucketName)
                    .recursive(false);
            if (prefix != null && !prefix.isEmpty()) {
                argsBuilder.prefix(prefix);
            }

            List<S3ObjectInfo> objects = new ArrayList<>();
            Iterable<Result<Item>> results = client.listObjects(argsBuilder.build());

            for (Result<Item> result : results) {
                try {
                    Item item = result.get();
                    S3ObjectInfo objInfo = new S3ObjectInfo();
                    objInfo.setKey(item.objectName());
                    // S3 没有"真正的目录"，目录是通过 key 中的 / 隐含的
                    // 以 / 结尾的对象（目录占位对象）也视为目录，否则空目录会被显示为文件
                    boolean isDir = item.isDir() || item.objectName().endsWith("/");
                    objInfo.setDirectory(isDir);
                    objInfo.setSize(isDir ? 0 : item.size());
                    if (item.lastModified() != null) {
                        objInfo.setLastModified(item.lastModified().toInstant());
                    }
                    objects.add(objInfo);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            return objects;
        } finally {
            // MinioClient 无需显式关闭
        }
    }

    /**
     * 获取对象输入流
     */
    public static InputStream getObjectStream(ConnectionConfig config, String bucketName, String key) throws Exception {
        MinioClient client = createClient(config);
        GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(key)
                .build());
        return response;
    }

    /**
     * 上传字符串内容到指定对象（覆盖已存在对象）
     * @param content 文本内容（UTF-8）
     */
    public static void putObject(ConnectionConfig config, String bucketName, String key, String content) throws Exception {
        MinioClient client = createClient(config);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        client.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(key)
                .stream(bis, bytes.length+0L, -1L)
                .contentType("text/markdown; charset=utf-8")
                .build());
    }

    /**
     * 上传文件流到指定对象（覆盖已存在对象）
     * @param stream 输入流
     * @param size 文件大小（字节），未知可传 -1
     * @param contentType MIME 类型，为空则使用 application/octet-stream
     */
    public static void uploadFile(ConnectionConfig config, String bucketName, String key,
                                  InputStream stream, long size, String contentType) throws Exception {
        MinioClient client = createClient(config);
        if (contentType == null || contentType.isEmpty()) {
            contentType = "application/octet-stream";
        }
        long partSize = size > 0 ? size : -1L;
        client.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(key)
                .stream(stream, partSize, -1L)
                .contentType(contentType)
                .build());
    }

    /**
     * 创建目录（S3 中通过创建以 / 结尾的空对象实现）
     * @param prefix 目录前缀，无需以 / 结尾（方法内部会补全）
     */
    public static void createDirectory(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        MinioClient client = createClient(config);
        if (prefix == null) prefix = "";
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        ByteArrayInputStream empty = new ByteArrayInputStream(new byte[0]);
        client.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(prefix)
                .stream(empty, 0L, -1L)
                .contentType("application/x-directory")
                .build());
    }

    /**
     * 删除对象
     */
    public static void deleteObject(ConnectionConfig config, String bucketName, String key) throws Exception {
        MinioClient client = createClient(config);
        client.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(key)
                .build());
    }

    /**
     * 服务端复制对象（用于 S3 重命名/移动）。同 bucket 内复制。
     */
    public static void copyObject(ConnectionConfig config, String bucketName, String sourceKey, String destKey) throws Exception {
        MinioClient client = createClient(config);
        client.copyObject(CopyObjectArgs.builder()
                .bucket(bucketName)
                .object(destKey)
                .source(CopySource.builder().bucket(bucketName).object(sourceKey).build())
                .build());
    }

    /**
     * 递归列出 prefix 下所有对象（用于目录删除/重命名/移动）。
     */
    public static List<S3ObjectInfo> listObjectsRecursive(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        MinioClient client = createClient(config);
        ListObjectsArgs.Builder argsBuilder = ListObjectsArgs.builder()
                .bucket(bucketName)
                .recursive(true);
        if (prefix != null && !prefix.isEmpty()) {
            argsBuilder.prefix(prefix);
        }
        List<S3ObjectInfo> objects = new ArrayList<>();
        Iterable<Result<Item>> results = client.listObjects(argsBuilder.build());
        for (Result<Item> result : results) {
            try {
                Item item = result.get();
                S3ObjectInfo objInfo = new S3ObjectInfo();
                objInfo.setKey(item.objectName());
                objInfo.setDirectory(item.isDir());
                objInfo.setSize(item.isDir() ? 0 : item.size());
                if (item.lastModified() != null) {
                    objInfo.setLastModified(item.lastModified().toInstant());
                }
                objects.add(objInfo);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return objects;
    }

    /**
     * 创建Bucket
     */
    public static void createBucket(ConnectionConfig config, String bucketName) throws Exception {
        MinioClient client = createClient(config);
        client.makeBucket(MakeBucketArgs.builder()
                .bucket(bucketName)
                .build());
    }

    /**
     * 删除Bucket（Bucket必须为空）
     */
    public static void deleteBucket(ConnectionConfig config, String bucketName) throws Exception {
        MinioClient client = createClient(config);
        client.removeBucket(RemoveBucketArgs.builder()
                .bucket(bucketName)
                .build());
    }

    /**
     * S3对象信息
     */
    public static class S3ObjectInfo {
        private String key;
        private boolean isDirectory;
        private long size;
        private Instant lastModified;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public boolean isDirectory() { return isDirectory; }
        public void setDirectory(boolean directory) { this.isDirectory = directory; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public Instant getLastModified() { return lastModified; }
        public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

        /**
         * 获取显示名称（去掉前缀路径的最后一部分）
         */
        public String getDisplayName() {
            if (key == null) return "";
            String displayKey = key;
            // 去掉末尾的/
            if (displayKey.endsWith("/")) {
                displayKey = displayKey.substring(0, displayKey.length() - 1);
            }
            // 取最后一个/后面的部分
            int lastSlash = displayKey.lastIndexOf('/');
            if (lastSlash >= 0) {
                return displayKey.substring(lastSlash + 1);
            }
            return displayKey;
        }

        /**
         * 获取子路径前缀（用于进入子目录时）
         */
        public String getPrefix() {
            return key;
        }

        /**
         * 格式化文件大小
         */
        public String getFormattedSize() {
            if (isDirectory) return "";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return (size / 1024) + " KB";
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }
    }
}
