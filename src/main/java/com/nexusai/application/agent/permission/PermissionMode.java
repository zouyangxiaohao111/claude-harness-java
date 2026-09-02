package com.nexusai.application.agent.permission;

/**
 * 7 种 PermissionMode · 对齐 CC {@code types/permissions.ts:16-38}
 *
 * <h2>5 种 ExternalPermissionMode（用户可见）</h2>
 * <ul>
 *   <li>{@link #DEFAULT} — 默认（最严格）</li>
 *   <li>{@link #ACCEPT_EDITS} — 自动接受文件编辑</li>
 *   <li>{@link #BYPASS_PERMISSIONS} — 完全跳过权限检查</li>
 *   <li>{@link #DONT_ASK} — 拒绝所有 ask</li>
 *   <li>{@link #PLAN} — 只读模式</li>
 * </ul>
 *
 * <h2>2 种 InternalPermissionMode（内部）</h2>
 * <ul>
 *   <li>{@link #AUTO} — YoloClassifier 自动审批（ant-only）</li>
 *   <li>{@link #BUBBLE} — 子 Agent 权限冒泡到父</li>
 * </ul>
 *
 * <h2>外部用户可见性</h2>
 * {@link #AUTO} / {@link #BUBBLE} 是 ant-only 或子 Agent 内部使用，
 * 对外只暴露 5 种 ExternalPermissionMode。
 */
public enum PermissionMode {
    DEFAULT,
    ACCEPT_EDITS,
    BYPASS_PERMISSIONS,
    DONT_ASK,
    PLAN,
    AUTO,
    BUBBLE;

    /**
     * 是否对外可见（用户可在 UI 上选择）。
     *
     * @return {@code true} 当且仅当属于 5 种 ExternalPermissionMode
     */
    public boolean isExternal() {
        return this == DEFAULT
                || this == ACCEPT_EDITS
                || this == BYPASS_PERMISSIONS
                || this == DONT_ASK
                || this == PLAN;
    }

    /**
     * 字符串 → 权限模式 · 对齐 CC {@code permissionModeFromString}
     * （Open-ClaudeCode/src/utils/permissions/PermissionMode.ts:117-120）。
     *
     * <p>CC 运行时合法集合 {@code PERMISSION_MODES} = external（acceptEdits / bypassPermissions /
     * default / dontAsk / plan）+ (classifier ? auto : ∅)；{@code bubble} 不可由字符串寻址
     * （types/permissions.ts:33-38）。未知串 → {@code 'default'}。
     *
     * <p>Java 端 {@code 'auto' → AUTO} 恒映射（classifier 门控由
     * {@link InitialPermissionModeResolver} 的 {@code transcriptClassifierFeature} 分支另行判定，
     * 对齐 CC 双重判定语义：CLI 路径在 classifier 关闭时把 auto 折叠为 default，
     * settings.defaultMode 直接 cast 不折叠）。
     *
     * @param str 权限模式字符串（CC permissionModeFromString 入参）
     * @return 对应枚举；null / 未知串 → {@link #DEFAULT}
     */
    public static PermissionMode fromString(String str) {
        if (str == null) {
            return DEFAULT;
        }
        return switch (str) {
            case "default" -> DEFAULT;
            case "acceptEdits" -> ACCEPT_EDITS;
            case "bypassPermissions" -> BYPASS_PERMISSIONS;
            case "dontAsk" -> DONT_ASK;
            case "plan" -> PLAN;
            case "auto" -> AUTO;
            default -> DEFAULT;
        };
    }

    /**
     * UI 可设置值域校验（写侧 fail-loud）· 镜像前端 types.ts:297 6 值联合
     * {@code 'default'|'plan'|'acceptEdits'|'bypassPermissions'|'dontAsk'|'auto'}。
     *
     * <p><b>WHY（V44 · 写侧双防）</b>：列必须存 CC 串（acceptEdits）而非枚举 name
     * （ACCEPT_EDITS 不被 {@link #fromString} 识别 → 静默折叠 DEFAULT，设置"不生效"）。
     * {@code fromString} 是读侧（未知 → DEFAULT 最严格回落，fail-safe）；本方法提供
     * 写侧校验——UI/HTTP 传入值不在 6 值集合即拒绝（ValidationException），而非静默
     * 折叠成最严格 DEFAULT。
     *
     * <p><b>BUBBLE 不可由 UI 设置</b>：'bubble' 不在集合——fromString 本就不识别
     * （未知 → DEFAULT），isSettable 从写侧钉死 BUBBLE 只能由子 Agent 冒泡内部产生，
     * 不可经 settings/session API 写入。
     *
     * @param str 待校验的 CC 权限模式字符串（可 null / 空白）
     * @return {@code true} 当且仅当 trim 后是 6 值集合成员
     */
    public static boolean isSettable(String str) {
        if (str == null) {
            return false;
        }
        String trimmed = str.trim();
        return SETTABLE_STRINGS.contains(trimmed);
    }

    /** 前端 types.ts:297 6 值联合（CC 串，非枚举 name）。 */
    private static final java.util.Set<String> SETTABLE_STRINGS =
        java.util.Set.of("default", "plan", "acceptEdits", "bypassPermissions", "dontAsk", "auto");
}
