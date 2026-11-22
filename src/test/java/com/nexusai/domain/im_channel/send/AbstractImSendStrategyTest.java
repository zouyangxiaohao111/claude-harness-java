package com.nexusai.domain.im_channel.send;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模板方法 {@link AbstractImSendStrategy#send} 的分支覆盖（NXS-UC-CHN-020）。
 *
 * <h2>WHY 这些用例</h2>
 * 模板方法把「什么情况下不该发」判断集中在一处，这里是它唯一的防线：
 * <ul>
 *   <li>平台不支持 / 缺令牌时**一个请求都不能发**（否则微信会发出空白消息、钉钉会调不存在的接口）；</li>
 *   <li>{@code send()} 必须**永不抛异常**，否则一个目标炸掉整批投递；</li>
 *   <li>超长文本必须截断并让收信人看得出来（⛔ 不能静默丢弃）。</li>
 * </ul>
 * 其中「不发请求」用 {@link CountingStrategy#doSendCalls} 亲测 —— 只看返回值无法区分
 * 「跳过了」和「发了但失败了」。
 */
class AbstractImSendStrategyTest {

    /** 可编排的测试策略：记录 doSend 被调用次数，行为由构造参数决定。 */
    private static final class CountingStrategy extends AbstractImSendStrategy {
        private final String platform;
        private final SendCapability capability;
        private final String returnId;
        /** 受检异常与运行时异常分开存：doSend 只能抛前者，后者用于模拟 NPE。 */
        private final ImSendException imSendError;
        private final RuntimeException runtimeError;
        /** 本平台是否支持「以用户名义」发送。 */
        private final boolean canSendAsUser;
        final AtomicInteger doSendCalls = new AtomicInteger();
        String lastText;
        /** doSend 实际收到的上下文（用来验「降级后下游看到的身份」）。 */
        ImSendContext lastCtx;

        CountingStrategy(String platform, SendCapability capability, String returnId,
                         ImSendException imSendError, RuntimeException runtimeError) {
            this(platform, capability, returnId, imSendError, runtimeError, false);
        }

        CountingStrategy(String platform, SendCapability capability, String returnId,
                         ImSendException imSendError, RuntimeException runtimeError,
                         boolean canSendAsUser) {
            this.platform = platform;
            this.capability = capability;
            this.returnId = returnId;
            this.imSendError = imSendError;
            this.runtimeError = runtimeError;
            this.canSendAsUser = canSendAsUser;
        }

        @Override public String platform() { return platform; }
        @Override public SendCapability capability() { return capability; }
        @Override public boolean canSendAsUser() { return canSendAsUser; }

        @Override public String doSend(ImSendContext ctx, String text) throws ImSendException {
            doSendCalls.incrementAndGet();
            lastText = text;
            lastCtx = ctx;
            if (imSendError != null) {
                throw imSendError;
            }
            if (runtimeError != null) {
                throw runtimeError;
            }
            return returnId;
        }
    }

    private static ImSendContext ctx(String replyToken) {
        return new ImSendContext("inst-1", "feishu", "oc_1", "group", "{\"appId\":\"a\"}", replyToken);
    }

    /** 指定发送身份的上下文。 */
    private static ImSendContext ctxAs(String replyToken, SendIdentity identity) {
        return new ImSendContext("inst-1", "feishu", "oc_1", "group", "{\"appId\":\"a\"}", replyToken, identity);
    }

    // ───────────────────────── 1. 入参判空 ─────────────────────────

    @Test
    @DisplayName("ctx=null → FAILED(IM_PARAM_INVALID)，不抛异常、不发请求")
    void nullContextFailsWithoutThrowing() {
        CountingStrategy s = new CountingStrategy("feishu", SendCapability.proactive(100, true), "om_1", null, null);

        ImSendResult r = s.send(null, "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.errorCode()).isEqualTo("IM_PARAM_INVALID");
        assertThat(s.doSendCalls.get()).isZero();
    }

    @Test
    @DisplayName("text=null → FAILED，不发请求")
    void nullTextFails() {
        CountingStrategy s = new CountingStrategy("feishu", SendCapability.proactive(100, true), "om_1", null, null);

        ImSendResult r = s.send(ctx(null), null);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(s.doSendCalls.get()).isZero();
    }

    @Test
    @DisplayName("text 全是空白 → FAILED（⛔ 不能把空白消息发出去）")
    void blankTextFails() {
        CountingStrategy s = new CountingStrategy("feishu", SendCapability.proactive(100, true), "om_1", null, null);

        ImSendResult r = s.send(ctx(null), "   \n\t  ");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(s.doSendCalls.get()).isZero();
    }

    // ───────────────────────── 2. 能力检查（关键：不发请求） ─────────────────────────

    @Test
    @DisplayName("平台不支持主动发 → SKIPPED(IM_SEND_UNSUPPORTED)，doSend 一次都没调用")
    void unsupportedPlatformSkipsWithoutCalling() {
        CountingStrategy s = new CountingStrategy("dingtalk",
                SendCapability.unsupported("钉钉无主动发送通道"), "x", null, null);

        ImSendResult r = s.send(ctx(null), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SKIPPED);
        assertThat(r.errorCode()).isEqualTo("IM_SEND_UNSUPPORTED");
        assertThat(r.reason()).contains("钉钉");
        assertThat(s.doSendCalls.get()).as("能力不足时绝不能发请求").isZero();
    }

    @Test
    @DisplayName("需要 replyToken 但为空 → SKIPPED(IM_REPLY_TOKEN_MISSING)，doSend 没调用")
    void missingReplyTokenSkips() {
        CountingStrategy s = new CountingStrategy("wechat",
                SendCapability.needsReplyToken(4096, false), "msg_1", null, null);

        ImSendResult r = s.send(ctx(null), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SKIPPED);
        assertThat(r.errorCode()).isEqualTo("IM_REPLY_TOKEN_MISSING");
        assertThat(s.doSendCalls.get()).as("缺 token 发出去会是空白消息，必须拦住").isZero();
    }

    @Test
    @DisplayName("需要 replyToken 且提供了 → 正常发送")
    void replyTokenPresentSends() {
        CountingStrategy s = new CountingStrategy("wechat",
                SendCapability.needsReplyToken(4096, false), "msg_1", null, null);

        ImSendResult r = s.send(ctx("ctx-token"), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.messageId()).isEqualTo("msg_1");
        assertThat(s.doSendCalls.get()).isEqualTo(1);
    }

    // ───────────────────────── 3. 异常归一 ─────────────────────────

    @Test
    @DisplayName("doSend 抛 ImSendException → FAILED，且沿用异常自带的 code")
    void imSendExceptionBecomesFailed() {
        CountingStrategy s = new CountingStrategy("feishu", SendCapability.proactive(100, true), null,
                new ImSendException("IM_CREDENTIAL_EXCHANGE_FAILED", "换票失败", "99991663", true), null);

        ImSendResult r = s.send(ctx(null), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.errorCode()).isEqualTo("IM_CREDENTIAL_EXCHANGE_FAILED");
        assertThat(r.reason()).contains("换票失败");
    }

    @Test
    @DisplayName("doSend 抛 RuntimeException（如 NPE）→ FAILED，绝不外溢炸掉批量投递")
    void runtimeExceptionIsCaught() {
        CountingStrategy s = new CountingStrategy("feishu", SendCapability.proactive(100, true), null,
                null, new NullPointerException("boom"));

        ImSendResult r = s.send(ctx(null), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(r.errorCode()).isEqualTo("IM_SEND_PLATFORM_ERROR");
    }

    @Test
    @DisplayName("doSend 返回 null（企微无 message_id）→ 仍算 SENT")
    void nullMessageIdIsStillSent() {
        CountingStrategy s = new CountingStrategy("wecom", SendCapability.proactive(2048, true), null, null, null);

        ImSendResult r = s.send(ctx(null), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.messageId()).isNull();
        assertThat(r.ok()).isTrue();
    }

    // ───────────────────────── 4. 文本归一 ─────────────────────────

    @Test
    @DisplayName("超长文本截断并追加提示（让收信人知道不完整）")
    void longTextIsTruncatedWithNotice() {
        CountingStrategy s = new CountingStrategy("wecom", SendCapability.proactive(50, true), "x", null, null);
        String longText = "あ".repeat(200);

        s.send(ctx(null), longText);

        assertThat(s.lastText).hasSizeLessThanOrEqualTo(50);
        assertThat(s.lastText).contains("已截断");
    }

    @Test
    @DisplayName("上限大到放不下提示语时只截断、不追加（避免提示比正文还长）")
    void tinyLimitTruncatesWithoutNotice() {
        CountingStrategy s = new CountingStrategy("wecom", SendCapability.proactive(10, true), "x", null, null);

        s.send(ctx(null), "abcdefghijklmnop");

        assertThat(s.lastText).hasSize(10);
        assertThat(s.lastText).doesNotContain("已截断");
    }

    @Test
    @DisplayName("正好等于上限 → 不截断")
    void exactLimitNoTruncation() {
        CountingStrategy s = new CountingStrategy("wecom", SendCapability.proactive(11, true), "x", null, null);

        s.send(ctx(null), "12345678901");

        assertThat(s.lastText).isEqualTo("12345678901");
    }

    // ───────────────────────── 5. 能力声明自身出错也不能崩 ─────────────────────────

    @Test
    @DisplayName("capability() 返回 null → FAILED，不抛 NPE")
    void nullCapabilityIsHandled() {
        CountingStrategy s = new CountingStrategy("feishu", null, "x", null, null);

        ImSendResult r = s.send(ctx(null), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(s.doSendCalls.get()).isZero();
    }

    // ───────────────────────── 6. 其它类型自身的防御 ─────────────────────────

    @Test
    @DisplayName("SendCapability.unsupported 不给原因 → 构造即拒（调用方会拿到没信息量的话）")
    void unsupportedCapabilityRequiresReason() {
        assertThatThrownBy(() -> SendCapability.unsupported(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SendCapability.unsupported("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("可发送平台 maxTextChars<=0 → 构造即拒")
    void proactiveCapabilityRequiresPositiveLimit() {
        assertThatThrownBy(() -> SendCapability.proactive(0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SendCapability.proactive(-1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ImSendContext 缺任一必填字段 → IllegalArgumentException（不是 NPE）")
    void contextRejectsMissingFields() {
        assertThatThrownBy(() -> new ImSendContext(null, "feishu", "p", "p2p", "{}", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("instanceId");
        assertThatThrownBy(() -> new ImSendContext("i", "  ", "p", "p2p", "{}", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("platform");
        assertThatThrownBy(() -> new ImSendContext("i", "feishu", null, "p2p", "{}", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("peerId");
        assertThatThrownBy(() -> new ImSendContext("i", "feishu", "p", "p2p", "", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credential");
    }

    @Test
    @DisplayName("hasReplyToken：null / 空白 都是 false")
    void hasReplyTokenHandlesBlank() {
        assertThat(ctx(null).hasReplyToken()).isFalse();
        assertThat(ctx("").hasReplyToken()).isFalse();
        assertThat(ctx("  ").hasReplyToken()).isFalse();
        assertThat(ctx("t").hasReplyToken()).isTrue();
    }

    // ───────────────────────── 7. 发送身份（BOT / USER） ─────────────────────────

    @Test
    @DisplayName("默认身份 = BOT：结果报 BOT，无身份说明")
    void defaultIdentityIsBot() {
        CountingStrategy s = new CountingStrategy("feishu",
                SendCapability.proactive(100, true), "om_1", null, null);

        ImSendResult r = s.send(ctx(null), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.identity()).isEqualTo(SendIdentity.BOT);
        assertThat(r.identityNote()).isNull();
    }

    @Test
    @DisplayName("请求 USER + 平台支持 ⇒ 保持 USER，且下游看到的也是 USER")
    void userIdentityKeptWhenPlatformSupports() {
        CountingStrategy s = new CountingStrategy("wecom",
                SendCapability.proactive(100, true), null, null, null, true);

        ImSendResult r = s.send(ctxAs(null, SendIdentity.USER), "hello");

        assertThat(r.identity()).isEqualTo(SendIdentity.USER);
        assertThat(r.identityNote()).isNull();
        assertThat(s.lastCtx.identity()).as("下游必须看到实际身份").isEqualTo(SendIdentity.USER);
    }

    @Test
    @DisplayName("🔴 请求 USER + 平台不支持 ⇒ 降级 BOT + 写明原因（⛔ 不能静默降级）")
    void userIdentityDowngradedWhenPlatformUnsupported() {
        CountingStrategy s = new CountingStrategy("feishu",
                SendCapability.proactive(100, true), "om_1", null, null);

        ImSendResult r = s.send(ctxAs(null, SendIdentity.USER), "hello");

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.identity()).as("报的是**实际**身份").isEqualTo(SendIdentity.BOT);
        assertThat(r.identityNote()).contains("不支持以用户名义").contains("降级");
        assertThat(s.lastCtx.identity()).as("下游看到的也必须是降级后的身份")
                .isEqualTo(SendIdentity.BOT);
    }

    @Test
    @DisplayName("请求 USER + 只有附件（用户身份端点发不了媒体）⇒ 降级 BOT + 说明原因")
    void userIdentityWithAttachmentOnlyDowngrades() {
        CountingStrategy s = new CountingStrategy("wecom",
                SendCapability.proactive(100, true), null, null, null, true);

        ImSendResult r = s.send(ctxAs(null, SendIdentity.USER), null,
                List.of(new ImSendAttachment(java.nio.file.Path.of("/tmp/x.png"),
                        MediaKind.IMAGE, "x.png")));

        assertThat(r.identity()).isEqualTo(SendIdentity.BOT);
        assertThat(r.identityNote()).contains("附件").contains("机器人身份");
    }

    @Test
    @DisplayName("请求 USER + 有正文有附件（平台不支持用户身份发媒体）⇒ 正文保持 USER，附件降级并写明")
    void userIdentityWithTextAndAttachmentKeepsUserForText() {
        CountingStrategy s = new CountingStrategy("wecom",
                SendCapability.proactive(100, true), null, null, null, true);

        ImSendResult r = s.send(ctxAs(null, SendIdentity.USER), "正文",
                List.of(new ImSendAttachment(java.nio.file.Path.of("/tmp/x.png"),
                        MediaKind.IMAGE, "x.png")));

        assertThat(r.identity()).as("正文是用户身份发的 ⇒ 整体报 USER").isEqualTo(SendIdentity.USER);
        assertThat(r.identityNote()).contains("文本以用户身份").contains("附件以机器人身份");
    }
}
