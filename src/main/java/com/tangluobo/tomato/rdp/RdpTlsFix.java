package com.tangluobo.tomato.rdp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;

import com.sshtools.javardp.RdesktopException;
import com.sshtools.javardp.State;
import com.sshtools.javardp.io.IO;
import com.sshtools.javardp.io.IOSocket;
import com.sshtools.javardp.io.SocketIO;
import com.sshtools.javardp.layers.ISO;
import com.sshtools.javardp.layers.Transport;

/**
 * 修复sshtools rdp库的TLS兼容性问题。
 * 
 * 根本原因：
 * Transport.negotiateSSL()调用SSLContext.getInstance("TLS")自建SSLContext，
 * 然后只调用sslSocket.setEnabledCipherSuites(CIPHERS)设置密码套件，
 * 但从未调用sslSocket.setEnabledProtocols()限制协议版本。
 * 
 * Java 17+默认启用TLS 1.3，SSLSocket会尝试TLS 1.3协商，
 * 而Windows RDP服务器不支持TLS 1.3，直接重置连接（Connection Reset）。
 * 
 * 修复方案（分层防御）：
 * 1. 创建RdpTransport子类覆盖negotiateSSL()，显式设置TLS 1.2协议
 * 2. 原地修改Transport.CIPHERS为现代TLS 1.2密码套件（排除TLS 1.3套件）
 * 3. 设置jdk.tls.client.protocols系统属性和Security属性限制TLS 1.2
 * 4. 设置JVM默认SSLContext使用宽松TrustManager
 */
public class RdpTlsFix {

    private static final Logger logger = Logger.getLogger(RdpTlsFix.class.getName());

    private static volatile boolean applied = false;

    /**
     * 仅TLS 1.2兼容密码套件（优先级从高到低）。
     * 
     * 严格排除TLS 1.3密码套件（TLS_AES_*, TLS_CHACHA20_POLY1305_SHA256），
     * 因为Transport.negotiateSSL()自建SSLContext，即使CIPHERS不包含TLS 1.3套件，
     * 如果SSLSocket的enabledProtocols包含TLSv1.3，握手仍会尝试TLS 1.3。
     */
    private static final String[] MODERN_CIPHERS = {
            // ECDHE + GCM (TLS 1.2, 现代Windows RDP首选)
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            // ECDHE + CHACHA20 (TLS 1.2)
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            // DHE + GCM (TLS 1.2)
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256",
            // ECDHE + CBC (TLS 1.2)
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
            // ECDHE + CBC SHA (TLS 1.2 兼容)
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            // DHE + CBC (TLS 1.2 兼容)
            "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256",
            "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256",
            "TLS_DHE_DSS_WITH_AES_256_CBC_SHA",
            "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
            // RSA + GCM/CBC (兼容旧服务器)
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_CBC_SHA256",
            "TLS_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
    };

    /**
     * Transport子类，覆盖negotiateSSL()强制使用TLS 1.2。
     * 
     * Transport.negotiateSSL()的关键缺陷是只调用setEnabledCipherSuites()
     * 而不调用setEnabledProtocols()，导致SSLSocket默认尝试TLS 1.3协商。
     * Windows RDP服务器不支持TLS 1.3，直接重置连接。
     */
    public static class RdpTransport extends Transport {

        public RdpTransport(State state, ISO iso) {
            super(state, iso);
        }

        @Override
        protected IO negotiateSSL(IO io) throws Exception {
            java.net.Socket socket = new IOSocket(io);
            X509TrustManager tm = getState().getOptions().getTrustManager() == null
                    ? createDefaultTrustManager()
                    : getState().getOptions().getTrustManager();

            // 使用TLSv1.2创建SSLContext，而非"TLS"（后者默认包含TLS 1.3）
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, new X509TrustManager[]{tm}, null);
            javax.net.ssl.SSLSocketFactory socketFactory = sc.getSocketFactory();

            logger.info("Initialising SSL (TLS 1.2 forced)");
            SSLSocket sslSocket = (SSLSocket) socketFactory.createSocket(
                    socket, socket.getInetAddress().getHostName(),
                    socket.getPort(), true);

            // 关键修复：显式限制协议版本为TLS 1.2
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.2"});

