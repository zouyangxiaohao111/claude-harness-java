package com.nexusai.kb.agent;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主线程智能体 main-model 的**取用通道** · 批次 W10 · 真机抓到的缺陷 30 回归。
 *
 * <p><b>缺陷经过（2026-11-05 真机）</b>：智能体 MD 里写了
 * {@code main-model: deepseek/deepseek-v4-pro}，会话也绑定了该智能体，
 * 但主对话仍然用会话原来的模型（{@code oc/deepseek-v4.1-flash}），
 * 而且日志里**连「main-model 不可用」的 WARN 都没有** —— 说明压根没进判断。
 *
 * <p><b>根因</b>：解析发生在 {@code doRun} <b>开头</b>，取 {@code SubagentTool} 走的是
 * **静态桥** {@code staticSubagentTool}，而那个桥是在 {@code doRun} <b>中段</b>才赋值的
 * ⇒ 首次 run 时桥还是 null，解析恒返回 null（静默）。
 *
 * <p><b>本测试锁住的契约</b>：取 {@code SubagentTool} 必须优先用**实例字段
 * {@code toolRegistry}**（构造时注入，恒可用），静态桥只能是兜底。
 * 若退回「只用静态桥」，本测试必红。
 */
@DisplayName("[W10] 主线程 main-model 取用通道")
class LlmAgentLoopMainModelWiringTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setFieldStatic(String name, Object value) throws Exception {
        Field f = LlmAgentLoop.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @Test
    @DisplayName("⭐ 静态桥为空时，仍能从实例 toolRegistry 取到 SubagentTool")
    void resolvesSubagentToolFromInstanceRegistry() throws Exception {
        // 先把静态桥清空，模拟「doRun 开头」的真实状态
        setFieldStatic("staticSubagentTool", null);

        ToolRegistry registry = new ToolRegistry();
        SubagentTool subagentTool = new SubagentTool();
        registry.register(subagentTool);

        LlmAgentLoop loop = new LlmAgentLoop(new com.nexusai.infra.llm.LlmProviderFactory(), registry);

        Method m = LlmAgentLoop.class.getDeclaredMethod("currentSubagentTool");
        m.setAccessible(true);
        Object got = m.invoke(loop);

        assertThat(got)
            .as("⭐ 只用静态桥 ⇒ 首次 run 时恒 null ⇒ main-model 一次都不生效")
            .isSameAs(subagentTool);
    }

    @Test
    @DisplayName("⭐ 解析方法必须是实例方法（否则无法读 toolRegistry）")
    void resolverIsInstanceMethod() {
        Method found = null;
        for (Method m : LlmAgentLoop.class.getDeclaredMethods()) {
            if (m.getName().equals("resolveMainThreadAgentModel")) {
                found = m;
                break;
            }
        }
        assertThat(found).as("方法必须存在").isNotNull();
        assertThat(Modifier.isStatic(found.getModifiers()))
            .as("⭐ static ⇒ 只能读静态桥 ⇒ 首次 run 静默失效（缺陷 30）")
            .isFalse();
    }
}
