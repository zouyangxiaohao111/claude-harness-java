package com.nexusai.application.agent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * remote_agent SDK log 解析 · 对齐 CC RemoteAgentTask/RemoteAgentTask.tsx
 * （extractTodoListFromLog :365-379 / extractReviewFromLog :254-283 /
 *  extractReviewTagFromLog :295-319 / extractPlanFromLog :208-218）。
 *
 * <p>事件模型：{@code List<Map<String,Object>>}，每项为 SDKMessage 的 JSON Map
 * （assistant / system / result ...），content/stdout 为原始 JSON 结构。
 */
public final class RemoteAgentLogParser {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentLogParser.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC constants/xml.ts:41 — <ultraplan> */
    public static final String ULTRAPLAN_TAG = "ultraplan";
    /** CC constants/xml.ts:45 — <remote-review> */
    public static final String REMOTE_REVIEW_TAG = "remote-review";
    /** CC constants/xml.ts:49 — <remote-review-progress> */
    public static final String REMOTE_REVIEW_PROGRESS_TAG = "remote-review-progress";

    /** CC TodoWriteTool.name — extractTodoListFromLog（:366） */
    public static final String TODO_WRITE_TOOL_NAME = "TodoWrite";

    private RemoteAgentLogParser() { /* utility class */ }

    /** CC extractTag（utils/messages.ts）— 首个 &lt;tag&gt;...&lt;/tag&gt; 非贪婪匹配。 */
    public static String extractTag(String text, String tag) {
        if (text == null) {
            return null;
        }
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int openAt = text.indexOf(open);
        if (openAt == -1) {
            return null;
        }
        int contentStart = openAt + open.length();
        int closeAt = text.indexOf(close, contentStart);
        if (closeAt == -1) {
            return null;
        }
        return text.substring(contentStart, closeAt);
    }

