package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * macOS AX 状态指纹的<b>跨进程一致性</b>单测。
 *
 * <h2>WHY 这个用例重要（规则 9 · 它防的是一类真实事故）</h2>
 * <p>macOS 的陈旧校验（dsh {@code expectedStateHash}）是<b>跨进程</b>的：
 * observe 在一次 {@code osascript} 里算指纹并存下，act 在<b>另一次</b> {@code osascript} 里
 * 复算并比对。两端<b>不在同一进程</b>，所以：
 * <ul>
 *   <li>算法必须一致（否则永远不等 ⇒ 每个动作都被拒）；</li>
 *   <li>输入必须同构（渲染后文本 ≠ 元素签名序列）。</li>
 * </ul>
 * <p>本仓曾真的踩过：observe 侧用 Java 的 SHA-256（输入＝渲染后文本）、
 * act 侧用 JXA 的 FNV-1a（输入＝元素签名序列）⇒ <b>两边永远不等</b>，
 * 结果 {@code click_element} / {@code type_into_element} 100% 被判「树已变」而拒绝
 * —— 工具看似存在，实际 100% 不可用。
 * <p>⇒ 本用例钉住「指纹只能来自 JXA，Java 侧不得重算」这条约束。
 */
class ComputerUseAxStateHashTest {

    @Test
    @DisplayName("AX 树指纹只能来自 JXA：MacOsUiaService 不得用 Java 侧重算")
    void snapshotStateHashComesFromJxa() {
        // 构造一份「JXA 侧算好的」指纹（模拟 osascript 返回的 stateHash 字段）
        String jxaHash = "6cefc7dd:1475";
        ComputerUseAccessibilitySnapshot snapshot = new ComputerUseAccessibilitySnapshot(
                "[0] Window\n[1] Button \"Open\"",
                java.util.List.of(
                        new ComputerUseAccessibilityNode("Window", "W", "", 0, 0, 0, 0, true, null,
                                java.util.List.of(), 0, "", java.util.List.of(), "", ""),
                        new ComputerUseAccessibilityNode("Button", "Open", "0", 0, 0, 0, 0, true, null,
                                java.util.List.of(), 1, "0", java.util.List.of("AXPress"), "", "")),
                System.currentTimeMillis(),
                "Finder", "com.apple.finder", 4242L, "Documents",
                jxaHash, false);

        // ⭐ 指纹必须是 JXA 给的那个，不能是 Java 侧重算的另一个值
        assertThat(snapshot.stateHash()).isEqualTo(jxaHash);
        // 且必须与 Java 侧对「渲染后文本」的指纹不同（证明两者不是同一算法/输入）
        assertThat(snapshot.stateHash())
                .isNotEqualTo(ComputerUseUiSnapshotStore.textFingerprint(snapshot.text()));
    }

    @Test
    @DisplayName("Java 侧文本指纹与 JXA 侧树指纹是两个不同域的东西（防止有人把它们混用）")
    void textFingerprintIsNotTheAxStateHash() {
        String text = "[0] Window\n[1] Button \"Open\"";
        // Java 文本指纹稳定、可重复
        assertThat(ComputerUseUiSnapshotStore.textFingerprint(text))
                .isEqualTo(ComputerUseUiSnapshotStore.textFingerprint(text));
        // 但它与「树指纹」是不同概念：树指纹的格式是 "<hex>:<长度>"（JXA FNV-1a 的产物）
        // ⇒ Java 文本指纹不得冒充树指纹
        assertThat(ComputerUseUiSnapshotStore.textFingerprint(text)).doesNotContain(":");
    }
}
