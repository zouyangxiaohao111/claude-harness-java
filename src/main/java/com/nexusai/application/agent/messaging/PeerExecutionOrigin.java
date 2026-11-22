package com.nexusai.application.agent.messaging;

import java.util.List;
import java.util.Objects;

/**
 * 跨会话 peer 回合的<b>可信执行来源</b>（服务端类型化载体）· B 批冻结契约。
 *
 * <p><b>唯一生产入口</b>：{@link PeerRuntimeAdapter} 在投递时从服务端生成的
 * {@link PeerEnvelope} 构造。⛔ HTTP {@code requestExtras}、模型正文、前端自报、
 * {@code structuredOutput} 里的同名字段<b>都不是</b>信任根；这里只接受服务端已解析的
 * {@code PeerEnvelope}。
 *
 * <p><b>传播链</b>：{@code QueueItem.peerEnvelope} → 运行中 drain / 空闲
 * {@code CronIdleExecutor} → {@link com.nexusai.application.agent.RunRequest#peerExecutionOrigin}
 * → base {@code ToolUseContext} → per-turn TUC（withXxx / copyWith 原样透传）→
 * {@code SubagentTool} 派生 agent；C 批的 {@code PeerActionGuard} 在
 * {@code HookPermissionResolver.resolve} 首段、{@code PermissionPipeline.check} 入口、
 * {@code StreamingToolExecutor} 最终 effectiveCall 后统一读
 * {@code ctx.peerExecutionOrigin()}。
 *
 * <p><b>清污语义</b>：用户正常下一轮的 {@code RunRequest} 为 {@code null} ⇒ 新 run 的 base /
 * per-turn TUC 也为 null；本对象绝不跨用户轮复用。
 *
 * <p><b>安全边界</b>：不携带 endpoint token（token 只存 A 的 {@code PeerSessionRegistry}）；
 * 不把来源标题用于路由 / 授权；不继承发送方权限（目标轮最终有效 mode 为准）。
 *
 * @param sourceSessionId     稳定来源会话 id（服务端解析；{@code [A-Za-z0-9_-]{1,80}}）
 * @param sourceTitle         来源会话标题快照（不可信展示文本）
 * @param sourceKind          来源类型（默认 {@link #SOURCE_KIND_PEER}）
 * @param messageId           稳定消息 id（= 队列 uuid = 落库主键）
 * @param sourceEndpointEpoch 来源 endpoint 代次（服务端生成）
 * @param fromMode            发送方 attest 的 CC 权限模式串（可 null）
 * @param fromAddress         可回信地址（name 或 name [ref]）
 * @param hopChain            防循环链（24 hex 段，<= {@link PeerEnvelope#MAX_HOP_CHAIN} 格式上限；
 *                            投递准入阈值在 {@link PeerEnvelope#MAX_CHAIN_LENGTH} /
 *                            {@link PeerEnvelope#MAX_SELF_HOPS}）
 * @param admission           入站准入方式（auto / approved；null = 未知）· 从服务端信封原样透传
 * @param admissionReason     准入理由（mode-match / self-sent / user-approved / explicit-accept；可 null）
 */
