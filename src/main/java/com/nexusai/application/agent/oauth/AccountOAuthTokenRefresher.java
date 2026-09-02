package com.nexusai.application.agent.oauth;

import com.nexusai.application.agent.api.OAuth401Refresher;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 账号级 OAuth 401 强制刷新器（provider-aware）· 实现
 * {@link OAuth401Refresher.TokenRefresher}，镜像 CC
 * {@code checkAndRefreshOAuthTokenIfNeededImpl} 锁内
 * {@code refreshOAuthToken(lockedTokens.refreshToken, {scopes})} +
 * {@code saveOAuthTokensIfNeeded(refreshedTokens)}（utils/auth.ts:1531-1539）。
 *
 * <p><b>与 MCP 域 {@code McpAuth.asTokenRefresher} 的分层</b>：MCP 域刷新走
 * {@code McpAuth.refreshAuthorization}（discoverAuthServer + {@code McpAuth.Tokens} primitive-long），
 * 本类走账号域 {@link OAuthTokenClient#refreshTokens} → {@link OAuthTokenResponse}
 * （nullable expiresAt/refreshToken），并落 {@link AccountOAuthTokenService}（provider|identity 复合键）。
 * 两层是「MCP 域 primitive-long Tokens vs 账号域 nullable OAuthTokenResponse」的分层，不可合并。
 *
 * <p><b>provider 感知 = 数据驱动（无 provider enum switch）</b>：本类不判断 provider 名，
 * 只问 {@link OAuthProviderConfig} 的两个语义声明（{@code accessTokenExpires()} /
 * {@code supportsRefreshToken()}）经 {@link OAuthTokenResponseParser} 决定刷新响应的
 * expiresAt/refreshToken 解析，与 CC auth.ts:1459/1464 的
 * {@code if (!tokens?.refreshToken) return false} 门完全一致——「无 refresh_token → 不刷新」
 * 由数据（存储中 refreshToken 是否为空）驱动，GitHub（无 refresh_token）自然走 false 重新授权，
 * Google（有 refresh_token）自然激活真实 refresh_token grant 刷新。
 *
 * <p><b>刷新响应缺 refresh_token → 保留旧值</b>（CC client.ts:178
 * {@code refresh_token: newRefreshToken = refreshToken}）：Google 刷新通常不再下发 refresh_token，
 * 必须保留旧 refreshToken 否则后续刷新链断裂（旧值被 null 覆盖即「一次性刷新」）。
 */
public final class AccountOAuthTokenRefresher implements OAuth401Refresher.TokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(AccountOAuthTokenRefresher.class);

    /**
     * refresh 锁重试上限 · CC original: {@code const MAX_RETRIES = 5}
     * (Open-ClaudeCode/src/utils/auth.ts:1451)。
     */
    private static final int MAX_RETRIES = 5;

    /**
     * 按 provider 键的 refresh 锁表 · 泛化 CC {@code lockfile.lock(claudeDir)}
     * (auth.ts:1488)。CC 是单 claudeDir 的跨进程文件锁，本项目是单服务端 JVM
     * （非多 CLI 进程），故用单 JVM {@link ReentrantLock} 语义等价，按 provider 键
     * （多 provider 泛化，CC 单 provider 单账号）。static 共享跨实例，锁不随实例泄漏。
     */
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /**
     * 锁竞争 backoff 默认值（1s + 随机 0-1s）· CC original:
     * {@code await sleep(1000 + Math.random() * 1000)} (auth.ts:1496)。
     */
    private static final LongSupplier DEFAULT_BACKOFF_MS =
        () -> 1000L + ThreadLocalRandom.current().nextLong(1000L);

    private final OAuthProviderConfig config;
    private final OAuthTokenClient tokenClient;
    private final AccountOAuthTokenService tokenService;
    private final LongSupplier backoffMs;

    /**
     * @param config       provider 配置（决定 tokenEndpoint/refresh 参数与 expiresAt/refreshToken
     *                     解析语义；不可为 null）
     * @param tokenClient  账号级 token 刷新客户端（null 时回退默认 {@link OAuthTokenClient}）
     * @param tokenService 账号级 token 持久化服务（不可为 null）
     */
    public AccountOAuthTokenRefresher(OAuthProviderConfig config, OAuthTokenClient tokenClient,
            AccountOAuthTokenService tokenService) {
        this(config, tokenClient, tokenService, DEFAULT_BACKOFF_MS);
    }

    /**
     * @param config        provider 配置（同 3 参构造器）
     * @param tokenClient   账号级 token 刷新客户端（同 3 参构造器）
     * @param tokenService  账号级 token 持久化服务（同 3 参构造器）
     * @param backoffMs     锁竞争退避毫秒供应器（测试注入 0/小值避免真睡 1-2s；null 回退默认）
     */
    public AccountOAuthTokenRefresher(OAuthProviderConfig config, OAuthTokenClient tokenClient,
            AccountOAuthTokenService tokenService, LongSupplier backoffMs) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.tokenClient = tokenClient == null ? new OAuthTokenClient() : tokenClient;
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService is required");
        this.backoffMs = backoffMs == null ? DEFAULT_BACKOFF_MS : backoffMs;
    }

    /**
     * 强制刷新（CC {@code checkAndRefreshOAuthTokenIfNeeded(0, true)} 等价）。
     *
     * <p><b>刷新锁 + 重试 + 竞态恢复</b>（对齐 CC auth.ts:1427-1556
     * {@code checkAndRefreshOAuthTokenIfNeededImpl}）：
     * <ol>
     *   <li><b>pre-lock 读</b>：{@code readLatest(provider)}；无 token 或 refreshToken 空 → false
     *       （CC auth.ts:1464 {@code !tokens?.refreshToken → false}）；</li>
     *   <li><b>获取锁</b>：按 provider 键 {@link #LOCKS} 取 {@link ReentrantLock}，{@code tryLock}
     *       失败即锁被并发持有 → backoff 重试至 {@link #MAX_RETRIES}=5
     *       （CC auth.ts:1488-1516 lock_retry / lock_retry_limit_reached）；</li>
     *   <li><b>锁内双检</b>：重读 {@code readLatest(provider)}；refreshToken 空 → false；已不过期 → false
     *       （race resolved，另一并发已刷新，本调用不二次刷新；
     *       CC auth.ts:1522-1527 {@code tengu_oauth_token_refresh_race_resolved}）；</li>
     *   <li><b>执行刷新</b>：POST refresh_token grant + 写回（原逻辑不变，CC auth.ts:1531-1539
     *       {@code refreshOAuthToken} + {@code saveOAuthTokensIfNeeded}）；</li>
     *   <li><b>失败竞态恢复</b>：catch 内重读 {@code readLatest}，若已是非过期 token → true
     *       （race recovered，CC auth.ts:1545-1556
     *       {@code tengu_oauth_token_refresh_race_recovered}），否则 false；</li>
     *   <li><b>finally unlock</b>（CC auth.ts:1557-1560 {@code await release()}）。</li>
     * </ol>
     *
     * <p>数据驱动流程（无 provider enum switch）不变：刷新响应解析 provider-aware；刷新响应缺
     * refresh_token → 保留旧值（CC client.ts:178）。
     *
     * <p><b>单进程简化</b>：CC {@code lockfile.lock(claudeDir)} 是跨进程文件锁；本项目单服务端 JVM，
     * 单 JVM {@link ReentrantLock} 语义等价（锁只串行化 JVM 内并发，非跨进程）。
     *
     * @return true=刷新后已有有效 token（成功写回 / race recovered）；
     *         false=无 refresh_token / 锁重试超限 / race resolved / 刷新失败且无并发恢复
     */
    @Override
    public boolean forceRefresh() {
        String provider = config.provider();
        // ① pre-lock 读（CC auth.ts:1459-1465：无 refreshToken → false，需重新授权）
        AccountOAuthToken current = tokenService.readLatest(provider);
        if (current == null || current.getRefreshToken() == null
                || current.getRefreshToken().isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[AccountOAuthTokenRefresher] provider={} 无 refresh_token，无法刷新"
                        + "（CC auth.ts:1464 !tokens?.refreshToken → false，需重新授权）",
                    provider);
            }
            return false;
        }

        // ② 获取锁（CC auth.ts:1488-1516 lockfile.lock + ELOCKED 重试至 MAX_RETRIES=5）
        ReentrantLock lock = LOCKS.computeIfAbsent(provider, k -> new ReentrantLock());
        boolean acquired = false;
        int retryCount = 0;
        while (!acquired) {
            acquired = lock.tryLock();
            if (acquired) {
                break;
            }
            if (retryCount < MAX_RETRIES) {
                retryCount++;
                long backoff = backoffMs.getAsLong();
                if (log.isDebugEnabled()) {
                    log.debug("[AccountOAuthTokenRefresher] provider={} 刷新锁被并发持有，重试 {}/{} "
                            + "backoff={}ms（CC auth.ts:1496 lock_retry）",
                        provider, retryCount, MAX_RETRIES, backoff);
                }
                sleepUninterruptibly(backoff);
            } else {
                log.warn("[AccountOAuthTokenRefresher] provider={} 刷新锁重试达上限 MAX_RETRIES={}，"
                        + "放弃刷新（CC auth.ts:1515 lock_retry_limit_reached）",
                    provider, MAX_RETRIES);
                return false;
            }
        }

        try {
            // ③ 锁内双检（CC auth.ts:1522-1527：另一并发可能已刷新，已不过期则不再二次刷新，返回 false）
            AccountOAuthToken lockedToken = tokenService.readLatest(provider);
            if (lockedToken == null || lockedToken.getRefreshToken() == null
                    || lockedToken.getRefreshToken().isBlank()) {
                return false;
            }
            boolean notExpired = !config.isTokenExpired(lockedToken.getExpiresAt());
            if (notExpired) {
                if (log.isDebugEnabled()) {
                    log.debug("[AccountOAuthTokenRefresher] provider={} 锁内双检发现 token 已并发刷新"
                            + "（已不过期={}），跳过二次刷新返回 false"
                            + "（CC auth.ts:1526-1527 race_resolved）",
                        provider, notExpired);
                }
                return false;
            }

            // ④ 执行刷新（原逻辑不变：refresh grant + provider-aware 解析 + 写回）
            String oldRefreshToken = lockedToken.getRefreshToken();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("grant_type", "refresh_token");
            params.put("refresh_token", oldRefreshToken);
            params.put("client_id", config.clientId());
            if (config.clientSecret() != null && !config.clientSecret().isBlank()) {
                // confidential client client_secret_post（对齐授权码交换的 client_secret 语义）
                params.put("client_secret", config.clientSecret());
            }
            if (lockedToken.getScope() != null && !lockedToken.getScope().isBlank()) {
                // CC refreshOAuthToken 请求体带 scope（client.ts:154-163）
                params.put("scope", lockedToken.getScope());
            }

            OAuthTokenResponse resp = tokenClient.refreshTokens(config.tokenEndpoint(), params, config);

            // CC client.ts:178 refresh_token: newRefreshToken = refreshToken —— 刷新响应缺
            // refresh_token 时保留旧值（Google refresh 通常不再下发 refresh_token，必须保留）
            String newRefreshToken = resp.refreshToken() != null
                ? resp.refreshToken() : oldRefreshToken;

            AccountOAuthToken updated = new AccountOAuthToken();
            updated.setProvider(lockedToken.getProvider());
            updated.setIdentity(lockedToken.getIdentity());
            updated.setAccessToken(resp.accessToken());
            updated.setRefreshToken(newRefreshToken);
            updated.setExpiresAt(resp.expiresAt());
            updated.setScope(resp.scope() != null && !resp.scope().isBlank()
                ? resp.scope() : lockedToken.getScope());
            tokenService.save(updated);

            if (log.isDebugEnabled()) {
                log.debug("[AccountOAuthTokenRefresher] 刷新成功 provider={} 保留旧refreshToken={} "
                        + "有expiresAt={}",
                    provider, resp.refreshToken() == null, resp.expiresAt() != null);
            }
            return true;
        } catch (Exception e) {
            // ⑤ 失败竞态恢复（CC auth.ts:1545-1556：catch 重读，并发已恢复则返回 true）
            AccountOAuthToken currentTokens = tokenService.readLatest(provider);
            if (currentTokens != null && !config.isTokenExpired(currentTokens.getExpiresAt())) {
                if (log.isDebugEnabled()) {
                    log.debug("[AccountOAuthTokenRefresher] provider={} 刷新抛错但并发已恢复为非过期 "
                            + "token，返回 true（CC auth.ts:1554 race_recovered）",
                        provider);
                }
                return true;
            }
            log.warn("[AccountOAuthTokenRefresher] 刷新失败 provider={}: {}",
                provider, e.getMessage() == null ? e.toString() : e.getMessage());
            return false;
        } finally {
            // ⑥ 释放锁（CC auth.ts:1557-1560 await release()）
            lock.unlock();
        }
    }

    /** 无中断退避睡眠（保持中断标志，CC {@code await sleep(...)} 无中断语义）。 */
    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
