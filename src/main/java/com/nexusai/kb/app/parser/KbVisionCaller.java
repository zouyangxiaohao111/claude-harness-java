package com.nexusai.kb.app.parser;

import java.nio.file.Path;

/**
 * 视觉能力接缝 · 把「一张图片 → 一段文字描述」抽象成接口，使 PDF 解析器能脱离 Spring 单测。
 *
 * <p><b>为什么不直接调 {@code VisionAnalyzeTool}</b>：那是 {@code Tool} 实现，入参是 LLM 的
 * {@code tool_use} JSON、出参是 {@code AgentToolResult}，走这条路要伪造模型调用上下文。
 * 派单书明确要求「复用它的能力，但不走 Tool 接口」—— 生产实现
 * {@link KbVisionModelCaller} 沿用它同一套 {@code settings.multimodalModelName} +
 * {@code LlmProviderFactory} + {@code LlmSideCallClient} 通道，只是抽出最小方法面。
 */
public interface KbVisionCaller {

    /**
     * 视觉能力是否可用（多模态档位模型已配置且 provider 可用）。
     *
     * <p>不可用时 PDF 解析器只抽文本层，并在 metadata 记原因 —— 这不算失败（弱降级），
     * 只是「没有视觉补强」。
     */
    boolean isAvailable();

    /**
     * 让视觉模型描述一张图片。
     *
     * @param imageFile 本地图片（JPEG/PNG）
     * @param prompt    给视觉模型的指令
     * @param ctx       解析上下文（用量归属）
     * @return 模型给出的纯文本；⛔ 恒非 null（空串表示模型没给内容）
     * @throws Exception 调用失败（PDF 解析器按 fail-soft 处理：保留文本层 + 记 metadata）
     */
    String describeImage(Path imageFile, String prompt, KbParseContext ctx) throws Exception;

    /**
     * 让视觉模型描述一张图片 · <b>可指定模型</b>。
     *
     * <p>⭐ 2026-11-11 用户裁定：知识库设置里的「VLLM 视觉模型」要「单独选择也能其效果」。
     * 该方法让调用方（图片理解）把库级选择透传下来；{@code modelIdOrName} 为空时
     * 回落到全局 {@code settings.multimodalModelName}。
     *
     * <p>默认实现直接忽略参数、委托旧方法 ⇒ 既有实现类与测试桩无需改动即可编译，
     * 且「不支持按库指定」的实现仍是安全的（只是不生效，行为与改动前一致）。
     *
     * @param modelIdOrName 库级指定的模型（`models.id` 或 `models.name`）；null/空 = 用全局默认
     */
    default String describeImageWithModel(Path imageFile, String prompt, KbParseContext ctx,
                                          String modelIdOrName) throws Exception {
        return describeImage(imageFile, prompt, ctx);
    }
}
