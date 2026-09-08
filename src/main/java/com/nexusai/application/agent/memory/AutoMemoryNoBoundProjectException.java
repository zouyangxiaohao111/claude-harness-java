package com.nexusai.application.agent.memory;

/**
 * auto-memory 无绑定项目业务异常（决策 2026-09-08：DB 查不到绑定＝错误，fail loud）。
 *
 * <p><b>触发条件</b>：组装 system prompt 的 memory 段时，会话（{@code streamSessionId} 非空）在
 * DB 中查不到有效绑定项目 —— {@code sessions.main_project_id} 为空，或对应 {@code projects.path}
 * 为空/无效（DB 确实无绑定）。此时 <b>不允许</b>回落把配置主目录（{@code ~/.nexusai}，即
 * {@code AutoMemPaths.currentSessionProjectRoot()} 的兜底值）当作项目去派生
 * {@code projects/<slug>/memory} 假目录（A′ 已在路径层拦截，本异常把「出现即错误」提升为
 * 会话级显式信号）。
 *
 * <p><b>消费约定</b>：该异常在 memory 段组装处（LlmAgentLoop 组装 LoadMemoryPrompt 前）抛出并被
 * <b>当场捕获</b> —— 不得向上传播击穿整个用户 turn（降级为该轮不注入 auto 记忆段，错误显著记录
 * log.error）。其它直接消费方（如未走 LlmAgentLoop 守卫的加载点）可在自身边界 catch 后按
 * MemoryPromptBuilder null 语义跳过。
 *
 * @param sessionId 会话 DB 主键（{@code "sess-..."} · LlmAgentLoop.streamSessionId），随消息携带便于
 *                  fail-loud 日志定位
 */
public class AutoMemoryNoBoundProjectException extends RuntimeException {

    private final String sessionId;

    public AutoMemoryNoBoundProjectException(String sessionId) {
        super("会话未绑定项目（sessions.main_project_id / projects.path 缺失），"
            + "auto-memory 无有效 per-project 目录，拒绝以配置主目录(~/.nexusai)充当项目假目录; "
            + "sessionId=" + sessionId);
        this.sessionId = sessionId;
    }

    /** 会话 DB 主键（{@code "sess-..."}）；理论非 null（构造前已判 blank）。 */
    public String sessionId() {
        return sessionId;
    }
}
