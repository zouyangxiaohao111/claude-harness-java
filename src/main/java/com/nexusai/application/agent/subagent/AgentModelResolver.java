package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.prompt.PromptCaching;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [H7-arch Phase 5 P5 D1] subagent effective model 解析器 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/model/agent.ts:37-95 getAgentModel}。
 *
 * <p><b>WHY（审计 D1）</b>: 原 {@code SubagentTool.getAgentModel} 仅 2 行
 * （{@code agent.model().orElse(defaultModel)}），缺失 CC 的完整解析链：
 * {@code CLAUDE_CODE_SUBAGENT_MODEL} env override / {@code 'inherit'} 运行时解析 /
 * {@code aliasMatchesParentTier}（裸家族别名继承父档位）/ tool-specified model 优先。
 * 注释假声称"对齐 CC"（Pattern #9）——本类按 CC 真源重构。
 *
 * <p><b>解析顺序（2026-09-03 用户拍板：tool model -> db -> env -> 继承）</b>:
 * <ol>
 *   <li>tool-specified model（显式指定最优先）→ {@link #aliasMatchesParentTier} 命中返回 parentModel，否则 parse</li>
 *   <li>settings.subagentModelId（DB 默认，用户"我的 db 就是 env"）→ {@link #parseUserSpecifiedModel}</li>
 *   <li>{@code CLAUDE_CODE_SUBAGENT_MODEL} env → {@link #parseUserSpecifiedModel}</li>
 *   <li>{@code agentModel ?? 'inherit'}</li>
 *   <li>{@code 'inherit'} → {@link #getRuntimeMainLoopModel}（运行时解析）</li>
 *   <li>aliasMatchesParentTier 命中 → parentModel（防降级）</li>
 *   <li>兜底 {@link #parseUserSpecifiedModel}</li>
 * </ol>
 *
 * <p><b>优先级 WHY（2026-09-03）</b>: 主代理引导 vision 子代理调 Agent 显式传
 * {@code model=deepseek-v4-flash-vision-exp}，原 settings.subagentModelId（DB 文本模型）最高优先级
 * 直接压过 tool model → 子代理用文本模型 Read pdf 被拒（日志实证 queryLoop 入口
 * {@code model=deepseek/deepseek-v4-flash}）。显式指定覆盖默认（工程常识）；db/env 仍作默认/全局兜底
 * （普通子代理不传 model 时生效）。
 *
 * <p><b>Bedrock 区域前缀继承降级说明</b>: CC 的 {@code getBedrockRegionPrefix/applyParentRegionPrefix}
 * 保证 subagent 继承父模型的跨区域 inference profile（数据驻留安全——IAM 权限按区域 profile 隔离）。
 * Java 侧当前无 Bedrock provider（LlmProvider 实现为 OpenAI/Anthropic 兼容），区域前缀逻辑降级为
 * no-op。但<strong>核心语义保留</strong>：用户显式指定的完整 model ID 自带区域前缀时原样保留
 * （CC 的 {@code applyParentRegionPrefix} 检查 {@code getBedrockRegionPrefix(originalSpec)}，
 * 防止静默数据驻留违规）。若未来接入 Bedrock，在此补充区域前缀继承。
 *
 * <p><b>Java 简化</b>: CC 返回解析后的 {@code Model} 对象（含 1m flag / 别名展开），Java 返回
 * 模型字符串（provider 层负责别名 → 具体 endpoint）。{@link #aliasMatchesParentTier} 用
 * {@link #getCanonicalName}（剥 [1m] + 小写）判断父档位，语义与 CC 等价。
 */
public final class AgentModelResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentModelResolver.class);

    private AgentModelResolver() {}

    /** CC agent.ts:25-27 getDefaultSubagentModel → 'inherit'（subagent 继承父线程模型）。 */
    public static final String DEFAULT_SUBAGENT_MODEL = "inherit";

    /**
     * 解析 subagent effective model · 对齐 CC agent.ts:37-95。
     *
     * <p>[W2-2] 无 settings.subagentModelId 参数的重载（等价传 null）——settings 值为空时
     * 仅走 env + 原解析链，供测试/旧调用点复用。
     *
     * @param agentModel          agent definition 配置的模型（可空 = 'inherit'）
     * @param parentModel         父线程/主循环当前模型（CC toolUseContext.options.mainLoopModel）
     * @param toolSpecifiedModel  工具显式指定的模型别名（可空；CC ModelAlias）
     * @param permissionMode      权限模式（可空；CC PermissionMode，plan 模式 opusplan 解析用）
     * @return effective model 字符串
     */
    public static String resolve(
            String agentModel,
            String parentModel,
            String toolSpecifiedModel,
            String permissionMode) {
        return resolve(agentModel, parentModel, toolSpecifiedModel, permissionMode, null);
    }

    /**
     * 解析 subagent effective model · 对齐 CC agent.ts:37-95 + settings.subagentModelId DB 承载。
     *
     * <p>[W2-2] settings.subagentModelId（DB settings 列，V25）为默认子代理模型（用户"我的 db 就是 env"）。
     * 解析链（2026-09-03 用户拍板：tool model -> db -> env -> 继承，显式指定覆盖默认）：
     * <ol>
     *   <li>tool-specified model（显式指定最优先）→ {@link #aliasMatchesParentTier} 命中返回 parentModel，否则 parse</li>
     *   <li>settings.subagentModelId（DB 默认）→ {@link #parseUserSpecifiedModel}</li>
     *   <li>{@code CLAUDE_CODE_SUBAGENT_MODEL} env → {@link #parseUserSpecifiedModel}</li>
     *   <li>{@code agentModel ?? 'inherit'}</li>
     *   <li>{@code 'inherit'} → {@link #getRuntimeMainLoopModel}（运行时解析）</li>
     *   <li>aliasMatchesParentTier 命中 → parentModel（防降级）</li>
     *   <li>兜底 {@link #parseUserSpecifiedModel}</li>
     * </ol>
     *
     * @param agentModel          agent definition 配置的模型（可空 = 'inherit'）
     * @param parentModel         父线程/主循环当前模型（CC toolUseContext.options.mainLoopModel）
     * @param toolSpecifiedModel  工具显式指定的模型别名（可空；CC ModelAlias）
     * @param permissionMode      权限模式（可空；CC PermissionMode，plan 模式 opusplan 解析用）
     * @param subagentModelId     settings.subagentModelId（DB，可空；非空时 DB 优先于 env）
     * @return effective model 字符串
     */
    public static String resolve(
            String agentModel,
            String parentModel,
            String toolSpecifiedModel,
            String permissionMode,
            String subagentModelId) {
        // 1. settings.subagentModelId DB 优先（W2-2；env 路 W4 清理）→ 2. env override · CC agent.ts:43-45
        return resolveWithEnv(agentModel, parentModel, toolSpecifiedModel, permissionMode,
            System.getenv("CLAUDE_CODE_SUBAGENT_MODEL"), subagentModelId);
    }

    /**
     * 带显式 env 的解析 · 测试隔离用（Java System.getenv 只读，无法在测试内设置）。
     *
     * <p>生产路径 {@link #resolve(String, String, String, String)} 传
     * {@code System.getenv("CLAUDE_CODE_SUBAGENT_MODEL")} + null settings；
     * 测试传可控 env（null = 无 override）验证非 env 分支 + env override 独立用例。
     */
    static String resolveWithEnv(
            String agentModel,
            String parentModel,
            String toolSpecifiedModel,
            String permissionMode,
            String envModel) {
        return resolveWithEnv(agentModel, parentModel, toolSpecifiedModel, permissionMode,
            envModel, null);
    }

    /**
     * 带显式 env + settings.subagentModelId 的解析 · 测试隔离用。
     *
     * <p>[W2-2] settings.subagentModelId（DB）优先于 env（本次接线 DB 优先；env 路 W4 清理）。
     */
    static String resolveWithEnv(
            String agentModel,
            String parentModel,
            String toolSpecifiedModel,
            String permissionMode,
            String envModel,
            String subagentModelId) {
        // [优先级 2026-09-03 用户拍板] tool model -> db(settings.subagentModelName) -> env -> 继承。
        //   WHY：主代理引导 vision 子代理调 Agent 显式传 model=deepseek-v4-flash-vision-exp，DB
        //   settings.subagentModelName（文本）原最高优先级直接压过 tool model → 子代理用文本模型
        //   Read pdf 被拒（日志实证 2026-09-03 queryLoop 入口 model=deepseek/deepseek-v4-flash）。
        //   显式指定覆盖默认（工程常识）；db/env 仍作默认/全局兜底（普通子代理不传 model 时生效）。
        // 1. tool-specified model 最优先 · CC agent.ts:70-76（显式覆盖默认）
        if (toolSpecifiedModel != null && !toolSpecifiedModel.isBlank()) {
            if (aliasMatchesParentTier(toolSpecifiedModel, parentModel)) {
                return parentModel;
            }
            return parseUserSpecifiedModel(toolSpecifiedModel);
        }
        // 2. settings.subagentModelId DB 默认（用户"我的 db 就是 env"——DB 承载默认子代理模型）· W2-2
        if (subagentModelId != null && !subagentModelId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentModelResolver] settings.subagentModelId 命中: {} → 走 parseUserSpecifiedModel",
                    subagentModelId);
            }
            return parseUserSpecifiedModel(subagentModelId);
        }
        // 3. env override · CC agent.ts:43-45
        if (envModel != null && !envModel.isBlank()) {
            return parseUserSpecifiedModel(envModel);
        }

        // 3. agentModel ?? 'inherit' · CC agent.ts:78
        String agentModelWithExp = (agentModel == null || agentModel.isBlank())
            ? DEFAULT_SUBAGENT_MODEL
            : agentModel;

        // 4. 'inherit' → 运行时解析 · CC agent.ts:80-88
        if (DEFAULT_SUBAGENT_MODEL.equalsIgnoreCase(agentModelWithExp)) {
            return getRuntimeMainLoopModel(parentModel, permissionMode);
        }

        // 5. 裸家族别名匹配父档位 → 继承父模型精确串（防降级）· CC agent.ts:90-92
        if (aliasMatchesParentTier(agentModelWithExp, parentModel)) {
            return parentModel;
        }

        // 6. 兜底 parse · CC agent.ts:93-94
        return parseUserSpecifiedModel(agentModelWithExp);
    }

    /**
     * 裸家族别名（opus/sonnet/haiku）是否匹配父模型档位 · 对齐 CC agent.ts:110-122。
     *
     * <p>仅裸家族别名匹配；{@code opus[1m]}/best/opusplan 语义超出"同档位"故不匹配（CC 注释）。
     */
    private static boolean aliasMatchesParentTier(String alias, String parentModel) {
        String canonical = getCanonicalName(parentModel);
        return switch (alias == null ? "" : alias.toLowerCase()) {
            case "opus" -> canonical.contains("opus");
            case "sonnet" -> canonical.contains("sonnet");
            case "haiku" -> canonical.contains("haiku");
            default -> false;
        };
    }

    /**
     * 模型规范名（档位判断用）· 简化对齐 CC getCanonicalName（剥 [1m] + 小写）。
     *
     * <p>Java 端 parentModel 可能是完整 ID（如 claude-opus-4-6-...），剥 [1m] 后
     * contains('opus') 语义与 CC 一致。
     */
    private static String getCanonicalName(String model) {
        if (model == null) return "";
        String m = model.trim().toLowerCase();
        if (m.endsWith("[1m]")) {
            m = m.substring(0, m.length() - "[1m]".length());
        }
        return m;
    }

    /**
     * 'inherit' 运行时解析 · 对齐 CC getRuntimeMainLoopModel（model.ts:145-167）。
     *
     * <p>CC 行为（model.ts:152-164）：
     * <ol>
     *   <li>用户 setting='opusplan' && permissionMode='plan' && !exceeds200kTokens →
     *       {@code getDefaultOpusModel()}（plan 模式 Opus 不带 [1m]）</li>
     *   <li>用户 setting='haiku' && permissionMode='plan' → {@code getDefaultSonnetModel()}</li>
     *   <li>否则返回 {@code mainLoopModel}</li>
     * </ol>
     *
     * <p><b>Java 映射（G-7 修复）</b>：
     * <ul>
     *   <li>{@code getUserSpecifiedModelSetting()}（model.ts:61-78，用户原始模型 setting）在
     *       subagent 'inherit' 路径即调用方传入的 {@code parentModel}（Java {@code getModelForCall()}
     *       读 session-override / skill / startup-flag / settings 层原始字符串，未经别名展开）——
     *       故本方法直接用 {@code mainLoopModel} 与 'opusplan'/'haiku' 比较，语义等价。</li>
     *   <li>CC subagent 路径（agent.ts:83）恒传 {@code exceeds200kTokens: false} →
     *       {@code !exceeds200kTokens} 恒真，Java 无需 200k 探测（query.ts:572 主循环路径的
     *       exceeds200k 计算不属于 subagent 'inherit' 范围）。</li>
     *   <li>{@code getDefaultOpusModel/getDefaultSonnetModel} → {@link PromptCaching#defaultOpusModel()/
     *       defaultSonnetModel()}（DB settings.strongModelId/mediumModelId 覆盖 + 默认 opus-4-6/sonnet-4-6，
     *       单一真源防双实现漂移）。</li>
     * </ul>
     */
    private static String getRuntimeMainLoopModel(String mainLoopModel, String permissionMode) {
        // CC model.ts:152-159 — opusplan uses Opus in plan mode without [1m] suffix.
        //   subagent 路径 exceeds200kTokens=false 恒成立 → 仅需 setting + plan 双条件。
        if ("opusplan".equalsIgnoreCase(mainLoopModel) && isPlanMode(permissionMode)) {
            String opus = PromptCaching.defaultOpusModel();
            if (log.isDebugEnabled()) {
                log.debug("[AgentModelResolver] getRuntimeMainLoopModel plan-upgrade: 父模型='{}'+plan → "
                        + "defaultOpus={}（CC model.ts:152-159）", mainLoopModel, opus);
            }
            return opus;
        }
        // CC model.ts:161-164 — sonnetplan by default（haiku + plan → defaultSonnet）。
        if ("haiku".equalsIgnoreCase(mainLoopModel) && isPlanMode(permissionMode)) {
            String sonnet = PromptCaching.defaultSonnetModel();
            if (log.isDebugEnabled()) {
                log.debug("[AgentModelResolver] getRuntimeMainLoopModel plan-upgrade: 父模型='{}'+plan → "
                        + "defaultSonnet={}（CC model.ts:161-164）", mainLoopModel, sonnet);
            }
            return sonnet;
        }
        return mainLoopModel;
    }

    /**
     * permissionMode 是否为 plan · 对齐 CC {@code permissionMode === 'plan'}（model.ts:155/162）。
     *
     * <p>大小写不敏感（容纳调用方 {@code PermissionMode.PLAN.name()='PLAN'} 与测试 'plan'）；
     * null/其他模式 → false（CC permissionMode ?? 'default' 语义，agent.ts:84）。
     */
    private static boolean isPlanMode(String permissionMode) {
        return "plan".equalsIgnoreCase(permissionMode);
    }

    /**
     * 用户指定模型规范化 · 对齐 CC parseUserSpecifiedModel（model.ts:445-506）。
     *
     * <p>CC 解析链：trim + 小写 + 剥 [1m] 后缀（has1mTag 保真）→ isModelAlias 别名展开
     * （opusplan/sonnet→defaultSonnet、haiku→defaultHaiku、opus→defaultOpus、best→getBestModel）→
     * legacy opus 首方 remap → 非别名保留原大小写（Azure/Foundry 部署 ID 大小写敏感）。
     *
     * <p>[D-5 别名展开] 委托 {@link SkillModelOverrideResolver#parseUserSpecifiedModel(String)}
     * （别名展开 + legacy opus remap + [1m] 保真 + 大小写保留，单一真源防双实现漂移，RF-6 ③ 同一模式）。
     * 原本方法仅 trim + legacy opus remap，别名展开为受控残留（open-decisions #12），本 session 补齐。
     *
     * <p>P1-5 由 private 提升为 public static：供 {@link com.nexusai.application.agent.skill.SkillsLoader}
     * 复用（frontmatter model 字段解析，CC loadSkillsDir.ts:221-226）。
     */
    public static String parseUserSpecifiedModel(String model) {
        if (model == null) return null;
        if (log.isDebugEnabled()) {
            log.debug("[AgentModelResolver] parseUserSpecifiedModel 入口: model={}", model);
        }
        // [D-5] CC model.ts:445-506 全量解析（别名展开 + legacy remap + [1m] 保真 + 大小写保留）。
        String resolved = SkillModelOverrideResolver.parseUserSpecifiedModel(model);
        if (log.isDebugEnabled()) {
            log.debug("[AgentModelResolver] parseUserSpecifiedModel 解析完成: {} → {}", model, resolved);
        }
        return resolved;
    }

    /**
     * 包可见重载（测试隔离）· 显式注入 legacy opus remap 门控三参。
     *
     * <p>模式同 {@link #resolveWithEnv}（env 注入隔离，不依赖测试机 ambient env）。
     *
     * @param model              原始模型字符串
     * @param firstParty         是否 firstParty provider（CC original: getAPIProvider()==='firstParty'）
     * @param legacyRemapEnabled 是否启用 legacy opus remap（CC original: isLegacyModelRemapEnabled()）
     * @param defaultOpus        默认 Opus 模型（CC original: getDefaultOpusModel()，model.ts:483）
     */
    static String parseUserSpecifiedModel(String model, boolean firstParty, boolean legacyRemapEnabled,
            String defaultOpus) {
        if (model == null) return null;
        return SkillModelOverrideResolver.parseUserSpecifiedModel(model, firstParty, legacyRemapEnabled, defaultOpus);
    }
}
