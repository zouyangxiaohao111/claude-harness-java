package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.impl.TaskCreateTool;
import com.nexusai.application.agent.tool.impl.TaskGetTool;
import com.nexusai.application.agent.tool.impl.TaskListTool;
import com.nexusai.application.agent.tool.impl.TaskUpdateTool;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * S11 默认 V1/V2 装配断言 · 对齐 CC tasks.ts:133-139 isTodoV2Enabled() + 决策 #65
 *
 * <p><b>WHY (意图验证)</b>: CC {@code isTodoV2Enabled()} = env truthy 强制 true（tasks.ts:135-136），
 * 否则 {@code !getIsNonInteractiveSession()} = {@code STATE.isInteractive}（tasks.ts:138 /
 * state.ts:1057-1059）。</p>
 *
 * <p><b>决策 #65（2026-08-23 用户拍板）</b>: Java Web 后端会话（有前端用户）应视为交互 →
 * {@code isInteractive()} 在 Web 请求路径（RequestContext 有 reqId）默认 true → todoV2 默认开；
 * cron/后台（仅 sessionId，无 reqId）→ 非交互 → V1 TodoWrite。故：
 * <ul>
 *   <li><b>无请求上下文 / cron/后台</b>（RequestContext 仅 sessionId 或无 MDC）→ V1：
 *       {@code isTodoV2Enabled()==false} → TodoWrite 可注册（isEnabled=true）、Task 工具族不可注册。</li>
 *   <li><b>Web 请求上下文</b>（RequestContext 设 sessionId + reqId）→ V2：
 *       {@code isTodoV2Enabled()==true} → TodoWrite 不可注册、Task 工具族可注册。</li>
 *   <li>显式开启（enableTaskV2 / nexusai.tasks.enabled / nexusai.interactive=true）→ V2；
 *       显式 nexusai.interactive=false → V1。</li>
 * </ul>
 *
 * <p>纯单元测试（无 Spring 上下文），经 isEnabled 链断言装配语义
 * （ToolRegistrationConfig.todoTaskTools 的 V1/V2 分支即 !isTodoV2Enabled() 判定）。
 */
