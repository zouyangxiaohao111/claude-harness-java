package com.nexusai.application.agent.subagent;

import com.nexusai.infra.util.AntModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [R2-SKILL] SkillModelOverrideResolver 规范化对齐 CC 测试。
 *
 * <p>对齐项（EV-DR-012 / AD-19 △）：
 * <ol>
 *   <li>比对前先经 parseUserSpecifiedModel 别名→canonical 规范化（CC model.ts:445-506），
 *       而非在 modelSupports1M 层手补裸 opus/sonnet（旧 Java 简化，deletion-manifest #21）；</li>
 *   <li>补别名 opusplan/best 命中（CC model.ts:459/467：opusplan→defaultSonnet、
 *       best→getBestModel()→defaultOpus）；</li>
 *   <li>has1mContext/modelSupports1M 对齐 CC context.ts:31-49 env 门控
 *       CLAUDE_CODE_DISABLE_1M_CONTEXT；</li>
 *   <li>modelSupports1M 精确匹配 canonical 家族（claude-sonnet-4 / opus-4-6，CC context.ts:43-49），
 *       收紧旧 contains("sonnet-4") 过宽匹配。</li>
 * </ol>
 */
class SkillModelOverrideResolverTest {

    /** R-A9 ant branch 测试隔离：USER_TYPE 注入缝（CC model.ts:485-498 只读 USER_TYPE） */
    @BeforeEach
    void setUp() {
        SkillModelOverrideResolver.ENV_READER = System::getenv;
        SkillModelOverrideResolver.ANT_MODEL_OVERRIDE_SUPPLIER = () -> null;
    }

    @AfterEach
    void tearDown() {
        SkillModelOverrideResolver.ENV_READER = System::getenv;
        SkillModelOverrideResolver.ANT_MODEL_OVERRIDE_SUPPLIER = () -> null;
    }

    // ---- R-A9 USER_TYPE=ant 分支（CC model.ts:485-498 + antModels.ts:51-64）----

    /** 测试用 ant model override config：alias=capybara → model=capybara-v2 */
    private static AntModels.AntModelOverrideConfig antConfig(AntModels.AntModel... models) {
        return new AntModels.AntModelOverrideConfig(null, null, null, List.of(models), null);
    }

