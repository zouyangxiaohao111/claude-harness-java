package com.nexusai.application.agent.subagent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「被用户显式停止」的内存标记 · 对齐 CC {@code Fat()}（claude.exe 2.1.328 偏移 334311557）：
 *
 * <pre>{...t, stoppedByUser:true, userStopCount:(t.userStopCount??0)+1}</pre>
 *
 * <p>CC 是<b>双写</b>：内存 task 记录 + 磁盘 {@code <agentId>.stopped-by-user} 伴生文件
 * （{@code c4o={stoppedByUser:".stopped-by-user", observerStopped:".observer-stopped"}}，
 * 偏移 325012255）。本仓原先只有磁盘侧（{@link AgentTranscript#markStoppedByUser}），
 * 内存侧没有等价物 —— 结果 resume 门只能靠磁盘探测，进程内「刚刚被停、worker 还没退出」的
 * 竞态窗口只能靠 {@code stopPending} 兜，且 {@code userStopCount} 的「同一停止周期内又被停」
 * 判据缺失。本类补上内存侧。
 *
 * <p>幂等语义与 CC 逐字对齐：<b>已停过的 agent 再写只保留标记，不重复自增</b>
 * （{@code if (stopped) return;}）。{@code userStopCount} 唯一用途 = resume 开始时快照、
 * 准入后比对（期间又被停过 ⇒ 丢弃这次投递，CC {@code "was stopped meanwhile, so this delivery was dropped."}）。
 */
@Component
public class StopMarkers {

    /** agentId → 停止次数（首次停止 = 1）。 */
    private final Map<String, AtomicInteger> userStopCounts = new ConcurrentHashMap<>();

    /**
     * 标记「被用户显式停止」· 幂等（已停不再自增），返回当前停止次数。
     *
     * @param agentId 目标 agent（taskId === agentId）
     * @return 当前 {@code userStopCount}（未知 agentId → 0，不写入）
     */
    public int markStopped(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return 0;
        }
        AtomicInteger count = userStopCounts.computeIfAbsent(agentId, k -> new AtomicInteger());
        // CC Fat(): 已是 stoppedByUser ⇒ 只保留标记。这里 count>0 即「已停」。
        count.compareAndSet(0, 1);
        return count.get();
    }

    /** 是否被用户停止过。 */
    public boolean isStopped(String agentId) {
        if (agentId == null) {
            return false;
        }
        AtomicInteger count = userStopCounts.get(agentId);
        return count != null && count.get() > 0;
    }

    /**
     * 当前停止周期计数（resume 准入前快照 · CC {@code sn}）。
     *
     * @return 从未停止 → 0
     */
    public int stopCount(String agentId) {
        if (agentId == null) {
            return 0;
        }
        AtomicInteger count = userStopCounts.get(agentId);
        return count == null ? 0 : count.get();
    }

    /**
     * 清除标记（用户显式 resume 成功后）· 对齐 CC {@code eKt()} 的内存侧等价。
     *
     * <p>⛔ 只有**用户显式发起**的 resume 能清；模型自动 resume 被 k9 拒绝时不清。
     */
    public void clear(String agentId) {
        if (agentId != null) {
            userStopCounts.remove(agentId);
        }
    }

    /** 清空（测试用）。 */
    public void reset() {
        userStopCounts.clear();
    }

    /** 当前被停过的 agent 数（测试/观测）。 */
    public int size() {
        return userStopCounts.size();
    }
}
