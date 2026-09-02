package com.nexusai.application.agent.skillsearch;

import com.nexusai.application.agent.loop.FeatureFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C-30] skill-search 子系统 7 模块架构骨架测试（RED→GREEN）。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>feature-off → start 恒 null / collect 恒空 / isSkillSearchEnabled=false /
 *       DiscoverSkillsTool.isEnabled=false</b> — 对齐 CC flag-off 时 {@code skillPrefetch===null} /
 *       {@code skillSearchFeatureCheck===null} / {@code DISCOVER_SKILLS_TOOL_NAME===null}
 *       （query.ts:66 / prompts.ts:95-99 / prompts.ts:90-93），{@code ?.()} 短路 → 生产行为零变化。</li>
 *   <li><b>feature-on（构造注入 enabled）→ 可观测 wiring</b> — start 返回非空占位句柄、collect 仍恒空集
 *       （不伪造发现结果，参考 CS-DEL-1 前科）；验证消费点在 LlmAgentLoop 存在（CC query.ts:331-335/:1620-1628），
 *       防止"假骨架"（只有类没有接线）。</li>
 *   <li><b>skill_discovery 渲染空 skills → 不注入</b> — CC messages.ts:3507 {@code skills.length===0 return []}，
 *       占位 collect 恒空集保证渲染层不注入任何东西。</li>
 * </ol>
 *
 * <p><b>RED→GREEN</b>: 实施前 LlmAgentLoop 0 命中 start/collect 消费点（RED）→ 实施后消费点存在（GREEN）。
 */
class SkillSearchSkeletonTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    @Test
    @DisplayName("feature-off: startSkillDiscoveryPrefetch 返回 null（CC query.ts:66 skillPrefetch===null ?.() 短路）")
    void featureOff_startReturnsNull() {
        // WHY: CC flag 关闭时 skillPrefetch===null（query.ts:66），调用点 ?.() 恒短路。
        //   占位 Default 默认 enabled=false → start 必须返回 null，LlmAgentLoop collect 分支不执行。
        SkillSearchPrefetch.Default prefetch = new SkillSearchPrefetch.Default();

        assertThat(prefetch.startSkillDiscoveryPrefetch(null, List.of(), null))
            .as("feature-off → start 恒 null（对齐 CC ?.() 短路）")
            .isNull();
    }

    @Test
    @DisplayName("feature-on（构造注入）: start 返回非空句柄但 collect 仍恒空集（可观测 wiring，不伪造发现结果）")
    void featureOn_wiringObservable() {
        // WHY: enabled=true 时 start 返回占位句柄使 LlmAgentLoop wiring 可观测；collect 仍恒空集
        //   —— 无真实技能搜索索引，绝不伪造发现结果（CS-DEL-1 前科）。真实发现待上游 prefetch.ts 补充。
        SkillSearchPrefetch.Default prefetch = new SkillSearchPrefetch.Default(true);
        SkillSearchPrefetch.PrefetchHandle handle =
            prefetch.startSkillDiscoveryPrefetch("user input", List.of(), null);

        assertThat(handle)
            .as("feature-on → start 返回非空占位句柄（wiring 可观测）")
            .isNotNull();
        assertThat(prefetch.collectSkillDiscoveryPrefetch(handle))
            .as("feature-on → collect 仍恒空集（不伪造发现结果）")
            .isEmpty();
        assertThat(prefetch.getTurnZeroSkillDiscovery("input", List.of(), null))
            .as("turn-0 发现占位恒空（CC attachments.ts:806-810，不伪造发现行为）")
            .isEmpty();
    }

    @Test
    @DisplayName("feature-off: isSkillSearchEnabled()=false（CC flag-off skillSearchFeatureCheck===null 短路）")
    void featureOff_isSkillSearchEnabledFalse() {
        // WHY: CC flags 关闭时 skillSearchFeatureCheck===null（prompts.ts:95-99），
        //   attachments.ts:2694 {?}.isSkillSearchEnabled() 短路。占位必须返回 false。
        assertThat(new SkillSearchFeatureCheck.Default().isSkillSearchEnabled())
            .as("feature-off → isSkillSearchEnabled=false（对齐 CC feature-off）")
            .isFalse();
    }

    @Test
    @DisplayName("DiscoverSkillsTool.isEnabled() 委托 feature flag（CC prompts.ts:90-93 flag-off → 工具 null）")
    void discoverSkillsTool_isEnabledDelegatesToFlag() {
        // WHY: CC DISCOVER_SKILLS_TOOL_NAME = feature(...) ? require(...).name : null（prompts.ts:90-93）。
        //   工具 isEnabled 必须委托 EXPERIMENTAL_SKILL_SEARCH 映射（FeatureFlags.skillPrefetch）。
        DiscoverSkillsTool off = new DiscoverSkillsTool(FeatureFlags.ALL_DISABLED);
        assertThat(off.isEnabled()).as("flag-off → DiscoverSkillsTool.isEnabled()=false").isFalse();
        assertThat(off.name()).as("DISCOVER_SKILLS_TOOL_NAME 占位常量必须暴露（真实值待上游 prompt.js 补充）")
            .isEqualTo(DiscoverSkillsTool.DISCOVER_SKILLS_TOOL_NAME);
        DiscoverSkillsTool on = new DiscoverSkillsTool(new FeatureFlags(true, true, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false));
        assertThat(on.isEnabled()).as("flag-on → DiscoverSkillsTool.isEnabled()=true（EXPERIMENTAL_SKILL_SEARCH 映射）").isTrue();
    }

    @Test
    @DisplayName("C-30 消费点接线存在：LlmAgentLoop 含 start/collect（feature-gated，CC query.ts:331-335/:1620-1628）")
    void consumptionPointsWiredInLlmAgentLoop() throws Exception {
        // WHY: 防止"假骨架" —— 类存在但没接线。C-30 消费点必须真实挂在 LlmAgentLoop
        //   （CC query.ts:331-335 start / :1620-1628 collect），否则骨架是死代码。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("LlmAgentLoop 必须含 skillSearchPrefetch().startSkillDiscoveryPrefetch( 消费点（feature-gated）")
            .contains("skillSearchPrefetch().startSkillDiscoveryPrefetch(");
        assertThat(source)
            .as("LlmAgentLoop 必须含 skillSearchPrefetch().collectSkillDiscoveryPrefetch( 消费点（feature-gated）")
            .contains("skillSearchPrefetch().collectSkillDiscoveryPrefetch(");
    }
}
