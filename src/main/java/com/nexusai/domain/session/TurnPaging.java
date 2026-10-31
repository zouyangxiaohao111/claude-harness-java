package com.nexusai.domain.session;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.session.entity.MessageRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * [turn-paging] 按<b>轮</b>分页的纯计算（无 Spring / 无 DB 依赖 ⇒ 单测可直接调度）。
 *
 * <p><b>WHY 要按轮分页</b>：前端原按「条数」分页（150 条/页）。数据形态是「一轮可能几百条 tool 消息」，
 * 实测某会话尾页 150 条全落在同一轮里 ⇒ 首屏连一轮都显示不全。轮分页把「轮」作为页单位，
 * <b>整轮返回、绝不切断</b>（一轮 = 一条真正的用户提问 + 它之后、下一条用户提问之前的所有
 * assistant / tool / 思考 / boundary 段）。
 *
 * <p><b>判据唯一真源</b>：{@link #isUserTurnRow} 逐条对齐前端
 * {@code front/src/stores/messageTurns.ts} 的 {@code isUserTurnRow}（role / is_meta / author /
 * is_compact_summary / NON_USER_TURN_SUBTYPES 五项）。两处若漂移，「后端切出来的轮」与「前端渲染的轮」
 * 就不是同一个东西 —— 切点会落在一轮中间。改动任一侧必须同步另一侧。
 *
 * <p><b>本类只做算术</b>：锚点识别 + 窗口定位 + 换算成 seq 行区间。
 * 真正的取行由 {@link MessageService#listMessagesByTurns} 按区间下推 SQL（无 LIMIT，整轮不截断）。
 */
public final class TurnPaging {

    /** 缺省轮数（前端不传 {@code turns} 时取最近 2 轮）。 */
    public static final int DEFAULT_TURNS = 2;

    /** 轮数上限（{@code turns > 10} 按 10 处理；防止一次拉爆超大响应）。 */
    public static final int MAX_TURNS = 10;

    /**
     * 非用户提问的 subtype —— <b>与前端 {@code NON_USER_TURN_SUBTYPES} 逐字同源</b>
     * （后台通知 / 调度 / 队列 / 离开摘要 / 子代理回执 / 压缩边界 / 实时展示行）。
     *
     * <p>这些即使 {@code role=user} 也<b>不得</b>作为轮次起点（否则后台通知会自成一轮，
     * 长过程里多出一个 {@code N nexus} 头 —— 用户裁定：只按用户消息算轮次）。
     */
    public static final Set<String> NON_USER_TURN_SUBTYPES = Set.of(
        "tool_use_summary", "stop_hook_summary",
        "task_notification", "task_started", "task_progress",
        "scheduled_task_fire", "cron", "queue", "away_summary",
        "compact_boundary", "microcompact_boundary", "snip_boundary", "snip_marker",
        "subagent_result", "subagent_receipt", "subagent_start", "subagent_stop");

    private TurnPaging() {
    }

    /** 轮锚点 = 一条「真正的用户提问」行：id + 位置键 seq（seq 非空，NULL 行由调用方剔除并留痕）。 */
    public record TurnAnchor(String id, long seq) {}

    /**
     * 轮窗口（半开区间 {@code [startIndex, endIndex)}，下标指向 {@link #anchorsOf} 的锚点序）。
     *
     * @param startIndex      窗口首轮在锚点列表中的下标
     * @param endIndex        窗口末轮之后的下标（exclusive）
     * @param hasMore         窗口之前是否还有更早的「用户提问」（= {@code startIndex > 0}）
     * @param seqFromInclusive 行区间下界（含）；<b>null = 无下界</b>（窗口覆盖到首轮 ⇒ 会话从头取，
     *                         这样首轮之前的无主行不会被永久遮挡）
     * @param seqToExclusive  行区间上界（不含）；null = 无上界（窗口覆盖到最后一轮 ⇒ 取到会话末尾）
     * @param firstTurnId     窗口首轮的用户提问 id（空窗口 → null）
     * @param lastTurnId      窗口末轮的用户提问 id（空窗口 → null）
     */
    public record TurnWindow(int startIndex, int endIndex, boolean hasMore,
                             Long seqFromInclusive, Long seqToExclusive,
                             String firstTurnId, String lastTurnId) {

        /** 空窗口（无轮可返回 / 游标已在首轮之前）——响应恒为 messages=[] + hasMore=false。 */
        public static TurnWindow empty() {
            return new TurnWindow(0, 0, false, null, null, null, null);
        }

        /** 窗口是否没有任何轮。 */
        public boolean isEmpty() {
            return endIndex <= startIndex;
        }
    }

    /** 轮数归一化：{@code <= 0} → {@link #DEFAULT_TURNS}；{@code > MAX_TURNS} → {@link #MAX_TURNS}。 */
    public static int normalizeTurns(int turns) {
        if (turns <= 0) {
            return DEFAULT_TURNS;
        }
        return Math.min(turns, MAX_TURNS);
    }

    /**
     * 是否「<b>真正的用户提问行</b>」（= 轮次起点）· 与前端 {@code isUserTurnRow} 同口径。
     *
     * <p>逐条对应（顺序无关，结果是合取）：
     * <ol>
     *   <li>{@code role == 'user'}；</li>
     *   <li>{@code is_meta} 为 NULL 或 0（DB {@code null / 0 / 1} 三态 → 非 {@code TRUE} 即非 meta）；</li>
     *   <li>{@code author} 不是 {@code system} / {@code attachment}；</li>
     *   <li>{@code is_compact_summary} 为 NULL 或 0；</li>
     *   <li>{@code subtype} 为 NULL 或不在 {@link #NON_USER_TURN_SUBTYPES}。</li>
     * </ol>
     *
     * @param role             DB {@code role} 列原文（user / assistant / tool / system）
     * @param author           DB {@code author} 列（可为 null）
     * @param subtype          DB {@code subtype} 列（可为 null）
     * @param isMeta           DB {@code is_meta} 列（可为 null = 旧行）
     * @param isCompactSummary DB {@code is_compact_summary} 列（可为 null = 旧行）
     */
    public static boolean isUserTurnRow(String role, String author, String subtype,
                                        Boolean isMeta, Boolean isCompactSummary) {
        if (!"user".equals(role)) {
            return false;
        }
        if (Boolean.TRUE.equals(isMeta)) {
            return false;
        }
        if ("system".equals(author) || "attachment".equals(author)) {
            return false;
        }
        if (Boolean.TRUE.equals(isCompactSummary)) {
            return false;
        }
        return subtype == null || !NON_USER_TURN_SUBTYPES.contains(subtype);
    }

    /**
     * 从<b>最小列集</b>行（{@code id/seq/role/author/subtype/is_meta/is_compact_summary}）筛出轮锚点。
     *
     * <p>seq 为 NULL 的行<b>不作锚点</b>：位置键未落 ⇒ 无法参与 seq 区间算术（调用方用
     * {@link #countNullSeqTurnRows} 数出来并 ERROR/warn 留痕，绝不静默当成 seq=0）。
     * 提取成公开静态方法是为了让「锚点识别」这段判据能被纯单测直接覆盖（不经 Spring / DB）。
     */
    public static List<TurnAnchor> anchorsOf(List<MessageRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<TurnAnchor> out = new ArrayList<>(rows.size());
        for (MessageRecord r : rows) {
            if (r == null || r.getId() == null) {
                continue;
            }
            if (!isUserTurnRow(r.getRole(), r.getAuthor(), r.getSubtype(),
                    r.getIsMeta(), r.getIsCompactSummary())) {
                continue;
            }
            if (r.getSeq() == null) {
                continue;
            }
            out.add(new TurnAnchor(r.getId(), r.getSeq()));
        }
        return out;
    }

    /** 「用户提问行但 seq 为 NULL」的行数（位置键未落 = 数据异常）—— 调用方据此 ERROR/warn 留痕。 */
    public static int countNullSeqTurnRows(List<MessageRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (MessageRecord r : rows) {
            if (r == null) {
                continue;
            }
            if (isUserTurnRow(r.getRole(), r.getAuthor(), r.getSubtype(),
                    r.getIsMeta(), r.getIsCompactSummary()) && r.getSeq() == null) {
                n++;
            }
        }
        return n;
    }

    /** 在锚点列表里找 {@code beforeTurnId} 的下标（线性；锚点数 = 会话轮数，量级远小于消息数）。无 → -1。 */
    public static int indexOfTurn(List<TurnAnchor> anchors, String beforeTurnId) {
        if (anchors == null || beforeTurnId == null || beforeTurnId.isBlank()) {
            return -1;
        }
        for (int i = 0; i < anchors.size(); i++) {
            TurnAnchor a = anchors.get(i);
            if (a != null && beforeTurnId.equals(a.id())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 定位轮窗口。
     *
     * <p>语义（接口契约）：
     * <ul>
     *   <li>{@code beforeTurnId} 省略/null → 取<b>最近</b> {@code turns} 轮；</li>
     *   <li>{@code beforeTurnId} 给定 → 取它<b>之前</b>的 {@code turns} 轮（<b>不含</b>该轮本身）；</li>
     *   <li>{@code hasMore} = 窗口之前是否还有更早的用户提问。</li>
     * </ul>
     *
     * @param anchors     轮锚点（按会话顺序 ASC）
     * @param beforeTurnId 游标（可为 null/blank）
     * @param turns        请求轮数（内部经 {@link #normalizeTurns} 归一化）
     * @return 轮窗口；无锚点 / 游标指向首轮 → {@link TurnWindow#isEmpty()} 为 true
     * @throws ValidationException {@code beforeTurnId} 不是任何一个锚点（不存在 / 不是用户提问行）
     *                             —— <b>显式失败</b>，绝不静默返回空页（前端会误判「到底了」）
     */
    public static TurnWindow resolveWindow(List<TurnAnchor> anchors, String beforeTurnId, int turns) {
        List<TurnAnchor> list = anchors == null ? List.of() : anchors;
        if (list.isEmpty()) {
            return TurnWindow.empty();
        }
        int n = normalizeTurns(turns);
        int end;
        if (beforeTurnId == null || beforeTurnId.isBlank()) {
            end = list.size();
        } else {
            int idx = indexOfTurn(list, beforeTurnId);
            if (idx < 0) {
                throw new ValidationException(
                    "[turn-paging] beforeTurnId " + beforeTurnId + " 不是轮起点（不存在或不是用户提问行），无法按轮定位");
            }
            end = idx;
        }
        int start = Math.max(0, end - n);
        if (end <= start) {
            return TurnWindow.empty();
        }
        // 下界：窗口覆盖到首轮（start==0）时不下界 ⇒ 首轮之前的无主行（会话开场的系统行等）随本页返回，
        //   否则它们永远取不到（hasMore 已为 false，前端不会再往上翻）。
        Long seqFrom = start > 0 ? list.get(start).seq() : null;
        // 上界：窗口之后还有轮（end < size）⇒ 严格切在下一个用户提问的 seq 上（该行属于下一轮，不含）。
        Long seqTo = end < list.size() ? list.get(end).seq() : null;
        return new TurnWindow(start, end, start > 0, seqFrom, seqTo,
            list.get(start).id(), list.get(end - 1).id());
    }
}
