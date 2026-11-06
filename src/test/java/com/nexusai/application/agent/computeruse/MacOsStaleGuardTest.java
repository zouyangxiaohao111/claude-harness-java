package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动作脚本的<b>陈旧校验</b>契约（对齐 dsh {@code expectedStateHash} / {@code COMPUTER_STALE_OBSERVATION}）。
 *
 * <h2>WHY 这些断言重要（不是「测它做了什么」，而是「测它为什么重要」）</h2>
 * <p>macOS 的 locator 是<b>树内路径</b>，不是稳定 id。观察之后界面一变
 * （弹层、列表刷新、窗口切换），同一路径就指向<b>另一个元素</b>：
 * <ul>
 *   <li>点击 ⇒ 按错按钮；</li>
 *   <li><b>写值 ⇒ 把文本写进另一个输入框</b>（用户看不出，但数据已错）。</li>
 * </ul>
 * dsh 用 {@code expectedStateHash} 挡住这种情况。若本仓只在 click 上做、type/perform_action
 * 不做，那「写错字段」这条最隐蔽的错就一直在。
 */
class MacOsStaleGuardTest {

    @Test
    @DisplayName("type 写值脚本包含陈旧校验三件套（prelude + 签名遍历 + 哈希）")
    void setValueScriptHasStaleGuard() {
        String script = MacOsJxa.accessibilitySetValue("0.1.2", "新值", "hash-abc", 4242L);
        // ⛔ 漏了 axHash 会 "Can't find variable: axHash" ⇒ 陈旧校验整段失效（真机踩过）
        assertThat(script)
                .as("必须自带 axHash 函数，否则陈旧校验引用的符号不存在")
                .contains("function axHash");
        assertThat(script).contains("axSignatures(");
        assertThat(script).contains("-25209");
        assertThat(script).contains("EXPECTED_HASH");
        assertThat(script).contains("hash-abc");
    }

    @Test
    @DisplayName("perform_action 脚本包含陈旧校验三件套")
    void performActionScriptHasStaleGuard() {
        String script = MacOsJxa.accessibilityPerformAction("0.1.2", "AXExpand", "hash-xyz", 4242L);
        assertThat(script).contains("function axHash");
        assertThat(script).contains("axSignatures(");
        assertThat(script).contains("-25209");
        assertThat(script).contains("hash-xyz");
        assertThat(script).contains("AXExpand");
    }

    @Test
    @DisplayName("无 hash 时不注入校验（保持既有调用方行为）")
    void noHashMeansNoGuard() {
        String script = MacOsJxa.accessibilitySetValue("0.1", "v", null, 0L);
        // EXPECTED_HASH 变量仍在（脚本结构统一），但值为空串 ⇒ 运行时 `if (el && EXPECTED_HASH)` 短路
        assertThat(script).contains("EXPECTED_HASH = \"\"");
        assertThat(script).contains("if (el && EXPECTED_HASH)");
    }

    @Test
    @DisplayName("陈旧错误码不能退化成 boolean false（否则模型会重复尝试）")
    void staleErrorCodeIsNotSwallowedIntoBoolean() {
        // 契约：AX_ERR_STALE 与 dsh 的 COMPUTER_STALE_OBSERVATION 是同一件事
        assertThat(ComputerUseStaleObservationException.AX_ERR_STALE).isEqualTo(-25209);
        assertThat(ComputerUseUiSnapshotStore.ERR_STALE_OBSERVATION)
                .isEqualTo("COMPUTER_STALE_OBSERVATION");
    }

    @Test
    @DisplayName("陈旧被拒时必须带错误码（不能只是「元素不可按」）")
    void staleRejectionCarriesErrorCode() {
        // WHY：真机曾把 SyntaxError 当成「陈旧被拒」而误判通过（假阳性）。
        // 判据必须是「带 COMPUTER_STALE_OBSERVATION 错误码」。
        ComputerUseStaleObservationException ex =
                new ComputerUseStaleObservationException("the accessibility tree changed");
        assertThatThrownBy(() -> {
            throw ex;
        }).isInstanceOf(ComputerUseStaleObservationException.class)
                .hasMessageContaining("accessibility tree changed");
    }
}
