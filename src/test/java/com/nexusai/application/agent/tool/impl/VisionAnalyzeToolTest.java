package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * VisionAnalyzeTool 代理视觉模型行为测试（RED→GREEN）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>analyze 分支把图片作为 image content block 发给视觉模型</b>——代理视觉模型链路的
 *       核心契约：图片只发给视觉模型，主模型收到纯文本 + 占位符，绝不接触 base64。若工具把
 *       base64 塞回主模型上下文，则回到旧 MultimodalAttachmentTool 的逻辑矛盾。</li>
 *   <li><b>suggest 分支纯文本不读图</b>——多模态不一定要传递图片（用户拍板：suggest 不需要
 *       contentId），纯 prompt 也能驱动视觉模型给建议。</li>
 *   <li><b>fail-loud 分支</b>——视觉模型未配置 / provider 解析失败 / 缓存未命中 / 超 5MB 都返回
 *       error 文本注入 LLM 自纠，不静默吞错（CLAUDE.md 规则十二）。</li>
 * </ol>
 */
class VisionAnalyzeToolTest {

    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private ImageAttachmentStore store;
    private ModelConfigResolver modelConfigResolver;
    private LlmProviderFactory llmProviderFactory;
    private LlmProvider provider;
    private VisionAnalyzeTool tool;

    @TempDir
    Path configHome;

    @BeforeEach
    void setUp() {
        store = new ImageAttachmentStore();
        modelConfigResolver = mock(ModelConfigResolver.class);
        llmProviderFactory = mock(LlmProviderFactory.class);
        provider = mock(LlmProvider.class);
        // G5：ImageAttachmentStore 写 nexusai 自有根 → 唯一 appName 隔离（防写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        tool = new VisionAnalyzeTool(store, modelConfigResolver, llmProviderFactory);
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
        NexusaiPaths.setAppNameOverride(null);
    }

    /** 标准成功桩：多模态模型名 + provider 解析 + factory 路由 + chat 返回文本。 */
    private void stubSuccessChain(String chatResult) {
        ProviderConfig cfg = new ProviderConfig("https://example.com", "sk-test");
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn("vision-model");
        when(modelConfigResolver.resolve("vision-model"))
            .thenReturn(new ModelConfigResolver.ResolvedModel(cfg, "anthropic"));
        when(llmProviderFactory.getProvider(cfg, "anthropic")).thenReturn(provider);
        when(provider.chatWithOptions(eq(cfg), eq("vision-model"), isNull(), any(), any()))
            .thenReturn(chatResult);
    }

    /** 便捷构造 ToolUseBlock：input 为 JSON 字符串，经 JsonNodeFactory 解析为 ObjectNode。 */
    private static ToolUseBlock call(String input) {
        return new ToolUseBlock("toolcall-1", "vision_analyze", json(input));
    }

