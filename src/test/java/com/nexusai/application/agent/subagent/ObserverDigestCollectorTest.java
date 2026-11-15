package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observer digest 渲染测试 · 逐字对齐 CC v2.1.325（off≈328,476,xxx）。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>digest 是喂给观察者的「只读证据」。nonce、截断、delivery cap 三者共同决定：
 * 观察者能否区分「digest 自己的标签」与「内容里伪造的标签」，以及单次投递会不会撑爆上下文。
 * 少任何一条都会让观察者被注入或被截断误导。
 */
@DisplayName("[agent-observer] digest 渲染")
class ObserverDigestCollectorTest {

    @Test
    @DisplayName("nonce 首行 + 外层标签 + 各事件标签")
    void rendersNonceAndTags() {
        ObserverDigestCollector c = new ObserverDigestCollector();
        c.assistant("hello");
        c.toolCall("Read", "{\"path\":\"a\"}");
        c.toolResult("content");
        c.turnEnded("completed");

        String out = c.render("n-1", "Worker Agent", "focus on regressions");

        assertThat(out).contains("[This delivery's digest tags carry nonce=\"n-1\"");
        assertThat(out).contains("<Worker-Agent-activity nonce=\"n-1\">");
        assertThat(out).contains("<tool-call nonce=\"n-1\" name=\"Read\">");
        assertThat(out).contains("<tool-result nonce=\"n-1\">content</tool-result>");
        assertThat(out).contains("<turn-ended nonce=\"n-1\" reason=\"completed\" />");
        assertThat(out).contains("hello");
        assertThat(out).contains(ObserverDigestCollector.READ_ONLY_NOTICE);
        assertThat(out).contains("focus on regressions");
    }

    @Test
    @DisplayName("单条内容超过 2000 字符 ⇒ 截断并标注省略字符数")
    void itemClippedAt2000() {
        ObserverDigestCollector c = new ObserverDigestCollector();
        c.assistant("x".repeat(2500));

        String out = c.render("n", "w", null);

        assertThat(out).contains("… [+500 chars truncated]");
    }

    @Test
    @DisplayName("整次投递超过 64000 字符 ⇒ 前部省略并显式说明")
    void deliveryCappedAt64000() {
        ObserverDigestCollector c = new ObserverDigestCollector();
        for (int i = 0; i < 40; i++) {
            c.assistant("y".repeat(2000));
        }

        String out = c.render("n", "w", null);

        assertThat(out).contains("earlier item(s) of this digest omitted: over the 64000-character delivery cap");
        assertThat(out.length()).isLessThan(70000);
    }

    @Test
    @DisplayName("标签名净化：非字母数字-_ ⇒ '-'")
    void sanitizesTagName() {
        ObserverDigestCollector c = new ObserverDigestCollector();
        c.assistant("x");

        String out = c.render("n", "a b/c", null);

        assertThat(out).contains("<a-b-c-activity nonce=\"n\">");
    }

    @Test
    @DisplayName("reset 清空后不再渲染旧事件")
    void resetClears() {
        ObserverDigestCollector c = new ObserverDigestCollector();
        c.assistant("old");
        assertThat(c.isEmpty()).isFalse();

        c.reset();

        assertThat(c.isEmpty()).isTrue();
        assertThat(c.render("n", "w", null)).doesNotContain("old");
    }
}
