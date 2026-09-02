package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.PermissionDeniedHookExecutor;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-02] hasHookForEvent 三源存在性检查 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks.ts:1582-1593 hasHookForEvent}.
 *
 * <p>WHY (规则九): CC 三源 = config（快照）+ registered（programmatic）+ session
 * （SessionHookStore），任一命中即 true。旧 Java 实现仅查 registered（hookEventFilters），
 * settings.json 配置的 PermissionDenied / StopFailure hook 永不触发（EV-001/EV-L03-016）。
 * 本测试验证三源语义闭环，防止退回单源。
 */
@DisplayName("[IMPL-02] hasHookForEvent 三源（config/registered/session）")
class HasHookForEventThreeSourceTest {

    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    /** 组装 registry：settings 配置指定事件的 command hook → 快照捕获 → registry. */
    private HookRegistry newRegistryWithConfig(HookEventType event, String command) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(event, commandHook(command), null, HookSource.USER_SETTINGS, null)));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        return registry;
    }

    @Test
    @DisplayName("config 源：settings 配置 PermissionDenied hook → hasHookForEvent true")
    void configSource_settingsConfiguredHook_returnsTrue() {
        // WHY: D2 根因——settings.json 配置的 hook 在旧实现永不触发（EV-001）；
        //      CC hooks.ts:1587-1588 快照源命中即 true。
        HookRegistry registry = newRegistryWithConfig(HookEventType.PERMISSION_DENIED, "echo config-hook");

        assertThat(registry.hasHookForEvent("PermissionDenied", null)).isTrue();
    }

    @Test
    @DisplayName("registered 源：programmatic 注册后 true（既有行为不回归）")
    void registeredSource_programmaticHook_returnsTrue() {
        // WHY: CC hooks.ts:1589-1590 registered 源；旧实现唯一覆盖的源，必须不回归。
        HookRegistry registry = new HookRegistry();
        registry.register("pd-hook", event -> GenericHook.HookResult.proceed(),
            HookEventType.PERMISSION_DENIED);

        assertThat(registry.hasHookForEvent("PermissionDenied", null)).isTrue();
    }

    @Test
    @DisplayName("session 源：session hook 注册后对应 sessionId 命中，其它 session 不命中")
    void sessionSource_sessionHookRegistered_returnsTrueForThatSession() {
        // WHY: CC hooks.ts:1591 sessionHooks.get(sessionId).hooks[hookEvent] 存在性；
        //      session 作用域隔离（其它 sessionId 不得误报）。
        HookRegistry registry = new HookRegistry();
        registry.addSessionHook("s1", HookEventType.PERMISSION_DENIED, "Bash",
            commandHook("echo session-hook"), null, null);

        assertThat(registry.hasHookForEvent("PermissionDenied", "s1")).isTrue();
        assertThat(registry.hasHookForEvent("PermissionDenied", "other-session")).isFalse();
    }

    @Test
    @DisplayName("三源任一命中即 true；全空 false（INV-3）")
    void anySourceHit_returnsTrue_noneFalse() {
        // WHY: INV-3 config+registered+session 任一命中即 true；全空必须 false
        //      （CC 注释 "err on the side of true"，但空配置不得误报执行链）。
        HookRegistry empty = new HookRegistry();
        assertThat(empty.hasHookForEvent("PermissionDenied", "s9")).isFalse();

        // config 命中（registered/session 均空）
        HookRegistry configOnly = newRegistryWithConfig(HookEventType.STOP_FAILURE, "echo stop-hook");
        assertThat(configOnly.hasHookForEvent("StopFailure", null)).isTrue();
    }

    @Test
    @DisplayName("验收1：settings 配置 PermissionDenied hook → executor 不早返（非空流）")
    void configSource_permissionDeniedExecutor_doesNotEarlyReturn() {
        // WHY: D2 验收标准 1——旧实现 hasHookForEvent 仅查 registered 源, settings 配置
        //      hook 时 PermissionDeniedHookExecutor:62 早返空流, 配置 hook 永不触发
        //      (EV-001)。三源后必须进入执行链（流非空）。
        HookRegistry registry = newRegistryWithConfig(HookEventType.PERMISSION_DENIED, "echo pd-hook");
        PermissionDeniedHookExecutor executor = new PermissionDeniedHookExecutor(registry);

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = new ToolUseContext(UUID.randomUUID(), sessionId,
            PermissionMode.DEFAULT,
            java.util.Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "", null, null, null, null);

        var stream = executor.executePermissionDeniedHooks(
            "Bash", "tool-1", new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("command", "ls"), "denied", ctx, "default", null);

        assertThat(stream).isNotNull();
        assertThat(stream.findFirst()).isPresent();
    }
}
