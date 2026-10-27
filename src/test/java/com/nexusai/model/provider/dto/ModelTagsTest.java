package com.nexusai.model.provider.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WHY（规则九）：tag 由 5 值枚举放开为自由文本后，历史库（DS/CL/GP/QW/KIMI）与目录新值（OR/GRO/MCC）
 * 必须共存，且脏数据（外部写入/迁移残留）不能让任何列表接口 500。
 *
 * <p>变异守护：把 {@link ModelTags#parse} 改回抛 {@code IllegalArgumentException}（等价于已删除的
 * 5 值枚举 {@code ModelTag.valueOf}），「脏值不抛」用例变红；把 {@code normalize} 的格式校验删掉，
 * 「非法 tag 必须拒绝」用例变红。
 */
class ModelTagsTest {

    @Test
    @DisplayName("normalize：合法值规范化（trim + 大写）")
    void normalizeValid() {
        assertThat(ModelTags.normalize("or")).isEqualTo("OR");
        assertThat(ModelTags.normalize(" GRO ")).isEqualTo("GRO");
        assertThat(ModelTags.normalize("MCC")).isEqualTo("MCC");
    }

    @Test
    @DisplayName("normalize：空 / 超长 / 非法字符 → ValidationException（写路径 fail-loud）")
    void normalizeInvalid() {
        assertThatThrownBy(() -> ModelTags.normalize(null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ModelTags.normalize("  ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ModelTags.normalize("TOOLONGTAG")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ModelTags.normalize("a-b")).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("parse：历史 5 值原样通过")
    void parseLegacyValues() {
        for (String legacy : new String[] {"DS", "CL", "GP", "QW", "KIMI"}) {
            assertThat(ModelTags.parse(legacy)).isEqualTo(legacy);
        }
    }

    @Test
    @DisplayName("parse：脏数据不抛异常，返回 null（列表接口不得 500）")
    void parseDirtyReturnsNull() {
        assertThat(ModelTags.parse("toolongtag")).isNull();
        assertThat(ModelTags.parse("a b")).isNull();
        assertThat(ModelTags.parse(null)).isNull();
        assertThat(ModelTags.parse("")).isNull();
    }

    @Test
    @DisplayName("默认标签沿用历史值 DS")
    void defaultTag() {
        assertThat(ModelTags.DEFAULT).isEqualTo("DS");
    }
}
