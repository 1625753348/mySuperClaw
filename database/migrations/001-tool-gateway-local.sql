-- 已有数据库增量升级。新库通过 schema.sql 直接获得该约束。
BEGIN;
ALTER TABLE gateway.tool_server DROP CONSTRAINT tool_server_protocol_check;
ALTER TABLE gateway.tool_server ADD CONSTRAINT tool_server_protocol_check
    CHECK (protocol IN ('HTTP', 'MCP', 'GRPC', 'DATABASE', 'RAG', 'SANDBOX', 'LOCAL'));
COMMIT;
