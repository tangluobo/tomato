package com.tangluobo.tomato.rdp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.tangluobo.tomato.rdp.rdp5.VChannel;
import com.tangluobo.tomato.rdp.rdp5.VChannels;

/**
 * Dynamic virtual-channel transport (MS-RDPEDYC) with the RDP Graphics
 * Pipeline bootstrap required by current GNOME Remote Desktop servers.
 */
final class DrdynvcChannel extends VChannel {

    private static final Logger logger = Logger.getLogger(DrdynvcChannel.class.getName());

    private static final int CMD_CREATE = 0x01;
    private static final int CMD_DATA_FIRST = 0x02;
    private static final int CMD_DATA = 0x03;
    private static final int CMD_CLOSE = 0x04;
    private static final int CMD_CAPABILITY = 0x05;

    private static final String GFX_CHANNEL = "Microsoft::Windows::RDS::Graphics";
    private static final int STATUS_SUCCESS = 0;
    private static final int STATUS_NOT_FOUND = 0xC0000225;
    private static final int RDPGFX_CMDID_CAPS_ADVERTISE = 0x0012;
    private static final int RDPGFX_CMDID_WIRE_TO_SURFACE_2 = 0x0002;
    private static final int RDPGFX_CMDID_CREATE_SURFACE = 0x0009;
    private static final int RDPGFX_CMDID_DELETE_SURFACE = 0x000A;
    private static final int RDPGFX_CMDID_START_FRAME = 0x000B;
    private static final int RDPGFX_CMDID_END_FRAME = 0x000C;
    private static final int RDPGFX_CMDID_FRAME_ACKNOWLEDGE = 0x000D;
    private static final int RDPGFX_CMDID_RESET_GRAPHICS = 0x000E;
    private static final int RDPGFX_CMDID_MAP_SURFACE_TO_OUTPUT = 0x000F;
    private static final int RDPGFX_CMDID_CAPS_CONFIRM = 0x0013;
    private static final int RDPGFX_CAPVERSION_10 = 0x000A0002;
    private static final int RDPGFX_CAPS_FLAG_AVC_DISABLED = 0x00000020;
    private static final int RDPGFX_CODECID_CAPROGRESSIVE = 0x0009;

    private final Runnable onFirstFrame;
    private Rdp8BulkDecompressor bulkDecompressor = new Rdp8BulkDecompressor();
    private final Map<Integer, GfxSurface> surfaces = new HashMap<>();
    private final Set<Integer> dirtySurfaces = new LinkedHashSet<>();
    private int gfxChannelId = -1;
    private int fragmentedChannelId = -1;
    private int fragmentedLength;
    private ByteArrayOutputStream fragmentedData;
    private long totalFramesDecoded;
    private boolean firstFrameDelivered;

    DrdynvcChannel() {
        this(null);
    }

    DrdynvcChannel(Runnable onFirstFrame) {
        this.onFirstFrame = onFirstFrame;
    }

    @Override
    public String name() {
        return "drdynvc";
    }

    @Override
    public int flags() {
        return VChannels.CHANNEL_OPTION_INITIALIZED
                | VChannels.CHANNEL_OPTION_ENCRYPT_RDP;
    }

    @Override
    public void process(Packet data) throws RdesktopException, IOException {
        if (remaining(data) < 1) {
            throw new RdesktopException("drdynvc packet has no header");
        }
        int header = data.get8();
        int command = (header >>> 4) & 0x0F;
        int sp = (header >>> 2) & 0x03;
        int cbChId = header & 0x03;
        switch (command) {
            case CMD_CAPABILITY:
                processCapability(data);
                break;
            case CMD_CREATE:
                processCreate(data, cbChId);
                break;
            case CMD_DATA_FIRST:
                processDataFirst(data, sp, cbChId);
                break;
            case CMD_DATA:
                processData(data, cbChId);
                break;
            case CMD_CLOSE:
                processClose(data, cbChId);
                break;
            default:
                logger.warning("drdynvc: unsupported command=" + command);
                break;
        }
    }

