package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ODF-C3] SubagentExecutor 子 Agent registry merge 测试 · 对齐 CC print.ts:4381-4383
 * (SDK {@code request.agents} → {@code parseAgentsFromJson(request.agents,'flagSettings')}
 * → 并入子 Agent agents 列表)。
 *
 * <p>验证：① {@code setAdditionalAgentDefinitions} 注入的 agents map 经
 * {@code resolveAgentDefinition} merge 进子 Agent registry（flag 来源可解析）；
 * ② fork 子 ctx AgentOptions.agentDefinitions 携带该 map（createSubagentContext merge 输入）。
 */
class SubagentExecutorAdditionalAgentsTest {

    private static final AgentDefinition FLAG_AGENT =
        AgentDefinition.CustomAgentDefinition.builder(
            "sdk-flag-agent", "desc", "flagSettings", "prompt").build();

    @Test
    @DisplayName("resolveAgentDefinition: 附加 agents map 优先命中（SDK request.agents 并入子 registry）")
    void resolveAgentDefinition_hits_additional_agent_definitions() {
        // WHY: CC print.ts:4381-4383 request.agents 解析后 push 进 agents 列表，子 agent 可解析
        //   这些 flagSettings agent。Java 端 setAdditionalAgentDefinitions 注入的 map 必须最先
        //   命中 resolveAgentDefinition，否则 SDK 传入 agent 在子 loop 不可达（AgentNotFound）。
        SubagentExecutor executor = newExecutor();
        executor.setAdditionalAgentDefinitions(Map.of("sdk-flag-agent", FLAG_AGENT));

        AgentDefinition resolved = executor.resolveAgentDefinition("sdk-flag-agent");

        assertThat(resolved).as("附加 agents map 必须优先命中").isEqualTo(FLAG_AGENT);
        assertThat(resolved.source()).isEqualTo("flagSettings");
    }

    @Test
    @DisplayName("resolveAgentDefinition: 附加 map 未命中时回退 resolver/builtIn（不吞既有来源）")
    void resolveAgentDefinition_falls_back_when_additional_miss() {
        // WHY: merge 是"并入"而非"替换" — 附加 map 未命中的 type 必须回退既有 resolver/builtIn，
        //   不能因注入附加 agents 导致内置 general-purpose 不可达。
        SubagentExecutor executor = newExecutor();
        executor.setAdditionalAgentDefinitions(Map.of("sdk-flag-agent", FLAG_AGENT));

        AgentDefinition gp = executor.resolveAgentDefinition("general-purpose");

        assertThat(gp).as("内置 agent 必须仍可解析（merge 不破坏既有来源）").isNotNull();
        assertThat(gp.source()).isEqualTo("built-in");
    }

    @Test
    @DisplayName("buildForkAgentOptions: fork 子 ctx.options.agentDefinitions 携带附加 agents map")
    void buildForkAgentOptions_carries_additional_agent_definitions() {
        // WHY: CC runAgent.ts:700-714 context.options.agentDefinitions 承载 agents 列表；
        //   fork 子 ctx 的 options 必须携带 SDK/flag agents map，createSubagentContext 才能 merge
        //   进子 loop（验收 #2 子 Agent prompt 可列出 flagSettings agent）。
        SubagentExecutor.ForkPathParams fp = new SubagentExecutor.ForkPathParams(
            null, List.of(), "<parent system prompt>", null);

        AgentOptions forkOptions = SubagentExecutor.buildForkAgentOptions(
            fp, Map.of("sdk-flag-agent", FLAG_AGENT));

        assertThat(forkOptions.agentDefinitions())
            .as("fork options.agentDefinitions 必须包含附加 agents map")
            .containsEntry("sdk-flag-agent", FLAG_AGENT);
        assertThat(forkOptions.querySource()).isEqualTo("agent:builtin:fork");
    }

    @Test
    @DisplayName("buildForkAgentOptions: null/空附加 map 不破坏既有 fork options")
    void buildForkAgentOptions_null_additional_map_keeps_fork_options() {
        SubagentExecutor.ForkPathParams fp = new SubagentExecutor.ForkPathParams(
            null, List.of(), "<parent system prompt>", null);

        AgentOptions forkOptions = SubagentExecutor.buildForkAgentOptions(fp, null);

        assertThat(forkOptions.agentDefinitions()).isEmpty();
        assertThat(forkOptions.querySource()).isEqualTo("agent:builtin:fork");
    }

