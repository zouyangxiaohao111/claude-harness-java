package com.nexusai.application.agent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IMP-5 · BashTool 沙箱 auto-allow 三闸测试。
 *
 * <p>对齐 CC bashPermissions.ts:1832-1838：
 * <pre>
 *   if (SandboxManager.isSandboxingEnabled() &amp;&amp;
 *       SandboxManager.isAutoAllowBashIfSandboxedEnabled() &amp;&amp;
 *       shouldUseSandbox(input)) { … auto-allow … }
 * </pre>
 * Java {@link BashTool#checkPermissions} 步 3 的沙箱 auto-allow 门必须显式叠加三闸
 * {@code isEnabled()}（isSupportedPlatform + checkDependencies + isPlatformInEnabledList +
 * 设置）——平台不支持 / 依赖缺失 / 白名单排除 / 用户未开启时，命令不得被当作
 * "沙箱内执行"而自动放行（fail-closed 对齐 CC sandbox-adapter.ts:532-547）。
 */
@DisplayName("BashTool 沙箱 auto-allow 三闸（IMP-5）")
class BashToolSandboxTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /** 沙箱 auto-allow 放行的归因原因（BashTool.java:1469 唯一沙箱 allow reason）。 */
    private static final String SANDBOXED_ALLOW_REASON = "Sandboxed bash command auto-allowed";

    // ──────────────────────────────────────────────
    // 构造辅助（复用 BashToolCheckPermissionsTest 同型 helper）
    // ──────────────────────────────────────────────

    private static ObjectNode bashInput(String command) {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        return input;
    }

    private static ToolUseContext toolCtx() {
        return ToolUseContext.of(AGENT, SESSION, PermissionMode.DEFAULT,
            List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()),
            PermissionMode.DEFAULT);
    }

    private static void injectSandboxManager(BashTool tool, SandboxManager sandboxManager) {
        try {
            Field f = BashTool.class.getDeclaredField("sandboxManager");
            f.setAccessible(true);
            f.set(tool, sandboxManager);
        } catch (Exception e) {
            throw new IllegalStateException("反射注入 BashTool.sandboxManager 失败", e);
        }
    }

    private static PermissionResult check(BashTool tool, String command) {
        return tool.checkPermissions(bashInput(command), toolCtx());
    }

    private static void assertNotSandboxAutoAllow(PermissionResult result) {
        if (result instanceof PermissionResult.Allow allow) {
            if (allow.reason() instanceof PermissionDecisionReason.Other other) {
                assertThat(other.reason())
                    .as("沙箱 auto-allow 必须被跳过：不得出现 Sandboxed 自动放行")
                    .isNotEqualTo(SANDBOXED_ALLOW_REASON);
            }
        }
    }

    private static void assertSandboxAutoAllow(PermissionResult result) {
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOfSatisfying(PermissionDecisionReason.Other.class,
                other -> assertThat(other.reason()).isEqualTo(SANDBOXED_ALLOW_REASON));
    }

    // ──────────────────────────────────────────────
    // 三闸组合：全部通过 → auto-allow；任一闸失败 → 跳过
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("四门全过 → 无规则命令沙箱 auto-allow 放行（CC bashPermissions.ts:1832-1838）")
    void autoAllow_allGatesPass_allows() {
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true, true, List.of(),
            () -> true, () -> true));

        assertSandboxAutoAllow(check(tool, "ls /tmp"));
    }

    @Test
    @DisplayName("平台不支持（isSupportedPlatform=false）→ auto-allow 跳过（fail-closed）")
    void autoAllow_platformUnsupported_skipped() {
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true, true, List.of(),
            () -> false, () -> true));

        assertNotSandboxAutoAllow(check(tool, "ls /tmp"));
    }

    @Test
    @DisplayName("依赖缺失（checkDependencies=false）→ auto-allow 跳过（fail-closed）")
    void autoAllow_dependenciesMissing_skipped() {
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true, true, List.of(),
            () -> true, () -> false));

        assertNotSandboxAutoAllow(check(tool, "ls /tmp"));
    }

    @Test
    @DisplayName("平台不在 enabledPlatforms 白名单 → auto-allow 跳过（NVIDIA 仅 macos 场景）")
    void autoAllow_platformNotInWhitelist_skipped() {
        BashTool tool = new BashTool();
        String cur = SandboxManager.currentPlatform();
        List<String> whitelist = List.of("linux", "macos", "windows", "wsl", "unknown")
            .stream().filter(p -> !p.equals(cur)).toList();
        assertThat(whitelist).isNotEmpty();
        injectSandboxManager(tool, new SandboxManager(true, true, true, whitelist,
            () -> true, () -> true));

        assertNotSandboxAutoAllow(check(tool, "ls /tmp"));
    }

    @Test
    @DisplayName("用户设置关闭（getSandboxEnabledSetting=false）→ auto-allow 跳过")
    void autoAllow_settingDisabled_skipped() {
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(false, true, true, List.of(),
            () -> true, () -> true));

        assertNotSandboxAutoAllow(check(tool, "ls /tmp"));
    }

    @Test
    @DisplayName("auto-allow 配置关闭（isAutoAllowBashIfSandboxed=false）→ 跳过（门 2）")
    void autoAllow_autoAllowConfigOff_skipped() {
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, false, true, List.of(),
            () -> true, () -> true));

        assertNotSandboxAutoAllow(check(tool, "ls /tmp"));
    }

    @Test
    @DisplayName("per-input dangerouslyDisableSandbox → shouldUseSandbox=false → 跳过")
    void autoAllow_perInputDisableSandbox_skipped() {
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true, true, List.of(),
            () -> true, () -> true));
        ObjectNode input = bashInput("ls /tmp");
        input.put("dangerouslyDisableSandbox", true);

        PermissionResult result = tool.checkPermissions(input, toolCtx());

        assertNotSandboxAutoAllow(result);
    }
}
