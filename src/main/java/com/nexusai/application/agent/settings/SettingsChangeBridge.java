package com.nexusai.application.agent.settings;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * SettingsChangeBridge · 对齐 CC hooks/useSettingsChange.ts:7-25。
 *
 * <p>L1 语义: 设置变更通知的中继。当某个来源 (SettingSource) 触发变更时, 重新读取当前设置快照,
 * 然后回调 {@code onChange(source, settings)}。CC 强调: 缓存已由 notifier(fanOut) 重置,
 * <b>此处不再重置缓存</b> — 否则 N 个订阅者会各自清缓存 + 重读磁盘造成 N 次抖动。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: static &lt;S,T&gt; void relay(S source, Supplier&lt;T&gt; settingsSupplier, BiConsumer&lt;S,T&gt; onChange)</li>
 *   <li><b>A2 Golden Trace</b>: 变更事件 → 读快照一次 → onChange(source, snapshot) 一次</li>
 *   <li><b>A3 纯编排</b>: 不清缓存, 仅读一次 (对齐 CC 防抖注释), 无重复读</li>
 *   <li><b>A4 边界</b>: settingsSupplier 只调用一次 (计数验证); source 原样透传</li>
 *   <li><b>A5 业务场景</b>: settings.json 改动 → 单次读取新快照 → 通知 useSettingsErrors 重算错误</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS useCallback+useEffect+subscribe → Java 静态中继函数;
 * getSettings_DEPRECATED() → 注入式 Supplier (测试可替身 + 验证只读一次)。
 */
public final class SettingsChangeBridge {

    private SettingsChangeBridge() {}

    /**
     * CC useSettingsChange.ts:11-18 handleChange —
     * <pre>
     * const newSettings = getSettings_DEPRECATED()  // 不重置缓存
     * onChange(source, newSettings)
     * </pre>
     *
     * @param source           变更来源
     * @param settingsSupplier 当前设置快照读取器 (恰好调用一次)
     * @param onChange         变更回调
     */
    public static <S, T> void relay(S source, Supplier<T> settingsSupplier, BiConsumer<S, T> onChange) {
        T newSettings = settingsSupplier.get();
        onChange.accept(source, newSettings);
    }
}
