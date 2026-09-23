package com.tangluobo.tomato.module.connect.handler;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VncClientTest {

    @Test
    void securitySelectionPrefersPasswordAuthenticationWhenPasswordExists() {
        assertEquals(2, VncClient.chooseSecurityType(new byte[]{1, 2}, "secret"));
        assertEquals(1, VncClient.chooseSecurityType(new byte[]{1, 2}, ""));
        assertEquals(2, VncClient.chooseSecurityType(new byte[]{2}, ""));
        assertEquals(0, VncClient.chooseSecurityType(new byte[]{16, 19}, "secret"));
    }

    @Test
    void vncPasswordAuthenticationUsesRfbBitReversedDesKey() throws Exception {
        byte[] challenge = new byte[16];
        for (int i = 0; i < challenge.length; i++) challenge[i] = (byte) i;

        assertArrayEquals(java.util.HexFormat.of().parseHex(
                        "B866924125C8EEBB9DEBC1DB61C538E2"),
                VncClient.encryptChallenge(challenge, "password"));
    }

    @Test
    void requestsMissingPasswordOnlyWhenServerRequiresVncAuthentication() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            byte[] challenge = new byte[16];
            for (int i = 0; i < challenge.length; i++) challenge[i] = (byte) (15 - i);
            CompletableFuture<Void> serverResult = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    output.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    assertEquals("RFB 003.008\n",
                            new String(input.readNBytes(12), StandardCharsets.US_ASCII));
                    output.writeByte(1);
                    output.writeByte(2);
                    output.flush();
                    assertEquals(2, input.readUnsignedByte());
                    output.write(challenge);
                    output.flush();
                    assertArrayEquals(VncClient.encryptChallenge(challenge, "prompted-secret"),
                            input.readNBytes(16));
                    output.writeInt(1);
                    output.writeInt(0);
                    output.flush();
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            CountDownLatch passwordRequested = new CountDownLatch(1);
            VncClient client = new VncClient("127.0.0.1", server.getLocalPort(), "",
                    new VncClient.Listener() {
                        @Override
                        public String onPasswordRequired() {
                            passwordRequested.countDown();
                            return "prompted-secret";
                        }
                    });

            Thread reader = Thread.ofVirtual().start(client::run);
            assertTrue(passwordRequested.await(5, TimeUnit.SECONDS));
            reader.join(Duration.ofSeconds(5));
            serverResult.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void embeddedClientNegotiatesRfbAndDecodesHextilePixels() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> serverResult = CompletableFuture.runAsync(() -> serveSingleFrame(server));
            CountDownLatch frameReceived = new CountDownLatch(1);
            AtomicReference<List<VncClient.RawRectangle>> received = new AtomicReference<>();
            AtomicReference<VncClient> clientRef = new AtomicReference<>();

            VncClient client = new VncClient("127.0.0.1", server.getLocalPort(), "",
                    new VncClient.Listener() {
                        @Override
                        public void onFrameUpdate(int width, int height,
                                                  List<VncClient.RawRectangle> rectangles) {
                            assertEquals(2, width);
                            assertEquals(1, height);
                            received.set(rectangles);
                            frameReceived.countDown();
                            clientRef.get().close();
                        }
                    });
            clientRef.set(client);
            Thread reader = Thread.ofVirtual().start(client::run);

            assertTrue(frameReceived.await(5, TimeUnit.SECONDS));
            reader.join(Duration.ofSeconds(5));
            serverResult.get(5, TimeUnit.SECONDS);

            VncClient.RawRectangle rectangle = received.get().getFirst();
            assertEquals(2, rectangle.width());
            assertEquals(1, rectangle.height());
            assertArrayEquals(new byte[]{1, 2, 3, (byte) 0xff, 4, 5, 6, (byte) 0xff},
                    rectangle.bgraPixels());
        }
    }

    private static void serveSingleFrame(ServerSocket server) {
        try (Socket socket = server.accept();
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            output.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            assertEquals("RFB 003.008\n", new String(input.readNBytes(12), StandardCharsets.US_ASCII));

            output.writeByte(1);
            output.writeByte(1);
            output.flush();
            assertEquals(1, input.readUnsignedByte());
            output.writeInt(0);
            output.flush();
            assertEquals(1, input.readUnsignedByte());

            output.writeShort(2);
            output.writeShort(1);
            output.write(new byte[16]);
            byte[] name = "test-desktop".getBytes(StandardCharsets.UTF_8);
            output.writeInt(name.length);
            output.write(name);
            output.flush();

            assertEquals(0, input.readUnsignedByte());
            input.readNBytes(19);
            assertEquals(2, input.readUnsignedByte());
            input.readNBytes(15);
            assertEquals(3, input.readUnsignedByte());
            input.readNBytes(9);

            output.writeByte(0);
            output.writeByte(0);
            output.writeShort(1);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(2);
            output.writeShort(1);
            output.writeInt(5);
            output.writeByte(2 | 8 | 16); // background + coloured subrectangles
            output.write(new byte[]{1, 2, 3, 0});
            output.writeByte(1);
            output.write(new byte[]{4, 5, 6, 0});
            output.writeByte(0x10); // x=1, y=0
            output.writeByte(0x00); // width=1, height=1
            output.flush();
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
