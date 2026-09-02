package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.AttachmentRequest;

import java.util.List;
import java.util.UUID;

/**
 * LlmAgentLoop.run() 唯一契约 · 对齐 CC query.ts QueryParams（query.ts:181-199）。
 *
 * <p>字段命名严格对齐 query.ts，把"调用参数"和"loop 配置"分离：
 * <ul>
 *   <li><b>必传</b>：{@link #userPrompt} / {@link #config} / {@link #modelName} / {@link #querySource}</li>
 *   <li><b>Session 元数据</b>：{@link #sessionId} / {@link #agentId}（null = 主线程 / 权限系统不可用）</li>
 *   <li><b>可选</b>：{@link #systemPrompt} / {@link #appendSystemPrompt} / {@link #maxTurns} /
 *       {@link #taskBudget} / {@link #fallbackModel} / {@link #skipCacheWrite} /
 *       {@link #maxOutputTokensOverride} / {@link #attachments}</li>
 * </ul>
 *
 * <p>[A1 · attachment-multimodal] {@link #attachments} 对齐 CC {@code pastedContents}
 * （utils/config.ts:54-62 {@code PastedContent}）：web 层接线 {@code SendMessageRequest.attachments}
 * （HTTP 请求体）→ {@link #attachments}。ChatService.processUserMessage 消费时已按 contentId
 * 经 {@code ImageAttachmentStore.getBase64} 补全 base64 + mediaType（直传 base64 原样保留），
 * LlmAgentLoop 侧据此组装 image content block / 多模态工具路由。null/空 = 无附件（行为与现状一致）。
 *
 * <p>[RES-SP31 · OPD-SP-31] {@link #appendSystemPrompt} 对齐 CC {@code appendSystemPrompt}
 * （main.tsx:1364-1382 来源链 + systemPrompt.ts:46/53/121）：用户追加指令的唯一无条件通道，
 * 经 HTTP 请求体 {@code SendMessageRequest.appendSystemPrompt} → 本字段 → {@link AgentState}
 * → LlmAgentLoop s10 组装恒末尾追加（I-5）。null = 无追加指令（行为与现状一致）。
 *
 * <p><b>[V-FB-02] {@link #fallbackModel} 接线状态（DEC-RV-02 · FIX-16 已接通）</b>：CC 顶层从
 * CLI flag 派生 fallbackModel（main.tsx:2020 {@code userSpecifiedFallbackModel}，CC TS 无
 * {@code FALLBACK_MODEL_ID} env 默认）。Java 端按调用传入结构齐备（本字段 → RetryOptions
 * → TransientErrorHandler.handle(fallbackModel) → tryFallbackModel 优先 param），且 web 层
 * per-call 接线已接通：{@code SendMessageRequest.fallbackModel}（HTTP 请求体）→ 本字段 →
 * QueryParams → RetryOptions → TransientErrorHandler（按调用传入优先）。settings
 * {@code fallbackModelId}（ApiErrors.java:156-162，DC-18，F4 迁移）仍仅作<b>默认值</b>（决策 10 不变）：
 * 无按调用传入时兜底。
 *
 * <p><b>[RV-11 · REV-FIX-2] {@link #permissionModeCli} / {@link #dangerouslySkipPermissions}
 * 初始权限模式输入源</b>：对齐 CC 顶层 CLI（main.tsx:1099 {@code permissionMode: permissionModeCli}
 * / main.tsx:621 {@code rawCliArgs.includes('--dangerously-skip-permissions')}）—— web 后端
 * 无进程级 CLI，等价"按调用请求配置"。web 层接线：{@code SendMessageRequest.permissionMode} /
 * {@code VerifyChatRequest.permissionMode}（HTTP 请求体）→ 本字段；{@code ...dangerouslySkipPermissions}
 * → {@link #dangerouslySkipPermissions}。LlmAgentLoop.doRun 据此 + {@code InitialPermissionModeSource}
 * （settings 磁盘 meta）组装 {@code InitialPermissionModeResolver.Input}，经 6 参重载使初始
 * mode 非恒 DEFAULT。
 *
 * <p>构造器只保留依赖注入（factory/registry/publisher），所有调用参数通过 RunRequest 传递。
 * querySource 为必传字段 —— 对齐 query.ts:189 {@code querySource: QuerySource}。
 *
 * <h2>L1/L2 契约保留（来自 replicating-python-to-java-systems）</h2>
 * <ul>
 *   <li>L1 行为：UserPrompt → LLM 流 → 工具 → 下一轮 LLM → 退出 —— 保留</li>
 *   <li>L2 契约：错误恢复三路径 / max_tokens 8K→64K / 历史压缩 —— 保留</li>
 *   <li>L3 实现：mutable state vs immutable —— 允许差异</li>
 * </ul>
 *
 * @see LlmAgentLoop#run(RunRequest)
 * @see QuerySource
 * @see TaskBudget
 */
