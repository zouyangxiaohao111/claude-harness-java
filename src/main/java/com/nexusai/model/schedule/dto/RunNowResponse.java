package com.nexusai.model.schedule.dto;

/**
 * POST /api/v1/schedules/{id}/run 响应
 *
 * <p>CRON-B4-4（决策 #15 / OPD-EL-04）：REST runNow 同步返回 fire-then-delete 结果，与工具路径
 * one-shot 语义一致（CC original: CronCreateTool.ts:152 {@code "It will fire once then auto-delete."}）。
 *
 * @param executed 是否已触发（once 同步 fire 成功 / recurring triggerNow 成功）
 * @param deleted  是否已删除（仅 once：同步 fire 后 fire-then-delete 已删行；recurring 恒 false）
 * @param output   结果文案（英文，与既有 DTO 一致）
 */
public record RunNowResponse(
    boolean executed,
    boolean deleted,
    String output
) {}
