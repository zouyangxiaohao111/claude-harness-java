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

import java.util.List;

/**
 * 按原文精确替换（{@code wiki_replace_text}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_replace_text.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { slug*, old_text*, new_text*, source_refs[] }
 * </pre>
 *
 * <h2>⭐⭐ 为什么必须「精确 old_text」</h2>
 * 源站写工具的设计原则是「最小可行修复」：替换是外科手术式的改动，
 * 整页重写容易顺手改坏无关内容。用 {@code indexOf} 精确定位，
 * <b>匹配不到就报错，⛔ 不做模糊匹配</b>——模糊匹配会把不该改的地方改掉。
 *
 * <h2>⚠️ 唯一匹配</h2>
 * 源站要求 {@code old_text} 在页面里<b>恰好出现一次</b>。出现多次说明模型给的定位不够精确，
 * 强行替换会改错位置 ⇒ 本实现按「出现次数 ≠ 1 就拒绝」处理。
 */
@Component
@ExclusiveTool
public class KbWikiReplaceTextTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiReplaceTextTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired private KbWikiPageService wikiPageService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_replace_text";
    }

    @Override
    public String description() {
        return "在 Wiki 页面里把一段精确的原文替换成新文本。"
            + " old_text 必须与当前页面内容完全一致，且在页面里恰好出现一次；"
            + " 匹配不到或出现多次都会拒绝（不模糊匹配，避免改错位置）。"
            + " 这是最小改动的修复方式，优于整页重写。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        props.set("knowledge_base_id", str("目标知识库 id（必填）"));
        props.set("slug", str("页面 slug（必填）"));
        props.set("old_text", str("当前页面里的精确原文（必填）。必须与页面内容完全一致。"));
        props.set("new_text", str("替换后的新文本（必填）"));

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
        required.add("old_text");
        required.add("new_text");
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
            String oldText = in.path("old_text").asText("");
            String newText = in.has("new_text") ? in.path("new_text").asText("") : null;

            if (kbId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_base_id 不能为空");
            }
            if (slug.isEmpty()) {
                return ToolResult.error(call.id(), "slug 不能为空");
            }
            if (oldText.isEmpty()) {
                return ToolResult.error(call.id(), "old_text 不能为空（需要当前页面里的精确原文）");
            }
            if (newText == null) {
                return ToolResult.error(call.id(), "new_text 不能为空（要替换成什么）");
            }
            if (oldText.equals(newText)) {
                return ToolResult.error(call.id(), "old_text 与 new_text 相同，替换无意义");
            }

            String denied = KbWikiToolSupport.checkKbWritable(scope, ctx, kbId);
            if (denied != null) {
                log.warn("[wiki_replace_text] 拒绝越权写入 kb={} slug={}", kbId, slug);
                return ToolResult.error(call.id(), denied + "，不能写入");
            }

            KbWikiPageRecord page = wikiPageService.findPage(kbId, slug);
            if (page == null) {
                return ToolResult.error(call.id(), "找不到页面 slug=" + slug + "（先用 wiki_search 确认 slug）");
            }
            String content = KbWikiToolSupport.safe(page.getContent());

            int first = content.indexOf(oldText);
            if (first < 0) {
                return ToolResult.error(call.id(),
                    "old_text 在页面里找不到。需要与当前内容完全一致（含空格与换行）；"
                        + "请先用 wiki_read_page 读取当前内容再取精确原文。");
            }
            int second = content.indexOf(oldText, first + 1);
            if (second >= 0) {
                return ToolResult.error(call.id(),
                    "old_text 在页面里出现多次（不唯一），替换会改错位置。"
                        + "请给出更长的上下文让定位唯一。");
            }

            String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());

            List<String> sourceRefs = KbWikiToolSupport.safe(page.getSourceRefs()).isEmpty()
                ? List.of()
                : KbWikiPageService.parseStringArray(page.getSourceRefs());
            List<String> refsOverride = readArray(in, "source_refs");
            if (!refsOverride.isEmpty()) {
                sourceRefs = refsOverride;
            }

            KbWikiReduceStage.MergeResult result = new KbWikiReduceStage.MergeResult(
                "updated",
                page.getTitle(), updated, page.getSummary(),
                KbWikiPageService.parseStringArray(page.getAliases()),
                page.getPageType(), sourceRefs,
                KbWikiPageService.parseStringArray(page.getChunkRefs()), null);

            KbWikiPageRecord saved = wikiPageService.applyMergeResult(kbId, slug, result, "agent");
            if (saved == null) {
                return ToolResult.error(call.id(), "替换未生效（服务返回空结果）");
            }

            log.info("[wiki_replace_text] 替换完成 kb={} slug={} old={} new={} 位置={}",
                kbId, slug, oldText.length(), newText.length(), first);
            return ToolResult.success(call.id(),
                "{\"action\":\"updated\",\"slug\":\"" + KbWikiToolSupport.esc(saved.getSlug())
                    + "\",\"version\":" + (saved.getVersion() == null ? 0 : saved.getVersion())
                    + ",\"position\":" + first
                    + ",\"old_length\":" + oldText.length()
                    + ",\"new_length\":" + newText.length() + "}");
        } catch (RuntimeException e) {
            log.error("[wiki_replace_text] 执行失败", e);
            return ToolResult.error(call.id(), "替换文本失败：" + e.getMessage());
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
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode el : node) {
            String v = el.asText("").trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }
}
