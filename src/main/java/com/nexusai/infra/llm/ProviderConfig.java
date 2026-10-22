package com.nexusai.infra.llm;

import java.util.Map;

/**
 * LlmProvider 运行时配置。
 *
 * <p>把 baseUrl + decrypted apiKey 打包成一个 record，让 LlmProvider 接口不依赖
 * ProviderService / ProviderEntity（解耦 + 易测试）。
 *
 * <p>apiKey 永远是运行时解密后的明文，调用方（如 ChatService / ProviderService.test）
 * 必须确保仅在调用栈内使用，<b>绝不</b>写日志 / 进 DTO。
 *
 * @param baseUrl      provider 端点；null/空白 → SDK 默认端点
 * @param apiKey       运行时解密后的明文 key
 * @param extraHeaders provider 级自定义请求头<b>原始值</b>（含未展开占位符，如
 *                     {@code ${session_id}}）。来源：{@code Provider.extraHeaders}（JSON 字符串，
 *                     实体）经 {@link com.nexusai.domain.provider.ProviderService#deserializeHeaders}
 *                     解析，或 {@code ProviderDto.extraHeaders}（已是 Map）。<b>可 null</b>
 *                     （绝大多数 provider 未配置自定义 header ——
 *                     {@code deserializeHeaders(null)} 恒返回 null；便捷构造器落 {@code Map.of()}）。
 *                     <b>敏感头过滤与占位符展开都不在这一层</b>：注入侧统一由
 *                     {@link ProviderHeaderInjector} 单点处理（防御存量脏数据）。
 */
public record ProviderConfig(String baseUrl, String apiKey, Map<String, String> extraHeaders,
                             Map<String, Object> extraBody) {

    /**
     * 模型级额外请求体（{@code extraBody}）· 2026-10-22。
     *
     * <p>来源：{@code models.extra_body}（JSON 对象字符串，前端「额外请求体」可编辑）。
     * 语义 = **逐键合并进请求体**（{@code putAdditionalBodyProperty}），只增/覆盖同名键，
     * ⛔ 不替换整个 body；与系统生成的字段（max_tokens / temperature / reasoning_effort…）
     * 同名时以本 map 为准。空 map → 无额外参数。
     */
    public ProviderConfig {
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
    }

    /** 兼容构造：无额外请求体（既有 3 参调用点零改动）。 */
    public ProviderConfig(String baseUrl, String apiKey, Map<String, String> extraHeaders) {
        this(baseUrl, apiKey, extraHeaders, Map.of());
    }

    /**
     * 便捷构造：无自定义 header。
     *
     * <p>既有调用点（生产 3 处 + 全仓测试）<b>源兼容、零改动</b> —— 实测全仓对 ProviderConfig
     * 无 {@code instanceof} / 解构 / {@code equals} 断言依赖，无 {@code Map<ProviderConfig>} 键用法。
     */
    public ProviderConfig(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, Map.of());
    }

    public static ProviderConfig empty() {
        return new ProviderConfig(null, null);
    }

    public boolean isUsable() {
        return apiKey != null && !apiKey.isBlank();
    }
}