    private void processCapability(Packet data) throws RdesktopException, IOException {
        if (remaining(data) < 3) {
            throw new RdesktopException("truncated drdynvc capability request");
        }
        data.get8(); // pad
        int offeredVersion = data.getLittleEndian16();
        int version = Math.max(1, Math.min(offeredVersion, 3));
        Packet response = new Packet(4);
        response.set8(0x50);
        response.set8(0);
        response.setLittleEndian16(version);
        response.markEnd();
        send_packet(response);
        logger.info("drdynvc: capability version=" + version);
    }

    private void processCreate(Packet data, int cbChId) throws RdesktopException, IOException {
        int channelId = readVariableUInt(data, cbChId);
        ByteArrayOutputStream nameBytes = new ByteArrayOutputStream();
        boolean terminated = false;
        while (remaining(data) > 0) {
            int value = data.get8();
            if (value == 0) {
                terminated = true;
                break;
            }
            nameBytes.write(value);
        }
        if (!terminated) {
            throw new RdesktopException("unterminated drdynvc channel name");
        }
        String channelName = nameBytes.toString(StandardCharsets.US_ASCII);
        boolean accepted = GFX_CHANNEL.equals(channelName);

        int idBytes = variableUIntBytes(cbChId);
        Packet response = new Packet(1 + idBytes + 4);
        response.set8((CMD_CREATE << 4) | cbChId);
        writeVariableUInt(response, cbChId, channelId);
        response.setLittleEndian32(accepted ? STATUS_SUCCESS : STATUS_NOT_FOUND);
        response.markEnd();
        send_packet(response);

        logger.info("drdynvc: create name=" + channelName + ", id=" + channelId
                + ", accepted=" + accepted);
        if (accepted) {
            gfxChannelId = channelId;
            bulkDecompressor = new Rdp8BulkDecompressor();
            sendGfxCapabilities();
        }
    }

    private void processDataFirst(Packet data, int sp, int cbChId) throws RdesktopException, IOException {
        int channelId = readVariableUInt(data, cbChId);
        int totalLength = readVariableUInt(data, sp);
        fragmentedChannelId = channelId;
        fragmentedLength = totalLength;
        fragmentedData = new ByteArrayOutputStream(totalLength);
        appendRemaining(data, fragmentedData);
        finishFragmentIfComplete();
    }

    private void processData(Packet data, int cbChId) throws RdesktopException, IOException {
        int channelId = readVariableUInt(data, cbChId);
        if (fragmentedData != null) {
            if (channelId != fragmentedChannelId) {
                throw new RdesktopException("interleaved drdynvc fragments are not supported");
            }
            appendRemaining(data, fragmentedData);
            finishFragmentIfComplete();
            return;
        }
        byte[] payload = readRemaining(data);
        dispatchDynamicData(channelId, payload);
    }

    private void processClose(Packet data, int cbChId) throws RdesktopException, IOException {
        int channelId = readVariableUInt(data, cbChId);
        Packet response = new Packet(1 + variableUIntBytes(cbChId));
        response.set8((CMD_CLOSE << 4) | cbChId);
        writeVariableUInt(response, cbChId, channelId);
        response.markEnd();
        send_packet(response);
        if (channelId == gfxChannelId) {
            gfxChannelId = -1;
            bulkDecompressor = new Rdp8BulkDecompressor();
            surfaces.clear();
            dirtySurfaces.clear();
        }
    }

    private void sendGfxCapabilities() throws RdesktopException, IOException {
        // Version 10 with AVC disabled requests a non-H.264 graphics codec,
        // which can be decoded without a native multimedia dependency.
        Packet gfx = new Packet(22);
        gfx.setLittleEndian16(RDPGFX_CMDID_CAPS_ADVERTISE);
        gfx.setLittleEndian16(0);
        gfx.setLittleEndian32(22);
        gfx.setLittleEndian16(1);
        gfx.setLittleEndian32(RDPGFX_CAPVERSION_10);
        gfx.setLittleEndian32(4);
        gfx.setLittleEndian32(RDPGFX_CAPS_FLAG_AVC_DISABLED);
        gfx.markEnd();
        sendDynamicData(gfxChannelId, readRemainingFromStart(gfx));
        logger.info("rdpgfx: advertised version 10 (AVC disabled)");
    }

