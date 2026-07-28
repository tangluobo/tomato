package com.tangluobo.tomato.rdp;

import java.util.logging.Logger;

import com.sshtools.javardp.IContext;
import com.sshtools.javardp.OrderException;
import com.sshtools.javardp.RdesktopException;
import com.sshtools.javardp.SecurityType;
import com.sshtools.javardp.State;
import com.sshtools.javardp.layers.Rdp;
import com.sshtools.javardp.rdp5.VChannels;

/**
 * 修复版RDP层，覆盖rdp5_process方法。
 *
 * 原始Rdp.rdp5_process存在两个bug：
 * 1. 当encryption=true时，secureLayer.decrypt(data)的结果被丢弃，
 *    未通过copyFromByteArray写回包缓冲区，导致后续用加密数据解析，产生乱码或全黑画面。
 * 2. 对于SSL安全类型，RDP5包头的encryption标志（0x80位）可能被服务器错误设置，
 *    但TLS已经处理了加密，RDP层不需要再解密。跳过7字节"签名"偏移会导致数据解析错位。
 *
 * 修复：
 * - SSL/HYBRID模式下忽略encryption标志（TLS已处理加密）
 * - STANDARD模式下将解密后的数据正确写回Packet缓冲区
 */
public class RdpPatch extends Rdp {

    private static final Logger logger = Logger.getLogger(RdpPatch.class.getName());

    private final State stateRef;

    public RdpPatch(IContext context, State state, VChannels channels) {
        super(context, state, channels);
        this.stateRef = state;
    }

    @Override
    public void rdp5_process(com.sshtools.javardp.Packet s, boolean encryption, boolean shortform)
            throws RdesktopException, OrderException {
        logger.fine("Processing RDP 5 order (patched), encryption=" + encryption);
        int length, count;
        int type;
        int next;

        // 修复：对于SSL/HYBRID安全类型，忽略RDP5包头中的encryption标志
        // TLS已经处理了加密，RDP层不需要额外解密。跳过7字节签名偏移会导致数据错位。
        boolean isSSL = stateRef.getSecurityType() == SecurityType.SSL
                || stateRef.getSecurityType() == SecurityType.HYBRID;
        if (encryption && isSSL) {
            logger.fine("RDP5 encryption flag ignored for SSL/HYBRID security type");
            encryption = false;
        }

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
            logger.fine("RDP5: type = " + type);
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
}
