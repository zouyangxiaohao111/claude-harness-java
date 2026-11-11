package com.nexusai.kb.app.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkImageRecord;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkImageMapper;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库图片资产 · 批次 W4 · V269。
 *
 * <p><b>源侧对照</b>：WeKnora {@code chunk_images}（000113）+ {@code GET|POST /knowledge-bases/{id}/images}
 * + {@code GET /image-attrs/schema}。
 *
 * <h2>⭐⭐ 投影表不是真相</h2>
 * 真相永远是 {@code kb_chunk.image_info}。本服务做的两件事：
 * <ol>
 *   <li><b>重投影</b>：{@link #projectChunk} —— 每次写 chunk 的 image_info 后整块重算；</li>
 *   <li><b>查询</b>：{@link #list} —— 只读投影表，因为「解析每个 image_info 再过滤」在大库上很慢。</li>
 * </ol>
 * 投影允许滞后（源侧用触发器保证实时）。取舍：本仓没有 SQLite 触发器（Flex 写事务里
 * 混触发器会让并发问题难定位），换成应用层显式调用。代价 = 若有第三方直接写
 * {@code kb_chunk} 而没调用投影，图库会漏图；本仓所有写入都经过 {@code KbChunkWriter} /
 * {@code KbChunkService}，因此可控。
 *
 * <h2>⭐ 同一张图会出现多行</h2>
 * 源侧注释写明：「copies of one image stay separate rows, and the gallery query picks
 * the copy on the most recently updated chunk」。这是<b>有意设计</b>：
 * 同一张图可能出现在多个分块（image_ocr / image_caption 子块），
 * 每行代表「哪个块里出现了它」。查列表时按 {@code image_key} 取最新那一行去重。
 */
@Service
public class KbImageService {

    private static final Logger log = LoggerFactory.getLogger(KbImageService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 合法排序字段（白名单，⛔ 不接受任意列名 —— SQL 注入面）。 */
    private static final Set<String> SORT_FIELDS = Set.of("created_at", "updated_at", "status");

    /** 合法搜索范围。 */
    private static final Set<String> SEARCH_IN = Set.of("all", "caption", "ocr", "attrs");

    private final KbChunkImageMapper imageMapper;
    private final KbChunkMapper chunkMapper;
    private final KbKnowledgeMapper knowledgeMapper;

    /**
     * ⚠️ 本类<b>刻意不注入 JdbcTemplate</b>。
     *
     * <p>本仓的 {@code @Transactional} 由 MyBatis-Flex 的 {@code FlexTransactionManager} 接管，
     * 它不绑定 Spring 的 {@code DataSourceUtils}；而 {@code JdbcTemplate} 正是靠那个
     * ThreadLocal 复用事务连接。两者混用 ⇒ 事务内另借一条连接 ⇒ 抢 SQLite 写锁
     * ⇒ {@code SQLITE_BUSY}（已在 {@link #projectChunk} 上真实发生过）。
     *
     * <p>⇒ 本类全部读写都走 {@code imageMapper}，与外层事务同一条连接。
     * ⛔ 不要再为「写个原生 SQL 方便」把 JdbcTemplate 加回来。
     */
    @Autowired
    public KbImageService(KbChunkImageMapper imageMapper,
                          KbChunkMapper chunkMapper,
                          KbKnowledgeMapper knowledgeMapper) {
        this.imageMapper = imageMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeMapper = knowledgeMapper;
    }

    /**
     * 重投影一个分块的图片。
     *
     * <p>语义 = 全量替换该 chunk 在投影表里的所有行（先 delete 再 insert）。
     * ⚠️ 用「全量替换」而不是「增量比对」：image_info 是整块 JSON，
     * 增量比对的下标对齐很容易错位，而一个 chunk 的图片数是个位数，全量重算可忽略。
     *
     * <h2>⭐⭐ 为什么必须走 Flex Mapper 而不是 JdbcTemplate</h2>
     * 本方法会被 {@code @Transactional} 方法调用（{@code KbChunkWriter.replaceChunks} /
     * {@code KbKnowledgeExtService.updateImageInfo}），而本仓的 {@code @Transactional}
     * 由 MyBatis-Flex 的 {@code FlexTransactionManager} 接管
     * （{@code FlexTransactionAutoConfiguration implements TransactionManagementConfigurer}）。
     * 它 {@code doBegin} 只生成一个 XID 字符串，<b>不调用 Spring 的
     * {@code DataSourceUtils.bindResource}</b>；Flex 的 Mapper 从
     * {@code FlexDataSource.getConnection()} 按 XID 复用同一条连接，
     * 而 {@code JdbcTemplate} 走 {@code DataSourceUtils} 时 ThreadLocal 是空的
     * ⇒ 另借一条 Hikari 连接 ⇒ 同事务内<b>自己和自己抢 SQLite 写锁</b>。
     *
     * <p>实测（2026-11-09）：上传一份 1.81 MB 带 3 张图的 docx，
     * `DELETE FROM kb_chunk_image` 空转 5 秒后 {@code SQLITE_BUSY}，重试 3 次全部失败，
     * 而解析本身完全成功 ⇒ 用户看到「解析失败」但日志里没有任何解析错误。
     * ⇒ 投影表的<b>全部写操作</b>必须走 {@code imageMapper}，与读操作同一条连接。
     *
     * @param chunk kb_chunk 行（caller 已读库；本方法不读 chunk）
     */
    public void projectChunk(KbChunkRecord chunk) {
        if (chunk == null || chunk.getId() == null) {
            return;
        }
        List<Map<String, Object>> rows = parseImageInfo(chunk);

        // 全量替换：先清该块的所有投影行
        imageMapper.deleteByQuery(
            QueryWrapper.create().where("chunk_id = ?", chunk.getId()));

        if (rows.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<KbChunkImageRecord> batch = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            KbChunkImageRecord rec = new KbChunkImageRecord();
            rec.setChunkId(chunk.getId());
            rec.setImageIndex(i);
            rec.setKnowledgeBaseId(chunk.getKnowledgeBaseId());
            rec.setKnowledgeId(chunk.getKnowledgeId());
            rec.setChunkType(chunk.getChunkType() == null ? "" : chunk.getChunkType());
            rec.setImageKey(str(r.get("image_key")));
            rec.setUrl(str(r.get("url")));
            rec.setOriginalUrl(str(r.get("original_url")));
            rec.setCaption(str(r.get("caption")));
            rec.setOcrText(str(r.get("ocr_text")));
            rec.setAttrs(str(r.get("attrs")));
            rec.setIsEnabled(chunk.getIsEnabled() == null ? 1 : chunk.getIsEnabled());
            rec.setStatus(intOf(r.get("status")));
            // 时间走实体上的 KbSqliteLocalDateTimeTypeHandler ⇒ 输出
            // "yyyy-MM-dd HH:mm:ss"（与 KbTimeFormat.format 逐字节同款，探针已验证）
            rec.setCreatedAt(now);
            rec.setUpdatedAt(now);
            rec.setTenantId(0L);
            batch.add(rec);
        }
        imageMapper.insertBatch(batch);
    }

    /** 列值 → 文本（null 落空串：投影表这几列都是 {@code NOT NULL DEFAULT ''}）。 */
    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 列值 → int（null 落 0：{@code status} 列 {@code NOT NULL DEFAULT 0}）。 */
    private static int intOf(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 重投影某文档下所有分块的图片。
     *
     * <p>用于「整批改写」场景（复制知识库、重新解析后回填）——
     * 逐个块调 {@link #projectChunk} 的批量版。
     */
    public void projectKnowledge(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return;
        }
        List<KbChunkRecord> chunks = chunkMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
        int withImages = 0;
        for (KbChunkRecord c : chunks) {
            if (c.getImageInfo() == null || c.getImageInfo().isBlank()) {
                continue;
            }
            projectChunk(c);
            withImages++;
        }
        if (withImages > 0) {
            log.info("[图片投影] 文档 {} 重投影完成，含图分块={} 总块={}",
                knowledgeId, withImages, chunks.size());
        }
    }

    /**
     * 解析 image_info 数组。
     *
     * <p>字段名与源侧一致：{@code url} / {@code original_url} / {@code caption} /
     * {@code ocr_text} / {@code attrs}（其中真实属性在 {@code attrs.attrs}）。
     *
     * <p>⚠️ 坏一行不能炸掉整个重投影 —— 单个 chunk 的 image_info 是模型/解析器产出的，
     * 偶发格式不合法时应该跳过并 WARN，而不是让整篇文档入库失败。
     */
    private static List<Map<String, Object>> parseImageInfo(KbChunkRecord chunk) {
        List<Map<String, Object>> out = new ArrayList<>();
        String raw = chunk.getImageInfo();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            log.warn("[图片投影] image_info 不是合法 JSON，跳过 chunk={} 原因={}",
                chunk.getId(), e.getMessage());
            return out;
        }
        if (root == null || !root.isArray()) {
            return out;
        }
        int i = 0;
        for (JsonNode node : root) {
            if (!node.isObject()) {
                i++;
                continue;
            }
            String url = text(node, "url");
            String originalUrl = text(node, "original_url");
            String key = !url.isEmpty() ? url
                : !originalUrl.isEmpty() ? originalUrl
                : chunk.getId() + "#" + i;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("image_key", key);
            r.put("url", url);
            r.put("original_url", originalUrl);
            r.put("caption", text(node, "caption"));
            r.put("ocr_text", text(node, "ocr_text"));
            // 源侧 attrs 结构是 { "attrs": {...}, ... }；取内层 map，没有就存外层
            JsonNode attrs = node.get("attrs");
            if (attrs != null && attrs.isObject() && attrs.has("attrs")) {
                attrs = attrs.get("attrs");
            }
            r.put("attrs", attrs == null || attrs.isNull() ? "{}" : attrs.toString());
            r.put("status", node.has("status") && node.get("status").canConvertToInt()
                ? node.get("status").asInt() : 0);
            out.add(r);
            i++;
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    /**
     * 图片资产分页列表。
     *
     * @param kbId          知识库
     * @param page          页码（1 起）
     * @param pageSize      每页条数
     * @param keyword       关键词（在 caption / ocr_text / attrs 里找）
     * @param searchIn      all | caption | ocr | attrs
     * @param sortBy        created_at | updated_at | status
     * @param sortOrder     asc | desc
     * @param isEnabled     启用过滤（可空）
     * @param knowledgeIds  文档过滤（逗号分隔，可空）
     * @param attrFilters   attr=value 过滤（简化为「attrs LIKE」；完整表达式见源侧）
     */
    public Page list(String kbId, Integer page, Integer pageSize, String keyword, String searchIn,
                     String sortBy, String sortOrder, Boolean isEnabled, String knowledgeIds,
                     String attrFilters) {
        if (kbId == null || kbId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        int p = page == null || page <= 0 ? 1 : page;
        int size = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        String sort = sortBy == null || sortBy.isBlank() ? "created_at" : sortBy.trim();
        if (!SORT_FIELDS.contains(sort)) {
            throw new ValidationException("sort_by 只能是 " + SORT_FIELDS + "，收到: " + sortBy);
        }
        boolean asc = "asc".equalsIgnoreCase(sortOrder);

        // ⚠️ 条件用 QueryWrapper 拼，不走裸 SQL 字符串：
        //    本类早已因「JdbcTemplate 与 Flex 事务不同连接」炸过一次（见 projectChunk 的 javadoc），
        //    这里若继续用 jdbcTemplate，将来把它包进事务的人会再踩一次同一个坑。
        QueryWrapper qw = QueryWrapper.create();
        qw.and("knowledge_base_id = ?", kbId);
        if (isEnabled != null) {
            qw.and("is_enabled = ?", isEnabled ? 1 : 0);
        }
        if (knowledgeIds != null && !knowledgeIds.isBlank()) {
            String[] ids = knowledgeIds.split(",");
            List<String> trimmed = new ArrayList<>(ids.length);
            for (String id : ids) {
                trimmed.add(id.trim());
            }
            qw.and("knowledge_id IN (" + String.join(",",
                java.util.Collections.nCopies(trimmed.size(), "?")) + ")",
                trimmed.toArray());
        }
        if (keyword != null && !keyword.isBlank()) {
            String scope = searchIn == null || searchIn.isBlank() ? "all" : searchIn.trim();
            if (!SEARCH_IN.contains(scope)) {
                throw new ValidationException("search_in 只能是 " + SEARCH_IN + "，收到: " + searchIn);
            }
            String like = "%" + keyword.trim() + "%";
            switch (scope) {
                case "caption" -> qw.and("caption LIKE ?", like);
                case "ocr" -> qw.and("ocr_text LIKE ?", like);
                case "attrs" -> qw.and("attrs LIKE ?", like);
                default -> qw.and("(caption LIKE ? OR ocr_text LIKE ? OR attrs LIKE ?)",
                    like, like, like);
            }
        }
        if (attrFilters != null && !attrFilters.isBlank()) {
            // 源侧支持完整表达式；本仓先支持「属性名=值」的逗号列表（够用且不引入解析器）
            for (String pair : attrFilters.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                qw.and("attrs LIKE ?", "%\"" + pair.substring(0, eq).trim() + "\"%"
                    + pair.substring(eq + 1).trim() + "%");
            }
        }

        long total = imageMapper.selectCountByQuery(qw);

        // 排序 + 分页（sort / asc 已在上面白名单校验，拼进 ORDER BY 是安全的）。
        // ⚠️ 复用同一个 qw 实例：Flex 的 QueryWrapper 是可变构建器，
        //    count 执行不消耗它；这里补上 ORDER BY / LIMIT 就是列表查询。
        qw.orderBy(sort, asc)
            .orderBy("chunk_id", true)
            .orderBy("image_index", true)
            .limit((p - 1) * size, size);

        List<KbChunkImageRecord> records = imageMapper.selectListByQuery(qw);
        List<ImageAsset> items = new ArrayList<>(records.size());
        Map<String, String> titleCache = new HashMap<>();
        for (KbChunkImageRecord rec : records) {
            ImageAsset a = new ImageAsset(
                rec.getChunkId() + "#" + rec.getImageIndex(),
                rec.getChunkId(),
                rec.getKnowledgeId(),
                rec.getChunkType(),
                rec.getUrl(),
                rec.getOriginalUrl(),
                rec.getCaption(),
                rec.getOcrText(),
                rec.getAttrs(),
                rec.getIsEnabled() == null || rec.getIsEnabled() != 0,
                rec.getStatus() == null ? 0 : rec.getStatus(),
                KbTimeFormat.format(rec.getCreatedAt()),
                KbTimeFormat.format(rec.getUpdatedAt()),
                "");
            String title = titleCache.computeIfAbsent(a.knowledgeId(), this::lookupTitle);
            items.add(a.withSourceName(title));
        }

        return new Page(items, p, size, (int) total);
    }

    private String lookupTitle(String knowledgeId) {
        try {
            KbKnowledgeRecord row = knowledgeMapper.selectOneById(knowledgeId);
            return row == null ? "" : (row.getTitle() == null ? "" : row.getTitle());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 图片属性字典。
     *
     * <p>⭐ 源侧返回的是「配置里声明的属性」而不是「库里出现过的属性」——
     * 因为前端要渲染<b>可选项</b>，如果按库里扫，新库会一个筛选项都没有。
     * 本仓同理：从已声明配置读；没有配置时返回空列表（⛔ 不编造属性名）。
     */
    public AttrSchema attrSchema() {
        // 源侧 schema 由配置驱动（版本号 + prompt + attributes 列表）。
        // 本仓尚无从配置读该 schema 的来源 ⇒ 返回空 attributes，但形状完整。
        // ⚠️ 不返回假属性：用户会据此以为系统支持某种筛选，实际查不到。
        return new AttrSchema("1", "", List.of(), Map.of("regenerate", false, "retranslate", false));
    }

    // ── DTO ─────────────────────────────────────────────────────────

    /** 单张图片资产（snake_case，对齐源侧 {@code types.ImageAsset}）。 */
    public record ImageAsset(
        @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_id") String chunkId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_id") String knowledgeId,
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_type") String chunkType,
        @com.fasterxml.jackson.annotation.JsonProperty("url") String url,
        @com.fasterxml.jackson.annotation.JsonProperty("original_url") String originalUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("caption") String caption,
        @com.fasterxml.jackson.annotation.JsonProperty("ocr_text") String ocrText,
        @com.fasterxml.jackson.annotation.JsonProperty("attrs") String attrs,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") boolean isEnabled,
        @com.fasterxml.jackson.annotation.JsonProperty("status") int status,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") String createdAt,
        @com.fasterxml.jackson.annotation.JsonProperty("updated_at") String updatedAt,
        @com.fasterxml.jackson.annotation.JsonProperty("source_name") String sourceName
    ) {
        ImageAsset withSourceName(String name) {
            return new ImageAsset(id, chunkId, knowledgeId, chunkType, url, originalUrl, caption,
                ocrText, attrs, isEnabled, status, createdAt, updatedAt, name);
        }
    }

    /** 分页结果。 */
    public record Page(
        @com.fasterxml.jackson.annotation.JsonProperty("data") List<ImageAsset> data,
        @com.fasterxml.jackson.annotation.JsonProperty("page") int page,
        @com.fasterxml.jackson.annotation.JsonProperty("page_size") int pageSize,
        @com.fasterxml.jackson.annotation.JsonProperty("total") int total) {
    }

    /** 属性字典。 */
    public record AttrSchema(
        @com.fasterxml.jackson.annotation.JsonProperty("version") String version,
        @com.fasterxml.jackson.annotation.JsonProperty("prompt") String prompt,
        @com.fasterxml.jackson.annotation.JsonProperty("attributes") List<AttrDef> attributes,
        @com.fasterxml.jackson.annotation.JsonProperty("default_actions")
        Map<String, Boolean> defaultActions) {
    }

    /** 单个属性定义。 */
    public record AttrDef(
        @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
        @com.fasterxml.jackson.annotation.JsonProperty("label") String label,
        @com.fasterxml.jackson.annotation.JsonProperty("type") String type,
        @com.fasterxml.jackson.annotation.JsonProperty("values") List<String> values) {
    }

    /** 供其它服务使用：去重后的图片 key 集合（调试 / 统计用）。 */
    public Set<String> distinctKeys(String kbId) {
        QueryWrapper qw = QueryWrapper.create()
            .select("DISTINCT image_key")
            .where("knowledge_base_id = ?", kbId);
        List<Object> keys = imageMapper.selectObjectListByQuery(qw);
        Set<String> out = new LinkedHashSet<>(keys.size());
        for (Object k : keys) {
            if (k != null) {
                out.add(String.valueOf(k));
            }
        }
        return out;
    }

    /** 删除某个知识下的全部投影行（源侧触发器在 hard delete 时做的事）。 */
    public void deleteByKnowledgeId(String knowledgeId) {
        imageMapper.deleteByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
    }

    /** 删除某个块的投影行。 */
    public void deleteByChunkId(String chunkId) {
        imageMapper.deleteByQuery(
            QueryWrapper.create().where("chunk_id = ?", chunkId));
    }

    /** 供测试 / 其它服务读投影行。 */
    public List<KbChunkImageRecord> listProjection(String chunkId) {
        return new ArrayList<>(imageMapper.selectListByQuery(
            QueryWrapper.create().where("chunk_id = ?", chunkId).orderBy("image_index", true)));
    }

    /** 读一个块的投影（Controller 用）。 */
    public List<KbChunkImageRecord> listByChunk(String chunkId) {
        KbChunkRecord chunk = chunkMapper.selectOneById(chunkId);
        if (chunk == null) {
            throw new NotFoundException("分块 " + chunkId + " 不存在");
        }
        return listProjection(chunkId);
    }
}