    /**
     * CC extractTextContent(content, '\n') 等价 — assistant content 块（List）中所有
     * text 块以换行拼接。content 为 String 时直接返回。
     */
    @SuppressWarnings("unchecked")
    public static String extractTextContent(Object content, String sep) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block) {
                    if ("text".equals(String.valueOf(block.get("type")))) {
                        Object text = block.get("text");
                        if (text != null) {
                            if (sb.length() > 0) {
                                sb.append(sep);
                            }
                            sb.append(text);
                        }
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** CC extractTodoListFromLog（:365-379）— 找最后一个 TodoWrite tool_use，返回其 input.todos。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractTodoListFromLog(List<Map<String, Object>> log) {
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = log.get(i);
            if (msg == null || !"assistant".equals(String.valueOf(msg.get("type")))) {
                continue;
            }
            Object content = msg.get("message") instanceof Map<?, ?> m ? m.get("content") : null;
            if (!(content instanceof List<?> blocks)) {
                continue;
            }
            Map<String, Object> todoBlock = null;
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block && "tool_use".equals(String.valueOf(block.get("type")))
                        && TODO_WRITE_TOOL_NAME.equals(String.valueOf(block.get("name")))) {
                    todoBlock = (Map<String, Object>) block;
                }
            }
            if (todoBlock == null) {
                continue;
            }
            Object input = todoBlock.get("input");
            if (input instanceof Map<?, ?> in) {
                Object todos = in.get("todos");
                if (todos instanceof List<?> tl) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Object t : tl) {
                        if (t instanceof Map<?, ?>) {
                            result.add((Map<String, Object>) t);
                        }
                    }
                    return result;
                }
            }
            return new ArrayList<>();
        }
        return new ArrayList<>();
    }

    /** CC extractPlanFromLog（:208-218）— 反向扫 assistant 消息，首个 &lt;ultraplan&gt; 内容。 */
    @SuppressWarnings("unchecked")
    public static String extractPlanFromLog(List<Map<String, Object>> log) {
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = log.get(i);
            if (msg == null || !"assistant".equals(String.valueOf(msg.get("type")))) {
                continue;
            }
            Object content = msg.get("message") instanceof Map<?, ?> m ? m.get("content") : null;
            String fullText = extractTextContent(content, "\n");
            String plan = extractTag(fullText, ULTRAPLAN_TAG);
            if (plan != null && !plan.trim().isEmpty()) {
                return plan.trim();
            }
        }
        return null;
    }

    /** CC extractReviewFromLog（:254-283）— 完整变体：tag 扫描 + hook stdout 拼接 + 全 assistant 文本兜底。 */
    @SuppressWarnings("unchecked")
    public static String extractReviewFromLog(List<Map<String, Object>> log) {
        // hook_progress / hook_response 每消息扫描（bughunter 路径）
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = log.get(i);
            if (isHookStdout(msg)) {
                String tagged = extractTag(String.valueOf(msg.get("stdout")), REMOTE_REVIEW_TAG);
                if (tagged != null && !tagged.trim().isEmpty()) {
                    return tagged.trim();
                }
            }
        }
        // assistant 文本每消息扫描（prompt 模式）
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = log.get(i);
            if (msg == null || !"assistant".equals(String.valueOf(msg.get("type")))) {
                continue;
            }
            Object content = msg.get("message") instanceof Map<?, ?> m ? m.get("content") : null;
            String tagged = extractTag(extractTextContent(content, "\n"), REMOTE_REVIEW_TAG);
            if (tagged != null && !tagged.trim().isEmpty()) {
                return tagged.trim();
            }
        }
        // hook stdout concat fallback（跨事件截断 tag）
        StringBuilder hookStdout = new StringBuilder();
        for (Map<String, Object> msg : log) {
            if (isHookStdout(msg)) {
                hookStdout.append(String.valueOf(msg.get("stdout")));
            }
        }
        String hookTagged = extractTag(hookStdout.toString(), REMOTE_REVIEW_TAG);
        if (hookTagged != null && !hookTagged.trim().isEmpty()) {
            return hookTagged.trim();
        }
        // 全 assistant 文本时序拼接兜底（:281-282）
        StringBuilder allText = new StringBuilder();
        for (Map<String, Object> msg : log) {
            if (msg == null || !"assistant".equals(String.valueOf(msg.get("type")))) {
                continue;
            }
            Object content = msg.get("message") instanceof Map<?, ?> m ? m.get("content") : null;
            String t = extractTextContent(content, "\n").trim();
            if (!t.isEmpty()) {
                if (allText.length() > 0) {
                    allText.append('\n');
                }
                allText.append(t);
            }
        }
        return allText.length() > 0 ? allText.toString() : null;
    }

    /**
     * CC extractReviewTagFromLog（:295-319）— tag-only 变体：无 tag 时<b>不</b>回退 assistant 文本。
     * delta 扫描关键：prompt 模式早期未打 tag 的 assistant 消息不得触发 fallback 提前完成。
     */
    @SuppressWarnings("unchecked")
    public static String extractReviewTagFromLog(List<Map<String, Object>> log) {
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = log.get(i);
            if (isHookStdout(msg)) {
                String tagged = extractTag(String.valueOf(msg.get("stdout")), REMOTE_REVIEW_TAG);
                if (tagged != null && !tagged.trim().isEmpty()) {
                    return tagged.trim();
                }
            }
        }
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = log.get(i);
            if (msg == null || !"assistant".equals(String.valueOf(msg.get("type")))) {
                continue;
            }
            Object content = msg.get("message") instanceof Map<?, ?> m ? m.get("content") : null;
            String tagged = extractTag(extractTextContent(content, "\n"), REMOTE_REVIEW_TAG);
            if (tagged != null && !tagged.trim().isEmpty()) {
                return tagged.trim();
            }
        }
        StringBuilder hookStdout = new StringBuilder();
        for (Map<String, Object> msg : log) {
            if (isHookStdout(msg)) {
                hookStdout.append(String.valueOf(msg.get("stdout")));
            }
        }
        String hookTagged = extractTag(hookStdout.toString(), REMOTE_REVIEW_TAG);
        if (hookTagged != null && !hookTagged.trim().isEmpty()) {
            return hookTagged.trim();
        }
        return null;
    }

    /** CC :260/:299 — system + (hook_progress | hook_response) 事件。 */
    private static boolean isHookStdout(Map<String, Object> msg) {
        if (msg == null || !"system".equals(String.valueOf(msg.get("type")))) {
            return false;
        }
        String subtype = String.valueOf(msg.get("subtype"));
        return "hook_progress".equals(subtype) || "hook_response".equals(subtype);
    }

    /** JSON stringify（CC jsonStringify 等价）— delta 输出文本化。 */
    public static String jsonStringify(Map<String, Object> msg) {
        try {
            return JSON.writeValueAsString(msg);
        } catch (Exception e) {
            return String.valueOf(msg);
        }
    }
}
