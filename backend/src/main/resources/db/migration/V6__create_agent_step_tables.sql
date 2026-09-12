CREATE TABLE conversation_message_agent_step (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    tool VARCHAR(128) NOT NULL,
    query VARCHAR(1000) NOT NULL,
    result_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_conversation_message_agent_step_message (message_id),
    CONSTRAINT fk_conversation_message_agent_step_message
        FOREIGN KEY (message_id) REFERENCES conversation_message (id)
);
