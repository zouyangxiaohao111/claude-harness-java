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
 * LlmAgentLoop.exemptWebSearchDeferForOpenAi 豁免行为测试（websearch-openai-alwaysload 2026-09-04）。
 *
 * <p>WHY（CLAUDE.md 规则 9 + 用户拍板）：WebSearch/WebFetch 工具侧 shouldDefer=true（对齐 CC
 * WebSearchTool.ts:156 / WebFetchTool.ts:71）——anthropic 有 tool_reference 能经 ToolSearch 激活，
 * 懒加载省 token；但 openai 系（deepseek 无 tool_reference）模型误判「没有 WebSearch」白派 agent。
 * 本豁免在装配层按主模型 provider 判定：
 * <ul>
 *   <li>mapper null（4 参旧签名无法判 provider）→ <b>不豁免</b>（deferred 保留，旧契约不变）；</li>
 *   <li>非 anthropic（openai_compatible/openai_sdk/未来 response）→ WebSearch/WebFetch 从 deferred
 *       移除 → 恒在初始 schema；</li>
 *   <li>anthropic → 保留懒加载（tool_reference 激活）；</li>
 *   <li>deferred 不含 web 工具 → no-op；deferred null → 不抛。</li>
 * </ul>
 */
class LlmAgentLoopWebSearchDeferExemptTest {

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

    private static Set<String> deferredWithWeb() {
        Set<String> s = new LinkedHashSet<>();
        s.add("ToolSearch");
        s.add(ToolNameConstants.WEB_SEARCH_TOOL_NAME);
        s.add(ToolNameConstants.WEB_FETCH_TOOL_NAME);
        return s;
    }

    @Test
    @DisplayName("provider.type=openai_compatible（deepseek 非 anthropic）→ WebSearch/WebFetch 从 deferred 移除（恒在初始 schema）")
    void openAiCompatible_removesWebToolsFromDeferred() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_compatible");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = deferredWithWeb();
        LlmAgentLoop.exemptWebSearchDeferForOpenAi(deferred, modelMapper, providerMapper, "deepseek/deepseek-v4-flash");

        assertThat(deferred)
            .as("openai 系无 tool_reference → WebSearch/WebFetch 必须进初始 schema，防模型误判无工具白派 agent")
            .doesNotContain(ToolNameConstants.WEB_SEARCH_TOOL_NAME)
            .doesNotContain(ToolNameConstants.WEB_FETCH_TOOL_NAME)
            .contains("ToolSearch");
    }

    @Test
    @DisplayName("provider.type=openai_sdk（官方 SDK，未来 response 同族非 anthropic）→ 同样移除")
    void openAiSdk_removesWebToolsFromDeferred() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_sdk");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = deferredWithWeb();
        LlmAgentLoop.exemptWebSearchDeferForOpenAi(deferred, modelMapper, providerMapper, "openai/gpt-5");

        assertThat(deferred)
            .as("openai_sdk 同样非 anthropic → 移除（未来 response 接入 type 也非 anthropic，天然覆盖）")
            .doesNotContain(ToolNameConstants.WEB_SEARCH_TOOL_NAME)
            .doesNotContain(ToolNameConstants.WEB_FETCH_TOOL_NAME);
    }

    @Test
    @DisplayName("provider.type=anthropic → WebSearch/WebFetch 保留 deferred（tool_reference 激活，对齐 CC 省 token）")
    void anthropic_keepsWebToolsDeferred() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("anthropic");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = deferredWithWeb();
        LlmAgentLoop.exemptWebSearchDeferForOpenAi(deferred, modelMapper, providerMapper, "anthropic/claude-sonnet-4-6");

        assertThat(deferred)
            .as("anthropic 有 tool_reference 能激活 → 保留懒加载省 token（对齐 CC 不偏离）")
            .contains(ToolNameConstants.WEB_SEARCH_TOOL_NAME)
            .contains(ToolNameConstants.WEB_FETCH_TOOL_NAME);
    }

    @Test
    @DisplayName("mapper null（4 参旧签名无法判 provider）→ 不豁免，WebSearch 保持 deferred（旧契约不变）")
    void mapperNull_keepsWebToolsDeferred() {
        Set<String> deferred = deferredWithWeb();
        LlmAgentLoop.exemptWebSearchDeferForOpenAi(deferred, null, null, "deepseek-chat");
        assertThat(deferred)
            .as("mapper null 无法判定 provider → 保持既有懒加载（区别于 vision 豁免的保守剔除方向）")
            .contains(ToolNameConstants.WEB_SEARCH_TOOL_NAME)
            .contains(ToolNameConstants.WEB_FETCH_TOOL_NAME);
    }

    @Test
    @DisplayName("deferred 不含 web 工具 → no-op（其它 defer 工具不受波及）")
    void noWebTools_noop() {
        stubResolvableModel();
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType("openai_compatible");
        when(providerMapper.selectOneById(any())).thenReturn(p);

        Set<String> deferred = new LinkedHashSet<>();
        deferred.add("ToolSearch");
        LlmAgentLoop.exemptWebSearchDeferForOpenAi(deferred, modelMapper, providerMapper, "deepseek/deepseek-v4-flash");
        assertThat(deferred).containsExactly("ToolSearch");
    }

    @Test
    @DisplayName("deferred null → 不抛（装配容忍）")
    void nullDeferred_noThrow() {
        stubResolvableModel();
        LlmAgentLoop.exemptWebSearchDeferForOpenAi(null, modelMapper, providerMapper, "deepseek/deepseek-v4-flash");
    }
}
