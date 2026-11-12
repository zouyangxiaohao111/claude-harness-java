package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * XMind 解析器意图测试。
 *
 * <p><b>WHY 这些断言（CLAUDE.md 规则九）</b>：
 * <ol>
 *   <li><b>两种容器格式都要出层级</b>：XMind 8 用 {@code content.xml}、Zen/2020+ 用
 *       {@code content.json}；只支持一种会让一半用户「上传成功、解析失败」。两种格式对同一
 *       思维导图必须产出同样的 Markdown。</li>
 *   <li><b>层级必须逐级递进</b>：这是本次新增的核心能力 —— 若递归断了（例如只渲染一层），
 *       文档仍然「解析成功」、不报错，但检索「1.1 需求调研」时看不到它属于「一、立项」。
 *       故用 {@code isEqualTo} 钉死整份正文，而不是只查包含关系。</li>
 *   <li><b>坏文件必须报错</b>：⛔ 不能返回空文本 —— 空文本会被当「解析成功但无内容」入库，
 *       用户永远查不到且没有任何错误提示。</li>
 * </ol>
 */
@DisplayName("[B2b] XMind 解析器（.xmind → Markdown 大纲）")
class KbXmindParserTest {

    /** 3 层思维导图（画布 → 根主题 → 分支 → 叶子），两种格式共用同一份内容。 */
    private static final String JSON_FIXTURE = """
        [
          {
            "id": "sheet-1",
            "title": "画布 1",
            "rootTopic": {
              "id": "root",
              "title": "项目计划",
              "children": {
                "attached": [
                  {
                    "id": "t1",
                    "title": "一、立项",
                    "children": {
                      "attached": [
                        {"id": "t1-1", "title": "1.1 需求调研"}
                      ]
                    }
                  },
                  {"id": "t2", "title": "二、实施"}
                ]
              }
            }
          }
        ]
        """;

    /** XMind 8 的 content.xml（带 {@code urn:xmind} 命名空间与 {@code <topics type>} 包装）。 */
    private static final String XML_FIXTURE = """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <xmap-content xmlns="urn:xmind:xmap:xmlns:content:2.0" version="2.0">
          <sheet id="sheet-1">
            <title>画布 1</title>
            <topic id="root">
              <title>项目计划</title>
              <children>
                <topics type="attached">
                  <topic id="t1">
                    <title>一、立项</title>
                    <children>
                      <topics type="attached">
                        <topic id="t1-1">
                          <title>1.1 需求调研</title>
                        </topic>
                      </topics>
                    </children>
                  </topic>
                  <topic id="t2">
                    <title>二、实施</title>
                  </topic>
                </topics>
              </children>
            </topic>
          </sheet>
        </xmap-content>
        """;

    /** 两种格式的共同期望：画布 {@code #}、根主题 {@code ##}、分支 {@code ###}、叶子 {@code ####}。 */
    private static final String EXPECTED_OUTLINE = """
        # 画布 1

        ## 项目计划
        ### 一、立项
        #### 1.1 需求调研
        ### 二、实施""";

    /** JSON（Zen/2020+）单行备注：{@code notes.plain.content}，备注行 = 2 空格缩进 + {@code > }。 */
    private static final String JSON_NOTE_FIXTURE = """
        [
          {
            "title": "画布 1",
            "rootTopic": {
              "title": "项目计划",
              "notes": {"plain": {"content": "  这是根主题备注  "}},
              "children": {
                "attached": [
                  {"id": "t1", "title": "一、立项"}
                ]
              }
            }
          }
        ]
        """;

