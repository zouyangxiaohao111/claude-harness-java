package com.nexusai.infra.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * 把 provider 的自定义请求头灌进 SDK client builder · 注入侧单点判据源。
 *
 * <p>两个 SDK provider（Anthropic / OpenAI）共用本类，避免出现两套合并/过滤逻辑
 * （本仓有「同一能力两套判据」的 R7 前科）。
 * 判据本身来自 {@link DynamicHeaderExpander}（与写侧校验同源）。
 *
 * <p>设计见 docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.2。
 *
 * <p><b>为什么还要一次防御性过滤</b>：写侧（{@code ProviderService.validateHeaders}）已拒绝
 * 敏感头，但**存量脏数据**（V72 之前手写序列化时代的库内旧值、或绕过 REST 直改 DB）可能含
 * 禁止头。T2 实测确认 {@code putHeader} 能<b>顶掉</b> SDK 依 {@code .apiKey()} 自动注入的凭据头，
 * 且与调用顺序无关 —— 故碰上禁止头必须<b>跳过而不是 put</b>，且不静默（warn）。
 */
public final class ProviderHeaderInjector {

    private static final Logger log = LoggerFactory.getLogger(ProviderHeaderInjector.class);

    /**
     * settings.allow_dynamic_header_values 的<b>实时读源</b>（启动期经
     * {@link #installGateSource(BooleanSupplier)} 安装）。
     *
     * <p>为什么是 static 字段而不是 Spring 注入：调用方 {@code buildClient} 是 static，
     * 本类也是 static 工具类，都拿不到容器。本仓对「static 代码要读 DB settings」的既有解法即
     * 「@PostConstruct 安装一个实时读源」——照抄
     * {@code TaskSystemConfig.installAgentSwarmsSettingsSource(this::readDbAgentSwarmsEnabled)}
     * 范式（安装点：{@code SettingsService} 的 @PostConstruct）。
     */
    private static volatile BooleanSupplier gateSource;

    /** 「读源未安装」的 warn <b>至多一次</b>标志（Web 多会话并发下防刷屏）。 */
    private static final AtomicBoolean WARNED_SOURCE_MISSING = new AtomicBoolean(false);

    /**
     * [D 可观测性] 「{@code extraHeaders} 为空 Map」的 warn 频控标志（**首次**必打）。
     *
     * <p>为什么这个条件是缺陷指纹：本仓 {@code ProviderConfig} 的三种取值各有确定来源 ——
     * <ul>
     *   <li>{@code null} = <b>未配置</b> header（{@code deserializeHeaders(null)} 恒 null；
     *       绝大多数 provider 走这里，属<b>正常</b>，⛔ 不该打日志）；</li>
     *   <li>非空 Map = 配了 header（正常生效路径）；</li>
     *   <li><b>空 Map = 2 参便捷构造器落 {@code Map.of()} 的指纹</b>（
     *       {@link ProviderConfig#ProviderConfig(String, String)}）—— 或 {@code deserializeHeaders}
     *       解析失败（那条路已自带 warn + 原始片段）。</li>
     * </ul>
     * 而 {@code ProviderConfig.empty()} 也是 2 参构造 → 空 Map，但它 {@code apiKey == null} ⇒
     * {@code isUsable() == false} ⇒ 经
     * {@code LlmProviderFactory.getProvider} 恒落 {@code MockLlmProvider}
     * （LlmProviderFactory.java:48）⇒ <b>永远到不了本类</b>。故本分支收到的「空 Map」必然是
     * 「一个可用配置却没带上 header」= 缺陷形态（2026-09-24 实测：两处 2 参生产构造点
     * ToolRegistrationConfig.java:1994/:2113 令 fork / 压缩 / away-summary 恒零自定义 header）。
     */
    private static final AtomicBoolean WARNED_EMPTY_MAP_CONFIG = new AtomicBoolean(false);