public record RunRequest(
    String userPrompt,
    ProviderConfig config,
    String modelName,
    QuerySource querySource,
    String sessionId,
    UUID agentId,
    String systemPrompt,
    Integer maxTurns,
    TaskBudget taskBudget,
    String fallbackModel,
    Boolean skipCacheWrite,
    Integer maxOutputTokensOverride,
    String appendSystemPrompt,
    String permissionModeCli,
    boolean dangerouslySkipPermissions,
    JsonNode jsonSchema,
    List<AttachmentRequest> attachments
) {
    /**
     * 紧凑构造器：校验 userPrompt / querySource 必传（对齐 CC 运行时检查）。
     *
     * @throws IllegalArgumentException if userPrompt blank or querySource null
     */
    public RunRequest {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt is blank");
        }
        if (querySource == null) {
            throw new IllegalArgumentException("querySource is null");
        }
    }

    // ── [IMP2-10 · MISS-2 · OD-13] taskBudget 生产来源解析 ──

    /**
     * taskBudget 生产来源默认值（tokens）。
     *
     * <p>对齐 CC 入口通道：CLI {@code --task-budget <tokens>}（main.tsx:982-988，正整型校验）。
     * CC 无数值默认（flag 未传 = undefined = 不注入 task_budget）；Java 三生产入口按
     * OD-13 来源链「请求参数 → 配置 → 默认值」解析，本默认值仅在请求参数与配置均缺席时兜底，
     * 保证 loop 内结转/注入链路生产有效（MISS-2 目标，INV-11 非死计算）。
     * 与既有测试基线一致（LlmAgentLoopTaskBudgetCcTest 全链路 200_000）。
     */
    public static final int DEFAULT_TASK_BUDGET_TOTAL = 200_000;

    /**
     * [IMP2-10 · MISS-2 · OD-13] 生产入口 taskBudget 解析：请求参数 → 配置 → 默认值。
     *
     * <p>来源链（OD-13 裁决）：
     * <ol>
     *   <li><b>请求参数</b> {@code requestParam}（CC original: {@code --task-budget <tokens>}
     *       main.tsx:982-988）：非 null 即采用；≤0 抛 {@link IllegalArgumentException}
     *       （CC argParser {@code must be a positive integer} 等价校验，TaskBudget 紧凑构造器兜底）。</li>
     *   <li><b>配置</b> {@code configuredTotal}（Spring 配置 {@code nexusai.agent.task-budget.total}，
     *       0 = 未配置）：&gt; 0 即采用。</li>
     *   <li><b>默认值</b> {@link #DEFAULT_TASK_BUDGET_TOTAL}：以上均缺席时兜底（恒非 null）。</li>
     * </ol>
     *
     * <p><b>remaining 不参与输入</b>：CC query.ts:197 输入契约仅 {@code {total}}，remaining 是
     * queryLoop 局部量（query.ts:291），由 LlmAgentLoop 压缩成功点结转（applyTaskBudgetCarryover）。
     *
     * @return 恒非 null 的 {@link TaskBudget}
     */
    public static TaskBudget resolveTaskBudget(Integer requestParam, int configuredTotal) {
        if (requestParam != null) {
            return new TaskBudget(requestParam);
        }
        if (configuredTotal > 0) {
            return new TaskBudget(configuredTotal);
        }
        return new TaskBudget(DEFAULT_TASK_BUDGET_TOTAL);
    }

    // ── Static factory helpers（上游注入 · OD-13：不再恒置 taskBudget=null）──

    /** 最小化测试 helper：userPrompt + modelName + querySource（USER）；taskBudget 可选（null = 无任务预算）。 */
    public static RunRequest forTest(String userPrompt, String modelName, TaskBudget taskBudget) {
        return new RunRequest(userPrompt, null, modelName, QuerySource.USER,
            null, null, null, null, taskBudget, null, null, null, null, null, false, null, null);
    }

    /** [RES-SP31] 测试 helper 重载：额外携带 appendSystemPrompt（验证 RunRequest → AgentState 传递链）。 */
    public static RunRequest forTest(String userPrompt, String modelName, TaskBudget taskBudget,
                                     String appendSystemPrompt) {
        return new RunRequest(userPrompt, null, modelName, QuerySource.USER,
            null, null, null, null, taskBudget, null, null, null, appendSystemPrompt, null, false, null, null);
    }

    /**
     * 主线程便捷：userPrompt + config + modelName + systemPrompt；taskBudget 可选（CC query.ts:197 可选）。
     *
     * <p>querySource=REPL_MAIN_THREAD（FIX-SM · 对齐 CC sessionMemory.ts:278 gate
     * {@code querySource !== 'repl_main_thread'} → return）：主线程 query 必须携带
     * repl_main_thread 标记，session-memory 提取 / MagicDocs / SkillImprovement 等
     * post-sampling hook 的主线程门才会放行（CC deriveQuerySource 主线程即 repl_main_thread）。
     */
    public static RunRequest user(String userPrompt, ProviderConfig config, String modelName, String systemPrompt,
                                  TaskBudget taskBudget) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            null, null, systemPrompt, null, taskBudget, null, null, null, null, null, false, null, null);
    }

    /** [RES-SP31] user 工厂重载：额外携带 appendSystemPrompt（VerifyChatController 等主线程 HTTP 入口）。 */
    public static RunRequest user(String userPrompt, ProviderConfig config, String modelName, String systemPrompt,
                                  String appendSystemPrompt, TaskBudget taskBudget) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            null, null, systemPrompt, null, taskBudget, null, null, null, appendSystemPrompt, null, false, null, null);
    }

    /**
     * [DEC-RV-02 · FIX-16] user 工厂重载：额外携带 fallbackModel（per-call 按调用传入）。
     *
     * <p>web 层 per-call 接线：{@code SendMessageRequest.fallbackModel} / {@code VerifyChatRequest.fallbackModel}
     * （HTTP 请求体，CC original: {@code --fallback-model} / {@code userSpecifiedFallbackModel} main.tsx:2020）
     * → 本字段 → QueryParams → RetryOptions → TransientErrorHandler（按调用传入优先，
     * 空则回落 settings.fallbackModelId 默认值，决策 10 不变（F4 env→settings 迁移）。
     */
    public static RunRequest user(String userPrompt, ProviderConfig config, String modelName, String systemPrompt,
                                  String appendSystemPrompt, String fallbackModel, TaskBudget taskBudget) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            null, null, systemPrompt, null, taskBudget, fallbackModel, null, null, appendSystemPrompt, null, false, null, null);
    }

    /**
     * [RV-11 · REV-FIX-2] user 工厂重载：额外携带初始权限模式输入
     * （permissionModeCli + dangerouslySkipPermissions）。
     *
     * <p>web 层 per-call 接线：{@code VerifyChatRequest.permissionMode} /
     * {@code VerifyChatRequest.dangerouslySkipPermissions}（HTTP 请求体，
     * CC original: {@code --permission-mode} main.tsx:1099 /
     * {@code --dangerously-skip-permissions} main.tsx:621）→ 本字段 →
     * LlmAgentLoop.doRun 组装 InitialPermissionModeResolver.Input（与 settings 磁盘 meta 合并）。
     */
    public static RunRequest user(String userPrompt, ProviderConfig config, String modelName, String systemPrompt,
                                  String appendSystemPrompt, String fallbackModel,
                                  String permissionModeCli, boolean dangerouslySkipPermissions,
                                  TaskBudget taskBudget) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            null, null, systemPrompt, null, taskBudget, fallbackModel, null, null,
            appendSystemPrompt, permissionModeCli, dangerouslySkipPermissions, null, null);
    }

    /**
     * Session 线程便捷：传入 sessionId/agentId；taskBudget 可选（CC query.ts:197 可选）。
     *
     * <p>[session-id-short] sessionId 为 short 形态（sess-xxx），由调用方直传（不再经
     * parseSessionUuid 派生 UUID 串）。
     *
     * <p>querySource=REPL_MAIN_THREAD（FIX-SM · 同 {@link #user}）：ChatService 主会话走
     * 此工厂，改 REPL_MAIN_THREAD 后 SM 提取 hook（LlmAgentLoop:3314-3322 PostSamplingContext）
     * 门 1 放行，主线程 SM 提取生产可达。
     *
     * <p>[ER-IMP-02 · R-TOK] <b>agentId 主线程须传 null</b>（对齐 CC 主线程
     * {@code toolUseContext.agentId = undefined}，query.ts:342 / query.ts:1311）：
     * 传非空会使 checkTokenBudget 首行 {@code if (agentId || ...)} 命中（tokenBudget.ts:51）
     * → 主线程首迭代 StopDecision → MAX_OUTPUT_TOKENS break。直接以 agentId==sessionId
     * 驱动 loop 的主线程调用方由 LlmAgentLoop checkTokenBudget 入参守卫（isSubagent 语义）
     * 归一为 null；真 subagent（agentId != sessionId）传非空。
     */
    public static RunRequest session(String userPrompt, String sessionId, UUID agentId,
                                     ProviderConfig config, String modelName, String systemPrompt,
                                     TaskBudget taskBudget) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            sessionId, agentId, systemPrompt, null, taskBudget, null, null, null, null, null, false, null, null);
    }

    /** [RES-SP31] session 工厂重载：额外携带 appendSystemPrompt（ChatService 主会话 HTTP 入口）。 */
    public static RunRequest session(String userPrompt, String sessionId, UUID agentId,
                                     ProviderConfig config, String modelName, String systemPrompt,
                                     String appendSystemPrompt, TaskBudget taskBudget) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            sessionId, agentId, systemPrompt, null, taskBudget, null, null, null, appendSystemPrompt, null, false, null, null);
    }

    /**
     * [DEC-RV-02 · FIX-16] session 工厂重载：额外携带 fallbackModel（per-call 按调用传入）。
     *
     * <p>web 层 per-call 接线：{@code SendMessageRequest.fallbackModel}（HTTP 请求体，
     * CC original: {@code --fallback-model} / {@code userSpecifiedFallbackModel} main.tsx:2020）
     * → 本字段 → QueryParams → RetryOptions → TransientErrorHandler（按调用传入优先，
     * 空则回落 settings.fallbackModelId 默认值，决策 10 不变（F4 env→settings 迁移）。
     */
    public static RunRequest session(String userPrompt, String sessionId, UUID agentId,
                                     ProviderConfig config, String modelName, String systemPrompt,
                                     String appendSystemPrompt, String fallbackModel, TaskBudget taskBudget) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            sessionId, agentId, systemPrompt, null, taskBudget, fallbackModel, null, null, appendSystemPrompt, null, false, null, null);
    }

    /**
     * [RV-11 · REV-FIX-2] session 工厂重载：额外携带初始权限模式输入
     * （permissionModeCli + dangerouslySkipPermissions）。
     *
     * <p>web 层 per-call 接线：{@code SendMessageRequest.permissionMode} /
     * {@code SendMessageRequest.dangerouslySkipPermissions}（HTTP 请求体，
     * CC original: {@code --permission-mode} main.tsx:1099 /
     * {@code --dangerously-skip-permissions} main.tsx:621）→ 本字段 →
     * LlmAgentLoop.doRun 组装 InitialPermissionModeResolver.Input（与 settings 磁盘 meta 合并）。
     */
    public static RunRequest session(String userPrompt, String sessionId, UUID agentId,
                                     ProviderConfig config, String modelName, String systemPrompt,
                                     String appendSystemPrompt, String fallbackModel,
                                     String permissionModeCli, boolean dangerouslySkipPermissions,
                                     TaskBudget taskBudget) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            sessionId, agentId, systemPrompt, null, taskBudget, fallbackModel, null, null,
            appendSystemPrompt, permissionModeCli, dangerouslySkipPermissions, null, null);
    }

    /**
     * [IMP-HR-08 · OPD-WF6-01-06-?-3] session 工厂重载：额外携带 jsonSchema（主循环结构化输出）。
     *
     * <p>web 层 per-call 接线：{@code SendMessageRequest.jsonSchema}（HTTP 请求体，
     * CC original: {@code --json-schema} main.tsx:1880-1883 + QueryEngine.ts:327-333）→ 本字段 →
     * LlmAgentLoop.doRun 注册主会话 structured output enforcement（STOP 门控）。
     * null = 未指定结构化输出 → 主循环不注册 enforcement（行为与现状一致）。
     */
    public static RunRequest session(String userPrompt, String sessionId, UUID agentId,
                                     ProviderConfig config, String modelName, String systemPrompt,
                                     String appendSystemPrompt, String fallbackModel,
                                     String permissionModeCli, boolean dangerouslySkipPermissions,
                                     TaskBudget taskBudget, JsonNode jsonSchema) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            sessionId, agentId, systemPrompt, null, taskBudget, fallbackModel, null, null,
            appendSystemPrompt, permissionModeCli, dangerouslySkipPermissions, jsonSchema, null);
    }

    // ── [A1 · attachment-multimodal] 附件透传工厂重载 ──

    /**
     * [A1 · attachment-multimodal] user 工厂重载：额外携带附件列表（{@link #attachments}）。
     *
     * <p>对齐 CC {@code pastedContents}（config.ts:54-62）：web 层 per-call 接线
     * {@code SendMessageRequest.attachments}（HTTP 请求体）→ 本字段 → LlmAgentLoop
     * （组装 image content block / 多模态工具路由）。null/空 = 无附件。
     */
    public static RunRequest user(String userPrompt, ProviderConfig config, String modelName, String systemPrompt,
                                  String appendSystemPrompt, String fallbackModel,
                                  String permissionModeCli, boolean dangerouslySkipPermissions,
                                  TaskBudget taskBudget, List<AttachmentRequest> attachments) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            null, null, systemPrompt, null, taskBudget, fallbackModel, null, null,
            appendSystemPrompt, permissionModeCli, dangerouslySkipPermissions, null, attachments);
    }

    /**
     * [A1 · attachment-multimodal] session 工厂重载：额外携带附件列表（ChatService 主会话 HTTP 入口）。
     *
     * <p>web 层接线：{@code SendMessageRequest.attachments} → {@link #attachments}
     * （ChatService.processUserMessage 已按 contentId 经 ImageAttachmentStore 补全
     * base64 + mediaType）。null/空 = 无附件。
     */
    public static RunRequest session(String userPrompt, String sessionId, UUID agentId,
                                     ProviderConfig config, String modelName, String systemPrompt,
                                     String appendSystemPrompt, String fallbackModel,
                                     String permissionModeCli, boolean dangerouslySkipPermissions,
                                     TaskBudget taskBudget, JsonNode jsonSchema, List<AttachmentRequest> attachments) {
        return new RunRequest(userPrompt, config, modelName, QuerySource.REPL_MAIN_THREAD,
            sessionId, agentId, systemPrompt, null, taskBudget, fallbackModel, null, null,
            appendSystemPrompt, permissionModeCli, dangerouslySkipPermissions, jsonSchema, attachments);
    }
}
