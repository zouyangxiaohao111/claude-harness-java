package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth 401 错误处理器 · 对齐 CC {@code src/utils/auth.ts:1360-1392}
 * {@code handleOAuth401Error} / {@code handleOAuth401ErrorImpl}。
 *
 * <p><b>行为</b>（CC auth.ts:1360-1392）：
 * <ol>
 *   <li><b>per-token pending 去重</b>（auth.ts:1363-1369 {@code pending401Handlers}）：
 *       同 {@code failedAccessToken} 并发调用共享同一刷新 promise，避免多线程同时打 keychain。</li>
 *   <li><b>清缓存 + 重读</b>（auth.ts:1377-1378）：{@code clearOAuthTokenCache()} +
 *       {@code getClaudeAIOAuthTokensAsync()} 重读当前 tokens。</li>
 *   <li><b>无 refreshToken → false</b>（auth.ts:1380-1382）：无法刷新，返回失败。</li>
 *   <li><b>token 已异于 failedToken → true</b>（auth.ts:1385-1388）：他处已刷新
 *       （另一 tab 已处理），直接用新 token。</li>
 *   <li><b>否则强制刷新</b>（auth.ts:1391）：{@code checkAndRefreshOAuthTokenIfNeeded(0, true)}
 *       绕过本地过期检查强制刷新。</li>
 * </ol>
 *
 * <p><b>Java 落地</b>：{@link #handle401(String)} 同步返回 boolean（CC 返回
 * {@code Promise<boolean>}），内部用 {@link CompletableFuture} 镜像 pending 去重；
 * token 存储/刷新依赖通过 {@link TokenStore} / {@link TokenRefresher} 注入
 * （纯 API-key 模式不接线，调用方按需提供）。
 */
public final class OAuth401Refresher {

    private static final Logger log = LoggerFactory.getLogger(OAuth401Refresher.class);

    /** 当前 OAuth tokens 快照（仅需 accessToken + refreshToken）。 */
    public record OAuthTokens(String accessToken, String refreshToken) {}

    /** 重读当前 tokens · 等价 CC {@code clearOAuthTokenCache() + getClaudeAIOAuthTokensAsync()}。 */
    @FunctionalInterface
    public interface TokenStore {
        /** @return 当前 tokens；null = 无 token。 */
        OAuthTokens readCurrent();
    }

    /**
     * 强制刷新 · 等价 CC {@code checkAndRefreshOAuthTokenIfNeeded(0, true)}。
     *
     * @return true = 刷新后已有有效 token；false = 刷新失败。
     */
    @FunctionalInterface
    public interface TokenRefresher {
        boolean forceRefresh();
    }

    private final TokenStore tokenStore;
    private final TokenRefresher tokenRefresher;

    /** CC original: pending401Handlers per-token 去重表（auth.ts:1363）。 */
    private final Map<String, CompletableFuture<Boolean>> pendingHandlers = new ConcurrentHashMap<>();

    public OAuth401Refresher(TokenStore tokenStore, TokenRefresher tokenRefresher) {
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore is required");
        this.tokenRefresher = tokenRefresher == null ? () -> false : tokenRefresher;
    }

    /**
     * CC handleOAuth401Error（auth.ts:1360-1371）。
     *
     * <p>per-token pending 去重：同 failedAccessToken 已有在途刷新则复用其结果
     * （CC auth.ts:1363-1369 {@code pending401Handlers.get → return pending}）。
     *
     * @param failedAccessToken 被 401 拒绝的 access token
     * @return true = 现在有有效 token；false = 无法恢复。
     */
    public boolean handle401(String failedAccessToken) {
        // CC auth.ts:1363-1369 pending401Handlers get→set 在 JS 单线程下原子；Java 多线程暴露
        // TOCTOU（get==null 后 putIfAbsent 前两 caller 各自提交 supplyAsync → 双 keychain
        // 读/刷新，且 loser 的 whenComplete remove 会清掉 winner 在途条目）。
        // computeIfAbsent 原子化：同 failedAccessToken 只提交一个 promise、单次刷新，完成后
        // remove（对齐 CC auth.ts:1354-1355 "deduplicated to a single keychain read"）。
        //
        // ⚠ 清理回调必须放在 computeIfAbsent 返回后注册：若把 whenComplete 放进 mapping 函数，
        // FJ 池任务立即完成时回调内联触发 remove，正处 computeIfAbsent 临界区内写同 bin →
        // ConcurrentHashMap "Recursive update"。computeIfAbsent 只负责"在途"期间的原子去重。
        CompletableFuture<Boolean> promise = pendingHandlers.computeIfAbsent(failedAccessToken,
            k -> CompletableFuture.supplyAsync(() -> handle401Impl(failedAccessToken)));
        promise.whenComplete((r, t) -> pendingHandlers.remove(failedAccessToken));
        if (log.isDebugEnabled()) {
            log.debug("[OAuth401Refresher] handle401 加入 per-token dedup 并 join（同 token 并发共享同一刷新 promise，CC auth.ts:1363-1369）");
        }
        return promise.join();
    }

    /** CC handleOAuth401ErrorImpl（auth.ts:1373-1392）。 */
    private boolean handle401Impl(String failedAccessToken) {
        OAuthTokens current = tokenStore.readCurrent();
        if (current == null || current.refreshToken() == null) {
            if (log.isDebugEnabled()) {
                log.debug("[OAuth401Refresher] 无 refreshToken，无法刷新（CC auth.ts:1380-1382）");
            }
            return false;
        }
        if (!Objects.equals(current.accessToken(), failedAccessToken)) {
            if (log.isDebugEnabled()) {
                log.debug("[OAuth401Refresher] 当前 token 已异于 failedToken（他处已刷新），直接用（CC auth.ts:1385-1388）");
            }
            return true;
        }
        // 同 token 仍失败 → 强制刷新（绕过本地过期检查）
        return tokenRefresher.forceRefresh();
    }
}
