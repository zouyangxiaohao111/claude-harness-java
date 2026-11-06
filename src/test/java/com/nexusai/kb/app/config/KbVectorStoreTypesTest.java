package com.nexusai.kb.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbVectorStoreTypes} 测试 · 批次 W8。
 *
 * <p><b>⭐ 核心判据</b>：①类型集合恰为源侧 7 种（不含 postgres/sqlite）；
 * ②每种类型的必填字段与源侧 {@code GetVectorStoreTypes} 一字不差；
 * ③必填/敏感字段集合能被正确抽取（Service 靠它做校验与脱敏）。
 */
class KbVectorStoreTypesTest {

    @Nested
    @DisplayName("类型集合")
    class Types {

        @Test
        @DisplayName("恰为 7 种，顺序与源侧一致")
        void hasSevenTypesInSourceOrder() {
            assertThat(KbVectorStoreTypes.all())
                .extracting(KbVectorStoreTypes.TypeInfo::type)
                .containsExactly(
                    "elasticsearch", "qdrant", "milvus", "tencent_vectordb",
                    "weaviate", "doris", "opensearch");
        }

        @Test
        @DisplayName("⭐ postgres / sqlite 不在可注册类型里（源侧明确排除）")
        void excludesDefaultConnectionEngines() {
            assertThat(KbVectorStoreTypes.isValid("postgres")).isFalse();
            assertThat(KbVectorStoreTypes.isValid("sqlite")).isFalse();
        }

        @Test
        @DisplayName("未知类型 isValid=false，get 返回 null（不抛）")
        void unknownTypeIsInvalid() {
            assertThat(KbVectorStoreTypes.isValid("redis")).isFalse();
            assertThat(KbVectorStoreTypes.get("redis")).isNull();
            assertThat(KbVectorStoreTypes.get(null)).isNull();
        }

        @Test
        @DisplayName("类型名大小写不敏感")
        void typeLookupIsCaseInsensitive() {
            assertThat(KbVectorStoreTypes.get("ElasticSearch")).isNotNull();
            assertThat(KbVectorStoreTypes.isValid("QDRANT")).isTrue();
        }
    }

    @Nested
    @DisplayName("必填连接字段（源侧逐条对齐）")
    class RequiredFields {

        @Test
        @DisplayName("elasticsearch 只需 addr")
        void elasticsearch() {
            assertThat(KbVectorStoreTypes.requiredConnectionFields("elasticsearch"))
                .containsExactly("addr");
        }

        @Test
        @DisplayName("qdrant 只需 host")
        void qdrant() {
            assertThat(KbVectorStoreTypes.requiredConnectionFields("qdrant"))
                .containsExactly("host");
        }

        @Test
        @DisplayName("milvus 只需 addr")
        void milvus() {
            assertThat(KbVectorStoreTypes.requiredConnectionFields("milvus"))
                .containsExactly("addr");
        }

        @Test
        @DisplayName("tencent_vectordb 需要 addr + username + api_key")
        void tencentVectorDb() {
            assertThat(KbVectorStoreTypes.requiredConnectionFields("tencent_vectordb"))
                .containsExactly("addr", "username", "api_key");
        }

        @Test
        @DisplayName("weaviate 只需 host")
        void weaviate() {
            assertThat(KbVectorStoreTypes.requiredConnectionFields("weaviate"))
                .containsExactly("host");
        }

        @Test
        @DisplayName("doris 需要 addr + database")
        void doris() {
            assertThat(KbVectorStoreTypes.requiredConnectionFields("doris"))
                .containsExactly("addr", "database");
        }

        @Test
        @DisplayName("opensearch 只需 addr")
        void opensearch() {
            assertThat(KbVectorStoreTypes.requiredConnectionFields("opensearch"))
                .containsExactly("addr");
        }

        @Test
        @DisplayName("未知类型返回空列表（不抛）")
        void unknownReturnsEmpty() {
            assertThat(KbVectorStoreTypes.requiredConnectionFields("nope")).isEmpty();
        }
    }

