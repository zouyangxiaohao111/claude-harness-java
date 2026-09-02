package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.compact.fork.CacheSafeParams;

import com.nexusai.application.agent.recovery.ErrorClassifier;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 应急压缩器（Reactive Compact）· 对齐 CC 真源 {@code Open-ClaudeCode/src/services/compact/reactiveCompact.ts}
 * （97 行，已入库 2026-08-18）。
 *
 * <h2>CC 对齐（reactiveCompact.ts 6 个导出 · grep -n 自验 2026-08-18）</h2>
 * <table>
 *   <tr><th>本类方法</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>{@link #isReactiveOnlyMode()}</td><td>{@code isReactiveOnlyMode}</td><td>reactiveCompact.ts:12</td></tr>
 *   <tr><td>{@link #reactiveCompactOnPromptTooLong}</td><td>{@code reactiveCompactOnPromptTooLong}</td><td>reactiveCompact.ts:14-41</td></tr>
 *   <tr><td>{@link #isReactiveCompactEnabled()}</td><td>{@code isReactiveCompactEnabled}</td><td>reactiveCompact.ts:43-46</td></tr>
 *   <tr><td>{@link #isWithheldPromptTooLong}</td><td>{@code isWithheldPromptTooLong}</td><td>reactiveCompact.ts:48-52</td></tr>
 *   <tr><td>{@link #isWithheldMediaSizeError}</td><td>{@code isWithheldMediaSizeError}</td><td>reactiveCompact.ts:54-58</td></tr>
 *   <tr><td>{@link #tryReactiveCompact}</td><td>{@code tryReactiveCompact}</td><td>reactiveCompact.ts:60-97</td></tr>
 * </table>
 *
 * <h2>内部算法（2026-08-18 对齐 CC 真源，替代用户算法 OD-01 #3）</h2>
 * <p>CC {@code reactiveCompact.ts} 真源已取回，6 个导出全部对齐。内部<b>不再</b>使用
 * 用户 2026-08-07 算法（tail_start=len−5 + tool 切口保护 + [Reactive compact] 前缀 +
 * transcriptPath 方案 A + null-callback fail-loud + isTooFewGroupsToCompact 定制）——全部删除：
 * <ol>
 *   <li><b>{@code tryReactiveCompact}</b> 委托 {@link CompactConversation#compactConversation}：
 *       {@code compactConversation(messages, ccCtx, true, null, true, RecompactionInfo(false,0,null,0,null))}
 *       （CC 不传 customInstructions 也不传 querySource，reactiveCompact.ts:75-87）</li>
 *   <li><b>{@code reactiveCompactOnPromptTooLong}</b> 委托
 *       {@code compactConversation(messages, ccCtx, true, customInstructions, true, RecompactionInfo(false,0,null,0,'compact'))}
 *       （reactiveCompact.ts:22-35）</li>
 *   <li><b>enabled 门收敛到调用方</b>：tryReactiveCompact 内部不再判 enabled
 *       （CC 模块存在性 = REACTIVE_COMPACT feature 在调用点判定，query.ts:1119）；本类
 *       {@code enabled} 仅驱动 {@link #isReactiveCompactEnabled()}（feature + DISABLE_COMPACT 双门）</li>
 *   <li><b>摘要生产</b> —— 经 {@link #summaryProducer()} 把 {@link AutoCompactor.CompactCallback}
 *       （生产 = {@link StreamCompactSummary}）适配为 {@link CompactConversation.SummaryProducer}
 *       （CC streamCompactSummary，compact.ts:451）</li>
 * </ol>
 *
 * <p><b>P-11 闭环（2026-08-19）</b>：{@link #isWithheldMediaSizeError} 谓词对齐 CC，生产生产者
 * 已接线 —— Java provider 仍以异常级（{@code LlmApiException Kind.IMAGE}）表达 media 错误，
 * LlmAgentLoop 在媒体错误处理点经
 * {@link com.nexusai.application.agent.recovery.ApiErrorMessageFactory#createMediaSizeErrorApiMessage}
 * 把异常级转回消息级（isApiErrorMessage=true + errorDetails=API 错误原文，对齐 CC
 * claude.ts:2743/2801 yield getAssistantMessageFromError）→ 本谓词可命中 → 走 reactive compact
 * 恢复链。原「防御性谓词无生产者」受控残留已解除。
 *
 * <h2>触发条件</h2>
 * <ul>
 *   <li>API 返回 413 / "prompt too long" / 上下文超限错误（query.ts:1119 isWithheld413）</li>
 *   <li>媒体超限错误（query.ts:1119 isWithheldMedia，isWithheldMediaSizeError）</li>
 *   <li>ReactiveCompact 模式启用（CC: REACTIVE_COMPACT flag，Java = {@code FeatureFlags.reactiveCompact()}）</li>
 * </ul>
 */
public class ReactiveCompactor {

    private static final Logger log = LoggerFactory.getLogger(ReactiveCompactor.class);

    /**
     * 应急模式是否启用 · 默认禁用（对齐 CC {@code feature('REACTIVE_COMPACT')} flag 关闭）。
     *
     * <p><b>WHY 默认 false</b>: CC 快照中 REACTIVE_COMPACT 为实验性 flag，默认关闭。
     * flag 关闭时模块为 null、所有调用点带空值保护。Java 端默认禁用，由外部显式开启
     * （ToolRegistrationConfig 经 {@code FeatureFlags.reactiveCompact()} 驱动）。
     */
    private boolean enabled = false;

    /**
     * 压缩配置 DB 实时读源 · [V52 token-compact-fix B1-6] @Autowired(required=false)：
     * null = 无 Spring 上下文 / 未接线 → 回落 FeatureFlags（enabled），零行为变化。
     */
    private CompactSettingsResolver settingsResolver;

    /** env 读取器 · 可注入便于测试（默认 System::getenv），与 AutoCompactor.envProvider 同模式。 */
    private java.util.function.Function<String, String> envProvider = System::getenv;

    /**
     * Token 计数器 · 保留仅供构造 API 兼容（旧签名）。CC 对齐后压缩 token 度量由
     * {@link CompactConversation#compactConversation} 内部完成，本字段不再参与算法。
     */
    private final TokenCounter tokenCounter;

    /**
     * 摘要回调 · 复用 {@link AutoCompactor.CompactCallback}（生产 = {@link StreamCompactSummary}）。
     * 经 {@link #summaryProducer()} 适配为 {@link CompactConversation.SummaryProducer} 注入
     * 压缩上下文。null → 摘要能力缺失（compactConversation NPE 由调用方 try/catch 兜底 → surface）。
     */
    private final AutoCompactor.CompactCallback compactCallback;

    /**
     * 兼容构造（旧签名）· 委托新构造传 null callback。
     *
     * <p><b>WHY 保留</b>: 既有测试/调用方以 TokenCounter lambda 构造（签名兼容）；null callback
     * 下摘要生产不可用（见 {@link #summaryProducer()}）。
     */
    public ReactiveCompactor(TokenCounter tokenCounter) {
        this(tokenCounter, null);
    }

    /**
     * 新构造 · 注入摘要回调（生产经 ToolRegistrationConfig 注入 {@link StreamCompactSummary}）。
     *
     * @param tokenCounter    Token 计数器（保留兼容；CC 对齐后不参与算法）
     * @param compactCallback LLM 摘要回调（{@link AutoCompactor.CompactCallback}；null = 摘要不可用）
     */
    public ReactiveCompactor(TokenCounter tokenCounter, AutoCompactor.CompactCallback compactCallback) {
        this.tokenCounter = tokenCounter;
        this.compactCallback = compactCallback;
    }

    /**
     * 设置是否启用应急压缩模式
     *
     * <p>对齐 CC feature('REACTIVE_COMPACT') + growthbook gate（模块存在性）。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (log.isDebugEnabled()) {
            log.debug("[ReactiveCompactor] 应急压缩模式: {}", enabled ? "启用" : "禁用");
        }
    }

    /**
     * 注入压缩配置 DB 实时读源 · [V52 B1-6] @Autowired(required=false)，同
     * {@link CompactThresholdSystem#setSettingsMapper(SettingsMapper)} 回落语义（可 null）。
     *
     * @param settingsResolver 压缩配置实时读源（可 null）
     */
    public void setSettingsResolver(CompactSettingsResolver settingsResolver) {
        this.settingsResolver = settingsResolver;
    }

    /** 注入 env 读取器（测试可注入 mock，对齐 AutoCompactor.setEnvProvider）。 */
    public void setEnvProvider(java.util.function.Function<String, String> envProvider) {
        this.envProvider = envProvider != null ? envProvider : System::getenv;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * reactive-only 模式判定 · 对齐 CC {@code isReactiveOnlyMode}
     * （reactiveCompact.ts:12，CC 硬编码恒 false）。
     *
     * <p><b>CC 行为</b>: {@code export const isReactiveOnlyMode: () => boolean = () => false}
     * —— 恒 false，{@code /compact} 命令 reactive-only 路由（commands/compact/compact.ts:87
     * {@code if (reactiveCompact?.isReactiveOnlyMode())}）<b>永不触发</b>。Java 旧实现返回
     * {@code isEnabled()}（flag 代理），对齐 CC 恒 false。{@code CompactCommand.isReactiveOnlyMode}
     * 因此恒 false → {@code compactViaReactive} 为 CC 对齐死代码（保留实现，路由不可达）。
     *
     * @return 恒 false（CC reactiveCompact.ts:12）
     */
    public boolean isReactiveOnlyMode() {
        return false;
    }

    /**
     * 应急压缩是否启用 · 对齐 CC {@code isReactiveCompactEnabled}
     * （reactiveCompact.ts:43-46，{@code !isEnvTruthy(process.env.DISABLE_COMPACT)}）。
     *
     * <p><b>Java 双门</b>: CC 中模块存在性（REACTIVE_COMPACT feature）与 env 门分离
     * （调用点 {@code reactiveCompact?.isReactiveCompactEnabled() ?? false}，query.ts:627）；
     * Java bean 恒存在 → 用 {@code enabled}（= FeatureFlags.reactiveCompact()）承载模块存在性，
     * 本方法 = {@code featureGate && !disable}. LlmAgentLoop mediaRecoveryEnabled
     * hoist（query.ts:626-627）读本方法，withhold 与 recovery 必须一致。
     *
     * <p><b>[DB 主控] DISABLE_COMPACT 判定</b>（用户决策「DB 直接改库即生效」）：DB
     * settings.disable_compact 有值（非 null）直接生效（true = 禁用，false = 显式放行覆盖 env）；
     * DB 无值回落 env DISABLE_COMPACT（部署级强制覆盖 fallback，CC reactiveCompact.ts:44）；
     * 再无 → 不由此禁用。
     *
     * @return true = REACTIVE_COMPACT 开启且 DISABLE_COMPACT（DB/env）未禁
     */
    public boolean isReactiveCompactEnabled() {
        // [V52 B1-6] DB settings.reactive_compact_enabled 有值覆盖 FeatureFlags 模块存在性
        // （null 回落 enabled=FeatureFlags.reactiveCompact()）。
        Boolean dbReactive = settingsResolver != null ? settingsResolver.reactiveCompactEnabled() : null;
        boolean featureGate = dbReactive != null ? dbReactive : enabled;
        if (!featureGate) {
            return false;
        }
        // [DB 主控] DISABLE_COMPACT：DB 有值直接生效；无值回落 env（部署级覆盖 fallback）
        Boolean dbDisable = settingsResolver != null ? settingsResolver.disableCompact() : null;
        if (dbDisable != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ReactiveCompactor] DB settings.disable_compact={} 主控（env DISABLE_COMPACT 被覆盖）",
                    dbDisable);
            }
            return !dbDisable;
        }
        return !ErrorClassifier.isEnvTruthy(envProvider.apply("DISABLE_COMPACT"));
    }

    /**
     * PTL withhold 判定 · 对齐 CC {@code isWithheldPromptTooLong}
     * （reactiveCompact.ts:48-52）：{@code type==='assistant' && isApiErrorMessage &&
     * isPromptTooLongMessage(message)}。
     *
     * <p><b>不挂 collapse 门</b>: CC reactive 版<b>无</b> CONTEXT_COLLAPSE gate
     * （query.ts:811 直接 {@code reactiveCompact?.isWithheldPromptTooLong(message)}）；
     * {@link com.nexusai.application.agent.loop.ContextCollapse#isWithheldPromptTooLong} 为
     * <b>另一归属</b>（collapse drain 门控版，挂 collapse 门，query.ts:800-810），两者并存，
     * 不破坏现有 ContextCollapse 调用链。
     *
     * @param message 待判定的消息（assistant API 错误消息）
     * @return true = 应 withhold（等待 reactive 恢复）
     */
    public boolean isWithheldPromptTooLong(ChatMessageDto message) {
        if (message == null || message.role() != Role.assistant || !message.isApiErrorMessage()) {
            return false;
        }
        return ErrorClassifier.isPromptTooLongMessage(message);
    }

    /**
     * media-size withhold 判定 · 对齐 CC {@code isWithheldMediaSizeError}
     * （reactiveCompact.ts:54-58）：{@code type==='assistant' && isApiErrorMessage &&
     * isMediaSizeErrorMessage(message)}。
     *
     * <p><b>P-11 闭环（2026-08-19）</b>: 本谓词消费方已接通 —— LlmAgentLoop 在媒体错误处理点经
     * {@link com.nexusai.application.agent.recovery.ApiErrorMessageFactory#createMediaSizeErrorApiMessage}
     * 生产消息级 {@code errorDetails}（对齐 CC claude.ts:2743/2801 注入消息流），LlmAgentLoop:4802
     * {@code isMediaError = mediaRecoveryEnabled && isMediaSizeErrorMessage(lastAssistantMsg)} 命中 →
     * 走 reactive compact 恢复链。原「异常级直 surface、谓词无生产生产者」受控残留（P-11）已解除。
     *
     * @param message 待判定的消息（assistant API 错误消息）
     * @return true = media-size 拒绝（strip-retry / reactive 可修复）
     */
    public boolean isWithheldMediaSizeError(ChatMessageDto message) {
        if (message == null || message.role() != Role.assistant || !message.isApiErrorMessage()) {
            return false;
        }
        return ErrorClassifier.isMediaSizeErrorMessage(message);
    }

    /**
     * 摘要生产适配 · 把 {@link AutoCompactor.CompactCallback}（生产 = {@link StreamCompactSummary}）
     * 适配为 {@link CompactConversation.SummaryProducer}（CC streamCompactSummary，compact.ts:451）。
     *
     * <p>供调用方构建 {@link CompactConversationContext} 时注入 summaryProducer
     * （LlmAgentLoop reactive 路径 / 需要委托 compactConversation 的调用点）。null callback
     * → 返回 null（摘要不可用；compactConversation 内 getSummaryProducer().summarize NPE 由
     * 调用方 try/catch 兜底 → surface，与 tryReactiveCompact 捕获异常语义一致）。
     *
     * @return SummaryProducer 适配器；compactCallback 为 null → null
     */
    public CompactConversation.SummaryProducer summaryProducer() {
        if (compactCallback == null) {
            return null;
        }
        return (messagesToSummarize, compactPrompt, preCompactTokenCount) -> {
            try {
                // [IMP-CM-14 F02] 透传回调返回的 SummaryResult（text + usage）——不丢 usage
                return compactCallback.summarize(compactPrompt, messagesToSummarize);
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * /compact manual 入口 · 对齐 CC {@code reactiveCompactOnPromptTooLong}
     * （reactiveCompact.ts:14-41）。
     *
     * <p><b>CC 行为</b>（reactiveCompact.ts:20-40）：
     * <ol>
     *   <li>委托 {@code compactConversation(messages, params.toolUseContext, params, true,
     *       options.customInstructions, true, {isRecompactionInChain:false,
     *       turnsSincePreviousCompact:0, autoCompactThreshold:0, querySource:'compact'})}</li>
     *   <li>成功 → {@code {ok:true, result}}</li>
     *   <li>异常 → {@code logError(error)} + {@code {ok:false, reason: String(error)}}</li>
     * </ol>
     *
     * <p>Java 映射：{@code CompactConversationContext}（ccCtx）= CC {@code params.toolUseContext}
     * 依赖面；fork 缓存共享经 {@link com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder}
     * 槽位（调用方 save → compactConversation 内 StreamCompactSummary 读取 → finally clear）。
     * 返回 {@link ReactiveCompactOutcome}（{@code {ok, reason, result}} 信封，CC 同形）。
     *
     * @param messages           待压缩消息（boundary 剥离后）
     * @param ccCtx              压缩上下文（compactConversationContextSupplier 产物，
     *                           summaryProducer 需已接线；null → 失败信封）
     * @param customInstructions 用户自定义指令（mergedInstructions，CC options.customInstructions）
     * @return {ok, reason, result} 信封
     */
    public ReactiveCompactOutcome reactiveCompactOnPromptTooLong(List<ChatMessageDto> messages,
                                                                 CompactConversationContext ccCtx,
                                                                 String customInstructions) {
        if (ccCtx == null) {
            log.error("[ReactiveCompactor] reactiveCompactOnPromptTooLong: CompactConversationContext 未注入（null），返回失败信封");
            return ReactiveCompactOutcome.fail("CompactConversationContext is required");
        }
        if (ccCtx.getSummaryProducer() == null) {
            ccCtx.setSummaryProducer(summaryProducer());
        }
        try {
            // CC reactiveCompact.ts:22-35 compactConversation(messages, toolUseContext, params, true,
            //   customInstructions, true, RecompactionInfo(false,0,null,0,'compact'))
            CompactionResult result = CompactConversation.compactConversation(
                messages, ccCtx, true, customInstructions, true,
                new CompactConversation.RecompactionInfo(false, 0, null, 0, "compact"));
            log.info("[ReactiveCompactor] reactiveCompactOnPromptTooLong 成功: preTokens={} summaryMsgs={} "
                    + "· CC reactiveCompact.ts:22-36",
                result.preCompactTokenCount(),
                result.summaryMessages() != null ? result.summaryMessages().size() : 0);
            return ReactiveCompactOutcome.ok(result);
        } catch (Exception error) {
            // CC reactiveCompact.ts:37-40：logError(error) + return { ok:false, reason: String(error) }
            log.error("[ReactiveCompactor] reactiveCompactOnPromptTooLong 失败: {} · CC reactiveCompact.ts:37-40",
                error.toString());
            return ReactiveCompactOutcome.fail(error.toString());
        }
    }

    /**
     * CC 契约入口 · 对齐 CC query.ts:1120-1132
     * {@code reactiveCompact.tryReactiveCompact({hasAttempted, querySource, aborted, messages, cacheSafeParams})}
     * 的 Java 委托（reactiveCompact.ts:60-97）。
     *
     * <p><b>CC 行为</b>（reactiveCompact.ts:72-96）：
     * <ol>
     *   <li>{@code hasAttempted || aborted} → null（单次守卫 + 中断，query.ts:1157/1123）</li>
     *   <li>委托 {@code compactConversation(messages, params.toolUseContext, params, true,
     *       undefined, true, {isRecompactionInChain:false, turnsSincePreviousCompact:0,
     *       autoCompactThreshold:0})} —— <b>不传 customInstructions、不传 querySource</b></li>
     *   <li>成功 → CompactionResult（真值）；异常 → logForDebugging(warn) + logError + null</li>
     * </ol>
     *
     * <p><b>enabled 门收敛到调用方</b>: CC 无内部 enabled 检查（模块存在性 = feature 在调用点
     * query.ts:1119 判定）。LlmAgentLoop reactiveGate（:4800）负责该门，本方法不再判 enabled。
     *
     * <p><b>cacheSafeParams</b>: CC 传入 systemPrompt/userContext/systemContext/toolUseContext/
     * forkContextMessages 供压缩时做缓存安全消息替换；Java 端 fork 缓存共享经
     * {@link com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder} 槽位
     * （LlmAgentLoop reactive 路径当前未接线，保留字段供 CC 签名对齐）。
     *
     * @param params 调用参数（hasAttempted 单次守卫 / querySource / aborted / messages /
     *               cacheSafeParams / ccCtx）
     * @return {@link ReactiveCompactResult}（真值 = 恢复成功）或 null（falsy = 无法恢复需 surface）
     */
    public ReactiveCompactResult tryReactiveCompact(TryReactiveCompactParams params) {
        if (params.hasAttempted()) {
            log.warn("[ReactiveCompactor] hasAttempted=true（单次限制）· CC reactiveCompact.ts:72 返回 null → surface 错误");
            return null;
        }
        if (params.aborted()) {
            log.debug("[ReactiveCompactor] aborted=true（调用已中断），跳过应急压缩返回 null · CC reactiveCompact.ts:72");
            return null;
        }
        CompactConversationContext ccCtx = params.ccCtx();
        if (ccCtx == null) {
            log.error("[ReactiveCompactor] ccCtx 未注入（调用方未构建 CompactConversationContext），"
                + "无法委托 compactConversation → 返回 null → surface");
            return null;
        }
        if (ccCtx.getSummaryProducer() == null) {
            ccCtx.setSummaryProducer(summaryProducer());
        }
        try {
            // CC reactiveCompact.ts:75-88 compactConversation(messages, toolUseContext, params, true,
            //   undefined, true, RecompactionInfo(false,0,null,0))——不传 customInstructions / querySource
            CompactionResult result = CompactConversation.compactConversation(
                params.messages(), ccCtx, true, null, true,
                new CompactConversation.RecompactionInfo(false, 0, null, 0, null));
            log.info("[ReactiveCompactor] tryReactiveCompact 成功: preTokens={} summaryMsgs={} "
                    + "· CC reactiveCompact.ts:75-88 委托 compactConversation",
                result.preCompactTokenCount(),
                result.summaryMessages() != null ? result.summaryMessages().size() : 0);
            return new ReactiveCompactResult(result);
        } catch (Exception error) {
            // CC reactiveCompact.ts:89-94：logForDebugging('reactiveCompact: emergency compaction
            //   failed — …', warn) + logError(error) → return null
            log.warn("[ReactiveCompactor] reactiveCompact: emergency compaction failed — {}",
                error.toString());
            log.error("[ReactiveCompactor] tryReactiveCompact 委托 compactConversation 失败: {}",
                error.getMessage(), error);
            return null;
        }
    }

    /**
     * reactiveCompactOnPromptTooLong 结果信封 · 对齐 CC {@code {ok, reason?, result?}}
     * （reactiveCompact.ts:18/36/39）。
     *
     * @param ok     true = 压缩成功（result 非 null）；false = 失败（reason 非 null）
     * @param reason 失败原因（CC {@code String(error)}；成功为 null）
     * @param result 压缩结果（成功非 null；失败为 null）
     */
    public record ReactiveCompactOutcome(boolean ok, String reason, CompactionResult result) {
        /** 成功信封 · CC { ok: true, result }（reactiveCompact.ts:36） */
        public static ReactiveCompactOutcome ok(CompactionResult result) {
            return new ReactiveCompactOutcome(true, null, result);
        }

        /** 失败信封 · CC { ok: false, reason: String(error) }（reactiveCompact.ts:39） */
        public static ReactiveCompactOutcome fail(String reason) {
            return new ReactiveCompactOutcome(false, reason, null);
        }

        public ReactiveCompactOutcome {
            if (ok && result == null) {
                throw new IllegalArgumentException("ReactiveCompactOutcome.ok=true 必须携带 result");
            }
            if (!ok && (reason == null || reason.isEmpty())) {
                throw new IllegalArgumentException("ReactiveCompactOutcome.ok=false 必须携带 reason");
            }
        }
    }

    /**
     * [H7-arch Phase 5 P4 C4] tryReactiveCompact 参数 · 对齐 CC query.ts:1120-1131。
     *
     * <p><b>CC 对齐后字段</b>：{@code ccCtx} 承载 compactConversation 上下文（调用方构建，
     * LlmAgentLoop reactive 路径经 buildAutoContext）。旧 transcriptPath 方案 A
     * （workspaceDir/sessionId 提示，OD-01 #3）已删除——reactive compact 摘要消息由
     * compactConversation 统一构建，transcript 路径经 ccCtx.getWorkspaceDir 读侧，无独立附加。
     *
     * @param hasAttempted   是否已尝试过 reactive compact（单次守卫）· CC query.ts:1121
     * @param querySource    查询来源（compact/session_memory 等）· CC query.ts:1122（CC 接收但不消费）
     * @param aborted        abortController.signal.aborted（调用已中断）· CC query.ts:1123
     * @param messages       当前消息列表（413 失败请求的输入）· CC query.ts:1124
     * @param cacheSafeParams 缓存安全参数（CC 传 systemPrompt/userContext/systemContext/
     *                        toolUseContext/forkContextMessages；Java fork 槽位经 Holder，
     *                        本字段 CC 签名对齐保留，可空）
     * @param ccCtx          压缩上下文（{@link CompactConversationContext}，调用方构建；
     *                        null → tryReactiveCompact 返回 null → surface）
     */
    public record TryReactiveCompactParams(
            boolean hasAttempted,
            String querySource,
            boolean aborted,
            List<ChatMessageDto> messages,
            CacheSafeParams cacheSafeParams,
            CompactConversationContext ccCtx
    ) {
        /** 兼容构造（5 参）· 委托 6 参传 ccCtx=null（无压缩上下文 → tryReactiveCompact 返回 null）。 */
        public TryReactiveCompactParams(
                boolean hasAttempted, String querySource, boolean aborted,
                List<ChatMessageDto> messages, CacheSafeParams cacheSafeParams) {
            this(hasAttempted, querySource, aborted, messages, cacheSafeParams, null);
        }

        public TryReactiveCompactParams {
            if (messages == null) {
                throw new IllegalArgumentException("TryReactiveCompactParams.messages is null");
            }
        }
    }
}
