package com.nexusai.application.agent.computeruse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「最近一次截图」的几何快照存储 · 按 sessionId 隔离。
 *
 * <p><b>WHY 需要它</b>：CCB {@code toolCalls.ts:270-284} 的注释明确：
 * “Uses the display geometry stashed AT CAPTURE TIME, not fresh. / Origin from the same snapshot
 * keeps clicks coherent with the captured display.” —— 点击换算必须用<b>截图时刻</b>冻结的几何，
 * 不能在点击时刻重新读实时显示几何（多屏切换 / 用户改显示设置后会点错屏）。
 *
 * <p><b>会话隔离</b>：本仓是多会话（见 memory「multisession-prefix-cache-invariants」），
 * 两个会话各自看到各自的截图 ⇒ 几何快照必须按 sessionId 分桶，不能用全局单值。
 * 无 sessionId（直调 {@code execute(ToolUseBlock)} 的测试路径）落到 {@link #GLOBAL_KEY} 桶。
 */
public final class ComputerUseGeometryStore {

    /** 无 sessionId 时的兜底桶 key。 */
    public static final String GLOBAL_KEY = "__global__";

    private final Map<String, ComputerUseScreenshotGeometry> bySession = new ConcurrentHashMap<>();

    /**
     * 记录某会话最近一次截图的几何快照。
     *
     * @param sessionId 会话 id（可为 null ⇒ 落全局桶）
     * @param geometry  几何快照
     */
    public void put(String sessionId, ComputerUseScreenshotGeometry geometry) {
        if (geometry == null) {
            return;
        }
        bySession.put(key(sessionId), geometry);
    }

    /**
     * 读取某会话最近一次截图的几何快照。
     *
     * <p><b>不做跨会话回落</b>：会话 B 从未截图时若借用了会话 A 的几何快照，
     * 会把 B 的点击算到 A 拍的那块屏上（多屏场景下直接点错屏）。
     * 没有快照就是没有 ⇒ 点击工具走冷启动兜底分支（CCB 语义）。
     *
     * @param sessionId 会话 id（可为 null ⇒ 落全局桶）
     * @return 几何快照；无则 null
     */
    public ComputerUseScreenshotGeometry get(String sessionId) {
        return bySession.get(key(sessionId));
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? GLOBAL_KEY : sessionId;
    }
}
