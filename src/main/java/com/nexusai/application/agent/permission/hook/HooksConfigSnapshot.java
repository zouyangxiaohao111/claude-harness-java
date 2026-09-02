package com.nexusai.application.agent.permission.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hooks Config Snapshot · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/hooksConfigSnapshot.ts} (133 行).
 *
 * <p>WHY: CC 用模块级变量 {@code initialHooksConfig} 缓存启动时的 hook 配置快照,
 * 供后续 {@code /hooks} 命令读取 (避免每次重读 settings). Java 端用 {@code @Component}
 * bean 实例字段等价表达. 本轮 H3 补全 5 个 CC 对齐方法.
 *
 * <p><b>CC 真源方法 (hooksConfigSnapshot.ts:62-133)</b>:
 * <ul>
 *   <li>{@link #shouldAllowManagedHooksOnly()} (:62-76)</li>
 *   <li>{@link #shouldDisableAllHooksIncludingManaged()} (:83-88)</li>
 *   <li>{@link #captureHooksConfigSnapshot()} (:95-97)</li>
 *   <li>{@link #updateHooksConfigSnapshot()} (:104-112) — 含 resetSettingsCache</li>
 *   <li>{@link #getHooksConfigFromSnapshot()} (:119-124)</li>
 *   <li>{@link #resetHooksConfigSnapshot()} (:130-133) — 含 resetSdkInitState</li>
 * </ul>
 *
 * <p><b>Java 端适配</b>:
 * <ul>
 *   <li>CC 模块级 {@code initialHooksConfig} → Java bean 实例字段 {@link #initialHooksConfig}</li>
 *   <li>CC {@code resetSettingsCache} (settingsCache.ts) → Java 端无直接对应;
 *       {@link #updateHooksConfigSnapshot()} 中以注释标 CC 原行为, 不调任何 cache 清理
 *       (Java settings loader 无状态, 每次重读盘)</li>
 *   <li>CC {@code resetSdkInitState} (bootstrap/state.ts) → Java 端无 SDK init state,
 *       {@link #resetHooksConfigSnapshot()} 中以注释标 CC 原行为, 省略实际调用</li>
 * </ul>
 *
 * <p><b>local-only 约束</b>: 快照仅本地缓存, 不外发.
 *
 */
@Component
public class HooksConfigSnapshot {

    private static final Logger log = LoggerFactory.getLogger(HooksConfigSnapshot.class);

    private final HooksSettings hooksSettings;

    /** Jackson (policy hooks JSON → HookMatcher 反序列化). */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** CC 模块级 {@code initialHooksConfig} 的 Java 等价: bean 实例字段. */
    private volatile Map<HookEventType, List<HookMatcher>> initialHooksConfig = null;

    /** 测试 / 手动构造: 使用独立 ObjectMapper. */
    public HooksConfigSnapshot(HooksSettings hooksSettings) {
        this(hooksSettings, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * Spring 注入构造器（RES-04 修复）· 显式 {@code @Autowired} 让容器在多构造器下按
     * (HooksSettings, ObjectMapper) 注入（恢复容器 ObjectMapper 注入意图；旧实现单参构造器
     * 内部 {@code new ObjectMapper()} → 容器 ObjectMapper 被忽略）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public HooksConfigSnapshot(HooksSettings hooksSettings,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        if (hooksSettings == null) {
            throw new IllegalArgumentException("hooksSettings is null");
        }
        this.hooksSettings = hooksSettings;
        this.objectMapper = objectMapper != null ? objectMapper : new com.fasterxml.jackson.databind.ObjectMapper();
    }
    /**
     * 是否仅允许 managed hooks · 对齐 CC hooksConfigSnapshot.ts:62-76
     * {@code shouldAllowManagedHooksOnly}.
     *
     * <p>[IMPL-01 D1-1/OD-10] 完整双条件委托 {@link HooksSettings#shouldAllowManagedHooksOnly()}:
     * policy.allowManagedHooksOnly==true OR (merged.disableAllHooks==true 且
     * policy.disableAllHooks!=true). 旧实现后半分支恒 false (注释保留未实现), 本次由
     * {@link HooksSettings#shouldDisableAllMerged()} 补齐.
     *
     * @return true = 仅 managed hook 生效
     */
    public boolean shouldAllowManagedHooksOnly() {
        return hooksSettings.shouldAllowManagedHooksOnly();
    }

    /**
     * 是否禁用所有 hook (含 managed) · 对齐 CC hooksConfigSnapshot.ts:83-88
     * {@code shouldDisableAllHooksIncludingManaged}.
     *
     * <p>WHY: 仅当 policySettings.disableAllHooks==true 时全部禁用 (含 managed).
     * 非 managed 设 disableAllHooks 不会禁 managed.
     *
     * @return true = 全部 hook 禁用 (含 managed)
     */
    public boolean shouldDisableAllHooksIncludingManaged() {
        return hooksSettings.shouldDisableAll();
    }

    /**
     * 捕获当前 hook 配置快照 · 对齐 CC hooksConfigSnapshot.ts:95-97
     * {@code captureHooksConfigSnapshot}.
     *
     * <p>WHY: CC 启动时调一次, 缓存 getHooksFromAllowedSources() 结果.
     */
    public synchronized void captureHooksConfigSnapshot() {
        initialHooksConfig = getHooksFromAllowedSources();
        if (log.isDebugEnabled()) {
            log.debug("captureHooksConfigSnapshot: 捕获快照, events={}",
                initialHooksConfig != null ? initialHooksConfig.size() : 0);
        }
    }

    /**
     * 更新 hook 配置快照 · 对齐 CC hooksConfigSnapshot.ts:104-112
     * {@code updateHooksConfigSnapshot}.
     *
     * <p>WHY: CC 先 resetSettingsCache (清 session 缓存, 防止外部编辑 settings.json 后读到旧值),
     * 再重新 capture. Java 端 settings loader 无状态 (每次重读盘), 无需 reset cache,
     * 直接重新 capture.
     */
    public synchronized void updateHooksConfigSnapshot() {
        // CC: resetSettingsCache() — Java 端无对应 (loader 无状态), 省略
        initialHooksConfig = getHooksFromAllowedSources();
        if (log.isDebugEnabled()) {
            log.debug("updateHooksConfigSnapshot: 更新快照, events={}",
                initialHooksConfig != null ? initialHooksConfig.size() : 0);
        }
    }

    /**
     * 从快照获取 hook 配置 · 对齐 CC hooksConfigSnapshot.ts:119-124
     * {@code getHooksConfigFromSnapshot}.
     *
     * <p>WHY: CC 若 initialHooksConfig==null 则先 capture, 再返回.
     *
     * @return event → HookMatcher 列表 的 Map (可能为空, 永不 null)
     */
    public synchronized Map<HookEventType, List<HookMatcher>> getHooksConfigFromSnapshot() {
        if (initialHooksConfig == null) {
            captureHooksConfigSnapshot();
        }
        return initialHooksConfig != null ? initialHooksConfig : Map.of();
    }

    /**
     * 重置 hook 配置快照 · 对齐 CC hooksConfigSnapshot.ts:130-133
     * {@code resetHooksConfigSnapshot}.
     *
     * <p>WHY: CC 置 initialHooksConfig=null + resetSdkInitState (防测试污染).
     * Java 端无 SDK init state, 省略 resetSdkInitState.
     */
    public synchronized void resetHooksConfigSnapshot() {
        initialHooksConfig = null;
        // CC: resetSdkInitState() — Java 端无对应, 省略
        if (log.isDebugEnabled()) {
            log.debug("resetHooksConfigSnapshot: 快照已重置");
        }
    }

    /**
     * 获取允许来源的 hook 配置 · 全量对齐 CC hooksConfigSnapshot.ts:18-53
     * {@code getHooksFromAllowedSources}（5 分支）.
     *
     * <p>[IMPL-01 D1-3/OD-10] CC 分支（行号为探查时点，符号为准）:
     * <ol>
     *   <li>:22-24 policySettings.disableAllHooks==true → 空（全部禁用含 managed）</li>
     *   <li>:27-29 policySettings.allowManagedHooksOnly==true → policySettings.hooks（非空）</li>
     *   <li>:39-41 strictPluginOnlyCustomization('hooks') → policySettings.hooks</li>
     *   <li>:47-49 merged(user).disableAllHooks==true → policySettings.hooks（非 managed 禁不掉 managed）</li>
     *   <li>:52 默认 → mergedSettings.hooks（合并 user/project/local，向后兼容）</li>
     * </ol>
     * 旧实现仅 3/5 分支且 allowManagedHooksOnly 分支恒空 Map（EV-CFG-016）；
     * 本次补 policy hooks 读取（{@link #policyHooksFromSettings()}）与分支 3/4 条件
     * （PluginOnlyPolicy / {@link HooksSettings#shouldDisableAllMerged()}）。
     *
     * @return event → HookMatcher 列表
     */
    private Map<HookEventType, List<HookMatcher>> getHooksFromAllowedSources() {
        // 分支 1 (:22-24): policy disableAllHooks==true → 空
        if (shouldDisableAllHooksIncludingManaged()) {
            if (log.isDebugEnabled()) {
                log.debug("getHooksFromAllowedSources: 分支1 policySettings.disableAllHooks=true, 返回空");
            }
            return Map.of();
        }

        // 分支 2/3/4 (:27-29/:39-41/:47-49): 仅 policy hooks
        //   - allowManagedHooksOnly=true（含 merged disableAllHooks 等效 managed-only）
        //   - strictPluginOnlyCustomization 锁定 hooks 面（plugin-only policy）
        boolean policyOnly = shouldAllowManagedHooksOnly()
            || com.nexusai.infra.util.PluginOnlyPolicy.isRestrictedToPluginOnly(
                com.nexusai.infra.util.PluginOnlyPolicy.SURFACE_HOOKS,
                hooksSettings::policySettingsMap);
        if (policyOnly) {
            Map<HookEventType, List<HookMatcher>> policyHooks = policyHooksFromSettings();
            if (log.isDebugEnabled()) {
                log.debug("getHooksFromAllowedSources: 分支2/3/4 仅 policy hooks, events={}",
                    policyHooks.size());
            }
            return policyHooks;
        }

        // 分支 5 (:52): 合并所有来源 hooks（向后兼容）
        List<IndividualHookConfig> all = hooksSettings.getAllHooks();
        Map<HookEventType, List<HookMatcher>> grouped = groupByEventAndMatcher(all);
        if (log.isDebugEnabled()) {
            log.debug("getHooksFromAllowedSources: 分支5 合并 {} 个 hook → {} 个 event", all.size(), grouped.size());
        }
        return grouped;
    }

    /**
     * 读取 policySettings.hooks 并转为 {@code event → List<HookMatcher>} ·
     * 对齐 CC {@code policySettings.hooks ?? {}}（hooksConfigSnapshot.ts:28/:40/:48）.
     *
     * <p>hooks JSON 形状与 settings.json 一致：{@code {"PreToolUse": [{matcher, hooks: [...]}]}}
     * （事件名 PascalCase），未知事件名跳过（与 MultiSourceHooksConfigLoader.expand 同语义）。
     *
     * @return event → HookMatcher 列表；无 policy hooks / 解析失败 → 空 Map
     */
    private Map<HookEventType, List<HookMatcher>> policyHooksFromSettings() {
        Object hooksVal = hooksSettings.policySettingsValue("hooks");
        if (hooksVal == null) {
            return Map.of();
        }
        try {
            Map<String, List<HookMatcher>> parsed = objectMapper.convertValue(hooksVal,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<HookMatcher>>>() {});
            if (parsed == null || parsed.isEmpty()) {
                return Map.of();
            }
            Map<HookEventType, List<HookMatcher>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<HookMatcher>> e : parsed.entrySet()) {
                HookEventType eventType = toEventType(e.getKey());
                if (eventType == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("policyHooksFromSettings: 跳过未知 hook 事件名: {}", e.getKey());
                    }
                    continue;
                }
                List<HookMatcher> matchers = e.getValue();
                if (matchers == null || matchers.isEmpty()) {
                    continue;
                }
                result.put(eventType, List.copyOf(matchers));
            }
            return result;
        } catch (Exception e) {
            // 解析失败 → 空 + warn（对齐 CC lenient 加载，不中断快照捕获）
            log.warn("policyHooksFromSettings: 解析 policy hooks 失败, 视为空: {}", e.toString());
            return Map.of();
        }
    }

    /** PascalCase 事件名 → HookEventType (UPPER_SNAKE); 未知 → null（本包内复制，原方法 private）. */
    private static HookEventType toEventType(String pascalName) {
        if (pascalName == null || pascalName.isEmpty()) {
            return null;
        }
        try {
            return HookEventType.valueOf(normalizeEventName(pascalName));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 事件名归一化：PascalCase "PreToolUse" → "PRE_TOOL_USE"; UPPER_SNAKE 原样. */
    private static String normalizeEventName(String eventName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eventName.length(); i++) {
            char c = eventName.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && eventName.charAt(i - 1) != '_') {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 把 IndividualHookConfig 列表按 event → matcher 分组为
     * {@code Map<HookEventType, List<HookMatcher>>} (CC HooksSettings 结构).
     */
    private Map<HookEventType, List<HookMatcher>> groupByEventAndMatcher(List<IndividualHookConfig> hooks) {
        // 先按 event → (matcher → List<HookCommand>) 分组
        Map<HookEventType, Map<String, List<HookCommand>>> byEvent = new LinkedHashMap<>();
        for (IndividualHookConfig h : hooks) {
            byEvent.computeIfAbsent(h.event(), k -> new LinkedHashMap<>())
                .computeIfAbsent(h.matcher(), k -> new java.util.ArrayList<>())
                .add(h.config());
        }
        // 转为 event → List<HookMatcher>
        Map<HookEventType, List<HookMatcher>> result = new LinkedHashMap<>();
        for (Map.Entry<HookEventType, Map<String, List<HookCommand>>> e : byEvent.entrySet()) {
            List<HookMatcher> matchers = new java.util.ArrayList<>();
            for (Map.Entry<String, List<HookCommand>> m : e.getValue().entrySet()) {
                matchers.add(new HookMatcher(m.getKey(), List.copyOf(m.getValue())));
            }
            result.put(e.getKey(), List.copyOf(matchers));
        }
        return result;
    }
}