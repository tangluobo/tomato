package com.tangluobo.tomato.module.connect.service;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.gson.JsonParseException;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * S3存储服务：基于MinIO SDK，支持AWS S3、MinIO等S3兼容存储
 */
public class S3Service {

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
        String endpoint = config.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = "https://s3." + region + ".amazonaws.com";
        } else if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "http://" + endpoint;
        }

        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(config.getUsername(), config.getPassword())
                .region(region);

        return builder.build();
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
