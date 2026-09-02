package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.impl.TaskCreateTool;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [reqId MDC 传播] 含 requestId MDC 的线程 isTodoV2Enabled()==true → Task V2 工具集一致
 *
 * <p>WHY (决策 #65 父 V2/子 V1 工具集分叉): Task 组默认开改法 {@code isInteractive()} =
 * {@code RequestContext.requestId()!=null}（MDC ThreadLocal）。subagent 异步线程（SubagentTool
 * executeAsync/executeResumeAsync 的 {@code new Thread}）经 MDC 传播后必须含 requestId → 本测试锁定
 * 「含 requestId MDC 的线程 isTodoV2Enabled()==true → V2」意图（Task 组工具集一致、TodoWrite 不注册）。
 *
 * <p><b>测试意图（规则九）</b>: 断言业务结果而非机制 —— MDC 含 requestId 的线程（subagent 传播后的状态）
 * 必须判 V2。若业务逻辑变更（如 isTodoV2Enabled 不再依赖 requestId）则本测试红。
 *
 * <p>注: subagent <b>线程内</b>实际拿到父 requestId 的传播链路由
 * {@code SubagentToolMdcPropagationTest}（真实 async worker 线程）+ {@code StreamingToolExecutorMdcPropagationTest}
 * （工具池线程）锁定；本测试锁定 MDC→V2 判定语义这一环。
 */
@DisplayName("reqId MDC 传播 · 含 requestId MDC 的线程 isTodoV2Enabled()==true → V2 工具集一致")
class TaskSystemConfigMdcInheritanceTest {

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
        RequestContext.clear();
    }

    @Test
    @DisplayName("含 requestId MDC 的线程 isInteractive()/isTodoV2Enabled()==true → TodoWrite 不注册、Task 工具族注册")
    void threadWithRequestIdMdc_isTodoV2EnabledTrue() {
        // WHY: 决策 #65 判据 = RequestContext.requestId()!=null → 交互 → V2。
        //      本测试锁定该判据在含 requestId MDC 的线程成立（subagent 线程传播后的状态）。
        TaskSystemConfig.clearForTest();
        RequestContext.set("sess-x", "req-x");

        assertThat(RequestContext.requestId()).isEqualTo("req-x");
        assertThat(TaskSystemConfig.isInteractive())
            .as("含 requestId MDC → 交互").isTrue();
        assertThat(TaskSystemConfig.isTodoV2Enabled())
            .as("含 requestId MDC → todoV2 默认开").isTrue();
        assertThat(new TodoWriteTool().isEnabled())
            .as("V2 模式下 TodoWrite 不注册（与父会话一致）").isFalse();
        assertThat(new TaskCreateTool(mock(TaskService.class), mock(HookRegistry.class)).isEnabled())
            .as("V2 模式下 Task 工具族注册（与父会话一致）").isTrue();
    }

    @Test
    @DisplayName("仅 sessionId（cron/后台，无 reqId）→ isTodoV2Enabled()==false（V1 判据对照）")
    void threadWithOnlySessionId_isTodoV2EnabledFalse() {
        // WHY: 对照锁死决策 #65 判据边界 —— 只有 reqId（前端用户消息在途）才算交互；
        //      仅 sessionId（cron/后台）必须保持 V1，否则后台任务误用 V2 工具集。
        TaskSystemConfig.clearForTest();
        RequestContext.setSession("sess-cron");

        assertThat(TaskSystemConfig.isInteractive()).isFalse();
        assertThat(TaskSystemConfig.isTodoV2Enabled()).isFalse();
    }
}
