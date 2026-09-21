package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [team-hang P-d] {@link StreamingToolExecutor} 默认执行器<b>不得泄漏线程</b>。
 *
 * <p><b>缺陷现场</b>：原 {@code defaultExecutor()} 每次实例化都新建一个
 * {@code newFixedThreadPool(min(8, cores))}，而全仓<b>没有任何 {@code shutdown} 调用点</b>
 * （本类无 close/AutoCloseable，也没有调用方知道「本实例用完了」）⇒ 池的 core 线程在任务结束后仍然存活
 * ⇒ executor 实例数 ≈ 轮数 ⇒ 线程数线性增长（实测 jstack：{@code tool-exec-*} 线程 <b>547</b> 条）。
 *
 * <p><b>判据（为什么要「跑真工具」）</b>：只对 {@code newFixedThreadPool} 做静态检查等于测实现细节。
 * 这里跑<b>真</b>工具执行：既拿到「任务确实跑在 {@code tool-exec-*} 线程上」的<b>正向对照</b>
 * （否则「线程数不增长」可能只是因为线程压根没被创建过 = 空绿），又用「反复实例化后平台线程增量」作为
 * 泄漏判据（虚拟线程不计入 {@code Thread.getAllStackTraces()} ⇒ 修复后增量恒为 0；修复前每个实例
 * 至少留下 1 条 core 线程）。
 *
 * <p><b>反向实验</b>：把 {@code defaultExecutor()} 改回 {@code newFixedThreadPool(...)} ⇒
 * 20 次实例化后增量 ≈ 20（> 阈值 2）⇒ 本用例变红。
 */
@DisplayName("[team-hang P-d] StreamingToolExecutor 默认执行器：反复实例化不累积 tool-exec 平台线程")
class StreamingToolExecutorDefaultExecutorLeakTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROBE_TOOL_NAME = "LeakProbe";
    /** 允许的噪声（其他用例/框架可能恰好留下少量同前缀线程）。真实泄漏量级是「每实例 ≥1 条」。 */
    private static final int ALLOWED_DELTA = 2;
    private static final int INSTANTIATIONS = 20;

    /** 探针工具：记录执行线程名（证明任务真的走了执行器，而不是在调用线程上内联跑）。 */
    private static final class ProbeTool implements Tool {
        final List<String> observedThreads = Collections.synchronizedList(new ArrayList<>());
        @Override public String name() { return PROBE_TOOL_NAME; }
        @Override public String description() { return "leak probe"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            observedThreads.add(Thread.currentThread().getName());
            return ToolResult.success(call.id(), "probe-done");
        }
    }

    /** 活着的<b>平台</b>线程里名字以 {@code tool-exec-} 开头者（虚拟线程不在其中）。 */
    private static int liveToolExecPlatformThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (name != null && name.startsWith("tool-exec-")) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("反复实例化 " + INSTANTIATIONS + " 次并各跑一个工具：tool-exec 平台线程不增长（且任务确实跑在 tool-exec 线程上）")
    void repeatedInstantiation_doesNotAccumulatePlatformToolExecThreads() throws Exception {
        ProbeTool probe = new ProbeTool();
        int before = liveToolExecPlatformThreads();

        for (int i = 0; i < INSTANTIATIONS; i++) {
            // 生产同款入口：只给 registry + ctx ⇒ 走 defaultExecutor()
            StreamingToolExecutor exec = new StreamingToolExecutor(
                new ToolRegistry().register(probe),
                ToolUseContext.of(UUID.randomUUID(), "sess-leak-" + i));
            exec.add(new ToolUseBlock("probe-" + i, PROBE_TOOL_NAME, JSON.createObjectNode()), null, null);
            assertThat(exec.getRemainingResults())
                .as("第 %d 次实例化必须真的执行完探针工具", i).hasSize(1);
        }

        assertThat(probe.observedThreads).as("探针工具必须真的被调用过（否则本用例空绿）")
            .hasSize(INSTANTIATIONS);
        assertThat(probe.observedThreads)
            .as("正向对照：任务必须跑在 tool-exec-* 线程上（执行器真的派了线程）")
            .allMatch(n -> n.startsWith("tool-exec-"));

        int after = liveToolExecPlatformThreads();
        assertThat(after - before)
            .as("★ 反复实例化后平台线程增量必须 ≈ 0 —— 修复前每个实例留下固定池的 core 线程"
                + "（实测全仓 jstack 里 tool-exec-* 达 547 条）。before=%d after=%d", before, after)
            .isLessThanOrEqualTo(ALLOWED_DELTA);
    }
}
