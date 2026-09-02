package com.nexusai.application.agent.settings;

/**
 * SettingsErrorsNotifier · 对齐 CC hooks/notifs/useSettingsErrors.tsx:1-52。
 *
 * <p>L1 语义: 根据设置校验错误数量, 决定是否弹出/移除通知。remote 模式下静默;
 * 错误数 &gt; 0 时弹出高优先级 warning 通知 (60s 超时), 文案 "Found N settings issue(s) · /doctor for details";
 * 错误数 = 0 时移除该通知。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: KEY="settings-errors" / COLOR="warning" / PRIORITY="high" / TIMEOUT_MS=60000;
 *       buildMessage(int) / shouldNotify(boolean,int)</li>
 *   <li><b>A2 Golden Trace</b>: !remote + errors&gt;0 → shouldNotify=true + message; errors=0 → shouldNotify=false (移除)</li>
 *   <li><b>A3 纯函数</b>: 无副作用, 输出仅依赖 (isRemoteMode, errorCount)</li>
 *   <li><b>A4 边界</b>: errorCount=1 → 单数 "issue"; errorCount&gt;1 → 复数 "issues"; remote 模式 → 恒 false</li>
 *   <li><b>A5 业务场景</b>: 2 个 settings 校验错误 → "Found 2 settings issues · /doctor for details"</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS React memo hook (useState/useEffect/useNotifications) →
 * Java 纯静态消息构造 + 决策; 通知副作用留给 Java 端 NotificationQueue 消费。
 */
public final class SettingsErrorsNotifier {

    /** CC useSettingsErrors.tsx:8 notification key */
    public static final String KEY = "settings-errors";
    /** CC useSettingsErrors.tsx:37 color */
    public static final String COLOR = "warning";
    /** CC useSettingsErrors.tsx:38 priority */
    public static final String PRIORITY = "high";
    /** CC useSettingsErrors.tsx:39 timeoutMs */
    public static final long TIMEOUT_MS = 60000L;

    private SettingsErrorsNotifier() {}

    /**
     * CC useSettingsErrors.tsx:33 message —
     * <pre>
     * `Found ${n} settings ${n === 1 ? 'issue' : 'issues'} · /doctor for details`
     * </pre>
     *
     * @param errorCount 校验错误数量
     * @return 通知文案
     */
    public static String buildMessage(int errorCount) {
        String noun = errorCount == 1 ? "issue" : "issues";
        return "Found " + errorCount + " settings " + noun + " · /doctor for details";
    }

    /**
     * CC useSettingsErrors.tsx:30-42 — remote 模式静默; 仅当 errorCount&gt;0 时通知。
     *
     * @param isRemoteMode 是否远程模式
     * @param errorCount   校验错误数量
     * @return 是否应弹出通知 (false = 移除/不显示)
     */
    public static boolean shouldNotify(boolean isRemoteMode, int errorCount) {
        if (isRemoteMode) {
            return false;
        }
        return errorCount > 0;
    }
}
