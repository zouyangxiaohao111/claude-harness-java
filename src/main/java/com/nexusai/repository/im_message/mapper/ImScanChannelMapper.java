package com.nexusai.repository.im_message.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.im_message.entity.ImScanChannelRecord;

import java.util.List;

/**
 * {@code im_scan_channel} mapper（V315）· 扫描渠道配置。
 *
 * <p>具名 default 方法，不把 {@link QueryWrapper} 泄到服务层（对齐本包其它 mapper）。
 */
public interface ImScanChannelMapper extends BaseMapper<ImScanChannelRecord> {

    /** 全部配置（按实例 id 排序，UI 列表用）。 */
    default List<ImScanChannelRecord> selectAllOrdered() {
        return selectListByQuery(QueryWrapper.create().orderBy("instance_id", true));
    }

    /** 只取启用中的（调度器每轮用）。 */
    default List<ImScanChannelRecord> selectListEnabled() {
        return selectListByQuery(QueryWrapper.create()
            .where("enabled = 1")
            .orderBy("instance_id", true));
    }

    /** 按实例查（至多一行；主键就是 instance_id）。 */
    default ImScanChannelRecord selectOneByInstanceId(String instanceId) {
        return selectOneByQuery(QueryWrapper.create().where("instance_id = ?", instanceId));
    }
}
