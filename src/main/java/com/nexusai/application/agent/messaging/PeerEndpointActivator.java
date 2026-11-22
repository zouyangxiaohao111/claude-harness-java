package com.nexusai.application.agent.messaging;

/**
 * 会话记录 → 运行期 endpoint 的按需登记缝（2026-11-20 真实 E2E 缺陷 2）。
 *
 * <h2>为什么存在</h2>
 * <p>产品口径（用户裁定）：「会话记录在即可投递并唤醒，只有会话不存在 / 已删除才拒」。
 * 但运行期注册表（{@link PeerSessionRegistry}）只在会话<b>起轮</b>时登记 —— 重启后注册表为空，
 * 磁盘里仍存在的会话会被判成 {@code No agent named ... reachable}，用户/模型误以为目标消失。
 * 故收件人解析在注册表 miss 时调用本接口，让实现方按 DB 记录补登记 endpoint。
 *
 * <h2>为什么不是第二个真源</h2>
 * <p>实现必须走会话服务自己的登记链（与首次起轮同一条：标题快照 + 有效权限模式 + 句柄记账），
 * 不得在解析侧另建 endpoint 记录 —— 否则关闭/删除会话时无法按代次注销（幽灵 endpoint）。
 *
 * <p>生产实现 = {@code ChatPeerEndpointActivator}（独立 {@code @Component}，内部委托 {@code ChatService}）。
 * ⛔ 不要直接让 {@code ChatService} 实现本接口：它带 {@code @Async}，实现接口会让 Spring 切到
 * JDK 动态代理，bean 类型退化成接口类型，按具体类型注入 ChatService 的调用方启动失败
 * （2026-11-20 实测阻断）。解析侧只通过
 * {@link org.springframework.beans.factory.ObjectProvider} 惰性取用，避免
 * {@code ChatService → ToolRegistry → SendMessageTool → 本接口实现} 的循环依赖。
 */
public interface PeerEndpointActivator {

    /** 按需登记结果。 */
    enum Result {
        /** 登记成功且当前可投递（accepting + 未 closing）。 */
        ACTIVATED,
        /** 会话记录不存在（从未创建 / 已删除）。⛔ 只有这一种情况才回 not-found。 */
        NOT_FOUND,
        /** 会话记录存在但当前无法登记（会话表不可读 / 注册表未接线 / 登记后不可投递）。 */
        UNAVAILABLE
    }

    /**
     * 按 sessionId 补登记 endpoint。
     *
     * @param sessionId 目标会话 id
     * @return 结果（永不 null）
     */
    Result activateSession(String sessionId);

    /**
     * 按展示名（会话标题）补登记 endpoint（重启后模型只知道标题时用）。
     *
     * <p>匹配规则与 ListAgents 地址名一致：标题 {@link PeerAddressing#normalizeName} 后相等。
     * 命中多条时全部登记（由解析侧判 AMBIGUOUS，绝不随机挑）。
     *
     * @param name 收件名（原始串；内部归一化）
     * @return ACTIVATED = 至少一个同名会话已登记；NOT_FOUND = 没有同名会话记录
     */
    Result activateSessionsByName(String name);
}
