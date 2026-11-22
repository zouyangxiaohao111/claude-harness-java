package com.nexusai.repository.im_channel.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.im_channel.entity.ImConfigRecord;
import org.apache.ibatis.annotations.Mapper;

/** {@code im_config}（单行）。只按固定主键读写，不需要自定义查询。 */
@Mapper
public interface ImConfigMapper extends BaseMapper<ImConfigRecord> {}
