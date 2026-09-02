package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-SP-07 会话冻结验证测试 · 对齐 CC {@code getSessionStartDate}
 * （CC original: {@code memoize(getLocalISODate)} (Open-ClaudeCode/src/constants/common.ts:24)）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：userContext 的 currentDate 必须用会话冻结日期渲染，
 * 而非实时 {@code LocalDate.now()} —— 跨午夜不陈旧（I-10），prompt cache-key 稳定。
 * 若实现改成每轮实时取日期，本测试 fail（渲染固定过去日期会滚到今天）。
 *
 * <p>验证两段不变量：
 * <ol>
 *   <li><b>构造定格</b>：{@link AgentState#sessionStartDate()} 构造时取本地日一次，之后恒定不变；</li>
 *   <li><b>渲染不滚到 now</b>：{@link UserContextProvider#currentDate(String)} 用冻结值渲染，
 *       固定过去日期 ≠ 实时 {@code LocalDate.now()} 渲染（跨午夜不陈旧）。</li>
 * </ol>
 */
class SessionFreezeTest {

    @Test
    @DisplayName("构造定格: sessionStartDate 构造时取本地日，多次读取恒定（CC getSessionStartDate 冻结）")
    void sessionStartDate_frozenAtConstruction() {
        String today = LocalDate.now().toString();
        AgentState state = new AgentState("system-prompt", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);

        assertThat(state.sessionStartDate())
            .as("本地 ISO 日期（YYYY-MM-DD）")
            .matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(state.sessionStartDate())
            .as("构造时取本地日")
            .isEqualTo(today);
        assertThat(state.sessionStartDate())
            .as("final 字段定格 → 多次读取恒定")
            .isEqualTo(state.sessionStartDate());
    }

    @Test
    @DisplayName("跨午夜不陈旧: 渲染固定过去日期 ≠ 实时日期（I-10，userContext currentDate 用冻结值）")
    void renderFixedPastDate_notRollToNow() {
        // 模拟会话启动于过去日期、跨午夜后仍冻结（构造时定格值，不会滚到今天）
        String pastFrozen = "2026-01-01";
        UserContextProvider provider = new UserContextProvider();

        String rendered = provider.currentDate(pastFrozen);
        assertThat(rendered).isEqualTo("Today's date is 2026-01-01.");

        String naiveNow = provider.currentDate(LocalDate.now().toString());
        assertThat(rendered)
            .as("冻结过去日期 ≠ 实时 now 渲染 → 跨午夜不陈旧（I-10）")
            .isNotEqualTo(naiveNow);
    }

    @Test
    @DisplayName("构造定格 + 渲染闭环: AgentState 冻结日期经 currentDate 渲染不变")
    void frozenDate_rendersStably() {
        AgentState state = new AgentState("system-prompt", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);

        String rendered = new UserContextProvider().currentDate(state.sessionStartDate());
        assertThat(rendered).isEqualTo("Today's date is " + state.sessionStartDate() + ".");
    }
}
