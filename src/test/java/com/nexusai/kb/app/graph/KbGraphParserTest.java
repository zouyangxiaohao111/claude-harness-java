package com.nexusai.kb.app.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbGraphParser} 测试 · 批次 W7。
 *
 * <p>要点：模型输出是外部输入，这里守的是「垃圾进、还能用的东西出」。
 */
class KbGraphParserTest {

    @Nested
    @DisplayName("实体解析")
    class Entities {

        @Test
        @DisplayName("标准 JSON 数组")
        void normal() {
            List<KbGraphParser.ExtractedEntity> out = KbGraphParser.parseEntities(
                "[{\"title\":\"区生态环境局\",\"type\":\"Organization\",\"description\":\"主管部门\"}]");
            assertThat(out).hasSize(1);
            assertThat(out.get(0).name()).isEqualTo("区生态环境局");
            assertThat(out.get(0).entityType()).isEqualTo("Organization");
        }

        @Test
        @DisplayName("带 ```json 围栏")
        void fenced() {
            assertThat(KbGraphParser.parseEntities(
                "```json\n[{\"title\":\"甲\",\"type\":\"Person\",\"description\":\"描述\"}]\n```"))
                .hasSize(1);
        }

        @Test
        @DisplayName("JSON 前后有废话")
        void surroundingProse() {
            assertThat(KbGraphParser.parseEntities(
                "好的，以下是抽取结果：\n[{\"title\":\"甲\",\"type\":\"Person\",\"description\":\"描述\"}]\n完成"))
                .hasSize(1);
        }

        @Test
        @DisplayName("⭐⭐ 缺名字或缺描述 ⇒ 丢弃（无名字的实体既不能被关系引用也无法显示）")
        void dropsIncomplete() {
            assertThat(KbGraphParser.parseEntities(
                "[{\"title\":\"\",\"type\":\"Person\",\"description\":\"描述\"},"
                    + "{\"title\":\"甲\",\"type\":\"Person\",\"description\":\"\"},"
                    + "{\"title\":\"乙\",\"type\":\"Person\",\"description\":\"有描述\"}]"))
                .extracting(KbGraphParser.ExtractedEntity::name)
                .containsExactly("乙");
        }

        @Test
        @DisplayName("⭐⭐ 未知类型 ⇒ 回落 Concept，⛔ 不丢实体")
        void unknownTypeFallsBack() {
            List<KbGraphParser.ExtractedEntity> out = KbGraphParser.parseEntities(
                "[{\"title\":\"某个东西\",\"type\":\"Widget\",\"description\":\"描述\"}]");
            assertThat(out).hasSize(1);
            assertThat(out.get(0).entityType()).isEqualTo("Concept");
        }

        @Test
        @DisplayName("同名实体合并，属性取并集")
        void mergesDuplicateNames() {
            List<KbGraphParser.ExtractedEntity> out = KbGraphParser.parseEntities(
                "[{\"title\":\"甲\",\"type\":\"Person\",\"description\":\"首个描述\",\"attributes\":[\"a\"]},"
                    + "{\"title\":\"甲\",\"type\":\"Person\",\"description\":\"第二个描述\",\"attributes\":[\"b\",\"a\"]}]");
            assertThat(out).hasSize(1);
            assertThat(out.get(0).description()).isEqualTo("首个描述");
            assertThat(out.get(0).attributes()).containsExactly("a", "b");
        }

        @Test
        @DisplayName("空数组 / 无法解析 ⇒ 空列表（不抛）")
        void emptyAndBad() {
            assertThat(KbGraphParser.parseEntities("[]")).isEmpty();
            assertThat(KbGraphParser.parseEntities("没有 JSON")).isEmpty();
            assertThat(KbGraphParser.parseEntities(null)).isEmpty();
            assertThat(KbGraphParser.parseEntities("{不是数组}")).isEmpty();
        }

        @Test
        @DisplayName("接受 name 作为 title 的别名（模型偶尔用错键）")
        void acceptsNameAlias() {
            assertThat(KbGraphParser.parseEntities(
                "[{\"name\":\"甲\",\"type\":\"Person\",\"description\":\"描述\"}]"))
                .extracting(KbGraphParser.ExtractedEntity::name).containsExactly("甲");
        }
    }

    @Nested
    @DisplayName("关系解析")
    class Relations {

        private static final Set<String> KNOWN = Set.of("甲", "乙", "丙");

