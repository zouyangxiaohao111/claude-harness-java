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
}
