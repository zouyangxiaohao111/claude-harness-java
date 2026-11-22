package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重启后从<b>主会话 DB 消息</b>的 tool_use / tool_result 配对恢复子代理名字。
 *
 * <h2>WHY（对齐 CC）</h2>
 * <p>CC 的 {@code agentNameRegistry} 是 appState 内存态，进程重启即空；但主会话 transcript 里
 * 留有完整的调用链：assistant 的 {@code Agent}/{@code Task} tool_use（入参含 {@code name}）+
 * 对应的 tool_result（{@code {status:"async_launched", agentId}}）。CC 重启后按这条链路恢复
 * 「名字 → agentId」，<b>不依赖</b>子代理 sidecar 里的 {@code name}（历史数据的 meta 可能
 * 只有 agentType）。本类就是这条配对规则的唯一实现。
 *
 * <p><b>纯函数、只读</b>：不注册活跃、不占配额、不写磁盘/DB、不猜名字。配不上的条目在调用方
 * 计数（{@link #scan} 的 Result 计数）后留空。
 *
 * <h2>配对规则</h2>
 * <ol>
 *   <li>tool_use：role=assistant 消息的 toolCalls 中，工具名为 {@code Agent}/{@code Task}，
 *       {@code arguments} JSON 含非空 {@code name}；以 tool_use id 为键暂存。</li>
 *   <li>tool_result：role=tool 消息的 {@code toolCallId} 命中暂存项；其 {@code content}
 *       （兜底：toolCalls 里的 UI 载荷 result）必须是 JSON 对象且
 *       {@code status=="async_launched"}、{@code agentId} 非空。同步完成的 Agent 调用
 *       （{@code status:"completed"}）<b>不</b>产生映射（CC 只在 async spawn 时登记名字）。</li>
 *   <li>同一 agentId 多次出现 ⇒ 以<b>最后一次</b>为准，并把该条移动到顺序末位；同名两次
 *       spawn 不同 agentId ⇒ 由调用方按本类的顺序依次写入退役表，从而「最后一次」赢。</li>
 * </ol>
 */
public final class AgentNameRecovery {

    private static final Logger log = LoggerFactory.getLogger(AgentNameRecovery.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentNameRecovery() { /* utility */ }

    /**
     * 读取入口：返回某会话的 DB 消息（按 seq 升序，含 toolCalls）。
     *
     * <p>生产实现 = {@code MessageService#listRawForTranscript}；测试可注入 lambda。
     */
    @FunctionalInterface
    public interface MessageSource {
        List<ChatMessageDto> load(String ownerSessionId);
    }

    /** 一条恢复结果（agentId → name）。 */
    public record Pair(String agentId, String name) {}

    /**
     * 一次扫描的结果。
     *
     * @param orderedPairs      按「配对在消息流中最后一次出现」排序的配对（写退役表的顺序）
     * @param namesByAgentId    agentId → name（内容与 orderedPairs 一致）
     * @param toolUses          带非空 name 的 Agent/Task tool_use 数
     * @param unnamedToolUses   Agent/Task tool_use 但入参无 name（不产生映射）
     * @param unpairedToolUses  有 name 但始终没有配对上的 tool_result（含非 async 结果）
     * @param unpairedResults   tool_result 存在但拿不到 async_launched/agentId（或找不到 tool_use）
     * @param duplicateAgentIds 同一 agentId 被配对多次的次数（以最后一次为准）
     */
    public record Result(List<Pair> orderedPairs, Map<String, String> namesByAgentId,
                         int toolUses, int unnamedToolUses, int unpairedToolUses,
                         int unpairedResults, int duplicateAgentIds) {

        public static final Result EMPTY =
            new Result(List.of(), Map.of(), 0, 0, 0, 0, 0);

        /** 该 agentId 恢复出的名字；无配对 → null（⛔ 不猜）。 */
        public String nameOf(String agentId) {
            return agentId == null ? null : namesByAgentId.get(agentId);
        }

        public int pairs() {
            return orderedPairs.size();
        }
    }

    /**
     * 从读取入口取消息并恢复名字。会话不可读（已删除 / DB 异常）⇒ {@link Result#EMPTY}，
     * 由调用方回落 meta.name；日志只计数，不抛。
     */
    public static Result recover(MessageSource source, String ownerSessionId) {
        if (source == null || ownerSessionId == null || ownerSessionId.isBlank()) {
            return Result.EMPTY;
        }
        List<ChatMessageDto> messages;
        try {
            messages = source.load(ownerSessionId);
        } catch (Exception e) {
            log.warn("[name-recovery] 读取会话消息失败，跳过 DB 名字恢复 session={}: {}",
                ownerSessionId, e.toString());
            return Result.EMPTY;
        }
        Result result = scan(messages);
        if (result.pairs() > 0) {
            log.info("[name-recovery] session={} 从 tool_use/tool_result 配对恢复 {} 个名字"
                    + "（tool_use={} unpaired={} unnamed={} result-unpaired={} dup-agentId={}）",
                ownerSessionId, result.pairs(), result.toolUses(), result.unpairedToolUses(),
                result.unnamedToolUses(), result.unpairedResults(), result.duplicateAgentIds());
        } else if (log.isDebugEnabled()) {
            log.debug("[name-recovery] session={} 无可用配对（tool_use={} unpaired={} unnamed={} "
                    + "result-unpaired={}）",
                ownerSessionId, result.toolUses(), result.unpairedToolUses(),
                result.unnamedToolUses(), result.unpairedResults());
        }
        return result;
    }

    /**
     * 纯扫描：按消息顺序配对 tool_use（Agent/Task + name）与 tool_result
     * （status=async_launched + agentId）。
     *
     * @param messages 会话消息（时间序；可为 null/空）
     * @return 配对结果（同一 agentId 以最后一次为准）
     */
    public static Result scan(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return Result.EMPTY;
        }
        Map<String, PendingUse> pending = new LinkedHashMap<>();
        Map<String, Pair> byAgentId = new LinkedHashMap<>();
        int toolUses = 0;
        int unnamed = 0;
        int unpairedResults = 0;
        int duplicates = 0;

        for (ChatMessageDto m : messages) {
            if (m == null) {
                continue;
            }
            if (m.role() == Role.assistant) {
                List<ToolCallDto> calls = m.toolCalls();
                if (calls == null) {
                    continue;
                }
                for (ToolCallDto tc : calls) {
                    if (tc == null || tc.id() == null || tc.id().isBlank()
                            || !isAgentTool(tc.name())) {
                        continue;
                    }
                    String name = nameFromArguments(tc.arguments());
                    if (name == null || name.isBlank()) {
                        unnamed++;
                        continue;
                    }
                    toolUses++;
                    pending.put(tc.id(), new PendingUse(name, tc.result()));
                }
                continue;
            }
            if (m.role() != Role.tool || m.toolCallId() == null) {
                continue;
            }
            PendingUse use = pending.remove(m.toolCallId());
            if (use == null) {
                if (agentIdFromResult(m.content()) != null) {
                    unpairedResults++;
                }
                continue;
            }
            String agentId = agentIdFromResult(m.content());
            if (agentId == null) {
                // 兜底：tool_calls.result（UI 载荷）——生产与 content 同源，旧数据可能只有这一列
                agentId = agentIdFromResult(use.uiResult());
            }
            if (agentId == null) {
                unpairedResults++;
                continue;
            }
            if (byAgentId.containsKey(agentId)) {
                duplicates++;
            }
            // 同一 agentId 以最后一次为准：先删再插，把位置移到「最后一次出现」的次序。
            byAgentId.remove(agentId);
            byAgentId.put(agentId, new Pair(agentId, use.name()));
        }

        List<Pair> ordered = new ArrayList<>(byAgentId.values());
        Map<String, String> names = new LinkedHashMap<>();
        for (Pair p : ordered) {
            names.put(p.agentId(), p.name());
        }
        return new Result(List.copyOf(ordered), Collections.unmodifiableMap(names),
            toolUses, unnamed, pending.size(), unpairedResults, duplicates);
    }

    /** 暂存的 tool_use（tool_result 到达时消费）。 */
    private record PendingUse(String name, String uiResult) {}

    /** 工具名是否 Agent/Task（CC 现名 + 旧 wire 名）。 */
    private static boolean isAgentTool(String toolName) {
        return AgentToolConstants.AGENT_TOOL_NAME.equals(toolName)
            || AgentToolConstants.LEGACY_AGENT_TOOL_NAME.equals(toolName);
    }

    /** 从 tool_use 入参 JSON 取 name（缺失/空白 → null）。 */
    private static String nameFromArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(arguments);
            if (node == null || !node.isObject()) {
                return null;
            }
            JsonNode name = node.get("name");
            if (name == null || !name.isTextual()) {
                return null;
            }
            String text = name.asText().trim();
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 tool_result 文本取 agentId —— 只认 {@code status:"async_launched"} 且 agentId 非空；
     * 其余（同步 completed / 非 JSON / 缺字段）⇒ null（不猜）。
     */
    private static String agentIdFromResult(String text) {
        if (text == null || text.isBlank() || !text.contains("async_launched")) {
            // 廉价预筛：同步完成 / 普通文本工具结果不可能是 async spawn，免 JSON 解析开销。
            return null;
        }
        String trimmed = text.trim();
        JsonNode node = readJsonObject(trimmed);
        if (node == null) {
            // 兼容结果被前后文案包裹的历史形态：截取首个 '{' 到末个 '}' 再试一次。
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            node = readJsonObject(trimmed.substring(start, end + 1));
        }
        if (node == null) {
            return null;
        }
        if (!"async_launched".equals(node.path("status").asText(null))) {
            return null;
        }
        JsonNode agentId = node.get("agentId");
        if (agentId == null || !agentId.isTextual()) {
            return null;
        }
        String id = agentId.asText().trim();
        return id.isBlank() ? null : id;
    }

    private static JsonNode readJsonObject(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