    @Nested
    @DisplayName("敏感字段（脱敏依据）")
    class SensitiveFields {

        @Test
        @DisplayName("⭐ 每种类型的密码/密钥都在敏感集合里，且不含普通字段")
        void sensitiveSets() {
            assertThat(KbVectorStoreTypes.sensitiveFields("elasticsearch"))
                .containsExactly("password");
            assertThat(KbVectorStoreTypes.sensitiveFields("qdrant"))
                .containsExactly("api_key");
            assertThat(KbVectorStoreTypes.sensitiveFields("milvus"))
                .containsExactly("password");
            assertThat(KbVectorStoreTypes.sensitiveFields("tencent_vectordb"))
                .containsExactly("api_key");
            assertThat(KbVectorStoreTypes.sensitiveFields("weaviate"))
                .containsExactly("api_key");
            assertThat(KbVectorStoreTypes.sensitiveFields("doris"))
                .containsExactly("password");
            assertThat(KbVectorStoreTypes.sensitiveFields("opensearch"))
                .containsExactly("password");
        }

        @Test
        @DisplayName("addr / host / username 不是敏感字段（否则界面会把普通信息也打码）")
        void connectionFieldsAreNotSensitive() {
            for (String type : List.of("elasticsearch", "qdrant", "milvus", "doris")) {
                assertThat(KbVectorStoreTypes.sensitiveFields(type))
                    .as("type=%s", type)
                    .doesNotContain("addr", "host", "username", "database");
            }
        }
    }

    @Nested
    @DisplayName("字段元数据完整性")
    class FieldMetadata {

        @Test
        @DisplayName("每个字段名非空、type 属于已知三种")
        void fieldsAreWellFormed() {
            for (KbVectorStoreTypes.TypeInfo info : KbVectorStoreTypes.all()) {
                for (KbVectorStoreTypes.Field f : concat(info)) {
                    assertThat(f.name()).as("type=%s", info.type()).isNotBlank();
                    assertThat(List.of("string", "number", "boolean"))
                        .as("type=%s field=%s", info.type(), f.name())
                        .contains(f.type());
                }
            }
        }

        @Test
        @DisplayName("⭐ opensearch 的 HNSW 参数标了 immutable（建索引后改不了）")
        void opensearchHnswIsImmutable() {
            KbVectorStoreTypes.TypeInfo os = KbVectorStoreTypes.get("opensearch");
            assertThat(os.indexFields())
                .filteredOn(f -> f.name().startsWith("hnsw_"))
                .isNotEmpty()
                .allMatch(KbVectorStoreTypes.Field::immutable);
        }

        @Test
        @DisplayName("⭐ opensearch knn_engine 是封闭枚举 lucene/faiss")
        void opensearchKnnEngineEnum() {
            KbVectorStoreTypes.TypeInfo os = KbVectorStoreTypes.get("opensearch");
            KbVectorStoreTypes.Field f = os.indexFields().stream()
                .filter(x -> x.name().equals("knn_engine")).findFirst().orElseThrow();
            assertThat(f.enumValues()).containsExactly("lucene", "faiss");
            assertThat(f.defaultValue()).isEqualTo("lucene");
        }

        @Test
        @DisplayName("数值字段带上下界时要 min < max")
        void boundsAreOrdered() {
            for (KbVectorStoreTypes.TypeInfo info : KbVectorStoreTypes.all()) {
                for (KbVectorStoreTypes.Field f : concat(info)) {
                    if (f.min() != null && f.max() != null) {
                        assertThat(f.min()).as("type=%s field=%s", info.type(), f.name())
                            .isLessThan(f.max());
                    }
                }
            }
        }

        private List<KbVectorStoreTypes.Field> concat(KbVectorStoreTypes.TypeInfo info) {
            return java.util.stream.Stream
                .concat(info.connectionFields().stream(), info.indexFields().stream())
                .toList();
        }
    }
}
