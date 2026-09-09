package com.aicopilot.common.storage;

import java.io.InputStream;

public interface ObjectStorageService {

    void upload(String objectName, InputStream inputStream, long size, String contentType);
}
