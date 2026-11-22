package com.nexusai.domain.im_channel.send;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 策略工厂：{@code platform → 策略实例}。
 *
 * <h2>为什么需要工厂</h2>
 * 调用方（{@code ImSendService}）拿到的是「一个 chat 的平台字符串」，不是具体实现类。
 * 若没有工厂，就得在每个调用点注入 4 个 Bean 再 if-else —— 新增平台时要改所有调用点。
 *
 * <p>Spring 自动注入 {@code List<ImSendStrategy>} ⇒ <b>新增平台 = 加一个 {@code @Component}，
 * 工厂、模板、调用方都不用改</b>。
 *
 * <h2>未知平台的出口</h2>
 * 返回 {@link UnsupportedSendStrategy} 而不是 null / 抛异常：
 * 库里可能有历史遗留或拼错的 platform，此时应当「明确跳过并说明」，不能让投递整体崩掉。
 */
@Component
public class ImSendStrategyFactory {

    private final Map<String, ImSendStrategy> byPlatform;

    public ImSendStrategyFactory(List<ImSendStrategy> strategies) {
        Map<String, ImSendStrategy> map = new LinkedHashMap<>();
        if (strategies != null) {
            for (ImSendStrategy s : strategies) {
                if (s == null) {
                    continue;
                }
                String p = s.platform();
                if (p == null || p.isBlank()) {
                    // 实现类忘了声明平台 = bug，但不能因此让整个应用起不来
                    continue;
                }
                map.put(p, s);
            }
        }
        this.byPlatform = Collections.unmodifiableMap(map);
    }

    /**
     * 取策略；未知平台返回 {@link UnsupportedSendStrategy}（永不返回 null）。
     *
     * @param platform 平台标识，可 null（会返回 Unsupported）
     */
    public ImSendStrategy of(String platform) {
        if (platform == null || platform.isBlank()) {
            return new UnsupportedSendStrategy(platform);
        }
        ImSendStrategy s = byPlatform.get(platform);
        return s != null ? s : new UnsupportedSendStrategy(platform);
    }

    /** 已注册的平台集合（排障 / 自检用）。 */
    public Set<String> supportedPlatforms() {
        return byPlatform.keySet();
    }

    /** 已注册的策略（测试用）。 */
    public Collection<ImSendStrategy> all() {
        return byPlatform.values();
    }
}