    /**
     * XMind 8 的 content.xml：备注写成 {@code <notes><plain>}，内容是多行（含一个空行）。
     *
     * <p>{@code <notes>} 里另带 {@code <html>} 富文本分支 —— 源站只读 {@code <plain>}，
     * 故期望正文里不能出现「富文本分支不该被读到」。
     */
    private static final String XML_NOTE_FIXTURE = """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <xmap-content xmlns="urn:xmind:xmap:xmlns:content:2.0" version="2.0">
          <sheet id="sheet-1">
            <title>画布 1</title>
            <topic id="root">
              <title>项目计划</title>
              <children>
                <topics type="attached">
                  <topic id="t1">
                    <title>一、立项</title>
                    <notes>
                      <plain>第一行备注
        第二行备注

        第四行备注</plain>
                      <html xmlns:xhtml="http://www.w3.org/1999/xhtml">
                        <xhtml:html><xhtml:body><xhtml:p>富文本分支不该被读到</xhtml:p></xhtml:body></xhtml:html>
                      </html>
                    </notes>
                    <children>
                      <topics type="attached">
                        <topic id="t1-1">
                          <title>1.1 需求调研</title>
                        </topic>
                      </topics>
                    </children>
                  </topic>
                </topics>
              </children>
            </topic>
          </sheet>
        </xmap-content>
        """;

    private final KbXmindParser parser = new KbXmindParser();

    @Nested
    @DisplayName("类型识别")
    class Supports {

        @Test
        @DisplayName("只认 .xmind（大小写不敏感）+ 带 xmind 的 MIME，不抢普通 zip")
        void recognisesXmindOnly() {
            assertThat(parser.supports("项目计划.xmind", null)).isTrue();
            assertThat(parser.supports("项目计划.XMIND", "application/octet-stream")).isTrue();
            assertThat(parser.supports("noext", "application/x-xmind")).isTrue();

            assertThat(parser.supports("a.zip", "application/zip")).isFalse();
            assertThat(parser.supports("a.txt", "text/plain")).isFalse();
            assertThat(parser.supports("a.docx", null)).isFalse();
        }

        @Test
        @DisplayName("能力声明与展示名：只报 xmind、文案为「内置 XMind 解析」、@Order(35)")
        void declaresCapability() {
            assertThat(parser.supportedExtensions()).containsExactly("xmind");
            assertThat(parser.displayName()).isEqualTo("内置 XMind 解析");
            assertThat(parser.name()).isEqualTo("KbXmindParser");
            Order order = KbXmindParser.class.getAnnotation(Order.class);
            assertThat(order).isNotNull();
            assertThat(order.value()).isEqualTo(35);
        }
    }

    @Nested
    @DisplayName("解析")
    class Parsing {

        @Test
        @DisplayName("content.json（Zen/2020+）：3 层 → #/##/###/#### 层级与顺序都正确")
        void parsesJsonThreeLevels(@TempDir Path dir) throws Exception {
            Path file = writeZip(dir, "项目计划.xmind", "content.json", JSON_FIXTURE);

            KbParsedDocument doc = parser.parse(file, KbParseContext.defaults());

            assertThat(doc.text()).isEqualTo(EXPECTED_OUTLINE);
            assertThat(doc.images()).isEmpty();
            assertThat(doc.sourceBlocks()).as("XMind 无法映射回原字节区间，⛔ 不伪造定位").isEmpty();
            assertThat(doc.metadata())
                .containsEntry(KbParsedDocument.META_PARSER, "KbXmindParser")
                .containsEntry(KbParsedDocument.META_FILE_NAME, "项目计划.xmind")
                .containsEntry(KbXmindParser.META_CONTENT_FORMAT, "json")
                .containsEntry(KbXmindParser.META_SHEET_COUNT, "1")
                .containsEntry(KbXmindParser.META_TOPIC_COUNT, "4")
                .containsEntry(KbXmindParser.META_MAX_LEVEL, "4")
                .containsEntry(KbParsedDocument.META_SOURCE_BLOCKS, "0");
        }

