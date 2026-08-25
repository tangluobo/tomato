package com.tangluobo.tomato.rdp.clipboard;

import java.awt.Toolkit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sshtools.javardp.Packet;
import com.sshtools.javardp.RdesktopException;
import com.sshtools.javardp.rdp5.cliprdr.ClipChannel;
import com.sshtools.javardp.rdp5.cliprdr.TextHandler;
import com.sshtools.javardp.rdp5.cliprdr.TypeHandler;

/**
 * 修复版剪贴板虚拟通道（cliprdr）。
 *
 * 修复javardp库ClipChannel在嵌入式场景下的三个问题：
 *
 * 1. clipboard字段未初始化：库的标准入口（RdesktopFrame）才会调用
 *    setClipboard(系统剪贴板)，嵌入式使用时clipboard为null，
 *    focusGained→send_format_announce抛NPE，
 *    导致本地剪贴板格式永远无法通告给远程端（远程Ctrl+V无数据可贴）。
 *    本类构造时立即设置系统剪贴板。
 *
 * 2. UnicodeHandler编码bug（中文乱码）：见{@link FixedUnicodeHandler}，
 *    构造时通过反射替换allHandlers中的bug版实现。
 *
 * 3. 远程→本地格式选择：库的handle_clip_format_announce按远程通告顺序
 *    取第一个handler，Windows通常CF_TEXT在前，TextHandler按Latin-1逐字节
 *    解码导致中文乱码。本类覆盖process()，优先选择CF_UNICODETEXT。
 */
public class FixedClipChannel extends ClipChannel {

    private static final Logger logger = Logger.getLogger(FixedClipChannel.class.getName());

    /** CLIPRDR消息类型（MS-RDPECLIP 2.2.1） */
    private static final int CLIPRDR_CONNECT = 1;
    private static final int CLIPRDR_FORMAT_ANNOUNCE = 2;
    private static final int CLIPRDR_FORMAT_ACK = 3;
    private static final int CLIPRDR_DATA_REQUEST = 4;
    private static final int CLIPRDR_DATA_RESPONSE = 5;
    /** FORMAT_ACK中MSG_FLAGS_MONITOR_READY标志 */
    private static final int MSG_FLAGS_MONITOR_READY = 2;

    /** CF_UNICODETEXT 标准格式ID */
    private static final int CF_UNICODETEXT = 13;
    /** CF_TEXT 标准格式ID */
    private static final int CF_TEXT = 1;

    private final FixedUnicodeHandler unicodeHandler = new FixedUnicodeHandler();

    public FixedClipChannel() {
        super();
        // 修复1：立即绑定系统剪贴板（原库在嵌入式场景下为null导致通告NPE）
        setClipboard(Toolkit.getDefaultToolkit().getSystemClipboard());
        // 修复2：替换库内bug版UnicodeHandler
        replaceUnicodeHandler();
    }

    /**
     * 反射替换allHandlers中的UnicodeHandler为修复版（保持原注册位置）。
     * allHandlers为package-private字段，TypeHandlerList.handlers为private字段。
     */
    @SuppressWarnings("unchecked")
    private void replaceUnicodeHandler() {
        try {
            Field allField = ClipChannel.class.getDeclaredField("allHandlers");
            allField.setAccessible(true);
            Object allHandlers = allField.get(this);
            if (allHandlers == null) {
                logger.warning("allHandlers未初始化，跳过UnicodeHandler替换");
                return;
            }
            Field listField = allHandlers.getClass().getDeclaredField("handlers");
            listField.setAccessible(true);
            List<TypeHandler> handlers = (List<TypeHandler>) listField.get(allHandlers);
            List<TypeHandler> replaced = new ArrayList<>();
            boolean replacedFlag = false;
            for (TypeHandler h : handlers) {
                if (h instanceof com.sshtools.javardp.rdp5.cliprdr.UnicodeHandler) {
                    replaced.add(unicodeHandler);
                    replacedFlag = true;
                } else {
                    replaced.add(h);
                }
            }
            if (replacedFlag) {
                handlers.clear();
                handlers.addAll(replaced);
                logger.info("UnicodeHandler已替换为UTF-16LE修复版");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "替换UnicodeHandler失败: " + e.getMessage(), e);
        }
    }

