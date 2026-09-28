package com.tangluobo.tomato.module.connect.service;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocketmqServiceTest {

    @Test
    void createTopicWritesTopicConfigToEveryMasterBroker() throws Exception {
        RecordingAdmin admin = new RecordingAdmin(clusterInfo(
                broker("broker-a", "10.0.0.1:10911", "10.0.0.1:10912"),
                broker("broker-b", "10.0.0.2:10911", null)));

        RocketmqService.createTopic(admin, "orders", 6);

        assertEquals(List.of("10.0.0.1:10911", "10.0.0.2:10911"), admin.brokerAddresses);
        assertEquals(2, admin.topicConfigs.size());
        for (TopicConfig config : admin.topicConfigs) {
            assertEquals("orders", config.getTopicName());
            assertEquals(6, config.getReadQueueNums());
            assertEquals(6, config.getWriteQueueNums());
        }
    }

    @Test
    void createTopicRejectsClusterWithoutMasterBroker() {
        RecordingAdmin admin = new RecordingAdmin(clusterInfo(
                broker("broker-a", null, "10.0.0.1:10912")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RocketmqService.createTopic(admin, "orders", 4));

        assertEquals("未发现可写入的 RocketMQ Master Broker", error.getMessage());
    }

    @Test
    void createTopicReportsTheFailingBrokerAndRootCause() {
        RecordingAdmin admin = new RecordingAdmin(clusterInfo(
                broker("broker-a", "10.0.0.1:10911", null)));
        admin.failure = new MQClientException("outer", new IllegalStateException("permission denied"));

        Exception error = assertThrows(Exception.class,
                () -> RocketmqService.createTopic(admin, "orders", 4));

        assertTrue(error.getMessage().contains("broker-a"));
        assertTrue(error.getMessage().contains("10.0.0.1:10911"));
        assertTrue(error.getMessage().contains("permission denied"));
    }

    private static ClusterInfo clusterInfo(BrokerData... brokers) {
        Map<String, BrokerData> brokerTable = new LinkedHashMap<>();
        for (BrokerData broker : brokers) {
            brokerTable.put(broker.getBrokerName(), broker);
        }
        ClusterInfo clusterInfo = new ClusterInfo();
        clusterInfo.setBrokerAddrTable(brokerTable);
        return clusterInfo;
    }

    private static BrokerData broker(String name, String masterAddress, String slaveAddress) {
        HashMap<Long, String> addresses = new HashMap<>();
        if (masterAddress != null) addresses.put(0L, masterAddress);
        if (slaveAddress != null) addresses.put(1L, slaveAddress);
        return new BrokerData("cluster-a", name, addresses);
    }

    private static final class RecordingAdmin extends DefaultMQAdminExt {
        private final ClusterInfo clusterInfo;
        private final List<String> brokerAddresses = new ArrayList<>();
        private final List<TopicConfig> topicConfigs = new ArrayList<>();
        private MQClientException failure;

        private RecordingAdmin(ClusterInfo clusterInfo) {
            this.clusterInfo = clusterInfo;
        }

        @Override
        public ClusterInfo examineBrokerClusterInfo() {
            return clusterInfo;
        }

        @Override
        public void createAndUpdateTopicConfig(String brokerAddress, TopicConfig topicConfig)
                throws RemotingException, MQBrokerException, InterruptedException, MQClientException {
            if (failure != null) throw failure;
            brokerAddresses.add(brokerAddress);
            topicConfigs.add(new TopicConfig(topicConfig));
        }
    }
}
