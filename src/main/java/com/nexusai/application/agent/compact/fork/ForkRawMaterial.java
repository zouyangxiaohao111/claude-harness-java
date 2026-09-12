package com.nexusai.application.agent.compact.fork;

import com.nexusai.application.agent.tool.ToolUseContext;
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
 * systemPrompt（<b>pre-append</b> 组装段数组）/ userContext / systemContext /
 * forkContextMessages（消息快照），在 LlmAgentLoop stop-hook 捕获点按会话捕获后经
 * StopHookPipeline 透传 extract/dream —— fork 载荷与主线程同值，cache 共享恢复（design 03 §10-1）。
 *
 * <p><b>[E-1a] systemPrompt 是 CC 语义的 pre-append 形态</b>（组装段数组，含 boundary 段，
 * <b>未经</b> {@code appendSystemContext}）: 与 CC 一致 —— CC 的 fork 在自身 query() 内
 * <b>重新执行</b> {@code appendSystemContext + prependUserContext}（query.ts:449-450/:660，
 * forkedAgent.ts:545-556 透传原始三参）。Java {@link ProductionForkedQuery} 发送边界现同样
 * <b>先</b> {@code appendSystemContext(systemPrompt, systemContext)} <b>再</b>
 * {@code splitSysPromptPrefix}（append 使用点，单一 static 实现）——因此本字段存 pre-append
 * 值 + 独立 {@link #systemContext} map 即可让 fork 发送 blocks 与主线程一致。
 *
 * <p><b>为什么不能存 post-append</b>: {@code appendSystemContext} <b>非幂等</b>（每次调用把
 * systemContext map join 成字符串追加为末尾元素）；存 post-append ⇒ 使用点再 append 一次 ⇒
 * 末尾多一段 ⇒ 发送 blocks 与主线程不一致 ⇒ prompt cache 永不命中（IMP-MV2-09 T9 同类根因）。
 *
 * <p><b>null 语义</b>: 无捕获（非主循环入口，如测试/直构）→ agents 保持现有兜底
 * （createMinimalCacheSafeParams / supplier 原样），<b>不 fail-loud</b>（捕获缺失 ≠ seam
 * 未注入，design 03 §10-1.4；与既有 D1 ⊕-4 / D2 P2 fail-loud 契约正交）。
 */
public record ForkRawMaterial(
        List<String> systemPrompt,
        Map<String, String> userContext,
        Map<String, String> systemContext,
        List<ChatMessageDto> forkContextMessages,
        // [SM-fork 模型直传 2026-09-10] 当轮会话运行模型（provider 全名，如
        //   deepseek/deepseek-v4.1-flash-…）· = CC {@code toolUseContext.options.mainLoopModel}
        //   （sessionMemory.ts:411-418 从 toolUseContext.options 取 mainLoopModel 组装 fork
        //   systemPrompt；extractMemories.ts:372 createCacheSafeParams(context) 同源）。
        //   WHY: 生产 supplier 的 toolUseContext 由 buildProductionCacheSafeParams 8 参构造 →
        //   effectiveModelName 缺省 null → ProductionForkedQuery 会话模型直传解析取不到模型 →
        //   model=null → provider 回落 MockLlmProvider（假回复 / 永不 Edit）。null = 未捕获
        //   （非主循环入口/测试直构）→ 不造字段，回落既有全局 supplier 语义。
        String effectiveModelName) {

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

    /**
     * [SM-fork 模型直传 2026-09-10] 合并 fork 用 toolUseContext：保留 supplied 的真实工具集
     * （{@code buildProductionCacheSafeParams} 唯一有效载荷 · {@code toolRegistry.all()}），
     * 并把会话运行模型写入 {@code effectiveModelName}。
     *
     * <p><b>WHY（生产事故回归）</b>：CC 的 fork 上下文（{@code createCacheSafeParams(context)}，
     * forkedAgent.ts:131-141 取 {@code context.toolUseContext}）<b>同时</b>携带
     * {@code options.tools} 与 {@code options.mainLoopModel}。Java 侧这两个维度来自不同源：
     * 工具集 = 生产 supplier 的合成上下文（8 参构造 → {@code effectiveModelName}=null），
     * 会话模型 = psContext/{@link ForkRawMaterial} 的会话捕获。若只传 supplier 上下文，
     * {@code ProductionForkedQuery} 的会话模型直传分支（:239-256）读不到模型 → 回落全局
     * supplier（settings.json 无 model → model=null → {@code ProviderConfig.empty()}）→
     * provider 回落 {@code MockLlmProvider}（假回复 / 永不 Edit，summary.md 冻结不前进）。
     *
     * <p><b>为什么用一个 wither 而不是换父上下文</b>：fork 的权限/abort/工具集语义由 supplier
     * 上下文（既有生产契约）承载，只补齐缺省的 {@code effectiveModelName} 一个维度，
     * 其余字段零变化（{@link ToolUseContext#withEffectiveModelName(String)} 透传全部字段）。
     *
     * @param suppliedCtx      supplier 注入的 fork 上下文（生产 = buildProductionCacheSafeParams）
     * @param sessionModelName 会话运行模型（provider 全名）；null/blank → 原样返回（不造字段）
     * @return 带 {@code effectiveModelName} 的 fork 上下文
     */
    public static ToolUseContext forkToolUseContext(ToolUseContext suppliedCtx, String sessionModelName) {
        if (suppliedCtx == null) {
            return null;
        }
        // withEffectiveModelName(null) → 原样返回（ToolUseContext:1409-1411，不造字段）
        return suppliedCtx.withEffectiveModelName(sessionModelName);
    }

    /**
     * {@link #forkToolUseContext(ToolUseContext, String)} 的会话上下文重载 ·
     * 取 {@code sessionCtx.effectiveModelName()}（= CC {@code toolUseContext.options.mainLoopModel}，
     * 由 LlmAgentLoop hook/loop 侧用 {@code state.currentModel()} 写入）。
     *
     * @param suppliedCtx supplier 注入的 fork 上下文（工具集载体）
     * @param sessionCtx  会话 ToolUseContext（模型来源；null → 不造字段）
     * @return 带会话模型的 fork 上下文
     */
    public static ToolUseContext forkToolUseContext(ToolUseContext suppliedCtx, ToolUseContext sessionCtx) {
        return forkToolUseContext(suppliedCtx,
            sessionCtx != null ? sessionCtx.effectiveModelName() : null);
    }

    /**
     * {@link #forkToolUseContext(ToolUseContext, String)} 的 stop-hook 原料重载 ·
     * 取 {@link #effectiveModelName()}（extract-memories / auto-dream 无 post-sampling 上下文，
     * 会话模型经本 record 从 LlmAgentLoop 捕获点透传 · CC createCacheSafeParams(context) 同源）。
     *
     * @param suppliedCtx supplier 注入的 fork 上下文（工具集载体）
     * @param raw         stop-hook 捕获的 fork 原料（null → 不造字段）
     * @return 带会话模型的 fork 上下文
     */
    public static ToolUseContext forkToolUseContext(ToolUseContext suppliedCtx, ForkRawMaterial raw) {
        return forkToolUseContext(suppliedCtx, raw != null ? raw.effectiveModelName() : null);
    }
}