    private void sendDynamicData(int channelId, byte[] payload) throws RdesktopException, IOException {
        int cbChId = variableUIntCode(channelId);
        Packet packet = new Packet(1 + variableUIntBytes(cbChId) + payload.length);
        packet.set8((CMD_DATA << 4) | cbChId);
        writeVariableUInt(packet, cbChId, channelId);
        packet.copyFromByteArray(payload, 0, packet.getPosition(), payload.length);
        packet.incrementPosition(payload.length);
        packet.markEnd();
        send_packet(packet);
    }

    private void dispatchDynamicData(int channelId, byte[] payload) throws RdesktopException, IOException {
        if (channelId != gfxChannelId) {
            logger.fine("drdynvc: data id=" + channelId + ", bytes=" + payload.length);
            return;
        }
        int descriptor = payload.length == 0 ? -1 : payload[0] & 0xFF;
        byte[] decoded = (descriptor == 0xE0 || descriptor == 0xE1)
                ? bulkDecompressor.decompress(payload)
                : payload;
        if (decoded.length == 0) {
            throw new RdesktopException("RDP8解压后没有RDPGFX数据");
        }
        int offset = 0;
        while (offset < decoded.length) {
            require(decoded, offset, 8, "RDPGFX头");
            int command = u16(decoded, offset);
            long unsignedLength = u32(decoded, offset + 4);
            if (unsignedLength < 8 || unsignedLength > decoded.length - offset) {
                throw new RdesktopException("RDPGFX PDU长度无效: " + unsignedLength);
            }
            int length = (int) unsignedLength;
            processGfxPdu(command, decoded, offset, length);
            offset += length;
        }
    }

    private void processGfxPdu(int command, byte[] pdu, int offset, int length)
            throws RdesktopException, IOException {
        switch (command) {
            case RDPGFX_CMDID_CAPS_CONFIRM:
                logger.info("rdpgfx: 服务端已确认图形能力");
                break;
            case RDPGFX_CMDID_RESET_GRAPHICS:
                processResetGraphics(pdu, offset, length);
                break;
            case RDPGFX_CMDID_CREATE_SURFACE:
                processCreateSurface(pdu, offset, length);
                break;
            case RDPGFX_CMDID_DELETE_SURFACE:
                processDeleteSurface(pdu, offset, length);
                break;
            case RDPGFX_CMDID_MAP_SURFACE_TO_OUTPUT:
                processMapSurfaceToOutput(pdu, offset, length);
                break;
            case RDPGFX_CMDID_START_FRAME:
                requirePdu(length, 16, "START_FRAME");
                break;
            case RDPGFX_CMDID_WIRE_TO_SURFACE_2:
                processWireToSurface2(pdu, offset, length);
                break;
            case RDPGFX_CMDID_END_FRAME:
                processEndFrame(pdu, offset, length);
                break;
            default:
                logger.fine("rdpgfx: 忽略未使用的命令=0x" + Integer.toHexString(command)
                        + ", bytes=" + length);
                break;
        }
    }

    private void processResetGraphics(byte[] pdu, int offset, int length) throws RdesktopException {
        requirePdu(length, 20, "RESET_GRAPHICS");
        int width = checkedDimension(u32(pdu, offset + 8), "宽度");
        int height = checkedDimension(u32(pdu, offset + 12), "高度");
        surfaces.clear();
        dirtySurfaces.clear();
        if (state != null && state.getCanvas() != null
                && (state.getWidth() != width || state.getHeight() != height)) {
            state.setWidth(width);
            state.setHeight(height);
            state.getCanvas().backingStoreResize(width, height, false);
        }
        logger.info("rdpgfx: 重置桌面=" + width + "x" + height);
    }

