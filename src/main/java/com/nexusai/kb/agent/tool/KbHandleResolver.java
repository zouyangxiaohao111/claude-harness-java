package com.nexusai.kb.agent.tool;

import com.nexusai.kb.app.parser.KbParseException;
import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.kb.domain.KnowledgeService;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import com.nexusai.kb.dto.KnowledgeDto;
import com.nexusai.kb.dto.KnowledgePageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库工具参数的<b>稳定句柄</b>解析（{@code kb://<id 前 8 位>} / {@code doc://<id 前 8 位>}）。
 *
 * <h2>为什么是「稳定句柄」而不是序号句柄</h2>
 * 源站用 {@code kb://1} 这类序号，序号会随上下文重排 ⇒ 历史重放必须重新编码（本仓要改
 * {@code MessageService.listForResume*}，已明确禁止）。稳定句柄恒指向同一对象，
 * 历史里的旧句柄天然仍然有效。
 *
 * <h2>防幻觉</h2>
 * 模型写 {@code kb://99} / 写错前缀时<b>必须报错</b>，⛔ 不能静默查到空 ——
 * 「查了但查不到」会让模型以为知识库里没有该内容，从而编造答案。
 * 唯一命中返回全 id；多命中 / 零命中一律抛 {@link KbParseException}，
 * 由工具转成 {@code ToolResult.error} 让模型自行改正。
 *
 * <h2>兼容</h2>
 * 不以 {@code kb://} / {@code doc://} 开头 ⇒ 原样返回（模型直接给完整 UUID 仍然可用）。
 * 依赖服务缺失时同样原样返回并记 warn —— 句柄解析不该让整个工具挂掉。
 */
@Component
public class KbHandleResolver {

    private static final Logger log = LoggerFactory.getLogger(KbHandleResolver.class);

    public static final String KB_PREFIX = "kb://";
    public static final String DOC_PREFIX = "doc://";

    /** 句柄只保留前 8 位（UUID 的前 8 个十六进制字符）。 */
    private static final int HANDLE_CHARS = 8;

    /** 单页扫描条数（知识服务 list 的上限 1000）。 */
    private static final int SCAN_PAGE_SIZE = 1000;

    /** 每个库最多翻几页（防止超大库把一次工具调用拖死）。 */
    private static final int MAX_SCAN_PAGES = 5;

    private static final String TYPE_KB = "知识库";
    private static final String TYPE_DOC = "文档";

    /** 知识库服务 · 缺失时退回「原样返回」。 */
    @Autowired(required = false)
    private KnowledgeBaseService knowledgeBaseService;

    /** 知识条目服务 · 缺失时退回「原样返回」。 */
    @Autowired(required = false)
    private KnowledgeService knowledgeService;

    // ── 对外 ────────────────────────────────────────────────────────────────

    /**
     * 把 {@code kb://xxxx} 解析成完整知识库 id。
     *
     * @param raw 模型传入的原值（{@code kb://xxxx} 或完整 id）
     * @return 完整 id；不是句柄 / 服务缺失时原样返回
     * @throws KbParseException 无效句柄或多命中
     */
    public String resolveKnowledgeBase(String raw) {
        String handle = suffixIfHandle(raw, KB_PREFIX);
        if (handle == null) {
            return raw;
        }
        if (handle.isEmpty()) {
            throw invalid(raw, TYPE_KB);
        }
        if (knowledgeBaseService == null) {
            log.warn("[句柄] KnowledgeBaseService 不可用，{} 原样返回: {}", KB_PREFIX, raw);
            return raw;
        }
        List<String> hits = new ArrayList<>(1);
        try {
            for (KnowledgeBaseDto kb : knowledgeBaseService.list()) {
                if (kb != null && matches(kb.id(), handle)) {
                    hits.add(kb.id());
                }
            }
        } catch (RuntimeException e) {
            // 查询失败不当作「无效句柄」：那是服务问题，不是模型写错
            log.warn("[句柄] 知识库列表查询失败，{} 原样返回: {} 原因={}", KB_PREFIX, raw, e.toString());
            return raw;
        }
        return unique(hits, raw, TYPE_KB);
    }

    /**
     * 把 {@code doc://xxxx} 解析成完整文档 id（无库上下文）。
     *
     * <p>没有所属库时遍历全部知识库做前缀匹配（每库最多 {@link #MAX_SCAN_PAGES} 页）；
     * 服务不可用时退回 {@code getById} 全等匹配并记 warn。
     *
     * @param raw 模型传入的原值（{@code doc://xxxx} 或完整 id）
     * @return 完整 id；不是句柄 / 服务缺失时原样返回
     * @throws KbParseException 无效句柄或多命中
     */
    public String resolveDocument(String raw) {
        return resolveDocument(raw, null);
    }

    /**
     * 把 {@code doc://xxxx} 解析成完整文档 id（已知所属库时只扫该库）。
     *
     * @param raw              模型传入的原值（{@code doc://xxxx} 或完整 id）
     * @param knowledgeBaseIdHint 所属知识库 id；空 = 不知道，要求全局扫描
     * @return 完整 id；不是句柄 / 服务缺失时原样返回
     * @throws KbParseException 无效句柄或多命中
     */
    public String resolveDocument(String raw, String knowledgeBaseIdHint) {
        String handle = suffixIfHandle(raw, DOC_PREFIX);
        if (handle == null) {
            return raw;
        }
        if (handle.isEmpty()) {
            throw invalid(raw, TYPE_DOC);
        }
        if (knowledgeService == null) {
            log.warn("[句柄] KnowledgeService 不可用，{} 原样返回: {}", DOC_PREFIX, raw);
            return raw;
        }

        List<String> hits = new ArrayList<>(1);
        boolean scannable = knowledgeBaseService != null;
        if (scannable) {
            try {
                if (knowledgeBaseIdHint != null && !knowledgeBaseIdHint.isBlank()) {
                    hits.addAll(matchInBase(knowledgeBaseIdHint.trim(), handle));
                } else {
                    for (KnowledgeBaseDto kb : knowledgeBaseService.list()) {
                        if (kb == null) {
                            continue;
                        }
                        hits.addAll(matchInBase(kb.id(), handle));
                        if (hits.size() > 1) {
                            break;
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[句柄] 文档前缀扫描失败，退回全等匹配: {} 原因={}", raw, e.toString());
                hits.clear();
            }
        }
        if (hits.size() > 1) {
            throw notUnique(raw);
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }

        // ── 兜底：没有批量前缀查询能力时按全等匹配 ────────────────────────────
        String exact = exactDocId(handle);
        if (exact != null) {
            log.warn("[句柄] 无批量前缀查询能力（或未命中），按全等匹配兜底 {} → {}", raw, exact);
            return exact;
        }
        throw invalid(raw, TYPE_DOC);
    }

    /** 生成展示给模型的句柄（{@code kb://} + id 前 8 位）。 */
    public static String toHandle(String prefix, String id) {
        if (id == null) {
            return prefix;
        }
        String trimmed = id.trim();
        return prefix + (trimmed.length() <= HANDLE_CHARS ? trimmed : trimmed.substring(0, HANDLE_CHARS));
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 该库内按前缀匹配文档 id。 */
    private List<String> matchInBase(String kbId, String handle) {
        List<String> hits = new ArrayList<>(1);
        int page = 1;
        long seen = 0;
        while (page <= MAX_SCAN_PAGES) {
            KnowledgePageResponse<KnowledgeDto> resp;
            try {
                resp = knowledgeService.list(kbId, page, SCAN_PAGE_SIZE, null, null, null, null);
            } catch (RuntimeException e) {
                log.warn("[句柄] 扫描知识库文档失败 kb={} page={} 原因={}", kbId, page, e.toString());
                break;
            }
            if (resp == null || resp.data() == null || resp.data().isEmpty()) {
                break;
            }
            for (KnowledgeDto d : resp.data()) {
                if (d != null && matches(d.id(), handle)) {
                    hits.add(d.id());
                    if (hits.size() > 1) {
                        return hits;
                    }
                }
            }
            seen += resp.data().size();
            if (seen >= resp.total()) {
                break;
            }
            page++;
        }
        if (page > MAX_SCAN_PAGES && seen > 0) {
            log.warn("[句柄] 知识库 {} 文档超过 {} 页，前缀匹配只覆盖前 {} 条",
                kbId, MAX_SCAN_PAGES, seen);
        }
        return hits;
    }

    /** 全等匹配兜底：{@code getById} 成功即返回其 id，否则 null。 */
    private String exactDocId(String handle) {
        try {
            KnowledgeDto dto = knowledgeService.getById(handle);
            return dto == null ? null : dto.id();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 是句柄 ⇒ 返回前缀后的部分；不是 ⇒ null。 */
    private static String suffixIfHandle(String raw, String prefix) {
        if (raw == null || !raw.startsWith(prefix)) {
            return null;
        }
        return raw.substring(prefix.length()).trim();
    }

    /** id 是否以 prefix 开头（UUID 小写；忽略大小写更宽容）。 */
    private static boolean matches(String id, String prefix) {
        if (id == null || prefix == null || prefix.isEmpty() || id.length() < prefix.length()) {
            return false;
        }
        return id.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String unique(List<String> hits, String raw, String type) {
        if (hits.isEmpty()) {
            throw invalid(raw, type);
        }
        if (hits.size() > 1) {
            throw notUnique(raw);
        }
        return hits.get(0);
    }

    private static KbParseException notUnique(String raw) {
        return new KbParseException("句柄不唯一，请使用完整 id: " + raw);
    }

    private static KbParseException invalid(String raw, String type) {
        return new KbParseException("无效句柄: " + raw + "（未找到对应" + type + "）");
    }
}
