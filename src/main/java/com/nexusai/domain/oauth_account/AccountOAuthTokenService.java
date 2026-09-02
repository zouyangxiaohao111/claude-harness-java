package com.nexusai.domain.oauth_account;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import com.nexusai.repository.oauth_account.entity.AccountOAuthTokenRecord;
import com.nexusai.repository.oauth_account.mapper.AccountOAuthTokenMapper;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 账号级 OAuth token 持久化服务（对齐 CC {@code SecureStorageData.claudeAiOauth}，Java 用 DB）。
 *
 * <p>CC 把 OAuth 凭据存 keychain（auth.ts saveOAuthTokensIfNeeded 写
 * {@code storageData.claudeAiOauth}，getClaudeAIOAuthTokens 读同一 entry）；
 * Java 泛化为 provider|identity 复合键落 {@code oauth_account_tokens} 表，支持多 provider
 * 多账号并存（CC 单 key 单账号 claude.ai 专用）。
 *
 * <p><b>读/删 null-safe，写 fail-loud</b>：read/readLatest/readByAccessToken/delete 对不存在的
 * provider|identity 或 null 参数静默返回 null / 静默成功（读/删不存在键是合法查询）；
 * <b>save 则 fail-loud</b>——provider|identity 复合键任一 null/blank 即抛
 * {@link IllegalArgumentException}（复合键不完整即编程错误，禁止落无键 token，使
 * {@code read(provider,identity)}/RemoteTriggerTool/BearerTokenAuthFilter 反查永远可命中）。
 * 敏感字段（accessToken/refreshToken）绝不打印到日志（遵守 BudgetTracker 隐私红线同级原则）。
 */
