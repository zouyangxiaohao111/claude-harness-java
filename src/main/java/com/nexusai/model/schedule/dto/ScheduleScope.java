package com.nexusai.model.schedule.dto;

/**
 * s14-P1-5: Schedule 生命周期 scope · 对齐 CC cronScheduler.ts:246-247 (session vs file tasks)。
 *
 * <p>SESSION: 仅存活于一个 chat session 内，session 结束自动清理 (CC addSessionCronTask 仅存内存，
 * cronTasks.ts:211-218)。<b>[acc8 · 用户裁定] 新建任务的缺省 = SESSION</b> —— CC 真源
 * {@code CronCreateTool.ts:117} {@code durable = false}（默认值）+ {@code ScheduleCronTool/prompt.ts:78}
 * 「By default (durable: false) the job lives only in this Claude session … Only use durable: true
 * when the user explicitly asks for the task to persist」。
 *
 * <p>DURABLE: 落盘持久化到 DB，跨进程 / 重启保留 · CC original: CronTask.durable=true
 * (cronTasks.ts:63 durable 落盘)。
 *
 * <p>⚠️ 两者<b>都带 sessionId</b>（{@code ScheduleService.create} 无条件落库）：SESSION 下它是
 * <b>生命周期绑定</b>，DURABLE 下它是<b>归属对话 / 注入目标</b>（fire 时 transcript 归创建会话；
 * 创建会话已关 → headless 代跑）。生命周期判定始终以 scope 列为权威。
 *
 * <p>⛔ 缺省值只在<b>新建</b>路径成立（REST/前端）；<b>存量行</b>读侧一律以落库的 scope 列为权威
 * （见 {@code ScheduleService.lookupScope}），不因本缺省值改动而变化。
 */
public enum ScheduleScope {
    DURABLE,   // 落盘持久化（CC durable=true）· 需用户显式选择
    SESSION    // 缺省 — 仅 session 生命周期，session 结束自动 cleanup（CC durable=false 默认）
}
