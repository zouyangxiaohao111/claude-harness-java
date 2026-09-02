package com.nexusai.application.agent.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * remote_agent SDK log 解析测试 · 对齐 CC RemoteAgentTask/RemoteAgentTask.tsx:208-319。
 *
 * <p><b>WHY（意图验证，规则九）</b>: 远程会话 log 是产物提取的唯一来源 —
 * extractPlanFromLog 供 ultraplan 失败通知（:208-218），extractReviewFromLog 供
 * remote-review 完成通知（:254-283），extractReviewTagFromLog 供 delta 扫描判完成
 * （:295-319）。测试锁死 CC 语义，防止"解析跑偏但测试仍绿"的假实现。
 */
@DisplayName("[W6-04] RemoteAgentLogParser SDK log 解析（对齐 CC RemoteAgentTask.tsx:208-319）")
class RemoteAgentLogParserTest {

    /** bughunter 路径：run_hunt.sh 是 SessionStart hook，echo 落地为 hook_progress stdout. */
    private static Map<String, Object> hookProgress(String stdout) {
        return Map.of("type", "system", "subtype", "hook_progress", "stdout", stdout);
    }

    private static Map<String, Object> assistantEvent(String text) {
        return Map.of("type", "assistant",
            "message", Map.of("content", List.of(Map.of("type", "text", "text", text))));
    }

    // ── extractPlanFromLog（CC :208-218）──

    @Test
    @DisplayName("extractPlanFromLog: 反向扫 assistant 消息，首个 <ultraplan> 内容（CC :208-218）")
    void extractPlanFromLogReverseScansAssistant() {
        // WHY: plan 只在消息末尾出现一次，反向扫描第一个命中即短路。
        List<Map<String, Object>> log = List.of(
            assistantEvent("思考中..."),
            assistantEvent("第一版 <ultraplan>旧计划</ultraplan>"),
            assistantEvent("最终 <ultraplan>\n新计划\n</ultraplan> 完成"));

        assertThat(RemoteAgentLogParser.extractPlanFromLog(log))
            .isEqualTo("新计划");
    }

    @Test
    @DisplayName("extractPlanFromLog: 无 <ultraplan> tag → null（CC :208-218）")
    void extractPlanFromLogNoTagReturnsNull() {
        List<Map<String, Object>> log = List.of(
            assistantEvent("没有任何 tag 的文本"));

        assertThat(RemoteAgentLogParser.extractPlanFromLog(log)).isNull();
    }

    // ── extractReviewFromLog（CC :254-283）──

    @Test
    @DisplayName("extractReviewFromLog: hook_progress 优先（bughunter 主路径，CC :258-265）")
    void extractReviewFromHookProgressFirst() {
        // WHY: bughunter 模式下 Claude 从不说话（零 assistant 消息），
        //      只有 hook stdout 里的一次 <remote-review> echo — 必须从 hook 扫描取。
        List<Map<String, Object>> log = List.of(
            hookProgress("运行 hunt..."),
            hookProgress("<remote-review>发现 3 个 bug</remote-review>"));

        assertThat(RemoteAgentLogParser.extractReviewFromLog(log))
            .isEqualTo("发现 3 个 bug");
    }

    @Test
    @DisplayName("extractReviewFromLog: 无 tag 时兜底全 assistant 文本（prompt 模式，CC :280-282）")
    void extractReviewFallsBackToAllAssistantText() {
        // WHY: prompt 模式 dev/fallback — review 内容可能未包 tag，
        //      但仍须把 assistant 全文交付给本地模型。
        List<Map<String, Object>> log = List.of(
            assistantEvent("第一段分析"),
            assistantEvent("第二段结论"));

        assertThat(RemoteAgentLogParser.extractReviewFromLog(log))
            .isEqualTo("第一段分析\n第二段结论");
    }

    // ── extractReviewTagFromLog（CC :295-319）──

    @Test
    @DisplayName("extractReviewTagFromLog: 无 tag 时不回退 assistant 文本 → null（CC :295-319）")
    void extractReviewTagNoFallback() {
        // WHY: delta 扫描的生死线 — prompt 模式早期未打 tag 的 assistant 消息
        //      （如 "I'm analyzing the diff..."）若触发 fallback，会在真 review 输出
        //      到达前提前完成 review。
        List<Map<String, Object>> log = List.of(
            assistantEvent("I'm analyzing the diff..."),
            hookProgress("progress 50%"));

        assertThat(RemoteAgentLogParser.extractReviewTagFromLog(log)).isNull();
    }

    @Test
    @DisplayName("extractReviewTagFromLog: 找到 tag 才返回（CC :295-319）")
    void extractReviewTagReturnsTagWhenPresent() {
        List<Map<String, Object>> log = List.of(
            assistantEvent("I'm analyzing the diff..."),
            hookProgress("<remote-review>真 review</remote-review>"));

        assertThat(RemoteAgentLogParser.extractReviewTagFromLog(log))
            .isEqualTo("真 review");
    }

    @Test
    @DisplayName("extractReviewTagFromLog: hook stdout 拼接兜底（tag 跨事件截断，CC :309-317）")
    void extractReviewTagConcatSplitTag() {
        // WHY: 大 JSON payload 在 pipe buffer 满时可能拆两个事件 —
        //      per-message 扫描漏掉拆开的 tag，须拼接 hook stdout 再提取。
        List<Map<String, Object>> log = List.of(
            hookProgress("<remote-review>大 payload 前半"),
            hookProgress("后半</remote-review>"));

        assertThat(RemoteAgentLogParser.extractReviewTagFromLog(log))
            .isEqualTo("大 payload 前半后半");
    }
}
