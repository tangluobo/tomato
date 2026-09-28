package com.tangluobo.tomato.module.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortMappingPaneTest {

    @Test
    void localMappingsConflictOnlyAtRuntimeWhenTheyUseTheSameLocalPort() {
        PortMappingPane.PortMapping first = mapping("L", "ssh-a", 13306, 3306);
        PortMappingPane.PortMapping second = mapping("L", "ssh-b", 13306, 3306);
        PortMappingPane.PortMapping otherPort = mapping("L", "ssh-a", 13307, 3306);

        assertTrue(PortMappingPane.runtimeEndpointsConflict(first, second));
        assertFalse(PortMappingPane.runtimeEndpointsConflict(first, otherPort));
    }

    @Test
    void remoteMappingsConflictOnlyOnTheSameSshServerAndRemotePort() {
        PortMappingPane.PortMapping first = mapping("R", "ssh-a", 8080, 19090);
        PortMappingPane.PortMapping sameServer = mapping("R", "ssh-a", 8081, 19090);
        PortMappingPane.PortMapping otherServer = mapping("R", "ssh-b", 8081, 19090);

        assertTrue(PortMappingPane.runtimeEndpointsConflict(first, sameServer));
        assertFalse(PortMappingPane.runtimeEndpointsConflict(first, otherServer));
    }

    @Test
    void oppositeDirectionsDoNotCompeteForTheSameListener() {
        PortMappingPane.PortMapping local = mapping("L", "ssh-a", 19090, 19090);
        PortMappingPane.PortMapping remote = mapping("R", "ssh-a", 19090, 19090);

        assertFalse(PortMappingPane.runtimeEndpointsConflict(local, remote));
    }

    private static PortMappingPane.PortMapping mapping(
            String direction, String sshHostId, int localPort, int remotePort) {
        PortMappingPane.PortMapping mapping = new PortMappingPane.PortMapping();
        mapping.setDirection(direction);
        mapping.setSshHostId(sshHostId);
        mapping.setLocalPort(localPort);
        mapping.setRemotePort(remotePort);
        return mapping;
    }
}
