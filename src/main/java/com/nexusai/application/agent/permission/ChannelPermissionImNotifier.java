package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.ImSendService;
import com.nexusai.model.im_channel.ImChatEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「工具权限确认」推到本会话绑定的原生 IM 聊天（[P1 端到端可达]）。
 *
 * <p>此前的状态是「能收不能发」：请求只推 STOMP topic 给桌面，用户手机上收不到。
 * 本类负责出站：<b>只发本会话绑定的聊天</b>（不广播全渠道），载荷走
 * {@link ChannelPermissionPayload} 的四字段 + 脱敏。
 *
 * <p>⛔ 失败只 warn/遥测，<b>不得</b>判拒绝、不得收敛 future（对齐 CC：
 * 出站失败只记 log，桌面继续等）。
 */
@Component
public class ChannelPermissionImNotifier {

    private static final Logger log = LoggerFactory.getLogger(ChannelPermissionImNotifier.class);

    @Autowired(required = false)
    private ImChatService imChatService;

    @Autowired(required = false)
    private ImSendService imSendService;

    /** [P0.1] 已发过的 (requestId, chatId) —— 同一渠道同一请求只建一张卡。 */
    private final java.util.Map<String, java.util.Set<String>> sent =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 单渠道「未送达」明细（上抛给前端标注用）。
     *
     * <p>⛔ 只带**可展示**的非敏感字段：不含 {@code context_token} / {@code botToken} / 凭证。
     * 之所以要把 {@code platform} 与 {@code peerName} 一起带上：多渠道绑定时前端必须能
     * **只标失败的那个渠道**，而不是整张卡标红。
     *
     * @param reasonCode 原因码（{@code IM_REPLY_TOKEN_MISSING} 等，见 {@code ImSendResult#errorCode}）
     */
    public record DeliveryFailure(String chatId, String platform, String instanceId,
                                  String peerName, String reasonCode, String reason) {
    }

    /**
     * 一次权限通知的汇总：成功投递数 + 未送达明细。
     *
     * <p>⚠️ 这里**只表达投递事实**，不带任何决策语义 ——
     * 「未送达」⛔ 不等于「拒绝」，future 继续等桌面（对齐 CC：出站失败只记 log）。
     */
    public record NotifyResult(int delivered, List<DeliveryFailure> failures) {
        public NotifyResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        /** 是否有渠道没送出去（前端据此显示「该渠道未激活，通知未送达」）。 */
        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }

    /** 本会话绑定的聊天（去重后，按目录顺序）；查不到/异常 ⇒ 空列表。 */
    public List<ImChatEntry> boundChats(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || imChatService == null) {
            return List.of();
        }
        try {
            List<ImChatEntry> chats = imChatService.listBySession(sessionId);
            return chats == null ? List.of() : chats;
        } catch (RuntimeException e) {
            log.warn("[ChannelPermIM] 查会话绑定聊天失败（不收敛请求，桌面继续等）session={} err={}",
                sessionId, e.toString());
            return List.of();
        }
    }

    /**
     * 发送权限确认到会话绑定的**每个**聊天（方案 a：全部发 + 共用一个编号 + 先答算谁）。
     *
     * <p>逐渠道独立成败：单个渠道抛异常只 warn，继续发其余渠道，且绝不收敛 future
     * （对齐 CC：出站失败只 log；「不加兜底」= 无人应答也不自动放行，不是「一个渠道挂就整体失败」）。
     *
     * @param sessionId   会话 id
     * @param requestId   唯一请求编号（所有渠道文本里同一个）
     * @param toolName    工具名
     * @param description 弹窗描述（外发前截断/脱敏）
     * @param input       工具入参（外发前脱敏 + 分档截断）
     * @return 成功投递数 + 逐渠道「未送达」明细（永不 null）
     */
    public NotifyResult notifyBoundChats(String sessionId, String requestId, String toolName,
                                         String description, JsonNode input) {
        List<ImChatEntry> chats = boundChats(sessionId);
        if (chats.isEmpty() || imSendService == null) {
            log.info("[ChannelPermIM] 无可用绑定渠道 ⇒ 不推送（桌面继续等）session={} requestId={}",
                sessionId, requestId);
            return new NotifyResult(0, List.of());
        }
        String text = ChannelPermissionPayload.toText(requestId, toolName, description, input);
        java.util.Set<String> already = sent.computeIfAbsent(requestId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        int ok = 0;
        List<DeliveryFailure> failures = new ArrayList<>();
        for (ImChatEntry chat : chats) {
            if (chat == null || chat.id() == null || chat.id().isBlank()) {
                continue;
            }
            if (!already.add(chat.id())) {
                // 同渠道同 requestId 重复通知：跳过（避免两张卡/两条消息）
                continue;
            }
            try {
                var results = imSendService.send(List.of(chat.id()), text);
                var first = (results != null && !results.isEmpty()) ? results.get(0) : null;
                if (first != null
                        && first.status() == com.nexusai.domain.im_channel.send.ImSendResult.Status.SENT) {
                    ok++;
                } else {
                    // ⚠️ SKIPPED（能力边界，如微信缺 context_token）与 FAILED 都算「未送达」：
                    //   对用户而言都是「这条权限通知没到我手机上」，区别只在原因码。
                    String code = first == null || first.errorCode() == null || first.errorCode().isBlank()
                        ? "IM_SEND_UNKNOWN" : first.errorCode();
                    String reason = first == null ? "投递未返回结果" : first.reason();
                    failures.add(new DeliveryFailure(chat.id(), chat.platform(), chat.instanceId(),
                        chat.displayName(), code, reason));
                    // 结构化日志：渠道 / 实例 / 聊天 / 会话 / 短编号 / 原因码
                    log.warn("[ChannelPermIM] 未送达 channel={} instance={} chat={} session={} shortId={} code={}",
                        chat.platform(), chat.instanceId(), chat.id(), sessionId, requestId, code);
                }
            } catch (RuntimeException e) {
                // 单渠道失败只 warn + 记账，继续下一个（⛔ 不收敛 future、不判拒绝）
                failures.add(new DeliveryFailure(chat.id(), chat.platform(), chat.instanceId(),
                    chat.displayName(), "IM_SEND_INTERNAL", "投递异常"));
                log.warn("[ChannelPermIM] 单渠道投递异常（不影响其他渠道/桌面）session={} shortId={} "
                        + "channel={} instance={} chat={} code={} err={}",
                    sessionId, requestId, chat.platform(), chat.instanceId(), chat.id(),
                    "IM_SEND_INTERNAL", e.toString());
            }
        }
        if (ok == 0) {
            log.warn("[ChannelPermIM] 本次请求未投递到任何渠道（仅记录；桌面继续等）session={} shortId={} targets={}",
                sessionId, requestId, chats.size());
        }
        return new NotifyResult(ok, failures);
    }
}
