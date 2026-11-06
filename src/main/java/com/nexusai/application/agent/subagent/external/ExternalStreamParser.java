package com.nexusai.application.agent.subagent.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 CLI 输出解析器 · 把 stdout 的一行行文本还原成「思考 / 工具调用 / 正文 / 结论」。
 *
 * <p>支持的 {@code streamFormat}：
 * <ul>
 *   <li>{@code claude-stream-json} —— Claude Code {@code -p --output-format stream-json} 的
 *       JSONL 事件（{@code assistant} / {@code content_block_delta} / {@code result}）</li>
 *   <li>{@code jsonl} —— 任意 JSONL，按已知键名容错提取（opencode / pi 等）</li>
 *   <li>{@code plain} —— 整段 stdout 当结论（dsh-agent-conductor 的 "stdout directly becomes the answer"）</li>
 * </ul>
 *
 * <p>⛔ 解析必须是<b>容错</b>的：外部 CLI 版本一变就可能多出新事件类型，未知事件一律跳过，
 * 不得因此让整次委派失败。解析不到任何文本时才退化为「整段 stdout」。
 */
public final class ExternalStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 累积结果。
     *
     * @param text      拼出的正文（delta 累积或最终 result 覆盖）
     * @param reasoning 思考文本（thinking / reasoning）
     * @param toolCalls 工具调用序列（name + 原始参数 JSON 串）
     */
    public record Parsed(String text, String reasoning, List<ToolCall> toolCalls) {
        public static Parsed empty() {
            return new Parsed("", "", List.of());
        }

        public boolean isEmpty() {
            return (text == null || text.isBlank()) && (reasoning == null || reasoning.isBlank())
                    && (toolCalls == null || toolCalls.isEmpty());
        }
    }

    public record ToolCall(String name, String arguments) {}

    /** 可变累积器（解析是逐行的，最后冻结成 {@link Parsed}）。 */
    public static final class Accumulator {
        private final StringBuilder textBuffer = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final List<ToolCall> toolCalls = new ArrayList<>();
        /** result 事件的权威结论（优先于 delta 累积）。 */
        private String finalResult;
        /**
         * 最后一条「行内错误项」· codex 会在 JSONL 里发
         * {@code item.completed{item.type=error}}（如 WebSocket 回退、重连）。
         * 这类错误**伴随成功答案**出现，故不能一律判失败 —— 只有最终既无正文又只有错误时才用。
         */
        private String lastItemError;
        private final String streamFormat;
        /** 无法解析的行原样保留（plain 回退用）。 */
        private final StringBuilder raw = new StringBuilder();

        Accumulator(String streamFormat) {
            this.streamFormat = streamFormat;
        }

        public void acceptLine(String line) {
            if (line == null || line.isBlank()) return;
            raw.append(line).append('\n');
            if ("plain".equalsIgnoreCase(streamFormat)) {
                // plain 不需要解析，最后整段取用
                return;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) return;
            JsonNode node;
            try {
                node = JSON.readTree(trimmed);
            } catch (Exception e) {
                return; // 非 JSON 行（日志混入 stdout）→ 跳过
            }
            if (node == null) return;
            try {
                acceptNode(node);
            } catch (Exception ignore) {
                // 单个事件结构异常不得打断整个解析
            }
        }

        private void acceptNode(JsonNode node) {
            String type = node.path("type").asText("");

            // ── opencode-jsonl（`opencode run --format json`，2026-11-04 真机实测）──
            //   形态：{type:"text", part:{type:"text", text:"42"}}
            //   顶层 type 与 part.type 同名，但**正文只在 part.text** ——
            //   若先走下面的"顶层找 text 字段"分支，会取到空串并最终回退成整段原始 JSON。
            if (node.has("part") && node.get("part").isObject()) {
                JsonNode part = node.get("part");
                String pType = part.path("type").asText("");
                if ("text".equals(pType)) {
                    appendText(part.path("text").asText(""));
                    return;
                }
                if ("reasoning".equals(pType) || "thinking".equals(pType)) {
                    appendReasoning(part.path("text").asText(""));
                    return;
                }
                if ("tool".equals(pType) || "tool_use".equals(pType)) {
                    addToolCall(part.path("tool").asText(""), part.path("state").path("input"));
                    return;
                }
                // step-start / step-finish 等无文本事件：明确吞掉，不让它污染回退
                if ("step-start".equals(pType) || "step-finish".equals(pType)
                        || "step_start".equals(pType) || "step_finish".equals(pType)) {
                    return;
                }
            }

            // ── claude-stream-json ──
            if ("assistant".equals(type)) {
                JsonNode content = node.path("message").path("content");
                acceptContentArray(content);
                return;
            }
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.path("delta");
                String dType = delta.path("type").asText("");
                if ("text_delta".equals(dType)) {
                    appendText(delta.path("text").asText(""));
                } else if ("thinking_delta".equals(dType)) {
                    appendReasoning(delta.path("thinking").asText(""));
                }
                return;
            }
            if ("result".equals(type)) {
                String result = node.path("result").asText("");
                if (!result.isBlank()) finalResult = result;
                return;
            }

            // ── codex-jsonl（`codex exec --json`，2026-11-04 真机实测形态）──
            //   实测事件：thread.started / turn.started / item.completed / turn.completed
            //   item.type ∈ agent_message | reasoning | command_execution | error
            //   ⚠️ 必须用 item.completed 而不是 turn.completed 取答案：turn.completed 只带 usage。
            if ("item.completed".equals(type) || "item.started".equals(type)) {
                JsonNode item = node.path("item");
                String itemType = item.path("type").asText("");
                if ("agent_message".equals(itemType)) {
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        // 同一 turn 可能有多条 agent_message：按块拼接（不是同一段 delta）
                        if (textBuffer.length() > 0) textBuffer.append('\n');
                        textBuffer.append(text);
                    }
                } else if ("reasoning".equals(itemType)) {
                    appendReasoning(item.path("text").asText(""));
                } else if ("command_execution".equals(itemType)) {
                    addToolCall("shell", item.has("command") ? item.get("command") : null);
                } else if ("error".equals(itemType) && "item.completed".equals(type)) {
                    lastItemError = item.path("message").asText("");
                }
                return;
            }
            if ("turn.failed".equals(type)) {
                lastItemError = node.path("error").path("message").asText("turn failed");
                return;
            }

            // ── pi-jsonl（`pi -p --mode json`，2026-11-04 真机实测形态）──
            //   message_update.assistantMessageEvent.type ∈ text_start | text_delta | text_end
            //   agent_end.messages[] 是**权威终态**（含每条 assistant 的 content 数组）
            if ("message_update".equals(type)) {
                JsonNode ev = node.path("assistantMessageEvent");
                String evType = ev.path("type").asText("");
                if ("text_delta".equals(evType)) {
                    appendText(ev.path("delta").asText(""));
                } else if ("thinking_delta".equals(evType)) {
                    appendReasoning(ev.path("delta").asText(""));
                } else if ("toolcall_end".equals(evType) || "tool_call".equals(evType)) {
                    addToolCall(ev.path("toolName").asText(""), ev.path("input"));
                }
                return;
            }
            if ("message_end".equals(type)) {
                JsonNode msg = node.path("message");
                if ("assistant".equals(msg.path("role").asText(""))) {
                    String t = joinTextParts(msg.path("content"));
                    if (!t.isBlank()) {
                        if (textBuffer.length() > 0) textBuffer.append('\n');
                        textBuffer.append(t);
                    }
                }
                return;
            }
            if ("agent_end".equals(type)) {
                JsonNode msgs = node.path("messages");
                if (msgs.isArray()) {
                    StringBuilder last = new StringBuilder();
                    for (JsonNode m : msgs) {
                        if (!"assistant".equals(m.path("role").asText(""))) continue;
                        String t = joinTextParts(m.path("content"));
                        if (!t.isBlank()) last.setLength(0);
                        if (!t.isBlank()) last.append(t);
                    }
                    if (last.length() > 0) finalResult = last.toString();
                }
                return;
            }

            // ── 通用容错：任意 JSONL 里找 text / reasoning / tool ──
            if ("thinking".equals(type) || "reasoning".equals(type)) {
                appendReasoning(firstNonBlank(node, "thinking", "text", "reasoning"));
                return;
            }
            if ("text".equals(type)) {
                appendText(firstNonBlank(node, "text", "content"));
                return;
            }
            if ("tool_use".equals(type) || "tool_call".equals(type)) {
                String name = firstNonBlank(node, "name", "tool", "toolName");
                JsonNode args = node.has("input") ? node.get("input") : node.path("arguments");
                addToolCall(name, args);
                return;
            }
        }

        private void acceptContentArray(JsonNode content) {
            if (content == null || !content.isArray()) return;
            for (JsonNode block : content) {
                String bType = block.path("type").asText("");
                if ("text".equals(bType)) {
                    appendText(block.path("text").asText(""));
                } else if ("thinking".equals(bType)) {
                    appendReasoning(block.path("thinking").asText(""));
                } else if ("tool_use".equals(bType)) {
                    addToolCall(block.path("name").asText(""), block.get("input"));
                }
            }
        }

        private void appendText(String s) {
            if (s != null && !s.isEmpty()) textBuffer.append(s);
        }

        /** pi 的 content 是 block 数组：`[{"type":"text","text":"5"}]`。 */
        private static String joinTextParts(JsonNode content) {
            if (content == null || !content.isArray()) {
                return content != null && content.isTextual() ? content.asText() : "";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText(""))) {
                    sb.append(block.path("text").asText(""));
                }
            }
            return sb.toString();
        }

        private void appendReasoning(String s) {
            if (s != null && !s.isEmpty()) reasoning.append(s);
        }

        private void addToolCall(String name, JsonNode args) {
            if (name == null || name.isBlank()) return;
            String argStr = "";
            if (args != null && !args.isNull() && !args.isMissingNode()) {
                try {
                    argStr = args.isTextual() ? args.asText() : JSON.writeValueAsString(args);
                } catch (Exception e) {
                    argStr = args.toString();
                }
            }
            toolCalls.add(new ToolCall(name, argStr));
        }

        private static String firstNonBlank(JsonNode node, String... keys) {
            for (String k : keys) {
                String v = node.path(k).asText("");
                if (!v.isBlank()) return v;
            }
            return "";
        }

        /**
         * 冻结解析结果。
         *
         * <p><b>「诚实的回退」只对 plain 成立</b>（用户 2026-11-04 真机踩到）：
         * pi 有一次只吐了 {@code {"type":"session",...}} 就退出，没有任何 agent_message。
         * 旧实现把整段原始 JSON 当结论并标 completed ⇒ 上层拿到一堆 JSON 当答案。
         * 结构化协议（*-jsonl / claude-stream-json）下「一条已知事件都没解析到」= **没有答案**，
         * 必须如实报空，由 runner 判失败。
         */
        public Parsed freeze() {
            String finalText = finalResult != null ? finalResult : textBuffer.toString();
            boolean nothingParsed = finalText.isBlank() && reasoning.toString().isBlank()
                    && toolCalls.isEmpty();
            if (nothingParsed) {
                if ("plain".equalsIgnoreCase(streamFormat)) {
                    // plain 协议：整段 stdout 就是答案（dsh conductor 语义）
                    finalText = raw.toString().trim();
                } else if (lastItemError != null && !lastItemError.isBlank()) {
                    // 结构化协议且只收到错误项 ⇒ 用错误文本作结论（runner 会判 failed）
                    finalText = lastItemError;
                }
                // 其余情况保持空白 = 「没有答案」，⛔ 不拿原始 JSON 冒充结论
            } else if (finalText.isBlank() && lastItemError != null && !lastItemError.isBlank()) {
                finalText = lastItemError;
            }
            return new Parsed(finalText, reasoning.toString(), List.copyOf(toolCalls));
        }

        /** 是否只收到错误、没有任何正文（供 runner 判失败）。 */
        public boolean isErrorOnly() {
            return textBuffer.toString().isBlank() && finalResult == null
                    && lastItemError != null && !lastItemError.isBlank();
        }
    }

    public static Accumulator accumulator(String streamFormat) {
        return new Accumulator(streamFormat == null ? "plain" : streamFormat);
    }

    /** 便捷入口：一次解析整段输出（测试用）。 */
    public static Parsed parse(String streamFormat, String stdout) {
        Accumulator acc = accumulator(streamFormat);
        if (stdout != null) {
            for (String line : stdout.split("\\r?\\n")) {
                acc.acceptLine(line);
            }
        }
        return acc.freeze();
    }

    /**
     * 拼成 transcript 用的消息 map 列表（复用 {@code AgentTranscript.recordSidechainTranscript}）。
     *
     * @param parsed    解析结果
     * @param timestamp ISO-8601
     */
    public static List<Map<String, Object>> toTranscriptMessages(Parsed parsed, String timestamp) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (parsed.reasoning() != null && !parsed.reasoning().isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "assistant");
            m.put("content", "");
            m.put("reasoning", parsed.reasoning());
            m.put("timestamp", timestamp);
            out.add(m);
        }
        if (!parsed.toolCalls().isEmpty()) {
            List<Map<String, Object>> tcs = new ArrayList<>();
            for (ToolCall tc : parsed.toolCalls()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", "external-" + out.size() + "-" + tcs.size());
                one.put("name", tc.name());
                one.put("arguments", tc.arguments());
                tcs.add(one);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "assistant");
            m.put("content", "");
            m.put("toolCalls", tcs);
            m.put("timestamp", timestamp);
            out.add(m);
        }
        if (parsed.text() != null && !parsed.text().isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "assistant");
            m.put("content", parsed.text());
            m.put("timestamp", timestamp);
            out.add(m);
        }
        return out;
    }

    private ExternalStreamParser() {
    }
}
