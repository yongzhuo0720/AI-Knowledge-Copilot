package com.aicopilot.common.storage;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MinioStorageServiceTest {

    private MinioClient minioClient;
    private MinioStorageService storageService;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        MinioProperties properties = new MinioProperties();
        properties.setBucket("test-bucket");
        storageService = new MinioStorageService(minioClient, properties);
    }

    @Test
    void uploadsObjectToConfiguredBucket() throws Exception {
        storageService.upload(
                "documents/example.txt",
                new ByteArrayInputStream("content".getBytes()),
                7,
                "text/plain"
        );

        verify(minioClient).putObject(any());
    }
}
