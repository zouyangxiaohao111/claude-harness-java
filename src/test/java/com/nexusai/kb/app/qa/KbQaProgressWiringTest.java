package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接线守卫 · {@link KbQaProgress} 在 {@link KbQaPipeline} 里**真的被调用**。
 *
 * <p><b>为什么需要它</b>：{@link KbQaProgressTest} 只测工具类自己的三态文案，
 * 把 {@code KbQaPipeline} 里的四个调用点整段删掉，那 13 例**照样全绿**
 * —— 「纯函数绿 ≠ 调用点接上」是本仓栽过多次的坑（见 {@code KbImageIndexWiringTest}）。
 *
 * <p><b>为什么读源码而不是构造 pipeline</b>：{@code KbQaPipeline} 有十余个注入依赖，
 * 为验「有没有这一行」去拼整条问答链，成本远大于收益；调用点存在性用源码断言就够，
 * 行为正确性由 {@link KbQaProgressTest} 覆盖。
 *
 * <p>⚠️ 断言前必须**剥掉注释**：本仓注释里大段引用源站函数名
 * （{@code BeginRetrievalProgress} 等），不剥会让「注释提到」冒充「代码调用了」。
 */
@DisplayName("问答链工具进度 · 接线守卫")
class KbQaProgressWiringTest {

    private static final Path PIPELINE = Path.of(
        "src", "main", "java", "com", "nexusai", "kb", "app", "qa", "KbQaPipeline.java");

    /** 读源码并去掉块注释 / 行注释（只用于存在性断言）。 */
    private static String code() throws IOException {
        assertThat(PIPELINE).as("找不到 KbQaPipeline（测试工作目录应为 backend/）").exists();
        String src = Files.readString(PIPELINE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    @Test
    @DisplayName("⭐ 改写阶段：begin / end 各一处（源站 ShouldEmitQueryUnderstandProgress）")
    void queryUnderstandWired() throws IOException {
        String s = code();
        assertThat(count(s, "KbQaProgress.beginQueryUnderstand("))
            .as("改写阶段没有 begin ⇒ 时间线上看不到 query_understand 这一步")
            .isEqualTo(1);
        assertThat(count(s, "KbQaProgress.endQueryUnderstand("))
            .as("只有 begin 没有 end ⇒ 工具卡永远转圈")
            .isEqualTo(1);
        int begin = s.indexOf("KbQaProgress.beginQueryUnderstand(");
        int call = s.indexOf("understand(req, question, history, params, memoryBackground)");
        assertThat(call).isGreaterThan(0);
        assertThat(begin).as("beginQueryUnderstand 必须在 understand(...) 之前").isLessThan(call);
    }

    @Test
    @DisplayName("⭐ 检索窗口：begin 一处、end 两处（正常收口 + 异常收口）")
    void retrievalWired() throws IOException {
        String s = code();
        assertThat(count(s, "KbQaProgress.beginRetrieval("))
            .as("检索窗口没有 begin ⇒ 前端看不到 knowledge_search 工具卡")
            .isEqualTo(1);
        assertThat(count(s, "KbQaProgress.endRetrieval("))
            .as("end 必须两处：⑩ 上下文就绪处收口 + catch 里兜底 —— "
                + "少了 catch 那处，检索段抛异常时工具卡会一直转圈"
                + "（源站 ShouldCloseRetrievalProgress 专门为 stageErr 写了这条）")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("⭐⭐ end 的 count 必须是「进上下文的条数」，⛔ 不能报召回条数")
    void countIsContextNotHits() throws IOException {
        String s = code();
        int i = s.indexOf("KbQaProgress.endRetrieval(retrievalStage,");
        assertThat(i).as("在「上下文就绪」处找不到收口调用").isGreaterThan(0);
        String block = s.substring(i, Math.min(i + 400, s.length()));
        /* ⚠️ 判据必须钉在**第 3 个实参**上：只断言「block 里出现了 rendered.candidateCount()」
           会被第 2 个实参（searchNothing 判据里也引用了它）满足 ⇒ 把 count 换成 hits.size()
           仍然全绿（实测假绿）。这里用「实参相邻形态」锁定位置。 */
        assertThat(block)
            .as("count 必须是 rendered.candidateCount()：报召回条数会让时间线承诺"
                + "答案里根本没有的结果")
            .contains("rendered.candidateCount(), hits.size(), null);");
        assertThat(block)
            .as("searchNothing 的判据必须是「召回过东西但一条都没进上下文」")
            .contains("!hits.isEmpty() && rendered.candidateCount() == 0");
    }

    @Test
    @DisplayName("⭐ 检索来源按配置判定（源站 retrievalSearchSource）")
    void searchSourceFromConfig() throws IOException {
        String s = code();
        int i = s.indexOf("private static String retrievalSearchSource(");
        assertThat(i).as("缺 retrievalSearchSource（search_source 会写死成一个值）").isGreaterThan(0);
        String body = s.substring(i, Math.min(i + 600, s.length()));
        assertThat(body).contains("KbQaProgress.SOURCE_MIXED");
        assertThat(body).contains("KbQaProgress.SOURCE_WEB");
        assertThat(body).contains("KbQaProgress.SOURCE_KNOWLEDGE");
    }
}
