package com.tangluobo.tomato.ssh.zmodem.xfer.zm.util;

import com.tangluobo.tomato.ssh.zmodem.util.FileAdapter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZModemSendTest {

    @Test
    void fileLengthDoesNotDependOnInputStreamAvailable() throws Exception {
        byte[] content = new byte[1234];
        ZModemSend sender = sender(new MemoryFile("payload.bin", content, 0));

        assertTrue(sender.nextFile());
        assertEquals(content.length, longField(sender, "filesize"));
        assertArrayEquals(content, invokeBlock(sender));
        assertTrue(booleanField(sender, "atEof"));
        assertFalse(sender.nextFile());
    }

    @Test
    void resumeOffsetIsAbsoluteAndCanRewind() throws Exception {
        byte[] content = new byte[20_000];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i & 0xff);
        ZModemSend sender = sender(new MemoryFile("payload.bin", content, content.length));

        assertTrue(sender.nextFile());
        invokeBlock(sender);
        invokePosition(sender, 4096);

        assertArrayEquals(Arrays.copyOfRange(content, 4096, 4096 + 8192), invokeBlock(sender));
        assertFalse(sender.nextFile());
    }

    @Test
    void exactBlockSizeIsMarkedAsEndOfFileEvenWhenAvailableLies() throws Exception {
        byte[] content = new byte[8192];
        ZModemSend sender = sender(new MemoryFile("payload.bin", content, 1));

        assertTrue(sender.nextFile());
        assertEquals(8192, invokeBlock(sender).length);
        assertTrue(booleanField(sender, "atEof"));
        assertFalse(sender.nextFile());
    }

    private static ZModemSend sender(FileAdapter file) throws IOException {
        return new ZModemSend(() -> List.of(file), InputStream.nullInputStream(),
                OutputStream.nullOutputStream());
    }

    private static byte[] invokeBlock(ZModemSend sender) throws Exception {
        Method method = ZModemSend.class.getDeclaredMethod("getNextBlock");
        method.setAccessible(true);
        byte[] block = (byte[]) method.invoke(sender);
        return block == null ? null : Arrays.copyOf(block, block.length);
    }

    private static void invokePosition(ZModemSend sender, int offset) throws Exception {
        Method method = ZModemSend.class.getDeclaredMethod("position", int.class);
        method.setAccessible(true);
        method.invoke(sender, offset);
    }

    private static long longField(ZModemSend sender, String name) throws Exception {
        Field field = ZModemSend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(sender);
    }

    private static boolean booleanField(ZModemSend sender, String name) throws Exception {
        Field field = ZModemSend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(sender);
    }

    private record MemoryFile(String name, byte[] content, int reportedAvailable)
            implements FileAdapter {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content) {
                @Override
                public int available() {
                    return reportedAvailable;
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public OutputStream getOutputStream(boolean append) {
            return getOutputStream();
        }

        @Override
        public FileAdapter getChild(String childName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long length() {
            return content.length;
        }

        @Override
        public boolean isDirectory() {
            return false;
        }

        @Override
        public boolean exists() {
            return true;
        }
    }
}
