package com.tangluobo.tomato.module.connect;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.QueryResult;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.apache.rocketmq.remoting.protocol.admin.TopicOffset;
import org.apache.rocketmq.remoting.protocol.admin.TopicStatsTable;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.body.ConsumerConnection;
import org.apache.rocketmq.remoting.protocol.body.Connection;
import org.apache.rocketmq.remoting.protocol.body.KVTable;
import org.apache.rocketmq.remoting.protocol.body.SubscriptionGroupWrapper;
import org.apache.rocketmq.remoting.protocol.body.TopicList;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.apache.rocketmq.remoting.protocol.route.TopicRouteData;
import org.apache.rocketmq.remoting.protocol.route.QueueData;
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.apache.rocketmq.common.message.MessageQueue;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RocketmqService {
    // 缓存MQAdminExt实例，key为nameServer地址
    private static final Map<String, DefaultMQAdminExt> adminCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建MQAdminExt实例
     */
    public static DefaultMQAdminExt getAdmin(ConnectionConfig config) throws MQClientException {
        String nameServer = config.getHost() + ":" + config.getPort();
        return adminCache.computeIfAbsent(nameServer, ns -> {
            DefaultMQAdminExt admin = new DefaultMQAdminExt();
            admin.setNamesrvAddr(ns);
            admin.setAdminExtGroup("tomato_admin_group");
            try {
                admin.start();
            } catch (MQClientException e) {
                throw new RuntimeException("启动MQAdminExt失败: " + e.getMessage(), e);
            }
            return admin;
        });
    }

    /**
     * 测试NameServer连接
     */
    public static boolean testConnection(ConnectionConfig config) {
        DefaultMQAdminExt admin = new DefaultMQAdminExt();
        try {
            admin.setNamesrvAddr(config.getHost() + ":" + config.getPort());
            admin.setAdminExtGroup("tomato_admin_test_" + System.currentTimeMillis());
            admin.start();
            // 尝试获取集群信息来验证连接
            admin.examineBrokerClusterInfo();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { admin.shutdown(); } catch (Exception ignored) {}
        }
    }

    /**
     * 关闭指定配置的Admin连接
     */
    public static void closeAdmin(ConnectionConfig config) {
        String nameServer = config.getHost() + ":" + config.getPort();
        DefaultMQAdminExt admin = adminCache.remove(nameServer);
        if (admin != null) {
            try { admin.shutdown(); } catch (Exception ignored) {}
        }
    }

    /**
     * 关闭所有Admin连接
     */
    public static void closeAllAdmins() {
        for (Map.Entry<String, DefaultMQAdminExt> entry : adminCache.entrySet()) {
            try { entry.getValue().shutdown(); } catch (Exception ignored) {}
        }
        adminCache.clear();
    }

    // ==================== Topic管理 ====================

    /**
     * 获取所有Topic列表
     */
    public static List<Map<String, Object>> getTopicList(ConnectionConfig config) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        TopicList topicList = admin.fetchAllTopicList();
        List<Map<String, Object>> result = new ArrayList<>();
        if (topicList != null && topicList.getTopicList() != null) {
            for (String topic : topicList.getTopicList()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("topic", topic);
                item.put("topicType", topic.startsWith("%") ? "SYSTEM" : "NORMAL");
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 获取Topic统计信息
     */
    public static Map<String, Object> getTopicStats(ConnectionConfig config, String topic) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        TopicStatsTable statsTable = admin.examineTopicStats(topic);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> offsetList = new ArrayList<>();
        if (statsTable != null && statsTable.getOffsetTable() != null) {
            for (Map.Entry<MessageQueue, TopicOffset> entry : statsTable.getOffsetTable().entrySet()) {
                Map<String, Object> offsetInfo = new LinkedHashMap<>();
                offsetInfo.put("brokerName", entry.getKey().getBrokerName());
                offsetInfo.put("queueId", entry.getKey().getQueueId());
                offsetInfo.put("minOffset", entry.getValue().getMinOffset());
                offsetInfo.put("maxOffset", entry.getValue().getMaxOffset());
                offsetInfo.put("lastUpdateTimestamp", entry.getValue().getLastUpdateTimestamp());
                offsetList.add(offsetInfo);
            }
        }
        result.put("offsetTable", offsetList);
        return result;
    }

    /**
     * 获取Topic路由信息
     */
    public static Map<String, Object> getTopicRoute(ConnectionConfig config, String topic) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        TopicRouteData routeData = admin.examineTopicRouteInfo(topic);
        Map<String, Object> result = new LinkedHashMap<>();
        if (routeData != null) {
            List<Map<String, Object>> brokerList = new ArrayList<>();
            if (routeData.getBrokerDatas() != null) {
                for (BrokerData broker : routeData.getBrokerDatas()) {
                    Map<String, Object> brokerInfo = new LinkedHashMap<>();
                    brokerInfo.put("brokerName", broker.getBrokerName());
                    brokerInfo.put("brokerAddrs", broker.getBrokerAddrs());
                    brokerList.add(brokerInfo);
                }
            }
            result.put("brokers", brokerList);
            List<Map<String, Object>> queueList = new ArrayList<>();
            if (routeData.getQueueDatas() != null) {
                for (QueueData qd : routeData.getQueueDatas()) {
                    Map<String, Object> queueInfo = new LinkedHashMap<>();
                    queueInfo.put("brokerName", qd.getBrokerName());
                    queueInfo.put("readQueueNums", qd.getReadQueueNums());
                    queueInfo.put("writeQueueNums", qd.getWriteQueueNums());
                    queueInfo.put("perm", qd.getPerm());
                    queueList.add(queueInfo);
                }
            }
            result.put("queues", queueList);
        }
        return result;
    }

    /**
     * 创建Topic
     */
    public static void createTopic(ConnectionConfig config, String topic, int queueNum) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            for (Map.Entry<String, BrokerData> entry : clusterInfo.getBrokerAddrTable().entrySet()) {
                String brokerName = entry.getKey();
                BrokerData brokerData = entry.getValue();
                if (brokerData.getBrokerAddrs() != null) {
                    for (Map.Entry<Long, String> addrEntry : brokerData.getBrokerAddrs().entrySet()) {
                        if (addrEntry.getKey() == 0L) {
                            admin.createTopic(addrEntry.getValue(), topic, queueNum, new HashMap<>());
                        }
                    }
                }
            }
        }
    }

    /**
     * 删除Topic
     */
    public static void deleteTopic(ConnectionConfig config, String topic) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            Set<String> masterAddrs = new HashSet<>();
            for (BrokerData brokerData : clusterInfo.getBrokerAddrTable().values()) {
                String masterAddr = brokerData.getBrokerAddrs().get(0L);
                if (masterAddr != null) {
                    masterAddrs.add(masterAddr);
                }
            }
            admin.deleteTopicInBroker(masterAddrs, topic);
        }
    }

    // ==================== 消息查询 ====================

    /**
     * 按Message ID查询消息
     */
    public static Map<String, Object> queryMessageById(ConnectionConfig config, String topic, String msgId) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        MessageExt msg = admin.viewMessage(topic, msgId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (msg != null) {
            result.put("msgId", msg.getMsgId());
            result.put("keys", msg.getKeys());
            result.put("tags", msg.getTags());
            result.put("topic", msg.getTopic());
            result.put("queueId", msg.getQueueId());
            result.put("queueOffset", msg.getQueueOffset());
            result.put("storeSize", msg.getStoreSize());
            result.put("bornTimestamp", msg.getBornTimestamp());
            result.put("storeTimestamp", msg.getStoreTimestamp());
            result.put("bornHost", String.valueOf(msg.getBornHost()));
            result.put("storeHost", String.valueOf(msg.getStoreHost()));
            result.put("body", new String(msg.getBody(), "UTF-8"));
            result.put("reconsumeTimes", msg.getReconsumeTimes());
        }
        return result;
    }

    /**
     * 按Key查询消息 - 使用queryMessage按key+最近3天时间范围查询
     */
    public static List<Map<String, Object>> queryMessageByKey(ConnectionConfig config, String topic, String key) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        long end = System.currentTimeMillis();
        long begin = end - 3 * 24 * 3600 * 1000L;
        QueryResult queryResult = admin.queryMessage(null, topic, key, 64, begin, end);
        return convertMessages(queryResult != null ? queryResult.getMessageList() : null);
    }

    /**
     * 按时间范围查询消息 - 直接用PullConsumer遍历Queue拉取
     * queryMessage不支持空key查询，所以直接用Pull方式
     */
    public static List<Map<String, Object>> queryMessageByTime(ConnectionConfig config, String topic, long begin, long end) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        tryPullMessages(config, topic, begin, end, result);
        return result;
    }

    /**
     * 用PullConsumer遍历Queue拉取指定时间范围内的消息
     */
    private static void tryPullMessages(ConnectionConfig config, String topic, long begin, long end, List<Map<String, Object>> result) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        TopicStatsTable statsTable = admin.examineTopicStats(topic);
        if (statsTable == null || statsTable.getOffsetTable() == null) return;

        String consumerGroup = "tomato_query_" + System.currentTimeMillis();
        DefaultMQPullConsumer pullConsumer = new DefaultMQPullConsumer(consumerGroup);
        pullConsumer.setNamesrvAddr(config.getHost() + ":" + config.getPort());
        pullConsumer.start();

        try {
            for (Map.Entry<MessageQueue, TopicOffset> entry : statsTable.getOffsetTable().entrySet()) {
                MessageQueue mq = entry.getKey();
                long minOffset = entry.getValue().getMinOffset();
                long maxOffset = entry.getValue().getMaxOffset();
                if (minOffset >= maxOffset) continue;

                long offset = minOffset;
                int pullBatch = 32;
                boolean foundInRange = false;

                while (offset < maxOffset && result.size() < 256) {
                    PullResult pullResult = pullConsumer.pull(mq, "*", offset, pullBatch);
                    if (pullResult == null) break;

                    if (pullResult.getPullStatus() == PullStatus.FOUND) {
                        for (MessageExt msg : pullResult.getMsgFoundList()) {
                            if (msg.getStoreTimestamp() >= begin && msg.getStoreTimestamp() <= end) {
                                foundInRange = true;
                                result.add(convertMessage(msg));
                            } else if (foundInRange && msg.getStoreTimestamp() > end) {
                                return;
                            }
                        }
                        offset = pullResult.getNextBeginOffset();
                    } else if (pullResult.getPullStatus() == PullStatus.NO_NEW_MSG
                            || pullResult.getPullStatus() == PullStatus.OFFSET_ILLEGAL) {
                        break;
                    } else {
                        offset = pullResult.getNextBeginOffset();
                    }
                }
            }
        } finally {
            pullConsumer.shutdown();
        }
    }

    private static Map<String, Object> convertMessage(MessageExt msg, boolean includeBody) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("msgId", msg.getMsgId());
        item.put("keys", msg.getKeys());
        item.put("tags", msg.getTags());
        item.put("topic", msg.getTopic());
        item.put("queueId", msg.getQueueId());
        item.put("queueOffset", msg.getQueueOffset());
        item.put("storeTimestamp", msg.getStoreTimestamp());
        item.put("bornTimestamp", msg.getBornTimestamp());
        item.put("bornHost", String.valueOf(msg.getBornHost()));
        item.put("storeHost", String.valueOf(msg.getStoreHost()));
        item.put("storeSize", msg.getStoreSize());
        item.put("reconsumeTimes", msg.getReconsumeTimes());
        if (includeBody) {
            try {
                item.put("body", new String(msg.getBody(), "UTF-8"));
            } catch (Exception e) {
                item.put("body", "[无法解码]");
            }
        }
        return item;
    }

    private static Map<String, Object> convertMessage(MessageExt msg) {
        return convertMessage(msg, true);
    }

    private static List<Map<String, Object>> convertMessages(List<MessageExt> msgs) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (msgs != null) {
            for (MessageExt msg : msgs) {
                result.add(convertMessage(msg));
            }
        }
        return result;
    }

    // ==================== 消费者组管理 ====================

    /**
     * 获取消费者组列表
     */
    public static List<Map<String, Object>> getConsumerGroupList(ConnectionConfig config) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        // 使用getAllSubscriptionGroup获取所有消费者组
        SubscriptionGroupWrapper wrapper = admin.getAllSubscriptionGroup(config.getHost() + ":" + config.getPort(), 3000L);
        List<Map<String, Object>> result = new ArrayList<>();
        if (wrapper != null && wrapper.getSubscriptionGroupTable() != null) {
            for (Map.Entry<String, SubscriptionGroupConfig> entry : wrapper.getSubscriptionGroupTable().entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("group", entry.getKey());
                try {
                    ConsumeStats stats = admin.examineConsumeStats(entry.getKey());
                    if (stats != null) {
                        item.put("consumeTps", String.format("%.2f", stats.getConsumeTps()));
                        item.put("diffTotal", stats.computeTotalDiff());
                    }
                } catch (Exception ignored) {
                    item.put("consumeTps", "0");
                    item.put("diffTotal", 0);
                }
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 获取消费者组详情（消费偏移信息）
     */
    public static Map<String, Object> getConsumerGroupDetail(ConnectionConfig config, String group) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ConsumeStats stats = admin.examineConsumeStats(group);
        Map<String, Object> result = new LinkedHashMap<>();
        if (stats != null) {
            result.put("consumeTps", stats.getConsumeTps());
            result.put("totalDiff", stats.computeTotalDiff());
            List<Map<String, Object>> offsetList = new ArrayList<>();
            if (stats.getOffsetTable() != null) {
                for (Map.Entry<MessageQueue, OffsetWrapper> entry : stats.getOffsetTable().entrySet()) {
                    Map<String, Object> offsetInfo = new LinkedHashMap<>();
                    offsetInfo.put("topic", entry.getKey().getTopic());
                    offsetInfo.put("brokerName", entry.getKey().getBrokerName());
                    offsetInfo.put("queueId", entry.getKey().getQueueId());
                    offsetInfo.put("brokerOffset", entry.getValue().getBrokerOffset());
                    offsetInfo.put("consumerOffset", entry.getValue().getConsumerOffset());
                    long diff = entry.getValue().getBrokerOffset() - entry.getValue().getConsumerOffset();
                    offsetInfo.put("diff", diff);
                    offsetList.add(offsetInfo);
                }
            }
            result.put("offsetTable", offsetList);
        }
        return result;
    }

    /**
     * 获取消费者组下的客户端连接
     */
    public static List<Map<String, Object>> getConsumerConnection(ConnectionConfig config, String group) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ConsumerConnection conn = admin.examineConsumerConnectionInfo(group);
        List<Map<String, Object>> result = new ArrayList<>();
        if (conn != null && conn.getConnectionSet() != null) {
            for (Connection connection : conn.getConnectionSet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("clientId", connection.getClientId());
                item.put("clientAddr", String.valueOf(connection.getClientAddr()));
                item.put("language", String.valueOf(connection.getLanguage()));
                item.put("version", connection.getVersion());
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 删除消费者组
     */
    public static void deleteConsumerGroup(ConnectionConfig config, String group) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            for (BrokerData brokerData : clusterInfo.getBrokerAddrTable().values()) {
                String masterAddr = brokerData.getBrokerAddrs().get(0L);
                if (masterAddr != null) {
                    admin.deleteSubscriptionGroup(masterAddr, group);
                }
            }
        }
    }

    // ==================== 集群信息 ====================

    /**
     * 获取集群信息
     */
    public static List<Map<String, Object>> getClusterInfo(ConnectionConfig config) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        List<Map<String, Object>> result = new ArrayList<>();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            for (Map.Entry<String, BrokerData> entry : clusterInfo.getBrokerAddrTable().entrySet()) {
                String brokerName = entry.getKey();
                BrokerData brokerData = entry.getValue();
                if (brokerData.getBrokerAddrs() != null) {
                    for (Map.Entry<Long, String> addrEntry : brokerData.getBrokerAddrs().entrySet()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("brokerName", brokerName);
                        item.put("brokerId", addrEntry.getKey());
                        item.put("address", addrEntry.getValue());
                        item.put("role", addrEntry.getKey() == 0L ? "MASTER" : "SLAVE");
                        // 获取Broker版本信息
                        try {
                            KVTable kvTable = admin.fetchBrokerRuntimeStats(addrEntry.getValue());
                            if (kvTable != null && kvTable.getTable() != null) {
                                String version = kvTable.getTable().get("brokerVersionDesc");
                                if (version != null) {
                                    item.put("version", version);
                                }
                            }
                        } catch (Exception ignored) {}
                        result.add(item);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 获取Broker运行时信息
     */
    public static Map<String, Object> getBrokerInfo(ConnectionConfig config, String brokerAddr) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        KVTable kvTable = admin.fetchBrokerRuntimeStats(brokerAddr);
        Map<String, Object> result = new LinkedHashMap<>();
        if (kvTable != null && kvTable.getTable() != null) {
            for (Map.Entry<String, String> e : kvTable.getTable().entrySet()) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }
}
