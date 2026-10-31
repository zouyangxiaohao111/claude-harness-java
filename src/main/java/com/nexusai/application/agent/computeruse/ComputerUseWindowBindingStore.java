package com.nexusai.application.agent.computeruse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「当前绑定的窗口」的<b>会话级</b>存储 · computer-use Phase 2b。
 *
 * <p><b>WHY 需要它</b>：CCB 的 Windows 点击走<b>窗口定向</b>
 * （{@code SendMessage} 到已绑定 HWND，<b>不动真实鼠标、不抢焦点</b>），
 * 而本仓 Phase 2a 的点击走<b>全局注入</b>（{@code SetCursorPos} + {@code SendInput}）。
 * <br>Phase 2b 实现 {@code bind_window} 后，<b>有绑定时</b>点击改走窗口定向 ⇒
 * 必须有一处「当前绑定的是哪个窗口」的<b>会话级</b>事实。
 *
 * <h2>WHY 按会话分桶（与 CCB 的差异，如实标注）</h2>
 * <p>CCB 用<b>模块级</b>单例（{@code boundHwnd}），靠 CU 跨会话锁保证「同一时刻只有一个会话在用桌面」。
 * 本仓是多会话后端（见 memory「multisession-prefix-cache-invariants」）⇒ 沿用本仓既有约定
 * （{@link ComputerUseGeometryStore} / {@link ComputerUseDisplayStore} / {@link ComputerUseMouseState}
 * 都是按 {@code sessionId} 分桶）⇒ 绑定也按会话隔离。
 *
 * <p><b>无 sessionId 的兜底桶</b>：与 {@link ComputerUseGeometryStore#GLOBAL_KEY} 同名约定
 * （直调 {@code execute(ToolUseBlock)} 的测试路径落这里）。
 */
public final class ComputerUseWindowBindingStore {

    /** 无 sessionId 时的兜底桶 key（与 {@link ComputerUseGeometryStore#GLOBAL_KEY} 同名约定）。 */
    public static final String GLOBAL_KEY = "__global__";

    private final Map<String, ComputerUseWindowBinding> bySession = new ConcurrentHashMap<>();

    /**
     * 绑定一个窗口（覆盖该会话已有的绑定）。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     * @param binding   绑定信息（null ⇒ 无操作）
     */
    public void bind(String sessionId, ComputerUseWindowBinding binding) {
        if (binding == null) {
            return;
        }
        bySession.put(key(sessionId), binding);
    }

    /**
     * 解除绑定（回到「全屏模式」）。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     */
    public void unbind(String sessionId) {
        bySession.remove(key(sessionId));
    }

    /**
     * 读会话当前的绑定。
     *
     * <p><b>不做跨会话回落</b>（与 {@link ComputerUseGeometryStore} 同款纪律）：
     * 会话 B 没有绑定时若借用了会话 A 的绑定 ⇒ B 的输入会打进 A 的窗口。
     *
     * @param sessionId 会话 id（可空 ⇒ 全局桶）
     * @return 绑定信息；未绑定 ⇒ null
     */
    public ComputerUseWindowBinding get(String sessionId) {
        return bySession.get(key(sessionId));
    }

    /**
     * 清理某会话的绑定（轮末清理 / 换锁持有者时用）。
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
