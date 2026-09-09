CREATE TABLE knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_knowledge_base_workspace (workspace_id),
    CONSTRAINT fk_knowledge_base_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id)
);

CREATE TABLE knowledge_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_object_key (object_key),
    KEY idx_knowledge_document_knowledge_base (knowledge_base_id),
    CONSTRAINT fk_knowledge_document_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id)
);
