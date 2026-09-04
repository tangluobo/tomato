package com.tangluobo.tomato.module.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tangluobo.tomato.utils.SecurityUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 全局配置管理
 */
public class GlobalConfig {
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String CONFIG_FILE = CONFIG_DIR + "/global.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ENCRYPTED_VALUE_PREFIX = "TOMATO_ENCRYPTED:";

    private int scrollbackLines = 1000;

    private String tableFontName = "Sans Serif";
    private int tableFontSize = 10;

    private boolean sidebarVisible = true;

    private String sshTerminalFontName = "monospace";
    private double sshTerminalFontSize = 13.0;

    // SSH 终端：双击 SSH 节点时是否展开服务管理子节点（终端/容器/服务/端口/文件）
    private boolean sshServiceManagementEnabled = true;

    // SSH 文件浏览器默认视图模式：ICON（图标视图）/ LIST（详细列表）/ COLUMN（多列列表，仿 macOS）
    private String sshDefaultFileViewMode = "LIST";

    // RDP全屏切换快捷键（全局默认，连接配置可按会话覆盖）
    private String rdpFullScreenShortcut = "Ctrl+Shift+Enter";

    // RDP打开方式：WINDOW（独立窗口，默认）/ TAB（连接标签页）
    private String rdpOpenMode = "WINDOW";

    // AI SQL：使用 OpenAI 兼容的 /chat/completions 接口。
    private String aiApiBaseUrl = "https://api.openai.com/v1";
    private String aiModel = "";
    private String aiApiKey = "";
    private boolean aiIncludeSchema = true;
    private int aiRequestTimeoutSeconds = 60;

    public int getScrollbackLines() {
        return scrollbackLines;
    }

    public void setScrollbackLines(int scrollbackLines) {
        this.scrollbackLines = scrollbackLines;
    }

    public String getTableFontName() {
        return tableFontName;
    }

    public void setTableFontName(String tableFontName) {
        this.tableFontName = tableFontName;
    }

    public int getTableFontSize() {
        return tableFontSize;
    }

    public void setTableFontSize(int tableFontSize) {
        this.tableFontSize = tableFontSize;
    }

    public boolean isSidebarVisible() {
        return sidebarVisible;
    }

    public void setSidebarVisible(boolean sidebarVisible) {
        this.sidebarVisible = sidebarVisible;
    }

    public String getSshTerminalFontName() {
        return sshTerminalFontName;
    }

    public void setSshTerminalFontName(String sshTerminalFontName) {
        this.sshTerminalFontName = sshTerminalFontName;
    }

    public double getSshTerminalFontSize() {
        return sshTerminalFontSize;
    }

    public void setSshTerminalFontSize(double sshTerminalFontSize) {
        this.sshTerminalFontSize = sshTerminalFontSize;
    }

    public boolean isSshServiceManagementEnabled() {
        return sshServiceManagementEnabled;
    }

    public void setSshServiceManagementEnabled(boolean sshServiceManagementEnabled) {
        this.sshServiceManagementEnabled = sshServiceManagementEnabled;
    }

    public String getSshDefaultFileViewMode() {
        return sshDefaultFileViewMode;
    }

    public void setSshDefaultFileViewMode(String sshDefaultFileViewMode) {
        this.sshDefaultFileViewMode = sshDefaultFileViewMode;
    }

    public String getRdpFullScreenShortcut() {
        return rdpFullScreenShortcut;
    }

    public void setRdpFullScreenShortcut(String rdpFullScreenShortcut) {
        this.rdpFullScreenShortcut = rdpFullScreenShortcut;
    }

    public String getRdpOpenMode() {
        return rdpOpenMode == null || rdpOpenMode.isBlank() ? "WINDOW" : rdpOpenMode;
    }

    public void setRdpOpenMode(String rdpOpenMode) {
        this.rdpOpenMode = rdpOpenMode;
    }

    public String getAiApiBaseUrl() {
        return aiApiBaseUrl == null ? "" : aiApiBaseUrl.trim();
    }

    public void setAiApiBaseUrl(String aiApiBaseUrl) {
        this.aiApiBaseUrl = aiApiBaseUrl == null ? "" : aiApiBaseUrl.trim();
    }

    public String getAiModel() {
        return aiModel == null ? "" : aiModel.trim();
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel == null ? "" : aiModel.trim();
    }

    public String getAiApiKey() {
        if (aiApiKey == null || aiApiKey.isBlank()) return "";
        if (!aiApiKey.startsWith(ENCRYPTED_VALUE_PREFIX)) return aiApiKey;
        try {
            return SecurityUtils.decrypt(aiApiKey.substring(ENCRYPTED_VALUE_PREFIX.length()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    public void setAiApiKey(String aiApiKey) {
        String value = aiApiKey == null ? "" : aiApiKey.trim();
        this.aiApiKey = value.isEmpty()
                ? ""
                : ENCRYPTED_VALUE_PREFIX + SecurityUtils.encrypt(value);
    }

    public boolean isAiIncludeSchema() {
        return aiIncludeSchema;
    }

    public void setAiIncludeSchema(boolean aiIncludeSchema) {
        this.aiIncludeSchema = aiIncludeSchema;
    }

    public int getAiRequestTimeoutSeconds() {
        return aiRequestTimeoutSeconds <= 0 ? 60 : aiRequestTimeoutSeconds;
    }

    public void setAiRequestTimeoutSeconds(int aiRequestTimeoutSeconds) {
        this.aiRequestTimeoutSeconds = Math.max(5, Math.min(300, aiRequestTimeoutSeconds));
    }

    public boolean isAiSqlConfigured() {
        return !getAiApiBaseUrl().isBlank() && !getAiModel().isBlank();
    }

    private static GlobalConfig instance;

    public static GlobalConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static GlobalConfig load() {
        Path filePath = Paths.get(CONFIG_FILE);
        if (!Files.exists(filePath)) {
            return new GlobalConfig();
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            GlobalConfig config = GSON.fromJson(content, GlobalConfig.class);
            return config != null ? config : new GlobalConfig();
        } catch (Exception e) {
            return new GlobalConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            String json = GSON.toJson(this);
            Files.writeString(Paths.get(CONFIG_FILE), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
