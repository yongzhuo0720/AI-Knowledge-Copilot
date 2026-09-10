package com.aicopilot.common.storage;

import com.aicopilot.common.exception.BusinessException;
import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@ConditionalOnProperty(prefix = "storage.minio", name = "enabled", havingValue = "true")
public class MinioStorageService implements ObjectStorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private volatile boolean bucketReady;

    public MinioStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public void upload(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new BusinessException("STORAGE_UPLOAD_FAILED", "failed to upload object");
        }
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
        }
        bucketReady = true;
    }
}
