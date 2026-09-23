package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>[步骤 7 · 投递层修正] 会话级「记忆文件变更基线」注册表</b>（进程内静态表 · 键 = sessionId）。
 *
 * <h2>本类修复的缺陷（独立验证 #1 判定 REFUTED 的那一条）</h2>
 * <pre>
 * 每 run 都调 seedMemoryFilesBaselineForChangeDetection
 *   → 每个记忆文件无条件 readFileState.set(key, ReadState(now, …))     // 登记时间戳 = 本 run 起点
 *   → 判据 mtime &gt; 记录时间戳（ChangedFilesDetector）
 *   ⇒ 用户在两条消息之间改 CLAUDE.md：mtime &lt; 本 run 登记时间戳
 *     ⇒ 被下一次登记「吸收」⇒ 永不投递
 * </pre>
 * 即：只有「同一 run 内改盘」才投递，而真实场景（用户两条消息之间改盘）恰好发生在
 * <b>run 与 run 之间</b> ⇒ 计划验收标准 #3 按自然解读不成立。
 *
 * <h2>⭐ 修法：把「首次会话登记」固化下来（会话级 readFileState + 会话级基线）</h2>
 * ⛔ <b>注释更正（批 edit-gate-session-scope · E）</b>：本段原写「本仓 {@code readFileState} 是
 * <b>run 级</b>缓存（{@code buildBaseToolUseContext} 的 {@code readFileState=null} 分支 →
 * 构造器每次新建；{@code LlmAgentLoop} 为 {@code @Scope("prototype")} 每 send 新实例）」。
 * 该断言<b>已不成立</b>：{@code buildBaseToolUseContext} 现取
 * {@code SessionReadFileStateRegistry.forSession(sessionId)} ⇒ {@code readFileState} 已是
 * <b>会话级</b>（同会话跨 run 恒同一张表；{@code LlmAgentLoop} 仍是 prototype，但缓存不再随
 * loop 实例销毁 —— 缓存挂在静态注册表上）。本表<b>仍然必要</b>，理由已变：
 * 本方法每 run 都会把「当下」写进登记的 entry（{@code registerMemoryFilesBaseline}），
 * 会话级缓存只让<b>条目</b>活得更久、<b>不改时间戳被覆盖</b>这一事实 ⇒
 * 没有本表仍会退化成「只有同一 run 内改盘才投递」。本表按 sessionId 持有
 * <b>首次登记</b>的 {@code ReadState}（时间戳 + 内容基线），于是：
 * <ol>
 *   <li><b>登记</b>（{@code ClaudemdEngine.registerMemoryFilesBaseline}）：key 已存在 ⇒
 *       <b>复用旧基线</b>（不覆盖）；否则落下首次基线。两种情形都把该基线写进本 run 的 readFileState
 *       ⇒ 变更检测的比对基准恒为「上次已知的字节」而不是「本 run 的当下」。</li>
 *   <li><b>投递</b>（{@code ChangedFilesDetector} → 尾部 {@code edited_text_file}）：判据仍是
 *       {@code mtime &gt; 记录时间戳}（⛔ 未改判据）⇒ 用户两条消息之间改盘必命中。</li>
 *   <li><b>固化</b>（{@link #syncFromRunCache(String, FileStateCache)}）：投递时检测器已把
 *       「新内容 + 新 mtime」写回本 run 的 readFileState，本方法把这份新状态<b>回写基线</b>
 *       ⇒ 同一变更<b>只投一次</b>（否则下一次 run 又拿旧基线比对，变成每 run 刷屏）。</li>
 * </ol>
 *
 * <h2>与 CC 的关系（批 edit-gate-session-scope 后已对齐）</h2>
 * CC 的 {@code readFileState} <b>整张表就是会话级</b>（{@code REPL.tsx:3797-3818 onInit} 把
 * memory files 写进 {@code readFileState.current}，该 ref 活到进程结束＝会话结束），
 * 且<b>只在会话启动登记一次</b>。
 * ⛔ <b>注释更正（批 edit-gate-session-scope · E）</b>：本段原写「本仓受『run 级
 * readFileState + prototype loop』的结构限制，只把登记基线的跨 run 记忆提到会话级，
 * readFileState 本身仍是 run 级 —— 这是有意的偏离」。该偏离<b>已由本批消除</b>：
 * readFileState 已提到会话级（{@code SessionReadFileStateRegistry}，键 = sessionId，
 * 注入点 {@code LlmAgentLoop.buildBaseToolUseContext}）。
 * 由此一并改变的正是当时列为「待独立验证」的两条跨 run 语义：
 * <ol>
 *   <li><b>Edit/Write/Notebook 的 read-before-write 门禁</b>跨 run 生效 —— 这是本批的
 *       <b>目标收益</b>（同会话上一轮 Read 过、本轮 Edit 不再被拒）；</li>
 *   <li><b>nested memory 的 readFileState 去重</b>跨 run 生效 —— 子代理侧已用
 *       {@code ToolUseContext.cloneFileStateCache} 隔离（{@code SubagentExecutor.withEffectiveCwd}），
 *       会话表不被子代理写/清污染。</li>
 * </ol>
 * ⚠️ 仍未实现的一条：CC 2.1.278 的<b>跨进程</b> resume 恢复（从会话消息历史重放 Read 调用
 * 重建缓存：发行产物 {@code restoreReadFileState} → {@code mergeReadFileStateFrom}）。
 * 本仓静态表只覆盖同 JVM 内跨 run；后端进程重启后缓存为空。属独立批次。
 *
 * <h2>为什么是本表而不是复用 SessionPromptCacheStore</h2>
 * {@code SessionPromptCacheStore} 是「prompt 缓存」容器（分段缓存 / 四个冻结值 / 尾部日期状态），
 * 本表是「变更检测基线」，语义不同（混放会让那个类的身份更糊）。两者生命周期一致
 * （跨 run 不销毁；会话删除时移除），故 {@code SessionService#delete} 两处 evict 并排接线。
 *
 * <h2>local-only 红线</h2>
 * 纯内存进程内状态，只承载「路径 → 字节」快照；绝不序列化 / 绝不经 STOMP / WebSocket /
 * EventPublisher / outbound DTO 外发。
 *
 * <h2>无会话标识（sessionId 为 null/空白）</h2>
 * ⛔ <b>不得</b>落到共享键（会把所有无会话调用方串成一桶）。{@link #forSession(String)}
 * 返回 {@code null} ⇒ 登记点退回改造前形态（每次登记取当下 = 无跨 run 记忆可用，因为
 * 无会话就没有「跨 run 的身份」）。这是诚实的降级，不是静默跳过。
 */
public final class SessionChangedFilesBaselineRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionChangedFilesBaselineRegistry.class);

    /** sessionId → 该会话的「记忆文件变更基线」表（一个会话恒一份，跨 run 不销毁）。 */
    private static final Map<String, FileStateCache> BASELINES = new ConcurrentHashMap<>();

    private SessionChangedFilesBaselineRegistry() {
    }

    /**
     * 取（必要时首建）该会话的基线表 · 同 sessionId 恒返回同一实例（跨 run 不销毁）。
     *
     * <p>容量与 {@code readFileState} 同口径（{@link ToolUseContext#createFileStateCache()} =
     * {@link ToolUseContext#READ_FILE_STATE_CACHE_SIZE} 条 + 25MB 双限真 LRU，条目数对齐目标
     * CC 2.1.278 的 {@code LC=5000}）—— 基线承载的正是同一批 entry 的「首次」版本，
     * 用同一容量语义避免两处口径分叉。
     *
     * @param sessionId 会话 id（short 形态 sess-xxx）
     * @return 该会话的基线表；{@code null}/空白会话 id ⇒ {@code null}（调用方退回改造前形态）
     */
    public static FileStateCache forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // ⛔ 无会话不共享键：返回 null（登记点据此退回「每次取当下」= 无跨 run 记忆）。
            if (log.isDebugEnabled()) {
                log.debug("[changed_files·基线] 无会话标识（null/空白）⇒ 无会话级基线（登记退回每 run 取当下）");
            }
            return null;
        }
        FileStateCache cache = BASELINES.computeIfAbsent(sessionId,
            id -> ToolUseContext.createFileStateCache());
        if (log.isDebugEnabled()) {
            log.debug("[changed_files·基线] 会话基线命中（跨 run 复用）: sessionId={} 基线条数={}",
                sessionId, cache.size());
        }
        return cache;
    }

    /**
     * 取该会话的基线表（<b>已存在才有</b>，⛔ 不创建）· 同步/失效路径专用
     * （否则为「同步一个还没跑过的会话」凭空建表 = 泄漏）。
     *
     * @param sessionId 会话 id（null/空白 ⇒ null）
     * @return 已注册的基线表；不存在 ⇒ null
     */
    public static FileStateCache peek(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return BASELINES.get(sessionId);
    }

    /**
     * <b>把本 run 的 readFileState 状态固化回会话基线</b>（同一变更只投一次的承重格）·
     * 由 {@code AgentLoopContext.maybeEmitChangedFiles} 在 {@code detectChangedFiles}
     * <b>之后</b>调用（检测器此时已把「新内容 + 新 mtime」写回 run 级 readFileState）。
     *
     * <p><b>逐 key 规则</b>（key 集合取自基线表本身 —— 只有记忆文件才进基线）：
     * <ol>
     *   <li>run 缓存里<b>没有</b>该 key ⇒ 基线里删除它（本 run 未登记 / 文件已被删（ENOENT 驱逐）/
     *       被 {@link ToolUseContext#READ_FILE_STATE_CACHE_SIZE} 条 LRU 挤出）—— 与 CC「entry 掉出
     *       readFileState 后不再投递」同义。</li>
     *   <li>run 缓存里是<b>全量视图</b>（{@code offset/limit} 均为 null）⇒ 固化（新时间戳 + 新内容）。</li>
     *   <li>run 缓存里是<b>窗口视图</b>（{@code offset/limit} 已设，模型用 Read(offset,limit) 读过）
     *       ⇒ <b>保留旧基线</b>：{@code ChangedFilesDetector} 会跳过窗口 entry（CC
     *       attachments.ts:2076-2078）⇒ 若把窗口 entry 固化进基线，该文件会被自己的跳过规则
     *       <b>永久</b>挡在投递通道外（把可检测条目降级成不可检测条目）。</li>
     * </ol>
     *
     * <p><b>子代理语义（如实登记）</b>：{@code maybeEmitChangedFiles} 由 {@code queryLoop} 统一调用
     * （主循环与子代理共用），子代理的 readFileState 是父缓存的 clone（{@code createSubagentContext}）
     * ⇒ 子代理若观测到某记忆文件变更，本方法会把该状态固化回会话基线 ⇒ 主线程不再重复投递同一变更。
     * CC 侧子代理的 clone 对父缓存无影响（父仍会投递）。本仓以「该变更已在本会话内被告知过」为口径，
     * ⛔ 不按 agentId 加门（那会让「主会话后台化 / cron」等路径一旦携带 agentId 就静默失去该通道
     * —— 静默失效比这条已登记的差异更坏）。
     *
     * <p>并发：{@code entries()} 返回快照（{@link FileStateCache} 内 {@code List.copyOf}）⇒
     * 迭代期写/删本表安全。
     *
     * @param sessionId 会话 id（null/空白 ⇒ no-op）
     * @param runCache  本 run 的 readFileState（null ⇒ no-op）
     * @return 实际固化的条数（0 = 无记忆文件基线 / 无会话；数据流日志与测试判据用）
     */
    public static int syncFromRunCache(String sessionId, FileStateCache runCache) {
        FileStateCache baseline = peek(sessionId);
        if (baseline == null || runCache == null || baseline.size() == 0) {
            return 0;
        }
        int synced = 0;
        int dropped = 0;
        int keptWindowView = 0;
        Iterator<Map.Entry<String, ToolUseContext.ReadState>> it = baseline.entries();
        while (it.hasNext()) {
            String key = it.next().getKey();
            ToolUseContext.ReadState cur = runCache.get(key);
            if (cur == null) {
                baseline.delete(key);
                dropped++;
                continue;
            }
            if (cur.offset() != null || cur.limit() != null) {
                keptWindowView++;
                continue;
            }
            baseline.set(key, cur);
            synced++;
        }
        if (log.isDebugEnabled()) {
            log.debug("[changed_files·基线] 会话基线固化完成（同一变更只投一次）: sessionId={} 固化={} 删除={}"
                + " 保留窗口视图={} 基线现有={} 条", sessionId, synced, dropped, keptWindowView, baseline.size());
        }
        return synced;
    }

    /**
     * 会话终结 → 移除该会话的基线表（CC 一进程一会话、进程退出即释放；本仓常驻 JVM 必须显式回收）。
     *
     * <p>接线点：{@code SessionService#delete}（与 {@code SessionPromptCacheRegistry.evict} /
     * {@code SessionGitStatusRegistry.evict} 同一处）。null/空白/未知会话 → no-op（不抛、不阻塞删除主流程）。
     *
     * @param sessionId 会话 id
     */
    public static void evict(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        FileStateCache removed = BASELINES.remove(sessionId);
        if (removed != null) {
            log.info("[changed_files·基线] 会话终结 → 会话级变更基线已移除: sessionId={} 原有 {} 条",
                sessionId, removed.size());
        } else if (log.isDebugEnabled()) {
            log.debug("[changed_files·基线] evict 未命中（该会话未建过基线）: sessionId={}", sessionId);
        }
    }

    /** 当前注册的会话数（测试 / 审计用）。 */
    public static int size() {
        return BASELINES.size();
    }

    /**
     * 清空全部会话基线（<b>仅测试用</b>）· 使静态表在用例之间归零
     * （对齐 {@code SessionPromptCacheRegistry.resetForTest} 先例）。
     */
    public static void resetForTest() {
        List<String> ids = new ArrayList<>(BASELINES.keySet());
        for (String id : ids) {
            evict(id);
        }
        if (log.isDebugEnabled()) {
            log.debug("[changed_files·基线] resetForTest: 已清空 {} 个会话基线", ids.size());
        }
    }
}
