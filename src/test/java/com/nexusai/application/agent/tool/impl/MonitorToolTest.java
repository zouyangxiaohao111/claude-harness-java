package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.MonitorMcpTaskRunner;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.mcp.dto.McpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MonitorTool 生产接线测试 — execute 触发 registerTask + 独立线程 monitor + 返回 taskId/outputFile。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>生产调用方从 0 变 1</b>：MonitorMcpTaskRunner 此前零生产调用（design §1.2 grep 自验）——
 *       不接 execute，MonitorTool 只是 fail-loud stub，MONITOR_TOOL flag 开了也没有任何能力。</li>
 *   <li><b>registerTask 落统一 store</b>：TaskStop / killMonitorMcpTasksForAgent 按 store 查任务终止
 *       （CC framework.ts registerTask 语义）——execute 必须经 registerTask 注册，否则终止链路查不到任务。</li>
 *   <li><b>独立线程跑 monitor</b>：monitor() 是阻塞轮询循环（2s/次），execute 同步调用会把整个 turn
 *       挂死（design §1.1）——必须在独立线程启动。</li>
 *   <li><b>返回契约</b>：taskId + outputFile 让模型经 TaskOutput 读流（对齐 BashTool.executeBackground 契约）。</li>
 *   <li><b>fail-loud</b>：runner 未装配 / McpServerService 无 bean → execute 直接 error（不假启动、
 *       不吞错误，monitor 抛错在子线程 execute 无法捕获，故前置 isMonitorAvailable 校验）。</li>
 * </ul>
 */
