package com.nexusai.repository.permissions_config.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.permissions_config.entity.PermissionsConfigRecord;

/**
 * MyBatis-Flex {@code permissions_config} 单行配置 mapper。
 *
 * <p>空体，继承 {@link BaseMapper} 获得 selectOneById/update/insert 等通用方法。
 * 由 {@code @MapperScan("com.nexusai.repository.*.mapper")} 通配自动扫描注册
 * （NexusAiApplication.java:11，覆盖 permissions_config.mapper，无需改注册）。
 */
public interface PermissionsConfigMapper extends BaseMapper<PermissionsConfigRecord> {
}
