package com.nexusai.application.agent;

import com.nexusai.application.agent.lsp.PromptCacheBreakDetection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-01 · QuerySource canonical 值域契约测试（映射侧归一单点，INV-18）。
 *
 * <p><b>WHY</b>: 生产传参侧（LlmAgentLoop）与匹配侧（main-thread 门 / 递归守卫 /
 * getTrackingKey / persist gate）的统一 canonical 值域必须单点可验证——大写
 * {@code name()} 与 CC 小写字面量不得再失配（S-3/S-12/V2-M1/V2-S5②/DRIFT-2 同根因家族）。
 */
class QuerySourceCcTest {

    @Test
    @DisplayName("canonical 映射: 全枚举对齐 CC 小写字面量值域")
    void canonical_mapsAllEnums_toCcLowerCaseValues() {
        assertThat(QuerySource.USER.canonical()).as("USER=主线程用户输入 → repl_main_thread（promptCategory.ts:41）")
            .isEqualTo("repl_main_thread");
        assertThat(QuerySource.REPL_MAIN_THREAD.canonical()).isEqualTo("repl_main_thread");
        assertThat(QuerySource.SDK.canonical()).isEqualTo("sdk");
        assertThat(QuerySource.COMPACT.canonical()).isEqualTo("compact");
        assertThat(QuerySource.SESSION_MEMORY.canonical()).isEqualTo("session_memory");
        assertThat(QuerySource.HOOK_AGENT.canonical()).isEqualTo("hook_agent");
        assertThat(QuerySource.EXTRACT_MEMORIES.canonical()).isEqualTo("extract_memories");
        assertThat(QuerySource.AUTO_DREAM.canonical()).isEqualTo("auto_dream");
        assertThat(QuerySource.SUBAGENT.canonical()).as("SUBAGENT 聚合占位 → agent: 前缀族（Java 近似，runAgent.ts:694）")
            .isEqualTo("agent:subagent");
        assertThat(QuerySource.FORK.canonical()).as("FORK → agent:builtin:fork（runAgent.ts:694/promptCategory.ts:23）")
            .isEqualTo("agent:builtin:fork");
        assertThat(QuerySource.MARBLE_ORIGAMI.canonical()).as("MARBLE_ORIGAMI → marble_origami（autoCompact.ts:180）")
            .isEqualTo("marble_origami");
        assertThat(QuerySource.WORKFLOW.canonical()).as("[Fix-D4] WORKFLOW → workflow（claudeCodeBackend.ts:304 runAgent 委托默认）")
            .isEqualTo("workflow");
    }

    @Test
    @DisplayName("canonicalize 归一: 大写 name() → 小写；小写/CC 风格幂等；null → null")
    void canonicalize_normalizesAndIdempotent() {
        assertThat(QuerySource.canonicalize("REPL_MAIN_THREAD")).isEqualTo("repl_main_thread");
        assertThat(QuerySource.canonicalize("SDK")).isEqualTo("sdk");
        assertThat(QuerySource.canonicalize("COMPACT")).isEqualTo("compact");
        assertThat(QuerySource.canonicalize("SESSION_MEMORY")).isEqualTo("session_memory");
        assertThat(QuerySource.canonicalize("MARBLE_ORIGAMI")).isEqualTo("marble_origami");
        assertThat(QuerySource.canonicalize("WORKFLOW")).isEqualTo("workflow");
        assertThat(QuerySource.canonicalize("FORK")).isEqualTo("agent:builtin:fork");
        // 小写既有值域幂等
        assertThat(QuerySource.canonicalize("repl_main_thread")).isEqualTo("repl_main_thread");
        assertThat(QuerySource.canonicalize("sdk")).isEqualTo("sdk");
        // CC 风格字符串原样（前缀匹配语义保持）
        assertThat(QuerySource.canonicalize("repl_main_thread:outputStyle:custom"))
            .isEqualTo("repl_main_thread:outputStyle:custom");
        assertThat(QuerySource.canonicalize("agent:builtin:fork")).isEqualTo("agent:builtin:fork");
        assertThat(QuerySource.canonicalize("compact")).isEqualTo("compact");
        // null 安全
        assertThat(QuerySource.canonicalize(null)).isNull();
        // 未知名原样（不猜测）
        assertThat(QuerySource.canonicalize("speculation")).isEqualTo("speculation");
    }

