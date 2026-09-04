package com.nexusai.application.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.hook.CollapseHookSummaries;
import com.nexusai.application.agent.recovery.RecoveryState;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoItem;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * Agent Loop State · 借鉴 Claude Code query.ts 的 State 对象设计。
 *
 * <p><b>偏离标注（ER-IMP-01）</b>：本类是 CC query.ts 分离式类型的 Java 大杂烩——
 * CC 把 loop 状态拆为 {@code type State}（query.ts:204-216）、入参
 * {@code type QueryParams}（query.ts:181-198）、终止结果
 * {@code Terminal}（query.ts:227/250 泛型）三块；Java 侧合入单 AgentState，且追加
 * CC 没有的便利字段（exitReason / sessionId / agentId / budgetTracker / recoveryState /
 * hasInterruptibleToolInProgress 等）。偏离项逐字段标注 TODO（见各字段 javadoc），
 * 不做一次性拆分（属后续 session 范围）。
 *
 * <p>CC State 有 10 字段（messages / toolUseContext / autoCompactTracking /
 * maxOutputTokensRecoveryCount / hasAttemptedReactiveCompact / maxOutputTokensOverride /
 * pendingToolUseSummary / stopHookActive / turnCount / transition）。本骨架保留 6 个
 * 关键字段，其余按需补。
 *
 * <p><b>OD-5 缺失字段承载标注（CC 10 字段中未在本类出现的 4 个）</b>：
 * <ul>
 *   <li>{@code autoCompactTracking} —— 承载于 {@link com.nexusai.application.agent.compact.AutoCompactor#getTracking()}
 *       （{@code AutoCompactTrackingState} 实例），由 Spring bean per-session 宿主。
 *       不硬塞 AgentState：Java AutoCompactor 非 singleton（per-session），tracking 随实例生命周期自然隔离。</li>
 *   <li>{@code pendingToolUseSummary} —— 承载于 {@code LlmAgentLoop.loop()} 递归局部变量
 *       （LlmAgentLoop.java:2267），每个 loop 帧独立，无需挂 state。
 *       CC 挂 State 因其单进程单会话；Java 多会话同 JVM，局部变量天然会话隔离。</li>
 *   <li>{@code stopHookActive} —— 承载于 {@code LlmAgentLoop.loop()} 递归参数
 *       （LlmAgentLoop.java:2205 {@code boolean stopHookActive}），重入点传 true。
 *       CC 挂 State 因单会话；Java 递归参数会话隔离更彻底，无需 state 字段。</li>
 *   <li>{@code maxOutputTokensRecoveryCount} / {@code hasAttemptedReactiveCompact} ——
 *       承载于 {@link com.nexusai.application.agent.recovery.RecoveryState}（每次 run() 新建注入）。</li>
 * </ul>
 *
 * <p><b>OD-8 会话级语义</b>：CC 单进程=单会话，STATE 即全局；Java 单 JVM 多会话，
 * 每个 AgentState 实例等价 CC 每个进程一个 STATE——会话级隔离宿主。所有字段语义按会话级理解：
 * run() 入口新建、loop 期间可变、run() 退出即弃（或经 SessionAgentStateRegistry 持久化）。
 * budgetTracker / recoveryState / invokedSkills 等跨压缩结转字段随 AgentState 实例贯穿
 * （replaceMessages 不清除），与 CC STATE 跨压缩存活语义对齐。
 *
 * <p>关键设计（来自 s01 README · 深入 CC 源码）：
 * <ul>
 *   <li><b>needsFollowUp</b> —— CC query.ts:554-558 明确指出
 *       {@code stop_reason === 'tool_use'} 在流式响应中不可靠，因此用
 *       per-iteration flag 决定是否继续。</li>
 *   <li><b>turnCount</b> —— CC 用它做 maxTurns 检查；放在 State 上方便各 turn 共享。</li>
 *   <li><b>cancelled</b> —— 对齐 CC {@code aborted_streaming}（query.ts:1051, toolUse:false）
 *       + {@code aborted_tools}（query.ts:1515, toolUse:true）。Java 单 ExitReason.ABORTED 合并二者。</li>
 *   <li><b>finishReason</b> —— 对齐 CC line 820 {@code isWithheldMaxOutputTokens}。</li>
 *   <li><b>exitReason</b> —— 便利字段（非 CC 类型）：Terminal reason 在 CC 是 query()
 *       的返回值而非 State 字段，Java 为调用方可读终止原因保留为状态字段。
 *       Terminal reason 全集见 {@link com.nexusai.application.agent.recovery.LoopReason}。</li>
 * </ul>
 *
 * <p><b>PR 4 改动</b>：新增 {@link #sessionId} / {@link #agentId} 两个不可变字段，
 * 由 {@link LlmAgentLoop} 在创建 state 时注入，传给
 * {@link com.nexusai.application.agent.permission.PermissionContextBuilder}
 * 用于构造 {@link com.nexusai.application.agent.permission.ToolUseContext} 和
 * {@link com.nexusai.application.agent.permission.ToolPermissionContext}。旧构造器
 * {@link #AgentState(String)} 保留 —— 不传 sessionId 时权限系统会抛 IllegalArgumentException。
 */
public class AgentState {

    /** [R32-b15 Stage 2 C5] lineage 操作日志. */
    private static final Logger log = LoggerFactory.getLogger(AgentState.class);

    private final List<ChatMessageDto> messages;
    /** s01 [P2] 修补新增 · 对齐 CC utils/attachments.ts:3201 */
    private final List<AttachmentMessageDto> attachments;
    private final String systemPrompt;
    /**
     * [RES-SP31 · OPD-SP-31] 用户追加指令（恒末尾追加进 effective system prompt）· CC original:
     * {@code appendSystemPrompt}（main.tsx:1364-1382 来源链 + systemPrompt.ts:46/53/121）。
     *
     * <p>由 {@link RunRequest#appendSystemPrompt()}（源自 HTTP 请求体 SendMessageRequest）经
     * {@link LlmAgentLoop} 创建 state 时注入；LlmAgentLoop s10:2793 组装链 + CacheSharingParamsBuilder
     * fork 缓存共享消费。null = 无追加指令（行为与现状一致）。
     */
    private final String appendSystemPrompt;
    /**
     * PR 4 新增：会话级 ID（{@link com.nexusai.application.agent.permission.ToolUseContext#sessionId()} 用）。
     *
     * <p>[session-id-short] 统一为 short 形态（sess-xxx），直接承载 HTTP 路径短 id，不再经
     * parseSessionUuid 派生 UUID 串。由 {@link LlmAgentLoop} 在创建 state 时从
     * {@code ChatService.processUserMessage} 透传（sessionId 来自 HTTP path）。null 时
     * {@code PermissionContextBuilder.buildToolUseContext} 抛 {@link IllegalArgumentException}
     * （fail loud —— CLAUDE.md 规则十二）。
     */
    private final String sessionId;
    /**
     * PR 4 新增：agent 实例 ID（同 session 可有多个并行 agent 实例）。
     *
     * <p>Phase 1 与 sessionId 共用；Phase 2 起会按 subagent / parallel agent 区分。
     */
    private final UUID agentId;
    /**
     * 最大轮数 · CC original: {@code maxTurns} (query.ts:190).
     * <p><b>OD-11 对齐 CC 无默认</b>：null = 无限轮（CC 不设默认值，调用方不传则无上限）。
     * 旧默认 8 已删（三处统一：LlmAgentLoop / SubagentExecutor / ProductionForkedQuery）。
     */
    private Integer maxTurns = null;

    private int turnCount = 0;
    private boolean needsFollowUp = false;
    private boolean cancelled = false;
    /**
     * [R28] 当前 run() 的 AbortController · LlmAgentLoop.run() 构造 runAbortController 后 attach。
     * 对齐 CC REPL.tsx abortController 持有者 —— cancelSession 经 {@link #abortStream(String)}
     * abort() 硬中断在飞 LLM 流（provider chunk 边界检查立即停止，对齐 CC abort('user-cancel')）。
     * null（attach 前 / loop 外）→ abortStream 仅置协式 flag。
     */
    private volatile com.nexusai.application.agent.tool.AbortController abortController;
    /**
     * [IMP-HR-08 R2] 本 run 起始时 StructuredOutput 工具调用数 · CC QueryEngine.ts:671-672
     * {@code initialStructuredOutputCalls}（query 起始 snapshot，stop_hook_blocking 重入不重新
     * snapshot —— 与 CC 循环闭包语义一致）。
     *
     * <p>安全阀 {@code MAX_STRUCTURED_OUTPUT_RETRIES}（QueryEngine.ts:1005-1035）用
     * {@code currentCalls - initialStructuredOutputCalls} 计算本 query 内 StructuredOutput
     * 调用数，超过上限 → {@code error_max_structured_output_retries} 终止（防 STOP 全 blocking
     * 重入挂起/无限循环）。本字段仅 jsonSchema 结构化输出模式启用时设置。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 绝不
     * 序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     */
    @JsonIgnore
    private int initialStructuredOutputCalls = 0;
    /**
     * [fix-loop-resume-history] 注入的 DB 历史消息 id 集 · ChatService.replayAndPersist 跳过
     * （防重复落库）。doRun 主路径恢复（对齐 CC loadConversationForResume 全量注入）把 DB 历史
     * （含 deserializer 合成的 Continue/sentinel 消息，id 为临时 UUID）灌入 {@link #messages}
     * 后登记本集 —— replayAndPersist 遍历 state.messages() 时凡 id ∈ 本集即跳过（history 消息带
     * 原始 DB id 作 PK，重插必 duplicate-key 崩；合成 sentinel/Continue CC 也不写 transcript，
     * 语义一致）。本轮新生成的 assistant/tool 消息 id 不在集合 → 正常落库（行为不变）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 绝不
     * 序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload。null = 无历史注入
     * （全新会话 / 测试构造路径 / 非主线程）→ replayAndPersist 跳过不生效（等价现状）。
     */
    @JsonIgnore
    private java.util.Set<String> prePersistedMessageIds = null;
    /**
     * [工具调用实时推] 本 turn 已实时推送的 tool_call id 集合 · executor
     * (StreamingToolExecutor.pushToolCallRealtime) 实时推时登记, ChatService.replayAndPersist
     * 据此跳过已推 STOMP (防前端重复卡片). {@link ConcurrentHashMap#newKeySet()} 保证 fixed-8 池
     * 线程并发 add 安全. 空集合语义 = 无实时推 → 回放全推 (天然向后兼容
     * VerifyChatController / cron / 单测路径零改动, T10).
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 绝不
     * 序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     */
    @JsonIgnore
    private final java.util.Set<String> realtimeToolCallsPushed = ConcurrentHashMap.newKeySet();
    /**
     * [工具调用实时推] 本 turn 已实时推送的 tool_result id 集合 · 语义同上 (按 toolCallId).
     * 与 tool_call 集合分开登记 —— Q4 关键边界: discard 短路路径只有 tool_call 实时可推、
     * tool_result 需回放补推, 单一 boolean 一刀切会永久缺结果卡片 (R1).
     */
    @JsonIgnore
    private final java.util.Set<String> realtimeToolResultsPushed = ConcurrentHashMap.newKeySet();
    /**
     * [mid-turn-align] mid-turn 注入的排队 user 消息（busy-queued）暂存 · goal 2：注入时<b>不立即落库</b>，
     * 轮结束由 ChatService 在 replayAndPersist 之后补落库（createQueuedUserMessage，指定 id = 队列 uuid，
     * DB 顺序 = user → assistant... → queued-user）。LlmAgentLoop 工具边界 drain busy-queued 时记录
     * 原始 value（非 wrapCommandText 包裹文本）；同一注入内容镜像写 LlmAgentLoop 实例字段一份作 error
     * 逃生门（run() 抛异常时本 state 不可达，ChatService 从 {@code loop.injectedQueuedMessages()} 重新
     * enqueue 回队列）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 绝不
     * 序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     * 仅本轮生命周期有效，run() 结束后随 state 自然回收。
     */
    @JsonIgnore
    private final List<InjectedQueuedMessage> injectedQueuedMessages = new ArrayList<>();

    /**
     * [mid-turn-align] 单条 mid-turn 注入的排队 user 消息（uuid = 队列命令 id，content = 原始文本，可空串）。
     *
     * <p>[P0-1 OD-1/OD-3] 3 参扩展 + queuedOrigin（排队来源标记）：本 registry 仅登记
     * busy-queued 项（busy-queued 才需落库），queuedOrigin 供 ChatService 落库联动
     * createQueuedUserMessage(..., queuedOrigin)。2 参便捷构造器默认 queuedOrigin=null
     * （测试 / 旧调用方兼容；语义 = 非标记落库，与现状等价）。
     */
    public record InjectedQueuedMessage(String uuid, String content, String queuedOrigin) {
        public InjectedQueuedMessage(String uuid, String content) {
            this(uuid, content, null);
        }
    }
    /**
     * [IMP-HR-08 R1/R2] 本 run 的结构化输出 jsonSchema · doRun 注册 enforcement 时写入
     * （CC main.tsx:1885-1891 --json-schema 等价）。
     *
     * <p>loop() 的 R1（暴露 schema 专用 SyntheticOutputTool）与 R2（MAX_STRUCTURED_OUTPUT_RETRIES
     * 安全阀）都以本字段非 null 判定是否启用（不引入 QueryParams 字段改动）；jsonSchema 缺省 null
     * → R1 不暴露、R2 零行为变化。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 绝不
     * 序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload。
     */
    @JsonIgnore
    private com.fasterxml.jackson.databind.JsonNode structuredOutputJsonSchema = null;
    /**
     * [OD-10 Terminal 设计] 最近一次错误信息 · CC Terminal.error 等价.
     * <p>CC 中 Terminal 是 query() 返回值的 error 字段 (query.ts:227/250 Terminal 泛型).
     * Java 挂 AgentState 作便利桥 (与 exitReason 同源设计).
     * 三字段联合表征 CC Terminal: lastError (error) + finishReason (stopReason) + exitReason (reason).
     */
    private String lastError = null;
    /**
     * [OD-10 Terminal 设计] LLM 实际停止原因 · CC Terminal.stopReason 等价.
     * <p>对齐 CC choices[0].finish_reason (query.ts:820 isWithheldMaxOutputTokens).
     * 三字段联合表征 CC Terminal: lastError (error) + finishReason (stopReason) + exitReason (reason).
     */
    private String finishReason = null;   // LLM 实际停止原因 · 对齐 CC choices[0].finish_reason
    /**
     * [OD-10 Terminal 设计] 循环退出原因 · CC Terminal.reason 等价.
     * <p>[ER-IMP-01 偏离标注] 便利字段（非 CC type State 成员）：
     * CC Terminal reason 是 query() 的返回值（query.ts:227/250 Terminal 泛型），非 State 字段。
     * Java 为调用方一眼看出终止原因保留为状态字段（便利桥），Terminal 全集见 LoopReason。
     * 三字段联合表征 CC Terminal: lastError (error) + finishReason (stopReason) + exitReason (reason).
     */
    private ExitReason exitReason = null; // 循环退出原因 · 终止路径最终标签

    /**
     * [R32-b15 C11] 是否有可中断工具 (interruptBehavior="cancel") 在执行中 ·
     * 对齐 CC {@code StreamingToolExecutor.ts:254-260} updateInterruptibleState +
     * {@code ToolUseContext.hasInterruptibleToolInProgress}.
     *
     * <p>含义: 当且仅当所有当前 EXECUTING 工具的 {@code interruptBehavior()} 均为
     * {@code "cancel"} 时为 {@code true}; 一旦有任一工具不是 cancel 或工具完成/启动/中止,
     * 由 StreamingToolExecutor 调用 {@link #setHasInterruptibleToolInProgress(boolean)} 同步.
     *
     * <p>用途 (CC 同款): UI "用户中断" 按钮的可用性判定; 当 false 时用户中断会被忽略
     * (避免误中断需要等待的工具).
     *
     * <p>线程安全: 由 StreamingToolExecutor 单线程在 executeAsync 入口/出口写入,
     * 其他线程读; volatile 不必要 (本字段由 {@link com.nexusai.application.agent.LlmAgentLoop}
     * 同步读取), 故保留普通字段. 这是与 budgetTracker 的有意差异 —
     * budgetTracker 跨压缩持久化, interruptible state 仅本 turn 内有效.
     */
    private boolean hasInterruptibleToolInProgress = false;

    /**
     * [OD-17 方案 1b] 会话级压缩后标记 · CC original: {@code STATE.pendingPostCompaction}
     * (bootstrap/state.ts:256/:422/:771/:777)。
     *
     * <p><b>CC 语义</b>（state.ts:256 JSDoc）：压缩（auto / manual /compact）成功后置 true，
     * 由 logAPISuccess（Java: AnthropicSdkProvider.consumePostCompactionAtApiSuccess）消费一次，
     * 给压缩后首个 API success 事件带 {@code isPostCompaction=true} 遥测元数据，
     * 用于区分"压缩诱导的 cache miss"与"TTL 过期导致的 cache miss"（logging.ts:452/573）。
     *
     * <p><b>方案 1b 差异（会话级布尔 vs CC 进程级单布尔）</b>：CC 是进程级单布尔
     * （{@code getInitialState()} state.ts:422 初始化，单 REPL 进程=单会话）；Java 单 JVM
     * 多会话，把布尔挂到 AgentState 使每会话独立互不冲突 —— 每个会话的 AgentState 等价
     * CC 每个进程一个会话，方案 1b 等价性依据。mark（经
     * {@link com.nexusai.application.agent.compact.PostCompactionState#markPostCompaction}）
     * 写本字段，consume 读-复位-返回，一次后恒 false。
     *
     * <p><b>线程语义</b>：mark 与 consume 均发生在 LlmAgentLoop 主线程（同步流式 SSE
     * 解析，AnthropicSdkProvider 无异步执行器）→ 普通字段即可，镜像
     * {@link #hasInterruptibleToolInProgress} 先例；与 budgetTracker 跨压缩持久化
     * 不同 —— 本字段仅会话内 turn 间语义，跨 run 持久化断点见 OD-17 登记 concerns。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：与
     * {@link #budgetTracker} / {@link #currentToolUseContext} 同属本地状态，绝不序列化
     * 到 outbound DTO / STOMP / WebSocket / EventPublisher payload。
     */
    private boolean pendingPostCompaction = false;

    /**
     * s05-P1-3：自上次 TodoWrite 调用以来的 assistant turn 数。
     * 对齐 CC utils/attachments.ts:3212-3264 getTodoReminderTurnCounts 的
     * turnsSinceLastTodoWrite（CC 从消息历史反向扫描计数；Java 用 State 计数器等价实现）。
     */
    private int turnsSinceLastTodoWrite = 0;

    /**
     * s05-P1-3：自上次 todo_reminder 注入以来的 assistant turn 数。
     * 对齐 CC TODO_REMINDER_CONFIG.TURNS_BETWEEN_REMINDERS（attachments.ts:254-257）——
     * 防止达到阈值后每轮连环 nag。
     */
    private int turnsSinceLastTodoReminder = 0;

    /**
     * s11 Error Recovery：单次调用的恢复状态跟踪（每次 run() 新建）。
     *
     * <p>[ER-IMP-01 偏离标注] 便利桥字段（非 CC type State 成员）：CC 的恢复中间态
     * （maxOutputTokensRecoveryCount / hasAttemptedReactiveCompact 等）活在 query.ts:204-216
     * {@code type State} 内，withRetry 动作计数活在 withRetry.ts 闭包内——均无跨类引用。
     * Java 侧因 LlmAgentLoop 多阶段恢复（TransientErrorHandler / MaxTokensHandler /
     * ContextCollapse）需共享可变恢复上下文，故挂到 AgentState 作会话级桥。保留 + 标注理由，
     * 归属 ER-IMP-04/05 深化。
     */
    private RecoveryState recoveryState = null;

    /**
     * [R32-b15 Stage 2 C5] 当前 turn 的父 assistant message lineage 索引 ·
     * 对齐 CC {@code toolOrchestration.ts:130-139,152-172} 按
     * {@code tool_use.id} 找父 assistant message.
     *
     * <p><b>设计意图</b>: 不持久化所有 assistant 对象 (避免外溢 DTO),
     * 只在本 turn 内跟踪 tool_use_id → assistant_message_id 单值映射,
     * 用于 {@link com.nexusai.application.agent.tool.StreamingToolExecutor#add}
     * parent-aware 重载定位 lineage.
     *
     * <p><b>生命周期</b>: turn 开始调用 {@link #prepareAssistantMessageId()}
     * 生成稳定 ID + 按 add 时机 bind 到各 tool_use_id;
     * {@link #replaceMessages} 时清理 (避免 compact 后悬空, R8 风险).
     *
     * <p><b>local-only 约束 (CLAUDE.md BudgetTracker 规则)</b>:
     * 不通过 STOMP/WebSocket 上传, 不写入 LLM 请求 payload, 不出现在
     * EventPublisher payload. 仅 AgentState 内部纯本地持久化.
     */
    private final java.util.Map<String, String> assistantIdByToolUseId =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * [R32-b15 Stage 2 C5] 当前 turn 已预分配的稳定父 assistant ID ·
     * turn 开始由 {@link LlmAgentLoop} 调 {@link #prepareAssistantMessageId()}
     * 一次性生成, 整个 stream 期间不变, 最终被
     * {@code LlmAgentLoop.assistantMessageWithToolCalls} 工厂写入
     * {@link com.nexusai.model.session.dto.ChatMessageDto#id} /
     * {@link com.nexusai.model.session.dto.ChatMessageDto#assistantMessageId}.
     */
    private volatile String currentAssistantMessageId = null;

    /**
     * Stream-A5: 内容替换持久化（对齐 CC recordContentReplacement, query.ts:394）。
     * 工具结果超 MAX_TOOL_RESULT_CHARS 时, 记录 truncated → original 映射,
     * 让 LLM 后续 turn 通过 getReplacement(toolCallId) 还原完整内容.
     */
    private final java.util.Map<String, ContentReplacementEntry> contentReplacements =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 旧版构造器（Phase A 原行为）—— sessionId / agentId 为 null。
     *
     * <p>保持向后兼容 —— PR 1-3 写的老测试不需要改。调用权限系统时
     * {@link com.nexusai.application.agent.permission.PermissionContextBuilder}
     * 会抛 IAE（fail loud 保护 —— 不允许匿名 state 走权限弹窗）。
     */
    public AgentState(String systemPrompt) {
        this(systemPrompt, null, null);
    }

    /**
     * PR 4 新增构造器 · 含 sessionId / agentId。
     *
     * @param systemPrompt 系统提示（可为 null）
     * @param sessionId    会话 ID（short 形态 sess-xxx；{@code null} → 权限系统不可用）
     * @param agentId      agent 实例 ID（{@code null} → 权限系统不可用）
     */
    public AgentState(String systemPrompt, String sessionId, UUID agentId) {
        this(systemPrompt, sessionId, agentId, null);
    }

    /**
     * [RES-SP31] 构造器重载 · 含 appendSystemPrompt（用户追加指令）。
     *
     * @param systemPrompt      系统提示（可为 null）
     * @param sessionId         会话 ID（{@code null} → 权限系统不可用）
     * @param agentId           agent 实例 ID（{@code null} → 权限系统不可用）
     * @param appendSystemPrompt 用户追加指令（CC main.tsx:1364-1382 + systemPrompt.ts:121；null = 无）
     */
    public AgentState(String systemPrompt, String sessionId, UUID agentId, String appendSystemPrompt) {
        this.systemPrompt = systemPrompt;
        this.appendSystemPrompt = appendSystemPrompt;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.messages = new ArrayList<>();
        this.attachments = new ArrayList<>();
    }

    // ── accessors ──

    public List<ChatMessageDto> messages() { return messages; }

    /**
     * [2026-08-25 flow 重构] 最后一条 user 消息 id（消息链推导，对齐 CC parentUuid 链）。
     * mid-turn 注入排队 user 后 = 排队 uuid；否则 = 本轮/最近 user。供事件 userMessageId 归属
     * （LlmAgentLoop 每轮 chunk + ChatService 终态事件）与落库 lastUserMessageId 同源——多排队
     * 连续消费时每轮归属 = 该轮回答的 user，事件/落库天然一致。返回 null = 无 user（罕见）。
     *
     * [2026-08-25 健壮性] 跳过 {@code isMeta=true} user（记忆注入等随机 UUID，非真实用户消息/
     * 排队）——否则记忆注入落在末尾会成为「最后 user」，事件/落库归属指向随机 UUID 归错 flow。
     * 排队 user（human prompt / busy-queued）isMeta=false 不受影响。
     */
    public String lastUserMessageId() {
        String last = null;
        for (ChatMessageDto m : messages) {
            if (m.role() == Role.user && m.id() != null && !m.isMeta()) {
                last = m.id();
            }
        }
        return last;
    }

    /** s01 [P2] 修补新增 · 对齐 CC utils/attachments.ts:3201。不可变视图。 */
    public List<AttachmentMessageDto> attachments() {
        return java.util.Collections.unmodifiableList(attachments);
    }

    public String systemPrompt() { return systemPrompt; }

    /** [RES-SP31] 用户追加指令（null = 无）。CC original: {@code appendSystemPrompt}（systemPrompt.ts:121）。 */
    public String appendSystemPrompt() { return appendSystemPrompt; }
    public int turnCount() { return turnCount; }

    /** [IMP-HR-08 R2] 记录本 run 起始 StructuredOutput 调用数基线（CC initialStructuredOutputCalls）。 */
    public void setInitialStructuredOutputCalls(int count) {
        this.initialStructuredOutputCalls = count;
    }

    /**
     * [IMP-HR-08 R2] 读取本 run 起始 StructuredOutput 调用数基线。
     * <p><b>local-only 约束</b>: {@code @JsonIgnore} 双重保险 —— 与字段注解共同保证不序列化到外部通道。
     */
    @JsonIgnore
    public int initialStructuredOutputCalls() {
        return this.initialStructuredOutputCalls;
    }

    /**
     * [fix-loop-resume-history] 写入注入历史消息 id 集 · doRun 主路径恢复块调用
     * （对齐 CC loadConversationForResume 全量注入后登记）。
     *
     * @param v 注入历史（含合成 sentinel/Continue）的 id 集合；null = 无注入
     */
    public void setPrePersistedMessageIds(java.util.Set<String> v) {
        this.prePersistedMessageIds = v;
    }

    /**
     * [fix-loop-resume-history] 读取注入历史消息 id 集 · ChatService.replayAndPersist 消费。
     * <p><b>local-only 约束</b>: {@code @JsonIgnore} 双重保险 —— 与字段注解共同保证不序列化到外部通道。
     */
    @JsonIgnore
    public java.util.Set<String> prePersistedMessageIds() {
        return this.prePersistedMessageIds;
    }

    /**
     * [工具调用实时推] 读取已实时推送的 tool_call id 集合 · executor 实时推登记 /
     * ChatService.replayAndPersist 回放去重消费。
     * <p><b>local-only 约束</b>: {@code @JsonIgnore} 双重保险 —— 与字段注解共同保证不序列化到外部通道。
     */
    @JsonIgnore
    public java.util.Set<String> realtimeToolCallsPushed() {
        return this.realtimeToolCallsPushed;
    }

    /**
     * [工具调用实时推] 读取已实时推送的 tool_result id 集合 · 语义同上 (按 toolCallId)。
     * <p><b>local-only 约束</b>: {@code @JsonIgnore} 双重保险 —— 与字段注解共同保证不序列化到外部通道。
     */
    @JsonIgnore
    public java.util.Set<String> realtimeToolResultsPushed() {
        return this.realtimeToolResultsPushed;
    }

    /** [IMP-HR-08 R1/R2] 写入本 run 结构化输出 jsonSchema（null = 非结构化输出模式）。 */
    public void setStructuredOutputJsonSchema(com.fasterxml.jackson.databind.JsonNode jsonSchema) {
        this.structuredOutputJsonSchema = jsonSchema;
    }

    /**
     * [IMP-HR-08 R1/R2] 读取本 run 结构化输出 jsonSchema。
     * <p><b>local-only 约束</b>: {@code @JsonIgnore} 双重保险 —— 与字段注解共同保证不序列化到外部通道。
     */
    @JsonIgnore
    public com.fasterxml.jackson.databind.JsonNode structuredOutputJsonSchema() {
        return this.structuredOutputJsonSchema;
    }

    /**
     * [mid-turn-align] 记录一条 mid-turn 注入的排队 user 消息 · LlmAgentLoop 工具边界 drain
     * busy-queued 时调用（原始 value，非 wrapCommandText 包裹文本）。
     *
     * @param uuid    队列命令 id（CC attachment.source_uuid · 落库用指定 id）
     * @param content 原始排队文本（可为空串/ null）
     */
    public void addInjectedQueuedMessage(String uuid, String content) {
        this.injectedQueuedMessages.add(new InjectedQueuedMessage(uuid, content));
    }

    /**
     * [P0-1 OD-1/OD-3] 3 参重载：+ queuedOrigin（排队来源标记）。
     *
     * <p>drain busy-queued 时经本重载登记 queuedOrigin='busy-queued'，ChatService 轮末补落库
     * 据此 createQueuedUserMessage(..., queuedOrigin)。registry 仅登记 busy-queued（coordinator/
     * channel/task-notification/cron mid-turn 不落库、不登记）。
     *
     * @param uuid         队列命令 id（CC attachment.source_uuid · 落库用指定 id）
     * @param content      原始排队文本（可为空串/ null）
     * @param queuedOrigin 排队来源标记（'busy-queued'；null = 不标记）
     */
    public void addInjectedQueuedMessage(String uuid, String content, String queuedOrigin) {
        this.injectedQueuedMessages.add(new InjectedQueuedMessage(uuid, content, queuedOrigin));
    }

    /**
     * [mid-turn-align] 取出 mid-turn 注入的排队 user 消息只读视图 · ChatService 轮结束补落库消费。
     * <p><b>local-only 约束</b>: {@code @JsonIgnore} 双重保险 —— 与字段注解共同保证不序列化到外部通道。
     */
    @JsonIgnore
    public List<InjectedQueuedMessage> injectedQueuedMessages() {
        return java.util.Collections.unmodifiableList(this.injectedQueuedMessages);
    }
    /** OD-11 对齐 CC 无默认: null = 无限轮 (CC query.ts:190 不设默认值) */
    public Integer maxTurns() { return maxTurns; }
    public boolean needsFollowUp() { return needsFollowUp; }
    public boolean cancelled() { return cancelled; }
    public String lastError() { return lastError; }
    public String finishReason() { return finishReason; }
    public ExitReason exitReason() { return exitReason; }

    /**
     * PR 4 新增：会话 ID（short 形态 sess-xxx；{@code null} → state 未接入权限系统，构造器调用方负责）。
     */
    public String sessionId() { return sessionId; }

    /**
     * PR 4 新增：agent 实例 ID（{@code null} → state 未接入权限系统）。
     */
    public UUID agentId() { return agentId; }

    /**
     * [R32-b15 C11] 是否有可中断工具在执行中 · 对齐 CC
     * {@code StreamingToolExecutor.ts:254-260} updateInterruptibleState 入口同步.
     *
     * @return true 当且仅当所有当前 EXECUTING 工具的 interruptBehavior="cancel"
     */
    public boolean hasInterruptibleToolInProgress() {
        return hasInterruptibleToolInProgress;
    }

    /**
     * [R32-b15 C11] 同步设置 interruptible state · 由
     * {@link com.nexusai.application.agent.tool.StreamingToolExecutor} 在
     * executeAsync 入口/出口调用, 保证本 turn 内最新值.
     *
     * @param value 新值 (true=所有执行中工具均为 cancel 可中断, false=有不可中断或无执行中工具)
     */
    public void setHasInterruptibleToolInProgress(boolean value) {
        this.hasInterruptibleToolInProgress = value;
    }

    /**
     * [OD-17 方案 1b] 会话级压缩后标记是否 pending · CC original:
     * {@code STATE.pendingPostCompaction}（bootstrap/state.ts:256/422/771/777）。
     *
     * @return true = 压缩已发生、等待下个 API success 消费（consume 后自动复位 false）
     */
    public boolean pendingPostCompaction() {
        return this.pendingPostCompaction;
    }

    /**
     * [OD-17 方案 1b] 设置会话级压缩后标记 · 由
     * {@link com.nexusai.application.agent.compact.PostCompactionState}
     * markPostCompaction / consumePostCompaction / clear 读写（会话级隔离宿主）。
     *
     * @param value true = markPostCompaction 置位；false = consume/clear 复位
     */
    public void setPendingPostCompaction(boolean value) {
        this.pendingPostCompaction = value;
    }

    /** s05-P1-3：自上次 TodoWrite 调用以来的 assistant turn 数 */
    public int turnsSinceLastTodoWrite() { return turnsSinceLastTodoWrite; }

    /** s05-P1-3：自上次 todo_reminder 注入以来的 assistant turn 数 */
    public int turnsSinceLastTodoReminder() { return turnsSinceLastTodoReminder; }

    /**
     * s05-P1-3：记录一个 assistant turn 的 todo reminder 计数。
     *
     * <p>对齐 CC attachments.ts:3231-3246：含 TodoWrite tool_use 的 assistant
     * message 本身不计数（"we don't want to count the TodoWrite message itself
     * as 1 turn since write"）→ 计数重置为 0；否则 +1。reminder 计数每 turn +1。
     */
    void recordAssistantTurnForTodoReminder(boolean calledTodoWrite) {
        if (calledTodoWrite) {
            this.turnsSinceLastTodoWrite = 0;
        } else {
            this.turnsSinceLastTodoWrite++;
        }
        this.turnsSinceLastTodoReminder++;
    }

    /** s05-P1-3：todo_reminder 注入后重置间隔计数 · public（AgentLoopContext 跨包 static 方法调用，P3-①）。 */
    public void resetTurnsSinceLastTodoReminder() { this.turnsSinceLastTodoReminder = 0; }

    /**
     * [R32-b15 Stage 2 C5] 预分配当前 turn 的稳定父 assistant message ID ·
     * 对齐 CC streaming 路径在 {@code onAssistantMessage} 之前的
     * {@code tool_use_id} 必须能定位父 envelope 约束.
     *
     * <p>turn 开始时调用一次, 整个 stream / fallback 期间保持不变; tool_use callback
     * 到达时用此 ID 调用 {@link #bindToolUseIdToAssistantId(String, String)} 绑定.
     * 同一个 ID 在 {@code assistantMessageWithToolCalls} 工厂与所有 tool_result
     * {@code toolResultMessage} 工厂中被引用, 形成完整 lineage 链.
     *
     * @return 新分配的稳定 ID (UUID.toString)
     */
    public String prepareAssistantMessageId() {
        String id = UUID.randomUUID().toString();
        this.currentAssistantMessageId = id;
        if (log.isDebugEnabled()) {
            log.debug("[R32-b15 C5] 预分配当前 turn 父 assistant ID: id={}", id);
        }
        return id;
    }

    /**
     * [R32-b15 Stage 2 C5] 取出当前 turn 预分配的 assistant ID (可能为 null —
     * {@link #prepareAssistantMessageId} 未调用时).
     */
    public String currentAssistantMessageId() {
        return this.currentAssistantMessageId;
    }

    /**
     * [R32-b15 Stage 2 C5] 把 tool_use_id 绑定到父 assistant ID ·
     * 由 LlmAgentLoop 在 tool_use 回调或
     * {@code handleToolCallsTurn} 注册时调用.
     *
     * <p>ConcurrentHashMap 保证多线程 (streaming 回调) 并发绑定安全.
     * 同一 toolUseId 重复绑定: 后写覆盖前 (fail loud 应在调用方处理, 这里
     * 保持 map.put 简单语义).
     */
    public void bindToolUseIdToAssistantId(String toolUseId, String assistantMessageId) {
        if (toolUseId == null || toolUseId.isBlank()) return;
        if (assistantMessageId == null || assistantMessageId.isBlank()) return;
        assistantIdByToolUseId.put(toolUseId, assistantMessageId);
        if (log.isDebugEnabled()) {
            log.debug("[R32-b15 C5] lineage 绑定: toolUseId={} assistantId={}",
                abbreviate(toolUseId, 24), abbreviate(assistantMessageId, 24));
        }
    }

    /**
     * [R32-b15 Stage 2 C5] 按 tool_use_id 查找父 assistant ID ·
     * 对齐 CC {@code toolOrchestration.ts:130-139} 父查找.
     *
     * @param toolUseId 工具调用 ID
     * @return 父 assistant ID; 不在索引中时抛 {@link IllegalStateException} (CLAUDE.md
     *         规则 12 · Fail loud, 不允许静默落到"最近 assistant"猜测)
     */
    public String findAssistantIdByToolUseId(String toolUseId) {
        if (toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalArgumentException("toolUseId is null/blank");
        }
        String id = assistantIdByToolUseId.get(toolUseId);
        if (id == null) {
            throw new IllegalStateException(
                "[R32-b15 C5] lineage 查找失败: toolUseId=" + abbreviate(toolUseId, 24)
                    + " 不在当前 turn lineage 索引中, 不允许 fallback 到'最近 assistant'");
        }
        return id;
    }

    /**
     * [R32-b15 Stage 2 C5] 取 lineage 索引只读快照 · 供测试 / 审计使用.
     */
    public java.util.Map<String, String> assistantIdByToolUseId() {
        return java.util.Collections.unmodifiableMap(assistantIdByToolUseId);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "…(" + s.length() + ")";
    }

    /** s11 Error Recovery：单次调用的恢复状态（每次 run() 新建后注入） */
    public RecoveryState recoveryState() { return recoveryState; }

    /** s11 Error Recovery：设置恢复状态（由 LlmAgentLoop 在 run() 开始时注入） */
    public void setRecoveryState(RecoveryState rs) { this.recoveryState = rs; }

    /**
     * [R27-3 / R26-1] Stream-A1: Token 预算追踪器 · 跨压缩结转持久化(对齐 CC query.ts:508-515;1138-1146).
     *
     * <p>每轮 LLM 调用前 tokenBudgetChecker.checkTokenBudget 用此 tracker 计算
     * continuationCount / lastDeltaTokens, 用于 90% completion threshold + diminishing returns 判定.
     * 之前 BudgetTracker 仅作 LlmAgentLoop 实例字段, 每次 run() 重置 → 跨压缩结转不可见.
     * 改为持久化到 AgentState, 与 RecoveryState.markReactiveCompact 一同承担"压缩后保留预算上下文"语义.
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线 · OD-9）</b>：{@code @JsonIgnore} ——
     * 绝不序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     * 字段 + getter 双重保险（OD-9 对齐 CC local-only 红线）。
     */
    @JsonIgnore
    private com.nexusai.application.agent.query.TokenBudgetChecker.BudgetTracker budgetTracker = null;

    /** [R27-3 / R26-1] 持久化 BudgetTracker 到 AgentState · 跨压缩结转 */
    public void setBudgetTracker(com.nexusai.application.agent.query.TokenBudgetChecker.BudgetTracker tracker) {
        this.budgetTracker = tracker;
    }

    /**
     * [R27-3 / R26-1] 取出 BudgetTracker; null 表示未初始化.
     * <p><b>OD-9</b>: {@code @JsonIgnore} 双重保险 —— 与字段注解共同保证不序列化到外部通道。
     */
    @JsonIgnore
    public com.nexusai.application.agent.query.TokenBudgetChecker.BudgetTracker budgetTracker() {
        return this.budgetTracker;
    }

    /**
     * [ER-IMP-13] 本 turn token budget · CC original: {@code currentTurnTokenBudget}
     * (Open-ClaudeCode/src/bootstrap/state.ts:726-731) + {@code snapshotOutputTokensForTurn}
     * (REPL.tsx:2895 {@code parseTokenBudget(input) ?? getCurrentTurnTokenBudget()})。
     *
     * <p><b>预算源语义</b>: CC query.ts:1312 checkTokenBudget 第三参 = {@code getCurrentTurnTokenBudget()}
     * = 用户 prompt 的 +500k 简写解析（utils/tokenBudget.ts:21 parseTokenBudget）或 null——而非
     * context-window 恒非 null 的预算。Java 端由 LlmAgentLoop doRun 入口对 {@code params.userPrompt()}
     * 调 {@code TokenBudgetParser.parseTokenBudget} 写入本字段，checkTokenBudget 调用点读取。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 与
     * {@link #budgetTracker} / {@link #effortValue} 同属本地会话状态，绝不序列化到 outbound DTO /
     * STOMP / WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     */
    @JsonIgnore
    private Integer turnTokenBudget = null;

    /** [ER-IMP-13] 取出本 turn token budget; null 表示未设置（CC currentTurnTokenBudget 初值 null） */
    public Integer turnTokenBudget() {
        return this.turnTokenBudget;
    }

    /** [ER-IMP-13] 设置本 turn token budget（doRun 入口由 +500k 解析写入） */
    public void setTurnTokenBudget(Integer turnTokenBudget) {
        this.turnTokenBudget = turnTokenBudget;
    }

    /**
     * [ER-IMP-2026-04 P-21] 本会话累计输出 tokens · CC original: {@code getTotalOutputTokens()}
     * (Open-ClaudeCode/src/bootstrap/state.ts:708-710 {@code sumBy(Object.values(STATE.modelUsage), 'outputTokens')})。
     *
     * <p><b>进程生命周期 vs Java per-run 近似</b>: CC STATE.modelUsage 为进程级全局累计
     * （跨 query 不清零），Java AgentState 每 run 新建（LlmAgentLoop:1627），本字段随
     * AgentState 生命周期重置 → 与 CC 的"进程累计"存在偏差，按每 run 近似（与 P-22 T3
     * 兜底差异同类，JavaDoc 标注）。output_token_usage attachment 的 {@code session} 载荷
     * 读取本字段（对齐 CC attachments.ts:3838 {@code session: getTotalOutputTokens()}）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 与
     * {@link #turnTokenBudget} 同属本地会话状态，绝不序列化到 outbound DTO / STOMP /
     * WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     */
    @JsonIgnore
    private long sessionOutputTokens = 0;

    /** [ER-IMP-2026-04 P-21] 取出本会话累计输出 tokens（CC getTotalOutputTokens 的 per-run 近似） */
    public long sessionOutputTokens() {
        return this.sessionOutputTokens;
    }

    /**
     * [ER-IMP-2026-04 P-21] 累加本会话输出 tokens · 对齐 CC cost-tracker.ts:267
     * {@code modelUsage.outputTokens += usage.output_tokens}（每 message_delta 累加）。
     * 调用点：LlmAgentLoop 两处累计点（工具回合 :4614-4616 / 文本回合 :4788-4792）。
     *
     * @param delta 本次模型响应 output_tokens（<=0 不计，与 CC {@code += 0} 等价）
     */
    public void addSessionOutputTokens(long delta) {
        if (delta > 0) {
            this.sessionOutputTokens += delta;
        }
    }

    /**
     * [V-TOK-02 实施] 本会话累计输入 tokens · CC original: {@code total_input_tokens}
     * (state.ts:704-710 {@code sumBy(Object.values(STATE.modelUsage), 'inputTokens')})。
     *
     * <p><b>会话累计载体</b>：LlmAgentLoop 每 message_delta 累加（工具回合 :5819-5826 /
     * 文本回合 :6051-6058），跨 turn 由 CostTracker 经 sessions 表列 restore（会话启动）
     * + save（轮结束）持久化（multi-session-vs-cc-single-session 铁律）。message.complete 的
     * {@code totalCostUsd}/{@code modelUsage}/{@code contextTokensUsed} 装配与
     * {@link #sessionTotalTokens()} 均由此派生。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 与
     * {@link #sessionOutputTokens} 同属本地会话状态，绝不序列化到 outbound DTO / STOMP /
     * WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     */
    @JsonIgnore
    private long sessionInputTokens = 0;

    /** [V-TOK-02 实施] 取出本会话累计输入 tokens（CC total_input_tokens 的 per-run 近似 + 跨 turn restore） */
    public long sessionInputTokens() {
        return this.sessionInputTokens;
    }

    /**
     * [V-TOK-02 实施] 累加本会话输入 tokens · 对齐 CC cost-tracker.ts:267
     * {@code modelUsage.inputTokens += usage.input_tokens}（每 message_delta 累加）。
     *
     * @param delta 本次模型响应 input_tokens（<=0 不计，与 CC {@code += 0} 等价）
     */
    public void addSessionInputTokens(long delta) {
        if (delta > 0) {
            this.sessionInputTokens += delta;
        }
    }

    /**
     * [usage-push] 本轮（run）级累计 input tokens · CC original: query 级累计 totalUsage 的
     * {@code input_tokens} 分量（QueryEngine.ts:790-816 totalUsage += message.usage）。
     *
     * <p><b>run 级 vs 会话级</b>：{@link #sessionInputTokens()} 是跨 turn 会话累计（CostTracker
     * 经 sessions 表 restore/save 持久化，CC cost-tracker 进程级近似）；本字段为<b>单 run（本轮
     * query）累计</b>——AgentState 每 run 新建（LlmAgentLoop:1627），初始恒 0 自动清零，只累计
     * 3 处 withUsage append（LlmAgentLoop 纯文本/截断 + AgentLoopContext 工具轮），对齐 CC
     * message_stop 逐条累计 → turn 末 result.usage（QueryEngine totalUsage）。message.complete 的
     * usage 从本字段派生（ChatService.publishCompleteEvent 改读 runUsage()）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 与
     * {@link #sessionOutputTokens} 同属本地会话状态，绝不序列化到 outbound DTO / STOMP /
     * WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     */
    @JsonIgnore
    private long runInputTokens = 0;

    /** [usage-push] 本轮（run）级累计 output tokens（CC totalUsage.output_tokens 分量）。 */
    @JsonIgnore
    private long runOutputTokens = 0;

    /** [usage-push] 本轮（run）级累计 cache read tokens（CC totalUsage.cache_read 分量）。 */
    @JsonIgnore
    private long runCacheRead = 0;

    /** [usage-push] 本轮（run）级累计 cache creation tokens（CC totalUsage.cache_creation 分量）。 */
    @JsonIgnore
    private long runCacheCreation = 0;

    /**
     * [usage-push] 累加一条消息 usage 入 run 级累计 · 对齐 CC message_stop 逐条累计（query 级
     * totalUsage += message.usage，QueryEngine.ts:790-816）。
     *
     * <p><b>调用点</b>：LlmAgentLoop.publishMessageUsage（3 处 withUsage append 后立即，
     * 纯文本/截断/工具轮）——与推送 message.usage 事件同点累加，保证 turn 末 complete.usage =
     * 各 message.usage 之和。null → no-op（无 usage 上报的消息不污染累计）。
     *
     * @param usage 本条 assistant 消息的 provider usage（null → no-op）
     */
    public void accumulateRunUsage(AgentUsage usage) {
        if (usage == null) {
            return;
        }
        this.runInputTokens += usage.inputTokens();
        this.runOutputTokens += usage.outputTokens();
        this.runCacheRead += usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0L;
        this.runCacheCreation += usage.cacheCreationInputTokens() != null ? usage.cacheCreationInputTokens() : 0L;
    }

    /**
     * [usage-push] 本轮累计 usage · 对齐 CC query 级 totalUsage（QueryEngine.ts:790-816/:861）
     * → result.usage（message.complete usage 装配读此）。
     *
     * <p>7 参 AgentUsage（serverToolUse/serviceTier/cacheCreation 嵌套字段 null —— 累计只算 4 个
     * token 字段，嵌套字段逐条不累计；与 MessageUsageDto.from 7 参投影形状一致，CC result.usage
     * 顶层仅 token 字段累计）。恒非 null：无任何消息带 usage → 全零哨兵（complete 事件仍发 usage
     * 对象，形状稳定）。
     *
     * @return 本轮 input/output/cacheRead/cacheCreation 4 字段累计 usage
     */
    public AgentUsage runUsage() {
        return new AgentUsage(this.runInputTokens, this.runOutputTokens,
            this.runCacheCreation, this.runCacheRead, null, null, null);
    }

    /**
     * [V-TOK-02 实施] 本会话累计花费（人民币元）· CC original: {@code total_cost_usd}
     * (state.ts:704-710 / result 事件)，值用元（用户拍板：字段名对齐 CC、不换算 USD）。
     *
     * <p><b>会话累计载体</b>：LlmAgentLoop 每轮经 {@link com.nexusai.application.agent.cost.ModelCostCalculator}
     * 按 usage × 模型价格折算累加，跨 turn 由 CostTracker 经 sessions 表 {@code total_cost_yuan}
     * 列持久化（会话启动 restore + 轮结束 save）。message.complete 的 {@code total_cost_usd}
     * 字段直接读取本值。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 与
     * {@link #sessionInputTokens} 同属本地会话状态，绝不序列化到 outbound DTO / STOMP /
     * WebSocket / EventPublisher payload。
     */
    @JsonIgnore
    private double sessionCostYuan = 0;

    /** [V-TOK-02 实施] 取出本会话累计花费（元；CC total_cost_usd 的 per-run 近似 + 跨 turn restore） */
    public double sessionCostYuan() {
        return this.sessionCostYuan;
    }

    /**
     * [V-TOK-02 实施] 累加本会话花费（元）。
     *
     * @param delta 本次模型调用折算花费（元）；&lt;=0 时 cost 无意义，不计（对齐 CC cost-tracker
     *              costUSD += cost 仅在正常计费路径累加的语义）
     */
    public void addSessionCostYuan(double delta) {
        if (delta > 0) {
            this.sessionCostYuan += delta;
        }
    }

    /**
     * [V-TOK-02 实施] 本会话按模型用量桶（model → 8 字段累计）· CC original:
     * {@code STATE.modelUsage}（cost-tracker.ts:250-276 {@code addToTotalModelUsage}，
     * 键 = model，值 = {@code ModelUsage}）——CC 从桶 {@code sumBy(modelUsage, ...)} 派生
     * total_input/output_tokens（state.ts:704-710）。
     *
     * <p><b>会话累计载体</b>：LlmAgentLoop 每 message_delta 经
     * {@link CostTracker#computeModelUsageIncrement} 合并入桶，跨 turn 由 CostTracker 经
     * sessions 表 {@code model_usage_json} 列持久化（会话启动 restore + 轮结束 save）。
     * message.complete 的 {@code modelUsage} 字段直接读取本桶。
     *
     * <p><b>保序</b>：synchronizedMap(LinkedHashMap) —— 插入序 = 首次出现模型序，序列化稳定
     * （CC getUsageForModel 语义 cost-tracker.ts:258）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 与
     * {@link #sessionCostYuan} 同属本地会话状态，绝不序列化到 outbound DTO / STOMP /
     * WebSocket / EventPublisher payload。
     */
    @JsonIgnore
    private final Map<String, CostTracker.ModelUsage> sessionModelUsage =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    /** [V-TOK-02 实施] 取出本会话按模型用量桶只读视图（message.complete modelUsage 装配读此） */
    public Map<String, CostTracker.ModelUsage> sessionModelUsage() {
        return java.util.Collections.unmodifiableMap(this.sessionModelUsage);
    }

    /**
     * [V-TOK-02 实施] 合并一条模型用量增量入桶 · 对齐 CC cost-tracker.ts:250-276
     * {@code addToTotalModelUsage}（tokens/costUSD 累加，contextWindow/maxOutputTokens 取 last）。
     *
     * @param model 生效模型名（null → 跳过）
     * @param inc   本轮增量（null → 跳过）
     */
    public void mergeSessionModelUsage(String model, CostTracker.ModelUsage inc) {
        if (model == null || inc == null) {
            return;
        }
        CostTracker.ModelUsage existing = this.sessionModelUsage.get(model);
        if (existing == null) {
            this.sessionModelUsage.put(model, inc);
            return;
        }
        CostTracker.ModelUsage merged = new CostTracker.ModelUsage(
            existing.inputTokens() + inc.inputTokens(),
            existing.outputTokens() + inc.outputTokens(),
            existing.cacheReadInputTokens() + inc.cacheReadInputTokens(),
            existing.cacheCreationInputTokens() + inc.cacheCreationInputTokens(),
            existing.webSearchRequests() + inc.webSearchRequests(),
            existing.costUSD() + inc.costUSD(),
            inc.contextWindow(),      // CC 取 last
            inc.maxOutputTokens());   // CC 取 last
        this.sessionModelUsage.put(model, merged);
    }

    /**
     * [V-TOK-02 实施] 本会话 token 总量 · <b>已按 deepseek（openai）语义改 input+output</b>（A5-2）：
     * deepseek {@code input_tokens} 已含 cache hit（input == H+M），若再加 cacheRead/cacheCreation
     * 会双计。模型桶各字段求和改为 {@code input + output}（展示/汇总口径）。
     *
     * <p><b>A5-2 登记</b>: 本方法<b>无消费者（dead）</b>（全仓 grep 仅定义/JavaDoc）——直接改为
     * openai 语义 + 本注释；若日后重新接线且需支持 anthropic 场景，须按 provider 分派
     * （anthropic 会话 = input+output+cacheRead+cacheCreation），并携带模型/协议上下文。
     */
    public long sessionTotalTokens() {
        long total = 0;
        synchronized (this.sessionModelUsage) {
            for (CostTracker.ModelUsage u : this.sessionModelUsage.values()) {
                total += u.inputTokens() + u.outputTokens();
            }
        }
        return total;
    }

    /**
     * [IMP-SP-02] run 级 system prompt section 缓存 · CC original: {@code systemPromptSectionCache}
     * (Open-ClaudeCode/src/bootstrap/state.ts:203/:399/:1641-1653)。
     *
     * <p><b>run 级事实（非会话级）</b>：AgentState 每 run 新建
     * （LlmAgentLoop:1627 {@code new AgentState(...)}，SessionAgentStateRegistry.register 每 run
     * 覆盖旧实例），本缓存随 AgentState 同生命周期 → 跨 run 命中恒 0，缓存仅服务于单 run 内
     * 的 section 组装（SP-09 口径）。CC state.ts:399 为进程级全局 Map，Java 以每 run 实例隔离
     * 达成等价语义（无跨会话串味）。由
     * {@link com.nexusai.application.agent.prompt.SystemPromptSectionRegistry#resolveAll} 读写
     * （per-section name-keyed，null 值也缓存），/clear、/compact、工具注册三触发点已接线
     * 直调 {@code clear()}（CommandController:333 / PostCompactCleanup:277 /
     * ToolRegistrationConfig:310，对齐 CC clearSystemPromptSections 的失效语义，SP-03 S-3）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：
     * {@code @JsonIgnore} —— 与 {@link #budgetTracker} / {@link #currentToolUseContext} /
     * {@link #invokedSkills} 同属 local-only 状态，绝不序列化到 outbound DTO / STOMP /
     * WebSocket / EventPublisher payload，绝不写入 LLM 请求 payload。
     */
    @JsonIgnore
    private final com.nexusai.application.agent.prompt.SystemPromptSectionCache systemPromptSectionCache =
        new com.nexusai.application.agent.prompt.SystemPromptSectionCache();

    /** [IMP-SP-02] 取出本 run 的 system prompt section 缓存（字段初始化器，非 null） */
    public com.nexusai.application.agent.prompt.SystemPromptSectionCache systemPromptSectionCache() {
        return this.systemPromptSectionCache;
    }

    /**
     * [C-31] 会话级 effort 值 · CC original: {@code appState.effortValue}
     * (Open-ClaudeCode/src/query.ts:694 / src/tools/SkillTool/SkillTool.ts:832 /
     * src/utils/effort.ts EffortValue)。
     *
     * <p><b>写入侧</b>: {@code SkillToolImpl.buildContextModifier} (c) effort 分支
     * （对齐 CC SkillTool.ts:823-836 {@code getAppState(){return {...appState, effortValue: effort}}}）
     * 在写入 appStateRef KEY_EFFORT_VALUE 的同时同步本字段，保证两处不漂移。
     *
     * <p><b>消费侧</b>: {@code LlmAgentLoop} ModelRequest 构造（:2762 等价 CC query.ts:694
     * {@code options.effortValue: appState.effortValue} 逐轮注入）→ {@code ModelRequest.effortValue()}
     * → {@code ModelCaller} → provider buildRequestBody（CC claude.ts:1458 resolveAppliedEffort）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>: {@code @JsonIgnore} —— 与
     * {@link #budgetTracker} / {@link #currentToolUseContext} / {@link #invokedSkills} 同属
     * local-only 状态，绝不序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload。
     * 跨压缩结转：本字段随 AgentState 实例贯穿压缩（{@link #replaceMessages} 不清除，budgetTracker
     * 先例 :354-364），effort 值在压缩后继续作用于后续 LLM 调用（CC appState 会话级语义）。
     */
    @JsonIgnore
    private String effortValue = null;

    /**
     * 会话级 agent 颜色 · CC original: {@code standaloneAgentContext.color}
     * (Open-ClaudeCode/src/commands/color/color.ts:53-60/82-89)。
     *
     * <p><b>GAP-2 修复（探查 EV-CM-011）</b>：CC color.ts 写入的 {@code standaloneAgentContext.color}
     * （AppState 会话级颜色状态）在 Java 无对应状态载体 → 挂到会话级 AgentState（OD-8 语义：
     * Java AgentState 实例等价 CC 每进程一个 STATE），由 /color 命令的 {@code setAppStateColor}
     * 写侧写入。
     * 【IMP-SUB-04 REWORK】注意与 {@code SubagentTool.getAgentColor} 解耦：本字段（会话级
     * standaloneAgentContext.color）与 SubagentTool.agentColors map（CC agentColorMap）在 CC 也是
     * 两个独立存储（color.ts 写 standalone、setAgentColor 写 agentColorMap），互不消费；当前
     * 本字段无读侧消费方（显式登记为待前端消费）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 与
     * {@link #budgetTracker} / {@link #effortValue} / {@link #currentModel} 同属本地会话状态，
     * 绝不序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload。
     */
    @JsonIgnore
    private volatile String color = null;

    /** [D4] 取出会话级 agent 颜色；null = 未设置（默认色）。 */
    public String color() {
        return this.color;
    }

    /** [D4] 设置会话级 agent 颜色（/color 命令 setAppStateColor 写侧）。 */
    public void setColor(String color) {
        this.color = color;
    }

    /**
     * [commands-real-exec] 会话级 plan 模式开关 · CC original: {@code appState.toolPermissionContext.mode === 'plan'}
     * （commands/plan/plan.tsx:70 读取 {@code getAppState().toolPermissionContext.mode}）。
     *
     * <p><b>写入侧</b>：{@code /plan} 命令 handler（CommandRegistrationConfig28.registerPlanHandler）——
     * 对齐 CC plan.tsx:73-82（非 plan 模式 → {@code setAppState(... toolPermissionContext: {mode:'plan'})}）：
     * 本字段承载 web 会话级的 plan 模式标志。true = 会话已启用 plan 模式；false/null = 未启用。
     *
     * <p><b>受控差异（fail loud）</b>：CC plan.tsx 写入的 appState.toolPermissionContext 是
     * {@link com.nexusai.application.agent.permission.ToolPermissionContext} 对象（EnterPlanModeTool 消费）；
     * Java 端 {@code /plan} 命令（web 后端无 ToolUseContext）以布尔标志近似表达会话 plan 意图，
     * 实际 PLAN permission 切换仍经 {@code EnterPlanModeTool} 在 agent loop 内完成 —— 本字段是会话级
     * 记录，loop 初始 mode 解析链（InitialPermissionModeResolver → settings.defaultMode）暂不读本字段
     * （登记差异，loop 消费接线归后续）。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} —— 与
     * {@link #budgetTracker} / {@link #color} / {@link #effortValue} 同属本地会话状态，
     * 绝不序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload。
     */
    @JsonIgnore
    private volatile boolean planMode = false;

    /** [commands-real-exec] 取出会话级 plan 模式开关（true = 会话已启用 plan 模式）。 */
    public boolean planMode() {
        return this.planMode;
    }

    /** [commands-real-exec] 设置会话级 plan 模式开关（/plan 命令 setPlanMode 写侧）。 */
    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
    }

    /**
     * [todo-rest-stream] 会话 todo 桶 · CC original: {@code appState.todos[todoKey]}
     * (TodoWriteTool.ts:65-94，key=context.agentId ?? getSessionId())。
     *
     * <p><b>REST 读侧载体</b>：{@link SessionAgentStateRegistry}（sessions map，short sessionId）
     * 持有的主会话 AgentState，TodoStatusController GET /api/v1/sessions/{sessionId}/todos 经
     * {@code registry.get(sessionId).todos()} 读取。V1 内存版：todo 数据只在最近一次 send 的
     * AgentState 内存活（每 run 新建 AgentState，LlmAgentLoop doRun 覆盖注册）——[R3] 持久化
     * 才是跨 send 持久解。
     *
     * <p><b>与 appStateRef 解耦</b>：appStateRef（LlmAgentLoop）供 loop 自身 todo reminder 读；
     * 本字段供 REST/前端读，由 {@link TodoWriteTool#execute} Step5 后同一存储点双写不漂移。
     *
     * <p><b>local-only 红线（CLAUDE.md BudgetTracker 架构）</b>：{@code @JsonIgnore} —— 与
     * {@link #budgetTracker} / {@link #effortValue} / {@link #color} 同属本地会话状态，
     * 绝不序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload
     * （budgetTracker 先例）。
     *
     * <p><b>包级循环引用说明</b>：AgentState ↔ TodoWriteTool 相互 import（本类引用 TodoItem
     * 静态嵌套 record，TodoWriteTool 引用 AgentState）——TodoItem 为静态嵌套 record，
     * 无类初始化循环，Java 编译运行时安全。
     */
    @JsonIgnore
    private final ConcurrentHashMap<String, List<TodoItem>> todos = new ConcurrentHashMap<>();

    /** [todo-rest-stream] 取出会话 todo 桶（todoKey → 不可变快照列表；无桶 → 空）。 */
    public Map<String, List<TodoItem>> todos() {
        return this.todos;
    }

    /**
     * [todo-rest-stream] 写入会话 todo 桶。
     *
     * <p>todoKey null 忽略；items null 视为 {@code List.of()}（对齐 CC allDone 空数组语义
     * TodoWriteTool.ts:70/:92）；内部 {@code List.copyOf(items)} 不可变快照防并发半读
     * （StreamingToolExecutor 工具线程并发执行）。
     */
    public void setTodos(String todoKey, List<TodoItem> items) {
        if (todoKey == null) {
            return;
        }
        List<TodoItem> snapshot = items == null ? List.of() : List.copyOf(items);
        this.todos.put(todoKey, snapshot);
    }

    /** [C-31] 取出会话级 effort 值; null 表示未设置（LLM 请求不注入 effort） */
    public String effortValue() {
        return this.effortValue;
    }

    /** [C-31] 设置会话级 effort 值（SkillToolImpl contextModifier / SubagentExecutor fork 注入） */
    public void setEffortValue(String effortValue) {
        this.effortValue = effortValue;
    }

    /**
     * [RES-C7 + RES-L1] 会话当前模型 · CC original: {@code toolUseContext.options.mainLoopModel}
     * (Open-ClaudeCode/src/tools/AgentTool/resumeAgent.ts:131)。
     *
     * <p>对齐 CC 语义：resume fork 时取 <b>resume 时刻的当前会话模型</b>（非 spawn 时持久化模型）。
     * CC 的 {@code options.mainLoopModel} 是主循环每轮解析的运行时模型（query.ts:572
     * {@code currentModel = getRuntimeMainLoopModel({...mainLoopModel: toolUseContext.options.mainLoopModel})}
     * 每次进入 query() 从 options.mainLoopModel 读最新值）。Java 等价：
     * <ul>
     *   <li>{@link LlmAgentLoop#doRun} 入口初始写入（{@code getModelForCall()} → modelName）</li>
     *   <li>{@code loop()} 每轮 turn 解析 effectiveModel 后覆盖写（
     *       {@code state.setCurrentModel(effectiveModel)}，对齐 CC 每轮读最新值语义）</li>
     * </ul>
     *
     * <p><b>消费侧</b>: {@link com.nexusai.application.agent.subagent.ResumeService}
     * {@code rebuildForkParentSystemPrompt} 读本字段（对齐 CC resumeAgent.ts:131）。
     * [#25] 原 meta.model() fallback（spawn 持久化扩展字段，web 跨进程恢复兜底）已删 ——
     * CC AgentMetadata 无 model 字段（sessionStorage.ts:264-272），模型一律现算；
     * 本字段不可得 → null → ResumeService 抛 "Cannot resume fork agent"（fail loud）。
     *
     * <p><b>local-only 约束</b>: {@code @JsonIgnore} —— 与 {@link #budgetTracker} /
     * {@link #effortValue} 同属本地会话状态，绝不序列化到 outbound DTO。
     */
    @JsonIgnore
    private volatile String currentModel = null;

    /**
     * [RES-C7] 取出会话当前模型（CC resumeAgent.ts:131 options.mainLoopModel）；
     * [#25] null = 未设置 → ResumeService 抛 "Cannot resume fork agent"（原 meta.model() fallback 已删，
     * CC AgentMetadata 无 model 字段，模型一律现算）。
     */
    public String currentModel() {
        return this.currentModel;
    }

    /** [RES-C7 + RES-L1] 设置会话当前模型（LlmAgentLoop.doRun() 初始写入 + loop() 每轮覆盖写） */
    public void setCurrentModel(String currentModel) {
        this.currentModel = currentModel;
    }

    /**
     * [IMP-SP-05] 会话冻结日期 · CC original: {@code getSessionStartDate}
     * (Open-ClaudeCode/src/constants/common.ts:24，memoize(getLocalISODate))。
     *
     * <p>构造时取本地日（{@code "YYYY-MM-DD"}，对齐 CC common.ts:4-15 getLocalISODate；
     * {@code CLAUDE_CODE_OVERRIDE_DATE} 可覆盖）。随 AgentState 实例贯穿压缩
     * （{@link #replaceMessages} 不清除，budgetTracker 先例 :354-364），userContext 的
     * currentDate 经本字段渲染 —— 跨午夜不陈旧（I-10），prompt cache-key 稳定。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：{@code @JsonIgnore} ——
     * 与 {@link #budgetTracker} / {@link #effortValue} 同属本地会话状态，绝不序列化到
     * outbound DTO / STOMP / WebSocket / EventPublisher payload。
     */
    @JsonIgnore
    private final String sessionStartDate = localIsoDate();

    /** [IMP-SP-05] 取出会话冻结日期（构造时定格，跨午夜不陈旧 · CC original: getSessionStartDate） */
    public String sessionStartDate() {
        return this.sessionStartDate;
    }

    /**
     * [IMP-SP-05] 本地 ISO 日期 · 对齐 CC {@code getLocalISODate}
     * (Open-ClaudeCode/src/constants/common.ts:4-15)：CLAUDE_CODE_OVERRIDE_DATE 可覆盖，
     * 否则本地 {@code YYYY-MM-DD}。
     */
    private static String localIsoDate() {
        String override = System.getenv("CLAUDE_CODE_OVERRIDE_DATE");
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return java.time.LocalDate.now().toString();
    }

    // ── [H6-FIX] Stop hook summary 本地暂存（UI/transcript 呈现源 · 绝不进入 state.messages()）──
    // WHY: CC createStopHookSummaryMessage (messages.ts:4398-4420) yield 给 UI transcript
    //   (stopHooks.ts:299)，Java 端 append 进 state.messages() 会被 OpenAI provider 原样序列化发给
    //   LLM 污染上下文（R32C1 证实 isMeta 不影响 provider 序列化，CHANGELOG 0.2.29 ⑥）→ 本地暂存
    //   + collapse 折叠，由 transcript/UI 层读取（AgentLoopExitedEvent.state() 可访问）。
    // [合并说明 2026-08-08] 远程 ab54f3cc 曾以"0 消费方"删除本通道；FOLLOWUP 批 EX-D（09 §7.5 R6）
    //   恢复消费方（LlmAgentLoop Stop 段 executeStopHooksCollecting → recordStopHookSummary，
    //   对齐 CC stopHooks.ts:175-333），故合并保留本通道。
    @JsonIgnore
    private final List<CollapseHookSummaries.HookMessage> stopHookSummaries =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * [H6-FIX] 记录一条 Stop hook summary。
     *
     * <p><b>WHY 生产调用点</b>: {@link CollapseHookSummaries#collapse} 此前 0 生产调用（CHANGELOG
     * 0.2.29 H6-3）；CC collapseHookSummaries.ts 在 UI 呈现前折叠连续同 label 的 stop_hook_summary。
     * 本方法每次记录即先 collapse 再存，保证消费端读到的一定是折叠形态（max duration / 合并 errors /
     * OR preventedContinuation + hasOutput）。
     *
     * <p><b>[IMP-HOOKS-S7 H6/D7 登记]</b>: 生产 Stop/SubagentStop 摘要现以 hookLabel=null 记录
     * （LlmAgentLoop :4534/:4775，对齐 CC stopHooks.ts:297-308 8 参无 hookLabel → undefined）——
     * isHookSummary 守卫（hookLabel != null）不过 → 折叠机器在本通道上恒为恒等变换，直至带 label
     * 的 Pre/PostToolUse 摘要生产者接入（CC toolExecution.ts:874-891/1546-1563，T6 域）。通道本体
     * 按 open-decisions #10 裁决保留为数据通道（无 UI 消费端，D7 标 N/A），不改代码逻辑。
     *
     * @param summary 单条 stop_hook_summary 契约消息（CollapseHookSummaries.SimpleHookMsg）
     */
    public void recordStopHookSummary(CollapseHookSummaries.HookMessage summary) {
        if (summary == null) return;
        List<CollapseHookSummaries.HookMessage> updated = new ArrayList<>(stopHookSummaries);
        updated.add(summary);
        List<CollapseHookSummaries.HookMessage> collapsed = CollapseHookSummaries.collapse(updated);
        stopHookSummaries.clear();
        stopHookSummaries.addAll(collapsed);
    }

    /** [H6-FIX] 当前折叠后的 Stop hook summaries（只读 · UI/transcript 消费源）。 */
    public List<CollapseHookSummaries.HookMessage> stopHookSummaries() {
        return List.copyOf(stopHookSummaries);
    }

    /** [H7-arch Phase 5-2 A2] 当前轮 ToolUseContext · 对齐 CC {@code state.toolUseContext}
     * (query.ts:1715-1727)。
     *
     * <p>loop 每轮 {@code toolExecContext(state, queryTracking)} 构建后 stamp 到本字段；
     * SubagentTool 经主循环 {@code mainLoop.getCurrentToolUseContext()} 读为 parentTUC
     * （fork 链在 {@code ToolUseContext.with()} 产生新 chainId + depth+1，对齐 CC forkedAgent.ts:451-455）。
     *
     * <p>{@code @JsonIgnore}：绝不序列化到外部（TUC 含 UI callback / session 引用，与
     * budgetTracker 同属 local-only 状态，跨 turn 持有、跨压缩结转——见 CLAUDE.md
     * "BudgetTracker 架构 local-only 约束"）。
     */
    @JsonIgnore
    private volatile ToolUseContext currentToolUseContext = null;

    /** [H7-arch Phase 5-2 A2] 取出当前轮 TUC; 未 run 或未构建时为 null */
    public ToolUseContext currentToolUseContext() {
        return this.currentToolUseContext;
    }

    /** [H7-arch Phase 5-2 A2] 设置当前轮 TUC（loop 每轮 stamp，对齐 CC state.toolUseContext） */
    public void setCurrentToolUseContext(ToolUseContext ctx) {
        this.currentToolUseContext = ctx;
    }

    /**
     * [P1-6-STATE-1] 已调用 skill 记录 · 对齐 CC {@code STATE.invokedSkills}
     * (bootstrap/state.ts:178-187).
     *
     * <p>复合键 {@code `${agentId ?? ''}:${skillName}`} 防止跨 agent 覆盖
     * (CC state.ts:177 注释 "Keys are composite: `${agentId ?? ''}:${skillName}`
     * to prevent cross-agent overwrites")。键构造<b>唯一收敛点</b>在
     * {@link #addInvokedSkill}，写入方（P1-6）不得各自拼键 ——
     * getInvokedSkillsForAgent / clearInvokedSkills 过滤依赖条目内 agentId 字段，
     * 与键格式正交。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>：
     * {@code @JsonIgnore} —— 绝不序列化到 outbound DTO / STOMP / WebSocket /
     * EventPublisher payload。与 {@link #budgetTracker} / {@link #currentToolUseContext}
     * 同属跨压缩持久化通道：压缩后 {@link #replaceMessages} 不清除本 map
     * （对齐 CC postCompactCleanup.ts:17-20 刻意保留 invokedSkills 策略），
     * 依赖同一 AgentState 实例贯穿压缩（budgetTracker 先例 LlmAgentLoop 复用）。
     *
     * <p>并发：ConcurrentHashMap —— 流式 tool_use 回调（写入侧 P1-6）与压缩主循环
     * 跨线程读写安全；与既有可变 Map 字段（assistantIdByToolUseId:132 /
     * contentReplacements:150 / structuredOutputs:391）规范一致（CLAUDE.md 规则十一）。
     */
    @JsonIgnore
    private final java.util.Map<String, InvokedSkillInfo> invokedSkills =
        new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * invokedAt 单调递增序列 · 同毫秒多次 {@link #addInvokedSkill} 时（生产流式回调 /
     * 测试批量注入均常见），{@code System.currentTimeMillis()} 碰撞导致
     * {@code createSkillAttachmentIfNeeded} 的 invokedAt 降序排序退化为 Map 迭代序
     * （ConcurrentHashMap 无序 → 预算丢弃最旧条目的决策随机化）。max(prev+1, now)
     * 保证严格单调：时间戳语义不变（仅碰撞时 +1ms），对齐 CC Date.now() 行为 +
     * 测试注释「invokedAt 单调递增」的既有假设。
     */
    @JsonIgnore
    private final java.util.concurrent.atomic.AtomicLong lastInvokedAt =
        new java.util.concurrent.atomic.AtomicLong();

    private final java.util.Map<String, java.util.Map<String, Object>> structuredOutputs =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * b14 本地暂存：工具执行器异步完成后，把 structured_output 按 toolUseId 交给
     * LlmAgentLoop 的 tool_result 消息工厂（ChatMessageDto.structuredOutput 载体注入）。
     * [IT-6] 该载体不再被 provider 序列化发模型；结构化载荷的模型侧呈现走 attachment 通道——
     * {@link #appendAttachment} structured_output attachment 已由 ToolResultApplier 同步落地。
     * 该 map 不属于 outbound DTO。
     */
    public void recordStructuredOutput(String toolUseId, java.util.Map<String, Object> output) {
        if (toolUseId == null || toolUseId.isBlank() || output == null || output.isEmpty()) {
            return;
        }
        structuredOutputs.put(toolUseId,
            java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(output)));
    }

    /** 取出并移除一次性 structured_output，避免跨 turn 重复注入。 */
    public java.util.Map<String, Object> takeStructuredOutput(String toolUseId) {
        if (toolUseId == null) return java.util.Map.of();
        java.util.Map<String, Object> output = structuredOutputs.remove(toolUseId);
        return output == null ? java.util.Map.of() : output;
    }

    /** 当前暂存载荷只读视图，供测试/审计使用。 */
    public java.util.Map<String, java.util.Map<String, Object>> structuredOutputs() {
        return java.util.Collections.unmodifiableMap(structuredOutputs);
    }

    // ── IMP-WF3-TC-01 · classifier 自动批准规则（matchedRule）按 toolUseId 暂存 ──
    /**
     * classifier 自动批准规则（bash matchedRule）· 对齐 CC
     * {@code classifierApprovals.ts} 的 {@code CLASSIFIER_APPROVALS} Map（keyed by toolUseID）。
     *
     * <p>写入侧：{@code StreamingToolExecutor.releaseClassifierApproval}（工具成功出口，
     * 读取 {@code getClassifierApproval} 后先按 toolUseId 暂存本 map，再对 CC store 做
     * deleteClassifierApproval 服务端清理——CC 在 UI 渲染时 getClassifierApproval 显示
     * "Auto-approved · matched "rule""（UserToolSuccessMessage.tsx:47-50）后 delete）。
     * 读取侧：{@code AgentLoopContext.handleToolCallsTurn} 构建 role=tool 工具结果 payload
     * （ChatMessageDto.matchedRule）时 {@link #takeClassifierMatchedRule} 取走一次性注入。
     *
     * <p>本地暂存，不进入 outbound DTO 之外的通道（CC original: matchedRule,
     * classifierApprovals.ts:11）。
     */
    @JsonIgnore
    private final java.util.Map<String, String> classifierMatchedRules =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 记录 classifier 自动批准规则（matchedRule）按 toolUseId 暂存 · null/blank 跳过。 */
    public void recordClassifierMatchedRule(String toolUseId, String matchedRule) {
        if (toolUseId == null || toolUseId.isBlank() || matchedRule == null || matchedRule.isBlank()) {
            return;
        }
        classifierMatchedRules.put(toolUseId, matchedRule);
    }

    /** 取出并移除一次性 classifier 自动批准规则，避免跨 turn 重复注入 · 无则返回 null。 */
    public String takeClassifierMatchedRule(String toolUseId) {
        if (toolUseId == null) return null;
        return classifierMatchedRules.remove(toolUseId);
    }

    // ── [fix-toolcalls-400 C] 工具 newMessages 按 toolUseId 延迟落地（CC toolExecution.ts:1478 addToolResult 先 / :1566 newMessages 后）──
    /**
     * 工具返回的 {@code newMessages}（如 Read pdf pages 的 isMeta image user 消息）按 toolUseId
     * 暂存，延迟到 {@code AgentLoopContext.handleToolCallsTurn} step 3 该工具 {@code tool_result}
     * append 之后 flush —— 对齐 CC toolExecution.ts:1478 addToolResult（产出含 tool_result 的 user
     * 消息）→ :1566-1570 push result.newMessages（页图消息）。
     *
     * <p><b>WHY（根因）</b>: 旧实现 {@code ToolResultApplier.apply} 在工具执行 dispatch 期就
     * {@code state.messages().addAll(tr.newMessages())}，早于 step 3 才 append 的 {@code tool_result}
     * → state.messages 顺序变成 [assistant(tool_calls), user(isMeta 页图), tool(tool_result)]。
     * provider 原序透传 → assistant tool_calls 后夹 image user 消息 → Anthropic 400
     * "assistant message with tool_calls must be followed by tool messages"。本 map 让 newMessages
     * 先暂存，待各自 tool_result 落地后再 flush。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>: {@code @JsonIgnore} —— 绝不
     * 序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload。与
     * {@link #structuredOutputs} / {@link #classifierMatchedRules} 同属 turn 级一次性暂存（take 后
     * 移除，handleToolCallsTurn 末尾 drain 兜底清空 → 不跨 turn 泄漏）。
     */
    @JsonIgnore
    private final java.util.Map<String, List<ChatMessageDto>> pendingNewMessagesByToolUseId =
        new ConcurrentHashMap<>();

    /** 工具执行期暂存 newMessages（ToolResultApplier 调用）· null/blank/empty 跳过。 */
    public void stashNewMessages(String toolUseId, List<ChatMessageDto> newMessages) {
        if (toolUseId == null || toolUseId.isBlank()
                || newMessages == null || newMessages.isEmpty()) {
            return;
        }
        // merge：同一 toolUseId 多处 handler（如 hook 普通消息并入 newMessages）累积，保序。
        pendingNewMessagesByToolUseId.merge(toolUseId, new ArrayList<>(newMessages),
            (old, add) -> {
                List<ChatMessageDto> merged = new ArrayList<>(old);
                merged.addAll(add);
                return merged;
            });
    }

    /** 取出并移除某 toolUseId 的暂存 newMessages（handleToolCallsTurn step 3 flush 后一次性消费）· 无则空 List。 */
    public List<ChatMessageDto> takeNewMessages(String toolUseId) {
        if (toolUseId == null) return List.of();
        List<ChatMessageDto> msgs = pendingNewMessagesByToolUseId.remove(toolUseId);
        return msgs == null ? List.of() : msgs;
    }

    /** 是否仍有未消费的暂存 newMessages（handleToolCallsTurn 末尾兜底 drain 判定用）。 */
    public boolean hasPendingNewMessages() {
        return !pendingNewMessagesByToolUseId.isEmpty();
    }

    /** 当前暂存只读视图，供测试/审计使用。 */
    public java.util.Map<String, List<ChatMessageDto>> pendingNewMessagesByToolUseId() {
        return java.util.Collections.unmodifiableMap(pendingNewMessagesByToolUseId);
    }

    // ── Stream-A5 内容替换持久化 (对齐 CC recordContentReplacement, query.ts:394) ──
    /**
     * 单条内容替换记录 · 完整原文 + 截断版本。
     */
    public record ContentReplacementEntry(String toolCallId, String original, String truncated, long replacedAt) {
    }

    /**
     * 记录一条工具结果的内容替换（truncated → original 映射）。
     * <p>调用方传入 truncated 字符串以便将来对比; LLM 可通过
     * {@link #getContentReplacement(String)} 还原完整内容。
     */
    public void recordContentReplacement(String toolCallId, String original, String truncated) {
        if (toolCallId == null) return;
        contentReplacements.put(toolCallId,
            new ContentReplacementEntry(toolCallId, original, truncated, System.currentTimeMillis()));
    }

    /**
     * 取回一条被截断的工具结果完整内容 · null 表示未截断或已清理。
     */
    public ContentReplacementEntry getContentReplacement(String toolCallId) {
        return toolCallId == null ? null : contentReplacements.get(toolCallId);
    }

    /** 当前所有内容替换记录（只读）。 */
    public java.util.Map<String, ContentReplacementEntry> contentReplacements() {
        return java.util.Collections.unmodifiableMap(contentReplacements);
    }

    // ── [P1-6-STATE-1] invokedSkills 持久化 (对齐 CC STATE.invokedSkills, bootstrap/state.ts:1502-1563) ──
    // WHY: skill 内容须跨压缩存活用于重注入 (CC postCompactCleanup 刻意不清理 invokedSkills),
    //   且多 agent (主会话 + forked 子 agent) 不得互相覆盖 → 复合键 + agentId 过滤.
    /**
     * 单条已调用 skill 记录 · 对齐 CC {@code InvokedSkillInfo} type
     * (bootstrap/state.ts:1502-1508)。
     *
     * @param skillName CC original: skillName (state.ts:1503) — skill 名，复合键后段
     * @param skillPath CC original: skillPath (state.ts:1504) — 缓存文件路径，压缩重注入定位来源
     * @param content   CC original: content (state.ts:1505) — 替换后的 skill 全文
     *                  （含 base directory 头 / ${CLAUDE_SKILL_DIR} 替换结果，SkillTool.ts:1088）
     * @param invokedAt CC original: invokedAt (state.ts:1506) — 调用毫秒时间戳（对齐 Date.now()）
     * @param agentId   CC original: agentId (state.ts:1507, string | null) —
     *                  null=主会话；UUID 非 null=forked 子 agent 实例 ID
     */
    public record InvokedSkillInfo(String skillName, String skillPath, String content,
                                   long invokedAt, UUID agentId) {
    }

    /**
     * 记录一次 skill 调用 · 对齐 CC {@code addInvokedSkill}
     * (bootstrap/state.ts:1510-1524)。
     *
     * <p>复合键 {@code `${agentId ?? ''}:${skillName}`} —— 同 skillName 不同 agentId
     * 并存不覆盖；同 agentId 同名后写覆盖（map size 保持）。无校验无抛错
     * （CC 原样，无 guard）。
     *
     * @param agentId CC original: agentId（默认参 null）—— null 表示主会话作用域
     */
    public void addInvokedSkill(String skillName, String skillPath, String content, UUID agentId) {
        String key = (agentId == null ? "" : agentId.toString()) + ":" + skillName;
        // 单调递增保证: 同毫秒批量调用时 currentTimeMillis 碰撞 → max(prev+1, now) 严格递增
        // (见 lastInvokedAt javadoc), 排序/预算丢弃决策确定性化
        long invokedAt = lastInvokedAt.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));
        invokedSkills.put(key, new InvokedSkillInfo(skillName, skillPath, content, invokedAt, agentId));
        if (log.isDebugEnabled()) {
            log.debug("[P1-6-STATE-1] addInvokedSkill: key={} skillName={} agentId={}",
                abbreviate(key, 24), abbreviate(skillName, 24), agentId);
        }
    }

    /** 主会话便捷重载 · 等价 CC 默认参 agentId=null（resume 路径 conversationRecovery.ts:391 恒 null）。 */
    public void addInvokedSkill(String skillName, String skillPath, String content) {
        addInvokedSkill(skillName, skillPath, content, null);
    }

    /**
     * 取出全部 invokedSkills 只读视图 · 对齐 CC {@code getInvokedSkills}
     * (bootstrap/state.ts:1526-1528，直接返回内部 Map 引用)。
     *
     * <p>CC 该函数全库零调用方（仅定义处），只读视图是 Java 规范安全选择
     * （对齐 {@link #attachments()} attachments():184 / {@link #contentReplacements()}
     * contentReplacements():477 只读视图先例），无行为差异面。
     *
     * <p><b>local-only 红线（CLAUDE.md BudgetTracker 架构 · M-7 补齐）</b>：
     * {@code @JsonIgnore} —— 与字段级 :743 注解构成双重保险（budgetTracker 先例
     * :488/:500「字段 + getter 双 @JsonIgnore」），保证 invokedSkills（skill 全文，
     * 可能含敏感项目内容）绝不序列化到 outbound DTO / STOMP / WebSocket /
     * EventPublisher payload。
     */
    @JsonIgnore
    public java.util.Map<String, InvokedSkillInfo> getInvokedSkills() {
        if (log.isDebugEnabled()) {
            log.debug("[P1-6-STATE-1] getInvokedSkills: 当前记录数={}", invokedSkills.size());
        }
        return java.util.Collections.unmodifiableMap(invokedSkills);
    }

    /**
     * 取出指定 agent 的 invokedSkills · 对齐 CC {@code getInvokedSkillsForAgent}
     * (bootstrap/state.ts:1530-1541)。
     *
     * <p>严格相等过滤 {@code skill.agentId === normalizedId}（CC）→
     * {@link java.util.Objects#equals}（Java，null 安全）。null 入参只匹配
     * null-agent（主会话）skill。返回<b>新 Map</b>，不共享内部引用（对齐 CC
     * 新建 filtered Map）。
     */
    public java.util.Map<String, InvokedSkillInfo> getInvokedSkillsForAgent(UUID agentId) {
        java.util.Map<String, InvokedSkillInfo> filtered = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, InvokedSkillInfo> e : invokedSkills.entrySet()) {
            if (java.util.Objects.equals(e.getValue().agentId(), agentId)) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[P1-6-STATE-1] getInvokedSkillsForAgent: agentId={} hits={}", agentId, filtered.size());
        }
        return filtered;
    }

    /**
     * 清理 invokedSkills · 对齐 CC {@code clearInvokedSkills}
     * (bootstrap/state.ts:1543-1555)。
     *
     * <p><b>关键语义（CC state.ts:1551）</b>：传入 preserved 集合时，null-agent
     * skill 恒被删除 —— 主会话 skill 不受保留集合保护；仅保留
     * {@code agentId ∈ preserved} 的条目。无参 / 空集 → 全清
     * （对齐 CC {@code !preservedAgentIds || size === 0}）。
     */
    public void clearInvokedSkills(java.util.Set<UUID> preservedAgentIds) {
        int before = invokedSkills.size();
        if (preservedAgentIds == null || preservedAgentIds.isEmpty()) {
            invokedSkills.clear();
            if (log.isDebugEnabled()) {
                log.debug("[P1-6-STATE-1] clearInvokedSkills(全清): cleared={}", before);
            }
            return;
        }
        invokedSkills.entrySet().removeIf(e ->
            e.getValue().agentId() == null || !preservedAgentIds.contains(e.getValue().agentId()));
        if (log.isDebugEnabled()) {
            log.debug("[P1-6-STATE-1] clearInvokedSkills(preserved={}): removed={}/{}",
                preservedAgentIds.size(), before - invokedSkills.size(), before);
        }
    }

    /**
     * 清理指定 agent 的 invokedSkills · 对齐 CC {@code clearInvokedSkillsForAgent}
     * (bootstrap/state.ts:1557-1563，子 agent 完成/失败后释放)。
     *
     * <p>仅删除 {@code skill.agentId === agentId} 全部条目，其余保留。
     */
    public void clearInvokedSkillsForAgent(UUID agentId) {
        int before = invokedSkills.size();
        invokedSkills.entrySet().removeIf(e -> java.util.Objects.equals(e.getValue().agentId(), agentId));
        if (log.isDebugEnabled()) {
            log.debug("[P1-6-STATE-1] clearInvokedSkillsForAgent: agentId={} removed={}/{}",
                agentId, before - invokedSkills.size(), before);
        }
    }

    public AgentState maxTurns(Integer maxTurns) {
        this.maxTurns = maxTurns;
        return this;
    }

    /** 外部 cancel 信号（如用户停止按钮 / HTTP cancel / SIGINT）。对齐 CC abortController。 */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * 外部硬中断 cancel（如用户 Esc 停止按钮 / HTTP cancel）：abort 在飞 LLM 流 + 置协式 flag。
     * 对齐 CC REPL.tsx onCancel → {@code abortController.abort('user-cancel')}（REPL.tsx:2147）
     * 硬中断 —— abort signal 透传 provider（LlmAgentLoop:5360-5364）→ SDK 流消费循环 chunk
     * 边界检查 aborted → CancellationException → done.countDown() → loop 立即退出（对齐 CC
     * 毫秒级硬断，替代原 cancel() 500ms 协式轮询）。
     *
     * @param reason 取消原因（对齐 CC AbortSignal.reason，'user-cancel' / 'interrupt' 等；null → 'user-cancel'）
     */
    public void abortStream(String reason) {
        com.nexusai.application.agent.tool.AbortController ac = this.abortController;
        if (ac != null && !ac.isCancelled()) {
            ac.abort(reason != null ? reason : "user-cancel");
        }
        this.cancelled = true;
    }

    /** attach 当前 run() 的 AbortController（LlmAgentLoop.run() 构造 runAbortController 后调用）。 */
    public void attachAbortController(com.nexusai.application.agent.tool.AbortController abortController) {
        this.abortController = abortController;
    }

    // ── internal mutators (package-private · 仅 AgentLoop 实现类调用) ──

    void incrementTurn() { this.turnCount++; }
    public void markNeedsFollowUp() { this.needsFollowUp = true; }
    void clearNeedsFollowUp() { this.needsFollowUp = false; }
    public void setError(String err) { this.lastError = err; }
    void clearError() { this.lastError = null; }
    public void setFinishReason(String r) { this.finishReason = r; }
    public void setExitReason(ExitReason r) { this.exitReason = r; }
    /**
     * [H7-arch Phase 2] 改 public · 供 SubagentExecutor 跨包注入 subagent initialMessages
     * （对齐 {@link #appendAttachment} L443 跨包 public 先例 · StreamingToolExecutor tool 子包同样跨包）。
     * 主循环 internal 调用语义不变。
     */
    public void appendMessage(ChatMessageDto m) {
        this.messages.add(m);
        Consumer<ChatMessageDto> listener = this.appendListener;
        if (listener != null) {
            listener.accept(m);
        }
    }

    /**
     * [S4-1] 逐消息 append 监听器 · CC original: {@code for await (const message of query(...))}
     * (runAgent.ts:748-806) 的逐消息 yield 语义 —— 父 Agent 必须实时观测子 Agent 产出。
     *
     * <p>本字段是 mid-flight 单点方案：{@link #appendMessage} 是唯一消息 append 通道
     * （AgentLoopContext:1499/:1575/:1582、StreamingToolExecutor 回写、loop() 内 14 处 append
     * 全部汇聚于此），监听器在 append 后同步触发，等价 CC for-await 逐消息 yield（决策 2
     * 流式化主目标，替代旧后置批量 emit）。默认 null 零行为影响，主循环不武装。
     *
     * <p><b>local-only 约束（CLAUDE.md BudgetTracker 架构红线）</b>: {@code @JsonIgnore} ——
     * 与 {@link #budgetTracker} / {@link #currentToolUseContext} 同属 local-only 状态，绝不
     * 序列化到 outbound DTO / STOMP / WebSocket / EventPublisher payload。
     *
     * <p>武装/解除由 SubagentExecutor.runSubagentQueryLoop 同步段单线程控制；回调体内
     * StreamingToolExecutor 异步 append 跨线程触发 —— 回调实现不得持锁（record 写文件 +
     * sink.accept 均无锁）。
     */
    @JsonIgnore
    private volatile Consumer<ChatMessageDto> appendListener = null;

    /**
     * [S4-1] 武装逐消息监听 · 每次 {@link #appendMessage} 后同步触发回调。
     *
     * @param listener 每次 appendMessage 后触发的回调（实时转录 + 流式 emit）；null 解除
     */
    public void setAppendListener(Consumer<ChatMessageDto> listener) {
        this.appendListener = listener;
    }

    /** [S4-1] 解除逐消息监听（queryLoop finally 必调，防泄漏跨 execute 复用）。 */
    public void clearAppendListener() {
        this.appendListener = null;
    }
    /** s01 [P2] 修补新增 · 对齐 CC createAttachmentMessage。
     *  [Session H5] 改 public · StreamingToolExecutor (tool 子包) 跨包注入 hook attachment,
     *  与既有 public mutator setHasInterruptibleToolInProgress 规范一致. */
    public void appendAttachment(AttachmentMessageDto a) { this.attachments.add(a); }

    /**
     * [P1-6-READ-1] 按 attachment 类型删除附件 · replace 语义。
     *
     * <p><b>WHY</b>: CC 每次压缩重建 transcript，仅最新一份 invoked_skills attachment
     * 存活（services/compact/compact.ts:558/:950 调用点 push 到 postCompactFileAttachments，
     * 每次压缩恰好一份）；Java {@link #attachments()} 列表跨压缩从不清理（s01 [P2]
     * 引入后无按类型删除通道），重复压缩会累积多份 invoked_skills，经
     * {@code maybeInjectHookAttachments} 每 turn 渲染 N 份 skill 内容污染上下文
     * （plan concern #A）。注入前必须先 {@code removeAttachmentsByType} 再
     * {@link #appendAttachment}。
     *
     * <p>CC 对齐: attachment.type 判别（utils/attachments.ts:3201 createAttachmentMessage
     * attachment.type，e.g. 'invoked_skills' / 'hook_cancelled'）。精确匹配，不模糊。
     *
     * @param type attachment 内部类型（CC attachment.type）· null/blank 时 no-op
     */
    public void removeAttachmentsByType(String type) {
        if (type == null || type.isBlank()) {
            return;
        }
        int before = attachments.size();
        attachments.removeIf(a -> a != null && type.equals(a.type()));
        if (log.isDebugEnabled()) {
            log.debug("[P1-6-READ-1] removeAttachmentsByType: type={} removed={}/{}",
                type, before - attachments.size(), before);
        }
    }

    /**
     * s08 压缩管线新增：替换全部消息列表（对齐 CC compactConversation 后
     * buildPostCompactMessages 替换 messages 数组）。
     *
     * <p>[R32-b15 Stage 2 C5] 替换时清理 lineage 索引（避免悬空, R8 风险）·
     * compressed 后旧 turn 的 toolUseId 不再有效, 必须重新 build 索引
     * (Stage 2 不实施重建 — 当前 turn 内 lineage 已足够).
     */
    public void replaceMessages(List<ChatMessageDto> newMessages) {
        this.messages.clear();
        if (newMessages != null) {
            this.messages.addAll(newMessages);
        }
        // C5 lineage 清理: 压缩后旧 lineage 视为失效, 避免悬空引用.
        // 当前 turn 重建责任在调用方 (LlmAgentLoop.compact 路径).
        assistantIdByToolUseId.clear();
    }

    // ── convenience ──

    /** 最后一条 assistant 文本；null 表示还没有 assistant turn。 */
    public String lastAssistant() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                return m.content();
            }
        }
        return null;
    }

    // ── exit reason enum · 6 条路径的显式标签 ──

    /**
     * AgentLoop 的退出原因。对齐 CC query.ts 的多种退出/继续路径：
     * <ul>
     *   <li>{@link #NORMAL} —— {@code !needsFollowUp}（CC line 1062）</li>
     *   <li>{@link #ABORTED} —— cancel 信号 · <b>合并 CC 两个独立 Terminal abort reason</b>：
 *       {@code aborted_streaming}（query.ts:1051, toolUse:false, 流式中断）+
 *       {@code aborted_tools}（query.ts:1515, toolUse:true, 工具执行中断）。CC 分流 toolUse 标志，
 *       Java 单值合并二者（VRSA-01/02 偏离）。{@link com.nexusai.application.agent.recovery.LoopReason}
 *       正确拆分 ABORTED_STREAMING / ABORTED_TOOLS 但未接线（待主 agent 拍板 Terminal 收敛）</li>
     *   <li>{@link #MAX_TURNS} —— turnCount 超过 maxTurns（CC line 191）</li>
     *   <li>{@link #STREAM_ERROR} —— provider 抛错（CC line 894 FallbackTriggeredError 之前）</li>
     *   <li>{@link #STREAM_TIMEOUT} —— 300s 无响应（CC abort timeout 兜底）</li>
     *   <li>{@link #INTERRUPTED} —— 线程 interrupt（CC 的 KeyboardInterrupt）</li>
     *   <li>{@link #MAX_OUTPUT_TOKENS} —— LLM 达到 max_tokens 截断（CC line 820
     *       {@code isWithheldMaxOutputTokens}）</li>
     *   <li>{@link #NO_ASSISTANT_TEXT} —— 流正常结束但无任何文本（防御性，CC 未显式列出）</li>
     * </ul>
     *
     * <p>s11 Error Recovery 新增（对齐 CC query.ts 各 Terminal reason）：
     * <ul>
     *   <li>{@link #PROMPT_TOO_LONG} —— prompt 超长 · <b>两个返回点</b>：query.ts:1175（media 三元 !isWithheldMedia）+ query.ts:1182（contextCollapse withheld）</li>
     *   <li>{@link #MODEL_ERROR} —— withRetry 重试耗尽 / 模型调用抛错退出 · CC query.ts:996
     *       {@code return { reason: 'model_error', error }}（ER-IMP-01 替代已删的 MAX_RETRIES/FALLBACK）</li>
     *   <li>{@link #STOP_HOOK_PREVENTED} —— stop hook preventContinuation → 优雅终止 · CC query.ts:1279</li>
     *   <li>{@link #HOOK_STOPPED} —— PostToolUse hook 停止续行 → 终止 · CC query.ts:1520（ER-IMP-09）</li>
     *   <li>{@link #IMAGE_ERROR} —— 图片错误 · <b>两个返回点</b>：query.ts:977（ImageSize/ResizeError）+ query.ts:1175（media 三元 isWithheldMedia）</li>
     *   <li>{@link #BLOCKING_LIMIT} —— token 估算达上限直接退出 · CC query.ts:646</li>
     * </ul>
     *
     * <p><b>偏离标注（ER-IMP-01）</b>：本枚举是 Java 便利字段（exitReason），非 CC 类型——
     * CC query.ts:204-216 {@code type State} 无 exitReason 字段（Terminal reason 是 query()
     * 的<b>返回值</b>，query.ts:227/250 {@code Terminal} 泛型）；Java 侧为调用方一眼看出
     * 终止原因而保留为状态字段（便利桥）。Terminal reason 全集见 {@link com.nexusai.application.agent.recovery.LoopReason}。
     */
    public enum ExitReason {
        NORMAL,            // 正常完成 · 无工具时 1 turn 后退出
        // cancel() 被调用 · 合并 CC aborted_streaming(1051,toolUse:false) + aborted_tools(1515,toolUse:true)
        //   CC 分流 toolUse 标志，Java 单值合并（VRSA-01/02 偏离，待主 agent 拍板 Terminal 收敛）
        ABORTED,
        MAX_TURNS,         // turnCount > maxTurns
        STREAM_ERROR,      // provider 抛错
        STREAM_TIMEOUT,    // 300s 无响应
        INTERRUPTED,       // 线程 interrupt
        MAX_OUTPUT_TOKENS, // LLM 截断（finishReason="length"）· [P-6 2026-08-15] 已不生产——
                           //   恢复耗尽改 ExitReason.NORMAL（对齐 CC query.ts:1264 return {reason:'completed'}，
                           //   LlmAgentLoop.loop() 不再 setExitReason(MAX_OUTPUT_TOKENS)）；枚举保留
                           //   防旧序列化载荷/测试断言断裂（D6 便利桥收敛随 Terminal 轮次）。
        NO_ASSISTANT_TEXT, // 流完成但无任何 chunk

        // ── s11 Error Recovery 新增 · 对齐 CC query.ts Terminal reason ──
        PROMPT_TOO_LONG, // prompt 超长 · 两返回点: CC query.ts:1175(!media) + 1182(collapse)
        MODEL_ERROR,     // withRetry 重试耗尽 / 模型调用抛错 · CC query.ts:996（ER-IMP-01 替代 MAX_RETRIES/FALLBACK）

        // [P1-4] Stop hook preventContinuation → 优雅终止 · CC query.ts:1279
        STOP_HOOK_PREVENTED,

        // [ER-IMP-09] hook_stopped 终止 · CC original: hook_stopped (query.ts:1520)
        // PostToolUse hook preventContinuation → 本 turn 产生 hook_stopped_continuation attachment
        // → shouldPreventContinuation=true → return { reason: 'hook_stopped' }（终止信号，不渲染注入
        // 文本继续）。与 STOP_HOOK_PREVENTED 独立 reason（CC :1279 vs :1520）。
        HOOK_STOPPED,

        // [A12] image_error - 图片错误 · 两返回点: CC query.ts:977(ImageSize/Resize) + 1175(media 三元)
        // 替代通用 STREAM_ERROR, 让调用方区分图片类输入问题与其他 LLM 错误
        IMAGE_ERROR,

        // [H7-arch Phase 5 P4 C1] blocking-limit · 对齐 CC query.ts:646 return {reason: 'blocking_limit'}
        // callModel 前 token 估算 >= effectiveContextWindow - MANUAL_COMPACT_BUFFER_TOKENS(3000) →
        // 直接退出（不调 provider），避免超窗请求 413。
        BLOCKING_LIMIT,

        // [IMP-HR-08 R2] structured-output 重试超限 · 对齐 CC query.ts:1025-1035
        //   error_max_structured_output_retries（jsonSchema 结构化输出模式：本 query 内
        //   StructuredOutput 调用次数 ≥ MAX_STRUCTURED_OUTPUT_RETRIES → 终止，防 STOP 全
        //   blocking 重入挂起/无限循环）
        STRUCTURED_OUTPUT_RETRIES_EXCEEDED,

        // [SH-02 E4] stop_hook_blocking 重入超限（Java 独有基础设施安全阀）· CC 无此 reason
        //   （query.ts:1300-1305 stop_hook_blocking 为栈平坦 state=next;continue，恒阻塞仅烧
        //   API 调用、永不崩溃）；Java loop() 递归重入每帧一栈帧，反复阻塞会 StackOverflowError。
        //   超 MAX_STOP_HOOK_BLOCKING_REENTRIES（LlmAgentLoop.maxStopHookBlockingReentries）
        //   → 终止防崩溃（等价「用户中断」，CC 恒阻塞场景同样不自行收敛）
        STOP_HOOK_BLOCKING_LIMIT_EXCEEDED
    }
}
