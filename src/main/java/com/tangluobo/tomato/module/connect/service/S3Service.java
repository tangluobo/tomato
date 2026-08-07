package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * S3存储服务：支持AWS S3、MinIO等S3兼容存储
 */
public class S3Service {

    /**
     * 创建S3客户端连接
     */
    public static S3Client createClient(ConnectionConfig config) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                config.getUsername(),
                config.getPassword()
        );

        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(config.getRegion()));

        // 设置自定义端点（MinIO等S3兼容服务）
        String endpoint = config.getEndpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                endpoint = "http://" + endpoint;
            }
            builder.endpointOverride(URI.create(endpoint));
        }

        // 路径风格访问（MinIO需要）
        if (config.isPathStyleAccess()) {
            builder.forcePathStyle(true);
        }

        return builder.build();
    }

    /**
     * 获取Bucket列表
     */
    public static List<String> listBuckets(ConnectionConfig config) throws Exception {
        S3Client client = createClient(config);
        try {
            ListBucketsResponse response = client.listBuckets();
            List<String> bucketNames = new ArrayList<>();
            for (Bucket bucket : response.buckets()) {
                bucketNames.add(bucket.name());
            }
            return bucketNames;
        } finally {
            client.close();
        }
    }

    /**
     * 获取Bucket中的对象列表
     */
    public static List<S3ObjectInfo> listObjects(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        S3Client client = createClient(config);
        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix != null ? prefix : "")
                    .delimiter("/")
                    .maxKeys(1000)
                    .build();

            ListObjectsV2Response response = client.listObjectsV2(request);

            List<S3ObjectInfo> objects = new ArrayList<>();

            // 添加子目录（CommonPrefixes）
            for (CommonPrefix commonPrefix : response.commonPrefixes()) {
                S3ObjectInfo dirInfo = new S3ObjectInfo();
                dirInfo.setKey(commonPrefix.prefix());
                dirInfo.setDirectory(true);
                dirInfo.setSize(0);
                objects.add(dirInfo);
            }

            // 添加文件对象
            for (S3Object s3Object : response.contents()) {
                // 跳过目录标记对象（key以/结尾且大小为0的通常是目录标记）
                if (s3Object.key().endsWith("/") && s3Object.size() == 0) {
                    continue;
                }
                // 跳过与prefix相同自身的目录标记
                if (prefix != null && !prefix.isEmpty() && s3Object.key().equals(prefix)) {
                    continue;
                }

                S3ObjectInfo objInfo = new S3ObjectInfo();
                objInfo.setKey(s3Object.key());
                objInfo.setDirectory(false);
                objInfo.setSize(s3Object.size());
                objInfo.setLastModified(s3Object.lastModified());
                objects.add(objInfo);
            }

            return objects;
        } finally {
            client.close();
        }
    }

    /**
     * 获取对象输入流
     */
    public static java.io.InputStream getObjectStream(ConnectionConfig config, String bucketName, String key) throws Exception {
        S3Client client = createClient(config);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        return client.getObject(request);
    }

    /**
     * 上传字符串内容到指定对象（覆盖已存在对象）
     * @param content 文本内容（UTF-8）
     */
    public static void putObject(ConnectionConfig config, String bucketName, String key, String content) throws Exception {
        S3Client client = createClient(config);
        try {
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            software.amazon.awssdk.core.sync.RequestBody body =
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("text/markdown; charset=utf-8")
                    .build();
            client.putObject(request, body);
        } finally {
            client.close();
        }
    }

    /**
     * 删除对象
     */
    public static void deleteObject(ConnectionConfig config, String bucketName, String key) throws Exception {
        S3Client client = createClient(config);
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            client.deleteObject(request);
        } finally {
            client.close();
        }
    }

    /**
     * 创建Bucket
     */
    public static void createBucket(ConnectionConfig config, String bucketName) throws Exception {
        S3Client client = createClient(config);
        try {
            CreateBucketRequest request = CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build();
            client.createBucket(request);
        } finally {
            client.close();
        }
    }

    /**
     * S3对象信息
     */
    public static class S3ObjectInfo {
        private String key;
        private boolean isDirectory;
        private long size;
        private java.time.Instant lastModified;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public boolean isDirectory() { return isDirectory; }
        public void setDirectory(boolean directory) { this.isDirectory = directory; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public java.time.Instant getLastModified() { return lastModified; }
        public void setLastModified(java.time.Instant lastModified) { this.lastModified = lastModified; }

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