        @Test
        @DisplayName("content.xml（XMind 8）：同一张图产出与 json 完全一致的正文")
        void parsesXmlThreeLevels(@TempDir Path dir) throws Exception {
            Path file = writeZip(dir, "项目计划8.xmind", "content.xml", XML_FIXTURE);

            KbParsedDocument doc = parser.parse(file, KbParseContext.defaults());

            assertThat(doc.text()).isEqualTo(EXPECTED_OUTLINE);
            assertThat(doc.metadata())
                .containsEntry(KbXmindParser.META_CONTENT_FORMAT, "xml")
                .containsEntry(KbXmindParser.META_TOPIC_COUNT, "4");
        }

        @Test
        @DisplayName("超过 6 级：第 7 级起用缩进列表，⛔ 不产出 #######")
        void deepLevelsFallBackToIndentedList(@TempDir Path dir) throws Exception {
            Path file = writeZip(dir, "深层级.xmind", "content.json", deepJsonFixture(7));

            String text = parser.parse(file, KbParseContext.defaults()).text();

            assertThat(text).contains("###### 层级5");
            assertThat(text).contains("\n  - 层级6");
            assertThat(text).contains("\n    - 层级7");
            assertThat(text).doesNotContain("#######");
        }

        @Test
        @DisplayName("无标题主题不产出空标题行，子主题顶到同一级")
        void blankTopicTitleIsSkipped(@TempDir Path dir) throws Exception {
            String json = """
                [{"title": "画布 1", "rootTopic": {
                  "title": "",
                  "children": {"attached": [
                    {"title": "一、立项"},
                    {"title": "二、实施"}
                  ]}
                }}]
                """;
            Path file = writeZip(dir, "空根标题.xmind", "content.json", json);

            String text = parser.parse(file, KbParseContext.defaults()).text();

            assertThat(text).isEqualTo("# 画布 1\n\n## 一、立项\n## 二、实施");
        }

        @Test
        @DisplayName("JSON 单行备注 ⇒ 标题下一行「2 空格 + > 」，去首尾空白，在子主题之前")
        void rendersSingleLineNoteFromJson(@TempDir Path dir) throws Exception {
            Path file = writeZip(dir, "备注.xmind", "content.json", JSON_NOTE_FIXTURE);

            String text = parser.parse(file, KbParseContext.defaults()).text();

            assertThat(text).isEqualTo("""
                # 画布 1

                ## 项目计划
                  > 这是根主题备注
                ### 一、立项""");
        }

        @Test
        @DisplayName("XML 多行备注 ⇒ 逐行加「> 」、空行只写「>」、只读 plain 不读 html")
        void rendersMultiLineNoteFromXml(@TempDir Path dir) throws Exception {
            Path file = writeZip(dir, "备注8.xmind", "content.xml", XML_NOTE_FIXTURE);

            String text = parser.parse(file, KbParseContext.defaults()).text();

            assertThat(text).isEqualTo("""
                # 画布 1

                ## 项目计划
                ### 一、立项
                  > 第一行备注
                  > 第二行备注
                  >
                  > 第四行备注
                #### 1.1 需求调研""");
            assertThat(text).as("源站只读 <plain>，不带 <html> 富文本分支")
                .doesNotContain("富文本分支不该被读到");
        }

        @Test
        @DisplayName("notes 缺失 / 纯空白 ⇒ 一个字都不输出（⛔ 不留空「> 」），正文与改前一致")
        void blankOrMissingNotesProduceNothing(@TempDir Path dir) throws Exception {
            String json = """
                [{"title": "画布 1", "rootTopic": {
                  "title": "项目计划",
                  "notes": {"plain": {"content": "   \\n\\n\\t  "}},
                  "children": {"attached": [
                    {"title": "一、立项",
                     "notes": {"plain": {"content": "   "}},
                     "children": {"attached": [{"title": "1.1 需求调研"}]}},
                    {"title": "二、实施"}
                  ]}
                }}]
                """;
            Path file = writeZip(dir, "无备注.xmind", "content.json", json);

            String text = parser.parse(file, KbParseContext.defaults()).text();

            assertThat(text).isEqualTo(EXPECTED_OUTLINE);
            assertThat(text).as("纯空白备注不能产出一行孤零零的「>」").doesNotContain(">");
        }

