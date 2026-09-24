package com.nexusai.application.agent.team;

import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 刀 1a · teammate 注册时带上会话归属（BackgroundTask.sessionId = state.identity().parentSessionId()）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这条会话归属为何重要：
 * <ul>
 *   <li>{@code TaskController.listTasks} 的会话级过滤是
 *       {@code if (task.sessionId() == null || !sessionId.equals(task.sessionId())) continue;}
 *       —— sessionId=null 的 teammate 被整条剔除，**会话级 REST 清单里看不到它**；</li>
 *   <li>前端「子代理运行状况 / 异步任务」的 REST 恢复通道（STOMP /topic/tasks 不重放历史事件）
 *       只能靠这份清单补录 ⇒ sessionId=null 时 teammate 只剩实时 STOMP 一条命，事件在窗口外
 *       丢掉就永久空白（用户报的「子代理在面板看不到」）。</li>
 * </ul>
 *
 * <p>对照（类型间不一致的证据）：普通 local_agent 由
 * {@code BackgroundTaskRunner.registerAsyncAgent/spawn} 显式带 createSessionId，同样的
 * {@code TaskDto} 走同一过滤却查得到。
 *
 * <p><b>反向实验</b>：把 {@code toBackgroundTask} 末尾的 {@code task.withSessionId(parentSessionId)}
 * 去掉（退回 13 参兼容构造的 sessionId=null）⇒ 本测试第一条断言变红。
 */
@DisplayName("[刀 1a] teammate 注册会话归属：BackgroundTask.sessionId = parentSessionId")
class InProcessTeammateSessionAttributionTest {

    @BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        // taskOutputPath → taskOutputDir → CwdResolution.getOriginalCwdLayer(sessionId)：
        //   纯 JUnit 夹具不接 DB 回源，必须显式声明「本环境确无会话」，否则 fail-loud 抛。
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    /** 构造最小 teammate 状态载体（identity 只填本用例关心的字段）。 */
    private static InProcessTeammateTaskState state(String taskId, String parentSessionId) {
        return new InProcessTeammateTaskState(
            taskId,
            new TeammateIdentity("a-1", "alice", "team-1", null, false, parentSessionId),
            "调研 prompt",
            null, false, "default", null,
            List.of(), Set.of(), List.of(), false, false, 0, 0,
            null, null, null, List.of());
    }

    @Test
    @DisplayName("toBackgroundTask 产出的 BackgroundTask 带 parentSessionId（非 null ⇒ 会话级清单不再剔除）")
    void toBackgroundTask_carriesParentSessionId() {
        BackgroundTask bg = InProcessTeammateTaskRegistry.toBackgroundTask(state("t-1", "sess-parent"), "tu-1");

        // 核心断言：会话归属 = 父会话（改动前 = null ⇒ 被 TaskController 会话级过滤剔除）
        assertThat(bg.sessionId())
            .as("teammate 的 BackgroundTask.sessionId 必须是父会话（否则会话级 REST 清单看不到它，"
                + "前端 REST 恢复通道全断）")
            .isEqualTo("sess-parent");
    }

    @Test
    @DisplayName("withSessionId 是不可变副本：其余字段（类型/状态/描述/toolUseId/outputFile）零损伤")
    void withSessionId_copyKeepsAllOtherFields() {
        // WHY：刀 1a 走 withSessionId（返回新对象）而非 canonical 20 参直构 —— 必须证明它没有丢字段
        //   （丢弃返回值 / 字段错位都会静默产生一个"半成品"任务，比 sessionId 为 null 更难查）。
        BackgroundTask bg = InProcessTeammateTaskRegistry.toBackgroundTask(state("t-2", "sess-parent"), "tu-2");

        assertThat(bg.id()).isEqualTo("t-2");
        assertThat(bg.type()).isEqualTo(TaskType.IN_PROCESS_TEAMMATE);
        assertThat(bg.status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        assertThat(bg.description()).isEqualTo("alice: 调研 prompt");
        assertThat(bg.toolUseId()).isEqualTo("tu-2");
        assertThat(bg.agentId()).isNull();
        assertThat(bg.isBackgrounded()).isTrue();
        assertThat(bg.notified()).isFalse();
        // 输出根仍按父会话分层（批 3b-D7 契约：{...}/{sessionId}/tasks/{taskId}.output）
        assertThat(bg.outputFile()).as("outputFile 未因 withSessionId 丢失")
            .endsWith("t-2.output");
        assertThat(bg.outputFile()).as("输出根目录仍按父会话分层").contains("sess-parent");
    }

    @Test
    @DisplayName("端到端：注册进统一 store 后，该任务在会话级过滤下可见（sessionId.equals(task.sessionId()) 命中）")
    void registeredTeammate_isVisibleUnderSessionScopedFilter() {
        // WHY：断言 TaskController.listTasks 的过滤判据本身（同一判据句：sessionId==null 才剔除）。
        //   注册入口 = TaskFrameworkService.registerTask（InProcessTeammateTaskRegistry.register 的
        //   store 写入同源）。
        SdkEventQueue sdk = new SdkEventQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        BackgroundTask bg = InProcessTeammateTaskRegistry.toBackgroundTask(state("t-3", "sess-parent"), "tu-3");
        service.registerTask(bg);

        BackgroundTask stored = service.getTask("t-3").orElseThrow();
        String sessionId = "sess-parent";
        boolean excludedBySessionFilter = stored.sessionId() == null || !sessionId.equals(stored.sessionId());

        assertThat(excludedBySessionFilter)
            .as("会话级过滤条件（TaskController.listTasks:121 同款判据）不得剔除该 teammate")
            .isFalse();
    }
}
