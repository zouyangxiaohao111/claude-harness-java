-- ===================================================================
-- V98: llm_usage_calls —— 每 call LLM 用量/费用明细表（新增，新表无既有 owner）
--
-- 背景：用量统计前置到请求网关后，除「会话级累计快照」（sessions.total_cost_yuan /
--   model_usage_json，V50）之外，还需要「每次 call 一行」的明细账 ——
--   一次尝试一行（常规调用 1 行；流式失败回落非流式 2 行；被 abort 的流式 1 行），
--   用于按 agent / 归类 / 层级（parent_agent_id + depth 树）逐行对账与回溯。
--   载体：{@code infra.llm.LlmCallRecord}（载荷 record） → {@code repository.usage.entity.LlmUsageCallRecord}
--   （表行投影，MyBatis-Flex camelCase ↔ snake_case 自动映射）。
--
-- 归属语义（列 → LlmCallRecord / UsageAttribution）：session_id / agent_id / category /
--   parent_agent_id / depth / label 构成 session → 主 agent(0) → 子代理(1) → 嵌套子代理(2)… 的树；
--   明细行按此树组织，「费用与 token 用量构成的层级信息」由此可重建（会话级树快照另见 V99）。
--
-- 列语义：
--   id                        = 调用标识；应用生成「uc- 前缀 + UUID8」（照仓库 id 约定，⛔ 非自增）。
--   session_id                = 归属会话（可 null = 无会话上下文的旁路调用）。
--   agent_id                  = 归属 agent（主 agent = 会话短 id；子代理 = a+16hex；teammate = 全名；可 null）。
--   category                  = 归类维度（MAIN|SUBAGENT|TEAMMATE|COMPACT|BACKGROUND|HOOK|SIDE_CALL|
--                               CLASSIFIER|UNKNOWN；应用层枚举封闭，⛔ 不落 CHECK——枚举扩展=append 迁移）。
--   parent_agent_id           = 父 agent id（null = 顶层）。
--   depth                     = 层级深度（0 = 顶层 agent；子代理 1；嵌套 2…）。
--   label                     = 可读标签（subagentName / skill 名 / cron workload / 旁路用途名；可 null）。
--   model_name                = 计费用模型名（DB 模型名，价格解析键）。
--   sdk_model_name            = 实际发给上游的模型名（已剥 provider 前缀）。
--   provider_type             = provider 类型（openai_sdk / anthropic / mock…）。
--   query_source              = 调用来源字符串（QuerySource 值或旁路自述名；可 null）。
--   stream                    = 是否流式（1/0）。
--   phase                     = 尝试结局（SETTLED|ABORTED|FAILED；应用层枚举封闭）。
--   token_source              = 数值来源可信度（UPSTREAM|ESTIMATED|UNKNOWN；⛔ 估算值不得与上游上报值混算）。
--   input_tokens              = 输入 token（默认 0）。
--   output_tokens             = 输出 token（默认 0）。
--   cache_read_input_tokens   = cache 读 token（可 null = 无 cache 数据）。
--   cache_creation_input_tokens = cache 写 token（可 null = 无 cache 数据）。
--   cost_yuan                 = 该次尝试折算费用（元；DECIMAL，可 null = 无计费）。
--   peak_hour                 = 计价时段（1 = 高峰档）。
--   status                    = 调用状态短标签（ok|aborted|error；可 null）。
--   error_kind                = 错误类别（可 null；仅 status != ok 时填）。
--   started_at                = 调用开始时刻（ISO-8601 文本，对齐 sessions 时间列 V1/V50 风格）。
--   elapsed_ms                = 调用耗时（ms）。
--   created_at                = 行写入时刻（SQLite UTC，DEFAULT）。
--
-- 索引：会话时间线读取（session_id 前缀 + started_at 降序）/ 按 agent 汇总 / 按归类汇总。
--
-- ⚠️ 本文件注释**不得**出现「美元符 + 左大括号」的 Flyway 占位符字面量
--    （V73/V78/V92..V97 同款警告：application.yml 未关 flyway.placeholder-replacement，
--     SQL 注释也在替换范围内 → Flyway 会把它当未配置占位符并解析失败）。
-- ===================================================================

CREATE TABLE IF NOT EXISTS llm_usage_calls (
    id                          TEXT PRIMARY KEY,
    session_id                  TEXT,
    agent_id                    TEXT,
    category                    TEXT NOT NULL,
    parent_agent_id             TEXT,
    depth                       INTEGER NOT NULL DEFAULT 0,
    label                       TEXT,
    model_name                  TEXT,
    sdk_model_name              TEXT,
    provider_type               TEXT,
    query_source                TEXT,
    stream                      INTEGER NOT NULL DEFAULT 0,
    phase                       TEXT NOT NULL,
    token_source                TEXT NOT NULL,
    input_tokens                INTEGER NOT NULL DEFAULT 0,
    output_tokens               INTEGER NOT NULL DEFAULT 0,
    cache_read_input_tokens     INTEGER,
    cache_creation_input_tokens INTEGER,
    cost_yuan                   DECIMAL(14,8),
    peak_hour                   INTEGER NOT NULL DEFAULT 0,
    status                      TEXT,
    error_kind                  TEXT,
    started_at                  TEXT NOT NULL,
    elapsed_ms                  INTEGER NOT NULL DEFAULT 0,
    created_at                  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 会话时间线读取（session×started_at 前缀 + 降序）
CREATE INDEX IF NOT EXISTS idx_llm_usage_calls_session_started
    ON llm_usage_calls(session_id, started_at);

-- 按 agent 汇总
CREATE INDEX IF NOT EXISTS idx_llm_usage_calls_agent
    ON llm_usage_calls(agent_id);

-- 按归类汇总
CREATE INDEX IF NOT EXISTS idx_llm_usage_calls_category
    ON llm_usage_calls(category);
