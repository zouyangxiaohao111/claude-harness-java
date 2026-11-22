package com.nexusai.model.schedule.dto;

/**
 * 定时任务「执行上下文形态」（V306）· 用户 2026-11-12 定的四种。
 *
 * <p><b>为什么是独立枚举而不是给 {@link ScheduleScope} 加值</b>：
 * <ul>
 *   <li>{@code ScheduleService.lookupScope} 遇未知 scope 值会<b>静默回落 DURABLE</b>
 *       （末行 {@code return ScheduleScope.DURABLE}）—— 加新值也不报错；</li>
 *   <li>{@code sweepSessionTasksAtStartup} 会删「scope=SESSION 且会话不活跃」的行
 *       —— 新形态若复用 SESSION 值，重启一次任务就没了。</li>
 * </ul>
 * {@link ScheduleScope} 表达「生命周期」，本枚举表达「执行时用哪个会话」，两者<b>正交</b>。
 *
 * <table border="1">
 *   <tr><th>形态</th><th>每次触发</th><th>会话历史</th><th>会话何时删</th></tr>
 *   <tr><td>{@link #REUSE}</td><td>写进创建时绑定的会话</td><td>共享其全部历史</td><td>跟那个会话走</td></tr>
 *   <tr><td>{@link #NEW_EACH}</td><td>每次新建一个</td><td>零</td><td>30 天后</td></tr>
 *   <tr><td>{@link #NEW_ONCE}</td><td>首次新建后固定复用</td><td>累积在该专属会话</td><td><b>任务删除时才删</b></td></tr>
 *   <tr><td>{@link #NONE}</td><td>不产生会话</td><td>—</td><td>—</td></tr>
 * </table>
 *
 * <p><b>缺省 {@link #REUSE}</b> ⇒ 存量行（V306 之前创建的）语义零变化。
 *
 * <p><b>降级规则</b>：{@code REUSE} / {@code NEW_ONCE} 绑定的会话若被删，fire 时降级为
 * {@link #NEW_EACH}（而不是退回「无会话」）—— 否则会变成「跑了完全没痕迹」。
 */
public enum SessionMode {
    /** A · 绑定已有会话：写进用户选的那个会话，共享其历史。 */
    REUSE,

    /** B · 每次触发都新建一个会话（一次性，30 天后清理）。 */
    NEW_EACH,

    /** C · 首次触发造一个专属会话，之后一直复用；任务删除时才删会话。 */
    NEW_ONCE,

    /** D · 不绑定会话：后台静默执行，不产生任何会话。 */
    NONE;

    /** 缺省值（存量行 / 未指定时的语义）· 对齐 V306 列默认值。 */
    public static final SessionMode DEFAULT = REUSE;

    /**
     * 宽松解析（脏值不抛，回落 {@link #DEFAULT}）。
     *
     * <p>为什么不像 {@code ScheduleScope.valueOf} 那样抛：读侧遇到历史脏行不该让整个列表 500
     * （对齐 {@code ScheduleService.parseKind} 的「坏条目不阻塞整列表」口径）。
     * 写侧（REST/tool）另做严格校验。
     *
     * @param raw DB 列值；null/空白/非法 → {@link #DEFAULT}
     */
    public static SessionMode parseOrDflt(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /** 该形态是否由任务自己造会话（决定 fire 时是否需要建会话）。 */
    public boolean createsOwnSession() {
        return this == NEW_EACH || this == NEW_ONCE;
    }

    /** 该形态是否需要「每次触发用同一个会话」（决定是否要把 session_id 回填固化）。 */
    public boolean pinsSession() {
        return this == NEW_ONCE;
    }
}
