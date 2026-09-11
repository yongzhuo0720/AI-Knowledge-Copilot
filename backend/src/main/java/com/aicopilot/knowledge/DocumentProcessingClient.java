package com.aicopilot.knowledge;

public interface DocumentProcessingClient {

    DocumentProcessingResponse submit(DocumentProcessingRequest request);

    DocumentProcessingResponse findTask(String taskId);

    DocumentProcessingResponse retry(String taskId);
}
