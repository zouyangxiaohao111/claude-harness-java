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

import java.util.List;
import java.util.Locale;

/**
 * 更新 Wiki 缺陷状态（{@code wiki_update_issue}）· 入参对齐源侧
 * {@code internal/agent/tools/wiki_update_issue.go}。
 *
 * <h2>源站 schema</h2>
 * <pre>
 * { issue_id*, status* }   // status: pending / resolved / ignored
 * </pre>
 *
 * <h2>⭐⭐ 「已解决」的判定纪律（源站 6 条修复流程的第 5、6 条）</h2>
 * 源站要求：「Mark an issue resolved <b>only when the repair succeeded</b>
 * or the issue was <b>already resolved</b>」（{@code weknora_assistant} 人格第 5-6 步）。
 * ⇒ 本工具只负责改状态，<b>不判断修复是否真的成功</b>；模型必须在调用前先核对写入结果。
 * 这一点写进 {@link #description()} 里，让模型看到约束。
 */
@Component
@ExclusiveTool
public class KbWikiUpdateIssueTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(KbWikiUpdateIssueTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** 允许的状态值。 */
    static final List<String> ALLOWED_STATUS = List.of("pending", "resolved", "ignored");

    @Autowired private KbWikiPageService wikiPageService;

    @Autowired private com.nexusai.kb.repo.mapper.KbWikiPageIssueMapper issueMapper;

    @Autowired(required = false)
    private KbAgentToolScope scope;

    @Override
    public String name() {
        return "wiki_update_issue";
    }

    @Override
    public String description() {
        return "更新 Wiki 缺陷的状态（pending / resolved / ignored）。"
            + " ⚠️ 只有修复真的成功、或问题本来就已解决时，才标 resolved —— "
            + "调用前必须先核对页面写入结果。"
            + " ignored 表示用户明确说不用管，重新 Lint 时会跳过。";
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
        issueId.put("description", "问题 id（必填）");
        props.set("issue_id", issueId);

        ObjectNode status = JSON.objectNode();
        status.put("type", "string");
        status.put("description", "新状态：" + String.join(" / ", ALLOWED_STATUS));
        ArrayNode enumNode = JSON.arrayNode();
        ALLOWED_STATUS.forEach(enumNode::add);
        status.set("enum", enumNode);
        props.set("status", status);

        ArrayNode required = JSON.arrayNode();
        required.add("knowledge_base_id");
        required.add("issue_id");
        required.add("status");
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
            String status = in.path("status").asText("").trim().toLowerCase(Locale.ROOT);

            if (kbId.isEmpty()) {
                return ToolResult.error(call.id(), "knowledge_base_id 不能为空");
            }
            if (issueId.isEmpty()) {
                return ToolResult.error(call.id(), "issue_id 不能为空");
            }
            if (!ALLOWED_STATUS.contains(status)) {
                return ToolResult.error(call.id(),
                    "status 取值非法：" + status + "。允许：" + String.join(" / ", ALLOWED_STATUS));
            }

            String denied = KbWikiToolSupport.checkKbWritable(scope, ctx, kbId);
            if (denied != null) {
                log.warn("[wiki_update_issue] 拒绝越权更新 kb={}", kbId);
                return ToolResult.error(call.id(), denied + "，不能更新");
            }

            // 先确认存在且属于该库（⛔ 不能凭 id 直接改，那是越权面）
            KbWikiPageIssueRecord row = issueMapper.selectOneById(issueId);
            if (row == null || !kbId.equals(row.getKnowledgeBaseId())) {
                return ToolResult.error(call.id(), "找不到该问题（或不属于该知识库）");
            }

            KbWikiPageIssueRecord updated = wikiPageService.updateIssueStatus(kbId, issueId, status);
            if (updated == null) {
                return ToolResult.error(call.id(), "更新未生效（服务返回空结果）");
            }

            log.info("[wiki_update_issue] 状态更新 kb={} issue={} {}→{}",
                kbId, issueId, row.getStatus(), status);
            return ToolResult.success(call.id(),
                "{\"action\":\"updated\",\"issue_id\":\"" + KbWikiToolSupport.esc(issueId)
                    + "\",\"from\":\"" + KbWikiToolSupport.esc(row.getStatus())
                    + "\",\"to\":\"" + KbWikiToolSupport.esc(status) + "\"}");
        } catch (RuntimeException e) {
            log.error("[wiki_update_issue] 执行失败", e);
            return ToolResult.error(call.id(), "更新缺陷状态失败：" + e.getMessage());
        }
    }
}
