ALTER TABLE knowledge_document
    ADD COLUMN processing_task_id VARCHAR(64) NULL AFTER status;

CREATE INDEX idx_knowledge_document_processing_task
    ON knowledge_document (processing_task_id);