public record PeerExecutionOrigin(
        String sourceSessionId,
        String sourceTitle,
        String sourceKind,
        String messageId,
        long sourceEndpointEpoch,
        String fromMode,
        String fromAddress,
        List<String> hopChain,
        String admission,
        String admissionReason
) {

    /** 默认来源类型：本机另一个 Nexus 会话。 */
    public static final String SOURCE_KIND_PEER = "peer-session";

    public PeerExecutionOrigin {
        Objects.requireNonNull(sourceSessionId, "sourceSessionId");
        if (!PeerEnvelope.FROM_SESSION.matcher(sourceSessionId).matches()) {
            throw new IllegalArgumentException("invalid sourceSessionId: " + sourceSessionId);
        }
        Objects.requireNonNull(messageId, "messageId");
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is blank");
        }
        sourceKind = (sourceKind == null || sourceKind.isBlank()) ? SOURCE_KIND_PEER : sourceKind;
        List<String> copy = hopChain == null ? List.of() : List.copyOf(hopChain);
        if (copy.size() > PeerEnvelope.MAX_HOP_CHAIN) {
            throw new IllegalArgumentException("hop-chain too long: " + copy.size());
        }
        for (String hop : copy) {
            if (hop == null || !PeerEnvelope.HOP_SEGMENT.matcher(hop).matches()) {
                throw new IllegalArgumentException("invalid hop segment: " + hop);
            }
        }
        hopChain = copy;
        // 只认两个已知准入值（与 PeerEnvelope 同一归一规则）：未知 ⇒ null（不默认成 auto）。
        admission = PeerEnvelope.ADMISSION_AUTO.equals(admission) ? PeerEnvelope.ADMISSION_AUTO
            : PeerEnvelope.ADMISSION_APPROVED.equals(admission) ? PeerEnvelope.ADMISSION_APPROVED : null;
        admissionReason = admissionReason == null || admissionReason.isBlank()
            ? null : admissionReason.trim();
    }

    /**
     * 8 参兼容构造（{@code admission = null, admissionReason = null}）。
     *
     * <p>既有 {@code new PeerExecutionOrigin(...)} 调用方（守卫/测试）零改动：
     * 未显式准入的 origin 恒为「未知」，⛔ 不会被当成自动通过。
     */
    public PeerExecutionOrigin(String sourceSessionId, String sourceTitle, String sourceKind,
                               String messageId, long sourceEndpointEpoch, String fromMode,
                               String fromAddress, List<String> hopChain) {
        this(sourceSessionId, sourceTitle, sourceKind, messageId, sourceEndpointEpoch, fromMode,
            fromAddress, hopChain, null, null);
    }

    /**
     * 从服务端生成的信封建立可信来源（唯一生产入口）。
     *
     * @param envelope    投递时由 router 生成的信封（非模型自报）
     * @param sourceTitle 展示快照（不可信文本，仅 UI / 信封 from-name）
     * @param sourceKind  来源类型（null/空白 → {@link #SOURCE_KIND_PEER}）
     */
    public static PeerExecutionOrigin fromEnvelope(PeerEnvelope envelope, String sourceTitle, String sourceKind) {
        Objects.requireNonNull(envelope, "envelope");
        return new PeerExecutionOrigin(
                envelope.fromSessionId(),
                sourceTitle,
                sourceKind,
                envelope.messageId(),
                envelope.sourceEndpointEpoch(),
                envelope.fromMode(),
                envelope.fromAddress(),
                envelope.hopChain(),
                // 准入方式随信封原样透传：held 行重建 / 批准重投后仍是 approved，⛔ 不回落成 auto。
                envelope.admission(),
                envelope.admissionReason());
    }

    /**
     * 还原信封供共享 formatter 使用（发送层 / 首次模型请求同一 formatter）。
     *
     * @param summary        UI 摘要（可 null）
     * @param notifyWhenIdle idle 订阅标记（formatter 不使用，仅保持往返保真）
     */
    public PeerEnvelope toEnvelope(String summary, boolean notifyWhenIdle) {
        // [peer-name-parity · 2026-11-20 E2E 缺陷 3] from-name 必须与 from 里的名字<b>同一来源</b>。
        //   旧实现取 sourceTitle（会话标题），而 from 是发送侧展示名 ⇒ 同一封信里两个名字
        //   （实测 from="team-lead [c1c93c]" / from-name="alpha-peer"）。
        //   现在统一按 from 解析出的名字（发送侧优先级：ListAgents 展示名 → 会话标题 → 来源 id），
        //   只有 from 缺失才回落标题 / 来源 id。
        String fromName = namePartOfAddress(fromAddress);
        if (fromName == null || fromName.isBlank()) {
            fromName = sourceTitle;
        }
        if (fromName == null || fromName.isBlank()) {
            fromName = sourceSessionId;
        }
        // 标题 + 准入方式随信封一起重建（turn-0 重新入队 / 无队列直拼都走这里）——否则重入队后的
        // peer_source.sourceTitle / admission 会丢成 null，用户又只看到会话 id / 未知准入。
        return new PeerEnvelope(messageId, fromAddress, sourceSessionId, fromName,
                fromMode, hopChain, sourceEndpointEpoch, summary, notifyWhenIdle, null, sourceTitle,
                admission, admissionReason);
    }

    /**
     * 从可回信地址里取名字部分（{@code name [ref]} → {@code name}）。
     *
     * <p>与 {@link PeerAddressing#parseNameWithRef} 同一解析器，保证「发出去时叫什么」
     * 与「显示成什么」永远同一判据。
     */
    private static String namePartOfAddress(String fromAddress) {
        if (fromAddress == null || fromAddress.isBlank()) {
            return null;
        }
        return PeerAddressing.parseNameWithRef(fromAddress).name();
    }

    /** 非 null 即 peer 轮；恒 true（类型化载体本身就是「来自 peer」的判据）。 */
    public boolean isPeer() {
        return true;
    }
}