    private static ToolUseBlock call(String id, String input) {
        return new ToolUseBlock(id, "vision_analyze", json(input));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 JSON: " + s, e);
        }
    }

    // ── 成功路径 ──

    @Test
    @DisplayName("suggest 成功：纯文本 prompt → 视觉模型建议 → data 含结果，无图片占位符")
    void suggest_success_returnsTextWithoutImagePlaceholder() {
        stubSuccessChain("建议使用更大的对比度来提升可读性。");
        AgentToolResult<?> result = tool.execute(call(
            "{\"type\":\"suggest\",\"prompt\":\"如何改进这个登录页面的视觉设计？\"}"));

        assertThat(result).isInstanceOf(ToolResult.class);
        String data = ((ToolResult<String>) result).data();
        assertThat(data).contains("type=suggest")
            .contains("建议使用更大的对比度")
            .doesNotContain("[image:");
    }

    @Test
    @DisplayName("analyze 成功：读缓存图 → 图片+prompt 发给视觉模型 → data 含 [image:contentId] 占位符，不含 base64")
    void analyze_success_readsImageAndProxiesToVisionModel() {
        store.storeWithId("sess-1", 1L, PNG_BASE64, "image/png");
        stubSuccessChain("图片中包含一棵树和一条河流。");

        AgentToolResult<?> result = tool.execute(
            call("toolcall-2", "{\"type\":\"analyze\",\"prompt\":\"这张图里有什么？\",\"contentId\":\"1\"}"),
            ToolUseContext.of(null, "sess-1"));

        assertThat(result).isInstanceOf(ToolResult.class);
        String data = ((ToolResult<String>) result).data();
        assertThat(data).contains("type=analyze")
            .contains("[image:1]")
            .contains("图片中包含一棵树")
            .doesNotContain(PNG_BASE64);  // 主模型绝不接触 base64

        // 核心契约：捕获 chatWithOptions 的 history，验证 user 消息 contentBlocks=[text, image]
        ArgumentCaptor<LlmProvider.ChatRequestOptions> optionsCaptor =
            ArgumentCaptor.forClass(LlmProvider.ChatRequestOptions.class);
        verify(provider).chatWithOptions(
            any(ProviderConfig.class), eq("vision-model"), isNull(), isNull(), optionsCaptor.capture());
        LlmProvider.ChatRequestOptions sent = optionsCaptor.getValue();
        List<ChatMessageDto> history = sent.history();
        assertThat(history).hasSize(1);
        ChatMessageDto user = history.get(0);
        assertThat(user.contentBlocks()).hasSize(2);
        assertThat(user.contentBlocks().get(0).toString()).contains("\"text\"").contains("这张图里有什么？");
        assertThat(user.contentBlocks().get(1).toString())
            .contains("\"image\"").contains("\"base64\"").contains(PNG_BASE64);
    }

    @Test
    @DisplayName("aliases 保留旧名 multimodal_attachment（历史 transcript 兼容）")
    void aliases_keepsLegacyName() {
        assertThat(tool.aliases()).contains("multimodal_attachment");
        assertThat(tool.name()).isEqualTo("vision_analyze");
    }

    @Test
    @DisplayName("懒加载：shouldDefer=true（工具 schema 不占初始 prompt，经 ToolSearch 检索加载）")
    void lazyLoad_shouldDeferTrue() {
        // CC Tool.ts:442 shouldDefer=true → defer_loading，需 ToolSearch 后才可调用。
        // WHY：主流视觉模型用不上本工具（图片直接注入 image block），始终进 schema 是 token 浪费。
        assertThat(tool.shouldDefer(null)).isTrue();
    }

    // ── 输入防御 ──

    @Test
    @DisplayName("type=analyze 缺 contentId → error（requires contentId）")
    void analyze_missingContentId_returnsError() {
        AgentToolResult<?> result = tool.execute(call(
            "{\"type\":\"analyze\",\"prompt\":\"看图\"}"));
        assertThat(((ToolResult<String>) result).data()).contains("requires contentId");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("type 非法值 → error（invalid type）")
    void invalidType_returnsError() {
        AgentToolResult<?> result = tool.execute(call(
            "{\"type\":\"foobar\",\"prompt\":\"x\"}"));
        assertThat(((ToolResult<String>) result).data()).contains("invalid type");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("prompt 缺失 → error（missing required input: prompt）")
    void missingPrompt_returnsError() {
        AgentToolResult<?> result = tool.execute(call("{\"type\":\"suggest\"}"));
        assertThat(((ToolResult<String>) result).data()).contains("missing required input: prompt");
        verifyNoInteractions(provider);
    }

    // ── fail-loud 分支 ──

    @Test
    @DisplayName("视觉模型未配置（multimodalModelName=null）→ error 提示配置，chatWithOptions 不调用")
    void multimodalModelNotConfigured_returnsError() {
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn(null);
        AgentToolResult<?> result = tool.execute(call(
            "{\"type\":\"suggest\",\"prompt\":\"建议\"}"));
        assertThat(((ToolResult<String>) result).data()).contains("multimodalModelName");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("provider 解析失败（resolve→null）→ error 无匹配 provider，factory 不调用")
    void providerResolutionFailure_returnsError() {
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn("vision-model");
        when(modelConfigResolver.resolve("vision-model")).thenReturn(null);
        AgentToolResult<?> result = tool.execute(call(
            "{\"type\":\"suggest\",\"prompt\":\"建议\"}"));
        assertThat(((ToolResult<String>) result).data()).contains("无匹配的 enabled provider");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("缓存未命中（contentId 无对应缓存）→ error（cache miss）")
    void analyze_cacheMiss_returnsError() {
        stubSuccessChain("ignored");
        AgentToolResult<?> result = tool.execute(
            call("t", "{\"type\":\"analyze\",\"prompt\":\"看图\",\"contentId\":\"999\"}"),
            ToolUseContext.of(null, "sess-1"));
        assertThat(((ToolResult<String>) result).data()).contains("cache miss");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("图片超 5MB → error（超 5MB，建议路径引用）")
    void analyze_oversizeImage_returnsError() {
        // 构造超 5MB 图片：isOversize 按 base64.length*3/4 估算原字节，需 base64 > 6.99MB → 7MB 字符串
        // （7_000_000 * 3/4 = 5,250,000 原字节 > 5MB 阈值）
        String big = "A".repeat(7_000_000);
        store.storeWithId("sess-1", 2L, big, "image/png");
        stubSuccessChain("ignored");
        AgentToolResult<?> result = tool.execute(
            call("t", "{\"type\":\"analyze\",\"prompt\":\"看图\",\"contentId\":\"2\"}"),
            ToolUseContext.of(null, "sess-1"));
        assertThat(((ToolResult<String>) result).data()).contains("超 5MB");
        verifyNoInteractions(provider);
    }

    // ── validateInput ──

    @Test
    @DisplayName("validateInput：type 缺失 → fail errorCode=1")
    void validateInput_missingType_fails() {
        var v = tool.validateInput(null, null);
        assertThat(v.ok()).isFalse();
        assertThat(v.errorCode()).isEqualTo("1");
    }

    @Test
    @DisplayName("validateInput：analyze 缺 contentId → fail errorCode=3")
    void validateInput_analyzeMissingContentId_fails() {
        var v = tool.validateInput(json("{\"type\":\"analyze\",\"prompt\":\"x\"}"), null);
        assertThat(v.ok()).isFalse();
        assertThat(v.errorCode()).isEqualTo("3");
    }
}
