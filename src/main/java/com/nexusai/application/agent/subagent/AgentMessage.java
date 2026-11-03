package com.nexusai.application.agent.subagent;

import java.util.List;

/**
 * Agent transcript 中的一条消息 · 对齐 CC {@code utils/messages.ts} Message 简化.
 *
 * <p>实现里通常映射 user/assistant/text/tool_use 等; CC 用 type 字段判别, 这里用 role + content
 * 简化. isApiError 标识该消息为 API 错误消息 (CC isApiErrorMessage 跳过语义).
 *
 * <p><b>[S5 P0 差异 4] record 契约扩展</b>: 对齐 CC sessionStorage.ts transcript 消息结构,
 * 补 6 字段 (用户已授权未上线可破约, 不留兼容壳):
 * <ul>
 *   <li>{@code agentId} —— CC original: {@code msg.agentId} (Open-ClaudeCode/src/utils/sessionStorage.ts:4201
 *       {@code msg.agentId === agentId && msg.isSidechain}) — 过滤本 agent 消息</li>
 *   <li>{@code isSidechain} —— CC original: {@code msg.isSidechain} (sessionStorage.ts:995/1042/1225)
 *       — sidechain (sub-agent transcript) 消息标记</li>
 *   <li>{@code uuid} —— CC original: {@code msg.uuid} (sessionStorage.ts:4210-4214 leaf 查找)</li>
 *   <li>{@code parentUuid} —— CC original: {@code msg.parentUuid} (sessionStorage.ts:2069-2094
 *       buildConversationChain parentUuid 链重建)</li>
 *   <li>{@code toolCalls} —— CC original: assistant 消息 content 内 {@code tool_use} 块 id/name/input
 *       (messages.ts:2795 filterUnresolvedToolUses 收集 tool_use id)</li>
 *   <li>{@code toolCallId} —— CC original: {@code tool_result.tool_use_id} (runAgent.ts:875-880
 *       filterIncompleteToolCalls 收集有结果的 tool_use id)</li>
 * </ul>
 */
public record AgentMessage(
    String role,        // "user" | "assistant" | "system" | "tool"
    String content,
    boolean isApiError,
    String agentId,     // CC original: msg.agentId (sessionStorage.ts:4201)
    boolean isSidechain, // CC original: msg.isSidechain (sessionStorage.ts:995)
    String uuid,         // CC original: msg.uuid (sessionStorage.ts:4210)
    String parentUuid,   // CC original: msg.parentUuid (sessionStorage.ts:2069)
    List<ToolCallInfo> toolCalls, // CC original: tool_use blocks (messages.ts:2795)
    String toolCallId,   // CC original: tool_result.tool_use_id (runAgent.ts:875)
    // [G2] 转录节点的 ISO-8601 时间戳 · CC original: msg.timestamp (sessionStorage.ts:2046-2059
    //   findLatestMessage 以 Date.parse(msg.timestamp) 取「最新 leaf」)。
    //   WHY 需要：compact_boundary 的 parentUuid=null（CC :1391-1407「truncates --continue chain
    //   at compact boundary」）⇒ 压缩后文件里存在**两条链头**，「取最新 leaf」只能靠时间戳判先后。
    //   null = 该条无时间信息（legacy 转录 / 非 chatMessageToMap 写入的消息）。
    String timestamp,
    // [2026-10-21] 思考内容 · CC original: msg.reasoning（子代理转录里的 thinking 文本）
    //   原先解析时被丢弃 ⇒ 右栏对话视图看不到思考，而主对话能看到。
    String reasoning,
    // [2026-11-02] 系统注入行三件套 · CC original: msg.isMeta / msg.author / msg.type(subtype)
    //
    //   WHY（用户实测「<system-reminder> 被渲染成用户气泡，应该显示为系统提示」）：
    //   transcript 此前**完全没有**这三个字段 ⇒ 消费侧（右栏 AgentTranscriptView）无法区分
    //   「用户真打的字」与「系统注入的 meta 行」（skill_listing / edited_text_file / date_change /
    //   changed_files / hook 回执 …）。它们在 DTO 里都是 role=user，于是右栏把技能清单这种
    //   几 KB 的系统提示渲染成了一条用户气泡。
    //
    //   写入侧 {@code SubagentExecutor.chatMessageToMap} 与读侧 {@link AgentTranscript#parseMessageNode}
    //   同步补齐；缺省值（旧转录无这些键）= false / null，与 CC undefined 同义（非双轨）。
    boolean isMeta,
    String author,
    String subtype
) {
    /** CC original: tool_use 块 id/name/input (messages.ts:2795 block.id / block.name). */
    public record ToolCallInfo(String id, String name, String arguments) {}

    /**
     * 9 参兼容构造器（timestamp 默认 null）· 与 [S5 P0] 扩展前的分量逐位同构。
     *
     * <p>⛔ 保留理由：{@code new AgentMessage(...)} 的既有调用方 12 处全为 9 实参
     * （{@code MessageFilters:224} + {@code AgentSummaryServiceIntegrationTest} 6 处 +
     * {@code AgentTranscriptReadTest} 4 处，python 括号匹配实测），其中 10 处在**本批领地之外**
     * 的测试文件 ⇒ 无本构造器则本批改动会牵动领地外文件（越界）。
     * 读侧合并（{@code MessageFilters.filterMessages} 相邻 user 合并）经本构造器重建消息 →
     * timestamp=null（该值只服务「取最新 leaf」，而 leaf 判定发生在合并**之前**的原始解析集上，
     * 故合并丢 timestamp 不影响 leaf 选择）。
     */
    public AgentMessage(String role, String content, boolean isApiError, String agentId,
                        boolean isSidechain, String uuid, String parentUuid,
                        List<ToolCallInfo> toolCalls, String toolCallId) {
        this(role, content, isApiError, agentId, isSidechain, uuid, parentUuid,
            toolCalls, toolCallId, null, null, false, null, null);
    }

    /** 兼容构造（含 timestamp，无 reasoning）· 既有测试/调用方沿用 10 参。 */
    public AgentMessage(String role, String content, boolean isApiError, String agentId,
                        boolean isSidechain, String uuid, String parentUuid,
                        List<ToolCallInfo> toolCalls, String toolCallId, String timestamp) {
        this(role, content, isApiError, agentId, isSidechain, uuid, parentUuid,
            toolCalls, toolCallId, timestamp, null, false, null, null);
    }

    /** 兼容构造（含 timestamp + reasoning，无系统注入三件套）· 11 参。 */
    public AgentMessage(String role, String content, boolean isApiError, String agentId,
                        boolean isSidechain, String uuid, String parentUuid,
                        List<ToolCallInfo> toolCalls, String toolCallId, String timestamp,
                        String reasoning) {
        this(role, content, isApiError, agentId, isSidechain, uuid, parentUuid,
            toolCalls, toolCallId, timestamp, reasoning, false, null, null);
    }

    public static AgentMessage of(String role, String content) {
        return new AgentMessage(role, content, false, null, false, null, null,
            List.<ToolCallInfo>of(), null, null, null, false, null, null);
    }
}
