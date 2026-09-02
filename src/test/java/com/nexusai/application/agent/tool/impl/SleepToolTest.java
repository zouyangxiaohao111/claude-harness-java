package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SleepTool 500ms 队列轮询唤醒 · 对齐 CC {@code SleepTool.ts} {@code wakeCheck} setInterval。
 *
 * <p>[G31① 重写后契约] 输入 {@code duration_seconds}（秒）、输出 {@code {slept_seconds, interrupted}}
 * （CC SleepOutput :23）；本测试锁定唤醒语义（S6 已由 master 覆盖勿重复）+ 新输入/输出契约。
 *
 * <p><b>WHY (意图验证，规则九)</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>cron 到点能提前唤醒 Sleep 回合</b>（D1 探查 Q3）：CC SleepTool.ts:179-183 每 500ms
 *       轮询 {@code hasCommandsInQueue()}，新工作（含 cron 入队）到达 → {@code interrupt()} 打断
 *       睡眠 → 本轮立即继续，不必睡满浪费 turn。Java 端等价：睡眠循环中每 500ms 检查
 *       {@code NotificationQueue.hasCommandsInQueue()}，新命令 → 提前返回
 *       {@code interrupted=true} + 实际 {@code slept_seconds}（对齐 CC interrupt → {@code {slept_seconds, interrupted:true}}）。</li>
 *   <li><b>无新命令不误唤醒</b>：队列空 → 睡满全时长（interrupted=false），防止无谓的模型调用开销。</li>
 *   <li><b>入眠前队列已非空 → 立即中断</b>（CC :108-115）：模型决策等待期间已有新工作到达时，
 *       不应进入睡眠（sleep 0 + interrupted=true）。</li>
 *   <li><b>NotificationQueue 未注入（null 守卫）→ 裸 sleep 原行为</b>：非 Spring 场景
 *       （proactive flag 关闭 / 直构测试）降级为纯睡眠，不做队列轮询，无兼容层双轨。</li>
 * </ul>
 *
 * @see SleepTool
 */
