package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [欠账清理批 · 补写入端覆盖] {@link SubagentExecutor#stampSubagentLoopContext} 的
 * <b>产出端</b>契约测试。
 *
 * <p><b>WHY 必须单独存在（批 5b-1 诚实申报的覆盖缺口）</b>：批 5b-1 的 8 条测试全部覆盖
 * <b>消费端</b>（TUC 的派生链是否透传 / commonPool worker 是否读得到 / sparse-edge 是否只发一次），
 * 而<b>唯一生产盖章点</b>（{@code executeStreaming} 的 TUC 定稿步）没有直接测试 ——
 * 「删掉那一行不会有测试变红」。这正是本仓反复出现的「<b>只覆盖一侧</b>」失效模式
 * （写入端与读取端必须都有断言；两侧只有一侧时，独立变异必然暴露）。
 *
 * <p>本类补上写入端。判据取自 {@code executeStreaming} 的实际盖章语句，抽成
 * {@link SubagentExecutor#stampSubagentLoopContext} 静态缝（本类既有惯例 Pattern #14，
 * 见 {@code withEffectiveCwd} 的同款理由：避免起整条 LLM 循环夹具）。
 *
 * <p><b>鉴别力</b>：变异（删掉缝里任一盖章调用）⇒ 本类精确红；接线本身由编译器保证
 * （{@code ctxForLoop} 在下方 lambda 中被使用，删掉赋值行编译不过）。
 * 反向对照见 {@link #unstampedTuc_hasNeitherCarrier()}（证明断言来自盖章动作而非 TUC 默认值）。
 */
class SubagentLoopContextStampTest {

    /** 与生产 {@code buildSubagentAgentContext} 同形（agentIdHex / parentSessionId / name / builtIn / reqId / kind）。 */
    private static AgentContext.SubagentContext subCtx(String invokingRequestId) {
        return new AgentContext.SubagentContext(
            "a0123456789abcdef", "sess-parent", "Explore", true, invokingRequestId, "spawn");
    }

    @Test
    @DisplayName("盖章：TUC 定稿步一次盖齐身份（subagentName/isBuiltIn）+ agent 归因上下文（同实例）")
    void stamp_carriesBothCarriers() {
        AgentContext.SubagentContext sub = subCtx("req_stamp_1");
        ToolUseContext subagentCtx = ToolUseContext.of(UUID.randomUUID(), "sess-child");

        ToolUseContext stamped =
            SubagentExecutor.stampSubagentLoopContext(subagentCtx, sub, "worker", false);

        assertThat(stamped.subagentName())
            .as("hook 侧归因载体（SessionFileAccessHooks.subagentProps 经 HOOK_EXECUTOR 取用）")
            .isEqualTo("worker");
        assertThat(stamped.isBuiltIn())
            .as("与 subagentName 同点盖章（CC agent.source==='built-in'）")
            .isFalse();
        assertThat(stamped.agentContext())
            .as("classifier / exec-prompt hook / tool-use summary 侧归因载体；"
                + "必须是**同一实例**（sparse-edge 靠共享的 invocationEmitted 标记）")
            .isSameAs(sub);
    }

    @Test
    @DisplayName("反向对照：未盖章的 TUC 两个载体皆空 ⇒ 上面断言来自盖章动作，不是 of() 的默认值")
    void unstampedTuc_hasNeitherCarrier() {
        // WHY（规则九）：本条是上一条的**正向对照**。没有它，一个「什么都不做、直接返回入参」的实现
        //   也能让上一条过吗？不能（断言值不匹配）—— 但「of() 恰好已带默认值」这类夹具陷阱必须有
        //   显式反证，否则无法区分「盖章生效」与「夹具本来就带值」。
        ToolUseContext bare = ToolUseContext.of(UUID.randomUUID(), "sess-child");

        assertThat(bare.subagentName()).isNull();
        assertThat(bare.agentContext()).isNull();
    }

    @Test
    @DisplayName("跨线程可见：盖章后的 agentContext 在 commonPool worker 上可读且发射归因边")
    void stampedCarrier_isReadableOnCommonPoolWorker() throws Exception {
        // WHY：盖章的**唯一目的**就是让 commonPool worker 读到（plain ThreadLocal 不跨线程）。
        //   只断言「字段有值」不覆盖该目的；本用例在真实派生线程上消费一次，闭合「盖章 → 跨线程可用」。
        AgentContext.SubagentContext sub = subCtx("req_stamp_worker");
        ToolUseContext stamped = SubagentExecutor.stampSubagentLoopContext(
            ToolUseContext.of(UUID.randomUUID(), "sess-child"), sub, "Explore", true);
        String testThread = Thread.currentThread().getName();

        String[] seen = CompletableFuture.supplyAsync(() -> {
            Map<String, Object> attrs = new java.util.LinkedHashMap<>();
            AgentContext.attachInvokingRequestEdge(attrs, stamped.agentContext());
            return new String[]{Thread.currentThread().getName(),
                String.valueOf(attrs.get("invokingRequestId"))};
        }).get(10, TimeUnit.SECONDS);

        assertThat(seen[0])
            .as("必须在派生线程（commonPool worker）上执行，否则本用例不覆盖真实缺陷路径")
            .isNotEqualTo(testThread);
        assertThat(seen[1])
            .as("盖章值跨线程可读（改前读 AgentContext.STORAGE ThreadLocal 恒 null）")
            .isEqualTo("req_stamp_worker");
    }

    @Test
    @DisplayName("null agentContext：身份仍盖章，agent 载体为空（非 agent 上下文，不得伪造）")
    void nullAgentContext_stillStampsIdentity() {
        // WHY（缺值策略 (b) 类边界）：SubagentExecutor 的调用点恒传非 null（buildSubagentAgentContext
        //   返回值），但缝本身必须定义 null 语义且**不得伪造**一个空对象（那会让下游以为有归因上下文）。
        ToolUseContext stamped = SubagentExecutor.stampSubagentLoopContext(
            ToolUseContext.of(UUID.randomUUID(), "sess-child"), null, "Explore", true);

        assertThat(stamped.subagentName()).isEqualTo("Explore");
        assertThat(stamped.agentContext()).isNull();
    }
}
