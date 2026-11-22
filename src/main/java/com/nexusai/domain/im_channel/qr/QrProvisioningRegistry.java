package com.nexusai.domain.im_channel.qr;

import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按平台分发到对应的 {@link QrProvisioningStrategy}。
 *
 * <p>Spring 注入所有实现，本类只管"找谁"；新增平台 = 新增一个 {@code @Component} 实现，
 * <b>本类不用改</b>（开闭原则的实际用途：四平台之外还有钉钉这类会变的清单）。
 */
@Service
public class QrProvisioningRegistry {

    private static final Logger log = LoggerFactory.getLogger(QrProvisioningRegistry.class);

    private final Map<String, QrProvisioningStrategy> byPlatform;

    @Autowired
    public QrProvisioningRegistry(List<QrProvisioningStrategy> strategies) {
        this.byPlatform = strategies.stream()
            .collect(Collectors.toMap(QrProvisioningStrategy::platform, Function.identity(), (a, b) -> {
                log.warn("[QrRegistry] 平台 {} 有多个策略实现，取 {}（忽略 {}）",
                    a.platform(), a.getClass().getSimpleName(), b.getClass().getSimpleName());
                return a;
            }));
        log.info("[QrRegistry] 已注册扫码策略: {}", byPlatform.keySet());
    }

    /** @throws ValidationException 该平台没有策略实现（不是"不支持扫码"，而是没接） */
    public QrProvisioningStrategy forPlatform(String platform) {
        QrProvisioningStrategy s = byPlatform.get(platform);
        if (s == null) {
            throw new ValidationException("平台 " + platform + " 尚未接入扫码策略");
        }
        return s;
    }

    /** 哪些平台支持扫码（前端据此决定"添加渠道"里显示扫码还是凭证表单）。 */
    public List<String> qrCapablePlatforms() {
        return byPlatform.values().stream()
            .filter(QrProvisioningStrategy::supportsQr)
            .map(QrProvisioningStrategy::platform)
            .sorted()
            .toList();
    }
}
