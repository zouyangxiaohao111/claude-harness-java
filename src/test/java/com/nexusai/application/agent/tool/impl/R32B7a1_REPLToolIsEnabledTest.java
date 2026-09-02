package com.nexusai.application.agent.tool.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R32-b7a-1 · REPLTool.isEnabled() 守卫验证.
 *
 * <p><b>WHY (意图验证)</b>: REPL 模式依赖环境变量 / 系统属性开启 — 默认关闭避免 LLM
 * 看到未启用工具 (CC {@code REPLTool/constants.ts:23 isReplModeEnabled()} 行为).
 * 测试验证:
 * <ul>
 *   <li>默认状态 → isEnabled()=false (不污染 LLM 工具列表)</li>
 *   <li>系统属性 truthy → isEnabled()=true (opt-in 生效)</li>
 *   <li>系统属性 falsy → isEnabled()=false (显式关闭)</li>
 *   <li>truthy 字符串容错 (1/yes/on, 大小写不敏感)</li>
 *   <li>空字符串与空白视为禁用 (防止 .env 文件误启)</li>
 * </ul>
 *
 * <p><b>环境变量测试限制 (JDK 25+)</b>: JDK 25 启用了强模块封装,
 * 反射修改 {@code ProcessEnvironment.theCaseInsensitiveEnvironment}
 * 需要 {@code --add-opens=java.base/java.lang=ALL-UNNAMED}, 否则抛
 * {@code InaccessibleObjectException}. 本测试通过 {@link Assumptions}
 * 在 JDK 25+ 跳过环境变量测试 (系统属性测试已覆盖核心逻辑).
 *
 * @see REPLTool#isEnabled()
 * @see REPLTool#isReplModeEnabled()
 */
class R32B7a1_REPLToolIsEnabledTest {

    private static final String ENV_KEY = "NEXUSAI_REPL_MODE";
    private static final String PROP_KEY = "nexusai.feature.repl-mode";

    private String originalEnv;
    private String originalProp;

    @BeforeEach
    void saveOriginals() {
        originalEnv = readEnv(ENV_KEY);
        originalProp = System.getProperty(PROP_KEY);
        clearEnv(ENV_KEY);
        System.clearProperty(PROP_KEY);
    }

    @AfterEach
    void restoreOriginals() {
        clearEnv(ENV_KEY);
        if (originalEnv != null) {
            setEnv(ENV_KEY, originalEnv);
        } else {
            unsetEnv(ENV_KEY);
        }
        if (originalProp != null) {
            System.setProperty(PROP_KEY, originalProp);
        } else {
            System.clearProperty(PROP_KEY);
        }
    }

    // ─────────── 默认行为 ───────────

    @Test
    @DisplayName("默认无配置 → isEnabled()=false (不污染 LLM 工具列表)")
    void defaultDisabled() {
        assertFalse(REPLTool.isReplModeEnabled(),
            "WHY: REPL 模式默认关闭; nexusai-backend 不是 ant CLI, 与 CC 默认 ant+cli 才启用一致");
        assertFalse(new REPLTool().isEnabled(),
            "isEnabled() 应委托给 isReplModeEnabled()");
    }

    // ─────────── 系统属性路径 (核心测试) ───────────

    @Test
    @DisplayName("sysprop=true → isEnabled()=true (opt-in 生效)")
    void syspropTrueEnables() {
        System.setProperty(PROP_KEY, "true");
        assertTrue(REPLTool.isReplModeEnabled(),
            "WHY: 系统属性 truthy 应启用 REPL 模式 (便于 IDE 调试 -Dnexusai.feature.repl-mode=true)");
        assertTrue(new REPLTool().isEnabled());
    }

    @Test
    @DisplayName("sysprop=false → isEnabled()=false (显式关闭)")
    void syspropFalseDisables() {
        System.setProperty(PROP_KEY, "false");
        assertFalse(REPLTool.isReplModeEnabled(),
            "WHY: sysprop=false 应明确禁用, 防止意外开启");
        assertFalse(new REPLTool().isEnabled());
    }

    @Test
    @DisplayName("sysprop=1 / yes / on (大小写不敏感) → isEnabled()=true")
    void syspropTruthyVariants() {
        for (String truthy : new String[]{"1", "yes", "on", "TRUE", "Yes", "ON"}) {
            System.setProperty(PROP_KEY, truthy);
            assertTrue(REPLTool.isReplModeEnabled(),
                "WHY: truthy 字符串容错, 对齐 shell convention (case-insensitive)");
        }
    }

