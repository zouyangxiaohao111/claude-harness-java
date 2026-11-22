package com.nexusai.repository.im_message.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.im_message.entity.ImScanTaskRecord;

/**
 * {@code im_scan_task} mapper（V318）· 「消息扫描」系统任务的单行开关。
 *
 * <p>只有一行（{@link com.nexusai.domain.im_message.ImScanTaskService#ROW_ID}），
 * 读写用 {@code BaseMapper} 自带的主键方法即可，不需要具名查询。
 */
public interface ImScanTaskMapper extends BaseMapper<ImScanTaskRecord> {
}
