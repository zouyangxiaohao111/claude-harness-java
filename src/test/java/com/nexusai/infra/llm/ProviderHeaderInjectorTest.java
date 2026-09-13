package com.nexusai.infra.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProviderHeaderInjector} 单测 · 注入侧单点判据。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）：
 * <ol>
 *   <li><b>零开销短路</b> —— 绝大多数 provider 没配自定义 header，未配置时 {@code putHeader}
 *       一次都不该被调（否则每个请求都白跑一次展开）。</li>
 *   <li><b>禁止头是唯一防线</b> —— T2 实测 {@code putHeader} 能顶掉 SDK 依 {@code .apiKey()}
 *       自动注入的凭据头，且与调用顺序无关；写侧虽已拒（D5），但**存量脏数据**（V72 前手写
 *       序列化时代的库内旧值 / 绕过 REST 直改 DB）可能绕过写侧。注入侧若不跳过，凭据头就被
 *       用户配置静默顶掉 —— 且没有任何第二道防线。</li>
 *   <li><b>占位符永不字面量上线</b> —— {@code ${session_id}} 必须落真值或兜底常量，
 *       否则 opencode 侧静默 400（本仓 ~18 条辅助调用拿不到会话上下文，故 D4 选发常量）。</li>
 *   <li><b>gate 默认开</b>（D6）—— 读源未安装（POJO 单测 / 装配失败）时默认 true，
 *       与 V72 列默认值 1 一致；读抛异常也按开（不因读失败关掉用户功能）。</li>
 * </ol>
 *
 * <p>[provider-custom-headers 任务 6]
 */
class ProviderHeaderInjectorTest {

    private static final String SESSION_TOKEN = DynamicHeaderExpander.SESSION_ID_TOKEN;
    private static final String STATIC_FALLBACK = DynamicHeaderExpander.STATIC_FALLBACK;
    private static final String SESSION_ID = "sess-abc12345";

    /** 用例间复位静态读源：否则前一个用例安装的 source 会泄漏到下一个（尤其 gate 相关用例）。 */
    @AfterEach
    void resetGateSource() {
        ProviderHeaderInjector.installGateSource(null);
    }

    // ─────────────────────── 1. 空短路（零开销） ───────────────────────

    @Test
    @DisplayName("注入侧：extraHeaders 为 null / 空 Map → putHeader 一次都不调（绝大多数请求走这里）")
    void extraHeadersNullOrEmpty_neverCallsPutHeader() {
        // ⚠️ 本用例对「零开销短路」**零鉴别力**（实测）：即便删掉 apply 的短路，
        //   expandAll(null/空) 也返回空 Map、putHeader 依然一次不调 → 本用例恒绿。
        //   短路的可鉴别断言在 extraHeadersNullOrEmpty_doesNotReadGateSource（不读 gate ⇒ 不落 DB）。
        List<String> calls = new ArrayList<>();
        BiConsumer<String, String> putHeader = (k, v) -> calls.add(k + "=" + v);

        ProviderHeaderInjector.apply(putHeader, null, SESSION_ID);
        ProviderHeaderInjector.apply(putHeader, Map.of(), SESSION_ID);

        assertThat(calls)
            .as("null / 空 Map 都必须零调用 putHeader")
            .isEmpty();
    }

