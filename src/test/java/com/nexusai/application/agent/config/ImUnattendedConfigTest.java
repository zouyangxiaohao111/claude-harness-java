package com.nexusai.application.agent.config;

import com.nexusai.application.agent.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无人值守工具裁剪（对齐 CC Ask 可用性门 {@code tfi()} 的非交互分支）。
 *
 * <h2>WHY</h2>
 * CC 的判据是**进程级**（`getAllowedChannels().length > 0`），我们是**多会话** ——
 * 照抄会把普通会话的 AskUserQuestion 一起关掉。这里逐条钉住「按会话」的语义：
 * <ul>
 *   <li>显式无人值守的会话 ⇒ 剔除（对话框会挂住）；</li>
 *   <li>普通会话（含<b>绑定了渠道但未显式开</b>的会话）⇒ **保留**（R6：绑定渠道本身不禁 Ask）；</li>
 *   <li>判定失败/未接线 ⇒ 保留（fail-open：宁可多问一句，也别在该问时闭嘴）。</li>
 * </ul>
 */
class ImUnattendedConfigTest {

    /** 最小 Tool 桩：只需要 name()（用 mock，避免跟 Tool 接口的抽象方法数量绑死）。 */
    private static Tool stub(String name) {
        Tool t = org.mockito.Mockito.mock(Tool.class);
        org.mockito.Mockito.when(t.name()).thenReturn(name);
        return t;
    }

    @AfterEach
    void tearDown() {
        ImUnattendedConfig.installChecker(null);
        SessionModeState.reset();
    }

    private static List<Tool> tools() {
        return List.of(stub("AskUserQuestion"), stub("Bash"), stub("SendUserMessage"));
    }

    @Test
    @DisplayName("无人值守会话 ⇒ 剔除 AskUserQuestion，其余工具不受影响")
    void unattendedRemovesAskUserQuestion() {
        ImUnattendedConfig.installChecker(sid -> true);

        List<Tool> out = ImUnattendedConfig.filterForUnattended(tools(), "sess-1");

        assertThat(out).extracting(Tool::name)
                .containsExactly("Bash", "SendUserMessage");
    }

    @Test
    @DisplayName("⭐ 未显式开启无人值守的会话（即便绑定了渠道）⇒ AskUserQuestion 必须保留（R6）")
    void attendedKeepsAskUserQuestion() {
        ImUnattendedConfig.installChecker(sid -> false);

        List<Tool> out = ImUnattendedConfig.filterForUnattended(tools(), "sess-2");

        assertThat(out).extracting(Tool::name).contains("AskUserQuestion");
        assertThat(out).hasSize(3);
    }

    @Test
    @DisplayName("按会话判定：同一批工具，不同会话结果不同")
    void decisionIsPerSession() {
        ImUnattendedConfig.installChecker("sess-im"::equals);

        List<Tool> imSession = ImUnattendedConfig.filterForUnattended(tools(), "sess-im");
        List<Tool> normalSession = ImUnattendedConfig.filterForUnattended(tools(), "sess-normal");

        assertThat(imSession).extracting(Tool::name).doesNotContain("AskUserQuestion");
        assertThat(normalSession).extracting(Tool::name).contains("AskUserQuestion");
    }

    @Test
    @DisplayName("未接线（checker=null）⇒ 不剔除（fail-open）")
    void notWiredKeepsEverything() {
        ImUnattendedConfig.installChecker(null);

        List<Tool> out = ImUnattendedConfig.filterForUnattended(tools(), "sess-1");

        assertThat(out).hasSize(3);
    }

    @Test
    @DisplayName("判定抛异常 ⇒ 按有人值守处理，不剔除（DB 抖动不能让 agent 失去提问能力）")
    void checkerFailureFailsOpen() {
        ImUnattendedConfig.installChecker(sid -> {
            throw new IllegalStateException("db down");
        });

        List<Tool> out = ImUnattendedConfig.filterForUnattended(tools(), "sess-1");

        assertThat(out).extracting(Tool::name).contains("AskUserQuestion");
    }

    @Test
    @DisplayName("sessionId 为空 ⇒ 不判定（不剔除）")
    void blankSessionIdSkipsDecision() {
        ImUnattendedConfig.installChecker(sid -> true);

        assertThat(ImUnattendedConfig.filterForUnattended(tools(), null)).hasSize(3);
        assertThat(ImUnattendedConfig.filterForUnattended(tools(), "  ")).hasSize(3);
    }

    @Test
    @DisplayName("工具列表 null / 空 ⇒ 原样返回，不 NPE")
    void nullOrEmptyTools() {
        ImUnattendedConfig.installChecker(sid -> true);

        assertThat(ImUnattendedConfig.filterForUnattended(null, "s")).isNull();
        assertThat(ImUnattendedConfig.filterForUnattended(List.of(), "s")).isEmpty();
    }

    @Test
    @DisplayName("无需裁剪时返回同一个实例（避免每轮都新建列表）")
    void returnsSameInstanceWhenNoFiltering() {
        ImUnattendedConfig.installChecker(sid -> false);
        List<Tool> in = tools();

        assertThat(ImUnattendedConfig.filterForUnattended(in, "s")).isSameAs(in);
    }

    @Test
    @DisplayName("生产来源 = 会话显式列 unattended_mode=1（不再看 im_chat 绑定）")
    void productionSourceIsExplicitSessionColumn() {
        com.nexusai.repository.session.entity.SessionRecord rec =
                new com.nexusai.repository.session.entity.SessionRecord();
        rec.setId("sess-1");
        rec.setUnattendedMode(1);
        com.nexusai.repository.session.mapper.SessionMapper mapper =
                org.mockito.Mockito.mock(com.nexusai.repository.session.mapper.SessionMapper.class);
        org.mockito.Mockito.when(mapper.selectOneById(org.mockito.ArgumentMatchers.any())).thenReturn(rec);
        SessionModeState.setSessionMapper(mapper);

        // 未 installChecker ⇒ 走 SessionModeState（生产路径）
        assertThat(ImUnattendedConfig.isUnattended("sess-1")).isTrue();
        assertThat(ImUnattendedConfig.filterForUnattended(tools(), "sess-1"))
                .extracting(Tool::name).containsExactly("Bash", "SendUserMessage");
    }

    @Test
    @DisplayName("只有 AskUserQuestion 会被无人值守剔除；SendUserMessage 不受影响（Brief 是另一份状态）")
    void onlyAskIsExcludedByUnattended() {
        ImUnattendedConfig.installChecker("sess-1"::equals);

        assertThat(ImUnattendedConfig.isExcludedInUnattended("AskUserQuestion", "sess-1")).isTrue();
        assertThat(ImUnattendedConfig.isExcludedInUnattended("SendUserMessage", "sess-1")).isFalse();
        assertThat(ImUnattendedConfig.isExcludedInUnattended("AskUserQuestion", "sess-2")).isFalse();
    }
}
