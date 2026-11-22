package com.nexusai.application.agent.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * ListAgents 分页游标存储（<b>服务端签发/校验</b>，调用方不得自造）。
 *
 * <p>契约：
 * <ul>
 *   <li><b>按会话隔离</b>：游标只对签发它的会话有效；别的会话拿同一串查不到。</li>
 *   <li><b>每会话上限 {@value #MAX_CURSORS_PER_SESSION}</b>：签发新游标时若已满，淘汰最旧的一条
 *       （先签发先失效）；被淘汰的游标按「不合法」处理。</li>
 *   <li><b>带过期</b>：默认 {@value #DEFAULT_TTL_MILLIS} ms；过期条目惰性清理，校验失败。</li>
 *   <li><b>游标本身不承载可解析的位置</b>：token 是随机串，偏移与快照都存在本存储里，
 *       ⇒ 模型无法自造/篡改页码（自造的串在表里查不到 = 不合法）。</li>
 *   <li><b>生命周期 = 进程内</b>：不落盘，重启即失效（与 {@link PeerRefPins} 同口径）。</li>
 * </ul>
 *
 * <p>存储内容是一份<b>快照</b>（{@link ListAgentsFormatter.Snapshot}）+ 下一页的全局行偏移。
 * 翻页全程读同一份快照 ⇒ 同一快照内<b>不重不漏</b>（枚举源在翻页期间变化也不影响已签发的页）。
 */
@Component
public class ListAgentsCursorStore {

    private static final Logger log = LoggerFactory.getLogger(ListAgentsCursorStore.class);

    /** 每会话同时有效的游标上限；超出淘汰最旧。 */
    public static final int MAX_CURSORS_PER_SESSION = 32;

    /** 游标默认有效期（10 分钟）。 */
    public static final long DEFAULT_TTL_MILLIS = 10L * 60L * 1000L;

    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, Deque<Entry>> bySession = new ConcurrentHashMap<>();

    /** 一条游标记录（token → 快照 + 下一页偏移 + 过期时刻）。 */
    private record Entry(String token, ListAgentsFormatter.Snapshot snapshot,
                         int offset, long expiresAt) {}

    /** 校验结果。 */
    public record Resolved(ListAgentsFormatter.Snapshot snapshot, int offset) {}

    public ListAgentsCursorStore() {
        this(DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    /**
     * 测试/接线用构造。
     *
     * @param ttlMillis 有效期（≤0 时回落默认值）
     * @param clock     时钟（测试可拨快）
     */
    public ListAgentsCursorStore(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * 签发一个游标。
     *
     * @param sessionId 签发会话（空 → 不签发，返回 null）
     * @param snapshot  该次列表的快照
     * @param offset    下一页的全局行偏移
     * @return 随机 token；未签发 → null
     */
    public String issue(String sessionId, ListAgentsFormatter.Snapshot snapshot, int offset) {
        if (isBlank(sessionId) || snapshot == null) {
            return null;
        }
        long now = clock.getAsLong();
        Deque<Entry> queue = bySession.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        String token = newToken();
        synchronized (queue) {
            pruneLocked(queue, now);
            while (queue.size() >= MAX_CURSORS_PER_SESSION) {
                Entry evicted = queue.removeFirst();
                if (log.isDebugEnabled()) {
                    log.debug("[ListAgentsCursor] evicted oldest cursor session={} token={}",
                        sessionId, evicted.token());
                }
            }
            queue.addLast(new Entry(token, snapshot, Math.max(0, offset), now + ttlMillis));
        }
        return token;
    }

    /**
     * 校验游标（<b>只认本会话签发的</b>）。
     *
     * @param sessionId 调用方会话
     * @param token     游标
     * @return 快照 + 偏移；非法 / 过期 / 别会话签发 → null
     */
    public Resolved resolve(String sessionId, String token) {
        if (isBlank(sessionId) || isBlank(token)) {
            return null;
        }
        Deque<Entry> queue = bySession.get(sessionId);
        if (queue == null) {
            return null;
        }
        long now = clock.getAsLong();
        synchronized (queue) {
            pruneLocked(queue, now);
            for (Entry entry : queue) {
                if (entry.token().equals(token)) {
                    return new Resolved(entry.snapshot(), entry.offset());
                }
            }
        }
        return null;
    }

    /** 某会话当前有效游标数（测试/观测）。 */
    public int size(String sessionId) {
        if (isBlank(sessionId)) {
            return 0;
        }
        Deque<Entry> queue = bySession.get(sessionId);
        if (queue == null) {
            return 0;
        }
        long now = clock.getAsLong();
        synchronized (queue) {
            pruneLocked(queue, now);
            return queue.size();
        }
    }

    /** 清掉某会话（会话销毁时）。 */
    public void clearSession(String sessionId) {
        if (!isBlank(sessionId)) {
            bySession.remove(sessionId);
        }
    }

    /** 全部清空（测试辅助；生产不调用 = 进程退出即灭）。 */
    public void clear() {
        bySession.clear();
    }

    /** 惰性清理过期条目（调用方已持有 queue 锁）。 */
    private void pruneLocked(Deque<Entry> queue, long now) {
        queue.removeIf(entry -> now >= entry.expiresAt());
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
