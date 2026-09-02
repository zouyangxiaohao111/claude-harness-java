package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.team.SwarmPermissionSync;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SwarmWorkerPermissionHandler 主链测试 · 对齐 CC swarmWorkerHandler.ts:40-156。
 *
 * <p>验证意图（规则九）：RV-07 去 @Deprecated 生产化后，swarm worker 权限请求必须<b>先注册
 * callback 再经 mailbox 发送</b>（防 race，CC :79-123），且守卫（swarms 未启用 / 非 worker）
 * 必须 fall through 到本地 UI。若实现退回 no-op stub（恒 null），本测试「mailbox 已发送 +
 * callback 已注册」断言必红。
 */
@DisplayName("SwarmWorkerPermissionHandler 生产化主链（对齐 CC swarmWorkerHandler.ts）")
class SwarmWorkerPermissionHandlerTest {

    private static final SwarmWorkerPermissionHandler.Params PARAMS =
        new SwarmWorkerPermissionHandler.Params("Bash", "tooluse-1", Map.of("command", "ls"),
            "run ls", null, null);

    @BeforeEach
    void setUp() {
        // SwarmPermissionSync.createPermissionRequest 需 team/agent 身份（CC getTeamName/getAgentId）
        System.setProperty("nexusai.agent.name", "worker-a");
        System.setProperty("nexusai.team.name", "my-team");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.agent.name");
        System.clearProperty("nexusai.team.name");
        System.clearProperty("nexusai.agent.color");
    }

    @Test
    @DisplayName("守卫：swarms 未启用 → 返回 null（fall through 到本地 UI，CC :43-45）")
    void guard_swarmsDisabled_returnsNull() {
        SwarmWorkerPermissionHandler handler = new SwarmWorkerPermissionHandler(
            () -> false, () -> true, () -> false, null, null, null, null, null);
        assertThat(handler.handle(PARAMS)).isNull();
    }

    @Test
    @DisplayName("守卫：非 swarm worker → 返回 null（CC :43-45）")
    void guard_notSwarmWorker_returnsNull() {
        SwarmWorkerPermissionHandler handler = new SwarmWorkerPermissionHandler(
            () -> true, () -> false, () -> false, null, null, null, null, null);
        assertThat(handler.handle(PARAMS)).isNull();
    }

    @Test
    @DisplayName("主链：先注册 callback 再 mailbox 发送，leader allow → future complete（CC :79-149）")
    void mainChain_registersCallbackBeforeSend_thenLeaderAllow() throws Exception {
        AtomicBoolean sendAfterRegister = new AtomicBoolean(false);
        AtomicBoolean registered = new AtomicBoolean(false);
        Map<String, Object>[] allowedInput = new Map[1];

        SwarmWorkerPermissionHandler handler = new SwarmWorkerPermissionHandler(
            () -> true, () -> true, () -> false, null,
            request -> {
                // mailbox 发送前 callback 必须已注册（防 race，CC :79-123）
                sendAfterRegister.set(registered.get());
                assertThat(request.toolName()).isEqualTo("Bash");
            },
            (requestId, toolUseId, onAllow, onReject) -> {
                registered.set(true);
                // 模拟 leader 回复 allow（CC onAllow → resolveOnce；[REV-FIX-6] onAllow 携带 AllowResult）
                onAllow.accept(new SwarmPermissionPoller.AllowResult(Map.of("command", "ls -la"), List.of()));
            },
            pending -> { },
            () -> { });

        CompletableFuture<SwarmWorkerPermissionHandler.PermissionDecision> future = handler.handle(PARAMS);
        assertThat(future).isNotNull();
        SwarmWorkerPermissionHandler.PermissionDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.behavior()).isEqualTo("allow");
        assertThat(sendAfterRegister.get()).as("mailbox 发送前 callback 必须已注册（防 race）").isTrue();
    }

    @Test
    @DisplayName("classifier 命中 → 直接返回 classifierResult（CC :52-57）")
    void classifierHit_returnsClassifierResult() {
        SwarmWorkerPermissionHandler.PermissionDecision classifierDecision =
            SwarmWorkerPermissionHandler.PermissionDecision.allow(Map.of("command", "ok"));
        SwarmWorkerPermissionHandler handler = new SwarmWorkerPermissionHandler(
            () -> true, () -> true, () -> true,
            (pending, input, toolUseId) -> classifierDecision,
            null, null, null, null);
        CompletableFuture<SwarmWorkerPermissionHandler.PermissionDecision> future = handler.handle(PARAMS);
        assertThat(future).isNotNull();
        assertThat(future.getNow(null)).isSameAs(classifierDecision);
    }

    @Test
    @DisplayName("OPD-WF7-02-01 swarm classifier runner: 投机命中 + feature 开 → allow（awaitClassifierAutoApproval 等价）")
    void classifierRunner_hit_returnsAllow() {
        // WHY: CC swarmWorkerHandler.ts:52-57 — feature 开时先试 classifier auto-approve 再转发 leader。
        //      旧生产 runner 恒 null stub（O18 删启发式）→ 激活 BASH_CLASSIFIER 即漂移（R2）。
        //      本测试验证 runner 经 SpeculativeClassifier consume 投机结果 + matches/confidence 判定。
        SpeculativeClassifier.seedSpeculativeClassifierCheckForTest("ls -la",
            new SpeculativeClassifier.SpeculativeClassifierResult(
                true, "allowed rule", "high", "Allowed by prompt rule: \"allowed rule\""));
        SwarmWorkerPermissionHandler.PermissionDecision d = SwarmWorkerPermissionHandler.tryClassifier(
            new PermissionResult.PendingClassifierCheck("ls -la", "/cwd", List.of("desc")),
            Map.of("command", "ls -la"), true);
        assertThat(d).isNotNull();
        assertThat(d.behavior()).isEqualTo("allow");
    }

    @Test
    @DisplayName("OPD-WF7-02-01 swarm classifier runner: 门关 / 无 pending → null（fall through mailbox 流）")
    void classifierRunner_featureOffOrNoPending_returnsNull() {
        // 门关（CC feature 双端关闭下等效）→ awaitClassifierAutoApproval 恒 undefined → null
        assertThat(SwarmWorkerPermissionHandler.tryClassifier(
            new PermissionResult.PendingClassifierCheck("ls -la", "/cwd", List.of("desc")),
            Map.of("command", "ls -la"), false)).isNull();
        // 无 pending（非 Bash 工具）→ CC tryClassifier 守卫 → null
        assertThat(SwarmWorkerPermissionHandler.tryClassifier(null, Map.of(), true)).isNull();
    }

    @Test
    @DisplayName("mailbox 发送抛错 → catch → null（fall through 到本地 UI，CC :150-155）")
    void mailboxSendThrows_returnsNull() {
        SwarmWorkerPermissionHandler handler = new SwarmWorkerPermissionHandler(
            () -> true, () -> true, () -> false, null,
            request -> { throw new RuntimeException("mailbox down"); },
            (requestId, toolUseId, onAllow, onReject) -> { },
            pending -> { },
            () -> { });
        assertThat(handler.handle(PARAMS)).isNull();
    }
}
