package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator-align] 子代理 spawn 的后台 shell 任务必须<b>携带归属 agentId</b> ——
 * 「task 从构造那一刻起就写死 agentId=null」这一上游断头路的钉子。
 *
 * <p><b>WHY（规则九 · 测试验证意图，而非行为）</b>：上一批（438d3986）把
 * {@link BackgroundTaskRunner} 的 11 处通知入队接上了 {@code task.agentId()}，但
 * {@code BackgroundTask} 的 11 参兼容构造器把 {@code agentId} 写死为 {@code null}，
 * 而 {@link BashTool} / {@link PowerShellTool} 的 {@code run_in_background} 路径
 * <b>全部</b>走这个 11 参构造器 ⇒ LOCAL_BASH 任务的 agentId <b>恒为 null</b>，
 * 不分主会话还是子代理。
 *
 * <p>后果（对用户可见）不是「某个字段没填」，而是
 * {@code NotificationQueue.drainForQuery} 的主线程规则（只捞 {@code agentId == null}）
 * <b>必然</b>把子代理 spawn 的后台 bash 完成通知截给主代理 —— 子代理永远等不到自己的
 * 完成通知（用户上报的现象）。本类断言「交给 runner 的 task 的 agentId == 发起调用的
 * ToolUseContext.agentId」，从而把「通知能被正确消费者领取」这一后果钉在源头。
 *
 * <p><b>CC 真源</b>（实测行号，非注释转述）：
 * <ul>
 *   <li>{@code Open-ClaudeCode/src/tools/BashTool/BashTool.tsx:656}
 *       {@code agentId: toolUseContext.agentId}（spawnShellTask 入参）</li>
 *   <li>{@code Open-ClaudeCode/src/tools/BashTool/BashTool.tsx:642}
 *       {@code const isMainThread = !toolUseContext.agentId} —— 主线程 agentId 为 undefined</li>
 *   <li>{@code Open-ClaudeCode/src/tools/PowerShellTool/PowerShellTool.tsx:465}
 *       {@code agentId: toolUseContext.agentId}（同款；:452 同款 isMainThread）</li>
 * </ul>
 *
 * <p><b>反向断言（防「改过头」）</b>：主会话 spawn（{@code ctx.agentId() == null}，
 * ctx 本身可为 null）的任务 agentId 必须<b>保持 null</b>。若实现改成「总是填一个非 null
 * 值」（回落全局 / 编造 UUID），主会话通知会变成谁都捞不到的孤儿 —— 比原缺陷更坏的静默
 * 丢失。本类两条用例互为约束：一条防「漏带」，一条防「带错」。
 *
 * <p><b>夹具说明</b>：用捕获式 stub runner（覆写 {@link BackgroundTaskRunner#spawn}）而非
 * 真跑 shell —— 本类只钉「task 被造出来时带没带 agentId」，命令是否真跑与该断言无关；
 * 这也让用例不依赖本机 shell。{@code spawn} 捕获到的 task 是工具侧<b>真的</b>构造出来的
 * 那个对象（含构造器语义），故构造器若再吞掉 agentId，本类同样转红。
 */
@DisplayName("[coordinator-align] run_in_background 的 task 必须携带 ctx.agentId()")
class ShellBackgroundTaskAgentIdSpawnTest {

    private static final String SESSION = "sess-bg-agentid-wiring";

    /** 捕获 spawn 收到的 task 的 stub runner（不跑命令、不落库）。 */
    private static class CapturingRunner extends BackgroundTaskRunner {
        final List<BackgroundTask> spawned = new ArrayList<>();

        CapturingRunner() {
            super(null, null);
        }

        @Override
        public void spawn(BackgroundTask task, String bashCommand, String createSessionId) {
            spawned.add(task);
        }
    }

    @BeforeEach
    @AfterEach
    void resetStatics() {
        SessionProjectRootTestSupport.declareNoDatabase();
        SessionCwdHolder.reset();
    }

    private static ToolUseBlock bgCall(String toolName, String id) {
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("command", "echo bg-agentid");
        input.put("description", "bg agentId wiring probe");
        input.put("run_in_background", true);
        return new ToolUseBlock(id, toolName, input);
    }

    // ── ① BashTool ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("① BashTool · 子代理 ctx（agentId 非 null）→ task.agentId == ctx.agentId()")
    void bashTool_subagentContext_taskCarriesAgentId() {
        CapturingRunner stub = new CapturingRunner();
        BashTool tool = new BashTool();
        tool.setBackgroundTaskRunner(stub);

        UUID subAgent = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
        tool.execute(bgCall("Bash", "bg-agentid-1"), ToolUseContext.of(subAgent, SESSION));

        assertThat(stub.spawned)
            .as("run_in_background 必须走到 spawn（前置条件，否则后续断言无意义）")
            .hasSize(1);
        assertThat(stub.spawned.get(0).agentId())
            .as("CC BashTool.tsx:656 agentId:toolUseContext.agentId —— 子代理 spawn 的 task 必须带归属 "
                + "agentId；带 null 会让完成通知按 drainForQuery 主线程规则被主代理截走，子代理收不到")
            .isEqualTo(subAgent);
    }

    @Test
    @DisplayName("① BashTool · 主线程 ctx（agentId == null）→ task.agentId 仍为 null（不得编造）")
    void bashTool_mainThreadContext_taskKeepsNullAgentId() {
        CapturingRunner stub = new CapturingRunner();
        BashTool tool = new BashTool();
        tool.setBackgroundTaskRunner(stub);

        tool.execute(bgCall("Bash", "bg-agentid-2"), ToolUseContext.of(null, SESSION));

        assertThat(stub.spawned).hasSize(1);
        assertThat(stub.spawned.get(0).agentId())
            .as("主线程 agentId = undefined（CC BashTool.tsx:642 isMainThread = !agentId）⇒ task.agentId "
                + "必须保持 null。填任何非 null 值都会让主会话通知变成谁都捞不到的孤儿")
            .isNull();
    }

    @Test
    @DisplayName("① BashTool · executeBackground(ctx == null) → agentId 为 null，不抛 NPE（null-safe 守卫）")
    void bashTool_nullContext_taskKeepsNullAgentId() {
        // ⚠ 夹具说明（实测）：ctx==null 经 execute() 入口<b>结构上不可达</b> —— 1 参 dispatch 兼容路径
        //   没有 sessionId（ctx==null ⇒ sessionId==null），而 executeBackground 第二行就调
        //   BackgroundTaskRunner.taskOutputPath(createSessionId, taskId)，后者对空白 sessionId
        //   fail-loud（BackgroundTaskRunner.java:611 "taskOutputDir 需要显式 sessionId"）⇒ 抛在
        //   task 构造<b>之前</b>，永远走不到 agentId 那一行。
        //   （实测输出：java.lang.IllegalArgumentException: taskOutputDir 需要显式 sessionId
        //     at BackgroundTaskRunner.taskOutputDir(BackgroundTaskRunner.java:611)
        //     at BashTool.executeBackground(BashTool.java:2728)）
        //   故这里直调私有 executeBackground 并显式给一个合法 sessionId，<b>只为</b>钉住新入参的
        //   null 安全性（⛔ 这不是生产路径验证）。实现若把守卫写成裸 ctx.agentId() → 本用例 NPE 转红。
        CapturingRunner stub = new CapturingRunner();
        BashTool tool = new BashTool();
        tool.setBackgroundTaskRunner(stub);

        ReflectionTestUtils.invokeMethod(tool, "executeBackground",
            "echo bg-agentid", "bg-agentid-3", SESSION, null);

        assertThat(stub.spawned).hasSize(1);
        assertThat(stub.spawned.get(0).agentId())
            .as("ctx 为 null（无 ToolUseContext 上下文）⇒ 无归属可传，保持 null 而非抛 NPE")
            .isNull();
    }

    // ── ② PowerShellTool ────────────────────────────────────────────────────

    @Test
    @DisplayName("② PowerShellTool · 子代理 ctx → task.agentId == ctx.agentId()")
    void powerShellTool_subagentContext_taskCarriesAgentId() {
        CapturingRunner stub = new CapturingRunner();
        PowerShellTool tool = new PowerShellTool();
        ReflectionTestUtils.setField(tool, "backgroundTaskRunner", stub);

        UUID subAgent = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
        tool.execute(bgCall("PowerShell", "bg-agentid-4"), ToolUseContext.of(subAgent, SESSION));

        assertThat(stub.spawned)
            .as("PowerShell 的 run_in_background 早返回必须先于 pwsh pre-flight（无 pwsh 也要到达）")
            .hasSize(1);
        assertThat(stub.spawned.get(0).agentId())
            .as("CC PowerShellTool.tsx:465 agentId:toolUseContext.agentId —— 与 BashTool 同款，"
                + "PS 走的也是同一 LocalShellTask(local_bash) 通道")
            .isEqualTo(subAgent);
    }

    @Test
    @DisplayName("② PowerShellTool · 主线程 ctx → task.agentId 仍为 null（不得编造）")
    void powerShellTool_mainThreadContext_taskKeepsNullAgentId() {
        CapturingRunner stub = new CapturingRunner();
        PowerShellTool tool = new PowerShellTool();
        ReflectionTestUtils.setField(tool, "backgroundTaskRunner", stub);

        tool.execute(bgCall("PowerShell", "bg-agentid-5"), ToolUseContext.of(null, SESSION));

        assertThat(stub.spawned).hasSize(1);
        assertThat(stub.spawned.get(0).agentId()).isNull();
    }
}