@DisplayName("SleepTool 500ms 队列轮询唤醒（CC SleepTool.ts wakeCheck）")
class SleepToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseBlock sleepCall(double durationSeconds) {
        ObjectNode input = JSON.createObjectNode();
        input.put("duration_seconds", durationSeconds);
        return new ToolUseBlock("sleep-1", "Sleep", input);
    }

    private static NotificationQueue.QueueItem cronCommand() {
        // 对齐 CC useScheduledTasks.ts:71-82 cron 入队形态（mode=prompt / priority=later）
        return new NotificationQueue.QueueItem(
            "cron 任务提示", NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.LATER, null);
    }

    @Test
    @DisplayName("睡眠中队列入新命令 → 提前中断（interrupted=true, 实际 slept < 请求）")
    void earlyInterrupt_whenCommandEnqueuedDuringSleep() throws Exception {
        // WHY: cron 到点把 prompt 入队后，Sleep 必须在 500ms 内被唤醒（CC wakeCheck 轮询），
        // 否则睡满全时长浪费 turn —— 这是本任务的核心验收（D1-cc-session-turn-cron-probe §Q3）。
        NotificationQueue queue = new NotificationQueue();
        SleepTool tool = new SleepTool();
        tool.setNotificationQueue(queue);

        double requested = 2.0; // 2s
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            // 200ms 后入队一条 cron 命令；首个 wakeCheck 在 500ms 处应检测到并提前唤醒
            scheduler.schedule(() -> queue.enqueue(cronCommand()), 200, TimeUnit.MILLISECONDS);

            long t0 = System.currentTimeMillis();
            ToolResult<String> r = tool.execute(sleepCall(requested));
            long wall = System.currentTimeMillis() - t0;

            JsonNode data = JSON.readTree(r.data());
            assertThat(data.path("interrupted").asBoolean())
                .as("队列出现新命令必须提前唤醒（对齐 CC SleepTool.ts:180-182 wakeCheck → interrupt）")
                .isTrue();
            assertThat(data.path("slept_seconds").asDouble())
                .as("提前唤醒返回实际 slept_seconds（对齐 CC {slept_seconds, interrupted:true}）")
                .isLessThan(requested);
            assertThat(wall)
                .as("cron 唤醒不必等待全时长")
                .isLessThan(2000L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("无新命令 → 睡满全时长（interrupted=false, slept_seconds ≈ requested）")
    void sleepsFull_whenNoCommand() throws Exception {
        // WHY: 队列空则不应误唤醒 —— 提前返回会触发多余的模型调用（prompt cache 过期 5 分钟）,
        // 睡满才能兑现"等待外部异步事件"的语义。
        NotificationQueue queue = new NotificationQueue();
        SleepTool tool = new SleepTool();
        tool.setNotificationQueue(queue);

        // 1.1s > 500ms 轮询间隔：跨多个 wakeCheck 均无新命令 → 不误唤醒
        double requested = 1.1;
        long t0 = System.currentTimeMillis();
        ToolResult<String> r = tool.execute(sleepCall(requested));
        long wall = System.currentTimeMillis() - t0;

        JsonNode data = JSON.readTree(r.data());
        assertThat(data.path("interrupted").asBoolean())
            .as("无新命令不应中断（空队列跨多个 wakeCheck 不误唤醒）")
            .isFalse();
        assertThat(data.path("slept_seconds").asDouble())
            .as("实际睡眠应达到请求时长（CC 成功路径 slept_seconds=duration_seconds）")
            .isGreaterThanOrEqualTo(requested);
        assertThat(wall)
            .as("无新命令应睡满")
            .isGreaterThanOrEqualTo(1100L);
    }

    @Test
    @DisplayName("NotificationQueue 未注入（null）→ 裸 sleep 原行为（interrupted=false, 睡满）")
    void bareSleep_whenQueueNotInjected() throws Exception {
        // WHY: @Autowired(required=false) + null 守卫 —— 未接线队列时降级为纯睡眠，
        // 不引入队列轮询（无兼容层双轨；proactive flag 关闭场景不应有任何唤醒副作用）。
        SleepTool tool = new SleepTool(); // 未注入队列

        double requested = 0.3; // < 500ms 轮询间隔，单段睡满
        long t0 = System.currentTimeMillis();
        ToolResult<String> r = tool.execute(sleepCall(requested));
        long wall = System.currentTimeMillis() - t0;

        JsonNode data = JSON.readTree(r.data());
        assertThat(data.path("interrupted").asBoolean())
            .as("未注入队列 → 保持裸 sleep 原行为（不中断）")
            .isFalse();
        assertThat(data.path("slept_seconds").asDouble())
            .as("裸 sleep 应睡满请求时长")
            .isGreaterThanOrEqualTo(requested);
        assertThat(wall)
            .as("裸 sleep 应睡满")
            .isGreaterThanOrEqualTo(300L);
    }

    @Test
    @DisplayName("入眠前队列已有命令 → 立即中断（slept≈0, interrupted=true）对齐 CC :108-115")
    void immediateInterrupt_whenCommandAlreadyQueued() throws Exception {
        // WHY: CC SleepTool.ts:108-115 入眠前先检查 shouldInterruptSleep() —— 模型决策等待期间
        // 新工作已到达时不应进入睡眠（sleep 0 + interrupted=true），否则白白等一个 500ms 轮询周期。
        NotificationQueue queue = new NotificationQueue();
        queue.enqueue(cronCommand());
        SleepTool tool = new SleepTool();
        tool.setNotificationQueue(queue);

        long t0 = System.currentTimeMillis();
        ToolResult<String> r = tool.execute(sleepCall(2.0));
        long wall = System.currentTimeMillis() - t0;

        JsonNode data = JSON.readTree(r.data());
        assertThat(data.path("interrupted").asBoolean())
            .as("入眠前队列非空必须立即中断")
            .isTrue();
        assertThat(data.path("slept_seconds").asDouble())
            .as("入眠前中断 slept_seconds 应为 0（CC :108-115 返回 {slept_seconds:0, interrupted:true}）")
            .isEqualTo(0.0);
        assertThat(wall)
            .as("入眠前中断应立即返回，不进入 500ms 轮询")
            .isLessThan(500L);
    }
}
