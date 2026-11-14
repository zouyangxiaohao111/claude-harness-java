package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ExclusiveTool;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.kb.app.chunk.KbChunkService;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 读文档内容（{@code read_document}）· 入参对齐源侧 {@code internal/agent/tools/read_document.go}。
 *
 * <h2>两种寻址方式（源站语义）</h2>
 * <ul>
 *   <li>{@code id=doc://N} —— 读一篇文档，从 {@code offset} 起按<b>阅读序</b>分页；
 *       可加 {@code query} 在文档内定位段落，或加 {@code query + regex}</li>
 *   <li>{@code id=chunk://N} —— 只读一个分块；{@code context=k} 追加前后各 k 个相邻分块</li>
 * </ul>
 *
 * <h2>⭐⭐ {@code offset} 是「阅读序」不是 {@code chunk_index}</h2>
 * 源站明确这一点：offset 对应「已输出多少块」的游标，翻页用返回值里的 {@code next_offset}。
 * 这与 {@code chunk_index} 不同 —— 后者是入库时的固定编号，中间可能有空洞或禁用的块。
 * 所以本实现维护一个<b>过滤后的可读序列</b>，offset 指向该序列的下标。
 *
 * <h2>⭐⭐ 一页可能少于 limit</h2>
 * 源站有输出预算：单页最多 {@link #MAX_TOTAL_CHARS} 字符。超预算就提前结束这一页，
 * 用 {@code next_offset} 续读。<b>不要</b>因为「返回条数 < limit」就认为文档读完了。
 *
 * <h2>作用域</h2>
 * 先用 {@link KbAgentToolScope} 校验所属知识库是否被授权 ——
 * ⛔ 不能只信模型传进来的 id（那等于把权限判断交给模型）。
 */
@Component
@ExclusiveTool
public class KbReadDocumentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbReadDocumentTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 单页最多返回多少块 · 源站 {@code DefaultLimit=20} / {@code MaxLimit=100}。 */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    /** {@code context} 上限 · 源站 {@code MaxContext=5}。 */
    static final int MAX_CONTEXT = 5;

    /** 单页正文总字符预算。 */
    static final int MAX_TOTAL_CHARS = 20_000;

    /** 单页最多返回多少条匹配 · 源站 {@code MaxMatches=20}。 */
    static final int MAX_MATCHES = 20;

    private static final String DOC_PREFIX = "doc://";
    private static final String CHUNK_PREFIX = "chunk://";

    @Autowired private KbChunkService chunkService;
    @Autowired private KbHandleResolver handleResolver;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "read_document";
    }

    @Override
    public String description() {
        return "读取文档或分块内容。"
            + " id=doc://N 读一篇文档：从 offset 起按阅读序分页（一页可能少于 limit，"
            + "续读用返回的 next_offset）；可用 query 在文档内定位段落，regex=true 时 query 按正则匹配。"
            + " id=chunk://N 只读一个分块，context=k 追加前后各 k 个相邻分块（k 最大 " + MAX_CONTEXT + "）。"
            + " 已经完整返回过的内容不必再读。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode id = JSON.objectNode();
        id.put("type", "string");
        id.put("description",
            "文档或分块的引用。doc://N 读整篇文档（N 为 knowledge_id 或其前 8 位稳定句柄，"
                + "如 doc://77a74fe1）；"
                + "chunk://N 读单个分块（N 为 chunk_id）。");
        props.set("id", id);

        ObjectNode offset = JSON.objectNode();
        offset.put("type", "integer");
        offset.put("description",
            "阅读序游标（从 0 起），只有 doc://N 有意义。翻页用上一次返回的 next_offset。");
        offset.put("minimum", 0);
        props.set("offset", offset);

        ObjectNode limit = JSON.objectNode();
        limit.put("type", "integer");
        limit.put("description",
            "本页最多返回多少块（默认 " + DEFAULT_LIMIT + "，范围 1.." + MAX_LIMIT + "）。"
                + "注意一页可能少于这个数：返回条数少于 limit 不代表文档读完了，请看 next_offset。");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        props.set("limit", limit);

        ObjectNode query = JSON.objectNode();
        query.put("type", "string");
        query.put("description",
            "可选：在文档内定位包含该文本的块。按空格切分后<b>全词 AND</b>（大小写不敏感）。"
                + "regex=true 时按正则表达式解释。");
        props.set("query", query);

        ObjectNode regex = JSON.objectNode();
        regex.put("type", "boolean");
        regex.put("description", "为 true 时 query 按正则表达式处理，而不是全词 AND。默认 false。");
        props.set("regex", regex);

        ObjectNode context = JSON.objectNode();
        context.put("type", "integer");
        context.put("description",
            "id=chunk://N 时，额外返回前后各 k 个相邻分块（0.." + MAX_CONTEXT + "，默认 0）。");
        context.put("minimum", 0);
        context.put("maximum", MAX_CONTEXT);
        props.set("context", context);

        ArrayNode required = JSON.arrayNode();
        required.add("id");
        root.set("required", required);
        return root;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        try {
            JsonNode in = call.input();
            String rawId = in.path("id").asText("").trim();
            if (rawId.isEmpty()) {
                return ToolResult.error(call.id(), "id 不能为空（doc://知识库/文档 或 chunk://分块）");
            }
            int limit = clamp(in.path("limit").asInt(DEFAULT_LIMIT), 1, MAX_LIMIT);
            int context = clamp(in.path("context").asInt(0), 0, MAX_CONTEXT);
            String query = in.path("query").asText("").trim();
            boolean regex = in.path("regex").asBoolean(false);

            if (rawId.startsWith(CHUNK_PREFIX)) {
                return readChunk(call, rawId.substring(CHUNK_PREFIX.length()).trim(), context, ctx);
            }
            // ⭐ 文档 id 先过句柄解析（doc://xxxx）：无效 / 多命中 ⇒ 明确报错，让模型改正再试。
            //   ⛔ 不能 catch 后继续用原值 —— 那会把「写错句柄」变成「文档没有可用分块」，
            //   模型会以为文档是空的（防幻觉的核心价值就在这里）。
            String rawDocId = rawId.startsWith(DOC_PREFIX)
                ? rawId.substring(DOC_PREFIX.length()).trim()
                : rawId;
            if (rawDocId.isEmpty()) {
                return ToolResult.error(call.id(), "doc:// 后面缺少 knowledge_id");
            }
            if (!rawId.startsWith(DOC_PREFIX)) {
                // 容错：没带前缀但看起来像 knowledge_id（历史调用 / 模型简化）
                log.warn("[read_document] id 缺少 doc:// 或 chunk:// 前缀，按文档处理 id={}", rawId);
            }
            String knowledgeId;
            try {
                knowledgeId = handleResolver.resolveDocument(rawDocId);
            } catch (com.nexusai.kb.app.parser.KbParseException e) {
                return ToolResult.error(call.id(), e.getMessage());
            }
            int offset = Math.max(0, in.path("offset").asInt(0));
            return readDoc(call, knowledgeId, offset, limit, query, regex, ctx);
        } catch (RuntimeException e) {
            log.error("[read_document] 执行失败", e);
            return ToolResult.error(call.id(), "读取失败：" + e.getMessage());
        }
    }

    // ── doc://N ────────────────────────────────────────────────────────────

    private AgentToolResult<?> readDoc(ToolUseBlock call, String knowledgeId, int offset, int limit,
                                       String query, boolean regex, ToolUseContext ctx) {
        List<KbChunkRecord> all;
        try {
            all = chunkService.list(knowledgeId, false);
        } catch (RuntimeException e) {
            log.warn("[read_document] 读分块失败 knowledge={}: {}", knowledgeId, e.toString());
            return ToolResult.error(call.id(), "读取文档失败：" + e.getMessage());
        }
        if (all == null || all.isEmpty()) {
            return ToolResult.success(call.id(),
                "{\"chunks\":[],\"next_offset\":0,\"note\":\"该文档没有可用分块，可能尚未解析完成或已被停用\"}");
        }

        String denied = checkScope(all.get(0).getKnowledgeBaseId(), knowledgeId, ctx);
        if (denied != null) {
            return ToolResult.error(call.id(), denied);
        }

        all.sort((a, b) -> {
            int ai = a.getChunkIndex() == null ? 0 : a.getChunkIndex();
            int bi = b.getChunkIndex() == null ? 0 : b.getChunkIndex();
            return Integer.compare(ai, bi);
        });

        // ── query 模式：在全文里找匹配块 ─────────────────────────────────────
        if (!query.isEmpty()) {
            return searchInDoc(call, knowledgeId, all, query, regex);
        }

        // ── 分页模式：按阅读序从 offset 起 ───────────────────────────────────
        int total = all.size();
        if (offset >= total) {
            return ToolResult.success(call.id(),
                "{\"chunks\":[],\"next_offset\":" + total + ",\"total\":" + total
                    + ",\"note\":\"offset 已到达文档末尾\"}");
        }

        StringBuilder sb = new StringBuilder("{\"chunks\":[");
        int used = 0;
        int returned = 0;
        int cursor = offset;
        for (; cursor < total && returned < limit; cursor++) {
            KbChunkRecord c = all.get(cursor);
            String text = c.getContent() == null ? "" : c.getContent();
            boolean overBudget = used + text.length() > MAX_TOTAL_CHARS;
            if (overBudget && returned > 0) {
                // 预算用尽：本页到此为止，cursor 不动（下一块的 offset 就是它）
                break;
            }
            if (overBudget) {
                // 单块就超预算：截断后返回，否则短预算下永远空
                text = KbSearchKnowledgeTool.truncate(text, MAX_TOTAL_CHARS);
            }
            if (returned > 0) {
                sb.append(',');
            }
            sb.append(chunkJson(c, text));
            used += text.length();
            returned++;
        }

        int nextOffset = offset + returned;
        sb.append("],\"next_offset\":").append(nextOffset)
          .append(",\"total\":").append(total)
          .append(",\"returned\":").append(returned);
        if (nextOffset < total) {
            sb.append(",\"note\":\"还有 ").append(total - nextOffset)
              .append(" 块未返回，续读请传 offset=").append(nextOffset).append("\"");
        }
        sb.append('}');

        log.info("[read_document] 读取完成 knowledge={} offset={} 返回={}/{} next={}",
            knowledgeId, offset, returned, total, nextOffset);
        return ToolResult.success(call.id(), sb.toString());
    }

    /** 文档内按 query 定位匹配块。 */
    private AgentToolResult<?> searchInDoc(ToolUseBlock call, String knowledgeId,
                                          List<KbChunkRecord> all, String query, boolean regex) {
        Pattern pattern = null;
        if (regex) {
            try {
                pattern = Pattern.compile(query);
            } catch (PatternSyntaxException e) {
                return ToolResult.error(call.id(), "正则表达式无效：" + e.getMessage());
            }
            // 防 ReDoS：嵌套量词 + 长输入会让回溯爆炸（本仓有过 Java 正则移植爆栈的先例）
            if (looksCatastrophic(query)) {
                log.warn("[read_document] 正则疑似高回溯风险，仍继续 pattern={}", query);
            }
        }
        String[] terms = regex ? null : query.toLowerCase(java.util.Locale.ROOT).split("\\s+");

        List<KbChunkRecord> matched = new ArrayList<>();
        for (KbChunkRecord c : all) {
            String text = c.getContent() == null ? "" : c.getContent();
            boolean hit;
            if (regex) {
                hit = pattern.matcher(text).find();
            } else {
                String lower = text.toLowerCase(java.util.Locale.ROOT);
                hit = true;
                for (String t : terms) {
                    if (!t.isEmpty() && !lower.contains(t)) {
                        hit = false;
                        break;
                    }
                }
            }
            if (hit) {
                matched.add(c);
                if (matched.size() >= MAX_MATCHES) {
                    break;
                }
            }
        }

        if (matched.isEmpty()) {
            return ToolResult.success(call.id(),
                "{\"chunks\":[],\"next_offset\":0,\"total\":" + all.size()
                    + ",\"note\":\"文档内没有匹配 " + (regex ? "正则" : "全部关键词")
                    + " 的分块；可放宽 query 或改用 search_knowledge\"}");
        }

        StringBuilder sb = new StringBuilder("{\"chunks\":[");
        for (int i = 0; i < matched.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            KbChunkRecord c = matched.get(i);
            sb.append(chunkJson(c, c.getContent()));
        }
        sb.append("],\"next_offset\":0,\"total\":").append(all.size())
          .append(",\"matched\":").append(matched.size());
        if (matched.size() >= MAX_MATCHES) {
            sb.append(",\"note\":\"匹配数已达上限 ").append(MAX_MATCHES).append("，请收窄 query\"");
        }
        sb.append('}');

        log.info("[read_document] 文档内定位 knowledge={} 匹配={}/{} regex={} query={}",
            knowledgeId, matched.size(), all.size(), regex, KbSearchKnowledgeTool.truncate(query, 40));
        return ToolResult.success(call.id(), sb.toString());
    }

    // ── chunk://N ──────────────────────────────────────────────────────────

    private AgentToolResult<?> readChunk(ToolUseBlock call, String chunkId, int context,
                                        ToolUseContext ctx) {
        if (chunkId.isEmpty()) {
            return ToolResult.error(call.id(), "chunk:// 后面缺少 chunk_id");
        }
        KbChunkRecord chunk;
        try {
            chunk = chunkService.getById(chunkId);
        } catch (RuntimeException e) {
            log.warn("[read_document] 读分块失败 chunk={}: {}", chunkId, e.toString());
            return ToolResult.error(call.id(), "读取分块失败：" + e.getMessage());
        }
        if (chunk == null) {
            return ToolResult.success(call.id(),
                "{\"chunks\":[],\"note\":\"找不到该分块（可能已被删除）\"}");
        }

        String denied = checkScope(chunk.getKnowledgeBaseId(), chunk.getKnowledgeId(), ctx);
        if (denied != null) {
            return ToolResult.error(call.id(), denied);
        }

        // context>0 时取相邻块：同文档的分块列表里按 chunk_index 定位前后
        List<KbChunkRecord> picked = new ArrayList<>();
        if (context > 0) {
            List<KbChunkRecord> siblings;
            try {
                siblings = chunkService.list(chunk.getKnowledgeId(), false);
            } catch (RuntimeException e) {
                log.warn("[read_document] 取相邻块失败 knowledge={}: {}",
                    chunk.getKnowledgeId(), e.toString());
                siblings = List.of(chunk);
            }
            siblings.sort((a, b) -> {
                int ai = a.getChunkIndex() == null ? 0 : a.getChunkIndex();
                int bi = b.getChunkIndex() == null ? 0 : b.getChunkIndex();
                return Integer.compare(ai, bi);
            });
            int at = -1;
            for (int i = 0; i < siblings.size(); i++) {
                if (chunkId.equals(siblings.get(i).getId())) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                picked.add(chunk);
            } else {
                int from = Math.max(0, at - context);
                int to = Math.min(siblings.size() - 1, at + context);
                picked.addAll(siblings.subList(from, to + 1));
            }
        } else {
            picked.add(chunk);
        }

        StringBuilder sb = new StringBuilder("{\"chunks\":[");
        for (int i = 0; i < picked.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            KbChunkRecord c = picked.get(i);
            sb.append(chunkJson(c, c.getContent()));
        }
        sb.append("],\"next_offset\":0}");
        log.info("[read_document] 读取分块 chunk={} context={} 返回块数={}", chunkId, context, picked.size());
        return ToolResult.success(call.id(), sb.toString());
    }

    // ── 公共 ───────────────────────────────────────────────────────────────

    /**
     * 作用域校验。
     *
     * @return 拒绝理由；通过时返回 {@code null}
     */
    private String checkScope(String kbId, String knowledgeId, ToolUseContext ctx) {
        if (scope == null) {
            return null;
        }
        List<String> allowed = scope.allowedKnowledgeBaseIds(ctx);
        if (allowed == null) {
            return null;
        }
        if (allowed.isEmpty() || !allowed.contains(kbId)) {
            log.warn("[read_document] 拒绝越权读取 knowledge={} kb={}（不在智能体授权范围）",
                knowledgeId, kbId);
            return "该文档所在知识库不在本智能体的授权范围内，不能读取";
        }
        return null;
    }

    private static String chunkJson(KbChunkRecord c, String text) {
        return "{\"chunk_id\":\"" + KbSearchKnowledgeTool.escape(c.getId())
            + "\",\"chunk_index\":" + (c.getChunkIndex() == null ? 0 : c.getChunkIndex())
            + ",\"content\":\"" + KbSearchKnowledgeTool.escape(text) + "\"}";
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        return Math.min(v, max);
    }

    /**
     * 粗判正则是否有灾难性回溯风险（嵌套量词形如 {@code (a+)+}、{@code (a*)*}）。
     *
     * <p>只用于<b>告警</b>，不阻断 —— 用户/模型写的正则可能有正当用途。
     * 完整防护应靠超时，但 Java 的 {@link Pattern} 不支持匹配超时，
     * 故此处只做可观测性（本仓有过正则回溯爆栈的先例）。
     */
    static boolean looksCatastrophic(String regex) {
        return regex.contains("+)+") || regex.contains("*)*") || regex.contains("+)*")
            || regex.contains("*)+") || regex.contains("?)+") || regex.contains("?)*");
    }
}
