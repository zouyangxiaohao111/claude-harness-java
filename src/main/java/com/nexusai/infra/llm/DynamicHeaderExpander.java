package com.nexusai.infra.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 提供商自定义请求 header 的占位符展开 + 安全校验 · 单点判据源。
 *
 * <p>设计见 docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md。
 *
 * <p><b>占位符是刻意封闭的集合</b>（对齐 qwen-code PR #11282 的 PLACEHOLDERS）：
 * 每新增一个 token 都要重新审一次隐私边界，本批只有 ${session_id} 一个。
 *
 * <p><b>关键不变量</b>：任何路径都绝不把字面量 {@code ${session_id}} 发到线上。
 */
public final class DynamicHeaderExpander {

    private static final Logger log = LoggerFactory.getLogger(DynamicHeaderExpander.class);

    /** 本批唯一占位符。 */
    public static final String SESSION_ID_TOKEN = "${session_id}";

    /**
     * 无会话上下文时的兜底常量。**不是会话状态**，发出去不泄露任何信息。
     *
     * <p>与参考实现 qwen-code 的有意分歧：qwen-code 在开关关 / 会话缺失时**丢弃** header；
     * 本仓改为**发常量**，因为 nexusai 有约 20 条辅助 LLM 调用拿不到 sessionId，
     * 丢弃会让它们对 opencode 静默 400（标题/摘要/视觉等功能无声失效）。
     * 详见规范 §11 R1。
     */
    public static final String STATIC_FALLBACK = "nexusai-static";

    /**
     * 禁止用户配置的 header 名（大小写不敏感）。
     *
     * <p>两类：
     * <ul>
     *   <li>凭据类 —— 由 SDK 依 apiKey 自动注入，或属会话凭据，不允许被 provider 级配置劫持（用户决策 D5）</li>
     *   <li>报文完整性类 —— 覆盖会直接破坏请求本身</li>
     * </ul>
     * 协议类头（anthropic-version / anthropic-beta / accept 等）**允许**：
     * 非凭据，且自建网关有正当的改版本/加 beta 需求。
     *
     * <p><b>为什么这份清单是唯一防线</b>（T2 桩实测，2026-09-12）：{@code putHeader} 能<b>顶掉</b>
     * SDK 依 {@code .apiKey()} 自动注入的凭据头，且与调用顺序无关（逆序实测同样顶掉）。
     * 因此一旦清单漏了某个凭据头名，<b>没有任何第二道防线</b>——顺序不能当安全网用。
     *
     * <p><b>{@code expect} 的由来</b>（2026-09-13 任务 8 实测补入）：JDK
     * {@code HttpRequest.Builder.header} 对<b>受限头</b>抛 {@code IllegalArgumentException}
     * （{@code restricted header name}），其受限集合为
     * {@code connection / content-length / expect / host / upgrade}——本清单原先覆盖了其中 4 个，
     * <b>漏了 {@code expect}</b>。<b>真实的危害路径只有一条</b>：{@code ProviderService.test}
     * （「测试连接」）直接用 {@code java.net.http} 发请求，而它的 {@code catch (Exception e)} 会把
     * 该异常吞成 {@code "Unknown error: ..."}——即一份含 {@code expect} 的存量脏数据会让「测试连接」
     * 报出<b>误导性的失败</b>，正是本批要消除的那类症状。补入后两侧同时受保护：写侧拒绝（400）、
     * 注入侧跳过（warn）。两个 SDK provider 走的是 {@code putHeader}，不受此 JDK 限制影响，
     * 故本条的收益方是「测试连接」那条路。
     */
    private static final Set<String> FORBIDDEN_HEADER_NAMES = Set.of(
        // 凭据类
        "authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie",
        // 报文完整性类（expect 见上方 javadoc：JDK 受限头，会在「测试连接」被吞成误导性失败）
        "content-length", "content-type", "host", "transfer-encoding", "connection", "te", "upgrade",
        "expect"
    );

