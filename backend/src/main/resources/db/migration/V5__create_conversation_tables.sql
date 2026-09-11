CREATE TABLE conversation_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_conversation_session_owner (knowledge_base_id, user_id, updated_at),
    CONSTRAINT fk_conversation_session_knowledge_base
        FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_conversation_session_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE TABLE conversation_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_conversation_message_session (session_id, created_at, id),
    CONSTRAINT fk_conversation_message_session
        FOREIGN KEY (session_id) REFERENCES conversation_session (id)
);

CREATE TABLE conversation_message_source (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    document_object_key VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    score DOUBLE NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conversation_message_source_message (message_id),
    CONSTRAINT fk_conversation_message_source_message
        FOREIGN KEY (message_id) REFERENCES conversation_message (id)
);