    @Test
    @DisplayName("返工#3: 子 ctx.options().agentDefinitions() 经 mergeOptionsAgentDefinitions 并入子 registry，resolveAgentDefinition 可命中")
    void mergeOptionsAgentDefinitions_consumes_carried_agents_into_sub_registry() {
        // WHY (返工#3): Reflection 指出 AgentOptions.agentDefinitions 仅承载不消费（grep 0 读取点）。
        //   本测试证明子 loop 消费点闭环：createSubagentContext 产出 SubagentContext.options 携带
        //   agents map → mergeOptionsAgentDefinitions 并入 additionalAgentDefinitions（子 registry）→
        //   resolveAgentDefinition 可列出 flagSettings agent（验收 #2 声明成立）。
        //   CC 对齐：runAgent.ts:700-714 context.options.agentDefinitions + print.ts:4381-4383
        //   SDK request.agents 并入子 Agent agents 列表。
        SubagentExecutor executor = newExecutor();
        // 构造携带 agents map 的子 ctx options（对齐 buildForkAgentOptions 的产物形态）
        AgentOptions carriedOptions = SubagentExecutor.buildForkAgentOptions(
            new SubagentExecutor.ForkPathParams(null, List.of(), "<parent system prompt>", null),
            Map.of("sdk-flag-agent", FLAG_AGENT));
        // 经 createSubagentContext 产出子 ctx（IMP-SUB-19 #23: 3 参 create 已删，create 直接返回
        //   ToolUseContext；options 不再塞进包装 record，改由 mergeOptionsAgentDefinitions(AgentOptions)
        //   直接消费 —— 对齐 CC createSubagentContext 直接返回 ToolUseContext）
        com.nexusai.application.agent.tool.ToolUseContext ctx =
            com.nexusai.application.agent.subagent.createSubagentContext.create(
                null,
                new com.nexusai.application.agent.tool.ToolUseContext.SubagentContextOverrides(
                    java.util.UUID.randomUUID(), "sdk-flag-agent", null,
                    null, null, null, null, null, null, null, null, null, null, null));

        assertThat(ctx).as("create() 必须返回非 null ToolUseContext").isNotNull();
        executor.mergeOptionsAgentDefinitions(carriedOptions);

        AgentDefinition resolved = executor.resolveAgentDefinition("sdk-flag-agent");
        assertThat(resolved)
            .as("子 loop 消费 options().agentDefinitions() 后必须能解析 flagSettings agent")
            .isEqualTo(FLAG_AGENT);
        assertThat(resolved.source()).isEqualTo("flagSettings");
    }

    @Test
    @DisplayName("IMP-SUB-15 querySourceForAgent: 内置 agent → agent:builtin:<type>（无连字符，D13/D9 builtin 分支）")
    void querySourceForAgent_builtin_noHyphen() {
        // WHY: CC promptCategory.ts:23 `agent:builtin:${agentType}`（无连字符）。旧 SubagentExecutor
        //   用 'agent:'+source()+':'+agentType 拼出 'agent:built-in:<type>'（source()="built-in" 带连字符），
        //   与 CC 值面（递归守卫检查的 'agent:builtin:fork'）永不相等。本断言锁 CC 无连字符值面。
        AgentDefinition builtin = AgentDefinition.BuiltInAgentDefinition.builder(
            "Explore", "desc", (modelId, dirs) -> "prompt").build();

        assertThat(builtin.querySourceForAgent()).isEqualTo("agent:builtin:Explore");
    }

    @Test
    @DisplayName("IMP-SUB-15 querySourceForAgent: 内置 agent 无 agentType → agent:default（CC promptCategory.ts:24）")
    void querySourceForAgent_builtin_noType_default() {
        // WHY: CC promptCategory.ts:24 `agentType ? ... : 'agent:default'`——空串 agentType 为 falsy →
        //   'agent:default'。Java PromptCategory 等价判空（!agentType.isEmpty()）。
        AgentDefinition builtin = AgentDefinition.BuiltInAgentDefinition.builder(
            "", "desc", (modelId, dirs) -> "prompt").build();

        assertThat(builtin.querySourceForAgent()).isEqualTo("agent:default");
    }

