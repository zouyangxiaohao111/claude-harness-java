package com.nexusai.application.agent.permission;

/**
 * 8 个规则来源 · 对齐 CC {@code types/permissions.ts:54-62}
 *
 * <h2>5 个磁盘 source</h2>
 * <ul>
 *   <li>{@link #USER_SETTINGS} — {@code ~/.nexusai/settings.json}</li>
 *   <li>{@link #PROJECT_SETTINGS} — {@code <project>/.nexusai/settings.json}</li>
 *   <li>{@link #LOCAL_SETTINGS} — {@code .nexusai/settings.local.json}（gitignored）</li>
 *   <li>{@link #FLAG_SETTINGS} — {@code --settings} CLI flag</li>
 *   <li>{@link #POLICY_SETTINGS} — 企业管控（managed）</li>
 * </ul>
 *
 * <h2>3 个内存 source</h2>
 * <ul>
 *   <li>{@link #CLI_ARG} — {@code --allowed-tools} / {@code --disallowed-tools}</li>
 *   <li>{@link #COMMAND} — slash command 配置</li>
 *   <li>{@link #SESSION} — 运行时临时授权</li>
 * </ul>
 *
 * <h2>优先级（从低到高）</h2>
 * <pre>userSettings &lt; projectSettings &lt; localSettings &lt; flagSettings &lt; policySettings
 *   &lt; cliArg &lt; command &lt; session</pre>
 *
 * <h2>只读性</h2>
 * <ul>
 *   <li>3 个 read-only：{@link #POLICY_SETTINGS} / {@link #FLAG_SETTINGS} / {@link #COMMAND}
 *       （{@link #isReadOnly()}，PermissionUpdateApplier removeRules 守卫）</li>
 * </ul>
 *
 * <p>【DEL-WF1-02】{@code isEditable} / {@code isRuntime} 已删——全仓无调用方（死代码，
 * EV-WF2-RP-025/026）；CC 无对应概念。
 *
 * <p>【DEL-WF2-01-01 / OPD-WF2-01-01】{@code configKey} 已删——全仓 0 调用方死代码，
 * CC 无 key 概念（source→文件路径表达由各 SettingsLoader.resolvePath() 承载）；
 * 原 OD-WF2-07 保留依据（CheckLayer1a_DenyRule.java:63,78 虚假调用方）v4 WF-2 域
 * 返工后推翻，用户 2026-08-18 拍板删除。{@code isReadOnly} 保留：write-persist 守卫
 * PermissionUpdateApplier.java:281（removeRules 拒绝删除只读源规则）。
 */
public enum PermissionRuleSource {
    USER_SETTINGS,
    PROJECT_SETTINGS,
    LOCAL_SETTINGS,
    FLAG_SETTINGS,
    POLICY_SETTINGS,
    CLI_ARG,
    COMMAND,
    SESSION;

    /**
     * 是否只读（{@link #POLICY_SETTINGS} / {@link #FLAG_SETTINGS} / {@link #COMMAND}
     * 不可删改）。
     *
     * <p>【DEL-WF1-02】保留：CC 无等价，但调用方合理——PermissionUpdateApplier.java:281
     * removeRules 守卫拒绝删除只读源规则（OD-WF2-07 决策）。
     */
    public boolean isReadOnly() {
        return this == POLICY_SETTINGS || this == FLAG_SETTINGS || this == COMMAND;
    }
}
