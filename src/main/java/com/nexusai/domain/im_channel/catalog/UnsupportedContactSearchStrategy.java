package com.nexusai.domain.im_channel.catalog;

import com.nexusai.domain.im_channel.send.ImSendException;

import java.util.List;

/** 兜底：该平台没有通讯录搜索接口（飞书/微信/钉钉首期都没有）。 */
public class UnsupportedContactSearchStrategy implements ImContactSearchStrategy {

    private final String platform;

    public UnsupportedContactSearchStrategy(String platform) {
        this.platform = platform;
    }

    @Override public String platform() { return platform; }

    @Override public boolean canSearch() { return false; }

    @Override public List<ImContactCandidate> search(String instanceId, String keyword) throws ImSendException {
        throw new ImSendException("IM_PLATFORM_UNSUPPORTED",
                "平台 " + platform + " 不支持搜索通讯录（只能对已同步的会话发消息）");
    }
}
