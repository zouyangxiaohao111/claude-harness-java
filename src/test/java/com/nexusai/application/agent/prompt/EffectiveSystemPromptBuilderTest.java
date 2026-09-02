package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.prompt.EffectiveSystemPromptBuilder.EffectivePromptOptions;
import com.nexusai.application.agent.subagent.AgentDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5 层优先级调度意图测试 · 对齐 CC {@code buildEffectiveSystemPrompt}（systemPrompt.ts:41-123）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：优先级序是 CC 行为契约——override 早退替换一切（I-4/I-5，
 * :56-58）、custom 替换 default 且跳过 default 组装（I-6/I-13，:118-119 + queryContext.ts:62-63 短路）、
 * append 恒末尾（:121）。测试钉死优先级与短路，防止调度器回归到"custom 追加在 default 之后"或
 * "override 场景仍带 append"等与 CC 相悖的实现。
 *
 * <p>[批次 F 返工] 新增 SP-02/03/04 三分支覆盖：coordinator 门（:62-75）→
 * {@code [getCoordinatorSystemPrompt(), ...(append?[append]:[])]}；agent 门（:77-83）→
 * agentSystemPrompt 替换 default；proactive 门（:103-113）→ default 之上追加
 * {@code "\n# Custom Agent Instructions\n"+agent}。这三分支为批次 F 核心新行为，
 * 此前零自动化覆盖而 coordinator 分支在生产可被 DB 门激活 —— 必须钉死分支触发/休眠语义
 * （门控位 + def supplier 组合），防止调度回归。
 */
class EffectiveSystemPromptBuilderTest {

    private static final List<String> DEFAULT_ELEMENTS = List.of("DEFAULT-1", "DEFAULT-2", "DEFAULT-3");

    /** 内置 agent 定义 · 固定 systemPromptFn 输出（SP-03/04 测试用 Supplier<AgentDefinition> 来源）。 */
    private static AgentDefinition builtinAgent(String prompt) {
        return AgentDefinition.BuiltInAgentDefinition.builder(
            "investigator", "when investigating auth bugs",
            (modelId, dirs) -> prompt).build();
    }

    /** 捕获入参的内置 agent 定义 · 验证 getSystemPrompt(modelId, dirs) 逐调用下传（CC runAgent.ts:340/504-506）。 */
    private static AgentDefinition capturingAgent(BiFunction<String, List<String>, String> fn) {
        return AgentDefinition.BuiltInAgentDefinition.builder("investigator", "when", fn).build();
    }

    /**
     * 计数 Supplier：记录 assemble 被调用次数（验证短路），返回固定 default 数组。
     */
    private static final class CountingSupplier implements Supplier<SystemPrompt> {
        private final AtomicInteger calls = new AtomicInteger();
        private final SystemPrompt value;

        CountingSupplier() {
            this(DEFAULT_ELEMENTS);
        }

        CountingSupplier(List<String> elements) {
            this.value = SystemPrompt.from(elements);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public SystemPrompt get() {
            calls.incrementAndGet();
            return value;
        }
    }

    // ── I-4/I-5：override 早退 ──

    @Test
    @DisplayName("I-4/I-5：override 非空 → 输出恰为 [override]，不含 append，assemble 不被调用（systemPrompt.ts:56-58）")
    void override_nonEmpty_replacesEverything() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, "OVERRIDE", "CUSTOM", "APPEND");

