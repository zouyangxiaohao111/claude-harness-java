package com.nexusai.domain.im_message.fetch;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拉取器工厂：{@code platform → ImMessageFetcher}。
 *
 * <h2>为什么要工厂</h2>
 * 调用方（{@code ImMessageQueryService}）拿到的是「一个 chat 的平台字符串」，不是具体实现类。
 * 若没有工厂，就得在每个调用点注入 3 个 Bean 再 if-else —— 新增平台时要改所有调用点。
 *
 * <p>Spring 自动注入 {@code List<ImMessageFetcher>} ⇒ <b>新增平台 = 加一个 {@code @Component}，
 * 工厂、调用方都不用改</b>。
 *
 * <h2>未知平台的出口</h2>
 * 返回 {@link UnsupportedImMessageFetcher} 而不是 null / 抛异常：
 * 库里可能有历史遗留或拼错的 platform，此时应当「明确跳过并说明」，不能让读消息整体崩掉。
 */
@Component
public class ImMessageFetcherFactory {

    private final Map<String, ImMessageFetcher> byPlatform;

    public ImMessageFetcherFactory(List<ImMessageFetcher> fetchers) {
        Map<String, ImMessageFetcher> map = new LinkedHashMap<>();
        if (fetchers != null) {
            for (ImMessageFetcher f : fetchers) {
                if (f == null) {
                    continue;
                }
                String p = f.platform();
                if (p == null || p.isBlank()) {
                    // 实现类忘了声明平台 = bug，但不能因此让整个应用起不来
                    continue;
                }
                map.put(p, f);
            }
        }
        this.byPlatform = Collections.unmodifiableMap(map);
    }

    /** 取拉取器；未知平台返回 {@link UnsupportedImMessageFetcher}（永不返回 null）。 */
    public ImMessageFetcher of(String platform) {
        if (platform == null || platform.isBlank()) {
            return new UnsupportedImMessageFetcher(platform);
        }
        ImMessageFetcher f = byPlatform.get(platform);
        return f != null ? f : new UnsupportedImMessageFetcher(platform);
    }

    /** 已注册的平台集合（排障 / 自检用）。 */
    public Set<String> supportedPlatforms() {
        return byPlatform.keySet();
    }

    /** 已注册的拉取器（测试用）。 */
    public Collection<ImMessageFetcher> all() {
        return byPlatform.values();
    }
}
