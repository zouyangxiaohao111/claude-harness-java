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
import com.nexusai.kb.domain.KnowledgeService;
import com.nexusai.kb.dto.KnowledgeDto;
import com.nexusai.kb.dto.KnowledgePageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 列出某个知识库里的文档（{@code list_documents}）· 入参对齐源侧
 * {@code internal/agent/tools/list_documents.go}。
 *
 * <h2>⭐⭐ 语义已变更（原实现列的是「知识库」，不是「文档」）</h2>
 * 源站 {@code list_documents} 的入参是 {@code knowledge_base_id}（<b>必填</b>），
 * 返回该库内的文档列表并按 {@code page} / {@code page_size} 分页。
 * 本仓旧实现列的是知识库本身 —— 那个能力现在由 {@link KbAgentToolScope} 表达的
 * 「授权范围」承载，不需要单独的工具。
 *
 * <h2>作用域</h2>
 * 只允许列本智能体被授权知识库里的文档（{@link KbAgentToolScope}）。
 * 用户原话「当配置了查哪些知识库时，list 就只能看到这个」。
 */
@Component
@ExclusiveTool
public class KbListDocumentsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbListDocumentsTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 分页默认与上限 · 对齐源站 {@code page_size} 默认 20 / 上限 100。 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    @Autowired private KnowledgeService knowledgeService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "list_documents";
    }

    @Override
    public String description() {
        return "列出指定知识库里的文档（分页）。返回每篇文档的标题、类型、解析状态与 id。"
            + " 检索范围不明确、或想知道某个库里有什么时使用。"
            + " 只能列本智能体被授权知识库里的文档。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode kbId = JSON.objectNode();
        kbId.put("type", "string");
        kbId.put("description", "知识库 id（必填）");
        props.set("knowledge_base_id", kbId);

        ObjectNode keyword = JSON.objectNode();
        keyword.put("type", "string");
        keyword.put("description", "按标题 / 文件名过滤（可选，不区分大小写）");
        props.set("keyword", keyword);

        ObjectNode page = JSON.objectNode();
        page.put("type", "integer");
        page.put("description", "页码（从 1 起，默认 1）");
        page.put("minimum", 1);
        props.set("page", page);

        ObjectNode pageSize = JSON.objectNode();
        pageSize.put("type", "integer");
        pageSize.put("description",
            "每页条数（默认 " + DEFAULT_PAGE_SIZE + "，范围 1.." + MAX_PAGE_SIZE + "）");
        pageSize.put("minimum", 1);
        pageSize.put("maximum", MAX_PAGE_SIZE);
        props.set("page_size", pageSize);

        ArrayNode required = JSON.arrayNode();
        required.add("knowledge_base_id");
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
            String kbId = in.path("knowledge_base_id").asText("").trim();
            if (kbId.isEmpty()) {
                return ToolResult.error(call.id(),
                    "knowledge_base_id 不能为空（先用 search_knowledge 或不带过滤的方式确定库 id）");
            }

            // ── 作用域校验：⛔ 不能只信模型传进来的 kbId ──────────────────────
            List<String> allowed = scope == null ? null : scope.allowedKnowledgeBaseIds(ctx);
            if (allowed != null && !allowed.contains(kbId)) {
                log.warn("[list_documents] 拒绝越权列文档 kb={}（不在智能体授权范围）", kbId);
                return ToolResult.error(call.id(),
                    "该知识库不在本智能体的授权范围内，不能列出其文档");
            }

            String keyword = in.path("keyword").asText("").trim();
            int page = Math.max(1, in.path("page").asInt(1));
            int pageSize = clampPageSize(in.path("page_size").asInt(DEFAULT_PAGE_SIZE));

            KnowledgePageResponse<KnowledgeDto> resp;
            try {
                resp = knowledgeService.list(kbId, page, pageSize, null, null,
                    keyword.isEmpty() ? null : keyword, null);
            } catch (RuntimeException e) {
                log.warn("[list_documents] 列文档失败 kb={}: {}", kbId, e.toString());
                return ToolResult.error(call.id(), "读取文档列表失败：" + e.getMessage());
            }
            if (resp == null) {
                return ToolResult.error(call.id(), "读取文档列表失败：服务未返回结果");
            }

            List<KnowledgeDto> docs = resp.data() == null ? List.of() : resp.data();
            StringBuilder sb = new StringBuilder("{\"documents\":[");
            int written = 0;
            for (KnowledgeDto d : docs) {
                if (d == null) {
                    continue;
                }
                if (written > 0) {
                    sb.append(',');
                }
                sb.append("{\"knowledge_id\":\"").append(KbSearchKnowledgeTool.escape(d.id()))
                  .append("\",\"title\":\"").append(KbSearchKnowledgeTool.escape(
                      d.title() != null ? d.title() : d.fileName()))
                  .append("\",\"type\":\"").append(KbSearchKnowledgeTool.escape(d.type()))
                  .append("\",\"file_type\":\"").append(KbSearchKnowledgeTool.escape(d.fileType()))
                  .append("\",\"parse_status\":\"").append(KbSearchKnowledgeTool.escape(d.parseStatus()))
                  .append("\"}");
                written++;
            }
            sb.append("],\"page\":").append(resp.page())
              .append(",\"page_size\":").append(resp.pageSize())
              .append(",\"total\":").append(resp.total());
            if ((long) page * pageSize < resp.total()) {
                sb.append(",\"note\":\"还有更多，续读请传 page=").append(page + 1).append("\"");
            }
            sb.append('}');

            log.info("[list_documents] 列出 kb={} page={} page_size={} 返回={} total={} keyword={}",
                kbId, page, pageSize, written, resp.total(),
                keyword.isEmpty() ? "-" : KbSearchKnowledgeTool.truncate(keyword, 30));
            return ToolResult.success(call.id(), sb.toString());
        } catch (RuntimeException e) {
            log.error("[list_documents] 执行失败", e);
            return ToolResult.error(call.id(), "列出文档失败：" + e.getMessage());
        }
    }

    private static int clampPageSize(int v) {
        if (v <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(v, MAX_PAGE_SIZE);
    }
}
