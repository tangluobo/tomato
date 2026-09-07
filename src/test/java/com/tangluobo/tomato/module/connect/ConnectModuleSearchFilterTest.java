package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ConnectModuleSearchFilterTest {

    @Test
    void leafMatchKeepsAncestorsButExcludesUnrelatedBranches() {
        List<ConnectionConfig> configs = List.of(
                config("production", "Production", null),
                config("databases", "Databases", "production"),
                config("orders", "Orders PostgreSQL", "databases"),
                config("cache", "Redis cache", "databases"),
                config("staging", "Staging", null)
        );

        assertEquals(
                Set.of("production", "databases", "orders"),
                ConnectModule.computeVisibleConnectionIds(configs, "orders"));
    }

    @Test
    void parentMatchKeepsItsAncestorsAndEntireDescendantSubtree() {
        List<ConnectionConfig> configs = List.of(
                config("production", "Production", null),
                config("databases", "Databases", "production"),
                config("orders", "Orders PostgreSQL", "databases"),
                config("archive", "Archive", "orders"),
                config("cache", "Redis cache", "databases"),
                config("staging", "Staging", null)
        );

        assertEquals(
                Set.of("production", "databases", "orders", "archive", "cache"),
                ConnectModule.computeVisibleConnectionIds(configs, "databases"));
    }

    @Test
    void matchingIsTrimmedCaseInsensitiveAndNullNameSafe() {
        List<ConnectionConfig> configs = List.of(
                config("primary", "Primary PROD", null),
                config("unnamed", null, null),
                config("other", "Development", null)
        );

        assertEquals(
                Set.of("primary"),
                ConnectModule.computeVisibleConnectionIds(configs, "  pRoD  "));
    }

    @Test
    void nullAndBlankQueriesKeepEveryConnection() {
        List<ConnectionConfig> configs = List.of(
                config("production", "Production", null),
                config("orders", "Orders", "production"),
                config("staging", "Staging", null)
        );
        Set<String> expected = Set.of("production", "orders", "staging");

        assertAll(
                () -> assertEquals(expected,
                        ConnectModule.computeVisibleConnectionIds(configs, null)),
                () -> assertEquals(expected,
                        ConnectModule.computeVisibleConnectionIds(configs, "   ")));
    }

    @Test
    void malformedParentCycleCannotLoopForever() {
        List<ConnectionConfig> configs = List.of(
                config("alpha", "Alpha", "beta"),
                config("beta", "Beta", "alpha"),
                config("unrelated", "Unrelated", null)
        );

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertEquals(
                        Set.of("alpha", "beta"),
                        ConnectModule.computeVisibleConnectionIds(configs, "alpha")));
    }

    @Test
    void orphanMatchDoesNotInventAVisibleParent() {
        List<ConnectionConfig> configs = List.of(
                config("orphan", "Orphan database", "missing-parent"),
                config("unrelated", "Unrelated", null)
        );

        assertEquals(
                Set.of("orphan"),
                ConnectModule.computeVisibleConnectionIds(configs, "orphan"));
    }

    private static ConnectionConfig config(String id, String name, String parentId) {
        ConnectionConfig config = new ConnectionConfig();
        config.setId(id);
        config.setName(name);
        config.setParentId(parentId);
        return config;
    }
}
