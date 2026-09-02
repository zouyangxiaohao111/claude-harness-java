package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.prompt.PromptCaching;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H7-arch Phase 5 P5 D1] AgentModelResolver 对齐 CC {@code utils/model/agent.ts:37-95 getAgentModel}。
 *
 * <p>WHY（规则九 · 测试验证意图）：验证 subagent 模型解析链的<strong>关键语义</strong>——
 * 'inherit' 继承父模型 / 裸家族别名匹配父档位防降级 / 兜底 parse / env override。原 2 行实现
 * （agent.model().orElse(defaultModel)）完全缺失这些语义，任何一条 revert 都会让测试 RED。
 *
 * <p>测试走 {@link AgentModelResolver#resolveWithEnv}（env 显式可控），避免测试机上的
 * CLAUDE_CODE_SUBAGENT_MODEL 环境变量干扰（Java System.getenv 只读无法在测试内设置）。
 */
class AgentModelResolverTest {

    /** env=null（无 override）时的非 env 分支。 */
    private static String resolve(String agentModel, String parentModel,
            String toolSpecifiedModel, String permissionMode) {
        return AgentModelResolver.resolveWithEnv(
            agentModel, parentModel, toolSpecifiedModel, permissionMode, null);
    }

    @Test
    @DisplayName("默认 subagent 模型为 'inherit'（CC agent.ts:25-27）")
    void defaultSubagentModel_isInherit() {
        // WHY: inherit 语义是 subagent 不指定模型时继承父线程模型，而非硬编码某个模型名
        assertThat(AgentModelResolver.DEFAULT_SUBAGENT_MODEL).isEqualTo("inherit");
    }

    @Test
    @DisplayName("agentModel 未指定 → 'inherit' → 返回父模型（CC agent.ts:80-88）")
    void inherit_returnsParentModel() {
        String parent = "claude-opus-4-6";
        // WHY: subagent 不指定模型时必须用父线程模型，避免子 agent 用不同档位造成结果漂移
        assertThat(resolve(null, parent, null, null)).isEqualTo(parent);
    }

    @Test
    @DisplayName("裸 'opus' 别名匹配 Opus 父档位 → 继承父模型精确串（防降级，CC agent.ts:90-92 + issue 30815）")
    void opusAlias_matchesOpusParent_inheritsParentModel() {
        String parent = "claude-opus-4-6";
        // WHY: Vertex/自定义用户通过 /model 选 Opus 后 spawn 子 agent 用 model:opus，
        //     应得到 Opus 4.6 而非 provider 默认（防止意外降级）
        assertThat(resolve("opus", parent, null, null)).isEqualTo(parent);
    }

    @Test
    @DisplayName("裸 'opus' 别名不匹配 Sonnet 父档位 → 兜底展开为 opus 默认（CC agent.ts:93-94 + model.ts:465）")
    void opusAlias_doesNotMatchSonnetParent_resolvesAlias() {
        String parent = "claude-sonnet-4-5";
        // WHY: 父是 sonnet 时 'opus' 语义是"升级到 opus 档位"，不能错误继承 sonnet；
        //   [D-5] 兜底 parse 现展开别名 → opus 默认 'claude-opus-4-6'（CC model.ts:465 getDefaultOpusModel）
        assertThat(resolve("opus", parent, null, null)).isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("工具指定 'sonnet' 别名匹配 Sonnet 父档位 → 继承父模型（CC agent.ts:70-73）")
    void toolSpecifiedModel_matchesParentTier_inheritsParent() {
        String parent = "claude-sonnet-4-5";
        // WHY: toolSpecifiedModel（CC ModelAlias）优先于 agentModel，命中父档位时继承父精确串
        assertThat(resolve("haiku", parent, "sonnet", null)).isEqualTo(parent);
    }

    @Test
    @DisplayName("CLAUDE_CODE_SUBAGENT_MODEL env override 最高优先级（CC agent.ts:43-45）")
    void envOverride_takesHighestPriority() {
        // WHY: env 全局覆盖 subagent 模型（如测试/CI 强制某模型），优先于 inherit/alias/agent 配置
        assertThat(AgentModelResolver.resolveWithEnv(
            "opus", "claude-sonnet-4-5", null, null, "claude-haiku-4"))
            .isEqualTo("claude-haiku-4");
    }

    @Test
    @DisplayName("settings.subagentModelId DB 优先于 env override（W2-2；env 路 W4 清理）")
    void settingsSubagentModelId_takesPriorityOverEnv() {
        // WHY: W2-2 settings.subagentModelId（DB settings 档位字段，V25 列）为 subagent 档位
        //     模型权威来源，env CLAUDE_CODE_SUBAGENT_MODEL 为遗留路（W4 清理）——DB 非空时优先，
        //     避免双源并存时 env 静默覆盖用户 DB 配置
        assertThat(AgentModelResolver.resolveWithEnv(
            "opus", "claude-sonnet-4-5", null, null, "claude-haiku-4", "claude-sonnet-4-6"))
            .isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("settings.subagentModelId 未配置/空白 → 回落 env override（CC agent.ts:43-45）")
    void settingsSubagentModelId_blank_fallsBackToEnv() {
        // WHY: DB 未配置时保持既有 env 行为——settings 为可选增强，不得破坏 env 覆盖链
        assertThat(AgentModelResolver.resolveWithEnv(
            "opus", "claude-sonnet-4-5", null, null, "claude-haiku-4", null))
            .isEqualTo("claude-haiku-4");
        assertThat(AgentModelResolver.resolveWithEnv(
            "opus", "claude-sonnet-4-5", null, null, "claude-haiku-4", "  "))
            .isEqualTo("claude-haiku-4");
    }

    @Test
    @DisplayName("inherit 非 opusplan/haiku 父模型 + plan 模式 → 原样返回父模型（CC model.ts:166 默认分支）")
    void inherit_nonAliasParent_planMode_returnsParent() {
        String parent = "claude-opus-4-6";
        // WHY: plan-upgrade（G-7）仅命中 setting='opusplan'/'haiku'；其他父模型在 plan 模式
        //     也恒继承父模型，不能因 plan 就任意升档（CC model.ts:166 return mainLoopModel）。
        assertThat(resolve("inherit", parent, null, "plan")).isEqualTo(parent);
    }

    // ---- [G-7] 'inherit' plan 模式升级（CC getRuntimeMainLoopModel model.ts:145-167）----

    @Test
    @DisplayName("[G-7] inherit + 父 setting='opusplan' + plan → 升级到 defaultOpus（CC model.ts:152-159）")
    void inherit_opusplanParent_planMode_upgradesToDefaultOpus() {
        // WHY: CC opusplan 默认解析为 Sonnet（model.ts:459），但 plan 模式由 getRuntimeMainLoopModel
        //     升级到 Opus（不带 [1m]）——subagent 'inherit' 在 plan 模式拿 Opus 而非 Sonnet，
        //     这是 CC 明确的产品语义（plan 需要更强的推理模型）。
        assertThat(resolve("inherit", "opusplan", null, "plan"))
            .isEqualTo(PromptCaching.defaultOpusModel());
    }

    @Test
    @DisplayName("[G-7] inherit + 父 setting='haiku' + plan → 升级到 defaultSonnet（CC model.ts:161-164）")
    void inherit_haikuParent_planMode_upgradesToDefaultSonnet() {
        // WHY: CC haiku 默认解析为 Haiku（model.ts:463），但 plan 模式由 getRuntimeMainLoopModel
        //     升级到 Sonnet（"sonnetplan by default"）——plan 模式至少用 Sonnet 档。
        assertThat(resolve("inherit", "haiku", null, "plan"))
            .isEqualTo(PromptCaching.defaultSonnetModel());
    }

    @Test
    @DisplayName("[G-7] inherit + opusplan 父但非 plan 模式 → 返回父模型原串（CC model.ts:166）")
    void inherit_opusplanParent_defaultMode_returnsParent() {
        // WHY: plan-upgrade 的门控是 permissionMode==='plan'；非 plan（default/bypass 等）时
        //     opusplan 保持原样（后续走 parseUserSpecifiedModel 展开为 Sonnet 是另一层），
        //     不能越权升到 Opus。
        assertThat(resolve("inherit", "opusplan", null, "default")).isEqualTo("opusplan");
        assertThat(resolve("inherit", "opusplan", null, null)).isEqualTo("opusplan");
    }

    @Test
    @DisplayName("[G-7] inherit + haiku 父但非 plan 模式 → 返回父模型原串（CC model.ts:166）")
    void inherit_haikuParent_defaultMode_returnsParent() {
        assertThat(resolve("inherit", "haiku", null, "default")).isEqualTo("haiku");
    }

    @Test
    @DisplayName("[G-7] permissionMode 大小写不敏感（生产传 PLAN.name()，测试传 'plan'）")
    void inherit_planMode_caseInsensitivePermissionMode() {
        // WHY: SubagentTool 生产路径传 PermissionMode.PLAN.name()="PLAN"，测试传 "plan"；
        //     isPlanMode 必须大小写不敏感，否则生产/测试行为分叉。
        assertThat(resolve("inherit", "opusplan", null, "PLAN"))
            .isEqualTo(PromptCaching.defaultOpusModel());
        assertThat(resolve("inherit", "haiku", null, "Plan"))
            .isEqualTo(PromptCaching.defaultSonnetModel());
    }

    // ---- [RF-6 ③] legacy opus 首方 remap + [1m] 保真（CC model.ts:477-483）----

    @Test
    @DisplayName("[RF-6 ③] legacy opus 4 个字符串全部 remap 到 defaultOpus（CC model.ts:477-483）")
    void legacyOpus_allFourStringsRemapToDefaultOpus() {
        // WHY: CC isLegacyOpusFirstParty 精确匹配 4 个 legacy Opus 4.0/4.1 显式串，
        //   firstParty + remap 未禁用 → 静默 remap 到 getDefaultOpusModel()（opus-4-6）。
        //   旧 Java parseUserSpecifiedModel 仅 trim → 保留 legacy 串（已下线，首方 API 400）。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("claude-opus-4-20250514", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("claude-opus-4-1-20250805", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("claude-opus-4-0", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("claude-opus-4-1", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("[RF-6 ③] legacy opus 带 [1m] 后缀 → remap 保留 [1m] 后缀（CC model.ts:483 has1mTag）")
    void legacyOpus_remapPreserves1mSuffix() {
        // WHY: CC model.ts:483 remap 结果 = getDefaultOpusModel() + (has1mTag ? '[1m]' : '')，
        //   [1m] 后缀顺延到 remap 后的 defaultOpus，防 1M 会话窗口塌缩。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("claude-opus-4-1[1m]", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6[1m]");
    }

    @Test
    @DisplayName("[RF-6 ③] 非 firstParty → legacy opus 原样保留（3P 无 4.6 容量，CC model.ts:482 gate）")
    void legacyOpus_nonFirstParty_noRemap() {
        // WHY: CC model.ts:482 gate getAPIProvider()==='firstParty'——3P provider 可能尚无 4.6 容量，
        //   legacy opus 原样通过。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("claude-opus-4-1", false, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-1");
    }

    @Test
    @DisplayName("[RF-6 ③] remap 被禁用 → legacy opus 原样保留（CC model.ts:480 opt-out 门）")
    void legacyOpus_remapDisabled_noRemap() {
        // WHY: CC model.ts:480 gate isLegacyModelRemapEnabled()（CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP opt-out）。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("claude-opus-4-1", true, false, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-1");
    }

    @Test
    @DisplayName("[RF-6 ③] 非 legacy opus 不 remap；裸 'opus' 别名展开为 opus 默认（[D-5]）")
    void nonLegacyOpusOrAlias_noRemap() {
        // WHY: isLegacyOpusFirstParty 精确匹配 4 个 legacy 串，opus-4-5 不在列表 → 原样保留。
        //   [D-5] 裸别名 'opus' 现展开为 opus 默认 'claude-opus-4-6'（CC model.ts:465），不再是受控残留。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("claude-opus-4-5", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-5");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("opus", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6");
    }

    // ---- [D-5] 别名展开（CC model.ts:456-470）----

    @Test
    @DisplayName("[D-5] 'opusplan' 别名 → Sonnet 默认（CC model.ts:459 Sonnet 默认，plan 模式才 Opus）")
    void opusplanAlias_expandsToSonnetDefault() {
        // WHY: opusplan 的默认档是 Sonnet（Opus 仅在 plan 模式由 getRuntimeMainLoopModel 解析），
        //   parseUserSpecifiedModel 层必须落在 Sonnet，否则 subagent 会错误拿到 Opus。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("opusplan", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("[D-5] 'sonnet' 别名 → Sonnet 默认（CC model.ts:461）")
    void sonnetAlias_expandsToSonnetDefault() {
        assertThat(AgentModelResolver.parseUserSpecifiedModel("sonnet", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("[D-5] 'haiku' 别名 → Haiku 默认（CC model.ts:463）")
    void haikuAlias_expandsToHaikuDefault() {
        assertThat(AgentModelResolver.parseUserSpecifiedModel("haiku", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    @DisplayName("[D-5] 'opus' 别名 → Opus 默认（CC model.ts:465）")
    void opusAlias_expandsToOpusDefault() {
        assertThat(AgentModelResolver.parseUserSpecifiedModel("opus", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("[D-5] 'best' 别名 → getBestModel()=Opus 默认（CC model.ts:467 + :100-102）")
    void bestAlias_expandsToBestModel() {
        // WHY: CC getBestModel() === getDefaultOpusModel()（model.ts:100-102），'best' 恒解析到 opus 默认档。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("best", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6");
    }

    @Test
    @DisplayName("[D-5] 别名带 [1m] 后缀 → 展开后保留 [1m]（CC model.ts:459-465 has1mTag 保真）")
    void aliasWith1mSuffix_preserves1m() {
        // WHY: CC has1mTag 把 [1m] 后缀顺延到展开后的默认档，防 1M 会话窗口塌缩到 200K。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("sonnet[1m]", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-sonnet-4-6[1m]");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("opus[1m]", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6[1m]");
    }

    @Test
    @DisplayName("[D-5] 别名大小写不敏感（CC model.ts:449 toLowerCase 归一后判别名）")
    void aliasCaseInsensitive_expandsRegardlessOfCase() {
        // WHY: CC normalizedModel = modelInputTrimmed.toLowerCase()（model.ts:449），
        //   'SONNET'/'Opus'/'Haiku' 都应命中别名并展开到小写默认档。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("SONNET", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-sonnet-4-6");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("Opus", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-opus-4-6");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("Haiku", true, true, "claude-opus-4-6"))
            .isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    @DisplayName("[D-5] 自定义模型名保留原大小写（CC model.ts:500-505 Azure/Foundry 部署 ID 大小写敏感）")
    void customModel_preservesOriginalCase() {
        // WHY: 非别名模型名（如 Azure Foundry 部署 ID）大小写敏感，CC 保留原大小写仅剥 [1m]。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("My-Custom-Model", true, true, "claude-opus-4-6"))
            .isEqualTo("My-Custom-Model");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("My-Custom-Model[1m]", true, true, "claude-opus-4-6"))
            .isEqualTo("My-Custom-Model[1m]");
    }

    @Test
    @DisplayName("[D-5] 公开单参入口委托共享真源展开别名（生产路径 SkillModelOverrideResolver 复用）")
    void publicSingleArg_delegatesAliasExpansion() {
        // WHY: 生产路径 parseUserSpecifiedModel(String) 委托 SkillModelOverrideResolver（单一真源），
        //   别名展开 + 大小写保留必须与包可见重载一致，防双实现漂移（RF-6 ③ 模式）。
        assertThat(AgentModelResolver.parseUserSpecifiedModel("sonnet"))
            .isEqualTo("claude-sonnet-4-6");
        assertThat(AgentModelResolver.parseUserSpecifiedModel("My-Custom-Model"))
            .isEqualTo("My-Custom-Model");
        assertThat(AgentModelResolver.parseUserSpecifiedModel(null))
            .isNull();
    }
}
