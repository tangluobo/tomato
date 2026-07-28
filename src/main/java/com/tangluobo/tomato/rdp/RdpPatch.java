package com.tangluobo.tomato.rdp;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sshtools.javardp.IContext;
import com.sshtools.javardp.OrderException;
import com.sshtools.javardp.RdesktopDisconnectException;
import com.sshtools.javardp.RdesktopException;
import com.sshtools.javardp.SecurityType;
import com.sshtools.javardp.State;
import com.sshtools.javardp.layers.Rdp;
import com.sshtools.javardp.rdp5.VChannels;

/**
 * 修复版RDP层，覆盖关键方法添加修复和诊断日志。
 */
public class RdpPatch extends Rdp {

    private static final Logger logger = Logger.getLogger(RdpPatch.class.getName());

    private final State stateRef;
    private final AtomicInteger bitmapUpdateCount = new AtomicInteger(0);
    private final AtomicInteger rdp5PacketCount = new AtomicInteger(0);
    private final AtomicInteger totalPduCount = new AtomicInteger(0);

    // 反射访问Rdp的private方法/字段
    private final Method receiveMethod;
    private final Method processPacketMethod;
    private final Field streamField;
    private final Field nextPacketField;

    public RdpPatch(IContext context, State state, VChannels channels) {
        super(context, state, channels);
        this.stateRef = state;

        // 反射获取private方法和字段
        Method rm = null, pm = null;
        Field sf = null, npf = null;
        try {
            rm = Rdp.class.getDeclaredMethod("receive", int[].class);
            rm.setAccessible(true);
            pm = Rdp.class.getDeclaredMethod("processPacket", int[].class, com.sshtools.javardp.Packet.class);
            pm.setAccessible(true);
            sf = Rdp.class.getDeclaredField("stream");
            sf.setAccessible(true);
            npf = Rdp.class.getDeclaredField("next_packet");
            npf.setAccessible(true);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "反射获取Rdp私有方法/字段失败: " + e.getMessage(), e);
        }
        receiveMethod = rm;
        processPacketMethod = pm;
        streamField = sf;
        nextPacketField = npf;
    }

    @Override
    public void rdp5_process(com.sshtools.javardp.Packet s, boolean encryption, boolean shortform)
            throws RdesktopException, OrderException {
        int pktNum = rdp5PacketCount.incrementAndGet();
        boolean isSSL = stateRef.getSecurityType() == SecurityType.SSL
                || stateRef.getSecurityType() == SecurityType.HYBRID;

        logger.info(String.format("[RDP5 #%d] encryption=%b, shortform=%b, securityType=%s, dataSize=%d",
                pktNum, encryption, shortform, stateRef.getSecurityType(), s.getEnd() - s.getPosition()));

        if (encryption && isSSL) {
            logger.info("[RDP5] Ignoring encryption flag for SSL/HYBRID");
            encryption = false;
        }

        int length, count;
        int type;
        int next;

        if (encryption) {
            s.incrementPosition(shortform ? 6 : 7);
            byte[] data = new byte[s.size() - s.getPosition()];
            s.copyToByteArray(data, 0, s.getPosition(), data.length);
            byte[] packet = secureLayer.decrypt(data);
            if (packet != null) {
                s.copyFromByteArray(packet, 0, s.getPosition(), packet.length);
            }
        }

        while (s.getPosition() < s.getEnd()) {
            type = s.get8();
            length = s.getLittleEndian16();
            next = s.getPosition() + length;
            logger.info(String.format("[RDP5 #%d] sub-type=%d, length=%d", pktNum, type, length));
            switch (type) {
            case 0: // orders
                count = s.getLittleEndian16();
                orders.processOrders(s, next, count);
                break;
            case 1: // bitmap update
                s.incrementPosition(2);
                processBitmapUpdates(s);
                break;
            case 2: // palette
                s.incrementPosition(2);
                processPalette(s);
                break;
            case 3: break;
            case 5: process_null_system_pointer_pdu(s); break;
            case 6: break;
            case 9: process_colour_pointer_pdu(s); break;
            case 10: process_cached_pointer_pdu(s); break;
            default:
                logger.warning("Unimplemented RDP5 opcode " + type);
            }
            s.setPosition(next);
        }
    }

    @Override
    public void rdp5_process(com.sshtools.javardp.Packet s, boolean e)
            throws RdesktopException, OrderException {
        rdp5_process(s, e, false);
    }

    @Override
    protected void processBitmapUpdates(com.sshtools.javardp.Packet data) throws RdesktopException {
        int count = bitmapUpdateCount.incrementAndGet();
        int pos = data.getPosition();
        int n_updates = data.getLittleEndian16();
        logger.info(String.format("[BITMAP UPDATE #%d] n_updates=%d", count, n_updates));
        data.setPosition(pos);
        super.processBitmapUpdates(data);

        try {
            java.awt.image.BufferedImage bi = stateRef.getCanvas().getDisplay().getBufferedImage();
            if (bi != null) {
                int w = bi.getWidth(), h = bi.getHeight();
                int[] xs = {0, w / 2, w - 1};
                int[] ys = {0, h / 2, h - 1};
                StringBuilder sb = new StringBuilder("[BITMAP #" + count + "] pixels(" + w + "x" + h + "):");
                for (int x : xs) for (int y : ys) {
                    sb.append(String.format(" (%d,%d)=%06x", x, y, bi.getRGB(x, y) & 0xFFFFFF));
                }
                logger.info(sb.toString());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "采样BufferedImage失败: " + e.getMessage());
        }
    }

    @Override
    public void connect(com.sshtools.javardp.io.IO io, com.sshtools.javardp.CredentialProvider credentialProvider,
            String command, String directory) throws IOException, RdesktopException {
        logger.info("[CONNECT] Starting, securityType=" + stateRef.getSecurityType()
                + ", rdp5=" + stateRef.isRDP5() + ", bpp=" + stateRef.getServerBpp()
                + ", size=" + stateRef.getWidth() + "x" + stateRef.getHeight());
        super.connect(io, credentialProvider, command, directory);
        logger.info("[CONNECT] Done, securityType=" + stateRef.getSecurityType()
                + ", rdp5=" + stateRef.isRDP5() + ", licenceIssued=" + stateRef.isLicenceIssued()
                + ", serverBpp=" + stateRef.getServerBpp());
    }

    @Override
    public void mainLoop() throws IOException, RdesktopException {
        logger.info("[MAINLOOP] Entering main loop, rdp5=" + stateRef.isRDP5());

        if (receiveMethod == null || processPacketMethod == null) {
            logger.warning("[MAINLOOP] 反射方法不可用，使用父类mainLoop");
            super.mainLoop();
            return;
        }

        boolean inputSyncSent = false;
        int[] type = new int[1];
        com.sshtools.javardp.Packet data;
        while (true) {
            // 调用private receive()
            data = null;
            try {
                logger.info("[MAINLOOP] 等待下一个PDU...");
                data = (com.sshtools.javardp.Packet) receiveMethod.invoke(this, (Object) type);
                if (data == null) {
                    logger.info("[MAINLOOP] receive() returned null, exiting");
                    return;
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof EOFException) {
                    logger.info("[MAINLOOP] EOF, exiting");
                    return;
                }
                if (cause instanceof IOException) {
                    logger.log(Level.SEVERE, "[MAINLOOP] IO error after " + totalPduCount.get() + " PDUs: " + cause.getMessage());
                    if (stateRef.getLastReason() > 0)
                        throw new RdesktopDisconnectException(stateRef.getLastReason());
                    else
                        throw new RdesktopDisconnectException(0, (IOException) cause);
                }
                if (cause instanceof RdesktopException) throw (RdesktopException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RdesktopException("receive failed: " + cause.getMessage(), cause);
            } catch (Exception e) {
                throw new RdesktopException("reflective receive failed: " + e.getMessage(), e);
            }

            int pduCount = totalPduCount.incrementAndGet();
            int pduType = type[0];

            // 诊断：输出PDU数据和stream状态
            String pduName;
            switch (pduType) {
            case 1: pduName = "DEMAND_ACTIVE"; break;
            case 6: pduName = "DEACTIVATE_ALL"; break;
            case 7: pduName = "DATA"; break;
            case 0: pduName = "KEEPALIVE"; break;
            default: pduName = "UNKNOWN(" + pduType + ")"; break;
            }
            int dataAvail = data.getEnd() - data.getPosition();
            logger.info(String.format("[MAINLOOP] PDU #%d: type=%s(%d), dataSize=%d, pos=%d, end=%d",
                    pduCount, pduName, pduType, dataAvail, data.getPosition(), data.getEnd()));

            // 诊断：输出data的前16字节hex
            if (dataAvail > 0) {
                int savePos = data.getPosition();
                int dumpLen = Math.min(dataAvail, 32);
                StringBuilder hexSb = new StringBuilder("[MAINLOOP] PDU #" + pduCount + " hex:");
                for (int i = 0; i < dumpLen; i++) {
                    hexSb.append(String.format(" %02x", data.get8()));
                }
                data.setPosition(savePos);
                logger.info(hexSb.toString());
            }

            // 调用private processPacket()
            try {
                processPacketMethod.invoke(this, (Object) type, data);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RdesktopException) {
                    logger.log(Level.SEVERE, String.format("[MAINLOOP] processPacket error at PDU #%d: %s", pduCount, cause.getMessage()));
                    throw (RdesktopException) cause;
                }
                if (cause instanceof IOException) throw (IOException) cause;
                if (cause instanceof OrderException) throw new RdesktopException(cause.getMessage(), cause);
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RdesktopException("processPacket failed: " + cause.getMessage(), cause);
            } catch (Exception e) {
                throw new RdesktopException("reflective processPacket failed: " + e.getMessage(), e);
            }

            // 诊断：processPacket后stream状态
            try {
                if (streamField != null && nextPacketField != null) {
                    Object stream = streamField.get(this);
                    int nextPkt = nextPacketField.getInt(this);
                    if (stream != null) {
                        com.sshtools.javardp.Packet p = (com.sshtools.javardp.Packet) stream;
                        logger.info(String.format("[MAINLOOP] After PDU #%d: stream pos=%d end=%d, next_packet=%d, remaining=%d",
                                pduCount, p.getPosition(), p.getEnd(), nextPkt, p.getEnd() - nextPkt));
                    }
                }
            } catch (Exception e) {
                // 忽略诊断错误
            }

            // 在DEMAND_ACTIVE（PDU type=1）处理完毕后发送Input Synchronize Event
            if (!inputSyncSent && pduType == 1) {
                inputSyncSent = true;
                try {
                    logger.info("[MAINLOOP] ===== 准备发送Input Synchronize Event =====");
                    this.sendInput(0, 0x0000, 0, 0, 0);
                    logger.info("[MAINLOOP] ===== Input Synchronize Event已发送 =====");
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "[MAINLOOP] ===== 发送Input Synchronize Event失败: " + e.getMessage(), e);
                }
            }
        }
    }

    public int getBitmapUpdateCount() { return bitmapUpdateCount.get(); }
    public int getRdp5PacketCount() { return rdp5PacketCount.get(); }
    public int getTotalPduCount() { return totalPduCount.get(); }
}
