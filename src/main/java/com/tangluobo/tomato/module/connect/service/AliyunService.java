package com.tangluobo.tomato.module.connect.service;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.ecs.model.v20140526.DescribeRegionsRequest;
import com.aliyuncs.ecs.model.v20140526.DescribeRegionsResponse;
import com.aliyuncs.domain.model.v20180129.QueryDomainListRequest;
import com.aliyuncs.domain.model.v20180129.QueryDomainListResponse;
import com.aliyuncs.alidns.model.v20150109.DescribeDomainRecordsRequest;
import com.aliyuncs.alidns.model.v20150109.DescribeDomainRecordsResponse;
import com.aliyuncs.alidns.model.v20150109.AddDomainRecordRequest;
import com.aliyuncs.alidns.model.v20150109.UpdateDomainRecordRequest;
import com.aliyuncs.alidns.model.v20150109.DeleteDomainRecordRequest;
import com.tangluobo.tomato.module.connect.ConnectionConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云服务，通过AK/SK进行OAuth2认证并获取可访问的云服务列表
 */
public class AliyunService {

    /**
     * 云服务产品定义
     */
    public static class AliyunProduct {
        private final String code;
        private final String name;
        private final String iconType;

        public AliyunProduct(String code, String name, String iconType) {
            this.code = code;
            this.name = name;
            this.iconType = iconType;
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getIconType() { return iconType; }
    }

    /**
     * 预定义的阿里云产品列表
     */
    private static final List<AliyunProduct> SUPPORTED_PRODUCTS = List.of(
        new AliyunProduct("ecs", "云服务器ECS", "server"),
        new AliyunProduct("rds", "云数据库RDS", "database"),
        new AliyunProduct("oss", "对象存储OSS", "storage"),
        new AliyunProduct("vpc", "专有网络VPC", "network"),
        new AliyunProduct("slb", "负载均衡SLB", "balance"),
        new AliyunProduct("redis", "云数据库Redis", "cache"),
        new AliyunProduct("mq", "消息队列MQ", "mq"),
        new AliyunProduct("ack", "容器服务ACK", "container"),
        new AliyunProduct("domain", "域名管理", "domain")
    );

    /**
     * 获取支持的云产品列表
     */
    public static List<AliyunProduct> getSupportedProducts() {
        return SUPPORTED_PRODUCTS;
    }

    /**
     * 创建阿里云API客户端
     */
    public static IAcsClient createClient(ConnectionConfig config) {
        String regionId = "cn-hangzhou"; // 默认区域，用于认证验证
        String accessKeyId = config.getUsername();
        String accessKeySecret = config.getPassword();

        DefaultProfile profile = DefaultProfile.getProfile(regionId, accessKeyId, accessKeySecret);
        return new DefaultAcsClient(profile);
    }

    /**
     * 验证AK/SK凭证是否有效（通过调用DescribeRegions API进行OAuth2认证）
     */
    public static boolean verifyCredentials(ConnectionConfig config) {
        IAcsClient client = createClient(config);
        try {
            DescribeRegionsRequest request = new DescribeRegionsRequest();
            request.setSysRegionId("cn-hangzhou");
            DescribeRegionsResponse response = client.getAcsResponse(request);
            return response.getRegions() != null;
        } catch (ClientException e) {
            return false;
        } finally {
            if (client instanceof DefaultAcsClient dac) {
                dac.shutdown();
            }
        }
    }

    /**
     * 获取可访问的地域列表
     */
    public static List<Map<String, String>> getRegions(ConnectionConfig config) throws Exception {
        IAcsClient client = createClient(config);
        try {
            DescribeRegionsRequest request = new DescribeRegionsRequest();
            request.setSysRegionId("cn-hangzhou");
            DescribeRegionsResponse response = client.getAcsResponse(request);

            List<Map<String, String>> regions = new ArrayList<>();
            for (DescribeRegionsResponse.Region region : response.getRegions()) {
                Map<String, String> regionInfo = new LinkedHashMap<>();
                regionInfo.put("regionId", region.getRegionId());
                regionInfo.put("localName", region.getLocalName());
                regions.add(regionInfo);
            }
            return regions;
        } finally {
            if (client instanceof DefaultAcsClient dac) {
                dac.shutdown();
            }
        }
    }

    /**
     * 获取ECS实例列表（指定地域）
     */
    public static List<Map<String, Object>> getEcsInstances(ConnectionConfig config, String regionId) throws Exception {
        DefaultProfile profile = DefaultProfile.getProfile(regionId, config.getUsername(), config.getPassword());
        DefaultAcsClient client = new DefaultAcsClient(profile);
        try {
            com.aliyuncs.ecs.model.v20140526.DescribeInstancesRequest request =
                    new com.aliyuncs.ecs.model.v20140526.DescribeInstancesRequest();
            request.setSysRegionId(regionId);
            request.setPageSize(100);
            com.aliyuncs.ecs.model.v20140526.DescribeInstancesResponse response =
                    client.getAcsResponse(request);

            List<Map<String, Object>> instances = new ArrayList<>();
            for (com.aliyuncs.ecs.model.v20140526.DescribeInstancesResponse.Instance instance :
                    response.getInstances()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("instanceId", instance.getInstanceId());
                info.put("instanceName", instance.getInstanceName());
                info.put("status", instance.getStatus());
                info.put("instanceType", instance.getInstanceType());
                info.put("regionId", instance.getRegionId());
                // 获取内网IP
                if (instance.getVpcAttributes() != null && instance.getVpcAttributes().getPrivateIpAddress() != null
                        && instance.getVpcAttributes().getPrivateIpAddress().size() > 0) {
                    info.put("privateIp", instance.getVpcAttributes().getPrivateIpAddress().get(0));
                }
                // 获取公网IP
                if (instance.getPublicIpAddress() != null && instance.getPublicIpAddress().size() > 0) {
                    info.put("publicIp", instance.getPublicIpAddress().get(0));
                }
                instances.add(info);
            }
            return instances;
        } finally {
            client.shutdown();
        }
    }

