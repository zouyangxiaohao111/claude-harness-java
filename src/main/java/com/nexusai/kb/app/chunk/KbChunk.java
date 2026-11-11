package com.nexusai.kb.app.chunk;

import java.util.List;
import java.util.Map;

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
 * <p><b>{@link #images} 从哪来</b>：切分器本身<b>不产出图片</b>（它只认文本），
 * 该字段由入库编排器（{@code KnowledgeParseProcessor}）在切分完成后按图片在原文中的
 * 字符偏移回填 —— 见 {@code KbChunk#withImages}。因此本 record 在切分阶段恒为空列表，
 * 不会破坏既有调用方。
 *
 * <p><b>{@link #sourceLocators} 为什么是 String 而不是 {@code List<KbSourceLocator>}</b>：
 * 分层依赖。{@code KbSourceLocator} 在 {@code kb.app.parser} 包，本类在 {@code kb.app.chunk} 包；
 * 让「更底层的 chunk 概念」依赖「parser 的产物类型」是反向依赖。故由入库编排器
 * （{@code KnowledgeParseProcessor}）在构造分块之前就把定位算成 JSON 字符串，
 * 本类只持有一个不透明字符串，落库时原样写入 {@code kb_chunk.source_locators}。
 *
 * @param content        分块正文（原文切片，逐字）
 * @param contextHeader  上下文头（可空）
 * @param seq            全局序号（0 起，跨整个文档单调）
 * @param start          原文起始 rune 偏移（含）
 * @param end            原文结束 rune 偏移（不含）
 * @param images         归属到本块的图片（按其在原文中的出现顺序；无图时为空列表）
 * @param sourceLocators 源位置定位 JSON（如 {@code [{"type":"docx","block":3}]}）；
 *                       {@code null} = 无定位（源站 {@code ParsedChunk.SourceLocators} 的同款语义）
 * @param parentIndex    父块在 {@code ParentChildResult.parents()} 里的下标；
 *                       {@code -1} = 无父块（普通切分 / 本身就是父块）。
 *                       对齐源站 {@code chunker.ChildChunk.ParentIndex}（{@code splitter.go:856-859}）。
 *                       ⚠️ 这是<b>内存下标</b>，落库时由 {@code KbChunkWriter} 换成父块的真实 id。
 */
public record KbChunk(
    String content,
    String contextHeader,
    int seq,
    int start,
    int end,
    List<Image> images,
    String sourceLocators,
    int parentIndex
) {

    /** 无父块的哨兵值（对齐源站 {@code parentIndex := -1}，{@code strategy.go:171}）。 */
    public static final int NO_PARENT = -1;

    /**
     * 分块类型常量 · 普通文本块（源侧 {@code types.ChunkTypeText}，{@code chunk.go}）。
     *
     * <p>⚠️ 与 {@code KbMergeStage} 的取值必须逐字一致 —— 检索侧的父块展开判据是
     * {@code "parent_text".equals(chunkType)}，写入侧写错一个字就整条链路静默失效。
     */
    public static final String CHUNK_TYPE_TEXT = "text";

    /** 分块类型常量 · 父子分块里的父块（源侧 {@code types.ChunkTypeParentText}，{@code chunk.go:18}）。 */
    public static final String CHUNK_TYPE_PARENT_TEXT = "parent_text";

    /**
     * 兼容构造器 · 不带图片、不带溯源（切分器与既有单测用）。
     *
     * <p>record 允许额外构造器委托给规范构造器；保留它可以让「切分」与「图片归属」
     * 两件事解耦 —— 切分器仍然只关心文本，图片由上层回填。
     */
    public KbChunk(String content, String contextHeader, int seq, int start, int end) {
        this(content, contextHeader, seq, start, end, List.of(), null, NO_PARENT);
    }

    /**
     * 兼容构造器 · 带图片、不带溯源（图片归属阶段用；溯源在同一阶段之后单独附加）。
     *
     * <p>⚠️ 必须保留：{@code KbHeadingSplitter} 重排 seq 时用的就是这个 6 参形态。
     */
    public KbChunk(String content, String contextHeader, int seq, int start, int end,
                   List<Image> images) {
        this(content, contextHeader, seq, start, end, images, null, NO_PARENT);
    }

    public KbChunk {
        images = images == null ? List.of() : List.copyOf(images);
    }

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

    /**
     * 回填图片（保持其余字段不变，包括已有的 {@link #sourceLocators}）。
     *
     * <p>⚠️ {@code sourceLocators} 必须原样带过去：一旦调用顺序变成
     * 「先加溯源 → 再回填图片」，丢掉它就是静默丢定位（编不出来、只有库里那一列变 NULL）。
     *
     * @param newImages 归属到本块的图片
     * @return 带图片的新分块
     */
    public KbChunk withImages(List<Image> newImages) {
        return new KbChunk(content, contextHeader, seq, start, end, newImages, sourceLocators,
            parentIndex);
    }

    /**
     * 附加源位置定位（保持其余字段不变）。
     *
     * <p>配合 {@link #withImages} 组成「切分 → 归图片 → 加溯源」三段式，
     * 每段只碰自己的字段，避免一次性拼一个 7 参构造器（易错位）。
     *
     * @param newSourceLocators 定位 JSON（{@code null} = 无定位）
     * @return 带溯源的新分块
     */
    public KbChunk withSourceLocators(String newSourceLocators) {
        return new KbChunk(content, contextHeader, seq, start, end, images, newSourceLocators,
            parentIndex);
    }

    /**
     * 绑定父块下标（保持其余字段不变）。
     *
     * <p>父子分块的两段式：先 {@link KbTextSplitter#splitParentChild} 产出「子块 + 父块列表」，
     * 再在落库前用本方法把下标换成真实父块 id —— 中间任何一步换序都会让子块指错父块。
     *
     * @param newParentIndex 父块下标（{@link #NO_PARENT} = 无父块）
     * @return 带父指针的新分块
     */
    public KbChunk withParentIndex(int newParentIndex) {
        return new KbChunk(content, contextHeader, seq, start, end, images, sourceLocators,
            newParentIndex);
    }

    /** 是否有父块（对齐源站 {@code ParentIndex >= 0} 判据，{@code knowledge_process.go:642}）。 */
    public boolean hasParent() {
        return parentIndex >= 0;
    }

    /**
     * 分块引用到的一张图片 · 已落盘、已有可访问 URL。
     *
     * <p><b>为什么不是解析器的 {@code KbImageRef}</b>：那个记录的是「临时目录里的绝对路径」，
     * 解析结束临时目录就被删了；本记录记录的是<b>持久化之后</b>的地址与可访问 URL，
     * 是能直接落库/给前端渲染的形态。
     *
     * <p><b>字段与 {@code kb_chunk.image_info} 一一对应</b>（源侧 {@code chunk.image_info}）：
     * {@code url} / {@code original_url} / {@code caption} / {@code ocr_text} / {@code attrs} / {@code status}。
     * 落库由 {@code KbChunkWriter} 负责，读投影由 {@code KbImageService} 负责，两侧字段名必须一致。
     *
     * @param key         去重键（当前恒等于 {@link #url}；对齐源侧 {@code url → original_url → chunk#index} 的优先序）
     * @param url         可访问地址（本仓 API 路径，如 {@code /api/v1/kb/images/<knowledgeId>/<file>}）
     * @param originalUrl 原始引用（原图文件名；用于排查「这张图从哪来」）
     * @param caption     说明（PDF 视觉补强的描述 / Office 资源名；没有则空串）
     * @param ocrText     OCR 文本（本批不产出，恒空串；字段保留以对齐源侧结构）
     * @param attrs       观测属性（{@code source_kind} / {@code page_index} / {@code size_bytes} /
     *                    {@code width} / {@code height} / {@code storage_key} 等）
     * @param status      状态（0 = 正常，对齐源侧默认值）
     */
    public record Image(
        String key,
        String url,
        String originalUrl,
        String caption,
        String ocrText,
        Map<String, Object> attrs,
        int status
    ) {

        public Image {
            key = key == null ? "" : key;
            url = url == null ? "" : url;
            originalUrl = originalUrl == null ? "" : originalUrl;
            caption = caption == null ? "" : caption;
            ocrText = ocrText == null ? "" : ocrText;
            attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        }
    }
}
