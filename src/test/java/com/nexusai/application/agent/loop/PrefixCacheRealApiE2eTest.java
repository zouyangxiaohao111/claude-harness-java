package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.OpenAiSdkProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>步骤 8 · 第 5 条（真 API e2e）· 骨架 + 显式门控</b>：证明「除第一条外每条请求都命中服务端前缀缓存」。
 *
 * <h2>⚠️ 状态：骨架（未在本批真跑过）</h2>
 * <p>本类<b>故意</b>不默认运行：{@code @EnabledIfEnvironmentVariable} 要求
 * {@code NEXUSAI_PREFIX_CACHE_E2E_API_KEY} 存在且非空。无 key ⇒ 本类整体 <b>skipped</b>
 * （surefire 记 skipped，<b>不是</b>绿 —— 见 CLAUDE.md 规则 12「有测试被跳过，宣称测试通过即为错误」）。
 *
 * <p><b>为什么只交骨架</b>（诚实登记，不假装已验证）：
 * <ol>
 *   <li>真调用会消耗用户的真实配额与网络；本批的判据要求「无 key 自动 skip」，而本环境无该 key；</li>
 *   <li>真跑需要确认 {@link OpenAiSdkProvider} 在「无 Spring 容器、直连构造」下的装配完整
 *       （该 provider 的生产装配由 Spring 注入若干可选协作者）；本骨架用最小实参驱动
 *       {@code stream}，未在真机验证过 —— 首次真跑必须由人来确认（不得把未验证的骨架当已验收）。</li>
 * </ol>
 *
 * <h2>真源对照（dsh {@code packages/core/agent-loop/tests/request-cache.e2e.ts}）</h2>
 * <table border="1">
 *   <caption>dsh 断言 → 本类</caption>
 *   <tr><th>dsh</th><th>本类</th></tr>
 *   <tr><td>{@code describe.skipIf(!process.env.DEEPSEEK_API_KEY)}（:71）</td>
 *       <td>{@code @EnabledIfEnvironmentVariable(named = "NEXUSAI_PREFIX_CACHE_E2E_API_KEY", matches = ".+")}</td></tr>
 *   <tr><td>场景 ≥3 次模型请求（:86 {@code usages.length >= 3}）</td>
 *       <td>本类循环发 3 次（前缀逐次加长）</td></tr>
 *   <tr><td>{@code for (const usage of usages.slice(1)) expect(cacheReadTokens ?? 0) > 0}（:92-94）</td>
 *       <td>{@code usages.subList(1, n)} 每条 {@code cacheReadInputTokens > 0}</td></tr>
 *   <tr><td>超时 180s（:103）</td><td>{@code @Timeout(180)}</td></tr>
 * </table>
 * <p>⚠ 本仓与 dsh 的一处映射差异：dsh 的 {@code cacheReadTokens} 来自其 DeepSeek adapter 的
 * {@code prompt_cache_hit_tokens}；本仓同一来源经 {@code OpenAiSdkProvider.extractUsage}
 * 落到 {@code AgentUsage.cacheReadInputTokens}（{@code _additionalProperties.prompt_cache_hit_tokens}，
 * 标准字段 {@code prompt_tokens_details.cached_tokens} 兜底）。
 */
@DisplayName("[步骤 8 · 真 API e2e（骨架 · 需显式门控 key）] 除首条外每条请求的 cacheRead > 0")
@EnabledIfEnvironmentVariable(named = "NEXUSAI_PREFIX_CACHE_E2E_API_KEY", matches = ".+")
class PrefixCacheRealApiE2eTest {

    /** 门控：key（必须显式提供；本类不硬编码任何 key）。 */
    private static final String API_KEY = System.getenv("NEXUSAI_PREFIX_CACHE_E2E_API_KEY");
    /** base url（默认 DeepSeek 官方；可用 env 覆盖到任意 OpenAI 兼容端点）。 */
    private static final String BASE_URL =
        envOrDefault("NEXUSAI_PREFIX_CACHE_E2E_BASE_URL", "https://api.deepseek.com");
    /** 模型名（默认 deepseek 兼容模型；可用 env 覆盖）。 */
    private static final String MODEL =
        envOrDefault("NEXUSAI_PREFIX_CACHE_E2E_MODEL", "deepseek-v4-flash");

