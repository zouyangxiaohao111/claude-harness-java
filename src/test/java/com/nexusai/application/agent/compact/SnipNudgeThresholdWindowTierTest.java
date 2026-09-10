package com.nexusai.application.agent.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnipCompactor.resolveSnipNudgeThreshold 窗口自适应档位测试（snip-nudge-scaleup 2026-09-08 ×3 + 2026-09-09 再 ×2）。
 *
 * <p>WHY（CLAUDE.md 规则 9）：「上下文过长通知」= snip nudge（历史消息数 ≥ 阈值时向模型注入
 * "history getting long, consider snip"）。用户实测 1M 上下文 150 条就通知过早打断长任务 →
 * 2026-09-08 拍板档位整体放大 ×3（1M → 450，其它自适应）；2026-09-09 实测对话 831 条仅用 ~61% 窗口
 * （450 条仍过早）→ 拍板再 ×2 同比例放大（1M → 900，其它自适应），避免模型刚进长任务就被 nudge
 * 分心；「窗口未知」场景须保持 CC 默认 30（阈值系统未接线时零行为变化，不臆测大窗口）。
 */
class SnipNudgeThresholdWindowTierTest {

    @Test
    @DisplayName("DB settings.snip_nudge_threshold > 0 → 直接覆盖窗口档位（用户显式配置优先）")
    void dbValue_overridesWindowTier() {
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(77, 900_000))
            .as("用户前端「环境配置」显式设 77 → 恒 77，窗口再大也不回落")
            .isEqualTo(77);
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(77, 200_000))
            .as("小窗口 + 显式配置 → 仍取 DB 值")
            .isEqualTo(77);
    }

    @Test
    @DisplayName("≥800k（1M 窗口，减 reserved 后）→ 900：831 条 ~61% 也不再过早通知")
    void window1m_tier900() {
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, 800_000))
            .as("用户拍板 1M→900：长会话至少攒 900 条消息才提示 snip，长任务不被打断")
            .isEqualTo(900);
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, 1_000_000)).isEqualTo(900);
    }

    @Test
    @DisplayName(">600k 且 <800k（≈512k~800k 窗口）→ 600（自适应 ×3×2）")
    void window600kTo800k_tier600() {
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, 700_000))
            .as("512k 档自适应放大 → 600")
            .isEqualTo(600);
    }

    @Test
    @DisplayName("≥400k 且 ≤600k（400k 窗口）→ 360（自适应 ×3×2）")
    void window400kTo600k_tier360() {
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, 500_000))
            .as("400k 档自适应放大 → 360")
            .isEqualTo(360);
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, 400_000)).isEqualTo(360);
    }

    @Test
    @DisplayName("已知小窗口（>0 且 <400k，如 200k）→ 180（自适应 ×3×2）")
    void smallWindow_tier180() {
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, 200_000))
            .as("200k 档自适应放大 → 180")
            .isEqualTo(180);
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, 1)).isEqualTo(180);
    }

    @Test
    @DisplayName("窗口未知（0/负：阈值系统未接线/单测/无 bean）→ 回落 CC 默认 30（零行为变化）")
    void unknownWindow_fallsBackCcDefault30() {
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, 0))
            .as("窗口 0 = 未接线 → 不能臆测大窗口放大，保持 CC 固定 30（snipCompact.ts:11）")
            .isEqualTo(30);
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(null, -1)).isEqualTo(30);
    }

    @Test
    @DisplayName("dbValue ≤ 0 / null → 视为未配置，回落窗口档位")
    void nonPositiveDbValue_ignored() {
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(0, 1_000_000)).isEqualTo(900);
        assertThat(SnipCompactor.resolveSnipNudgeThreshold(-5, 500_000)).isEqualTo(360);
    }
}
