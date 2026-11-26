package com.nexusai.application.agent.subagent.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI 错误信号回归 · 「退出码 0 ≠ 成功」。
 *
 * <p><b>WHY</b>（2026-11-26 真机两个确定缺陷）：
 * <ul>
 *   <li>claude 未识别模型时退出码 0，stdout 只有
 *       {@code [claude-code:unrecognized_model] {...}} —— 旧解析器只认 JSON，结果为空，
 *       上层只能报「未返回任何正文」，用户完全看不到真因；</li>
 *   <li>codex 缺 env 时发 {@code {"type":"error",...}} 后 {@code turn.failed}，同样被吞。</li>
 * </ul>
 * 修法后：错误进 {@link ExternalStreamParser.Parsed#error()}；正文为空 ⇒ 上层报这条错误，
 * 正文非空 ⇒ 仍是成功（codex 成功轮次会夹带 item error，不能一刀切判失败）。
 *
 * <p>⛔ fixture 中 agy 登录 URL 的 {@code plt} 已替换为 REDACTED（真实值属凭据，不入库）；
 * 其余字段名与文案保持真机原文。
 */
@DisplayName("[external-agent] CLI 错误行捕获（claude/codex/agy）")
class ExternalCliErrorSignalTest {

    @Test
    @DisplayName("claude: [claude-code:unrecognized_model] 非 JSON 行 → 正文空 + 可读错误")
    void claudeTaggedDiagnosticBecomesError() throws Exception {
        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse(
                "claude-stream-json", readResource("/cli-errors/claude-unrecognized-model.txt"));

        assertThat(parsed.text()).isBlank();
        assertThat(parsed.error())
                .as("必须把标签行收成错误，而不是让上层报「未返回任何正文」")
                .isNotNull()
                .contains("claude-code:unrecognized_model")
                .contains("qwen4-max");
    }

    @Test
    @DisplayName("codex: type=error / turn.failed 都被捕获，正文为空")
    void codexErrorEventsBecomeError() throws Exception {
        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse(
                "codex-jsonl", readResource("/cli-errors/codex-missing-env.txt"));

        assertThat(parsed.text()).isBlank();
        assertThat(parsed.error()).isNotNull().contains("Missing environment variable: OPENAI_API_KEY");
    }

    @Test
    @DisplayName("agy: result.status=ERROR + result.error 被捕获，且不从 response 猜正文")
    void agyErrorResultBecomesError() throws Exception {
        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse(
                "jsonl", readResource("/cli-errors/agy-eligibility-error.txt"));

        assertThat(parsed.text()).isBlank();
        assertThat(parsed.error()).isNotNull().contains("Eligibility check failed");
    }

    @Test
    @DisplayName("正文非空 + 错误事件 ⇒ 仍算成功（错误只作诊断，不否定答案）")
    void errorWithBodyKeepsSuccess() {
        String stdout = String.join("\n",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"error\",\"message\":\"websocket fallback\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"答案\"}}");

        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse("codex-jsonl", stdout);

        assertThat(parsed.text()).isEqualTo("答案");
        assertThat(parsed.error()).contains("websocket fallback");
    }

    @Test
    @DisplayName("错误文案脱敏：sk-xxx / api_key=xxx / 登录 URL 流程参数")
    void errorTextIsRedacted() {
        String stdout = "{\"type\":\"error\",\"message\":\"bad key sk-abcdefghijklmnop "
                + "api_key=supersecret https://x/?plt=Tok3n&sarp=1\"}";

        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse("jsonl", stdout);

        assertThat(parsed.error())
                .contains("sk-****")
                .contains("api_key=****")
                .contains("plt=****")
                .doesNotContain("sk-abcdefghijklmnop")
                .doesNotContain("supersecret")
                .doesNotContain("Tok3n");
    }

    @Test
    @DisplayName("单段 [warn] 行不算错误信号（不误报）")
    void plainBracketWarnIsNotError() {
        ExternalStreamParser.Parsed parsed = ExternalStreamParser.parse(
                "claude-stream-json", "[warn] 正在加载模型...\n{\"type\":\"result\",\"result\":\"OK\"}");

        assertThat(parsed.text()).isEqualTo("OK");
        assertThat(parsed.error()).isNull();
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = ExternalCliErrorSignalTest.class.getResourceAsStream(path)) {
            assertThat(in).as("测试资源缺失: %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}