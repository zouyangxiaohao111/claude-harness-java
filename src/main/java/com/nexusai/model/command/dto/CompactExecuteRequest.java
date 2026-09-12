package com.nexusai.model.command.dto;

/**
 * /compact 执行请求体 · 对齐 CC compact 命令的 {@code args} 入参
 * （{@code src/commands/compact/compact.ts:54} {@code const customInstructions = args.trim()}）。
 *
 * <p><b>WHY 需要 body（P2-8 · 2026-09-11）</b>：CC 支持 {@code /compact 用中文总结} 形态 ——
 * 自定义指令作为 {@code customInstructions} 一路传到 {@code compactConversation}（compact.ts:106-112），
 * 且<b>有指令时跳过 SM 优先分支</b>（compact.ts:44-48 {@code if (!customInstructions)}，session memory
 * 压缩不支持自定义指令）。Java 侧 {@code CompactCommand.call(args, ctx)} 本就消费该参数
 * （{@code CompactCommand.java:215-224}），但 REST 字面端点
 * {@code CommandController.executeCompactBuiltin} 只收 {@code ?sessionId=}、且恒以
 * {@code dispatchResult("/compact")} 分派（args 恒空）→ 指令在 Web 端不可达，且 manual /compact 永远
 * 落 SM 优先分支。本请求体补齐该入站通道。
 *
 * @param args /compact 之后的原始文本（可为 null/空 = 无自定义指令 · 与 CC args 空串等价）
 */
public record CompactExecuteRequest(
        String args
) {
}
