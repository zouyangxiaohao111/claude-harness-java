package com.nexusai.domain.im_channel.catalog;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略工厂：{@code platform → 会话列表策略}。
 *
 * <p>与 {@code ImSendStrategyFactory} 同范式：Spring 注入 {@code List<ImChatListStrategy>}，
 * 新增平台 = 加一个 {@code @Component}，工厂与调用方都不用改。
 * 未注册的平台返回 {@link UnsupportedChatListStrategy}（永不返回 null）。
 */
@Component
public class ImChatListStrategyFactory {

    private final Map<String, ImChatListStrategy> byPlatform;

    public ImChatListStrategyFactory(List<ImChatListStrategy> strategies) {
        Map<String, ImChatListStrategy> map = new LinkedHashMap<>();
        if (strategies != null) {
            for (ImChatListStrategy s : strategies) {
                if (s == null) {
                    continue;
                }
                String p = s.platform();
                if (p == null || p.isBlank()) {
                    continue;   // 忘了声明平台 = bug，但不让应用起不来
                }
                map.put(p, s);
            }
        }
        this.byPlatform = Collections.unmodifiableMap(map);
    }

    /** 取策略；未知/空平台返回 Unsupported（永不 null）。 */
    public ImChatListStrategy of(String platform) {
        if (platform == null || platform.isBlank()) {
            return new UnsupportedChatListStrategy(platform);
        }
        ImChatListStrategy s = byPlatform.get(platform);
        return s != null ? s : new UnsupportedChatListStrategy(platform);
    }
}
