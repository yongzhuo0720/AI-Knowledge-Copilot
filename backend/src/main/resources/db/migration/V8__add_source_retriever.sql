-- 记录每条引用片段是被哪一路召回器命中的。
--
-- 背景：检索从「纯向量」升级为「向量 + BM25 关键词」双路召回 + RRF 融合，
-- 前端需要展示某条引用来自哪一路（vector / keyword / hybrid），
-- 这既是用户体验（能看出关键词命中的片段），也是验证混合检索生效的直接证据。
--
-- 用 VARCHAR 而不是 ENUM：取值集合后续可能继续扩充（比如将来加入 reranker 或
-- 第三路召回），ENUM 每次加值都要改表结构，VARCHAR 只需应用层放行。
ALTER TABLE conversation_message_source
    ADD COLUMN retriever VARCHAR(16) NOT NULL DEFAULT 'vector' AFTER score;
