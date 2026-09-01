package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionImportPlannerTest {

    @Test
    void detectsSameConnectionEvenWhenIdsParentsAndPasswordsDiffer() {
        ConnectionConfig existing = ssh("existing", "生产机", "host-a", "secret");
        existing.setParentId("existing-folder");
        ConnectionConfig incoming = ssh("exported", "生产机", "host-a", null);
        incoming.setParentId("exported-folder");

        ConnectionImportPlanner.DuplicateAnalysis analysis =
                ConnectionImportPlanner.analyzeDuplicates(List.of(incoming), List.of(existing));

        assertTrue(analysis.duplicates().contains(incoming));
        assertEquals(existing, analysis.duplicateTargets().get(incoming));
    }

    @Test
    void changedEndpointIsNotTreatedAsDuplicate() {
        ConnectionConfig existing = ssh("one", "服务器", "host-a", "secret");
        ConnectionConfig incoming = ssh("two", "服务器", "host-b", "secret");

        ConnectionImportPlanner.DuplicateAnalysis analysis =
                ConnectionImportPlanner.analyzeDuplicates(List.of(incoming), List.of(existing));

        assertFalse(analysis.duplicates().contains(incoming));
    }

    @Test
    void selectedChildrenReuseExistingDuplicateFolderAndReferencesAreRemapped() {
        ConnectionConfig existingFolder = folder("existing-folder", "生产环境", null);
        ConnectionConfig incomingFolder = folder("old-folder", "生产环境", null);
        ConnectionConfig tunnel = ssh("old-tunnel", "跳板机", "jump.example.com", null);
        tunnel.setParentId("old-folder");
        ConnectionConfig database = ssh("old-database", "数据库", "db.example.com", null);
        database.setParentId("old-folder");
        database.setSshTunnelHostId("old-tunnel");
        List<ConnectionConfig> incoming = List.of(incomingFolder, tunnel, database);
        ConnectionImportPlanner.DuplicateAnalysis analysis =
                ConnectionImportPlanner.analyzeDuplicates(incoming, List.of(existingFolder));
        AtomicInteger ids = new AtomicInteger();

        List<ConnectionConfig> prepared = ConnectionImportPlanner.prepareSelected(
                incoming,
                List.of(tunnel, database),
                List.of(existingFolder),
                analysis,
                () -> "new-" + ids.incrementAndGet());

        assertEquals(2, prepared.size());
        assertEquals("existing-folder", prepared.get(0).getParentId());
        assertEquals("existing-folder", prepared.get(1).getParentId());
        assertEquals(prepared.get(0).getId(), prepared.get(1).getSshTunnelHostId());
        assertNotEquals("old-tunnel", prepared.get(0).getId());
    }

    @Test
    void missingUnselectedParentFallsBackToRoot() {
        ConnectionConfig incomingFolder = folder("old-folder", "新目录", null);
        ConnectionConfig child = ssh("old-child", "服务器", "host", null);
        child.setParentId("old-folder");
        List<ConnectionConfig> incoming = List.of(incomingFolder, child);
        ConnectionImportPlanner.DuplicateAnalysis analysis =
                ConnectionImportPlanner.analyzeDuplicates(incoming, List.of());

        List<ConnectionConfig> prepared = ConnectionImportPlanner.prepareSelected(
                incoming, List.of(child), List.of(), analysis, () -> "new-child");

        assertNull(prepared.get(0).getParentId());
    }

    private static ConnectionConfig folder(String id, String name, String parentId) {
        ConnectionConfig config = new ConnectionConfig();
        config.setId(id);
        config.setName(name);
        config.setParentId(parentId);
        return config;
    }

    private static ConnectionConfig ssh(String id, String name, String host, String password) {
        ConnectionConfig config = new ConnectionConfig();
        config.setId(id);
        config.setName(name);
        config.setType(ConnectType.SSH);
        config.setHost(host);
        config.setPort(22);
        config.setUsername("root");
        config.setPassword(password);
        return config;
    }
}
