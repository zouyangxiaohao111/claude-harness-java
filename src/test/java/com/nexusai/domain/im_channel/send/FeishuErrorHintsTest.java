package com.nexusai.domain.im_channel.send;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 飞书错误码 → 人话（发送与拉取共用的同一份映射）。
 *
 * <p>判据：扫描摘要（{@code last_error}）要能给出「机器人不在该会话」这种可读原因，
 * 而不是把「HTTP 400」直接当成解释。
 */
class FeishuErrorHintsTest {

    @Test
    @DisplayName("shortReason：230002/230027/230006 都有可读原因（拉取失败摘要用）")
    void shortReasonCoversPullFailureCodes() {
        assertThat(FeishuErrorHints.shortReason(230002)).isEqualTo("机器人不在该会话");
        assertThat(FeishuErrorHints.shortReason(230027)).isEqualTo("缺少权限或外部群不支持");
        assertThat(FeishuErrorHints.shortReason(230006)).isEqualTo("机器人能力未开启");
        // 字符串重载（ImMessageFetchException.platformCode() 就是字符串）
        assertThat(FeishuErrorHints.shortReason("230002")).isEqualTo("机器人不在该会话");
    }

    @Test
    @DisplayName("hint：发送路径的既有文案保持不变，且新增码有解释")
    void hintKeepsSendPathWording() {
        assertThat(FeishuErrorHints.hint(230002)).contains("机器人不在该会话里");
        assertThat(FeishuErrorHints.hint(230002)).contains("把机器人拉进群");
        assertThat(FeishuErrorHints.hint(99991672)).contains("缺少所需权限 scope");
        assertThat(FeishuErrorHints.hint(99991663)).contains("令牌无效或已过期");
        assertThat(FeishuErrorHints.hint(230001)).contains("接收方 id 无效");
        assertThat(FeishuErrorHints.hint(230027)).contains("外部群");
        assertThat(FeishuErrorHints.hint(230006)).contains("机器人能力未开启");
    }

    @Test
    @DisplayName("未收录的码：hint 为空串、shortReason 为 null（不新造词）")
    void unknownCodesFallBackToRaw() {
        assertThat(FeishuErrorHints.hint(123456)).isEmpty();
        assertThat(FeishuErrorHints.shortReason(123456)).isNull();
        assertThat(FeishuErrorHints.shortReason((String) null)).isNull();
        assertThat(FeishuErrorHints.shortReason("400")).as("HTTP 状态码不是业务码，不能当原因").isNull();
        assertThat(FeishuErrorHints.shortReason("not-a-code")).isNull();
    }
}
