package com.nexusai.wechatlog.key.elevation;

import com.nexusai.wechatlog.key.WeChatKeyExtractor.KeyExtractionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 失败分支 → 用户文案：取消 UAC、版本未适配、预算不足必须各说各的。 */
class WeChatKeyExtractionMessagesTest {

    @Test
    @DisplayName("取消 UAC 给明确文案，不是「没有候选」")
    void cancelledIsExplicit() {
        var e = WeChatKeyExtractionMessages.cancelled();
        assertTrue(e.getMessage().contains("已取消提权"), e.getMessage());
        assertFalse(e.getMessage().contains("候选"), "取消不是候选为空");
        assertEquals(KeyExtractionException.Kind.PERMISSION, e.kind());
    }

    @Test
    @DisplayName("0 候选的 remedy 不得再写「管理员」（变异点）")
    void noCandidatesDoesNotBlamePermission() {
        var e = WeChatKeyExtractionMessages.noCandidates();
        assertFalse(e.remedy().contains("管理员"), "0 候选不是权限问题: " + e.remedy());
        assertTrue(e.remedy().contains("企业微信") || e.remedy().contains("飞书"),
                "必须给替代渠道: " + e.remedy());
        assertEquals(KeyExtractionException.Kind.NO_CANDIDATES, e.kind());
    }

    @Test
    @DisplayName("版本未适配指向偏移配置文件")
    void versionUnsupportedMentionsOffsets() {
        var e = WeChatKeyExtractionMessages.versionUnsupported("版本 4.2.0.53");
        assertEquals(KeyExtractionException.Kind.VERSION_UNSUPPORTED, e.kind());
        assertTrue(e.remedy().contains("wechat-offsets.properties"), e.remedy());
    }

    @Test
    @DisplayName("预算不足指向 maxBytes")
    void budgetMentionsMaxBytes() {
        var e = WeChatKeyExtractionMessages.scanBudgetExhausted("已扫 256MB");
        assertEquals(KeyExtractionException.Kind.SCAN_BUDGET_EXHAUSTED, e.kind());
        assertTrue(e.remedy().contains("maxBytes"), e.remedy());
    }

    @Test
    @DisplayName("提权不可用保留原始权限错误")
    void elevationUnavailableKeepsOriginal() {
        var e = WeChatKeyExtractionMessages.elevationUnavailable("ACCESS_DENIED");
        assertTrue(e.getMessage().contains("自动提权不可用"), e.getMessage());
        assertTrue(e.remedy().contains("ACCESS_DENIED"), e.remedy());
    }
}