            // 设置现代密码套件（仅TLS 1.2兼容套件）
            sslSocket.setEnabledCipherSuites(CIPHERS);

            logger.info("Starting SSL handshake (TLS 1.2, " + CIPHERS.length + " cipher suites)");
            sslSocket.startHandshake();
            logger.info("Completed SSL handshake");

            return new SocketIO(sslSocket);
        }

        /**
         * 复制Transport的createDefaultTrustManager逻辑。
         * 基类中该方法是private，子类无法访问，需要重新实现。
         */
        private X509TrustManager createDefaultTrustManager() {
            return new X509TrustManager() {
                private final java.util.LinkedList<java.security.cert.X509Certificate> listCert = new java.util.LinkedList<>();

                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] arg0, String arg1)
                        throws java.security.cert.CertificateException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                    for (java.security.cert.X509Certificate cert : chain) {
                        listCert.add(cert);
                    }
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return listCert.toArray(new java.security.cert.X509Certificate[0]);
                }
            };
        }
    }

    /**
     * 应用RDP TLS兼容性修复。只需调用一次。
     */
    public static synchronized void apply(X509TrustManager trustManager) {
        if (applied) return;

        try {
            // 1. 限制TLS协议版本为1.2（必须在创建SSLContext之前设置）
            enableRdpTlsCiphers();

            // 2. 修复Transport.CIPHERS字段（仅TLS 1.2密码套件）
            fixTransportCiphers();

            // 3. 设置系统属性（分层防御，影响所有SSLContext.getInstance("TLS")）
            System.setProperty("jdk.tls.client.protocols", "TLSv1.2");
            logger.info("已设置系统属性jdk.tls.client.protocols=TLSv1.2");

            // 4. 设置JVM默认SSLContext使用宽松TrustManager
            fixDefaultSslContext(trustManager);

            applied = true;
            logger.info("RDP TLS兼容性修复已应用");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "RDP TLS修复失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过反射替换ISO对象中的transport字段为RdpTransport实例。
     * 
     * 这是最核心的修复：RdpTransport覆盖了negotiateSSL()方法，
     * 使用SSLContext.getInstance("TLSv1.2")并显式设置setEnabledProtocols，
     * 确保SSL握手只使用TLS 1.2协议，避免Windows RDP服务器的Connection Reset。
     * 
     * @param rdpLayer RDP层对象，用于导航到ISO层
     */
    public static void injectRdpTransport(Object rdpLayer) {
        try {
            // 导航路径: RdpPatch(Rdp) → secureLayer → mcsLayer → isoLayer → transport
            // 1. 获取secureLayer字段（Rdp类中声明为protected）
            Field secureField = rdpLayer.getClass().getSuperclass().getDeclaredField("secureLayer");
            secureField.setAccessible(true);
            Object secureLayer = secureField.get(rdpLayer);

            // 2. 获取mcsLayer字段（Secure类）
            Field mcsField = secureLayer.getClass().getDeclaredField("mcsLayer");
            mcsField.setAccessible(true);
            Object mcsLayer = mcsField.get(secureLayer);

            // 3. 获取isoLayer字段（MCS类）
            Field isoField = mcsLayer.getClass().getDeclaredField("isoLayer");
            isoField.setAccessible(true);
            Object isoLayer = isoField.get(mcsLayer);

            // 4. 获取transport字段（ISO类）
            Field transportField = isoLayer.getClass().getDeclaredField("transport");
            transportField.setAccessible(true);
            Transport originalTransport = (Transport) transportField.get(isoLayer);

            // 5. 创建RdpTransport并替换
            State state = originalTransport.getState();
            RdpTransport rdpTransport = new RdpTransport(state, (ISO) isoLayer);
            transportField.set(isoLayer, rdpTransport);

            logger.info("已替换Transport为RdpTransport（强制TLS 1.2协议）");
        } catch (NoSuchFieldException e) {
            logger.log(Level.WARNING, "反射替换Transport失败（字段不存在）: " + e.getMessage()
                    + "，将依赖系统属性限制TLS 1.2");
        } catch (IllegalAccessException e) {
            logger.log(Level.WARNING, "反射替换Transport失败（访问被拒）: " + e.getMessage()
                    + "，将依赖系统属性限制TLS 1.2");
        } catch (Exception e) {
            logger.log(Level.WARNING, "反射替换Transport失败: " + e.getMessage()
                    + "，将依赖系统属性限制TLS 1.2");
        }
    }

    /**
     * 原地修改Transport.CIPHERS数组内容。
     *
     * Transport.CIPHERS是public static final String[]，字段引用为final不可重新赋值，
     * 但数组内容可变。将14个旧版SSL/TLS 1.0密码套件替换为现代TLS 1.2密码套件。
     */
    private static void fixTransportCiphers() {
        try {
            // 先过滤出JVM实际支持的密码套件
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, null, null);
            Set<String> supported = new HashSet<>();
            try (SSLSocket socket = (SSLSocket)
                    ctx.getSocketFactory().createSocket()) {
                supported.addAll(Arrays.asList(socket.getSupportedCipherSuites()));
            }

            // 按优先级过滤，保持顺序，严格排除TLS 1.3密码套件
            List<String> filtered = new ArrayList<>();
            for (String cipher : MODERN_CIPHERS) {
                if (supported.contains(cipher) && !filtered.contains(cipher)) {
                    filtered.add(cipher);
                }
            }

            if (filtered.isEmpty()) {
                logger.warning("JVM不支持任何现代RDP密码套件");
                return;
            }

            // 原地修改CIPHERS数组内容（字段为final，但数组内容可变）
            String[] ciphers = Transport.CIPHERS;
            for (int i = 0; i < ciphers.length; i++) {
                ciphers[i] = i < filtered.size() ? filtered.get(i) : filtered.get(0);
            }

            logger.info("已修改Transport.CIPHERS(原地): " + Math.min(filtered.size(), ciphers.length)
                    + "个TLS 1.2密码套件");
            logger.fine("密码套件: " + Arrays.toString(ciphers));
        } catch (Exception e) {
            logger.log(Level.WARNING, "修改Transport.CIPHERS失败: " + e.getMessage(), e);
        }
    }

    /**
     * 设置JVM默认SSLContext使用宽松TrustManager，接受RDP服务器自签名证书。
     */
    private static void fixDefaultSslContext(X509TrustManager trustManager) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, new javax.net.ssl.TrustManager[]{trustManager}, null);
            SSLContext.setDefault(ctx);
            logger.info("已设置默认SSLContext（TLS 1.2 + 宽松TrustManager）");
        } catch (Exception e) {
            logger.log(Level.WARNING, "设置默认SSLContext失败: " + e.getMessage());
        }
    }

    /**
     * 允许RDP协议所需的TLS密码套件，并限制为TLS 1.2协议。
     *
     * Windows RDP服务器对TLS 1.3支持不完善，可能导致Connection Reset，
     * 因此限制为TLS 1.2。同时从jdk.tls.disabledAlgorithms中移除DHE限制
     * 以兼容更多密码套件。
     */
    private static void enableRdpTlsCiphers() {
        try {
            // 限制客户端仅使用TLS 1.2，避免TLS 1.3与Windows RDP的兼容性问题
            java.security.Security.setProperty("jdk.tls.client.protocols", "TLSv1.2");
            logger.info("已限制TLS客户端协议为TLSv1.2（Security属性）");

            // 从禁用列表中移除DHE限制（保留TLSv1/TLSv1.1禁用以确保安全性）
            String disabled = java.security.Security.getProperty("jdk.tls.disabledAlgorithms");
            if (disabled != null) {
                StringBuilder sb = new StringBuilder();
                for (String item : disabled.split(",")) {
                    String trimmed = item.trim();
                    if (trimmed.isEmpty()) continue;
                    // 仅移除DHE相关限制，保留TLSv1/TLSv1.1禁用
                    if (trimmed.startsWith("DH ") || trimmed.equals("DHE_DSS") || trimmed.equals("DHE_RSA")) {
                        logger.fine("移除TLS禁用项: " + trimmed);
                        continue;
                    }
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(trimmed);
                }
                java.security.Security.setProperty("jdk.tls.disabledAlgorithms", sb.toString());
                logger.info("已调整TLS禁用算法以兼容RDP协议");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "调整TLS安全属性失败: " + e.getMessage());
        }
    }
}
