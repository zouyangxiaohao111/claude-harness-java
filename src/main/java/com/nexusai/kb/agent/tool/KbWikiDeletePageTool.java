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
 * 删除 Wiki 页面（{@code wiki_delete_page}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_delete_page.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { slug* }
 * </pre>
 *
 * <h2>⭐⭐ 这是破坏性操作</h2>
 * 源站把它归入需要用户确认的一类（访问策略 {@code confirm} 下会弹确认卡）。
 * 本仓当前不做操作目录（阶段三），因此本工具自身的保护是：
 * <ul>
 *   <li>作用域校验（越权删不掉）</li>
 *   <li>页面必须存在（删不存在的直接报错，而不是静默成功）</li>
 *   <li>日志记 WARN 级（删除是危险动作，运维要能看到）</li>
 * </ul>
 */
@Component
@ExclusiveTool
public class KbWikiDeletePageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiDeletePageTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired private KbWikiPageService wikiPageService;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_delete_page";
    }

    @Override
    public String description() {
        return "删除一个 Wiki 页面（破坏性操作）。页面必须存在。"
            + " 删除后指向它的链接会变成死链。只能删本智能体授权范围内的知识库里的页面。";
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
        slug.put("description", "要删除的页面 slug（必填）");
        props.set("slug", slug);

        ArrayNode required = JSON.arrayNode();
        required.add("knowledge_base_id");
        required.add("slug");
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

            if (kbId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_base_id 不能为空");
            }
            if (slug.isEmpty()) {
                return ToolResult.error(call.id(), "slug 不能为空");
            }

            String denied = KbWikiToolSupport.checkKbAllowed(scope, ctx, kbId);
            if (denied != null) {
                log.warn("[wiki_delete_page] 拒绝越权删除 kb={} slug={}", kbId, slug);
                return ToolResult.error(call.id(), denied + "，不能删除");
            }

            KbWikiPageRecord page = wikiPageService.findPage(kbId, slug);
            if (page == null) {
                return ToolResult.error(call.id(), "找不到页面 slug=" + slug + "（无需删除）");
            }

            wikiPageService.deletePage(kbId, slug);
            wikiPageService.rebuildInLinks(kbId);

            // 破坏性动作记 WARN（运维要能 grep 到）
            log.warn("[wiki_delete_page] 已删除页面 kb={} slug={} 标题={}", kbId, slug, page.getTitle());
            return ToolResult.success(call.id(),
                "{\"action\":\"deleted\",\"slug\":\"" + KbWikiToolSupport.esc(slug)
                    + "\",\"title\":\"" + KbWikiToolSupport.esc(page.getTitle()) + "\"}");
        } catch (RuntimeException e) {
            log.error("[wiki_delete_page] 执行失败", e);
            return ToolResult.error(call.id(), "删除页面失败：" + e.getMessage());
        }
    }
}
