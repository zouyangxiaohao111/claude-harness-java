package com.nexusai.application.session;

import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「该会话是否已被<b>真实用户</b>接管」的判定组件 —— 从 {@code SessionToolDisableConfig} 抽出的
 * 持久化读取职责（配置类不应承担查库）。
 *
 * <p><b>用途</b>：派生/平级会话默认禁用 {@code AskUserQuestion}；一旦真人在这条会话里发过消息，
 * 就不再默认禁用（下一次求值生效 = 模型下一轮请求的 tools 数组才变化）。
 *
 * <p><b>口径（严格白名单，判定不出来一律当「不是用户」）</b> —— 一条 {@code messages} 行必须同时满足：
 * <ol>
 *   <li>{@code role='user'}；</li>
 *   <li>{@code is_meta} 不是 TRUE；</li>
 *   <li>{@code author} 为空；</li>
 *   <li>{@code subtype} 为空；</li>
 *   <li>{@code queued_origin} 为空或 {@code 'busy-queued'}；</li>
 *   <li>{@code held_state} 为空；</li>
 *   <li>id 不是 {@code msg-slash-*}；</li>
 *   <li>content 非空。</li>
 * </ol>
 *
 * <p><b>各类内部注入为何被排除（逐条按真实写入点核对，⛔ 不是按文案猜）</b>：
 * <ul>
 *   <li><b>peer 来信 / 跨会话投递</b>：{@code MessageService.insertQueuedUserMessage}（由
 *       {@code CronIdleExecutor.handlePeerQueuedItem:1466-1472} 调用）写
 *       {@code is_meta=true} + {@code author=来源会话} + {@code subtype='agent_message'} +
 *       {@code queued_origin='peer'} —— 四重排除。</li>
 *   <li><b>hold 待确认来信</b>：{@code MessageService.insertHeldPeerMessage:1943-1957} 同一形状
 *       （多一个 {@code held_state='pending'}）。</li>
 *   <li><b>cron / 定时注入</b>：{@code CronIdleExecutor:1197-1218} 以 {@code isCron} 落
 *       {@code is_meta=true}。</li>
 *   <li><b>IM 渠道入站</b>：{@code ImInboundRouter.enqueueForAgent:252-257} 把入站消息包成
 *       {@code NotificationQueue} 项（{@code workload=WORKLOAD_CRON} + {@code isMeta=true}），
 *       执行时走上面同一条 cron 落库路径 ⇒ {@code role=user / is_meta=true / author=null /
 *       subtype=null / queued_origin=null}。⚠️ 它与真人桌面消息的<b>唯一</b>区别就是
 *       {@code is_meta}：{@code author/subtype/origin} 三者全为空，所以 is_meta 这一条必须留
 *       （去掉它就会把 IM 入站误判成真人接管）。</li>
 *   <li><b>teammate 唤醒注入</b>：{@code AutonomousAgentLoop.injectUserMessage:396-403} 只写
 *       进程内队列 + 内存 transcript，<b>不落 messages 表</b> ⇒ 天然不参与判定；teammate →
 *       主会话的 delivery 走 peer 形状（见上）。</li>
 *   <li><b>系统提醒 / 子代理回报</b>：{@code is_meta=true}。</li>
 * </ul>
 *
 * <p><b>数据源 = 已有 {@code messages} 表</b>（⛔ 不新增 session 字段 / 迁移），
 * 故判定结果跨重启稳定（无内存状态）。
 */
@Component
public class SessionTakeoverResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionTakeoverResolver.class);

    /** 消息表 mapper；null（无 Spring / 单测未注入）→ 一律按「未接管」处理。 */
    private MessageMapper messageMapper;

    /** Spring 构造（无参；mapper 由 setter 注入，未注入也能装配）。 */
    public SessionTakeoverResolver() {
    }

    /** 测试用构造：直接给定 mapper。 */
    public SessionTakeoverResolver(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    /**
     * Spring 注入 MessageMapper（可选；无 DB 的装配场景允许缺失）。
     *
     * @param messageMapper 消息 mapper
     */
    @Autowired(required = false)
    public void setMessageMapper(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    /**
     * 该会话是否已被真实用户接管（有过人在界面上发送的消息）。
     *
     * @param sessionId 会话键（{@code sess-xxx}）
     * @return true = 有过真实用户消息；mapper 未注入 / 读失败 / 无匹配 → false（保守）
     */
    public boolean hasRealUserMessage(String sessionId) {
        MessageMapper mapper = messageMapper;
        if (sessionId == null || mapper == null) {
            return false;
        }
        try {
            List<MessageRecord> rows = mapper.selectListByQuery(QueryWrapper.create().where(
                "session_id = ? AND role = ? AND (is_meta IS NULL OR is_meta != 1)",
                sessionId, "user"));
            if (rows == null || rows.isEmpty()) {
                return false;
            }
            for (MessageRecord m : rows) {
                if (countsAsRealUserMessage(m)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            // 判不出来 ≠ 用户发过（保守：派生会话继续默认禁 Ask）
            log.warn("会话真实用户消息判定失败（按未接管处理，派生会话仍禁 Ask）: 会话 '{}' 原因={}",
                sessionId, e.toString());
            return false;
        }
    }

    /**
     * 单行判定：这条 user 消息是否代表「人在界面上发送的输入」。口径见 {@link #hasRealUserMessage}。
     *
     * <p>抽成纯函数让它能被单测直接用真实行驱动（⛔ 不是先按 id/文案猜）。
     *
     * @param m 消息行（可 null）
     * @return true = 真实用户输入
     */
    static boolean countsAsRealUserMessage(MessageRecord m) {
        if (m == null || !"user".equals(m.getRole())) {
            return false;
        }
        // peer / cron / IM 入站 / 系统提醒 / 内部注入 / 子代理回报：全部 is_meta=true
        if (Boolean.TRUE.equals(m.getIsMeta())) {
            return false;
        }
        // peer 行 author=来源会话 id；系统通知 author=接收方会话；同会话投递 author=来源
        if (m.getAuthor() != null && !m.getAuthor().isBlank()) {
            return false;
        }
        // peer 行 subtype='agent_message'
        if (m.getSubtype() != null && !m.getSubtype().isBlank()) {
            return false;
        }
        // peer 行 'peer'；其它内部来源标记一律不认（只放行真实 busy 排队）
        String origin = m.getQueuedOrigin();
        if (origin != null && !origin.isBlank() && !"busy-queued".equals(origin)) {
            return false;
        }
        // 待确认 / 已拒绝的来信行
        if (m.getHeldState() != null && !m.getHeldState().isBlank()) {
            return false;
        }
        // slash 命令结果行（含 cron 触发）：is_meta=false 但属于命令输出，不是用户输入
        if (m.getId() != null && m.getId().startsWith("msg-slash-")) {
            return false;
        }
        return m.getContent() != null && !m.getContent().isBlank();
    }
}
