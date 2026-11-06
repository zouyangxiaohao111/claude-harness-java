package com.nexusai.kb.app.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 视觉模型调用 · 生产实现（{@link KbVisionCaller}）。
 *
 * <p><b>为什么自己组装而不是调 {@code VisionAnalyzeTool}</b>：那是一个 LLM 工具，需要
 * {@code ToolUseBlock}/{@code ToolUseContext}，从解析器调它等于伪造一次工具调用。本类只抄它
 * <b>被验证过的那条通道</b>（{@code VisionAnalyzeTool#execute} 第 3–5 步）：
 * <ol>
 *   <li>{@code ModelConfigResolver.resolveMultimodalModelName()} 取多模态档位模型名</li>
 *   <li>{@code ModelConfigResolver.resolve(name)} 解析出 ProviderConfig + providerType</li>
 *   <li>{@code LlmSideCallClient.chat(...)} 单发 + 记账（它内部经 {@code LlmInvocationGateway}
 *       调 {@code LlmProviderFactory} 路由到具体 provider，并保证「调用发生了但用量丢失」不再发生）</li>
 * </ol>
 *
 * <p><b>消息形态</b>：与 {@code VisionAnalyzeTool} 完全一致 —— 一条 user 消息，contentBlocks =
 * {@code [text, image]}（text 在前），并把该消息放进 history；{@code userMessage} 传 null，
 * 否则会出现连续两条 user 消息（AnthropicSdkProvider 会拒绝）。
 *
 * <p><b>失败语义</b>：本类<b>抛异常</b>（不吞），由 {@link KbPdfParser} 按 fail-soft 处理 ——
 * 「这一页视觉失败」不该让整份文档解析失败，但失败必须可观测（metadata + warn）。
 */
@Component
public class KbVisionModelCaller implements KbVisionCaller {

    private static final Logger log = LoggerFactory.getLogger(KbVisionModelCaller.class);

    /** 单图 base64 上限（对齐 Anthropic image block 硬限制，与 VisionAnalyzeTool 同口径）。 */
    private static final long MAX_IMAGE_BASE64 = 5L * 1024 * 1024;

    private final ModelConfigResolver modelConfigResolver;
    private final LlmSideCallClient sideCallClient;

    /**
     * 构造注入（单构造器，Spring 无需 {@code @Autowired}；参数可 null → {@link #isAvailable()} 返回 false）。
     */
    @Autowired
    public KbVisionModelCaller(ModelConfigResolver modelConfigResolver,
                               LlmSideCallClient sideCallClient) {
        this.modelConfigResolver = modelConfigResolver;
        this.sideCallClient = sideCallClient;
    }

    @Override
    public boolean isAvailable() {
        if (modelConfigResolver == null || sideCallClient == null) {
            return false;
        }
        String modelName = resolveModelNameQuietly();
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        try {
            ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
            return resolved != null && resolved.config() != null && resolved.config().isUsable();
        } catch (RuntimeException e) {
            log.warn("KbVisionModelCaller: 视觉模型可用性探测失败 model={} 原因={}", modelName, e.getMessage());
            return false;
        }
    }

    @Override
    public String describeImage(Path imageFile, String prompt, KbParseContext ctx) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("视觉模型未配置（settings.multimodalModelName 缺失或 provider 不可用）");
        }
        String modelName = resolveModelNameQuietly();
        ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            throw new IllegalStateException("视觉模型 provider 解析失败: " + modelName);
        }
        // 路由由 LlmSideCallClient → LlmInvocationGateway 统一完成（它内部调 factory），
        // 这里不再自行 getProvider：否则是同一请求解析两次，且工厂恒非 null 使那次判定成了死代码。

        byte[] bytes = Files.readAllBytes(imageFile);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        if ((long) base64.length() > MAX_IMAGE_BASE64) {
            throw new IllegalStateException("页图超 5MB 无法发送视觉模型: " + imageFile.getFileName()
                + "（base64 " + base64.length() + "）");
        }
        String mediaType = mediaTypeOf(imageFile);
        String userPrompt = prompt == null || prompt.isBlank() ? DEFAULT_PAGE_PROMPT : prompt;

        List<JsonNode> blocks = new ArrayList<>(2);
        blocks.add(textBlock(userPrompt));
        blocks.add(LlmAgentLoop.imageContentBlock(mediaType, base64));
        ChatMessageDto user = LlmAgentLoop.toMessage(
            Role.user, userPrompt, null, null, blocks, List.of(), true);

        LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
            List.of(user), null, null, null, null, "kb-doc-parse", null, null,
            ctx == null ? null : ctx.agentContext());

        String result = sideCallClient.chat(resolved.config(), modelName, null, null, options,
            usageOf(ctx));
        if (log.isInfoEnabled()) {
            log.info("KbVisionModelCaller: 视觉描述完成 file={} model={} chars={}",
                imageFile.getFileName(), modelName, result == null ? 0 : result.length());
        }
        return result == null ? "" : result;
    }

    /** 台账归属 · 有会话 → inSession；无会话 → standalone（⛔ 不伪造 sessionId）。 */
    private static LlmSideCallClient.SideCallUsage usageOf(KbParseContext ctx) {
        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            return LlmSideCallClient.SideCallUsage.standalone(UsageCategory.SIDE_CALL, "kb-doc-parse");
        }
        return LlmSideCallClient.SideCallUsage
            .inSession(ctx.sessionId(), ctx.agentId(), UsageCategory.SIDE_CALL, "kb-doc-parse")
            .withAgentContext(ctx.agentContext());
    }

    private String resolveModelNameQuietly() {
        try {
            return modelConfigResolver.resolveMultimodalModelName();
        } catch (RuntimeException e) {
            log.warn("KbVisionModelCaller: 读取多模态档位模型失败 原因={}", e.getMessage());
            return null;
        }
    }

    private static String mediaTypeOf(Path file) {
        String n = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        // PdfSupport 渲染产出的就是 JPEG
        return "image/jpeg";
    }

    private static ObjectNode textBlock(String text) {
        ObjectNode block = JsonNodeFactory.instance.objectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    /** 默认提示词 · 面向知识库入库（要的是可检索的正文，不是寒暄）。 */
    static final String DEFAULT_PAGE_PROMPT =
        "请完整、逐行转写这张页面图片中的全部文字内容。要求："
            + "1) 保持原有阅读顺序与层级（标题、段落、列表、表格）；"
            + "2) 表格用 Markdown 表格输出；"
            + "3) 不要总结、不要评论、不要添加原文没有的内容；"
            + "4) 若页面没有可读文字（纯图/空白），只回复一行：NO_TEXT。";
}
