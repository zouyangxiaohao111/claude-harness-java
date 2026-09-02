package com.nexusai.application.agent.api;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用 withOAuth401Retry 等价工具 · 对齐 CC {@code src/utils/http.ts:115-136}
 * {@code withOAuth401Retry} + {@code src/utils/auth.ts:1360-1392} {@code handleOAuth401Error}。
 *
 * <p><b>主链</b>（CC http.ts:119-134）：
 * <pre>
 * try { return await request() }
 * catch (err) {
 *   if (!axios.isAxiosError(err)) throw err
 *   const status = err.response?.status
 *   const isAuthError = status === 401 ||
 *     (opts?.also403Revoked && status === 403 &&
 *      typeof data === 'string' && data.includes('OAuth token has been revoked'))
 *   if (!isAuthError) throw err
 *   const failedAccessToken = getClaudeAIOAuthTokens()?.accessToken
 *   if (!failedAccessToken) throw err
 *   await handleOAuth401Error(failedAccessToken)
 *   return await request()   // 重试一次
 * }
 * </pre>
 *
 * <p><b>用途</b>：处理 OAuth 401 错误，强制刷新 token 后重试一次。解决本地过期
 * 检查与服务端判定不一致的时钟漂移场景。request 闭包重试时应重读 auth
 * （等价 CC 注释 http.ts:106-107 "re-read auth to pick up refreshed token"）。
 *
 * <p><b>E5 · OAuth 401 与 AWS/GCP 凭证自愈互斥说明</b>：
 * 本类处理 <b>OAuth 401/403-revoked</b>（用户级 OAuth token 过期/吊销场景，
 * CC auth.ts:1360-1392）；与 <b>AWS Bedrock / GCP Vertex 凭证自愈</b>
 * （{@link com.nexusai.application.agent.recovery.ErrorClassifier#handleAwsCredentialError} /
 * {@code handleGcpCredentialError}，基础设施级 IAM 凭证轮转）处理不同层级的认证问题，
 * 天然互斥、无冲突：
 * <ul>
 *   <li>OAuth 路径：用户级 access token 过期（时钟漂移 / 服务端吊销） → 强制刷新 OAuth token</li>
 *   <li>AWS/GCP 路径：基础设施 IAM 凭证轮转（EC2 metadata / workload identity） → 刷新 SDK 凭证</li>
 * </ul>
 * 一笔 LLM 请求只会走其中一条路径（取决于 provider 是 OAuth-Anthropic / AWS-Bedrock / GCP-Vertex），
 * 不会同时触发两种自愈机制。Java 端 {@link com.nexusai.application.agent.recovery.TransientErrorHandler}
 * 先调凭证自愈（handleAwsCredentialError/handleGcpCredentialError），未命中时再走 withRetry
 * 重试循环；OAuth 401 由本类（含 {@link OAuth401Refresher}）单独处理。
 *
 * <p><b>Java 落地说明</b>：本项目当前无 OAuth token 流（AnthropicSdkProvider 纯 API-key；
 * HttpMcpTransport 的 MCP server 401 → {@code McpAuthError}，对齐 CC client.ts:3194-3208，
 * 不在 transport 层重试 —— CC MCP transport 亦不 wrap withOAuth401Retry）。
 * 故本工具<b>只建不接线</b>（不伪造调用点）：当未来接入 claude.ai OAuth / grove API 调用时，
 * 由调用方通过 {@link OAuthRequestCall} 注入请求闭包 + {@link OAuth401Refresher} 触发刷新。
 *
 * @param <T> 请求返回类型
 */
public final class OAuth401Retry {

    private static final Logger log = LoggerFactory.getLogger(OAuth401Retry.class);

    /** CC original: 'OAuth token has been revoked' body 标记（http.ts:129）。 */
    public static final String OAUTH_TOKEN_REVOKED_MARKER = "OAuth token has been revoked";

    private OAuth401Retry() {
        // 纯静态工具类
    }

    /** 请求闭包（等价 CC {@code () => Promise<T>}）· 可抛受检/非受检异常。 */
    @FunctionalInterface
    public interface OAuthRequestCall<T> {
        T call() throws Exception;
    }

    /**
     * 从抛出的错误中提取 HTTP 状态码 / 响应体 · Java 等价 {@code axios.isAxiosError}
     * 后读取 {@code err.response.status / err.response.data}。
     */
    public interface HttpStatusExtractor {
        /**
         * @return HTTP 状态码；非 HTTP 错误返回 null（= 透传，CC http.ts:122-123）。
         */
        Integer statusOf(Throwable error);

        /**
         * @return 响应体字符串；非字符串体返回 null（CC http.ts:128
         *         {@code typeof data === 'string'} 检查）。
         */
        String bodyOf(Throwable error);
    }

    /** 读取当前 OAuth access token · 等价 CC {@code getClaudeAIOAuthTokens()?.accessToken}。 */
    @FunctionalInterface
    public interface CurrentOAuthTokenProvider {
        /** @return 当前 access token；无 token 返回 null（= 重抛，CC http.ts:131-132）。 */
        String accessToken();
    }

    /** 选项 · CC original: {@code opts?: { also403Revoked?: boolean }}（http.ts:117）。 */
    public record Options(boolean also403Revoked) {
        public static Options defaults() {
            return new Options(false);
        }
    }

    /**
     * CC withOAuth401Retry 主链（http.ts:115-136）。
     *
     * <p>主链：try request → catch → isAuthError(401|403+revoked body) →
     * 读当前 token（null 重抛）→ refresher.handle401(failedToken) → 重试一次。
     * 无论 handle401 刷新是否成功都重试一次（CC http.ts:133-134 无条件
     * {@code return await request()}）。
     *
     * @param request      请求闭包（重试时重读 auth）
     * @param extractor    HTTP 状态/响应体提取器（Java 等价 axios 错误解析）
     * @param tokenProvider 当前 OAuth access token 提供者
     * @param refresher    OAuth 401 处理器（含 per-token pending 去重 / 重读 / 强制刷新）
     * @param opts         选项（also403Revoked）
     * @return 请求成功结果
     * @throws Exception 原异常（非 auth 错误 / 无 token）或重试后异常
     */
    public static <T> T withOAuth401Retry(
            OAuthRequestCall<T> request,
            HttpStatusExtractor extractor,
            CurrentOAuthTokenProvider tokenProvider,
            OAuth401Refresher refresher,
            Options opts) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        // CC http.ts:133 无条件 await handleOAuth401Error(failedAccessToken) —— handleOAuth401Error
        // 是顶层函数恒在，非可空注入。Java 端 refresher 等价其 handler，必非 null；为 null 表示
        // 调用方误用（无 OAuth 不应走本工具），显式失败而非静默跳过刷新（跳过刷新同 token 必再 401）。
        Objects.requireNonNull(refresher, "refresher is required (CC http.ts:133 handleOAuth401Error 恒在，不可 null)");
        Options options = opts == null ? Options.defaults() : opts;
        try {
            return request.call();
        } catch (Exception err) {
            Integer status = extractor == null ? null : extractor.statusOf(err);
            String body = extractor == null ? null : extractor.bodyOf(err);
            boolean isAuthError = status != null && isAuthError(status, body, options);
            if (!isAuthError) {
                if (log.isDebugEnabled()) {
                    log.debug("[withOAuth401Retry] 非 OAuth 401 错误，透传（status={}）", status);
                }
                throw err;
            }
            String failedAccessToken = tokenProvider == null ? null : tokenProvider.accessToken();
            if (failedAccessToken == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[withOAuth401Retry] OAuth 401 但无当前 access token，重抛（CC http.ts:131-132）");
                }
                throw err;
            }
            if (log.isDebugEnabled()) {
                log.debug("[withOAuth401Retry] OAuth 401 命中（status={}），刷新 token 后重试一次", status);
            }
            refresher.handle401(failedAccessToken);
            return request.call();
        }
    }

    /**
     * CC original: isAuthError 判定（http.ts:124-129）。
     *
     * <p>status===401；或 also403Revoked && status===403 && body 为字符串且含
     * {@code 'OAuth token has been revoked'}。
     */
    static boolean isAuthError(Integer status, String body, Options opts) {
        if (status == null) return false;
        if (status == 401) return true;
        return opts != null && opts.also403Revoked()
            && status == 403
            && body != null
            && body.contains(OAUTH_TOKEN_REVOKED_MARKER);
    }
}