        assertThat(prompt.elements()).as("override 替换一切，输出恰为 [override]").containsExactly("OVERRIDE");
        assertThat(prompt.elements()).as("I-5：override 场景不含 append").doesNotContain("APPEND");
        assertThat(assemble.calls()).as("override 早退，default 组装不被触发").isZero();
    }

    @Test
    @DisplayName("I-4：override 早退优先于 custom —— override 非空时 custom 也不出现（systemPrompt.ts:56 早于 :115 三元）")
    void override_takesPrecedenceOverCustom() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, "OVERRIDE", "CUSTOM", null);

        assertThat(prompt.elements()).as("override 优先级最高，custom 被替换").containsExactly("OVERRIDE");
        assertThat(prompt.elements()).as("custom 不出现在结果").doesNotContain("CUSTOM");
    }

    // ── I-6/I-13：custom 替换 default + 短路 ──

    @Test
    @DisplayName("I-6/I-13：custom 非空 → 输出恰为 [custom, append?]，default 逐元素不出现在结果，assemble 不调用（queryContext.ts:62-63 短路）")
    void custom_present_replacesDefaultAndShortCircuits() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, "CUSTOM", "APPEND");

        assertThat(prompt.elements()).as("custom 替换 default：输出为 [custom, append]").containsExactly("CUSTOM", "APPEND");
        for (String defaultElement : DEFAULT_ELEMENTS) {
            assertThat(prompt.elements()).as("I-6：default 元素 '%s' 完全不出现在结果", defaultElement)
                .doesNotContain(defaultElement);
        }
        assertThat(assemble.calls()).as("I-13：custom 时 default 组装短路，不被调用").isZero();
    }

    @Test
    @DisplayName("I-6：custom 无 append → 输出恰为 [custom]（非追加在 default 之后）")
    void custom_withoutAppend_soleElement() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, "CUSTOM", null);

        assertThat(prompt.elements()).containsExactly("CUSTOM");
        assertThat(assemble.calls()).isZero();
    }

    // ── 无 override/custom → default 组装 + append 恒末尾 ──

    @Test
    @DisplayName("无 override/custom → assemble 结果 + append 恒末尾，assemble 恰调用一次（systemPrompt.ts:120-121）")
    void noOverrideNoCustom_defaultAssembledAppendTail() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, null, "APPEND");

        assertThat(prompt.elements()).as("default 元素序保持 + append 恒末尾")
            .containsExactly("DEFAULT-1", "DEFAULT-2", "DEFAULT-3", "APPEND");
        assertThat(assemble.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("append 位置断言：custom 与 default 两路径下 append 均恒为末元素（:121 / QueryEngine.ts:324）")
    void append_alwaysLastElement() {
        CountingSupplier customAssemble = new CountingSupplier();

        SystemPrompt customPath = EffectiveSystemPromptBuilder.build(
            customAssemble, null, "CUSTOM", "APPEND");
        SystemPrompt defaultPath = EffectiveSystemPromptBuilder.build(
            new CountingSupplier(), null, null, "APPEND");

        assertThat(customPath.elements().get(customPath.elements().size() - 1)).as("custom 路径 append 恒末尾").isEqualTo("APPEND");
        assertThat(defaultPath.elements().get(defaultPath.elements().size() - 1)).as("default 路径 append 恒末尾").isEqualTo("APPEND");
    }

    @Test
    @DisplayName("无 override/custom/append → 输出恰为 assemble 结果（原引用），无追加")
    void noPromptOverrides_defaultAssembledAsIs() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, null, null);

        assertThat(prompt.elements()).as("无任何调度输入 → default 原样").containsExactlyElementsOf(DEFAULT_ELEMENTS);
        assertThat(assemble.calls()).isEqualTo(1);
    }

    // ── 空串边界（CC JS truthiness：'' 判 falsy、' ' 判 truthy）──

    @Test
    @DisplayName("空串边界：override='' 不触发 override，落入 default 组装（CC truthiness '' 判 falsy）")
    void emptyOverride_fallsThroughToDefault() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, "", null, null);

        assertThat(prompt.elements()).as("'' 不触发 override 早退").containsExactlyElementsOf(DEFAULT_ELEMENTS);
        assertThat(assemble.calls()).as("落入 default 组装，assemble 被调用").isEqualTo(1);
    }

    @Test
    @DisplayName("空串边界：custom='' 不触发替换，走 default 组装（CC truthiness '' 判 falsy）")
    void emptyCustom_fallsThroughToDefault() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, "", null);

        assertThat(prompt.elements()).containsExactlyElementsOf(DEFAULT_ELEMENTS);
        assertThat(assemble.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("空串边界：append='' 不追加，输出恰为 default（CC truthiness '' 判 falsy）")
    void emptyAppend_notAppended() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, null, "");

        assertThat(prompt.elements()).as("'' 不追加").containsExactlyElementsOf(DEFAULT_ELEMENTS);
    }

    @Test
    @DisplayName("空串边界：' '（单空格）判 truthy —— override=' ' 触发 override、append=' ' 触发追加（与 CC JS truthiness 一致，非 isBlank）")
    void whitespaceIsTruthy() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt overridePrompt = EffectiveSystemPromptBuilder.build(
            new CountingSupplier(), " ", null, null);
        SystemPrompt appendPrompt = EffectiveSystemPromptBuilder.build(
            new CountingSupplier(), null, null, " ");

        assertThat(overridePrompt.elements()).as("' ' 判 truthy，触发 override 早退").containsExactly(" ");
        assertThat(appendPrompt.elements()).as("' ' 判 truthy，触发末尾追加").containsExactly("DEFAULT-1", "DEFAULT-2", "DEFAULT-3", " ");
    }

    // ── OPD-R2-11/G-11: memoryMechanicsPrompt 层（CC QueryEngine.ts:316-325）──

    @Test
    @DisplayName("G-11: memoryMechanics 位于 custom 与 append 之间（CC asSystemPrompt([custom, memory, append])）")
    void memoryMechanics_betweenCustomAndAppend() {
        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            new CountingSupplier(), null, "CUSTOM", "MEM-MECH", "APPEND");

        assertThat(prompt.elements())
            .as("元素序 = [custom, memoryMechanics, append]（CC QueryEngine.ts:321-325）")
            .containsExactly("CUSTOM", "MEM-MECH", "APPEND");
    }

    @Test
    @DisplayName("G-11: memoryMechanics 为 null 时不注入（custom 路径保持 [custom, append]）")
    void memoryMechanics_null_absent() {
        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            new CountingSupplier(), null, "CUSTOM", null, "APPEND");

        assertThat(prompt.elements()).as("null memoryMechanics 不产生元素").containsExactly("CUSTOM", "APPEND");
    }

    @Test
    @DisplayName("G-11: default 路径 memoryMechanics 非空同样按序注入（CC 数组拼接语义）")
    void memoryMechanics_withDefault() {
        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            new CountingSupplier(), null, null, "MEM-MECH", "APPEND");

        assertThat(prompt.elements()).as("default 元素后注入 memoryMechanics，append 恒末尾")
            .containsExactly("DEFAULT-1", "DEFAULT-2", "DEFAULT-3", "MEM-MECH", "APPEND");
    }

    // ────────────────────────────────────────────────────────────────
    // [批次 F 返工] SP-02 coordinator 分支（CC systemPrompt.ts:62-75）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SP-02: coordinator 门开 + def null → [getCoordinatorSystemPrompt(), append?]，custom/memMechanics/default 全不出现在结果（systemPrompt.ts:71-74，coordinator 优先级高于 custom/default）")
    void coordinator_gateEnabled_defNull_coordinatorPlusAppend() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(null, false, false, true, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, "CUSTOM", "MEM-MECH", "APPEND", opts);

        assertThat(prompt.elements())
            .as("coordinator 分支返回 [getCoordinatorSystemPrompt(), append]（CC :71-74）")
            .containsExactly(CoordinatorMode.getCoordinatorSystemPrompt(), "APPEND");
        assertThat(prompt.elements()).as("coordinator 优先级高于 custom（:62 早于 :118 三元）").doesNotContain("CUSTOM");
        assertThat(prompt.elements()).as("coordinator 分支无 memoryMechanics（CC 分支字面量不含该项）").doesNotContain("MEM-MECH");
        for (String defaultElement : DEFAULT_ELEMENTS) {
            assertThat(prompt.elements()).as("default 元素 '%s' 完全不出现在结果", defaultElement)
                .doesNotContain(defaultElement);
        }
        assertThat(assemble.calls()).as("coordinator 分支不触发 default 组装").isZero();
    }

    @Test
    @DisplayName("SP-02: coordinator 门开 + def null + 无 append → 输出恰为 [getCoordinatorSystemPrompt()]（CC ...(append?[append]:[])）")
    void coordinator_gateEnabled_noAppend_soleCoordinatorPrompt() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(null, false, false, true, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(assemble, null, null, null, null, opts);

        assertThat(prompt.elements()).as("无 append → 仅 coordinator prompt 单元素").containsExactly(CoordinatorMode.getCoordinatorSystemPrompt());
        assertThat(assemble.calls()).isZero();
    }

    @Test
    @DisplayName("SP-02: coordinator 门关 → 分支不触发，落 custom/default 现路径（门控位是唯一触发源）")
    void coordinator_gateDisabled_fallsThroughToCustom() {
        CountingSupplier assemble = new CountingSupplier();

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, "CUSTOM", null, "APPEND", EffectivePromptOptions.disabled());

        assertThat(prompt.elements()).as("coordinator 门关 → custom 路径 [custom, append]")
            .containsExactly("CUSTOM", "APPEND");
        assertThat(prompt.elements()).as("coordinator prompt 不出现").doesNotContain(CoordinatorMode.getCoordinatorSystemPrompt());
    }

    @Test
    @DisplayName("SP-02: coordinator 门开但 def 非 null → 分支不触发（CC !mainThreadAgentDefinition 恒真条件破坏，:63），落 agent/custom 路径")
    void coordinator_gateEnabled_defPresent_coordinatorSkipped() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(() -> builtinAgent("AGENT-PROMPT"), false, false, true, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(assemble, null, "CUSTOM", null, "APPEND", opts);

        assertThat(prompt.elements()).as("def 非 null → coordinator 跳过，agent 门关 → custom 路径").containsExactly("CUSTOM", "APPEND");
        assertThat(prompt.elements()).as("coordinator prompt 不出现").doesNotContain(CoordinatorMode.getCoordinatorSystemPrompt());
    }

    // ────────────────────────────────────────────────────────────────
    // [批次 F 返工] SP-03 agent 分支（CC systemPrompt.ts:77-83）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SP-03: agentMainThreadEnabled + def 非 null → agentSystemPrompt 替换 default（先于 custom），append 恒末尾（CC :116-117 + :121）")
    void agentMainThread_gateEnabled_defPresent_agentReplacesDefault() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(
            () -> builtinAgent("AGENT-PROMPT"), true, false, false, "model-x", List.of("dir1"));

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(
            assemble, null, "CUSTOM", "MEM-MECH", "APPEND", opts);

        assertThat(prompt.elements()).as("agent 替换 default：元素 = [agentPrompt, memMechanics, append]")
            .containsExactly("AGENT-PROMPT", "MEM-MECH", "APPEND");
        assertThat(prompt.elements()).as("agent 优先级高于 custom（:116 早于 :118 三元）").doesNotContain("CUSTOM");
        assertThat(assemble.calls()).as("agent 命中 → default 组装短路，不被调用").isZero();
    }

    @Test
    @DisplayName("SP-03: agent 分支 getSystemPrompt(modelId, dirs) 逐调用下传（CC runAgent.ts:340 / runAgent.ts:504-506）")
    void agentMainThread_gateEnabled_passesModelIdAndDirs() {
        AtomicReference<String> capturedModel = new AtomicReference<>();
        AtomicReference<List<String>> capturedDirs = new AtomicReference<>();
        EffectivePromptOptions opts = new EffectivePromptOptions(
            () -> capturingAgent((modelId, dirs) -> {
                capturedModel.set(modelId);
                capturedDirs.set(dirs);
                return "AGENT-PROMPT";
            }), true, false, false, "model-x", List.of("dir1", "dir2"));

        EffectiveSystemPromptBuilder.build(new CountingSupplier(), null, null, null, null, opts);

        assertThat(capturedModel.get()).as("modelId 逐调用传递（CC resolvedAgentModel）").isEqualTo("model-x");
        assertThat(capturedDirs.get()).as("additionalWorkingDirs 逐调用传递（CC additionalWorkingDirectories）").containsExactly("dir1", "dir2");
    }

    @Test
    @DisplayName("SP-03: agentMainThreadEnabled 关（DB 门默认关）→ def 非 null 也不产生 agentSystemPrompt，落 custom/default（门控位是唯一触发源）")
    void agentMainThread_gateDisabled_defPresent_agentDormant() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(() -> builtinAgent("AGENT-PROMPT"), false, false, false, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(assemble, null, "CUSTOM", null, "APPEND", opts);

        assertThat(prompt.elements()).as("agent 门关 → def 存在也休眠，走 custom 路径").containsExactly("CUSTOM", "APPEND");
        assertThat(prompt.elements()).doesNotContain("AGENT-PROMPT");
        assertThat(assemble.calls()).isZero();
    }

    @Test
    @DisplayName("SP-03: agentMainThreadEnabled 开但 def supplier 为 null（主循环无 /init）→ 分支休眠，落 default（扩展点登记）")
    void agentMainThread_gateEnabled_defSupplierNull_agentDormant() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(null, true, false, false, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(assemble, null, null, null, "APPEND", opts);

        assertThat(prompt.elements()).as("def supplier null → agent 分支休眠，落 default+append")
            .containsExactly("DEFAULT-1", "DEFAULT-2", "DEFAULT-3", "APPEND");
        assertThat(assemble.calls()).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────
    // [批次 F 返工] SP-04 proactive 分支（CC systemPrompt.ts:103-113）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SP-04: proactive 门开 + agentSystemPrompt 非空 → [default, \"\\n# Custom Agent Instructions\\n\"+agent, append?]（default 之上追加，非替换）")
    void proactive_gateEnabled_agentPresent_defaultPlusAgentInstructions() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(
            () -> builtinAgent("AGENT-PROMPT"), true, true, false, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(assemble, null, null, null, "APPEND", opts);

        assertThat(prompt.elements())
            .as("proactive 模式 agent 指令追加在 default 之上（CC :108-112）")
            .containsExactly(
                "DEFAULT-1", "DEFAULT-2", "DEFAULT-3",
                "\n# Custom Agent Instructions\nAGENT-PROMPT",
                "APPEND");
        assertThat(assemble.calls()).as("proactive 保留 default → assemble 恰调用一次").isEqualTo(1);
    }

    @Test
    @DisplayName("SP-04: proactive 门关 → agentSystemPrompt 非空也走替换路径（非追加），落 [agent, append]（CC :115-122 三元）")
    void proactive_gateDisabled_agentPresent_fallsToAgentReplace() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(
            () -> builtinAgent("AGENT-PROMPT"), true, false, false, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(assemble, null, null, null, "APPEND", opts);

        assertThat(prompt.elements()).as("proactive 关 → agent 替换 default（非追加）").containsExactly("AGENT-PROMPT", "APPEND");
        assertThat(prompt.elements()).as("无 Custom Agent Instructions 段").doesNotContain("\n# Custom Agent Instructions\nAGENT-PROMPT");
    }

    @Test
    @DisplayName("SP-04: proactive 门开但 agentSystemPrompt 缺省（无 def/agent 门关）→ 分支休眠，落 default+append")
    void proactive_gateEnabled_noAgentDef_agentDormant() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(null, false, true, false, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(assemble, null, null, null, "APPEND", opts);

        assertThat(prompt.elements()).as("agentSystemPrompt 缺省 → proactive 不触发，落 default+append")
            .containsExactly("DEFAULT-1", "DEFAULT-2", "DEFAULT-3", "APPEND");
        assertThat(prompt.elements()).doesNotContain("\n# Custom Agent Instructions\n");
        assertThat(assemble.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("SP-04: proactive 无 append → 输出 [default..., \"\\n# Custom Agent Instructions\\n\"+agent]（...(...(append?[append]:[]))）")
    void proactive_noAppend_defaultPlusAgentSoleTail() {
        CountingSupplier assemble = new CountingSupplier();
        EffectivePromptOptions opts = new EffectivePromptOptions(
            () -> builtinAgent("AGENT-PROMPT"), true, true, false, null, List.of());

        SystemPrompt prompt = EffectiveSystemPromptBuilder.build(assemble, null, null, null, null, opts);

        assertThat(prompt.elements())
            .containsExactly("DEFAULT-1", "DEFAULT-2", "DEFAULT-3", "\n# Custom Agent Instructions\nAGENT-PROMPT");
    }
}
