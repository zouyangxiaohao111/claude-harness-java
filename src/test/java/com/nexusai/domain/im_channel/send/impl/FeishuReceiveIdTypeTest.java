package com.nexusai.domain.im_channel.send.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 守卫 {@link FeishuSendStrategy#receiveIdTypeOf}：飞书 {@code receive_id_type} 必须按 id 形态选。
 *
 * <h2>为什么要守</h2>
 * 之前这里写死 {@code chat_id}，于是「通讯录搜到人 → 落库 {@code ou_xxx} → 发消息」必然被飞书拒
 * （{@code ou_xxx} 不是 {@code chat_id}）。表现出来是「搜到人却发不出去」，
 * 而错误信息与真因无关 —— 正是企微那边 {@code 40073} 的同一类坑。
 *
 * <p>这些断言故意写**字面量**（{@code "chat_id"} 等），不引用被测类的常量：
 * 若有人把常量值改错，用常量当期望值的测试会跟着一起变绿。
 */
class FeishuReceiveIdTypeTest {

    @Test
    @DisplayName("oc_ 前缀 = 会话/群 ⇒ chat_id")
    void chatIdForConversation() {
        assertEquals("chat_id", FeishuSendStrategy.receiveIdTypeOf("oc_5ce6d572455d361153b7aa51da133945"));
    }

    @Test
    @DisplayName("ou_ 前缀 = 用户 open_id ⇒ open_id（给陌生人发的唯一路径）")
    void openIdForUser() {
        assertEquals("open_id", FeishuSendStrategy.receiveIdTypeOf("ou_7d8a6e6df7621556ce0d21922b676706ccs"));
    }

    @Test
    @DisplayName("含 @ ⇒ email")
    void emailForMailAddress() {
        assertEquals("email", FeishuSendStrategy.receiveIdTypeOf("zhang.san@example.com"));
    }

    @Test
    @DisplayName("null / 空白 / 无法判别 ⇒ 回落 chat_id（保持历史行为，不因判别失败而更糟）")
    void fallsBackToChatId() {
        assertEquals("chat_id", FeishuSendStrategy.receiveIdTypeOf(null));
        assertEquals("chat_id", FeishuSendStrategy.receiveIdTypeOf(""));
        assertEquals("chat_id", FeishuSendStrategy.receiveIdTypeOf("   "));
        assertEquals("chat_id", FeishuSendStrategy.receiveIdTypeOf("oc_x"), "oc_ 仍是 chat_id");
    }

    @Test
    @DisplayName("前后空白要被裁掉再判（入站/落库链路可能带空格）")
    void trimsBeforeDetecting() {
        assertEquals("open_id", FeishuSendStrategy.receiveIdTypeOf("  ou_abc  "));
    }
}
