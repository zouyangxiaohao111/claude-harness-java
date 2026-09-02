package com.nexusai.repository.oauth_account.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.oauth_account.entity.AccountOAuthTokenRecord;

/**
 * 账号级 OAuth token 持久化 mapper（{@code oauth_account_tokens} 表）。
 *
 * <p>空体，继承 {@link BaseMapper} 获得 selectOneByQuery/deleteByQuery/updateByQuery/insert
 * 等复合键可用的通用方法。由 {@code @MapperScan("com.nexusai.repository.*.mapper")}
 * 通配自动扫描注册（NexusAiApplication.java:11，覆盖 oauth_account.mapper，无需改注册）。
 */
public interface AccountOAuthTokenMapper extends BaseMapper<AccountOAuthTokenRecord> {
}
