package com.nexusai.application.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 产物/来源抽取解析器单测（纯 JUnit，无 Spring 上下文）。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：模型输出是<b>不可控输入</b>——带 ```json 围栏、
 * 前后夹解释性废话、标题缺失都是线上常态。解析层一旦「猜」错，脏数据会直接进
 * {@code session_artifact} 并被前端当作可点击的交付物展示（死链 / 假产物）。
 * 因此这里钉死三条意图：
 * <ul>
 *   <li><b>能容忍壳</b>：围栏/废话不影响解析（否则合法结果被整轮丢弃，功能等于不存在）；</li>
 *   <li><b>非法不猜</b>：解析失败返回空而不是抛异常或部分拼接（规则十二：不掩盖不确定性）；</li>
 *   <li><b>无标题即丢弃</b>：title 是 upsert 键（V205 唯一索引）且是展示名，空标题条目无法落库也不会被前端认出。</li>
 * </ul>
 *
 * <p>⛔ 不启动 Spring（@SpringBootTest 会迁移用户真库 —— 仓库铁律）。
 */
class SessionArtifactParserTest {

    @Test
    @DisplayName("正常 JSON：两桶按 kind 分开解析，字段原样透出")
    void parsesPlainJson() {
        String raw = """
                {"deliverables":[{"title":"季度分析报告","path":"D:/out/report.html","summary":"最终报告"}],
                 "sources":[{"title":"官方文档","url":"https://example.com/docs","summary":"参考"}]}
                """;

        SessionArtifactParser.ParsedExtraction parsed = SessionArtifactParser.parse(raw);

        assertThat(parsed.deliverables()).hasSize(1);
        assertThat(parsed.deliverables().get(0).title()).isEqualTo("季度分析报告");
        assertThat(parsed.deliverables().get(0).path()).isEqualTo("D:/out/report.html");
        assertThat(parsed.deliverables().get(0).summary()).isEqualTo("最终报告");
        assertThat(parsed.sources()).hasSize(1);
        assertThat(parsed.sources().get(0).url()).isEqualTo("https://example.com/docs");
    }

    @Test
    @DisplayName("```json 围栏：剥壳后照常解析（否则合法结果会被整轮丢弃）")
    void parsesFencedJson() {
        String raw = """
                ```json
                {"deliverables":[{"title":"设计稿","path":"/tmp/design.html","summary":null}],
                 "sources":[]}
                ```
                """;

        SessionArtifactParser.ParsedExtraction parsed = SessionArtifactParser.parse(raw);

        assertThat(parsed.deliverables()).hasSize(1);
        assertThat(parsed.deliverables().get(0).title()).isEqualTo("设计稿");
        assertThat(parsed.deliverables().get(0).path()).isEqualTo("/tmp/design.html");
        assertThat(parsed.deliverables().get(0).summary()).isNull();
        assertThat(parsed.sources()).isEmpty();
    }

    @Test
    @DisplayName("前后有废话：取第一个 { 到最后一个 } 之间的 JSON")
    void parsesJsonWithProseAround() {
        String raw = """
                好的，我分析完了。结果如下：
                {"deliverables":[],"sources":[{"title":"知识库文档","url":"https://kb.example.com/a"}]}
                以上仅供参考。
                """;

        SessionArtifactParser.ParsedExtraction parsed = SessionArtifactParser.parse(raw);

        assertThat(parsed.deliverables()).isEmpty();
        assertThat(parsed.sources()).hasSize(1);
        assertThat(parsed.sources().get(0).title()).isEqualTo("知识库文档");
    }

    @Test
    @DisplayName("非法 JSON：返回空结果，不抛异常、不猜测（规则十二）")
    void returnsEmptyOnInvalidJson() {
        SessionArtifactParser.ParsedExtraction parsed =
                SessionArtifactParser.parse("这不是 JSON，{ 而且花括号也没闭合");

        assertThat(parsed.deliverables()).isEmpty();
        assertThat(parsed.sources()).isEmpty();
    }

    @Test
    @DisplayName("缺 title：该条丢弃，同批其它条目照常保留")
    void dropsItemsWithoutTitle() {
        String raw = """
                {"deliverables":[
                    {"title":"   ","path":"/tmp/a.html"},
                    {"path":"/tmp/b.html","summary":"没有标题"},
                    {"title":"有效产物","path":"/tmp/c.html"}
                 ],
                 "sources":[{"url":"https://example.com/no-title"}]}
                """;

        SessionArtifactParser.ParsedExtraction parsed = SessionArtifactParser.parse(raw);

        assertThat(parsed.deliverables()).hasSize(1);
        assertThat(parsed.deliverables().get(0).title()).isEqualTo("有效产物");
        assertThat(parsed.sources()).isEmpty();
    }

    @Test
    @DisplayName("空输入 / 非对象顶层：返回空结果")
    void returnsEmptyOnBlankOrNonObject() {
        assertThat(SessionArtifactParser.parse(null).deliverables()).isEmpty();
        assertThat(SessionArtifactParser.parse("   ").deliverables()).isEmpty();
        assertThat(SessionArtifactParser.parse("[1,2,3]").sources()).isEmpty();
    }
}
