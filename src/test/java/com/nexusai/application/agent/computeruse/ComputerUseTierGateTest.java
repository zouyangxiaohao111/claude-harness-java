package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档位门控 + 默认档位 + 键归一 单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：档位是 computer-use 的<b>安全边界</b> ——
 * 「read 档的浏览器不许点」是这条边界存在的全部理由。若 {@code tierSatisfies} 的四个分支
 * 写错一个，模型就能在只读授权下点进用户邮箱。
 *
 * <p>断言直接对着 CCB {@code toolCalls.ts:357-368} 的四个分支逐条锁死：
 * {@code mouse_position} 恒真；{@code keyboard} / {@code mouse_full} 需 full；
 * {@code mouse} 需 click 或 full。
 */
class ComputerUseTierGateTest {

    @Test
    @DisplayName("mouse_position 任何档位都放行（CCB: 纯光标定位不与应用交互）")
    void mousePositionPassesAtAnyTier() {
        assertThat(ComputerUseTierGate.tierSatisfies("read", ComputerUseActionKind.MOUSE_POSITION)).isTrue();
        assertThat(ComputerUseTierGate.tierSatisfies("click", ComputerUseActionKind.MOUSE_POSITION)).isTrue();
        assertThat(ComputerUseTierGate.tierSatisfies("full", ComputerUseActionKind.MOUSE_POSITION)).isTrue();
        assertThat(ComputerUseTierGate.tierSatisfies((String) null, ComputerUseActionKind.MOUSE_POSITION)).isTrue();
    }

    @Test
    @DisplayName("keyboard / mouse_full 只在 full 档放行（read 与 click 都被拒）")
    void keyboardAndMouseFullRequireFullTier() {
        for (ComputerUseActionKind kind : new ComputerUseActionKind[]{
                ComputerUseActionKind.KEYBOARD, ComputerUseActionKind.MOUSE_FULL}) {
            assertThat(ComputerUseTierGate.tierSatisfies("read", kind)).isFalse();
            assertThat(ComputerUseTierGate.tierSatisfies("click", kind)).isFalse();
            assertThat(ComputerUseTierGate.tierSatisfies("full", kind)).isTrue();
            // CCB: const tier = grantTier ?? 'full' —— null 回落 full ⇒ 放行
            assertThat(ComputerUseTierGate.tierSatisfies((String) null, kind)).isTrue();
        }
    }

    @Test
    @DisplayName("mouse 在 click 与 full 放行，read 拒绝")
    void mouseNeedsClickOrFull() {
        assertThat(ComputerUseTierGate.tierSatisfies("read", ComputerUseActionKind.MOUSE)).isFalse();
        assertThat(ComputerUseTierGate.tierSatisfies("click", ComputerUseActionKind.MOUSE)).isTrue();
        assertThat(ComputerUseTierGate.tierSatisfies("full", ComputerUseActionKind.MOUSE)).isTrue();
        assertThat(ComputerUseTierGate.tierSatisfies((String) null, ComputerUseActionKind.MOUSE)).isTrue();
    }

    @Test
    @DisplayName("未知档位串不放行 mouse（CCB 语义：未知串既非 full 也非 click）")
    void unknownTierDeniesMouse() {
        assertThat(ComputerUseTierGate.tierSatisfies("bogus", ComputerUseActionKind.MOUSE)).isFalse();
        assertThat(ComputerUseTierGate.tierSatisfies("bogus", ComputerUseActionKind.KEYBOARD)).isFalse();
        // mouse_position 恒真（CCB toolCalls.ts:362）
        assertThat(ComputerUseTierGate.tierSatisfies("bogus", ComputerUseActionKind.MOUSE_POSITION)).isTrue();
        assertThat(ComputerUseTier.fromWire("bogus")).isNull();
        assertThat(ComputerUseTier.fromWire(null)).isEqualTo(ComputerUseTier.FULL);
        assertThat(ComputerUseTier.fromWire("")).isEqualTo(ComputerUseTier.FULL);
    }

    @Test
    @DisplayName("默认档位：exe 名归一后浏览器→read、终端/IDE→click、其它→full")
    void defaultTierByExeName() {
        // 浏览器（CCB deniedApps.ts:327-350）
        assertThat(ComputerUseAppPolicy.defaultTier("chrome", "Chrome")).isEqualTo(ComputerUseTier.READ);
        assertThat(ComputerUseAppPolicy.defaultTier("msedge", "Microsoft Edge")).isEqualTo(ComputerUseTier.READ);
        assertThat(ComputerUseAppPolicy.defaultTier("firefox", "Firefox")).isEqualTo(ComputerUseTier.READ);
        // 终端 / IDE（CCB deniedApps.ts:352-410）
        assertThat(ComputerUseAppPolicy.defaultTier("windowsterminal", "Windows Terminal"))
                .isEqualTo(ComputerUseTier.CLICK);
        assertThat(ComputerUseAppPolicy.defaultTier("powershell", "Windows PowerShell"))
                .isEqualTo(ComputerUseTier.CLICK);
        assertThat(ComputerUseAppPolicy.defaultTier("code", "Visual Studio Code"))
                .isEqualTo(ComputerUseTier.CLICK);
        // 其它 → full
        assertThat(ComputerUseAppPolicy.defaultTier("notepad", "Notepad")).isEqualTo(ComputerUseTier.FULL);
    }

    @Test
    @DisplayName("授权键归一：exe 全路径 → exe 名（CCB Windows 缺陷的修正）")
    void appKeyNormalization() {
        assertThat(ComputerUseAppPolicy.normalizeAppKey("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"))
                .isEqualTo("chrome");
        assertThat(ComputerUseAppPolicy.normalizeAppKey("Chrome.EXE")).isEqualTo("chrome");
        assertThat(ComputerUseAppPolicy.normalizeAppKey("notepad")).isEqualTo("notepad");
        assertThat(ComputerUseAppPolicy.normalizeAppKey("/usr/local/bin/firefox")).isEqualTo("firefox");
        assertThat(ComputerUseAppPolicy.normalizeAppKey(null)).isEmpty();
    }

    @Test
    @DisplayName("策略黑名单优先：netflix / spotify 等被拒，且不受用户批准影响")
    void policyDeniedApps() {
        assertThat(ComputerUseAppPolicy.isPolicyDenied("netflix", "Netflix")).isTrue();
        assertThat(ComputerUseAppPolicy.isPolicyDenied("spotify", "Spotify")).isTrue();
        assertThat(ComputerUseAppPolicy.isPolicyDenied("chrome", "Chrome")).isFalse();
        assertThat(ComputerUseAppPolicy.isPolicyDenied("notepad", "Notepad")).isFalse();
    }
}
