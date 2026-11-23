package com.nexusai.repository.connector.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.connector.entity.ConnectorCredentialRecord;

/**
 * {@code connector_credentials}（V336）Mapper。
 *
 * <p>由 {@code @MapperScan("com.nexusai.repository.*.mapper")} 自动注册；
 * 主键是 {@code connector_key}，{@code selectOneById} 直接可用。
 */
public interface ConnectorCredentialMapper extends BaseMapper<ConnectorCredentialRecord> {
}
