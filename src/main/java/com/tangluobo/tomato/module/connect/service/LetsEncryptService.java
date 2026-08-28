package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.util.KeyPairUtils;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 使用阿里云 DNS-01 验证申请 Let's Encrypt 证书，并保存到 S3/OSS。 */
public final class LetsEncryptService {
    private static final Duration ACME_TIMEOUT = Duration.ofMinutes(3);

    private LetsEncryptService() {}

    private static void ensureBouncyCastleProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static void issue(ConnectionConfig aliyun, String domain, String email, boolean wildcard,
                             ConnectionConfig storage, String bucket, String prefix,
                             Consumer<String> progress) throws Exception {
        issue(aliyun, domain, domain, email, wildcard, storage, bucket, prefix,
                List.of("通用 PEM"), false, "", "Let's Encrypt（90 天）", "", "", progress);
    }

    public static void issue(ConnectionConfig aliyun, String certificateDomain, String dnsZone,
                             String email, boolean wildcard, ConnectionConfig storage,
                             String bucket, String prefix, List<String> serverTypes, boolean zipPackage,
                             String keystorePassword, String certificateAuthority,
                             String eabKid, String eabHmac, Consumer<String> progress) throws Exception {
        ensureBouncyCastleProvider();
        progress.accept("正在注册 " + certificateAuthority + " 账户…");
        KeyPair accountKey = KeyPairUtils.createECKeyPair("secp256r1");
        Session session = new Session(caUri(certificateAuthority));
        AccountBuilder accountBuilder = new AccountBuilder()
                .agreeToTermsOfService()
                .addEmail(email)
                .useKeyPair(accountKey);
        if (!certificateAuthority.startsWith("Let's Encrypt")) {
            if (eabKid == null || eabKid.isBlank() || eabHmac == null || eabHmac.isBlank()) {
                throw new IllegalArgumentException(certificateAuthority + " 需要填写 EAB KID 和 HMAC Key");
            }
            accountBuilder.withKeyIdentifier(eabKid.trim(), eabHmac.trim());
        }
        Account account = accountBuilder.create(session);

        List<String> domains = wildcard
                ? List.of(certificateDomain, "*." + certificateDomain)
                : List.of(certificateDomain);
        progress.accept("正在创建证书订单…");
        Order order = account.newOrder().domains(domains).create();

        for (Authorization auth : order.getAuthorizations()) {
            if (auth.getStatus() == Status.VALID) continue;
            Dns01Challenge challenge = auth.findChallenge(Dns01Challenge.class)
                    .orElseThrow(() -> new IllegalStateException("Let's Encrypt 未提供 DNS-01 验证"));
            String recordName = challenge.getRRName(auth.getIdentifier());
            String rr = toRelativeRecord(recordName, dnsZone);
            String digest = challenge.getDigest();
            progress.accept("正在添加 DNS TXT 记录：" + recordName);
            String recordId = aliyun.getType() == ConnectType.TENCENT_CLOUD
                    ? TencentCloudService.addDomainRecord(aliyun, dnsZone, rr, "TXT", digest, 600L, null, null)
                    : AliyunService.addDomainRecord(aliyun, dnsZone, rr, "TXT", digest, 600L, null, null);
            try {
                waitForDns(recordName, digest, progress);
                progress.accept("正在验证域名所有权…");
                challenge.trigger();
                Status status = challenge.waitForCompletion(ACME_TIMEOUT);
                if (status != Status.VALID) {
                    throw new IllegalStateException("DNS 验证失败：" + challenge.getError()
                            .map(Object::toString).orElse("未知原因"));
                }
            } finally {
                try {
                    if (aliyun.getType() == ConnectType.TENCENT_CLOUD)
                        TencentCloudService.deleteDomainRecord(aliyun, dnsZone, recordId);
                    else AliyunService.deleteDomainRecord(aliyun, recordId);
                } catch (Exception ignored) {}
            }
        }

        progress.accept("正在签发证书…");
        order.waitUntilReady(ACME_TIMEOUT);
        KeyPair domainKey = KeyPairUtils.createKeyPair(2048);
        order.execute(domainKey);
        if (order.waitForCompletion(ACME_TIMEOUT) != Status.VALID) {
            throw new IllegalStateException("证书签发失败：" + order.getError()
                    .map(Object::toString).orElse("未知原因"));
        }
        Certificate certificate = order.getCertificate();
        if (certificate == null) throw new IllegalStateException("签发成功，但未返回证书");

        StringWriter fullchain = new StringWriter();
        certificate.writeCertificate(fullchain);
        String certPem = toPem(certificate.getCertificate());
        StringWriter privateKey = new StringWriter();
        KeyPairUtils.writeKeyPair(domainKey, privateKey);
        StringWriter accountKeyPem = new StringWriter();
        KeyPairUtils.writeKeyPair(accountKey, accountKeyPem);

        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String serverType : serverTypes) {
            Map<String, byte[]> typeFiles = createServerFiles(serverType, certificateDomain, certificate,
                    domainKey, fullchain.toString(), certPem, privateKey.toString(),
                    accountKeyPem.toString(), keystorePassword);
            String typeDirectory = serverDirectory(serverType) + "/";
            typeFiles.forEach((name, value) -> files.put(typeDirectory + name, value));
        }
        progress.accept("正在上传 " + serverTypes.size() + " 种服务器格式到 " + storage.getName() + "…");
        if (zipPackage) {
            String zipName = safeName(certificateDomain) + "-certificates.zip";
            uploadBytes(storage, bucket, prefix + zipName, zip(files), "application/zip");
        } else {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                uploadBytes(storage, bucket, prefix + file.getKey(), file.getValue(),
                        "application/octet-stream");
            }
        }
        progress.accept("证书已保存到 " + bucket + "/" + prefix);
    }

    private static void uploadBytes(ConnectionConfig storage, String bucket, String key,
                                    byte[] value, String contentType) throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(value);
        if (storage.getType() == ConnectType.ALIYUN_OSS) {
            OssService.uploadFile(storage, bucket, key, input, value.length, contentType);
        } else {
            S3Service.uploadFile(storage, bucket, key, input, value.length, contentType);
        }
    }

    private static Map<String, byte[]> createServerFiles(
            String serverType, String domain, Certificate certificate, KeyPair domainKey,
            String fullchain, String certPem, String privateKey, String accountKey,
            String password) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String name = safeName(domain);
        if (serverType.startsWith("Nginx")) {
            putText(files, name + ".pem", fullchain);
            putText(files, name + ".key", privateKey);
        } else if (serverType.startsWith("Apache")) {
            putText(files, name + ".crt", certPem);
            putText(files, name + "-chain.crt", fullchain);
            putText(files, name + ".key", privateKey);
        } else if (serverType.startsWith("Tomcat") || serverType.startsWith("IIS")) {
            files.put(name + ".pfx", createKeyStore("PKCS12", certificate, domainKey, password));
        } else if (serverType.contains("JKS")) {
            files.put(name + ".jks", createKeyStore("JKS", certificate, domainKey, password));
        } else {
            putText(files, "fullchain.pem", fullchain);
            putText(files, "cert.pem", certPem);
            putText(files, "private.key", privateKey);
            putText(files, "account.key", accountKey);
        }
        return files;
    }

    private static byte[] createKeyStore(String type, Certificate certificate, KeyPair keyPair,
                                         String password) throws Exception {
        char[] chars = password == null ? new char[0] : password.toCharArray();
        KeyStore store = KeyStore.getInstance(type);
        store.load(null, chars);
        List<X509Certificate> chain = certificate.getCertificateChain();
        java.security.cert.Certificate[] certificates;
        if (chain.isEmpty() || !chain.get(0).equals(certificate.getCertificate())) {
            certificates = new java.security.cert.Certificate[chain.size() + 1];
            certificates[0] = certificate.getCertificate();
            for (int i = 0; i < chain.size(); i++) certificates[i + 1] = chain.get(i);
        } else {
            certificates = chain.toArray(java.security.cert.Certificate[]::new);
        }
        store.setKeyEntry("certificate", keyPair.getPrivate(), chars, certificates);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.store(output, chars);
        return output.toByteArray();
    }

    private static void putText(Map<String, byte[]> files, String name, String value) {
        files.put(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static String safeName(String value) {
        return value.replace("*", "wildcard").replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String serverDirectory(String serverType) {
        if (serverType.startsWith("Nginx")) return "nginx";
        if (serverType.startsWith("Apache")) return "apache";
        if (serverType.startsWith("Tomcat")) return "tomcat";
        if (serverType.startsWith("IIS")) return "iis";
        if (serverType.contains("JKS")) return "jks";
        return "pem";
    }

    private static String caUri(String certificateAuthority) {
        if (certificateAuthority.startsWith("ZeroSSL")) return "acme://zerossl.com";
        if (certificateAuthority.startsWith("Google")) return "acme://pki.goog";
        if (certificateAuthority.startsWith("SSL.com")) return "acme://ssl.com/rsa";
        return "acme://letsencrypt.org";
    }

    private static String toRelativeRecord(String fqdn, String rootDomain) {
        String name = fqdn.endsWith(".") ? fqdn.substring(0, fqdn.length() - 1) : fqdn;
        String suffix = "." + rootDomain;
        if (!name.endsWith(suffix)) {
            throw new IllegalArgumentException("验证记录不属于当前云平台域名：" + fqdn);
        }
        return name.substring(0, name.length() - suffix.length());
    }

    private static void waitForDns(String name, String expected, Consumer<String> progress) throws Exception {
        progress.accept("等待 DNS TXT 记录生效：" + name);
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            // 优先查询腾讯云 Public DNS，避免 Windows/JVM 对此前 NXDOMAIN 的负缓存；
            // 同时查询公共 DNS，确保 ACME 服务在公网也能看到该记录。
            for (String provider : List.of("dns://119.29.29.29/", "dns://1.1.1.1/")) {
                try {
                    Hashtable<String, String> env = new Hashtable<>();
                    env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
                    env.put("java.naming.provider.url", provider);
                    InitialDirContext context = new InitialDirContext(env);
                    try {
                        Attributes attrs = context.getAttributes(name, new String[]{"TXT"});
                        Attribute txt = attrs.get("TXT");
                        if (txt != null) {
                            for (int i = 0; i < txt.size(); i++) {
                                String value = String.valueOf(txt.get(i)).replace("\"", "").replace(" ", "");
                                if (expected.equals(value)) return;
                            }
                        }
                    } finally {
                        context.close();
                    }
                } catch (Exception ex) {
                    last = ex;
                }
            }
            Thread.sleep(5000L);
        }
        throw new IllegalStateException("等待 DNS TXT 记录生效超时：" + name
                + "。请确认该域名的 NS 已指向腾讯云 DNSPod，并检查域名是否处于正常解析状态"
                + (last == null ? "" : "（最后错误：" + last.getMessage() + "）"));
    }

    private static String toPem(X509Certificate certificate) throws Exception {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
    }
}
