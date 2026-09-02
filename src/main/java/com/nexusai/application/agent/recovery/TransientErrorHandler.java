package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 临时错误（429/529）恢复处理器 · 对齐 CC withRetry.ts:170-517。
 *
 * <h2>恢复路径 3: 429/529 临时错误</h2>
 * <ol>
 *   <li><b>分类</b> — 通过 {@link ErrorClassifier#isRetryable(Throwable)} 判断是否可重试
 *       （凭证自愈 handleAwsCredentialError/handleGcpCredentialError 或 shouldRetry 全集）</li>
 *   <li><b>429 Rate Limit</b> — 指数退避后重试 · CC withRetry.ts:108</li>
 *   <li><b>529 Overloaded</b> — 计数连续 529 · CC withRetry.ts:610-621</li>
 *   <li><b>连续 3 次 529</b> — 切换 fallback 模型 · CC withRetry.ts:337-351</li>
 *   <li><b>REPEATED_529 快速失败</b> — 无 fallback → CannotRetryException（CC withRetry.ts:353-363
 *       external 快速失败，用户决策 4：剥离 external 维度，统一阈值）</li>
 *   <li><b>非可重试错误</b> — 直接 FATAL，不重试</li>
 * </ol>
 *
 * <p><b>ER-IMP-03 接线变更</b>：attempt 与 consecutive529Errors 由调用方（LlmAgentLoop Path3）
 * 以闭包局部量传入（弃 RecoveryState 计数器 POJO，DC-10）；重试耗尽判定上移到 Path3
 * 以 {@link WithRetryEngine#getMaxRetries(RetryOptions)} 计算上限（CC withRetry.ts:370-371），
 * 本类不再判死。
 *
 * <p><b>ER-IMP-04 重建</b>：分类闸由旧 {@code ErrorClassifier.isTransient}（消息/类名启发式）
 * 改为 {@link ErrorClassifier#isRetryable}（类型化状态码 + 凭证自愈）；529 计数由前台闸
 * （shouldRetry529，LlmAgentLoop Path3 前置丢弃后台 529）约束；连续 3 次 529 无 fallback →
 * 抛 {@link CannotRetryException}(REPEATED_529)（用户决策 4，统一阈值）。
 *
 * <p>延迟计算委托给 {@link RetryDelayCalculator}（CC 精确公式）。
 */
public class TransientErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(TransientErrorHandler.class);

    /**
     * settings.fallbackModelId 提供器（Spring 接线于 ToolRegistrationConfig.transientErrorHandler
     * —— 注入 SettingsMapper 读单例行 id=1）· 默认 null（未接线 / 未配置 → 不降级）。
     * 测试可经 {@link #setSettingsFallbackProvider} 替换。
     */
    private static volatile java.util.function.Supplier<String> SETTINGS_FALLBACK_PROVIDER = () -> null;

    /**
     * 注入 settings.fallbackModelId 提供器（F4 · 用户拍板：FALLBACK_MODEL_ID 从 env 改为 settings 配置）。
     *
     * <p>CC 无 FALLBACK_MODEL_ID env（withRetry.ts:337-351 按调用传入 options.fallbackModel），
     * Java 原以 env 提供默认值属自建；现迁移 settings.fallbackModelId（V27 列）。未配置
     * （null/blank）→ null → 529 快速失败不降级（对齐 CC 无全局默认时的行为）。
     */
    public static void setSettingsFallbackProvider(java.util.function.Supplier<String> provider) {
        SETTINGS_FALLBACK_PROVIDER = provider;
    }

    /**
     * fallback 模型提供器（包内可见，测试可注入）· 默认读 settings.fallbackModelId。
     *
     * <p>CC withRetry.ts:337-351 使用按调用传入的 {@code options.fallbackModel}；Java 以 settings
     * 列（V27 fallback_model_id）承载<b>默认值</b>（决策 10 + DC-18：按调用传入优先，settings
     * 仅作默认值，ER-IMP-10 接线 handle(fallbackModel) 形参）。未配置（null/blank）→ null →
     * 529 快速失败不降级。测试可替换以覆盖 fallback 触发分支。
     */
    static volatile java.util.function.Supplier<String> FALLBACK_MODEL_SUPPLIER = () -> {
        String m = SETTINGS_FALLBACK_PROVIDER.get();
        if (m == null || m.isBlank()) {
            log.debug("TransientErrorHandler: settings.fallbackModelId 未配置，降级模型为 null（连续 529 快速失败不降级 · 对齐 CC 无全局默认）");
            return null;
        }
        log.debug("TransientErrorHandler: settings.fallbackModelId 命中: {}", m);
        return m;
    };

    /**
     * 529 fallback 资格闸 · CC withRetry.ts:330-335。
     *
     * <p>CC: {@code FALLBACK_FOR_ALL_PRIMARY_MODELS || (!isClaudeAISubscriber() && isNonCustomOpusModel(model))}。
     * isClaudeAISubscriber 本项目 N/A（ErrorClassifier:649 恒 false）→ 等价为
     * {@code FALLBACK_FOR_ALL_PRIMARY_MODELS(env truthy) || ModelNameUtil.isNonCustomOpusModel(model)}。
     * 命中才计入 consecutive529Errors 并可能 fallback；未命中 → 仅退避重试（CC:329 注释"fall through"）。
     *
     * <p><b>A3 决策对齐</b>：CC 使用 JavaScript truthy 语义（{@code process.env.FALLBACK_FOR_ALL_PRIMARY_MODELS}），
     * 任意非空字符串即开启（含 "false"、"0"）。Java 端以 {@code env != null && !env.isEmpty()} 等价对齐，
     * 不用 {@link ErrorClassifier#isEnvTruthy}（仅 {1,true,yes,on} → true，语义不符）。
     *
     * <p><b>V-WR-02 修复</b>：提升为 public static，供 LlmAgentLoop Path 3 在递增
     * consecutive529ErrorsHolder 前判定资格（CC withRetry.ts:327-333 递增与资格闸同处一 if）。
     * 旧实现 LlmAgentLoop 对所有 529 无条件递增，资格检查延迟到 handle()，计数已膨胀。
     *
     * @param model 当前请求主模型（CC options.model 等价）
     * @return true=可累计 529 并触发 fallback
     */
    public static boolean isEligibleFor529Fallback(String model) {
        // A3：对齐 CC process.env.FALLBACK_FOR_ALL_PRIMARY_MODELS truthy 语义（任意非空即开启，含 "false"/"0"）
        String envVal = ErrorClassifier.ENV_READER.apply(ApiErrors.ENV_FALLBACK_FOR_ALL_PRIMARY_MODELS);
        return (envVal != null && !envVal.isEmpty())
            || ModelNameUtil.isNonCustomOpusModel(model);
    }

    /**
     * 处理临时错误（非持久路径）· 委托给 {@link #handle(Throwable, RecoveryState, int, int, int, RetryContext, boolean, int[], String)}。
     *
     * <p><b>ER-IMP-06</b>：旧 6 参签名保留（既有测试/调用不变），持久分支走 8 参重载
     * （persistent=true 时 LlmAgentLoop Path 3 传入 persistentAttemptHolder 闭包）。
     * <b>ER-IMP-10</b>：fallbackModel 未显式传入 → null（走 env 默认），保持旧调用语义。
     */
    public RecoveryResult handle(Throwable e, RecoveryState state, int attempt, int maxRetries,
                                 int consecutive529Errors, RetryContext retryContext) {
        return handle(e, state, attempt, maxRetries, consecutive529Errors, retryContext,
            false, null, null);
    }

    /**
     * 处理临时错误。
     *
     * @param e                   原始异常
     * @param state               当前恢复状态（会被修改：lastReason/lastBackoffMs/currentModel）
     * @param attempt             当前重试次数（从 1 开始，调用方闭包局部）
     * @param maxRetries          本次 withRetry 的上限（{@link WithRetryEngine#getMaxRetries}，供日志）
     * @param consecutive529Errors 连续 529 累计数（调用方闭包局部 · CC withRetry.ts:186）
     * @param retryContext        重试上下文快照（REPEATED_529 CannotRetryException 载荷 · CC:147）
     * @param persistent          持久重试模式（CC withRetry.ts:368-369 {@code isPersistentRetryEnabled() &&
     *                            isTransientCapacityError(error)}）——true 时用 PERSISTENT 上限 + reset header，
     *                            且 LlmAgentLoop 耗尽判定据此绕过（CC:370-371）
     * @param persistentAttemptHolder 持久重试计数闭包（CC persistentAttempt :188，独立于 attempt 持续增长至
     *                            5min cap；null=非持久路径）
     * @param fallbackModel       按调用传入的降级模型（CC original: options.fallbackModel withRetry.ts:130/337）；
     *                            非空优先，空则回落 {@link #FALLBACK_MODEL_SUPPLIER}（settings.fallbackModelId 仅默认值）
     * @return RecoveryResult 指示是否可恢复以及延迟时间
     */
    public RecoveryResult handle(Throwable e, RecoveryState state, int attempt, int maxRetries,
                                 int consecutive529Errors, RetryContext retryContext,
                                 boolean persistent, int[] persistentAttemptHolder,
                                 String fallbackModel) {
        // 分类闸 · CC withRetry.ts:375-382: handledCloudAuthError || (APIError && shouldRetry)
        // Java 等价 = ErrorClassifier.isRetryable（凭证自愈 handle* 或 shouldRetry 全集）
        if (!ErrorClassifier.isRetryable(e)) {
            log.error("TransientErrorHandler: 不可重试错误，不重试: {}", e.getMessage());
            // reason=null：withRetry 域动作（DC-09），CC query.ts 无 FATAL reason
            state.setLastReason(null);
            return new RecoveryResult(false, null,
                "non-retryable error: " + e.getMessage());
        }

        // 529 计数（计数由调用方闭包维护，本类只消费阈值判定）· CC withRetry.ts:610-621
        if (ErrorClassifier.is529Error(e)) {
            // [ER-IMP-10] CC 资格闸 · withRetry.ts:330-335:
            //   consecutive529Errors++ / fallback 仅当
            //   FALLBACK_FOR_ALL_PRIMARY_MODELS || (!isClaudeAISubscriber() && isNonCustomOpusModel(model))
            //   isClaudeAISubscriber 本项目 N/A（ErrorClassifier:649 恒 false）→
            //   闸 = FALLBACK_FOR_ALL_PRIMARY_MODELS || isNonCustomOpusModel(state.getCurrentModel())
            //   （state.getCurrentModel 即 CC options.model——当前请求主模型，fallback 切换后为 fallback 模型）
            if (!isEligibleFor529Fallback(state.getCurrentModel())) {
                log.warn("TransientErrorHandler: 主模型 {} 非自定义 Opus 且 FALLBACK_FOR_ALL_PRIMARY_MODELS 未开，"
                    + "529 不累计/不降级，仅退避重试 · CC withRetry.ts:329-333", state.getCurrentModel());
            } else if (consecutive529Errors >= ApiErrors.MAX_CONSECUTIVE_529) {
                // 连续 3 次 529 → fallback / REPEATED_529 快速失败 · CC withRetry.ts:337-363
                // V-EC-1: CC withRetry.ts:353-357 gates REPEATED_529 with !isPersistentRetryEnabled().
                //   persistent: has fallback -> FallbackTriggeredError (CC:337-351, not gated by persistent);
                //               no fallback -> null (fall through to backoff, continue retrying).
                //   non-persistent: has fallback -> FallbackTriggeredError; no fallback -> REPEATED_529.
                RecoveryResult fallbackResult = tryFallbackModel(state, retryContext, fallbackModel, persistent);
                if (fallbackResult != null) {
                    return fallbackResult;
                }
                // null = persistent + no fallback: fall through to backoff (CC:353-357 !persistent exempt)
            } else {
                log.warn("TransientErrorHandler: 529 #{} (连续), attempt={}/{} · CC withRetry.ts:610-621",
                    consecutive529Errors, attempt, maxRetries);
            }
        } else {
            // 429 — 不影响 529 计数
            log.info("TransientErrorHandler: 429 rate limit, attempt={}/{} · CC withRetry.ts:108",
                attempt, maxRetries);
        }

        // 延迟计算 · CC withRetry.ts:429-463
        // ER-IMP-04: 只读 Retry-After header（CC getRetryAfter 只读 header，DC-04/05 消息正则已删）
        // ER-IMP-06: 持久分支用 PERSISTENT 上限 + anthropic-ratelimit-unified-reset header（CC:433-460）
        Long retryAfterSeconds = e instanceof LlmApiException lae
            ? ErrorClassifier.extractRetryAfterSeconds(lae) : null;
        long delayMs;
        if (persistent) {
            int persistentAttempt = persistentAttemptHolder != null
                ? persistentAttemptHolder[0] + 1 : attempt;
            if (persistentAttemptHolder != null) {
                persistentAttemptHolder[0] = persistentAttempt;
            }
            LlmApiException lae = e instanceof LlmApiException apiEx ? apiEx : null;
            delayMs = RetryDelayCalculator.calculatePersistentDelay(
                persistentAttempt, retryAfterSeconds, lae);
            log.warn("TransientErrorHandler: 持久退避 {}ms (persistentAttempt={}, retryAfter={}) · CC withRetry.ts:433-463",
                delayMs, persistentAttempt, retryAfterSeconds);
        } else {
            delayMs = RetryDelayCalculator.calculate(attempt, retryAfterSeconds,
                ApiErrors.MAX_DELAY_MS);
            log.info("TransientErrorHandler: backoff {}ms (attempt={}, retryAfter={}) · CC withRetry.ts:530-548",
                delayMs, attempt, retryAfterSeconds);
        }
        state.setLastBackoffMs(delayMs);

        // reason=null：退避属 withRetry 域动作（DC-09），CC query.ts 无 BACKOFF_RETRY reason
        state.setLastReason(null);

        return new RecoveryResult(true, null,
            "backoff " + delayMs + "ms for attempt " + attempt);
    }

    /**
     * 尝试切换到 fallback 模型 · CC withRetry.ts:337-351。
     *
     * <p><b>ER-IMP-10 DC-18</b>：fallback 模型<b>按调用传入优先</b>（fallbackModelParam，
     * 即 CC withRetry.ts:337 {@code options.fallbackModel}），空则回落
     * {@link #FALLBACK_MODEL_SUPPLIER}（settings.fallbackModelId 仅默认值）。
     *
     * <p>切换模型（529 计数复位由 Path3 在 FallbackTriggeredError 处理时置闭包局部 0 ——
     * CC 中 FallbackTriggeredError 抛出 withRetry 后新 withRetry 计数清零）。抛出前埋
     * tengu_api_opus_fallback_triggered 遥测（CC withRetry.ts:338-346，provider 字段 Java 仅
     * anthropic 直连，等价 firstParty）。
     *
     * <p><b>无 fallback → CannotRetryException（ER-IMP-04 用户决策 4）</b>：CC withRetry.ts:353-363
     * 对 external 用户抛 CannotRetryError(new Error(REPEATED_529_ERROR_MESSAGE))；
     * 本项目无 USER_TYPE='external' 概念，等价实现为<b>统一阈值</b>——连续 3 次 529 且无
     * fallback 即快速失败（代码标注「CC original: USER_TYPE='external' 快速失败，本项目无
     * external 概念，等价实现为统一阈值」）。
     */
    private RecoveryResult tryFallbackModel(RecoveryState state, RetryContext retryContext,
                                            String fallbackModelParam, boolean persistent) {
        // [F4] 按调用传入优先，settings.fallbackModelId 仅默认值 · CC withRetry.ts:337 options.fallbackModel
        String fallbackModel = (fallbackModelParam != null && !fallbackModelParam.isBlank())
            ? fallbackModelParam
            : FALLBACK_MODEL_SUPPLIER.get();

        if (fallbackModel != null && !fallbackModel.isEmpty()) {
            String oldModel = state.getCurrentModel();
            state.setCurrentModel(fallbackModel);

            // tengu_api_opus_fallback_triggered 等价（slf4j+logback 中文）· CC withRetry.ts:338-346
            // provider 字段：Java 仅 anthropic 直连（无 bedrock/vertex 通道），等价 CC getAPIProviderForStatsig()='firstParty'
            log.warn("TransientErrorHandler: tengu_api_opus_fallback_triggered 等价 "
                    + "{{original_model={}, fallback_model={}, provider={}}} → fallback 模型切换 · CC withRetry.ts:338-346",
                oldModel, fallbackModel, "firstParty");

            // [H7-arch Phase 5 P4 C3] 抛 FallbackTriggeredError 对齐 CC withRetry.ts:337-351:
            // 连续 529 达阈值 + 配置 fallback → 抛出，loop 捕获后做 CC query.ts:894-953 清理。
            throw new FallbackTriggeredError(oldModel, fallbackModel);
        }

        // V-EC-1: CC withRetry.ts:353-357 gates REPEATED_529 with !isPersistentRetryEnabled().
        //   persistent mode: return null to fall through to backoff (continue retrying).
        //   non-persistent: throw CannotRetryException(REPEATED_529).
        if (persistent) {
            log.warn("TransientErrorHandler: persistent mode 3x529 no fallback, continue backoff (CC withRetry.ts:353-357)");
            return null;
        }
        log.error("TransientErrorHandler: 3x529 no fallback, REPEATED_529 fast fail (CannotRetryException) · CC withRetry.ts:353-363");
        throw new CannotRetryException(
            new RuntimeException(ApiErrors.REPEATED_529_ERROR_MESSAGE),
            retryContext);
    }
}
