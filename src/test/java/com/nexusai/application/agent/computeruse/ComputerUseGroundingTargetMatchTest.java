package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-01] 护栏 ① · <b>目标描述一致性</b>单测（纯单测 · 零 LLM 调用）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：护栏① 是 grounding 降级路径里<b>唯一</b>能抓住
 * 「<b>模型高置信度地指错到相邻控件</b>」的闸门（实测那种错误<b>静默</b>：模型不报错、主模型不报错、
 * 点击落在别处）⇒ 它<b>宁可失败也不放行</b>。
 * <p>⇒ 本测试同时钉住两件互相拉扯的事：
 * <ul>
 *   <li>⭐ <b>不许放过真指错</b>：请求「关闭」而模型回填「最小化」⇒ 必须判失败
 *       （互斥语义必须仍能区分）；</li>
 *   <li>⭐ <b>不许白丢有效定位</b>（2026-11-01 真机实测：原图组 10 次里有 <b>3 次</b>被误拦）：
 *       请求「记事本窗口标题栏最右侧的关闭按钮」而模型回填「记事本窗口关闭按钮」
 *       ⇒ <b>语义完全正确</b>，必须放行。</li>
 * </ul>
 *
 * <p><b>口径</b>：断言对象是<b>判定函数本身</b>（{@link ComputerUseGroundingTargetMatch#matches}），
 * 另有一条走 {@link ComputerUseGroundingService#parse} 的端到端断言（确认失败态确实是
 * {@code TARGET_MISMATCH} 而不只是「不通过」）。
 */
class ComputerUseGroundingTargetMatchTest {

    // ══════════════════════════════════════════════════════════════════
    // ① 真实假阴性案例（本批修复的起因）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ 真实假阴性：请求「记事本窗口标题栏最右侧的关闭按钮」vs 回填「记事本窗口关闭按钮」⇒ 放行")
    void realFalseNegativeIsAccepted() {
        // 真机实测（2026-11-01 · 原图组）：模型回填「记事本窗口关闭按钮」——
        // 它只是省略了「标题栏最右侧的」这类【方位修饰】，语义完全正确 ⇒ 修复前被误判 TARGET_MISMATCH。
        assertThat(ComputerUseGroundingTargetMatch.matches(
                "记事本窗口标题栏最右侧的关闭按钮",
                "记事本窗口关闭按钮")).isTrue();
    }

    @Test
    @DisplayName("⭐ 真实假阴性的端到端形态：护栏① 放行 ⇒ 状态 = FOUND（不是 TARGET_MISMATCH）")
    void realFalseNegativeEndToEnd() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100,\"target\":\"记事本窗口关闭按钮\"}",
                        "记事本窗口标题栏最右侧的关闭按钮", 1920, 1080);

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.FOUND);
    }

    @Test
    @DisplayName("⭐ 剥弱化词后核心词一致 ⇒ 放行（请求更长、回填更短 ⇒ 单向豁免）")
    void coreWordsMatchAfterStrippingModifiers() {
        // 「标题栏 / 最右侧 / 的」是【弱化词】⇒ 剥掉后两边核心都是「记事本close按钮」。
        assertThat(ComputerUseGroundingTargetMatch.stripIgnorable(
                ComputerUseGroundingTargetMatch.normalize(
                        ComputerUseGroundingTargetMatch.expandSynonyms("记事本窗口标题栏最右侧的关闭按钮"))))
                .isEqualTo("记事本close按钮");
        assertThat(ComputerUseGroundingTargetMatch.stripIgnorable(
                ComputerUseGroundingTargetMatch.normalize(
                        ComputerUseGroundingTargetMatch.expandSynonyms("记事本窗口关闭按钮"))))
                .isEqualTo("记事本close按钮");
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 不许放过真指错（互斥语义必须仍能区分）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐ 互斥语义：回填「最小化按钮」vs 请求「关闭按钮」⇒ 判失败")
    void mutuallyExclusiveControlIsRejected() {
        assertThat(ComputerUseGroundingTargetMatch.matches("关闭按钮", "最小化按钮")).isFalse();
        assertThat(ComputerUseGroundingTargetMatch.matches("记事本窗口标题栏最右侧的关闭按钮", "最小化按钮"))
                .isFalse();
        assertThat(ComputerUseGroundingTargetMatch.matches("最小化按钮", "关闭按钮")).isFalse();
    }

    @Test
    @DisplayName("⭐ 互斥语义的端到端形态：失败态 = TARGET_MISMATCH（错误里带两段原文）")
    void mutuallyExclusiveControlIsRejectedEndToEnd() {
        ComputerUseGroundingService.GroundingOutcome outcome =
                ComputerUseGroundingService.parse(
                        "{\"found\":true,\"x\":100,\"y\":100,\"target\":\"最小化按钮\"}",
                        "记事本窗口标题栏最右侧的关闭按钮", 1920, 1080);

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.status()).isEqualTo(ComputerUseGroundingService.Status.TARGET_MISMATCH);
        assertThat(outcome.detail()).contains("最小化按钮");
        assertThat(outcome.detail()).contains("关闭按钮");
    }

    @Test
    @DisplayName("⭐ 互斥语义（跨语言）：请求「the X button (close)…」vs 回填「最小化按钮」⇒ 判失败")
    void mutuallyExclusiveControlAcrossLanguagesIsRejected() {
        assertThat(ComputerUseGroundingTargetMatch.matches(
                "the X button (close) of the Notepad window", "最小化按钮")).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ 完全不相干 / 边界
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("完全不相干的目标 ⇒ 判失败")
    void completelyUnrelatedTargetIsRejected() {
        assertThat(ComputerUseGroundingTargetMatch.matches("记事本窗口标题栏最右侧的关闭按钮", "打印机属性对话框"))
                .isFalse();
        assertThat(ComputerUseGroundingTargetMatch.matches("保存按钮", "取消按钮")).isFalse();
        assertThat(ComputerUseGroundingTargetMatch.matches("文件菜单", "屏幕亮度滑块")).isFalse();
    }

    @Test
    @DisplayName("空回填 / 空请求 ⇒ 判失败（无从交叉验证 ⇒ 保守 fail loud）")
    void emptyEchoIsRejected() {
        assertThat(ComputerUseGroundingTargetMatch.matches("关闭按钮", "")).isFalse();
        assertThat(ComputerUseGroundingTargetMatch.matches("关闭按钮", null)).isFalse();
        assertThat(ComputerUseGroundingTargetMatch.matches("", "关闭按钮")).isFalse();
        assertThat(ComputerUseGroundingTargetMatch.matches(null, null)).isFalse();
    }

    @Test
    @DisplayName("同义词（close / 关闭 / 独立 x）⇒ 放行")
    void synonymEchoIsAccepted() {
        // PoC 真机实测形态：请求英文、模型回填中文 ⇒ 同义词展开后语义相同 ⇒ 放行。
        assertThat(ComputerUseGroundingTargetMatch.matches(
                "the X button (close) of the Notepad window", "关闭")).isTrue();
        assertThat(ComputerUseGroundingTargetMatch.matches("the x button", "关闭")).isTrue();
        assertThat(ComputerUseGroundingTargetMatch.matches("关闭", "close")).isTrue();
        assertThat(ComputerUseGroundingTargetMatch.matches("关闭按钮", "close")).isTrue();
        assertThat(ComputerUseGroundingTargetMatch.matches("close", "关闭按钮")).isTrue();
    }

    @Test
    @DisplayName("简称（回填是请求的子串）⇒ 放行（规则 B 不变）")
    void abbreviationIsAccepted() {
        assertThat(ComputerUseGroundingTargetMatch.matches("记事本右上角的最小化按钮", "最小化")).isTrue();
    }

    @Test
    @DisplayName("Jaccard 边界值：恰在阈值上 ⇒ 放行；刚低于阈值 ⇒ 失败")
    void jaccardBoundaryIsExact() {
        // 阈值 0.6（= 3/5）：构造字符集合交 3 并 5 的两个串
        assertThat(ComputerUseGroundingTargetMatch.JACCARD_THRESHOLD).isEqualTo(0.6d);
        assertThat(ComputerUseGroundingTargetMatch.jaccard("abc", "abcde")).isEqualTo(0.6d);
        assertThat(ComputerUseGroundingTargetMatch.jaccard("abc", "abcd")).isEqualTo(0.75d);
        assertThat(ComputerUseGroundingTargetMatch.jaccard("abc", "abcdef")).isEqualTo(0.5d);
        // 边界：0.6 放行 / 0.5 不放行（阈值未被下调 ⇒ 「回填更长 = 更大复合元素」仍被拦）
        assertThat(ComputerUseGroundingTargetMatch.matches("abc", "abcde")).isTrue();
        assertThat(ComputerUseGroundingTargetMatch.matches("abc", "abcdef")).isFalse();
    }

    @Test
    @DisplayName("回填比请求更长（匹配到更大的复合元素）⇒ 判失败（单向豁免的方向不能反）")
    void longerEchoIsRejected() {
        // 「关闭」⇒「关闭所有标签页」：不是同一个控件 ⇒ 失败。
        assertThat(ComputerUseGroundingTargetMatch.matches("关闭", "关闭所有标签页")).isFalse();
        // 「确定」⇒「确定取消」：引入了 cancel 语义 ⇒ 失败（既有行为不能被新规则放宽）。
        assertThat(ComputerUseGroundingTargetMatch.matches("确定", "确定取消")).isFalse();
    }

    @Test
    @DisplayName("弱化词表不含任何控件语义词 ⇒ 剥弱化词不会把「关闭」削成「最小化」")
    void ignorableWordsNeverContainControlConcepts() {
        assertThat(ComputerUseGroundingTargetMatch.stripIgnorable("关闭按钮")).isEqualTo("关闭按钮");
        assertThat(ComputerUseGroundingTargetMatch.stripIgnorable("最小化按钮")).isEqualTo("最小化按钮");
        assertThat(ComputerUseGroundingTargetMatch.stripIgnorable("最大化按钮")).isEqualTo("最大化按钮");
    }
}
