package com.nexusai.domain.im_message.fetch;

import com.nexusai.model.im_message.ImMessageEntry;

import java.util.List;

/**
 * 未知平台的出口（永不返回 null 的工厂兜底）。
 *
 * <p>范式对齐 {@code UnsupportedSendStrategy} / {@code UnsupportedChatListStrategy}：
 * 库里可能有历史遗留或拼错的 {@code platform}，此时应当「明确跳过并说明」，
 * 不能让整个读消息流程崩掉，也不能静默返回空列表（那会让「平台不支持」看起来像「没有消息」）。
 */
public class UnsupportedImMessageFetcher implements ImMessageFetcher {

    private final String platform;

    public UnsupportedImMessageFetcher(String platform) {
        this.platform = platform;
    }

    @Override
    public String platform() {
        return platform;
    }

    @Override
    public List<ImMessageEntry> fetch(String instanceId, String chatId, String sinceIso,
                                      String untilIso, int maxMessages) throws ImMessageFetchException {
        throw new ImMessageFetchException("IM_FETCH_UNSUPPORTED",
            "平台「" + platform + "」暂不支持拉取历史消息（只读本地已有记录）");
    }
}
