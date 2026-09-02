package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * System Prompt 组装器 · 对齐 CC {@code getSystemPrompt} 常规分支（prompts.ts:444-577）。
 *
 * <p>组装序（CC 返回数组，prompts.ts:562-576）：
 * <ol>
 *   <li>7 静态 section（固定顺序，I-11）：intro / system / doingTasks(门控) / actions /
 *       usingYourTools / toneAndStyle / outputEfficiency（:562-571）</li>
 *   <li>doingTasks 门控：{@code outputStyleConfig === null || keepCodingInstructions === true}
 *       （:564-566）</li>
 *   <li>boundary 独立数组元素：仅 shouldUseGlobalCacheScope 为真时插入（:573，I-7）</li>
 *   <li>registry 解析结果（buildDynamicSections 注册 → resolveAll，I-12）</li>
 *   <li>null filter：{@code .filter(s => s !== null)}（:576，I-3 null 段不出现）</li>
 * </ol>
 *
 * <p><b>CLAUDE_CODE_SIMPLE / PROACTIVE 分支</b>：N/A（OPD-SP-20/21，Java 无 env 门控）。
 *
 * <p><b>组件层</b>：已接线 —— 4 消费方经本类组装（LlmAgentLoop s10 :3159-3163 /
 * CacheSharingParamsBuilder :114-118 / ContextAnalyzeService :354-359 /
 * ResumeService :415-419，SP-02 §10-6 EVD-SP02-04）。缓存由 run 级
 * {@link SystemPromptSectionCache} 注入（挂 AgentState）；boundary 门控由
 * {@link BooleanSupplier} 注入（Java 后端 provider 判定接线，默认不插入）。
 */
public final class SystemPromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptAssembler.class);

    /**
     * 动态/静态缓存边界标记 · 对齐 CC {@code SYSTEM_PROMPT_DYNAMIC_BOUNDARY}
     * （prompts.ts:114-115）。发送前由 splitSysPromptPrefix 剥离（I-8，API 层）。
     */
    public static final String SYSTEM_PROMPT_DYNAMIC_BOUNDARY = "__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__";

    private final SystemPromptSectionCache cache;
    private final BooleanSupplier globalCacheScopeGate;

    /**
     * @param cache               会话级 section 缓存（挂 AgentState，IMP-SP-08 接线）
     * @param globalCacheScopeGate boundary 门控 · 对齐 CC {@code shouldUseGlobalCacheScope()}
     *                             （betas.ts:227-233，firstParty && !disableBetas）
     */
    public SystemPromptAssembler(SystemPromptSectionCache cache, BooleanSupplier globalCacheScopeGate) {
        this.cache = cache;
        this.globalCacheScopeGate = globalCacheScopeGate;
    }

    /**
     * 便捷构造：boundary 门控默认关闭（Java 后端非 firstParty provider，CC 仅 firstParty 插入 boundary）。
     *
     * @param cache 会话级 section 缓存
     */
    public SystemPromptAssembler(SystemPromptSectionCache cache) {
        this(cache, () -> false);
    }

    /**
     * boundary 门最终解析 · [SP-14] DB settings.system_prompt_boundary_enabled 覆盖 firstParty
     * 判定链（对齐 CC prompts.ts:572-573 BOUNDARY MARKER + shouldUseGlobalCacheScope 门；
     * betas.ts:227-233 firstParty && !disableBetas）。DB 有值 → 用之；null → 回落
     * globalCacheScopeGate（原 firstParty 判定，零行为变化）。
     * systemPromptBoundaryEnabled 静态读源 = PromptAlignSettingsResolver.staticSystemPromptBoundaryEnabled()
     * （ToolRegistrationConfig 接线 setStaticResolver 同源，无分叉）。
     *
     * @return boundary 是否插入
     */
    private boolean resolveBoundaryGate() {
        Boolean db = com.nexusai.application.agent.prompt.PromptAlignSettingsResolver.staticSystemPromptBoundaryEnabled();
        boolean gate = db != null ? db : globalCacheScopeGate.getAsBoolean();
        if (db != null && log.isDebugEnabled()) {
            log.debug("[SystemPromptAssembler] SP-14 DB system_prompt_boundary_enabled={} 覆盖 firstParty 判定（globalCacheScopeGate 原值={}）→ boundary={}",
                db, globalCacheScopeGate.getAsBoolean(), gate);
        }
        return gate;
    }

    /**
     * 组装 system prompt · 对齐 CC getSystemPrompt 常规分支返回数组。
     *
     * @param input 组装输入（enabledTools/model/additionalWorkingDirs/mcpClients/outputStyleConfig/
     *              skillToolCommands/language/memoryLoader）
     * @return 品牌化 SystemPrompt（elements 含 null filter 后的最终发送序数组）
     */
    public SystemPrompt assemble(SystemPromptAssemblyInput input) {
        // 1. registry 动态 sections：buildDynamicSections 注册 → resolveAll 并行解析（I-12）
        List<SystemPromptSection> dynamicSections = SystemPromptSections.buildDynamicSections(input);
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        dynamicSections.forEach(registry::register);
        List<String> resolved = registry.resolveAll(cache);

        // 2. 7 静态固定序（I-11）+ doingTasks 门控 + boundary 门控 + 动态 + null filter
        //    [SP-14] boundary 门经 resolveBoundaryGate（DB system_prompt_boundary_enabled 覆盖 firstParty）
        boolean boundaryGate = resolveBoundaryGate();
        List<String> elements = new ArrayList<>();
        elements.add(StaticPromptSections.simpleIntroSection(input.outputStyleConfig()));
        elements.add(StaticPromptSections.simpleSystemSection());
        boolean doingTasksInjected = input.outputStyleConfig() == null
            || Boolean.TRUE.equals(input.outputStyleConfig().keepCodingInstructions());
        if (doingTasksInjected) {
            elements.add(StaticPromptSections.simpleDoingTasksSection());
        } else {
            elements.add(null); // 门控剔除 → 被 null filter 移除（CC :565-567）
        }
        elements.add(StaticPromptSections.actionsSection());
        elements.add(StaticPromptSections.usingYourToolsSection(input.enabledTools()));
        elements.add(StaticPromptSections.simpleToneAndStyleSection());
        elements.add(StaticPromptSections.outputEfficiencySection());
        if (boundaryGate) {
            elements.add(SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        }
        elements.addAll(resolved);

        List<String> filtered = elements.stream().filter(Objects::nonNull).toList();
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptAssembler] 组装完成: 输入 {} 元素（含 null 占位），filter 后 {} 元素；"
                    + "doingTasks={}, boundary={}",
                elements.size(), filtered.size(),
                doingTasksInjected,
                boundaryGate);
        }
        return SystemPrompt.from(filtered);
    }
}
