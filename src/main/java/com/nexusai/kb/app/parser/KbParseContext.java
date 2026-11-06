package com.nexusai.kb.app.parser;

import com.nexusai.application.agent.subagent.AgentContext;

/**
 * 解析上下文 · 一次解析所需的全部外部输入（不持有 Spring bean、不读全局状态）。
 *
 * <p><b>WHY 显式上下文而不是让解析器自己取</b>：解析器要能脱离 Spring 上下文做单测（本仓铁律
 * ⛔ 禁 {@code @SpringBootTest}）；把「写图片到哪个目录」「视觉调用能不能用」「归因给谁」都提成
 * 形参，单测就能用临时目录 + 假视觉调用跑完整路径。
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@code workDir} — 图片等副产物落盘目录（由调用方创建并负责清理）。为 null 时解析器自建
 *       系统临时目录（{@link java.nio.file.Files#createTempDirectory}），生命周期交调用方兜底。</li>
 *   <li>{@code visionEnabled} — 是否允许视觉补强。false 时 PDF 只抽文本层（供离线/无模型环境）。</li>
 *   <li>{@code sessionId} / {@code agentId} / {@code agentContext} — 视觉旁路调用的用量归属
 *       （透传给 {@code LlmSideCallClient}）。全 null = standalone 归属，不伪造会话。</li>
 * </ul>
 *
 * @param workDir       副产物（图片）落盘目录；null → 解析器自建临时目录
 * @param visionEnabled 是否允许视觉模型补强（PDF）
 * @param sessionId     视觉调用的会话归属（可 null）
 * @param agentId       视觉调用的代理归属（可 null）
 * @param agentContext  视觉调用的归因上下文（可 null）
 */
public record KbParseContext(
    java.nio.file.Path workDir,
    boolean visionEnabled,
    String sessionId,
    String agentId,
    AgentContext agentContext
) {

    /**
     * 默认上下文 · 允许视觉补强、无归属（standalone 记账）、自建临时目录。
     *
     * <p>供单测与「没有会话上下文」的编排路径使用（如重建索引任务）。
     */
    public static KbParseContext defaults() {
        return new KbParseContext(null, true, null, null, null);
    }

    /** 同配置但指定副产物目录（调用方已建好目录、负责清理时用）。 */
    public KbParseContext withWorkDir(java.nio.file.Path dir) {
        return new KbParseContext(dir, visionEnabled, sessionId, agentId, agentContext);
    }

    /** 关闭视觉补强 · PDF 仅抽文本层（无模型 / 离线环境）。 */
    public KbParseContext withoutVision() {
        return new KbParseContext(workDir, false, sessionId, agentId, agentContext);
    }
}
