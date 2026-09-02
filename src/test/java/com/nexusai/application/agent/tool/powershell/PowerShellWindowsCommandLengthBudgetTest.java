package com.nexusai.application.agent.tool.powershell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [IMP-5 WinLen] Windows 命令长度预算推导 drift-guard 单测 · 对齐 CC parser.ts:622-630。
 *
 * <p><b>WHY</b>: CC 的 Windows 预算随 {@code PARSE_SCRIPT_BODY.length} 漂移（drift-prone 值是
 * Windows 预算），且 Java 必须从自身脚本本体长度推导（不照抄 CC 的 1088 常量）。本测试锁定
 * 公式三性质：（1）当前脚本本体长度下的黄金预算锚点（公式改动会红）；（2）脚本本体变长时预算
 * 单调递减；（3）脚本本体过长时预算下取整至 0（fail-closed，超预算命令不放行）。
 */
class PowerShellWindowsCommandLengthBudgetTest {

    /** Java 自身 PARSE_SCRIPT_BODY.length()（资源 powershell/parse-script.ps1 当前 10851 字符）。 */
    private static final int CURRENT_BODY_LENGTH = 10851;

    @Test
    @DisplayName("当前脚本本体长度推导的 Windows 预算为黄金锚点值 905")
    void goldenBudgetForCurrentBodyLength() {
        // 公式黄金锚点：若 SAFETY_MARGIN / 推导公式改动，此断言变红提醒回归
        assertEquals(905, PowerShellAstService.windowsMaxCommandBytes(CURRENT_BODY_LENGTH));
    }

    @Test
    @DisplayName("脚本本体变长时预算单调递减（drift-guard）")
    void budgetDriftsDownWhenBodyGrows() {
        int budget = PowerShellAstService.windowsMaxCommandBytes(CURRENT_BODY_LENGTH);
        int budgetForLongerBody = PowerShellAstService.windowsMaxCommandBytes(CURRENT_BODY_LENGTH + 1000);
        assertTrue(budgetForLongerBody < budget,
            "脚本本体变长时 Windows 预算应减小（当前 " + budget + " → " + budgetForLongerBody + "）");
    }

    @Test
    @DisplayName("脚本本体过长时预算下取整至 0（fail-closed）")
    void budgetFloorsAtZeroForOversizedBody() {
        assertEquals(0, PowerShellAstService.windowsMaxCommandBytes(100_000));
    }
}
