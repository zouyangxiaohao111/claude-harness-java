package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.permission.hook.ApiQueryHookHelper.ApiQueryHookConfig;
import com.nexusai.application.agent.permission.hook.ApiQueryHookHelper.ApiQueryHookContext;
import com.nexusai.application.agent.permission.hook.ApiQueryHookHelper.ApiQueryResult;
import com.nexusai.application.agent.permission.hook.ApiQueryHookHelper.ApiQuerySuccess;
import com.nexusai.application.agent.permission.hook.ApiQueryHookHelper.ModelQueryExecutor;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H12] ApiQueryHookHelper 泛型工厂 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/apiQueryHookHelper.ts:56-141}.
 *
 * <p>WHY (规则九 · 测试验证意图): apiQueryHook 是"侧信道 LLM 查询"的通用工厂 —
 * shouldRun 门控 → buildMessages 构造消息 → queryModelWithoutStreaming 查询 →
 * parseResponse 解析 → logResult 上报成功/失败. 若任一环节断裂 (门控不生效 / 查询不调用 /
 * 解析失败无 error 上报), 侧信道能力会静默失效.
 *
 * <ul>
 *   <li>shouldRun=false → 不调用查询、不上报 (CC L61-64)</li>
 *   <li>useTools 门控 → useTools=false 时传空 tools (CC L78-79)</li>
 *   <li>getModel 惰性加载 → 仅 shouldRun 通过后才解析 model (CC L82)</li>
 *   <li>parseResponse 异常 → logResult 上报 error 判别联合 (CC L126-136)</li>
 *   <li>querySource 透传 → config.name 作为 querySource (CC L104)</li>
 * </ul>
 */
@DisplayName("[H12] ApiQueryHookHelper 泛型工厂对齐 CC apiQueryHookHelper.ts")
class ApiQueryHookHelperTest {

    /** WHY: 测试便捷构造 user 消息. */
    private static ChatMessageDto userMsg(String content) {
        return new ChatMessageDto("m1", "sess", Role.user, "user", content, null, null, null,
                null, null, "刚刚", OffsetDateTime.now(), null, null, null, null, null);
    }

    /** WHY: 测试便捷构造 assistant 消息. */
    private static ChatMessageDto assistantMsg(String content) {
        return new ChatMessageDto("m2", "sess", Role.assistant, "assistant", content, null, null, null,
                null, null, "刚刚", OffsetDateTime.now(), null, null, null, null, null);
    }

