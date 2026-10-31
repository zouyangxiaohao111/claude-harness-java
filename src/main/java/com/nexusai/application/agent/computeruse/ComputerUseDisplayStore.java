package com.nexusai.application.agent.computeruse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级「当前选中的显示器」存储 · computer-use Phase 2a 的 {@code switch_display} 用。
 *
 * <h2>WHY 需要它</h2>
 * <p>CCB 的 {@code switch_display}（{@code packages/@ant/computer-use-mcp/src/toolCalls.ts:3477-3537}）
 * 通过宿主回调 {@code overrides.onDisplayPinned(displayId)} 把选择<b>写回宿主</b>，
 * 后续的 {@code screenshot} 会用它决定抓哪块屏。
 * <br>⇒ 本仓没有那种宿主回调 ⇒ 本仓把选择存进<b>会话级存储</b>（与
 * {@link ComputerUseGeometryStore} 同款约定：按 {@code sessionId} 分桶，无 sessionId 落全局桶）。
 *
 * <h2>与 CCB 的差异（如实标注）</h2>
 * <p>CCB 的选中状态在<b>宿主</b>（CCB 桌面端的 React 状态），而本仓的选中状态在
 * <b>后端会话存储</b> ⇒ 本仓的 {@code switch_display} 只影响<b>本后端进程</b>，
 * 不会同步到前端。
 *
 * <p>另外：CCB 的 {@code switch_display} 需要宿主提供 {@code onDisplayPinned} 回调，
 * 没有就报 “Display switching is not available in this session.”（{@code toolCalls.ts:3485-3490}）。
 * <br>⇒ 本仓<b>有</b>这个存储 ⇒ 该错误分支在本仓<b>不适用</b>（本仓总是能切换）。
 */
public final class ComputerUseDisplayStore {

    /** 无 sessionId 时的兜底桶 key（与 {@link ComputerUseGeometryStore#GLOBAL_KEY} 同名约定）。 */
    public static final String GLOBAL_KEY = "__global__";

    private final Map<String, ComputerUseDisplay> bySession = new ConcurrentHashMap<>();

    /**
     * 记录会话当前选中的显示器。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     * @param display   选中的显示器
     */
    public void put(String sessionId, ComputerUseDisplay display) {
        if (display == null) {
            return;
        }
        bySession.put(key(sessionId), display);
    }

    /**
     * 读会话当前选中的显示器。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     * @return 选中的显示器；未选过 ⇒ null（= 自动选择）
     */
    public ComputerUseDisplay get(String sessionId) {
        return bySession.get(key(sessionId));
    }

    /**
     * 清除会话的显示器选择（回到「自动选择」）。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     */
    public void clear(String sessionId) {
        bySession.remove(key(sessionId));
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? GLOBAL_KEY : sessionId;
    }
}
