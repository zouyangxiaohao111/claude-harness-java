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
import com.nexusai.kb.repo.entity.KbWikiPageIssueRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取 Wiki 缺陷（{@code wiki_read_issue}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_read_issue.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { issue_id, slug }   // 二选一：给 issue_id 读单条；给 slug 读该页面的全部问题
 * </pre>
 */
@Component
@ExclusiveTool
public class KbWikiReadIssueTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiReadIssueTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 一次最多返回多少条问题。 */
    static final int MAX_ISSUES = 50;

    @Autowired private KbWikiPageService wikiPageService;

    @Autowired private com.nexusai.kb.repo.mapper.KbWikiPageIssueMapper issueMapper;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_read_issue";
    }

    @Override
    public String description() {
        return "读取 Wiki 缺陷。给 issue_id 读单条；给 slug 读该页面的全部问题（可不带 status 过滤，或指定 pending/resolved/ignored）。";
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

        ObjectNode issueId = JSON.objectNode();
        issueId.put("type", "string");
        issueId.put("description", "问题 id。给了它就读单条，忽略 slug / status。");
        props.set("issue_id", issueId);

        ObjectNode slug = JSON.objectNode();
        slug.put("type", "string");
        slug.put("description", "页面 slug。按页面读该页的全部问题。");
        props.set("slug", slug);

        ObjectNode status = JSON.objectNode();
        status.put("type", "string");
        status.put("description", "状态过滤（可选）：pending / resolved / ignored。不传 = 全部。");
        props.set("status", status);

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
            String issueId = in.path("issue_id").asText("").trim();
            String slug = in.path("slug").asText("").trim();
            String status = in.path("status").asText("").trim();

            if (kbId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_base_id 不能为空");
            }
            if (issueId.isEmpty() && slug.isEmpty()) {
                return ToolResult.error(call.id(), "issue_id 与 slug 至少给一个");
            }

            String denied = KbWikiToolSupport.checkKbAllowed(scope, ctx, kbId);
            if (denied != null) {
                log.warn("[wiki_read_issue] 拒绝越权读取 kb={}", kbId);
                return ToolResult.error(call.id(), denied + "，不能读取");
            }

            // ── 按 issue_id 读单条 ────────────────────────────────────────────
            if (!issueId.isEmpty()) {
                KbWikiPageIssueRecord row = issueMapper.selectOneById(issueId);
                if (row == null || !kbId.equals(row.getKnowledgeBaseId())) {
                    return ToolResult.success(call.id(),
                        "{\"issues\":[],\"note\":\"找不到该问题（或不属于该知识库）\"}");
                }
                log.info("[wiki_read_issue] 读取单条 kb={} issue={}", kbId, issueId);
                return ToolResult.success(call.id(),
                    "{\"issues\":[" + KbWikiToolSupport.issueJson(row) + "],\"total\":1}");
            }

            // ── 按 slug 读该页全部问题 ────────────────────────────────────────
            List<KbWikiPageIssueRecord> rows = wikiPageService.listIssues(
                kbId, status.isEmpty() ? null : status);
            List<KbWikiPageIssueRecord> picked = new ArrayList<>();
            for (KbWikiPageIssueRecord r : rows == null ? List.<KbWikiPageIssueRecord>of() : rows) {
                if (r == null || !slug.equals(r.getSlug())) {
                    continue;
                }
                picked.add(r);
                if (picked.size() >= MAX_ISSUES) {
                    break;
                }
            }

            StringBuilder sb = new StringBuilder("{\"issues\":[");
            for (int i = 0; i < picked.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(KbWikiToolSupport.issueJson(picked.get(i)));
            }
            sb.append("],\"total\":").append(picked.size());
            if (picked.size() >= MAX_ISSUES) {
                sb.append(",\"note\":\"已达返回上限 ").append(MAX_ISSUES).append("\"");
            }
            sb.append('}');

            log.info("[wiki_read_issue] 读取页面问题 kb={} slug={} status={} 条数={}",
                kbId, slug, status.isEmpty() ? "-" : status, picked.size());
            return ToolResult.success(call.id(), sb.toString());
        } catch (RuntimeException e) {
            log.error("[wiki_read_issue] 执行失败", e);
            return ToolResult.error(call.id(), "读取缺陷失败：" + e.getMessage());
        }
    }
}