    @Test
    @DisplayName("sysprop=空字符串 → isEnabled()=false (空值不启用)")
    void syspropBlankDisables() {
        System.setProperty(PROP_KEY, "");
        assertFalse(REPLTool.isReplModeEnabled(),
            "WHY: 空字符串不算 truthy, 必须明确 opt-in (防止 .env 文件中 nexusai.feature.repl-mode= 误启)");
    }

    @Test
    @DisplayName("sysprop=任意垃圾字符串 → isEnabled()=false")
    void syspropGarbageDisables() {
        for (String garbage : new String[]{"enabled", "foo", "yes-please", "0"}) {
            System.setProperty(PROP_KEY, garbage);
            assertFalse(REPLTool.isReplModeEnabled(),
                "WHY: 仅 true/1/yes/on 视为 truthy; 'enabled'/'0' 等其他值视为禁用 "
                    + "(避免 'enabled' 误启, 防止 '0' 字符串歧义)");
        }
    }

    // ─────────── 环境变量路径 (JDK 25+ 受限) ───────────

    @Test
    @DisplayName("env=true → isEnabled()=true (JDK 17/21 路径; JDK 25+ 自动跳过)")
    void envTrueEnables() {
        Assumptions.assumeTrue(canModifyEnv(),
            "JDK 25+ 强模块封装下无法反射修改 env var, 需要 --add-opens=java.base/java.lang=ALL-UNNAMED");
        setEnv(ENV_KEY, "true");
        assertTrue(REPLTool.isReplModeEnabled(),
            "WHY: env NEXUSAI_REPL_MODE=true 应启用 REPL 模式 (对齐 CC CLAUDE_CODE_REPL 行为)");
        assertTrue(new REPLTool().isEnabled());
    }

    @Test
    @DisplayName("env=false → isEnabled()=false (JDK 17/21 路径)")
    void envFalseDisables() {
        Assumptions.assumeTrue(canModifyEnv(),
            "JDK 25+ 强模块封装下无法反射修改 env var");
        setEnv(ENV_KEY, "false");
        assertFalse(REPLTool.isReplModeEnabled(),
            "WHY: env=false 应明确禁用 (不能默认 enabled=true, 否则 deploy 时会意外开启)");
        assertFalse(new REPLTool().isEnabled());
    }

    // ─────────── Env 反射工具 (受 JDK 模块封装限制) ───────────

    /** 检查当前 JDK 是否允许反射修改 env var (JDK 17/21 可, JDK 25+ 受限). */
    private static boolean canModifyEnv() {
        try {
            java.lang.reflect.Field f = Class.forName("java.lang.ProcessEnvironment")
                    .getDeclaredField("theCaseInsensitiveEnvironment");
            f.setAccessible(true);
            return true;
        } catch (java.lang.reflect.InaccessibleObjectException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取当前进程环境变量 (System.getenv 不可直接读, 需反射). */
    private static String readEnv(String key) {
        try {
            java.lang.reflect.Field f = Class.forName("java.lang.ProcessEnvironment")
                    .getDeclaredField("theCaseInsensitiveEnvironment");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> env = (java.util.Map<String, String>) f.get(null);
            return env.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** 设置环境变量 (需 JDK 模块允许反射, 否则抛 RuntimeException). */
    private static void setEnv(String key, String value) {
        try {
            java.lang.reflect.Field f = Class.forName("java.lang.ProcessEnvironment")
                    .getDeclaredField("theCaseInsensitiveEnvironment");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> orig = (java.util.Map<String, String>) f.get(null);
            // JDK 21+ 中 theCaseInsensitiveEnvironment 是 Collections.unmodifiableMap(...),
            // 直接 put 会抛 UnsupportedOperationException. 替换为新 HashMap 绕过.
            java.util.Map<String, String> modifiable = new java.util.HashMap<>(orig);
            if (value == null) {
                modifiable.remove(key);
            } else {
                modifiable.put(key, value);
            }
            f.set(null, modifiable);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set env var: " + key, e);
        }
    }

    private static void clearEnv(String key) {
        try {
            setEnv(key, "");
        } catch (RuntimeException e) {
            // 静默吞掉, 因为 @BeforeEach 不应中断后续测试
            // canModifyEnv() 已用于 assumeTrue 跳过 env 测试
        }
    }

    private static void unsetEnv(String key) {
        try {
            setEnv(key, null);
        } catch (RuntimeException e) {
            // 静默吞掉
        }
    }
}