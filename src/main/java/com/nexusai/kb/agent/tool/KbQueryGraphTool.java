package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ExclusiveTool;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.kb.app.graph.KbGraphSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询知识图谱（{@code query_knowledge_graph}）· 入参对齐源侧
 * {@code internal/agent/tools/query_knowledge_graph.go}。
 *
 * <p><b>⭐⭐ 入参已改（从单个 {@code knowledge_base_id} 改为数组 {@code knowledge_base_ids}）</b>：
 * 源站 schema 是 {@code {knowledge_base_ids: array, query: string}}，本仓旧实现是
 * {@code {knowledge_base_id, query, max_entities, max_relations}}。
 * 用户 2026-11-10 裁定「去掉本仓自加的 max_entities / max_relations」——
 * 那些是内部预算参数，不该暴露给模型（模型不知道合适值，只会瞎填）。
 *
 * <p><b>⭐⭐ 默认不激活</b>：标 {@link ExclusiveTool} ⇒ 只有 MD 的 {@code tools:} 点名才可见。
 *
 * <p><b>数据来源</b>：W7 已建的 {@code KbGraphSearchService}（两张 SQLite 表，
 * 实体/关系/权重都在）。本工具只做「工具契约 → 现有服务」的翻译，⛔ 不复制算法。
 */
