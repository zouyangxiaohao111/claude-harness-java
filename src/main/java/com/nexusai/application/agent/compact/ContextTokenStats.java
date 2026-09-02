package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 压缩上下文 token 分类统计 · 对齐 CC {@code analyzeContext} + {@code tokenStatsToStatsigMetrics}
 * （Open-ClaudeCode/src/utils/contextAnalysis.ts:27-193 + :195-267）。
 *
 * <p><b>[IMP-CM-17] 用途</b>: {@code tengu_compact} 事件（compact.ts:650-695）尾部
 * {@code ...tokenStatsToStatsigMetrics(analyzeContext(messages))} 上下文窗口 breakdown。
 * Java 端把 CC 逐 content-block 走查适配为 ChatMessageDto 模型（content 字符串 + toolCalls +
 * role=tool 消息经 toolCallId 关联），产出与 CC 相同属性名的 statsig 指标 map。
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: 压缩遥测的 breakdown 属性名必须与 CC 一致
 * （total_tokens / human_message_tokens / tool_request_{tool}_tokens / duplicate_read_tokens 等），
 * 属性名漂移 = analytics 数据丢失。
 *
 * <p>CC 算法要点（上下文分析，非逐字照搬）：
 * <ul>
 *   <li>text 走 roughTokenCountEstimation（len/4）；tool_use/tool_result 块走其 JSON 字符串估算</li>
 *   <li>user 文本含 {@code local-command-stdout} → localCommandOutputs（CC :42/:89-91）</li>
 *   <li>tool_use → toolRequests[toolName]；toolId→name 映射；Read 工具记录 file_path（CC :103-131）</li>
 *   <li>tool_result → toolResults[toolName]（经 tool_use_id 解析）；Read 结果累计 fileReadStats（CC :134-153）</li>
 *   <li>重复文件读：count&gt;1 → avg*(count-1) 计入 duplicateFileReads（CC :155-166）</li>
 * </ul>
 */
final class ContextTokenStats {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ContextTokenStats() {
    }

    /** 单块 token 估算 · CC original: {@code countTokens}（contextAnalysis.ts:28，len/4 粗估）。 */
    static int roughTokens(String text) {
        if (text == null) {
            return 0;
        }
        return (int) Math.round(text.length() / 4.0);
    }

    /** 工具调用块 JSON 化 token 估算 · CC original: {@code countTokens(jsonStringify(block))}。 */
    static int toolBlockTokens(ToolCallDto call) {
        if (call == null) {
            return 0;
        }
        String repr = "{name:" + (call.name() == null ? "" : call.name())
            + ",input:" + (call.arguments() == null ? "" : call.arguments()) + "}";
        return roughTokens(repr);
    }

    /**
     * 分析消息集 → statsig 指标 map · 对齐 CC {@code tokenStatsToStatsigMetrics(analyzeContext(messages))}
     * （contextAnalysis.ts:195-267）。
     *
     * @param messages 压缩前消息集（Java ChatMessageDto，等效 CC API 归一化消息）
     * @return 与 CC 属性名一致的 map；空消息 → 全 0 指标（CC 对空集同样产出全 0 map）
     */
    static Map<String, Object> analyze(List<ChatMessageDto> messages) {
        Map<String, Integer> toolRequests = new LinkedHashMap<>();
        Map<String, Integer> toolResults = new LinkedHashMap<>();
        Map<String, Long> attachments = new LinkedHashMap<>();
        Map<String, FileReadStat> fileReads = new LinkedHashMap<>();
        Map<String, String> toolIdsToNames = new LinkedHashMap<>();
        Map<String, String> readToolIdToFilePath = new LinkedHashMap<>();
        long humanTokens = 0L;
        long assistantTokens = 0L;
        long localCommandTokens = 0L;
        long otherTokens = 0L;
        long total = 0L;

        if (messages == null) {
            return statsigMetrics(total, humanTokens, assistantTokens, localCommandTokens,
                otherTokens, attachments, toolRequests, toolResults, fileReads);
        }

        for (ChatMessageDto msg : messages) {
            if (msg == null) {
                continue;
            }
            Role role = msg.role();
            String content = msg.content();

            // 文本内容（user/assistant text + tool result 文本）· CC :68-84 字符串分支
            if (content != null && !content.isBlank()) {
                long tokens = roughTokens(content);
                total += tokens;
                if (role == Role.user) {
                    if (content.contains("local-command-stdout")) {
                        // CC :42/:89-91 —— local-command 输出归入独立桶
                        localCommandTokens += tokens;
                    } else {
                        humanTokens += tokens;
                    }
                } else if (role == Role.assistant) {
                    assistantTokens += tokens;
                } else if (role == Role.tool) {
                    // tool_result · CC :134-153：经 toolCallId 解析工具名
                    String toolName = toolIdsToNames.get(msg.toolCallId());
                    if (toolName == null) {
                        toolName = "unknown";
                    }
                    toolResults.merge(toolName, (int) tokens, Integer::sum);
                    // Read 结果累计文件读统计 · CC :145-153
                    trackFileRead(toolName, msg.toolCallId(), tokens, readToolIdToFilePath, fileReads);
                } else {
                    otherTokens += tokens;
                }
            }

            // 工具调用块（assistant 消息内嵌 tool_use）· CC :103-131 tool_use 分支
            if (msg.toolCalls() != null) {
                for (ToolCallDto call : msg.toolCalls()) {
                    if (call == null || call.name() == null) {
                        continue;
                    }
                    long tokens = toolBlockTokens(call);
                    total += tokens;
                    toolRequests.merge(call.name(), (int) tokens, Integer::sum);
                    if (call.id() != null) {
                        toolIdsToNames.put(call.id(), call.name());
                    }
                    if ("Read".equals(call.name()) && call.id() != null) {
                        String path = extractFilePath(call.arguments());
                        if (path != null) {
                            readToolIdToFilePath.put(call.id(), path);
                        }
                    }
                }
            }
        }

        // 重复文件读 · CC :155-166（count>1 → avg*(count-1)）
        Map<String, FileReadStat> duplicates = new LinkedHashMap<>();
        for (Map.Entry<String, FileReadStat> e : fileReads.entrySet()) {
            FileReadStat stat = e.getValue();
            if (stat.count > 1) {
                long avg = stat.totalTokens / stat.count;
                long dupTokens = avg * (stat.count - 1);
                duplicates.put(e.getKey(), new FileReadStat(stat.count, dupTokens));
            }
        }

        return statsigMetrics(total, humanTokens, assistantTokens, localCommandTokens,
            otherTokens, attachments, toolRequests, toolResults, duplicates);
    }