    @Test
    @DisplayName("IMP-SUB-15 querySourceForAgent: 自定义 agent → 恒常量 agent:custom（D13/D9 custom 分支）")
    void querySourceForAgent_custom_constant() {
        // WHY: CC promptCategory.ts:26 非内置恒 'agent:custom'。旧实现 'agent:userSettings:<type>' 非常量
        //   与 CC 值面不符。source() 必须保持 'userSettings'（loadAgentsDir:1182-1185 过滤依赖），
        //   仅 querySource 派生侧改走常量（本任务不修改 source()）。
        AgentDefinition custom = AgentDefinition.CustomAgentDefinition.builder(
            "my-agent", "desc", "userSettings", "prompt").build();

        assertThat(custom.source()).isEqualTo("userSettings");
        assertThat(custom.querySourceForAgent()).isEqualTo("agent:custom");
    }

    @Test
    @DisplayName("IMP-SUB-15 querySourceForAgent: 插件 agent → 恒常量 agent:custom（CC promptCategory.ts:26）")
    void querySourceForAgent_plugin_constant() {
        // WHY: CC getQuerySourceForAgent 仅按 isBuiltInAgent 分两路；插件非内置 → 'agent:custom'
        //   （旧实现 'agent:plugin:<type>' 值面漂移）。
        AgentDefinition plugin = AgentDefinition.PluginAgentDefinition.builder(
            "plugin:ns:base", "desc", "my-plugin", "prompt").build();

        assertThat(plugin.source()).isEqualTo("plugin");
        assertThat(plugin.querySourceForAgent()).isEqualTo("agent:custom");
    }

    @Test
    @DisplayName("IMP-SUB-15 返工 R2: resolveQuerySource 非 fork+buildin → agent:builtin:<type>（锁生产接线非 fork 值面）")
    void resolveQuerySource_nonFork_builtin() {
        // WHY (规则九 / 反射 R2): 原 4 条 helper 测试打在 AgentDefinition.querySourceForAgent() 上，
        //   即使把 execute() Step 10 的非 fork 分支改回旧公式 'agent:'+source()+':'+agentType
        //   甚至删除整段组合，4 条仍全绿——对生产接线（组合三元）零保护。resolveQuerySource 是
        //   接线本体，锁其非 fork builtin 分支 = 组合点回退/删除即变红。
        AgentDefinition builtin = AgentDefinition.BuiltInAgentDefinition.builder(
            "Explore", "desc", (modelId, dirs) -> "prompt").build();

        assertThat(SubagentExecutor.resolveQuerySource(false, builtin))
            .isEqualTo("agent:builtin:Explore");
    }

    @Test
    @DisplayName("IMP-SUB-15 返工 R2: resolveQuerySource 非 fork+custom → 恒 agent:custom")
    void resolveQuerySource_nonFork_custom() {
        // WHY (反射 R2): 非 fork custom 分支必须经接线本体产出 CC 恒常量 'agent:custom'
        //   （promptCategory.ts:26），不能退回旧公式 'agent:userSettings:<type>'。
        AgentDefinition custom = AgentDefinition.CustomAgentDefinition.builder(
            "my-agent", "desc", "userSettings", "prompt").build();

        assertThat(SubagentExecutor.resolveQuerySource(false, custom))
            .isEqualTo("agent:custom");
    }

    @Test
    @DisplayName("IMP-SUB-15 返工 R2: resolveQuerySource fork → 恒 agent:builtin:fork（锁 fork 常量进接线本体）")
    void resolveQuerySource_fork_constant() {
        // WHY (反射 R2): fork 分支若被改回旧公式 'agent:built-in:fork'（source()="built-in" 带连字符），
        //   autocompact 递归守卫（SubagentTool 检查 context.options.querySource === 'agent:builtin:fork'，
        //   AgentTool.tsx:332）将永不命中——fork 抗压缩守卫静默失效。锁在接线本体使该回归可被测试捕获。
        AgentDefinition custom = AgentDefinition.CustomAgentDefinition.builder(
            "my-agent", "desc", "userSettings", "prompt").build();

        assertThat(SubagentExecutor.resolveQuerySource(true, custom))
            .isEqualTo("agent:builtin:fork");
    }

