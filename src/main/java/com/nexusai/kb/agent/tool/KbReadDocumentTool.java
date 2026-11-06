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
import com.nexusai.kb.app.chunk.KbChunkService;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 读一篇文档的分块内容（{@code read_document}）· 批次 W10。
 *
 * <p>对齐源侧 {@code ToolReadDocument = "read_document"}（{@code definitions.go:22}）。
 *
 * <p><b>⭐⭐ 默认不激活</b>：标 {@link ExclusiveTool} ⇒ 只有 MD 的 {@code tools:} 点名才可见。
 *
 * <p><b>为什么按文档而不是按 chunk id</b>：模型拿到 {@code search_knowledge} 的命中后
 * 通常想「把这篇文档看全」，而不是逐个 chunk 要。按 chunk 读会让模型反复调工具。
 *
 * <p><b>作用域</b>：先用 {@link KbAgentToolScope} 校验该文档所在库是否被允许 ——
 * ⛔ 不能只信模型传进来的 knowledgeId（那等于把权限判断交给模型）。
 */
@Component
@ExclusiveTool
public class KbReadDocumentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbReadDocumentTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 单次返回的总字符上限（防止整篇超长文档撑爆上下文）。 */
    private static final int MAX_TOTAL_CHARS = 20_000;

    @Autowired private KbChunkService chunkService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "read_document";
    }

    @Override
    public String description() {
        return "读取一篇文档的分块内容（按块顺序）。只允许读取本智能体被授权知识库里的文档。"
            + " 内容较长时会在总字符上限处截断，并告知还有多少块未返回。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");
        ObjectNode kid = JSON.objectNode();
        kid.put("type", "string");
        kid.put("description", "文档 id（knowledge_id，来自 search_knowledge / list_documents 的结果）");
        props.set("knowledge_id", kid);
        ObjectNode max = JSON.objectNode();
        max.put("type", "integer");
        max.put("description", "最多返回多少块（可选，默认 20）");
        props.set("max_chunks", max);
        com.fasterxml.jackson.databind.node.ArrayNode required = JSON.arrayNode();
        required.add("knowledge_id");
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
            String knowledgeId = call.input().path("knowledge_id").asText("").trim();
            if (knowledgeId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_id 不能为空");
            }
            int maxChunks = call.input().path("max_chunks").asInt(20);
            if (maxChunks <= 0) {
                maxChunks = 20;
            }

            List<KbChunkRecord> chunks;
            try {
                chunks = chunkService.list(knowledgeId, false);
            } catch (RuntimeException e) {
                log.warn("[read_document] 读分块失败 knowledge={}: {}", knowledgeId, e.toString());
                return ToolResult.error(call.id(), "读取文档失败：" + e.getMessage());
            }
            if (chunks == null || chunks.isEmpty()) {
                return ToolResult.success(call.id(),
                    "[]（该文档没有可用分块，可能尚未解析完成或已被停用）");
            }

            // 作用域校验：用文档所在库（取第一块的 kb id；同一文档的块必属同一个库）
            List<String> allowed = scope == null ? null : scope.allowedKnowledgeBaseIds(ctx);
            if (allowed != null) {
                String kbId = chunks.get(0).getKnowledgeBaseId();
                if (allowed.isEmpty() || !allowed.contains(kbId)) {
                    log.warn("[read_document] 拒绝越权读取 knowledge={} kb={}（不在智能体授权范围）",
                        knowledgeId, kbId);
                    return ToolResult.error(call.id(),
                        "该文档所在知识库不在本智能体的授权范围内，不能读取");
                }
            }

            chunks.sort((a, b) -> {
                int ai = a.getChunkIndex() == null ? 0 : a.getChunkIndex();
                int bi = b.getChunkIndex() == null ? 0 : b.getChunkIndex();
                return Integer.compare(ai, bi);
            });

            StringBuilder sb = new StringBuilder();
            int used = 0;
            int returned = 0;
            List<KbChunkRecord> picked = new ArrayList<>();
            for (KbChunkRecord c : chunks) {
                if (returned >= maxChunks) {
                    break;
                }
                String text = c.getContent() == null ? "" : c.getContent();
                if (used + text.length() > MAX_TOTAL_CHARS) {
                    // 至少返回一块，否则短上限时永远空
                    if (returned > 0) {
                        break;
                    }
                    text = KbSearchKnowledgeTool.truncate(text, MAX_TOTAL_CHARS);
                }
                picked.add(c);
                used += text.length();
                returned++;
            }

            sb.append("[\n");
            for (int i = 0; i < picked.size(); i++) {
                KbChunkRecord c = picked.get(i);
                if (i > 0) {
                    sb.append(",\n");
                }
                sb.append("  {\"chunk_id\":\"").append(KbSearchKnowledgeTool.escape(c.getId()))
                  .append("\",\"chunk_index\":").append(c.getChunkIndex() == null ? 0 : c.getChunkIndex())
                  .append(",\"content\":\"").append(KbSearchKnowledgeTool.escape(c.getContent()))
                  .append("\"}");
            }
            sb.append("\n]");
            if (returned < chunks.size()) {
                sb.append("\n（共 ").append(chunks.size()).append(" 块，已返回前 ")
                  .append(returned).append(" 块；需要更多请调大 max_chunks 或换用 search_knowledge 精确定位）");
            }
            log.info("[read_document] 读取完成 knowledge={} 块数={}/{}", knowledgeId, returned, chunks.size());
            return ToolResult.success(call.id(), sb.toString());
        } catch (RuntimeException e) {
            log.error("[read_document] 执行失败", e);
            return ToolResult.error(call.id(), "读取文档失败：" + e.getMessage());
        }
    }
}
