package com.nexusai.model.schedule.dto;

/** Schedule 类型 */
public enum ScheduleKind {
    cron,        // 需要 cron 表达式
    once,        // 需要 runAt（单次执行）
    interval     // 需要 intervalSeconds
}
