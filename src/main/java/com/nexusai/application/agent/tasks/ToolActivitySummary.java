package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具活动摘要 · 对齐 CC {@code ToolActivity} + {@code summarizeRecentActivities}
 * （LocalAgentTask.tsx:24-32、collapseReadSearch.ts:1146-1181）。
 *
 * <p>CC 的活动描述由各工具自己的 {@code getActivityDescription} 生成（Read → “Reading &lt;file&gt;”，
 * Grep → “Searching for &lt;pattern&gt;”），再由 {@code summarizeRecentActivities} 把尾部连续的
 * search/read 合并成一句话。Java 侧没有 {@code getActivityDescription} 接口，本类按工具名 + 入参
 * 生成等价描述（中文，与任务清单 UI 一致）。
 *
 * <p>用途：in-process teammate 每收到一条带 tool_use 的 assistant 消息时采集一条
 * （{@link #toActivity}，最多保留 {@value #MAX_RECENT} 条），REST 侧读取清单时用
 * {@link #summarize} 合并成进行中任务第二行的活动文案。
 */
public final class ToolActivitySummary {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** CC MAX_RECENT_ACTIVITIES = 5（LocalAgentTask.tsx:51）。 */
    public static final int MAX_RECENT = 5;
    private static final Set<String> SEARCH_TOOLS = Set.of("Grep", "Glob", "WebSearch");
    private static final Set<String> READ_TOOLS = Set.of("Read", "NotebookRead");
    /** 单段入参文案上限（避免长命令/长路径把一行撑爆）。 */
    private static final int MAX_TEXT = 60;

    private ToolActivitySummary() { /* 静态工具类 */ }

    /**
     * 由一次工具调用生成活动记录 · 对齐 CC {@code updateProgressFromMessage} 里 push 的形状。
     *
     * @param toolName      工具名（ToolCallDto.name）
     * @param argumentsJson 工具入参 JSON 字符串（ToolCallDto.arguments；可为 null/非法）
     * @return 活动记录；toolName 空白 → null
     */
    @Nullable
    public static MainSessionTaskState.ToolActivity toActivity(
            @Nullable String toolName, @Nullable String argumentsJson) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        Map<String, Object> input = parseArguments(argumentsJson);
        return new MainSessionTaskState.ToolActivity(
                toolName,
                input,
                describe(toolName, input),
                SEARCH_TOOLS.contains(toolName),
                READ_TOOLS.contains(toolName));
    }

    /**
     * 合并最近活动为一句可显示文案 · 对齐 CC {@code summarizeRecentActivities}
     * （collapseReadSearch.ts:1146-1181）。
     *
     * <p>规则：从尾部数连续的 search/read；≥2 条 → 合并成「正在搜索 N 个模式，读取 M 个文件」；
     * 否则回退到最近一条带描述的（工具各自的活动文案）。
     *
     * @param activities 最近活动（可为 null/空）
     * @return 一句话；无可用活动 → null
     */
    @Nullable
    public static String summarize(@Nullable List<MainSessionTaskState.ToolActivity> activities) {
        if (activities == null || activities.isEmpty()) {
            return null;
        }
        int searchCount = 0;
        int readCount = 0;
        for (int i = activities.size() - 1; i >= 0; i--) {
            MainSessionTaskState.ToolActivity a = activities.get(i);
            if (Boolean.TRUE.equals(a.isSearch())) {
                searchCount++;
            } else if (Boolean.TRUE.equals(a.isRead())) {
                readCount++;
            } else {
                break;
            }
        }
        if (searchCount + readCount >= 2) {
            List<String> parts = new ArrayList<>();
            if (searchCount > 0) {
                parts.add("正在搜索 " + searchCount + " 个模式");
            }
            if (readCount > 0) {
                parts.add("读取 " + readCount + " 个文件");
            }
            return String.join("，", parts);
        }
        for (int i = activities.size() - 1; i >= 0; i--) {
            String desc = activities.get(i).activityDescription();
            if (desc != null && !desc.isBlank()) {
                return desc;
            }
        }
        return null;
    }

    /** 追加一条活动并裁剪到 {@link #MAX_RECENT} 条（尾部保留最新 · CC :120-122 shift）。 */
    public static List<MainSessionTaskState.ToolActivity> append(
            @Nullable List<MainSessionTaskState.ToolActivity> existing,
            @Nullable MainSessionTaskState.ToolActivity activity) {
        List<MainSessionTaskState.ToolActivity> out =
                existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        if (activity != null) {
            out.add(activity);
        }
        while (out.size() > MAX_RECENT) {
            out.remove(0);
        }
        return out;
    }

    private static Map<String, Object> parseArguments(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(
                    e.getKey(),
                    e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
            return out;
        } catch (Exception e) {
            // 入参不是合法 JSON：活动照样记录（只丢失描述里的具体路径/命令）
            return Map.of();
        }
    }

    private static String describe(String toolName, Map<String, Object> input) {
        String path = firstText(input, "file_path", "path");
        String pattern = firstText(input, "pattern", "query");
        switch (toolName) {
            case "Read":
                return path != null ? "正在读取 " + shorten(path) : "正在读取文件";
            case "Grep":
                return pattern != null ? "正在搜索 \"" + shorten(pattern) + "\"" : "正在搜索";
            case "Glob":
                return pattern != null ? "正在搜索 " + shorten(pattern) : "正在查找文件";
            case "Write":
                return path != null ? "正在写入 " + shorten(path) : "正在写入文件";
            case "Edit":
                return path != null ? "正在编辑 " + shorten(path) : "正在编辑文件";
            case "Bash": {
                String cmd = firstText(input, "command", "cmd");
                return cmd != null ? "正在执行 " + shorten(cmd) : "正在执行命令";
            }
            default:
                return "正在使用 " + toolName;
        }
    }

    @Nullable
    private static String firstText(Map<String, Object> input, String... keys) {
        for (String k : keys) {
            Object v = input.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static String shorten(String text) {
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() > MAX_TEXT ? oneLine.substring(0, MAX_TEXT) + "…" : oneLine;
    }
}
