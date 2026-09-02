package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.subagent.AgentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 有效系统提示 5 层优先级调度 · 对齐 CC {@code buildEffectiveSystemPrompt}
 * （Open-ClaudeCode/src/utils/systemPrompt.ts:41-123）。
 *
 * <p>优先级（CC 实际 TS 源码行为，非注释）：
 * <ol>
 *   <li><b>override</b>（:56-58）：非空即早退 {@code asSystemPrompt([overrideSystemPrompt])} ——
 *       替换一切、不含 append、不触发 default 组装（I-4/I-5）</li>
 *   <li><b>coordinator</b>（:62-75）：{@code feature('COORDINATOR_MODE') && isEnvTruthy(...) &&
 *       !mainThreadAgentDefinition} → {@code asSystemPrompt([getCoordinatorSystemPrompt(),
 *       ...(append?[append]:[])])}。Java 门控 = {@link EffectivePromptOptions#coordinatorModeEnabled()}
 *       （LlmAgentLoop 经 resolver.coordinatorModeEnabled() 求值，null→回落
 *       CoordinatorMode.isCoordinatorMode()）；Java 无主线程 agent 定义（def 恒 null）→
 *       {@code !mainThreadAgentDefinition} 恒真（CC 语义保持）。</li>
 *   <li><b>agent</b>（:77-83）：mainThreadAgentDefinition 非空 → agentSystemPrompt（built-in 走
 *       options 变体 / custom 走无参变体，Java 统一 {@code getSystemPrompt(modelId, dirs)}）。
 *       Java 门控 = {@link EffectivePromptOptions#agentMainThreadEnabled()}
 *       （resolver.agentMainThreadEnabled()，null→false）；主循环无 /init 主线程 agent 概念 →
 *       def supplier 默认 null，分支休眠（扩展点）。</li>
 *   <li><b>proactive</b>（:103-113）：agentSystemPrompt 非空 && (PROACTIVE|KAIROS) &&
 *       isProactiveActive → {@code asSystemPrompt([...default, "\n# Custom Agent Instructions\n" +
 *       agentSystemPrompt, ...(append?[append]:[])])}。Java 门控 =
 *       {@link EffectivePromptOptions#proactiveEnabled()}（resolver.proactiveEnabled()，null→false）。
 *       agentSystemPrompt 来源与 agent 分支共享同一 def supplier → 默认休眠。</li>
 *   <li><b>custom 替换 default</b>（:115-122 三元 + queryContext.ts:62-63 短路）：agentSystemPrompt
 *       undefined 且 custom 非空时 default 完全不出现在结果且跳过 default 组装（I-6/I-13）</li>
 *   <li><b>default + append</b>（:120-121）：无 override/custom/agent 时走 default 组装，append 恒末尾追加</li>
 * </ol>
 *
 * <p>appendSystemPrompt 恒末尾（:121 / QueryEngine.ts:324），且仅非 override 场景追加（I-5）。
 * memoryMechanicsPrompt 仅 custom/default 路径注入（QueryEngine.ts:316-325 组装在
 * buildEffectiveSystemPrompt 之外），coordinator/agent/proactive 分支不含（CC 分支字面量无该项）。
 *
 * <p><b>组件层</b>：已接线 —— 4 消费方直调本类调度（LlmAgentLoop s10 :3159-3163 /
 * CacheSharingParamsBuilder :114-118 / ContextAnalyzeService :354-359 /
 * ResumeService :415-419，SP-02 §10-6 EVD-SP02-04）。default 组装经 {@link Supplier} 惰性
 * 注入，custom 命中时 supplier 不被调用（等价 CC queryContext.ts:62-63 在调度前将
 * defaultSystemPrompt 短路为 {@code Promise.resolve([])}）。
 */
public final class EffectiveSystemPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(EffectiveSystemPromptBuilder.class);

    private EffectiveSystemPromptBuilder() {
    }

    /**
     * 分支门控 + 主线程 agent 定义选项 · SP-03/04/02 批次 F 新增。
     *
     * <p>对齐 CC buildEffectiveSystemPrompt 的输入（systemPrompt.ts:41-55）：{@code
     * mainThreadAgentDefinition} 为唯一新增数据，其余三个门控位是 CC feature/env 判定
     * （coordinator :63-65 / proactive :105-106 / agent :77）的 Java DB 化等价
     * （PromptAlignSettingsResolver 实时读源，null 回落原判定链）。
     *
     * @param mainThreadAgentDefinition 主线程 agent 定义惰性源 · CC original:
     *                                  {@code mainThreadAgentDefinition}（systemPrompt.ts:49/77-83）。
     *                                  Java 主循环无 /init 主线程 agent 概念 → LlmAgentLoop 传
     *                                  {@code null}（分支休眠扩展点）；null 或 get() 返回 null → agent
     *                                  分支不参与（落 custom/default 三元，现行为零变化）。
     * @param agentMainThreadEnabled    agent 分支门控 · CC original:
     *                                  mainThreadAgentDefinition 非空即参与（:77）；Java 侧
     *                                  resolver.agentMainThreadEnabled()（settings 列，null→false），
     *                                  门控真且 def 非 null 才产生 agentSystemPrompt。
     * @param proactiveEnabled          proactive 分支门控 · CC original:
     *                                  {@code feature('PROACTIVE') || feature('KAIROS') &&
     *                                  isProactiveActive_SAFE_TO_CALL_ANYWHERE()}（:105-106）；
     *                                  Java 侧 resolver.proactiveEnabled()（null→false，CC 3P 默认不激活）。
     * @param coordinatorModeEnabled    coordinator 分支门控 · CC original:
     *                                  {@code feature('COORDINATOR_MODE') &&
     *                                  isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE)}（:63-65）；
     *                                  Java 侧 resolver.coordinatorModeEnabled()（null→回落
     *                                  CoordinatorMode.isCoordinatorMode()，feature+env 双真）。
     * @param modelId                   内置 agent env 块 modelDescription 渲染入参 · CC original:
     *                                  resolvedAgentModel（runAgent.ts:340，逐调用传递）；仅 agent
     *                                  分支命中时消费（休眠，LlmAgentLoop 传 null）。
     * @param additionalWorkingDirs     内置 agent env 块 Additional working directories 渲染入参 ·
     *                                  CC original: additionalWorkingDirectories（runAgent.ts:504-506）；
     *                                  仅 agent 分支命中时消费（休眠）。
     */
    public record EffectivePromptOptions(
        Supplier<AgentDefinition> mainThreadAgentDefinition,
        boolean agentMainThreadEnabled,
        boolean proactiveEnabled,
        boolean coordinatorModeEnabled,
        String modelId,
        List<String> additionalWorkingDirs
    ) {

        /**
         * 默认关闭选项 · 既有 4/5 参调用零改动迁移：三个分支门控全关 + def null →
         * 仅 custom/default 三元路径（现行为零变化）。
         *
         * @return {@code (null, false, false, false, null, [])}
         */
        public static EffectivePromptOptions disabled() {
            return new EffectivePromptOptions(null, false, false, false, null, List.of());
        }
    }

    /**
     * 按 CC 5 层优先级调度组装有效系统提示。
     *
     * @param assemble              default 组装入口（惰性；仅 override/custom/agent 均缺省时调用）。
     *                              CC original: {@code defaultSystemPrompt}
     *                              （systemPrompt.ts:120 三元 / queryContext.ts:62-63 短路）。
     * @param overrideSystemPrompt  override 提示（非空即早退替换一切，不含 append）。
     *                              CC original: {@code overrideSystemPrompt}（systemPrompt.ts:56-58）。
     * @param customSystemPrompt    custom 提示（非空时替换 default，跳过 default 组装）。
     *                              CC original: {@code customSystemPrompt}（systemPrompt.ts:118-119）。
     * @param memoryMechanicsPrompt 记忆机制提示（仅 custom/default 路径注入 · CC original:
     *                              QueryEngine.ts:316-325 memoryMechanicsPrompt =
     *                              {@code customPrompt !== undefined && hasAutoMemPathOverride()
     *                              ? loadMemoryPrompt() : null}，位于 custom 与 append 之间）。
     *                              null/空 = 不注入。
     * @param appendSystemPrompt    恒末尾追加提示（仅非 override 场景）。
     *                              CC original: {@code appendSystemPrompt}（systemPrompt.ts:121）。
     * @param options               SP-03/04/02 分支门控选项（null → {@link EffectivePromptOptions#disabled()}）
     * @return 品牌化 SystemPrompt（元素序即发送序）
     */
    public static SystemPrompt build(
            Supplier<SystemPrompt> assemble,
            String overrideSystemPrompt,
            String customSystemPrompt,
            String memoryMechanicsPrompt,
            String appendSystemPrompt,
            EffectivePromptOptions options) {

        EffectivePromptOptions opt = options != null ? options : EffectivePromptOptions.disabled();

        // ── 第 1 层：override 早退（CC systemPrompt.ts:56-58，I-4/I-5）──
        // 非空即返 [override]，替换一切：不含 append、不触发 default 组装。
        // 空串 '' 按 JS truthiness 判 falsy（isEmpty 匹配；不落 isBlank，' ' 判 truthy 与 CC 一致）
        if (overrideSystemPrompt != null && !overrideSystemPrompt.isEmpty()) {
            log.info("[EffectiveSystemPromptBuilder] overrideSystemPrompt 非空，早退返回 [override]：替换一切，不触发 default 组装、不含 append（CC systemPrompt.ts:56-58）");
            return SystemPrompt.from(List.of(overrideSystemPrompt));
        }

        // 主线程 agent 定义惰性解析（一次性；coordinator/agent/proactive 三分支共享）
        // CC: mainThreadAgentDefinition（systemPrompt.ts:49）为直接值；Java 以 Supplier 承载
        //   惰性构造（主循环无来源 → null supplier，分支休眠）。
        AgentDefinition mainDef = opt.mainThreadAgentDefinition() != null
            ? opt.mainThreadAgentDefinition().get()
            : null;

        // ── 第 2 层：coordinator 分支（CC systemPrompt.ts:62-75）──
        // 条件 coordinatorGate && !mainThreadAgentDefinition（Java def 恒 null → 恒真）。
        // 返回 [getCoordinatorSystemPrompt(), ...(append?[append]:[])]，无 memoryMechanics。
        if (opt.coordinatorModeEnabled() && mainDef == null) {
            if (log.isDebugEnabled()) {
                log.debug("[EffectiveSystemPromptBuilder] coordinatorModeEnabled 命中，返回 [getCoordinatorSystemPrompt(), ...(append?[append]:[])]（CC systemPrompt.ts:71-74）");
            }
            List<String> coordinatorPrompt = new ArrayList<>();
            coordinatorPrompt.add(com.nexusai.application.agent.coordinator.CoordinatorMode.getCoordinatorSystemPrompt());
            if (appendSystemPrompt != null && !appendSystemPrompt.isEmpty()) {
                coordinatorPrompt.add(appendSystemPrompt);
            }
            return SystemPrompt.from(coordinatorPrompt);
        }

        // ── 第 3 层：agent 分支（CC systemPrompt.ts:77-83）──
        // gate 真且 def 非 null → agentSystemPrompt=def.getSystemPrompt(modelId, additionalWorkingDirs)。
        // Java 主循环无 /init 概念 → LlmAgentLoop 传 null supplier → agentSystemPrompt 恒 undefined，
        //   分支休眠（扩展点，task-register 注记）。
        String agentSystemPrompt = null;
        if (opt.agentMainThreadEnabled() && mainDef != null) {
            agentSystemPrompt = mainDef.getSystemPrompt(opt.modelId(), opt.additionalWorkingDirs());
            if (log.isDebugEnabled()) {
                log.debug("[EffectiveSystemPromptBuilder] agentMainThreadEnabled 命中，agentSystemPrompt 已生成（长度 {}）", agentSystemPrompt.length());
            }
        }

        // ── 第 4 层：proactive 分支（CC systemPrompt.ts:103-113）──
        // agentSystemPrompt 非空 && proactiveGate → [...default, "\n# Custom Agent Instructions\n"+agent，
        //   ...(append?[append]:[])]（agent 指令追加在 default 之上，非替换）。
        // 共享 agent 分支 def supplier → 默认休眠（LlmAgentLoop 不传 def）。
        if (agentSystemPrompt != null && opt.proactiveEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[EffectiveSystemPromptBuilder] proactive 命中，默认+agent 追加（CC systemPrompt.ts:108-112）");
            }
            long startNanos = System.nanoTime();
            SystemPrompt base = assemble.get();
            List<String> merged = new ArrayList<>(base.elements());
            merged.add("\n# Custom Agent Instructions\n" + agentSystemPrompt);
            if (appendSystemPrompt != null && !appendSystemPrompt.isEmpty()) {
                merged.add(appendSystemPrompt);
            }
            if (log.isDebugEnabled()) {
                log.debug("[EffectiveSystemPromptBuilder] proactive 组装完成: default {} 元素 + agent 追加（耗时 {} ms）",
                    base.elements().size(), (System.nanoTime() - startNanos) / 1_000_000);
            }
            return SystemPrompt.from(merged);
        }

        // ── 第 5 层前半：agent/custom 替换 default（CC systemPrompt.ts:115-122 三元 +
        //    queryContext.ts:62-63 短路）──
        // agentSystemPrompt 优先于 custom 优先于 default；custom 非空时 default 完全不出现在
        // 结果，且跳过 default 组装（I-6/I-13）
        SystemPrompt base;
        if (agentSystemPrompt != null) {
            if (log.isDebugEnabled()) {
                log.debug("[EffectiveSystemPromptBuilder] agentSystemPrompt 非空，替换 default（CC systemPrompt.ts:116-117）");
            }
            base = SystemPrompt.from(List.of(agentSystemPrompt));
        } else if (customSystemPrompt != null && !customSystemPrompt.isEmpty()) {
            log.info("[EffectiveSystemPromptBuilder] customSystemPrompt 非空，替换 default：跳过 default 组装（CC queryContext.ts:62-63 短路）");
            base = SystemPrompt.from(List.of(customSystemPrompt));
        } else {
            // default 组装（惰性调用；CC systemPrompt.ts:120）
            long startNanos = System.nanoTime();
            base = assemble.get();
            if (log.isDebugEnabled()) {
                log.debug("[EffectiveSystemPromptBuilder] 无 override/custom/agent，调用 assemble 组装 default（CC systemPrompt.ts:120）耗时 {} ms，元素数 {}",
                        (System.nanoTime() - startNanos) / 1_000_000, base.elements().size());
            }
        }

        // ── 第 5 层中段：memoryMechanicsPrompt（仅 custom/default 路径 · CC QueryEngine.ts:316-325，
        //   位于 custom 与 append 之间）──
        List<String> merged = new ArrayList<>(base.elements());
        if (memoryMechanicsPrompt != null && !memoryMechanicsPrompt.isEmpty()) {
            log.info("[EffectiveSystemPromptBuilder] memoryMechanicsPrompt 非空，注入 custom 与 append 之间（CC QueryEngine.ts:316-325）");
            merged.add(memoryMechanicsPrompt);
        }

        // ── 第 5 层后半：append 恒末尾追加（CC systemPrompt.ts:121 / QueryEngine.ts:324，I-5）──
        if (appendSystemPrompt != null && !appendSystemPrompt.isEmpty()) {
            log.info("[EffectiveSystemPromptBuilder] appendSystemPrompt 非空，恒末尾追加（CC systemPrompt.ts:121）");
            merged.add(appendSystemPrompt);
            return SystemPrompt.from(merged);
        }
        return merged.size() == base.elements().size() ? base : SystemPrompt.from(merged);
    }

    /**
     * 5 参便捷构造（SP-03/04/02 分支全部关闭）· 供既有调用点零改动迁移
     * （LlmAgentLoop/CacheSharingParamsBuilder/ContextAnalyzeService/ResumeService）。
     */
    public static SystemPrompt build(
            Supplier<SystemPrompt> assemble,
            String overrideSystemPrompt,
            String customSystemPrompt,
            String memoryMechanicsPrompt,
            String appendSystemPrompt) {
        return build(assemble, overrideSystemPrompt, customSystemPrompt, memoryMechanicsPrompt,
            appendSystemPrompt, EffectivePromptOptions.disabled());
    }

    /**
     * 4 参便捷构造（无 memoryMechanicsPrompt + SP-03/04/02 分支全部关闭）· 供非 QueryEngine 组装路径
     * （CacheSharingParamsBuilder/ContextAnalyzeService/ResumeService 等）使用 ——
     * CC 的 memoryMechanics 注入仅存在于 QueryEngine.ts（主查询链），这些路径等价 undefined。
     */
    public static SystemPrompt build(
            Supplier<SystemPrompt> assemble,
            String overrideSystemPrompt,
            String customSystemPrompt,
            String appendSystemPrompt) {
        return build(assemble, overrideSystemPrompt, customSystemPrompt, null, appendSystemPrompt);
    }
}
