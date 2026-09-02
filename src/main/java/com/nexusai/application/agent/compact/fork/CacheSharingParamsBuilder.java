package com.nexusai.application.agent.compact.fork;

import com.nexusai.application.agent.prompt.EffectiveSystemPromptBuilder;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.SystemPromptParts;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * fork 缓存共享参数生产者 · 对齐 CC {@code getCacheSharingParams}
 * （Open-ClaudeCode/src/commands/compact/compact.ts:250-287）。
 *
 * <p><b>WHY 存在（RES-②）</b>: Anthropic API 的 prompt cache key 由 system prompt / tools /
 * model / messages(prefix) / thinking config 组成。fork（压缩摘要）要与主线程共享缓存，
 * 必须用与主线程完全一致的 5 项构建 {@link CacheSafeParams}。本类复刻 CC
 * {@code getCacheSharingParams} 的组装链，产物交给 {@link CacheSafeParamsHolder} 槽位
 * （LlmAgentLoop autoCompact 触发点）→ StreamCompactSummary cacheSafeParamsSupplier 消费。
 *
 * <p><b>CC 组装链复刻</b>（compact.ts:250-287）:
 * <ol>
 *   <li>{@code defaultSysPrompt = await getSystemPrompt(tools, model, dirs, mcpClients)}
 *       （:259-263）→ Java 由调用方注入 {@code defaultAssemble} Supplier
 *       （LlmAgentLoop 经 sysPromptAssembler.assemble + buildSystemPromptAssemblyInput 惰性组装）</li>
 *   <li>{@code systemPrompt = buildEffectiveSystemPrompt({customSystemPrompt, defaultSystemPrompt,
 *       appendSystemPrompt, ...})}（:265-275）→ {@link EffectiveSystemPromptBuilder#build}
 *       （custom 短路 I-13：custom 非空时 default 完全不出现在结果）</li>
 *   <li>{@code [userContext, systemContext] = await Promise.all([getUserContext(), getSystemContext()])}
 *       （:277-281）→ {@link SystemPromptContextProvider#fetchSystemPromptParts} 三路并行
 *       （userContext/systemContext 从 {@link SystemPromptParts} 取出）</li>
 *   <li>systemContext 并入 systemPrompt（compact.ts:265-275 CC buildEffectiveSystemPrompt 语义 +
 *       Java api.ts:437-447 appendSystemContext）</li>
 *   <li>{@code toolUseContext: context}（:285）→ 入参 toolUseContext；{@code forkContextMessages}
 *       （:286）→ 入参 forkContextMessages（压缩前消息）</li>
 * </ol>
 *
 * <p><b>systemPrompt 数组语义（RES-R4）</b>: 本构建器<b>不再</b>把数组扁平化为单个 String
 * （旧 {@code String.join("\n\n", fullSystemPrompt)} 丢失 boundary 元素 → firstParty 场景
 * boundary 字面量混入正文且 cacheScope 与主线程不一致）。现保留主线程<b>发送前</b>数组
 * （含 boundary 元素）原样存入 {@code CacheSafeParams.systemPrompt}，发送边界（StreamCompactSummary）
 * 再用实际 {@code useGlobalCacheScope} 门控经 {@link SystemPromptSplitter} 剥离：
 * <ul>
 *   <li>3P 默认（gate=false 或 boundary 缺失）→ defaultMode 将 rest 以 \n\n 拼接为单 block，
 *       与旧 flat join 字节等价（REQ-R4-4，无回归）</li>
 *   <li>firstParty/boundary（gate=true 且数组含 boundary）→ boundary 剥离、静态→global、
 *       动态→null（REQ-R4-1，与主线程 LlmAgentLoop s10 同一 split 输出）</li>
 * </ul>
 * 本字段同时携带 {@code useGlobalCacheScope} gate 值（Java 通信通道，CC 为进程级全局函数），
 * 保证 fork 与主线程同一 gate 判定（REQ-R4-3）。
 */
public final class CacheSharingParamsBuilder {

    private static final Logger log = LoggerFactory.getLogger(CacheSharingParamsBuilder.class);

    private CacheSharingParamsBuilder() {
    }

    /**
     * 构建 fork 缓存共享参数 · 对齐 CC {@code getCacheSharingParams}（compact.ts:250-287）。
     *
     * @param sysPromptCtxProvider 会话级 system/user 上下文提供者（fetchSystemPromptParts）
     * @param defaultAssemble      default system prompt 惰性组装入口 · CC original:
     *                             {@code getSystemPrompt(tools, model, dirs, mcpClients)}
     *                             （compact.ts:259-263；custom 非空时不被调用）
     * @param customSystemPrompt   自定义 system prompt · CC original:
     *                             {@code context.options.customSystemPrompt}
     *                             （compact.ts:269；Java 取 state.systemPrompt()）
     * @param appendSystemPrompt   用户追加指令（恒末尾追加）· CC original:
     *                             {@code context.options.appendSystemPrompt}
     *                             （compact.ts:274；Java 取 state.appendSystemPrompt()，
     *                             OPD-SP-31 接线）
     * @param toolUseContext       fork 继承的 tool use context · CC original:
     *                             {@code context}（compact.ts:285；Java 取 params.toolUseContext()）
     * @param forkContextMessages  主线程压缩前消息（cache prefix 复用）· CC original:
     *                             {@code forkContextMessages}（compact.ts:286；Java 取
     *                             autoCompact 触发点 state.messages() 压缩前快照）
     * @param useGlobalCacheScope  boundary/gate 判定值（fork 与主线程一致）· CC original:
     *                             {@code shouldUseGlobalCacheScope()}（utils/betas.ts:227-233）；
     *                             Java 由调用方注入（LlmAgentLoop auto / CompactCommand manual）
     * @return 6 字段 CacheSafeParams（systemPrompt 数组 + userContext / systemContext /
     *         toolUseContext / forkContextMessages / useGlobalCacheScope · forkedAgent.ts:57-68 +
     *         betas.ts:227-233）
     */
    public static CacheSafeParams build(
            SystemPromptContextProvider sysPromptCtxProvider,
            Supplier<SystemPrompt> defaultAssemble,
            String customSystemPrompt,
            String appendSystemPrompt,
            ToolUseContext toolUseContext,
            List<ChatMessageDto> forkContextMessages,
            boolean useGlobalCacheScope) {
        if (sysPromptCtxProvider == null) {
            log.warn("[CacheSharingParamsBuilder] sysPromptCtxProvider 为 null，跳过构建");
            return null;
        }
        if (toolUseContext == null) {
            // CacheSafeParams 紧凑构造对 null toolUseContext 抛异常（fork 必须继承权限），
            // 此处前置守卫返回 null → 调用方跳过 save，不进入 fork 路径。
            log.warn("[CacheSharingParamsBuilder] toolUseContext 为 null，跳过构建");
            return null;
        }
        long startNanos = System.nanoTime();

        // 1. fetchSystemPromptParts 等价（queryContext.ts:44-74；custom 短路 I-13）
        SystemPromptParts sysParts =
            sysPromptCtxProvider.fetchSystemPromptParts(customSystemPrompt, defaultAssemble);

        // 2. buildEffectiveSystemPrompt（systemPrompt.ts:115-122）· custom 替换 default，append 恒末尾
        //    [SP-01] override 保持 null（CC compact.ts:269 调用点不传 overrideSystemPrompt，分支休眠——
        //    Java 主循环 LlmAgentLoop 为唯一会话 loop_mode_override 触发源）
        SystemPrompt systemPrompt = EffectiveSystemPromptBuilder.build(
            () -> SystemPrompt.from(sysParts.defaultSystemPrompt()),
            null,                                  // overrideSystemPrompt（CC compact.ts:269 不传 → 保持 null）
            customSystemPrompt,                    // customSystemPrompt（替换 default）
            appendSystemPrompt);                   // appendSystemPrompt（OPD-SP-31 接线：恒末尾追加，CC compact.ts:274）

        // 3. appendSystemContext（api.ts:437-447）· systemContext（gitStatus?/cacheBreaker?）并入 systemPrompt
        //    [RES-R4] 保留主线程发送前数组（含 boundary 元素），不再无条件 \n\n 扁平化 ——
        //    发送边界（StreamCompactSummary）用实际 gate 经 SystemPromptSplitter 剥离（REQ-R4-1/2/4）。
        List<String> fullSystemPrompt =
            sysPromptCtxProvider.appendSystemContext(systemPrompt, sysParts.systemContext());

        CacheSafeParams params = new CacheSafeParams(
            fullSystemPrompt,
            sysParts.userContext(),
            sysParts.systemContext(),
            toolUseContext,
            forkContextMessages,
            useGlobalCacheScope);

        if (log.isInfoEnabled()) {
            log.info("[CacheSharingParamsBuilder] CacheSafeParams 构建完成: custom={}, "
                    + "systemPromptBlocks={}, useGlobalCacheScope={}, userKeys={}, systemKeys={}, "
                    + "forkMsgs={}, 耗时 {} ms",
                customSystemPrompt != null,
                fullSystemPrompt.size(),
                useGlobalCacheScope,
                sysParts.userContext().keySet(),
                sysParts.systemContext().keySet(),
                forkContextMessages == null ? 0 : forkContextMessages.size(),
                (System.nanoTime() - startNanos) / 1_000_000);
        }
        return params;
    }
}
