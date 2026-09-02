package com.nexusai.application.agent.workflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;

/**
 * AgentRunResult 判别联合 · CC original: {@code AgentRunResult}
 * (Open-ClaudeCode/packages/workflow-engine/src/types.ts:43-74)。
 *
 * <p>三态：{@code ok} / {@code skipped} / {@code dead}。
 * <ul>
 *   <li>{@code ok}: {@code {kind:'ok', output, usage:{outputTokens}, model?, toolCount?, tokenCount?}}
 *       —— Java 端将嵌套 {@code usage:{outputTokens}} 展平为 {@code outputTokens} 字段（types-doc §5.2，
 *       journal 为 Java 内部持久化，字节格式无需对齐 CC，语义等价即可）。</li>
 *   <li>{@code skipped}: {@code {kind:'skipped'}}（pendingAction skip 路径）。</li>
 *   <li>{@code dead}: {@code {kind:'dead', reason?, detail?}}，{@code reason} 5 枚举
 *       （{@link DeadReason}，日志聚合/审计关键，勿合并）。</li>
 * </ul>
 *
 * <p>三态实现为<b>同包 public 顶层 record</b>（{@link AgentRunResultOk}/{@link AgentRunResultSkipped}/
 * {@link AgentRunResultDead}，各独立文件）：sealed permits 子句要求规范全名且引擎（W-1c WorkflowHooksImpl）
 * 以顶层导入访问（P0-plan §2 命名 {@code AgentRunResultOk/Skipped/Dead}）。{@link DeadReason} 为嵌套枚举
 * （对齐 types-doc §5.2 + W-1c StructuredOutputValidator 引用 {@code AgentRunResult.DeadReason}）。
 *
 * <p><b>W-1d 自包含编译声明</b>：本类为 W-1a 类型底座子集（W-1d ports 签名所需）。
 * W-1a 任务合入后以 W-1a 规范版本为准 reconcile（同包同名冲突由主 agent 仲裁）。
 *
 * <p>{@code kind} 判别属性：{@code @JsonTypeInfo} 序列化为 {@code "kind"} 字段
 * （对齐 CC 判别字段名），供 {@link FileJournalStore} jsonl 往返（append→read）。
 */
@JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AgentRunResultOk.class, name = "ok"),
        @JsonSubTypes.Type(value = AgentRunResultSkipped.class, name = "skipped"),
        @JsonSubTypes.Type(value = AgentRunResultDead.class, name = "dead")
})
public sealed interface AgentRunResult
        permits AgentRunResultOk, AgentRunResultSkipped, AgentRunResultDead {

    /**
     * {@code dead.reason} 5 枚举 · CC original: types.ts:55-66 字面量联合。
     * <ul>
     *   <li>NO_STRUCTURED_OUTPUT — 正常结束但 finalize 无 StructuredOutput</li>
     *   <li>INVALID_STRUCTURED_OUTPUT — 结构化输出不匹配调用方 JSON Schema（引擎边界统一校验）</li>
     *   <li>RUNAGENT_THREW — runAgent 抛非 abort 错误，重试仍败降级至此</li>
     *   <li>WORKTREE_FAILED — {@code isolation:'worktree'} 建树失败（fail-closed 降级）</li>
     *   <li>UNKNOWN — 未分类（兼容旧后端/第三方 adapter）</li>
     * </ul>
     */
    enum DeadReason {
        NO_STRUCTURED_OUTPUT,
        INVALID_STRUCTURED_OUTPUT,
        RUNAGENT_THREW,
        WORKTREE_FAILED,
        UNKNOWN
    }
}
