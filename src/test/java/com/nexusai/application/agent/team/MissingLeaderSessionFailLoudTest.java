package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.common.SessionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * <b>[fail-loud] teammate 取不到 Leader 真实会话键 ⇒ 直接报错（⛔ 不回落任何伪造会话键）</b>。
 *
 * <p><b>WHY（规则九 · 本类守护的意图）</b>：改前 {@code SpawnInProcess.spawnInProcessTeammate}
 * 在 {@code SpawnContext} 未携带 Leader 会话时回落到 {@code TaskService.getTaskListId(null, null)}
 * —— 它的最终回退是<b>进程级共享 UUID</b>（永不返回 null/blank，见
 * {@link TaskService#isFallbackProcessSessionId}），中间还可能命中 {@code nexusai.team.name}
 * （<b>team 名</b>）。于是「真 Leader 会话取不到」（config.json 无 leadSessionId / 反查失败 /
 * SubagentTool 侧 {@code ctx.sessionId()} 为 null）被<b>静默替换成一个看起来像会话的伪造键</b>：
 * <ul>
 *   <li>{@code TeammateIdentity.parentSessionId} / {@code loop.setTaskListId} / teammate 执行 TUC
 *       的 sessionId 全挂幻影会话；</li>
 *   <li>下游 CwdResolution / SessionStorage 按「未知会话」在<b>链路深处</b> fail-loud 抛
 *       （报错点离根因很远，且**不含**「Leader 会话缺失」这一上下文）；</li>
 *   <li>权限授权 / transcript / file-history 锚到不存在的会话。</li>
 * </ul>
 * 用户裁定（2026-09-22）：「将来若出现『携带 teammateIdentity 但没走 T1 父 TUC 通道』的新路径
 * <b>直接报错</b>」 + 铁律「不许静默伪造一个看起来合法的会话键」。
 *
 * <p><b>判别力（正反双向，⛔ 非恒真）</b>：
 * <ul>
 *   <li>正向：四条判据（null/空白、NO_SESSION 哨兵、进程兜底 UUID、team 名）各自必须抛
 *       {@link MissingLeaderSessionException}，且文案含<b>四要素</b>（环节/期望/实际/如何修）；</li>
 *   <li>反向对照（{@link #realLeaderSession_doesNotThrow_andInheritsIt}）：<b>真实</b>会话键必须
 *       <b>不抛</b>并真的被继承为 teammate 的 Leader 归属 ⇒ 排除「把守卫写成恒抛」的假绿。</li>
 *   <li><b>反向实验配方（本类可证伪，实测已跑 RED）</b>：把 {@code requireLeaderSessionId} 的
 *       四条判据删成 {@code return context != null ? context.parentSessionId() : TaskService.getTaskListId(null, null)}
 *       （= 改回伪造回退）⇒ 四个 fail-loud 用例全红（异常不再抛、且注册表里出现幻影会话的 teammate）。</li>
 * </ul>
 *
 * <p><b>边界（⛔ 本类不守护的东西）</b>：「确无会话」的合法降级路径（入站 MCP 子进程 / 无会话 plan
 * provider / workflow worker / standalone fork 子代理）仍走 {@link SessionKeys#NO_SESSION} 与
 * {@code CwdResolution} 命名出口 —— 那些路径<b>不该</b>被改成抛；本类只覆盖 teammate 启动这一条
 * 结构上必然属于某个 Leader 会话的链路。
 */
@DisplayName("fail-loud · teammate 取不到 Leader 会话即报错（⛔ 不回落伪造键）")
class MissingLeaderSessionFailLoudTest {

    private static final String TEAM = "team-t1";
    private static final String AGENT_ID = "alice@" + TEAM;

    @TempDir
    Path tempDir;

    private SpawnInProcess spawner;
    private final List<String> spawnedTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
    }

    @AfterEach
    void tearDown() {
        for (String taskId : spawnedTaskIds) {
            spawner.registry().kill(taskId);
        }
        System.clearProperty("nexusai.task.config-dir");
        TaskSystemConfig.clearForTest();
    }

    /** spawn 配置 · cwd 显式给定（本类只考察「Leader 会话键」这一条判据，不掺 cwd 解析）。 */
    private SpawnInProcess.InProcessSpawnConfig config() {
        return new SpawnInProcess.InProcessSpawnConfig(
            "alice", TEAM, "do X", null, false, null, "general-purpose", tempDir.toString());
    }

    private Throwable spawnWith(String parentSessionId) {
        return catchThrowable(() -> spawner.spawnInProcessTeammate(
            config(), new SpawnInProcess.SpawnContext(parentSessionId, "tu-test")));
    }

    /** 四条判据共用的断言：异常类型 + 四要素文案 + ⛔ 未走到任何副作用（不留半成品 teammate）。 */
    private MissingLeaderSessionException assertFailsLoud(Throwable thrown, String actualExpectation) {
        assertThat(thrown)
            .as("取不到真实 Leader 会话键必须 fail-loud 抛（⛔ 不得回落进程 UUID / team 名 / 哨兵）")
            .isInstanceOf(MissingLeaderSessionException.class);
        MissingLeaderSessionException ex = (MissingLeaderSessionException) thrown;
        assertThat(ex.link())
            .as("文案必须写清**哪个环节**").contains("SpawnInProcess.spawnInProcessTeammate");
        assertThat(ex.expected())
            .as("文案必须写清**期望是什么**（真实 Lead 会话键 + 显式载体）").contains("真实 Lead 会话键");
        assertThat(ex.actual())
            .as("文案必须写清**实际拿到什么**").contains(actualExpectation);
        assertThat(ex.fix())
            .as("文案必须写清**如何修**").contains("ToolUseContext.sessionId()").contains("config.json");
        assertThat(ex.getMessage())
            .as("整段文案须自带四要素标签，便于排障者一眼定位").contains("环节=")
            .contains("期望=").contains("实际=").contains("如何修=");
        assertThat(spawner.registry().findByAgentId(AGENT_ID))
            .as("异常必须在注册/启动**之前**抛出（⛔ 不留半成品 teammate）").isEmpty();
        return ex;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 正向：四条伪造形态各自必须抛
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("判据1a：SpawnContext 为 null（config 反查失败 / 调用方无会话）⇒ 抛")
    void nullSpawnContext_failsLoud() {
        // WHY：这是改前「回落进程 UUID」的主要触发形态（SpawnContext 未携带 Leader 会话）。
        assertFailsLoud(spawnWith(null), "null/空白");
    }

    @Test
    @DisplayName("判据1b：parentSessionId 为空白串 ⇒ 抛（空白同样是「取不到」）")
    void blankParentSessionId_failsLoud() {
        assertFailsLoud(spawnWith("   "), "null/空白");
        assertFailsLoud(spawnWith(""), "null/空白");
    }

    @Test
    @DisplayName("判据2：SessionKeys.NO_SESSION 哨兵被当会话 ⇒ 抛（哨兵 ≠ 会话键）")
    void noSessionSentinel_failsLoud() {
        // WHY：哨兵是「确无会话」的**合法降级标记**，但它服务的是 MCP/fork 等路径；teammate 这条
        //   链结构上必属于某个 Leader 会话 ⇒ 拿哨兵当 sessionId = 同一类幻影会话桶（形态更显式）。
        assertFailsLoud(spawnWith(SessionKeys.NO_SESSION), "no-session");
    }

    @Test
    @DisplayName("⭐判据3：改前的**回落值本身**（进程级兜底 UUID）被当会话 ⇒ 抛（入口判 null 拦不住）")
    void legacyProcessFallbackUuid_failsLoud() {
        // WHY（本用例是本任务的核心证据）：改前回落的是 TaskService.getTaskListId(null, null)，
        //   它**非 null、非空白、形态上就是一个合格 UUID** ⇒ 任何「只在入口判 null」的守卫都拦不住
        //   （这正是必须新增 isFallbackProcessSessionId 按值识别的原因）。本用例先把改前那个值原样
        //   复现出来，再断言它现在被拒绝。
        String legacyFallback = TaskService.getTaskListId(null, null);
        Assumptions.assumeTrue(TaskService.isFallbackProcessSessionId(legacyFallback),
            "环境设置了 CLAUDE_CODE_TASK_LIST_ID / nexusai.taskListId 等前置 sysprop，"
                + "本次拿不到进程级兜底值，跳过（该前提由 TaskListIdExplicitResolutionTest 覆盖）");

        assertThat(legacyFallback).as("改前回落值非 null/非空白").isNotBlank();
        assertThat(UUID.fromString(legacyFallback))
            .as("⭐ 形态上就是合格 UUID ⇒ 看起来像会话（仅判 null 的守卫拦不住）").isNotNull();

        assertFailsLoud(spawnWith(legacyFallback), "进程级兜底 UUID");
    }

    @Test
    @DisplayName("判据4：team 名被当会话 ⇒ 抛（TaskService 优先级 3 的 nexusai.team.name 返回值）")
    void teamNameAsSession_failsLoud() {
        // WHY：TaskService.getTaskListId 优先级 3 返回 nexusai.team.name = **team 名**，改前同样可能
        //   被当作 teammate 的 sessionId 喂下去（brief 明列「会把进程 UUID 或 team 名当 sessionId」）。
        assertFailsLoud(spawnWith(TEAM), "team 名");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 反向对照：真实会话键必须放行，且真的成为 Leader 归属（判据非恒真）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("反向对照：真实 Leader 会话键 ⇒ 不抛、spawn 成功、且被继承为 teammate 归属")
    void realLeaderSession_doesNotThrow_andInheritsIt() {
        // WHY：若把守卫写成「恒抛」或「无条件拒绝」，本用例红 ⇒ 上面四个 fail-loud 断言才有鉴别力。
        //   继承断言落在 leaderParentTuc（T1 的父 TUC 通道载体）：teammate 执行 TUC 就是靠它继承
        //   sessionId/effectiveCwd（详见 TeammateLeaderSessionInheritanceTest 的链路级验证）。
        String realSession = "sess-leader-t1-real";
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            config(), new SpawnInProcess.SpawnContext(realSession, "tu-real"));
        spawnedTaskIds.add(out.taskId());

        assertThat(out.success()).as("真实会话键必须放行（⛔ 守卫不得恒抛）").isTrue();
        AutonomousAgentLoop loop = spawner.registry().get(out.taskId()).orElseThrow();
        assertThat(loop.leaderParentTuc())
            .as("[T1] Leader 归属父 TUC 必须无条件构造（原「无会话 → null」分支已随 fail-loud 删除）")
            .isNotNull();
        assertThat(loop.leaderParentTuc().sessionId())
            .as("teammate 继承的就是传进来的真实会话键").isEqualTo(realSession);
        assertThat(loop.leaderParentTuc().effectiveCwd())
            .as("effectiveCwd 必须钉在 spawn 配置给出的 Leader cwd 上").isEqualTo(tempDir.toAbsolutePath());
    }
}
