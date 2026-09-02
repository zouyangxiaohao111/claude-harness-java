package com.nexusai.infra.exception;

import com.nexusai.infra.exception.ConflictException;

/**
 * 定时任务数量超限 → HTTP 409（RFC 7807 Problem 带 errorCode "3"）。
 *
 * <p>CC 对齐：CronCreateTool.ts:97-104 validateInput 的 MAX_JOBS(50) 超限分支返回
 * {@code {result:false, message:'Too many scheduled jobs (max 50). Cancel one first.', errorCode:3}}。
 * 工具路径（CronCreateTool.validateInput）已对齐 errorCode3 门控；REST 直达用户路径
 * （POST /api/v1/schedules）旧实现抛 {@link IllegalStateException} → HTTP 500，偏离 CC 语义
 * （决策 #13）。本异常为 ConflictException 子类 → 全局处理器映射 409，并携带 errorCode "3"
 * 与工具路径 errorCode 语义一致。create 超限在实际运行中仅竞态兜底（工具路径已门控）。
 *
 * <p>errorCode 常量：对齐 CC 三元错误码（1=非法 cron / 2=一年内无匹配 / 3=任务数量超限 / 4=durable teammate）。
 */
public class MaxJobsExceededException extends ConflictException {

    /** CC CronCreateTool.ts:101 三元错误码 3（任务数量超限） */
    public static final String ERROR_CODE = "3";

    public MaxJobsExceededException(String message) {
        super(message);
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
