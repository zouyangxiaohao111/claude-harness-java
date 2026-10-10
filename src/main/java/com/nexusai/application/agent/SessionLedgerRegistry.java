package com.nexusai.application.agent;

import com.nexusai.application.agent.session.SessionResumeDeserializer;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [会话账本柜 · session-ledger 2026-10-10] 会话历史「活账本」接力注册表（JVM 冷热家族第五块表）。
 *
 * <h2>WHY（要修的缺陷）</h2>
 * 本仓 {@code AgentState} 每 run 新建、每 run 从 DB 全量重读 + 注入过滤（{@code LlmAgentLoop} 注入段）——
 * 同一会话存在「长跑 run 的内存账本」与「新 run 的 DB 重抄」两套历史构造，产出不完全一致
 * （专项实测：同历史两版差 −8,719 tokens）→ 换 run 即前缀缓存断裂（实测 68.2% / 每 6 分钟重演）。
 * CC（REPL 内存 messages 恒持、从不重读磁盘，REPL.tsx:2793）与 dsh（live 禁止重载、冷启动一次派生，
 * coordinator.ts:723）均无此问题。本表即「单一构造源」的落地：热态接力，只有冷启动才重抄。
 *
 * <h2>机制（接力棒语义 · 一次性消费）</h2>
 * <ul>
 *   <li>{@link #take}：取出并<b>原子摘除</b>（{@code ConcurrentHashMap.remove} 返回旧值）。
 *       有 = 热接力（调用方做新鲜度校验后直接用）；无 = 冷启动（调用方走 DB 全量重抄）。</li>
 *   <li>{@link #commit}：<b>仅 run 正常完成（NORMAL 终态）</b>时调用 → 快照入柜（盖戳交棒）。</li>
 *   <li>任何异常路径（run 抛错 / 非 NORMAL 终态）不 commit → 柜台无此会话 → 下个 run 冷启动重抄。
 *       「一次性消费 + 完成才交棒」消灭所有错接分支（对照：表里常驻最新 state 会错接到已崩 run 的
 *       过期快照——缺崩掉那轮的消息）。</li>
 *   <li>take 后若新鲜度校验不过（快照后出现旁路新行）→ 快照<b>天然作废</b>（已被摘除）→ 冷启动。
 *       校验由调用方执行（{@code MessageService.latestMessageIds} + 对比
 *       {@link LedgerSnapshot#lastMessageId()}），本表只带锚点。</li>
 * </ul>
 *
 * <h2>新鲜度锚 = lastMessageId（⛔ 曾用 createdAt，实测不可行）</h2>
 * 初版锚用「快照最大 createdAt」做字符串比较，e2e 实锤双缺陷：<b>①双源精度不同</b>（state DTO 为
 * 纳秒精度、DB 列存毫秒截断）；<b>②assistant 消息的 DTO.createdAt 与落库值不同源</b>（落库经
 * nextCreatedAt 单调分配器取号，内存 DTO 未必同步）⇒ 快照「最后一条」都可能判错 → 校验<b>永久不新鲜</b>
 * （每轮弃快照走冷循环）。改用 <b>消息 id</b> 比对：id 是 DTO 与 DB 的<b>同源稳定键</b>
 * （appendListener 用 DTO 原 id 落库），且「最近 N 条 id」查询天然绕开时间比较的并列/精度问题。
 *
 * <h2>哨兵剔除（commit 时）</h2>
 * 热快照 = 「上一次注入的漏斗产物 + 运行中新增」。漏斗（{@link SessionResumeDeserializer}）会在
 * 中断 / 部分配对场景<b>合成</b>三类哨兵消息（Continue meta user / No response requested. assistant /
 * Tool result missing tool）——它们不落 DB，冷路径读 DB 天然没有；故 commit 时按 role+content
 * 双匹配剔除，保证「账本 = DB 等价物」——热接产物与冷启动产物逐条一致（这是「统一构造源」的核心不变量）。
 *
 * <h2>生命周期（对齐既有四块 JVM 冷热表 · SessionStartSeenRegistry 先例）</h2>
 * <ul>
 *   <li>JVM 重启 → 表天然清空 = 全冷（正确：下个 run 冷启动重抄）。</li>
 *   <li>{@code /clear} → {@link #remove}（CommandController；注意 /clear 命令当前产品停用
 *       P0-0/N1，挂点保留待启用）。</li>
 *   <li>会话删除 → {@link #remove}（{@code SessionService.delete} 清理段）。</li>
 *   <li>第一版不做空闲逐出（对齐先例纪律：只在删除时清）。</li>
 * </ul>
 *
 * <h2>local-only 红线</h2>
 * 纯进程内存，绝不序列化 / 绝不经 STOMP / WebSocket / EventPublisher / outbound DTO 外发
 * （对齐 CLAUDE.md BudgetTracker 架构条款与既有四表先例）。
 */
public final class SessionLedgerRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionLedgerRegistry.class);

    /** sessionId → 已完成账本快照（一次性接力棒；take 即摘除）。 */
    private static final Map<String, LedgerSnapshot> LEDGERS = new ConcurrentHashMap<>();

    private SessionLedgerRegistry() {
    }

    /**
     * 账本快照 · 完整消息列表（列表与元素均已不可变/copy）+ 新鲜度校验锚。
     *
     * @param messages      完整消息列表（commit 时已拷贝、已剔合成哨兵；元素为不可变 record）
     * @param lastMessageId 快照最后一条消息的 id（剔除哨兵后；DTO/DB 同源稳定键，新鲜度校验锚）
     */
    public record LedgerSnapshot(List<ChatMessageDto> messages, String lastMessageId) {
    }

    /**
     * 取出并原子摘除该会话的账本快照（接力棒）。
     *
     * <p>有 = 上一位 run 正常跑完留下的账本（调用方先做新鲜度校验再用）；无 = 冷启动。
     *
     * @param sessionId 会话 ID（short 形态 sess-xxx；null/空白 → 返回 null）
     * @return 账本快照；柜台无此会话 → null
     */
    public static LedgerSnapshot take(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        LedgerSnapshot snap = LEDGERS.remove(sessionId);
        if (snap != null && log.isInfoEnabled()) {
            log.info("[会话账本柜] take（一次性接力）: session={} 账本 {} 条 lastMessageId={}",
                sessionId, snap.messages().size(), snap.lastMessageId());
        }
        return snap;
    }

    /**
     * 交棒：run 正常完成（NORMAL 终态）时把最终账本快照入柜。
     *
     * <p>入柜前剔除三类合成哨兵（见类 javadoc「哨兵剔除」），保证账本 = DB 等价物。
     *
     * @param sessionId 会话 ID（null/空白 → no-op）
     * @param messages  run 终态全量消息（{@code state.rawMessages()}；null/空 → no-op）
     */
    public static void commit(String sessionId, List<ChatMessageDto> messages) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        List<ChatMessageDto> cleaned = new ArrayList<>(messages.size());
        int stripped = 0;
        for (ChatMessageDto m : messages) {
            if (m == null) {
                continue;
            }
            if (isSyntheticSentinel(m)) {
                stripped++;
                continue;
            }
            cleaned.add(m);
        }
        if (cleaned.isEmpty()) {
            // 全哨兵（理论罕见）→ 不交棒：下个 run 冷启动重抄，安全兜底
            log.warn("[会话账本柜] commit 跳过（剔除后为空·全哨兵？）: session={} 原 {} 条", sessionId, messages.size());
            return;
        }
        // 新鲜度锚 = 剔除后最后一条的 id（DTO/DB 同源；⛔ 勿改回 createdAt——见类 javadoc「新鲜度锚」）
        ChatMessageDto last = cleaned.get(cleaned.size() - 1);
        String lastMessageId = last.id();
        // List.copyOf：不可变拷贝（元素为 record，共享安全）；cleaned 已过滤 null。
        LEDGERS.put(sessionId, new LedgerSnapshot(List.copyOf(cleaned), lastMessageId));
        if (log.isInfoEnabled()) {
            log.info("[会话账本柜] commit（交棒）: session={} 账本 {} 条（剔除合成哨兵 {} 条）lastMessageId={}",
                sessionId, cleaned.size(), stripped, lastMessageId);
        }
    }

    /**
     * 移除该会话账本（{@code /clear} 与 会话删除 两处挂点）。
     *
     * @param sessionId 会话 ID（null/空白 → no-op）
     */
    public static void remove(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        LedgerSnapshot removed = LEDGERS.remove(sessionId);
        if (removed != null && log.isInfoEnabled()) {
            log.info("[会话账本柜] remove: session={}（/clear 或会话删除；释放账本 {} 条）",
                sessionId, removed.messages().size());
        }
    }

    /**
     * 合成哨兵判别（role + content 双匹配）· 常量与 {@link SessionResumeDeserializer} 同源（真源单点）。
     *
     * <p>三类（对照漏斗合成点）：
     * <ul>
     *   <li>Continue meta user —— {@code deserializeWithInterruptDetection} 步骤 5（interrupted_turn 恢复）；</li>
     *   <li>No response requested. assistant —— 步骤 6（末条为 user → splice assistant sentinel）；</li>
     *   <li>Tool result missing tool —— 步骤 1.5 {@code appendMissingToolResults}（部分配对补桩）。</li>
     * </ul>
     * 三者均为英文固定句 + 各自唯一 role，误伤概率趋近于零；且仅作用于「入柜快照」，
     * 不影响 DB / UI / 出站（冷路径读 DB 天然无此三者）。
     */
    static boolean isSyntheticSentinel(ChatMessageDto m) {
        if (m == null || m.content() == null || m.role() == null) {
            return false;
        }
        String c = m.content();
        if (m.role() == Role.user && SessionResumeDeserializer.CONTINUE_FROM_LEFT_OFF.equals(c)) {
            return true;
        }
        if (m.role() == Role.assistant && SessionResumeDeserializer.NO_RESPONSE_REQUESTED.equals(c)) {
            return true;
        }
        return m.role() == Role.tool && SessionResumeDeserializer.SYNTHETIC_MISSING_TOOL_RESULT.equals(c);
    }

    /**
     * 清空全表（@VisibleForTesting：重启模拟测试用）。生产进程重启天然清空（JVM 内存），不供生产调用。
     */
    static void reset() {
        LEDGERS.clear();
    }

    /**
     * 柜台是否有该会话账本（@VisibleForTesting：断言用；生产不消费）。
     */
    static boolean contains(String sessionId) {
        return sessionId != null && LEDGERS.containsKey(sessionId);
    }
}
