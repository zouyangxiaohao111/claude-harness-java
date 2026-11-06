package com.nexusai.application.agent.subagent.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部 CLI 输出解析器回归 · 锁死三种 streamFormat 的核心语义。
 *
 * <p>WHY（谓词对齐 DSH / OpenDesign 的真实协议）：
 * <ul>
 *   <li>Claude Code {@code -p --output-format stream-json} 实际发
 *       {@code assistant}（含 content 数组）/ {@code content_block_delta} / {@code result}
 *       —— 若把 delta 当最终结果取，会丢掉后半段；若只取 result，流式思考就没了。</li>
 *   <li>dsh-agent-conductor 的 plain 形态是「stdout 直接当答案」—— 这里必须有诚实回退，
 *       否则一个非 JSON 的 CLI 会永远返回空结论。</li>
 * </ul>
 */
@DisplayName("[external-agent] ExternalStreamParser 三格式回归")
class ExternalStreamParserTest {

    @Test
    @DisplayName("claude-stream-json: 累积 text_delta，result 覆盖为最终结论")
    void claudeStreamJson_accumulatesDeltasAndPrefersResult() {
        String stdout = String.join("\n",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"半\"}}",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"成品\"}}",
                "{\"type\":\"result\",\"result\":\"半成品【最终】\"}");

        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse("claude-stream-json", stdout);

        assertThat(parsed.text()).isEqualTo("半成品【最终】");
    }

    @Test
    @DisplayName("claude-stream-json: thinking_delta 归入 reasoning，不进正文")
    void claudeStreamJson_separatesReasoning() {
        String stdout = String.join("\n",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"我先算一下\"}}",
                "{\"type\":\"result\",\"result\":\"2\"}");

        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse("claude-stream-json", stdout);

        assertThat(parsed.reasoning()).isEqualTo("我先算一下");
        assertThat(parsed.text()).isEqualTo("2");
    }

    @Test
    @DisplayName("claude-stream-json: assistant content 里的 tool_use 被收集")
    void claudeStreamJson_collectsToolUse() {
        String stdout = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"查一下\"},"
                + "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"a.txt\"}}]}}";

        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse("claude-stream-json", stdout);

        assertThat(parsed.toolCalls()).hasSize(1);
        assertThat(parsed.toolCalls().get(0).name()).isEqualTo("Read");
        assertThat(parsed.toolCalls().get(0).arguments()).contains("a.txt");
        assertThat(parsed.text()).isEqualTo("查一下");
    }

    @Test
    @DisplayName("jsonl: opencode 风格 part 事件可解析")
    void jsonl_parsesPartEvents() {
        String stdout = String.join("\n",
                "{\"part\":{\"type\":\"text\",\"text\":\"结果 A\"}}",
                "{\"part\":{\"type\":\"tool\",\"tool\":\"bash\",\"state\":{\"input\":{\"cmd\":\"ls\"}}}}");

        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse("jsonl", stdout);

        assertThat(parsed.text()).isEqualTo("结果 A");
        assertThat(parsed.toolCalls()).extracting(ExternalStreamParser.ToolCall::name)
                .containsExactly("bash");
    }

    @Test
    @DisplayName("plain: 整段 stdout 即结论（dsh conductor 语义）")
    void plain_wholeStdoutIsAnswer() {
        ExternalStreamParser.Parsed parsed =
                ExternalStreamParser.parse("plain", "第 1 行\n第 2 行");

        assertThat(parsed.text()).isEqualTo("第 1 行\n第 2 行");
    }

    @Test
    @DisplayName("结构化协议 + 完全非 JSON 输出 ⇒ 判为无答案（⛔ 不拿原始文本冒充结论）")
    void structuredProtocol_nonJsonYieldsNoAnswer() {
        ExternalStreamParser.Parsed parsed =
                ExternalStreamParser.parse("claude-stream-json", "这是纯文本，不是 JSONL");

        // 上层（runner）据此判 failed 并给出人话原因；若这里回退成原文，
        // 用户会看到「已完成，结论=这是纯文本，不是 JSONL」——把故障伪装成答案。
        assertThat(parsed.text()).isBlank();
    }

    @Test
    @DisplayName("脏行混入 stdout 不打断解析")
    void garbageLines_doNotAbortParsing() {
        String stdout = String.join("\n",
                "[warn] 正在加载模型...",
                "{\"type\":\"result\",\"result\":\"OK\"}",
                "{\"broken json");

        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse("claude-stream-json", stdout);

        assertThat(parsed.text()).isEqualTo("OK");
    }

    @Test
    @DisplayName("transcript 消息：reasoning / toolCalls / text 各成一条，均带 timestamp")
    void toTranscriptMessages_shapesForSubagentPanel() {
        ExternalStreamParser.Parsed parsed =
                new ExternalStreamParser.Parsed("正文", "思考", List.of(
                        new ExternalStreamParser.ToolCall("Read", "{}")));

        var messages = ExternalStreamParser.toTranscriptMessages(parsed, "2026-11-04T00:00:00+08:00");

        assertThat(messages).hasSize(3);
        assertThat(messages).extracting(m -> m.get("role")).containsOnly("assistant");
        assertThat(messages).allSatisfy(m ->
                assertThat(m.get("timestamp")).isEqualTo("2026-11-04T00:00:00+08:00"));
        assertThat(messages.get(0).get("reasoning")).isEqualTo("思考");
        assertThat(messages.get(1).get("toolCalls")).isNotNull();
        assertThat(messages.get(2).get("content")).isEqualTo("正文");
    }

    @Test
    @DisplayName("结构化协议：只收到 session 元数据事件 ⇒ 判定为「没有答案」，不拿原始 JSON 冒充")
    void structuredProtocol_noAnswerStaysBlank() {
        // 2026-11-04 真机：pi 只吐 {"type":"session",...} 就退出
        String stdout = "{\"type\":\"session\",\"version\":3,\"id\":\"01a1\",\"cwd\":\"D:/x\"}";

        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse("pi-jsonl", stdout);

        assertThat(parsed.text()).isBlank();
        assertThat(parsed.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("plain 协议：整段文本仍照常作为答案（回退只对 plain 成立）")
    void plainStillFallsBackToRaw() {
        ExternalStreamParser.Parsed parsed =
                ExternalStreamParser.parse("plain", "{\"type\":\"session\"}");

        assertThat(parsed.text()).isEqualTo("{\"type\":\"session\"}");
    }
}