    /**
     * 覆盖消息分发：
     * - FORMAT_ANNOUNCE（msgType=2）自行处理，优先CF_UNICODETEXT，避免TextHandler的Latin-1解码乱码；
     * - 其余消息反射调用父类package-private方法，保持库原行为。
     */
    @Override
    public void process(Packet packet) throws RdesktopException, java.io.IOException {
        int msgType = packet.getLittleEndian16();
        int msgFlags = packet.getLittleEndian16();
        int dataLen = packet.getLittleEndian32();

        if (msgFlags == MSG_FLAGS_MONITOR_READY && msgType == CLIPRDR_FORMAT_ACK) {
            // 服务器监视器就绪：发送初始格式通告（本地剪贴板格式告知远程）
            invokePrivate("send_format_announce");
            return;
        }

        switch (msgType) {
            case CLIPRDR_CONNECT:
                invokePrivate("send_format_announce");
                break;
            case CLIPRDR_FORMAT_ANNOUNCE:
                handleFormatAnnounce(packet, dataLen);
                break;
            case CLIPRDR_DATA_REQUEST:
                invokePrivate("handle_data_request", packet);
                break;
            case CLIPRDR_DATA_RESPONSE:
                invokePrivate("handle_data_response", packet, dataLen);
                break;
            default:
                // FORMAT_ACK / CLIPRDR_ERROR等：忽略（与库行为一致）
                break;
        }
    }

    /**
     * 远端剪贴板格式通告处理（远程Ctrl+C后触发）。
     *
     * 与库的handle_clip_format_announce差异：优先请求CF_UNICODETEXT，
     * 无Unicode格式时回退CF_TEXT，避免库按通告顺序选中TextHandler
     * 逐字节Latin-1解码导致的中文乱码。
     */
    private void handleFormatAnnounce(Packet packet, int dataLen) {
        try {
            List<Integer> formats = new ArrayList<>();
            int len = dataLen;
            // 每个格式项36字节：formatId(4) + 格式名(32)
            while (len >= 36) {
                formats.add(packet.getLittleEndian32());
                packet.incrementPosition(32);
                len -= 36;
            }
            // 回复FORMAT_ACK
            send_null(CLIPRDR_FORMAT_ACK, 1);
            // 优先选择Unicode格式
            TypeHandler chosen = null;
            if (formats.contains(CF_UNICODETEXT)) {
                chosen = unicodeHandler;
            } else if (formats.contains(CF_TEXT)) {
                chosen = new TextHandler();
            }
            if (chosen != null) {
                setField("currentHandler", chosen);
                invokePrivate("request_clipboard_data", chosen.preferredFormat());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "处理远程剪贴板格式通告失败: " + e.getMessage(), e);
        }
    }

    /**
     * 反射调用父类package-private方法（按名称和参数个数匹配）。
     * 反射异常统一包装为RdesktopException以匹配process()签名。
     */
    private void invokePrivate(String methodName, Object... args) throws RdesktopException {
        try {
            for (Method m : ClipChannel.class.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    m.invoke(this, args);
                    return;
                }
            }
            throw new NoSuchMethodException(methodName);
        } catch (Exception e) {
            if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
                Throwable cause = e.getCause();
                if (cause instanceof RdesktopException) {
                    throw (RdesktopException) cause;
                }
                if (cause instanceof java.io.IOException) {
                    throw new RdesktopException("剪贴板通道处理失败: " + cause.getMessage(), cause);
                }
            }
            throw new RdesktopException("反射调用剪贴板方法失败: " + methodName + ": " + e.getMessage(), e);
        }
    }

    /**
     * 反射设置父类package-private字段。
     */
    private void setField(String fieldName, Object value) throws Exception {
        Field f = ClipChannel.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(this, value);
    }
}
