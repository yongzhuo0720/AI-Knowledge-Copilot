CREATE TABLE document_processing_task (
    task_id VARCHAR(64) NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(2000) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    replace_existing BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id),
    KEY idx_document_processing_task_status (status, updated_at),
    KEY idx_document_processing_task_knowledge_base (knowledge_base_id),
    CONSTRAINT fk_document_processing_task_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id)
);
