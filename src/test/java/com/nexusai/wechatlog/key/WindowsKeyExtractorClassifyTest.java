package com.nexusai.wechatlog.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0 候选时的失败归因。
 *
 * <p>这批断言直接对应被修掉的两处错误：
 * ① 预算不足被当成「没有候选」；② 版本未适配被当成「需要管理员」。
 */
class WindowsKeyExtractorClassifyTest {

    private static WeChatOffsetTable.Resolution resolution(boolean verified) {
        return new WeChatOffsetTable.Resolution(
                WeChatOffsetTable.builtin411(), verified, "4.1.11");
    }

    @Test
    @DisplayName("预算耗尽 ⇒ SCAN_BUDGET_EXHAUSTED，而不是 NO_CANDIDATES")
    void budgetExhaustedWins() {
        var out = new MemoryScanner.ScanOutcome(List.of(), 0, 100, 50, true);
        var e = WindowsKeyExtractor.classifyFailure(out, resolution(true), "4.1.11");
        assertEquals(WeChatKeyExtractor.KeyExtractionException.Kind.SCAN_BUDGET_EXHAUSTED,
                e.kind());
        assertTrue(e.remedy().contains("maxBytes"), "应给出提高预算的办法: " + e.remedy());
    }

    @Test
    @DisplayName("未验证版本 + 0 候选 ⇒ 版本未适配（报成功/无候选都必须红）")
    void unverifiedVersionReportsUnsupported() {
        var out = new MemoryScanner.ScanOutcome(List.of(), 3, 100, 0, false);
        var e = WindowsKeyExtractor.classifyFailure(out, resolution(false), "4.2.0.53");
        assertEquals(WeChatKeyExtractor.KeyExtractionException.Kind.VERSION_UNSUPPORTED,
                e.kind());
        assertTrue(e.getMessage().contains("4.2.0.53"), "必须带版本号: " + e.getMessage());
    }

    @Test
    @DisplayName("已验证版本 + 0 候选 ⇒ NO_CANDIDATES，且不再提「请用管理员」")
    void verifiedVersionNoCandidatesHasNoAdminRemedy() {
        var out = new MemoryScanner.ScanOutcome(List.of(), 3, 100, 0, false);
        var e = WindowsKeyExtractor.classifyFailure(out, resolution(true), "4.1.11");
        assertEquals(WeChatKeyExtractor.KeyExtractionException.Kind.NO_CANDIDATES, e.kind());
        assertFalse(e.remedy().contains("管理员"),
                "0 候选不是权限问题，remedy 不得再写管理员: " + e.remedy());
        assertTrue(e.remedy().contains("企业微信") || e.remedy().contains("飞书"),
                "应给替代渠道: " + e.remedy());
    }
}