    @Test
    @DisplayName("fromString 解析: name() 与 canonical 双形态可逆")
    void fromString_acceptsBothForms() {
        assertThat(QuerySource.fromString("REPL_MAIN_THREAD")).isEqualTo(QuerySource.REPL_MAIN_THREAD);
        // "repl_main_thread" 与 USER/REPL_MAIN_THREAD canonical 同值（主线程），枚举序 USER 在前
        assertThat(QuerySource.fromString("repl_main_thread").canonical()).isEqualTo("repl_main_thread");
        assertThat(QuerySource.fromString("SDK")).isEqualTo(QuerySource.SDK);
        assertThat(QuerySource.fromString("sdk")).isEqualTo(QuerySource.SDK);
        assertThat(QuerySource.fromString("agent:builtin:fork")).isEqualTo(QuerySource.FORK);
        assertThat(QuerySource.fromString("FORK")).isEqualTo(QuerySource.FORK);
        assertThat(QuerySource.fromString("marble_origami")).isEqualTo(QuerySource.MARBLE_ORIGAMI);
        assertThat(QuerySource.fromString("workflow")).as("[Fix-D4] canonical 小写 'workflow' → WORKFLOW（守卫类别归一）")
            .isEqualTo(QuerySource.WORKFLOW);
        assertThat(QuerySource.fromString("WORKFLOW")).isEqualTo(QuerySource.WORKFLOW);
        assertThat(QuerySource.fromString("unknown-source")).isNull();
        assertThat(QuerySource.fromString(null)).isNull();
        // CC 风格变体（带 outputStyle 后缀）不在枚举映射内 → null（调用方按后台处理）
        assertThat(QuerySource.fromString("repl_main_thread:outputStyle:custom")).isNull();
    }

    @Test
    @DisplayName("[IMP2-05] effectiveValue 值域复活: agentType 级精确值优先（CC promptCategory.ts:16-28 → AgentTool.tsx:609）")
    void effectiveValue_prefersExactAgentTypeValue() {
        // WHY（规则九）: IMP2-05 把 subagent querySource 从聚合占位（agent:subagent）复活为
        // agentType 级精确值（CC promptCategory.ts:16-28 getQuerySourceForAgent → AgentTool.tsx:609
        // toolUseContext.options.querySource ?? getQuerySourceForAgent(...)）。effectiveValue 是
        // loop 发射侧唯一取用点（LlmAgentLoop:3830 ModelRequest.querySource 构建处），exactValue
        // 非 null 必须优先返回 —— 否则枚举 category.canonical() 会吞掉 agentType 区分度，
        // 遥测/持久化将无法区分是 Explore 还是 general-purpose 子 agent 在跑。
        assertThat(QuerySource.effectiveValue(QuerySource.SUBAGENT, "agent:builtin:Explore"))
            .as("内置 agent 精确值（agent:builtin:Explore）必须原样命中，不被 category.canonical 吞掉")
            .isEqualTo("agent:builtin:Explore");
        assertThat(QuerySource.effectiveValue(QuerySource.SUBAGENT, "agent:builtin:general"))
            .as("内置 general-purpose 精确值与 Explore 区分（agentType 级粒度，遥测可分辨）")
            .isEqualTo("agent:builtin:general");
        assertThat(QuerySource.effectiveValue(QuerySource.SUBAGENT, "agent:custom"))
            .as("自定义 agent 精确值恒 agent:custom（promptCategory.ts:26）")
            .isEqualTo("agent:custom");
        assertThat(QuerySource.effectiveValue(QuerySource.FORK, "agent:builtin:fork"))
            .as("fork 精确值与枚举 FORK.canonical() 字面量一致（守卫不变性，SubagentTool:1641 精确匹配）")
            .isEqualTo("agent:builtin:fork");
    }

    @Test
    @DisplayName("[IMP2-05] effectiveValue 回退: exactValue=null → category.canonical()（未接线聚合占位）")
    void effectiveValue_fallsBackToCanonicalWhenNull() {
        // WHY（向后兼容）: 未接线路径（主线程 / 旧调用方）querySourceValue=null，发射侧必须回退
        // category.canonical() —— SUBAGENT → 'agent:subagent'（聚合占位）、FORK → 'agent:builtin:fork'、
        // USER/REPL_MAIN_THREAD → 'repl_main_thread'。守卫消费侧（persist gate / autocompact 递归守卫 /
        // 529 / main-thread 判定）语义与 IMP2-01 完全一致（守卫消费枚举 canonical，不因精确化改变）。
        assertThat(QuerySource.effectiveValue(QuerySource.SUBAGENT, null)).isEqualTo("agent:subagent");
        assertThat(QuerySource.effectiveValue(QuerySource.FORK, null)).isEqualTo("agent:builtin:fork");
        assertThat(QuerySource.effectiveValue(QuerySource.USER, null)).isEqualTo("repl_main_thread");
        assertThat(QuerySource.effectiveValue(QuerySource.REPL_MAIN_THREAD, null)).isEqualTo("repl_main_thread");
        assertThat(QuerySource.effectiveValue(null, null)).isNull();
    }

