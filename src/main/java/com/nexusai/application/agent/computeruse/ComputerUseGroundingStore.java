package com.nexusai.application.agent.computeruse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * grounding 定位帧存储 · 按 sessionId 隔离。
 *
 * <p><b>WHY 需要它</b>：护栏 ②（点击前落点校验）要求「与<b>首次截图</b>的同一区域」比对 ⇒
 * 截图工具（grounding 路径）必须把那一帧留下来给点击工具用。
 *
 * <p><b>不落盘</b>：只在内存里活到下一次定位 / 会话结束 ⇒ 桌面画面不进磁盘。
 *
 * <p><b>会话隔离</b>：与 {@link ComputerUseGeometryStore} 同款 —— 不做跨会话回落。
 */
public final class ComputerUseGroundingStore {

    /** 无 sessionId 时的兜底桶 key。 */
    public static final String GLOBAL_KEY = "__global__";

    private final Map<String, ComputerUseGroundingFrame> bySession = new ConcurrentHashMap<>();

    /**
     * 记录某会话最近一次 grounding 定位帧。
     *
     * @param sessionId 会话 id（可为 null ⇒ 落全局桶）
     * @param frame     定位帧
     */
    public void put(String sessionId, ComputerUseGroundingFrame frame) {
        if (frame == null) {
            return;
        }
        bySession.put(key(sessionId), frame);
    }

    /**
     * 读取某会话最近一次 grounding 定位帧。
     *
     * @param sessionId 会话 id（可为 null ⇒ 落全局桶）
     * @return 定位帧；无则 null
     */
    public ComputerUseGroundingFrame get(String sessionId) {
        return bySession.get(key(sessionId));
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? GLOBAL_KEY : sessionId;
    }
}
