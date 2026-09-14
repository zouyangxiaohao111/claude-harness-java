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
            SubagentExecutor.stampSubagentLoopContext(subagentCtx, sub, "worker", false, null);

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
        assertThat(bare.teammateIdentity()).isNull();
    }

    @Test
    @DisplayName("跨线程可见：盖章后的 agentContext 在 commonPool worker 上可读且发射归因边")
    void stampedCarrier_isReadableOnCommonPoolWorker() throws Exception {
        // WHY：盖章的**唯一目的**就是让 commonPool worker 读到（plain ThreadLocal 不跨线程）。
        //   只断言「字段有值」不覆盖该目的；本用例在真实派生线程上消费一次，闭合「盖章 → 跨线程可用」。
        AgentContext.SubagentContext sub = subCtx("req_stamp_worker");
        ToolUseContext stamped = SubagentExecutor.stampSubagentLoopContext(
            ToolUseContext.of(UUID.randomUUID(), "sess-child"), sub, "Explore", true, null);
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
    @DisplayName("[S1-T2b] teammate 身份同点盖章；非 teammate（null）不得伪造身份")
    void stamp_carriesTeammateIdentity_andDoesNotFabricate() {
        // WHY（规则九）：teammate 身份与 subagentName/agentContext 同点是**唯一生产盖章点**
        //   （SubagentExecutor Step 20 TUC 定稿步）。取值链 = teammate 的
        //   InProcessTeammateTaskState.identity() → AutonomousAgentLoop.runOneTurn 的 per-call 形参
        //   → 本缝。断言两条：① 传值必盖（否则工具池线程读 null，Cron*/SendMessage/TaskUpdate 的
        //   teammate 分支静默退化）；② 不传（null）不得伪造（普通 Agent-tool 子代理 / hook agent
        //   路径不得被当成 teammate —— 失败方向取「身份缺失」而非「身份归到别人」）。
        com.nexusai.application.agent.team.TeammateIdentity identity =
            new com.nexusai.application.agent.team.TeammateIdentity(
                "alice@team-x", "alice", "team-x", null, false, "sess-parent");

        ToolUseContext stamped = SubagentExecutor.stampSubagentLoopContext(
            ToolUseContext.of(UUID.randomUUID(), "sess-child"), null, "general-purpose", true, identity);

        assertThat(stamped.teammateIdentity())
            .as("teammate 身份必须被盖章（同一实例，不入 copy 语义）").isSameAs(identity);
        assertThat(stamped.subagentName())
            .as("身份盖章不影响 subagentName 同点盖章").isEqualTo("general-purpose");
        // 反向对照：不传身份时不得凭空造一个
        assertThat(SubagentExecutor.stampSubagentLoopContext(
                ToolUseContext.of(UUID.randomUUID(), "sess-child"), null, "general-purpose", true, null)
                .teammateIdentity())
            .as("非 teammate 路径不得伪造 teammate 身份").isNull();
    }

    @Test
    @DisplayName("null agentContext：身份仍盖章，agent 载体为空（非 agent 上下文，不得伪造）")
    void nullAgentContext_stillStampsIdentity() {
        // WHY（缺值策略 (b) 类边界）：SubagentExecutor 的调用点恒传非 null（buildSubagentAgentContext
        //   返回值），但缝本身必须定义 null 语义且**不得伪造**一个空对象（那会让下游以为有归因上下文）。
        ToolUseContext stamped = SubagentExecutor.stampSubagentLoopContext(
            ToolUseContext.of(UUID.randomUUID(), "sess-child"), null, "Explore", true, null);

        assertThat(stamped.subagentName()).isEqualTo("Explore");
        assertThat(stamped.agentContext()).isNull();
    }
}
