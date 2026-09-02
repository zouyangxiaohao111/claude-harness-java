package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [X3 部分删除 → C-30 完整引入] SkillDiscoveryPrefetch 索引清除契约宿主测试（P3-5）。
 *
 * <p><b>X3 处置（2026-08-04 收尾）</b>: 原零行为发现骨架（startSkillDiscoveryPrefetch /
 * collectSkillDiscoveryPrefetch，CC query.ts:331-335/:1620-1628，collect 恒返回空 list）已随
 * X3 整段删除 —— 本类保留为 P3-5 {@code clearSkillIndexCache} 索引清除宿主（P3-5 已把本类接入
 * SkillRegistry.refresh() / McpToolPool list_changed 处理器，整类删除会破坏接线）。
 *
 * <p><b>C-30 取代（2026-08-05）</b>: X3『条件删除前提（不引入则删骨架）』已被用户决策『C-30 完整
 * 引入 skill-search 子系统 7 模块架构骨架』取代 —— LlmAgentLoop 消费点重挂到新包
 * {@code com.nexusai.application.agent.skillsearch.SkillSearchPrefetch}
 * （startSkillDiscoveryPrefetch / collectSkillDiscoveryPrefetch，feature-gated，占位恒 null/空 → 生产零变化）。
 * 本测试第三项 {@code loopWiringC30ConsumptionPoints_gateRetained} 由「0 命中」改判为「消费点存在 + feature-gated」。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>flag=on → clearSkillIndexCache 被触发（计数 +1）</b> — CC flag 开启时 clearSkillIndexCache
 *       是真实函数（useManageMCPConnections.ts:27-30），list_changed / clearCommandMemoizationCaches
 *       调用点 ?.() 会真正触发。Java 无真实索引（concern #30）仍须让调用可观测，否则挂钩静默空转。</li>
 *   <li><b>flag=off → no-op（计数保持 0）</b> — CC flag 关闭时 clearSkillIndexCache===undefined，
 *       {@code ?.()} 短路，Java enabled=false 必须不触碰任何状态。</li>
 *   <li><b>C-30 消费点复验</b> — LlmAgentLoop 含新包 SkillSearchPrefetch 的 startSkillDiscoveryPrefetch /
 *       collectSkillDiscoveryPrefetch 调用点（feature-gated），A8 门控仍检查 featureFlags().skillPrefetch()。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert {@link SkillDiscoveryPrefetch}（enabled 不生效 / 删 clearCount）→ 本测试必须 fail。
 */
class SkillDiscoveryPrefetchTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    @Test
    @DisplayName("P3-5 flag=off → clearSkillIndexCache no-op（计数保持 0，对齐 CC flag-off ?.() 短路）")
    void flagOff_clearSkillIndexCache_noOp() {
        // WHY: CC flag 关闭时 clearSkillIndexCache===undefined（useManageMCPConnections.ts:27-30 /
        //   commands.ts:96-99），调用点 ?.() 恒 no-op。Java enabled=false 必须不触碰任何状态、
        //   计数保持 0 —— 若误触发（计数 +1 或抛异常），本测试 fail。
        SkillDiscoveryPrefetch sp = new SkillDiscoveryPrefetch(false);

        sp.clearSkillIndexCache();

        assertThat(sp.clearCount())
            .as("flag=off → clearSkillIndexCache 计数必须保持 0（no-op）")
            .isZero();
    }

    @Test
    @DisplayName("P3-5 flag=on → clearSkillIndexCache 被触发（计数 +1，结构就位待填充）")
    void flagOn_clearSkillIndexCache_triggered() {
        // WHY: CC flag 开启时 clearSkillIndexCache 是真实函数（useManageMCPConnections.ts:27-30），
        //   list_changed / clearCommandMemoizationCaches 调用点 ?.() 会真正触发。Java 无真实索引
        //   （concern #30）仍须让调用可观测（计数 +1），否则挂钩静默空转无法验证 wiring。
        SkillDiscoveryPrefetch sp = new SkillDiscoveryPrefetch(true);

        sp.clearSkillIndexCache();

        assertThat(sp.clearCount())
            .as("flag=on → clearSkillIndexCache 计数必须 +1（索引清除挂钩被触发）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("C-30 消费点存在 + feature-gated：LlmAgentLoop 含 start/collect 调用点（skillsearch 新包），A8 门控保留 skillPrefetch flag 检查")
    void loopWiringC30ConsumptionPoints_gateRetained() throws Exception {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        // WHY: X3『条件删除前提（不引入则删骨架）』被 C-30『完整引入』取代 —— LlmAgentLoop 消费点
        //   重挂到新包 SkillSearchPrefetch（CC query.ts:331-335/:1620-1628 契约），占位恒 null/空。
        //   若 C-30 骨架被误删 / 消费点丢失，本测试必须 fail（验证消费点确实存在，避免"假骨架"）。
        assertThat(source)
            .as("C-30 后 LlmAgentLoop 必须含 startSkillDiscoveryPrefetch 消费点（CC query.ts:331-335，feature-gated）")
            .contains("skillSearchPrefetch().startSkillDiscoveryPrefetch(");
        assertThat(source)
            .as("C-30 后 LlmAgentLoop 必须含 collectSkillDiscoveryPrefetch 消费点（CC query.ts:1620-1628，feature-gated）")
            .contains("skillSearchPrefetch().collectSkillDiscoveryPrefetch(");
        // A8 filterToBundledAndMcp 门控保留（P3-5 concern #2，attachments.ts:2692-2697）+ C-30 短路门控
        assertThat(source)
            .as("LlmAgentLoop A8 门控必须检查 featureFlags().skillPrefetch()（P3-5 保留）")
            .contains("ctx.featureFlags().skillPrefetch()");
    }
}
