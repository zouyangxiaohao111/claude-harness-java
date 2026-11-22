package com.nexusai.application.agent.ast;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AST 工具的前端载荷构造与序列化 · 两个投放通道（实时 push / 落库重拉）的<b>单一源</b>。
 *
 * <h2>两条通道必须同形</h2>
 * {@code StreamingToolExecutor.pushToolResultRealtime}（实时）与 {@code ChatService.toolResultUiPayload}
 * （落库/F5 重拉）都走 {@link #serialize}，前端 {@code AstToolBody} 只认 {@link #KIND_GREP} /
 * {@link #KIND_EDIT}。⛔ 不要在一处单独拼 JSON：F5 前后的卡片形态就会不一致。
 *
 * <h2>上限</h2>
 * 模型面文本已受 {@code maxMatches} 与工具自身的 50 条上限约束，但结构化载荷再收一次
 * （30 条匹配 / 60 条变更，单段文本 200 字符）——前端渲染是 DOM 密集操作，长文本卡顿的收益为负。
 * 被截掉的条数写进 {@code more}，前端显示「另有 N 条未展示」。
 */
public final class AstUiPayload {

    public static final String KIND_GREP = "ast_grep";
    public static final String KIND_EDIT = "ast_edit";

    /** 工具名判定（容忍大小写/下划线/短横：AstGrep / ast_grep / ast-grep）。 */
    private static final java.util.regex.Pattern GREP_NAME =
        java.util.regex.Pattern.compile("^ast[\\s_-]?grep$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern EDIT_NAME =
        java.util.regex.Pattern.compile("^ast[\\s_-]?edit$", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final int MAX_MATCHES = 30;
    private static final int MAX_CHANGES = 60;
    private static final int MAX_TEXT = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private AstUiPayload() {}

    /**
     * AstGrep 载荷（匹配已按展示路径分组前的扁平列表）。
     *
     * @param pattern   AST 模式
     * @param lang      语言覆盖（null = 按扩展名推断）
     * @param scope     作用域展示串（入参 path 或会话工作目录）
     * @param page      分页结果（文件路径应为展示路径）
     * @param skip      本次 skip
     */
    public static Map<String, Object> grep(String pattern, String lang, String scope,
                                           AstGrepRunner.Page page, int skip) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", KIND_GREP);
        root.put("pattern", pattern);
        root.put("lang", lang);
        root.put("scope", scope);
        root.put("filesSearched", page.filesSearched());
        root.put("skip", skip);
        root.put("limitReached", page.limitReached());
        root.put("totalMatches", page.totalMatches());

        Map<String, Map<String, Object>> byFile = new LinkedHashMap<>();
        int included = 0;
        int totalInPage = page.matches().size();
        for (AstMatch m : page.matches()) {
            if (included >= MAX_MATCHES) {
                break;
            }
            Map<String, Object> fileNode = byFile.computeIfAbsent(m.file(), k -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", k);
                f.put("language", m.language());
                f.put("matches", new ArrayList<Map<String, Object>>());
                return f;
            });
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("line", m.line());
            match.put("column", m.column());
            match.put("endLine", m.endLine());
            match.put("endColumn", m.endColumn());
            match.put("text", truncate(m.text()));
            match.put("meta", new LinkedHashMap<>(m.singleMeta()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) fileNode.get("matches");
            list.add(match);
            included++;
        }
        int omitted = totalInPage - included;
        root.put("files", new ArrayList<>(byFile.values()));
        root.put("more", omitted);
        root.put("truncated", omitted > 0 || page.outputTruncated());
        return root;
    }

    /**
     * AstEdit 载荷。
     *
     * @param applied      是否已写盘
     * @param ops          改写 op（pat/out）
     * @param paths        作用域展示路径
     * @param plans        文件计划（{@code file()} 应为展示路径）
     * @param limitReached 是否有更多变更未展示
     */
    public static Map<String, Object> edit(boolean applied, List<Map<String, String>> ops,
                                           List<String> paths, List<AstEditPlan> plans,
                                           boolean limitReached) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", KIND_EDIT);
        root.put("applied", applied);
        root.put("ops", ops == null ? List.of() : ops);
        root.put("paths", paths == null ? List.of() : paths);

        int totalChanges = 0;
        for (AstEditPlan plan : plans) {
            totalChanges += plan.changes().size();
        }
        root.put("totalReplacements", totalChanges);
        root.put("filesTouched", plans.size());
        root.put("limitReached", limitReached);

        List<Map<String, Object>> files = new ArrayList<>();
        int included = 0;
        for (AstEditPlan plan : plans) {
            Map<String, Object> fileNode = new LinkedHashMap<>();
            fileNode.put("path", plan.file());
            List<Map<String, Object>> changes = new ArrayList<>();
            for (AstEditPlan.Change c : plan.changes()) {
                if (included >= MAX_CHANGES) {
                    break;
                }
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("line", c.line());
                change.put("column", c.column());
                change.put("endLine", c.endLine());
                change.put("endColumn", c.endColumn());
                change.put("before", truncate(c.before()));
                change.put("after", truncate(c.after()));
                changes.add(change);
                included++;
            }
            fileNode.put("changes", changes);
            files.add(fileNode);
            if (included >= MAX_CHANGES) {
                break;
            }
        }
        int omitted = totalChanges - included;
        root.put("files", files);
        root.put("more", omitted);
        root.put("truncated", omitted > 0);
        return root;
    }

    /**
     * 工具名 + 结构化数据 → 前端 JSON 串。
     *
     * @param toolName 工具名（只认 AstGrep/AstEdit 的拼写变体）
     * @param data     {@code ToolResult.data}（{@code successWithStructuredOutput} 的 Map）
     * @return JSON 串；非 AST 工具 / 非 Map / kind 不符 / 序列化失败 → null
     */
    public static String serialize(String toolName, Object data) {
        if (toolName == null || !(GREP_NAME.matcher(toolName).matches() || EDIT_NAME.matcher(toolName).matches())) {
            return null;
        }
        if (!(data instanceof Map<?, ?> raw)) {
            return null;
        }
        Object kind = raw.get("kind");
        boolean grepTool = GREP_NAME.matcher(toolName).matches();
        if (grepTool ? !KIND_GREP.equals(kind) : !KIND_EDIT.equals(kind)) {
            return null;
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if ("summary".equals(e.getKey())) {
                continue; // summary 只给模型面/兜底文本，不进 UI 载荷
            }
            clean.put(String.valueOf(e.getKey()), e.getValue());
        }
        try {
            return JSON.writeValueAsString(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…";
    }
}
