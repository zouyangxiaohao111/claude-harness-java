package com.nexusai.domain.im_channel.catalog;

import com.nexusai.domain.im_channel.send.ImSendException;

import java.util.List;

/**
 * 兜底策略：该平台没有会话列表接口（或 platform 拼错）。
 *
 * <p>为什么返回 {@code canRefresh()=false} 而不是抛异常：
 * 「平台不支持」是<b>可预期</b>的能力边界，调用方据此告诉 AI「只能看已同步目录」；
 * 抛异常会让整次工具调用失败，AI 以为是自己用错了参数。
 */
public class UnsupportedChatListStrategy implements ImChatListStrategy {

    private final String platform;

    public UnsupportedChatListStrategy(String platform) {
        this.platform = platform;
    }

    @Override public String platform() { return platform; }

    @Override public boolean canRefresh() { return false; }

    @Override public List<ImChatCandidate> fetch(String instanceId) throws ImSendException {
        throw new ImSendException("IM_PLATFORM_UNSUPPORTED",
                "平台 " + platform + " 不支持拉取会话列表（只能使用已同步的本地目录）");
    }
}
