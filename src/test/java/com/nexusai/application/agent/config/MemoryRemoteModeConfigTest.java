package com.nexusai.application.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-CM-19 remote mode 建模判定测试 · 统一 {@link MemoryRemoteModeConfig#isRemoteMode()}。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>配置可开关</b>——{@code nexusai.memory.remote-mode} 显式设置时生效（Java 独有增强，
 *       CC bootstrap/state.ts:1631-1637 无配置通道，仅经 main.tsx:3328/:3447 setIsRemoteMode
 *       CLI 置位），true 时记忆功能跳过（SM init sessionMemory.ts:358 / extract
 *       extractMemories.ts:549-552）。</li>
 *   <li><b>默认非 remote</b>——配置未设 → false（对齐 CC state.ts:390 STATE.isRemoteMode 默认
 *       false，web 后端行为等价不变）。</li>
 * </ol>
 *
 * <p>环境确定性：getIsRemoteMode() 纯读 STATE（无 env 通道，CC 与 CLAUDE_CODE_REMOTE env 是
 * 两路独立信号），故本类无 env 覆盖缝，仅配置 tri-state + 默认。静态桥接经 {@code reset()}
 * 防污染（对齐 MemoryBareModeConfig）。
 */
class MemoryRemoteModeConfigTest {

    @AfterEach
    void tearDown() {
        MemoryRemoteModeConfig.reset();
    }

    @Test
    @DisplayName("配置=true → isRemoteMode true（SM 提取 / extract 跳过语义激活）")
    void configTrue() {
        new MemoryRemoteModeConfig(true);                   // 配置显式 true

        assertThat(MemoryRemoteModeConfig.isRemoteMode()).isTrue();
    }

    @Test
    @DisplayName("配置=false → isRemoteMode false")
    void configFalse() {
        new MemoryRemoteModeConfig(false);                  // 配置显式 false

        assertThat(MemoryRemoteModeConfig.isRemoteMode()).isFalse();
    }

    @Test
    @DisplayName("配置未设 → 默认 false（对齐 CC state.ts:390，web 后端行为等价不变）")
    void configUnsetDefaultsFalse() {
        new MemoryRemoteModeConfig(null);                   // 配置未设

        assertThat(MemoryRemoteModeConfig.isRemoteMode()).isFalse();
    }
}