    private void processCreateSurface(byte[] pdu, int offset, int length) throws RdesktopException {
        requirePdu(length, 15, "CREATE_SURFACE");
        int id = u16(pdu, offset + 8);
        int width = u16(pdu, offset + 10);
        int height = u16(pdu, offset + 12);
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE) {
            throw new RdesktopException("RDPGFX图面尺寸无效: " + width + "x" + height);
        }
        surfaces.put(id, new GfxSurface(width, height));
        logger.info("rdpgfx: 创建图面 id=" + id + ", size=" + width + "x" + height);
    }

    private void processDeleteSurface(byte[] pdu, int offset, int length) throws RdesktopException {
        requirePdu(length, 10, "DELETE_SURFACE");
        int id = u16(pdu, offset + 8);
        surfaces.remove(id);
        dirtySurfaces.remove(id);
    }

    private void processMapSurfaceToOutput(byte[] pdu, int offset, int length) throws RdesktopException {
        requirePdu(length, 20, "MAP_SURFACE_TO_OUTPUT");
        int id = u16(pdu, offset + 8);
        GfxSurface surface = surface(id);
        surface.outputX = (int) u32(pdu, offset + 12);
        surface.outputY = (int) u32(pdu, offset + 16);
        surface.mapped = true;
        logger.info("rdpgfx: 映射图面 id=" + id + " -> " + surface.outputX + "," + surface.outputY);
    }

    private void processWireToSurface2(byte[] pdu, int offset, int length) throws RdesktopException {
        requirePdu(length, 21, "WIRE_TO_SURFACE_2");
        int surfaceId = u16(pdu, offset + 8);
        int codecId = u16(pdu, offset + 10);
        long bitmapLength = u32(pdu, offset + 17);
        if (bitmapLength > length - 21) {
            throw new RdesktopException("RDPGFX位图数据长度越界: " + bitmapLength);
        }
        if (codecId != RDPGFX_CODECID_CAPROGRESSIVE) {
            throw new RdesktopException("不支持的RDPGFX位图编码: " + codecId);
        }
        GfxSurface surface = surface(surfaceId);
        byte[] bitmapData = Arrays.copyOfRange(pdu, offset + 21, offset + 21 + (int) bitmapLength);
        int tileCount = 0;
        for (RfxProgressiveDecoder.Region region : surface.progressiveDecoder.decode(bitmapData)) {
            for (RfxProgressiveDecoder.Tile tile : region.tiles()) {
                surface.writeTile(tile);
                tileCount++;
            }
        }
        if (tileCount > 0) {
            dirtySurfaces.add(surfaceId);
            logger.fine("rdpgfx: 解码Progressive图块=" + tileCount + ", surface=" + surfaceId);
        }
    }

    private void processEndFrame(byte[] pdu, int offset, int length) throws RdesktopException, IOException {
        requirePdu(length, 12, "END_FRAME");
        int frameId = (int) u32(pdu, offset + 8);
        boolean rendered = renderDirtySurfaces();
        totalFramesDecoded++;
        sendFrameAcknowledge(frameId);
        if (rendered && !firstFrameDelivered) {
            firstFrameDelivered = true;
            if (onFirstFrame != null) {
                onFirstFrame.run();
            }
        }
    }

    private boolean renderDirtySurfaces() throws RdesktopException {
        if (state == null || state.getCanvas() == null || dirtySurfaces.isEmpty()) {
            return false;
        }
        boolean rendered = false;
        for (Integer id : dirtySurfaces) {
            GfxSurface surface = surfaces.get(id);
            if (surface == null || !surface.mapped) continue;
            int sourceX = Math.max(0, -surface.outputX);
            int sourceY = Math.max(0, -surface.outputY);
            int destinationX = Math.max(0, surface.outputX);
            int destinationY = Math.max(0, surface.outputY);
            int width = Math.min(surface.width - sourceX, state.getWidth() - destinationX);
            int height = Math.min(surface.height - sourceY, state.getHeight() - destinationY);
            if (width <= 0 || height <= 0) continue;

            int[] pixels;
            if (sourceX == 0 && sourceY == 0 && width == surface.width) {
                pixels = surface.pixels;
            } else {
                pixels = new int[width * height];
                for (int row = 0; row < height; row++) {
                    System.arraycopy(surface.pixels, (sourceY + row) * surface.width + sourceX,
                            pixels, row * width, width);
                }
            }
            state.getCanvas().displayImage(pixels, width, height,
                    destinationX, destinationY, width, height);
            state.getCanvas().getDisplay().repaint(destinationX, destinationY, width, height);
            rendered = true;
        }
        dirtySurfaces.clear();
        return rendered;
    }

    private void sendFrameAcknowledge(int frameId) throws RdesktopException, IOException {
        Packet ack = new Packet(20);
        ack.setLittleEndian16(RDPGFX_CMDID_FRAME_ACKNOWLEDGE);
        ack.setLittleEndian16(0);
        ack.setLittleEndian32(20);
        ack.setLittleEndian32(0); // QUEUE_DEPTH_UNAVAILABLE
        ack.setLittleEndian32(frameId);
        ack.setLittleEndian32((int) Math.min(totalFramesDecoded, 0xFFFFFFFFL));
        ack.markEnd();
        sendDynamicData(gfxChannelId, readRemainingFromStart(ack));
    }

    private GfxSurface surface(int id) throws RdesktopException {
        GfxSurface surface = surfaces.get(id);
        if (surface == null) {
            throw new RdesktopException("RDPGFX引用了不存在的图面: " + id);
        }
        return surface;
    }

    private static int checkedDimension(long value, String name) throws RdesktopException {
        if (value <= 0 || value > 32768) {
            throw new RdesktopException("RDPGFX" + name + "无效: " + value);
        }
        return (int) value;
    }

    private static void requirePdu(int actual, int minimum, String name) throws RdesktopException {
        if (actual < minimum) {
            throw new RdesktopException("RDPGFX " + name + "数据过短: " + actual);
        }
    }

    private static void require(byte[] data, int offset, int length, String name) throws RdesktopException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new RdesktopException(name + "数据不完整");
        }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private void finishFragmentIfComplete() throws RdesktopException, IOException {
        if (fragmentedData == null || fragmentedData.size() < fragmentedLength) {
            return;
        }
        if (fragmentedData.size() != fragmentedLength) {
            throw new RdesktopException("drdynvc fragmented message exceeds declared length");
        }
        byte[] payload = fragmentedData.toByteArray();
        int channelId = fragmentedChannelId;
        fragmentedData = null;
        fragmentedChannelId = -1;
        fragmentedLength = 0;
        dispatchDynamicData(channelId, payload);
    }

    private static final class GfxSurface {
        final int width;
        final int height;
        final int[] pixels;
        final RfxProgressiveDecoder progressiveDecoder = new RfxProgressiveDecoder();
        int outputX;
        int outputY;
        boolean mapped;

        GfxSurface(int width, int height) {
            this.width = width;
            this.height = height;
            this.pixels = new int[width * height];
            Arrays.fill(this.pixels, 0xFF000000);
        }

        void writeTile(RfxProgressiveDecoder.Tile tile) {
            int copyWidth = Math.min(64, width - tile.x);
            int copyHeight = Math.min(64, height - tile.y);
            if (copyWidth <= 0 || copyHeight <= 0) return;
            for (int row = 0; row < copyHeight; row++) {
                System.arraycopy(tile.argb, row * 64, pixels,
                        (tile.y + row) * width + tile.x, copyWidth);
            }
        }
    }

    private static int remaining(Packet packet) {
        return packet.getEnd() - packet.getPosition();
    }

    private static int variableUIntBytes(int code) {
        return code == 0 ? 1 : code == 1 ? 2 : 4;
    }

    private static int variableUIntCode(int value) {
        return value <= 0xFF ? 0 : value <= 0xFFFF ? 1 : 2;
    }

    private static int readVariableUInt(Packet packet, int code) throws RdesktopException {
        int bytes = variableUIntBytes(code);
        if (remaining(packet) < bytes) {
            throw new RdesktopException("truncated drdynvc variable integer");
        }
        if (bytes == 1) return packet.get8();
        if (bytes == 2) return packet.getLittleEndian16();
        return packet.getLittleEndian32();
    }

    private static void writeVariableUInt(Packet packet, int code, int value) {
        int bytes = variableUIntBytes(code);
        if (bytes == 1) packet.set8(value);
        else if (bytes == 2) packet.setLittleEndian16(value);
        else packet.setLittleEndian32(value);
    }

    private static void appendRemaining(Packet packet, ByteArrayOutputStream output) {
        byte[] value = readRemaining(packet);
        output.write(value, 0, value.length);
    }

    private static byte[] readRemaining(Packet packet) {
        int length = Math.max(remaining(packet), 0);
        byte[] value = new byte[length];
        packet.copyToByteArray(value, 0, packet.getPosition(), length);
        packet.incrementPosition(length);
        return value;
    }

    private static byte[] readRemainingFromStart(Packet packet) {
        byte[] value = new byte[packet.getEnd()];
        packet.copyToByteArray(value, 0, 0, value.length);
        return value;
    }
}
