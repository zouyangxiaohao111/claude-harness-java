package com.nexusai.kb.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbStorageBackendTypes} 测试 · 批次 W8。
 *
 * <p><b>⭐ 核心判据</b>：①只做 local / minio / cos 三种（用户裁定范围）；
 * ②local 恒「本机可用」，minio/cos 恒「本机不可用」——
 * 这个布尔会直接显示给用户，写反了就是「界面上说能用，一点就报错」。
 */
class KbStorageBackendTypesTest {

    @Nested
    @DisplayName("类型集合")
    class Types {

        @Test
        @DisplayName("恰为 local / minio / cos（用户裁定范围）")
        void hasThreeProviders() {
            assertThat(KbStorageBackendTypes.all())
                .extracting(KbStorageBackendTypes.TypeInfo::provider)
                .containsExactly("local", "minio", "cos");
        }

        @Test
        @DisplayName("未知 provider 非法")
        void unknownIsInvalid() {
            assertThat(KbStorageBackendTypes.isValid("s3")).isFalse();
            assertThat(KbStorageBackendTypes.isValid(null)).isFalse();
            assertThat(KbStorageBackendTypes.get("nope")).isNull();
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            assertThat(KbStorageBackendTypes.isValid("MinIO")).isTrue();
            assertThat(KbStorageBackendTypes.get("COS")).isNotNull();
        }
    }

    @Nested
    @DisplayName("⭐ 本机可用性（直接显示给用户）")
    class Availability {

        @Test
        @DisplayName("local 恒可用 —— 它是「没有 MinIO 就本地」的兜底")
        void localIsAlwaysAvailable() {
            assertThat(KbStorageBackendTypes.get("local").locallyAvailable()).isTrue();
        }

        @Test
        @DisplayName("minio / cos 本机不可用（无 SDK）")
        void objectStoresAreNotAvailable() {
            assertThat(KbStorageBackendTypes.get("minio").locallyAvailable()).isFalse();
            assertThat(KbStorageBackendTypes.get("cos").locallyAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("字段元数据")
    class Fields {

        @Test
        @DisplayName("local 没有必填字段（path_prefix 可空）")
        void localHasNoRequiredFields() {
            assertThat(KbStorageBackendTypes.requiredFields("local")).isEmpty();
        }

        @Test
        @DisplayName("minio 必填 endpoint / bucket_name / access_key_id / secret_access_key")
        void minioRequiredFields() {
            assertThat(KbStorageBackendTypes.requiredFields("minio"))
                .containsExactly("endpoint", "bucket_name", "access_key_id", "secret_access_key");
        }

        @Test
        @DisplayName("cos 必填 region / bucket_name / access_key_id / secret_access_key")
        void cosRequiredFields() {
            assertThat(KbStorageBackendTypes.requiredFields("cos"))
                .containsExactly("region", "bucket_name", "access_key_id", "secret_access_key");
        }

        @Test
        @DisplayName("⭐ 两个密钥字段都是敏感字段（脱敏依据）")
        void secretsAreMarked() {
            assertThat(KbStorageBackendTypes.sensitiveFields("minio"))
                .containsExactly("access_key_id", "secret_access_key");
            assertThat(KbStorageBackendTypes.sensitiveFields("cos"))
                .containsExactly("access_key_id", "secret_access_key");
        }

        @Test
        @DisplayName("endpoint / bucket_name 不是敏感字段")
        void nonSecretsAreNotMasked() {
            assertThat(KbStorageBackendTypes.sensitiveFields("minio"))
                .doesNotContain("endpoint", "bucket_name", "path_prefix");
        }

        @Test
        @DisplayName("local 没有敏感字段")
        void localHasNoSecrets() {
            assertThat(KbStorageBackendTypes.sensitiveFields("local")).isEmpty();
        }
    }

    @Nested
    @DisplayName("provider 常量")
    class Constants {

        @Test
        @DisplayName("常量值与元数据 key 一致")
        void constantsMatchKeys() {
            assertThat(KbStorageBackendTypes.get(KbStorageBackendTypes.PROVIDER_LOCAL)).isNotNull();
            assertThat(KbStorageBackendTypes.get(KbStorageBackendTypes.PROVIDER_MINIO)).isNotNull();
            assertThat(KbStorageBackendTypes.get(KbStorageBackendTypes.PROVIDER_COS)).isNotNull();
        }
    }
}