    /**
     * 够长的 system：使其跨越服务端缓存块粒度（dsh 同款理由 —— 首次请求就要有可被命中的前缀，
     * 否则「第二条命中」可能只是缓存块还没成形）。
     */
    private static final String SYSTEM =
        "You are a terse coding assistant used in an automated prefix-cache test. "
            + "Always follow instructions literally and exactly. When the user asks you to look "
            + "something up, call the lookup tool with the requested key and wait for its result "
            + "before answering. Never invent a value the tool has not returned. After the tool "
            + "returns, answer with a single short sentence that repeats the returned value "
            + "verbatim. Do not add explanations, do not use markdown, do not ask follow-up "
            + "questions. If the user asks anything else, answer in one short sentence.";

    @Test
    @Timeout(180)
    @DisplayName("连续 3 次请求（前缀逐次加长）：第 2、3 次的 cacheRead 必须 > 0")
    void everyRequestAfterTheFirstHitsTheProviderPrefixCache() {
        assertThat(API_KEY).as("门控放行时 key 必须非空（否则本类不该运行）").isNotBlank();

        LlmProvider provider = new OpenAiSdkProvider();
        ProviderConfig config = new ProviderConfig(BASE_URL, API_KEY);

        List<Long> cacheReads = new ArrayList<>();
        List<ChatMessageDto> history = new ArrayList<>();

        // 第 1 次：只有 system + 首条 user（无任何可命中前缀）
        history.add(userMessage("Look up the key \"deploy-color\" and tell me the value."));
        cacheReads.add(callOnce(provider, config, history));

        // 第 2 / 3 次：把上一轮的助手回复追加回历史（append-only ⇒ 前缀严格扩展）
        for (int i = 2; i <= 3; i++) {
            history.add(assistantMessage("azure-falcon-42"));
            history.add(userMessage("Thanks. Repeat that value one more time."));
            cacheReads.add(callOnce(provider, config, history));
        }

        assertThat(cacheReads).as("场景必须 ≥3 次模型请求（dsh :86）").hasSizeGreaterThanOrEqualTo(3);
        for (int i = 1; i < cacheReads.size(); i++) {
            Long read = cacheReads.get(i);
            assertThat(read)
                .as("第 %d 次请求必须报出 cacheRead（usage 缺失 ⇒ 无法判定，按 fail-loud 处理，"
                    + "⛔ 不得默认成「通过」）", i + 1)
                .isNotNull();
            assertThat(read)
                .as("第 %d 次请求的 cacheRead 必须 > 0（前缀与上一次逐字节相同 ⇒ 服务端必须命中）· dsh :92-94", i + 1)
                .isGreaterThan(0L);
        }
    }

    /** 单次调用 · 返回 cacheRead（usage 缺失 ⇒ null，由断言 fail-loud）。 */
    private static Long callOnce(LlmProvider provider, ProviderConfig config, List<ChatMessageDto> history) {
        AtomicReference<AssistantMessage> last = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        provider.stream(config, MODEL,
            List.of(new SystemPromptBlock(SYSTEM, CacheScope.NULL)),
            List.copyOf(history), null,
            null, null, null, null,
            chunk -> { }, last::set, call -> { }, chunk -> { },
            () -> { }, AbortController.NOOP, failure::set, () -> { }, null, null);
        if (failure.get() != null) {
            throw new IllegalStateException("真 API 调用失败（e2e 必须 fail-loud，⛔ 不静默跳过）", failure.get());
        }
        AssistantMessage msg = last.get();
        if (msg == null || msg.usage() == null) {
            return null;
        }
        return msg.usage().cacheReadInputTokens();
    }

    private static ChatMessageDto userMessage(String text) {
        return new ChatMessageDto(UUID.randomUUID().toString(), null, Role.user, "user",
            text, null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false);
    }

    private static ChatMessageDto assistantMessage(String text) {
        return new ChatMessageDto(UUID.randomUUID().toString(), null, Role.assistant, "assistant",
            text, null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false);
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
