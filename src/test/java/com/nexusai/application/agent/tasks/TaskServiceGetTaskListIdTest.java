package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.team.TeammateIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * teammate 身份定向测试 · 对齐 CC tasks.ts:199-210 getTaskListId() 优先级链
 *
 * <p><b>WHY（意图验证）</b>: CC 的 getTaskListId() 优先级第 2 位是 in-process teammate
 * 的 leader teamName（tasks.ts:205-208: {@code const teammateCtx = getTeammateContext();
 * if (teammateCtx) return teammateCtx.teamName}），使 teammate 与 leader 共享同一任务列表
 * （teammateContext.ts:47-49 getTeammateContext() 取 AsyncLocalStorage store）。该优先级
 * <b>高于</b> CC 优先级 3 的 getTeamName()（Java 近似 sysprop nexusai.team.name）。
 *
 * <p><b>[S1-T6] 载体修订</b>：本仓身份不再经 ThreadLocal 读取（原「读 ThreadLocal →
 * 池线程恒 null → 只能靠回放」），改为**显式形参</b> {@code getTaskListId(sessionId, identity)}；
 * 生产来源 = {@code ToolUseContext.teammateIdentity()}（工具侧）/
 * {@code InProcessTeammateTaskState.identity()}（teammate loop 侧）。
 *
 * <p>本测试验证 3 个意图：
 * <ol>
 *   <li><b>无身份回退链无回归</b>：identity=null 时，解析链保持改动前行为
 *       （nexusai.taskListId → nexusai.team.name → leaderTeamName → nexusai.sessionId →
 *       显式会话形参 → 进程级 UUID），回归护栏。U-3 弃硬编码 'tasklist'。</li>
 *   <li><b>identity 优先级第 2 且有鉴别力</b>：identity 非 null 时返回 identity.teamName()，
 *       且高于 nexusai.team.name；同一 sysprop 环境下把 identity 置 null 结果必须改变
 *       （证明判据由显式载体驱动，而非硬编码 null / 硬编码 sysprop）。</li>
 *   <li><b>跨线程可用</b>：在**另一线程**上以显式 identity 调用仍返回 identity.teamName()
 *       —— 证明该值不依赖任何 thread-local（原 ThreadLocal 载体在派生线程读不到，
 *       正是本批要消灭的形态）。</li>
 * </ol>
 *
 * <p>参考 CC 源码（grep 自验，非注释）：
 * <ul>
 *   <li>{@code const teammateCtx = getTeammateContext(); if (teammateCtx) return teammateCtx.teamName}
 *       — tasks.ts:205-208</li>
 *   <li>{@code export function getTeammateContext() { return teammateContextStorage.getStore() }}
 *       — teammateContext.ts:47-49</li>
 *   <li>{@code return getTeamName() || leaderTeamName || getSessionId()} — tasks.ts:209</li>
 *   <li>{@code export function getTeamName() { const inProcessCtx = getTeammateContext(); ... }}
 *       — teammate.ts:111-119（Java 用 sysprop nexusai.team.name 近似）</li>
 * </ul>
 */
class TaskServiceGetTaskListIdTest {

    /** [批 3c] 显式会话（原优先级 6 读裸 MDC 会话槽，该槽已删 ⇒ 改为显式形参传入）。 */
    private static final String SESSION = "sess-explicit-1";

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetSysprops() {
        System.clearProperty("nexusai.taskListId");
        System.clearProperty("nexusai.team.name");
        System.clearProperty("nexusai.sessionId");
        // [P0-2 · B1] 按本用例 sessionId 清（0 参重载 = clear(null) ⇒ 立即 return，清不掉任何键）。
        //   本类 :110 显式 setLeaderTeamName("leader-1", SESSION) ⇒ 键必须在这里被清掉，
        //   否则 JVM 级 static Map 会把 "leader-1" 泄漏给同类后续用例与同 JVM 的其它测试类。
        TaskService.clearLeaderTeamName(SESSION);
    }

    private TaskService newService() {
        // 显式 configHome 构造器：不依赖 Spring 上下文（getTaskListId 为 static，仅验证解析链）
        return new TaskService(tempDir);
    }

    /** [S1-T6] teammate 身份 = 纯数据 record（原 ThreadLocal 载体类已删）。 */
    private static TeammateIdentity newIdentity(String teamName) {
        return new TeammateIdentity(
            "researcher@my-team", "researcher", teamName, "#ff0000", false, "parent-session-id");
    }