class TaskSystemConfigDefaultV1Test {

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
        RequestContext.clear();
    }

    @Test
    @DisplayName("无请求上下文（无 sysprop 注入）isTodoV2Enabled()==false → V1")
    void noContext_noSysprop_isTodoV2Enabled_false() {
        TaskSystemConfig.clearForTest();
        RequestContext.clear();
        assertThat(TaskSystemConfig.isTodoV2Enabled()).isFalse();
    }

    @Test
    @DisplayName("无请求上下文（无 sysprop 注入）isInteractive()==false（对齐 CC STATE.isInteractive 非交互默认）")
    void noContext_noSysprop_isInteractive_false() {
        TaskSystemConfig.clearForTest();
        RequestContext.clear();
        assertThat(TaskSystemConfig.isInteractive()).isFalse();
    }

    @Test
    @DisplayName("cron/后台上下文（仅 sessionId 无 reqId）→ 非交互 → V1 TodoWrite（决策#65 区分后台/cron）")
    void cronContext_onlySessionId_nonInteractive_v1() {
        TaskSystemConfig.clearForTest();
        RequestContext.setSession("sess-cron");

        assertThat(TaskSystemConfig.isInteractive()).isFalse();
        assertThat(TaskSystemConfig.isTodoV2Enabled()).isFalse();
        assertThat(new TodoWriteTool().isEnabled()).isTrue();
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Web 请求上下文（sessionId + reqId）→ 交互 → todoV2 默认开 → V2（决策#65 Web 会话默认 V2）")
    void webRequestContext_interactive_v2() {
        TaskSystemConfig.clearForTest();
        RequestContext.set("sess-web", "msg-1");

        assertThat(TaskSystemConfig.isInteractive()).isTrue();
        assertThat(TaskSystemConfig.isTodoV2Enabled()).isTrue();
        assertThat(new TodoWriteTool().isEnabled()).isFalse();
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isTrue();
        assertThat(new TaskGetTool(mock(TaskService.class)).isEnabled()).isTrue();
        assertThat(new TaskListTool(mock(TaskService.class)).isEnabled()).isTrue();
        assertThat(new TaskUpdateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("无请求上下文默认 V1 装配：TodoWrite 可注册，Task 工具族不可注册")
    void noContext_v1_todoWriteRegistered_taskToolsNotRegistered() {
        TaskSystemConfig.clearForTest();
        RequestContext.clear();

        // V1：TodoWrite 注册（ToolRegistrationConfig.todoTaskTools 分支）
        assertThat(new TodoWriteTool().isEnabled()).isTrue();

        // V1：Task 工具族不注册（AbstractTaskTool.isEnabled → isTodoV2Enabled()==false）
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isFalse();
        assertThat(new TaskGetTool(mock(TaskService.class)).isEnabled()).isFalse();
        assertThat(new TaskListTool(mock(TaskService.class)).isEnabled()).isFalse();
        assertThat(new TaskUpdateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("enableTaskV2 显式开启 → V2：Task 工具族可注册，TodoWrite 不注册")
    void enableTaskV2_forcesV2_todoWriteNotRegistered() {
        TaskSystemConfig.clearForTest();
        TaskSystemConfig.enableTaskV2();

        assertThat(TaskSystemConfig.isTodoV2Enabled()).isTrue();
        assertThat(new TodoWriteTool().isEnabled()).isFalse();
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isTrue();
        assertThat(new TaskGetTool(mock(TaskService.class)).isEnabled()).isTrue();
        assertThat(new TaskListTool(mock(TaskService.class)).isEnabled()).isTrue();
        assertThat(new TaskUpdateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("nexusai.tasks.enabled=true sysprop 显式开启 → V2（对齐 CC env 强制分支）")
    void enableTasksSysprop_true_forcesV2() {
        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.tasks.enabled", "true");

        assertThat(TaskSystemConfig.isTodoV2Enabled()).isTrue();
    }

    @Test
    @DisplayName("nexusai.interactive=true sysprop 显式开启 → V2（对齐 CC STATE.isInteractive 语义）")
    void interactiveSysprop_true_enablesV2() {
        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.interactive", "true");

        assertThat(TaskSystemConfig.isTodoV2Enabled()).isTrue();
        assertThat(new TodoWriteTool().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("nexusai.interactive=false sysprop 显式关闭 → V1")
    void interactiveSysprop_false_disablesV2() {
        TaskSystemConfig.clearForTest();
        System.setProperty("nexusai.interactive", "false");

        assertThat(TaskSystemConfig.isTodoV2Enabled()).isFalse();
        assertThat(new TodoWriteTool().isEnabled()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [agent-swarms-setting V42] settings.agentSwarmsEnabled 静态覆盖标志判定链
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("agentSwarmsSettingsOverride(true) 单独放行 → isAgentSwarmsEnabled()==true（前端开关为额外 opt-in 源）")
    void settingsOverride_true_alone_enables() {
        // WHY（规则九）：前端「环境配置」Agent Swarms 开关是外部用户的额外 opt-in 源——
        //   无 env/--agent-teams、非 ant 时，settings.agentSwarmsEnabled=true 必须单独放行
        //   （否则设置页开开关无效）。
        TaskSystemConfig.clearForTest();
        TaskSystemConfig.setAgentSwarmsSettingsOverride(true);

        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).isTrue();
    }

    @Test
    @DisplayName("agentSwarmsSettingsOverride(false) 不额外放行 → false（仍需 env/flag）")
    void settingsOverride_false_doesNotEnable() {
        // WHY：settings=false 不额外放行——无 env/flag opt-in 时必须 false（不破坏 CC 默认关闭）。
        TaskSystemConfig.clearForTest();
        TaskSystemConfig.setAgentSwarmsSettingsOverride(false);

        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).isFalse();
    }

    @Test
    @DisplayName("agentSwarmsSettingsOverride(null/clearForTest) 默认不覆盖 → false（维持 CC 原判定链）")
    void settingsOverride_null_defaultNoEffect() {
        // WHY：null=未配置不覆盖——设置页未配置开关时维持 CC 原 opt-in 判定链（默认关闭），
        //   clearForTest 必须把静态标志归 null 防串状态。
        TaskSystemConfig.clearForTest();

        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).isFalse();
    }

    @Test
    @DisplayName("killswitch 仍末位优先：settings=true + killswitch=true → false")
    void killswitch_stillPriority_overridesSetting() {
        // WHY（规则九）：对齐 CC agentSwarmsEnabled.ts:39-41——killswitch 约束外部用户，即使
        //   settings 已放行，killswitch 关闭仍必须优先返回 false（改判定链不得削弱 killswitch）。
        TaskSystemConfig.clearForTest();
        TaskSystemConfig.setAgentSwarmsSettingsOverride(true);
        System.setProperty("nexusai.swarms.killswitch", "true");

        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [agent-swarms-global] settings 实时 DB 读源判定链
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("source 实时读 DB：db 改 false（模拟换会话未调 get/update）→ isAgentSwarmsEnabled() 立即 false")
    void source_liveRead_reflectsDbChangeWithoutGetUpdate() {
        // WHY（规则九 · 核心回归）：agentSwarmsSettingsOverride 是进程内存静态标志，仅 get/update
        //   触发刷新；换会话未调 get/update 时静态标志仍 null → 读不到全局 settings → false。
        //   安装实时读源后必须每次调用实时读 DB，另一会话改全局开关后立即可见（全局配置所有会话生效）。
        //   变异点：仍依赖静态标志 → 此测试 fail（db=false 后仍 true）。
        TaskSystemConfig.clearForTest();
        AtomicBoolean db = new AtomicBoolean(true);
        TaskSystemConfig.installAgentSwarmsSettingsSource(db::get);

        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).as("source=true → 放行").isTrue();
        db.set(false);  // 模拟另一会话关闭全局开关（未调 get/update）
        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).as("db 改 false（换会话）→ 实时读源立即反映 → false").isFalse();
    }

    @Test
    @DisplayName("source 权威优先于 stale override：source=false + override=true → false")
    void source_winsOverStaleOverride() {
        // WHY（规则九）：若 OR 合并，get/update 残留的 override 镜像（跨会话过期）会在 DB 已改 false
        //   时仍放行——破坏「全局关 = 所有会话关」。source 安装后必须为权威（实时 DB 恒为真值源）。
        TaskSystemConfig.clearForTest();
        TaskSystemConfig.installAgentSwarmsSettingsSource(() -> false);
        TaskSystemConfig.setAgentSwarmsSettingsOverride(true);

        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).as("source 权威 → 不被 stale override=true 覆盖 → false").isFalse();
    }

    @Test
    @DisplayName("installSource(null) 不覆盖既有 source（null-guard，对齐 installTierSources）")
    void installSource_null_keepsExisting() {
        // WHY（规则九）：对齐 ModelNameResolver.installTierSources null-guard——null 注入不得清掉
        //   既有 source（生产启动装配与测试互不干扰）。
        TaskSystemConfig.clearForTest();
        TaskSystemConfig.installAgentSwarmsSettingsSource(() -> true);
        TaskSystemConfig.installAgentSwarmsSettingsSource(null);

        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).as("null 不覆盖既有 source → 仍 true").isTrue();
    }

    @Test
    @DisplayName("clearForTest 重置实时读源 → 回落默认 false（防测试间串状态）")
    void clearForTest_resetsSource() {
        // WHY：clearForTest 必须同时清实时读源与 override，否则后序测试误读上一测试安装的 DB 值串状态。
        TaskSystemConfig.clearForTest();
        TaskSystemConfig.installAgentSwarmsSettingsSource(() -> true);
        TaskSystemConfig.clearForTest();

        assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).isFalse();
    }
}
