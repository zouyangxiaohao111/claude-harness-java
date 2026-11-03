package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MacOsUiaService#rebuildTree} 单测 · computer-use。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：JXA 侧把 AX 树压成<b>扁平记录</b>，
 * 根记录（通常是 {@code AXWindow}）的 {@code loc} 是<b>空串</b>，而
 * {@code parentLocator("")} 也返回空串 ⇒ 若按「parent → children」直接归组，
 * 根会成为<b>自己的子节点</b>，递归立即无限展开。
 * <br>2026-11-02 真机实测（TextEdit 窗口）正是如此：{@code captureSnapshot} 抛
 * {@code StackOverflowError}，整个 observe 不可用。
 * <br>本测试钉住的<b>意图</b>是：<b>根记录自指必须被排除</b>，且窗口节点仍要留在树里
 * （否则工具会丢掉「窗口」这一层，模型看不到 window 标题/角色）。
 */
class MacOsUiaServiceTreeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode records(String json) throws Exception {
        return M.readTree(json);
    }

    @Test
    @DisplayName("根记录 loc 为空串时不得自指递归（真机 StackOverflow 回归）")
    void rootWithEmptyLocatorDoesNotRecurse() throws Exception {
        // 真实形状：窗口 loc="", 其子 "0"、孙 "0.0"
        JsonNode recs = records("""
                [
                  {"i":0,"loc":"","role":"AXWindow","name":"未命名","actions":["AXRaise"]},
                  {"i":1,"loc":"0","role":"AXTextArea","name":"","actions":[]},
                  {"i":2,"loc":"0.0","role":"AXButton","name":"好","actions":["AXPress"]}
                ]
                """);

        MacOsUiaService svc = new MacOsUiaService();
        // 旧实现会在这里 StackOverflowError
        List<ComputerUseAccessibilityNode> roots = svc.rebuildTree(recs);
        assertThat(roots).hasSize(1);
        ComputerUseAccessibilityNode window = roots.get(0);
        assertThat(window.role()).isEqualTo("Window");
        assertThat(window.name()).isEqualTo("未命名");
        assertThat(window.locator()).isEmpty();
        assertThat(window.children()).hasSize(1);
        assertThat(window.children().get(0).role()).isEqualTo("TextArea");
        assertThat(window.children().get(0).locator()).isEqualTo("0");
        assertThat(window.children().get(0).children()).hasSize(1);
        assertThat(window.children().get(0).children().get(0).role()).isEqualTo("Button");
    }

    @Test
    @DisplayName("没有根记录时按 parent 归组，顶层是各孤立子树")
    void withoutRootRecordGroupsByParent() throws Exception {
        JsonNode recs = records("""
                [
                  {"i":0,"loc":"0","role":"AXGroup","name":"g"},
                  {"i":1,"loc":"0.1","role":"AXStaticText","name":"t"},
                  {"i":2,"loc":"1","role":"AXButton","name":"b","actions":["AXPress"]}
                ]
                """);

        List<ComputerUseAccessibilityNode> roots = new MacOsUiaService().rebuildTree(recs);
        assertThat(roots).extracting(ComputerUseAccessibilityNode::locator)
                .containsExactly("0", "1");
        assertThat(roots.get(0).children()).extracting(ComputerUseAccessibilityNode::locator)
                .containsExactly("0.1");
    }

    @Test
    @DisplayName("locator 路径层级由点号决定（parentLocator 语义）")
    void parentLocatorSemantics() {
        assertThat(MacOsUiaService.parentLocator("")).isEmpty();
        assertThat(MacOsUiaService.parentLocator("0")).isEmpty();
        assertThat(MacOsUiaService.parentLocator("0.2")).isEqualTo("0");
        assertThat(MacOsUiaService.parentLocator("0.2.1")).isEqualTo("0.2");
    }
}
