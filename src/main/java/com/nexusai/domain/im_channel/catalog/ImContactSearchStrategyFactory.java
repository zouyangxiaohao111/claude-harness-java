package com.nexusai.domain.im_channel.catalog;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联系人搜索策略工厂（与 {@code ImChatListStrategyFactory} 同范式）。
 *
 * <p>Spring 注入 {@code List<ImContactSearchStrategy>}：新增平台 = 加一个 {@code @Component}。
 * 未注册/空平台返回 {@link UnsupportedContactSearchStrategy}（永不返回 null）。
 */
@Component
public class ImContactSearchStrategyFactory {

    private final Map<String, ImContactSearchStrategy> byPlatform;

    public ImContactSearchStrategyFactory(List<ImContactSearchStrategy> strategies) {
        Map<String, ImContactSearchStrategy> map = new LinkedHashMap<>();
        if (strategies != null) {
            for (ImContactSearchStrategy s : strategies) {
                if (s == null) continue;
                String p = s.platform();
                if (p == null || p.isBlank()) continue;
                map.put(p, s);
            }
        }
        this.byPlatform = Collections.unmodifiableMap(map);
    }

    public ImContactSearchStrategy of(String platform) {
        if (platform == null || platform.isBlank()) {
            return new UnsupportedContactSearchStrategy(platform);
        }
        ImContactSearchStrategy s = byPlatform.get(platform);
        return s != null ? s : new UnsupportedContactSearchStrategy(platform);
    }
}
