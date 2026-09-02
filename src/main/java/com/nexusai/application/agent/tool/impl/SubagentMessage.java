package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.AgentUsage;

/**
 * 子 Agent 流式消息 · 对齐 CC {@code runAgent.ts:248} {@code AsyncGenerator<Message, void>} 的
 * Message 联合类型 (Pattern #8: CC AsyncGenerator&lt;UnionType&gt; → Java sealed interface +
 * record 子类型 + 消费端显式 if-else 链).
 *
 * <p>CC 真源 (runAgent.ts:748-806 for-await yield 分发, 已 grep 自验):
 * <ul>
 *   <li>{@code message.type === 'stream_event'} → 全部 <b>丢弃</b> (不 yield): message_start 仅
 *       pushApiMetricsEntry(TTFT) 后 continue (runAgent.ts:761-768), 其余 stream_event 非 recordable
 *       → 静默丢弃 (isRecordableMessage 不匹配 stream_event, runAgent.ts:231-245)</li>
 *   <li>{@code message.type === 'attachment'} → max_turns_reached 分支 break (不 yield), 其余 yield
 *       (runAgent.ts:770-790). Java 端 attachment 由 {@code AttachmentMessageDto} 承担
 *       (独立 channel, 经 AgentState.appendAttachment, 非本 sealed union 子类型)</li>
 *   <li>{@code isRecordableMessage(message)} → recordSidechainTranscript + yield
 *       (assistant / user / progress / system-compact_boundary, runAgent.ts:792-805)</li>
 * </ul>
 *
 * <p>Java 消费端 (executeStreaming sink) 镜像 CC yield 类型: assistant/user/system 经
 * {@code AgentState.appendListener} 产出 (S4-1); progress 经 {@code QueryParams.withOnToolProgress}
 * 独立进度通道产出 (R32-03: 子 agent 工具报告进度 → toProgressMessage → sink; DEC-25 降级保留 =
 * messageSink==null 的 async worker / 非流式 execute 不产出).
 *
 * <p>WHY sealed interface: 与 CC Message 联合类型一一对应, 编译期穷举子类型, 新增 message type
 * 必须显式处理 (Fail loud). 每字段 JavaDoc 标 CC 原名 + 行号 (Pattern #8 规范).
 */
public sealed interface SubagentMessage {

    /**
     * [P1-18] 该消息是否含 tool 内容 · CC original: {@code content.some(c => c.type === 'tool_use' ||
     * c.type === 'tool_result')} (SkillTool.ts:246-248). fork skill_progress 仅对含 tool 块的消息
     * 上报 (CC onProgress 过滤), 非全部 assistant/user 消息. 非 tool 消息类型默认 false.
     */
    default boolean toolContent() {
        return false;
    }

    /**
     * [P1-18] 产出该消息的 fork 子 agent id · CC original: {@code onProgress.data.agentId}
     * (SkillTool.ts:256). fork agentId 在 SubagentExecutor 内部创建 (createSubagentContext),
     * 逐消息载体透传, 供 SkillToolImpl.buildForkProgressSink 回填 SkillProgressData.agentId
     * (不再硬编码 null). 非 fork / 未接线消息默认 null.
     */
    default String agentId() {
        return null;
    }

    /**
     * CC original: type 'assistant' (runAgent.ts:792-804) · assistant message, 含 usage (agentToolUtils.ts:355).
     *
     * <p>[P1-18] 新增 {@code toolContent} + {@code agentId} 两字段 (兼容 2 参便利构造器, 默认
     * toolContent=false / agentId=null) · CC original: onProgress.data.message 归一化 (SkillTool.ts:253).
     *
     * @param content     CC original: message.text (AssistantMessage 文本)
     * @param usage       CC original: message.usage (agentToolUtils.ts:355)
     * @param toolContent CC original: content.some(tool_use|tool_result) (SkillTool.ts:246-248)
     * @param agentId     CC original: onProgress.data.agentId (SkillTool.ts:256)
     */
    record AssistantMessage(String content, AgentUsage usage, boolean toolContent, String agentId)
            implements SubagentMessage {
        /** 2 参便利构造器 (向后兼容 · P1-18 前调用点). */
        public AssistantMessage(String content, AgentUsage usage) {
            this(content, usage, false, null);
        }
    }

    /** CC original: type 'user' (runAgent.ts:792-804) · user message. */
    record UserMessage(String content, boolean toolContent, String agentId) implements SubagentMessage {
        /** 1 参便利构造器 (向后兼容 · P1-18 前调用点). */
        public UserMessage(String content) {
            this(content, false, null);
        }
    }

    /** CC original: type 'progress' (runAgent.ts:792-804) · 进度消息; recordable 但不更新 lastRecordedUuid (runAgent.ts:801).
     *
     * <p>[R32-03 闭环] 生产路径: {@link SubagentExecutor#toProgressMessage} (Tool.ToolProgress →
     * ProgressMessage, description=进度数据可读渲染) 经 {@code QueryParams.withOnToolProgress}
     * (runSubagentQueryLoop 接线) 在子 agent 工具 (McpServerTool / SkillToolImpl fork) 报告进度时
     * 构造并发射 messageSink · CC original: createProgressMessage (utils/messages.ts:603-618) →
     * query.ts:1380-1387 yield update.message → runAgent.ts:792-805 yield progress.
     * DEC-25 降级保留: messageSink==null (async worker / 非流式 execute) → 不产出 (CC S4-7 async
     * 无实时进度). 非经 appendListener (progress 不落 AgentState / 不更新 lastRecordedUuid :801). */
    record ProgressMessage(String description) implements SubagentMessage {}

    /** CC original: type 'system' (runAgent.ts:792-804) · system message (compact_boundary subtype recordable).
     *
     * <p>{@code subtype='compact_boundary'} 时 recordable (CC isRecordableMessage runAgent.ts:242-244);
     * Java 端 {@link SubagentExecutor#toSubagentMessage} 将非 assistant/user/tool 角色映射为 SystemMessage. */
    record SystemMessage(String content, String subtype) implements SubagentMessage {}
}
