package com.nexusai.infra.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DynamicHeaderExpander} 单测：占位符展开规则 + 敏感头禁止清单 + 毒字符。
 *
 * <p>设计见 docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.1 / §6.4。
 *
 * <p><b>三条被本类钉住的不变量</b>：
 * <ol>
 *   <li>任何路径都绝不把字面量 {@code ${session_id}} 发到线上
 *       （{@link #neverEmitsLiteralPlaceholderUnderAnyCombination} 穷举「开关开/关 × sessionId null/空/空白/有值」）</li>
 *   <li>不含占位符的值恒等返回 → 既有静态配置零行为变化，且不进新代码路径</li>
 *   <li>{@link DynamicHeaderExpander#STATIC_FALLBACK} 是「取不到会话时发什么」的答案
 *       —— 与参考实现 qwen-code 的<b>有意分歧</b>（qwen-code 丢弃 header；我们发常量）</li>
 * </ol>
 *
 * <p><b>变异自证（反向实验三连，已实跑确认变红后还原）</b>：
 * ① {@code if (!gateEnabled)} → {@code if (false)} ⇒ {@link #fallsBackToConstantWhenGateOff} 红；
 * ② {@code isForbiddenHeaderName} → {@code return false} ⇒
 * {@link #forbiddenHeaderNamesRejectedCaseInsensitively} + {@link #sdkCredentialHeaderNamesMustBeForbidden} 红；
 * ③ sessionId 缺失支 {@code return STATIC_FALLBACK} → {@code return value} ⇒
 * {@link #neverEmitsLiteralPlaceholderUnderAnyCombination} 红（字面量守护）。
 */
@DisplayName("DynamicHeaderExpander · 占位符展开 + 敏感头清单")
class DynamicHeaderExpanderTest {

    // ---- expand ----

    @Test
    @DisplayName("不含占位符的值恒等返回")
    void staticValuePassesThroughUnchanged() {
        assertEquals("nexusai", DynamicHeaderExpander.expand("nexusai", "sess-1", true));
        assertEquals("nexusai", DynamicHeaderExpander.expand("nexusai", null, false));
    }

    @Test
    @DisplayName("开关开且会话就绪时替换为真值")
    void expandsToRealSessionIdWhenGateOnAndSessionPresent() {
        assertEquals("sess-abc", DynamicHeaderExpander.expand("${session_id}", "sess-abc", true));
    }

    @Test
    @DisplayName("开关开但会话缺失时落兜底常量")
    void fallsBackToConstantWhenSessionMissing() {
        assertEquals("nexusai-static", DynamicHeaderExpander.expand("${session_id}", null, true));
        assertEquals("nexusai-static", DynamicHeaderExpander.expand("${session_id}", "   ", true));
    }

    @Test
    @DisplayName("开关关时落兜底常量")
    void fallsBackToConstantWhenGateOff() {
        assertEquals("nexusai-static", DynamicHeaderExpander.expand("${session_id}", "sess-abc", false));
    }

    @Test
    @DisplayName("任何情形都绝不发出字面量占位符")
    void neverEmitsLiteralPlaceholderUnderAnyCombination() {
        for (String sid : new String[]{null, "", "   ", "sess-1"}) {
            for (boolean gate : new boolean[]{true, false}) {
                String out = DynamicHeaderExpander.expand("${session_id}", sid, gate);
                assertFalse(out.contains("${session_id}"), "泄漏了字面量: sid=" + sid + " gate=" + gate);
            }
        }
    }

    @Test
    @DisplayName("值里多个占位符全部替换")
    void replacesAllOccurrencesInOneValue() {
        assertEquals("a-sess-1-b-sess-1-c",
            DynamicHeaderExpander.expand("a-${session_id}-b-${session_id}-c", "sess-1", true));
    }

    @Test
    @DisplayName("值里混合静态与占位符")
    void expandsPlaceholderMixedWithStaticPrefix() {
        assertEquals("nexusai:sess-1",
            DynamicHeaderExpander.expand("nexusai:${session_id}", "sess-1", true));
    }

    @Test
    @DisplayName("不含占位符但会话缺失也不受影响")
    void staticValueUnaffectedByMissingSession() {
        assertEquals("Bearer xyz", DynamicHeaderExpander.expand("Bearer xyz", null, true));
    }

    // ---- 敏感头清单（D5）----

    @Test
    @DisplayName("敏感头一律拒绝（大小写不敏感）")
    void forbiddenHeaderNamesRejectedCaseInsensitively() {
        for (String name : new String[]{"Authorization", "AUTHORIZATION", "authorization",
                "proxy-authorization", "x-api-key", "X-Api-Key", "api-key",
                "cookie", "set-cookie",
                "content-length", "content-type", "host", "transfer-encoding",
                "connection", "te", "upgrade",
                // expect：JDK HttpRequest.Builder.header 的受限头之一。漏了它的真实危害 = 存量脏数据会让
                // ProviderService.test（「测试连接」）抛 IllegalArgumentException，而它的 catch(Exception)
                // 会吞成误导性的 "Unknown error"。见 DynamicHeaderExpander 清单上的 javadoc。
                "expect", "Expect"}) {
            assertTrue(DynamicHeaderExpander.isForbiddenHeaderName(name), "应被拒绝: " + name);
        }
    }

    @Test
    @DisplayName("协议类头不在拒绝清单内（防止清单开太大）")
    void protocolHeadersNotForbidden() {
        for (String name : new String[]{"anthropic-version", "anthropic-beta",
                "accept", "user-agent", "x-opencode-session", "x-opencode-client"}) {
            assertFalse(DynamicHeaderExpander.isForbiddenHeaderName(name), "不应被拒绝: " + name);
        }
    }

    /**
     * 【T2 实测固化】两个 SDK 依 .apiKey() 自动注入的凭据头名，必须在拒绝清单里。
     *
     * <p>T2 实测：{@code putHeader} 能<b>顶掉</b> SDK 自动注入的凭据头，且与调用顺序无关
     * —— 所以这份清单是唯一防线，一旦漏掉一个凭据头名，没有任何第二道防线。
     *
     * <p><b>⚠️ 本测试不是漂移守卫</b>：下面两个名字是测试内<b>硬编码字面量</b>，本测试<b>不读 SDK</b>。
     * 真正的「SDK 自动注入的头名是什么」由 {@code SdkPutHeaderProbeTest} 经 stub 抓包断言：
     * {@code :171}（Anthropic → {@code x-api-key} 仍等于 {@code k}）与
     * {@code :292}（OpenAI → {@code authorization} 仍等于 {@code Bearer k}）。
     * <b>那两条是长期漂移守卫，不是一次性探针。</b>
     * 若将来要删 {@code SdkPutHeaderProbeTest}，<b>必须先把那两条断言迁到这里</b>，
     * 否则「本清单 ↔ SDK 实际注入头名」之间的漂移将再无守卫。
     */
    @Test
    @DisplayName("两个 SDK 的凭据头名必须都在拒绝清单里")
    void sdkCredentialHeaderNamesMustBeForbidden() {
        for (String credentialHeader : new String[]{"x-api-key", "authorization"}) {
            assertTrue(DynamicHeaderExpander.isForbiddenHeaderName(credentialHeader),
                "SDK 凭据头 [" + credentialHeader + "] 不在拒绝清单里 —— 用户可自定义它来顶掉真实 apiKey");
        }
    }

    @Test
    @DisplayName("非法 header 名被拒")
    void invalidHeaderNamesRejected() {
        for (String name : new String[]{"", " ", "a b", "a:b", "a\nb", "a\tb", "naïve"}) {
            assertFalse(DynamicHeaderExpander.isValidHeaderName(name), "应判非法: [" + name + "]");
        }
    }

    @Test
    @DisplayName("合法 header 名通过")
    void validHeaderNamesAccepted() {
        for (String name : new String[]{"x-opencode-session", "X-Custom-1", "a"}) {
            assertTrue(DynamicHeaderExpander.isValidHeaderName(name), "应判合法: " + name);
        }
    }

    @Test
    @DisplayName("含换行的值被拒")
    void headerValuesWithNewlinesRejected() {
        assertFalse(DynamicHeaderExpander.isValidHeaderValue("a\r\nX-Evil: 1"));
        assertFalse(DynamicHeaderExpander.isValidHeaderValue("a\nb"));
        assertFalse(DynamicHeaderExpander.isValidHeaderValue("a\rb"));
        assertTrue(DynamicHeaderExpander.isValidHeaderValue("a b, c"));
    }

    // ---- 批量 ----

    /**
     * <b>真的</b>构造 null 条目（原先此测试只放两个正常条目，故跳过分支零覆盖 —— 名不副实）。
     *
     * <p>WHY：跳过分支是<b>承重</b>的 —— 规范 §6.2 要求注入侧防「存量脏数据」，
     * 而脏 JSON 反序列化正是产生 null key/value 的唯一通道。透传 null 给 SDK 无法发出，
     * 且跳过必须 warn（不静默，规范 §6.3）。
     */
    @Test
    @DisplayName("批量展开跳过 null 键值")
    void expandAllSkipsNullEntries() {
        Map<String,String> in = new HashMap<>();
        in.put("a", "${session_id}");
        in.put("b", "static");
        in.put(null, "键为 null 的条目");
        in.put("d", null);
        Map<String,String> out = DynamicHeaderExpander.expandAll(in, "sess-1", true);
        // 两条 null 条目被跳过（不抛），只留两条正常条目
        assertEquals(Map.of("a", "sess-1", "b", "static"), out);
        assertEquals(2, out.size(), "null 条目不得进入结果");
        assertFalse(out.containsKey(null), "键为 null 的条目必须被跳过");
        assertFalse(out.containsValue(null), "值为 null 的条目必须被跳过");
    }

    @Test
    @DisplayName("expand 对 null 值返回 null（不抛）")
    void expandNullValueReturnsNull() {
        assertNull(DynamicHeaderExpander.expand(null, "sess-1", true));
        assertNull(DynamicHeaderExpander.expand(null, null, false));
    }

    @Test
    @DisplayName("expandAll 对 null / 空 Map 返回空表")
    void expandAllNullAndEmptyMapReturnEmpty() {
        assertTrue(DynamicHeaderExpander.expandAll(null, "sess-1", true).isEmpty());
        assertTrue(DynamicHeaderExpander.expandAll(Map.of(), "sess-1", true).isEmpty());
    }

    @Test
    @DisplayName("校验方法对 null 入参判非法（不抛）")
    void validatorsRejectNullArguments() {
        assertFalse(DynamicHeaderExpander.isValidHeaderName(null));
        assertFalse(DynamicHeaderExpander.isValidHeaderValue(null));
        assertFalse(DynamicHeaderExpander.isForbiddenHeaderName(null));
    }

    // ---- 占位符拼写守卫（任务 4 顺带新增的纯函数）----
    // WHY：占位符 token 大小写敏感，写成 ${SESSION_ID} 会被原样当字面量发到线上（静默失效）
    // → 写侧必须 fail loud，否则症状是「网关又 400 了」而毫无线索。

    @Test
    @DisplayName("拼写错误的占位符被识别")
    void detectsMalformedPlaceholderSpelling() {
        for (String bad : new String[]{"${SESSION_ID}", "${Session_Id}", "${session-id}", "${session_id }"}) {
            assertTrue(DynamicHeaderExpander.hasMalformedPlaceholder(bad), "应判为拼写错误: " + bad);
        }
    }

    @Test
    @DisplayName("精确占位符与普通值都不算拼写错误")
    void exactPlaceholderAndPlainValueAreNotMalformed() {
        assertFalse(DynamicHeaderExpander.hasMalformedPlaceholder("${session_id}"),
            "精确 token 不是拼写错误");
        assertFalse(DynamicHeaderExpander.hasMalformedPlaceholder("a${session_id}b"),
            "精确 token 夹杂静态前后缀不是拼写错误");
        assertFalse(DynamicHeaderExpander.hasMalformedPlaceholder("nexusai"),
            "无 ${ 的普通值不是拼写错误");
        assertFalse(DynamicHeaderExpander.hasMalformedPlaceholder(null),
            "null 不是拼写错误（不抛）");
    }
}