    @Test
    @DisplayName("无身份回退链无回归：taskListId → teamName → leaderTeamName → sessionId → 显式会话形参 → 进程级 UUID")
    void fallbackChainWithoutTeammateIdentity() {
        TaskService service = newService();

        // 环境显式 CLAUDE_CODE_TASK_LIST_ID 会掩盖 sysprop 层，跳过默认回退断言（环境偶发，非被测逻辑）
        String envTaskListId = System.getenv("CLAUDE_CODE_TASK_LIST_ID");
        Assumptions.assumeTrue(envTaskListId == null || envTaskListId.isBlank(),
            "测试环境设置了 CLAUDE_CODE_TASK_LIST_ID，跳过默认回退断言");

        // 全空 → 回退进程级稳定会话 UUID（U-3 弃硬编码 'tasklist'；CC getTaskListId 永不返回 'tasklist'，
        // 最终回退 getSessionId()=STATE.sessionId 会话 UUID，state.ts:331/431-432；Java 静态方法无会话
        // 上下文时以进程级稳定 UUID 兜底，保证非 null——DC-4 后 sanitizePathComponent(null) 会 NPE）。
        // [S1-T11] 无参重载已删除 ⇒ 此处的「无会话上下文」改为显式传 null
        //   （该分支前提本就是「调用方确实无会话」，与旧无参重载逐字等价）。
        String fallback = service.getTaskListId(null, null);
        assertThat(fallback).isNotBlank().isNotEqualTo("tasklist");
        assertThat(service.getTaskListId(null, null)).as("进程级会话 UUID 应进程内稳定").isEqualTo(fallback);

        // 显式会话形参（优先级 6，getSessionId() 当前会话等价物）> 进程级 UUID
        // [批 3c] 该级原读裸 MDC 的会话槽（已删除）⇒ 现按形参显式传入本测试的会话变量。
        assertThat(service.getTaskListId(SESSION, null)).isEqualTo(SESSION);

        // sysprop nexusai.sessionId（优先级 5）> 显式会话形参
        System.setProperty("nexusai.sessionId", "sess-1");
        assertThat(service.getTaskListId(SESSION, null)).isEqualTo("sess-1");

        // 会话级 leaderTeamName（优先级 4）> sessionId
        // [批 3c] leaderTeamName 由 ThreadLocal 改为**按会话分桶**（写读均须显式会话）。
        TaskService.setLeaderTeamName("leader-1", SESSION);
        assertThat(service.getTaskListId(SESSION, null)).isEqualTo("leader-1");

        // sysprop nexusai.team.name（优先级 3）> leaderTeamName
        System.setProperty("nexusai.team.name", "team-1");
        assertThat(service.getTaskListId(SESSION, null)).isEqualTo("team-1");

        // sysprop nexusai.taskListId（优先级 1）> 一切
        System.setProperty("nexusai.taskListId", "explicit-1");
        assertThat(service.getTaskListId(SESSION, null)).isEqualTo("explicit-1");
    }

    @Test
    @DisplayName("teammate 身份优先级 2：显式 identity 非 null → 返回 identity.teamName，高于 nexusai.team.name")
    void teammateIdentityTakesPriorityOverTeamNameSysprop() {
        TaskService service = newService();
        System.setProperty("nexusai.team.name", "sysprop-team-1");

        // CC tasks.ts:206-207：ctx 存在即返回 teamName（teammate 与 leader 共享任务列表）
        assertThat(service.getTaskListId(null, newIdentity("teammate-leader-team")))
            .as("优先级2 显式 teammate identity.teamName 应高于 sysprop nexusai.team.name")
            .isEqualTo("teammate-leader-team");

        // ⭐ 鉴别力（防「硬编码 null / 硬编码 sysprop」同行为）：同一环境把 identity 置 null，
        //   结果必须**改变**回落到 sysprop 级
        assertThat(service.getTaskListId(null, null))
            .as("identity 置 null → 输出必须改变（回落 sysprop team name）")
            .isEqualTo("sysprop-team-1");
    }

    @Test
    @DisplayName("跨线程：非调用线程上以显式 identity 调用仍返回 identity.teamName（不依赖 thread-local）")
    void explicitIdentityWorksOnAnotherThread() throws Exception {
        TaskService service = newService();
        System.setProperty("nexusai.team.name", "sysprop-team-1");

        // WHY: 原 ThreadLocal 载体在派生线程（工具执行池 / STREAM_EXECUTOR 虚拟线程）读恒 null，
        //   正是旧实现必须「捕获→回放」的根因。显式形参载体不依赖线程身份 ⇒ 派生线程结果不变。
        java.util.concurrent.atomic.AtomicReference<String> onOtherThread =
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> onOtherThread.set(
            service.getTaskListId(null, newIdentity("teammate-leader-team"))), "s1-t6-worker");
        worker.start();
        worker.join(5_000);

        assertThat(onOtherThread.get())
            .as("派生线程上显式 identity 仍生效（若实现改成读 ThreadLocal 则此处为 null/回落值）")
            .isEqualTo("teammate-leader-team");
    }

    @Test
    @DisplayName("identity 存在但 teamName 空白/缺失 → 不占用优先级 2（回落后续级）")
    void blankTeamNameFallsThrough() {
        TaskService service = newService();
        System.setProperty("nexusai.team.name", "sysprop-team-1");

        assertThat(service.getTaskListId(null, newIdentity("")))
            .as("identity.teamName 空白 → 优先级 2 不命中，回落 sysprop team name")
            .isEqualTo("sysprop-team-1");
        assertThat(service.getTaskListId(null, newIdentity(null)))
            .as("identity.teamName null → 优先级 2 不命中，回落 sysprop team name")
            .isEqualTo("sysprop-team-1");
    }
}
