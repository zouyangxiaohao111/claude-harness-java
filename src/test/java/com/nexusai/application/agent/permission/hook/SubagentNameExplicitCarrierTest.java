package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * [A#1 tuc-invoking-req] hook 侧 {@code subagent_name} 归因 · <b>显式 TUC 载体</b>行为锁。
 *
 * <h2>WHY（本批根因 · CLAUDE.md 规则九「测试验证意图」）</h2>
 * 旧实现 {@code SessionFileAccessHooks.subagentProps()} 读
 * {@code AgentContext.getSubagentLogName()}（plain ThreadLocal），而本 hook 的 PostToolUse
 * 回调由 {@code HookRegistry:2596
 * {@code CompletableFuture.supplyAsync(withSessionProjectRoot(...), HOOK_EXECUTOR)}}
 * 派发到 {@code HOOK_EXECUTOR} 线程 —— ThreadLocal 不跨线程，且 AgentContext 不在回放白名单
 * ⇒ {@code subagent_name} 生产恒空（CC 侧 AsyncLocalStorage 自动传播，无此缺陷）。
 *
 * <p>修法 = 显式载体（CC 自己就把 {@code Tool.ts:245-246} 的 {@code agentId/agentType}
 * 定为「hook 侧子代理判别载体」）：{@code ToolUseContext.subagentName/isBuiltIn}，
 * 由 {@code SubagentExecutor} Step 20 以 {@code withSubagentIdentity} 单点写入。
 *
 * <p><b>⚠️ 覆盖边界（本类只覆盖消费端）</b>：本类夹具<b>自己造 TUC</b>
 * （{@code ToolUseContext.of(...).withSubagentIdentity(...)}），因此只证明
 * 「{@code subagentProps} 会读字段 + 隐私映射正确」，<b>不</b>证明
 * 「{@code SubagentExecutor} 真的盖章、盖的是 agentDefinition 的值」——
 * 把 Step 20 改成 {@code withSubagentIdentity(null, false)} 本类 4 条全绿（实测）。
 * <b>产出端</b>由 {@code SubagentIdentityProducerTest} 覆盖（真实 AgentDefinition 驱动的
 * 值断言 + Step 20 接线源级守卫）。两侧合起来才是完整的「产出 → 消费」守卫。
 *
 * <h2>夹具为什么必须走真 HookRegistry（真 HOOK_EXECUTOR 线程）</h2>
 * 「测试线程设 ThreadLocal → 同线程读 ThreadLocal → 断言」这类夹具对本案<b>零覆盖力</b>
 * （它天然读得到值，删掉修复代码照样绿）。本测试因此：
 * <ol>
 *   <li>用 <b>真 {@link HookRegistry#executePostToolUse} 派发</b>（= 真 HOOK_EXECUTOR 线程）；</li>
 *   <li>断言事件发射线程 ≠ 测试线程（排除同线程零覆盖夹具）。</li>
 * </ol>
 * <p>[S1-T7-2] 原还有第 3 条「断言发射线程上 ambient 归因上下文为 null」—— 随该载体整体删除而移除：
 * 该维度现由<b>编译期</b>保证（无 ambient 读取 API 可调）+ {@code AgentContextAmbientReadInventoryTest}
 * 守卫（断言载体在 {@code src/main} 彻底不存在）；运行期已无可断言对象。
 *
 * <p><b>反向实验（已实测）</b>：把 {@code subagentProps} 改回读线程环境变量（即批 2b 当时的
 * ambient 读取路线）⇒ 本类用例全红（见批 2b 报告）。
 * <p>[S1-T7-2] 该 ambient 读取入口已整体删除 ⇒ 现在这条回退会<b>直接编译失败</b>（比红灯更强的保证）。
 */
class SubagentNameExplicitCarrierTest {

    // ── [S2 · F-09/F-20 2026-09-14] 夹具 DB 姿态显式声明 ──
    //   本夹具不接 DB 回源 ⇒ 未绑定 sessionId 属「确无会话」（还原本批前的 cwd 域行为）。
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution / PathGuard
    //   fail-loud 抛（F-10 起 PathGuard 消费侧也不再吞）。见 SessionProjectRootTestSupport 类 javadoc。

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static final String SESSION_MEMORY_PATH =
        Path.of(System.getProperty("user.home"), ".claude", "session-memory", "abc.md").toString();

    /** 观测点：事件属性 + 发射线程 + 发射线程上的 ambient AgentContext。 */
    private static final class Observations {
        final List<Map<String, Object>> sessionMemoryAttrs = new CopyOnWriteArrayList<>();
        final List<String> emittingThreads = new CopyOnWriteArrayList<>();
        // [S1-T7-2] 原还有「发射线程上的 ambient AgentContext」观测项 —— 随载体整体删除而移除：
        //   该维度现由编译期（无 ambient API）+ AgentContextAmbientReadInventoryTest 守卫保证。
    }

    private static Observations wire(Telemetry telemetry) {
        Observations obs = new Observations();
        doAnswer(inv -> {
            obs.sessionMemoryAttrs.add(inv.getArgument(1));
            obs.emittingThreads.add(Thread.currentThread().getName());
            return null;
        }).when(telemetry).recordEvent(eq("tengu_session_memory_accessed"), any());
        return obs;
    }

    private static ObjectNode sessionMemoryInput() {
        ObjectNode input = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .build().createObjectNode();
        input.put("file_path", SESSION_MEMORY_PATH);
        return input;
    }

    @Test
    @DisplayName("真 HOOK_EXECUTOR 线程 + 显式 TUC（内置 agent）→ subagent_name=Explore"
        + "（CC agentContext.ts:148 内置名直出）")
    void builtInAgent_subagentName_arrivesViaExplicitTuc_onRealHookExecutorThread() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        Observations obs = wire(telemetry);
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(telemetry);
        HookRegistry registry = new HookRegistry();
        hooks.registerSessionFileAccessHooks(registry);

        // 生产者路径：SubagentExecutor Step 20 用同一份 agentDefinition 盖身份
        // （agentDefinition.agentType() + instanceof BuiltInAgentDefinition）
        ToolUseContext subagentTuc = ToolUseContext.of(null, "sess-test")
            .withSubagentIdentity("Explore", true);

        registry.executePostToolUse("Read", sessionMemoryInput(),
            ToolResult.success("tu-1", "ok"), subagentTuc);

        assertThat(obs.sessionMemoryAttrs)
            .as("subagent 上下文必须发射 tengu_session_memory_accessed 且携带 subagent_name=Explore"
                + "（CC sessionFileAccessHooks.ts:158-159）")
            .isNotEmpty();
        assertThat(obs.sessionMemoryAttrs.get(0))
            .as("内置 agent：isBuiltIn=true → subagent_name 取类型名本身（CC agentContext.ts:148）")
            .containsEntry("subagent_name", "Explore");
        assertThat(obs.emittingThreads)
            .as("夹具有效性：事件必须在非测试线程（真 HOOK_EXECUTOR）发射 —— 排除同线程零覆盖夹具")
            .allSatisfy(t -> assertThat(t).isNotEqualTo(Thread.currentThread().getName()));
    }

    @Test
    @DisplayName("真 HOOK_EXECUTOR 线程 + 显式 TUC（自定义 agent）→ subagent_name='user-defined'"
        + "（CC agentContext.ts:150 隐私映射：自定义名不泄入 analytics）")
    void customAgent_mapsToUserDefined_onRealHookExecutorThread() throws Exception {
        Telemetry telemetry = mock(Telemetry.class);
        Observations obs = wire(telemetry);
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(telemetry);
        HookRegistry registry = new HookRegistry();
        hooks.registerSessionFileAccessHooks(registry);

        // 自定义 agent：isBuiltIn=false（CC loadAgentsDir.ts:172 isBuiltInAgent = source==='built-in'）
        ToolUseContext customAgentTuc = ToolUseContext.of(null, "sess-test")
            .withSubagentIdentity("my-private-agent-name", false);

        registry.executePostToolUse("Read", sessionMemoryInput(),
            ToolResult.success("tu-2", "ok"), customAgentTuc);

        assertThat(obs.sessionMemoryAttrs).isNotEmpty();
        assertThat(obs.sessionMemoryAttrs.get(0))
            .as("自定义 agent：isBuiltIn=false → 恒映射字面量 user-defined（隐私：用户自定义名不进 analytics）")
            .containsEntry("subagent_name", "user-defined");
        assertThat(obs.sessionMemoryAttrs.get(0))
            .as("自定义名绝不得原样进事件属性")
            .doesNotContainValue("my-private-agent-name");
        assertThat(obs.emittingThreads)
            .as("夹具有效性：真 HOOK_EXECUTOR 线程（非测试线程）")
            .allSatisfy(t -> assertThat(t).isNotEqualTo(Thread.currentThread().getName()));
    }

    @Test
    @DisplayName("主线程（TUC.subagentName=null）→ 事件无 subagent_name（CC :145-146 非子代理 undefined）")
    void mainThreadTuc_noSubagentNameAttribute() {
        Telemetry telemetry = mock(Telemetry.class);
        Observations obs = wire(telemetry);
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(telemetry);
        HookRegistry registry = new HookRegistry();
        hooks.registerSessionFileAccessHooks(registry);

        // 主线程 TUC：未盖身份（subagentName=null / isBuiltIn=false）
        ToolUseContext mainTuc = ToolUseContext.of(null, "sess-test");

        registry.executePostToolUse("Read", sessionMemoryInput(),
            ToolResult.success("tu-3", "ok"), mainTuc);

        assertThat(obs.sessionMemoryAttrs)
            .as("主线程事件仍必须发射（归因属性缺失不影响事件本身）")
            .isNotEmpty();
        assertThat(obs.sessionMemoryAttrs.get(0))
            .as("非子代理上下文 → 无 subagent_name 属性（CC agentContext.ts:145-146 undefined）")
            .doesNotContainKey("subagent_name");
    }

}
