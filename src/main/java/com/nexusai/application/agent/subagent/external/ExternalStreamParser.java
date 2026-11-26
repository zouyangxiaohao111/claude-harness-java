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

    /** 带命名空间的 CLI 诊断行（{@code [namespace:code] ...}）；单段 {@code [warn]} 不算。 */
    private static final java.util.regex.Pattern TAGGED_DIAGNOSTIC =
            java.util.regex.Pattern.compile("^\\[[A-Za-z0-9_.-]+:[A-Za-z0-9_.\\-/]+\\].*$",
                    java.util.regex.Pattern.DOTALL);

    /**
     * 错误文案脱敏 · CLI 的报错里可能带 key/token，直接回显等于把它们写进 UI/日志。
     *
     * <p>只处理两种常见形态（宁可少遮不可漏遮）：
     * {@code sk-xxxx} 形式的 key；{@code api_key/token/authorization/bearer} 后跟的值。
     */
    static String redact(String s) {
        if (s == null || s.isBlank()) return s;
        String out = s.replaceAll("(?i)\\bsk-[A-Za-z0-9_-]{8,}", "sk-****");
        out = out.replaceAll("(?i)(api[_-]?key|access[_-]?token|auth[_-]?token|authorization|bearer)"
                + "\\s*[:=]\\s*[^\\s\"',}]+", "$1=****");
        // OAuth 登录链接里的流程令牌（agy 的 Eligibility 报错会带一条 Google 登录 URL）
        out = out.replaceAll("(?i)([?&](?:plt|sarp|scc|code|state|access_token|id_token)=)[^&\\s]+",
                "$1****");
        return out;
    }

    /**
     * 累积结果。
     *
     * @param text      拼出的正文（delta 累积或最终 result 覆盖）
     * @param reasoning 思考文本（thinking / reasoning）
     * @param toolCalls 工具调用序列（name + 原始参数 JSON 串）
     * @param error     CLI 自己报的错误（已脱敏）；无错误 = null。
     *                  ⚠️ 与 text 的关系：text 为空且 error 非空 ⇒ 本次没有答案；
     *                  text 非空且 error 非空 ⇒ 仍算成功，error 只作诊断（codex 会在成功的库里
     *                  夹带 item error，不能一律判失败）。
     */
    public record Parsed(String text, String reasoning, List<ToolCall> toolCalls, String error) {
        /** 兼容旧 3 参构造（既有测试/调用点）· 等价于「没有错误信号」。 */
        public Parsed(String text, String reasoning, List<ToolCall> toolCalls) {
            this(text, reasoning, toolCalls, null);
        }

        public static Parsed empty() {
            return new Parsed("", "", List.of(), null);
        }

        public boolean isEmpty() {
            return (text == null || text.isBlank()) && (reasoning == null || reasoning.isBlank())
                    && (toolCalls == null || toolCalls.isEmpty());
        }
    }

    public record ToolCall(String name, String arguments) {}

    // ────────────────── 输出格式策略表 ──────────────────
    // 为什么策略化（用户 2026-11-26）：格式分派原来散在 acceptLine/freeze 的 equalsIgnoreCase
    // 判断里；改成「格式 → 策略」静态表后，调用方只查表，未知格式仍回落到容错策略（⛔ 不抛）。
    // ⛔ 普通 interface + 静态 Map，不进 Spring / 无 SPI 扫描。
    //
    // ⚠️ 行为保真：当前实现对**所有非 plain 格式走同一个容错分发器**（claude/codex/pi/opencode
    //   的事件形状在一个 acceptNode 里按事件特征识别，而不是按声明格式裁剪）。故 jsonl /
    //   claude-stream-json / codex-jsonl / pi-jsonl 全部注册到同一个 StructuredFormat 实现 ——
    //   拆成「按格式只认自己事件」的实现会**改变现有解析结果**，属行为变化，不做。
    /** 一种输出格式的解析策略。 */
    interface StreamFormatStrategy {
        /** 处理一条已解析的 JSON 事件（plain 不需要）。 */
        void accept(Accumulator acc, JsonNode node);

        /** 是否需要尝试解析 JSON 行（plain = false，纯整段文本）。 */
        default boolean parsesJson() {
            return true;
        }

        /** 一条已知事件都没解析到正文时，是否把整段原始输出当答案（只有 plain）。 */
        default boolean rawFallback() {
            return false;
        }
    }

    /** plain：不解析 JSON，整段 stdout 就是答案（dsh conductor 语义）。 */
    private static final class PlainFormat implements StreamFormatStrategy {
        @Override public void accept(Accumulator acc, JsonNode node) {
            // 无操作：plain 不做事件解析
        }
        @Override public boolean parsesJson() {
            return false;
        }
        @Override public boolean rawFallback() {
            return true;
        }
    }

    /** 结构化 JSONL 的容错分发器（claude / codex / pi / opencode / 任意未知事件）。 */
    private static final class StructuredFormat implements StreamFormatStrategy {
        @Override public void accept(Accumulator acc, JsonNode node) {
            acc.acceptNode(node);
        }
    }

    private static final StreamFormatStrategy PLAIN_FORMAT = new PlainFormat();
    private static final StreamFormatStrategy STRUCTURED_FORMAT = new StructuredFormat();

    /** key = 小写格式名；未注册（含旧数据里的未知格式）⇒ 结构化容错策略。 */
    private static final Map<String, StreamFormatStrategy> FORMAT_STRATEGIES = Map.of(
            "plain", PLAIN_FORMAT,
            "jsonl", STRUCTURED_FORMAT,
            "claude-stream-json", STRUCTURED_FORMAT,
            "codex-jsonl", STRUCTURED_FORMAT,
            "pi-jsonl", STRUCTURED_FORMAT);

    private static final StreamFormatStrategy DEFAULT_FORMAT = STRUCTURED_FORMAT;

    private static StreamFormatStrategy strategyFor(String streamFormat) {
        if (streamFormat == null) return DEFAULT_FORMAT;
        // ⛔ 不 trim：旧实现是 equalsIgnoreCase("plain")，带空白的 " plain " 不会被当成 plain；
        //   这里保持同一口径，避免「顺手规范化」改变行为。
        return FORMAT_STRATEGIES.getOrDefault(streamFormat.toLowerCase(java.util.Locale.ROOT),
                DEFAULT_FORMAT);
    }

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
        /** 本累积器使用的格式策略（构造时查表，逐行解析不再判断格式）。 */
        private final StreamFormatStrategy formatStrategy;
        /** 无法解析的行原样保留（plain 回退用）。 */
        private final StringBuilder raw = new StringBuilder();

        Accumulator(String streamFormat) {
            this.formatStrategy = strategyFor(streamFormat);
        }

        public void acceptLine(String line) {
            if (line == null || line.isBlank()) return;
            raw.append(line).append('\n');
            if (!formatStrategy.parsesJson()) {
                // plain 不需要解析，最后整段取用
                return;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                // 非 JSON 行：可能是 CLI 的诊断/错误行（如 claude 的
                // "[claude-code:unrecognized_model] {...}"，真机实测退出码仍是 0）
                captureTaggedDiagnostic(trimmed);
                return;
            }
            JsonNode node;
            try {
                node = JSON.readTree(trimmed);
            } catch (Exception e) {
                return; // 非 JSON 行（日志混入 stdout）→ 跳过
            }
            if (node == null) return;
            try {
                formatStrategy.accept(this, node);
            } catch (Exception ignore) {
                // 单个事件结构异常不得打断整个解析
            }
        }

        private void acceptNode(JsonNode node) {
            String type = node.path("type").asText("");

            // ── agy stream-json（2026-11-26 真机实测失败分支）──
            //   {"event":"result","result":{"status":"ERROR","error":"...","response":""}}
            //   ⚠️ 成功分支的事件/字段形状**未实测**（账号被 Eligibility 拦截）⇒ 这里只捕获错误，
            //      不从 result.response 猜正文；拿到真实成功输出后再补映射。
            if (node.has("event") && "result".equals(node.path("event").asText(""))) {
                JsonNode result = node.path("result");
                String status = result.path("status").asText("");
                String err = result.path("error").asText("");
                boolean failed = "ERROR".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
                if (failed && !err.isBlank()) {
                    lastItemError = err;
                }
                return;
            }

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
            // codex 的另一条错误事件（2026-11-26 真机样本）：
            //   {"type":"error","message":"Missing environment variable: OPENAI_API_KEY."}
            if ("error".equals(type)) {
                String msg = firstNonBlank(node, "message", "error");
                if (!msg.isBlank()) lastItemError = msg;
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
         * 捕获「带命名空间的诊断行」· 例如 claude 的
         * {@code [claude-code:unrecognized_model] {"model":"..."}}。
         *
         * <p>这类行走的是 stdout 但不是 JSON，退出码仍为 0 ⇒ 旧实现完全看不见它，
         * 上层只能报「未返回任何正文」（2026-11-26 真机）。
         * ⚠️ 只认 {@code [namespace:code]} 形态：单段 {@code [warn] xxx} 不算错误信号。
         */
        private void captureTaggedDiagnostic(String line) {
            if (line == null || line.isEmpty()) return;
            if (!TAGGED_DIAGNOSTIC.matcher(line).matches()) return;
            lastItemError = line;
        }

        /**
         * 冻结解析结果。
         *
         * <p><b>「诚实的回退」只对 plain 成立</b>（用户 2026-11-04 真机踩到）：
         * pi 有一次只吐了 {@code {"type":"session",...}} 就退出，没有任何 agent_message。
         * 旧实现把整段原始 JSON 当结论并标 completed ⇒ 上层拿到一堆 JSON 当答案。
         * 结构化协议（*-jsonl / claude-stream-json）下「一条已知事件都没解析到」= **没有答案**，
         * 必须如实报空，由 runner 判失败。
         *
         * <p><b>[cli-error-signal 2026-11-26] 错误不再塞进正文</b>：以前把 lastItemError 直接
         * 当 text 返回，退出码 0 时 runner 会把错误当成「已完成的答案」（用户看到错误文本却以为成功）。
         * 现在错误走 {@link Parsed#error()}：正文为空 ⇒ runner 据此报可读失败；
         * 正文非空 ⇒ 仍算成功，错误只作诊断（codex 成功轮次里会夹带 item error，不能一刀切失败）。
         */
        public Parsed freeze() {
            String finalText = finalResult != null ? finalResult : textBuffer.toString();
            if (finalText.isBlank() && formatStrategy.rawFallback()) {
                // plain 协议：整段 stdout 就是答案（dsh conductor 语义）
                finalText = raw.toString().trim();
            }
            // 其余情况保持空白 = 「没有答案」，⛔ 不拿原始 JSON 冒充结论
            String error = (lastItemError == null || lastItemError.isBlank())
                    ? null : redact(lastItemError);
            return new Parsed(finalText, reasoning.toString(), List.copyOf(toolCalls), error);
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
