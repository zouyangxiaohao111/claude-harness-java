package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.InitialPermissionModeResolver;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.source.InitialPermissionModeSource;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.chat.ChatService;
import com.nexusai.application.chat.EffectivePermissionModeResolver;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * [2026-11-20 真实 E2E 缺陷 1] 入站 auto 的模式判定两侧同源。
 *
 * <h2>缺陷</h2>
 * <p>发送方 attest 的模式来自主循环完整链（per-call → 会话列 → settings），接收方注册表模式却只用
 * {@code ChatService.resolveEffectivePermissionMode}（per-call ?? 会话列，<b>不回退 settings</b>）。
 * 只配全局 {@code settings.permission_mode}=bypassPermissions、会话列未写时：
 * 发送方 NON_PROMPTING、接收方 DEFAULT(PROMPTING) ⇒ auto 判 {@code MODE_MISMATCH} 全部转 hold。
 *
 * <h2>RED tooth（旧实现）</h2>
 * <p>把登记处改回「只读 sessions.permission_mode」（`PermissionMode.fromString(session.getPermissionMode())`）
 * ⇒ 第 1 例注册表模式变 DEFAULT、入站判定变 HOLD，两条断言同时红。
 *
 * <h2>优先级链</h2>
 * <p>per-call → 会话记录 → settings（DB 全局 ?? 磁盘 defaultMode）→ default；发送方与接收方同一链。
 */
@DisplayName("[E2E D1] 入站 auto 模式判定：settings 回退 + 两侧同源")
class PeerModeSettingsParityTest {

    private static final String SOURCE = "sess-srcp";
    private static final String TARGET = "sess-tgtp";

    private PeerSessionRegistry registry;
    private EffectivePermissionModeResolver resolver;
    private ChatService chat;
    private SessionMapper sessionMapper;
    private PeerRuntimeAdapter adapter;
    private PeerInboundPolicy policy;

    /** settings 槽固定值的测试替身（不读真实 user.home / settings.json）。 */
    private static final class FixedSettingsSource extends InitialPermissionModeSource {
        private final String defaultMode;
        private final boolean disableBypass;

        FixedSettingsSource(String defaultMode, boolean disableBypass) {
            super(new SettingsJsonParser(new com.fasterxml.jackson.databind.ObjectMapper(),
                    new com.nexusai.application.agent.permission.source.PermissionRuleValueParser()),
                () -> "D:/tmp/erf-test-nohome");
            this.defaultMode = defaultMode;
            this.disableBypass = disableBypass;
        }

        @Override
        public InitialPermissionModeResolver.Input resolveInput(
                String sessionId, String permissionModeCli, boolean dangerouslySkipPermissions) {
            return new InitialPermissionModeResolver.Input(
                permissionModeCli, dangerouslySkipPermissions, defaultMode, disableBypass);
        }
    }