    @Test
    @DisplayName("R-A9: USER_TYPE=ant + resolveAntModel 别名命中 → antModel.model（CC model.ts:490-493）")
    void ant_aliasResolved() {
        // WHY: CC model.ts:489-493 resolveAntModel(baseAntModel) 命中 antModels 配置 →
        //   return antModel.model + (has1mAntTag?'[1m]':'')。Java R-A9 补该分支（WF-G-UN-3，
        //   A-9 决策归 D17 USER_TYPE 域）。旧 Java 无该分支 → 'capybara' 原样返回（丢映射）。
        SkillModelOverrideResolver.ENV_READER = key -> "USER_TYPE".equals(key) ? "ant" : System.getenv(key);
        SkillModelOverrideResolver.ANT_MODEL_OVERRIDE_SUPPLIER = () -> antConfig(
            new AntModels.AntModel("capybara", "capybara-v2", "Capybara V2", null, null, null, null, null, null, null));
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("capybara", false, true, true, "claude-opus-4-6"))
                .isEqualTo("capybara-v2");
    }

    @Test
    @DisplayName("R-A9: USER_TYPE=ant + resolveAntModel 别名命中 + [1m] 后缀顺延（CC model.ts:486/491-493）")
    void ant_aliasResolved_1mSuffixCarried() {
        // WHY: CC model.ts:486 has1mAntTag = has1mContext(normalizedModel)；命中时 suffix =
        //   has1mAntTag?'[1m]':''（:491-493）。输入带 [1m] → 返回 antModel.model + '[1m]'。
        SkillModelOverrideResolver.ENV_READER = key -> "USER_TYPE".equals(key) ? "ant" : System.getenv(key);
        SkillModelOverrideResolver.ANT_MODEL_OVERRIDE_SUPPLIER = () -> antConfig(
            new AntModels.AntModel("capybara", "capybara-v2", "Capybara V2", null, null, null, null, null, null, null));
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("capybara[1m]", false, true, true, "claude-opus-4-6"))
                .isEqualTo("capybara-v2[1m]");
    }

    @Test
    @DisplayName("R-A9: USER_TYPE=ant + model 子串匹配（lower.includes）→ antModel.model（CC antModels.ts:61-63）")
    void ant_modelSubstringMatch() {
        // WHY: CC antModels.ts:61-63 resolveAntModel 第二条件 lower.includes(m.model.toLowerCase())
        //   —— codename 子串匹配（非仅 alias 精确相等）。Java AntModels.resolveAntModel 同构。
        SkillModelOverrideResolver.ENV_READER = key -> "USER_TYPE".equals(key) ? "ant" : System.getenv(key);
        SkillModelOverrideResolver.ANT_MODEL_OVERRIDE_SUPPLIER = () -> antConfig(
            new AntModels.AntModel("capybara", "capybara-v2", "Capybara V2", null, null, null, null, null, null, null));
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("capybara-v2", false, true, true, "claude-opus-4-6"))
                .isEqualTo("capybara-v2");
    }

    @Test
    @DisplayName("R-A9: USER_TYPE=ant + 无匹配 → 落到原样保大小写分支（CC model.ts:495-498 fall-through）")
    void ant_noMatch_fallsThroughToOriginalCase() {
        // WHY: CC model.ts:495-498 resolveAntModel 未命中 → "Fall through to the alias string"——
        //   API 调用将用该字符串失败但经反馈暴露；不返回 null 也不丢原串大小写（:500-505 保原大小写）。
        SkillModelOverrideResolver.ENV_READER = key -> "USER_TYPE".equals(key) ? "ant" : System.getenv(key);
        SkillModelOverrideResolver.ANT_MODEL_OVERRIDE_SUPPLIER = () -> antConfig(
            new AntModels.AntModel("capybara", "capybara-v2", "Capybara V2", null, null, null, null, null, null, null));
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("MyCustom-Deployment", false, true, true, "claude-opus-4-6"))
                .isEqualTo("MyCustom-Deployment");
    }

    @Test
    @DisplayName("R-A9: USER_TYPE=ant + 无 override 配置（默认 null supplier）→ 落到原样（CC getAntModelOverrideConfig null → []）")
    void ant_noConfig_fallsThrough() {
        // WHY: CC antModels.ts:34-42 getAntModelOverrideConfig 无配置返回 null → getAntModels [] →
        //   resolveAntModel 未命中 → 落到原样（model.ts:495-498）。Java 默认 ANT_MODEL_OVERRIDE_SUPPLIER=null
        //   恒落空，与本断言一致（HIPAA/外部构建场景）。
        SkillModelOverrideResolver.ENV_READER = key -> "USER_TYPE".equals(key) ? "ant" : System.getenv(key);
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("capybara", false, true, true, "claude-opus-4-6"))
                .isEqualTo("capybara");
    }

    @Test
    @DisplayName("R-A9: 非 ant USER_TYPE → ant 分支不触发，别名正常展开（CC model.ts:485 门控）")
    void nonAnt_antBranchNotTriggered() {
        // WHY: CC model.ts:485 if (process.env.USER_TYPE === 'ant') —— 外部构建 USER_TYPE!=ant
        //   恒不触发 ant 分支 → 'opus' 走别名展开到 defaultOpus。Java ENV_READER 注 "external"
        //   验证分支门控不被误触（保护既有别名展开路径）。
        SkillModelOverrideResolver.ENV_READER = key -> "USER_TYPE".equals(key) ? "external" : System.getenv(key);
        SkillModelOverrideResolver.ANT_MODEL_OVERRIDE_SUPPLIER = () -> antConfig(
            new AntModels.AntModel("opus", "claude-opus-4-6", "Opus", null, null, null, null, null, null, null));
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("opus", false, true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-6");
    }

    // ---- 别名命中（RED 核心：opusplan/best 旧代码裸别名层不命中） ----

    @Test
    @DisplayName("skill model=opus 在 1M 会话 → 顺延 [1m]")
    void opus_alias_carries1m() {
        // WHY: CC model.ts:465 opus → getDefaultOpusModel()（claude-opus-4-6）→
        //   modelSupports1M 命中 opus-4-6 → 顺延 [1m]（防 200K 窗口塌缩）。
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("opus", "opus[1m]"))
                .isEqualTo("opus[1m]");
    }

    @Test
    @DisplayName("skill model=sonnet 在 1M 会话 → 顺延 [1m]")
    void sonnet_alias_carries1m() {
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("sonnet", "opus[1m]"))
                .isEqualTo("sonnet[1m]");
    }

    @Test
    @DisplayName("skill model=opusplan 在 1M 会话 → 顺延 [1m]（CC: opusplan→defaultSonnet 4.6）")
    void opusplan_alias_carries1m() {
        // WHY: CC model.ts:459 opusplan → getDefaultSonnetModel()（claude-sonnet-4-6，支持 1M）→
        //   modelSupports1M 命中 claude-sonnet-4 → 顺延 [1m]。旧 Java modelSupports1M
        //   裸别名层（.equals("opus")/.equals("sonnet")）不匹配 opusplan → 返回 "opusplan"
        //   （200K 窗口塌缩 → autocompact 误报 "Context limit reached"）。对齐缺口。
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("opusplan", "opus[1m]"))
                .isEqualTo("opusplan[1m]");
    }

    @Test
    @DisplayName("skill model=best 在 1M 会话 → 顺延 [1m]（CC: best→getBestModel()→defaultOpus 4.6）")
    void best_alias_carries1m() {
        // WHY: CC model.ts:467 best → getBestModel()（model.ts:100-102 = getDefaultOpusModel()）→
        //   claude-opus-4-6 支持 1M。旧 Java 裸别名层不匹配 best → 不顺延（对齐缺口）。
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("best", "opus[1m]"))
                .isEqualTo("best[1m]");
    }

    @Test
    @DisplayName("best[1m] 直接 parse → 丢弃 [1m]（CC model.ts:466-467 best 是唯一不追加 [1m] 的别名分支）")
    void bestAlias_with1mTag_discardsSuffix() {
        // WHY: CC model.ts:466-467 `case 'best': return getBestModel()` 是 parseUserSpecifiedModel
        //   别名 switch 里唯一不追加 (has1mTag?'[1m]':'') 的分支，输入 "best[1m]" 时 CC 丢弃 [1m]
        //   返回 "claude-opus-4-6"（getBestModel()=getDefaultOpusModel()，model.ts:100-102）。
        //   旧 Java 统一 `canonical + (has1mTag?'[1m]':'')` 对 best 也追加 [1m] → 语义分歧。
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("best[1m]", true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-6");
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("best", true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("skill model=haiku 在 1M 会话 → 不顺延（haiku 无 1M 变体，autocompact 正确）")
    void haiku_alias_no1m() {
        // WHY: CC model.ts:518-520 语义：haiku → claude-haiku-4-5 家族不匹配
        //   claude-sonnet-4/opus-4-6 → 保持原样（降级 200K + autocompact 是正确行为）。
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("haiku", "opus[1m]"))
                .isEqualTo("haiku");
    }

    // ---- 规范化对齐：canonical 家族精确匹配（CC context.ts:43-49 + model.ts:264-269） ----

    @Test
    @DisplayName("canonical claude-sonnet-4-5-20250929 → 含 claude-sonnet-4 → 顺延 [1m]")
    void canonical_sonnet45_carries1m() {
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("claude-sonnet-4-5-20250929", "opus[1m]"))
                .isEqualTo("claude-sonnet-4-5-20250929[1m]");
    }

    @Test
    @DisplayName("裸 sonnet-4-5（非别名非 canonical）→ 不顺延（CC getCanonicalName 需 claude- 前缀）")
    void bare_sonnet45_no1m() {
        // WHY: CC getCanonicalName（model.ts:264-269 regex）需 claude- 前缀；裸 "sonnet-4-5"
        //   canonical 不含 "claude-sonnet-4" → modelSupports1M=false → 不顺延。
        //   旧 Java contains("sonnet-4") 过宽 → 错误顺延 [1m]（本次收紧，对齐 CC）。
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("sonnet-4-5", "opus[1m]"))
                .isEqualTo("sonnet-4-5");
    }

    // ---- 守卫分支（CC model.ts:523-536） ----

    @Test
    @DisplayName("skillModel=null → null（调用方跳过）")
    void null_skillModel_returnsNull() {
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride(null, "opus[1m]")).isNull();
    }

    @Test
    @DisplayName("当前非 1M 会话（currentModel 无 [1m]）→ 原样返回（CC model.ts:527）")
    void non1m_session_unchanged() {
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("opus", "claude-sonnet-4-6"))
                .isEqualTo("opus");
    }

    @Test
    @DisplayName("skill model 已带 [1m] → 原样保留（CC model.ts:520-521 'left untouched'）")
    void skillModel_already1m_unchanged() {
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("opus[1m]", "opus[1m]"))
                .isEqualTo("opus[1m]");
    }

    // ---- env 门控（CC context.ts:31-49 CLAUDE_CODE_DISABLE_1M_CONTEXT） ----

    @Test
    @DisplayName("1M 上下文被禁用 → has1mContext/modelSupports1M 恒 false → 原样返回（不顺延）")
    void disabled1mContext_noCarryOver() {
        // WHY: CC context.ts:31-49 is1mContextDisabled()（isEnvTruthy(CLAUDE_CODE_DISABLE_1M_CONTEXT)）
        //   → has1mContext/modelSupports1M 恒 false，即使 currentModel 带 [1m] 也不顺延
        //   （HIPAA 合规关停场景）。Java System.getenv 只读 → 走包可见 3 参重载注入开关。
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("opus", "opus[1m]", true))
                .isEqualTo("opus");
    }

    @Test
    @DisplayName("1M 上下文未禁用（disable=false）→ 正常顺延 [1m]")
    void enabled1mContext_carriesOver() {
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride("opus", "opus[1m]", false))
                .isEqualTo("opus[1m]");
    }

    @Test
    @DisplayName("D14 禁用分支：parseUserSpecifiedModel 5 参 disable=true → 别名不展开 [1m]（原样保留）")
    void d14_parseDisable1m_noAliasExpansion() {
        // WHY: CC context.ts:36-38 has1mContext 在 is1mContextDisabled() 时恒 false → model.ts:451
        //   has1mTag=false → 不剥 [1m] 后缀（:452-454）→ modelString 非别名（:456）→ 非 legacy（:477-483）→
        //   原样返回（:500-505）。D14 本次新增门控行为：若回退 :249 为未门控 contains("[1m]")，
        //   has1mTag=true → 剥后缀 → 别名 opus 展开 → 返回 "claude-opus-4-6[1m]" —— 本断言必红。
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("opus[1m]", true, true, true, "claude-opus-4-6"))
                .isEqualTo("opus[1m]");
    }

    @Test
    @DisplayName("D14 禁用分支：remap disable=true → legacy opus 带 [1m] 不 remap（原样保留）")
    void d14_remapDisable1m_noLegacyRemap() {
        // WHY: CC model.ts:482 remap 的 has1mTag 同源自 :451 门控值（context.ts:36-38 禁用恒 false）→
        //   base 保留 "[1m]"（:465-467）→ isLegacyOpusFirstParty("claude-opus-4-1[1m]") 精确匹配失败（:545-547）→
        //   remap null → 非别名分支原样返回（:500-505）。若回退 :464 为未门控 contains("[1m]")，
        //   has1mTag=true → base="claude-opus-4-1" → legacy 精确命中 → remap "claude-opus-4-6[1m]" —— 本断言必红。
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-1[1m]", true, true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-1[1m]");
    }

    // ---- legacy opus 首方 remap（CC model.ts:477-483）----

    @Test
    @DisplayName("legacy opus claude-opus-4-1 在 1M 会话 → remap opus-4-6 → 顺延 [1m]")
    void legacyOpus_firstPartyRemap_carries1m() {
        // WHY: CC model.ts:477-483 legacy opus 首方 remap（firstParty && isLegacyOpusFirstParty &&
        //   isLegacyModelRemapEnabled）把显式 legacy opus 字符串静默 remap 到 defaultOpus（opus-4-6），
        //   再经 modelSupports1M 命中 opus-4-6 → 顺延 [1m]（防 200K 窗口塌缩）。旧 Java 未实现该
        //   分支 → modelSupports1M('claude-opus-4-1')=false → 不顺延（对齐缺口 R32-05）。
        //   走 6 参重载注入 defaultOpus='claude-opus-4-6'（不依赖测试机 ANTHROPIC_DEFAULT_OPUS_MODEL env）。
        assertThat(SkillModelOverrideResolver.resolveSkillModelOverride(
                "claude-opus-4-1", "opus[1m]", false, true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-1[1m]");
    }

    @Test
    @DisplayName("legacy opus 4 个字符串全部 remap 到 claude-opus-4-6")
    void legacyOpus_allFourStringsRemap() {
        // WHY: CC LEGACY_OPUS_FIRSTPARTY（model.ts:538-543）= 4 个 legacy opus 显式串，
        //   isLegacyOpusFirstParty 精确 includes 判定（非前缀），全部静默 remap 到 defaultOpus。
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-20250514", true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-6");
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-1-20250805", true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-6");
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-0", true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-6");
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-1", true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("legacy opus 带 [1m] 后缀 → remap 保留 [1m] 后缀")
    void legacyOpus_remapPreserves1mSuffix() {
        // WHY: CC model.ts:483 remap 结果 = getDefaultOpusModel() + (has1mTag ? '[1m]' : '')，
        //   [1m] 后缀顺延到 remap 后的 defaultOpus。
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-1[1m]", true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-6[1m]");
    }

    @Test
    @DisplayName("非 firstParty（bedrock/vertex/foundry）→ legacy opus 原样保留（3P 无 4.6 容量）")
    void legacyOpus_nonFirstParty_noRemap() {
        // WHY: CC model.ts:482 gate getAPIProvider()==='firstParty'——3P provider 可能尚无 4.6 容量，
        //   legacy opus 原样通过（CC 注释 model.ts:476-478）。
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-1", false, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-1");
    }

    @Test
    @DisplayName("remap 被禁用（CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP）→ legacy opus 原样保留")
    void legacyOpus_remapDisabled_noRemap() {
        // WHY: CC model.ts:480 gate isLegacyModelRemapEnabled()（!isEnvTruthy(CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP)），
        //   用户显式 opt-out 时 remap 关闭。
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-1", true, false, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-1");
    }

    @Test
    @DisplayName("非 legacy opus（claude-opus-4-5）→ 不 remap")
    void nonLegacyOpus_noRemap() {
        // WHY: isLegacyOpusFirstParty 精确匹配 4 个 legacy 串，opus-4-5 不在列表 → 原样保留。
        assertThat(SkillModelOverrideResolver.parseUserSpecifiedModel("claude-opus-4-5", true, true, "claude-opus-4-6"))
                .isEqualTo("claude-opus-4-5");
    }
}
