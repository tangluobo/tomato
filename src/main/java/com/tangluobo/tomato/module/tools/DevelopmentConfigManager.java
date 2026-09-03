package com.tangluobo.tomato.module.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 开发项目、全局 JDK 与 Node 安装项的配置持久化。 */
final class DevelopmentConfigManager {
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".tomato");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("development.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final class Entry {
        private String name;
        private String path;
        private String projectType;
        private String jdkId;
        private String nodeId;
        private String flutterId;
        private String mavenId;
        private String mainClass;
        private String mainSourcePath;
        private List<String> activeProfiles;
        private String runType;
        private String runTarget;

        Entry(String name, String path, String projectType, String jdkId, String nodeId, String flutterId) {
            this(name, path, projectType, jdkId, nodeId, flutterId, null);
        }

        Entry(String name, String path, String projectType, String jdkId, String nodeId,
              String flutterId, String mavenId) {
            this(name, path, projectType, jdkId, nodeId, flutterId, mavenId, null, null);
        }

        Entry(String name, String path, String projectType, String jdkId, String nodeId,
              String flutterId, String mavenId, String mainClass, String mainSourcePath) {
            this(name, path, projectType, jdkId, nodeId, flutterId, mavenId,
                    mainClass, mainSourcePath, List.of(), null, null);
        }

        Entry(String name, String path, String projectType, String jdkId, String nodeId,
              String flutterId, String mavenId, String mainClass, String mainSourcePath,
              List<String> activeProfiles, String runType, String runTarget) {
            this.name = name;
            this.path = path;
            this.projectType = projectType;
            this.jdkId = jdkId;
            this.nodeId = nodeId;
            this.flutterId = flutterId;
            this.mavenId = mavenId;
            this.mainClass = mainClass;
            this.mainSourcePath = mainSourcePath;
            this.activeProfiles = activeProfiles == null ? new ArrayList<>() : new ArrayList<>(activeProfiles);
            this.runType = runType;
            this.runTarget = runTarget;
        }

        String getName() { return name; }
        String getPath() { return path; }
        String getProjectType() { return projectType; }
        String getJdkId() { return jdkId; }
        String getNodeId() { return nodeId; }
        String getFlutterId() { return flutterId; }
        String getMavenId() { return mavenId; }
        String getMainClass() { return mainClass; }
        String getMainSourcePath() { return mainSourcePath; }
        List<String> getActiveProfiles() {
            if (activeProfiles == null) activeProfiles = new ArrayList<>();
            return activeProfiles;
        }
        String getRunType() { return runType; }
        String getRunTarget() { return runTarget; }
    }

    static final class RuntimeEntry {
        private String id;
        private String name;
        private String path;
        private String version;
        private String type;

        RuntimeEntry(String id, String name, String path, String version, String type) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.version = version;
            this.type = type;
        }

        String getId() { return id; }
        String getName() { return name; }
        String getPath() { return path; }
        String getVersion() { return version; }
        String getType() { return type; }
    }

    static final class ConfigData {
        private List<Entry> projects;
        private List<RuntimeEntry> runtimes;
        private List<BuildToolEntry> buildTools;
        // 兼容上一版配置，加载后合并到 runtimes，不再写回。
        private List<RuntimeEntry> jdks;
        private List<RuntimeEntry> nodes;

        ConfigData() {
            this(List.of(), List.of(), List.of());
        }

        ConfigData(List<Entry> projects, List<RuntimeEntry> runtimes) {
            this(projects, runtimes, List.of());
        }

        ConfigData(List<Entry> projects, List<RuntimeEntry> runtimes, List<BuildToolEntry> buildTools) {
            this.projects = new ArrayList<>(projects);
            this.runtimes = new ArrayList<>(runtimes);
            this.buildTools = new ArrayList<>(buildTools);
        }

        List<Entry> getProjects() {
            if (projects == null) projects = new ArrayList<>();
            return projects;
        }

        List<RuntimeEntry> getRuntimes() {
            if (runtimes == null) runtimes = new ArrayList<>();
            return runtimes;
        }

        List<BuildToolEntry> getBuildTools() {
            if (buildTools == null) buildTools = new ArrayList<>();
            return buildTools;
        }

        void migrateLegacyRuntimes() {
            if (runtimes == null) runtimes = new ArrayList<>();
            mergeLegacy(jdks, "JDK");
            mergeLegacy(nodes, "NODE");
            jdks = null;
            nodes = null;
        }

        private void mergeLegacy(List<RuntimeEntry> legacy, String type) {
            if (legacy == null) return;
            for (RuntimeEntry entry : legacy) {
                boolean exists = runtimes.stream().anyMatch(item -> item.id.equals(entry.id));
                if (!exists) {
                    runtimes.add(new RuntimeEntry(entry.id, entry.name, entry.path, entry.version, type));
                }
            }
        }
    }

    static final class BuildToolEntry {
        private String id;
        private String type;
        private String name;
        private String homePath;
        private String version;
        private String settingsPath;

        BuildToolEntry(String id, String type, String name, String homePath,
                       String version, String settingsPath) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.homePath = homePath;
            this.version = version;
            this.settingsPath = settingsPath;
        }

        String getId() { return id; }
        String getType() { return type; }
        String getName() { return name; }
        String getHomePath() { return homePath; }
        String getVersion() { return version; }
        String getSettingsPath() { return settingsPath; }
    }

    private DevelopmentConfigManager() {
    }

    static ConfigData load() {
        if (!Files.exists(CONFIG_FILE)) {
            return new ConfigData();
        }
        try {
            String json = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonArray()) {
                Entry[] legacyProjects = GSON.fromJson(root, Entry[].class);
                return new ConfigData(legacyProjects == null ? List.of() : List.of(legacyProjects), List.of());
            }
            ConfigData data = GSON.fromJson(root, ConfigData.class);
            if (data == null) return new ConfigData();
            data.migrateLegacyRuntimes();
            return data;
        } catch (Exception e) {
            System.err.println("[DevelopmentConfigManager] 加载开发配置失败: " + e.getMessage());
            return new ConfigData();
        }
    }

    static void save(ConfigData data) throws IOException {
        data.migrateLegacyRuntimes();
        Files.createDirectories(CONFIG_DIR);
        Path temporaryFile = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
        Files.writeString(temporaryFile, GSON.toJson(data), StandardCharsets.UTF_8);
        try {
            Files.move(temporaryFile, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveError) {
            Files.move(temporaryFile, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
