package com.tangluobo.tomato.module.connect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 连接树的纯数据操作，避免在删除目录时递归扫描整个连接列表。 */
final class ConnectionTreeUtils {

    private ConnectionTreeUtils() {
    }

    /**
     * 一次建立父子索引后遍历子树，复杂度为 O(n)。
     * visited 同时防止损坏配置中的父子环导致无限递归。
     */
    static Set<String> collectSubtreeIds(List<ConnectionConfig> connections, String rootId) {
        if (rootId == null || rootId.isBlank()) {
            return Set.of();
        }
        return collectSubtreesIds(connections, List.of(rootId));
    }

    /** 多个根节点共用同一份父子索引，批量删除仍保持 O(n)。 */
    static Set<String> collectSubtreesIds(
            List<ConnectionConfig> connections, Collection<String> rootIds) {
        Set<String> result = new HashSet<>();
        if (rootIds == null || rootIds.isEmpty()) {
            return result;
        }

        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (ConnectionConfig config : connections) {
            if (config == null || config.getId() == null) {
                continue;
            }
            String parentId = config.getParentId();
            if (parentId != null && !parentId.isBlank()) {
                childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>())
                        .add(config.getId());
            }
        }

        ArrayDeque<String> pending = new ArrayDeque<>();
        for (String rootId : rootIds) {
            if (rootId != null && !rootId.isBlank()) {
                pending.push(rootId);
            }
        }
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!result.add(id)) {
                continue;
            }
            List<String> childIds = childrenByParent.get(id);
            if (childIds != null) {
                pending.addAll(childIds);
            }
        }
        return result;
    }
}
