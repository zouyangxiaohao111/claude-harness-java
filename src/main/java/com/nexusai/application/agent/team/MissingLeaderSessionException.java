package com.nexusai.application.agent.team;

/**
 * <b>teammate 启动时取不到 Leader 真实会话键 ⇒ fail-loud</b>（2026-09-22 用户裁定：
 * 「将来若出现『携带 teammateIdentity 但没走 T1 父 TUC 通道』的新路径<b>直接报错</b>」）。
 *
 * <p><b>WHY 必须有这个类型（改前缺陷 · 改动前形态已读码复核）</b>：改前
 * {@code SpawnInProcess.spawnInProcessTeammate} 在 {@code SpawnContext} 未携带 Leader 会话时回落
 * {@code TaskService.getTaskListId(null, null)} —— 该方法的最终回退是<b>进程级共享 UUID</b>
 * （{@code TaskService.PROCESS_SESSION_ID}，永不返回 null/blank），中间还可能命中
 * {@code nexusai.team.name}（team 名）/ {@code nexusai.taskListId}（任务列表键）。于是「真 Leader
 * 会话取不到」被静默替换成一个<b>看起来像会话的伪造键</b>喂给 teammate：
 * <ul>
 *   <li>{@code TeammateIdentity.parentSessionId} / {@code loop.setTaskListId} 挂幻影会话；</li>
 *   <li>teammate 执行 TUC 的 sessionId 继承该伪造值 ⇒ CwdResolution / SessionStorage 按
 *       「未知会话」<b>在链路深处</b> fail-loud 抛（报错点离根因很远、无任何「Leader 会话缺失」
 *       上下文）；</li>
 *   <li>权限授权 / transcript 目录 / file-history 桶全部锚到不存在的会话上。</li>
 * </ul>
 * 这与本仓铁律「不许静默伪造一个看起来合法的会话键」（{@code SessionKeys.NO_SESSION} javadoc
 * 用户裁定）直接冲突。
 *
 * <p><b>边界（本异常只用于 teammate 这条路，⛔ 不是「全局 no-session 一律抛」）</b>：
 * 「确无会话」的合法降级路径（入站 MCP 子进程 / 无会话 plan provider / workflow worker /
 * standalone fork 子代理）仍走 {@link com.nexusai.common.SessionKeys#NO_SESSION} 哨兵与
 * {@code CwdResolution} 的命名出口 —— 本异常<b>不</b>触碰那些路径，只拦「teammate 启动」这一条
 * <b>结构上必然属于某个 Leader 会话</b>的链路（team lead + teammate 是会话内概念，
 * 对齐 CC 一进程=一会话：CC 的 teammate 直接复用 Leader 的 toolUseContext，
 * CCB {@code inProcessRunner.ts:892-902}）。
 *
 * <p><b>继承 {@code IllegalStateException} 的理由</b>：与 {@code CwdResolution} 家族
 * （{@code UnresolvedProjectRootException}）同族语义 —— 「本该有的链路前提不成立」= 数据链路异常，
 * 且不改变既有 {@code catch (IllegalStateException)} 吞点的语义面。
 *
 * @see SpawnInProcess#spawnInProcessTeammate
 */
public class MissingLeaderSessionException extends IllegalStateException {

    /** 出错的环节（人类可读链路名，如 {@code "SpawnInProcess.spawnInProcessTeammate"}）。 */
    private final String link;
    /** 期望拿到什么（哪个槽位、应当是什么）。 */
    private final String expected;
    /** 实际拿到了什么（原值 + 触发路径）。 */
    private final String actual;
    /** 如何修（可操作的下一步，供调用方/排障者直接照做）。 */
    private final String fix;

    public MissingLeaderSessionException(String link, String expected, String actual, String fix) {
        super("[Leader 会话缺失 · fail-loud] 环节=" + link
            + "；期望=" + expected
            + "；实际=" + actual
            + "；如何修=" + fix
            + "。⛔ 本路径不回落到进程级 UUID / team 名 / no-session 哨兵等伪造会话键"
            + "（2026-09-22 用户裁定：取不到就报错）。");
        this.link = link;
        this.expected = expected;
        this.actual = actual;
        this.fix = fix;
    }

    /** 出错环节（人类可读链路名）。 */
    public String link() {
        return link;
    }

    /** 期望值描述。 */
    public String expected() {
        return expected;
    }

    /** 实际值描述。 */
    public String actual() {
        return actual;
    }

    /** 修复指引。 */
    public String fix() {
        return fix;
    }
}
