package com.nexusai.application.agent.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ant-deepseek 双计修复 2026-10-10] Tokens.inputIncludesCacheHit 判据单点 + getTokenCountFromUsage 自适应。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 上游 usage 存在两种数字形态——「三小票」
 * （Claude 原生：input 不含 cache，三字段独立）与「总小票」（OpenAI 语义来源：input 已含 cache hit，
 * cache_read/cache_creation 是 input 的子集，如 DeepSeek /anthropic 端点）。形态判别只能靠数字自证
 * （cr + cc ≤ input），不能靠协议名（provider.type=anthropic 的 DeepSeek 返回的仍是 OpenAI 语义）。
 *
 * <p><b>数据全部取自 2026-10-10 真库实测</b>（ds-zcw 会话 10/10 行满足宽松判据；精确恒等式
 * {@code input == cr + cc} 仅 2/10 行成立——cc=0 轮不满足，故判据用宽松式）。
 *
 * <p><b>RED（变异哪些会红）</b>: ① anthropic 分支去掉自证 → 总小票用例红（×2 双计回归）；
 * ② 判据去掉 {@code input<=0} 门 / 无缓存字段门 → 边界用例红。
 */
@DisplayName("[ant-deepseek 双计修复] Tokens 缓存形态数字自证")
class TokensTest {

    // ─────────────────────────── 判据本体（真库数据） ───────────────────────────

    @Test
    @DisplayName("总小票形态：cr+cc ≤ input → true（真库 3 种轮型全过）")
    void inputIncludesCacheHit_totalFormRows() {
        // 真库 sess-bf736cb3 09:59:51（cc>0：input=397798=396800+998）
        assertThat(Tokens.inputIncludesCacheHit(397798L, 396800L, 998L)).isTrue();
        // 真库 09:41:13（cc=0 轮——增量未写入 cache；精确恒等式不成立但宽松式成立）
        assertThat(Tokens.inputIncludesCacheHit(345389L, 341760L, 0L)).isTrue();
        // 真库 01:48:38（部分命中轮：cr 占比 ~60%）
        assertThat(Tokens.inputIncludesCacheHit(392428L, 234368L, 0L)).isTrue();
    }

    @Test
    @DisplayName("三小票形态（Claude 原生：cache_read 远大于 input）→ false")
    void inputIncludesCacheHit_threeTicketForm() {
        // Claude 典型轮：新增 input 小、缓存读大 → cr+cc > input
        assertThat(Tokens.inputIncludesCacheHit(1000L, 9000L, 2000L)).isFalse();
        // 首轮写缓存：cache_creation 大、input 小（Claude 首轮形态）
        assertThat(Tokens.inputIncludesCacheHit(50L, 0L, 30000L)).isFalse();
    }

    @Test
    @DisplayName("边界：input≤0 / 无缓存字段 → false（无信息量不判，维持 CC 语义）")
    void inputIncludesCacheHit_boundaries() {
        assertThat(Tokens.inputIncludesCacheHit(0L, 900L, 100L)).isFalse();
        assertThat(Tokens.inputIncludesCacheHit(-5L, 0L, 0L)).isFalse();
        // 无任何缓存字段：两形态同值（都取 input），false = 走 CC 原式（结果等价）
        assertThat(Tokens.inputIncludesCacheHit(1000L, 0L, 0L)).isFalse();
    }

    // ─────────────────────────── getTokenCountFromUsage 自适应 ───────────────────────────

    @Test
    @DisplayName("anthropic=true：总小票 → input+output（防 ×2）；三小票 → CC 4 项和")
    void tokenCountFromUsage_adaptive() {
        // Tokens.Usage 构造序 = (input, output, cacheRead, cacheCreation)
        Tokens.Usage total = new Tokens.Usage(397798, 745, 396800, 998);
        assertThat(Tokens.getTokenCountFromUsage(total, true))
            .as("总小票 → 397798+745=398543（非 795588）").isEqualTo(398543);

        Tokens.Usage three = new Tokens.Usage(1000, 500, 9000, 2000);
        assertThat(Tokens.getTokenCountFromUsage(three, true))
            .as("三小票 → 1000+2000+9000+500=12500（CC 4 项和）").isEqualTo(12500);
        assertThat(Tokens.getTokenCountFromUsage(three, false))
            .as("非 anthropic → input+output=1500").isEqualTo(1500);
    }

    @Test
    @DisplayName("1 参重载（anthropic 缺省 true）同样自适应——防未来裸调用点踩坑")
    void tokenCountFromUsage_oneArgAlsoAdaptive() {
        Tokens.Usage total = new Tokens.Usage(397798, 745, 396800, 998);
        assertThat(Tokens.getTokenCountFromUsage(total))
            .as("1 参 = anthropic 语义，但总小票形态仍自证 → 398543")
            .isEqualTo(398543);
    }
}