    @Test
    @DisplayName("IMP2-05 运行时精确值: fork + 内置 agent → 恒 agent:builtin:fork（fork 子继承父，runAgent.ts:694）")
    void resolveQuerySource_fork_overrides_builtinExactValue() {
        // WHY（IMP2-05 运行时能区分 agentType 的反例）: fork 子 agent 的 querySource 不取自身
        //   agentType 精确值（agent:builtin:<type>），而是继承父 querySource 恒 'agent:builtin:fork'
        //   （CC runAgent.ts:694 ...(useExactTools && { querySource })）。递归守卫 SubagentTool:1641
        //   精确匹配的正是该值。若 fork 分支错误改为 agentType 精确值（agent:builtin:Explore），
        //   守卫永不命中 → fork 递归死锁。本断言锁「fork 分支优先级高于 agentType 值」。
        AgentDefinition builtin = AgentDefinition.BuiltInAgentDefinition.builder(
            "Explore", "desc", (modelId, dirs) -> "prompt").build();

        assertThat(SubagentExecutor.resolveQuerySource(true, builtin))
            .as("fork + 内置 Explore → 仍恒 agent:builtin:fork（守卫不变，不取 agentType 值）")
            .isEqualTo("agent:builtin:fork");
    }

    @Test
    @DisplayName("IMP2-05 运行时精确值闭环: resolveQuerySource → withQuerySourceValue → effectiveValue 发射侧取用（遥测 agentType 级）")
    void runtimeExactValue_survivesWithQuerySourceValue_toEffectiveValue() {
        // WHY（IMP2-05 核心闭环 · 规则九）: resolveQuerySource 组合点产出的是 agentType 级精确值，
        //   必须经 QueryParams.withQuerySourceValue 透传，且发射侧 effectiveValue 优先取用 ——
        //   否则精确值在 to-loop 途中被枚举 category.canonical() 吞掉（回归 'agent:subagent' 聚合占位，
        //   遥测无法区分子 agent 类型）。本断言锁整链：非 fork 内置 → 'agent:builtin:Explore'；
        //   非 fork 内置 type 空 → 'agent:default'；非 fork 自定义 → 'agent:custom'；fork →
        //   'agent:builtin:fork'（四值语义互不相同，CC promptCategory.ts:16-28 四分支）。
        AgentDefinition builtin = AgentDefinition.BuiltInAgentDefinition.builder(
            "Explore", "desc", (modelId, dirs) -> "prompt").build();
        AgentDefinition builtinNoType = AgentDefinition.BuiltInAgentDefinition.builder(
            "", "desc", (modelId, dirs) -> "prompt").build();
        AgentDefinition custom = AgentDefinition.CustomAgentDefinition.builder(
            "my-agent", "desc", "userSettings", "prompt").build();

        String builtinExact = SubagentExecutor.resolveQuerySource(false, builtin);
        String builtinNoTypeExact = SubagentExecutor.resolveQuerySource(false, builtinNoType);
        String customExact = SubagentExecutor.resolveQuerySource(false, custom);
        String forkExact = SubagentExecutor.resolveQuerySource(true, builtin);

        // 运行时能区分 agentType：builtin / builtinNoType / custom / fork 值域正确且互不相同（遥测/持久化精确）
        assertThat(builtinExact).isEqualTo("agent:builtin:Explore");
        assertThat(builtinNoTypeExact)
            .as("内置 agent 且 agentType 为空 → agent:default（CC promptCategory.ts:24 agentType? 判空）")
            .isEqualTo("agent:default");
        assertThat(customExact).isEqualTo("agent:custom");
        assertThat(forkExact).isEqualTo("agent:builtin:fork");
        assertThat(builtinExact).isNotEqualTo(builtinNoTypeExact);
        assertThat(builtinNoTypeExact).isNotEqualTo(customExact);
        assertThat(builtinExact).isNotEqualTo(customExact);
        assertThat(builtinExact).isNotEqualTo(forkExact);

        // 组合点输出注入 QueryParams.querySourceValue，发射侧 effectiveValue 优先取用（不吞 agentType）
        com.nexusai.application.agent.loop.QueryParams params = com.nexusai.application.agent.loop.QueryParams
            .forLoop(null, "sys", null, QuerySource.SUBAGENT, "model", null, null, null, null, null, null, null)
            .withQuerySourceValue(builtinExact);
        assertThat(QuerySource.effectiveValue(params.querySource(), params.querySourceValue()))
            .as("发射侧 effectiveValue 必须取用 agentType 级精确值（遥测可区分 Explore 子 agent）")
            .isEqualTo("agent:builtin:Explore");

        // 发射侧对 agent:default 同样优先取用精确值（内置 type 空路径不吞成 'agent:subagent'）
        com.nexusai.application.agent.loop.QueryParams defaultParams = com.nexusai.application.agent.loop.QueryParams
            .forLoop(null, "sys", null, QuerySource.SUBAGENT, "model", null, null, null, null, null, null, null)
            .withQuerySourceValue(builtinNoTypeExact);
        assertThat(QuerySource.effectiveValue(defaultParams.querySource(), defaultParams.querySourceValue()))
            .as("发射侧 effectiveValue 必须取用 agent:default（builtin type 空路径）")
            .isEqualTo("agent:default");
    }

