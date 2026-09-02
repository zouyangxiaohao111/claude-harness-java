package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PreToolUseHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [B session] StreamingToolExecutor 调度核心对齐 CC — 8 子缺口 RED/GREEN 测试.
 *
 * <p>覆盖 (对齐 CC StreamingToolExecutor.ts 真源, Pattern #2/#9 实证):
 * <ol>
 *   <li>processQueue break 守序 (ts:140-151) — unsafe 守门失败后 safe(C) 不得越过</li>
 *   <li>getAbortReason per-tool (ts:210-231 + getToolInterruptBehavior ts:233-241)</li>
 *   <li>createSyntheticErrorMessage 四态文案 (ts:153-205) — 父链归因走消息层 (ER-IMP-14)</li>
 *   <li>abort bubble-up listener (ts:304-318) — 非 sibling_error 冒泡父 ctx</li>
 *   <li>Bash isError success-path sibling abort (ts:347-364) — 正常 return isError 也级联</li>
 *   <li>TrackedTool 父 assistant 关联 (ts:21) — synthetic error 携带父 UUID</li>
 *   <li>hook 异常级联 = non-blocking + error attachment (utils/hooks.ts:2698-2730, 修正 B.md 措辞)</li>
 *   <li>sibling_error 不冒泡边界 (ts:308) + parent 已取消不重复 abort</li>
 * </ol>
 *
 * <p>WHY (规则九): 每个测试断言 CC 语义 — 若对应实现回退, 测试必须变红 (RED 验证见
 * 各测试 JavaDoc). 中文 WHY 注释 + 数据流日志断言.
 */
class StreamingToolExecutorDispatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════
    // T1: processQueue break 守序 (CC ts:140-151)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1 [GAP-EXEC-01 queue] unsafe 守门失败 → 其后 safe 工具不得越过启动 (CC ts:146-149 break)")
    void processQueue_unsafeToolPreservesOrderAfterBreak() throws Exception {
        // WHY: CC processQueue 在 unsafe 工具无法执行时 break, 保持 unsafe 顺序语义;
        //      队列 [safe(A), unsafe(B), safe(C)] 中 C 必须等 B 完成后才启动.
        //      Java 旧实现无 break → C 在 A 执行期间提前启动, 与 CC 语义相反.
        // RED: 删除 processQueue 的 break → C 在 A 阻塞期间执行 → order 含 "C" → 断言失败.
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();

        Tool safeA = new Tool() {
            @Override public String name() { return "safeA"; }
            @Override public String description() { return "safe blocking A"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                aEntered.countDown();
                try { releaseA.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                order.add("A");
                return ToolResult.success(call.id(), "A done");
            }
        };
        Tool unsafeB = new Tool() {
            @Override public String name() { return "unsafeB"; }
            @Override public String description() { return "unsafe B"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return false; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                order.add("B");
                return ToolResult.success(call.id(), "B done");
            }
        };
        Tool safeC = new Tool() {
            @Override public String name() { return "safeC"; }
            @Override public String description() { return "safe C"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                order.add("C");
                return ToolResult.success(call.id(), "C done");
            }
        };
        registry.register(safeA);
        registry.register(unsafeB);
        registry.register(safeC);
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());

        exec.add(call("a1", "safeA"));
        assertThat(aEntered.await(10, TimeUnit.SECONDS)).isTrue();
        exec.add(call("b1", "unsafeB"));
        exec.add(call("c1", "safeC"));

        Thread.sleep(150);
        assertThat(order)
            .as("unsafe(B) 守门失败时 safe(C) 不得越过启动 (CC break 守序)")
            .doesNotContain("C");

        releaseA.countDown();
        exec.getRemainingResults();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(order)
            .as("B 必须先于 C 执行 (unsafe 顺序语义)")
            .containsSubsequence("B", "C");
    }

    // ════════════════════════════════════════════════════════════════════
    // T2: getAbortReason per-tool (CC ts:210-231)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T2 [GAP-EXEC-01/04] interrupt 时 per-tool 区分 cancel/block (CC ts:210 getAbortReason(tool))")
    void getAbortReason_perToolCancelVsBlock_returnsSpecificToolBehavior() throws Exception {
        // WHY: CC getAbortReason 接具体 tool, interrupt 判定用 getToolInterruptBehavior(tool);
        //      多工具并发 (Bash=cancel, FileRead=block) 时两者取不同决策.
        //      Java 旧实现取"首 EXECUTING 工具"全局判定 → 无法 per-tool 区分.
        // RED: getAbortReason 忽略 per-tool (全按 cancel 或全按 block) → blockTool 结果错误 → 断言失败.
        AbortController parent = new AbortController();
        parent.abort("interrupt");
        ToolUseContext ctx = context(parent);
        AtomicBoolean cancelToolRan = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();

        Tool cancelTool = new Tool() {
            @Override public String name() { return "cancelTool"; }
            @Override public String description() { return "cancel interrupt tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "cancel"; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                cancelToolRan.set(true);
                return ToolResult.success(call.id(), "cancel_done");
            }
        };
        Tool blockTool = new Tool() {
            @Override public String name() { return "blockTool"; }
            @Override public String description() { return "block interrupt tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "block_done");
            }
        };
        registry.register(cancelTool);
        registry.register(blockTool);
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, ctx);

        exec.add(call("c1", "cancelTool"));
        exec.add(call("b1", "blockTool"));
        List<ToolResult> results = exec.getRemainingResults();
        pool.shutdown();

        ToolResult cancelResult = byData(results, "doesn't want to proceed");
        ToolResult blockResult = byData(results, "block_done");
        assertThat(exec.getResultErrorFlags().get("c1"))
            .as("interrupt + interruptBehavior=cancel → user_interrupted synthetic (CC ts:223-227)")
            .isTrue();
        assertThat(String.valueOf(cancelResult.data()))
            .as("user_interrupted 文案 = CC REJECT_MESSAGE (utils/messages.ts:212-213)")
            .startsWith("The user doesn't want to proceed with this tool use.");
        assertThat(cancelToolRan.get())
            .as("cancel 工具不得真实执行 (synthetic 短路)")
            .isFalse();
        assertThat(String.valueOf(blockResult.data()))
            .as("interrupt + interruptBehavior=block → 工具继续执行 (per-tool 区分)")
            .isEqualTo("block_done");
    }

    // ════════════════════════════════════════════════════════════════════
    // T3 [ER-IMP-14]: synthetic error 不得用 mcpMeta 混用携带父链 (CC ts:153-205)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T3 [ER-IMP-14] synthetic error 不携带 mcpMeta · 父链归因经 AgentState map 达 ChatMessageDto.assistantMessageId")
    void createSyntheticErrorMessage_lineageToDtoInsteadOfMcpMeta() {
        // WHY (规则九): CC createSyntheticErrorMessage(toolUseId, reason, assistantMessage) 把
        //   sourceToolAssistantUUID: assistantMessage.uuid 写在 Message 字段 (ts:171,186,203),
        //   消费点是 insertMessageChain 父链 override (sessionStorage.ts:1033-1036);
        //   mcpMeta (Tool.ts:331-335) 仅属 MCP _meta/structuredContent, never sent to model.
        //   Java 父链归因放消息层: AgentState.assistantIdByToolUseId 绑定 (LlmAgentLoop:3230 /
        //   AgentLoopContext:1451) → AgentLoopContext:1598-1617 解析 parentAssistantId →
        //   toolResultMessage → ChatMessageDto.assistantMessageId (= CC sourceToolAssistantUUID
        //   等价位). executor 合成错误仅返回 ToolResult.error, 不再用 mcpMeta 混用携带父链.
        // RED (已证): 旧 3-arg 曾填 mcpMeta sourceToolAssistantUUID → 断言失败 (RED 阶段输出
        //   "expected: null but was: McpMeta[meta={sourceToolAssistantUUID=parent-uuid}]").
        StreamingToolExecutor exec = new StreamingToolExecutor(new ToolRegistry());

        ToolResult r = exec.createSyntheticErrorMessage("toolu_x", "user_interrupted");
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isTrue();
        assertThat(r.mcpMeta())
            .as("mcpMeta 仅属 MCP 协议通道, 不得被父链归因劫持 (CC Tool.ts:331-335)")
            .isNull();
        assertThat(String.valueOf(r.data()))
            .as("user_interrupted 文案对齐 CC REJECT_MESSAGE (messages.ts:212-213)")
            .startsWith("The user doesn't want to proceed with this tool use.");

        // 父链归因达消息层: AgentState 绑定 toolUseId→assistantId → toolResultMessage.assistantMessageId
        AgentState state = new AgentState("system-prompt");
        state.bindToolUseIdToAssistantId("toolu_x", "asst-uuid");
        String parentAssistantId = state.assistantIdByToolUseId().get("toolu_x");
        ChatMessageDto dto = com.nexusai.application.agent.LlmAgentLoop.toolResultMessage(
            r, parentAssistantId, null, List.of(), List.of(), Map.of());
        assertThat(dto.assistantMessageId())
            .as("tool 结果 DTO 父链 = 含对应 tool_use 的 assistant uuid (CC sourceToolAssistantUUID 等价位)")
            .isEqualTo("asst-uuid");

        ToolResult fb = exec.createSyntheticErrorMessage("toolu_y", "streaming_fallback");
        assertThat(String.valueOf(fb.data()))
            .isEqualTo("<tool_use_error>Error: Streaming fallback - tool execution discarded</tool_use_error>");

        ToolResult se = exec.createSyntheticErrorMessage("toolu_z", "sibling_error");
        assertThat(String.valueOf(se.data()))
            .contains("Cancelled: parallel tool call");
    }

    // ════════════════════════════════════════════════════════════════════
    // T4: abort bubble-up listener (CC ts:304-318)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T4 [GAP-EXEC-07] 子取消冒泡父 ctx: 非 sibling_error 冒泡 / sibling_error 不冒泡 / discarded 不冒泡")
    void perToolAbort_bubblesUpToParentCtx_whenNotSiblingAndNotDiscarded() {
        // WHY: CC toolAbortController.signal abort → 若 reason != sibling_error && 父未取消
        //      && !discarded → parent.abort(reason) (ts:304-318). 无冒泡则 permission 拒绝
        //      只杀单个 tool, 整个 turn 不终止 (#21056 regression).
        // RED: 移除 listener 守门 (直接 return) → 父永不取消 → 断言失败.
        ToolRegistry registry = new ToolRegistry();

        // 正向: 非 sibling_error 冒泡
        AbortController parent = new AbortController();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, context(parent));
        AbortController child = new AbortController();
        exec.registerAbortBubbleUp(child);
        child.abort("permission_cancel");
        assertThat(parent.isCancelled())
            .as("permission_cancel 必须冒泡到父 ctx.abortController (CC ts:312-314)")
            .isTrue();
        assertThat(parent.reason()).isEqualTo("permission_cancel");

        // 反向: sibling_error 不冒泡 (CC ts:308 守门)
        AbortController parent2 = new AbortController();
        StreamingToolExecutor exec2 = new StreamingToolExecutor(registry, context(parent2));
        AbortController child2 = new AbortController();
        exec2.registerAbortBubbleUp(child2);
        child2.abort("sibling_error");
        assertThat(parent2.isCancelled())
            .as("sibling_error 是设计内联的兄弟级联, 不结束 turn (CC ts:308)")
            .isFalse();

        // 反向: discarded 不冒泡 (CC ts:310 守门)
        AbortController parent3 = new AbortController();
        StreamingToolExecutor exec3 = new StreamingToolExecutor(registry, context(parent3));
        exec3.discard();
        AbortController child3 = new AbortController();
        exec3.registerAbortBubbleUp(child3);
        child3.abort("custom_reason");
        assertThat(parent3.isCancelled())
            .as("discarded 后子取消不冒泡 (CC ts:310)")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // T5: Bash isError success-path sibling abort (CC ts:347-364)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 [GAP-EXEC-08] Bash 正常 return isError → success 路径级联 sibling abort (CC ts:347-364)")
    void bashIsError_successPathTriggersSiblingAbort() throws Exception {
        // WHY: CC isErrorResult 不区分异常/正常 return — Bash 返回 is_error=true 的 result
        //      (非异常) 同样设 hasErrored + siblingAbortController.abort('sibling_error').
        //      Java 旧实现仅在 catch(Throwable) 级联, success 路径 isError 只发 telemetry.
        // RED: 删除 success 路径 abort 分支 → queuedTool 真实执行 → executed=1 + result=done → 断言失败.
        CountDownLatch bashEntered = new CountDownLatch(1);
        CountDownLatch releaseBash = new CountDownLatch(1);
        AtomicInteger queuedExecuted = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();

        Tool bashErr = new Tool() {
            @Override public String name() { return ToolNameConstants.BASH_TOOL_NAME; }
            @Override public String description() { return "bash failing tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                bashEntered.countDown();
                try { releaseBash.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                // [IMP-C2 返工 R2] 真实 Bash 语义错误载荷（无 "Error:" 前缀，BashTool.tsx:687+699
                //   输出 + "\nExit code N"）。旧前缀启发式对 "cat:" 漏检 → 本测试 RED 阶段 sibling abort 不触发。
                return ToolResult.error(call.id(),
                    "cat: /nonexistent: No such file or directory\nExit code 2", "execution");
            }
        };
        Tool queuedTool = new Tool() {
            @Override public String name() { return "queuedTool"; }
            @Override public String description() { return "queued after bash"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return false; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                queuedExecuted.incrementAndGet();
                return ToolResult.success(call.id(), "queued_done");
            }
        };
        registry.register(bashErr);
        registry.register(queuedTool);
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());

        JsonNode bashInput = JSON.createObjectNode().put("command", "cat /foo");
        exec.add(call("b1", ToolNameConstants.BASH_TOOL_NAME, bashInput));
        assertThat(bashEntered.await(10, TimeUnit.SECONDS)).isTrue();
        exec.add(call("q1", "queuedTool"));
        releaseBash.countDown();
        List<ToolResult> results = exec.getRemainingResults();
        pool.shutdown();

        ToolResult queuedResult = byData(results, "Cancelled: parallel tool call");
        assertThat(exec.getResultErrorFlags().get("q1"))
            .as("Bash success-path isError 必须级联 sibling abort → queuedTool 得 sibling_error synthetic")
            .isTrue();
        assertThat(String.valueOf(queuedResult.data()))
            .contains("Cancelled: parallel tool call")
            .contains("Bash(cat /foo)");  // CC getToolDescription (ts:243-252): ${name}(${command})
        assertThat(queuedExecuted.get())
            .as("queuedTool 不得真实执行 (sibling_error 短路)")
            .isZero();
        ToolResult bashResult = byData(results, "cat: /nonexistent");
        assertThat(String.valueOf(bashResult.data()))
            .as("出错 Bash 工具自身保留其错误 result（真实载荷 + Exit code）")
            .contains("cat: /nonexistent: No such file or directory")
            .contains("Exit code 2");
    }

    // ════════════════════════════════════════════════════════════════════
    // T6: TrackedTool 父 assistant 关联 (CC ts:21)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T6 [GAP-EXEC-08 + ER-IMP-14 TrackedTool] synthetic error 经 add(parent) 不携带 mcpMeta · 父链由消息层 map 归因")
    void trackedTool_carriesAssistantMessageForParentAttribution() throws Exception {
        // WHY: CC TrackedTool.assistantMessage (ts:21) 父子关联 — addTool(block, assistantMessage);
        //      synthetic error 用 assistantMessage.uuid 作 sourceToolAssistantUUID.
        //      Java 端父句柄 ToolParent.assistantMessageId (add#3 注入, 供 SubagentTool fork:
        //      :1540); 父链归因经 AgentState.assistantIdByToolUseId → toolResultMessage →
        //      ChatMessageDto.assistantMessageId (AgentLoopContext:1598-1617).
        //      executor 合成错误不再用 mcpMeta 混用携带父链.
        // RED (已证): 旧实现 abort 路径经 sourceAssistantId 填 mcpMeta → 断言失败 (RED 阶段输出
        //   "expected: null but was: McpMeta[meta={sourceToolAssistantUUID=parent-abc}]").
        AbortController parent = new AbortController();
        parent.abort("interrupt");
        ToolUseContext ctx = context(parent);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "cancelTool"; }
            @Override public String description() { return "cancel tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "cancel"; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "done");
            }
        });
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, ctx);

        exec.add(call("c1", "cancelTool"), ToolParent.of("parent-abc"), null);
        List<ToolResult> results = exec.getRemainingResults();
        pool.shutdown();

        ToolResult r = byData(results, "doesn't want to proceed");
        assertThat(exec.getResultErrorFlags().get("c1")).isTrue();
        assertThat(r.mcpMeta())
            .as("mcpMeta 仅属 MCP 协议通道, 不得被父链归因劫持; 父链由 AgentState.assistantIdByToolUseId "
                + "在消息层解析 (AgentLoopContext:1598-1617 → ChatMessageDto.assistantMessageId)")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // T7: hook 异常级联 = non-blocking + error attachment (CC utils/hooks.ts:2698-2730)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T7 [GAP-HOOK-02 修正] PreToolUse hook 抛异常 → 工具继续 + hook_error_during_execution attachment")
    void preToolUseHookGenericException_continuesToolWithErrorAttachment() throws Exception {
        // WHY: CC executeHooks catch (hooks.ts:2698-2730) 对 hook 异常 yield hook_non_blocking_error /
        //      hook_error_during_execution attachment, outcome=non_blocking_error — 工具<b>继续</b>,
        //      不 stop / 不 erroredCount++. (B.md 原措辞 "stop/erroredCount++" 与 CC 源码不符,
        //      Pattern #9 实证修正 — 见 J.md.)
        // RED: 移除 HookRegistry 错误 sink 装配 → attachment 不注入 → 断言失败.
        AgentState state = new AgentState("system prompt");
        HookRegistry hooks = new HookRegistry();
        PreToolUseHook throwingHook = (toolName, input, ctx) -> {
            throw new RuntimeException("intentional hook explosion");
        };
        hooks.registerPreToolUse("throwing", throwingHook);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub_done");
            }
        });
        // 5 参便捷构造器 (registry, ctx, handler, gate, hookRegistry) → 6 参终端构造器装配 sink
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, context(),
            null, null, hooks);
        exec.setAgentState(state);

        exec.add(call("h1", "stub"));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(exec.getResultErrorFlags().get("h1"))
            .as("hook 抛异常不得阻断工具 (CC non-blocking, outcome=non_blocking_error)")
            .isFalse();
        assertThat(state.attachments())
            .as("hook 异常必须注入 hook_error_during_execution attachment (CC hooks.ts:2714-2728)")
            .anyMatch(a -> "hook_error_during_execution".equals(a.type()));
    }

    // ════════════════════════════════════════════════════════════════════
    // T9 [IMP-C4 REQ-G3-2-3]: PreToolUse stop 分支 content = CANCEL_MESSAGE
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T9 [IMP-C4] PreToolUse stop 分支 content = CANCEL_MESSAGE（CC toolExecution.ts:443-448，修复 'Error: undefined'）")
    void preToolUseStopCase_usesCancelMessage() throws Exception {
        // WHY: TR-A3 △-4 — Java 旧实现 PreToolUse stop case content = `Error: ${stopReason}`
        //   （stopReason null → "Error: undefined"，LLM 可见行为可观测差异）。CC
        //   toolExecution.ts:443-448 createToolResultStopMessage content = CANCEL_MESSAGE
        //   （utils/messages.ts:210-211）+ withMemoryCorrectionHint（Java 恒 identity）。
        //   触发条件：hookRegistry != null + hook 执行期间 ctx.abortController().isCancelled()（abort-stop 路径）。
        // RED: 回退旧 `Error: undefined` → data != CANCEL_MESSAGE → 断言红.
        AbortController parent = new AbortController();
        HookRegistry hooks = new HookRegistry();
        // hook 执行期间 abort 父 controller（模拟"hook 期间用户中断"→ 工具不执行 + stop case）
        hooks.registerPreToolUse("aborter", (toolName, input, ctx) -> {
            parent.abort("interrupt");
            return com.nexusai.application.agent.permission.hook.AggregatedHookResult.proceed();
        });
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "blockTool"; }
            @Override public String description() { return "block tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "done");
            }
        });
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, context(parent), null, null, hooks);

        exec.add(call("p1", "blockTool"));
        List<ToolResult> results = exec.getRemainingResults();

        ToolResult r = results.stream()
            .filter(t -> LlmAgentLoop.isToolErrorData(t.data()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no error result for PreToolUse stop case"));
        assertThat(String.valueOf(r.data()))
            .as("PreToolUse stop 分支 content = CANCEL_MESSAGE（CC toolExecution.ts:443-448）")
            .isEqualTo(com.nexusai.application.agent.permission.PermissionRejectMessages.CANCEL_MESSAGE);
    }

    // ════════════════════════════════════════════════════════════════════
    // T8: sibling_error 不冒泡边界 (CC ts:308) — 端到端
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T8 [GAP-EXEC-07 边界] Bash sibling_error 不冒泡到父 ctx (端到端)")
    void bashSiblingError_doesNotBubbleToParentCtx() throws Exception {
        // WHY: CC ts:308 `reason !== 'sibling_error'` 守门 — sibling 级联只杀兄弟,
        //      不结束 turn. Bash 错误 → siblingAbortController.abort('sibling_error')
        //      → per-tool child abort(sibling_error) → bubble-up listener 必须拦截.
        // RED: 移除 sibling_error 守门 → 子取消冒泡 → parent cancelled → 断言失败.
        AbortController parent = new AbortController();
        ToolUseContext ctx = context(parent);
        CountDownLatch bashEntered = new CountDownLatch(1);
        CountDownLatch releaseBash = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();

        Tool bashErr = new Tool() {
            @Override public String name() { return ToolNameConstants.BASH_TOOL_NAME; }
            @Override public String description() { return "bash failing"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                bashEntered.countDown();
                try { releaseBash.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                // [IMP-C2 返工 R2] 真实 Bash 语义错误载荷（ls: 命令失败，无 "Error:" 前缀）。
                return ToolResult.error(call.id(),
                    "ls: cannot access /nope: No such file or directory\nExit code 2", "execution");
            }
        };
        Tool queuedTool = new Tool() {
            @Override public String name() { return "queuedTool"; }
            @Override public String description() { return "queued"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return false; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "done");
            }
        };
        registry.register(bashErr);
        registry.register(queuedTool);
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, ctx);

        exec.add(call("b1", ToolNameConstants.BASH_TOOL_NAME,
            JSON.createObjectNode().put("command", "ls /nope")));
        assertThat(bashEntered.await(10, TimeUnit.SECONDS)).isTrue();
        exec.add(call("q1", "queuedTool"));
        releaseBash.countDown();
        List<ToolResult> results = exec.getRemainingResults();
        pool.shutdown();

        assertThat(parent.isCancelled())
            .as("sibling_error 不冒泡 → 父 ctx.abortController 不得取消 (CC ts:308)")
            .isFalse();
        assertThat(String.valueOf(byData(results, "Cancelled: parallel tool call").data()))
            .as("queuedTool 仍得 sibling synthetic (级联生效但不冒泡)")
            .contains("Cancelled: parallel tool call");
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-C4 R1 rework] executor 路径 isEnabled 守卫（REQ-G3-2-1）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[IMP-C4 R1] exec.add: 已注册 disabled 工具报 No such tool available 且 0 执行（CC tools.ts:325 + StreamingToolExecutor.ts:91）")
    void add_disabledTool_returnsNoSuchToolAvailable_andDoesNotExecute() throws Exception {
        // WHY: CC getTools() isEnabled 过滤在可见集层（tools.ts:325）→ disabled 工具不在执行器
        //   pool → StreamingToolExecutor.ts:77 findToolByName 查不到 → :91 `No such tool available`
        //   （is_error=true）。Java add() 主路径经 registry.get 解析注册表（含 disabled），若缺
        //   isEnabled 守卫则 disabled 工具会被真实执行，与 CC 语义相悖（dispatch 守卫仅覆盖 fork 路径）。
        // RED 变异点: 去掉 add() 的 isEnabled 守卫 → disabled 工具真实执行 → executeCalls==1
        //   + data="ok"（非 error）→ 断言红.
        AtomicInteger executeCalls = new AtomicInteger();
        Tool disabled = new Tool() {
            @Override public String name() { return "DisabledTool"; }
            @Override public String description() { return "disabled stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isEnabled() { return false; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                executeCalls.incrementAndGet();
                return ToolResult.success(call.id(), "ok");
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ToolRegistry registry = new ToolRegistry().register(disabled);
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context());
        exec.add(call("c1", "DisabledTool"));
        List<ToolResult> results = exec.getRemainingResults();
        pool.shutdown();
        assertThat(results).as("disabled 工具仍产出 1 条结果（error）").hasSize(1);
        assertThat(LlmAgentLoop.isToolErrorData(results.get(0).data()))
            .as("disabled 工具执行结果必须为 isError（CC StreamingToolExecutor.ts:92 is_error: true）")
            .isTrue();
        assertThat(String.valueOf(results.get(0).data()))
            .as("disabled 工具报 No such tool available（CC StreamingToolExecutor.ts:91 文案）")
            .isEqualTo("No such tool available: DisabledTool");
        assertThat(executeCalls.get())
            .as("disabled 工具不得真实执行（CC 可见集过滤 → 执行器 findToolByName 查不到）")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════════════════════════════

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseBlock call(String id, String name, JsonNode input) {
        return new ToolUseBlock(id, name, input);
    }

    private static ToolUseContext context() {
        return context(new AbortController());
    }

    private static ToolUseContext context(AbortController abortController) {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", abortController, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> Collections.unmodifiableSet(java.util.Set.of()));
    }

    /** [IMP-C2] toolUseId 由执行器推导（结果 add 序 == getResultErrorFlags 键序），
     *  results 内无 toolUseId 字段，按结果 data 内容匹配（各测试工具 data 可区分）。 */
    private static ToolResult byData(List<ToolResult> results, String dataSubstring) {
        return results.stream()
            .filter(r -> String.valueOf(r.data()).contains(dataSubstring))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no result containing " + dataSubstring));
    }
}
