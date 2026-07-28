package com.tangluobo.tomato.rdp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import com.sshtools.javardp.layers.Transport;

/**
 * 修复sshtools rdp库的TLS兼容性问题。
 * 
 * 问题：Transport.CIPHERS静态字段硬编码了14个旧版SSL/TLS 1.0密码套件
 * （如SSL_RSA_WITH_RC4_128_MD5），现代Windows RDP服务器和Java 24均不支持，
 * 导致SSL协商时Connection Reset。
 * 
 * 修复：
 * 1. 原地修改Transport.CIPHERS数组为现代GCM/ECDHE密码套件
 * 2. 设置宽松X509TrustManager接受自签名证书
 * 3. 设置JVM默认SSLContext使用宽松TrustManager
 *
 * 注意：Java 17+移除了Field.modifiers字段，旧的反射清除final修饰符方式已失效
 * （NoSuchFieldException: modifiers），改为原地修改数组内容。
 */
public class RdpTlsFix {

    private static final Logger logger = Logger.getLogger(RdpTlsFix.class.getName());

    private static volatile boolean applied = false;

    /**
     * 现代RDP兼容密码套件（优先级从高到低）。
     * TLS 1.2密码套件排在前面，因为Windows RDP服务器对TLS 1.3支持不完善，
     * 可能导致"Received fatal alert: internal_error"错误。
     * Transport.CIPHERS数组仅有14个槽位，TLS 1.3套件放在末尾不会占用槽位。
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
            // TLS 1.3 cipher suites (放在末尾，受jdk.tls.client.protocols限制不会使用)
            "TLS_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            "TLS_CHACHA20_POLY1305_SHA256",
    };

    /**
     * 应用RDP TLS兼容性修复。只需调用一次。
     * 
     * @param trustManager 宽松的X509TrustManager，接受RDP服务器自签名证书
     */
    public static synchronized void apply(X509TrustManager trustManager) {
        if (applied) return;

        try {
            // 1. 修复Transport.CIPHERS字段
            fixTransportCiphers();

            // 2. 设置JVM默认SSLContext使用宽松TrustManager
            fixDefaultSslContext(trustManager);

            // 3. 允许RDP所需的TLS密码套件（移除Java安全限制）
            enableRdpTlsCiphers();

            applied = true;
            logger.info("RDP TLS兼容性修复已应用");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "RDP TLS修复失败: " + e.getMessage(), e);
        }
    }

    /**
     * 原地修改Transport.CIPHERS数组内容。
     *
     * Transport.CIPHERS是public static final String[]，字段引用为final不可重新赋值，
     * 但数组内容可变。Java 25已移除Field.modifiers字段，旧的反射清除final修饰符
     * 方式失效（NoSuchFieldException: modifiers），因此直接修改数组元素。
     *
     * 将14个旧版SSL/TLS 1.0密码套件替换为现代GCM/ECDHE密码套件。
     */
    private static void fixTransportCiphers() {
        try {
            // 先过滤出JVM实际支持的密码套件
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            Set<String> supported = new HashSet<>();
            try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket)
                    ctx.getSocketFactory().createSocket()) {
                supported.addAll(Arrays.asList(socket.getSupportedCipherSuites()));
            }

            // 按优先级过滤，保持顺序
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
                    + "个现代密码套件");
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
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{trustManager}, null);
            SSLContext.setDefault(ctx);
            logger.info("已设置默认SSLContext（宽松TrustManager）");
        } catch (Exception e) {
            logger.log(Level.WARNING, "设置默认SSLContext失败: " + e.getMessage());
        }
    }

    /**
     * 允许RDP协议所需的TLS密码套件，并限制为TLS 1.2协议。
     *
     * Windows RDP服务器对TLS 1.3支持不完善，可能导致
     * "Received fatal alert: internal_error"错误，因此限制为TLS 1.2。
     * 同时从jdk.tls.disabledAlgorithms中移除DHE限制以兼容更多密码套件。
     */
    private static void enableRdpTlsCiphers() {
        try {
            // 限制客户端仅使用TLS 1.2，避免TLS 1.3与Windows RDP的兼容性问题
            java.security.Security.setProperty("jdk.tls.client.protocols", "TLSv1.2");
            logger.info("已限制TLS客户端协议为TLSv1.2");

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
