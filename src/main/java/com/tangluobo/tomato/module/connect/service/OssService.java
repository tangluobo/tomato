package com.tangluobo.tomato.module.connect.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.tangluobo.tomato.module.connect.ConnectionConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云OSS存储服务
 */
public class OssService {

    /**
     * 创建OSS客户端连接
     */
    public static OSS createClient(ConnectionConfig config) {
        String endpoint = config.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            // 自动拼接阿里云OSS端点
            endpoint = "https://oss-" + config.getRegion() + ".aliyuncs.com";
        }
        // 如果端点不含协议前缀，自动添加
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "https://" + endpoint;
        }

        return new OSSClientBuilder().build(endpoint, config.getUsername(), config.getPassword());
    }

    /**
     * 获取Bucket列表
     */
    public static List<String> listBuckets(ConnectionConfig config) throws Exception {
        OSS client = createClient(config);
        try {
            List<com.aliyun.oss.model.Bucket> buckets = client.listBuckets();
            List<String> bucketNames = new ArrayList<>();
            for (com.aliyun.oss.model.Bucket bucket : buckets) {
                bucketNames.add(bucket.getName());
            }
            return bucketNames;
        } finally {
            client.shutdown();
        }
    }

    /**
     * 获取Bucket中的对象列表
     */
    public static List<OssObjectInfo> listObjects(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        OSS client = createClient(config);
        try {
            ObjectListing listing;
            if (prefix != null && !prefix.isEmpty()) {
                listing = client.listObjects(bucketName, prefix);
            } else {
                listing = client.listObjects(bucketName);
            }

            List<OssObjectInfo> objects = new ArrayList<>();

            // 添加子目录（CommonPrefixes）
            // 记录已通过 CommonPrefix 显示的目录，避免与 ObjectSummaries 中的目录标记对象重复
            java.util.Set<String> commonPrefixSet = new java.util.HashSet<>(listing.getCommonPrefixes());
            for (String commonPrefix : commonPrefixSet) {
                OssObjectInfo dirInfo = new OssObjectInfo();
                dirInfo.setKey(commonPrefix);
                dirInfo.setDirectory(true);
                dirInfo.setSize(0);
                objects.add(dirInfo);
            }

            // 添加文件对象
            for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                // 跳过与prefix相同自身的目录标记（即当前所在目录本身）
                if (prefix != null && !prefix.isEmpty() && summary.getKey().equals(prefix)) {
                    continue;
                }

                // 以 / 结尾的对象是目录占位对象
                if (summary.getKey().endsWith("/")) {
                    // 如果已通过 CommonPrefix 显示，跳过避免重复
                    if (commonPrefixSet.contains(summary.getKey())) {
                        continue;
                    }
                    // 空目录的情况：CommonPrefix 不会返回，但 ObjectSummaries 会包含这个 0 字节占位对象
                    OssObjectInfo dirInfo = new OssObjectInfo();
                    dirInfo.setKey(summary.getKey());
                    dirInfo.setDirectory(true);
                    dirInfo.setSize(0);
                    dirInfo.setLastModified(summary.getLastModified());
                    objects.add(dirInfo);
                    continue;
                }

                OssObjectInfo objInfo = new OssObjectInfo();
                objInfo.setKey(summary.getKey());
                objInfo.setDirectory(false);
                objInfo.setSize(summary.getSize());
                objInfo.setLastModified(summary.getLastModified());
                objects.add(objInfo);
            }

            return objects;
        } finally {
            client.shutdown();
        }
    }

    /**
     * 获取对象输入流
     */
    public static java.io.InputStream getObjectStream(ConnectionConfig config, String bucketName, String key) throws Exception {
        OSS client = createClient(config);
        com.aliyun.oss.model.OSSObject ossObject = client.getObject(bucketName, key);
        // 返回流后调用者负责关闭，但OSS客户端在流关闭时会自动shutdown
        return ossObject.getObjectContent();
    }

    /**
     * 上传字符串内容到指定对象（覆盖已存在对象）
     * @param content 文本内容（UTF-8）
     */
    public static void putObject(ConnectionConfig config, String bucketName, String key, String content) throws Exception {
        OSS client = createClient(config);
        try {
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.aliyun.oss.model.ObjectMetadata metadata = new com.aliyun.oss.model.ObjectMetadata();
            metadata.setContentLength(bytes.length);
            metadata.setContentType("text/markdown; charset=utf-8");
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes);
            client.putObject(bucketName, key, bis, metadata);
        } finally {
            client.shutdown();
        }
    }

    /**
     * 上传文件流到指定对象（覆盖已存在对象）
     * @param stream 输入流
     * @param size 文件大小（字节），未知可传 -1（OSS 需要在 metadata 中设置 ContentLength，未知时使用 chunked 上传）
     * @param contentType MIME 类型，为空则使用 application/octet-stream
     */
    public static void uploadFile(ConnectionConfig config, String bucketName, String key,
                                  java.io.InputStream stream, long size, String contentType) throws Exception {
        OSS client = createClient(config);
        try {
            if (contentType == null || contentType.isEmpty()) {
                contentType = "application/octet-stream";
            }
            com.aliyun.oss.model.ObjectMetadata metadata = new com.aliyun.oss.model.ObjectMetadata();
            if (size > 0) {
                metadata.setContentLength(size);
            }
            // 未指定 ContentLength 时，OSS Java SDK 会自动使用 chunked 传输编码
            metadata.setContentType(contentType);
            client.putObject(bucketName, key, stream, metadata);
        } finally {
            client.shutdown();
        }
    }

    /**
     * 创建目录（OSS 中通过创建以 / 结尾的空对象实现）
     * @param prefix 目录前缀，无需以 / 结尾（方法内部会补全）
     */
    public static void createDirectory(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        OSS client = createClient(config);
        try {
            if (prefix == null) prefix = "";
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
            com.aliyun.oss.model.ObjectMetadata metadata = new com.aliyun.oss.model.ObjectMetadata();
            metadata.setContentLength(0);
            metadata.setContentType("application/x-directory");
            java.io.ByteArrayInputStream empty = new java.io.ByteArrayInputStream(new byte[0]);
            client.putObject(bucketName, prefix, empty, metadata);
        } finally {
            client.shutdown();
        }
    }

    /**
     * 删除对象
     */
    public static void deleteObject(ConnectionConfig config, String bucketName, String key) throws Exception {
        OSS client = createClient(config);
        try {
            client.deleteObject(bucketName, key);
        } finally {
            client.shutdown();
        }
    }

    /**
     * 服务端复制对象（用于 OSS 重命名/移动）。同 bucket 内复制。
     */
    public static void copyObject(ConnectionConfig config, String bucketName, String sourceKey, String destKey) throws Exception {
        OSS client = createClient(config);
        try {
            client.copyObject(bucketName, sourceKey, bucketName, destKey);
        } finally {
            client.shutdown();
        }
    }

    /**
     * 递归列出 prefix 下所有对象（用于目录删除/重命名/移动）。
     */
    public static List<OssObjectInfo> listObjectsRecursive(ConnectionConfig config, String bucketName, String prefix) throws Exception {
        OSS client = createClient(config);
        try {
            List<OssObjectInfo> objects = new ArrayList<>();
            String nextMarker = null;
            do {
                com.aliyun.oss.model.ListObjectsRequest request = new com.aliyun.oss.model.ListObjectsRequest(bucketName);
                if (prefix != null && !prefix.isEmpty()) {
                    request.setPrefix(prefix);
                }
                request.setMaxKeys(1000);
                if (nextMarker != null) {
                    request.setMarker(nextMarker);
                }
                ObjectListing listing = client.listObjects(request);
                for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                    OssObjectInfo objInfo = new OssObjectInfo();
                    objInfo.setKey(summary.getKey());
                    objInfo.setDirectory(summary.getKey().endsWith("/"));
                    objInfo.setSize(summary.getSize());
                    objInfo.setLastModified(summary.getLastModified());
                    objects.add(objInfo);
                }
                nextMarker = listing.isTruncated() ? listing.getNextMarker() : null;
            } while (nextMarker != null && !nextMarker.isEmpty());
            return objects;
        } finally {
            client.shutdown();
        }
    }

    /**
     * 重命名对象（文件或目录）
     * OSS 不支持直接重命名，通过复制+删除实现
     * @param oldKey 原始 key（文件完整 key 或目录 prefix，目录需以 / 结尾）
     * @param newKey 新 key（文件完整 key 或目录 prefix，目录需以 / 结尾）
     * @param isDirectory 是否为目录
     */
    public static void renameObject(ConnectionConfig config, String bucketName, String oldKey, String newKey, boolean isDirectory) throws Exception {
        if (isDirectory) {
            // 目录重命名：递归复制所有对象到新 prefix，然后删除旧对象
            List<OssObjectInfo> objects = listObjectsRecursive(config, bucketName, oldKey);
            for (OssObjectInfo obj : objects) {
                String objKey = obj.getKey();
                String newObjKey = newKey + objKey.substring(oldKey.length());
                copyObject(config, bucketName, objKey, newObjKey);
            }
            // 删除旧目录下所有对象
            for (OssObjectInfo obj : objects) {
                deleteObject(config, bucketName, obj.getKey());
            }
        } else {
            // 文件重命名：复制后删除
            copyObject(config, bucketName, oldKey, newKey);
            deleteObject(config, bucketName, oldKey);
        }
    }

    /**
     * 创建Bucket
     */
    public static void createBucket(ConnectionConfig config, String bucketName) throws Exception {
        OSS client = createClient(config);
        try {
            client.createBucket(bucketName);
        } finally {
            client.shutdown();
        }
    }

    /**
     * OSS对象信息
     */
    public static class OssObjectInfo {
        private String key;
        private boolean isDirectory;
        private long size;
        private java.util.Date lastModified;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public boolean isDirectory() { return isDirectory; }
        public void setDirectory(boolean directory) { this.isDirectory = directory; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public java.util.Date getLastModified() { return lastModified; }
        public void setLastModified(java.util.Date lastModified) { this.lastModified = lastModified; }

        /**
         * 获取显示名称
         */
        public String getDisplayName() {
            if (key == null) return "";
            String displayKey = key;
            if (displayKey.endsWith("/")) {
                displayKey = displayKey.substring(0, displayKey.length() - 1);
            }
            int lastSlash = displayKey.lastIndexOf('/');
            if (lastSlash >= 0) {
                return displayKey.substring(lastSlash + 1);
            }
            return displayKey;
        }

        /**
         * 获取子路径前缀
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
