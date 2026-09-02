package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PermissionUpdate wire-format schema · 对齐 CC {@code utils/permissions/PermissionUpdateSchema.ts:42-78}
 * {@code permissionUpdateSchema().safeParse(entry)}。
 *
 * <p><b>职责</b>：校验并解析一条外部来源（mailbox IPC / 磁盘轮询）的 permissionUpdate 原始条目，
 * 将其映射为 Java 6 种 {@link PermissionUpdate} sealed record。畸形条目（来自 buggy/旧版
 * teammate 进程）返回 {@link Optional#empty()}，供上游逐条过滤，而非未经校验透传到
 * {@code callback.onAllow()}（CC useSwarmPermissionPoller.ts:31-34 的防污染意图）。
 *
 * <p><b>strict type 判别（对齐 CC discriminatedUnion('type', [...])）</b>：CC 用
 * {@code z.discriminatedUnion('type', ...)} —— 必须携带合法 {@code type} 字面量（6 种之一），
 * 缺 {@code type} 或非法 {@code type} → safeParse 失败。本类在委托 {@link WebSocketPermissionPrompter#parsePermissionUpdate}
 * 前先做 strict type 门（该解析器的「无 type 字段形状推断」回退分支不适用于本 schema ——
 * CC 从不接受缺 type 的条目）。
 *
 * <p>字段级解析复用 {@link WebSocketPermissionPrompter#parsePermissionUpdate}（同包既有
 * 解析器，已对齐 CC camelCase 字面量 {@code destination}/{@code behavior}/{@code mode} +
 * {@code rules: [{toolName, ruleContent}]} 形状），避免双实现漂移（规则八 / 规则十一）。
 *
 * <h2>6 种 type（CC PermissionUpdateSchema.ts:43-77）</h2>
 * <ul>
 *   <li>{@code addRules} — rules + behavior + destination（:44-49）</li>
 *   <li>{@code replaceRules} — rules + behavior + destination（:50-55）</li>
 *   <li>{@code removeRules} — rules + behavior + destination（:56-61；Java record 无 behavior 字段，但 safeParse 仍校验 behavior 存在性后丢弃）</li>
 *   <li>{@code setMode} — mode + destination（:62-66；Java record 无 destination 字段，但 safeParse 仍校验 destination 存在性）</li>
 *   <li>{@code addDirectories} — directories + destination（:67-71；Java record 无 destination 字段，但 safeParse 仍校验 destination 存在性）</li>
 *   <li>{@code removeDirectories} — directories + destination（:72-76；Java record 无 destination 字段，但 safeParse 仍校验 destination 存在性）</li>
 * </ul>
 */
public final class PermissionUpdateSchema {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(PermissionUpdateSchema.class);

    /**
     * CC {@code permissionUpdateDestinationSchema} 5 合法字面量
     * （PermissionUpdateSchema.ts:27-40，z.enum 大小写敏感）。
     */
    private static final Set<String> CC_DESTINATIONS =
        Set.of("userSettings", "projectSettings", "localSettings", "session", "cliArg");

    /**
     * CC {@code permissionBehaviorSchema} 3 合法字面量
     * （PermissionRule.ts:25-27，z.enum 大小写敏感）。
     */
    private static final Set<String> CC_BEHAVIORS = Set.of("allow", "deny", "ask");

    private PermissionUpdateSchema() {
    }

    /**
     * safeParse 单条原始条目 → {@link PermissionUpdate}。
     *
     * <p>对齐 CC {@code permissionUpdateSchema().safeParse(entry)}：
     * <ul>
     *   <li>非 Map（或 null）→ empty（CC {@code z.discriminatedUnion} 对非对象必 fail）；</li>
     *   <li>缺 {@code type} / 非 String / 非 6 种合法字面量 → empty（strict 判别门）；</li>
     *   <li>否则委托 {@link WebSocketPermissionPrompter#parsePermissionUpdate} 做字段级校验与映射，
     *       字段缺失/非法 → empty。</li>
     * </ul>
     *
     * @param entry 原始条目（mailbox {@code permission_updates} 数组元素，Jackson 反序列化为 Map）
     * @return 解析成功的 {@link PermissionUpdate}；畸形 → {@link Optional#empty()}
     */
    public static Optional<PermissionUpdate> safeParse(Object entry) {
        if (!(entry instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Object typeObj = map.get("type");
        if (!(typeObj instanceof String type) || type.isBlank()) {
            return Optional.empty();
        }
        switch (type) {
            case "setMode", "addDirectories", "removeDirectories" -> {
                // CC 6 变体均强制 destination（PermissionUpdateSchema.ts:44-77）。委托解析器
                //   parseSetMode / parseDirectories 不读 destination，故在此前置强制校验
                //   （缺失/非法 → empty，对齐 CC permissionUpdateDestinationSchema 5 字面量）。
                if (!isValidDestination(map.get("destination"))) {
                    if (log.isDebugEnabled()) {
                        log.debug("PermissionUpdateSchema 拒绝畸形条目: type={} destination 缺失或非法", type);
                    }
                    return Optional.empty();
                }
            }
            case "removeRules" -> {
                // CC removeRules 强制 behavior（PermissionUpdateSchema.ts:56-61）。Java
                //   RemoveRules record 不存 behavior，但 safeParse 仍须校验存在性后丢弃
                //   （对齐 CC 严格性，不得以「Java 无法承载」跳过校验）。
                if (!isValidBehavior(map.get("behavior"))) {
                    if (log.isDebugEnabled()) {
                        log.debug("PermissionUpdateSchema 拒绝畸形条目: type=removeRules behavior 缺失或非法");
                    }
                    return Optional.empty();
                }
            }
            case "addRules", "replaceRules" -> {
                // destination + behavior 已由 parseAddOrReplace 强制校验（缺失/非法 → null → empty），无需前置。
            }
            default -> {
                return Optional.empty();
            }
        }
        JsonNode node = JSON.valueToTree(entry);
        return Optional.ofNullable(WebSocketPermissionPrompter.parsePermissionUpdate(node));
    }

    /**
     * destination 校验：存在且 ∈ CC 5 合法字面量
     * （{@code userSettings/projectSettings/localSettings/session/cliArg}，z.enum 大小写敏感）。
     */
    private static boolean isValidDestination(Object destination) {
        return destination instanceof String s && !s.isBlank() && CC_DESTINATIONS.contains(s);
    }

    /**
     * behavior 校验：存在且 ∈ CC 3 合法字面量
     * （{@code allow/deny/ask}，z.enum 大小写敏感）。校验后丢弃（RemoveRules record 不存）。
     */
    private static boolean isValidBehavior(Object behavior) {
        return behavior instanceof String s && !s.isBlank() && CC_BEHAVIORS.contains(s);
    }
}
