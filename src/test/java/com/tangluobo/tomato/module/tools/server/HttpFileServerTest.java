package com.tangluobo.tomato.module.tools.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpFileServerTest {

    @TempDir
    Path sharedDirectory;

    @Test
    void protectedFileServerRequiresAnEnabledAccount() {
        ServerConfig config = new ServerConfig(ServerType.HTTP);
        config.setBindAddress("127.0.0.1");
        config.setPort(0);
        config.setRootDirectory(sharedDirectory.toString());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new HttpFileServer().start(config));

        assertTrue(error.getMessage().contains("至少配置一个启用账号"));
    }

    @Test
    void authenticatedDirectoryIssuesTemporaryFileLink() throws Exception {
        byte[] expected = "temporary token download".getBytes(StandardCharsets.UTF_8);
        String fileName = "sample + 中文.txt";
        Files.write(sharedDirectory.resolve(fileName), expected);

        ServerConfig config = new ServerConfig(ServerType.HTTP);
        config.setBindAddress("127.0.0.1");
        config.setPort(0);
        config.setRootDirectory(sharedDirectory.toString());
        config.setAccounts(java.util.List.of(new ServerAccount("reader", "secret")));

        HttpFileServer server = new HttpFileServer();
        server.start(config);
        try {
            URI root = URI.create(server.getListenAddress().replace("0.0.0.0", "127.0.0.1") + "/");
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(root).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            String credentials = Base64.getEncoder().encodeToString(
                    "reader:secret".getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> listing = client.send(
                    HttpRequest.newBuilder(root)
                            .header("Authorization", "Basic " + credentials)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listing.statusCode());

            Matcher link = Pattern.compile("href=\"([^\"]+\\?token=[^\"]+)\"")
                    .matcher(listing.body());
            assertTrue(link.find(), "the directory listing should contain a signed file URL");
            URI signedFile = root.resolve(link.group(1));

            HttpResponse<byte[]> downloaded = client.send(
                    HttpRequest.newBuilder(signedFile).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, downloaded.statusCode());
            assertArrayEquals(expected, downloaded.body());
            assertTrue(downloaded.headers().firstValue("Content-Disposition")
                    .orElse("").startsWith("attachment;"));

            URI wrongPath = root.resolve("other.txt?" + signedFile.getRawQuery());
            HttpResponse<Void> reused = client.send(
                    HttpRequest.newBuilder(wrongPath).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(401, reused.statusCode());

            HttpResponse<Void> writeAttempt = client.send(
                    HttpRequest.newBuilder(signedFile)
                            .PUT(HttpRequest.BodyPublishers.ofString("changed"))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(401, writeAttempt.statusCode());
            assertArrayEquals(expected, Files.readAllBytes(sharedDirectory.resolve(fileName)));
        } finally {
            server.stop();
        }
    }

    @Test
    void sharedDirectoryEntryUsesTemporaryTokensForEachNavigationStep() throws Exception {
        byte[] expected = "shared directory download".getBytes(StandardCharsets.UTF_8);
        Files.write(sharedDirectory.resolve("archive.zip"), expected);

        ServerConfig config = new ServerConfig(ServerType.HTTP);
        config.setBindAddress("127.0.0.1");
        config.setPort(0);
        config.setSharedDirectories(List.of(
                new SharedDirectory("team files", sharedDirectory.toString(), true)));
        config.setAccounts(List.of(new ServerAccount("reader", "secret")));

        HttpFileServer server = new HttpFileServer();
        server.start(config);
        try {
            URI root = URI.create(server.getListenAddress() + "/");
            HttpClient client = HttpClient.newHttpClient();
            String credentials = Base64.getEncoder().encodeToString(
                    "reader:secret".getBytes(StandardCharsets.UTF_8));

            HttpResponse<String> shares = client.send(
                    HttpRequest.newBuilder(root)
                            .header("Authorization", "Basic " + credentials)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, shares.statusCode());

            Matcher directoryLink = Pattern.compile("href=\"([^\"]*team\\+files/\\?token=[^\"]+)\"")
                    .matcher(shares.body());
            assertTrue(directoryLink.find());
            URI signedDirectory = root.resolve(directoryLink.group(1));

            HttpResponse<String> listing = client.send(
                    HttpRequest.newBuilder(signedDirectory).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listing.statusCode());

            Matcher fileLink = Pattern.compile("href=\"([^\"]*archive\\.zip\\?token=[^\"]+)\"")
                    .matcher(listing.body());
            assertTrue(fileLink.find());
            HttpResponse<byte[]> downloaded = client.send(
                    HttpRequest.newBuilder(root.resolve(fileLink.group(1))).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, downloaded.statusCode());
            assertArrayEquals(expected, downloaded.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void anonymousAccessRemainsReadOnly() throws Exception {
        Path file = sharedDirectory.resolve("public.txt");
        byte[] expected = "public download".getBytes(StandardCharsets.UTF_8);
        Files.write(file, expected);

        ServerConfig config = new ServerConfig(ServerType.HTTP);
        config.setBindAddress("127.0.0.1");
        config.setPort(0);
        config.setRootDirectory(sharedDirectory.toString());
        config.setAnonymousAccess(true);

        HttpFileServer server = new HttpFileServer();
        server.start(config);
        try {
            URI fileUrl = URI.create(server.getListenAddress() + "/public.txt");
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<byte[]> download = client.send(
                    HttpRequest.newBuilder(fileUrl).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());
            assertArrayEquals(expected, download.body());

            HttpResponse<Void> write = client.send(
                    HttpRequest.newBuilder(fileUrl)
                            .PUT(HttpRequest.BodyPublishers.ofString("changed"))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(401, write.statusCode());
            assertArrayEquals(expected, Files.readAllBytes(file));
        } finally {
            server.stop();
        }
    }
}
