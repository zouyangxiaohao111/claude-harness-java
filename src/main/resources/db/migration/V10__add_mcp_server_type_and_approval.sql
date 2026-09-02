-- ===================================================================
-- V10: mcp_servers 新增 type + approval_status 两列（MCP-I-1 T7/T8）
-- 对齐 CC 语义（grep 自验 Open-ClaudeCode/src/services/mcp/）：
--   * type —— CC original: McpServerConfig.type（types.ts:124-135 8 传输 union）。
--     既有行缺省 'stdio'（types.ts:30 type optional backwards compatibility）。
--     仅过渡：Java REST 旧请求无 type → 推导 stdio。
--   * approval_status —— CC original: getProjectMcpServerStatus（utils.ts:351-406）
--     'approved' | 'rejected' | 'pending'。审批状态机（Q-25=A）默认 pending，
--     导入时按 CC 判定初始态（approved→enabled=true；rejected→enabled=false；
--     pending→enabled=false+pending）。
-- SQLite 一次 ALTER 仅支持单列，故分两条。
-- ===================================================================

ALTER TABLE mcp_servers ADD COLUMN type TEXT DEFAULT 'stdio';
ALTER TABLE mcp_servers ADD COLUMN approval_status TEXT NOT NULL DEFAULT 'pending';
