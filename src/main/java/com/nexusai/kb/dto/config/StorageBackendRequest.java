package com.nexusai.kb.dto.config;

import java.util.Map;

/**
 * 创建/更新存储后端请求体（批次 W8）。
 *
 * <p>⚠️ PATCH 语义：{@code null} = 不改。{@code provider} 允许改（源侧允许），
 * 但换了 provider 必须同时给新 config —— 否则新类型的必填字段校验会失败，
 * 相当于强制「换类型必须重填配置」。
 */
public record StorageBackendRequest(
    String name,
    String provider,
    Map<String, Object> config,
    /** active | disabled；null = 不改 */
    String status
) {
}
