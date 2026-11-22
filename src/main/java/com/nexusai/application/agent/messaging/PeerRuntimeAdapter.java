package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.domain.settings.SettingsService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 跨会话运行期适配器（B 批）· 把 A 批的路由/注册表/策略接到本仓真实运行态上。
 *
 * <p><b>四个角色</b>：
 * <ol>
 *   <li>{@link PeerMessageRouter.DeliveryPort}：投递 → 唯一待消费队列 {@link NotificationQueue}；
 *       队列项 = {@code mode=prompt + workload="peer-message" + agentId=null + uuid=messageId +
 *       isMeta=true + skipSlashCommands=true + origin.kind="peer" + sessionId=目标 + peerEnvelope=信封}。
 *       ⛔ hold 绝不入队（由 router 在投递前分流到 held store，本类不碰）。</li>
 *   <li>{@link PeerSessionRegistry.StatusSource}：只读真实 {@code running/dispatching}，
 *       ⛔ 不另造真源。</li>
 *   <li>{@link PeerIdleSubscriptions.NoticeSink}：idle 通知投回源会话；<b>只在源仍 live 时投回</b>，
 *       已退出源不复活（订阅侧取出即删 ⇒ 至多一次）。</li>
 *   <li>{@link PeerMessageRouter.HeldPeerStore} 的持有者：消费前 revalidate 的非 ACCEPT 项回写 held
 *       （未审批不得入历史/入模型）；与 router 共用同一个 store 实例，HTTP 审批链读到的就是这里写的。</li>
 * </ol>
 *
 * <p><b>装配</b>（{@link #wire()}）：安装 deliveryPort / statusSource / noticeSink / 设置源
 * （{@code SettingsService#get().crossSessionInbound()}，每次现读，不缓存）与
 * {@link TaskSystemConfig} 动态部署门。未装配时 router 返回 {@code NOT_WIRED}——本类绝不假装成功。
 *
 * <p><b>认证边界（endpoint token · 已停用，对齐清单 T01）</b>：
 * <ul>
 *   <li><b>CC 的做法</b>（2.1.328 {@code bin/claude.exe}：{@code lnl/$ds/dnl/cnl/pnl}，偏移
 *       ~317878947 / ~317882292 区；注册表见 TS 源 {@code src/utils/concurrentSessions.ts:22,68-71}）：
 *       注册表落 {@code <CLAUDE_CONFIG_HOME>/sessions/<pid>.json}，token <b>单独落盘</b>
 *       {@code <pid>.<sha256(小写规范化管道路径)>.key}（目录 0700、文件 0600，内容
 *       {@code {peerToken, childToken, ...}}）；SendMessage 解析 token 与命名管道，用
 *       {@code pnl(token, identity)} 常量时间比对，防止别的进程冒用该会话地址
 *       （{@code requireLiveOwner} 时还要探 pid / pidDomain 判死主）。</li>
 *   <li><b>本仓现状</b>：单进程。{@link #deliver} 只按「目标代次 + 可投递 + 未 closing」校验，
 *       来源身份由服务端上下文（{@link PeerEnvelope#fromSessionId()} /
 *       {@link PeerEnvelope#fromAddress()}）生成，<b>token 未被任何生产路径校验</b>，因此
 *       {@link PeerSessionRegistry} 不再生成 token（句柄里的字段恒为空串）。</li>
 *   <li><b>何时恢复</b>：将来做多进程 / 多实例（跨进程投递、命名管道寻址）时，在投递入口补
 *       token + 通道归属校验，并恢复 {@code PeerSessionRegistry.register} 的 token 生成。</li>
 * </ul>
 */
@Component
public class PeerRuntimeAdapter implements PeerMessageRouter.DeliveryPort,
        PeerSessionRegistry.StatusSource, PeerIdleSubscriptions.NoticeSink,
        PeerMessageRouter.RejectionNotifier {

    private static final Logger log = LoggerFactory.getLogger(PeerRuntimeAdapter.class);

    /** 队列 workload 标记（peer 项的唯一判别符之一；另一判别符 = peerEnvelope 非 null）。 */
    public static final String WORKLOAD_PEER_MESSAGE = "peer-message";

    @Autowired(required = false)
    private NotificationQueue notificationQueue;

    /**
     * STOMP 模板（拒绝通知 → 发送方会话 stream topic；null = 未接线，只记日志）。
     *
     * <p>⛔ 不用 NotificationQueue 做这件事：入队会在发送方空闲时拉起一轮模型推理，
     * 等于「唤醒」已退出的会话 —— 拒绝通知只应是 UI 提示。
     */
    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private PeerSessionRegistry peerSessionRegistry;

    @Autowired(required = false)
    private PeerMessageRouter peerMessageRouter;

    @Autowired(required = false)
    private PeerInboundPolicy peerInboundPolicy;

    @Autowired(required = false)
    private PeerIdleSubscriptions peerIdleSubscriptions;

    @Autowired(required = false)
    private SettingsService settingsService;

    /** 会话服务：只读来源/目标会话标题快照（可 null ⇒ 标题 null，不阻断通知）。 */
    @Autowired(required = false)
    private com.nexusai.domain.session.SessionService sessionService;

    /**
     * 失败通知落库出口（接收侧准入丢弃 → 发送方会话一条 meta 通知；离线/重启后仍可见）。
     *
     * <p>对应 CC 的异步 {@code dropped} 回执：发送方先拿到成功 + message_id，接收侧准入失败
     * 之后才告知。本仓进程内同步可见，但仍坚持落库 —— 只推 STOMP 会在发送方离线时丢件。
     */
    @Autowired(required = false)
    private PeerFailureNotifier peerFailureNotifier;

    /** 自动循环刹车（CC {@code aVn}）：记录 starter，供发送侧判「无用户输入的持续回复」。 */
    @Autowired(required = false)
    private PeerLoopBrake peerLoopBrake;

    /**
     * 「最终有效权限模式」统一链（2026-11-20 E2E 缺陷 1）：注册表里没有该会话/没有模式时的
     * 入站判定读源。⛔ 不得只读 {@code sessions.permission_mode}（不回退 settings 会让只配全局
     * 权限模式的部署把默认自动投递全部转 hold）。
     */
    @Autowired(required = false)
    private com.nexusai.application.chat.EffectivePermissionModeResolver effectivePermissionModeResolver;

    /** 测试/接线用 setter（统一权限模式解析链）。 */
    public void setEffectivePermissionModeResolver(
            com.nexusai.application.chat.EffectivePermissionModeResolver resolver) {
        this.effectivePermissionModeResolver = resolver;
    }

    /**
     * 接收侧准入器（每个接收会话一个）：令牌桶 30 / 0.5s、同文去重 30s、队容 50。
     *
     * <p>键 = 接收会话 id（维度 = 接收会话 × 发送方；发送方键由信封的
     * {@code from:<addr>} 派生）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, PeerInboundAdmission> admissions =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 准入器时钟（测试注入可变时钟 ⇒ 用例不需要 sleep）。 */
    private volatile java.util.function.LongSupplier admissionClock = System::currentTimeMillis;

    /** 测试/接线用 setter（准入器时钟；⛔ 必须在首次 deliver 之前调用）。 */
    public void setAdmissionClock(java.util.function.LongSupplier clock) {
        this.admissionClock = clock == null ? System::currentTimeMillis : clock;
        admissions.clear();
    }

    /** 测试/接线用 setter（失败通知落库出口；null = 只记日志）。 */
    public void setPeerFailureNotifier(PeerFailureNotifier notifier) {
        this.peerFailureNotifier = notifier;
    }

    /** 测试/接线用 setter（自动循环刹车；null = 不记录 starter）。 */
    public void setPeerLoopBrake(PeerLoopBrake brake) {
        this.peerLoopBrake = brake;
    }

    /** 测试/接线用 setter（标题快照；null = 不解析）。 */
    public void setSessionService(com.nexusai.domain.session.SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 测试/接线用 setter（拒绝通知 UI 出口；null = 不推送，只记日志）。 */
    public void setMessagingTemplate(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** 部署门（application.yml）；空 = 未显式配置（回落系统属性/环境变量）。 */
    @Value("${nexusai.feature.messaging-beyond-own-agents:}")
    private String messagingBeyondOwnAgentsValue;

    /**
     * 容器注入的持久化 held store（{@link DbHeldPeerStore} · V322 / SQLite）；null = 无 DB（非 Spring 单测）。
     *
     * <p>⛔ 不得用内存实现顶掉它 —— 内存版重启丢件，且 C 批 HTTP 审批链读的是 router 持有的同一实例。
     */
    @Autowired(required = false)
    private PeerMessageRouter.HeldPeerStore heldPeerStore;

    /** 实际使用的 held store（{@link #wire()} 解析；与 router 同一实例，消费前回写与审批链同源）。 */
    private volatile PeerMessageRouter.HeldPeerStore heldStore;

    /** 无容器注入时的内存回落（与 router 默认实现同语义；仅供单测/无 DB 部署）。 */
    private final PeerMessageRouter.InMemoryHeldPeerStore inMemoryHeldStore =
            new PeerMessageRouter.InMemoryHeldPeerStore();

    @PostConstruct
    void wire() {
        // held store 解析：优先容器注入的持久化实现；注入缺失才回落到内存实例。
        // ⛔ 绝不无条件 setHeldStore(内存) —— 那会把 DbHeldPeerStore 顶掉（重启丢件）。
        PeerMessageRouter.HeldPeerStore store = heldPeerStore != null ? heldPeerStore : inMemoryHeldStore;
        heldStore = store;
        if (peerMessageRouter != null) {
            peerMessageRouter.setDeliveryPort(this);
            // router 侧对「已注入 DB store 却收到内存实现」另有忽略逻辑（双保险，见 PeerMessageRouter）
            peerMessageRouter.setHeldStore(store);
            // 拒绝通知出口（UI 事件；不触发模型推理、不唤醒发送方）
            peerMessageRouter.setRejectionNotifier(this);
            log.info("PeerRuntimeAdapter: deliveryPort installed (peer 投递 → NotificationQueue)；"
                + "heldStore={}（注入持久化={}）",
                store.getClass().getSimpleName(), heldPeerStore != null);
        }
        if (peerSessionRegistry != null) {
            peerSessionRegistry.setStatusSource(this);
        }
        if (peerIdleSubscriptions != null) {
            peerIdleSubscriptions.setNoticeSink(this);
        }
        if (peerInboundPolicy != null) {
            // 每次 decide/revalidate 现读 DB（不冻结构造时值）；null = 未配置 ⇒ 运行时 auto。
            peerInboundPolicy.setSettingsSource(receiverSessionId -> {
                SettingsService svc = settingsService;
                return svc == null ? null : svc.get().crossSessionInbound();
            });
        }
        installFeatureGate();
    }

    /** 部署门：yml 显式值优先（非空 ⇒ 冻结该布尔），否则不安装（回落系统属性/环境变量）。 */
    private void installFeatureGate() {
        Boolean configured = parseBooleanOrNull(messagingBeyondOwnAgentsValue);
        if (configured == null) {
            return;
        }
        TaskSystemConfig.installMessagingBeyondOwnAgentsSource(() -> configured);
    }

    private static Boolean parseBooleanOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    // ══════════════════════════ DeliveryPort ══════════════════════════

    @Override
    public PeerMessageRouter.Receipt deliver(String targetSessionId, long targetEndpointEpoch,
                                             PeerEnvelope envelope, String body) {
        String messageId = envelope == null ? null : envelope.messageId();
        if (envelope == null || messageId == null || messageId.isBlank()) {
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.NOT_DELIVERED,
                messageId, "missing peer envelope", null);
        }
        NotificationQueue queue = notificationQueue;
        if (queue == null) {
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.NOT_WIRED,
                messageId, "notification queue is not wired", null);
        }
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.NOT_DELIVERED,
                messageId, "missing target session", null);
        }
        // 接收侧准入（CC Es()，偏移 325455000）：入队之前判令牌桶 / 同文去重 / 收件队列上限。
        // 顺序 = hop（路由层已判，这里传 null 跳过）→ 去重 → 令牌桶 → 队容。
        PeerInboundAdmission admission = admissionFor(targetSessionId);
        PeerInboundAdmission.Decision decision = admission.admit(
            new PeerInboundAdmission.Input(senderKeyOf(envelope), body,
                queue.countPeerQueued(targetSessionId)));
        if (!decision.admitted()) {
            return drop(targetSessionId, envelope, decision.reason());
        }
        // 唯一待消费队列项：peer 语义全部由 peerEnvelope 承载（⛔ 不写 held、不写历史）。
        NotificationQueue.QueueItem item = new NotificationQueue.QueueItem(
            body, NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null /* agentId=null：主线程 */, messageId /* uuid=messageId：同 id 幂等 */,
            true /* isMeta：UI 隐藏、模型可见 */, WORKLOAD_PEER_MESSAGE,
            true /* skipSlashCommands：peer 正文是纯文本，"/clear" 不得当命令 */,
            new NotificationQueue.MessageOrigin("peer", null),
            targetSessionId, null, null, null, null, null, null, envelope);
        // 队列上限在同一把锁内二次确认（并发入队不得越过 50，CC queue-full）。
        if (!queue.enqueuePeer(item, PeerRateLimits.MAX_QUEUED_PEER_MESSAGES)) {
            return drop(targetSessionId, envelope, PeerRateLimits.Reason.QUEUE_FULL);
        }
        // 记录 starter（CC mea）：本会话收到的第一条合格来信 origin —— 刹车判定的起点。
        PeerLoopBrake brake = peerLoopBrake;
        if (brake != null) {
            brake.onPeerOrigin(targetSessionId, envelope.fromSessionId(),
                java.util.Objects.equals(envelope.fromSessionId(), targetSessionId),
                true /* 进程内跨会话 = 已证同机 */, false /* 非子代理发送 */, false /* 非 session-task */,
                envelope.hopChain());
        }
        if (log.isInfoEnabled()) {
            log.info("PeerRuntimeAdapter: peer 已入队 target={} endpointEpoch={} messageId={} from={} chars={}",
                targetSessionId, targetEndpointEpoch, messageId, envelope.fromSessionId(),
                body == null ? 0 : body.length());
        }
        return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.ACCEPTED, messageId,
            "enqueued for target session " + targetSessionId, null);
    }

    /**
     * 接收侧准入失败 ⇒ <b>dropped</b>（CC 异步回执）：给发送方写一条失败通知 + STOMP 即时提示。
     *
     * <p>reason 与 CC 原文一一对应（{@code rate-limited} / {@code duplicate} / {@code queue-full}）；
     * 通知落库失败不回滚「拒绝」本身（消息确实没进队列）。
     */
    private PeerMessageRouter.Receipt drop(String targetSessionId, PeerEnvelope envelope,
                                           PeerRateLimits.Reason reason) {
        String messageId = envelope.messageId();
        log.warn("PeerRuntimeAdapter: 接收侧准入丢弃 target={} messageId={} from={} reason={}"
                + "（CC: {}）", targetSessionId, messageId, envelope.fromSessionId(),
            reason.wire(), PeerRateLimits.textOf(reason));
        PeerFailureNotifier sink = peerFailureNotifier;
        if (sink == null) {
            log.info("PeerRuntimeAdapter: 丢弃通知未落库（PeerFailureNotifier 未接线）messageId={}",
                messageId);
        } else {
            try {
                sink.notifyFailure(new PeerFailureNotifier.FailureNotice(
                    messageId, envelope.fromSessionId(), targetSessionId,
                    PeerSessionTitles.resolve(sessionService, targetSessionId),
                    reason.wire()));
            } catch (RuntimeException e) {
                log.warn("PeerRuntimeAdapter: 丢弃通知异常（丢弃已生效，仅记日志）messageId={} err={}",
                    messageId, e.toString());
            }
        }
        return new PeerMessageRouter.Receipt(PeerMessageRouter.Status.DROPPED, messageId,
            PeerRateLimits.textOf(reason), null);
    }

    /** 取/建该接收会话的准入器（维度 = 接收会话 × 发送方）。 */
    private PeerInboundAdmission admissionFor(String targetSessionId) {
        return admissions.computeIfAbsent(targetSessionId,
            key -> new PeerInboundAdmission(admissionClock));
    }

    /**
     * 发送方键（CC {@code senderKey: from !== "unknown" ? `from:${from}` : `pid:${pid}`}）：
     * 本仓来源地址由服务端从 live registry 生成，恒可用 ⇒ 只用 {@code from:<addr>}。
     */
    private static String senderKeyOf(PeerEnvelope envelope) {
        String from = envelope.fromAddress();
        if (from == null || from.isBlank()) {
            from = envelope.fromSessionId();
        }
        return "from:" + from;
    }

    // ══════════════════════════ StatusSource ══════════════════════════

    /**
     * 真实运行态：running / dispatching 都算 RUNNING（与 {@code LlmAgentLoop.isSessionActive} 同义），
     * 其余 IDLE。⛔ 不引入第二份真源。
     */
    @Override
    public PeerSessionRegistry.Status status(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return PeerSessionRegistry.Status.UNKNOWN;
        }
        try {
            if (LlmAgentLoop.isSessionRunning(sessionId) || LlmAgentLoop.isSessionDispatching(sessionId)) {
                return PeerSessionRegistry.Status.RUNNING;
            }
            return PeerSessionRegistry.Status.IDLE;
        } catch (RuntimeException e) {
            return PeerSessionRegistry.Status.UNKNOWN;
        }
    }

    // ══════════════════════════ NoticeSink ══════════════════════════

    /**
     * idle 通知投回源会话：只在<b>源仍 live（同代次）</b>时入队；已退出源静默丢弃（不复活）。
     *
     * <p>通知项同样是 peer 项（peerEnvelope 非 null）⇒ 走同一投影 / 同一落库出口；
     * uuid 由 messageId 派生 ⇒ 重复投递同 id 幂等。订阅侧已「取出即删」，至多一次。
     */
    @Override
    public void onNotice(PeerIdleSubscriptions.IdleNotice notice) {
        if (notice == null || notice.sourceSessionId() == null) {
            return;
        }
        PeerSessionRegistry registry = peerSessionRegistry;
        if (registry == null
                || !registry.isLive(notice.sourceSessionId(), notice.sourceEndpointEpoch())) {
            log.debug("PeerRuntimeAdapter: idle notice 源已退出/代次不符，丢弃（不复活）source={} epoch={} kind={}",
                notice.sourceSessionId(), notice.sourceEndpointEpoch(), notice.kind());
            return;
        }
        NotificationQueue queue = notificationQueue;
        if (queue == null) {
            log.warn("PeerRuntimeAdapter: NotificationQueue 未接线，idle notice 无法投回 source={}",
                notice.sourceSessionId());
            return;
        }
        // 通知行的「来源」= 刚空闲的那个会话（target）⇒ 标题取它的会话记录；
        // 取不到为 null（⛔ 不伪造：不拿 session id 冒充标题）。
        String targetTitle = PeerSessionTitles.resolve(sessionService, notice.targetSessionId());
        PeerEnvelope noticeEnvelope;
        try {
            noticeEnvelope = new PeerEnvelope(
                "peer-idle-" + notice.messageId(),
                notice.targetSessionId(),
                notice.targetSessionId(),
                targetTitle != null ? targetTitle : notice.targetSessionId(),
                null,
                List.of(PeerEnvelope.hopToken(notice.targetSessionId())),
                notice.targetEndpointEpoch(),
                null,
                false,
                null,
                targetTitle);
        } catch (RuntimeException e) {
            log.warn("PeerRuntimeAdapter: idle notice 信封构造失败（丢弃，不伪造来源）: messageId={} err={}",
                notice.messageId(), e.toString());
            return;
        }
        queue.enqueue(new NotificationQueue.QueueItem(
            notice.render(), NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, noticeEnvelope.messageId(), true, WORKLOAD_PEER_MESSAGE, true,
            new NotificationQueue.MessageOrigin("peer", null),
            notice.sourceSessionId(), null, null, null, null, null, null, noticeEnvelope));
        log.info("PeerRuntimeAdapter: idle notice 投回 source={} kind={} messageId={}（至多一次，源仍 live）",
            notice.sourceSessionId(), notice.kind(), notice.messageId());
    }

    // ══════════════════════════ 消费前复核 / held ══════════════════════════

    /**
     * 消费前复核（与发送时同一判据）：设置/模式可能已变化。
     *
     * <p><b>服务端一次性批准标记</b>（{@link PeerEnvelope#preApproved()}，只有
     * {@link PeerMessageRouter#approveHeld} 成功路径能设置）：跳过策略重判（否则 hold 设置下
     * 用户已批准的消息又被判 HOLD，回写时因行已是终态而静默丢弃 —— 2026-11-20 E2E 丢件根因），
     * 只校验<b>目标仍在线且代次一致</b>。该标记只用于「消费期不重判策略」，⛔ 不代表 held 行存在
     * 中间态（严格 CC 语义：批准即结算，行已 accepted）。
     * 不满足时返回 {@code HOLD + TARGET_NOT_LIVE}：调用方回写时行已是终态 ⇒ 只记 ERROR（不丢正文）。
     *
     * @param envelope          服务端信封（非模型自报）
     * @param receiverSessionId 接收方会话
     * @return 判定；未接线（policy 缺失）⇒ 按 HOLD 处理（绝不放行）
     */
    public PeerInboundPolicy.Outcome revalidateForConsumption(PeerEnvelope envelope,
                                                             String receiverSessionId) {
        if (envelope == null || receiverSessionId == null) {
            return new PeerInboundPolicy.Outcome(PeerInboundPolicy.Decision.HOLD,
                PeerInboundPolicy.Reason.INVALID_SETTING, "missing envelope or receiver");
        }
        if (envelope.preApproved()) {
            return revalidatePreApproved(envelope, receiverSessionId);
        }
        PeerInboundPolicy policy = peerInboundPolicy;
        if (policy == null) {
            return new PeerInboundPolicy.Outcome(PeerInboundPolicy.Decision.HOLD,
                PeerInboundPolicy.Reason.INVALID_SETTING, "peer inbound policy is not wired");
        }
        PermissionMode receiverMode = currentReceiverMode(receiverSessionId);
        boolean selfSent = Objects.equals(envelope.fromSessionId(), receiverSessionId);
        return policy.revalidate(new PeerInboundPolicy.Input(
            receiverSessionId, null, envelope.fromMode(), receiverMode, selfSent));
    }

    /**
     * 已批准消息的消费前复核：跳过入站策略，只校验目标在线 + 代次。
     *
     * <p>拒绝路径按「目标不在线」处理（HOLD + {@code TARGET_NOT_LIVE}），调用方
     * {@link #holdRevalidated} 回写时行已是终态（批准即结算）⇒ 只记 ERROR，不会复活成 pending。
     */
    private PeerInboundPolicy.Outcome revalidatePreApproved(PeerEnvelope envelope,
                                                            String receiverSessionId) {
        PeerSessionRegistry registry = peerSessionRegistry;
        PeerSessionRegistry.LiveSession target = registry == null ? null
            : registry.resolve(receiverSessionId).orElse(null);
        if (target == null || !target.accepting() || target.closing()) {
            return new PeerInboundPolicy.Outcome(PeerInboundPolicy.Decision.HOLD,
                PeerInboundPolicy.Reason.TARGET_NOT_LIVE,
                "approved message: target is no longer live: " + receiverSessionId);
        }
        Long approvedEpoch = envelope.preApprovedTargetEpoch();
        if (approvedEpoch != null && approvedEpoch >= 0
            && approvedEpoch != target.endpointEpoch()) {
            return new PeerInboundPolicy.Outcome(PeerInboundPolicy.Decision.HOLD,
                PeerInboundPolicy.Reason.TARGET_NOT_LIVE,
                "approved message: target endpoint generation changed (approved=" + approvedEpoch
                    + " current=" + target.endpointEpoch() + ")");
        }
        return new PeerInboundPolicy.Outcome(PeerInboundPolicy.Decision.ACCEPT,
            PeerInboundPolicy.Reason.PRE_APPROVED,
            "user pre-approved this message; inbound policy is not re-evaluated");
    }

    /**
     * 接收方当前有效权限模式（统一解析链）· 2026-11-20 E2E 缺陷 1。
     *
     * <p><b>顺序</b>：
     * <ol>
     *   <li>运行期注册表（登记时已写入「最终有效模式」：per-call ?? 会话列 ?? settings ?? default）；
     *       live endpoint 的模式是<b>当前轮真实生效值</b>，优先。</li>
     *   <li>注册表没登记 / 模式为 null ⇒ 用同一解析链（{@link #effectivePermissionModeResolver}）
     *       按会话记录 + settings 现算。⛔ 不回落到「只读 {@code sessions.permission_mode}」。</li>
     * </ol>
     * 两者都没有（未接线/非 Spring 单测）→ null ⇒ 策略判 UNKNOWN 模式类（hold），fail-closed。
     */
    private PermissionMode currentReceiverMode(String receiverSessionId) {
        PeerSessionRegistry registry = peerSessionRegistry;
        PermissionMode fromRegistry = registry == null ? null
            : registry.resolve(receiverSessionId)
                .map(PeerSessionRegistry.LiveSession::permissionMode)
                .orElse(null);
        if (fromRegistry != null) {
            return fromRegistry;
        }
        com.nexusai.application.chat.EffectivePermissionModeResolver resolver =
            effectivePermissionModeResolver;
        if (resolver == null) {
            return null;
        }
        try {
            return resolver.resolve(receiverSessionId, null);
        } catch (RuntimeException e) {
            log.debug("PeerRuntimeAdapter: 接收方模式统一链解析失败（按未知处理）session={} err={}",
                receiverSessionId, e.toString());
            return null;
        }
    }

    /**
     * 把消费前复核非 ACCEPT 的项写回 held（未审批不得入历史、不唤醒）。
     *
     * <p><b>⛔ 不得静默丢弃</b>：回写走 {@link PeerMessageRouter.HeldPeerStore#hold}
     * 的状态校验（不再是 INSERT OR IGNORE）—— 已存在 pending 的行保持原状态；
     * 若行已是终态（accepted/rejected/expired）则不回退，记 ERROR 并留下可识别结果
     * （严格 CC 语义下批准即结算，已批准消息的行在批准时已是终态）。
     *
     * @param targetSessionId 接收方会话
     * @param envelope        原信封
     * @param body            原文
     * @param outcome         判定（HOLD/REFUSE 理由）
     * @return true = 已保留为 pending；false = 行已是终态、<b>未回退</b>（已记 ERROR）
     */
    public boolean holdRevalidated(String targetSessionId, PeerEnvelope envelope, String body,
                                   PeerInboundPolicy.Outcome outcome) {
        if (envelope == null || targetSessionId == null || outcome == null) {
            return false;
        }
        long epoch = 0L;
        PeerSessionRegistry registry = peerSessionRegistry;
        if (registry != null) {
            epoch = registry.resolve(targetSessionId)
                .map(PeerSessionRegistry.LiveSession::endpointEpoch).orElse(0L);
        }
        PeerMessageRouter.HeldPeerStore store = heldStore;
        if (store == null) {
            store = inMemoryHeldStore;
        }
        boolean kept = store.hold(new PeerMessageRouter.HeldMessage(envelope.messageId(),
            targetSessionId, epoch, envelope, body, outcome.reason(), Instant.now()));
        if (kept) {
            log.info("PeerRuntimeAdapter: 消费前复核非 ACCEPT → 回写 held 保留（行保持 pending）"
                    + " target={} messageId={} preApproved={} reason={} detail={}",
                targetSessionId, envelope.messageId(), envelope.preApproved(), outcome.reason(),
                outcome.detail());
        } else {
            // 行已是终态（accepted/rejected/expired）：不回退，但必须留痕（可识别结果，不假装成功）。
            log.error("PeerRuntimeAdapter: 消费前复核非 ACCEPT 但 held 行已是终态，未回退"
                    + "（该消息不会进入历史）target={} messageId={} preApproved={} reason={} detail={}",
                targetSessionId, envelope.messageId(), envelope.preApproved(), outcome.reason(),
                outcome.detail());
        }
        return kept;
    }

    /**
     * [idle-then-drain 去重] 该 messageId 是否<b>已消费落地</b>（held 行 accepted）。
     *
     * <p><b>WHY</b>：空闲路径（{@code CronIdleExecutor.handlePeerQueuedItem}）消费一条已批准的 peer 项
     * 时先落库（行在批准时已是 accepted），随后以
     * {@code RunRequest.peerExecutionOrigin} 起轮；{@code LlmAgentLoop.doRun} 会把同一条消息重建为
     * 本回合首条 prompt 重新入队 —— 而 {@link PeerExecutionOrigin} 不含一次性批准标记，重建信封
     * {@code preApproved=false}。运行中 drain 若仍按策略复核，hold 设置下会误判 HOLD → 回写命中
     * accepted 终态行 → 误报 ERROR，且消息进不了本轮上下文。
     *
     * <p>这里返回的是<b>权威落库状态</b>（不是进程内队列标记）：批准即结算 ⇒ 投递成功时行已是
     * accepted；未投递（批准失败/未批准）⇒ 返回 false，drain 仍走常规复核。
     *
     * @param messageId 消息 id（= PeerEnvelope.messageId）
     * @return true = 已 accepted（调用方应跳过策略重判与 markConsumed）
     */
    public boolean isMessageConsumed(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        PeerMessageRouter.HeldPeerStore store = heldStore;
        if (store == null) {
            store = inMemoryHeldStore;
        }
        return store.isConsumed(messageId);
    }

    /**
     * 消费落地（严格 CC 语义下<b>不再是状态转换</b>）：批准即结算 ⇒ held 行在
     * {@link PeerMessageRouter#approveHeld} 投递成功时已是 {@code accepted}，消息真正写入
     * 会话历史时无需再改行状态。
     *
     * <p>本方法保留为消费点的一致性校验：只对带一次性批准标记的信封生效，读权威落库状态；
     * 若行不是 accepted（行被删/被别的路径重建）只记 WARN，⛔ 不改任何状态、不影响消息本身。
     *
     * @param envelope 被消费的服务端信封（队列项上原样携带）
     */
    public void markConsumed(PeerEnvelope envelope) {
        if (envelope == null || !envelope.preApproved()) {
            return;
        }
        PeerMessageRouter.HeldPeerStore store = heldStore;
        if (store == null) {
            return;
        }
        String status = store.statusOf(envelope.messageId());
        if (PeerMessageRouter.HeldMessage.STATUS_ACCEPTED.equals(status)) {
            log.debug("PeerRuntimeAdapter: 消费落地校验通过（批准即结算 → accepted）messageId={}",
                envelope.messageId());
        } else {
            log.warn("PeerRuntimeAdapter: 消费落地但 held 行状态=" + status
                + "（期望 accepted；批准即结算，⛔ 不回写状态）messageId={}", envelope.messageId());
        }
    }

    /**
     * peer 行的 structuredOutput 投影（落库 / message.insert / 与 LlmAgentLoop 运行中投影同形）。
     *
     * @param origin  服务端类型化来源
     * @param summary UI 摘要
     * @return {@code { peer_source: {...}, agent_summary: ... }}
     */
    public static Map<String, Object> structuredOutput(PeerExecutionOrigin origin, String summary) {
        Map<String, Object> peerSource = new LinkedHashMap<>();
        peerSource.put("sourceSessionId", origin.sourceSessionId());
        peerSource.put("sourceTitle", origin.sourceTitle());
        peerSource.put("sourceKind", origin.sourceKind());
        peerSource.put("messageId", origin.messageId());
        peerSource.put("endpointGeneration", origin.sourceEndpointEpoch());
        peerSource.put("fromMode", origin.fromMode());
        peerSource.put("from", origin.fromAddress());
        peerSource.put("hopChain", origin.hopChain());
        // [peer-admission] 前端卡片：这条来信是「自动通过」还是「手动批准」，以及发送方是否声明了模式
        //   （fromMode 缺失 = 未声明，⛔ 不补成 default）。值来自服务端信封，⛔ 不来自正文/工具输入。
        peerSource.put("admission", origin.admission());
        peerSource.put("admissionReason", origin.admissionReason());
        Map<String, Object> so = new LinkedHashMap<>();
        so.put("peer_source", java.util.Collections.unmodifiableMap(peerSource));
        so.put("agent_summary", summary);
        return java.util.Collections.unmodifiableMap(so);
    }

    /** 服务端信封 → 类型化来源（消费侧唯一入口；标题取信封上的来源会话标题快照）。 */
    public static PeerExecutionOrigin originOf(PeerEnvelope envelope) {
        // ⛔ 不再把 fromName（发送方展示名，如 "main"）当标题：那是另一种字段，冒充标题 = 伪造。
        return PeerExecutionOrigin.fromEnvelope(envelope, envelope.sourceTitle(),
            PeerExecutionOrigin.SOURCE_KIND_PEER);
    }

    // ══════════════════════════ RejectionNotifier ══════════════════════════

    /**
     * 拒绝通知 → 发送方会话 stream topic（只做 UI 展示）。
     *
     * <p><b>可达性复核</b>：router 已判过一次；这里再按 live 注册表确认（双保险），
     * 发送方未登记 / closing ⇒ 不推送、只记日志（⛔ 不唤醒）。
     * <p><b>失败不伪装</b>：模板缺失 / convertAndSend 抛异常 → false + 日志；拒绝本身已生效。
     */
    @Override
    public boolean notifyRejected(PeerMessageRouter.RejectionNotice notice) {
        if (notice == null || notice.sourceSessionId() == null
            || notice.sourceSessionId().isBlank()) {
            return false;
        }
        PeerSessionRegistry registry = peerSessionRegistry;
        if (registry != null) {
            PeerSessionRegistry.LiveSession live = registry
                .resolve(notice.sourceSessionId()).orElse(null);
            if (live == null || !live.accepting() || live.closing()) {
                log.info("PeerRuntimeAdapter: 拒绝通知不投递（发送方已退出/不可达）source={} messageId={}",
                    notice.sourceSessionId(), notice.messageId());
                return false;
            }
        }
        SimpMessagingTemplate template = messagingTemplate;
        if (template == null) {
            log.warn("PeerRuntimeAdapter: 拒绝通知未投递（STOMP 模板不可用）source={} messageId={}",
                notice.sourceSessionId(), notice.messageId());
            return false;
        }
        String destination = "/topic/sessions/" + notice.sourceSessionId() + "/stream";
        try {
            template.convertAndSend(destination, new PeerMessageRejectedEvent(notice));
            log.info("PeerRuntimeAdapter: 拒绝通知已推送 source={} messageId={} target={}",
                notice.sourceSessionId(), notice.messageId(), notice.targetSessionId());
            return true;
        } catch (RuntimeException e) {
            log.warn("PeerRuntimeAdapter: 拒绝通知推送失败（拒绝已生效，仅记日志）source={} messageId={} err={}",
                notice.sourceSessionId(), notice.messageId(), e.toString());
            return false;
        }
    }

    /** 是否已接线（投递端口 + 队列都在）。 */
    public boolean isWired() {
        return notificationQueue != null && peerMessageRouter != null;
    }
}
