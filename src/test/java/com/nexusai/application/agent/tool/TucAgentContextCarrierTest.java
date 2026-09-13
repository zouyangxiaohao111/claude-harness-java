package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 5b-1] {@code ToolUseContext.agentContext} 显式载体契约测试。
 *
 * <p><b>WHY（意图验证，不只验证行为）</b>：本批把「classifier / exec-prompt hook / tool-use summary
 * 在 commonPool worker 上读 {@code AgentContext.STORAGE} ThreadLocal（恒 null，sparse-edge 归因静默丢失）」
 * 改为「读 TUC 上显式盖章的 {@link AgentContext} 实例」。这条改造的失效模式有<b>三种</b>，
 * 每种都必须有断言，否则就是「声称守护 X 实际守不住」：
 * <ol>
 *   <li><b>派生链丢值</b>：TUC 的 wither（{@code withXxx} / {@code copyWith} / {@code with(overrides)} /
 *       {@code SubagentExecutor.withEffectiveCwd}）若忘记透传，值在派生途中被清成 null
 *       （本仓有前科：{@code subagentName} 被 Step 18 派生链清掉 ⇒ 必须在本步重新盖章）。
 *       ⇒ {@link #witherChain_preservesAgentContext()} / {@link #withEffectiveCwd_preservesAgentContext()}</li>
 *   <li><b>跨线程不可见</b>：值必须能被 commonPool worker 读到（否则等于没改）
 *       ⇒ {@link #agentContext_readableOnCommonPoolWorker_andConsumedOnce()}</li>
 *   <li><b>同实例语义</b>：sparse edge「每个 invocation 只发一次」靠共享的可变标记
 *       {@code invocationEmitted}；按标量重建实例会让每次消费都重新发射
 *       ⇒ {@link #rebuiltInstance_emitsEdgeAgain_sparseSemanticsRequiresSameInstance()}</li>
 * </ol>
 */
class TucAgentContextCarrierTest {

    private static AgentContext.SubagentContext subCtx(String reqId, String kind) {
        return new AgentContext.SubagentContext(
            "a0123456789abcdef", "sess-parent", "Explore", true, reqId, kind);
    }

    @Test
    @DisplayName("[批 5b-1] TUC 派生链（withXxx/copyWith/with）恒透传 agentContext")
    void witherChain_preservesAgentContext() {
        AgentContext.SubagentContext sub = subCtx("req_wither", "spawn");
        ToolUseContext base = ToolUseContext.of(UUID.randomUUID(), "sess-x").withAgentContext(sub);

        // 生产派生链（per-turn TUC 由 toolExecContext 经这些 wither 派生）
        ToolUseContext derived = base
            .withQueryTracking(Map.of("chainId", "c1"))
            .withMessages(List.of("m"))
            .withAvailableTools(List.of())
            .withNonInteractiveSession(true);

        assertThat(derived.agentContext())
            .as("派生链任一环丢值 ⇒ classifier/hook/summary 的归因边又静默消失")
            .isSameAs(sub);
    }

    @Test
    @DisplayName("[批 5b-1] SubagentExecutor.withEffectiveCwd（Step 18 派生）恒透传 agentContext")
    void withEffectiveCwd_preservesAgentContext() {
        AgentContext.SubagentContext sub = subCtx("req_step18", "spawn");
        ToolUseContext source = ToolUseContext.of(UUID.randomUUID(), "sess-x").withAgentContext(sub);

        ToolUseContext derived = SubagentExecutor.withEffectiveCwd(source, Path.of(".").toAbsolutePath());

        assertThat(derived).isNotNull();
        assertThat(derived.agentContext())
            .as("该 wither 是「Step 18 清身份」的历史现场（subagentName 曾被清），必须透传")
            .isSameAs(sub);
    }

    @Test
    @DisplayName("[批 5b-1] 真实 commonPool 线程可读 + sparse edge 恰好发射一次（同实例）")
    void agentContext_readableOnCommonPoolWorker_andConsumedOnce() throws Exception {
        AgentContext.SubagentContext sub = subCtx("req_worker", "spawn");
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-x").withAgentContext(sub);
        String testThread = Thread.currentThread().getName();

        // 真实无 executor 的 CompletableFuture ⇒ ForkJoinPool.commonPool worker
        CompletableFuture<String[]> worker = CompletableFuture.supplyAsync(() -> {
            Map<String, Object> attrs = new LinkedHashMap<>();
            AgentContext.attachInvokingRequestEdge(attrs, tuc.agentContext());
            return new String[]{Thread.currentThread().getName(),
                String.valueOf(attrs.get("invokingRequestId")),
                String.valueOf(attrs.get("invocationKind"))};
        });
        String[] first = worker.get(10, TimeUnit.SECONDS);

        assertThat(first[0])
            .as("必须在派生线程（commonPool worker）上执行——否则本条不覆盖真实缺陷路径")
            .isNotEqualTo(testThread);
        assertThat(first[1])
            .as("显式载体 ⇒ worker 上能读到 invokingRequestId（改前读 ThreadLocal 恒 null）")
            .isEqualTo("req_worker");
        assertThat(first[2]).isEqualTo("spawn");

        // sparse edge：同一 invocation 的第二个 terminal event 不再携带（CC agentContext.ts:159-161）
        Map<String, Object> secondAttrs = new LinkedHashMap<>();
        AgentContext.attachInvokingRequestEdge(secondAttrs, tuc.agentContext());
        assertThat(secondAttrs)
            .as("sparse edge 已消费 ⇒ 第二次不携带（同实例共享 invocationEmitted）")
            .doesNotContainKeys("invokingRequestId", "invocationKind");
    }

    @Test
    @DisplayName("[批 5b-1] 反面对照：按标量重建实例 ⇒ 边会重复发射（证明「必须同实例」非空话）")
    void rebuiltInstance_emitsEdgeAgain_sparseSemanticsRequiresSameInstance() {
        AgentContext.SubagentContext original = subCtx("req_rebuild", "spawn");
        // 消费一次（模拟主循环 modelRequest 那条路径）
        AgentContext.attachInvokingRequestEdge(new LinkedHashMap<>(), original);

        // 反面对照：字段逐字相同但**新实例**（= 若各消费点按 TUC 标量重建会发生的形态）
        AgentContext.SubagentContext rebuilt = subCtx("req_rebuild", "spawn");
        Map<String, Object> attrs = new LinkedHashMap<>();
        AgentContext.attachInvokingRequestEdge(attrs, rebuilt);

        assertThat(attrs)
            .as("重建实例 ⇒ 同一次 invocation 的归因边被重复发射（偏离 CC 的 sparse 语义）"
                + " —— 这正是载体必须传「同一实例」而不是标量副本的原因")
            .containsEntry("invokingRequestId", "req_rebuild");

        // 而同一实例的第二次消费不发射（上一条用例已断言；此处再锁一次对照）
        Map<String, Object> again = new LinkedHashMap<>();
        AgentContext.attachInvokingRequestEdge(again, original);
        assertThat(again).isEmpty();
    }
}
