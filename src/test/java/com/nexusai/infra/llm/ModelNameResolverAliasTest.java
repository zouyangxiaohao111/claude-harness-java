package com.nexusai.infra.llm;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    // ── [T1] 查表容忍 [1m] 后缀（普通模型名 + [1m]）──────────────────────────────

    @Test
    @DisplayName("T1: resolve('deepseek-flash[1m]') 剥 [1m] 后命中 name=deepseek-flash（不再落 FALLBACK_TOKEN_BUDGET=200_000）")
    void plainModelWith1m_strippedLookupHitsDb() {
        ModelRecord row = new ModelRecord();
        row.setId("m-deepseek-flash");
        row.setProviderId("p1");
        row.setName("deepseek-flash");
        row.setEnabled(true);
        row.setMaxContextTokens(1_048_576);

        // 按 SQL 分流：原键（带 [1m]）查不到 ⇒ 只能靠「剥后缀重查」命中。
        // WHY 不用 when(...selectListByQuery(any())).thenReturn(List.of(row))：any() 对带 [1m] 的
        // 查询键同样返回该行 ⇒ 修复前也绿（假绿，判据失效，见派单书 assumption ②）。
        when(modelMapper.selectListByQuery(any())).thenAnswer(inv -> {
            QueryWrapper q = inv.getArgument(0);
            return q.toSQL().contains("'deepseek-flash[1m]'") ? List.of() : List.of(row);
        });

        ModelRecord hit = ModelNameResolver.resolve(modelMapper, null, "deepseek-flash[1m]");

        assertThat(hit)
            .as("会话主力模型名 deepseek-flash[1m] 必须命中 models.name=deepseek-flash 那条记录 —— "
                + "否则 AgentLoopContext.computeBudgetFromGates（AgentLoopContext.java FALLBACK_TOKEN_BUDGET）"
                + " 把 1M 会话按 200_000 静默计窗口，autoCompact 阈值随之腰斩")
            .isNotNull();
        assertThat(hit.getName())
            .as("返回的必须是 DB 原记录：剥 [1m] 只用于查询键，绝不篡改/回写模型名")
            .isEqualTo("deepseek-flash");
        assertThat(hit.getMaxContextTokens()).isEqualTo(1_048_576);

        // 铁证（SQL 级）：第一次仍按原键精确查（不改变既有字面量 [1m] 行的解析），
        // 第二次才用剥掉 [1m] 的查询键 —— 对齐 CC model.ts:451-454「剥 [1m] 再比对」。
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(modelMapper, times(2)).selectListByQuery(captor.capture());
        assertThat(captor.getAllValues().get(0).toSQL())
            .as("第一次查询必须是原键（精确优先，保证表内真实的 'foo[1m]' 行零回归）")
            .contains("name = 'deepseek-flash[1m]'");
        assertThat(captor.getAllValues().get(1).toSQL())
            .as("第二次查询键必须已剥 [1m]（这是修复生效的唯一判据：不带 [1m] 的名字才能命中表里的 deepseek-flash）")
            .contains("name = 'deepseek-flash'")
            .doesNotContain("[1m]");
    }

    @Test
    @DisplayName("T1 反向护栏: 别名 sonnet[1m] 的 [1m] 保真展开不回归，且查表键归一后命中 DB 裸名")
    void aliasWith1m_expansionUnchangedNotRegressed() {
        ModelNameResolver.mediumTierModelSource = () -> "claude-sonnet-4-6";

        // ① 别名展开语义逐字节不变（[1m] 保真，CC model.ts:458-468）—— 本任务不得触碰 expandAlias
        assertThat(ModelNameResolver.expandAlias("sonnet[1m]"))
            .as("别名 + [1m] 的展开保真语义必须原样（G-4 护栏，不得因本次 [1m] 剥离而回归）")
            .isEqualTo("claude-sonnet-4-6[1m]");

        ModelRecord row = new ModelRecord();
        row.setId("m-sonnet");
        row.setProviderId("p1");
        row.setName("claude-sonnet-4-6");
        row.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenAnswer(inv -> {
            QueryWrapper q = inv.getArgument(0);
            return q.toSQL().contains("'claude-sonnet-4-6[1m]'") ? List.of() : List.of(row);
        });

        // ② 展开后的 "claude-sonnet-4-6[1m]" 查询键被归一 → 命中 DB 裸名行（同族缺陷一并收口）
        ModelRecord hit = ModelNameResolver.resolve(modelMapper, null, "sonnet[1m]");

        assertThat(hit)
            .as("展开产物 claude-sonnet-4-6[1m] 同样必须容忍 [1m] 后缀，否则 sonnet[1m] 会话"
                + "仍按 FALLBACK_TOKEN_BUDGET=200_000 计窗口（与 deepseek-flash[1m] 同病）")
            .isNotNull();
        assertThat(hit.getName()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("T1 歧义取舍: 表内同时有 foo 与 foo[1m] 两条 → 输入 foo[1m] 取 foo[1m] 行（精确优先，确定性）")
    void bothRowsExist_exactWins() {
        ModelRecord exactRow = new ModelRecord();
        exactRow.setId("m-exact");
        exactRow.setProviderId("p1");
        exactRow.setName("foo[1m]");
        exactRow.setEnabled(true);
        ModelRecord baseRow = new ModelRecord();
        baseRow.setId("m-base");
        baseRow.setProviderId("p1");
        baseRow.setName("foo");
        baseRow.setEnabled(true);

        when(modelMapper.selectListByQuery(any())).thenAnswer(inv -> {
            QueryWrapper q = inv.getArgument(0);
            return q.toSQL().contains("name = 'foo[1m]'") ? List.of(exactRow) : List.of(baseRow);
        });

        ModelRecord hit = ModelNameResolver.resolve(modelMapper, null, "foo[1m]");

        assertThat(hit.getId())
            .as("取舍已固化：精确键优先、剥后缀键仅兜底 ⇒ 'foo[1m]' 命中字面量行 foo[1m]，"
                + "行为与修复前一致（零回归）；若改「先剥再查」会静默降级到 foo 行")
            .isEqualTo("m-exact");
        verify(modelMapper, times(1)).selectListByQuery(any());
    }

    @Test
    @DisplayName("T1 无回归: 不带 [1m] 的名字仍只发一次查询（G5Test 的 times(1) 严格校验不被破坏）")
    void noSuffix_stillSingleQuery() {
        ModelRecord row = new ModelRecord();
        row.setId("m-plain");
        row.setName("deepseek-flash");
        row.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(row));

        ModelRecord hit = ModelNameResolver.resolve(modelMapper, null, "deepseek-flash");

        assertThat(hit.getName()).isEqualTo("deepseek-flash");
        verify(modelMapper, times(1)).selectListByQuery(any());
    }
}
