package com.nexusai.kb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [KB 对话聚合入口] 分流判据。
 *
 * <h2>这条判据为什么必须锁死</h2>
 * 分流决定「这一轮走 RAG 还是 ReAct」。判错的后果不对称：
 * <ul>
 *   <li><b>该 RAG 却走了 ReAct</b>：quick-answer 的 agent 声明 0 工具，ReAct 会空转；
 *       它的人格是「依据参考材料作答」，没有检索工具就拿不到材料</li>
 *   <li><b>该 ReAct 却走了 RAG</b>：smart-reasoning 需要多步调工具，RAG 链没有工具，
 *       整个能力丢失</li>
 * </ul>
 * 两种都不报错，只是「回答质量莫名其妙地差」。
 *
 * <h2>默认值纪律</h2>
 * ⛔ <b>未绑定 agent ⇒ ReAct</b>（本仓既有默认）。新入口不能改变老会话的行为 ——
 * 这是「加聚合入口而不动现有接口」这个方案的底线。
 */
class KbChatGatewayRouteTest {

    /** 只为调 resolveRoute（不注入任何依赖）。 */
    private static KbChatGatewayController controller() {
        return new KbChatGatewayController();
    }

    @Test
    @DisplayName("⭐ quick-answer ⇒ RAG")
    void quick_answer_goes_rag() {
        assertThat(controller().resolveRoute("builtin-quick-answer"))
            .isEqualTo(KbChatGatewayController.Route.RAG);
    }

    @Test
    @DisplayName("⭐ smart-reasoning ⇒ ReAct（它需要调工具）")
    void smart_reasoning_goes_react() {
        assertThat(controller().resolveRoute("builtin-smart-reasoning"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
        assertThat(controller().resolveRoute("builtin-data-analyst"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
        assertThat(controller().resolveRoute("builtin-wiki-researcher"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
        assertThat(controller().resolveRoute("builtin-wiki-fixer"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
        assertThat(controller().resolveRoute("builtin-assistant"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
    }

    @Test
    @DisplayName("⭐ 未绑定 agent ⇒ ReAct（保持本仓既有默认，⛔ 新入口不改默认）")
    void unbound_goes_react() {
        assertThat(controller().resolveRoute(null))
            .isEqualTo(KbChatGatewayController.Route.REACT);
    }

    @Test
    @DisplayName("绑定了非 kb agent（Explore 等）⇒ ReAct（它们没有 agentMode）")
    void non_kb_agent_goes_react() {
        assertThat(controller().resolveRoute("general-purpose"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
        assertThat(controller().resolveRoute("Explore"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
        assertThat(controller().resolveRoute("Plan"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
    }

    @Test
    @DisplayName("未知 agent id ⇒ ReAct（不猜，不抛）")
    void unknown_agent_goes_react() {
        assertThat(controller().resolveRoute("does-not-exist"))
            .isEqualTo(KbChatGatewayController.Route.REACT);
    }

    @Test
    @DisplayName("只有 builtin-quick-answer 一个走 RAG（6 个 kb agent 里唯一的 quick-answer 模式）")
    void only_one_rag_agent_among_kb_agents() {
        long ragCount = java.util.List.of(
                "builtin-quick-answer",
                "builtin-smart-reasoning",
                "builtin-data-analyst",
                "builtin-wiki-researcher",
                "builtin-wiki-fixer",
                "builtin-assistant")
            .stream()
            .filter(id -> controller().resolveRoute(id) == KbChatGatewayController.Route.RAG)
            .count();

        assertThat(ragCount).as("源站 yaml 里只有 quick-answer 一个 entry 是 quick-answer 模式")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("两个 Route 值都有（枚举完整性）")
    void route_enum_complete() {
        assertThat(KbChatGatewayController.Route.values())
            .containsExactly(KbChatGatewayController.Route.RAG,
                KbChatGatewayController.Route.REACT);
    }
}
