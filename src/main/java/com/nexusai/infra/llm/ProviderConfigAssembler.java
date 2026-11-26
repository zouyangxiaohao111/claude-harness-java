package com.nexusai.infra.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google 订阅（Gemini Code Assist / Cloud Code）的 {@link ProviderConfig} 装配单一真源。
 *
 * <p><b>为什么在 infra</b>：装配点分布在 application（{@code ChatService}）与 infra
 * （{@code ModelConfigResolver}）。infra 不能反向依赖 application，故把「固定 host + 透传
 * project + 保留 extraHeaders/extraBody」这套判据放在 infra，两路都调同一个静态方法，
 * ⛔ 不允许各自复制一份（同一能力两套判据必然漂移）。
 *
 * <p><b>⛔ 这里不读 DB、不依赖 ProviderService</b>：调用方各自取好 {@code authMode} / {@code project}
 * / 明文 token 再传入；本类只做纯装配（可单测）。
 */
public final class ProviderConfigAssembler {

    /**
     * Gemini Code Assist（Cloud Code）生产 host。
     *
     * <p>取证：{@code google-gemini/gemini-cli@08e9a20} {@code packages/core/src/code_assist/server.ts:73}
     * （{@code CODE_ASSIST_ENDPOINT}），API 版本 {@code v1internal}（同文件 :74）。
     * 订阅凭据只对该 host 有效；用户填的 baseUrl 不参与。
     */
    public static final String GOOGLE_CLOUD_CODE_BASE_URL = "https://cloudcode-pa.googleapis.com";

    /** 与 {@code ProviderService.AUTH_MODE_GOOGLE_OAUTH} 同值（infra 不反向依赖 domain 常量）。 */
    public static final String AUTH_MODE_GOOGLE_OAUTH = "google_oauth";

    private ProviderConfigAssembler() {
    }

    /** 该 auth_mode 是否为 Google 订阅形态。 */
    public static boolean isGoogleOAuth(String authMode) {
        return AUTH_MODE_GOOGLE_OAUTH.equals(authMode);
    }

    /**
     * 装配 Google 订阅的运行时配置。
     *
     * <p>规则：
     * <ul>
     *   <li>baseUrl <b>强制</b> {@link #GOOGLE_CLOUD_CODE_BASE_URL}（用户填的值被忽略）；</li>
     *   <li>{@code project} 原样透传（blank → null）；无 project 时 provider 必须快速失败并复述原因，
     *       ⛔ 不落到 Mock；</li>
     *   <li>{@code extraHeaders} 复制成可写 map（调用方可能传不可变视图），{@code extraBody} 原样
     *       交给 record 归一化 —— 二者都不能因走 Google 分支而丢。</li>
     * </ul>
     *
     * @param rawKey       已解密的 OAuth access token（调用方负责不落日志）
     * @param project      Cloud Code project（{@code providers.oauth_project}；可 null）
     * @param extraHeaders provider 级自定义请求头（可 null）
     * @param extraBody    模型级额外请求体（可 null）
     */
    public static ProviderConfig forGoogleOAuth(String rawKey, String project,
            Map<String, String> extraHeaders, Map<String, Object> extraBody) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        String normalizedProject = project == null || project.isBlank() ? null : project.trim();
        return new ProviderConfig(GOOGLE_CLOUD_CODE_BASE_URL, rawKey, headers,
            extraBody == null ? Map.of() : extraBody, normalizedProject);
    }
}
