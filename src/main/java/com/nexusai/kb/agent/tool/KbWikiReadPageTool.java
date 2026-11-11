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
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取 Wiki 页面（{@code wiki_read_page}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_tools.go} 的 {@code NewWikiReadPageTool}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { slugs*: string[] }   // 例如 ["entity/acme-corp", "index"]
 * </pre>
 * 源站还兼容单数 {@code slug}（legacy），本仓一并支持（取并集去重）。
 *
 * <h2>⭐⭐ 为什么是「一次读多个」而不是「一次读一个」</h2>
 * 源站实现里对多页读取有<b>共享预算</b>与<b>已见链接去重</b>：
 * 一页读完把它的链接摘要标为「已见」，后续页面再出现同一链接就不再重复展开。
 * 这能显著降低上下文消耗。本仓简化实现：逐页返回完整内容，靠 {@code content} 预算截断。
 */
@Component
@ExclusiveTool
public class KbWikiReadPageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiReadPageTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 单次最多读多少页（防止一次拉爆上下文）。 */
    static final int MAX_PAGES = 10;
    /** 单页正文最多返回多少字符。 */
    static final int MAX_CONTENT_CHARS = 12_000;
    /** 所有页合计的正文预算。 */
    static final int MAX_TOTAL_CHARS = 40_000;

    @Autowired private KbWikiPageService wikiPageService;

    @Autowired(required = false)
    private com.nexusai.kb.domain.KnowledgeBaseService knowledgeBaseService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_read_page";
    }

    @Override
    public String description() {
        return "按 slug 读取一个或多个 Wiki 页面的完整内容、元数据与链接。"
            + " 知道 slug 时（如 entity/acme-corp、concept/rag）用它读整页；"
            + " 不知道 slug 时先用 wiki_search 找。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode slugs = JSON.objectNode();
        slugs.put("type", "array");
        slugs.put("description", "要读取的 Wiki 页面 slug 列表（例如 [\"entity/acme-corp\", \"index\"]）");
        ObjectNode items = JSON.objectNode();
        items.put("type", "string");
        slugs.set("items", items);
        props.set("slugs", slugs);

        ArrayNode required = JSON.arrayNode();
        required.add("slugs");
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
            // 兼容源站的 slug / slugs 双形态
            Set<String> wanted = new LinkedHashSet<>();
            JsonNode arr = in.path("slugs");
            if (arr.isArray()) {
                for (JsonNode el : arr) {
                    String v = el.asText("").trim();
                    if (!v.isEmpty()) {
                        wanted.add(v);
                    }
                }
            }
            String single = in.path("slug").asText("").trim();
            if (!single.isEmpty()) {
                wanted.add(single);
            }
            if (wanted.isEmpty()) {
                return ToolResult.error(call.id(), "缺少 slugs 参数（要读取的页面 slug 列表）");
            }
            if (wanted.size() > MAX_PAGES) {
                List<String> truncated = new ArrayList<>(wanted).subList(0, MAX_PAGES);
                log.warn("[wiki_read_page] 请求页数 {} 超过上限 {}，只取前 {} 个",
                    wanted.size(), MAX_PAGES, MAX_PAGES);
                wanted = new LinkedHashSet<>(truncated);
            }

            List<String> targets = resolveTargets(in, ctx);

            StringBuilder sb = new StringBuilder("{\"pages\":[");
            List<String> missing = new ArrayList<>();
            int written = 0;
            int used = 0;
            for (String slug : wanted) {
                KbWikiPageRecord page = findAcross(slug, targets);
                if (page == null) {
                    missing.add(slug);
                    continue;
                }
                String denied = KbWikiToolSupport.checkKbAllowed(scope, ctx, page.getKnowledgeBaseId());
                if (denied != null) {
                    log.warn("[wiki_read_page] 拒绝越权读取 slug={} kb={}",
                        slug, page.getKnowledgeBaseId());
                    missing.add(slug + "（无权访问）");
                    continue;
                }
                String content = KbWikiToolSupport.safe(page.getContent());
                boolean truncated = content.length() > MAX_CONTENT_CHARS;
                if (truncated) {
                    content = KbWikiToolSupport.truncate(content, MAX_CONTENT_CHARS);
                }
                if (used + content.length() > MAX_TOTAL_CHARS) {
                    if (written == 0) {
                        content = KbWikiToolSupport.truncate(content,
                            Math.min(MAX_CONTENT_CHARS, MAX_TOTAL_CHARS));
                    } else {
                        missing.add(slug + "（总字数超出预算，未返回）");
                        continue;
                    }
                }
                if (written > 0) {
                    sb.append(',');
                }
                sb.append("{\"slug\":\"").append(KbWikiToolSupport.esc(page.getSlug()))
                  .append("\",\"title\":\"").append(KbWikiToolSupport.esc(page.getTitle()))
                  .append("\",\"page_type\":\"").append(KbWikiToolSupport.esc(page.getPageType()))
                  .append("\",\"summary\":\"").append(KbWikiToolSupport.esc(page.getSummary()))
                  .append("\",\"aliases\":\"").append(KbWikiToolSupport.esc(page.getAliases()))
                  .append("\",\"source_refs\":\"").append(KbWikiToolSupport.esc(page.getSourceRefs()))
                  .append("\",\"out_links\":\"").append(KbWikiToolSupport.esc(page.getOutLinks()))
                  .append("\",\"content\":\"").append(KbWikiToolSupport.esc(content))
                  .append("\"");
                if (truncated) {
                    sb.append(",\"content_truncated\":true");
                }
                sb.append('}');
                written++;
                used += content.length();
            }
            sb.append("],\"returned\":").append(written);
            if (!missing.isEmpty()) {
                sb.append(",\"missing\":[");
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(KbWikiToolSupport.esc(missing.get(i))).append('"');
                }
                sb.append(']');
            }
            sb.append('}');

            log.info("[wiki_read_page] 读取完成 请求={} 返回={} 缺失={}",
                wanted.size(), written, missing.size());
            return ToolResult.success(call.id(), sb.toString());
        } catch (RuntimeException e) {
            log.error("[wiki_read_page] 执行失败", e);
            return ToolResult.error(call.id(), "读取 Wiki 页面失败：" + e.getMessage());
        }
    }

    /**
     * 在候选库里按 slug 找页面（源站「KB 路由自动」的简化版）：
     * 依次试每个库，第一个命中即返回。
     */
    private KbWikiPageRecord findAcross(String slug, List<String> kbIds) {
        for (String kbId : kbIds) {
            try {
                KbWikiPageRecord p = wikiPageService.getBySlug(kbId, slug);
                if (p != null) {
                    return p;
                }
            } catch (RuntimeException e) {
                log.debug("[wiki_read_page] 库内查找失败 kb={} slug={} 原因={}",
                    kbId, slug, e.toString());
            }
        }
        return null;
    }

    /**
     * 目标库集合。
     *
     * <p>⭐⭐ 三态同 {@link KbWikiSearchTool#resolveTargets}：
     * {@code allowed == null} = 不限制 ⇒ 取<b>全部库</b>（⛔ 不能返回空，
     * 那会让「未限制」和「明确禁止」混为一谈，表现为永远读不到页面）。
     */
    private List<String> resolveTargets(JsonNode in, ToolUseContext ctx) {
        List<String> allowed = scope == null ? null : scope.allowedKnowledgeBaseIds(ctx);
        List<String> requested = KbSearchKnowledgeTool.readStringArray(in, "knowledge_base_ids", 10);

        List<String> effective = allowed != null ? allowed : allKbIds();
        if (requested.isEmpty()) {
            return effective;
        }
        List<String> out = new ArrayList<>();
        for (String want : requested) {
            if (allowed == null || allowed.contains(want)) {
                out.add(want);
            }
        }
        return out;
    }

    /** 未绑定范围时枚举全部知识库；失败只 WARN。 */
    private List<String> allKbIds() {
        if (knowledgeBaseService == null) {
            return List.of();
        }
        try {
            List<String> ids = new ArrayList<>();
            knowledgeBaseService.list().forEach(kb -> {
                if (kb != null && kb.id() != null) {
                    ids.add(kb.id());
                }
            });
            return ids;
        } catch (RuntimeException e) {
            log.warn("[wiki_read_page] 知识库列表查询失败: {}", e.toString());
            return List.of();
        }
    }
}
