package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.attachment.PdfSubagentParser.PageSummary;
import com.nexusai.application.agent.attachment.PdfSubagentParser.Route;
import com.nexusai.application.agent.attachment.PdfSubagentParser.RouteDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PdfSubagentParser R2 路由 + 结构化解析测试（RED→GREEN，纯静态逻辑无 Spring 上下文）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图 · R2 三态分页路由）：
 * <ol>
 *   <li><b>路由边界（20/21/100/101/null）</b>——≤20 直传（R1）、&gt;20≤100 子代理、&gt;100 pdf_reference、
 *       无法确定页数 → ERROR。若边界错位（如 21 走 DIRECT），&gt;20 页 PDF 会被主循环 document 块
 *       直传击穿单次 20 页上限（ReadFileTool pages 最大 20，apiLimits.ts:77）。</li>
 *   <li><b>pdf_reference 文本契约</b>——任务约定 {@code 'PDF {path} 共 N 页，请用 Read 工具 + pages 参数读取'}，
 *       主代理读该文本自行分页读取；格式错位会误导主代理走全量注入击穿上下文。</li>
 *   <li><b>每页结构化解析（PAGE N: …）</b>——子代理输出经确定性正则提取 {@code {page, summary}}，
 *       OVERVIEW/非法行忽略；解析失败 → 空列表（回退 summaryText）。这是「结果一次返回主代理」的
 *       数据形状保障。</li>
 * </ol>
 */
class PdfSubagentParserTest {

    @Test
    @DisplayName("decideRoute 边界: 20→DIRECT / 21→SUBAGENT / 100→SUBAGENT / 101→REFERENCE / null→ERROR · R2 三态路由")
    void decideRoute_boundaries() {
        assertThat(PdfSubagentParser.decideRoute(20).route()).isEqualTo(Route.DIRECT);
        assertThat(PdfSubagentParser.decideRoute(21).route()).isEqualTo(Route.SUBAGENT);
        assertThat(PdfSubagentParser.decideRoute(50).route()).isEqualTo(Route.SUBAGENT);
        assertThat(PdfSubagentParser.decideRoute(100).route()).isEqualTo(Route.SUBAGENT);
        assertThat(PdfSubagentParser.decideRoute(101).route()).isEqualTo(Route.REFERENCE);
        assertThat(PdfSubagentParser.decideRoute(1000).route()).isEqualTo(Route.REFERENCE);
        assertThat(PdfSubagentParser.decideRoute(null).route()).isEqualTo(Route.ERROR);
    }

    @Test
    @DisplayName("decideRoute 页数透传 + REFERENCE 无文本（由 decide(pdfPath) 补全）· R2 路由产物")
    void decideRoute_carriesPageCount() {
        RouteDecision d = PdfSubagentParser.decideRoute(42);
        assertThat(d.route()).isEqualTo(Route.SUBAGENT);
        assertThat(d.pageCount()).isEqualTo(42);
        assertThat(d.referenceText()).isNull();

        RouteDecision ref = PdfSubagentParser.decideRoute(101);
        assertThat(ref.pageCount()).isEqualTo(101);
    }

    @Test
    @DisplayName("buildPdfReferenceText 文本契约: 'PDF {path} 共 N 页，请用 Read 工具 + pages 参数读取' · R2 >100 页 pdf_reference")
    void buildPdfReferenceText_contract() {
        String text = PdfSubagentParser.buildPdfReferenceText("/tmp/report.pdf", 120);
        assertThat(text).isEqualTo("PDF /tmp/report.pdf 共 120 页，请用 Read 工具 + pages 参数读取");
    }

    @Test
    @DisplayName("parsePages 提取每页 {page,summary}，OVERVIEW/非法行忽略 · R2 结构化结果一次返回")
    void parsePages_extractsStructuredSummaries() {
        String out = "PAGE 1: 引言与背景介绍\n"
            + "PAGE 2: 方法论章节\n"
            + "OVERVIEW: 本文档介绍某系统\n"
            + "PAGE x: 非法页号行\n"
            + "PAGE 3: 实验结果分析\n";
        List<PageSummary> pages = PdfSubagentParser.parsePages(out);

        assertThat(pages).hasSize(3);
        assertThat(pages.get(0)).isEqualTo(new PageSummary(1, "引言与背景介绍"));
        assertThat(pages.get(1)).isEqualTo(new PageSummary(2, "方法论章节"));
        assertThat(pages.get(2)).isEqualTo(new PageSummary(3, "实验结果分析"));
    }

    @Test
    @DisplayName("parsePages 空/null/无 PAGE 行 → 空列表（调用方回退 summaryText）· R2 解析容错")
    void parsePages_toleratesMalformed() {
        assertThat(PdfSubagentParser.parsePages(null)).isEmpty();
        assertThat(PdfSubagentParser.parsePages("")).isEmpty();
        assertThat(PdfSubagentParser.parsePages("  \n  ")).isEmpty();
        assertThat(PdfSubagentParser.parsePages("自由文本无结构化行")).isEmpty();
    }
}
