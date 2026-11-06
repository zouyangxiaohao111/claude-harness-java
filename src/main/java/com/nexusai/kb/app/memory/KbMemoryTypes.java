package com.nexusai.kb.app.memory;

import java.util.List;

/**
 * 长期记忆的枚举与预算常量（批次 W9）。
 *
 * <p><b>源侧对照</b>：WeKnora {@code internal/types/memory.go:20-110}。
 *
 * <h2>⭐ 三个枚举决定了整个功能的行为</h2>
 * <ul>
 *   <li>{@code kind}：前两种是<b>常驻块</b>（每轮注入提示词），后三种是<b>情境</b>
 *       （按当前查询匹配才拉进来）。</li>
 *   <li>{@code origin}：⚠️ 只有 {@link #ORIGIN_EXTRACTED} 走墓碑拦截 ——
 *       用户明说「记住这个」必须永远能写进去，后台重抽才需要防复活。</li>
 *   <li>{@code status}：{@link #STATUS_PENDING} = 系统推断的，<b>绝不注入提示词</b>，
 *       等用户确认。源侧理由：从提问猜角色「often right, but asserting a wrong guess
 *       silently is how a memory feature loses trust for good」。</li>
 * </ul>
 *
 * <p>⚠️ 这些是字符串常量而不是 Java enum：DB 里存的就是字符串，
 * 用 enum 会在「读到脏值」时抛异常，而读路径不能因为一行脏数据整个列表 500。
 */
public final class KbMemoryTypes {

    private KbMemoryTypes() {
    }

    // ── kind ──────────────────────────────────────────────

    /** 关于用户的稳定属性（常驻块）。 */
    public static final String KIND_PROFILE = "profile";
    /** 用户的偏好（常驻块）。 */
    public static final String KIND_PREFERENCE = "preference";
    /** 情境事实（按查询匹配）。 */
    public static final String KIND_FACT = "fact";
    /** 在办事项（按查询匹配）。 */
    public static final String KIND_TASK = "task";
    /** 长期关注的主题（常驻块）。由复现次数推导，不是用户说出来的。 */
    public static final String KIND_INTEREST = "interest";

    /** 全部 kind，<b>按常驻块渲染顺序</b>排列。 */
    public static final List<String> KINDS = List.of(
        KIND_PROFILE, KIND_PREFERENCE, KIND_FACT, KIND_TASK, KIND_INTEREST);

    /** 进常驻块的 kind（每轮都注入）。 */
    public static final List<String> RESIDENT_KINDS = List.of(
        KIND_PROFILE, KIND_PREFERENCE, KIND_INTEREST);

    /**
     * kind 对应的常驻块标题。
     *
     * <p>⚠️ 用英文做结构标题（源侧同）：模型应当把它读成<b>结构</b>，
     * 而不是当成要被复述的内容。
     */
    public static final java.util.Map<String, String> KIND_LABELS = java.util.Map.of(
        KIND_PROFILE, "About the user",
        KIND_PREFERENCE, "Preferences",
        KIND_FACT, "Relevant facts",
        KIND_TASK, "Ongoing tasks",
        KIND_INTEREST, "Long-term focus");

    // ── origin ────────────────────────────────────────────

    /** 用户在对话里明确要求记住的。 */
    public static final String ORIGIN_EXPLICIT = "explicit";
    /** 后台蒸馏任务抽出来的。 */
    public static final String ORIGIN_EXTRACTED = "extracted";
    /** 在记忆管理器里手工创建或编辑的。 */
    public static final String ORIGIN_MANUAL = "manual";

    // ── status ────────────────────────────────────────────

    public static final String STATUS_ACTIVE = "active";
    /** 被更新的事实取代（内容保留，可追溯）。 */
    public static final String STATUS_SUPERSEDED = "superseded";
    /** 超出容量被归档 / 到期。 */
    public static final String STATUS_ARCHIVED = "archived";
    /** 系统推断、等用户确认；<b>不注入</b>。 */
    public static final String STATUS_PENDING = "pending";

    /** 合法的 status 过滤值（空 = 不过滤）。 */
    public static final List<String> FILTERABLE_STATUSES = List.of(
        STATUS_ACTIVE, STATUS_SUPERSEDED, STATUS_ARCHIVED, STATUS_PENDING);

    // ── 写入模式 ──────────────────────────────────────────

    /** 只记用户明说的（不调模型，默认）。 */
    public static final String WRITE_EXPLICIT_ONLY = "explicit_only";
    /** 额外用后台任务从对话里蒸馏。 */
    public static final String WRITE_AUTO = "auto";

    // ── 预算（单位：码点，不是字节也不是 UTF-16 单元）─────────

    /**
     * 常驻块预算。
     *
     * <p>源侧用码点而不是 token：块内是短单行条目，精确数 token 需要在读路径上挂分词器。
     */
    public static final int BLOCK_RUNE_BUDGET = 900;

    /** 单轮召回预算。 */
    public static final int RECALL_RUNE_BUDGET = 600;

    /** 单轮召回最多拉几条情境条目（与码点预算独立）。 */
    public static final int RECALL_MAX_ITEMS = 5;

    /** 常驻块最多带几条兴趣。 */
    public static final int RESIDENT_INTEREST_MAX_ITEMS = 5;

    /** 单条记忆内容上限（码点）。超过就是摘要，应该进知识库而不是这里。 */
    public static final int CONTENT_MAX_RUNES = 300;

    /** 一个主体最多保有多少条 active 条目；超出时归档排名最低的。 */
    public static final int DEFAULT_MAX_ITEMS = 200;

    /** 兴趣提升阈值（同一主题被问到几次才记为长期关注）。 */
    public static final int DEFAULT_INTEREST_THRESHOLD = 3;

    /** 重要性默认值。 */
    public static final int DEFAULT_IMPORTANCE = 3;
    /** 重要性上下界。 */
    public static final int MIN_IMPORTANCE = 1;
    public static final int MAX_IMPORTANCE = 5;

    /** kind 是否合法（未知 ⇒ 调用方回落 fact，⛔ 不丢）。 */
    public static boolean isValidKind(String kind) {
        return kind != null && KINDS.contains(kind.trim().toLowerCase());
    }

    /** kind 是否进常驻块。 */
    public static boolean isResidentKind(String kind) {
        return kind != null && RESIDENT_KINDS.contains(kind.trim().toLowerCase());
    }

    /** status 过滤值是否合法（空串合法 = 不过滤）。 */
    public static boolean isValidStatusFilter(String status) {
        return status == null || status.isBlank()
            || FILTERABLE_STATUSES.contains(status.trim().toLowerCase());
    }

    /** 重要性夹到 1..5。 */
    public static int clampImportance(int importance) {
        if (importance < MIN_IMPORTANCE) {
            return MIN_IMPORTANCE;
        }
        return Math.min(importance, MAX_IMPORTANCE);
    }

    /** 默认写入模式（未配置时）。 */
    public static String defaultWriteMode() {
        return WRITE_EXPLICIT_ONLY;
    }
}
