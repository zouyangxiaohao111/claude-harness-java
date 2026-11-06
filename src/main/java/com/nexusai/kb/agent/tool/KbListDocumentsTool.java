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
import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 列出知识库与文档（{@code list_documents}）· 批次 W10。
 *
 * <p>对齐源侧 {@code ToolListDocuments = "list_documents"}（{@code definitions.go:23}）。
 *
 * <p><b>⭐⭐ 默认不激活</b>：标 {@link ExclusiveTool} ⇒ 只有 MD 的 {@code tools:} 点名才可见。
 *
 * <p><b>作用域</b>：只列本智能体被允许的知识库（{@link KbAgentToolScope}）。
 * 用户原话「当配置了查哪些知识库时，list 就只能看到这个」—— 本方法即该语义的实现。
 */
@Component
@ExclusiveTool
public class KbListDocumentsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbListDocumentsTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 最多列多少个知识库（防止一次吐爆上下文）。 */
    private static final int MAX_KBS = 50;

    @Autowired private KnowledgeBaseService knowledgeBaseService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "list_documents";
    }

    @Override
    public String description() {
        return "列出可访问的知识库及其文档数量。只返回本智能体被授权的知识库；"
            + "若配置为不检索知识库，则返回空列表。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");
        ObjectNode kw = JSON.objectNode();
        kw.put("type", "string");
        kw.put("description", "按名称过滤（可选，不区分大小写）");
        props.set("name_contains", kw);
        return root;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        try {
            String filter = call.input().path("name_contains").asText("").trim().toLowerCase();
            List<String> allowed = scope == null ? null : scope.allowedKnowledgeBaseIds(ctx);
            if (allowed != null && allowed.isEmpty()) {
                return ToolResult.success(call.id(),
                    "[]（该智能体未授权任何知识库）");
            }

            List<KnowledgeBaseDto> all;
            try {
                all = knowledgeBaseService.list();
            } catch (RuntimeException e) {
                log.warn("[list_documents] 知识库列表查询失败: {}", e.toString());
                return ToolResult.error(call.id(), "无法读取知识库列表：" + e.getMessage());
            }

            List<KnowledgeBaseDto> visible = new ArrayList<>();
            for (KnowledgeBaseDto kb : all) {
                if (kb == null) {
                    continue;
                }
                if (allowed != null && !allowed.contains(kb.id())) {
                    continue;
                }
                if (!filter.isEmpty()
                        && (kb.name() == null || !kb.name().toLowerCase().contains(filter))) {
                    continue;
                }
                visible.add(kb);
                if (visible.size() >= MAX_KBS) {
                    break;
                }
            }

            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < visible.size(); i++) {
                KnowledgeBaseDto kb = visible.get(i);
                if (i > 0) {
                    sb.append(",\n");
                }
                sb.append("  {\"knowledge_base_id\":\"").append(KbSearchKnowledgeTool.escape(kb.id()))
                  .append("\",\"name\":\"").append(KbSearchKnowledgeTool.escape(kb.name()))
                  .append("\",\"description\":\"")
                  .append(KbSearchKnowledgeTool.escape(KbSearchKnowledgeTool.truncate(kb.description(), 200)))
                  .append("\",\"type\":\"").append(KbSearchKnowledgeTool.escape(kb.type()))
                  .append("\"}");
            }
            sb.append("\n]");
            log.info("[list_documents] 列出 库数={} 过滤=『{}』", visible.size(), filter);
            return ToolResult.success(call.id(), sb.toString());
        } catch (RuntimeException e) {
            log.error("[list_documents] 执行失败", e);
            return ToolResult.error(call.id(), "列出知识库失败：" + e.getMessage());
        }
    }
}
