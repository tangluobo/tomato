package com.tangluobo.tomato.module.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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

    private int scrollbackLines = 1000;

    public int getScrollbackLines() {
        return scrollbackLines;
    }

    public void setScrollbackLines(int scrollbackLines) {
        this.scrollbackLines = scrollbackLines;
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
