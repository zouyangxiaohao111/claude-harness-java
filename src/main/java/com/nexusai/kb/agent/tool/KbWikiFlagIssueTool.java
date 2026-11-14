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
import com.nexusai.kb.app.wiki.KbWikiLinter;
import com.nexusai.kb.app.wiki.KbWikiPageService;
import com.nexusai.kb.repo.entity.KbWikiPageIssueRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 上报 Wiki 缺陷（{@code wiki_flag_issue}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_flag_issue.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { slug*, issue_type, description*, suspected_knowledge_ids[] }
 * </pre>
 *
 * <h2>⭐⭐ 与 Linter 的关系</h2>
 * {@link KbWikiLinter} 是<b>自动</b>扫出来的问题（死链、空页、孤儿页 …；
 * 由 {@code replacePendingIssues} 整批替换）。本工具是<b>模型/用户主动</b>上报的问题，
 * 两者共用 {@code kb_wiki_page_issue} 表，但来源不同：
 * <ul>
 *   <li>Linter 产出的 {@code reported_by} = 系统</li>
 *   <li>本工具产出的 {@code reported_by} = {@code agent}</li>
 * </ul>
 * ⇒ <b>⛔ 不能调 {@code replacePendingIssues}</b>：那会把 Linter 之外的
 * 所有 pending 问题一并抹掉（包括历史人工上报的）。
 * 所以这里直接走 mapper 单条插入。
 */
@Component
@ExclusiveTool
public class KbWikiFlagIssueTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiFlagIssueTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 上报来源标记（与 Linter 的产出区分）。 */
    static final String REPORTED_BY = "agent";

    /** 允许的问题类型（与 Linter 的检查项对齐，外加 agent 自定义）。 */
    private static final List<String> ALLOWED_TYPES = List.of(
        "dead_link", "orphan_page", "empty_page", "broken_citation",
        "outdated_content", "contradictory_content", "missing_source", "other");

    @Autowired private com.nexusai.kb.repo.mapper.KbWikiPageIssueMapper issueMapper;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_flag_issue";
    }

    @Override
    public String description() {
        return "上报一个 Wiki 页面缺陷，供后续修复。会用 description 说明问题，"
            + " suspected_knowledge_ids 指出怀疑有问题的来源文档。"
            + " 这是「记录问题」而不是「修复问题」——修复请用 wiki_replace_text / wiki_write_page。";
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
        slug.put("description", "出问题的页面 slug（必填）");
        props.set("slug", slug);

        ObjectNode type = JSON.objectNode();
        type.put("type", "string");
        type.put("description", "问题类型，取值：" + String.join(" / ", ALLOWED_TYPES));
        props.set("issue_type", type);

        ObjectNode desc = JSON.objectNode();
        desc.put("type", "string");
        desc.put("description", "问题描述（必填）：具体哪里不对、期望是什么");
        props.set("description", desc);

        ObjectNode ids = JSON.objectNode();
        ids.put("type", "array");
        ids.put("description", "怀疑有问题的来源文档 id 列表（可选）");
        ObjectNode items = JSON.objectNode();
        items.put("type", "string");
        ids.set("items", items);
        props.set("suspected_knowledge_ids", ids);

        ArrayNode required = JSON.arrayNode();
        required.add("knowledge_base_id");
        required.add("slug");
        required.add("description");
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
            String description = in.path("description").asText("").trim();
            String issueType = in.path("issue_type").asText("").trim().toLowerCase(Locale.ROOT);

            if (kbId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_base_id 不能为空");
            }
            if (slug.isEmpty()) {
                return ToolResult.error(call.id(), "slug 不能为空");
            }
            if (description.isEmpty()) {
                return ToolResult.error(call.id(), "description 不能为空（要说明具体哪里不对）");
            }
            if (issueType.isEmpty()) {
                issueType = "other";
            } else if (!ALLOWED_TYPES.contains(issueType)) {
                return ToolResult.error(call.id(),
                    "issue_type 取值非法：" + issueType + "。允许：" + String.join(" / ", ALLOWED_TYPES));
            }

            String denied = KbWikiToolSupport.checkKbWritable(scope, ctx, kbId);
            if (denied != null) {
                log.warn("[wiki_flag_issue] 拒绝越权上报 kb={} slug={}", kbId, slug);
                return ToolResult.error(call.id(), denied + "，不能上报");
            }

            List<String> suspected = readArray(in, "suspected_knowledge_ids");

            KbWikiPageIssueRecord row = new KbWikiPageIssueRecord();
            row.setId(java.util.UUID.randomUUID().toString());
            row.setKnowledgeBaseId(kbId);
            row.setSlug(slug);
            row.setIssueType(issueType);
            row.setSeverity("warning");
            row.setDescription(description);
            row.setSuspectedKnowledgeIds(KbWikiPageService.toJsonArray(suspected));
            row.setStatus("pending");
            row.setReportedBy(REPORTED_BY);
            issueMapper.insert(row);

            log.info("[wiki_flag_issue] 上报完成 kb={} slug={} type={} id={}",
                kbId, slug, issueType, row.getId());
            return ToolResult.success(call.id(),
                "{\"action\":\"flagged\",\"issue_id\":\"" + KbWikiToolSupport.esc(row.getId())
                    + "\",\"slug\":\"" + KbWikiToolSupport.esc(slug)
                    + "\",\"type\":\"" + KbWikiToolSupport.esc(issueType)
                    + "\",\"status\":\"pending\"}");
        } catch (RuntimeException e) {
            log.error("[wiki_flag_issue] 执行失败", e);
            return ToolResult.error(call.id(), "上报缺陷失败：" + e.getMessage());
        }
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
