package com.nexusai.model.schedule.dto;

/**
 * s14-P1-5: Schedule 生命周期 scope · 对齐 CC cronScheduler.ts:246-247 (session vs file tasks)。
 *
 * <p>DURABLE: 落盘持久化到 DB，跨进程 / 重启保留 (default) · CC original: CronTask.durable=true
 * (cronTasks.ts:63 durable 落盘)。
 * SESSION: 仅存活于一个 chat session 内，session 结束自动清理 (CC addSessionCronTask 仅存内存，
 * cronTasks.ts:211-218)。
 */
public enum ScheduleScope {
    DURABLE,   // 默认 — 落盘持久化（CC durable）
    SESSION    // 仅 session 生命周期 — session 结束自动 cleanup
}
