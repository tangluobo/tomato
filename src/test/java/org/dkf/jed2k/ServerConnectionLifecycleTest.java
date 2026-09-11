package org.dkf.jed2k;

import org.dkf.jed2k.alert.ServerConectionClosed;
import org.dkf.jed2k.alert.ServerIdAlert;
import org.dkf.jed2k.exception.ErrorCode;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.server.IdChange;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.channels.Selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConnectionLifecycleTest {

    @Test
    void connectedServerEndpointRequiresCurrentCompletedHandshake() throws Exception {
        Session session = new Session(new Settings());
        try (Selector selector = Selector.open()) {
            session.selector = selector;
            ServerConnection stale = connection(session, "stale", 4661);
            ServerConnection current = connection(session, "current", 4662);

            assertFalse(session.getConnectedServerEndpoint().defined());
            session.serverConection = current;
            assertFalse(session.getConnectedServerEndpoint().defined());

            stale.onServerIdChange(idChange(1, 2, 3));
            assertFalse(session.getConnectedServerEndpoint().defined());

            current.onServerIdChange(idChange(4, 5, 6));
            Endpoint endpoint = session.getConnectedServerEndpoint();
            assertEquals(new Endpoint(current.getAddress()), endpoint);

            // The caller gets a defensive snapshot, not mutable session state.
            endpoint.setPort(1);
            assertEquals(4662, session.getConnectedServerEndpoint().getPort());

            stale.close(ErrorCode.NO_ERROR);
            current.close(ErrorCode.NO_ERROR);
            assertFalse(session.getConnectedServerEndpoint().defined());
        }
    }

    @Test
    void closingConnectionTwiceNotifiesSessionOnce() throws Exception {
        Session session = new Session(new Settings());
        try (Selector selector = Selector.open()) {
            session.selector = selector;
            ServerConnection connection = connection(session, "server", 4661);
            session.serverConection = connection;

            connection.close(ErrorCode.NO_ERROR);
            connection.close(ErrorCode.IO_EXCEPTION);

            assertTrue(connection.isDisconnecting());
            assertNull(session.serverConection);
            assertInstanceOf(ServerConectionClosed.class, session.popAlert());
            assertNull(session.popAlert());
        }
    }

    @Test
    void staleDisconnectCannotClearReplacementServerState() throws Exception {
        Session session = new Session(new Settings());
        try (Selector selector = Selector.open()) {
            session.selector = selector;
            ServerConnection stale = connection(session, "stale", 4661);
            ServerConnection current = connection(session, "current", 4662);
            session.serverConection = current;
            session.clientId = 0x01020304;
            session.tcpFlags = 0x11223344;
            session.auxPort = 4672;

            stale.close(ErrorCode.IO_EXCEPTION);

            assertSame(current, session.serverConection);
            assertEquals(0x01020304, session.clientId);
            assertEquals(0x11223344, session.tcpFlags);
            assertEquals(4672, session.auxPort);
            assertNull(session.popAlert());

            current.close(ErrorCode.NO_ERROR);
            assertNull(session.serverConection);
            assertEquals(0, session.clientId);
            assertEquals(0, session.tcpFlags);
            assertEquals(0, session.auxPort);
        }
    }

    @Test
    void staleHandshakeCannotOverwriteReplacementServerState() throws Exception {
        Session session = new Session(new Settings());
        try (Selector selector = Selector.open()) {
            session.selector = selector;
            ServerConnection stale = connection(session, "stale", 4661);
            ServerConnection current = connection(session, "current", 4662);
            session.serverConection = current;
            session.clientId = 7;
            session.tcpFlags = 8;
            session.auxPort = 9;
            IdChange id = idChange(0x01020304, 0x11223344, 4672);

            stale.onServerIdChange(id);

            assertEquals(7, session.clientId);
            assertEquals(8, session.tcpFlags);
            assertEquals(9, session.auxPort);
            assertFalse(stale.isHandshakeCompleted());
            assertNull(session.popAlert());

            current.onServerIdChange(id);
            assertEquals(id.clientId, session.clientId);
            assertEquals(id.tcpFlags, session.tcpFlags);
            assertEquals(id.auxPort, session.auxPort);
            assertTrue(current.isHandshakeCompleted());
            assertInstanceOf(ServerIdAlert.class, session.popAlert());

            stale.close(ErrorCode.NO_ERROR);
            current.close(ErrorCode.NO_ERROR);
        }
    }

    private static ServerConnection connection(Session session, String id, int port)
            throws Exception {
        return ServerConnection.makeConnection(id,
                new InetSocketAddress("127.0.0.1", port), session);
    }

    private static IdChange idChange(int clientId, int tcpFlags, int auxPort) {
        IdChange value = new IdChange();
        value.clientId = clientId;
        value.tcpFlags = tcpFlags;
        value.auxPort = auxPort;
        return value;
    }
}
