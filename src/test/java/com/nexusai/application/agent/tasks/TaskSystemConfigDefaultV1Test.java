package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.impl.TaskCreateTool;
import com.nexusai.application.agent.tool.impl.TaskGetTool;
import com.nexusai.application.agent.tool.impl.TaskListTool;
import com.nexusai.application.agent.tool.impl.TaskUpdateTool;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * S11 V1/V2 装配断言 · 对齐 CC tasks.ts:133-139 isTodoV2Enabled()
 *
 * <p><b>WHY (意图验证)</b>: CC {@code isTodoV2Enabled()} = env truthy 强制 true（tasks.ts:135-136），
 * 否则 {@code !getIsNonInteractiveSession()} = {@code STATE.isInteractive}（tasks.ts:138 /
 * state.ts:1057-1059）。</p>
 *
 * <p><b>[批 3c · 2026-09-13 推翻决策 #65]</b> 本类原锁「无请求上下文 / cron/后台 → V1 TodoWrite」。
 * 该判据读<b>裸 MDC 会话槽</b>（{@code requestId()!=null}，该工具类批 3c 已整体删除）（MDC 第三态：
 * 可能读到上一请求残留的、别的会话的 id；且消费点 {@code Tool.isEnabled()} 无会话参数、删后无显式
 * 来源可传）⇒ 判定提升为<b>进程级</b>（对齐 CC {@code STATE.isInteractive}），无 sysprop 时默认
 * <b>交互（V2）</b>。故本类的 V1 默认断言<b>逐条反转</b>为 V2 默认断言（不是删 —— 旧断言会阻止
 * 任何人把判定改回 CC 的进程级语义，正是它给那个偏离行为背了书）。</p>
 *
 * <p>现语义（逐条）：
 * <ul>
 *   <li><b>无 sysprop 注入</b>（无论有无会话 / 有无 MDC）→ 进程级默认交互 → V2：
 *       {@code isTodoV2Enabled()==true} → TodoWrite 不可注册、Task 工具族可注册。</li>
 *   <li>显式 {@code nexusai.tasks.enabled=true} / {@code nexusai.interactive=true} → V2（不变）。</li>
 *   <li>显式 {@code nexusai.interactive=false} → V1（<b>唯一降级通道</b>）。</li>
 * </ul>
 *
 * <p>纯单元测试（无 Spring 上下文），经 isEnabled 链断言装配语义
 * （ToolRegistrationConfig.todoTaskTools 的 V1/V2 分支即 !isTodoV2Enabled() 判定）。
 * 进程级「不随线程变」的机制级守卫见 {@link TaskSystemConfigProcessLevelTest}。
 */
class TaskSystemConfigDefaultV1Test {

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    @Test
    @DisplayName("[批 3c 反转] 无 sysprop 注入 → 进程级默认交互 → isTodoV2Enabled()==true → V2")
    void noContext_noSysprop_isTodoV2Enabled_true() {
        TaskSystemConfig.clearForTest();
        assertThat(TaskSystemConfig.isTodoV2Enabled())
            .as("无 sysprop → 进程级默认交互（对齐 CC STATE.isInteractive），不再按 MDC 有无判 V1")
            .isTrue();
    }

    @Test
    @DisplayName("[批 3c 反转] 无 sysprop 注入 → isInteractive()==true（Web UI 后端=交互进程）")
    void noContext_noSysprop_isInteractive_true() {
        TaskSystemConfig.clearForTest();
        assertThat(TaskSystemConfig.isInteractive()).isTrue();
    }

    @Test
    @DisplayName("[批 3c 反转] cron/后台轮次 → 与 Web 轮次同为 V2（进程级判定，不再按 reqId 降级 V1）")
    void cronTurn_isAlsoV2_processLevel() {
        TaskSystemConfig.clearForTest();

        assertThat(TaskSystemConfig.isInteractive())
            .as("进程级判定不区分 Web/cron —— 对齐 CC（同一进程 STATE.isInteractive 恒同值）").isTrue();
        assertThat(TaskSystemConfig.isTodoV2Enabled()).isTrue();
        assertThat(new TodoWriteTool().isEnabled())
            .as("V2 模式下 TodoWrite 不注册（cron 轮次亦同）").isFalse();
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("[批 3c 反转] 无 sysprop 默认 V2 装配：TodoWrite 不可注册，Task 工具族全部可注册")
    void noContext_v2_todoWriteNotRegistered_taskToolsRegistered() {
        TaskSystemConfig.clearForTest();

        // V2：TodoWrite 不注册（ToolRegistrationConfig.todoTaskTools 的 !isTodoV2Enabled() 分支不命中）
        assertThat(new TodoWriteTool().isEnabled()).isFalse();

        // V2：Task 工具族可注册（AbstractTaskTool.isEnabled → isTodoV2Enabled()==true）
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isTrue();
        assertThat(new TaskGetTool(mock(TaskService.class)).isEnabled()).isTrue();
        assertThat(new TaskListTool(mock(TaskService.class)).isEnabled()).isTrue();
        assertThat(new TaskUpdateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled()).isTrue();
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