    /** RFC 7230 token：!#$%&'*+-.^_`|~ 与数字字母。 */
    private static final Pattern VALID_HEADER_NAME = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");

    /** RFC 7230 field-value：可见 ASCII + 空格/水平制表，**不含 CR/LF**。 */
    private static final Pattern VALID_HEADER_VALUE = Pattern.compile("^[\\x20-\\x7E\\t]*$");

    /**
     * 「开关关闭但配了占位符」的 warn **至多一次**标志（静态 + 线程安全：Web 多会话并发）。
     *
     * <p>用 {@link AtomicBoolean} 而非 Set：本方法只在 {@code hasPlaceholder(value)} 为真时可达，
     * 原先按值算出的去重键<b>恒等于</b> {@link #SESSION_ID_TOKEN}（Set 里最多只装一个元素），
     * 即「用集合装一个布尔」。换成本类型后语义与实现一致：<b>整个进程只 warn 一次</b>。
     *
     * <p>该「至多一次」语义<b>与设计规范 §6.1 的「每个不同 header 名集合一次」表述不符</b>，
     * 已由任务书裁定<b>保留</b>（理由见 {@link #warnGateOffAtMostOncePerProcess}）。
     */
    private static final AtomicBoolean WARNED_GATE_OFF = new AtomicBoolean(false);

    private DynamicHeaderExpander() {
    }

    // ════════════════════════════════════════════════════════════════
    // 展开
    // ════════════════════════════════════════════════════════════════

    /**
     * value 是否请求了运行时占位符。
     *
     * <p><b>预定调用方 / 用途</b>：本批唯一调用点是类内的 {@link #expand}（判断是否需要走展开
     * 分支）。按设计规范 §6.1 保持 {@code public} —— 它是「该值是否请求了运行时占位符」的
     * <b>对外判据</b>，供后续写侧校验与前端镜像判定复用（本批未接线，但<b>不是死代码</b>：
     * {@code expand} 依赖它做提前返回）。
     *
     * <p>对 null 入参返回 {@code false}（不抛）。
     */
    public static boolean hasPlaceholder(String value) {
        return value != null && value.contains(SESSION_ID_TOKEN);
    }

