package com.nexusai.application.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolParent;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.OpenAiToolCallAccumulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fix-toolcall-fault A/R3] 混合批（幽灵槽 + 真调用）结果错配修复 · 按 toolUseId 配对。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：幽灵槽（name 空）在流式期 {@code isComplete()} 恒 false ⇒ 不入
 * executor；派发前的 catch-up 补 add 只能**追加在尾部** ⇒ executor 的 drain 顺序与
 * {@code msg.toolCalls()} 的 index 顺序**相反**（更普遍地：drain 顺序 = 完成批序，并行调用快者先出）。
 * 原 IMP-C2「按位置配对」（AgentLoopContext 旧 :2196-2223）在此会把**真调用的结果写到幽灵槽 id 下**
 * —— 父 Agent 看到"幽灵调用成功、真调用失败"的错乱内容（比报错更坏的静默错误）。
 *
 * <p>修复 = 结果↔调用**按 toolUseId** 绑定：id 由执行器 drain 时按同序记录
 * （{@code StreamingToolExecutor.drainedToolUseIds}）经 {@code ToolRunOutcome} 透传
 * （ToolResult 4 字段契约不存 id，故只能在此记录）。
 *
 * <p>变异点：把 {@code pairResultsByToolUseId} 换回位置配对（results.get(i) → 第 i 个 call）
 * → 用例 1 的两条内容断言互换 → 红。
 */
@DisplayName("[fix-toolcall-fault A/R3] 混合批结果-调用按 id 配对（幽灵在前 + 真调用在后）")
class ToolResultPairingByToolUseIdTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Tool realTool() {
        return new Tool() {
            @Override public String name() { return "realTool"; }
            @Override public String description() { return "real stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "real-ok");
            }
        };
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseContext context() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of(), "", new AbortController(), List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> Collections.unmodifiableSet(java.util.Set.of()));
    }

    @Test
    @DisplayName("R3-1 幽灵在前 + 真调用在后：按 id 配对 → 真调用拿到自己的结果（位置配对会串内容）")
    void ghostFirst_realCallAfter_idPairingKeepsContentCorrect() {
        // WHY: 场景复刻 —— 响应 tool_calls = [ghost-1(哨兵名), real-1]；流式期只有 real-1 入队
        //   （幽灵槽 isComplete() 恒 false）→ catch-up 把 ghost-1 追加在尾部
        //   ⇒ executor 顺序 [real-1, ghost-1] 与 tool_calls 顺序 [ghost-1, real-1] 相反。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        StreamingToolExecutor exec = new StreamingToolExecutor(
            new ToolRegistry().register(realTool()), pool, context());
        List<String> toolCallOrder = List.of("ghost-1", "real-1"); // msg.toolCalls() 的 index 序
        exec.add(call("real-1", "realTool"), ToolParent.of("asst-1"), null);   // 流式回调入队
        exec.add(call("ghost-1", OpenAiToolCallAccumulator.MALFORMED_TOOL_CALL_NAME),
            ToolParent.of("asst-1"), null);                                    // catch-up 追加在尾部
        assertThat(exec.hasToolCall("ghost-1"))
            .as("catch-up 判据：补 add 后 executor 必须能查到该 toolUseId").isTrue();

        List<ToolResult> results = exec.getRemainingResults();
        List<String> drainedIds = exec.drainedToolUseIds();
        pool.shutdown();

        assertThat(results).as("两条调用各得一条结果（不再出现 Tool result missing）").hasSize(2);
        assertThat(drainedIds).as("id 台账与结果同序同量").containsExactly("real-1", "ghost-1");

        Map<String, ToolResult> byId = AgentLoopContext.pairResultsByToolUseId(results, drainedIds);
        assertThat(String.valueOf(byId.get("real-1").data()))
            .as("真调用必须拿到自己的成功结果（位置配对会把 ghost 的结果给它）").isEqualTo("real-ok");
        assertThat(String.valueOf(byId.get("ghost-1").data()))
            .as("幽灵槽拿 CC 同形 error 结果（No such tool available）")
            .contains("No such tool available");

        // 反面对照（证明该用例真的能抓错配）：位置配对下 toolCallOrder[0]=ghost 会拿到 results[0]=真结果
        assertThat(String.valueOf(results.get(0).data()))
            .as("前提断言：drain 首位确实是真调用的结果（故位置配对必然串内容）")
            .isEqualTo("real-ok");
        assertThat(String.valueOf(byId.get(toolCallOrder.get(0)).data()))
            .as("按 id 配对后 ghost（index 0）拿到的不是真调用结果 —— 与位置配对相反")
            .isNotEqualTo("real-ok");
    }

    @Test
    @DisplayName("R3-2 hasToolCall 判据（catch-up 门）：未入队 → false；入队后 → true")
    void hasToolCall_gate() {
        // WHY: AgentLoopContext 的 catch-up 循环以 hasToolCall 为门；若恒 true（或按名字判）
        //   则幽灵槽永不补齐（回到 results 少一项 → Tool result missing 路径）。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        StreamingToolExecutor exec = new StreamingToolExecutor(
            new ToolRegistry().register(realTool()), pool, context());
        assertThat(exec.hasToolCall("real-1")).as("未入队 → false").isFalse();
        assertThat(exec.hasToolCall(null)).as("null 安全 → false").isFalse();
        exec.add(call("real-1", "realTool"), ToolParent.of("asst-1"), null);
        assertThat(exec.hasToolCall("real-1")).as("入队后 → true（catch-up 跳过，不双 add）").isTrue();
        pool.shutdown();
    }

    @Test
    @DisplayName("R3-3 防御兜底：id 台账缺失/短于结果 → 该结果无主（配对侧对无结果调用补 synthetic error）")
    void missingResultIds_leaveResultsUnpaired() {
        // WHY: ToolResult 4 字段契约不存 id，id 台账是**唯一**的配对凭据；台账缺失时不得"按位置猜"
        //   （猜 = 把 A 的结果写给 B）。正确行为 = 结果无主、由调用侧补 synthetic error：
        //   既保证 [assistant(N calls)] 后必有 N 条 tool 响应（OpenAI 400 防线），又不串内容。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        StreamingToolExecutor exec = new StreamingToolExecutor(
            new ToolRegistry().register(realTool()), pool, context());
        exec.add(call("real-1", "realTool"), ToolParent.of("asst-1"), null);
        List<ToolResult> results = exec.getRemainingResults();
        pool.shutdown();
        assertThat(results).hasSize(1);

        assertThat(AgentLoopContext.pairResultsByToolUseId(results, List.of()))
            .as("空台账 → 无配对（不得按位置猜）").isEmpty();
        assertThat(AgentLoopContext.pairResultsByToolUseId(results, null))
            .as("null 台账 → 无配对，且不抛 NPE").isEmpty();
        assertThat(AgentLoopContext.pairResultsByToolUseId(List.of(), List.of("real-1")))
            .as("无结果 → 空映射（调用侧对每个调用补 synthetic error）").isEmpty();
    }
}
