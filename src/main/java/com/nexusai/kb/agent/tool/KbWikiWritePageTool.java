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
import com.nexusai.kb.app.wiki.KbWikiPageService;
import com.nexusai.kb.app.wiki.KbWikiReduceStage;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 整页写入 Wiki 页面（{@code wiki_write_page}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_write_page.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { slug*, title, summary, content*, page_type, aliases[], source_refs[] }
 * </pre>
 *
 * <h2>⭐⭐ 这是「写工具」，必须做作用域校验</h2>
 * 源站的 wiki 写工具在 Agent 里跑，作用域来自会话绑定的知识库。
 * 本仓复用 {@link KbAgentToolScope} —— ⛔ 不能只信模型传进来的 kbId。
 *
 * <h2>⚠️ 复用 {@code applyMergeResult} 而不是自己拼 UPDATE</h2>
 * 那条路径已经处理了：Not-NULL 列兜底、版本快照（revision）、反向链接重建、
 * 正文里 {@code [[...]]} 重新扫描 out_links。自己写 UPDATE 会漏掉这些，且不报错。
 */
@Component
@ExclusiveTool
public class KbWikiWritePageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiWritePageTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired private KbWikiPageService wikiPageService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_write_page";
    }

    @Override
    public String description() {
        return "整页写入一个 Wiki 页面（新建或覆盖）。必须提供完整内容，不得使用省略占位符"
            + "（如「其余不变」）。页面内容会被 [[slug|显示名]] 链接扫描并重建反向链接。"
            + " 只能写本智能体授权范围内的知识库。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        props.set("knowledge_base_id", str("目标知识库 id（必填）"));
        props.set("slug", str("页面 slug（必填，例如 entity/acme-corp）"));
        props.set("title", str("页面标题（可选，缺省沿用现有标题）"));
        props.set("summary", str("页面摘要（可选）"));
        props.set("content", str("完整正文（必填，Markdown）。不要用省略占位符。"));
        props.set("page_type", str("页面类型（entity / concept / summary / synthesis / comparison），缺省 entity"));

        ObjectNode aliases = JSON.objectNode();
        aliases.put("type", "array");
        aliases.put("description", "别名列表（可选）");
        ObjectNode aliasItems = JSON.objectNode();
        aliasItems.put("type", "string");
        aliases.set("items", aliasItems);
        props.set("aliases", aliases);

        ObjectNode refs = JSON.objectNode();
        refs.put("type", "array");
        refs.put("description", "来源文档 id 列表（可选）");
        ObjectNode refItems = JSON.objectNode();
        refItems.put("type", "string");
        refs.set("items", refItems);
        props.set("source_refs", refs);

        ArrayNode required = JSON.arrayNode();
        required.add("knowledge_base_id");
        required.add("slug");
        required.add("content");
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
            String slug = in.path("slug").asText("").trim();
            String content = in.path("content").asText("");
            if (kbId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_base_id 不能为空");
            }
            if (slug.isEmpty()) {
                return ToolResult.error(call.id(), "slug 不能为空");
            }
            if (content.isBlank()) {
                return ToolResult.error(call.id(),
                    "content 不能为空。整页写入必须提供完整正文，不得使用省略占位符。");
            }

            String denied = KbWikiToolSupport.checkKbAllowed(scope, ctx, kbId);
            if (denied != null) {
                log.warn("[wiki_write_page] 拒绝越权写入 kb={} slug={}", kbId, slug);
                return ToolResult.error(call.id(), denied + "，不能写入");
            }

            KbWikiPageRecord existing = wikiPageService.findPage(kbId, slug);
            String title = in.path("title").asText("");
            if (title.isBlank() && existing != null) {
                title = KbWikiToolSupport.safe(existing.getTitle());
            }
            String summary = in.has("summary")
                ? in.path("summary").asText("")
                : (existing == null ? "" : KbWikiToolSupport.safe(existing.getSummary()));
            String pageType = in.path("page_type").asText("");
            if (pageType.isBlank()) {
                pageType = existing != null && existing.getPageType() != null
                    ? existing.getPageType() : "entity";
            }

            List<String> aliases = readArray(in, "aliases");
            List<String> sourceRefs = readArray(in, "source_refs");

            KbWikiReduceStage.MergeResult result = new KbWikiReduceStage.MergeResult(
                existing == null ? "created" : "updated",
                title, content, summary, aliases, pageType, sourceRefs,
                List.of(), null);

            KbWikiPageRecord saved = wikiPageService.applyMergeResult(kbId, slug, result, "agent");
            if (saved == null) {
                return ToolResult.error(call.id(), "写入未生效（服务返回空结果）");
            }

            String action = existing == null ? "created" : "updated";
            log.info("[wiki_write_page] 写入完成 action={} kb={} slug={} 字符数={}",
                action, kbId, slug, content.length());
            return ToolResult.success(call.id(),
                "{\"action\":\"" + action + "\",\"slug\":\"" + KbWikiToolSupport.esc(saved.getSlug())
                    + "\",\"title\":\"" + KbWikiToolSupport.esc(saved.getTitle())
                    + "\",\"version\":" + (saved.getVersion() == null ? 0 : saved.getVersion())
                    + ",\"content_length\":" + content.length() + "}");
        } catch (RuntimeException e) {
            log.error("[wiki_write_page] 执行失败", e);
            return ToolResult.error(call.id(), "写入 Wiki 页面失败：" + e.getMessage());
        }
    }

    private static ObjectNode str(String desc) {
        ObjectNode n = JSON.objectNode();
        n.put("type", "string");
        n.put("description", desc);
        return n;
    }

    private static List<String> readArray(JsonNode in, String field) {
        JsonNode node = in.path(field);
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode el : node) {
            String v = el.asText("").trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }
}
