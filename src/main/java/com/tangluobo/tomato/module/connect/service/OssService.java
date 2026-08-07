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
            for (String commonPrefix : listing.getCommonPrefixes()) {
                OssObjectInfo dirInfo = new OssObjectInfo();
                dirInfo.setKey(commonPrefix);
                dirInfo.setDirectory(true);
                dirInfo.setSize(0);
                objects.add(dirInfo);
            }

            // 添加文件对象
            for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                // 跳过目录标记对象
                if (summary.getKey().endsWith("/") && summary.getSize() == 0) {
                    continue;
                }
                // 跳过与prefix相同自身的目录标记
                if (prefix != null && !prefix.isEmpty() && summary.getKey().equals(prefix)) {
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