    /**
     * [D 可观测性] 「空 Map 配置」累计命中数（只用于每 {@link #EMPTY_MAP_CONFIG_WARN_EVERY}
     * 次复述一次，证明缺陷仍在发生；不逐条打）。
     */
    private static final java.util.concurrent.atomic.AtomicLong EMPTY_MAP_CONFIG_HITS =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * 空 Map 配置 warn 的复述周期。
     *
     * <p><b>日志量控制（硬要求，本仓有「日志刷屏」前科）</b>：缺陷形态下**每个请求**都会命中本分支
     * （fork 家族每轮触发）⇒ 逐条 WARN 会瞬间淹没日志。策略 = <b>首次必打 + 每 1000 次复述一次</b>
     * ⇒ 一个 24h 跑 1658 次 fork 的会话最多 2~3 行；其余情况静默。
     * 逐请求级别的「header 到底出去没有」由 {@code [前缀缓存探针] 出站 ... hdrs=N} 承担
     * （见 {@link #injectableCount}），本 warn 只负责<b>指认成因</b>。
     */
    private static final long EMPTY_MAP_CONFIG_WARN_EVERY = 1000L;

    private ProviderHeaderInjector() {
    }

    /**
     * 启动期安装实时读源（{@code SettingsService.readDbAllowDynamicHeaderValues} 的方法引用）。
     *
     * <p><b>传 {@code null} = 卸载</b>（回到「未安装」态 → {@link #gateEnabled()} 返回默认 true）。
     * 这与 {@code TaskSystemConfig.installAgentSwarmsSettingsSource} 的 null-guard（null 不覆盖）
     * **有意不同**：那边 null 会掩盖「装配失败」，而本类必须让测试能把静态读源复位到未安装态
     * （否则「未安装 → 默认开」这条防线无法独立验证，且读源会跨用例泄漏）。
     *
     * @param source 每次调用实时读 settings.allow_dynamic_header_values；null = 卸载（仅测试用）
     */
    public static void installGateSource(BooleanSupplier source) {
        gateSource = source;
    }

    /**
     * 读取 settings.allow_dynamic_header_values 开关。
     *
     * <p>语义（任务书定案，与 V72 列默认值 1 一致）：
     * <ul>
     *   <li>未安装读源（POJO 单测 / 装配失败）→ 默认 <b>true</b> + warn 至多一次；</li>
     *   <li>读取抛异常 → 默认 <b>true</b> + warn（不因读失败而关掉用户功能）；</li>
     *   <li>其余 → 读源原值。</li>
     * </ul>
     *
     * <p><b>不引入缓存</b>：本仓 settings 读路径就是「每请求读一次」（{@code PromptAlignSettingsResolver} 同款）。
     */
    static boolean gateEnabled() {
        BooleanSupplier s = gateSource;
        if (s == null) {
            if (WARNED_SOURCE_MISSING.compareAndSet(false, true)) {
                log.warn("[ProviderHeaderInjector] allow_dynamic_header_values 实时读源未安装"
                    + "（无 Spring 上下文 / 装配失败）→ 按默认开处理，占位符将按会话展开");
            }
            return true;
        }
        try {
            return s.getAsBoolean();
        } catch (Exception e) {
            log.warn("[ProviderHeaderInjector] 读取 allow_dynamic_header_values 失败，按默认开处理: {}",
                e.toString());
            return true;
        }
    }

    /**
     * 展开占位符 → 防御性过滤敏感头 → 逐个 putHeader。
     *
     * @param putHeader    SDK client builder 的方法引用（两个 SDK 签名一致：{@code putHeader(String,String)}）
     * @param extraHeaders provider 配置的原始 header（含未展开占位符）；null/空 → 直接返回，零开销
     * @param sessionId    本次请求解析出的会话 ID（见 {@link SessionIdResolver}）；
     *                     null → 占位符落兜底常量 {@link DynamicHeaderExpander#STATIC_FALLBACK}
     */
    public static void apply(BiConsumer<String, String> putHeader,
                             Map<String, String> extraHeaders,
                             String sessionId) {
        if (putHeader == null || extraHeaders == null || extraHeaders.isEmpty()) {
            // [D · provider-hdr 2026-09-24] 零 header 出口的可判读性：条件严格限于
            //   「**非 null 但空** Map」（= 2 参便捷构造指纹，见 WARNED_EMPTY_MAP_CONFIG 的判据说明）。
            //   刻意**不**在 null 分支打（= 未配置 header，绝大多数请求的正常态）；
            //   也刻意**不**在此处读 gate（gateEnabled 会落一次 DB 查询 —— ProviderHeaderInjectorTest
            //   的 extraHeadersNullOrEmpty_doesNotReadGateSource 把「空配置零 DB 查询」钉死了）。
            if (extraHeaders != null && extraHeaders.isEmpty()) {
                warnEmptyMapConfigAtMostOncePerProcess(sessionId);
            }
            return;   // 绝大多数请求走这里：零新增开销
        }
        Map<String, String> expanded =
            DynamicHeaderExpander.expandAll(extraHeaders, sessionId, gateEnabled());
        // 日志口径 = **真实 putHeader 次数**（不是 expanded.size()）：expanded 里还含下一步才被跳过的
        // 禁止头（以及 expandAll 已丢弃的 null 条目），而这是注入侧唯一一条日志 —— 文案说「注入」就必须
        // 是注入数，否则排障时会看到「说注入 1 条、实际 0 条」。
        int injected = 0;
        for (Map.Entry<String, String> e : expanded.entrySet()) {
            if (DynamicHeaderExpander.isForbiddenHeaderName(e.getKey())) {
                log.warn("[ProviderHeaderInjector] 跳过被禁止的自定义 header（疑似存量脏数据）: {}",
                    e.getKey());
                continue;
            }
            putHeader.accept(e.getKey(), e.getValue());
            injected++;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ProviderHeaderInjector] 注入自定义 header count={}（展开后 {} 条，其中 {} 条被禁止头过滤跳过）sessionId={}",
                injected, expanded.size(), expanded.size() - injected, sessionId);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // [D 可观测性] 注入条数（供 provider 层探针行复用）+ 零 header 成因告警
    // ════════════════════════════════════════════════════════════════

    /**
     * 单条原始 header 是否会被真的注入（判据：名/值非 null + 名不在禁止清单）。
     *
     * <p><b>与 {@link #apply} 的判据共享哪一半（照实说，不夸大）</b>：
     * <ul>
     *   <li>「名在禁止清单」这一半是<b>字面共用</b>：{@code apply} 走
     *       {@link DynamicHeaderExpander#isForbiddenHeaderName}，本谓词走同一静态方法；</li>
     *   <li>「名/值非 null」这一半是<b>语义等价而非同一行代码</b>：{@code apply} 的丢弃发生在
     *       {@link DynamicHeaderExpander#expandAll} 内（跳过 null 名/null 值脏条目），本谓词在计数侧
     *       复述同一规则。⇒ 二者的等价性由 {@code ProviderHeaderObservabilityTest} 的逐档矩阵
     *       <b>实测钉住</b>（而不是靠注释断言）。</li>
     * </ul>
     */
    public static boolean isInjectableHeader(String name, String value) {
        return name != null && value != null && !DynamicHeaderExpander.isForbiddenHeaderName(name);
    }

    /**
     * 本次注入<b>会发生</b>的 header 条数 —— 供 provider 层探针行
     * （{@code OpenAiSdkProvider} 的 {@code [前缀缓存探针] 出站 ... hdrs=N}）复用同一口径。
     *
     * <p><b>为什么必须要这个数（D 的动机）</b>：本次排查 90% 的成本花在「没有任何日志能证明 header
     * 是否注入」上 —— {@link #apply} 的计数日志是 DEBUG（运行级别 INFO ⇒ 实测 backend.log 0 行），
     * 而探针行只打 sessionId、从不打 header 信息。有了本数，探针行上 {@code hdrs=0} 即一眼指认
     * 「这次请求一条自定义 header 都没出去」（opencode 这类强制带头的 provider 必 400）。
     *
     * <p><b>与 {@link #apply} 实际 putHeader 次数同源的证明</b>（不是「近似」）：
     * {@code apply} 先过 {@link DynamicHeaderExpander#expandAll}（<b>只改值</b>：占位符 → 真值/兜底常量，
     * 唯一丢弃是 null 名/null 值的脏条目）再按 {@code isForbiddenHeaderName} 跳过 ⇒ 其 putHeader 次数
     * == |{e ∈ extraHeaders : e.key ≠ null ∧ e.value ≠ null ∧ ¬forbidden(e.key)}| == 本方法返回值。
     * 该等价性由 {@code ProviderHeaderObservabilityTest.injectableCount_matchesApplyPutHeaderCount}
     * 逐档钉住（含禁止头 / null 值 / 占位符混入的矩阵）。
     *
     * <p>计数与 {@code sessionId} / gate 开关<b>无关</b>：{@code expand} 对任何输入都返回一个值
     * （真值或 {@link DynamicHeaderExpander#STATIC_FALLBACK}），永不返回 null ⇒ 不改变条目数。
     * 故本方法不需要这两个入参（也就不必在读 gate 时多落一次 DB）。
     *
     * @param extraHeaders provider 配置的原始 header（可 null）；null / 空 → 0
     */
    public static int injectableCount(Map<String, String> extraHeaders) {
        if (extraHeaders == null || extraHeaders.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            if (isInjectableHeader(e.getKey(), e.getValue())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 「本次配置的 extraHeaders 是空 Map」在<b>进程内</b>的频控告警（首次必打 + 每
     * {@link #EMPTY_MAP_CONFIG_WARN_EVERY} 次复述一次；其余静默 —— 日志量控制见该常量的 javadoc）。
     *
     * <p>判据与动机见 {@link #WARNED_EMPTY_MAP_CONFIG}：空 Map = 「可用配置却没带 header」的缺陷指纹
     * （2 参便捷构造落 {@code Map.of()}）。消息里给出<b>下一步动作</b>（而非只报现象）：
     * 到 {@code ProviderConfig} 的构造点找 2 参调用，或看护栏测试
     * {@code ProviderConfigConstructionGuardTest} 是否已红。
     */
    private static void warnEmptyMapConfigAtMostOncePerProcess(String sessionId) {
        long hits = EMPTY_MAP_CONFIG_HITS.incrementAndGet();
        boolean first = WARNED_EMPTY_MAP_CONFIG.compareAndSet(false, true);
        boolean repeat = hits % EMPTY_MAP_CONFIG_WARN_EVERY == 0;
        if (!first && !repeat) {
            return;
        }
        // ⚠️ 文案里**不得出现 `${}` 字面量**：SLF4J 会把 `{}` 当占位符 ⇒ 吞掉后面的实参并把
        //   sessionId/hits 依次左移（2026-09-24 实测：`配了 ${} 占位符` 令日志打成 `sessionId=1`，
        //   排障时读到的是错位的值）。故此处改用中文描述「运行时占位符」，占位符全部收尾集中。
        log.warn("[ProviderHeaderInjector] 本次请求零自定义 header，且成因是 config.extraHeaders 为**空 Map**"
            + "（非 null）：这是「用 ProviderConfig 2 参便捷构造器」的指纹 —— 该构造落 Map.of() ⇒ 本类"
            + "空值早返回 ⇒ 一条 header 都不发（配了运行时占位符的 provider 会因此恒 400）。"
            + "排查：检查 ProviderConfig 的生产构造点是否有 2 参调用（应走 3 参带 extraHeaders，"
            + "先例 ModelConfigResolver.java:117 / ChatService.java:2219）；护栏测试 "
            + "ProviderConfigConstructionGuardTest 会在新加 2 参生产调用点时转红。"
            + " 另注：deserializeHeaders 解析失败也落空 Map（那条路自带 warn + 原始片段）。"
            + " sessionId={} 本进程累计命中={}（本条为首次，或每 {} 次命中复述一次）",
            sessionId, hits, EMPTY_MAP_CONFIG_WARN_EVERY);
    }

    /**
     * <b>仅测试用</b>：复位「空 Map 配置」频控状态（首次标志 + 计数）。
     *
     * <p>为什么需要：该状态是 <b>static</b>，而 {@code ProviderHeaderInjectorTest} 里既有的用例
     * （{@code extraHeadersNullOrEmpty_neverCallsPutHeader} 等）本身就会调
     * {@code apply(..., Map.of(), ...)} ⇒ 会把「首次」标志吃掉，使断言「缺陷形态下有 WARN」的用例
     * 因<b>用例执行顺序</b>而假红。与本类 {@code installGateSource(null)}（= 卸载静态读源，仅测试用）
     * 同一先例；包级可见，不进生产 API。
     */
    static void resetEmptyMapConfigWarnForTest() {
        WARNED_EMPTY_MAP_CONFIG.set(false);
        EMPTY_MAP_CONFIG_HITS.set(0);
    }
}