        @Test
        @DisplayName("标准 JSON 数组")
        void normal() {
            List<KbGraphParser.ExtractedRelation> out = KbGraphParser.parseRelations(
                "[{\"source\":\"甲\",\"target\":\"乙\",\"description\":\"属于\",\"strength\":8}]", KNOWN);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).source()).isEqualTo("甲");
            assertThat(out.get(0).strength()).isEqualTo(8);
        }

        @Test
        @DisplayName("⭐⭐ 自环关系被丢弃（力导向下会把节点拽死）")
        void dropsSelfLoop() {
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"甲\",\"target\":\"甲\",\"description\":\"自己\",\"strength\":8}]", KNOWN))
                .isEmpty();
        }

        @Test
        @DisplayName("⭐⭐ 端点不在实体列表 ⇒ 丢弃（否则变成悬空边）")
        void dropsDangling() {
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"甲\",\"target\":\"不存在\",\"strength\":8}]", KNOWN)).isEmpty();
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"不存在\",\"target\":\"乙\",\"strength\":8}]", KNOWN)).isEmpty();
        }

        @Test
        @DisplayName("缺 source / target ⇒ 丢弃")
        void dropsMissingEnds() {
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"\",\"target\":\"乙\",\"strength\":8}]", KNOWN)).isEmpty();
        }

        @Test
        @DisplayName("⭐⭐ 强度越界被夹到 5..10")
        void clampsStrength() {
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"甲\",\"target\":\"乙\",\"strength\":99}]", KNOWN).get(0).strength())
                .isEqualTo(10);
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"甲\",\"target\":\"乙\",\"strength\":0}]", KNOWN).get(0).strength())
                .isEqualTo(5);
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"甲\",\"target\":\"乙\",\"strength\":-3}]", KNOWN).get(0).strength())
                .isEqualTo(5);
        }

        @Test
        @DisplayName("⭐ 关系类型被解析出来（空类型会让星图边无法分类）")
        void parsesType() {
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"甲\",\"target\":\"乙\",\"type\":\"制定\",\"strength\":9}]", KNOWN)
                .get(0).type()).isEqualTo("制定");
        }

        @Test
        @DisplayName("提示词明写要求 type 字段（与解析端同口径）")
        void promptRequiresType() {
            assertThat(KbGraphPrompts.EXTRACT_RELATIONSHIPS).contains("source、target、type、description、strength");
            assertThat(KbGraphPrompts.EXTRACT_RELATIONSHIPS).contains("\"type\": \"制定\"");
        }

        @Test
        @DisplayName("缺 strength ⇒ 按下界 5")
        void defaultStrength() {
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"甲\",\"target\":\"乙\"}]", KNOWN).get(0).strength()).isEqualTo(5);
        }

        @Test
        @DisplayName("knownNames 为 null ⇒ 不做端点校验（便于单测与宽松场景）")
        void nullKnownSkipsCheck() {
            assertThat(KbGraphParser.parseRelations(
                "[{\"source\":\"X\",\"target\":\"Y\",\"strength\":7}]", null)).hasSize(1);
        }

        @Test
        @DisplayName("空数组 / 无法解析 ⇒ 空列表")
        void empty() {
            assertThat(KbGraphParser.parseRelations("[]", KNOWN)).isEmpty();
            assertThat(KbGraphParser.parseRelations("没有", KNOWN)).isEmpty();
            assertThat(KbGraphParser.parseRelations(null, KNOWN)).isEmpty();
        }
    }

    @Nested
    @DisplayName("类型归一")
    class NormalizeType {

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            assertThat(KbGraphParser.normalizeType("person")).isEqualTo("Person");
            assertThat(KbGraphParser.normalizeType("ORGANIZATION")).isEqualTo("Organization");
        }

        @Test
        @DisplayName("复数形式容错")
        void plural() {
            assertThat(KbGraphParser.normalizeType("Persons")).isEqualTo("Person");
            assertThat(KbGraphParser.normalizeType("Locations")).isEqualTo("Location");
        }

        @Test
        @DisplayName("下划线/空格容错")
        void separators() {
            assertThat(KbGraphParser.normalizeType(" Organization ")).isEqualTo("Organization");
        }

        @Test
        @DisplayName("空 / null / 未知 ⇒ Concept")
        void fallback() {
            assertThat(KbGraphParser.normalizeType(null)).isEqualTo("Concept");
            assertThat(KbGraphParser.normalizeType("")).isEqualTo("Concept");
            assertThat(KbGraphParser.normalizeType("Widget")).isEqualTo("Concept");
        }

        @Test
        @DisplayName("全部 11 种合法类型都能原样通过")
        void allTypes() {
            for (String t : KbGraphPrompts.ENTITY_TYPES) {
                assertThat(KbGraphParser.normalizeType(t)).isEqualTo(t);
            }
        }
    }
}
