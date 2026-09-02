package com.nexusai.repository.session.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.session.entity.AttachmentRecord;

/**
 * attachments 表 mapper（MyBatis-Flex 泛型 BaseMapper，仿 {@code MessageMapper}）。
 * V64 建表；业务入口 {@code AttachmentService}（register/getContent/getPath）。
 */
public interface AttachmentMapper extends BaseMapper<AttachmentRecord> {

}