    @Test
    @DisplayName("注入侧：空短路必须**不读 gate 读源**（零开销的真实语义是「不落 DB」，不是「不调 putHeader」）")
    void extraHeadersNullOrEmpty_doesNotReadGateSource() {
        // WHY：apply 的空短路真正在买的东西 = 「不读 gate ⇒ 不落一次 DB」。
        //   若把短路删掉，gateEnabled() 会作为 expandAll 的**实参被提前求值** → 每个空配置请求
        //   白落一次 settings 查询（Web 多会话下高频）。用计数型读源把这点钉住：
        //   传空 header 必须 0 次读；传非空 header 必然 ≥1 次读（证明读源本身是通的，不是恒 0 的假绿）。
        AtomicInteger gateReads = new AtomicInteger();
        ProviderHeaderInjector.installGateSource(() -> {
            gateReads.incrementAndGet();
            return true;
        });

        ProviderHeaderInjector.apply((k, v) -> {}, null, SESSION_ID);
        ProviderHeaderInjector.apply((k, v) -> {}, Map.of(), SESSION_ID);
        assertThat(gateReads.get())
            .as("null / 空 Map 必须在读 gate **之前**短路（否则每个请求白落一次 DB 查询）")
            .isZero();

        ProviderHeaderInjector.apply((k, v) -> {}, Map.of("X-A", "v"), SESSION_ID);
        assertThat(gateReads.get())
            .as("非空 header 必然读 gate 至少一次（同时证明上面的 0 不是「读源根本没被调用」的假绿）")
            .isGreaterThanOrEqualTo(1);
    }

    // ─────────────────────── 2. 静态值原样透传 ───────────────────────

    @Test
    @DisplayName("注入侧：不含占位符的静态值原样注入（既有静态配置零行为变化）")
    void staticValue_passedThroughUnchanged() {
        Map<String, String> calls = collect(Map.of("X-Static", "v1", "X-Another", "v2"), SESSION_ID);

        assertThat(calls)
            .as("静态值必须逐字注入，不做任何改写")
            .containsExactlyInAnyOrderEntriesOf(Map.of("X-Static", "v1", "X-Another", "v2"));
    }

    // ─────────────────────── 3. 占位符展开 ───────────────────────

    @Test
    @DisplayName("注入侧：${session_id} + 真会话 ID → 注入真值（缓存亲和的来源）")
    void placeholder_withRealSessionId_injectsRealValue() {
        // 显式安装 true：本用例的**被测意图**是「sessionId 真值 → 展开真值」，
        //   与 gate 默认值解耦（否则「默认开」变异会连带打红本用例，诊断噪音）。
        //   gate 默认值本身由 gateSourceNotInstalled_defaultsToTrue 单独守。
        ProviderHeaderInjector.installGateSource(() -> true);

        Map<String, String> calls = collect(Map.of("X-Session", SESSION_TOKEN), SESSION_ID);

        assertThat(calls)
            .as("占位符必须替换为本次请求的真会话 ID（否则主链失去缓存亲和）")
            .containsExactlyInAnyOrderEntriesOf(Map.of("X-Session", SESSION_ID));
    }

    @Test
    @DisplayName("注入侧：${session_id} + sessionId 为 null → 注入兜底常量 nexusai-static（绝不发字面量占位符）")
    void placeholder_withNullSessionId_injectsStaticFallback() {
        Map<String, String> calls = collect(Map.of("X-Session", SESSION_TOKEN), null);

        assertThat(calls)
            .as("会话上下文缺失必须落 STATIC_FALLBACK（D4：发常量而非丢弃，避免辅助调用静默 400）")
            .containsExactlyInAnyOrderEntriesOf(Map.of("X-Session", STATIC_FALLBACK));
        assertThat(calls.values())
            .as("任何路径都绝不把字面量 ${session_id} 发到线上（关键不变量）")
            .doesNotContain(SESSION_TOKEN);
    }

    // ─────────────────────── 4. 防御性过滤（存量脏数据） ───────────────────────

    @Test
    @DisplayName("注入侧：map 里混入禁止头（模拟存量脏数据）→ 该头被跳过，其余照常注入")
    void forbiddenHeaderInDirtyData_isSkippedOthersInjected() {
        // 依据：ProviderHeaderInjector 的防御性过滤（写侧 D5 已拒，但存量脏数据可绕过）。
        // 本用例在「删掉防御性过滤」的变异下必须变红。
        Map<String, String> dirty = new LinkedHashMap<>();
        dirty.put("Authorization", "Bearer stolen");
        dirty.put("x-api-key", "stolen-key");
        dirty.put("X-Custom", "kept");

        Map<String, String> calls = collect(dirty, SESSION_ID);

        assertThat(calls)
            .as("禁止头必须被跳过（T2 实测 putHeader 恒顶掉 SDK 凭据头，且无第二道防线）")
            .doesNotContainKeys("Authorization", "x-api-key", "authorization");
        assertThat(calls)
            .as("非禁止头必须照常注入（过滤不能误伤合法自定义头）")
            .containsExactlyInAnyOrderEntriesOf(Map.of("X-Custom", "kept"));
    }