        @Test
        @DisplayName("无标题主题的备注不输出（源站备注块嵌在 if topic.title 内）")
        void noteOnUntitledTopicIsDropped(@TempDir Path dir) throws Exception {
            String json = """
                [{"title": "画布 1", "rootTopic": {
                  "title": "",
                  "notes": {"plain": {"content": "不该出现"}},
                  "children": {"attached": [
                    {"title": "一、立项", "notes": {"plain": {"content": "该出现"}}}
                  ]}
                }}]
                """;
            Path file = writeZip(dir, "空根备注.xmind", "content.json", json);

            String text = parser.parse(file, KbParseContext.defaults()).text();

            assertThat(text).isEqualTo("# 画布 1\n\n## 一、立项\n  > 该出现");
        }
    }

    @Nested
    @DisplayName("失败语义：⛔ 不返回空文本")
    class Failures {

        @Test
        @DisplayName("坏 zip / 0 字节文件 ⇒ KbParseException")
        void corruptOrEmptyFileFails(@TempDir Path dir) throws Exception {
            Path garbage = dir.resolve("坏文件.xmind");
            Files.writeString(garbage, "这不是一个 zip 压缩包");
            assertThatThrownBy(() -> parser.parse(garbage, KbParseContext.defaults()))
                .isInstanceOf(KbParseException.class)
                .hasMessageContaining("XMind 压缩包");

            Path empty = dir.resolve("空文件.xmind");
            Files.createFile(empty);
            assertThatThrownBy(() -> parser.parse(empty, KbParseContext.defaults()))
                .isInstanceOf(KbParseException.class)
                .hasMessageContaining("XMind 压缩包");
        }

        @Test
        @DisplayName("zip 里缺 content.json / content.xml ⇒ 说明原因")
        void missingContentEntryFails(@TempDir Path dir) throws Exception {
            Path file = writeZip(dir, "不是xmind.xmind", "manifest.json", "{}");

            assertThatThrownBy(() -> parser.parse(file, KbParseContext.defaults()))
                .isInstanceOf(KbParseException.class)
                .hasMessageContaining("content.json");
        }

        @Test
        @DisplayName("只有空白画布（无任何主题）⇒ 报「不含任何可解析主题」而不是空文本")
        void emptyCanvasFails(@TempDir Path dir) throws Exception {
            String json = """
                [{"title": "空画布", "rootTopic": {"title": "", "children": {"attached": []}}}]
                """;
            Path file = writeZip(dir, "空画布.xmind", "content.json", json);

            assertThatThrownBy(() -> parser.parse(file, KbParseContext.defaults()))
                .isInstanceOf(KbParseException.class)
                .hasMessageContaining("不含任何可解析主题");
        }
    }

    //__HELPERS__

    /** 把一个文本条目打成 zip（模拟 .xmind 容器）。 */
    private static Path writeZip(Path dir, String fileName, String entryName, String content)
        throws IOException {
        Path file = dir.resolve(fileName);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    /**
     * 造一条 {@code levels} 层深的单链思维导图（层级1 → 层级2 → …）。
     *
     * <p>用于验证「超过 6 级改用缩进列表」——手写 7 层 JSON 太容易括号不配对。
     */
    private static String deepJsonFixture(int levels) {
        StringBuilder topic = new StringBuilder("{\"title\":\"层级1\"");
        for (int level = 2; level <= levels; level++) {
            topic.append(",\"children\":{\"attached\":[{\"title\":\"层级").append(level).append("\"");
        }
        for (int level = 2; level <= levels; level++) {
            topic.append("}]}");
        }
        topic.append("}");
        return "[{\"title\":\"画布 1\",\"rootTopic\":" + topic + "}]";
    }
}
