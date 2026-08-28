package com.tangluobo.tomato.module.connect.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tangluobo.tomato.module.connect.ConfigManager;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.ConnectType;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDNS 全局服务（单例）。
 * 持久化 DDNS 配置到 ~/.tomato/ddns.json，应用启动即自动加载并定时更新，
 * 不依赖阿里云连接或子域名面板是否打开。
 */
public class DdnsService {
    private static final DdnsService INSTANCE = new DdnsService();
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String DDNS_FILE = CONFIG_DIR + "/ddns.json";
    private static final long INTERVAL_MINUTES = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<DdnsEntry>>() {}.getType();
    private static final String[] PUBLIC_IP_URLS = {
        "https://ifconfig.me/ip",
        "https://ddns.oray.com/checkip"
    };
    private static final Pattern IPV4_PATTERN = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");

    private final List<DdnsEntry> entries = new CopyOnWriteArrayList<>();
    private final Map<String, ConnectionConfig> configCache = new ConcurrentHashMap<>();
    private volatile String lastPublicIp = null;
    private ScheduledExecutorService scheduler;
    private ExecutorService worker;
    private final AtomicBoolean tickRunning = new AtomicBoolean(false);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private DdnsService() {}

    public static DdnsService getInstance() { return INSTANCE; }

    /** DDNS 持久化条目 */
    public static class DdnsEntry {
        public String connectionId;
        public String domainName;
        public String recordId;
        public String rr;
        public String type;
        public String ttl;
        public String line;
    }

    /** 应用启动时调用：加载配置并启动后台调度 */
    public synchronized void start() {
        load();
        refreshConfigCache();
        if (entries.isEmpty()) return;
        ensureScheduler();
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    private void ensureScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DDNS-Global-Scheduler");
            t.setDaemon(true);
            return t;
        });
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DDNS-Worker");
            t.setDaemon(true);
            return t;
        });
        // initialDelay=0：启动后立即检测一次，然后每分钟一次
        scheduler.scheduleAtFixedRate(this::tick, 0, INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    public boolean isEnabled(String connectionId, String recordId) {
        for (DdnsEntry e : entries) {
            if (Objects.equals(e.connectionId, connectionId) && Objects.equals(e.recordId, recordId)) {
                return true;
            }
        }
        return false;
    }

    /** 开启某条记录的 DDNS（持久化 + 确保调度运行） */
    public synchronized void enable(ConnectionConfig config, String domainName,
                                    String recordId, String rr, String type, String ttl, String line) {
        entries.removeIf(e -> Objects.equals(e.recordId, recordId));
        DdnsEntry entry = new DdnsEntry();
        entry.connectionId = config.getId();
        entry.domainName = domainName;
        entry.recordId = recordId;
        entry.rr = rr;
        entry.type = type;
        entry.ttl = ttl;
        entry.line = line;
        entries.add(entry);
        configCache.put(config.getId(), config);
        save();
        ensureScheduler();
    }

    /** 关闭某条记录的 DDNS（移除持久化） */
    public synchronized void disable(String recordId) {
        entries.removeIf(e -> Objects.equals(e.recordId, recordId));
        save();
        if (entries.isEmpty()) {
            stop();
        }
    }

    private void refreshConfigCache() {
        try {
            List<ConnectionConfig> configs = ConfigManager.loadConnections();
            for (ConnectionConfig c : configs) {
                configCache.put(c.getId(), c);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 定时触发：仅将实际工作提交到独立工作线程，不阻塞调度线程，绝不影响主界面 */
    private void tick() {
        if (entries.isEmpty()) return;
        if (worker == null || worker.isShutdown()) return;
        worker.submit(this::doTick);
    }

    /** 实际检测公网IP变化并更新所有开启DDNS的记录（运行在 DDNS-Worker 线程） */
    private void doTick() {
        // 防止上一次更新尚未完成时重复执行（网络慢时避免堆积）
        if (!tickRunning.compareAndSet(false, true)) return;
        try {
            if (configCache.isEmpty()) refreshConfigCache();
            String ip;
            try {
                ip = fetchPublicIp();
            } catch (Exception e) {
                System.out.println("[DDNS] 获取公网IP失败: " + e.getMessage());
                return;
            }
            if (ip.equals(lastPublicIp)) {
                System.out.println("[DDNS] 公网IP未变化: " + ip + "，跳过更新");
                return;
            }
            System.out.println("[DDNS] 检测到公网IP变化: " + (lastPublicIp == null ? "(首次)" : lastPublicIp) + " → " + ip
                    + "，开始更新 " + entries.size() + " 条记录");
            lastPublicIp = ip;
            for (DdnsEntry entry : entries) {
                ConnectionConfig config = configCache.get(entry.connectionId);
                if (config == null) {
                    System.out.println("[DDNS] 跳过 " + entry.rr + "." + entry.domainName + "：连接配置不存在");
                    continue;
                }
                try {
                    if (config.getType() == ConnectType.TENCENT_CLOUD)
                        TencentCloudService.updateDomainRecord(config, entry.domainName, entry.recordId, entry.rr,
                                entry.type, ip, parseLong(entry.ttl), nullIfEmpty(entry.line), null);
                    else AliyunService.updateDomainRecord(config, entry.recordId, entry.rr, entry.type, ip,
                                parseLong(entry.ttl), nullIfEmpty(entry.line), null);
                    System.out.println("[DDNS] 已更新 " + entry.rr + "." + entry.domainName + " → " + ip);
                } catch (Exception e) {
                    String msg = e.getMessage();
                    // DomainRecordDuplicate: 记录值已等于新IP，视为已是最新，不算失败
                    if (msg != null && msg.contains("DomainRecordDuplicate")) {
                        System.out.println("[DDNS] " + entry.rr + "." + entry.domainName + " 已是最新IP " + ip + "，无需更新");
                    } else {
                        System.out.println("[DDNS] 更新失败 " + entry.rr + "." + entry.domainName + ": " + msg);
                    }
                }
            }
        } finally {
            tickRunning.set(false);
        }
    }

    /** 获取当前网络公网 IPv4（多服务回退，自动提取首个 IPv4） */
    public String fetchPublicIp() throws Exception {
        Exception last = null;
        for (String url : PUBLIC_IP_URLS) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "curl/8.0")
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                Matcher m = IPV4_PATTERN.matcher(resp.body());
                if (m.find()) return m.group(1);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new RuntimeException("获取公网IP失败: " + (last != null ? last.getMessage() : "所有服务均无响应"));
    }

    private void load() {
        try {
            Path p = Paths.get(DDNS_FILE);
            if (!Files.exists(p)) return;
            String content = Files.readString(p, StandardCharsets.UTF_8);
            List<DdnsEntry> list = GSON.fromJson(content, ENTRY_LIST_TYPE);
            if (list != null) {
                entries.clear();
                entries.addAll(list);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void save() {
        try {
            Path dir = Paths.get(CONFIG_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Files.writeString(Paths.get(DDNS_FILE), GSON.toJson(entries), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Long parseLong(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