@DisplayName("[monitor-rework] MonitorTool 生产接线（execute → registerTask + monitor 线程 + 返回契约）")
class MonitorToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** MONITOR_TOOL flag 开启的 FeatureFlags（第 16 位 monitorTool=true，record 17 字段）。 */
    private static final FeatureFlags MONITOR_FLAG_ON = new FeatureFlags(
        false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, true, false, false, false, false, false);

    // ════════════════════════════════════════════════════════════════
    // 基建：mock McpServerService + 真实 framework/sdk/queue（同 MonitorMcpTaskRunnerTest 模式）
    // ════════════════════════════════════════════════════════════════

    private static MonitorMcpTaskRunner newRunner(McpServerService mcp, TaskFrameworkService service,
                                                  SdkEventQueue sdk, NotificationQueue nq) {
        MonitorMcpTaskRunner runner = new MonitorMcpTaskRunner();
        ReflectionTestUtils.setField(runner, "mcpServerService", mcp);
        ReflectionTestUtils.setField(runner, "taskFrameworkService", service);
        ReflectionTestUtils.setField(runner, "sdkEventQueue", sdk);
        ReflectionTestUtils.setField(runner, "notificationQueue", nq);
        return runner;
    }

    private static McpServerDto server(String id, McpStatus status) {
        // 11 参便捷构造（userFacingName/channelPermissions 走默认；gap31 字段本测试不断言）
        return new McpServerDto(id, "server-" + id, "cmd",
            List.of(), java.util.Map.of(), status, status == McpStatus.error ? "boom" : null, true, null, "stdio", null);
    }

    private static void awaitUntil(BooleanSupplier cond, String desc) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + desc, e);
            }
        }
        throw new AssertionError("等待超时: " + desc);
    }

    private static ToolUseBlock call(String id, String description) {
        ObjectNode input = JSON.createObjectNode();
        if (description != null) {
            input.put("description", description);
        }
        return new ToolUseBlock(id, "Monitor", input);
    }

    // ════════════════════════════════════════════════════════════════
    // 1. 生产接线：execute → registerTask + monitor 线程启动 + 返回契约
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("execute 触发 registerTask + 独立线程 monitor，返回 taskId+outputFile，store 落 RUNNING+归属")
    void execute_wiresRegisterTaskAndMonitorThread() throws Exception {
        // WHY：MONITOR_TOOL 开启时 MonitorTool 必须是"真实现"——execute 经 registerTask 注册任务
        // （TaskStop/kill 按 store 可命中）→ 独立线程跑 monitor（阻塞轮询不挂 turn）→ 返回契约供
        // TaskOutput 读流。若仅 stub 返回 error，MONITOR_TOOL 开了也是死工具（design §1.2）。
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.getCurrentTools()).thenReturn(List.of());
        when(mcp.listAll()).thenReturn(List.of(server("s1", McpStatus.running)));

        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        MonitorMcpTaskRunner runner = newRunner(mcp, service, sdk, nq);
        MonitorTool tool = new MonitorTool(MONITOR_FLAG_ON, runner);

        UUID agentId = UUID.randomUUID();
        ToolUseContext ctx = ToolUseContext.of(agentId, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        AgentToolResult<?> result = tool.execute(call("call-m-1", "watch mcp"), ctx);

        // 返回契约：非 error + "Monitor started: <taskId>" + "Use TaskOutput to read the stream from: <outputFile>"
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("MONITOR_TOOL 开且 runner 可用 → execute 不应 error").isFalse();
        String msg = (String) result.data();
        assertThat(msg).contains("Monitor started: m");
        assertThat(msg).contains("Use TaskOutput to read the stream from:");
        // 提取 taskId：消息格式 "Monitor started: <taskId>\nUse TaskOutput..."（首行第 3 token）。
        // 注意不能用 replaceFirst(".*started: (\\S+).*") —— Java 正则 `.` 不匹配 \n，会残留换行后内容。
        String taskId = msg.split("\\s+")[2];
        assertThat(taskId).startsWith("m");

        // registerTask 落统一 store：RUNNING + toolUseId=call.id + agentId=ctx.agentId（kill 归属命中）
        var registered = service.getTask(taskId).orElseThrow();
        assertThat(registered.type().getTypeString()).isEqualTo("monitor_mcp");
        assertThat(registered.toolUseId()).isEqualTo("call-m-1");
        assertThat(registered.agentId()).isEqualTo(agentId);

        // 独立线程 monitor 真正启动：outputFile 被观测行写入（streaming-only 运行期增长）
        String outputFile = runner.outputFileFor(taskId);
        Path out = Path.of(outputFile);
        try {
            awaitUntil(() -> {
                try {
                    return Files.exists(out) && Files.readString(out).contains("servers=1");
                } catch (Exception e) {
                    return false;
                }
            }, "monitor 线程启动并写入首个观测");
        } finally {
            runner.stop();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 2. fail-loud：runner 未装配 → error（不假启动）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("runner null → execute fail-loud error（MONITOR_TOOL 开但无 runner bean）")
    void execute_runnerNull_returnsError() {
        // WHY：MONITOR_TOOL flag 可开但 MonitorMcpTaskRunner 是 @Component —— 装配缺失时 execute 必须
        // 显式报错（不能静默 success"假启动"，design §1.2 前科：假接线）。
        MonitorTool tool = new MonitorTool(MONITOR_FLAG_ON, null);
        AgentToolResult<?> result = tool.execute(call("call-m-2", "watch"), null);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("runner null → fail-loud").isTrue();
        assertThat((String) result.data()).contains("MonitorMcpTaskRunner 未装配");
    }

    // ════════════════════════════════════════════════════════════════
    // 3. fail-loud：McpServerService 无 bean → execute 前置 error（不启动线程不注册任务）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("McpServerService 未装配 → execute 前置 error（monitor 抛错在子线程无法同步捕获）")
    void execute_mcpUnavailable_returnsErrorBeforeRegister() {
        // WHY：monitor() 无 mcpServerService 时抛 IllegalStateException（MonitorMcpTaskRunner:154-158），
        // 但 monitor 在独立线程跑——异常发生在子线程，execute 无法 catch。前置 isMonitorAvailable 校验
        // 保证 execute 在启动线程/注册任务前同步 error（不吞错误、不产生孤儿线程）。
        SdkEventQueue sdk = new SdkEventQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        MonitorMcpTaskRunner runner = newRunner(null, service, sdk, new NotificationQueue());
        MonitorTool tool = new MonitorTool(MONITOR_FLAG_ON, runner);

        AgentToolResult<?> result = tool.execute(call("call-m-3", "watch"), null);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("McpServerService null → fail-loud").isTrue();
        assertThat((String) result.data()).contains("McpServerService 未装配");
        // 前置校验在 registerTask 之前 → store 无任何 monitor 任务残留
        assertThat(service.listAll()).noneMatch(t -> t.type().getTypeString().equals("monitor_mcp"));
    }

    // ════════════════════════════════════════════════════════════════
    // 4. DEC-4：BackgroundTaskRunner.getOutput 经 store 回退可读 monitor 任务
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DEC-4：getOutput 经 store 回退可读 monitor 任务（CC 单一 state.tasks map 语义）")
    void getOutput_resolvesMonitorTaskViaStoreFallback() {
        // WHY：monitor 任务经 MonitorMcpTaskRunner.registerTask 注册到 TaskFrameworkService.store，
        //   不在 BackgroundTaskRunner 本地 tasks map。getOutput 若只查本地 map → TaskOutput 对 monitor
        //   任务返回 found=false → DEC-4「模型经 TaskOutput 读 outputFile」断裂（design §1.3 实证）。
        //   S4 resolveOutputTask 回退 store 后，monitor 任务必须可被 TaskOutput 查到（CC framework.ts
        //   registerTask 单一 state.tasks map，TaskOutput 天然可读 monitor 任务）。
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.getCurrentTools()).thenReturn(List.of());
        when(mcp.listAll()).thenReturn(List.of(server("s1", McpStatus.running)));

        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        MonitorMcpTaskRunner runner = newRunner(mcp, service, sdk, nq);
        BackgroundTaskRunner brRunner = new BackgroundTaskRunner(nq, service, sdk);

        String taskId = runner.registerTask("mcp-watch", "tu-1", null);
        // monitor 任务仅在 store，不在 runner 本地 map（S4 前置确认：getTask 只查本地 map）
        assertThat(brRunner.getTask(taskId)).as("monitor 任务不落 runner 本地 map").isEmpty();

        BackgroundTaskRunner.TaskOutput out = brRunner.getOutput(taskId, false, 0);
        assertThat(out.found()).as("monitor 任务经 store 回退可被 TaskOutput 查到（DEC-4）").isTrue();
        assertThat(out.status()).as("非阻塞读 → 当前 RUNNING 状态").isEqualTo(BackgroundTaskStatus.RUNNING);
    }
}
