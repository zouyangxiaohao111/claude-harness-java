package com.nexusai.infra.llm;

import com.nexusai.application.agent.compact.CompactSettingsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流空闲看门狗配置解析（对齐 CC 2.1.296 · 方案规格 §2.1）。
 *
 * <p>CC 证据：开关 {@code KT=a.CLAUDE_ENABLE_STREAM_WATCHDOG??!0}（exe 223,134,892，未设 = 开）；
 * 阈值 {@code WJt(){return Math.max(am()??0, 300000)}}（exe 216,198,281），{@code am()} 读
 * env {@code CLAUDE_STREAM_IDLE_TIMEOUT_MS}（>0 生效）否则远端配置——本仓对应层 = DB 设置
 * （V78 列，前端「设置→通用」页，用户 10-10 追加要求）。<b>300000 是下限</b>：env/设置设更低
 * 也会被抬到 300s（对齐 CC 原样），并打 WARN 留痕（规则十二 · 显式失败）。
 *
 * <p>解析优先级：env &gt; DB 设置 &gt; 默认 300000。
 */
public final class StreamIdleWatchdogSettings {

    private static final Logger log = LoggerFactory.getLogger(StreamIdleWatchdogSettings.class);

    /** CC {@code WJt} 的下限/默认值（300s）。 */
    public static final long DEFAULT_IDLE_MS = 300_000L;

    // [测试钩子] 单测覆盖用（绕过 300s 下限，让"空闲触发"可在毫秒级注入）；生产恒 null。
    // 用法：@BeforeEach setTestOverrides(...)；@AfterEach resetTestOverrides()（照 LlmAgentLoop
    // setSettingsResolver 的测试钩子注释风格）。
    private static volatile Boolean testEnabledOverride = null;
    private static volatile Long testIdleMsOverride = null;

    private StreamIdleWatchdogSettings() {
    }

    /** 开关：未设/空/不可辨识 = 开；仅显式假值（0/false/no/off，大小写不敏感）才关。 */
    public static boolean enabled() {
        if (testEnabledOverride != null) {
            return testEnabledOverride;
        }
        return parseEnabledSwitch(System.getenv("CLAUDE_ENABLE_STREAM_WATCHDOG"));
    }

    /**
     * 阈值毫秒：{@code max(env>0 ? env : settings>0 ? settings : 0, 300000)}。
     *
     * @param resolver DB 设置实时读源（可为 null = 无设置层，如子代理路径/测试）
     */
    public static long idleMs(CompactSettingsResolver resolver) {
        if (testIdleMsOverride != null) {
            return testIdleMsOverride;
        }
        Integer settingsValue = resolver != null ? resolver.streamIdleTimeoutMs() : null;
        return resolveIdleMs(System.getenv("CLAUDE_STREAM_IDLE_TIMEOUT_MS"), settingsValue);
    }

    // ══════════════════════════ 可测静态核（不依赖进程 env） ══════════════════════════

    /** 开关解析核（对齐 CC triBool 默认开：未设 = 开；仅显式假值关；其余按开）。 */
    static boolean parseEnabledSwitch(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("0") || normalized.equals("false")
            || normalized.equals("no") || normalized.equals("off")) {
            return false;
        }
        // 1/true/yes/on 与不可辨识值均按开（对齐 CC triBool 解析失败 → ?? !0 → 开）
        return true;
    }

    /** 阈值解析核：env(>0) 优先，其次设置值，再默认；统一抬到 300000 下限。 */
    static long resolveIdleMs(String envRaw, Integer settingsValue) {
        long env = parsePositiveLong(envRaw);
        long value = env > 0 ? env : (settingsValue != null ? settingsValue : 0L);
        if (value > 0 && value < DEFAULT_IDLE_MS) {
            log.warn("[StreamIdleWatchdog] 配置的空闲阈值 {}ms 低于下限 {}ms，按下限生效"
                + "（对齐 CC Math.max(am()??0,300000)）", value, DEFAULT_IDLE_MS);
        }
        return Math.max(value, DEFAULT_IDLE_MS);
    }

    /** 解析正整数毫秒；null/空/非法 → -1（非正值在消费点按未配置处理）。 */
    static long parsePositiveLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // ══════════════════════════ 测试钩子 ══════════════════════════

    /** [测试钩子] 仅单测调用；@AfterEach 必须 resetTestOverrides()。 */
    public static void setTestOverrides(Boolean enabled, Long idleMs) {
        testEnabledOverride = enabled;
        testIdleMsOverride = idleMs;
    }

    public static void resetTestOverrides() {
        testEnabledOverride = null;
        testIdleMsOverride = null;
    }
}
