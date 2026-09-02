package com.nexusai.application.agent.compact.fork;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.Map;

/**
 * fork 原料（主线程会话捕获）· 对齐 CC {@code createCacheSafeParams(context)}
 * (Open-ClaudeCode/src/utils/forkedAgent.ts:131-141)。
 *
 * <p><b>WHY 存在（IMP-MV2-09 T9 · 域级唯一 HIGH）</b>: extract-memories / auto-dream 后台
 * fork 由 stop-hook 触发（CC stopHooks.ts:149-156），Java stop-hook 调用点（LlmAgentLoop）
 * 没有 post-sampling 上下文（PostSamplingContext）—— RES-C5 降级根源（stop-hook 无 post-sampling
 * 上下文，fork systemPrompt/userContext/systemContext 恒空 → 提取子代理无主系统提示 +
 * prompt-cache key 与主线程不一致，每轮 fork 全价计费）。本 record 承载当轮主线程
 * systemPrompt（fullSystemPrompt，<b>已含 appendSystemContext 并入的 systemContext</b>）/
 * userContext / systemContext / forkContextMessages（消息快照），在 LlmAgentLoop:5154
 * 调用点按会话捕获后经 StopHookPipeline 透传 extract/dream —— fork 载荷与主线程同值，
 * cache 共享恢复（design 03 §10-1）。
 *
 * <p><b>为什么 systemPrompt 传 fullSystemPrompt（已含 systemContext）而非 CC 的
 * pre-append 数组</b>: CC 的 fork 在自身 query() 内 <b>重新执行</b>
 * {@code appendSystemContext + prependUserContext}（query.ts:449-450/:660，forkedAgent.ts:545-556
 * 透传原始三参）；Java {@link ProductionForkedQuery} 发送边界只做
 * {@code splitSysPromptPrefix(params.systemPrompt())}（无 appendSystemContext 步骤）——因此
 * Java 侧必须传已并入 systemContext 的 fullSystemPrompt，fork 发送 blocks 才与主线程一致。
 *
 * <p><b>null 语义</b>: 无捕获（非主循环入口，如测试/直构）→ agents 保持现有兜底
 * （createMinimalCacheSafeParams / supplier 原样），<b>不 fail-loud</b>（捕获缺失 ≠ seam
 * 未注入，design 03 §10-1.4；与既有 D1 ⊕-4 / D2 P2 fail-loud 契约正交）。
 */
public record ForkRawMaterial(
        List<String> systemPrompt,
        Map<String, String> userContext,
        Map<String, String> systemContext,
        List<ChatMessageDto> forkContextMessages) {

    /** 紧凑构造器 · null 兜底（对齐 CacheSafeParams 同款防御；CC createCacheSafeParams 从不产 null）。 */
    public ForkRawMaterial {
        if (systemPrompt == null) {
            systemPrompt = List.of();
        }
        if (userContext == null) {
            userContext = Map.of();
        }
        if (systemContext == null) {
            systemContext = Map.of();
        }
        if (forkContextMessages == null) {
            forkContextMessages = List.of();
        }
    }

    /**
     * supplied（生产 supplier 载荷）优先合并 · 同 SessionMemoryService.mergeSystemPrompt
     * （RES-C5 "supplied 优先"语义 · REQ-C5-1）：supplied 非空（未来接线方注入完整组装数组）
     * 保留原值；空 → 用 ForkRawMaterial 主线程原料（forkedAgent.ts:131 createCacheSafeParams）。
     *
     * @param supplied supplier 注入值（生产 toolUseContext 载体，三段恒空占位）
     * @param captured ForkRawMaterial 主线程原料（null/无捕获 → 空）
     * @return supplied 非空 → supplied；否则 captured
     */
    public static List<String> mergeSystemPrompt(List<String> supplied, List<String> captured) {
        return supplied != null && !supplied.isEmpty() ? supplied
            : (captured != null ? captured : List.of());
    }

    /**
     * userContext / systemContext 合并 · 同 {@link #mergeSystemPrompt} 的 supplied 优先语义
     * （cache key 组成部分 · forkedAgent.ts:61/63）。
     *
     * @param supplied supplier 注入值（生产恒 Map.of() 占位）
     * @param captured ForkRawMaterial 主线程原料（null/无捕获 → 空）
     * @return supplied 非空 → supplied；否则 captured
     */
    public static Map<String, String> mergeContext(Map<String, String> supplied, Map<String, String> captured) {
        return supplied != null && !supplied.isEmpty() ? supplied
            : (captured != null ? captured : Map.of());
    }
}