    @BeforeEach
    void setUp() {
        registry = new PeerSessionRegistry();
        sessionMapper = Mockito.mock(SessionMapper.class);
        resolver = new EffectivePermissionModeResolver();
        resolver.setSessionMapper(sessionMapper);
        resolver.setInitialPermissionModeSource(new FixedSettingsSource("bypassPermissions", false));

        chat = new ChatService();
        ReflectionTestUtils.setField(chat, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(chat, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(chat, "effectivePermissionModeResolver", resolver);

        policy = new PeerInboundPolicy();
        policy.setSettingsSource(id -> "auto");
        adapter = new PeerRuntimeAdapter();
        ReflectionTestUtils.setField(adapter, "peerSessionRegistry", registry);
        ReflectionTestUtils.setField(adapter, "peerInboundPolicy", policy);
        ReflectionTestUtils.setField(adapter, "effectivePermissionModeResolver", resolver);
    }

    private SessionRecord session(String id, String permissionMode) {
        SessionRecord s = new SessionRecord();
        s.setId(id);
        s.setTitle(id);
        s.setPermissionMode(permissionMode);
        return s;
    }

    private void stubSession(String id, String permissionMode) {
        SessionRecord row = session(id, permissionMode);
        Mockito.when(sessionMapper.selectOneById(anyString()))
            .thenAnswer(inv -> id.equals(inv.getArgument(0)) ? row : null);
    }

    @Test
    @DisplayName("全局 bypass + 会话未单独设置 ⇒ 两侧都 NON_PROMPTING，auto 不再误 hold")
    void globalBypassWithoutSessionOverrideDoesNotHold() {
        stubSession(TARGET, null);
        // 接收方按会话记录登记 live endpoint（生产入口 = ensurePeerEndpointLive）
        chat.ensurePeerEndpointLive(TARGET);

        PermissionMode registered = registry.resolve(TARGET).orElseThrow().permissionMode();
        assertThat(registered)
            .as("★ 只配全局 bypassPermissions 时，接收方注册表模式必须是 BYPASS（旧实现 = DEFAULT）")
            .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(PeerInboundPolicy.modeClass(registered))
            .isEqualTo(PeerInboundPolicy.ModeClass.NON_PROMPTING);

        PeerEnvelope envelope = PeerEnvelope.create("peer-d1-auto", "team-lead [c1c93c]", SOURCE,
            "team-lead", PermissionMode.BYPASS_PERMISSIONS, List.of(), 1L, "s", false);
        PeerInboundPolicy.Outcome outcome = adapter.revalidateForConsumption(envelope, TARGET);

        assertThat(outcome.decision())
            .as("★ 发送方 NON_PROMPTING + 接收方 NON_PROMPTING ⇒ auto 自动通过（旧实现 = MODE_MISMATCH/HOLD）")
            .isEqualTo(PeerInboundPolicy.Decision.ACCEPT);
        assertThat(outcome.reason()).isEqualTo(PeerInboundPolicy.Reason.MODE_MATCH);
    }

    @Test
    @DisplayName("会话单独设 prompting（default）⇒ 会话记录胜全局 bypass，仍正确判 MODE_MISMATCH")
    void sessionOverridePromptingBeatsGlobalBypass() {
        stubSession(TARGET, "default");
        chat.ensurePeerEndpointLive(TARGET);

        PermissionMode registered = registry.resolve(TARGET).orElseThrow().permissionMode();
        assertThat(registered)
            .as("会话 override(default) 必须胜 settings(bypassPermissions)")
            .isEqualTo(PermissionMode.DEFAULT);

        PeerEnvelope envelope = PeerEnvelope.create("peer-d1-mismatch", "team-lead [c1c93c]", SOURCE,
            "team-lead", PermissionMode.BYPASS_PERMISSIONS, List.of(), 1L, "s", false);
        PeerInboundPolicy.Outcome outcome = adapter.revalidateForConsumption(envelope, TARGET);
        assertThat(outcome.decision()).isEqualTo(PeerInboundPolicy.Decision.HOLD);
        assertThat(outcome.reason()).isEqualTo(PeerInboundPolicy.Reason.MODE_MISMATCH);
    }

    @Test
    @DisplayName("解析链优先级：per-call → 会话记录 → settings → default")
    void precedenceChain() {
        stubSession(TARGET, "plan");
        assertThat(resolver.resolve(session(TARGET, "plan"), "dontAsk"))
            .as("per-call 恒胜会话记录").isEqualTo(PermissionMode.DONT_ASK);
        assertThat(resolver.resolve(session(TARGET, "plan"), null))
            .as("会话记录胜 settings").isEqualTo(PermissionMode.PLAN);
        assertThat(EffectivePermissionModeResolver.resolvePure(null, "bypassPermissions", false))
            .as("无 per-call/会话 → settings").isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(EffectivePermissionModeResolver.resolvePure(null, null, false))
            .as("全空 → default").isEqualTo(PermissionMode.DEFAULT);
        assertThat(EffectivePermissionModeResolver.resolvePure(null, "bypassPermissions", true))
            .as("bypass 被组织门禁用 ⇒ 跳过该层").isEqualTo(PermissionMode.DEFAULT);
    }
}
