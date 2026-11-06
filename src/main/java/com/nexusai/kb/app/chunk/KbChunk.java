package com.nexusai.kb.app.chunk;

/**
 * 分块产物 · 对齐 WeKnora {@code chunker.Chunk}（{@code splitter.go:28-34}）。
 *
 * <p><b>⭐ 位置不变式</b>（源侧 {@code splitter.go:20-22} 明文）：
 * {@link #content} 恰好是原文 {@code [start, end)} 区间的文本，
 * 且 {@code end - start == content 的 rune 数}。
 * ⛔ 该不变式被文档重建（摘要生成、UI 高亮）依赖。
 *
 * <p><b>{@link #contextHeader} 为什么不并入 content</b>：它是独立跟踪的上下文串
 * （如 Markdown 标题面包屑），embedding 时才前置。合进 content 会破坏位置不变式。
 *
 * @param content       分块正文（原文切片，逐字）
 * @param contextHeader 上下文头（可空）
 * @param seq           全局序号（0 起，跨整个文档单调）
 * @param start         原文起始 rune 偏移（含）
 * @param end           原文结束 rune 偏移（不含）
 */
public record KbChunk(
    String content,
    String contextHeader,
    int seq,
    int start,
    int end
) {

    /**
     * 送去 embedding 的文本 · 对齐源侧 {@code Chunk.EmbeddingContent()}（{@code splitter.go:45-51}）。
     *
     * <p>先 trim 再拼 header：正文首尾的换行（来自边界切片）会稀释向量、浪费 token；
     * <b>内部</b>空白保留。
     *
     * @return 可直接喂给 embedding 的文本
     */
    public String embeddingContent() {
        String body = content == null ? "" : content.strip();
        if (contextHeader == null || contextHeader.isEmpty()) {
            return body;
        }
        return contextHeader + "\n\n" + body;
    }
}
