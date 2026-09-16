package com.tangluobo.tomato.module.tools.server;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryDownloadTokenTest {

    private static final byte[] SIGNING_KEY = new byte[32];
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void tokenIsLimitedToReadRequestsAndTheSignedPath() {
        TemporaryDownloadToken tokens = tokensAt(NOW);
        String token = tokenFrom(tokens.signPath("/files/report.pdf"));

        assertTrue(tokens.isValid("GET", "/files/report.pdf", token));
        assertTrue(tokens.isValid("HEAD", "/files/report.pdf", token));
        assertFalse(tokens.isValid("PUT", "/files/report.pdf", token));
        assertFalse(tokens.isValid("GET", "/files/other.pdf", token));
        assertFalse(tokens.isValid("GET", "/files/report.pdf", token + "x"));
    }

    @Test
    void tokenExpiresAfterConfiguredLifetime() {
        String token = tokenFrom(tokensAt(NOW).signPath("/files/report.pdf"));

        assertTrue(tokensAt(NOW.plusSeconds(300))
                .isValid("GET", "/files/report.pdf", token));
        assertFalse(tokensAt(NOW.plusSeconds(301))
                .isValid("GET", "/files/report.pdf", token));
    }

    @Test
    void tokenIsRevokedWhenTheServerSigningKeyChanges() {
        String token = tokenFrom(tokensAt(NOW).signPath("/files/report.pdf"));
        byte[] replacementKey = SIGNING_KEY.clone();
        replacementKey[0] = 1;
        TemporaryDownloadToken restartedServer = new TemporaryDownloadToken(replacementKey,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

        assertFalse(restartedServer.isValid("GET", "/files/report.pdf", token));
    }

    private static TemporaryDownloadToken tokensAt(Instant instant) {
        return new TemporaryDownloadToken(SIGNING_KEY,
                Clock.fixed(instant, ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    private static String tokenFrom(String signedPath) {
        return signedPath.substring(signedPath.indexOf("?token=") + 7);
    }
}
