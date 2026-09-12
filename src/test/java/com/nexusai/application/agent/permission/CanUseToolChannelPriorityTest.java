package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [5b · canUseTool 通道] 受限 canUseTool 通道 + gate 优先级测试（D2 fork 收敛硬前置）。
 *
 * <p><b>WHY 本测试存在（规则九 · 验证意图，非仅行为）</b>：
 * fork 最刚需的能力是<b>按 input 内容判定</b>的受限权限 —— CC {@code createAutoMemCanUseTool}
 * (extractMemories.ts:170-229)：Read/Grep/Glob 无条件放行、<b>同一个 Bash 只有只读命令才放行</b>
 * （{@code tool.isReadOnly(parsed.data)}，:204-215）、Edit/Write 仅 auto-memory 目录内、其余 deny。
 * 这种语义 {@code availableTools} 白名单<b>表达不了</b>（子代理用白名单所以能共用主循环，fork 不能）。
 * CC 侧 fork 经 {@code createSubagentContext()} 后用<b>自己的</b> canUseTool 调同一个 query
 * （forkedAgent.ts:569）—— 而 nexusai 主循环承载该能力的落点 {@code QueryParams.canUseTool}
 * 在 H9-GAP-4 被删除，权限只能从单例 {@code beans.permissionGate} 取 → fork 只能自建循环。
 * 本测试钉死 4 条不变量，任何一条被业务逻辑变更破坏都必须红：
 * <ol>
 *   <li><b>未传 canUseTool → 逐位回落现状</b>：单例 gate 被消费，且 {@code check} 六参逐位与
 *       5b 前一致（主线程 / 子代理 / hook agent 三条生产路径零行为变化）—— 回归底线</li>
 *   <li><b>优先级</b>：传入受限 canUseTool → <b>覆盖</b>单例 gate（gate 零消费），与 CC
 *       {@code StreamingToolExecutor(tools, canUseTool, toolUseContext)}（query.ts:763）同构</li>
 *   <li><b>受限语义可表达（INV-6）</b>：同一个 Bash 工具，按 input 内容放行/拒绝 —— 这是通道
 *       存在的唯一理由，不能证明它就等于没实现</li>
 *   <li><b>wiring 层优先级</b>：{@code AgentLoopContext.buildStreamingExecutor} 的
 *       {@code QueryParams.canUseTool} 透传位优先于 {@code beans.permissionGate}</li>
 * </ol>
 *
 * <p><b>变异点（回退任一优先级行 → 必红）</b>：
 * <ul>
 *   <li>{@code StreamingToolExecutor} 内 {@code canUseTool != null ? canUseTool.canUse(...) : gate.check(...)}
 *       的判定改回恒 false → {@link #perTurnCanUseTool_overridesSingletonGate} /
 *       {@link #restrictedCanUseTool_decidesPerToolInput} 红</li>
 *   <li>{@code buildStreamingExecutor} 不透传 override → {@link #buildStreamingExecutor_prefersCanUseToolOverBeansGate} 红</li>
 * </ul>
 *
 * @see HookPermissionResolver.CanUseTool
 * @see com.nexusai.application.agent.loop.QueryParams#canUseTool
 * @since 5b
 */
@DisplayName("[5b] 受限 canUseTool 通道 + gate 优先级（INV-6）")
class CanUseToolChannelPriorityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-00000005b001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-00000005b002";

    // ════════════════════════════════════════════════════════════════════
    // 测试基建
    // ════════════════════════════════════════════════════════════════════

    /** 计数 stub 工具 · {@code executeCalls} 证明「DENY → 工具真实未执行」（INV-6 硬断言）。 */
    private static final class CountingTool implements Tool {
        private final String name;
        final AtomicInteger executeCalls = new AtomicInteger();

        CountingTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "5b counting stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            executeCalls.incrementAndGet();
            return ToolResult.success(call.id(), "executed:" + name);
        }
    }

    /** 恒定决策管线（隔离 10 层真实管线，只钉 gate 消费与否）。 */
    private static final class StubPipeline extends PermissionPipeline {
        private final PermissionResult result;
        StubPipeline(PermissionResult result) { this.result = result; }
        @Override public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                                ToolUseContext ctx, ToolPermissionContext permCtx) {
            return result;
        }
    }

    /** 恒放行弹窗器（gate ASK 分支不参与本测试，仅满足构造器必填）。 */
    private static final class AllowPrompter implements PermissionPrompter {
        @Override public PermissionResult prompt(Tool tool, JsonNode input, PermissionDecisionReason reason,
                                                 ToolUseContext ctx, String requestId) {
            return new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("user allowed"), requestId, false, null, List.of());
        }
    }

    /**
     * 记录型 gate · 捕获 {@code check} 六参后委托真实 gate 决策。
     *
     * <p>WHY: 「未传 canUseTool 时三路行为逐位不变」不能只断言结果，必须钉住<b>入参契约</b> ——
     * 5b 前内层 lambda 形如
     * {@code gate.check(tool, new ToolUseBlock(toolUseId, tool.name(), input), input, cctx, cctx.permissionContext(), forceDecision)}。
     * 本类把该契约变成可断言事实（任一参漂移 → 测试红）。
     */
    private static final class RecordingGate extends ToolPermissionGate {
        int checkCalls;
        ToolUseBlock capturedCall;
        JsonNode capturedInput;
        ToolUseContext capturedCtx;
        ToolPermissionContext capturedPermCtx;
        PermissionResult capturedForceDecision;

        RecordingGate(PermissionResult pipelineResult) {
            super(new StubPipeline(pipelineResult), new AllowPrompter(), null, null, null);
        }

        @Override public DecisionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                              ToolUseContext ctx, ToolPermissionContext permCtx,
                                              PermissionResult forceDecision) {
            checkCalls++;
            capturedCall = call;
            capturedInput = input;
            capturedCtx = ctx;
            capturedPermCtx = permCtx;
            capturedForceDecision = forceDecision;
            return super.check(tool, call, input, ctx, permCtx, forceDecision);
        }
    }

    private static ToolPermissionContext permCtx() {
        return new ToolPermissionContext(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
    }

    private static ToolUseContext newCtx(List<Tool> tools, ToolPermissionContext permCtx) {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            tools, "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);
    }

    private static PermissionResult allowResult() {
        return new PermissionResult.Allow(JSON.createObjectNode(),
            new PermissionDecisionReason.Other("allowed by stub pipeline"), "stub", false, null, List.of());
    }

    private static ToolPermissionGate.DecisionResult denyDecision(String message, String toolUseId) {
        return ToolPermissionGate.DecisionResult.deny(new PermissionResult.Deny(
            message, new PermissionDecisionReason.Other("restricted-canUseTool-5b"), toolUseId));
    }

    private static List<String> dataOf(List<ToolResult> results) {
        return results.stream().map(r -> String.valueOf(r.data())).toList();
    }

    // ════════════════════════════════════════════════════════════════════
    // ① 未传 canUseTool → 逐位回落现状（回归底线：三路生产路径零行为变化）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("① canUseTool 未传 → 单例 gate 被消费，且 check 六参与 5b 前逐位一致（主线程/子代理/hook agent）")
    void canUseToolAbsent_fallsBackToGateWithIdenticalArgs() {
        // WHY: 主线程 run() / SubagentExecutor / ExecAgentHook 三路经 QueryParams.forLoop 构建，
        //   canUseTool 恒 null（未注入）。此时取到的决策源必须与 5b 之前<b>逐位相同</b> ——
        //   不仅是"结果相同"，入参构造（ToolUseBlock(id, name, input) / ctx / permCtx / forceDecision）
        //   也必须相同，否则 hook ask 的 forceDecision 透传、rule 内容规则（依赖 input）等会静默漂移。
        CountingTool tool = new CountingTool("Read");
        ToolRegistry registry = new ToolRegistry().register(tool);
        RecordingGate gate = new RecordingGate(allowResult());
        ToolPermissionContext permCtx = permCtx();
        ToolUseContext perTurn = newCtx(List.of(tool), permCtx);
        AgentState state = new AgentState("sys");

        // 5 参兼容构造器（canUseTool 缺省位）+ 8 参 buildStreamingExecutor = 三路生产路径形态
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, perTurn, null, gate, null);
        exec.setAgentState(state);
        ToolUseBlock call = new ToolUseBlock("toolu_gate_1", "Read",
            JSON.createObjectNode().put("file_path", "/tmp/5b.txt"));
        exec.add(call);
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(gate.checkCalls).as("未传 canUseTool → 单例 gate 必须仍被消费（回落现状）").isEqualTo(1);
        assertThat(gate.capturedCall.id()).as("check 第 2 参 call.id 必须是 toolUseId").isEqualTo("toolu_gate_1");
        assertThat(gate.capturedCall.name()).as("check 第 2 参 call.name 必须是工具名").isEqualTo("Read");
        assertThat(gate.capturedInput).as("check 第 3 参 input 与 call.input 同源").isEqualTo(call.input());
        assertThat(gate.capturedCtx).as("check 第 4 参 = per-turn TUC 同实例").isSameAs(perTurn);
        assertThat(gate.capturedPermCtx).as("check 第 5 参 = ctx.permissionContext() 同实例").isSameAs(permCtx);
        assertThat(gate.capturedForceDecision).as("无 hook ask → forceDecision null（原始透传）").isNull();

        assertThat(exec.getResultErrorFlags().get("toolu_gate_1")).isFalse();
        assertThat(tool.executeCalls.get()).as("gate ALLOW → 工具真实执行（现状不变）").isEqualTo(1);
        assertThat(dataOf(results)).as("行为与 5b 前一致的结果载荷").containsExactly("executed:Read");
    }

    // ════════════════════════════════════════════════════════════════════
    // ② 优先级：per-turn canUseTool 覆盖单例 gate（CC query.ts:763 / forkedAgent.ts:569）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("② 传入受限 canUseTool → 覆盖单例 gate（gate 零消费）+ DENY 时工具真实未执行")
    void perTurnCanUseTool_overridesSingletonGate() {
        // WHY: CC 侧 executor 只持调用方给的 canUseTool（query.ts:763），没有"单例 gate"这一层；
        //   Java 的 beans.permissionGate 只是该函数的<b>默认实现</b>。fork 把自己的受限函数传进来时
        //   必须整体接管决策，否则 fork 继承主线程权限 → INV-6 破坏（ToolRegistrationConfig:1719 自述）。
        CountingTool tool = new CountingTool("Read");
        ToolRegistry registry = new ToolRegistry().register(tool);
        RecordingGate gate = new RecordingGate(allowResult());   // 单例 = 全放行（若被消费则工具会执行）
        HookPermissionResolver.CanUseTool restricted =
            (t, input, cctx, toolUseId, forceDecision) -> denyDecision("受限：fork 不允许读取", toolUseId);
        ToolUseContext perTurn = newCtx(List.of(tool), permCtx());

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, perTurn, null, gate, restricted, null);
        exec.setAgentState(new AgentState("sys"));
        exec.add(new ToolUseBlock("toolu_override_1", "Read", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(gate.checkCalls).as("canUseTool 非 null → 单例 gate 零消费（优先级高于单例）").isZero();
        assertThat(exec.getResultErrorFlags().get("toolu_override_1")).isTrue();
        assertThat(tool.executeCalls.get()).as("DENY → 工具<b>真实未执行</b>（INV-6 硬断言）").isZero();
        assertThat(dataOf(results)).as("DENY 消息取受限 canUseTool 的 message（非 gate 的）")
            .containsExactly("受限：fork 不允许读取");
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ 受限语义可表达（INV-6 真实需求）：同一个 Bash 工具按 input 内容放行/拒绝
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("③ 受限语义可表达：同一 Bash 工具按 input 内容放行/拒绝（createAutoMemCanUseTool 的真实需求）")
    void restrictedCanUseTool_decidesPerToolInput() {
        // WHY: 白名单（availableTools）只能整体放开/关闭一个工具，表达不了"同一个 Bash，
        //   `ls` 放行、`rm -rf` 拒绝"。这正是 fork 不能共用主循环的唯一硬障碍（审计 §二）。
        //   本用例用最小实现证明通道够用：判定只依赖 (tool, input)，与 CC
        //   createAutoMemCanUseTool（extractMemories.ts:170-229）同构。
        CountingTool bash = new CountingTool("Bash");
        ToolRegistry registry = new ToolRegistry().register(bash);
        HookPermissionResolver.CanUseTool autoMemLike = (tool, input, cctx, toolUseId, forceDecision) -> {
            if (!"Bash".equals(tool.name())) {
                return denyDecision("only read-only shell commands are permitted", toolUseId);
            }
            String command = input.path("command").asText("");
            boolean readOnly = command.startsWith("ls") || command.startsWith("cat");
            return readOnly
                ? ToolPermissionGate.DecisionResult.allow()
                : denyDecision("Only read-only shell commands are permitted in this context "
                    + "(ls, find, grep, cat, stat, wc, head, tail, and similar)", toolUseId);
        };
        ToolUseContext perTurn = newCtx(List.of(bash), permCtx());

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, perTurn, null, null, autoMemLike, null);
        exec.setAgentState(new AgentState("sys"));
        exec.add(new ToolUseBlock("toolu_ro", "Bash", JSON.createObjectNode().put("command", "ls -la")));
        exec.add(new ToolUseBlock("toolu_rw", "Bash", JSON.createObjectNode().put("command", "rm -rf /")));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(exec.getResultErrorFlags().get("toolu_ro")).as("只读命令 → 放行").isFalse();
        assertThat(exec.getResultErrorFlags().get("toolu_rw")).as("写命令 → 拒绝（同一工具！）").isTrue();
        assertThat(bash.executeCalls.get()).as("同一 Bash 工具：只读放行 1 次，写命令被拒不得执行").isEqualTo(1);
        assertThat(dataOf(results)).as("拒绝消息逐字对齐 CC createAutoMemCanUseTool deny 文案")
            .anySatisfy(m -> assertThat(m).contains("Only read-only shell commands are permitted in this context"));
    }

    // ════════════════════════════════════════════════════════════════════
    // ④ wiring 层：buildStreamingExecutor 的 canUseTool 位优先于 beans.permissionGate
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("④ buildStreamingExecutor：传 canUseTool → 覆盖 beans.permissionGate；不传 → beans gate 被消费")
    void buildStreamingExecutor_prefersCanUseToolOverBeansGate() {
        // WHY: 优先级必须钉在<b>装配点</b>（AgentLoopContext.buildStreamingExecutor），否则
        //   QueryParams.canUseTool 只是"结构体无调用方"（H9-GAP-4 删除本字段时正是这个理由）。
        CountingTool tool = new CountingTool("Read");
        ToolRegistry registry = new ToolRegistry().register(tool);
        RecordingGate beansGate = new RecordingGate(allowResult());
        AgentLoopContext ctx = TestContexts.agentLoopContext(registry, null, null, null, null,
            new AgentLoopContext.ToolExecutionBeans(null, beansGate, null, null, null, null, false,
                null, null, null, null, null),
            (com.nexusai.application.agent.permission.hook.HookRegistry) null);
        AgentState state = new AgentState("sys", "sess-5b", null);
        ToolUseContext perTurn = newCtx(List.of(tool), permCtx());
        HookPermissionResolver.CanUseTool restricted =
            (t, input, cctx, toolUseId, forceDecision) -> denyDecision("受限：覆盖单例 gate", toolUseId);

        // 正例：9 参入口传受限 canUseTool → beans.permissionGate 零消费
        StreamingToolExecutor withOverride = AgentLoopContext.buildStreamingExecutor(
            ctx, perTurn, state, "tu-5b-override", null, true, null, null, restricted);
        assertThat(withOverride).as("availableTools 非空 → 必须构建出真实 executor").isNotNull();
        withOverride.setAgentState(state);
        withOverride.add(new ToolUseBlock("toolu_beans_1", "Read", JSON.createObjectNode()));
        withOverride.getRemainingResults();

        assertThat(beansGate.checkCalls).as("传入 canUseTool → 单例 beans.permissionGate 零消费").isZero();
        assertThat(tool.executeCalls.get()).as("DENY → 工具真实未执行").isZero();

        // 反例：8 参入口（未传 canUseTool，三路生产路径形态）→ beans.permissionGate 被消费
        StreamingToolExecutor withoutOverride = AgentLoopContext.buildStreamingExecutor(
            ctx, perTurn, state, "tu-5b-fallback", null, true, null, null);
        withoutOverride.setAgentState(state);
        withoutOverride.add(new ToolUseBlock("toolu_beans_2", "Read", JSON.createObjectNode()));
        withoutOverride.getRemainingResults();

        assertThat(beansGate.checkCalls).as("未传 canUseTool → 回落 beans.permissionGate（现状，零行为变化）")
            .isEqualTo(1);
        assertThat(tool.executeCalls.get()).as("gate ALLOW → 工具执行").isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // ⑤ QueryParams 通道形态：默认不注入 + 派生副本保留/清除通道
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑤ QueryParams：forLoop 默认 canUseTool=null（三路不变）；withCanUseTool 往返保真")
    void queryParams_canUseToolChannelDefaultAndRoundTrip() {
        // WHY: 通道的"默认不注入"必须钉死 —— 一旦 forLoop 默认值漂移成某个非 null 函数，
        //   三条生产路径会静默改变权限行为。同时派生副本必须<b>保留</b>通道（否则 5c fork 在
        //   withThinkingConfig/withQuerySourceValue 链上丢权限 → INV-6 静默失效）。
        QueryParams params = QueryParams.forLoop(
            List.of(), "sys", newCtx(List.of(), permCtx()), QuerySource.USER, "test-model",
            null, null, null, null, null, null, ProviderConfig.empty());
        assertThat(params.canUseTool()).as("forLoop 默认 canUseTool=null（主线程/子代理/hook agent 三路不变）")
            .isNull();

        HookPermissionResolver.CanUseTool restricted =
            (t, input, cctx, toolUseId, forceDecision) -> denyDecision("受限", toolUseId);
        QueryParams injected = params.withCanUseTool(restricted);

        assertThat(injected.canUseTool()).as("withCanUseTool 注入生效").isSameAs(restricted);
        assertThat(injected.querySource()).as("派生副本保留查询来源").isEqualTo(params.querySource());
        assertThat(injected.modelName()).as("派生副本保留模型名").isEqualTo(params.modelName());
        assertThat(injected.withThinkingConfig(null).canUseTool())
            .as("withThinkingConfig 派生必须保留 canUseTool 通道（不丢权限）").isSameAs(restricted);
        assertThat(injected.withQuerySourceValue("agent:builtin:fork").canUseTool())
            .as("withQuerySourceValue 派生必须保留 canUseTool 通道（不丢权限）").isSameAs(restricted);
        assertThat(injected.withCanUseTool(null).canUseTool())
            .as("withCanUseTool(null) 可显式清除 → 回落 gate").isNull();
    }
}