    /**
     * 展开单个 header 值。
     *
     * @param value       用户配置的原始值
     * @param sessionId   本次请求解析出的会话 ID（可 null / 空白）
     * @param gateEnabled settings.allow_dynamic_header_values
     * @return 真正要发的值；**永远不含字面量占位符**
     */
    public static String expand(String value, String sessionId, boolean gateEnabled) {
        if (value == null) {
            return null;
        }
        if (!hasPlaceholder(value)) {
            return value;   // 既有静态配置零行为变化，且不进新代码路径
        }
        if (!gateEnabled) {
            warnGateOffAtMostOncePerProcess();
            if (log.isDebugEnabled()) {
                log.debug("[DynamicHeaderExpander] 开关关闭，占位符落兜底常量 · 原值={}", value);
            }
            return STATIC_FALLBACK;
        }
        if (sessionId == null || sessionId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[DynamicHeaderExpander] 会话上下文缺失，占位符落兜底常量 · 原值={}", value);
            }
            return STATIC_FALLBACK;
        }
        return value.replace(SESSION_ID_TOKEN, sessionId);
    }

    /**
     * 批量展开。**跳过 key/value 为 null 的条目**，且对每个被跳过的条目 {@code warn} 一次。
     *
     * <p>为什么要跳过：null 名 / null 值都不是合法 HTTP header，透传给 SDK 的
     * {@code putHeader} 无法发出。<b>但跳过绝不静默</b>——这是规范 §6.3 自定的原则：
     * 否则用户会看到「header 莫名消失」而无从排查。
     *
     * <p>null 条目的真实来源是<b>存量脏数据</b>：脏 JSON 反序列化正是产生 null key/value 的通道。
     */
    public static Map<String, String> expandAll(Map<String, String> headers, String sessionId, boolean gateEnabled) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                log.warn("[DynamicHeaderExpander] 跳过非法条目：{} —— 疑似存量脏数据（脏 JSON 反序列化产物），"
                    + "该 header 本次不会发出",
                    e.getKey() == null ? "有一个 header 名为 null 的条目" : "header [" + e.getKey() + "] 的值为 null");
                continue;
            }
            out.put(e.getKey(), expand(e.getValue(), sessionId, gateEnabled));
        }
        return out;
    }

    /**
     * 就「配了占位符但开关关闭」在**进程生命周期内告警一次**（防止刷屏）。
     *
     * <p>刻意不按 header 名去重：gate 默认是开的（用户决策 D6），gate-off 只可能是用户主动关闭，
     * 此时这条 warn 是提醒而非诊断；而按名去重需要 {@link #expand} 多收一个 name 参数，
     * 会破坏 expand 的纯函数性（无日志副作用）。
     *
     * <p><b>本语义与设计规范 §6.1 的「每个不同 header 名集合一次」表述不符</b>，如实登记：
     * 本方法只在 {@code hasPlaceholder(value)} 为真时可达，故键恒为 {@link #SESSION_ID_TOKEN}，
     * 实际是<b>整个进程只 warn 一次</b>（实测：两个不同占位符值各做一次 gate-off 展开后，
     * 去重结构仍只此一项）。已由任务书裁定<b>保留</b>（理由见上）。
     */
    private static void warnGateOffAtMostOncePerProcess() {
        if (WARNED_GATE_OFF.compareAndSet(false, true)) {
            log.warn("[DynamicHeaderExpander] header 值里含运行时占位符，但"
                + " settings.allow_dynamic_header_values 未开启 —— 本次将发送兜底常量 [{}]。"
                + "如需按会话展开，请在「设置 · 高级」里打开该开关。", STATIC_FALLBACK);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 校验（写侧 + 注入侧共用同一判据）
    // ════════════════════════════════════════════════════════════════

    /** header 名是否命中禁止清单（大小写不敏感）。 */
    public static boolean isForbiddenHeaderName(String name) {
        return name != null && FORBIDDEN_HEADER_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /** header 名是否符合 RFC 7230 token 且不在禁止清单。 */
    public static boolean isValidHeaderName(String name) {
        if (name == null || name.isBlank() || !VALID_HEADER_NAME.matcher(name).matches()) {
            return false;
        }
        return !isForbiddenHeaderName(name);
    }

    /** header 值是否合法（不含 CR/LF，仅可见 ASCII + 空格/制表）。 */
    public static boolean isValidHeaderValue(String value) {
        return value != null && VALID_HEADER_VALUE.matcher(value).matches();
    }

    /**
     * 值里出现了 {@code ${}} 但不是精确的 {@link #SESSION_ID_TOKEN}（拼写/大小写错误）→ true。
     *
     * <p><b>WHY</b>：占位符 token 大小写敏感（{@link String#contains}，非正则），写成
     * {@code ${SESSION_ID}} 会被<b>原样当字面量发到线上</b> —— 静默失效，且症状是
     * 「opencode 又 400 了」而毫无线索。写侧宁可 fail loud。
     *
     * <p>实现：先把所有精确 token 挖掉，若仍残留 {@code ${} 即为拼写错误。纯函数、无副作用。
     *
     * <p><b>代价（规范 §6.4 已登记）</b>：值里合法地含 {@code ${}（非占位符用途）会被误判。
     * HTTP header 值出现 {@code ${} 的场景极罕见，用 fail loud 换「不静默失效」值得。
     *
     * @param value 用户配置的原始 header 值（可 null）
     * @return true = 疑似占位符拼写错误；null / 无 {@code ${} / 精确占位符 → false
     */
    public static boolean hasMalformedPlaceholder(String value) {
        if (value == null || !value.contains("${")) {
            return false;
        }
        return value.replace(SESSION_ID_TOKEN, "").contains("${");
    }
}
