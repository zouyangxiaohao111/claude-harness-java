package com.nexusai.infra.llm;

import com.nexusai.application.agent.QuerySource;

/**
 * 用量/费用归类维度 · 请求网关记账的「哪一类调用」字段（聚合与归类统计的键之一）。
 *
 * <p><b>WHY 独立于 {@link QuerySource}</b>：{@code QuerySource} 是 CC 对齐的 query 来源枚举
 * （USER / SUBAGENT / COMPACT / SESSION_MEMORY / REPL_MAIN_THREAD / SDK / WORKFLOW），只覆盖
 * 「经 query 循环发起的调用」；而本仓还存在大量<b>不经 query 循环</b>的旁路 LLM 调用
 * （标题生成、/btw、记忆检索、离开摘要、视觉分析、WebSearch/WebFetch、文档更新、权限解释器、
 * Yolo 分类器、prompt hook 等 —— 21 处直调 provider 的口子）。这些调用同样消耗上游 token/费用，
 * 但没有任何 QuerySource 值可表达 ⇒ 需要用本枚举统一归类，才能做到「无遗漏统计」。
 *
 * <p><b>归类来源（每类都有生产证据）</b>：
 * <ul>
 *   <li>{@link #MAIN} — 主 agent 交互回合（{@code LlmAgentLoop.run}，querySource USER /
 *       REPL_MAIN_THREAD / SDK）</li>
 *   <li>{@link #SUBAGENT} — Agent tool 子代理（{@code SubagentExecutor}，querySource SUBAGENT /
 *       WORKFLOW）</li>
 *   <li>{@link #TEAMMATE} — 进程内队友（{@code TeammateAgentContext} 身份）</li>
 *   <li>{@link #COMPACT} — 压缩调用（auto / reactive / manual-partial / fork 摘要；
 *       querySource COMPACT / SESSION_MEMORY）</li>
 *   <li>{@link #BACKGROUND} — 后台化 run（{@code CronIdleExecutor} 空闲调度、
 *       {@code MainSessionBackgroundService} 主会话后台任务）</li>
 *   <li>{@link #HOOK} — hook agent 查询（querySource HOOK_AGENT / thinkingConfig 注入路径）</li>
 *   <li>{@link #SIDE_CALL} — 旁路单发（标题生成、记忆检索、摘要、视觉、搜索抓取、文档更新等）</li>
 *   <li>{@link #CLASSIFIER} — 权限分类器（{@code YoloClassifierImpl} sideQuery）</li>
 *   <li>{@link #UNKNOWN} — 无归属信息（老调用方未接线时的兜底，<b>不伪造</b>）</li>
 * </ul>
 */
public enum UsageCategory {

    MAIN,
    SUBAGENT,
    TEAMMATE,
    COMPACT,
    BACKGROUND,
    HOOK,
    SIDE_CALL,
    CLASSIFIER,
    UNKNOWN;

    /**
     * {@link QuerySource} → 归类映射（经 query 循环的调用可自动归类）。
     * 无对应值 → {@link #UNKNOWN}
     */
    public static UsageCategory of(QuerySource querySource) {
        if (querySource == null) {
            return UNKNOWN;
        }
        return switch (querySource) {
            case USER, REPL_MAIN_THREAD, SDK -> MAIN;
            // fork 子 agent / ctx-agent(marble_origami) / workflow 子代理本质都是子代理派生查询
            // （QuerySource.FORK 的 javadoc 自述「fork 本质是 subagent」）
            case SUBAGENT, FORK, MARBLE_ORIGAMI, WORKFLOW -> SUBAGENT;
            case COMPACT, SESSION_MEMORY -> COMPACT;
            case HOOK_AGENT -> HOOK;
            // 会话级后台派生（记忆提取 / auto-dream fork）—— 非用户交互、非压缩，归类为后台任务
            case EXTRACT_MEMORIES, AUTO_DREAM -> BACKGROUND;
            default -> UNKNOWN;
        };
    }
}
