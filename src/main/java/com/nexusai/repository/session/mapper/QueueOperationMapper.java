package com.nexusai.repository.session.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.session.entity.QueueOperationRecord;

/**
 * queue_operation 表 mapper（MyBatis-Flex 泛型 BaseMapper，仿 {@code AttachmentMapper}）。
 *
 * <p>V68 建表；业务入口 {@code QueueAuditService}（insertSelective 落库，created_at 走 DB 默认）。
 *
 * <p>由 {@code @MapperScan("com.nexusai.repository.*.mapper")} 通配自动扫描注册
 * （NexusAiApplication:11 —— 单段通配先例，见 PermissionConfigMapper/AccountOAuthTokenMapper JavaDoc）。
 */
public interface QueueOperationMapper extends BaseMapper<QueueOperationRecord> {

}