@Component
@ExclusiveTool
public class KbQueryGraphTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbQueryGraphTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 内部返回条数（远小于服务的硬顶 500/1000/300，避免一次塞爆上下文）。 */
    private static final int DEFAULT_ENTITIES = 30;
    private static final int DEFAULT_RELATIONS = 50;
    private static final int DEFAULT_CHUNKS = 0;

    /** 可指定的知识库 id 上限 · 与 search_knowledge 一致。 */
    private static final int MAX_KB_IDS = 10;

    @Autowired private KbGraphSearchService graphSearchService;
    @Autowired private KbHandleResolver handleResolver;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "query_knowledge_graph";
    }

    @Override
    public String description() {
        return "在知识库的实体关系图中查询与问题相关的实体与关系。"
            + " 适合回答「A 和 B 是什么关系」「有哪些 X 类型的东西」这类结构化问题。"
            + " 只查询本智能体被授权知识库里的图。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode kbIds = JSON.objectNode();
        kbIds.put("type", "array");
        kbIds.put("description",
            "要查询的知识库 id 列表（最多 " + MAX_KB_IDS + " 个）。"
                + "可传 kb:// 句柄或完整 id。"
                + "省略时查本智能体被授权的全部知识库。");
        ObjectNode items = JSON.objectNode();
        items.put("type", "string");
        kbIds.set("items", items);
        props.set("knowledge_base_ids", kbIds);

        ObjectNode q = JSON.objectNode();
        q.put("type", "string");
        q.put("description", "检索词（实体名或关键词；不传 = 返回度数最高的实体）");
        props.set("query", q);
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
            String query = in.path("query").asText("").trim();
            // ⭐ 句柄 kb://xxxx → 全 id；无效 / 多命中 ⇒ 明确报错，让模型改正再试。
            List<String> requested = new java.util.ArrayList<>();
            try {
                for (String raw : KbSearchKnowledgeTool.readStringArray(
                        in, "knowledge_base_ids", MAX_KB_IDS)) {
                    String id = handleResolver.resolveKnowledgeBase(raw);
                    if (!requested.contains(id)) {
                        requested.add(id);
                    }
                }
            } catch (com.nexusai.kb.app.parser.KbParseException e) {
                return ToolResult.error(call.id(), e.getMessage());
            }

            List<String> allowed = scope == null ? null : scope.allowedKnowledgeBaseIds(ctx);
            if (allowed != null && allowed.isEmpty()) {
                return ToolResult.success(call.id(),
                    "{\"entities\":[],\"relationships\":[],\"note\":\"该智能体未授权任何知识库\"}");
            }

            // ── 解析目标库：显式传入的 ∩ 授权的 ──────────────────────────────
            List<String> targets;
            String scopeNote = "";
            if (requested.isEmpty()) {
                if (allowed == null) {
                    return ToolResult.error(call.id(),
                        "需要 knowledge_base_ids：未绑定智能体时无法确定在哪个库里查图");
                }
                targets = allowed;
            } else {
                targets = new java.util.ArrayList<>();
                List<String> rejected = new java.util.ArrayList<>();
                for (String want : requested) {
                    if (allowed == null || allowed.contains(want)) {
                        targets.add(want);
                    } else {
                        rejected.add(want);
                    }
                }
                if (!rejected.isEmpty()) {
                    // ⛔ 不静默丢弃：模型以为查了，实际没查
                    scopeNote = ",\"note\":\"已忽略无权访问的知识库：" + String.join(", ", rejected) + "\"";
                    log.warn("[query_knowledge_graph] 请求的知识库无权访问，已跳过 ids={}", rejected);
                }
                if (targets.isEmpty()) {
                    return ToolResult.success(call.id(),
                        "{\"entities\":[],\"relationships\":[],\"note\":\"请求的知识库均不在本智能体的授权范围内\"}");
                }
            }

            int maxEntities = DEFAULT_ENTITIES;
            int maxRelations = DEFAULT_RELATIONS;

            KbGraphSearchService.Result merged = null;
            for (String kbId : targets) {
                try {
                    KbGraphSearchService.Result r = graphSearchService.search(kbId,
                        new KbGraphSearchService.Request(query, maxEntities, maxRelations, DEFAULT_CHUNKS));
                    merged = merge(merged, r, maxEntities, maxRelations);
                } catch (RuntimeException e) {
                    log.warn("[query_knowledge_graph] 库查询失败 kb={} 原因={}", kbId, e.toString());
                }
            }
            if (merged == null) {
                return ToolResult.success(call.id(),
                    "{\"entities\":[],\"relationships\":[]" + scopeNote + "}");
            }
            log.info("[query_knowledge_graph] 查询完成 库数={} 实体={} 关系={}",
                targets.size(), merged.entities().size(), merged.relationships().size());
            return ToolResult.success(call.id(), render(merged, scopeNote));
        } catch (RuntimeException e) {
            log.error("[query_knowledge_graph] 执行失败", e);
            return ToolResult.error(call.id(), "查询图谱失败：" + e.getMessage());
        }
    }

    /** 多库合并：按 id 去重，按总条数封顶。 */
    private static KbGraphSearchService.Result merge(KbGraphSearchService.Result a,
                                                    KbGraphSearchService.Result b,
                                                    int maxEntities, int maxRelations) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Map<String, KbGraphSearchService.Entity> es = new LinkedHashMap<>();
        for (KbGraphSearchService.Entity e : a.entities()) {
            es.putIfAbsent(e.id(), e);
        }
        for (KbGraphSearchService.Entity e : b.entities()) {
            if (es.size() >= maxEntities) {
                break;
            }
            es.putIfAbsent(e.id(), e);
        }
        Map<String, KbGraphSearchService.Relation> rs = new LinkedHashMap<>();
        for (KbGraphSearchService.Relation r : a.relationships()) {
            rs.putIfAbsent(r.id(), r);
        }
        for (KbGraphSearchService.Relation r : b.relationships()) {
            if (rs.size() >= maxRelations) {
                break;
            }
            rs.putIfAbsent(r.id(), r);
        }
        return new KbGraphSearchService.Result(
            a.knowledgeBaseId(), a.query(),
            List.copyOf(es.values()), List.copyOf(rs.values()),
            List.of(), a.meta());
    }

    /** 渲染成紧凑 JSON（实体名 + 类型 + 关系两端名字）。 */
    private static String render(KbGraphSearchService.Result r, String extraFields) {
        Map<String, String> nameById = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("{\"entities\":[");
        for (int i = 0; i < r.entities().size(); i++) {
            KbGraphSearchService.Entity e = r.entities().get(i);
            nameById.put(e.id(), e.name());
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(KbSearchKnowledgeTool.escape(e.name()))
              .append("\",\"type\":\"").append(KbSearchKnowledgeTool.escape(e.entityType()))
              .append("\",\"knowledge_id\":\"").append(KbSearchKnowledgeTool.escape(e.knowledgeId()))
              .append("\"}");
        }
        sb.append("],\"relationships\":[");
        for (int i = 0; i < r.relationships().size(); i++) {
            KbGraphSearchService.Relation rel = r.relationships().get(i);
            if (i > 0) {
                sb.append(',');
            }
            String src = nameById.getOrDefault(rel.sourceId(), rel.sourceId());
            String tgt = nameById.getOrDefault(rel.targetId(), rel.targetId());
            sb.append("{\"source\":\"").append(KbSearchKnowledgeTool.escape(src))
              .append("\",\"target\":\"").append(KbSearchKnowledgeTool.escape(tgt))
              .append("\",\"type\":\"").append(KbSearchKnowledgeTool.escape(rel.type()))
              .append("\",\"weight\":").append(String.format("%.2f", rel.weight()))
              .append('}');
        }
        sb.append("]}");
        if (extraFields != null && !extraFields.isEmpty()) {
            // extraFields 形如 ",\"note\":\"...\"" ⇒ 插在最后一个 '}' 之前
            sb.setLength(sb.length() - 1);
            sb.append(extraFields).append('}');
        }
        return sb.toString();
    }
}
