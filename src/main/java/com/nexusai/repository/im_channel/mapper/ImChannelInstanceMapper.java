package com.nexusai.repository.im_channel.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.im_channel.entity.ImChannelInstanceRecord;

import java.util.List;

/**
 * {@code im_channel_instance} mapper（V302）。
 *
 * <p>一律用具名 default 方法，不把 {@link QueryWrapper} 泄到服务层
 * （对齐 {@code ChannelBindingMapper}；MyBatis-Flex 的 wrapper 无
 * {@code getParamValues()}，mock 时只能按方法名桩）。
 */
public interface ImChannelInstanceMapper extends BaseMapper<ImChannelInstanceRecord> {

    /** 全部实例（频道 tab 列表），按创建时间升序保证展示稳定。 */
    default List<ImChannelInstanceRecord> selectAllOrdered() {
        return selectListByQuery(QueryWrapper.create().orderBy("created_at", true));
    }

    /** 某平台的实例（"同一平台可加多个"，用于计数与"再加一个"文案）。 */
    default List<ImChannelInstanceRecord> selectListByPlatform(String platform) {
        return selectListByQuery(QueryWrapper.create().where("platform = ?", platform));
    }

    /** 全部已连接实例（启动时建连、发消息时选实例）。 */
    default List<ImChannelInstanceRecord> selectListConnected() {
        return selectListByQuery(QueryWrapper.create().where("status = ?", "connected"));
    }

    /** 按 id 查（至多一行）。 */
    default ImChannelInstanceRecord selectOneById(String id) {
        return selectOneByQuery(QueryWrapper.create().where("id = ?", id));
    }
}
