package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.util.AbortControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * teammate-ctx 定向测试 · 对齐 CC tasks.ts:199-210 getTaskListId() 优先级链
 *
 * <p><b>WHY（意图验证）</b>: CC 的 getTaskListId() 优先级第 2 位是 in-process teammate
 * 的 leader teamName（tasks.ts:205-208: {@code const teammateCtx = getTeammateContext();
 * if (teammateCtx) return teammateCtx.teamName}），使 teammate 与 leader 共享同一任务列表
 * （teammateContext.ts:47-49 getTeammateContext() 取 AsyncLocalStorage store）。该优先级
 * <b>高于</b> CC 优先级 3 的 getTeamName()（Java 近似 sysprop nexusai.team.name）。
 *
 * <p>本测试验证 3 个意图：
 * <ol>
 *   <li><b>无 context 回退链无回归</b>：不设 teammate ctx 时，解析链保持改动前行为
 *       （nexusai.taskListId → nexusai.team.name → leaderTeamName → nexusai.sessionId →
 *       RequestContext 会话 → 进程级 UUID），回归护栏。U-3 弃硬编码 'tasklist'（CC getTaskListId
 *       永不返回 'tasklist'，最终回退 getSessionId() 会话 UUID，tasks.ts:209）。</li>
 *   <li><b>teammate ctx 优先级第 2</b>：runWithTeammateContext 设 ctx 后，getTaskListId()
 *       返回 ctx.teamName，且高于 nexusai.team.name（对齐 CC 优先级 2 &gt; 3）。</li>
 *   <li><b>ThreadLocal 无泄漏</b>：runWithTeammateContext 退出后 ctx 已 restore，
 *       后续 getTaskListId() 回到 sysprop teamName（runWithTeammateContext 自身 finally
 *       语义，防跨测试泄漏）。</li>
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

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetSysprops() {
        System.clearProperty("nexusai.taskListId");
        System.clearProperty("nexusai.team.name");
        System.clearProperty("nexusai.sessionId");
        TaskService.clearLeaderTeamName();
    }

    private TaskService newService() {
        // 显式 configHome 构造器：不依赖 Spring 上下文（getTaskListId 为 static，仅验证解析链）
        return new TaskService(tempDir);
    }

    private TeammateContext newContext(String teamName) {
        return new TeammateContext(
            "researcher@my-team",
            "researcher",
            teamName,
            "#ff0000",
            false,
            "parent-session-id",
            AbortControllerFactory.create());
    }

    @Test
    @DisplayName("无 context 回退链无回归：taskListId → teamName → leaderTeamName → sessionId → RequestContext 会话 → 进程级 UUID")
    void fallbackChainWithoutTeammateContext() {
        TaskService service = newService();

        // 环境显式 CLAUDE_CODE_TASK_LIST_ID 会掩盖 sysprop 层，跳过默认回退断言（环境偶发，非被测逻辑）
        String envTaskListId = System.getenv("CLAUDE_CODE_TASK_LIST_ID");
        Assumptions.assumeTrue(envTaskListId == null || envTaskListId.isBlank(),
            "测试环境设置了 CLAUDE_CODE_TASK_LIST_ID，跳过默认回退断言");

        // 全空 → 回退进程级稳定会话 UUID（U-3 弃硬编码 'tasklist'；CC getTaskListId 永不返回 'tasklist'，
        // 最终回退 getSessionId()=STATE.sessionId 会话 UUID，state.ts:331/431-432；Java 静态方法无会话
        // 上下文时以进程级稳定 UUID 兜底，保证非 null——DC-4 后 sanitizePathComponent(null) 会 NPE）。
        String fallback = service.getTaskListId();
        assertThat(fallback).isNotBlank().isNotEqualTo("tasklist");
        assertThat(service.getTaskListId()).as("进程级会话 UUID 应进程内稳定").isEqualTo(fallback);

        // RequestContext MDC 会话（优先级 6，getSessionId() 当前会话等价物）> 进程级 UUID
        RequestContext.setSession("sess-request-1");
        try {
            assertThat(service.getTaskListId()).isEqualTo("sess-request-1");
        } finally {
            RequestContext.clear();
        }

        // sysprop nexusai.sessionId（优先级 5）> RequestContext 会话
        RequestContext.setSession("sess-request-2");
        System.setProperty("nexusai.sessionId", "sess-1");
        try {
            assertThat(service.getTaskListId()).isEqualTo("sess-1");
        } finally {
            RequestContext.clear();
        }

        // ThreadLocal leaderTeamName（优先级 4）> sessionId
        TaskService.setLeaderTeamName("leader-1");
        assertThat(service.getTaskListId()).isEqualTo("leader-1");

        // sysprop nexusai.team.name（优先级 3）> leaderTeamName
        System.setProperty("nexusai.team.name", "team-1");
        assertThat(service.getTaskListId()).isEqualTo("team-1");

        // sysprop nexusai.taskListId（优先级 1）> 一切
        System.setProperty("nexusai.taskListId", "explicit-1");
        assertThat(service.getTaskListId()).isEqualTo("explicit-1");
    }

    @Test
    @DisplayName("teammate ctx 优先级 2：runWithTeammateContext 设 ctx 后返回 ctx.teamName，高于 nexusai.team.name")
    void teammateContextTakesPriorityOverTeamNameSysprop() {
        TaskService service = newService();
        System.setProperty("nexusai.team.name", "sysprop-team-1");

        // CC tasks.ts:206-207：ctx 存在即返回 teamName（teammate 与 leader 共享任务列表）
        TeammateContext ctx = newContext("teammate-leader-team");
        String resolved = TeammateContext.runWithTeammateContext(ctx, () -> {
            String r = service.getTaskListId();
            assertThat(r).as("优先级2 in-process teammate teamName 应高于 sysprop nexusai.team.name")
                .isEqualTo("teammate-leader-team");
            return r;
        });

        assertThat(resolved).isEqualTo("teammate-leader-team");
    }

    @Test
    @DisplayName("ThreadLocal 无泄漏：runWithTeammateContext 退出后 ctx 已 restore，回到 sysprop teamName")
    void noThreadLocalLeakAfterRunWithTeammateContext() {
        TaskService service = newService();
        System.setProperty("nexusai.team.name", "sysprop-team-1");

        TeammateContext ctx = newContext("teammate-leader-team");
        TeammateContext.runWithTeammateContext(ctx, () -> {
            assertThat(service.getTaskListId()).isEqualTo("teammate-leader-team");
            return null;
        });

        // runWithTeammateContext finally 已 restore 前值（此处前值 null → remove）
        assertThat(service.getTaskListId())
            .as("ctx 退出后 ThreadLocal 应已清理，回退到 sysprop teamName")
            .isEqualTo("sysprop-team-1");
    }
}
