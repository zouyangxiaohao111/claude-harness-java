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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
    @DisplayName("工具意图懒：shouldDefer=true（是否实际懒由装配层按主模型豁免，见 LlmAgentLoop.exemptVisionAnalyzeDeferForTextModel）")
    void deferIntentTrue() {
        // 2026-09-03 定稿：vision_analyze 工具层表达"想懒"（defer_loading 语义）；是否真懒由装配层按
        // 主模型判定 —— 仅 ant/response 直给格式 + 多模态保留懒；deepseek（openai-completions，含
        // vision-exp）/ 文本模型 → 从 deferred 剔除强制直发（vision_analyze 是唯一视觉通道）。
        // WHY（规则 9）：若工具意图回退 shouldDefer=false（常驻），ant 多模态主模型也直发 → 多占
        // schema token；若装配层豁免缺失，deepseek 文本又要走 ToolSearch 激活 → 回归历史死循环。
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

    // ── [v2] path / contentType / pages 校验分支 ──

    @Test
    @DisplayName("validateInput v2：analyze 给 path（无 contentId）→ pass（文件系统源）")
    void validateInput_pathAlone_passes() {
        var v = tool.validateInput(json("{\"type\":\"analyze\",\"prompt\":\"x\",\"path\":\"a/b.png\"}"), null);
        assertThat(v.ok()).isTrue();
    }

    @Test
    @DisplayName("validateInput v2：contentId 与 path 同给 → fail errorCode=5（互斥）")
    void validateInput_bothSources_fails() {
        var v = tool.validateInput(json(
            "{\"type\":\"analyze\",\"prompt\":\"x\",\"contentId\":\"1\",\"path\":\"a.png\"}"), null);
        assertThat(v.ok()).isFalse();
        assertThat(v.errorCode()).isEqualTo("5");
    }

    @Test
    @DisplayName("validateInput v2：pages 但 contentType≠pdf → fail errorCode=9")
    void validateInput_pagesWithoutPdf_fails() {
        var v = tool.validateInput(json(
            "{\"type\":\"analyze\",\"prompt\":\"x\",\"contentId\":\"1\",\"contentType\":\"image\",\"pages\":[1]}"), null);
        assertThat(v.ok()).isFalse();
        assertThat(v.errorCode()).isEqualTo("9");
    }

    @Test
    @DisplayName("validateInput v2：pages 超 20 页 → fail errorCode=10（对齐 CC PDF_MAX_PAGES_PER_READ）")
    void validateInput_pagesOverLimit_fails() {
        StringBuilder pages = new StringBuilder();
        for (int i = 1; i <= 21; i++) {
            pages.append(i).append(',');
        }
        var v = tool.validateInput(json("{\"type\":\"analyze\",\"prompt\":\"x\",\"path\":\"a.pdf\","
            + "\"contentType\":\"pdf\",\"pages\":[" + pages.substring(0, pages.length() - 1) + "]}"), null);
        assertThat(v.ok()).isFalse();
        assertThat(v.errorCode()).isEqualTo("10");
    }

    @Test
    @DisplayName("validateInput v2：contentType 非法值 → fail errorCode=6")
    void validateInput_badContentType_fails() {
        var v = tool.validateInput(json(
            "{\"type\":\"analyze\",\"prompt\":\"x\",\"contentId\":\"1\",\"contentType\":\"video\"}"), null);
        assertThat(v.ok()).isFalse();
        assertThat(v.errorCode()).isEqualTo("6");
    }

    // ── [v2] path 源（文件系统读盘 / PDF 懒渲染）──

    @Test
    @DisplayName("[v2 path 图] analyze(path=图片) 读盘 → 单 image block 送视觉模型（无需 contentId/注册）")
    void analyze_pathImage_readsFileAndProxies() throws Exception {
        Path img = configHome.resolve("shot.png");
        Files.write(img, Base64.getDecoder().decode(PNG_BASE64));
        stubSuccessChain("截图里有一个登录按钮。");

        // Windows 反斜杠破坏 JSON 转义 → 正斜杠（Path 可接受）
        String jsonPath = img.toString().replace('\\', '/');
        AgentToolResult<?> result = tool.execute(
            call("t", "{\"type\":\"analyze\",\"prompt\":\"这个截图里有什么？\",\"path\":\"" + jsonPath + "\"}"),
            ToolUseContext.of(null, "sess-1"));

        assertThat(result).isInstanceOf(ToolResult.class);
        assertThat(((ToolResult<String>) result).data()).contains("type=analyze").contains("截图里有一个登录按钮");

        ArgumentCaptor<LlmProvider.ChatRequestOptions> captor =
            ArgumentCaptor.forClass(LlmProvider.ChatRequestOptions.class);
        verify(provider).chatWithOptions(
            any(ProviderConfig.class), eq("vision-model"), isNull(), isNull(), captor.capture());
        List<ChatMessageDto> history = captor.getValue().history();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).contentBlocks()).hasSize(2);
        assertThat(history.get(0).contentBlocks().get(1).toString())
            .contains("\"image\"").contains("\"base64\"").contains(PNG_BASE64);
    }

    @Test
    @DisplayName("[v2 pdf] analyze(path=PDF, contentType=pdf, pages=[1,3]) 懒渲染选中页 → 双 image block 送视觉模型，临时目录清理")
    void analyze_pathPdf_pages_rendersSelectedPagesToVisionModel() throws Exception {
        Path pdf = configHome.resolve("sample.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(pdf.toFile());
        }
        stubSuccessChain("PDF 第 1、3 页是合同条款。");

        // Windows 反斜杠破坏 JSON 转义 → 正斜杠（Path 可接受）
        String jsonPath = pdf.toString().replace('\\', '/');
        AgentToolResult<?> result = tool.execute(
            call("t", "{\"type\":\"analyze\",\"prompt\":\"这 PDF 讲了什么？\",\"path\":\"" + jsonPath
                + "\",\"contentType\":\"pdf\",\"pages\":[1,3]}"),
            ToolUseContext.of(null, "sess-1"));

        assertThat(result).isInstanceOf(ToolResult.class);
        String data = ((ToolResult<String>) result).data();
        assertThat(data).contains("type=analyze").contains("PDF 占位符=[pdf:").contains("pages=[1, 3]")
            .contains("PDF 第 1、3 页是合同条款");

        // 核心契约：懒渲染只送选中页 → user 消息 contentBlocks=[text, image(页1), image(页3)]，共 3 块
        ArgumentCaptor<LlmProvider.ChatRequestOptions> captor =
            ArgumentCaptor.forClass(LlmProvider.ChatRequestOptions.class);
        verify(provider).chatWithOptions(
            any(ProviderConfig.class), eq("vision-model"), isNull(), isNull(), captor.capture());
        List<ChatMessageDto> history = captor.getValue().history();
        assertThat(history).hasSize(1);
        List<?> blocks = history.get(0).contentBlocks();
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(1).toString()).contains("\"image\"").contains("\"image/jpeg\"");
        assertThat(blocks.get(2).toString()).contains("\"image\"").contains("\"image/jpeg\"");

        // 渲染临时目录已清理（finally deleteRecursive）
        try (java.util.stream.Stream<Path> walk = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(walk.filter(p -> p.getFileName().toString().startsWith("vision-analyze-pdf-"))).isEmpty();
        }
    }

    @Test
    @DisplayName("[400 回归 2026-09-03] inputSchema pages.items 必须是 schema 对象（OpenAI Invalid schema anyOf 防回归）")
    void inputSchema_pagesItems_isObjectSchema() {
        // 生产 400：原 putArray("items").add("integer") 生成 items=["integer"]（字符串数组）→ 非法 JSON Schema
        // → OpenAI "Invalid schema for function 'vision_analyze' not valid under anyOf"。items 必须为
        // 对象 {"type":"integer"}。deepseek 全 schema 直发，schema 非法即 API 400（整轮失败）。
        JsonNode pages = tool.inputSchema().path("properties").path("pages");
        assertThat(pages.get("type").asText()).isEqualTo("array");
        JsonNode items = pages.get("items");
        assertThat(items)
            .as("items 必须存在")
            .isNotNull();
        assertThat(items.isObject())
            .as("items 必须是 schema 对象 {" + "\"type\":\"integer\"" + "}（putObject），非字符串数组（putArray+add('integer')）")
            .isTrue();
        assertThat(items.get("type").asText()).isEqualTo("integer");
    }
}
