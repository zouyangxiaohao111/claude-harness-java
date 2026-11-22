package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * live endpoint 注册表：代次 / token（已停用）/ 关闭 / 重名 / 注销不复活。
 */
@DisplayName("PeerSessionRegistry · endpoint 代次与可见性")
class PeerSessionRegistryTest {

    private static final String SES_A = "sess-aaaaaaa1";
    private static final String SES_B = "sess-bbbbbbb2";

    private static PeerSessionRegistry.Registration reg(String sessionId, String title) {
        return new PeerSessionRegistry.Registration(sessionId, title, "/proj",
            PermissionMode.DEFAULT, 7L);
    }

    @Test
    @DisplayName("register 生成 epoch；token 已停用（空串，字段保留）且公开视图不含 token")
    void registerGeneratesEpochAndToken() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        PeerSessionRegistry.EndpointHandle handle = registry.register(reg(SES_A, "alpha"));

        assertThat(handle.endpointEpoch()).isPositive();
        // token 停用（对齐清单 T01）：CC 的 token + 命名管道防冒充依赖多进程，
        // 本仓单进程、来源身份由服务端上下文给出，token 无生产校验点 ⇒ 不再生成。
        assertThat(handle.endpointToken()).as("token 已停用：字段保留但恒为空串").isEmpty();
        assertThat(registry.resolve(SES_A)).get()
            .extracting(PeerSessionRegistry.LiveSession::endpointEpoch)
            .isEqualTo(handle.endpointEpoch());
        assertThat(registry.snapshot().get(0).toString())
            .as("对外只读视图不得出现 token 字段").doesNotContain("endpointToken");
    }

    @Test
    @DisplayName("重复 register 产生新 epoch；旧代次 unregister 不删新 endpoint")
    void unregisterChecksGeneration() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        PeerSessionRegistry.EndpointHandle first = registry.register(reg(SES_A, "alpha"));
        PeerSessionRegistry.EndpointHandle second = registry.register(reg(SES_A, "alpha"));

        assertThat(second.endpointEpoch()).isGreaterThan(first.endpointEpoch());
        assertThat(registry.unregister(SES_A, first.endpointEpoch())).isFalse();
        assertThat(registry.resolve(SES_A)).isPresent();
        assertThat(registry.isLive(SES_A, second.endpointEpoch())).isTrue();
        assertThat(registry.unregister(SES_A, second.endpointEpoch())).isTrue();
        assertThat(registry.resolve(SES_A)).as("已注销 endpoint 不复活").isEmpty();
    }

    @Test
    @DisplayName("beginClosing: 仍在快照中可见，但不可投递")
    void closingIsReadableButNotDeliverable() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        PeerSessionRegistry.EndpointHandle handle = registry.register(reg(SES_A, "alpha"));
        registry.beginClosing(SES_A, handle.endpointEpoch());

        assertThat(registry.resolve(SES_A)).get()
            .satisfies(s -> {
                assertThat(s.closing()).isTrue();
                assertThat(s.accepting()).isFalse();
            });
        assertThat(registry.isLive(SES_A, handle.endpointEpoch())).isFalse();
    }

    @Test
    @DisplayName("updateMode 只接受前进的 modeVersion")
    void updateModeMonotonic() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        PeerSessionRegistry.EndpointHandle handle = registry.register(reg(SES_A, "alpha"));

        assertThat(registry.updateMode(SES_A, handle.endpointEpoch(),
            PermissionMode.PLAN, 2L)).isTrue();
        assertThat(registry.updateMode(SES_A, handle.endpointEpoch(),
            PermissionMode.BYPASS_PERMISSIONS, 1L)).as("旧版本不覆盖新值").isFalse();
        assertThat(registry.resolve(SES_A)).get()
            .extracting(PeerSessionRegistry.LiveSession::permissionMode)
            .isEqualTo(PermissionMode.PLAN);
    }

    @Test
    @DisplayName("updateTitle 只接受同代次；标题与按标题寻址同步变化")
    void updateTitleUpdatesSnapshot() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        PeerSessionRegistry.EndpointHandle handle = registry.register(reg(SES_A, "New session"));

        assertThat(registry.updateTitle(SES_A, handle.endpointEpoch(), "竞品调研")).isTrue();
        assertThat(registry.resolve(SES_A)).get()
            .extracting(PeerSessionRegistry.LiveSession::title)
            .isEqualTo("竞品调研");
        assertThat(registry.findByAddress("竞品调研", null)).hasSize(1);
        assertThat(registry.findByAddress("New session", null)).as("旧标题不再可寻址").isEmpty();

        assertThat(registry.updateTitle(SES_A, handle.endpointEpoch() - 1, "旧代次"))
            .as("代次不符 → 不更新").isFalse();
        assertThat(registry.resolve(SES_A)).get()
            .extracting(PeerSessionRegistry.LiveSession::title)
            .isEqualTo("竞品调研");
    }

    @Test
    @DisplayName("heartbeat 只推进同代次的活动时间；旧代次 no-op（不更新、不复活）")
    void heartbeatAdvancesOnlyForCurrentGeneration() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        PeerSessionRegistry.EndpointHandle first = registry.register(reg(SES_A, "alpha"));
        java.time.Instant baseline = registry.resolve(SES_A).orElseThrow().lastActivityAt();

        assertThat(registry.heartbeat(SES_A, first.endpointEpoch() - 1))
            .as("旧代次 heartbeat 必须 no-op").isFalse();
        assertThat(registry.resolve(SES_A).orElseThrow().lastActivityAt())
            .as("旧代次不得推进活动时间").isEqualTo(baseline);

        assertThat(registry.heartbeat(SES_A, first.endpointEpoch())).isTrue();
        assertThat(registry.resolve(SES_A).orElseThrow().lastActivityAt())
            .as("活动时间不得倒退").isAfterOrEqualTo(baseline);

        // 新代次登记后，旧代次 heartbeat 必须 no-op（不得碰新 endpoint）
        PeerSessionRegistry.EndpointHandle second = registry.register(reg(SES_A, "alpha"));
        java.time.Instant secondBaseline = registry.resolve(SES_A).orElseThrow().lastActivityAt();
        assertThat(registry.heartbeat(SES_A, first.endpointEpoch())).isFalse();
        assertThat(registry.resolve(SES_A).orElseThrow().lastActivityAt())
            .isEqualTo(secondBaseline);
        assertThat(registry.resolve(SES_A).orElseThrow().endpointEpoch())
            .isEqualTo(second.endpointEpoch());
    }

    @Test
    @DisplayName("注销后 heartbeat 为 no-op，不复活已注销端点")
    void heartbeatAfterUnregisterDoesNotResurrect() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        PeerSessionRegistry.EndpointHandle handle = registry.register(reg(SES_A, "alpha"));
        assertThat(registry.unregister(SES_A, handle.endpointEpoch())).isTrue();

        assertThat(registry.heartbeat(SES_A, handle.endpointEpoch()))
            .as("已注销端点 heartbeat 必须 no-op").isFalse();
        assertThat(registry.resolve(SES_A))
            .as("heartbeat 不得复活已注销端点").isEmpty();
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("同名跨 session：findByAddress 原样返回多条（由调用方判 AMBIGUOUS）")
    void findByAddressReturnsAllMatches() {
        PeerSessionRegistry registry = new PeerSessionRegistry();
        registry.register(reg(SES_A, "alpha"));
        registry.register(reg(SES_B, "alpha"));

        List<PeerSessionRegistry.LiveSession> byName = registry.findByAddress("alpha", null);
        assertThat(byName).hasSize(2);
        // 重名时地址名追加 ref 以保证唯一
        assertThat(registry.snapshot())
            .extracting(PeerSessionRegistry.LiveSession::addressName)
            .doesNotHaveDuplicates();
        assertThat(registry.findByAddress("alpha", PeerAddressing.refFor(SES_A))).hasSize(1);
    }
}
