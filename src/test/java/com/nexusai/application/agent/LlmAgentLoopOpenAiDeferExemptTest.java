package com.nexusai.application.agent;

import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LlmAgentLoop.exemptAllDeferredForOpenAi 通用懒加载豁免测试（openai-defer-exempt 2026-09-08）。
 *
 * <p>WHY（CLAUDE.md 规则 9 + 用户拍板）：defer 工具的前提 = 模型能经 ToolSearch/tool_reference
 * 激活被剔工具（CC anthropic 语义）；openai 兼容模型（deepseek/fz/moonshot）无 tool_reference，
 * 被 {@code filterToolsForSchema} 剔出初始 schema 后不自知存在、也不主动搜索 → 对模型"工具不存在"。
 * 逐工具白名单豁免（vision/WebSearch/SendMessage）曾漏网 EnterWorktree/ExitWorktree（shouldDefer=true
 * 对齐 CC）→ 用户实测叫它建 worktree，它用 bash {@code git worktree add} 兜底。本豁免通用化：
 * <ul>
 *   <li>mapper null（4 参旧签名无法判 provider）→ <b>不豁免</b>（deferred 保留，旧契约不变，
 *       同 WebSearch 语义，区别于 vision 保守直发）；</li>
 *   <li>非 anthropic（openai_compatible/openai_sdk/未来 response）→ <b>清空整个 deferred</b>
 *       （所有 shouldDefer 工具 schema 直发，含 EnterWorktree/ExitWorktree）；</li>
 *   <li>anthropic → 保留懒加载（tool_reference 激活，对齐 CC）；</li>
 *   <li>deferred null / 空 → no-op 不抛。</li>
 * </ul>
 */
class LlmAgentLoopOpenAiDeferExemptTest {

    private ModelMapper modelMapper;
    private ProviderMapper providerMapper;

    /** 模型全名路径可解析（providers 前缀命中 → models 命中 m1）。 */
    private void stubResolvableModel() {
        modelMapper = mock(ModelMapper.class);
        providerMapper = mock(ProviderMapper.class);
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName("deepseek-v4-flash");
        m.setEnabled(true);
        when(modelMapper.selectOneByQuery(any())).thenReturn(m);
    }

    /** deferred 集合含 ToolSearch + worktree 两工具（回归锚点：它们 shouldDefer=true 对齐 CC）。 */
    private static Set<String> deferredWithWorktree() {
        Set<String> s = new LinkedHashSet<>();
        s.add("ToolSearch");
        s.add(ToolNameConstants.ENTER_WORKTREE_TOOL_NAME);
        s.add(ToolNameConstants.EXIT_WORKTREE_TOOL_NAME);
        s.add(ToolNameConstants.VISION_ANALYZE_TOOL_NAME);
        s.add(ToolNameConstants.WEB_SEARCH_TOOL_NAME);
        return s;
    }

    @Test
    @DisplayName("provider.type=openai_compatible（deepseek 非 anthropic）→ 清空整个 deferred（EnterWorktree/ExitWorktree schema 直发）")
    void openAiCompatible_clearsAllDeferred() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_compatible");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = deferredWithWorktree();
        LlmAgentLoop.exemptAllDeferredForOpenAi(deferred, modelMapper, providerMapper, "deepseek/deepseek-v4-flash");

