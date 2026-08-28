package com.tangluobo.tomato.module.connect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tangluobo.tomato.module.connect.ConnectionConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 腾讯云开放平台（TC3-HMAC-SHA256）客户端。 */
public final class TencentCloudService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final List<TencentProduct> PRODUCTS = List.of(
            new TencentProduct("cvm", "云服务器 CVM"),
            new TencentProduct("dnspod", "DNSPod 域名解析")
    );

    private TencentCloudService() {}

    public record TencentProduct(String code, String name) {}

    public static List<TencentProduct> getSupportedProducts() { return PRODUCTS; }

    public static boolean verifyCredentials(ConnectionConfig config) {
        try {
            verifyCredentialsOrThrow(config);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 使用 STS 查询调用者身份；该校验不要求账号拥有 CVM 权限。 */
    public static void verifyCredentialsOrThrow(ConnectionConfig config) throws Exception {
        call(config, "sts", "2018-08-13", "GetCallerIdentity", "ap-guangzhou", Map.of());
    }

    public static List<Map<String, Object>> getCvmInstances(ConnectionConfig config, String region) throws Exception {
        JsonNode response = call(config, "cvm", "2017-03-12", "DescribeInstances", region,
                Map.of("Limit", 100));
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : response.path("InstanceSet")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("instanceId", text(item, "InstanceId"));
            value.put("instanceName", text(item, "InstanceName"));
            value.put("status", text(item, "InstanceState"));
            value.put("instanceType", text(item, "InstanceType"));
            value.put("privateIp", first(item.path("PrivateIpAddresses")));
            value.put("publicIp", first(item.path("PublicIpAddresses")));
            result.add(value);
        }
        return result;
    }

    public static List<Map<String, Object>> getDomainList(ConnectionConfig config) throws Exception {
        JsonNode response = call(config, "dnspod", "2021-03-23", "DescribeDomainList", "", Map.of("Limit", 3000));
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : response.path("DomainList")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("domainName", text(item, "Name"));
            value.put("instanceId", item.path("DomainId").asText());
            value.put("status", text(item, "Status"));
            result.add(value);
        }
        return result;
    }

    public static List<Map<String, Object>> getDomainRecords(ConnectionConfig config, String domain) throws Exception {
        JsonNode response = call(config, "dnspod", "2021-03-23", "DescribeRecordList", "",
                Map.of("Domain", domain, "Limit", 3000));
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : response.path("RecordList")) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("recordId", item.path("RecordId").asText());
            value.put("rr", text(item, "Name"));
            value.put("type", text(item, "Type"));
            value.put("value", text(item, "Value"));
            value.put("ttl", item.path("TTL").asText());
            value.put("status", text(item, "Status"));
            value.put("priority", item.path("MX").asText());
            value.put("line", text(item, "Line"));
            result.add(value);
        }
        return result;
    }

    public static String addDomainRecord(ConnectionConfig config, String domain, String rr, String type,
                                         String value, Long ttl, String line, Long priority) throws Exception {
        Map<String, Object> body = recordBody(domain, rr, type, value, ttl, line, priority);
        return call(config, "dnspod", "2021-03-23", "CreateRecord", "", body).path("RecordId").asText();
    }

    public static void updateDomainRecord(ConnectionConfig config, String domain, String recordId, String rr,
                                          String type, String value, Long ttl, String line, Long priority) throws Exception {
        Map<String, Object> body = recordBody(domain, rr, type, value, ttl, line, priority);
        body.put("RecordId", Long.parseLong(recordId));
        call(config, "dnspod", "2021-03-23", "ModifyRecord", "", body);
    }

    public static void deleteDomainRecord(ConnectionConfig config, String domain, String recordId) throws Exception {
        call(config, "dnspod", "2021-03-23", "DeleteRecord", "",
                Map.of("Domain", domain, "RecordId", Long.parseLong(recordId)));
    }

    private static Map<String, Object> recordBody(String domain, String rr, String type, String value,
                                                   Long ttl, String line, Long priority) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Domain", domain); body.put("SubDomain", rr); body.put("RecordType", type);
        body.put("Value", value);
        body.put("RecordLine", line == null || line.isBlank() || "default".equalsIgnoreCase(line) ? "默认" : line);
        if (ttl != null) body.put("TTL", ttl);
        if (priority != null) body.put("MX", priority);
        return body;
    }

    private static JsonNode call(ConnectionConfig config, String service, String version, String action,
                                 String region, Map<String, Object> parameters) throws Exception {
        String host = service + ".tencentcloudapi.com";
        String payload = JSON.writeValueAsString(parameters);
        long timestamp = Instant.now().getEpochSecond();
        String date = DATE.format(Instant.ofEpochSecond(timestamp));
        String canonicalHeaders = "content-type:application/json; charset=utf-8\nhost:" + host + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256(payload);
        String scope = date + "/" + service + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256(canonicalRequest);
        byte[] secretDate = hmac(("TC3" + config.getPassword()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = hex(hmac(secretSigning, stringToSign));
        String authorization = "TC3-HMAC-SHA256 Credential=" + config.getUsername() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://" + host))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                // java.net.http 会根据 URI 自动生成 Host；该受限头不能手动设置。
                // Host 仍按腾讯云 TC3 规范参与上面的签名计算。
                .header("X-TC-Action", action)
                .header("X-TC-Version", version).header("X-TC-Timestamp", Long.toString(timestamp))
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (region != null && !region.isBlank()) request.header("X-TC-Region", region);
        HttpResponse<String> result = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(result.body());
        JsonNode response = root.path("Response");
        if (result.statusCode() / 100 != 2 || response.has("Error")) {
            JsonNode error = response.path("Error");
            throw new IllegalStateException(error.path("Code").asText("HTTP " + result.statusCode())
                    + ": " + error.path("Message").asText(result.body()));
        }
        return response;
    }

    private static String text(JsonNode node, String field) { return node.path(field).asText(""); }
    private static String first(JsonNode array) { return array.isArray() && !array.isEmpty() ? array.get(0).asText("") : ""; }
    private static String sha256(String value) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
