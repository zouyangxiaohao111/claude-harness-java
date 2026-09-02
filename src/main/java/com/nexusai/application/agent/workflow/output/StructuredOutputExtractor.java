package com.nexusai.application.agent.workflow.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.AgentRunResultOk;
import com.nexusai.application.agent.workflow.StructuredOutputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * schema 模式下从 agent 终态消息中提取 JSON 对象 · 对齐 CC
 * {@code Open-ClaudeCode/packages/workflow-engine/src/engine/structuredOutput.ts:82-149 全} +
 * {@code claudeCodeBackend.ts:371-398 分类} + {@code engine/hooks.ts:346-363 二次校验}。
 *
 * <p><b>健壮性策略</b>（按优先级，返回首个成功解析者）：
 * <ol>
 *   <li><b>fenced code block</b>（```json ... ``` 或 ``` ... ```）——agent 常自发加围栏（structuredOutput.ts:95-101）</li>
 *   <li><b>裸文本中首个「括号平衡」的 {...} 片段</b>——容忍前后叙述/多段输出（structuredOutput.ts:102-110）</li>
 * </ol>
 *
 * <p><b>括号平衡扫描</b>（非 {@code indexOf('{')..lastIndexOf('}')}）：正确处理嵌套对象、
 * 字符串字面量内的 {@code {}}、转义字符；不拼接多个不相关 JSON 片段（structuredOutput.ts:72-73）。
 * <b>不做语法修复</b>（尾逗号/单引号/注释）——agent 不产出非标准 JSON，修复反而可能误改字符串内
 * 内容（structuredOutput.ts:75-77）。解析失败直接跳到下一候选。
 * <b>仅返回纯对象</b>（object 且非 null 且非 array）——schema 模式契约是对象，
 * 数组/数字/字符串都视为 agent 跑偏（structuredOutput.ts:79-80）。</p>
 *
 * <p><b>{@code {kind:'dead', reason}} 分类</b>（claudeCodeBackend.ts:373-389 + hooks.ts:346-363）：
 * <ul>
 *   <li>文本中找不到纯对象 JSON → {@code dead{NO_STRUCTURED_OUTPUT, detail=200 字预览}}</li>
 *   <li>找到但不符合调用方 JSON Schema → {@code dead{INVALID_STRUCTURED_OUTPUT, detail=错误列表}}</li>
 * </ul>
 * 200 字预览 = 全部 text 块按 {@code '\n'} 拼接后截前 200 字符（claudeCodeBackend.ts:377-379
 * {@code extractTextContent(finalized.content, '\n').slice(0, 200)}）。</p>
 *
 * <p><b>接线</b>：W-2a {@code ClaudeCodeBackendAdapter} 在 schema 模式下调用
 * {@link #classifySchemaMode(Object, List)} 完成「提取 → 校验 → 分类」；引擎边界
 * {@link StructuredOutputValidator#validateStructuredResult} 做二次校验（含 String 输出解析）。</p>
 */
public final class StructuredOutputExtractor {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputExtractor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 预览截断长度 · CC original: {@code .slice(0, 200)} (claudeCodeBackend.ts:379) */
    private static final int PREVIEW_LIMIT = 200;

    /**
     * fenced code block 匹配 · CC original:
     * {@code /```[\t ]*[a-zA-Z0-9_-]*\s*\n([\s\S]*?)\n?```/g} (structuredOutput.ts:96-98)。
     * 组 1 = 围栏内全部内容（含换行）；围栏语言标签可为空/任意 {@code [a-zA-Z0-9_-]} 序列。
     */
    private static final Pattern FENCE_PATTERN = Pattern.compile(
            "```[\\t ]*[a-zA-Z0-9_-]*\\s*\\n([\\s\\S]*?)\\n?```");

    private StructuredOutputExtractor() {
    }

    /**
     * 从内容块数组中提取首个可解析为纯对象的 JSON · CC original: {@code extractStructuredOutput}
     * (structuredOutput.ts:82-91)。非 "text" 块或空 text 直接跳过；返回纯对象 Map 或 null。
     *
     * @param content 内容块数组（text 块参与提取）
     * @return 纯对象 Map；未找到返回 null
     */
    public static Object extractStructuredOutput(List<StructuredContentBlock> content) {
        if (content == null) {
            return null;
        }
        for (StructuredContentBlock block : content) {
            if (!"text".equals(block.type()) || block.text() == null) {
                continue;
            }
            Object found = findFirstJsonObject(block.text());
            if (found != null) {
                if (log.isDebugEnabled()) {
                    log.debug("StructuredOutputExtractor 在 text 块中提取到结构化对象（type={}）", block.type());
                }
                return found;
            }
        }
        return null;
    }

    /**
     * 查找 text 中首个可解析为纯对象的 JSON 片段 · CC original: {@code findFirstJsonObject}
     * (structuredOutput.ts:94-111)。
     * <ol>
     *   <li>fenced code block —— 优先级（agent 天然爱加围栏；剥围栏后整块解析）</li>
     *   <li>裸文本：逐 '{' 找平衡对并尝试解析</li>
     * </ol>
     */
    private static Object findFirstJsonObject(String text) {
        // 1. fenced code blocks（structuredOutput.ts:96-101）
        Matcher m = FENCE_PATTERN.matcher(text);
        while (m.find()) {
            Object parsed = tryParseObject(m.group(1));
            if (parsed != null) {
                return parsed;
            }
        }
        // 2. 裸文本括号平衡扫描（structuredOutput.ts:103-109）
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{') {
                continue;
            }
            int end = findBalancedObjectEnd(text, i);
            if (end < 0) {
                continue;
            }
            Object parsed = tryParseObject(text.substring(i, end + 1));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /**
     * 从 start（必须为 '{'）找匹配的 '}' 下标；不平衡返回 -1。
     * 跳过字符串字面量与转义字符内的括号；不跳过注释（JSON 标准不允许注释，agent 不产出；
     * 跳过反而有风险——见 {@link StructuredOutputExtractor} 类文档）·
     * CC original: {@code findBalancedObjectEnd} (structuredOutput.ts:118-137)。
     */
    static int findBalancedObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // 跳过转义符及其后一字符（structuredOutput.ts:124-125）
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 尝试把候选解析为纯对象 · CC original: {@code tryParseObject} (structuredOutput.ts:140-149)。
     * 修剪后必须以 '{' 开头且以 '}' 结尾；JSON 解析成功且为纯对象（非数组/非 null/非标量）才返回，
     * 否则 null。
     */
    private static Object tryParseObject(String candidate) {
        String trimmed = candidate.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (node != null && node.isObject()) {
                return MAPPER.convertValue(node, Map.class);
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 200 字预览 · CC original: {@code extractTextContent(finalized.content, '\n').slice(0, 200)}
     * (claudeCodeBackend.ts:377-379)。全部 text 块按 '\n' 拼接后截前 200 字符，供 dead detail
     * 让 hooks 重试日志/面板立即看到 agent 实际说了什么。
     *
     * @param content 内容块数组
     * @return 截断后的文本预览（可能为空串）
     */
    public static String extractPreview(List<StructuredContentBlock> content) {
        if (content == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (StructuredContentBlock block : content) {
            if ("text".equals(block.type()) && block.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(block.text());
            }
        }
        if (sb.length() <= PREVIEW_LIMIT) {
            return sb.toString();
        }
        return sb.substring(0, PREVIEW_LIMIT);
    }

    /**
     * schema 模式完整分类：提取 → 校验（Ajv 式）→ 产出 {@code AgentRunResult} ·
     对齐 CC adapter 侧 (claudeCodeBackend.ts:371-397) + 引擎边界二次校验 (hooks.ts:346-363)。
     *
     * <ul>
     *   <li>未找到纯对象 JSON → {@code dead{NO_STRUCTURED_OUTPUT, detail=200 字预览}}</li>
     *   <li>找到但不符合 schema → {@code dead{INVALID_STRUCTURED_OUTPUT, detail=错误 '; ' 拼接}}</li>
     *   <li>找到且符合 → {@code ok{output=紧凑 JSON 字符串, outputTokens=0}}（token 由 adapter 覆盖，
     *       本方法不知最终 usage）</li>
     * </ul>
     *
     * @param schema  JSON Schema（null → 跳过校验，直接 ok）
     * @param content agent 终态消息内容块
     * @return ok 或 dead（带 reason 分类 + detail）
     */
    public static AgentRunResult classifySchemaMode(Object schema, List<StructuredContentBlock> content) {
        Object structured = extractStructuredOutput(content);
        if (structured == null) {
            String preview = extractPreview(content);
            log.warn("StructuredOutputExtractor schema 模式未找到 JSON 对象 → dead{{no-structured-output}}，预览前 200 字：{}",
                    preview);
            return new AgentRunResultDead(AgentRunResult.DeadReason.NO_STRUCTURED_OUTPUT, preview);
        }
        StructuredOutputValidator.ValidationResult v =
                StructuredOutputValidator.validateAgainstSchema(structured, schema);
        if (!v.valid()) {
            String errors = String.join("; ", v.errors());
            log.warn("StructuredOutputExtractor 提取到 JSON 但不匹配 schema → dead{{invalid-structured-output}}：{}",
                    errors);
            return new AgentRunResultDead(AgentRunResult.DeadReason.INVALID_STRUCTURED_OUTPUT, errors);
        }
        try {
            String json = MAPPER.writeValueAsString(structured);
            if (log.isDebugEnabled()) {
                log.debug("StructuredOutputExtractor schema 模式通过校验 → ok{{output={}}}", json);
            }
            return new AgentRunResultOk(json, 0, null, null, null);
        } catch (JsonProcessingException e) {
            // 提取出的 Map 再序列化失败几乎不可能（readTree→convertValue 是确定性往返）；
            // 仍显式失败，不吞错误（CLAUDE.md 规则 12 · Fail loud）
            String errors = "serialize structured output failed: " + e.getMessage();
            log.error("StructuredOutputExtractor 序列化提取结果失败 → dead{{invalid-structured-output}}：{}", errors);
            return new AgentRunResultDead(AgentRunResult.DeadReason.INVALID_STRUCTURED_OUTPUT, errors);
        }
    }
}
