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
 * 查询知识图谱（{@code query_knowledge_graph}）· 批次 W10。
 *
 * <p>对齐源侧 {@code ToolQueryKnowledgeGraph = "query_knowledge_graph"}（{@code definitions.go:24}）。
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

    /** 默认返回条数（远小于服务的硬顶 500/1000/300，避免一次塞爆上下文）。 */
    private static final int DEFAULT_ENTITIES = 30;
    private static final int DEFAULT_RELATIONS = 50;
    private static final int DEFAULT_CHUNKS = 0;

    @Autowired private KbGraphSearchService graphSearchService;

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
        ObjectNode kb = JSON.objectNode();
        kb.put("type", "string");
        kb.put("description", "知识库 id（不传 = 在全部可访问知识库里找）");
        props.set("knowledge_base_id", kb);
        ObjectNode q = JSON.objectNode();
        q.put("type", "string");
        q.put("description", "检索词（实体名或关键词；不传 = 返回度数最高的实体）");
        props.set("query", q);
        ObjectNode me = JSON.objectNode();
        me.put("type", "integer");
        me.put("description", "最多返回实体数（默认 " + DEFAULT_ENTITIES + "）");
        props.set("max_entities", me);
        ObjectNode mr = JSON.objectNode();
        mr.put("type", "integer");
        mr.put("description", "最多返回关系数（默认 " + DEFAULT_RELATIONS + "）");
        props.set("max_relations", mr);
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
            String explicitKb = in.path("knowledge_base_id").asText("").trim();
            String query = in.path("query").asText("").trim();
            int maxEntities = clamp(in.path("max_entities").asInt(DEFAULT_ENTITIES), DEFAULT_ENTITIES);
            int maxRelations = clamp(in.path("max_relations").asInt(DEFAULT_RELATIONS), DEFAULT_RELATIONS);

            List<String> allowed = scope == null ? null : scope.allowedKnowledgeBaseIds(ctx);
            if (allowed != null && allowed.isEmpty()) {
                return ToolResult.success(call.id(),
                    "{\"entities\":[],\"relationships\":[],\"note\":\"该智能体未授权任何知识库\"}");
            }

            List<String> targets;
            if (!explicitKb.isEmpty()) {
                if (allowed != null && !allowed.contains(explicitKb)) {
                    log.warn("[query_knowledge_graph] 拒绝越权查询 kb={}（不在智能体授权范围）", explicitKb);
                    return ToolResult.error(call.id(),
                        "该知识库不在本智能体的授权范围内，不能查询");
                }
                targets = List.of(explicitKb);
            } else if (allowed != null) {
                targets = allowed;
            } else {
                return ToolResult.error(call.id(),
                    "需要 knowledge_base_id：未绑定智能体时无法确定在哪个库里查图");
            }

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
                return ToolResult.success(call.id(), "{\"entities\":[],\"relationships\":[]}");
            }
            log.info("[query_knowledge_graph] 查询完成 库数={} 实体={} 关系={}",
                targets.size(), merged.entities().size(), merged.relationships().size());
            return ToolResult.success(call.id(), render(merged));
        } catch (RuntimeException e) {
            log.error("[query_knowledge_graph] 执行失败", e);
            return ToolResult.error(call.id(), "查询图谱失败：" + e.getMessage());
        }
    }

    private static int clamp(int v, int def) {
        if (v <= 0) {
            return def;
        }
        return Math.min(v, 200);
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
    private static String render(KbGraphSearchService.Result r) {
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
        return sb.toString();
    }
}
