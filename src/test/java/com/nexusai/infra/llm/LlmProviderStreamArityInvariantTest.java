package com.nexusai.infra.llm;

import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [fix-junit 2026-09-14] <b>位置索引不变量守卫</b>：{@link LlmProvider#stream} 的<b>重载 arity 集合</b>与
 * <b>回调参数位置</b>被钉住，使「给 stream 追加/移除形参」必须同步改写全仓用<b>位置索引</b>消费回调的桩。
 *
 * <h2>它守护什么（WHY · 有真实前科）</h2>
 * <p>本接口的桩在测试树里以<b>纯位置索引</b>取回调（{@code inv.getArgument(9/10/16)} 或
 * {@code args.length == N ? big : small} 三元）。而本接口的签名历史上被<b>两次末尾追加</b>
 * （{@code Boolean skipCacheWrite}、{@code AgentContext agentContext}）⇒ 三档 arity 从
 * {@code 18 / 18 / 19} 整体推到 <b>{@code 19 / 19 / 20}</b>。
 * <p>其中一批桩用了 {@code args.length == 19 ? 大值 : 小值} 的<b>条件常量</b>（寓意「19 参 = 带
 * thinkingConfig 的大档」）。arity 整体右移一格后该寓意反转 ⇒ <b>19 参时取到的是大档的索引</b>：
 * <ul>
 *   <li>{@code E1aForkShieldGateTest}：{@code chunkIdx} 取到 10（= onAssistantMessage）后
 *       {@code onChunk.accept("plain text reply")} ⇒ {@code ClassCastException: String cannot be cast
 *       to AssistantMessage} ⇒ {@code onComplete} 永不执行 ⇒ 300s {@code STREAM_TIMEOUT} × 重试
 *       （实测该类 1505s / 2 条断言失败）。</li>
 *   <li>{@code LlmAgentLoopPerRunPromptAssemblyTest}：{@code msgIdx} 取到 11
 *       （= onToolCallComplete，{@code Consumer<ToolUseBlock>}）⇒ {@code ClassCastException:
 *       AssistantMessage cannot be cast to ToolUseBlock} ⇒ 同型超时链（实测该类 2111s /
 *       3 条断言失败）。</li>
 * </ul>
 * <p>⇒ 那 2 个文件里共 <b>7 处位置常量</b>（{@code E1aForkShieldGateTest.java} 的
 * {@code chunkIdx/msgIdx/doneIdx} = 3 处；{@code LlmAgentLoopPerRunPromptAssemblyTest.java} 的
 * 两个桩工厂各 2 处 = 4 处）必须以 <b>20</b> 为大档判据。本类把「档位」这个前提<b>机械钉住</b>，
 * 使下一次 arity 变更时它们在编译/断言期暴露，而不是变成一次静默的 300s 超时。
 *
 * <h2>⛔ 反向实验（falsification · 本守卫是「能红的」）</h2>
 * <p>任选其一即可让本类翻红（本仓判据：护栏有效性靠反向实验，不靠声明）：
 * <ol>
 *   <li>给 {@link LlmProvider#stream} 的任一重载<b>再加一个形参</b>（如 19 → 20）
 *       ⇒ {@link #streamOverloadArities_arePinned()} 的 {@code containsExactly(19, 19, 20)} 红；</li>
 *   <li>在任一重载的<b>中部插入</b>一个形参（如把 {@code querySource} 插到 {@code onChunk} 之前）
 *       ⇒ {@link #nineteenArgOverload_callbackPositions_arePinned()} /
 *       {@link #twentyArgOverload_callbackPositions_arePinned()} 的位置断言红。</li>
 * </ol>
 * <p>⚠️ 本类<b>只</b>守护「接口签名这个前提」。它<b>不</b>守护各测试文件里那 7 处常量本身与
 * 本类一致（常量是字面量，反射看不到）—— 两者的连接点是：<b>本类红 ⟺ 必须回去改那 7 处</b>。
 * <p>⚠️ 全仓绝大多数桩用<b>绝对索引</b> {@code getArgument(9)/(10)/(16)}（那是 19 参重载的正确值），
 * 本类同样守护它们：arity 变更时它们也会被本类提前点名。
 */
@DisplayName("[fix-junit] LlmProvider.stream 重载 arity + 回调位置不变量（位置索引桩的前提）")
class LlmProviderStreamArityInvariantTest {

    /** 按 arity 升序取全部 {@code stream} 重载（含 inherited public 方法，故先过滤名字）。 */
    private static List<Method> streamOverloads() {
        return Arrays.stream(LlmProvider.class.getMethods())
            .filter(m -> "stream".equals(m.getName()))
            .sorted(Comparator.comparingInt(Method::getParameterCount))
            .toList();
    }

    /** 取唯一一个 arity == n 且第 3 个形参（index 2）为 {@code expectedParam2} 的 stream 重载。 */
    private static Method overload(int arity, Class<?> expectedParam2) {
        List<Method> hits = streamOverloads().stream()
            .filter(m -> m.getParameterCount() == arity)
            .filter(m -> expectedParam2.equals(m.getParameterTypes()[2]))
            .toList();
        assertThat(hits)
            .as("arnity=%d 且 index2=%s 的 stream 重载必须唯一（当前命中 %d 个）；"
                + "0 个 = 签名已改，> 1 个 = 本守卫的定位前提失效，都需人工复核全仓位置索引桩",
                arity, expectedParam2.getSimpleName(), hits.size())
            .hasSize(1);
        return hits.get(0);
    }

    /** 形参的泛型实参（如 {@code Consumer<AssistantMessage>} → {@code AssistantMessage.class}）。 */
    private static Class<?> genericArg(Method m, int index) {
        assertThat(m.getGenericParameterTypes()[index])
            .as("index=%d 必须是参数化类型", index)
            .isInstanceOf(ParameterizedType.class);
        return (Class<?>) ((ParameterizedType) m.getGenericParameterTypes()[index])
            .getActualTypeArguments()[0];
    }

    private static void assertCallbackSlot(Method m, int index, Class<?> rawType, Class<?> genericType) {
        assertThat(m.getParameterTypes()[index])
            .as("stream arity=%d 的 index=%d 必须是 %s（位置索引桩据此取值）",
                m.getParameterCount(), index, rawType.getSimpleName())
            .isEqualTo(rawType);
        if (genericType != null) {
            assertThat(genericArg(m, index))
                .as("stream arity=%d 的 index=%d 泛型实参必须是 %s（取错即 ClassCastException）",
                    m.getParameterCount(), index, genericType.getSimpleName())
                .isEqualTo(genericType);
        }
    }

    @Test
    @DisplayName("stream 重载 arity 集合 == {19, 19, 20}（追加/移除形参 ⇒ 全仓位置索引桩必须同步）")
    void streamOverloadArities_arePinned() {
        assertThat(streamOverloads())
            .as("本接口 stream 必须恰好 3 个重载；arity 变更 ⇒ 本仓 7 处条件常量"
                + "（E1aForkShieldGateTest 3 处 + LlmAgentLoopPerRunPromptAssemblyTest 4 处）"
                + "与 ~104 处绝对索引桩全部需要复核")
            .hasSize(3);
        assertThat(streamOverloads().stream().map(Method::getParameterCount).toList())
            .as("arity 集合（升序）—— 实测自 LlmProvider.class.getMethods()")
            .containsExactly(19, 19, 20);
    }

    @Test
    @DisplayName("19 参 blocks 重载（无 thinkingConfig）：onChunk@9 / onAssistantMessage@10 / "
        + "onToolCallComplete@11 / onComplete@16")
    void nineteenArgOverload_callbackPositions_arePinned() {
        Method m = overload(19, List.class);
        assertCallbackSlot(m, 9, Consumer.class, String.class);
        assertCallbackSlot(m, 10, Consumer.class, AssistantMessage.class);
        assertCallbackSlot(m, 11, Consumer.class, ToolUseBlock.class);
        assertCallbackSlot(m, 16, Runnable.class, null);
    }

    @Test
    @DisplayName("20 参 blocks+thinkingConfig 重载：各回调比 19 参者后移一位（onChunk@10 / "
        + "onAssistantMessage@11 / onComplete@17）")
    void twentyArgOverload_callbackPositions_arePinned() {
        Method m = overload(20, List.class);
        assertCallbackSlot(m, 10, Consumer.class, String.class);
        assertCallbackSlot(m, 11, Consumer.class, AssistantMessage.class);
        assertCallbackSlot(m, 17, Runnable.class, null);
    }
}
