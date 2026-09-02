package com.nexusai.application.agent.config;

import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.SkillsLoader;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ODF-A3 bareMode 建模三态判定测试 · 统一 {@link MemoryBareModeConfig#isBareMode()}。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>配置优先</b>——owner 拍板方案 C：{@code nexusai.memory.bare-mode} 显式设置时优先于 env
 *       （Java 独有增强，CC envUtils.ts:60-65 无配置通道），配置 false 可压过 env truthy。</li>
 *   <li><b>env 权威回退</b>——配置未设时对齐 CC isBareMode：{@code CLAUDE_CODE_SIMPLE} truthy 集
 *       {1,true,yes,on}（envUtils.ts:32-38）→ true。</li>
 *   <li><b>默认非 bare</b>——双未设 → false（对齐 CC 默认行为）。</li>
 *   <li><b>3 调用点收敛一致</b>——SkillsLoader:145（默认 lambda）/ LlmAgentLoop:3612 /
 *       BundledSkillEnabledGates:111 全部委托同一静态方法，注入后判定必须一致。</li>
 * </ol>
 *
 * <p>环境确定性：Java 无法进程内改 env，经 {@link MemoryBareModeConfig} 的 env 覆盖测试缝
 * （与 SkillsLoader.setBareModeSupplier 同款，P2-20 约定）。静态桥接经 {@code reset()} 防污染。
 */
class MemoryBareModeConfigTest {

    @AfterEach
    void tearDown() {
        MemoryBareModeConfig.reset();
    }

    @Test
    @DisplayName("配置=true 优先于 env truthy（含 env=1）→ true")
    void configTrueWinsOverEnvTruthy() {
        MemoryBareModeConfig.setEnvOverride("1");          // env=1（CC truthy）
        new MemoryBareModeConfig(true);                     // 配置显式 true

        assertThat(MemoryBareModeConfig.isBareMode()).isTrue();
    }

    @Test
    @DisplayName("配置=false 优先于 env truthy → false（Java 独有覆盖通道可压 env）")
    void configFalseWinsOverEnvTruthy() {
        MemoryBareModeConfig.setEnvOverride("on");         // env=on（CC truthy）
        new MemoryBareModeConfig(false);                    // 配置显式 false

        assertThat(MemoryBareModeConfig.isBareMode()).isFalse();
    }

    @Test
    @DisplayName("配置未设 + CLAUDE_CODE_SIMPLE=1 → true（对齐 CC isBareMode env 权威）")
    void configUnsetEnvTruthy() {
        MemoryBareModeConfig.setEnvOverride("1");
        new MemoryBareModeConfig(null);                     // 配置未设

        assertThat(MemoryBareModeConfig.isBareMode()).isTrue();
    }

    @Test
    @DisplayName("配置未设 + env 未设 → false（对齐 CC 默认非 bare）")
    void bothUnsetDefaultsFalse() {
        MemoryBareModeConfig.setEnvOverride(null);          // env 未设
        new MemoryBareModeConfig(null);                     // 配置未设

        assertThat(MemoryBareModeConfig.isBareMode()).isFalse();
    }

    @Test
    @DisplayName("SkillsLoader 默认判定收敛到统一 isBareMode（三态一致）且保留测试注入缝隙")
    void skillsLoaderDelegatesToUnified() {
        SkillsLoader loader = new SkillsLoader();

        // 配置 true → loader bare
        new MemoryBareModeConfig(true);
        assertThat(loader.isBareMode()).isTrue();

        // 配置未设 + env truthy → loader bare
        MemoryBareModeConfig.setEnvOverride("1");
        new MemoryBareModeConfig(null);
        assertThat(loader.isBareMode()).isTrue();

        // 双未设 → loader 非 bare
        MemoryBareModeConfig.setEnvOverride(null);
        new MemoryBareModeConfig(null);
        assertThat(loader.isBareMode()).isFalse();

        // 测试注入缝隙仍有效（覆盖默认 lambda 委托）
        MemoryBareModeConfig.setEnvOverride("1");
        new MemoryBareModeConfig(null);
        loader.setBareModeSupplier(() -> false);
        assertThat(loader.isBareMode()).isFalse();
    }

    @Test
    @DisplayName("BundledSkillEnabledGates:111 链内收敛 — 配置 true → isAutoMemoryEnabled false")
    void bundledGatesConvergesOnUnified() {
        // 配置 true（bare）→ 5 级链第 3 级短路 → auto-memory 关闭
        new MemoryBareModeConfig(true);

        assertThat(MemoryBareModeConfig.isBareMode()).isTrue();
        assertThat(BundledSkillEnabledGates.isAutoMemoryEnabled()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // [V33] 会话级 bare 判定（用户 2026-08-23 拍板：bareMode 随会话走）
    // 优先级：会话 sessions.bare_mode（DB）→ 回落 isBareMode()（配置 → env → false）
    // ════════════════════════════════════════════════════════════════════

    private static final String SESS = "sess-test1";

    private static SessionMapper mockMapperWithBare(Integer bareMode) {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionRecord r = new SessionRecord();
        r.setBareMode(bareMode);
        when(mapper.selectOneById(SESS)).thenReturn(r);
        return mapper;
    }

    @Test
    @DisplayName("会话 bare_mode=1 → isBareMode(sessionId)=true（会话级覆盖胜出，即使配置 false + env 未设）")
    void sessionBareModeTrueWins() {
        // WHY: 会话显式开启 bare → 必须生效，与全局配置/env 无关（用户拍板 bareMode 随会话走，
        //   V33 列 bare_mode 是 Web 多会话的承载）。变异点：会话级判定未接线 → 回落配置 false → 误判非 bare → 红。
        MemoryBareModeConfig.setSessionMapper(mockMapperWithBare(1));
        MemoryBareModeConfig.setEnvOverride(null);
        new MemoryBareModeConfig(false);

        assertThat(MemoryBareModeConfig.isBareMode(SESS)).isTrue();
    }

    @Test
    @DisplayName("会话 bare_mode=0 → isBareMode(sessionId)=false（会话显式关闭可压过全局配置 true）")
    void sessionBareModeFalseWins() {
        // WHY: 会话显式关闭 bare（V33 列 0）→ 该会话必须全量工具池，全局配置 true 不得覆盖会话级
        //   （对齐 effort/ultracode 会话级语义）。变异点：会话级判定未接线 → 回落配置 true → 误判 bare → 红。
        MemoryBareModeConfig.setSessionMapper(mockMapperWithBare(0));
        MemoryBareModeConfig.setEnvOverride(null);
        new MemoryBareModeConfig(true);

        assertThat(MemoryBareModeConfig.isBareMode(SESS)).isFalse();
    }

    @Test
    @DisplayName("会话 bare_mode=null → 回落 env CLAUDE_CODE_SIMPLE truthy → true")
    void sessionBareModeNullFallsBackToEnv() {
        // WHY: 会话未显式设置（null）→ 对齐 CC isBareMode() 的 env 权威通道（envUtils.ts:60-65）
        //   （Java 侧 nexusai.memory.bare-mode 配置未设时）。变异点：null 误判 false → env=1 的部署误关 bare → 红。
        MemoryBareModeConfig.setSessionMapper(mockMapperWithBare(null));
        MemoryBareModeConfig.setEnvOverride("1");           // CLAUDE_CODE_SIMPLE=1（CC truthy）
        new MemoryBareModeConfig(null);                      // 配置未设

        assertThat(MemoryBareModeConfig.isBareMode(SESS)).isTrue();
    }

    @Test
    @DisplayName("会话 bare_mode=null + 配置 true → 回落配置 → true")
    void sessionBareModeNullFallsBackToConfig() {
        // WHY: Java 独有增强（nexusai.memory.bare-mode）在会话未设置时仍优先于 env（ODF-A3 方案 C 优先级）。
        MemoryBareModeConfig.setSessionMapper(mockMapperWithBare(null));
        MemoryBareModeConfig.setEnvOverride(null);
        new MemoryBareModeConfig(true);

        assertThat(MemoryBareModeConfig.isBareMode(SESS)).isTrue();
    }

    @Test
    @DisplayName("会话 bare_mode=null + 配置/env 双未设 → false（对齐 CC 默认非 bare）")
    void sessionBareModeNullBothUnsetDefaultsFalse() {
        // WHY: 会话未设置 + 全局未配置 → 默认非 bare（CC isBareMode 默认 false），不误开精简模式。
        MemoryBareModeConfig.setSessionMapper(mockMapperWithBare(null));
        MemoryBareModeConfig.setEnvOverride(null);
        new MemoryBareModeConfig(null);

        assertThat(MemoryBareModeConfig.isBareMode(SESS)).isFalse();
    }

    @Test
    @DisplayName("会话不存在（selectOneById=null）→ 回落全局判定（配置 false → false）")
    void sessionNotFoundFallsBackToGlobal() {
        // WHY: 会话未落库/删除后残留 → 会话级读取 null → 回落全局（不因 DB 空记录抛错/误判）。
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(SESS)).thenReturn(null);
        MemoryBareModeConfig.setSessionMapper(mapper);
        MemoryBareModeConfig.setEnvOverride(null);
        new MemoryBareModeConfig(false);

        assertThat(MemoryBareModeConfig.isBareMode(SESS)).isFalse();
    }

    @Test
    @DisplayName("会话 bare_mode DB 读取抛异常 → 回落全局判定（配置 false → false），不向上抛")
    void sessionBareModeDbFailureFallsBackToGlobal() {
        // WHY: DB 故障不能阻断主循环工具装配（bare 裁剪是裁量非硬约束）——失败须静默回落全局判定。
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(SESS)).thenThrow(new RuntimeException("db down"));
        MemoryBareModeConfig.setSessionMapper(mapper);
        MemoryBareModeConfig.setEnvOverride(null);
        new MemoryBareModeConfig(false);

        assertThat(MemoryBareModeConfig.isBareMode(SESS)).isFalse();
    }

    @Test
    @DisplayName("无会话上下文（sessionId=null）→ 回落全局判定（配置 true → true）")
    void nullSessionFallsBackToGlobal() {
        // WHY: 无 MDC/无 TUC 会话（cron/后台线程）→ 会话级读取无键 → 走全局判定（配置通道仍生效）。
        MemoryBareModeConfig.setSessionMapper(mockMapperWithBare(1));
        MemoryBareModeConfig.setEnvOverride(null);
        new MemoryBareModeConfig(true);

        assertThat(MemoryBareModeConfig.isBareMode(null)).isTrue();
    }
}
