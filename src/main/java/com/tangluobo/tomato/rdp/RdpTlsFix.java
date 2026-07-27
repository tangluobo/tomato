package com.tangluobo.tomato.rdp;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
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
 * 1. 通过反射替换Transport.CIPHERS为现代GCM/ECDHE密码套件
 * 2. 设置宽松X509TrustManager接受自签名证书
 * 3. 设置JVM默认SSLContext使用宽松TrustManager
 */
public class RdpTlsFix {

    private static final Logger logger = Logger.getLogger(RdpTlsFix.class.getName());

    private static volatile boolean applied = false;

    /** 现代RDP兼容密码套件（优先级从高到低） */
    private static final String[] MODERN_CIPHERS = {
            // TLS 1.3 cipher suites
            "TLS_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            "TLS_CHACHA20_POLY1305_SHA256",
            // ECDHE + GCM (TLS 1.2, 现代Windows RDP首选)
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            // ECDHE + CHACHA20
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
            // ECDHE + CBC SHA (TLS 1.0/1.2 兼容)
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            // DHE + CBC (TLS 1.0/1.2 兼容)
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
     * 通过反射替换Transport.CIPHERS静态final字段。
     * 将14个旧版SSL/TLS 1.0密码套件替换为现代GCM/ECDHE密码套件。
     */
    private static void fixTransportCiphers() {
        try {
            Field ciphersField = Transport.class.getDeclaredField("CIPHERS");
            ciphersField.setAccessible(true);

            // 移除final修饰符
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(ciphersField, ciphersField.getModifiers() & ~Modifier.FINAL);

            // 先过滤出JVM实际支持的密码套件
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            Set<String> supported = new HashSet<>();
            try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket)
                    ctx.getSocketFactory().createSocket()) {
                supported.addAll(Arrays.asList(socket.getSupportedCipherSuites()));
            }

            Set<String> filtered = new HashSet<>();
            for (String cipher : MODERN_CIPHERS) {
                if (supported.contains(cipher)) {
                    filtered.add(cipher);
                }
            }

            // 设置新值
            String[] newCiphers = filtered.toArray(new String[0]);
            ciphersField.set(null, newCiphers);

            logger.info("已替换Transport.CIPHERS: " + newCiphers.length + "个现代密码套件");
            logger.fine("密码套件: " + Arrays.toString(newCiphers));
        } catch (Exception e) {
            logger.log(Level.WARNING, "替换Transport.CIPHERS失败: " + e.getMessage(), e);
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
     * 允许RDP协议所需的TLS密码套件。
     * Java 24默认禁用了DHE等旧版密码套件，需从jdk.tls.disabledAlgorithms中移除。
     */
    private static void enableRdpTlsCiphers() {
        try {
            String disabled = java.security.Security.getProperty("jdk.tls.disabledAlgorithms");
            if (disabled != null) {
                StringBuilder sb = new StringBuilder();
                for (String item : disabled.split(",")) {
                    String trimmed = item.trim();
                    if (trimmed.isEmpty()) continue;
                    // 跳过DHE相关限制和旧版TLS协议限制
                    if (trimmed.startsWith("DH ") || trimmed.equals("DHE_DSS") || trimmed.equals("DHE_RSA")
                            || trimmed.equals("TLSv1") || trimmed.equals("TLSv1.1")) {
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