    /** tool_use input 提取 file_path · CC :119-126（Read 工具，input 为 object 且含 file_path） */
    private static String extractFilePath(String argumentsJson) {
        if (argumentsJson == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(argumentsJson);
            JsonNode fp = node.get("file_path");
            if (fp != null && fp.isTextual()) {
                return fp.asText();
            }
        } catch (Exception ignored) {
            // arguments 非合法 JSON → 不追踪（CC 仅对 object input 有 'file_path' in input 判定）
        }
        return null;
    }

    /** Read 结果累计 · CC :145-153 */
    private static void trackFileRead(String toolName, String toolUseId, long tokens,
                                      Map<String, String> readToolIdToFilePath,
                                      Map<String, FileReadStat> fileReads) {
        if (!"Read".equals(toolName) || toolUseId == null) {
            return;
        }
        String path = readToolIdToFilePath.get(toolUseId);
        if (path == null) {
            return;
        }
        FileReadStat current = fileReads.computeIfAbsent(path, p -> new FileReadStat(0, 0));
        fileReads.put(path, new FileReadStat(current.count + 1, current.totalTokens + tokens));
    }

    /** statsig 指标组装 · 对齐 CC tokenStatsToStatsigMetrics（contextAnalysis.ts:195-267）。 */
    private static Map<String, Object> statsigMetrics(long total,
                                                      long humanTokens,
                                                      long assistantTokens,
                                                      long localCommandTokens,
                                                      long otherTokens,
                                                      Map<String, Long> attachments,
                                                      Map<String, Integer> toolRequests,
                                                      Map<String, Integer> toolResults,
                                                      Map<String, FileReadStat> duplicates) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("total_tokens", total);
        metrics.put("human_message_tokens", humanTokens);
        metrics.put("assistant_message_tokens", assistantTokens);
        metrics.put("local_command_output_tokens", localCommandTokens);
        metrics.put("other_tokens", otherTokens);

        attachments.forEach((type, count) -> metrics.put("attachment_" + type + "_count", count));
        toolRequests.forEach((tool, tokens) -> metrics.put("tool_request_" + tool + "_tokens", tokens));
        toolResults.forEach((tool, tokens) -> metrics.put("tool_result_" + tool + "_tokens", tokens));

        long duplicateTotal = 0L;
        for (FileReadStat stat : duplicates.values()) {
            duplicateTotal += stat.totalTokens;
        }
        metrics.put("duplicate_read_tokens", duplicateTotal);
        metrics.put("duplicate_read_file_count", duplicates.size());

        if (total > 0) {
            metrics.put("human_message_percent", Math.round(humanTokens * 100.0 / total));
            metrics.put("assistant_message_percent", Math.round(assistantTokens * 100.0 / total));
            metrics.put("local_command_output_percent", Math.round(localCommandTokens * 100.0 / total));
            metrics.put("duplicate_read_percent", Math.round(duplicateTotal * 100.0 / total));

            long toolRequestTotal = 0L;
            for (Integer v : toolRequests.values()) {
                toolRequestTotal += v;
            }
            long toolResultTotal = 0L;
            for (Integer v : toolResults.values()) {
                toolResultTotal += v;
            }
            metrics.put("tool_request_percent", Math.round(toolRequestTotal * 100.0 / total));
            metrics.put("tool_result_percent", Math.round(toolResultTotal * 100.0 / total));

            toolRequests.forEach((tool, tokens) ->
                metrics.put("tool_request_" + tool + "_percent", Math.round(tokens * 100.0 / total)));
            toolResults.forEach((tool, tokens) ->
                metrics.put("tool_result_" + tool + "_percent", Math.round(tokens * 100.0 / total)));
        }
        return metrics;
    }

    /** 文件读统计 · CC fileReadStats（contextAnalysis.ts:140-144） */
    private record FileReadStat(int count, long totalTokens) {
    }
}