        assertThat(deferred)
            .as("openai 系无 tool_reference，被剔工具对模型不存在 → 所有 shouldDefer 工具必须进初始 "
                + "schema（回归锚点：EnterWorktree 不可见时模型退 bash git worktree add）")
            .isEmpty();
    }

    @Test
    @DisplayName("provider.type=openai_sdk（官方 SDK，未来 response 同族非 anthropic）→ 同样清空")
    void openAiSdk_clearsAllDeferred() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_sdk");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = deferredWithWorktree();
        LlmAgentLoop.exemptAllDeferredForOpenAi(deferred, modelMapper, providerMapper, "openai/gpt-5");

        assertThat(deferred)
            .as("openai_sdk 同样非 anthropic → 清空（未来 response 接入 type 也非 anthropic，天然覆盖）")
            .isEmpty();
    }

    @Test
    @DisplayName("provider.type=anthropic → deferred 全部保留（tool_reference 激活，对齐 CC 省 token）")
    void anthropic_keepsAllDeferred() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("anthropic");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = deferredWithWorktree();
        LlmAgentLoop.exemptAllDeferredForOpenAi(deferred, modelMapper, providerMapper, "anthropic/claude-sonnet-4-6");

        assertThat(deferred)
            .as("anthropic 有 tool_reference 能激活 → 保留懒加载省 token（对齐 CC 不偏离）")
            .containsExactlyInAnyOrder(
                "ToolSearch",
                ToolNameConstants.ENTER_WORKTREE_TOOL_NAME,
                ToolNameConstants.EXIT_WORKTREE_TOOL_NAME,
                ToolNameConstants.VISION_ANALYZE_TOOL_NAME,
                ToolNameConstants.WEB_SEARCH_TOOL_NAME);
    }

    @Test
    @DisplayName("2026-09-11 单点化：anthropic + haiku → 判据含模型那一半 → 清空（修掉旧实现只判 provider 的漏清）")
    void anthropicHaiku_clearsAllDeferred() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("anthropic");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = deferredWithWorktree();
        LlmAgentLoop.exemptAllDeferredForOpenAi(
            deferred, modelMapper, providerMapper, "anthropic/claude-haiku-4-5-20251001");

        assertThat(deferred)
            .as("WHY（规则 9）：haiku 不解析 tool_reference（toolSearch.ts:200-204）→ tool_reference 不可用 "
                + "→ 全体 schema 直发。旧实现只判 provider（isAnthropic=true → return）→ 漏清：ToolSearch "
                + "留在 deferred 且仍给 haiku 发 tool_reference（跨尺度不一致）。"
                + "变异：判据回退 ContextUsageCalculator.isAnthropic(...) → 本用例变红。")
            .isEmpty();
    }

    @Test
    @DisplayName("anthropic + claude-opus-4 → tool_reference 可用 → deferred 全部保留（模型那一半成立）")
    void anthropicOpus_keepsAllDeferred() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("anthropic");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = deferredWithWorktree();
        LlmAgentLoop.exemptAllDeferredForOpenAi(
            deferred, modelMapper, providerMapper, "anthropic/claude-opus-4");

        assertThat(deferred)
            .as("anthropic × 非 haiku 模型 → 两半均成立 → 保留懒加载省 token（对齐 CC，不因单点化而过度清空）")
            .containsExactlyInAnyOrder(
                "ToolSearch",
                ToolNameConstants.ENTER_WORKTREE_TOOL_NAME,
                ToolNameConstants.EXIT_WORKTREE_TOOL_NAME,
                ToolNameConstants.VISION_ANALYZE_TOOL_NAME,
                ToolNameConstants.WEB_SEARCH_TOOL_NAME);
    }

    @Test
    @DisplayName("mapper null（4 参旧签名无法判 provider）→ 不豁免，deferred 保留（旧契约不变）")
    void mapperNull_keepsDeferred() {
        Set<String> deferred = deferredWithWorktree();
        LlmAgentLoop.exemptAllDeferredForOpenAi(deferred, null, null, "deepseek-chat");
        assertThat(deferred)
            .as("mapper null 无法判定 provider → 保持既有懒加载（区别于 vision 豁免的保守剔除方向，"
                + "同 WebSearch 语义：4 参旧签名契约不变）")
            .containsExactlyInAnyOrder(
                "ToolSearch",
                ToolNameConstants.ENTER_WORKTREE_TOOL_NAME,
                ToolNameConstants.EXIT_WORKTREE_TOOL_NAME,
                ToolNameConstants.VISION_ANALYZE_TOOL_NAME,
                ToolNameConstants.WEB_SEARCH_TOOL_NAME);
    }

    @Test
    @DisplayName("deferred 空集 → no-op（装配空工具集安全）")
    void emptyDeferred_noop() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_compatible");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = new LinkedHashSet<>();
        LlmAgentLoop.exemptAllDeferredForOpenAi(deferred, modelMapper, providerMapper, "deepseek/deepseek-v4-flash");
        assertThat(deferred).isEmpty();
    }

    @Test
    @DisplayName("deferred null → 不抛（装配容忍）")
    void nullDeferred_noThrow() {
        stubResolvableModel();
        LlmAgentLoop.exemptAllDeferredForOpenAi(null, modelMapper, providerMapper, "deepseek/deepseek-v4-flash");
    }
}
