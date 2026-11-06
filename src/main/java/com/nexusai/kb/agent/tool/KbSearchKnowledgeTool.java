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
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.config.KbConfigService;
import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库检索工具（{@code search_knowledge}）· 批次 W10。
 *
 * <p><b>⭐⭐ 默认不激活</b>：标 {@link ExclusiveTool} ⇒
 * <ul>
 *   <li>没绑定主线程智能体的会话 —— 不可见</li>
 *   <li>绑定了智能体但 MD 的 {@code tools:} 缺省 / {@code ['*']} —— 不可见</li>
 *   <li>MD 里显式写 {@code tools: search_knowledge} —— 可见</li>
 * </ul>
 * （判定在 {@code AgentToolPolicy}：通配分支跳过 EXCLUSIVE，显式名单分支不过滤。）
 *
 * <p><b>检索范围</b>：由会话绑定智能体的 {@code kb_agent.knowledge_bases} 决定
 * （{@code all} / {@code selected} / {@code none}），对齐源侧
 * {@code resolveKnowledgeBasesFromAgent}（{@code session_knowledge_qa.go:328}）。
 *
 * <p><b>为什么工具名用下划线</b>：对齐源侧 {@code ToolSearchKnowledge = "search_knowledge"}
 * （{@code internal/agent/tools/definitions.go:21}）。本仓 CC 系工具是 PascalCase，
 * 但知识库这组是**新增能力**、不是 CC 对齐项，用源侧名字更便于对照。
 */
@Component
@ExclusiveTool
public class KbSearchKnowledgeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbSearchKnowledgeTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 单次返回条数上限（防止模型一次拉爆上下文）。 */
    private static final int MAX_RESULTS = 20;
    private static final int DEFAULT_RESULTS = 8;

    @Autowired private KbHybridSearcher searcher;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private KbConfigService kbConfigService;

    /** 供 {@link KbAgentToolScope} 注入「本会话允许的库」。可 null（= 全部库）。 */
    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "在知识库中检索与问题相关的内容片段。返回命中片段的正文与出处（知识库名 / 文档 id）。"
            + " 只检索本智能体被允许访问的知识库；未配置范围时检索全部。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");
        ObjectNode q = JSON.objectNode();
        q.put("type", "string");
        q.put("description", "检索问题（自然语言）");
        props.set("query", q);
        ObjectNode limit = JSON.objectNode();
        limit.put("type", "integer");
        limit.put("description", "返回条数（默认 " + DEFAULT_RESULTS + "，上限 " + MAX_RESULTS + "）");
        props.set("limit", limit);
        ArrayNode required = JSON.arrayNode();
        required.add("query");
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
            String query = in.path("query").asText("").trim();
            if (query.isEmpty()) {
                return ToolResult.error(call.id(), "query 不能为空");
            }
            int limit = clampLimit(in.path("limit").asInt(DEFAULT_RESULTS));

            List<String> kbIds = scope == null ? null : scope.allowedKnowledgeBaseIds(ctx);
            if (kbIds != null && kbIds.isEmpty()) {
                return ToolResult.success(call.id(),
                    "[]（该智能体未授权任何知识库，检索已跳过）");
            }
            if (kbIds == null) {
                kbIds = allSearchableKbIds();
            }

            String modelId = kbConfigService == null ? null : kbConfigService.readEmbeddingModelId();
            List<KbHybridSearcher.Hit> hits = new ArrayList<>();
            for (String kbId : kbIds) {
                try {
                    hits.addAll(searcher.search(kbId, query, limit, modelId));
                } catch (RuntimeException e) {
                    // 单个库失败不打断其它库（⛔ 也不要静默：记 warn 并在结果里注明）
                    log.warn("[search_knowledge] 库检索失败 kb={} 原因={}", kbId, e.toString());
                }
            }
            hits.sort((a, b) -> Double.compare(b.score(), a.score()));
            if (hits.size() > limit) {
                hits = hits.subList(0, limit);
            }

            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < hits.size(); i++) {
                KbHybridSearcher.Hit h = hits.get(i);
                if (i > 0) {
                    sb.append(",\n");
                }
                sb.append("  {\"chunk_id\":\"").append(escape(h.chunkId()))
                  .append("\",\"knowledge_id\":\"").append(escape(h.knowledgeId()))
                  .append("\",\"score\":").append(String.format("%.4f", h.score()))
                  .append(",\"content\":\"").append(escape(truncate(h.content(), 800)))
                  .append("\"}");
            }
            sb.append("\n]");
            log.info("[search_knowledge] 检索完成 库数={} 命中={} query={}", kbIds.size(), hits.size(),
                truncate(query, 40));
            return ToolResult.success(call.id(), sb.toString());
        } catch (RuntimeException e) {
            log.error("[search_knowledge] 执行失败", e);
            return ToolResult.error(call.id(), "检索失败：" + e.getMessage());
        }
    }

    private static int clampLimit(int v) {
        if (v <= 0) {
            return DEFAULT_RESULTS;
        }
        return Math.min(v, MAX_RESULTS);
    }

    private List<String> allSearchableKbIds() {
        try {
            List<String> ids = new ArrayList<>();
            knowledgeBaseService.list().forEach(kb -> ids.add(kb.id()));
            return ids;
        } catch (RuntimeException e) {
            log.warn("[search_knowledge] 知识库列表查询失败: {}", e.toString());
            return List.of();
        }
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
