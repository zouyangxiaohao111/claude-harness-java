package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** [Wave2-I] HTTP onFailure 升级矩阵（spawn catch 与 mcp_tool 由集成路径覆盖）。 */
class HookRegistryOnFailureTest {

    private static HttpHook http(String onFailure) {
        return new HttpHook("http://hook.local/x", null, null, null, null, null, null, onFailure);
    }

    private static ExecHttpHook.HttpHookResult error() {
        return new ExecHttpHook.HttpHookResult(false, 500, "", "boom", false);
    }

    @Test
    void httpOnFailureBlock_upgradesErrorToBlocking() {
        GenericHook.HookResult r = HookRegistry.httpToHookResult(
            error(), http("block"), "h", "PreToolUse", "tid-1");
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.BLOCKING);
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError()).contains("boom");
    }

    @Test
    void httpOnFailureContinue_staysNonBlocking() {
        GenericHook.HookResult r = HookRegistry.httpToHookResult(
            error(), http("continue"), "h", "PreToolUse", "tid-1");
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
        assertThat(r.blockingError()).isNull();
    }

    @Test
    void httpAborted_staysCancelled_evenWithBlock() {
        ExecHttpHook.HttpHookResult aborted =
            new ExecHttpHook.HttpHookResult(false, null, "", null, true);
        GenericHook.HookResult r = HookRegistry.httpToHookResult(
            aborted, http("block"), "h", "PreToolUse", "tid-1");
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.CANCELLED);
    }

    @Test
    void httpOnFailureBlock_isIgnoredOnStop() {
        GenericHook.HookResult r = HookRegistry.httpToHookResult(
            error(), http("block"), "h", "Stop", null);
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
    }

    @Test
    void httpValidationError_upgradesToBlockingWhenConfigured() {
        HttpHook hook = http("block");
        ExecHttpHook.HttpHookResult badBody =
            new ExecHttpHook.HttpHookResult(true, 200, "not-json", null, false);
        GenericHook.HookResult r = HookRegistry.httpToHookResult(
            badBody, hook, "h", "PreToolUse", "tid-1");
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.BLOCKING);
        assertThat(r.blockingError().blockingError()).contains("JSON validation failed");
    }
}
