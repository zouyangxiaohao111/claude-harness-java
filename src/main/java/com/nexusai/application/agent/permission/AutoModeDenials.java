package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * auto-mode 分类器拒绝记录 · 对齐 CC {@code utils/autoModeDenials.ts}.
 *
 * <p><b>WHY 存在</b>: auto-mode 分类器自动拒绝命令后, 用户需要能复盘 "为什么被自动拒"
 * (CC /permissions 面板 RecentDenialsTab 读取本 store). 只把 Deny 注入 LLM 不记录 →
 * 用户完全看不到自动拒绝的理由.
 *
 * <p>CC 真源 (autoModeDenials.ts):
 * <pre>{@code
 * export type AutoModeDenial = { toolName, display, reason, timestamp }
 * const MAX_DENIALS = 20
 * export function recordAutoModeDenial(denial) {
 *   if (!feature('TRANSCRIPT_CLASSIFIER')) return
 *   DENIALS = [denial, ...DENIALS.slice(0, MAX_DENIALS - 1)]
 * }
 * }</pre>
 *
 * <p><b>SDK 面补充 (GC-04 / OPD-WF7-GC-04)</b>: CC 的 SDK result {@code permission_denials}
 * 数组元素 ({@code SDKPermissionDenialSchema}, entrypoints/sdk/coreSchemas.ts:1399-1404)
 * 含 {@code tool_name / tool_use_id / tool_input} 三字段, 由 QueryEngine.ts:260-267
 * {@code wrappedCanUseTool} 在非 allow 决策时收集上报. Java store 为 web 后端该通道的
 * 唯一输出面 (AutoModeDenialsController), 故在 {@link AutoModeDenial} 上补
 * {@code toolUseId}/{@code toolInput} 两字段对齐 SDK schema (原缺, 已由探查
 * EV-WF7-GC-001 登记).
 *
 * <p><b>Java 特有说明</b>:
 * <ul>
 *   <li>CC 的 {@code feature('TRANSCRIPT_CLASSIFIER')} 门控由调用方 (ToolPermissionGate
 *       deny 分支, 仅 {@code Classifier.classifier == "auto-mode"} 时记录) 完成, 本 store 不再
 *       重复判断 (CC 真源: toolExecution.ts:1078 {@code decisionReason.classifier === 'auto-mode'};
 *       classifier 变体无 mode 字段, permissions.ts:304-306)</li>
 *   <li>CC 单线程数组替换 → Java synchronized List (记录/读取可跨线程)</li>
 * </ul>
 */
public final class AutoModeDenials {

    /** CC MAX_DENIALS = 20. */
    private static final int MAX_DENIALS = 20;

    /**
     * CC AutoModeDenial 结构体 (autoModeDenials.ts) + SDK 补全字段 (coreSchemas.ts:1399-1404).
     *
     * @param toolName  工具名 (CC original: {@code toolName}, autoModeDenials.ts:8;
     *                  SDK 面 original: {@code tool_name}, coreSchemas.ts:1400)
     * @param display   人类可读描述 (CC original: {@code display}, autoModeDenials.ts:9)
     * @param reason    分类器拒绝理由 (CC original: {@code reason}, autoModeDenials.ts:10)
     * @param timestamp 拒绝时间戳 (CC original: {@code timestamp}, autoModeDenials.ts:11)
     * @param toolUseId 本次工具调用唯一 ID (CC original: {@code tool_use_id},
     *                  coreSchemas.ts:1401; 来源 ToolUseBlock.id)
     * @param toolInput 工具调用输入 (CC original: {@code tool_input},
     *                  coreSchemas.ts:1402, z.record(z.string(), z.unknown()); 来源
     *                  ToolUseBlock.input), 可为 null
     */
    public record AutoModeDenial(String toolName, String display, String reason, long timestamp,
                                 String toolUseId, JsonNode toolInput) {}

    private static final List<AutoModeDenial> DENIALS = new ArrayList<>();

    private AutoModeDenials() {}

    /**
     * 记录一次 auto-mode 拒绝 · 对齐 CC {@code recordAutoModeDenial}.
     *
     * <p>新记录插队首 (最近拒绝排最前), 超出 {@link #MAX_DENIALS} 丢弃最旧.
     *
     * @param toolName  工具名 (CC original: toolName)
     * @param display   人类可读描述 (CC original: display — 如 bash 命令串)
     * @param reason    分类器拒绝理由 (CC original: reason)
     * @param timestamp 拒绝时间戳 (CC original: timestamp)
     * @param toolUseId 本次工具调用唯一 ID (CC original: tool_use_id, coreSchemas.ts:1401)
     * @param toolInput 工具调用输入 (CC original: tool_input, coreSchemas.ts:1402), 可为 null
     */
    public static synchronized void recordAutoModeDenial(String toolName, String display,
                                                         String reason, long timestamp,
                                                         String toolUseId, JsonNode toolInput) {
        DENIALS.add(0, new AutoModeDenial(toolName, display, reason, timestamp, toolUseId, toolInput));
        if (DENIALS.size() > MAX_DENIALS) {
            DENIALS.remove(DENIALS.size() - 1);
        }
    }

    /**
     * 读取拒绝记录 · 对齐 CC {@code getAutoModeDenials}.
     *
     * @return 不可变快照 (最近拒绝在前)
     */
    public static synchronized List<AutoModeDenial> getAutoModeDenials() {
        return List.copyOf(DENIALS);
    }

}
