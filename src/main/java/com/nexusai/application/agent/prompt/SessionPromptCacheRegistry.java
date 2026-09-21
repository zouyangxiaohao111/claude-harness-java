package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>会话级</b> prompt 缓存 store 注册表（进程内静态表 · 键 = sessionId）·
 * {@link SessionPromptCacheStore} 的唯一寻址通道。
 *
 * <p><b>⭐ 为什么是静态工具表（不是 @Component）</b>：唯一需要按 sessionId 取 store 的<b>调用方</b>是
 * {@link com.nexusai.application.agent.AgentState}——它是 plain 对象（全仓 {@code new AgentState(...)}
 * 生产 5 处、测试 401 处），拿不到 Spring 容器，也<b>不能</b>改构造签名。故沿用本仓既有先例
 * {@code SkillListingSentRegistry}（进程级会话键控静态表 + 静态 {@code remove*} 回收）与
 * {@code SessionStartSeenRegistry}，把「按 sessionId 分区」的静态表放在同包内。
 * 需要 Spring bean 的回收侧（{@code SessionService.delete}）直呼静态方法即可（同
 * {@code SkillListingSentRegistry.removeSessionEntries} 的调用形态）。
 *
 * <p><b>与 CC 的对应</b>：CC {@code STATE} 是模块级单例（bootstrap/state.ts:429），一进程一份、
 * 无 session 维度；本表是它的「按 sessionId 分区」映射（本仓一 JVM 多会话 ⇒ 必须分区，见
 * {@link SessionPromptCacheStore} 的语义映射段）。
 *
 * <p><b>无会话调用方（sessionId 为 null/空白）</b>：⛔ <b>不得</b>落到共享键（会把所有无会话调用方
 * 串成一桶）——返回一个<b>未注册</b>的一次性 store（由调用方自己持有；对 {@code AgentState} 即
 * 「每实例一份」，等价改造前的每 run 实例级缓存）。
 *
 * <p><b>防无界增长</b>：{@link #evict(String)}（会话终结：{@code SessionService#delete}）移除 + 终结
 * 该会话 store。⛔ <b>不在 /clear、/compact、worktree 进/出接</b>：那些事件清<b>缓存内容</b>
 * 而不销毁 store（CC {@code clearSystemPromptSections} 只清 Map，STATE 继续存活）。
 */
public final class SessionPromptCacheRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionPromptCacheRegistry.class);

    /** sessionId → 会话级 store（一个会话恒一份）。 */
    private static final Map<String, SessionPromptCacheStore> STORES = new ConcurrentHashMap<>();

    private SessionPromptCacheRegistry() {
    }

    /**
     * 取（必要时首建）该会话的 store · 同 sessionId 恒返回同一实例（跨 run 不销毁）。
     *
     * <p>{@code sessionStartDate} 只在<b>首建</b>时消费（后续 run 传的值被丢弃）——
     * 对齐 CC 会话冻结日期（{@code memoize(getLocalISODate)}，constants/common.ts:24）：
     * 会话首个 run 的本地日即全会话日期（跨午夜保留旧日期）。
     *
     * <p>并发：{@code computeIfAbsent} 原子单飞（同 {@code SessionGitStatusRegistry} 先例：
     * 首个调用方传的 {@code sessionStartDate} 胜）。
     *
     * @param sessionId        会话 id（short 形态 sess-xxx）；null/空白 ⇒ 返回<b>未注册</b>的一次性
     *                         store（不共享键，见类 javadoc）
     * @param sessionStartDate 会话冻结日期（{@code AgentState.sessionStartDate()}；
     *                         无会话时为 null → store 内该值为 null，provider 构造方自行处理）
     * @return 该会话的 store（恒非 null）
     */
    public static SessionPromptCacheStore forSession(String sessionId, String sessionStartDate) {
        if (sessionId == null || sessionId.isBlank()) {
            // ⛔ 无会话不共享键：一次性实例（调用方自行持有），不进注册表 ⇒ 不泄漏、不串味。
            if (log.isDebugEnabled()) {
                log.debug("[SessionPromptCacheRegistry] 无会话标识（null/空白）⇒ 返回未注册的一次性 store"
                    + "（等价每实例一份，防无会话调用方共用一个桶）");
            }
            return new SessionPromptCacheStore(null, sessionStartDate);
        }
        SessionPromptCacheStore store = STORES.computeIfAbsent(sessionId,
            id -> new SessionPromptCacheStore(id, sessionStartDate));
        if (log.isDebugEnabled()) {
            log.debug("[SessionPromptCacheRegistry] 会话 store 命中（跨 run 复用）: sessionId={} provider已建={} 段缓存条数={}",
                sessionId, store.hasContextProvider(), store.sectionCache().size());
        }
        return store;
    }

    /**
     * <b>按集合清某个会话的头部 prompt 缓存</b>（会话级失效的<b>唯一入口</b>）·
     * CC {@code clearSessionCaches} / {@code runPostCompactCleanup} / worktree 工具
     * 等清空点在 nexusai 的对等物。
     *
     * <p><b>⛔ 只清指定集合，绝不「统一清」</b>：CC 的 4 个集合在不同事件上被清不同子集
     * （{@code /clear} 全集 / worktree 只清 A / {@code /compact} 清 A+B(仅主线程)）。
     * 集合成员与调用点的对应关系<b>单点</b>定义在 {@link PromptCacheGroup}
     * （用 {@code PromptCacheGroup.CLEAR_SESSION_ALL} 这类具名常量，⛔ 不要在各调用点现拼集合）。
     * 统一清会把打掉前缀的频率弄得<b>高于</b> CC ⇒ 命中率反而更低（计划 §6）。
     *
     * <p><b>⛔ 会话级而非广播</b>：CC 是「一进程 = 一会话」，其清空是进程级；本仓一 JVM 多会话
     * ⇒ 这里只清 {@code sessionId} 那一个会话。广播清会「会话 A 压缩 ⇒ 打掉会话 B 的头部」
     * （跨会话串味）。无会话标识调用方（sessionId null/空白）⇒ <b>no-op</b>（不猜、不广播）。
     *
     * <p><b>⛔ 不存在则不建</b>：用 {@link #peek(String)} 取 store，<b>不</b>经
     * {@link #forSession(String, String)} —— 后者会按需 <b>创建</b> store，为「清一个还没跑过的
     * 会话」而凭空建 store 是泄漏（且新 store 本就无缓存可清）。
     *
     * <p><b>⛔ 不得用于「同 run / 同会话再次进入」</b>：本仓每发一次消息 = 一次 run，
     * 若在 run 边界（或 CC {@code sessionRestore} 的等价路径）清任何集合，就等于每 run 清一次
     * ⇒ 本 bug 原样复发。详见 {@link PromptCacheGroup} 的「映射陷阱」段。
     *
     * @param sessionId 目标会话（null/空白 ⇒ no-op；不广播、不猜）
     * @param groups    待清集合（见 {@link PromptCacheGroup} 具名常量；null/空 ⇒ no-op）
     * @param reason    失效原因（进日志，必须可与 CC 清空点对排，如
     *                  {@code "executeBuiltin(/clear)"} / {@code "compact:main-thread"} /
     *                  {@code "EnterWorktreeTool"} / {@code "工具注册(todoTaskTools @Bean)"}）
     * @return true = 命中会话并已执行清空；false = 无会话标识 / 未知会话 / 空集合（no-op）
     */
    public static boolean clearPromptCaches(String sessionId, java.util.Set<PromptCacheGroup> groups, String reason) {
        if (sessionId == null || sessionId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionPromptCacheRegistry] clearPromptCaches 跳过：无会话标识"
                    + "（⛔ 不广播清全部会话，不猜）reason={} groups={}", reason, PromptCacheGroup.describe(groups));
            }
            return false;
        }
        if (groups == null || groups.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionPromptCacheRegistry] clearPromptCaches 跳过：空集合 reason={} sessionId={}",
                    reason, sessionId);
            }
            return false;
        }
        SessionPromptCacheStore store = peek(sessionId);
        if (store == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionPromptCacheRegistry] clearPromptCaches 未命中（该会话未建过 store ⇒ 无缓存可清）: "
                    + "sessionId={} groups={} reason={}", sessionId, PromptCacheGroup.describe(groups), reason);
            }
            return false;
        }
        log.info("[SessionPromptCacheRegistry] 按集合清会话缓存: sessionId={} groups={} reason={}（对齐 CC 各清空点）",
            sessionId, PromptCacheGroup.describe(groups), reason);
        store.clearGroups(groups, reason);
        return true;
    }

    /**
     * 取该会话的 store（<b>已存在才有</b>，⛔ 不创建）· 失效路径专用。
     *
     * <p>与 {@link #forSession(String, String)} 的区别：后者「按需创建」（供 run 期取用），
     * 本方法「只读不建」（供失效路径取用）—— 否则为清一个尚未跑过的会话会凭空建 store。
     *
     * @param sessionId 会话 id（null/空白 ⇒ null）
     * @return 已注册的 store；不存在 ⇒ null
     */
    public static SessionPromptCacheStore peek(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return STORES.get(sessionId);
    }

    /**
     * 会话终结 → 移除并终结该会话 store（CC 一进程一会话、进程退出即释放；本仓常驻 JVM 必须显式回收）。
     *
     * <p>接线点：{@code SessionService#delete}（与 {@code SessionGitStatusRegistry.evict} /
     * {@code SessionAgentStateRegistry.removeBySessionId} 同一处）。
     * null/空白/未知会话 → no-op（不抛，不阻塞删除主流程）。
     *
     * @param sessionId 会话 id
     */
    public static void evict(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        SessionPromptCacheStore removed = STORES.remove(sessionId);
        if (removed != null) {
            try {
                removed.close();
            } catch (Exception e) {
                // best-effort：close 内部已幂等；失败不得让会话删除主流程断链
                log.warn("[SessionPromptCacheRegistry] 终结会话 store 失败 session={}: {}", sessionId, e.toString());
            }
            log.info("[SessionPromptCacheRegistry] 会话终结 → 会话级 prompt 缓存 store 已移除: sessionId={}", sessionId);
        } else if (log.isDebugEnabled()) {
            log.debug("[SessionPromptCacheRegistry] evict 未命中（该会话未建过 store）: sessionId={}", sessionId);
        }
    }

    /** 当前注册的会话数（测试 / 审计用）。 */
    public static int size() {
        return STORES.size();
    }

    /**
     * 清空全部会话 store（<b>仅测试用</b>）· 逐个 {@code close()} 后移除，使静态表在用例之间归零
     * （对齐 {@code SkillListingSentRegistry.reset()} / {@code PostCompactCleanup.resetForTest()} 先例）。
     */
    public static void resetForTest() {
        List<String> ids = new ArrayList<>(STORES.keySet());
        for (String id : ids) {
            evict(id);
        }
        if (log.isDebugEnabled()) {
            log.debug("[SessionPromptCacheRegistry] resetForTest: 已清空 {} 个会话 store", ids.size());
        }
    }
}
