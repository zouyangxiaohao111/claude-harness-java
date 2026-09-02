package com.nexusai.infra.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mailbox.send() → void 对齐 CC 的契约测试。
 *
 * <p>WHY（意图）：CC {@code mailbox.ts:33 send(msg: Message): void} 是 fire-and-forget 投递 ——
 * 发送方<b>不得</b>观察消息是被 waiter 直接 resolve 还是入队，路由是 mailbox 内部实现细节。
 * Java 旧版 {@code Mailbox.SendResult{delivered, resolvedMessage}} 是目标端独有扩展（⊕-1），
 * 把内部路由结果暴露给了调用方。删除后公共契约与 CC 对齐：send 只投递，不返回投递结果。
 *
 * <p>本类同时锁定 fire-and-forget 语义的可观察行为（waiter 命中 resolve 不入队 / 未命中入队 /
 * revision 递增），确保签名改 void 后投递语义不回归。
 */
class MailboxTest {

    @Test
    @DisplayName("send 返回 void —— 发送方不得观察投递结果（对齐 CC mailbox.ts:33）")
    void send_returnsVoid_noDeliveryResultExposed() throws NoSuchMethodException {
        Method send = Mailbox.class.getMethod("send", Mailbox.Message.class);
        assertThat(send.getReturnType())
            .as("CC mailbox.ts:33 send(msg): void；Java 端必须返回 void，不得暴露 SendResult")
            .isEqualTo(void.class);
    }

    @Test
    @DisplayName("send 命中 waiter 直接 resolve 不入队（fire-and-forget 路由语义保留）")
    void send_matchingWaiter_resolvesWithoutQueueing() {
        Mailbox mb = new Mailbox();
        CompletableFuture<Mailbox.Message> fut =
            mb.receive(m -> m.source() == Mailbox.MessageSource.teammate);
        Mailbox.Message msg = msg("1", Mailbox.MessageSource.teammate);

        mb.send(msg);

        assertThat(fut.join()).isSameAs(msg);
        assertThat(mb.length()).isZero();
    }

    @Test
    @DisplayName("send 无匹配 waiter 入队，后续 poll 可取回（fire-and-forget 路由语义保留）")
    void send_noMatchingWaiter_queuesMessage() {
        Mailbox mb = new Mailbox();
        Mailbox.Message msg = msg("2", Mailbox.MessageSource.system);

        mb.send(msg);

        assertThat(mb.length()).isEqualTo(1);
        assertThat(mb.poll(m -> true)).isSameAs(msg);
        assertThat(mb.length()).isZero();
    }

    @Test
    @DisplayName("send 每次投递 revision 递增（CC mailbox.ts:34 _revision++）")
    void send_incrementsRevision() {
        Mailbox mb = new Mailbox();
        long before = mb.revision();

        mb.send(msg("3", Mailbox.MessageSource.user));

        assertThat(mb.revision()).isEqualTo(before + 1);
    }

    private static Mailbox.Message msg(String id, Mailbox.MessageSource source) {
        return new Mailbox.Message(id, source, "content-" + id, null, null, "2026-08-13T00:00:00Z");
    }
}
