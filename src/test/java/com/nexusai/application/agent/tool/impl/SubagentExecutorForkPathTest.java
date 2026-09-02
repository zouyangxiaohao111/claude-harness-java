package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.subagent.AgentMessage;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.subagent.ForkSubagentMessages;
import com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 fork 缓存共享机制 RED-GREEN 双证测试.
 *
 * <p>规则九 (测试验证意图而非行为): 意图是 — fork 子 agent 必须与父 agent 共享 prompt cache
 * 前缀 (byte-identical), 否则 fork 是空壳: 子 agent cold start, 无缓存共享、无上下文继承、
 * 无 boilerplate 约束. CC AgentTool.tsx:512 buildForkedMessages + :622-623 override.systemPrompt +
 * :630 forkContextMessages 是 fork 语义实质的三根支柱.
 *
 * <p>测试方式 (对齐既有 SubagentExecutorSessionHookCleanupTest 模式): {@code execute()} 22 步
 * 主流程依赖 LLM 循环重依赖, 无法在单测跑全流程. 故把 fork 决策点抽成 package-private static
 * seams (assembleForkInitialMessages / resolveForkAgentSystemPrompt / resolveForkThinkingConfig /
 * buildForkAgentOptions / filterIncompleteToolCalls), execute() 真实调用这些 seam, 本测试验证
 * seam 语义 = 验证生产逻辑. RED 依据: 本测试引用的 ForkPathParams / 各 seam 在 S3 实施前不存在
 * (编译即失败) → 回退任一 seam 实现 → 对应测试红.
 */
@DisplayName("[S3] fork 缓存共享机制 (buildForkedMessages 接入 / forkParentSystemPrompt 透传 / forkContextMessages 前缀 / thinkingConfig 继承 / querySource 设子)")
class SubagentExecutorForkPathTest {

    // ────────────────────────────────────────────────────────────────────────
    // 测试夹具
    // ────────────────────────────────────────────────────────────────────────

    private static final String SESSION = UUID.randomUUID().toString();

    /** 构造带 1 个 tool_use + 1 个 text 块的父 assistant message (对齐 CC BetaToolUseBlock + BetaTextBlock). */
    private static ForkSubagentMessages.AssistantMessage forkAssistantWithToolUse() {
        ObjectNode input = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .build().createObjectNode();
        input.put("command", "ls");
        return new ForkSubagentMessages.AssistantMessage(
            "asst-1",
            List.of(
                new ForkSubagentMessages.BetaToolUseBlock("tool-1", "Bash", input),
                new ForkSubagentMessages.BetaTextBlock("thinking + 计划文本")
            ));
    }

    /** 无 tool_use 的 assistant message (CC forkSubagent.ts:127-139 边界). */
    private static ForkSubagentMessages.AssistantMessage forkAssistantWithoutToolUse() {
        return new ForkSubagentMessages.AssistantMessage(
            "asst-2",
            List.of(new ForkSubagentMessages.BetaTextBlock("纯文本无工具调用")));
    }

    private static SubagentExecutor.ForkPathParams forkParams(
            ForkSubagentMessages.Message assistantMessage,
            List<?> forkContextMessages,
            String forkParentSystemPrompt,
            Object parentThinkingConfig) {
        return new SubagentExecutor.ForkPathParams(
            assistantMessage, forkContextMessages, forkParentSystemPrompt, parentThinkingConfig);
    }

