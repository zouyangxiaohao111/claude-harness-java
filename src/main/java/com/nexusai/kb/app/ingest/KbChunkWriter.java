package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.image.KbImageService;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分块落库 · 单个事务内「清旧 + 写新 + 串顺序链 + 写图片」。
 *
 * <p><b>为什么必须单独一个 bean</b>：{@code @Transactional} 靠 Spring 代理生效，
 * 同类内部自调用不会开启事务。解析与分块是 CPU 活，不能包在写事务里（SQLite 会长时间锁库）；
 * 只有落库这一小段需要事务 ⇒ 拆成独立 bean，由编排器在解析完成后调用。
 *
 * <p><b>为什么先逻辑删旧块再插新块</b>：重新解析会产出全新分块集合，旧块的
 * {@code chunk_index} / 顺序链与新集合完全不对应；留着会让「取某文档全部分块」
 * 同时返回两轮结果。用逻辑删（{@code deleted=1}）而非物理删，与本域其余表一致。
 *
 * <h2>⭐ 图片为什么在这里写</h2>
 * 图片的真相字段是 {@code kb_chunk.image_info}（JSON 数组），投影表
 * {@code kb_chunk_image} 只是查询加速。两者必须在<b>同一个事务</b>里落：
 * 否则会出现「块写进去了、图库看不到」，而这在界面上表现为「解析完成但图片没了」，
 * 用户无法自行判断是解析没产出图还是落库漏写。
 *
 * <p>写入顺序 = 先 {@code kb_chunk}（拿 chunk id）→ 再投影（投影行主键含 chunk_id）。
 * 投影复用 {@link KbImageService#projectChunk}，⛔ 不在这里重抄一份 SQL ——
 * 两处 SQL 迟早漂移（源侧用触发器同步，本仓只能靠「唯一实现」保证一致）。
 */
@Component
public class KbChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(KbChunkWriter.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KbChunkMapper chunkMapper;

    /**
     * 图片投影器。
     *
     * <p>⚠️ 可为 null：单测直接 {@code new KbChunkWriter(chunkMapper)} 时不装配 Spring，
     * 此时只写 {@code image_info} 的<b>数据库行</b>、跳过投影表（并留 info 日志，⛔ 不静默）。
     * 生产恒由 Spring 注入。
     */
    private final KbImageService imageService;

    /** 单测/直构装配用（无投影器）。 */
    public KbChunkWriter(KbChunkMapper chunkMapper) {
        this(chunkMapper, null);
    }

    @Autowired
    public KbChunkWriter(KbChunkMapper chunkMapper, KbImageService imageService) {
        this.chunkMapper = chunkMapper;
        this.imageService = imageService;
    }

    /**
     * 用新分块替换该文档的全部分块。
     *
     * @param knowledge 知识条目（取 id / knowledgeBaseId）
     * @param chunks    分块器产物（要求按 {@code seq} 升序；{@code images} 由编排器回填）
     * @return 写入结果（条数 + 内容字节总和 + 图片数）
     */
    @Transactional
    public WriteResult replaceChunks(KbKnowledgeRecord knowledge, List<KbChunk> chunks) {
        return replaceChunks(knowledge, chunks, List.of());
    }

    /**
     * 用新分块替换该文档的全部分块 · <b>父子模式</b>（源侧 {@code knowledge_process.go:578-674}）。
     *
     * <p><b>写入顺序与字段口径逐条照源站</b>：
     * <ol>
     *   <li><b>先写父块</b>，{@code chunk_type='parent_text'}、{@code parent_chunk_id=null}，
     *       父块之间串 {@code pre/next}（源侧 {@code :600-606}）</li>
     *   <li><b>再写子块</b>，{@code chunk_type='text'}，{@code parent_chunk_id} = 对应父块 id
     *       （{@code parentIndex < 0} 时为 null —— 源侧 {@code :641-644}）</li>
     *   <li><b>父子模式下不建子块间 pre/next 链</b>（源侧 {@code :664-674} 明文
     *       {@code skip if parent-child, children don't need prev/next links}）——
     *       子块的邻居在检索里没有意义（命中后要展开的是父块），串了反而会让人误以为
     *       可以按 {@code next_chunk_id} 顺序拼接文档</li>
     *   <li>{@code chunk_index} 沿用各自 {@code seq}（源侧两批都用 {@code ChunkIndex: pc.Seq / seq}）</li>
     * </ol>
     *
     * <p>⚠️ <b>父块与子块的 {@code chunk_index} 在同一文档内会重复</b>（父块用自己的 0..M-1，
     * 子块用 0..N-1）。源站同样如此 —— 区分靠 {@code chunk_type}，⛔ 不要试图给父块加偏移量，
     * 那会让「按 seq 对齐原文」的既有逻辑全部错位。
     *
     * @param knowledge 知识条目（取 id / knowledgeBaseId）
     * @param children  子块（{@code parentIndex} 指向 {@code parents} 下标，{@code -1} = 无父块）
     * @param parents   保留的父块（可为空 = 等价普通切分，此时退回单列表语义）
     * @return 写入结果（{@code count} 含父块 + 子块）
     */
    @Transactional
    public WriteResult replaceChunks(KbKnowledgeRecord knowledge, List<KbChunk> children,
                                     List<KbChunk> parents) {
        if (parents == null || parents.isEmpty()) {
            return replaceFlat(knowledge, children == null ? List.of() : children);
        }
        String knowledgeId = knowledge.getId();
        String knowledgeBaseId = knowledge.getKnowledgeBaseId();

        // 1) 逻辑删旧块（父子与普通块一起清：同一 knowledge_id 下两轮产物不能共存）
        chunkMapper.deleteByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));

        String now = KbTimeFormat.now();

        // 2) 父块：先分配 id（子块要引用），再串父块之间的双向链
        List<String> parentIds = new ArrayList<>(parents.size());
        for (int i = 0; i < parents.size(); i++) {
            parentIds.add(UUID.randomUUID().toString());
        }
        long contentBytes = 0L;
        for (int i = 0; i < parents.size(); i++) {
            KbChunk parent = parents.get(i);
            contentBytes += utf8Len(parent.content());
            KbChunkRecord row = newRow(knowledgeBaseId, knowledgeId, parent, now);
            row.setId(parentIds.get(i));
            row.setChunkType(KbChunk.CHUNK_TYPE_PARENT_TEXT);
            row.setParentChunkId(null);
            row.setPreChunkId(i > 0 ? parentIds.get(i - 1) : null);
            row.setNextChunkId(i + 1 < parentIds.size() ? parentIds.get(i + 1) : null);
            chunkMapper.insert(row);
        }

        // 3) 子块：chunk_type='text'，parent_chunk_id 指父块，⛔ 不串 pre/next
        int imageCount = 0;
        int chunksWithImages = 0;
        for (KbChunk child : children) {
            contentBytes += utf8Len(child.content());
            KbChunkRecord row = newRow(knowledgeBaseId, knowledgeId, child, now);
            row.setId(UUID.randomUUID().toString());
            row.setChunkType(KbChunk.CHUNK_TYPE_TEXT);
            String parentId = null;
            int pi = child.parentIndex();
            if (pi >= 0 && pi < parentIds.size()) {
                parentId = parentIds.get(pi);
            } else if (pi >= 0) {
                // 下标越界 = 上游把两个列表配错了（父块被过滤过却没同步改下标）⇒ 留痕但不炸
                log.warn("[入库] 子块父指针下标越界，按无父块写入 knowledge={} seq={} parentIndex={} 父块数={}",
                    knowledgeId, child.seq(), pi, parentIds.size());
            }
            row.setParentChunkId(parentId);
            row.setPreChunkId(null);
            row.setNextChunkId(null);

            String imageInfo = toImageInfoJson(child.images());
            if (imageInfo != null) {
                row.setImageInfo(imageInfo);
                imageCount += child.images().size();
                chunksWithImages++;
            }
            chunkMapper.insert(row);

            if (imageInfo != null) {
                projectImages(row, child, knowledgeId);
            }
        }

        int total = parents.size() + children.size();
        log.info("[入库] 已写入父子分块 knowledge={} 父块={} 子块={} 内容字节={} 含图块={} 图片={}",
            knowledgeId, parents.size(), children.size(), contentBytes, chunksWithImages, imageCount);
        return new WriteResult(total, contentBytes, imageCount);
    }

    /**
     * 普通（非父子）写入主体 —— 原 {@link #replaceChunks(KbKnowledgeRecord, List)} 的逻辑原样搬入。
     */
    private WriteResult replaceFlat(KbKnowledgeRecord knowledge, List<KbChunk> chunks) {
        String knowledgeId = knowledge.getId();
        String knowledgeBaseId = knowledge.getKnowledgeBaseId();

        // 1) 逻辑删旧块（Flex 对 @Column(isLogicDelete=true) 会把 DELETE 改写成 UPDATE deleted=1）
        chunkMapper.deleteByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));

        if (chunks.isEmpty()) {
            log.info("[入库] 文档无分块可写 knowledge={}", knowledgeId);
            return new WriteResult(0, 0L, 0);
        }

        // 2) 先分配 ID，再串双向链（顺序链需要前后两块同时存在）
        List<String> ids = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }

        String now = KbTimeFormat.now();
        long contentBytes = 0L;
        int imageCount = 0;
        int chunksWithImages = 0;
        for (int i = 0; i < chunks.size(); i++) {
            KbChunk chunk = chunks.get(i);
            contentBytes += utf8Len(chunk.content());
            KbChunkRecord row = newRow(knowledgeBaseId, knowledgeId, chunk, now);
            row.setId(ids.get(i));
            row.setPreChunkId(i > 0 ? ids.get(i - 1) : null);
            row.setNextChunkId(i + 1 < ids.size() ? ids.get(i + 1) : null);
            row.setChunkType(KbChunk.CHUNK_TYPE_TEXT);
            row.setParentChunkId(null);

            String imageInfo = toImageInfoJson(chunk.images());
            if (imageInfo != null) {
                row.setImageInfo(imageInfo);
                imageCount += chunk.images().size();
                chunksWithImages++;
            }

            chunkMapper.insert(row);

            // 3) 投影该块的图片（同事务；镜像源侧触发器语义）
            if (imageInfo != null) {
                projectImages(row, chunk, knowledgeId);
            }
        }

        log.info("[入库] 已写入分块 knowledge={} 条数={} 内容字节={} 含图块={} 图片={}",
            knowledgeId, chunks.size(), contentBytes, chunksWithImages, imageCount);
        return new WriteResult(chunks.size(), contentBytes, imageCount);
    }

    /**
     * 建一行 {@code kb_chunk}（父子与普通路径共用）· 只填与内容相关的字段。
     *
     * <p>⚠️ 调用方必须自己补 {@code id / chunkType / pre / next / parent}：
     * 这几项在父子与普通模式下的取值规则不同，塞进来只会让下一处调用点忘记覆盖。
     */
    private static KbChunkRecord newRow(String knowledgeBaseId, String knowledgeId,
                                        KbChunk chunk, String now) {
        KbChunkRecord row = new KbChunkRecord();
        row.setKnowledgeBaseId(knowledgeBaseId);
        row.setKnowledgeId(knowledgeId);
        row.setContent(chunk.content());
        row.setChunkIndex(chunk.seq());
        row.setStartAt(chunk.start());
        row.setEndAt(chunk.end());
        row.setContextHeader(chunk.contextHeader() == null ? "" : chunk.contextHeader());

        // ⭐ 源位置定位 · 随分块一次写入（源站 knowledge_process.go:4271 构造 ParsedChunk 时
        //    就带上 SourceLocators，无二次 UPDATE）。
        //    null = 无定位 ⇒ 不补默认值：本列可空，NULL 就是「无定位」的语义
        //    （⛔ 不写 "[]"：那会让「有没有定位」的判断全变成解析 JSON）。
        row.setSourceLocators(chunk.sourceLocators());

        // ⚠️ MyBatis-Flex 的 insert 会把 null 字段也列进 INSERT 列清单 ⇒
        //    DB DEFAULT 不生效。所有带 DEFAULT 的 NOT NULL 列必须显式赋值。
        row.setIsEnabled(1);
        row.setSourceContent("");
        row.setContentRevision(0);
        row.setIndexStatus("ready");
        row.setLastEditorId("");
        row.setStatus(0);
        row.setFlags(1);

        // 审计列（Listener 只在 null 时补，这里显式写死更可控）
        row.setCreator("");
        row.setUpdater("");
        row.setDeleted(0);
        row.setTenantId(0L);
        row.setCreateTime(KbTimeFormat.parse(now));
        row.setUpdateTime(KbTimeFormat.parse(now));
        return row;
    }

    /** 投影单块图片 · 投影器未装配时留 info（⛔ 不静默）。 */
    private void projectImages(KbChunkRecord row, KbChunk chunk, String knowledgeId) {
        if (imageService == null) {
            log.info("[入库] 图片投影器未装配，仅写 image_info（跳过 kb_chunk_image）chunk={} 图片数={}",
                row.getId(), chunk.images().size());
        } else {
            imageService.projectChunk(row);
        }
    }

    /** 内容 UTF-8 字节数（null 安全）。 */
    private static long utf8Len(String content) {
        return content == null ? 0L
            : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * 分块图片 → {@code image_info} JSON（源侧 {@code chunk.image_info} 同构）。
     *
     * <p>字段名必须与 {@link KbImageService} 的解析端一致（{@code url} / {@code original_url} /
     * {@code caption} / {@code ocr_text} / {@code attrs} / {@code status}），否则投影表会整列落空，
     * 而库里看着「有 image_info」—— 最难查的那种半通。
     *
     * @param images 分块图片
     * @return JSON 数组文本；无图返回 null
     */
    private static String toImageInfoJson(List<KbChunk.Image> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        ArrayNode arr = JSON.createArrayNode();
        for (KbChunk.Image img : images) {
            ObjectNode node = JSON.createObjectNode();
            node.put("url", img.url());
            node.put("original_url", img.originalUrl());
            node.put("caption", img.caption());
            node.put("ocr_text", img.ocrText());
            ObjectNode attrs = JSON.createObjectNode();
            for (var e : img.attrs().entrySet()) {
                putAttr(attrs, e.getKey(), e.getValue());
            }
            node.set("attrs", attrs);
            node.put("status", img.status());
            arr.add(node);
        }
        try {
            return JSON.writeValueAsString(arr);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 序列化失败不该让整篇文档入库失败：图片是补充材料
            log.warn("[入库] image_info 序列化失败，本块按无图处理 图片数={} 原因={}",
                images.size(), e.toString());
            return null;
        }
    }

    private static void putAttr(ObjectNode attrs, String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        switch (value) {
            case String s -> attrs.put(key, s);
            case Integer i -> attrs.put(key, i);
            case Long l -> attrs.put(key, l);
            case Double d -> attrs.put(key, d);
            case Boolean b -> attrs.put(key, b);
            default -> attrs.put(key, String.valueOf(value));
        }
    }

    /**
     * 落库结果。
     *
     * @param count        写入的分块数
     * @param contentBytes 全部块内容的 UTF-8 字节总和（近似 {@code storage_size}）
     * @param imageCount   写入的图片数（{@code image_info} 里的条目总数）
     */
    public record WriteResult(int count, long contentBytes, int imageCount) {
    }

    /**
     * 删除某文档的全部分块（逻辑删）。
     *
     * @param knowledgeId 知识条目 ID
     */
    @Transactional
    public void deleteChunks(String knowledgeId) {
        chunkMapper.deleteByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
    }
}
