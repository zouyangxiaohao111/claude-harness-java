package com.nexusai.application.agent.workflow;

import jakarta.annotation.Nullable;

/**
 * WorkflowService.launch 返回 · CC original: {@code Promise<{ runId: string; scriptPath?: string }>}
 * (service.ts:67)。
 *
 * @param runId      本次 run 唯一 id（taskRegistrar.register 产出；resume 复用外部 runId）·
 *                   CC original: runId (service.ts:67)
 * @param scriptPath inline 脚本持久化后的可复用路径（缺省 = 非 inline 入口或持久化失败降级）·
 *                   CC original: scriptPath? (service.ts:67)，可选
 */
public record LaunchResult(
        String runId,
        @Nullable String scriptPath
) {
}
