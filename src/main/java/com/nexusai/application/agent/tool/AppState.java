package com.nexusai.application.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session 全局应用状态 · 对齐 CC {@code Open-ClaudeCode/src/state/AppStateStore.ts:89-292} {@code AppState}.
 *
 * <p><b>CC 真源</b> ({@code state/AppStateStore.ts:89-292}):
 * <pre>
 * export type AppState = DeepImmutable&lt;{
 *   settings: SettingsJson;
 *   verbose: boolean;
 *   mainLoopModel: ModelSetting;
 *   toolPermissionContext: ToolPermissionContext;
 *   // ... 60+ 顶层字段 + 30+ 嵌套字段 (共 ~90 字段)
 * }&gt; &amp; { tasks, agentNameRegistry, mcp, plugins, ... }
 * </pre>
 *
 * <p><b>Java L3 镜像</b> (本阶段简化): 用 {@link Map}{@code <String, Object>} 作为 L3 镜像,
 * 任何新字段可动态添加, 避免照搬 CC 90+ 字段造成的过早设计.
 *
 * <p><b>WHY 简化</b> (CLAUDE.md 规则 2 简单至上):
 * <ol>
 *   <li>Java record 照搬 60+ 字段会引入大量与本阶段无关的契约, 违背最小代码原则.</li>
 *   <li>{@code Map<String, Object>} 是 L3 idiom, 与 b15 Stage 1+2+3.1 既有简化模式一致
 *       (如 {@code toolDecisions}: {@code Map<String, ToolDecisionInfo>}).</li>
 *   <li>Stage 3.3 (UI 字段 11 项) 与 Stage 3.4 (session 维度 13 字段) 后续阶段按需添加
 *       {@code with()} 工厂方法, 不阻塞本阶段交付.</li>
 * </ol>
 *
 * <p><b>不变量</b>:
 * <ul>
 *   <li>{@link #fields()} 永远返回不可变 Map (防御性 copy, 外部 mutate 不影响 ctx).</li>
 *   <li>{@link #empty()} 提供空白单例, 避免重复分配.</li>
 *   <li>{@link #with(String, Object)} 函数式更新返回新 AppState, 与 CC React useState setter 语义对齐.</li>
 * </ul>
 *
 * <p><b>UI Integration</b> (用户 2026-07-28 决策): 本类作为 Java 后端状态容器,
 * UI 渲染由 web 前端 React 侧实现. Stage 3.3 阶段
 * 将按需追加具体字段契约 (Notification / JSX / OS 通知 / Prompt 等).
 *
 * @see ToolUseContext#getAppState()
 * @see ToolUseContext#setAppState()
 * @see LlmAgentLoop#setAppState
 */
public record AppState(Map<String, Object> fields) {

    /**
     * compact constructor：防御性 copy.
     *
     * <p>WHY: 外部 mutate Map 会污染上层规则匹配 (与 CC {@code DeepImmutable} 等价).
     */
    public AppState {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** 空白单例 (避免重复分配). */
    private static final AppState EMPTY = new AppState(Map.of());

    /** 空白 AppState 单例 · fields = {@code Map.of()}. */
    public static AppState empty() {
        return EMPTY;
    }

    /**
     * 按 key 取字段值 (null 表示字段不存在).
     *
     * @param key 字段名 (e.g. {@code "streamMode"} / {@code "sdkStatus"})
     * @return 字段值, 不存在返回 {@code null}
     */
    public Object get(String key) {
        return fields.get(key);
    }

    /**
     * 函数式更新 · 返回新 AppState (镜像 CC React useState setter).
     *
     * <p>既有 {@code AppState} 不变, 调用方需持有新引用.
     *
     * @param key   字段名
     * @param value 新值 (e.g. {@code SpinnerMode.REQUESTING} / {@code SDKStatus.COMPACTING})
     * @return 新 {@link AppState}, 含 updated key
     */
    public AppState with(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(fields);
        next.put(key, value);
        return new AppState(next);
    }

    /**
     * 函数式更新 · 批量.
     *
     * @param updates 待更新 key-value 对 (null 值会被跳过)
     * @return 新 {@link AppState}, 含所有更新
     */
    public AppState with(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return this;
        }
        Map<String, Object> next = new LinkedHashMap<>(fields);
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                next.put(e.getKey(), e.getValue());
            }
        }
        return new AppState(next);
    }

    // ════════════════════════════════════════════════════════════════════════
    // G33① 逐步类型化 · 已知稳定 key 的显式类型化访问器
    // （保留 get(key)/with(key,value) 向后兼容读侧，本批仅覆盖 ConfigTool 相关稳定 key）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 类型化读取 verbose · CC original: {@code verbose: boolean}（AppStateStore.ts:91）。
     *
     * <p>G33① 逐步类型化：Map 中未写入该 key → 返回 {@code null}（等价 CC AppState
     * 未初始化该字段的读侧缺省）。写侧由 {@link #withVerbose(boolean)} 提供。
     *
     * @return verbose 布尔值；未写入返回 {@code null}
     */
    public Boolean verbose() {
        return (Boolean) fields.get("verbose");
    }

    /**
     * 类型化写入 verbose · CC original: {@code verbose: boolean}（AppStateStore.ts:91）。
     *
     * @param verbose 新值
     * @return 新 {@link AppState}，含 verbose 更新
     */
    public AppState withVerbose(boolean verbose) {
        return with("verbose", verbose);
    }

    /**
     * 类型化读取 mainLoopModel · CC original: {@code mainLoopModel: ModelSetting}
     * （AppStateStore.ts:92）。
     *
     * <p>Java 端该 key 由 ConfigTool 同步写入为 String（SupportedSettings.java:204
     * {@code "model" → syncKey "mainLoopModel"}）；读侧统一 {@code String.valueOf} 兜底，
     * 与 {@code LlmAgentLoop.getModelForCall} 优先级链 / ReadFileTool:1414 /
     * SkillToolImpl:1986 的读法一致。
     *
     * @return mainLoopModel 字符串；未写入返回 {@code null}
     */
    public String mainLoopModel() {
        Object v = fields.get("mainLoopModel");
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 类型化写入 mainLoopModel · CC original: {@code mainLoopModel: ModelSetting}
     * （AppStateStore.ts:92）。
     *
     * @param model 模型名
     * @return 新 {@link AppState}，含 mainLoopModel 更新
     */
    public AppState withMainLoopModel(String model) {
        return with("mainLoopModel", model);
    }

    /**
     * 类型化读取 thinkingEnabled · CC original: {@code thinkingEnabled: boolean | undefined}
     * （AppStateStore.ts:229）。
     *
     * <p>写侧由 {@link #withThinkingEnabled(boolean)} 提供；Map 中未写入 → 返回 {@code null}
     * （对齐 CC {@code boolean | undefined} 的 undefined 缺省语义）。
     *
     * @return thinkingEnabled 布尔值；未写入返回 {@code null}
     */
    public Boolean thinkingEnabled() {
        return (Boolean) fields.get("thinkingEnabled");
    }

    /**
     * 类型化写入 thinkingEnabled · CC original: {@code thinkingEnabled: boolean | undefined}
     * （AppStateStore.ts:229）。
     *
     * @param enabled 新值
     * @return 新 {@link AppState}，含 thinkingEnabled 更新
     */
    public AppState withThinkingEnabled(boolean enabled) {
        return with("thinkingEnabled", enabled);
    }
}