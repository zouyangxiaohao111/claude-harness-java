package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询意图 · 归一化与「是否检索」判定。
 *
 * <p><b>WHY 这些断言</b>：这是整条问答链的<b>总闸门</b>。
 * <ul>
 *   <li><b>未知值必须 fail-open 到检索</b>：源侧真实事故 —— 把模型标签原样用，
 *       任何意外值都变成「不检索」⇒ <b>答案凭空生成、既没查库也不报错</b>。
 *       这类缺陷在日志里看不出来（模型答得很流畅），只有对照知识库才发现全是编的。</li>
 *   <li><b>web_search 要受开关控制</b>：否则用户没配联网也会去调外部搜索。</li>
 * </ul>
 */
@DisplayName("[B5] 查询意图")
class KbQueryIntentTest {

    @Nested
    @DisplayName("⭐ 归一化（fail-open 到检索）")
    class Normalize {

        @Test
        @DisplayName("9 个已知值原样通过")
        void knownValues() {
            assertThat(KbQueryIntent.normalize("kb_search")).isEqualTo(KbQueryIntent.KB_SEARCH);
            assertThat(KbQueryIntent.normalize("web_search")).isEqualTo(KbQueryIntent.WEB_SEARCH);
            assertThat(KbQueryIntent.normalize("greeting")).isEqualTo(KbQueryIntent.GREETING);
            assertThat(KbQueryIntent.normalize("chitchat")).isEqualTo(KbQueryIntent.CHITCHAT);
            assertThat(KbQueryIntent.normalize("follow_up")).isEqualTo(KbQueryIntent.FOLLOW_UP);
            assertThat(KbQueryIntent.normalize("image_only")).isEqualTo(KbQueryIntent.IMAGE_ONLY);
            assertThat(KbQueryIntent.normalize("doc_only")).isEqualTo(KbQueryIntent.DOC_ONLY);
            assertThat(KbQueryIntent.normalize("summarize")).isEqualTo(KbQueryIntent.SUMMARIZE);
            assertThat(KbQueryIntent.normalize("clarification")).isEqualTo(KbQueryIntent.CLARIFICATION);
        }

        @Test
        @DisplayName("大小写与分隔符宽恕（KB-Search / kb search / Kb_Search）")
        void forgivingSeparators() {
            assertThat(KbQueryIntent.normalize("KB-Search")).isEqualTo(KbQueryIntent.KB_SEARCH);
            assertThat(KbQueryIntent.normalize("kb search")).isEqualTo(KbQueryIntent.KB_SEARCH);
            assertThat(KbQueryIntent.normalize("  Kb_Search  ")).isEqualTo(KbQueryIntent.KB_SEARCH);
            assertThat(KbQueryIntent.normalize("WEB-SEARCH")).isEqualTo(KbQueryIntent.WEB_SEARCH);
        }

        @Test
        @DisplayName("⭐⭐ null / 空白 / 未知值 ⇒ KB_SEARCH（不是「不检索」）")
        void unknownFailsOpenToRetrieval() {
            for (String bad : new String[] {null, "", "   ", "unknown_intent", "随便编的", "kbsearch"}) {
                assertThat(KbQueryIntent.normalize(bad))
                    .as("⭐ 输入 %s 必须 fail-open 到 KB_SEARCH —— "
                        + "源侧事故：未知标签 ⇒ 不检索 ⇒ 答案凭空生成且不报错", bad)
                    .isEqualTo(KbQueryIntent.KB_SEARCH);
            }
        }

        @Test
        @DisplayName("归一化结果恒非 null")
        void neverNull() {
            assertThat(KbQueryIntent.normalize(null)).isNotNull();
        }
    }

    @Nested
    @DisplayName("是否检索")
    class RetrievalGate {

        @Test
        @DisplayName("kb_search / clarification ⇒ 检索")
        void kbIntentsRetrieve() {
            assertThat(KbQueryIntent.KB_SEARCH.needsKbRetrieval()).isTrue();
            assertThat(KbQueryIntent.CLARIFICATION.needsKbRetrieval()).isTrue();
        }

        @Test
        @DisplayName("⭐ web_search 受开关控制（未开启则不走检索）")
        void webSearchRespectsFlag() {
            assertThat(KbQueryIntent.WEB_SEARCH.needsRetrieval(false))
                .as("未开联网 ⇒ 不该去调外部搜索")
                .isFalse();
            assertThat(KbQueryIntent.WEB_SEARCH.needsRetrieval(true)).isTrue();
        }

        @Test
        @DisplayName("其余 6 个意图 ⇒ 不检索")
        void nonRetrievalIntents() {
            for (KbQueryIntent i : List.of(
                KbQueryIntent.GREETING, KbQueryIntent.CHITCHAT, KbQueryIntent.FOLLOW_UP,
                KbQueryIntent.IMAGE_ONLY, KbQueryIntent.DOC_ONLY, KbQueryIntent.SUMMARIZE)) {
                assertThat(i.needsRetrieval(true)).as("意图 %s 不该触发检索", i.wire()).isFalse();
            }
        }

        @Test
        @DisplayName("wire 值与源侧 JSON 字段逐字一致")
        void wireValues() {
            assertThat(KbQueryIntent.KB_SEARCH.wire()).isEqualTo("kb_search");
            assertThat(KbQueryIntent.WEB_SEARCH.wire()).isEqualTo("web_search");
            assertThat(KbQueryIntent.FOLLOW_UP.wire()).isEqualTo("follow_up");
            assertThat(KbQueryIntent.IMAGE_ONLY.wire()).isEqualTo("image_only");
            assertThat(KbQueryIntent.DOC_ONLY.wire()).isEqualTo("doc_only");
            assertThat(KbQueryIntent.CLARIFICATION.wire()).isEqualTo("clarification");
        }

        @Test
        @DisplayName("每个意图都有中文标签（日志与前端要读）")
        void allHaveLabels() {
            for (KbQueryIntent i : KbQueryIntent.values()) {
                assertThat(i.label()).as("%s 缺中文标签", i.wire()).isNotBlank();
            }
        }
    }
}
