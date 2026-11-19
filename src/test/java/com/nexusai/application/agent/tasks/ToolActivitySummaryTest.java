package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tasks.MainSessionTaskState.ToolActivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 活动摘要生成/合并 · 对齐 CC {@code updateProgressFromMessage} + {@code summarizeRecentActivities}
 * （LocalAgentTask.tsx:145-176、collapseReadSearch.ts:1146-1181）。
 */
class ToolActivitySummaryTest {

    @Test
    @DisplayName("Read → 「正在读取 <file>」且 isRead=true")
    void readActivity() {
        ToolActivity a = ToolActivitySummary.toActivity("Read", "{\"file_path\":\"src/auth/login.ts\"}");

        assertThat(a).isNotNull();
        assertThat(a.activityDescription()).isEqualTo("正在读取 src/auth/login.ts");
        assertThat(a.isRead()).isTrue();
        assertThat(a.isSearch()).isFalse();
    }

    @Test
    @DisplayName("Grep → 「正在搜索 \"<pattern>\"」且 isSearch=true")
    void grepActivity() {
        ToolActivity a = ToolActivitySummary.toActivity("Grep", "{\"pattern\":\"login\"}");

        assertThat(a).isNotNull();
        assertThat(a.activityDescription()).isEqualTo("正在搜索 \"login\"");
        assertThat(a.isSearch()).isTrue();
        assertThat(a.isRead()).isFalse();
    }

    @Test
    @DisplayName("非法 JSON 不抛：活动仍生成，描述回退到通用文案")
    void invalidArgumentsFallsBack() {
        ToolActivity a = ToolActivitySummary.toActivity("Read", "not-json");

        assertThat(a).isNotNull();
        assertThat(a.activityDescription()).isEqualTo("正在读取文件");
    }

    @Test
    @DisplayName("append 只保留最近 5 条（超出移出最旧）")
    void appendKeepsLatestFive() {
        List<ToolActivity> acts = List.of();
        for (int i = 1; i <= 7; i++) {
            acts = ToolActivitySummary.append(acts,
                ToolActivitySummary.toActivity("Read", "{\"file_path\":\"f" + i + ".ts\"}"));
        }

        assertThat(acts).hasSize(ToolActivitySummary.MAX_RECENT);
        assertThat(acts.get(0).activityDescription()).isEqualTo("正在读取 f3.ts");
        assertThat(acts.get(4).activityDescription()).isEqualTo("正在读取 f7.ts");
    }

    @Test
    @DisplayName("尾部连续 search/read ≥2 → 合并文案（对齐 CC getSearchReadSummaryText）")
    void summarizeMergesTrailingSearchRead() {
        List<ToolActivity> acts = List.of(
            ToolActivitySummary.toActivity("Read", "{\"file_path\":\"a.ts\"}"),
            ToolActivitySummary.toActivity("Grep", "{\"pattern\":\"x\"}"),
            ToolActivitySummary.toActivity("Read", "{\"file_path\":\"b.ts\"}"));

        assertThat(ToolActivitySummary.summarize(acts))
            .isEqualTo("正在搜索 1 个模式，读取 2 个文件");
    }

    @Test
    @DisplayName("非 search/read 尾部 → 回退最近一条带描述的活动")
    void summarizeFallsBackToLatestDescription() {
        List<ToolActivity> acts = List.of(
            ToolActivitySummary.toActivity("Read", "{\"file_path\":\"a.ts\"}"),
            ToolActivitySummary.toActivity("Bash", "{\"command\":\"npm test\"}"));

        assertThat(ToolActivitySummary.summarize(acts)).isEqualTo("正在执行 npm test");
    }

    @Test
    @DisplayName("空/null → null")
    void summarizeEmpty() {
        assertThat(ToolActivitySummary.summarize(null)).isNull();
        assertThat(ToolActivitySummary.summarize(List.of())).isNull();
    }
}
