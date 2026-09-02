package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [DB 主控] DISABLE_COMPACT / DISABLE_AUTO_COMPACT DB 优先判定测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: 用户决策「DB 直接改库即生效」——
 * settings 表 disable_compact / disable_auto_compact 列直接改库即生效（有值用 DB），
 * env 仅作 DB 无值时的部署级强制覆盖 fallback，再无回落默认 false（不由此开关禁用）。
 * 本测试固化三态优先级：
 * <ol>
 *   <li>DB 有值（true/false）直接生效，覆盖 env（含 false 显式放行覆盖 env 真值）</li>
 *   <li>DB 无值 → env fallback（部署级强制覆盖，对齐 CC autoCompact.ts:148/:152、
 *       reactiveCompact.ts:44）</li>
 *   <li>DB/env 皆无 → 默认启用（autoCompactEnabled=true）</li>
 * </ol>
 *
 * <p><b>env 注入</b>: AutoCompactor 经 {@link AutoCompactor#setEnvProvider} 注入 map
 * （Java 无法改真实 env）；ReactiveCompactor 经 {@link ReactiveCompactor#setEnvProvider}
 * （本测试新增，同 AutoCompactor 模式）。DB 经 {@link CompactSettingsResolver} 子类
 * StubResolver 覆写固定值（无 DB/mapper 依赖）。
 */
@DisplayName("[DB 主控] DISABLE_COMPACT / DISABLE_AUTO_COMPACT DB 优先判定")
class DisableCompactDbPrimaryTest {

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
    }

    // ════════════════════════════════════════════════════════════════════
    // StubResolver · 覆写 disableCompact/disableAutoCompact/autoCompactEnabled/reactiveCompactEnabled
    // ════════════════════════════════════════════════════════════════════

    /** 测试用固定值 resolver · 无 DB/mapper 依赖，字段 null = DB 未配置（回落 env）。 */
    private static class StubResolver extends CompactSettingsResolver {
        private final Boolean disableCompact;
        private final Boolean disableAutoCompact;
        private final Boolean autoCompactEnabled;
        private final Boolean reactiveCompactEnabled;

        StubResolver(Boolean disableCompact, Boolean disableAutoCompact,
                     Boolean autoCompactEnabled, Boolean reactiveCompactEnabled) {
            this.disableCompact = disableCompact;
            this.disableAutoCompact = disableAutoCompact;
            this.autoCompactEnabled = autoCompactEnabled;
            this.reactiveCompactEnabled = reactiveCompactEnabled;
        }

        @Override
        public Boolean disableCompact() { return disableCompact; }

        @Override
        public Boolean disableAutoCompact() { return disableAutoCompact; }

        @Override
        public Boolean autoCompactEnabled() { return autoCompactEnabled; }

        @Override
        public Boolean reactiveCompactEnabled() { return reactiveCompactEnabled; }
    }

    private static StubResolver db(Boolean disableCompact, Boolean disableAutoCompact) {
        return new StubResolver(disableCompact, disableAutoCompact, null, null);
    }

    /** 恒定高 token → shouldAutoCompact 阈值必达（排除阈值因素，聚焦 DISABLE 门）。 */
    private AutoCompactor autoCompactor(CompactSettingsResolver resolver, Map<String, String> env) {
        AutoCompactor ac = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("summary", null));
        if (resolver != null) {
            ac.setSettingsResolver(resolver);
        }
        ac.setEnvProvider(env::get);
        return ac;
    }

    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false));
        }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════
    // AutoCompactor · disable_compact DB 主控
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DB disable_compact=true（无 env）→ 压缩禁用（isAutoCompactEnabled/shouldAutoCompact/tryAutoCompact）")
    void dbDisableCompactTrueDisablesWithoutEnv() {
        AutoCompactor auto = autoCompactor(db(true, null), Map.of());

        assertThat(auto.isAutoCompactEnabled())
            .as("DB true 直接禁用自动压缩（autoCompact.ts:148 一票否决）").isFalse();
        assertThat(auto.shouldAutoCompact(largeMessages(50), "user", 0))
            .as("shouldAutoCompact 早退 false（autoCompact.ts:185-187 链）").isFalse();
        assertThat(auto.tryAutoCompact(largeMessages(50)).wasCompacted())
            .as("autoCompactIfNeeded DISABLE_COMPACT 早退（autoCompact.ts:253-255）").isFalse();
    }

    @Test
    @DisplayName("DB disable_compact=false（无 env）→ 启用（DB 显式放行）")
    void dbDisableCompactFalseEnablesWithoutEnv() {
        AutoCompactor auto = autoCompactor(db(false, null), Map.of());
        assertThat(auto.isAutoCompactEnabled()).isTrue();
    }

    @Test
    @DisplayName("DB disable_compact=false 覆盖 env DISABLE_COMPACT=true → 启用（DB 主控，放行优先）")
    void dbDisableCompactFalseOverridesEnvTrue() {
        AutoCompactor auto = autoCompactor(db(false, null), Map.of("DISABLE_COMPACT", "true"));
        assertThat(auto.isAutoCompactEnabled())
            .as("DB 有值（false）主控，env 真值被覆盖 → 启用").isTrue();
    }

    @Test
    @DisplayName("DB disable_compact=true 覆盖 env（env 无值）→ 禁用")
    void dbDisableCompactTrueWithNoEnvDisables() {
        AutoCompactor auto = autoCompactor(db(true, null), Map.of("DISABLE_COMPACT", "false"));
        assertThat(auto.isAutoCompactEnabled())
            .as("DB 有值（true）主控 → 禁用").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // AutoCompactor · disable_auto_compact DB 主控
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DB disable_auto_compact=true（无 env）→ 自动压缩禁用（保留手动 /compact）")
    void dbDisableAutoCompactTrueDisablesWithoutEnv() {
        AutoCompactor auto = autoCompactor(db(null, true), Map.of());
        assertThat(auto.isAutoCompactEnabled())
            .as("DB true 直接禁用自动压缩（autoCompact.ts:152 一票否决）").isFalse();
    }

    @Test
    @DisplayName("DB disable_auto_compact=false 覆盖 env DISABLE_AUTO_COMPACT=true → 启用")
    void dbDisableAutoCompactFalseOverridesEnvTrue() {
        AutoCompactor auto = autoCompactor(db(null, false), Map.of("DISABLE_AUTO_COMPACT", "on"));
        assertThat(auto.isAutoCompactEnabled())
            .as("DB 有值（false）主控，env 真值被覆盖 → 启用").isTrue();
    }

    @Test
    @DisplayName("DB disable_compact=true 优先于 disable_auto_compact=false（任一 true 即禁用）")
    void dbDisableCompactTrueBeatsDisableAutoFalse() {
        AutoCompactor auto = autoCompactor(db(true, false), Map.of());
        assertThat(auto.isAutoCompactEnabled())
            .as("disable_compact=true 一票否决（DISABLE_COMPACT 覆盖 DISABLE_AUTO_COMPACT，CC :148 在前）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // AutoCompactor · DB 无值 → env fallback（部署级强制覆盖）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DB 无值 → env DISABLE_COMPACT=true 兜底禁用")
    void noDbFallsBackToEnvDisableCompact() {
        AutoCompactor auto = autoCompactor(db(null, null), Map.of("DISABLE_COMPACT", "true"));
        assertThat(auto.isAutoCompactEnabled())
            .as("DB 未配置，env 部署级覆盖生效").isFalse();
    }

    @Test
    @DisplayName("DB 无值 → env DISABLE_AUTO_COMPACT=true 兜底禁用")
    void noDbFallsBackToEnvDisableAutoCompact() {
        AutoCompactor auto = autoCompactor(db(null, null), Map.of("DISABLE_AUTO_COMPACT", "1"));
        assertThat(auto.isAutoCompactEnabled())
            .as("DB 未配置，env 部署级覆盖生效").isFalse();
    }

    @Test
    @DisplayName("DB/env 皆无 → 回落 autoCompactEnabled（默认 true 启用，可关）")
    void noDbNoEnvFallsBackToAutoCompactEnabled() {
        AutoCompactor auto = autoCompactor(db(null, null), Map.of());
        assertThat(auto.isAutoCompactEnabled()).isTrue();
        auto.setAutoCompactEnabled(false);
        assertThat(auto.isAutoCompactEnabled())
            .as("无 DISABLE 门时 userConfig.autoCompactEnabled 判定（CC autoCompact.ts:157）").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // ReactiveCompactor · disable_compact DB 主控
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Reactive: DB disable_compact=true（feature 开）→ isReactiveCompactEnabled=false")
    void reactiveDbDisableCompactTrueDisables() {
        ReactiveCompactor rc = reactiveCompactor(db(true, null), Map.of());
        assertThat(rc.isReactiveCompactEnabled())
            .as("DB true 主控禁用应急压缩（reactiveCompact.ts:44 一票否决）").isFalse();
    }

    @Test
    @DisplayName("Reactive: DB disable_compact=false 覆盖 env DISABLE_COMPACT=true → 启用")
    void reactiveDbDisableCompactFalseOverridesEnvTrue() {
        ReactiveCompactor rc = reactiveCompactor(db(false, null), Map.of("DISABLE_COMPACT", "true"));
        assertThat(rc.isReactiveCompactEnabled())
            .as("DB 有值（false）主控，env 真值被覆盖 → 应急压缩可用").isTrue();
    }

    @Test
    @DisplayName("Reactive: DB 无值 → env DISABLE_COMPACT=true 兜底禁用")
    void reactiveNoDbFallsBackToEnvDisableCompact() {
        ReactiveCompactor rc = reactiveCompactor(db(null, null), Map.of("DISABLE_COMPACT", "on"));
        assertThat(rc.isReactiveCompactEnabled())
            .as("DB 未配置，env 部署级覆盖生效（reactiveCompact.ts:44）").isFalse();
    }

    @Test
    @DisplayName("Reactive: DB/env 皆无且 feature 开 → 启用")
    void reactiveNoDbNoEnvEnabledWhenFeatureOn() {
        ReactiveCompactor rc = reactiveCompactor(db(null, null), Map.of());
        assertThat(rc.isReactiveCompactEnabled())
            .as("featureGate=enabled=true，无 DISABLE 门 → 启用").isTrue();
    }

    private static ReactiveCompactor reactiveCompactor(CompactSettingsResolver resolver, Map<String, String> env) {
        ReactiveCompactor rc = new ReactiveCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("summary", null));
        rc.setEnabled(true);
        if (resolver != null) {
            rc.setSettingsResolver(resolver);
        }
        rc.setEnvProvider(env::get);
        return rc;
    }
}