    private static ChatMessageDto dto(Role role, String content, String toolCallId,
            List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, role, null, content, null,
            toolCalls,
            (toolCalls != null && !toolCalls.isEmpty()) ? FinishReason.tool_calls : null,
            null, null, null, OffsetDateTime.now(),
            toolCallId, null, null, List.of(), List.of());
    }

    private static ToolCallDto toolCall(String id) {
        return new ToolCallDto(id, "Bash", "{\"command\":\"ls\"}", null, null);
    }

    /** 从 Map 列表提取 role 序列 (验证顺序). */
    private static List<String> roles(List<Map<String, Object>> maps) {
        return maps.stream().map(m -> String.valueOf(m.get("role"))).toList();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 正向 ×5
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fork path: buildForkedMessages 被调用, tool_result 占位进入 initialMessages")
    void forkPath_shouldCallBuildForkedMessages_whenAssistantMessageProvided() {
        // WHY: buildForkedMessages 构造 [fullAssistantMessage(克隆), userMessage(tool_result 占位 + directive)]
        //   是缓存共享前缀核心 (CC forkSubagent.ts:107-169). Java 现状 (S3 前): ForkSubagentMessages:164
        //   dead code, SubagentExecutor 用纯 user 消息装配. 回退本实现 → 测试红 (无 tool-role 占位消息).
        // CC AgentTool.tsx:512 promptMessages = buildForkedMessages(prompt, assistantMessage).
        List<Map<String, Object>> maps = SubagentExecutor.assembleForkInitialMessages(
            "完成这个任务", forkParams(forkAssistantWithToolUse(), List.of(),
                "<父 system prompt>", null));

        // buildForkedMessages 输出: [assistant(克隆, tool_use), user(tool_result 占位 + directive)]
        //   → toInitialMessageMaps: [assistant(带 toolCalls), tool(toolCallId=占位), user(directive)]
        assertThat(maps)
            .as("fork 前缀必须由 buildForkedMessages 产出: 首条 assistant(克隆) + tool_result 占位 + directive")
            .isNotEmpty();
        assertThat(roles(maps))
            .as("CC forkSubagent.ts:158-166: assistant(克隆) → tool_result 占位 → directive user")
            .isEqualTo(List.of("assistant", "tool", "user"));
        // tool_result 占位文本必须 byte-identical (CC forkSubagent.ts:93 FORK_PLACEHOLDER_RESULT)
        assertThat(maps.get(1))
            .as("tool_result 占位必须携带 FORK_PLACEHOLDER_RESULT 文本 + toolCallId")
            .containsEntry("role", "tool")
            .containsEntry("toolCallId", "tool-1")
            .containsEntry("content", ForkSubagentMessages.FORK_PLACEHOLDER_RESULT);
        // 克隆的 assistant 消息保留 tool_use 块 (id 对齐 BetaToolUseBlock.id)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) maps.get(0).get("toolCalls");
        assertThat(toolCalls)
            .as("克隆 assistant 的 tool_use 块必须保留 (CC forkSubagent.ts:113-120)")
            .hasSize(1);
        assertThat(toolCalls.get(0)).containsEntry("id", "tool-1").containsEntry("name", "Bash");
    }

    @Test
    @DisplayName("fork path: agentSystemPrompt = forkParentSystemPrompt (跳过 buildAgentSystemPrompt)")
    void forkPath_shouldUseForkParentSystemPrompt_skipBuildAgentSystemPrompt() {
        // WHY: fork 子 agent system prompt 必须与父 rendered bytes byte-identical 才能 prompt cache 共享.
        //   Java 现状 (S3 前): SubagentExecutor buildAgentSystemPrompt 重新构造, forkParentSystemPrompt 不消费
        //   (grep -nE 'forkParentSystemPrompt' SubagentExecutor.java exit 1). 回退 → 测试红.
        //   CC runAgent.ts:508-509 agentSystemPrompt = override?.systemPrompt ? override.systemPrompt : ...
        String parentPrompt = "父 agent rendered system prompt 完整字节";
        SubagentExecutor.ForkPathParams fp = forkParams(forkAssistantWithToolUse(), List.of(),
            parentPrompt, null);

        String resolved = SubagentExecutor.resolveForkAgentSystemPrompt(true, fp, () -> "buildAgentSystemPrompt 输出");

        assertThat(resolved)
            .as("fork path 必须直接用父 rendered bytes (cache-identical prefix)")
            .isEqualTo(parentPrompt);
    }

    @Test
    @DisplayName("fork path: forkContextMessages 经 filterIncompleteToolCalls 前置 (剔除残缺 tool_use)")
    void forkPath_shouldPrependForkContextMessages_withFilterIncompleteToolCalls() {
        // WHY: fork 子 agent 必须继承父对话历史 (forkContextMessages), 否则 cold start.
        //   CC runAgent.ts:370-371 initialMessages = [...filterIncompleteToolCalls(forkContextMessages), ...promptMessages].
        //   filterIncompleteToolCalls 剔除无 tool_result 的 tool_use (避免 API 拒绝, runAgent.ts:866-904).
        // 父历史: user "你好" + assistant(含已配对 tool-1 的 tool_use) + tool(tool-1 结果) + assistant(含未配对 tool-2)
        List<?> forkContext = List.of(
            dto(Role.user, "父对话: 你好", null, null),
            dto(Role.assistant, "已配对工具调用", null, List.of(toolCall("tool-1"))),
            dto(Role.tool, "tool-1 结果", "tool-1", null),
            dto(Role.assistant, "未配对工具调用 (应被剔除)", null, List.of(toolCall("tool-2"))));
        SubagentExecutor.ForkPathParams fp = forkParams(forkAssistantWithToolUse(), forkContext,
            "<父 system prompt>", null);

        List<Map<String, Object>> maps = SubagentExecutor.assembleForkInitialMessages("指令", fp);

        // contextMessages (过滤后) 前置 + promptMessages 后置
        //   过滤: user 保留, assistant(tool-1 已配对) 保留, tool 保留, assistant(tool-2 未配对) 剔除
        assertThat(roles(maps))
            .as("CC runAgent.ts:370-373: [过滤后父历史..., buildForkedMessages...] 顺序")
            .containsExactly("user", "assistant", "tool", "assistant", "tool", "user");
        // 剔除验证: 未配对的 tool-2 assistant 消息不在结果中
        assertThat(maps)
            .as("filterIncompleteToolCalls 必须剔除含未配对 tool_use 的 assistant 消息 (CC runAgent.ts:898-901)")
            .noneMatch(m -> "assistant".equals(m.get("role"))
                && "未配对工具调用 (应被剔除)".equals(m.get("content")));
        // 已配对 assistant(tool-1) 保留 (有对应 tool 消息)
        assertThat(maps)
            .as("已配对 tool_use 的 assistant 消息必须保留")
            .anyMatch(m -> "assistant".equals(m.get("role"))
                && "已配对工具调用".equals(m.get("content")));
    }

    @Test
    @DisplayName("fork path: thinkingConfig 继承父 (非硬编码 enabled)")
    void forkPath_shouldInheritParentThinkingConfig_notHardcodedEnabled() {
        // WHY: fork 子 agent thinkingConfig 必须与父一致 (prompt cache key 一致).
        //   Java 现状 (S3 前): SubagentExecutor isForkAgentType ? Map.of("type","enabled") : Map.of()
        //   — fork 传 enabled 与父不同 → cache key 不一致 → prompt cache 失效 (S3-7).
        //   CC runAgent.ts:682-683 thinkingConfig: useExactTools ? toolUseContext.options.thinkingConfig.
        Map<String, Object> parentThinkingConfig = Map.of("type", "enabled", "budget_tokens", 2048);
        SubagentExecutor.ForkPathParams fp = forkParams(forkAssistantWithToolUse(), List.of(),
            "<父 system prompt>", parentThinkingConfig);

        Map<String, Object> resolved = SubagentExecutor.resolveForkThinkingConfig(true, fp);

        assertThat(resolved)
            .as("fork path 必须继承父 thinkingConfig (cache key 一致)")
            .isEqualTo(parentThinkingConfig);
        assertThat(resolved)
            .as("继承源是父配置, 不是硬编码")
            .containsEntry("budget_tokens", 2048);
    }

    @Test
    @DisplayName("fork path: querySource='agent:builtin:fork' 设到子 AgentOptions (抗 autocompact 递归守卫)")
    void forkPath_shouldSetQuerySourceOnChildOptions_antiAutocompact() {
        // WHY: querySource='agent:builtin:fork' 设到子 ctx.options 是抗 autocompact 递归守卫.
        //   Java 现状 (S3 前): SubagentExecutor:611 用 'agent:'+source+':'+agentType 拼出
        //   'agent:built-in:fork' (带连字符), 与守卫 SubagentTool 检查的 'agent:builtin:fork' 永不相等
        //   = Pattern #11 bypass. 回退 → 测试红.
        //   CC runAgent.ts:694 ...(useExactTools && { querySource }) 设到 context.options.
        SubagentExecutor.ForkPathParams fp = forkParams(forkAssistantWithToolUse(), List.of(),
            "<父 system prompt>", null);

        AgentOptions forkOptions = SubagentExecutor.buildForkAgentOptions(fp);

        assertThat(forkOptions.querySource())
            .as("fork 子 ctx.options.querySource 必须为常量 'agent:builtin:fork' (CC AgentTool.tsx:332 template literal)")
            .isEqualTo(ForkSubagent.FORK_QUERY_SOURCE)
            .isEqualTo("agent:builtin:fork");
        assertThat(forkOptions.useExactTools())
            .as("useExactTools=true 仅 fork path (CC AgentTool.tsx:631-632)")
            .isTrue();
        assertThat(forkOptions.thinkingConfig())
            .as("fork 子 ctx.options.thinkingConfig 继承父 (CC runAgent.ts:682-683)")
            .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 反向 ×1
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[RF-1] buildSubagentAgentOptions 读父 thinkingConfig（非硬编码 null）→ fork 继承父 Map")
    void buildSubagentAgentOptions_shouldCarryParentThinkingConfig_notNull() {
        // WHY: 旧 buildSubagentAgentOptions 第 3 参硬编码 null → 父启用 thinking 时 fork child 不继承
        //   → cache-key 漂移（DISC-SUB-03 Q-2）。CC runAgent.ts:682-683
        //   thinkingConfig: useExactTools ? toolUseContext.options.thinkingConfig : {type:'disabled'}。
        var enabled = LlmAgentLoop.buildSubagentAgentOptions(QuerySource.SUBAGENT, ThinkingConfig.enabled(2048));
        assertThat(enabled.thinkingConfig())
            .as("buildSubagentAgentOptions 必须读父 thinkingConfig（非硬编码 null），enabled 父 → Map enabled+budget_tokens")
            .isEqualTo(Map.of("type", "enabled", "budget_tokens", 2048));

        // disabled 父 → {type:'disabled'}（CC runAgent.ts:684）
        var disabled = LlmAgentLoop.buildSubagentAgentOptions(QuerySource.SUBAGENT, ThinkingConfig.disabled());
        assertThat(disabled.thinkingConfig())
            .as("disabled 父 thinkingConfig → {type:'disabled'}（CC :684 控制输出 token 成本）")
            .isEqualTo(Map.of("type", "disabled"));

        // null 父（QueryParams.forLoop 默认）→ {type:'disabled'}（不 NPE）
        var nullTc = LlmAgentLoop.buildSubagentAgentOptions(QuerySource.SUBAGENT, null);
        assertThat(nullTc.thinkingConfig())
            .as("null 父 thinkingConfig → {type:'disabled'}（防御性兜底）")
            .isEqualTo(Map.of("type", "disabled"));
    }

    @Test
    @DisplayName("非 fork path: 不触发 buildForkedMessages, 用 buildAgentSystemPrompt 输出")
    void nonForkPath_shouldNotCallBuildForkedMessages_useBuildAgentSystemPrompt() {
        // WHY: 反向证 — 非 fork path 不应触发 fork 缓存共享逻辑. buildForkedMessages 仅 fork path
        //   调用 (CC AgentTool.tsx:512 isForkPath 分支), 否则普通 subagent 路径被污染.
        String computedPrompt = "buildAgentSystemPrompt 输出 (非 fork)";

        String resolved = SubagentExecutor.resolveForkAgentSystemPrompt(false, null, () -> computedPrompt);

        assertThat(resolved)
            .as("非 fork path 必须用 buildAgentSystemPrompt 输出, 不消费 forkParentSystemPrompt")
            .isEqualTo(computedPrompt);
        // 非 fork 的 thinkingConfig 对齐 CC runAgent.ts:684 {type:'disabled'} (控制输出 token 成本)
        assertThat(SubagentExecutor.resolveForkThinkingConfig(false, null))
            .as("非 fork path thinkingConfig = {type:'disabled'} (CC runAgent.ts:684)")
            .isEqualTo(Map.of("type", "disabled"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // R1-THINK: 派生 thinkingConfig 注入 QueryParams（fork child 运行时继承父 thinking）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fork path: 派生 thinkingConfig 经 toThinkingConfig 转换注入（fork child 运行时继承父 thinking，非硬编码 disabled）")
    void forkPath_shouldConvertDerivedThinkingConfig_forQueryParamsInjection() {
        // WHY: resolveForkThinkingConfig 输出的 Map（落到 AgentRunOptions.thinkingConfig）此前
        //   在 runSubagentQueryLoop 零读取 —— QueryParams.forLoop 恒 null→disabled，fork child
        //   运行时未继承父 thinking → 子请求 thinking 与父不一致 → cache key 偏移 → prompt cache
        //   失效（DISC-SUB-03 EV-FK-014 / Q2）。CC runAgent.ts:682-683
        //   thinkingConfig: useExactTools ? toolUseContext.options.thinkingConfig : {type:'disabled'}.
        Map<String, Object> parentThinkingConfig = Map.of("type", "enabled", "budget_tokens", 2048);
        SubagentExecutor.ForkPathParams fp = forkParams(forkAssistantWithToolUse(), List.of(),
            "<父 system prompt>", parentThinkingConfig);

        // 生产接线链: resolveForkThinkingConfig → buildAgentOptions(AgentRunOptions.thinkingConfig)
        //   → toThinkingConfig → QueryParams.withThinkingConfig（runSubagentQueryLoop 消费点）
        Map<String, Object> derived = SubagentExecutor.resolveForkThinkingConfig(true, fp);
        ThinkingConfig injected = SubagentExecutor.toThinkingConfig(derived);

        assertThat(injected)
            .as("fork 派生 thinkingConfig 必须转换为 enabled+budgetTokens（继承父，非硬编码 disabled）")
            .isEqualTo(ThinkingConfig.enabled(2048));

        // 非 fork: disabled（CC runAgent.ts:684 控制输出 token 成本）
        assertThat(SubagentExecutor.toThinkingConfig(SubagentExecutor.resolveForkThinkingConfig(false, null)))
            .as("非 fork thinkingConfig 必须 disabled（CC runAgent.ts:684）")
            .isEqualTo(ThinkingConfig.disabled());
    }

    @Test
    @DisplayName("toThinkingConfig 边界: adaptive / null / 空 Map / 缺 budget_tokens 均正确降级")
    void toThinkingConfig_boundary_adaptiveNullEmpty() {
        // WHY: 派生 map 的形状来自父 AgentOptions.thinkingConfig（Object），可能为 adaptive、null
        //   （buildSubagentAgentOptions 硬编码 null 历史）、或 enabled 缺 budget_tokens 的畸形输入。
        //   转换必须对齐 CC union 三态（thinking.ts:10-13），畸形输入显式降级 disabled 不 NPE。
        assertThat(SubagentExecutor.toThinkingConfig(Map.of("type", "adaptive")))
            .as("adaptive → ThinkingConfig.adaptive()（CC thinking.ts:11）")
            .isEqualTo(ThinkingConfig.adaptive());
        assertThat(SubagentExecutor.toThinkingConfig(null))
            .as("null map → disabled（防御性兜底，不 NPE）")
            .isEqualTo(ThinkingConfig.disabled());
        assertThat(SubagentExecutor.toThinkingConfig(Map.of()))
            .as("空 map → disabled（无 type 键）")
            .isEqualTo(ThinkingConfig.disabled());
        assertThat(SubagentExecutor.toThinkingConfig(Map.of("type", "enabled")))
            .as("enabled 缺 budget_tokens → 畸形输入显式降级 disabled（显式失败，不静默构造 enabled(0)）")
            .isEqualTo(ThinkingConfig.disabled());
    }

    // ────────────────────────────────────────────────────────────────────────
    // [RES-SP31-1 返工] resume append 为 fork-only（CC 真源复验修正）——
    //   非 fork resume 系统提示不含 append；fork resume 经 forkParentSystemPrompt 已含 append
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[RES-SP31-1 返工] 非 fork resume: buildAgentSystemPrompt 结果原样返回（CC runAgent.ts:508-518 getAgentSystemPrompt 不含 append）")
    void nonForkResume_keepsComputed_noAppend() {
        // WHY (REQ-SP31-1，返工修正): CC resumeAgent.ts:183-185 非 fork resume override:undefined →
        //   runAgent.ts:508-518 agentSystemPrompt = getAgentSystemPrompt = [agentPrompt] + env（:906-924，
        //   不含 append）；append 内容仅经 buildEffectiveSystemPrompt（systemPrompt.ts:115-122）达 fork
        //   路径（resumeAgent.ts:135-141 在 isResumedFork 内）。若 Step 9 对非 fork resume 追加 append，
        //   续跑子代理系统提示与原 spawn 不一致且偏离 CC —— 必须原样返回 computed。
        // RED 依据（返工）：本测试在上一轮实现（Step 9 非 fork resume 追加 append）下红 —— 锁定偏差根因；
        //   现 Step 9 已移除 append 分支（ForkPathParams 亦无 append 字段），非 fork resume 恒返回 computed。
        SubagentExecutor.ForkPathParams resumeParams = new SubagentExecutor.ForkPathParams(
            null, null, "", null,
            List.of(AgentMessage.of("user", "pre-resume 上下文")),
            null, null, null);
        String computed = "buildAgentSystemPrompt 输出";

        String resolved = SubagentExecutor.resolveForkAgentSystemPrompt(false, resumeParams, () -> computed);

        assertThat(resolved)
            .as("非 fork resume 必须原样返回 buildAgentSystemPrompt 输出（CC runAgent.ts:508-518 无 append）")
            .isEqualTo(computed);
    }

    @Test
    @DisplayName("[RES-SP31-1 返工] fork resume: forkParentSystemPrompt 直用，Step 9 不追加（append 已由 ResumeService 含入父提示）")
    void forkResume_usesParentPrompt_noStep9Append() {
        // WHY (REQ-SP31-1): fork resume 父提示（ResumeService resolveForkParentSystemPrompt rendered 补
        //   append :340-354 / 重建路径 :418 已含 append）已承载 append；Step 9 必须保持 forkParentSystemPrompt
        //   原样直用（CC runAgent.ts:508-509 override.systemPrompt 优先），重复追加会双写。
        SubagentExecutor.ForkPathParams resumeParams = new SubagentExecutor.ForkPathParams(
            null, null, "PARENT_WITH_APPEND", null,
            List.of(AgentMessage.of("user", "pre-resume 上下文")),
            null, null, null);

        String resolved = SubagentExecutor.resolveForkAgentSystemPrompt(
            true, resumeParams, () -> "computed");

        assertThat(resolved)
            .as("fork resume 必须直用 forkParentSystemPrompt（含 append，不重复追加）")
            .isEqualTo("PARENT_WITH_APPEND");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 边界 ×1
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("边界: assistantMessage null → 降级纯 user 消息, 不 NPE")
    void forkPath_assistantMessageNull_shouldDegradeToUserMessage_boundary() {
        // WHY: 边界 — assistantMessage 为 null 时 (上游未透传 / 非 fork 误入), buildForkedMessages
        //   直接访问 assistantMessage.content() 会 NPE. CC forkSubagent.ts:127-139 无 tool_use 边界
        //   返回单 user 消息; Java 端必须对 null assistantMessage 同样降级不崩.
        SubagentExecutor.ForkPathParams fp = forkParams(null, List.of(), "<父 system prompt>", null);

        List<Map<String, Object>> maps = SubagentExecutor.assembleForkInitialMessages("降级指令", fp);

        assertThat(maps)
            .as("assistantMessage null → 降级为单条纯 user 消息 (不 NPE, 不触发 buildForkedMessages)")
            .hasSize(1);
        assertThat(maps.get(0))
            .containsEntry("role", "user")
            .containsEntry("content", "降级指令");
    }
}
