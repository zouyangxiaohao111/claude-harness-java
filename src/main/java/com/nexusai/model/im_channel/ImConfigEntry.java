package com.nexusai.model.im_channel;

/**
 * IM 安全与权限配置（domain record，单行）。
 *
 * <p>这些开关约束的是 <b>AI 对外动作的边界</b>（读谁的记录、能不能直接发、发给谁），
 * 因此默认值取"最小放行"：{@code aiDirect} 与 {@code allowUserIdentity} 默认关闭。
 *
 * <p><b>⚠️ 当前只有 {@code aiDirect} / {@code existingOnly} 会被真正的发送链路读取</b>
 * （那条链路尚未接入渠道投递）；其余开关落库后可被后续实现直接消费，
 * 界面上不再标注"只是界面状态"。
 *
 * @param readAll         任何任务都能读 IM（false = 仅接线到该任务的聊天可见）
 * @param autoBind        首次从某任务给聊天发消息时，自动把该聊天接到这个任务
 * @param aiDirect        AI 可直接发送（免确认）；对面是真人，默认关闭
 * @param existingOnly    AI 只能回复已有会话，不能给新联系人发消息
 * @param takeoverConfirm 目标聊天已接在别的任务时，发送前弹确认
 * @param audit           记录每次对外动作的来源任务 / 用户 / 对象 / 内容摘要
 * @param allowUserIdentity 是否允许 AI <b>以用户本人名义</b>在 IM 发消息，默认关闭
 * @param updatedAt       最后更新时间（ISO-8601）
 */
public record ImConfigEntry(
    boolean readAll,
    boolean autoBind,
    boolean aiDirect,
    boolean existingOnly,
    boolean takeoverConfirm,
    boolean audit,
    boolean allowUserIdentity,
    String updatedAt
) {
    /**
     * 兼容构造器（6 个旧开关 + 时间戳，身份开关按<b>默认关闭</b>）。
     *
     * <p>为什么保留：老调用点只关心那 6 个开关；让"以用户名义"必须显式写出来，
     * 避免某个旧调用点不小心把 AI 切到用户身份。
     */
    public ImConfigEntry(
        boolean readAll,
        boolean autoBind,
        boolean aiDirect,
        boolean existingOnly,
        boolean takeoverConfirm,
        boolean audit,
        String updatedAt) {
        this(readAll, autoBind, aiDirect, existingOnly, takeoverConfirm, audit, false, updatedAt);
    }
}
