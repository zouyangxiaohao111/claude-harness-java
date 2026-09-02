package com.nexusai.application.agent.workflow.agent;

/**
 * registry 找不到匹配 adapter 时抛出 · CC original: {@code AdapterNotFoundError}
 * (Open-ClaudeCode/packages/workflow-engine/src/agentAdapter.ts:82-87)。
 *
 * <pre>{@code
 * export class AdapterNotFoundError extends Error {
 *   constructor(message: string) {
 *     super(message)
 *     this.name = 'AdapterNotFoundError'
 *   }
 * }
 * }</pre>
 *
 * <p>配置错误（无匹配规则 + 无 default）不重试（hooks.agent 在 try 外 resolve，
 * 对齐 hooks.ts:183）；P0 空注册表恒抛本异常（fail-closed，DEC-P0-04）。
 */
public class AdapterNotFoundError extends RuntimeException {

    public AdapterNotFoundError(String message) {
        super(message);
    }

    public AdapterNotFoundError(String message, Throwable cause) {
        super(message, cause);
    }
}
