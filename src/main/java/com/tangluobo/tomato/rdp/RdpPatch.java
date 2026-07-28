package com.tangluobo.tomato.rdp;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sshtools.javardp.IContext;
import com.sshtools.javardp.OrderException;
import com.sshtools.javardp.RdesktopException;
import com.sshtools.javardp.SecurityType;
import com.sshtools.javardp.State;
import com.sshtools.javardp.layers.Rdp;
import com.sshtools.javardp.rdp5.VChannels;

/**
 * 修复版RDP层，覆盖rdp5_process方法并添加诊断日志。
 *
 * 修复：
 * - SSL/HYBRID模式下忽略encryption标志（TLS已处理加密）
 * - STANDARD模式下将解密后的数据正确写回Packet缓冲区
 * - 诊断日志追踪bitmap更新处理
 */
public class RdpPatch extends Rdp {

    private static final Logger logger = Logger.getLogger(RdpPatch.class.getName());

    private final State stateRef;
    private final AtomicInteger bitmapUpdateCount = new AtomicInteger(0);
    private final AtomicInteger rdp5PacketCount = new AtomicInteger(0);

    public RdpPatch(IContext context, State state, VChannels channels) {
        super(context, state, channels);
        this.stateRef = state;
    }

    @Override
    public void rdp5_process(com.sshtools.javardp.Packet s, boolean encryption, boolean shortform)
            throws RdesktopException, OrderException {
        int pktNum = rdp5PacketCount.incrementAndGet();
        boolean isSSL = stateRef.getSecurityType() == SecurityType.SSL
                || stateRef.getSecurityType() == SecurityType.HYBRID;

        logger.info(String.format("[RDP5 #%d] encryption=%b, shortform=%b, securityType=%s, packetSize=%d",
                pktNum, encryption, shortform, stateRef.getSecurityType(), s.getEnd() - s.getPosition()));

        // 修复：对于SSL/HYBRID安全类型，忽略RDP5包头中的encryption标志
        if (encryption && isSSL) {
            logger.info("[RDP5] Ignoring encryption flag for SSL/HYBRID security type");
            encryption = false;
        }

        int length, count;
        int type;
        int next;

        if (encryption) {
            s.incrementPosition(shortform ? 6 : 7); // signature
            byte[] data = new byte[s.size() - s.getPosition()];
            s.copyToByteArray(data, 0, s.getPosition(), data.length);
            byte[] packet = secureLayer.decrypt(data);
            // 修复：将解密后的数据写回Packet缓冲区（原始代码缺少这一步）
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
                s.incrementPosition(2); // part length
                processBitmapUpdates(s);
                break;
            case 2: // palette
                s.incrementPosition(2);
                processPalette(s);
                break;
            case 3: // palette with offset
                break;
            case 5:
                process_null_system_pointer_pdu(s);
                break;
            case 6: // default pointer
                break;
            case 9:
                process_colour_pointer_pdu(s);
                break;
            case 10:
                process_cached_pointer_pdu(s);
                break;
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
        logger.info(String.format("[BITMAP UPDATE #%d] n_updates=%d, remainingBytes=%d",
                count, n_updates, data.getEnd() - data.getPosition()));
        // 调用父类处理
        data.setPosition(pos);
        super.processBitmapUpdates(data);

        // 诊断：采样BufferedImage确认是否写入
        try {
            java.awt.image.BufferedImage bi = stateRef.getCanvas().getDisplay().getBufferedImage();
            if (bi != null) {
                int w = bi.getWidth(), h = bi.getHeight();
                int[] xs = {0, w / 2, w - 1};
                int[] ys = {0, h / 2, h - 1};
                StringBuilder sb = new StringBuilder("[BITMAP #" + count + "] BufferedImage(" + w + "x" + h + "):");
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
        logger.info("[CONNECT] Starting RDP connection, securityType=" + stateRef.getSecurityType());
        super.connect(io, credentialProvider, command, directory);
        logger.info("[CONNECT] RDP connect completed, securityType=" + stateRef.getSecurityType()
                + ", licenceIssued=" + stateRef.isLicenceIssued()
                + ", serverBpp=" + stateRef.getServerBpp());
    }

    @Override
    public void mainLoop() throws IOException, RdesktopException {
        logger.info("[MAINLOOP] Entering main loop");
        try {
            super.mainLoop();
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format("[MAINLOOP] Error after %d bitmap updates, %d rdp5 packets: %s",
                    bitmapUpdateCount.get(), rdp5PacketCount.get(), e.getMessage()));
            throw e;
        }
        logger.info(String.format("[MAINLOOP] Exited normally. Total: %d bitmap updates, %d rdp5 packets",
                bitmapUpdateCount.get(), rdp5PacketCount.get()));
    }

    /**
     * 获取已处理的bitmap更新数量（用于诊断）
     */
    public int getBitmapUpdateCount() {
        return bitmapUpdateCount.get();
    }

    /**
     * 获取已处理的RDP5包数量（用于诊断）
     */
    public int getRdp5PacketCount() {
        return rdp5PacketCount.get();
    }
}
