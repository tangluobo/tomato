package com.tangluobo.tomato.module.connect;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 导入连接的重复分析、复制及引用重映射。 */
public final class ConnectionImportPlanner {

    private static final Gson GSON = new Gson();
    private static final Set<String> IGNORED_DUPLICATE_FIELDS = Set.of(
            "id", "parentId", "password", "sshTunnelPassword", "sshTunnelHostId"
    );

    private ConnectionImportPlanner() {
    }

    /**
     * 重复项按“除 ID、所属目录和密码外，其余配置完全一致”判断。
     * 这样从不含密码的导出文件重新导入时，也能识别现有连接。
     */
    public static DuplicateAnalysis analyzeDuplicates(
            List<ConnectionConfig> incoming, List<ConnectionConfig> existing) {
        Map<String, ConnectionConfig> seenByKey = new HashMap<>();
        for (ConnectionConfig config : existing) {
            if (config != null) {
                seenByKey.putIfAbsent(duplicateKey(config), config);
            }
        }

        Set<ConnectionConfig> duplicates = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ConnectionConfig, ConnectionConfig> duplicateTargets = new IdentityHashMap<>();
        for (ConnectionConfig config : incoming) {
            if (config == null) {
                continue;
            }
            String key = duplicateKey(config);
            ConnectionConfig match = seenByKey.get(key);
            if (match != null) {
                duplicates.add(config);
                duplicateTargets.put(config, match);
            } else {
                seenByKey.put(key, config);
            }
        }
        return new DuplicateAnalysis(duplicates, duplicateTargets);
    }

    /**
     * 深拷贝用户选中的项，生成新 ID，并重映射 parentId / sshTunnelHostId。
     * 未选择的重复目录若已存在，会复用现有目录作为所选子项的父目录。
     */
    public static List<ConnectionConfig> prepareSelected(
            List<ConnectionConfig> allIncoming,
            Collection<ConnectionConfig> selected,
            List<ConnectionConfig> existing,
            DuplicateAnalysis analysis,
            Supplier<String> idSupplier) {
        Set<ConnectionConfig> selectedSet = Collections.newSetFromMap(new IdentityHashMap<>());
        selectedSet.addAll(selected);

        Map<ConnectionConfig, String> newIds = new IdentityHashMap<>();
        Map<String, ConnectionConfig> incomingById = new HashMap<>();
        for (ConnectionConfig config : allIncoming) {
            if (config != null && config.getId() != null) {
                incomingById.putIfAbsent(config.getId(), config);
            }
            if (selectedSet.contains(config)) {
                newIds.put(config, idSupplier.get());
            }
        }

        Set<String> existingIds = new HashSet<>();
        Set<String> usedNames = new HashSet<>();
        for (ConnectionConfig config : existing) {
            if (config.getId() != null) {
                existingIds.add(config.getId());
            }
            usedNames.add(safeName(config.getName()));
        }

        List<ConnectionConfig> result = new ArrayList<>();
        for (ConnectionConfig source : allIncoming) {
            if (!selectedSet.contains(source)) {
                continue;
            }
            ConnectionConfig copy = GSON.fromJson(GSON.toJson(source), ConnectionConfig.class);
            copy.setId(newIds.get(source));
            copy.setParentId(resolveReference(
                    source.getParentId(), incomingById, newIds, existingIds, analysis, new HashSet<>()));
            copy.setSshTunnelHostId(resolveReference(
                    source.getSshTunnelHostId(), incomingById, newIds, existingIds, analysis, new HashSet<>()));

            String baseName = safeName(copy.getName());
            String uniqueName = baseName;
            int suffix = 1;
            while (usedNames.contains(uniqueName)) {
                uniqueName = baseName + " (" + suffix++ + ")";
            }
            copy.setName(uniqueName);
            usedNames.add(uniqueName);
            result.add(copy);
        }
        return result;
    }

    private static String resolveReference(
            String oldId,
            Map<String, ConnectionConfig> incomingById,
            Map<ConnectionConfig, String> newIds,
            Set<String> existingIds,
            DuplicateAnalysis analysis,
            Set<ConnectionConfig> visited) {
        if (oldId == null || oldId.isBlank()) {
            return null;
        }
        ConnectionConfig referenced = incomingById.get(oldId);
        if (referenced == null) {
            return existingIds.contains(oldId) ? oldId : null;
        }

        String selectedId = newIds.get(referenced);
        if (selectedId != null) {
            return selectedId;
        }
        if (!visited.add(referenced)) {
            return null;
        }
        ConnectionConfig duplicateTarget = analysis.duplicateTargets().get(referenced);
        if (duplicateTarget == null) {
            return null;
        }
        String importedTargetId = newIds.get(duplicateTarget);
        if (importedTargetId != null) {
            return importedTargetId;
        }
        if (duplicateTarget.getId() != null && existingIds.contains(duplicateTarget.getId())) {
            return duplicateTarget.getId();
        }
        return resolveReference(
                duplicateTarget.getId(), incomingById, newIds, existingIds, analysis, visited);
    }

    static String duplicateKey(ConnectionConfig config) {
        return canonicalize(GSON.toJsonTree(config));
    }

    private static String canonicalize(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            StringBuilder out = new StringBuilder("[");
            for (JsonElement child : array) {
                out.append(canonicalize(child)).append(',');
            }
            return out.append(']').toString();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            StringBuilder out = new StringBuilder("{");
            object.entrySet().stream()
                    .filter(entry -> !IGNORED_DUPLICATE_FIELDS.contains(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.append(entry.getKey())
                            .append(':')
                            .append(canonicalize(entry.getValue()))
                            .append(','));
            return out.append('}').toString();
        }
        return element.toString();
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "未命名连接" : name;
    }

    public record DuplicateAnalysis(
            Set<ConnectionConfig> duplicates,
            Map<ConnectionConfig, ConnectionConfig> duplicateTargets) {
    }
}
