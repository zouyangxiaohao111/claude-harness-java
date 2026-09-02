package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RF-2 ①] AgentProgressTracker 摘要推送通道（CC updateAgentSummary → emitTaskProgress）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：CC 周期摘要 {@code updateAgentSummary}
 * （LocalAgentTask.tsx:359-407）产出一句摘要后，除写入 {@code task.progress.summary}，还经
 * {@code emitTaskProgress} 把 {@code task_progress} SDK 事件推给前端（VS Code subagent panel），
 * 且 gate 在 {@code getSdkAgentProgressSummariesEnabled()} 上。Java 侧旧 {@code updateCallback}
 * 为 no-op —— 摘要文本产出即丢，前端拿不到进度事件。本测试锁定：
 * <ul>
 *   <li>sdk 开 → 摘要经 {@code task_progress} 事件推出（task_id / summary / description 均摘要文本）</li>
 *   <li>sdk 关 → 摘要仅记录（{@code summary()} 可见），不发射 SDK 事件（对齐 CC 门 false no-op）</li>
 * </ul>
 */
@DisplayName("[RF-2] AgentProgressTracker 摘要推送通道")
class AgentProgressTrackerTest {

    @Test
    @DisplayName("sdk 开 → applySummary 发射 task_progress 事件（description 与 summary 均摘要文本）")
    void applySummary_withSdkEnabled_emitsTaskProgressEvent() {
        SdkEventQueue queue = new SdkEventQueue();
        AgentProgressTracker tracker = new AgentProgressTracker("task-1", "tu-1", 1000L);
        tracker.setProgress(3L, 150L);

        tracker.applySummary("Reading runAgent.ts", true, queue);

        assertThat(tracker.summary()).isEqualTo("Reading runAgent.ts");
        List<SdkEventQueue.DrainedSdkEvent> drained = queue.drainSdkEvents("session-1");
        assertThat(drained).hasSize(1);
        SdkEventQueue.SdkEvent event = drained.get(0).event();
        assertThat(event).isInstanceOf(SdkEventQueue.TaskProgressEvent.class);
        SdkEventQueue.TaskProgressEvent progress = (SdkEventQueue.TaskProgressEvent) event;
        // CC emitTaskProgress({ description: summary, ..., summary }) —— 两者均摘要文本
        assertThat(progress.taskId()).isEqualTo("task-1");
        assertThat(progress.toolUseId()).isEqualTo("tu-1");
        assertThat(progress.description()).isEqualTo("Reading runAgent.ts");
        assertThat(progress.summary()).isEqualTo("Reading runAgent.ts");
        assertThat(progress.usage().totalTokens()).isEqualTo(150);
        assertThat(progress.usage().toolUses()).isEqualTo(3);
    }

    @Test
    @DisplayName("sdk 关 → applySummary 仅记录摘要、不发射 SDK 事件（对齐 CC 门 false no-op）")
    void applySummary_withSdkDisabled_doesNotEmit() {
        SdkEventQueue queue = new SdkEventQueue();
        AgentProgressTracker tracker = new AgentProgressTracker("task-2", null, 2000L);

        tracker.applySummary("Fixing null check", false, queue);

        assertThat(tracker.summary()).isEqualTo("Fixing null check");
        assertThat(queue.drainSdkEvents("session-1")).isEmpty();
    }

    @Test
    @DisplayName("queue 未装配（测试直构无 bean）→ 仅记录、不发射")
    void applySummary_withNullQueue_doesNotEmit() {
        AgentProgressTracker tracker = new AgentProgressTracker("task-3", null, 3000L);

        tracker.applySummary("Running auth module tests", true, null);

        assertThat(tracker.summary()).isEqualTo("Running auth module tests");
    }

    @Test
    @DisplayName("setProgress 累积 toolUseCount / tokenCount（CC updateProgressFromMessage 累积）")
    void setProgress_accumulatesCounters() {
        AgentProgressTracker tracker = new AgentProgressTracker("task-4", null, 0L);
        tracker.setProgress(1L, 50L);
        tracker.setProgress(2L, 110L);

        assertThat(tracker.toolUseCount()).isEqualTo(2L);
        assertThat(tracker.tokenCount()).isEqualTo(110L);
    }

    @Test
    @DisplayName("accumulateFromMessage 逐 assistant message 累积 → applySummary 发射 token/toolUse > 0（对齐 CC updateProgressFromMessage）")
    void accumulateFromMessage_accumulatesThenEmitsPositiveProgress() {
        SdkEventQueue queue = new SdkEventQueue();
        AgentProgressTracker tracker = new AgentProgressTracker("task-5", "tu-5", 0L);

        // 第 1 条 assistant message: input 100 + cache_creation 10 + cache_read 5, output 30, 2 个 tool_use
        tracker.accumulateFromMessage(
            new AgentUsage(100L, 30L, 10L, 5L, null, "standard", null), 2);
        // 第 2 条 assistant message: input 200（最新, 覆盖 115）+ output 40, 1 个 tool_use
        tracker.accumulateFromMessage(
            new AgentUsage(200L, 40L, 0L, 0L, null, "standard", null), 1);

        // CC 累积: latestInputTokens=200, cumulativeOutputTokens=30+40=70, tokenCount=270; toolUseCount=3
        assertThat(tracker.toolUseCount()).isEqualTo(3L);
        assertThat(tracker.tokenCount()).isEqualTo(270L);

        tracker.applySummary("Reading runAgent.ts", true, queue);
        SdkEventQueue.TaskProgressEvent progress =
            (SdkEventQueue.TaskProgressEvent) queue.drainSdkEvents("session-1").get(0).event();
        assertThat(progress.usage().totalTokens()).isEqualTo(270);
        assertThat(progress.usage().toolUses()).isEqualTo(3);
    }
}
