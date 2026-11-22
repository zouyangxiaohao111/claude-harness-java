package com.nexusai.repository.schedule.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.schedule.entity.ScheduleRunRecord;

/**
 * {@code schedule_runs} 表 mapper（V306）。
 *
 * <p>查询需求都很简单（按任务 / 按时间倒序 / 按状态），直接用 {@code QueryWrapper}
 * 在 Service 侧拼，不在这里声明自定义方法 —— 与 {@link ScheduleMapper} 同款做法。
 */
public interface ScheduleRunMapper extends BaseMapper<ScheduleRunRecord> {
}
