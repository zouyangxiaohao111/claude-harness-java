package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.powershell.PowerShellAstService;
import com.nexusai.application.agent.tool.powershell.PowerShellPermissionChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R32-b7a-1 · PowerShellTool.isEnabled() 可见性门控验证（OPD-TOOL-35 门控统一）。
 *
 * <p><b>WHY (意图验证)</b>: CC {@code shellToolUtils.ts:17-22 isPowerShellToolEnabled()} 为
 * 平台 + USER_TYPE + env 三因子门控；Java 端折叠为 {@code isEnabled() = isWindows() &&
 * featureFlags.usePowerShellTool()}（ToolRegistry 分发前过滤，等价 CC tools.ts:325
 * {@code getTools filter(t => t.isEnabled())}）。旧版 {@code isEnabled() = isWindows()}
 * 是纯平台判断，丢 USER_TYPE + env 两因子，导致外部用户在 Windows 上仍能看到 PowerShell
 * 工具（CC 语义：外部默认关）。本测试锁定新门控意图：
 * <ul>
 *   <li>平台短路：非 Windows 恒 false，不看 usePowerShellTool（不浪费 LLM 一次必失败调用）</li>
 *   <li>flag 合取：Windows 且 usePowerShellTool()=true → true；Windows 且 false → false</li>
 *   <li>os.name 运行时模拟：JVM 允许 {@code System.setProperty("os.name", ...)}，Linux/Mac CI 可模拟 Windows</li>
 * </ul>
 *
 * <p>usePowerShellTool 三元（ant 默认开/opt-out，外部默认关/opt-in）在 FeatureFlagsConfig
 * 侧承载；本测试只验证 PowerShellTool 侧的「平台 × flag」合取合成，不重复三元逻辑。
 *
 * @see PowerShellTool#isEnabled()
 */
class R32B7a1_PowerShellToolIsEnabledTest {

    private String originalOsName;

    /** usePowerShellTool=true（其余全关）· 模拟 Windows 上 PowerShell 门控开启。 */
    private static FeatureFlags psEnabled() {
        return new FeatureFlags(false, false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, true, false, false, false);
    }

    /** A5：PowerShellTool 新增 checkPermissions 依赖权限链 + 门控依赖 FeatureFlags。 */
    private static PowerShellTool newPowerShellTool(FeatureFlags flags) {
        return new PowerShellTool(new PowerShellPermissionChain(new PowerShellAstService()), flags);
    }

    @BeforeEach
    void saveOsName() {
        originalOsName = System.getProperty("os.name");
    }

    @AfterEach
    void restoreOsName() {
        if (originalOsName != null) {
            System.setProperty("os.name", originalOsName);
        }
    }

    @Test
    @DisplayName("Windows (os.name 含 'win') + usePowerShellTool=true → isEnabled()=true")
    void enabledOnWindowsWhenFlagOn() {
        System.setProperty("os.name", "Windows 11");
        PowerShellTool tool = newPowerShellTool(psEnabled());
        assertTrue(tool.isEnabled(),
            "WHY: Windows + usePowerShellTool=true → isWindows()&&flag 双因子都真 → 对 LLM 可见");
    }

    @Test
    @DisplayName("Windows + usePowerShellTool=false（外部默认关）→ isEnabled()=false")
    void disabledOnWindowsWhenFlagOff() {
        System.setProperty("os.name", "Windows 11");
        PowerShellTool tool = newPowerShellTool(FeatureFlags.ALL_DISABLED);
        assertFalse(tool.isEnabled(),
            "WHY: Windows 但 USER_TYPE!=ant 且未设 CLAUDE_CODE_USE_POWERSHELL_TOOL → 外部默认关（CC opt-in），"
                + "isWindows()=true && usePowerShellTool()=false → false");
    }

    @Test
    @DisplayName("Linux + usePowerShellTool=true → isEnabled()=false（平台短路，不看 flag）")
    void disabledOnLinux() {
        System.setProperty("os.name", "Linux");
        PowerShellTool tool = newPowerShellTool(psEnabled());
        assertFalse(tool.isEnabled(),
            "WHY: 非 Windows → isWindows()=false 先短路，flag 不再参与 → 恒 false，"
                + "LLM 调一次必失败，提前隐藏避免浪费 token");
    }

    @Test
    @DisplayName("Mac 平台 → isEnabled()=false")
    void disabledOnMac() {
        System.setProperty("os.name", "Mac OS X");
        PowerShellTool tool = newPowerShellTool(psEnabled());
        assertFalse(tool.isEnabled(),
            "WHY: macOS 没有 powershell.exe → 必须隐藏");
    }

    @Test
    @DisplayName("os.name 大小写: 'WIN32' / 'Win*' 都视为 Windows (含 'win' 子串)")
    void osNameCaseInsensitive() {
        // WHY: CC isPowerShellToolEnabled 用 process.platform==='win32' === 严格比较;
        // Java 端因历史兼容 (Windows XP/Win7) 使用 contains('win') 而非 equals;
        // 此测试锁定该 L3 简化行为, 防止未来"修正"为 equals 时静默破坏 Linux 兼容性
        for (String osName : new String[]{"WIN32", "Win7", "win98"}) {
            System.setProperty("os.name", osName);
            assertTrue(newPowerShellTool(psEnabled()).isEnabled(),
                "os.name='" + osName + "' (含 'win' 子串) 应视为 Windows");
        }
    }

    @Test
    @DisplayName("isEnabled() = isWindows() && usePowerShellTool()（两因子合取，无独立开关）")
    void isEnabledConjoinsWindowsAndFlag() {
        // WHY: 验证 isEnabled() 没有独立状态，完全由平台 AND flag 派生；
        // 防止未来重构时引入独立开关导致 isEnabled() 与 execute() 行为不一致。
        FeatureFlags on = psEnabled();
        System.setProperty("os.name", "Linux");
        assertEquals(false, newPowerShellTool(on).isEnabled(), "非 Windows 恒 false");
        System.setProperty("os.name", "Windows Server 2022");
        assertEquals(true, newPowerShellTool(on).isEnabled(), "Windows + flag=true → true");
        assertEquals(false, newPowerShellTool(FeatureFlags.ALL_DISABLED).isEnabled(),
            "Windows + flag=false → false");
    }

    @Test
    @DisplayName("name() 与 isEnabled() 在默认 Windows 下可同时调用 (无 NPE)")
    void nameAndIsEnabledCoexist() {
        // WHY: ToolRegistry 在分发前调用 name() + isEnabled() 两遍, 验证两者不冲突
        System.setProperty("os.name", "Windows 11");
        PowerShellTool tool = newPowerShellTool(psEnabled());
        assertEquals("PowerShell", tool.name());
        assertTrue(tool.isEnabled());
    }
}
