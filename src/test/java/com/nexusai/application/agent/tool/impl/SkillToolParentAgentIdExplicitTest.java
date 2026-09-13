package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * [批 2 · A 类 #2] {@code SkillTool.parent_agent_id} <b>显式传参</b>回归测试。
 *
 * <p><b>缺陷（R2 实证）</b>：本工具经 {@code StreamingToolExecutor:1296} 的 tool-exec
 * fixed-8 池执行（该执行器只回放 MDC + projectRoot，<b>不</b>回放 {@code AgentContext}），
 * 而旧实现 {@code resolveParentAgentId()} 读 {@link AgentContext#getAgentContext()} ThreadLocal
 * ⇒ 池线程恒 null ⇒ {@code parent_agent_id} <b>生产永不下发</b>。
 *
 * <p><b>修法</b>：取值源改显式参数 {@link ToolUseContext#agentId()}（工具执行闭包
 * {@code tool.execute(call, ctx, cb)} 显式携带，跨线程存活）。
 *
 * <p><b>夹具设计（防零覆盖力）</b>：本测试<b>不用</b>「测试线程设 ThreadLocal → 同线程断言」
 * 那种夹具（本仓已有此类夹具掩盖同类生产缺陷）。三条用例全部在<b>独立真实线程</b>
 * （模拟 tool-exec 池线程）上执行工具，并各自带反向对照：
 * <ol>
 *   <li>池线程无任何 AgentContext → 断言值非空（旧实现必红）</li>
 *   <li>池线程注册<b>诱饵</b> AgentContext（不同 agentId）→ 断言值 = 显式 ctx 值 ≠ 诱饵
 *       （证明取值源是显式参数而非 ThreadLocal；旧实现必红）</li>
 *   <li>主线程语义（ctx.agentId()==null）→ 断言字段<b>省略</b>（缺值策略 (b) 边界对照，
 *       防「恒返回某值」的假绿）</li>
 * </ol>
 */
@DisplayName("[批 2 · A#2] SkillTool parent_agent_id 显式传参（池线程）")
class SkillToolParentAgentIdExplicitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 显式 ctx.agentId 对应的 CC 形制 a+16hex（17 字符：'a' + 16 hex）。 */
    private static final String EXPLICIT_AGENT_HEX = "a1234567890abcdef";

    /** 诱饵 AgentContext 的 agentId（恒与 EXPLICIT_AGENT_HEX 不同；后缀 16 hex）。 */
    private static final String DECOY_AGENT_HEX = "adeca00000000000f";

    private static final String POOL_THREAD_NAME = "test-tool-exec-pool";

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        // 防 ThreadLocal 串台：本测试仅在诱饵用例内注册 AgentContext（runWithAgentContext 作用域自动还原）
        assertThat(AgentContext.getAgentContext()).as("测试线程不得残留 AgentContext").isNull();
    }

    /** 真实池线程（模拟 tool-exec fixed-8 池）· 单线程便于断言线程身份。 */
    private ExecutorService newToolExecPool() {
        pool = Executors.newSingleThreadExecutor(r -> new Thread(r, POOL_THREAD_NAME));
        return pool;
    }

    /** 在真实池线程执行 {@code body}，等待完成并把异常原样抛回测试线程（fail loud）。 */
    private void runOnPoolThread(ExecutorService exec, Runnable body) throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        exec.execute(() -> {
            try {
                threadName.set(Thread.currentThread().getName());
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).as("池线程任务须在 10s 内完成").isTrue();
        if (failure.get() != null) {
            throw new AssertionError("池线程执行抛出: " + failure.get(), failure.get());
        }
        assertThat(threadName.get()).as("必须在独立真实线程（池线程）执行，非测试线程")
            .isEqualTo(POOL_THREAD_NAME);
        assertThat(Thread.currentThread().getName()).as("断言线程 ≠ 执行线程")
            .isNotEqualTo(POOL_THREAD_NAME);
    }

    /** 返回单个 Command 的注册表（复用 SkillToolTelemetryTest.SingleCommandRegistry 模式，避免读盘）。 */
    private static final class SingleCommandRegistry extends SkillRegistry {
        private final List<Command> cmds;

        SingleCommandRegistry(List<Command> cmds) {
            super(".claude/skills");
            this.cmds = cmds;
        }

        @Override
        public List<Command> getAllCommands() {
            return cmds;
        }
    }

    private static Command skill(String name) {
        Command cmd = new Command();
        cmd.setName(name);
        cmd.setSource(CommandSource.USER);
        cmd.setPromptFn((args, cwd) -> List.of(
            (ContentBlockParam) new ContentBlockParam.TextBlockParam("# " + name + "\n\nbody")));
        return cmd;
    }

    private static ToolUseBlock skillBlock(String skillName) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", skillName);
        return new ToolUseBlock(UUID.randomUUID().toString(), "Skill", input);
    }

    private static String newSessionId() {
        return "sess-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> captured(org.mockito.ArgumentCaptor captor) {
        return (Map<String, Object>) captor.getValue();
    }

    private static org.mockito.ArgumentCaptor<Map<String, Object>> invocationCaptor() {
        return (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(Map.class);
    }

    // ══════════════════════════════════════════════════════════════════════
    // #1 池线程无 AgentContext → 值仍非空（旧 ThreadLocal 实现必红）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("池线程无 AgentContext → parent_agent_id = 显式 ctx.agentId()（非空 · 旧实现恒 null）")
    void poolThreadWithoutAgentContext_parentAgentIdFromExplicitCtx() throws Exception {
        // WHY: 生产路径 = 池线程 + 零 AgentContext 回放 ⇒ 旧实现（读 ThreadLocal）恒 null，
        //   parent_agent_id 永不下发（BQ 无法归因「哪个子代理调了哪个 skill」）。
        //   本用例在真实池线程执行，并断言该线程上 AgentContext==null，证明值只可能来自显式参数。
        SkillToolImpl tool = new SkillToolImpl(new SingleCommandRegistry(List.of(skill("explicit-skill"))));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        ToolUseContext tuc = ToolUseContext.of(
            AgentContext.packAgentId(EXPLICIT_AGENT_HEX), newSessionId());
        AtomicReference<AgentContext> seen = new AtomicReference<>();

        runOnPoolThread(newToolExecPool(), () -> {
            seen.set(AgentContext.getAgentContext());   // 无回放 → 期望 null
            tool.execute(skillBlock("explicit-skill"), tuc);
        });

        assertThat(seen.get()).as("池线程上不得存在 AgentContext（无回放）").isNull();
        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(captured(captor))
            .as("显式传参生效：ThreadLocal 为空但 parent_agent_id 非空")
            .containsEntry("parent_agent_id", EXPLICIT_AGENT_HEX);
    }

    // ══════════════════════════════════════════════════════════════════════
    // #2 诱饵 AgentContext → 显式参数胜出（证明取值源不是 ThreadLocal）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("池线程存在诱饵 AgentContext → parent_agent_id 取显式 ctx（≠ 诱饵 · 证明非 ThreadLocal 源）")
    void poolThreadWithDecoyAgentContext_explicitCtxWins() throws Exception {
        // WHY: 反向对照 · 若实现退回「读当前线程 AgentContext」，本用例会取到诱饵 id 而变红。
        //   该断言同时排除「ThreadLocal 读」与「恒空/硬编码」两种假绿。
        SkillToolImpl tool = new SkillToolImpl(new SingleCommandRegistry(List.of(skill("explicit-skill"))));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        ToolUseContext tuc = ToolUseContext.of(
            AgentContext.packAgentId(EXPLICIT_AGENT_HEX), newSessionId());
        AgentContext decoy = new AgentContext.SubagentContext(
            DECOY_AGENT_HEX, null, "DecoyAgent", true, null, null);
        AtomicReference<AgentContext> seen = new AtomicReference<>();

        runOnPoolThread(newToolExecPool(), () ->
            AgentContext.runWithAgentContext(decoy, () -> {
                seen.set(AgentContext.getAgentContext());   // 诱饵在本线程可见 → 旧实现会读到它
                tool.execute(skillBlock("explicit-skill"), tuc);
            }));

        assertThat(seen.get()).as("诱饵已生效（同线程可见）").isSameAs(decoy);
        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        Map<String, Object> attrs = captured(captor);
        assertThat(attrs)
            .as("显式参数胜出（= ctx.agentId()），而非诱饵（ThreadLocal 值）")
            .containsEntry("parent_agent_id", EXPLICIT_AGENT_HEX);
        assertThat(attrs).doesNotContainValue(DECOY_AGENT_HEX);
    }

    // ══════════════════════════════════════════════════════════════════════
    // #3 主线程语义（ctx.agentId()==null）→ 字段省略
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("主线程语义 ctx.agentId()==null → parent_agent_id 字段省略（CC undefined 等价 · 差分对照）")
    void mainThreadNullAgentId_fieldOmitted() throws Exception {
        // WHY: CC getAgentContext()?.agentId 为 undefined 时（:166-169）字段不展开；Java 主线程
        //   TUC.agentId 恒 null（ToolUseContext 不变量）。差分对照防止实现「恒返回某值」的假绿。
        SkillToolImpl tool = new SkillToolImpl(new SingleCommandRegistry(List.of(skill("explicit-skill"))));
        Telemetry telemetry = spy(new Telemetry());
        tool.setTelemetry(telemetry);

        ToolUseContext mainTuc = ToolUseContext.of(null, newSessionId());

        runOnPoolThread(newToolExecPool(), () ->
            tool.execute(skillBlock("explicit-skill"), mainTuc));

        var captor = invocationCaptor();
        verify(telemetry).recordEvent(eq("tengu_skill_tool_invocation"), captor.capture());
        assertThat(captured(captor)).doesNotContainKey("parent_agent_id");
    }
}