    @Test
    @DisplayName("[收尾 IMP2-05 · 决策 B] buildSubagentAgentOptions 非 FORK → querySource=null（对齐 CC runAgent.ts:694 不 spread）；FORK → agent:builtin:fork 不变（守卫完好）")
    void buildSubagentAgentOptions_nonForkNull_forkGuardIntact() {
        // WHY（决策 B · 规则九）: CC runAgent.ts:694 非 fork 分支 useExactTools=false →
        //   ...(useExactTools && { querySource }) 不 spread → options.querySource=undefined。
        //   旧 Java 实现非 FORK 分支产 name().toLowerCase() 失真值（SUBAGENT → 'subagent'，CC 值域
        //   不存在）。收尾决定改为恒 null，消除值域失真（与 AgentOptions.defaultOptions() 的
        //   querySource=null 形态一致）。本断言锁两点：① 非 FORK（SUBAGENT/USER）→ null（对齐 CC）；
        //   ② FORK → 恒 'agent:builtin:fork'（SubagentTool:1633 fork 递归守卫唯一语义消费点，只精确
        //   匹配该值 —— 若回归失真值，守卫静默失效）。SubagentTool:1633 已做 null 防护
        //   （"agent:builtin:fork".equals(querySource)），null 不破坏守卫。
        com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions subagentOpts =
            LlmAgentLoop.buildSubagentAgentOptions(QuerySource.SUBAGENT, ThinkingConfig.disabled());
        assertThat(subagentOpts.querySource())
            .as("非 FORK（SUBAGENT）→ querySource=null（对齐 CC runAgent.ts:694 不 spread；消除 'subagent' 值域失真）")
            .isNull();

        com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions userOpts =
            LlmAgentLoop.buildSubagentAgentOptions(QuerySource.USER, ThinkingConfig.disabled());
        assertThat(userOpts.querySource())
            .as("非 FORK（USER）同样 → null（非 FORK 分支不注入任何 querySource 值）")
            .isNull();

        com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions forkOpts =
            LlmAgentLoop.buildSubagentAgentOptions(QuerySource.FORK, ThinkingConfig.disabled());
        assertThat(forkOpts.querySource())
            .as("FORK → 恒 agent:builtin:fork（SubagentTool:1633 递归守卫唯一语义消费点，守卫不变）")
            .isEqualTo("agent:builtin:fork");
    }

    private SubagentExecutor newExecutor() {
        // 无 Spring 依赖的最小构造（对齐 SubagentExecutorForkPathTest 既有构造模式）
        return new SubagentExecutor(
            new com.nexusai.application.agent.tool.ToolRegistry(),
            null, null, null, null,
            "gpt-4", "system", null);
    }
}
