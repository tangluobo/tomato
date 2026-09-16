package com.tangluobo.tomato.module.tools.server;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Creates short-lived, path-bound tokens for HTTP GET/HEAD requests.
 * Tokens are signed with an in-memory key, so restarting the server revokes them.
 */
final class TemporaryDownloadToken {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] signingKey;
    private final Clock clock;
    private final Duration ttl;

    TemporaryDownloadToken() {
        this(randomKey(), Clock.systemUTC(), DEFAULT_TTL);
    }

    TemporaryDownloadToken(byte[] signingKey, Clock clock, Duration ttl) {
        if (signingKey == null || signingKey.length < 32) {
            throw new IllegalArgumentException("The token signing key must be at least 256 bits");
        }
        if (clock == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("The token lifetime must be positive");
        }
        this.signingKey = signingKey.clone();
        this.clock = clock;
        this.ttl = ttl;
    }

    String signPath(String rawPath) {
        long expiresAt = Math.addExact(clock.instant().getEpochSecond(), ttl.toSeconds());
        String signature = signature(rawPath, expiresAt);
        return rawPath + "?token=" + expiresAt + "." + signature;
    }

    boolean isValid(String method, String rawPath, String token) {
        if (!("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
                || rawPath == null || token == null || token.length() > 256) {
            return false;
        }

        int separator = token.indexOf('.');
        if (separator <= 0 || separator != token.lastIndexOf('.')) return false;

        try {
            long expiresAt = Long.parseLong(token.substring(0, separator));
            if (expiresAt < clock.instant().getEpochSecond()) return false;

            byte[] supplied = Base64.getUrlDecoder().decode(token.substring(separator + 1));
            byte[] expected = Base64.getUrlDecoder().decode(signature(rawPath, expiresAt));
            return MessageDigest.isEqual(expected, supplied);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String signature(String rawPath, long expiresAt) {
        if (rawPath == null || !rawPath.startsWith("/")) {
            throw new IllegalArgumentException("A token path must be absolute");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            byte[] payload = ("GET\n" + rawPath + "\n" + expiresAt)
                    .getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create a temporary download token", ex);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