@Service
public class AccountOAuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AccountOAuthTokenService.class);

    @Autowired private AccountOAuthTokenMapper accountOAuthTokenMapper;

    /** 构造 provider|identity 复合查询条件。 */
    private static QueryWrapper keyQuery(String provider, String identity) {
        return QueryWrapper.create().eq("provider", provider).eq("identity", identity);
    }

    /**
     * 读取指定 provider|identity 的 token 记录；provider/identity 任一 null 或不存在返回 null。
     */
    public AccountOAuthToken read(String provider, String identity) {
        if (provider == null || identity == null) {
            return null;
        }
        AccountOAuthTokenRecord r = accountOAuthTokenMapper.selectOneByQuery(keyQuery(provider, identity));
        if (r == null) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[AccountOAuthTokenService] read token key={} 命中", AccountOAuthToken.accountKey(provider, identity));
        }
        return r.toDomain();
    }

    /**
     * 读取指定 provider 下最近更新的 token 记录（S6 RemoteTriggerTool 补 Bearer 头消费）。
     *
     * <p>RemoteTriggerTool 执行期无 identity（identity 在授权完成后才写入），故按 {@code provider}
     * 反查「最近更新」（updated_at 降序取首条）的账号 token，等价 CC {@code getClaudeAIOAuthTokens}
     * 读单 key 凭据（auth.ts:1255 memoize 读 {@code claudeAiOauth}）——CC 只支持单 provider 单账号
     * （claude.ai），Java 泛化为 provider 维度「最近更新」（多账号并存时取 updated_at 最新）。
     *
     * <p>null-safe：provider null/blank 或该 provider 无任何记录返回 null。
     * 敏感字段（accessToken/refreshToken）绝不打印到日志（遵守 BudgetTracker 隐私红线同级原则）。
     */
    public AccountOAuthToken readLatest(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        AccountOAuthTokenRecord r = accountOAuthTokenMapper.selectOneByQuery(
            QueryWrapper.create().eq("provider", provider).orderBy("updated_at", false));
        if (r == null) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[AccountOAuthTokenService] readLatest provider={} 命中 identity={}",
                provider, r.getIdentity());
        }
        return r.toDomain();
    }

    /**
     * 按 accessToken 反查 token 记录（S5 BearerTokenAuthFilter 鉴权消费）。
     *
     * <p>过滤器只拿到 {@code Authorization: Bearer <token>} 字符串，无 provider|identity
     * 复合键，故须按 {@code access_token} 列反查定位账号 token 判过期。
     * 对齐 CC {@code getClaudeAIOAuthTokens} 读单 key 凭据（auth.ts:1289-1291），
     * Java 侧泛化为按 accessToken 反查（CC 单账号直接读固定 key，无此反查步骤）。
     *
     * <p>null-safe：accessToken 为 null/blank 或未命中均返回 null。
     * 敏感字段（accessToken/refreshToken）绝不打印到日志（遵守 BudgetTracker 隐私红线同级原则）。
     */
    public AccountOAuthToken readByAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        AccountOAuthTokenRecord r = accountOAuthTokenMapper.selectOneByQuery(
            QueryWrapper.create().eq("access_token", accessToken));
        if (r == null) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[AccountOAuthTokenService] readByAccessToken 命中 provider={} identity={}",
                r.getProvider(), r.getIdentity());
        }
        return r.toDomain();
    }

    /**
     * 保存 token 记录（insert 或 update，幂等）。
     *
     * <p>refreshToken/expiresAt 可空（GitHub 无 refresh_token / 不过期 token），
     * update 走 {@code updateByQuery(record, false, qw)}（ignoreNulls=false 显式写 NULL，
     * 覆盖旧值），createdAt 从既有行继承以防被 NULL 覆盖（对齐 McpOAuthTokenService:55-66
     * 的 insert/update 幂等 + createdAt 显式填充规避 MyBatis-Flex NULL 覆盖 DB DEFAULT）。
     *
     * <p><b>fail-loud（区别于 read/delete 的 null-safe）</b>：provider|identity 复合键任一
     * null/blank 即抛 {@link IllegalArgumentException}——复合键是 {@code read(provider,identity)}
     * /RemoteTriggerTool/BearerTokenAuthFilter 反查复用的唯一依据，缺失则落库即死数据。
     * 生产调用方（授权码流、AccountOAuthTokenRefresher 刷新链）均已保证
     * identity 非 null。
     *
     * @throws IllegalArgumentException token 为 null，或 provider/identity 任一 null/blank
     */
    public void save(AccountOAuthToken token) {
        if (token == null || token.getProvider() == null || token.getProvider().isBlank()
                || token.getIdentity() == null || token.getIdentity().isBlank()) {
            throw new IllegalArgumentException(
                "save 需要 provider|identity 复合键，provider/identity 不能为 null/blank");
        }
        AccountOAuthTokenRecord r = AccountOAuthTokenRecord.fromDomain(token);
        QueryWrapper qw = keyQuery(token.getProvider(), token.getIdentity());
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        AccountOAuthTokenRecord existing = accountOAuthTokenMapper.selectOneByQuery(qw);
        if (existing != null) {
            // createdAt 保留原始创建时间；refreshToken/expiresAt 用 ignoreNulls=false 覆盖（含 NULL 清除旧值）
            r.setCreatedAt(existing.getCreatedAt());
            r.setUpdatedAt(now);
            accountOAuthTokenMapper.updateByQuery(r, false, qw);
        } else {
            // 对齐 McpOAuthTokenService:createdAt 显式填充（MyBatis-Flex insert 会带 NULL 覆盖 DB DEFAULT）
            r.setCreatedAt(now);
            r.setUpdatedAt(now);
            accountOAuthTokenMapper.insert(r);
        }
        if (log.isDebugEnabled()) {
            log.debug("[AccountOAuthTokenService] save token key={}", AccountOAuthToken.accountKey(token.getProvider(), token.getIdentity()));
        }
    }

    /**
     * 删除指定 provider|identity 的 token 记录（provider/identity 任一 null 或不存在静默成功）。
     */
    public void delete(String provider, String identity) {
        if (provider == null || identity == null) {
            return;
        }
        accountOAuthTokenMapper.deleteByQuery(keyQuery(provider, identity));
        if (log.isDebugEnabled()) {
            log.debug("[AccountOAuthTokenService] delete token key={}", AccountOAuthToken.accountKey(provider, identity));
        }
    }
}