    @Test
    @DisplayName("注入侧：脏数据里 value 为 null 的条目 → 跳过（不静默丢，也不 NPE）")
    void dirtyEntryWithNullValue_isSkipped() {
        // 脏 JSON 反序列化正是产生 null value 的唯一通道（DynamicHeaderExpander.expandAll 的 skip 分支）。
        Map<String, String> dirty = new HashMap<>();
        dirty.put("X-NullValue", null);
        dirty.put("X-Ok", "v");

        Map<String, String> calls = collect(dirty, SESSION_ID);

        assertThat(calls)
            .as("null 值条目必须被跳过（跳过绝不静默：DynamicHeaderExpander 已 warn）")
            .containsExactlyInAnyOrderEntriesOf(Map.of("X-Ok", "v"));
    }

    // ─────────────────────── 5. gate（settings.allow_dynamic_header_values） ───────────────────────

    @Test
    @DisplayName("gate：读源未安装 → 默认 true（与 V72 列默认值 1 / D6 默认开一致）")
    void gateSourceNotInstalled_defaultsToTrue() {
        // 未安装态：@AfterEach 已复位 + 本用例不安装。
        assertThat(ProviderHeaderInjector.gateEnabled())
            .as("读源未安装必须默认开（POJO 单测 / 装配失败不得静默关闭用户功能）")
            .isTrue();

        Map<String, String> calls = collect(Map.of("X-Session", SESSION_TOKEN), SESSION_ID);
        assertThat(calls)
            .as("未安装时占位符仍须按会话展开（默认开的行为面证据）")
            .containsExactlyInAnyOrderEntriesOf(Map.of("X-Session", SESSION_ID));
    }

    @Test
    @DisplayName("gate：读源返回 false（用户主动关闭）→ 占位符落兜底常量")
    void gateSourceReturnsFalse_placeholderFallsBackToStatic() {
        ProviderHeaderInjector.installGateSource(() -> false);

        assertThat(ProviderHeaderInjector.gateEnabled())
            .as("读源原值 false 必须如实返回（默认开不等于恒开）")
            .isFalse();

        Map<String, String> calls = collect(Map.of("X-Session", SESSION_TOKEN), SESSION_ID);
        assertThat(calls)
            .as("开关关闭时占位符落兜底常量（不按会话展开，但也不丢 header）")
            .containsExactlyInAnyOrderEntriesOf(Map.of("X-Session", STATIC_FALLBACK));
    }

    @Test
    @DisplayName("gate：读源抛异常 → 默认 true（不因读失败关掉用户功能）")
    void gateSourceThrows_defaultsToTrue() {
        ProviderHeaderInjector.installGateSource(() -> {
            throw new IllegalStateException("DB down");
        });

        assertThat(ProviderHeaderInjector.gateEnabled())
            .as("读取抛异常必须按默认开处理（fail-soft，与 SettingsService.readDbAllowDynamicHeaderValues 同款）")
            .isTrue();
    }

    // ─────────────────────── helpers ───────────────────────

    /** 用收集型 BiConsumer 跑一次 apply，返回 被注入的 header 名 → 值。 */
    private static Map<String, String> collect(Map<String, String> extraHeaders, String sessionId) {
        Map<String, String> injected = new LinkedHashMap<>();
        ProviderHeaderInjector.apply(injected::put, extraHeaders, sessionId);
        return injected;
    }
}