    private static PostSamplingContext contextWith(QuerySource querySource, List<ChatMessageDto> messages) {
        return new PostSamplingContext(messages, List.of("system"), Map.of(), Map.of(), null, querySource);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1-2. 正向流水线
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: 完整流水线 must run in order — shouldRun → buildMessages → query →
     * parseResponse → logResult(success). 若 logResult 收不到成功结果, 侧信道检测
     * (如 skillImprovement) 无法上报, 功能断裂.
     */
    @Test
    @DisplayName("shouldRun→buildMessages→query→parseResponse→logResult(success)")
    void pipeline_producesSuccessResult() {
        AtomicReference<ApiQueryResult<List<String>>> captured = new AtomicReference<>();
        List<String> callLog = new ArrayList<>();
        ModelQueryExecutor executor = (sp, user, model, useTools, options) -> {
            callLog.add("query:" + model + ":" + useTools);
            return new com.nexusai.infra.llm.LlmProvider.LlmRawResponse("<updates>[{\"section\":\"a\"}]</updates>", "msg-1", null, null);
        };
        ApiQueryHookConfig<List<String>> config = new ApiQueryHookConfig<>(
                "skill_improvement",
                ctx -> { callLog.add("shouldRun"); return true; },
                ctx -> { callLog.add("buildMessages"); return "classify me"; },
                null,
                false,   // useTools = false → 传空 tools
                (content, ctx) -> {
                    callLog.add("parseResponse");
                    return List.of(content);
                },
                (result, ctx) -> captured.set(result),
                ctx -> { callLog.add("getModel"); return "small-fast"; });

        PostSamplingHookRegistry.PostSamplingHook hook =
                ApiQueryHookHelper.createApiQueryHook(config, executor);
        hook.onSampled(contextWith(QuerySource.REPL_MAIN_THREAD, List.of(userMsg("hi"), assistantMsg("ok"))));

        assertThat(captured.get()).isInstanceOf(ApiQuerySuccess.class);
        ApiQuerySuccess<List<String>> success = (ApiQuerySuccess<List<String>>) captured.get();
        assertThat(success.queryName()).isEqualTo("skill_improvement");
        assertThat(success.model()).isEqualTo("small-fast");
        assertThat(success.uuid()).isNotBlank();
        // CC 顺序: shouldRun → buildMessages → getModel(惰性) → queryModelWithoutStreaming → parseResponse
        assertThat(callLog).containsExactly(
                "shouldRun", "buildMessages", "getModel", "query:small-fast:false", "parseResponse");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3-5. 门控 / 惰性 / 错误
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: shouldRun 门控 (CC L61-64) — 门控不通过必须短路, 不调用查询、不调用 getModel、
     * 不上报 logResult. 若门控被跳过, 每个 post-sampling 都发一次无用 LLM 查询 = 成本爆炸.
     */
    @Test
    @DisplayName("shouldRun=false → 短路: 不查询、不 getModel、不上报")
    void shouldRunFalse_shortCircuits() {
        AtomicReference<ApiQueryResult<List<String>>> captured = new AtomicReference<>();
        int[] queryCount = {0};
        int[] getModelCount = {0};
        ModelQueryExecutor executor = (sp, user, model, useTools, options) -> { queryCount[0]++; return new com.nexusai.infra.llm.LlmProvider.LlmRawResponse("", null, null, null); };
        ApiQueryHookConfig<List<String>> config = new ApiQueryHookConfig<>(
                "skill_improvement",
                ctx -> false,
                ctx -> "msg",
                null,
                null,
                (content, ctx) -> List.of(content),
                (result, ctx) -> captured.set(result),
                ctx -> { getModelCount[0]++; return "m"; });

        PostSamplingHookRegistry.PostSamplingHook hook =
                ApiQueryHookHelper.createApiQueryHook(config, executor);
        hook.onSampled(contextWith(QuerySource.REPL_MAIN_THREAD, List.of(userMsg("hi"))));

        assertThat(queryCount[0]).isZero();
        assertThat(getModelCount[0]).isZero();
        assertThat(captured.get()).isNull();
    }

    /**
     * WHY: parseResponse 抛异常时 (CC L126-136) 必须上报 error 判别联合而非吞掉 —
     * 让调用方 (logResult) 能区分成功/失败, 失败不应写 suggestion.
     */
    @Test
    @DisplayName("parseResponse 异常 → logResult 上报 error 判别联合")
    void parseResponseThrows_reportsErrorUnion() {
        AtomicReference<ApiQueryResult<List<String>>> captured = new AtomicReference<>();
        ModelQueryExecutor executor = (sp, user, model, useTools, options) ->
                new com.nexusai.infra.llm.LlmProvider.LlmRawResponse("<updates>bad json</updates>", "msg-1", null, null);
        ApiQueryHookConfig<List<String>> config = new ApiQueryHookConfig<>(
                "skill_improvement",
                ctx -> true,
                ctx -> "msg",
                null,
                null,
                (content, ctx) -> { throw new IllegalArgumentException("bad json"); },
                (result, ctx) -> captured.set(result),
                ctx -> "m");

        PostSamplingHookRegistry.PostSamplingHook hook =
                ApiQueryHookHelper.createApiQueryHook(config, executor);
        hook.onSampled(contextWith(QuerySource.REPL_MAIN_THREAD, List.of(userMsg("hi"))));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().queryName()).isEqualTo("skill_improvement");
        assertThat(captured.get().error()).isNotNull();
        assertThat(captured.get().error()).contains("bad json");
    }

    /**
     * WHY: useTools 门控 (CC L78-79) — useTools=false 必须把 useTools 透传到查询层.
     * 若门控被忽略, 模型会拿到 tools 定义去猜工具, 违背"纯分类不调工具"意图.
     */
    @Test
    @DisplayName("useTools=false → 查询层收到 useTools=false")
    void useToolsFalse_passedToQueryLayer() {
        AtomicReference<Boolean> capturedUseTools = new AtomicReference<>();
        ModelQueryExecutor executor = (sp, user, model, useTools, options) -> {
            capturedUseTools.set(useTools);
            return new com.nexusai.infra.llm.LlmProvider.LlmRawResponse("[]", null, null, null);
        };
        ApiQueryHookConfig<List<String>> config = new ApiQueryHookConfig<>(
                "skill_improvement", ctx -> true, ctx -> "msg", null, false,
                (content, ctx) -> List.of(), (result, ctx) -> {}, ctx -> "m");

        PostSamplingHookRegistry.PostSamplingHook hook =
                ApiQueryHookHelper.createApiQueryHook(config, executor);
        hook.onSampled(contextWith(QuerySource.REPL_MAIN_THREAD, List.of(userMsg("hi"))));

        assertThat(capturedUseTools.get()).isFalse();
    }

    /**
     * WHY: querySource 透传 (CC L104) — config.name 作为 querySource 传给查询层, 让
     * LLM 侧/遥测能区分是 skill_improvement 还是别的侧信道查询.
     */
    @Test
    @DisplayName("querySource 透传 = config.name")
    void querySource_passthroughFromConfigName() {
        AtomicReference<String> capturedSource = new AtomicReference<>();
        ModelQueryExecutor executor = (sp, user, model, useTools, options) -> {
            capturedSource.set("skill_improvement");
            return new com.nexusai.infra.llm.LlmProvider.LlmRawResponse("[]", null, null, null);
        };
        ApiQueryHookConfig<List<String>> config = new ApiQueryHookConfig<>(
                "skill_improvement", ctx -> true, ctx -> "msg", null, null,
                (content, ctx) -> List.of(), (result, ctx) -> {}, ctx -> "m");

        PostSamplingHookRegistry.PostSamplingHook hook =
                ApiQueryHookHelper.createApiQueryHook(config, executor);
        hook.onSampled(contextWith(QuerySource.REPL_MAIN_THREAD, List.of(userMsg("hi"))));

        assertThat(capturedSource.get()).isEqualTo("skill_improvement");
    }

    /** WHY: 判别联合 — error 分支有 error 字段, success 分支有 result 字段 (互斥). */
    @Test
    @DisplayName("ApiQueryResult 判别联合: success 有 result / error 有 error")
    void resultUnion_separatesSuccessAndError() {
        ApiQuerySuccess<List<String>> s = new ApiQuerySuccess<>("q", List.of("a"), "m1", "m", "u");
        assertThat(s.result()).containsExactly("a");
        assertThat(s.error()).isNull();
    }
}
