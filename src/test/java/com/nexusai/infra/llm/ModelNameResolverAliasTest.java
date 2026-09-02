package com.nexusai.infra.llm;

import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * [G-4] ModelNameResolver 主链别名展开前置测试。
 *
 * <p>WHY（规则九 · 测试验证意图）：CC getMainLoopModel（model.ts:92-98）对用户指定 model 无条件过
 * parseUserSpecifiedModel（model.ts:456-470）——裸别名 opus/sonnet/haiku/best/opusplan 必须展开为
 * 各档真实模型名后才能命中 DB。修复前 ChatService.resolveModelNameForSession 原样传
 * modelName=opus → resolve 按字面量 DB 精确匹配 → miss → 抛 'No enabled model for: opus'。
 * 本测试锁定：<b>主链裸别名展开后按 DB 真实名命中</b>（G-4 验收），且非别名 / 含 '/'（G-5 透明名）
 * 一律原样透传不破坏既有路径。
 */
@ExtendWith(MockitoExtension.class)
class ModelNameResolverAliasTest {

    @Mock private ModelMapper modelMapper;
    @Mock private ProviderMapper providerMapper;

    @AfterEach
    void resetTierSources() {
        // 还原 static volatile 档位来源，避免污染其他测试（默认 null → 回落 CC canonical 默认）
        ModelNameResolver.strongTierModelSource = () -> null;
        ModelNameResolver.mediumTierModelSource = () -> null;
        ModelNameResolver.weakTierModelSource = () -> null;
    }

    // ── 别名 → 档位展开（expandAlias 包可见直接验证）────────────────────────────

    @Test
    @DisplayName("G-4: opus → 强档（settings.strongModelId 反查名），大小写不敏感")
    void opus_expandsToStrongTier() {
        ModelNameResolver.strongTierModelSource = () -> "claude-opus-4-6";

        assertThat(ModelNameResolver.expandAlias("opus")).isEqualTo("claude-opus-4-6");
        assertThat(ModelNameResolver.expandAlias("  Opus  ")).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("G-4: opus 未配置强档 → 回落 CC canonical 默认 claude-opus-4-6（等价 CC env 未设）")
    void opus_fallsBackToCanonicalDefault() {
        assertThat(ModelNameResolver.expandAlias("opus")).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("G-4: sonnet → 中档、haiku → 弱档、opusplan → 中档（CC model.ts:459-463）")
    void sonnetHaikuOpusplan_expandToTiers() {
        ModelNameResolver.mediumTierModelSource = () -> "claude-sonnet-4-6";
        ModelNameResolver.weakTierModelSource = () -> "claude-haiku-4-5-20251001";

        assertThat(ModelNameResolver.expandAlias("sonnet")).isEqualTo("claude-sonnet-4-6");
        assertThat(ModelNameResolver.expandAlias("haiku")).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(ModelNameResolver.expandAlias("opusplan")).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("G-4: best → 强档（CC :467 getBestModel→defaultOpus），不追加 [1m]")
    void best_expandsToStrongWithout1m() {
        ModelNameResolver.strongTierModelSource = () -> "claude-opus-4-6";

        assertThat(ModelNameResolver.expandAlias("best")).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("G-4: 非 best 别名带 [1m] → 展开后保留 [1m] 后缀（CC :458-468 保真）")
    void aliasWith1m_preservesSuffix() {
        ModelNameResolver.strongTierModelSource = () -> "claude-opus-4-6";
        ModelNameResolver.mediumTierModelSource = () -> "claude-sonnet-4-6";

        assertThat(ModelNameResolver.expandAlias("opus[1m]")).isEqualTo("claude-opus-4-6[1m]");
        assertThat(ModelNameResolver.expandAlias("sonnet[1m]")).isEqualTo("claude-sonnet-4-6[1m]");
        // best 即使带 [1m] 也不追加（CC :466-467）
        assertThat(ModelNameResolver.expandAlias("best[1m]")).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("G-4: 非别名原样透传（null）、含 '/' 由 G-5 处理不展开")
    void nonAlias_andFullName_notExpanded() {
        assertThat(ModelNameResolver.expandAlias(null)).isNull();
        assertThat(ModelNameResolver.expandAlias("deepseek-x")).isNull();
        assertThat(ModelNameResolver.expandAlias("anthropic/opus")).isNull();
        // 含 '/' 但模型段非别名 → 透传
        assertThat(ModelNameResolver.expandAlias("anthropic/claude-opus-4-6")).isNull();
    }

    // ── 主链 resolve 验收：modelName=opus 命中 DB 模型 ──────────────────────────

    @Test
    @DisplayName("G-4 验收: resolve('opus') 展开后按 DB 真实名命中（修复前抛 'No enabled model for: opus'）")
    void resolve_opus_hitsDbModel() {
        // 强档反查命中 settings.strongModelId → DB 真实名 claude-opus-4-6
        ModelNameResolver.strongTierModelSource = () -> "claude-opus-4-6";

        ModelRecord model = new ModelRecord();
        model.setId("m-opus");
        model.setName("claude-opus-4-6");
        model.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));

        ModelRecord hit = ModelNameResolver.resolve(modelMapper, null, "opus");

        assertThat(hit).as("裸别名 opus 必须展开为强档真实名并命中 DB（G-4）").isNotNull();
        assertThat(hit.getName()).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("G-4: resolve('deepseek-x') 非别名行为不变（原样 DB 精确匹配）")
    void resolve_nonAlias_unchanged() {
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setName("deepseek-x");
        model.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));

        assertThat(ModelNameResolver.resolve(modelMapper, null, "deepseek-x").getName())
            .isEqualTo("deepseek-x");
    }
}
