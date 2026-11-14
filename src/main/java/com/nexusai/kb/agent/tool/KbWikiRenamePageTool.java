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

/**
 * Wiki 页面改名 / 改 slug（{@code wiki_rename_page}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_rename_page.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { slug*, new_slug* }
 * </pre>
 *
 * <h2>⭐⭐ 改名不只是改一个字段</h2>
 * slug 是 Wiki 的注解寻址键（{@code [[entity/acme-corp|Acme]} 这种链接靠它稳定）。
 * 改 slug 之后：
 * <ol>
 *   <li>旧 slug 的所有入链会失效 ⇒ 需要重建反向链接</li>
 *   <li>新 slug 可能已被占用 ⇒ 必须先检查冲突</li>
 *   <li>其它页面正文里指向旧 slug 的 {@code [[...]]} 会变成死链 ⇒ 由 lint 报告</li>
 * </ol>
 * 本工具做 ①（复用 {@code rebuildInLinks}）②（拒绝冲突），③ 交给 {@code wiki_flag_issue}。
 */
@Component
@ExclusiveTool
public class KbWikiRenamePageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiRenamePageTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired private KbWikiPageService wikiPageService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_rename_page";
    }

    @Override
    public String description() {
        return "修改 Wiki 页面的 slug（改名）。新 slug 若已被占用会被拒绝。"
            + " 改名后会重建反向链接；其它页面里指向旧 slug 的链接会变成死链，需要另行修正。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode kb = JSON.objectNode();
        kb.put("type", "string");
        kb.put("description", "目标知识库 id（必填）");
        props.set("knowledge_base_id", kb);

        ObjectNode slug = JSON.objectNode();
        slug.put("type", "string");
        slug.put("description", "当前页面 slug（必填）");
        props.set("slug", slug);

        ObjectNode newSlug = JSON.objectNode();
        newSlug.put("type", "string");
        newSlug.put("description", "新的页面 slug（必填，例如 concept/renamed）");
        props.set("new_slug", newSlug);

        ArrayNode required = JSON.arrayNode();
        required.add("knowledge_base_id");
        required.add("slug");
        required.add("new_slug");
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
            String newSlug = in.path("new_slug").asText("").trim();

            if (kbId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_base_id 不能为空");
            }
            if (slug.isEmpty()) {
                return ToolResult.error(call.id(), "slug 不能为空");
            }
            if (newSlug.isEmpty()) {
                return ToolResult.error(call.id(), "new_slug 不能为空");
            }
            if (slug.equals(newSlug)) {
                return ToolResult.error(call.id(), "slug 与 new_slug 相同，无需改名");
            }

            String denied = KbWikiToolSupport.checkKbWritable(scope, ctx, kbId);
            if (denied != null) {
                log.warn("[wiki_rename_page] 拒绝越权写入 kb={} slug={}", kbId, slug);
                return ToolResult.error(call.id(), denied + "，不能改名");
            }

            KbWikiPageRecord page = wikiPageService.findPage(kbId, slug);
            if (page == null) {
                return ToolResult.error(call.id(), "找不到页面 slug=" + slug);
            }
            KbWikiPageRecord conflict = wikiPageService.findPage(kbId, newSlug);
            if (conflict != null) {
                return ToolResult.error(call.id(),
                    "新 slug 已被占用：" + newSlug + "（先确认是否需要合并或换个名字）");
            }

            // ── 改名 = 以新 slug 写一份 + 删掉旧 slug ───────────────────────────
            com.nexusai.kb.app.wiki.KbWikiReduceStage.MergeResult result =
                new com.nexusai.kb.app.wiki.KbWikiReduceStage.MergeResult(
                    "created",
                    page.getTitle(), KbWikiToolSupport.safe(page.getContent()),
                    KbWikiToolSupport.safe(page.getSummary()),
                    KbWikiPageService.parseStringArray(page.getAliases()),
                    page.getPageType(),
                    KbWikiPageService.parseStringArray(page.getSourceRefs()),
                    KbWikiPageService.parseStringArray(page.getChunkRefs()), null);

            KbWikiPageRecord created = wikiPageService.applyMergeResult(kbId, newSlug, result, "agent");
            if (created == null) {
                return ToolResult.error(call.id(), "改名失败：新页面写入未生效");
            }
            wikiPageService.deletePage(kbId, slug);
            wikiPageService.rebuildInLinks(kbId);

            log.info("[wiki_rename_page] 改名完成 kb={} from={} to={}", kbId, slug, newSlug);
            return ToolResult.success(call.id(),
                "{\"action\":\"renamed\",\"from\":\"" + KbWikiToolSupport.esc(slug)
                    + "\",\"to\":\"" + KbWikiToolSupport.esc(newSlug)
                    + "\",\"note\":\"指向旧 slug 的链接已变成死链，可用 wiki_flag_issue 记录待修\"}");
        } catch (RuntimeException e) {
            log.error("[wiki_rename_page] 执行失败", e);
            return ToolResult.error(call.id(), "改名失败：" + e.getMessage());
        }
    }
}