    @Test
    @DisplayName("[收尾 IMP2-05] getTrackingKey 精确值命中: 聚合值 agent:subagent 不追踪；精确值 agent:builtin:<type>/agent:custom/agent:default 命中 TRACKED_SOURCE_PREFIXES → 开始追踪子代理 cache-break（向 CC 收拢）")
    void getTrackingKey_exactSubagentValue_hitsTrackedPrefixes() {
        // WHY（规则九 · 可观察行为变化需锁定）: IMP2-05 前发射侧（LlmAgentLoop ModelRequest.querySource
        //   经 canonical）对子代理产出聚合值 'agent:subagent' → PromptCacheBreakDetection.
        //   TRACKED_SOURCE_PREFIXES（repl_main_thread / sdk / agent:custom / agent:default /
        //   agent:builtin）无一命中 → getTrackingKey 返回 null → 子代理 cache-break 不追踪。
        //   IMP2-05 后发射侧经 effectiveValue 产出精确值 'agent:builtin:<type>' / 'agent:custom' /
        //   'agent:default' → 命中前缀 → 开始追踪（对齐 CC：CC 的 querySource 本就是精确值，
        //   promptCategory.ts:16-28 + AgentTool.tsx:609 + runAgent.ts:694）。此为向 CC 收拢的
        //   可观察行为变化（非回归），必须测试锁定：聚合值不追踪 + 精确值追踪，缺一不可——
        //   防止未来发射侧回退聚合值（或守卫改吃 canonical）时静默丢子代理 cache-break 追踪。
        String agentId = "agent-123";

        // ① 聚合占位值（IMP2-05 前发射侧值）→ 不命中 TRACKED_SOURCE_PREFIXES → 不追踪
        assertThat(PromptCacheBreakDetection.getTrackingKey(
                QuerySource.SUBAGENT.canonical(), agentId))
            .as("聚合值 agent:subagent 不在 TRACKED_SOURCE_PREFIXES（repl_main_thread/sdk/agent:custom/agent:default/agent:builtin）→ 不追踪")
            .isNull();

        // ② 精确值（IMP2-05 后发射侧值）→ 命中前缀 → 开始追踪（向 CC 收拢）
        assertThat(PromptCacheBreakDetection.getTrackingKey("agent:builtin:Explore", agentId))
            .as("agent:builtin:<type> 命中 'agent:builtin' 前缀 → 追踪（向 CC 收拢）")
            .isEqualTo(agentId);
        assertThat(PromptCacheBreakDetection.getTrackingKey("agent:custom", agentId))
            .as("agent:custom 命中 'agent:custom' 前缀 → 追踪")
            .isEqualTo(agentId);
        assertThat(PromptCacheBreakDetection.getTrackingKey("agent:default", agentId))
            .as("agent:default 命中 'agent:default' 前缀 → 追踪")
            .isEqualTo(agentId);
        assertThat(PromptCacheBreakDetection.getTrackingKey("agent:builtin:fork", agentId))
            .as("agent:builtin:fork 命中 'agent:builtin' 前缀 → 追踪（fork 子代理同样纳入）")
            .isEqualTo(agentId);

        // ③ 精确值经发射侧 effectiveValue 真实产出（非测试臆造）——链：SUBAGENT 枚举 + 精确值 → 命中
        assertThat(PromptCacheBreakDetection.getTrackingKey(
                QuerySource.effectiveValue(QuerySource.SUBAGENT, "agent:builtin:Explore"), agentId))
            .as("effectiveValue 发射侧真实输出（agent:builtin:Explore）→ getTrackingKey 命中 → 追踪")
            .isEqualTo(agentId);
        //    聚合回退（querySourceValue=null）→ 不追踪（与 ① 一致，锁「回退即丢追踪」语义）
        assertThat(PromptCacheBreakDetection.getTrackingKey(
                QuerySource.effectiveValue(QuerySource.SUBAGENT, null), agentId))
            .as("effectiveValue 聚合回退（agent:subagent）→ 不追踪（锁回退即丢追踪，防发射侧退化）")
            .isNull();
    }
}