    /**
     * 获取域名列表
     */
    public static List<Map<String, Object>> getDomainList(ConnectionConfig config) throws Exception {
        IAcsClient client = createClient(config);
        try {
            QueryDomainListRequest request = new QueryDomainListRequest();
            request.setPageNum(1);
            request.setPageSize(100);
            request.setLang("zh");
            QueryDomainListResponse response = client.getAcsResponse(request);

            List<Map<String, Object>> domains = new ArrayList<>();
            List<QueryDomainListResponse.Domain> domainList = response.getData();
            if (domainList != null) {
                for (QueryDomainListResponse.Domain domain : domainList) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("domainName", domain.getDomainName());
                    info.put("instanceId", domain.getInstanceId());
                    info.put("registrationDate", domain.getRegistrationDate());
                    info.put("expirationDate", domain.getExpirationDate());
                    info.put("domainStatus", domain.getDomainStatus());
                    info.put("domainAuditStatus", domain.getDomainAuditStatus());
                    info.put("domainGroupName", domain.getDomainGroupName());
                    info.put("remark", domain.getRemark());
                    info.put("expirationCurrDateDiff", domain.getExpirationCurrDateDiff());
                    info.put("domainType", domain.getDomainType());
                    info.put("registrantType", domain.getRegistrantType());
                    info.put("registrar", domain.getRegistrar());
                    domains.add(info);
                }
            }
            return domains;
        } finally {
            if (client instanceof DefaultAcsClient dac) {
                dac.shutdown();
            }
        }
    }

    /**
     * 获取指定域名下的解析记录（子域名列表）
     */
    public static List<Map<String, Object>> getDomainRecords(ConnectionConfig config, String domainName) throws Exception {
        IAcsClient client = createClient(config);
        try {
            DescribeDomainRecordsRequest request = new DescribeDomainRecordsRequest();
            request.setDomainName(domainName);
            request.setPageSize(500L);
            request.setLang("zh");
            DescribeDomainRecordsResponse response = client.getAcsResponse(request);

            List<Map<String, Object>> records = new ArrayList<>();
            List<DescribeDomainRecordsResponse.Record> recordList = response.getDomainRecords();
            if (recordList != null) {
                for (DescribeDomainRecordsResponse.Record record : recordList) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("recordId", record.getRecordId());
                    info.put("rr", record.getRR());
                    info.put("type", record.getType());
                    info.put("value", record.getValue());
                    info.put("ttl", record.getTTL());
                    info.put("status", record.getStatus());
                    info.put("priority", record.getPriority());
                    info.put("domainName", record.getDomainName());
                    info.put("line", record.getLine());
                    records.add(info);
                }
            }
            return records;
        } finally {
            if (client instanceof DefaultAcsClient dac) {
                dac.shutdown();
            }
        }
    }

    /**
     * 添加解析记录（子域名）
     */
    public static String addDomainRecord(ConnectionConfig config, String domainName,
                                         String rr, String type, String value,
                                         Long ttl, String line, Long priority) throws Exception {
        IAcsClient client = createClient(config);
        try {
            AddDomainRecordRequest request = new AddDomainRecordRequest();
            request.setDomainName(domainName);
            request.setRR(rr);
            request.setType(type);
            request.setValue(value);
            if (ttl != null) request.setTTL(ttl);
            if (line != null && !line.isEmpty()) request.setLine(line);
            if (priority != null) request.setPriority(priority);
            request.setLang("zh");
            return client.getAcsResponse(request).getRecordId();
        } finally {
            if (client instanceof DefaultAcsClient dac) {
                dac.shutdown();
            }
        }
    }

    /**
     * 修改解析记录（子域名）
     */
    public static void updateDomainRecord(ConnectionConfig config, String recordId,
                                          String rr, String type, String value,
                                          Long ttl, String line, Long priority) throws Exception {
        IAcsClient client = createClient(config);
        try {
            UpdateDomainRecordRequest request = new UpdateDomainRecordRequest();
            request.setRecordId(recordId);
            request.setRR(rr);
            request.setType(type);
            request.setValue(value);
            if (ttl != null) request.setTTL(ttl);
            if (line != null && !line.isEmpty()) request.setLine(line);
            if (priority != null) request.setPriority(priority);
            request.setLang("zh");
            client.getAcsResponse(request);
        } finally {
            if (client instanceof DefaultAcsClient dac) {
                dac.shutdown();
            }
        }
    }

    /**
     * 删除解析记录（子域名）
     */
    public static void deleteDomainRecord(ConnectionConfig config, String recordId) throws Exception {
        IAcsClient client = createClient(config);
        try {
            DeleteDomainRecordRequest request = new DeleteDomainRecordRequest();
            request.setRecordId(recordId);
            request.setLang("zh");
            client.getAcsResponse(request);
        } finally {
            if (client instanceof DefaultAcsClient dac) {
                dac.shutdown();
            }
        }
    }
}
