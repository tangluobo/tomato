package com.tangluobo.tomato.module.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tangluobo.tomato.utils.SecurityUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ConfigManager {
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String CONFIG_FILE = CONFIG_DIR + "/connections.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ENCRYPTION_MARKER = "TOMATO_ENCRYPTED";

    public static List<ConnectionConfig> loadConnections() {
        Path filePath = Paths.get(CONFIG_FILE);
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }

        try {
            long size = Files.size(filePath);
            if (size == 0) {
                return new ArrayList<>();
            }
        } catch (IOException e) {
            return new ArrayList<>();
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            if (content.startsWith(ENCRYPTION_MARKER)) {
                String encryptedContent = content.substring(ENCRYPTION_MARKER.length());
                String decryptedContent = SecurityUtils.decrypt(encryptedContent);
                return parseJson(decryptedContent);
            } else {
                List<ConnectionConfig> configs = parseJson(content);
                for (ConnectionConfig config : configs) {
                    if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                        try {
                            config.setPassword(SecurityUtils.decrypt(config.getPassword()));
                        } catch (Exception e) {
                        }
                    }
                }
                saveConnections(configs);
                return configs;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private static List<ConnectionConfig> parseJson(String json) {
        try {
            ConnectionConfig[] configs = GSON.fromJson(json, ConnectionConfig[].class);
            if (configs == null) {
                return new ArrayList<>();
            }
            return new ArrayList<>(List.of(configs));
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public static void saveConnections(List<ConnectionConfig> connections) {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        String json = GSON.toJson(connections);
        String encryptedContent = ENCRYPTION_MARKER + SecurityUtils.encrypt(json);

        Path tempFile = Paths.get(CONFIG_FILE + ".tmp");
        try {
            Files.writeString(tempFile, encryptedContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        try {
            Files.move(tempFile, Paths.get(CONFIG_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            e.printStackTrace();
            try {
                Files.copy(tempFile, Paths.get(CONFIG_FILE), StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    public static String generateId() {
        return UUID.randomUUID().toString();
    }
}