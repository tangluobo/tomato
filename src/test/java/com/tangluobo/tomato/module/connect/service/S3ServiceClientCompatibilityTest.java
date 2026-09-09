package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class S3ServiceClientCompatibilityTest {

    @Test
    void createsMinioClientWithTheResolvedOkioRuntime() {
        ConnectionConfig config = new ConnectionConfig();
        config.setEndpoint("http://127.0.0.1:9000");
        config.setRegion("us-east-1");
        config.setUsername("test-access-key");
        config.setPassword("test-secret-key");

        MinioClient client = S3Service.createClient(config);

        assertNotNull(client);
    }
}
