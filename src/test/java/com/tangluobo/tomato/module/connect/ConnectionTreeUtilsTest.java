package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTreeUtilsTest {

    @Test
    void collectsOnlyRequestedSubtreeInOneTraversal() {
        List<ConnectionConfig> configs = List.of(
                config("root", null),
                config("child-a", "root"),
                config("grandchild", "child-a"),
                config("child-b", "root"),
                config("other", null)
        );

        assertEquals(
                Set.of("root", "child-a", "grandchild", "child-b"),
                ConnectionTreeUtils.collectSubtreeIds(configs, "root"));
    }

    @Test
    void brokenCycleCannotCauseInfiniteRecursion() {
        List<ConnectionConfig> configs = new ArrayList<>();
        configs.add(config("a", "b"));
        configs.add(config("b", "a"));

        Set<String> result = ConnectionTreeUtils.collectSubtreeIds(configs, "a");

        assertEquals(Set.of("a", "b"), result);
        assertTrue(ConnectionTreeUtils.collectSubtreeIds(configs, null).isEmpty());
    }

    @Test
    void multipleSelectedRootsShareOneSubtreeCollection() {
        List<ConnectionConfig> configs = List.of(
                config("folder-a", null),
                config("child-a", "folder-a"),
                config("folder-b", null),
                config("child-b", "folder-b"),
                config("untouched", null)
        );

        assertEquals(
                Set.of("folder-a", "child-a", "folder-b", "child-b"),
                ConnectionTreeUtils.collectSubtreesIds(
                        configs, Set.of("folder-a", "folder-b")));
    }

    private static ConnectionConfig config(String id, String parentId) {
        ConnectionConfig config = new ConnectionConfig();
        config.setId(id);
        config.setParentId(parentId);
        return config;
    }
}
