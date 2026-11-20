BEGIN;
ALTER TABLE memory.task_episode ADD COLUMN summary_text text;
CREATE TABLE memory.knowledge_chunk_body (
    tenant_id text NOT NULL,
    document_id uuid NOT NULL,
    document_version integer NOT NULL,
    ordinal integer NOT NULL,
    body text NOT NULL CHECK (length(body) BETWEEN 1 AND 6000),
    PRIMARY KEY (tenant_id, document_id, document_version, ordinal),
    FOREIGN KEY (tenant_id, document_id, document_version, ordinal)
        REFERENCES memory.knowledge_chunk (tenant_id, document_id, document_version, ordinal) ON DELETE CASCADE
);
CREATE TABLE memory.index_job (
    tenant_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('EPISODE', 'SEMANTIC')),
    source_key text NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_at timestamptz NOT NULL DEFAULT now(),
    lease_id uuid,
    lease_until timestamptz,
    indexed_at timestamptz,
    failed_at timestamptz,
    PRIMARY KEY (tenant_id, kind, source_key)
);
CREATE INDEX memory_index_due ON memory.index_job (next_at) WHERE indexed_at IS NULL AND failed_at IS NULL;
COMMENT ON COLUMN memory.task_episode.summary_text IS '自动沉淀的结构化案例摘要，供索引与出库使用。';
COMMENT ON TABLE memory.knowledge_chunk_body IS '知识分块正文，按租户、文档版本和分块序号存放。';
COMMENT ON COLUMN memory.knowledge_chunk_body.body IS '导入后保存在 PostgreSQL 的原文分块。';
COMMENT ON TABLE memory.index_job IS '记忆向量索引的持久化作业及重试状态。';
COMMENT ON COLUMN memory.index_job.source_key IS '案例 UUID 或文档 UUID、版本及分块序号组成的引用。';
COMMENT ON COLUMN memory.index_job.lease_until IS '索引 Worker 租约失效时间，到期可重新领取。';
COMMENT ON COLUMN memory.index_job.failed_at IS '索引重试耗尽的时间。';
COMMIT;
