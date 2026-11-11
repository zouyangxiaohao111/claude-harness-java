package com.nexusai.kb.agent.tool;

import com.nexusai.kb.repo.entity.KbWikiPageIssueRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;

import java.util.List;
import java.util.Locale;

/**
 * Wiki 工具共用的小工具方法 · 只在 {@code com.nexusai.kb.agent.tool} 包内使用。
 *
 * <p>抽出来的原因：9 个 wiki 工具都要做同一件事 ——
 * ①作用域校验（kbId 必须在该智能体的授权范围内）②JSON 转义 ③页面记录 → 紧凑 JSON。
 * 每处复制一遍的话，作用域校验漏一个就是越权漏洞。
 */
final class KbWikiToolSupport {

    private KbWikiToolSupport() {
    }

    /**
     * 作用域校验：该知识库是否在本智能体的授权范围内。
     *
     * <p>⚠️ <b>写工具必须调用</b>；读工具也调用（越权读同样是漏洞）。
     *
     * @param scope  会话作用域（可 {@code null} = 未绑定智能体 = 不限制）
     * @param ctx    工具上下文（可能为 null）
     * @param kbId   目标知识库
     * @return 拒绝理由；通过时返回 {@code null}
     */
    static String checkKbAllowed(KbAgentToolScope scope,
                                 com.nexusai.application.agent.tool.ToolUseContext ctx,
                                 String kbId) {
        if (scope == null) {
            return null;
        }
        List<String> allowed = scope.allowedKnowledgeBaseIds(ctx);
        if (allowed == null) {
            return null;
        }
        if (allowed.isEmpty() || !allowed.contains(kbId)) {
            return "该知识库不在本智能体的授权范围内";
        }
        return null;
    }

    /** 页面 → 紧凑 JSON（slug / title / page_type / summary / 内容长度，不含正文）。 */
    static String pageBrief(KbWikiPageRecord p) {
        return "{\"slug\":\"" + esc(p.getSlug())
            + "\",\"title\":\"" + esc(p.getTitle())
            + "\",\"page_type\":\"" + esc(p.getPageType())
            + "\",\"summary\":\"" + esc(truncate(p.getSummary(), 300))
            + "\",\"content_length\":" + (p.getContent() == null ? 0 : p.getContent().length())
            + "}";
    }

    /** 页面 → 完整 JSON（含正文）。 */
    static String pageFull(KbWikiPageRecord p) {
        return "{\"slug\":\"" + esc(p.getSlug())
            + "\",\"title\":\"" + esc(p.getTitle())
            + "\",\"page_type\":\"" + esc(p.getPageType())
            + "\",\"summary\":\"" + esc(p.getSummary())
            + "\",\"content\":\"" + esc(p.getContent())
            + "\",\"aliases\":\"" + esc(p.getAliases())
            + "\",\"source_refs\":\"" + esc(p.getSourceRefs())
            + "\"}";
    }

    /** 问题 → 紧凑 JSON。 */
    static String issueJson(KbWikiPageIssueRecord r) {
        return "{\"issue_id\":\"" + esc(r.getId())
            + "\",\"type\":\"" + esc(r.getIssueType())
            + "\",\"severity\":\"" + esc(r.getSeverity())
            + "\",\"slug\":\"" + esc(r.getSlug())
            + "\",\"description\":\"" + esc(truncate(r.getDescription(), 500))
            + "\",\"status\":\"" + esc(r.getStatus())
            + "\"}";
    }

    /** JSON 字符串转义（含换行/制表）。 */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 截断到 max 个字符（超出加省略号）。 */
    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 安全字符串（null → 空串）。 */
    static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 小写化（null → 空串）。 */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
