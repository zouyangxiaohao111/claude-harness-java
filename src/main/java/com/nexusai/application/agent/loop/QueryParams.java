package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.TaskBudget;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * queryLoop 参数载体 · 对齐 CC {@code query(params, deps)} 的 params（query.ts:181-199）。
 *
 * <p><b>[H7-arch Phase 5-2 B1]</b> 旧 {@code agent.QueryParams}（run 契约）已改名
 * {@code RunRequest}；本 record 承载 loop 级输入，严格对齐 CC QueryParams 13 字段：
 * 7 必填（messages / systemPrompt / userContext / systemContext /
 * <b>canUseTool</b> / toolUseContext / querySource）+ 6 可选（fallbackModel /
 * maxOutputTokensOverride / maxTurns / skipCacheWrite / taskBudget / deps）。
 *
 * <p><b>[5b · canUseTool 通道恢复]</b> {@link #canUseTool} 为 CC 第 5 必填字段
 * （query.ts:243）。CC 侧 fork 经 {@code createSubagentContext()} 后用**自己的受限
 * canUseTool** 调同一个 query（forkedAgent.ts:569 {@code canUseTool} 透传）；
 * H9-GAP-4 删除本字段使主循环无法承载受限权限 → fork 只能自建循环。本批把通道补回：
 * <b>Java 侧 null 语义 = 未注入 → 回落 {@code ToolPermissionGate}（现状）</b>，
 * 非 null → 逐位覆盖内层 gate 决策。
 *
 * <p><b>[IMP2-05 值域复活] {@link #querySourceValue}</b> —— 第 18 字段（String，nullable）。
 * 枚举 {@code querySource} 承担守卫类别，本字段承担 CC 动态 agentType 级精确值
 * （promptCategory.ts:16-28 → AgentTool.tsx:609 → runAgent.ts:694：{@code agent:builtin:&lt;type&gt;}
 * / {@code agent:custom} / {@code agent:default} / {@code agent:builtin:fork}）。
 * null = 未注入精确值 → 发射侧 {@link QuerySource#effectiveValue(QuerySource, String)} 回退
 * {@code category.canonical()}（SUBAGENT → {@code agent:subagent} 聚合占位，向后兼容）。
 * <b>[收尾 IMP2-05] 接线已闭环</b>：精确值由 SubagentExecutor 在子 agent 自己的 QueryParams
 * 构建处经 {@link #withQuerySourceValue(String)} 注入（对齐 CC promptCategory.ts:16-28 →
 * AgentTool.tsx:609 → runAgent.ts:694），本字段承载 agentType 级运行时值；主线程 / forLoop
 * 默认 null → 发射侧回退类别聚合值。
 *
 * <p><b>Java 特有字段</b>：
 * <ul>
 *   <li>{@link #config} —— 第 14 字段。loop 内 provider.stream 需要（CC 经
 *       deps.callModel 内联，无显式 config）。P3 的 LoopDeps.callModel 封装后本字段移除。</li>
 *   <li>{@link #modelName} —— 第 15 字段。run()/Subagent/Hook 入口已解析的 model 名
 *       （主循环 getModelForCall() 首解 + RunRequest.modelName 回落），loop 内
 *       RecoveryState / budget 计算 / AgentTurnStartedEvent / buildPromptContext 使用。
 *       P3 的 deps.resolveModel() 封装后本字段移除。</li>
 * </ul>
 *
 * <p>compact ctor 仅校验 {@code querySource} 非空（对齐 CC query.ts:189 必传）；其余
 * 字段 B1 消费面小，可宽松（如 messages 可为 null 占位，B1 未消费）。
 *
 * @see com.nexusai.application.agent.LlmAgentLoop#queryLoop
 * @see com.nexusai.application.agent.LlmAgentLoop.MainLoopDeps
 * @see SubagentLoopDeps
 * @see HookLoopDeps
 */
public record QueryParams(
    List<ChatMessageDto> messages,                                   // CC 必填（B1 未消费，可为 null 占位）· CC original: messages (Open-ClaudeCode/src/query.ts:181)
    String systemPrompt,                                             // CC 必填 · CC original: systemPrompt (Open-ClaudeCode/src/query.ts:182)
    Map<String, String> userContext,                                 // CC 必填（⚠️ 必须 Map，非 String！）· CC original: userContext {[k]:string} (Open-ClaudeCode/src/query.ts:183)
    Map<String, String> systemContext,                               // CC 必填（⚠️ 必须 Map）· CC original: systemContext (Open-ClaudeCode/src/query.ts:184)
    // [5b · canUseTool 通道恢复] 受限 canUseTool 覆盖（CC 必填第 5 字段）。
    //   [历史] [merge 冲突解决] line B 曾删除 ToolHooks (R32-D 死代码, 0 生产调用)。
    //   [历史] [H9-GAP-4] canUseTool 字段曾被删除，理由 = "全仓 grep 仅 JavaDoc 引用, 无生产消费点
    //     (StreamingToolExecutor gate 路径走 ToolPermissionGate.check 6 参 forceDecision)"。
    //   [5b 复核] 该结论**只对主线程成立、对 fork 不成立**：fork 最刚需的能力正是**按 input 内容判定**
    //     的受限权限（CC createAutoMemCanUseTool, extractMemories.ts:170-229：Read/Grep/Glob 无条件放行、
    //     同一个 Bash 仅只读命令放行、Edit/Write 仅 auto-memory 目录内），这种语义
    //     `availableTools` 白名单表达不了 → 故本批恢复通道（D2 fork 收敛的硬前置）。
    //   [形态] 类型 = {@link com.nexusai.application.agent.permission.hook.HookPermissionResolver.CanUseTool}
    //     —— 本仓既有的「CC CanUseToolFn 等价物」(HookPermissionResolver.java:132-152，签名
    //     (tool, input, ctx, toolUseId, forceDecision) → DecisionResult)，与 fork 侧
    //     {@code RunForkedAgent.ForkQueryParams.canUseTool} **同型**，5c 可零转换透传。
    //   [默认] null = 未注入 → 回落现状：StreamingToolExecutor 内层消费
    //     {@code ToolPermissionGate.check}(beans.permissionGate / createSpringBean)，
    //     主线程 / 子代理 / hook agent 三路生产路径逐位不变（回归底线）。
    //   CC original: {@code canUseTool: CanUseToolFn} (Open-ClaudeCode/src/query.ts:243) ·
    //     消费点 query.ts:763 / :978 / :1174（StreamingToolExecutor 构造）/ :1671（runTools）。
    HookPermissionResolver.CanUseTool canUseTool,
    ToolUseContext toolUseContext,                                   // CC 必填（com.nexusai.application.agent.tool.ToolUseContext）· CC original: toolUseContext (Open-ClaudeCode/src/query.ts:186)
    QuerySource querySource,                                         // CC 必填（compact ctor 校验非空）· CC original: querySource (Open-ClaudeCode/src/query.ts:188)
    // [IMP2-05 值域复活] 运行时 querySource 精确字符串（agentType 级：'agent:builtin:<type>' /
    //   'agent:custom' / 'agent:default' / 'agent:builtin:fork'；null = 未接线 → 发射侧回退
    //   querySource.canonical()，SUBAGENT → 'agent:subagent' 聚合占位）。枚举 querySource 承担
    //   守卫类别（autocompact/persist/529/main-thread），本字段承担 agentType 级精确值。
    //   CC original: promptCategory.ts:16-28 getQuerySourceForAgent → AgentTool.tsx:609
    //   （toolUseContext.options.querySource ?? getQuerySourceForAgent(...)）→ runAgent.ts:694
    //   （...(useExactTools && { querySource }) fork 子继承父值）。
    String querySourceValue,                                         // [IMP2-05] 精确值（null = 回退 category.canonical()）· 已接线：SubagentExecutor 子 agent 构建处 withQuerySourceValue 注入
    String fallbackModel,                                            // CC 可选 · CC original: fallbackModel (Open-ClaudeCode/src/query.ts:187)
    Integer maxOutputTokensOverride,                                 // CC 可选 · CC original: maxOutputTokensOverride (Open-ClaudeCode/src/query.ts:189)
    Integer maxTurns,                                                // CC 可选 · CC original: maxTurns (Open-ClaudeCode/src/query.ts:190)
    Boolean skipCacheWrite,                                          // CC 可选 · CC original: skipCacheWrite (Open-ClaudeCode/src/query.ts:191)
    TaskBudget taskBudget,                                           // CC 可选 · CC original: taskBudget (Open-ClaudeCode/src/query.ts:195)
    LoopDeps deps,                                                   // 同包，无需 import · CC 作 query() 第 2 参 deps (Open-ClaudeCode/src/query.ts:198)
    ProviderConfig config,                                           // Java 特有第 14 字段（loop 内 provider.stream 需要；P3 callModel 封装后移除）
    String modelName,                                                // Java 特有第 15 字段（run()/Subagent/Hook 入口已解析 model 名；P3 deps.resolveModel() 后移除）
    ThinkingConfig thinkingConfig,                                   // Java 特有第 16 字段（实际查询配置 thinkingConfig · CC original: toolUseContext.options.thinkingConfig (Open-ClaudeCode/src/query.ts:662)）
    Consumer<Tool.ToolProgress> onToolProgress                       // Java 特有第 17 字段（子 agent 工具进度回调 · CC original: createProgressMessage (utils/messages.ts:603-618)，toolExecution.ts:550 tool.call progress → query 流 → runAgent.ts:792-805 yield；null = 主循环/非流式）
) {
    /**
     * 紧凑构造器：校验 querySource 必传（对齐 CC query.ts:189 运行时必填）；thinkingConfig null → disabled。
     *
     * @throws IllegalArgumentException if querySource null
     */
    public QueryParams {
        if (querySource == null) {
            throw new IllegalArgumentException("querySource is null");
        }
        if (thinkingConfig == null) {
            thinkingConfig = ThinkingConfig.disabled();
        }
    }

    /**
     * run()/SubagentExecutor/ExecAgentHook 三调用方工厂。
     *
     * <p>覆盖 record 全部调用方必填字段；userContext/systemContext 默认空 Map。
     * <b>[5b]</b> {@code canUseTool} 默认 null —— 三路生产调用方（主线程 run() /
     * SubagentExecutor / ExecAgentHook）当前均不注入受限 canUseTool，取到的内层
     * {@code ToolPermissionGate} 与 5b 之前<b>逐位相同</b>（回归底线）。分支/子代理如需受限
     * 权限，经 {@link #withCanUseTool} 注入（5c fork 收敛消费）。
     * 可选字段（fallbackModel / skipCacheWrite /
     * maxOutputTokensOverride / taskBudget / maxTurns）可为 null（CC 可选语义）。
     *
     * @param messages                loop 消息列表（run(): state.rawMessages()）
     * @param systemPrompt            system prompt
     * @param toolUseContext          初始 ToolUseContext（B1 占位；loop 内部仍用 toolExecContext 重建）
     * @param querySource             查询来源（compact ctor 已校验非空）
     * @param modelName               入口已解析 model 名
     * @param maxTurns                最大轮数（null = 无限轮，CC query.ts:190 无默认值）
     * @param taskBudget              API task_budget（CC query.ts:195，可选）
     * @param fallbackModel           fallback model（可选）
     * @param skipCacheWrite          跳过缓存写入（可选）
     * @param maxOutputTokensOverride 输出 token 上限覆盖（可选）
     * @param deps                    依赖载体（MainLoopDeps / SubagentLoopDeps / HookLoopDeps）
     * @param config                  provider 运行时配置（P3 callModel 封装后移除）
     * @return loop.QueryParams
     */
    public static QueryParams forLoop(
            List<ChatMessageDto> messages,
            String systemPrompt,
            ToolUseContext toolUseContext,
            QuerySource querySource,
            String modelName,
            Integer maxTurns,
            TaskBudget taskBudget,
            String fallbackModel,
            Boolean skipCacheWrite,
            Integer maxOutputTokensOverride,
            LoopDeps deps,
            ProviderConfig config) {
        return new QueryParams(
            messages, systemPrompt, Map.of(), Map.of(),
            null, // [5b] canUseTool null → 内层回落 ToolPermissionGate（主线程/子代理/hook agent 现状，零行为变化）
            toolUseContext, querySource,
            null, // [IMP2-05] querySourceValue null → 发射侧回退 category.canonical()（向后兼容；
                  //       agent B 接线后经 withQuerySourceValue 注入 agentType 级精确值）
            fallbackModel,
            maxOutputTokensOverride, maxTurns, skipCacheWrite,
            taskBudget, deps, config, modelName,
            null, // thinkingConfig null → 紧凑构造器默认 disabled（当前 Java 主循环请求路径未接线 thinking 发送）
            null); // onToolProgress null → 主循环/非流式无工具进度回调（CC 主循环不 yield progress）
    }

    /**
     * [IMP2-05 值域复活] 派生副本 · 注入 agentType 级精确 querySource 字符串。
     *
     * <p><b>为何需要</b>：subagent 运行时 querySource 需达到 agentType 粒度（CC
     * promptCategory.ts:16-28 getQuerySourceForAgent → AgentTool.tsx:609 → runAgent.ts:694），
     * 枚举 {@link QuerySource#SUBAGENT} 仅承载守卫类别（canonical {@code 'agent:subagent'}
     * 聚合占位）。本字段承载精确值（{@code agent:builtin:&lt;type&gt;} / {@code agent:custom} /
     * {@code agent:default} / {@code agent:builtin:fork}），loop 发射侧
     * {@link QuerySource#effectiveValue(QuerySource, String)} 优先取用。
     *
     * <p><b>[IMP2-05 接线已完成（agent B）]</b>：
     * <ol>
     *   <li>SubagentExecutor.runSubagentQueryLoop 构建 QueryParams 时经
     *       {@code .withQuerySourceValue(agentOptions.querySource())} 注入精确值——该值源自
     *       {@code resolveQuerySource(isForkPath, agentDefinition)}（组合点，原 :1445），
     *       AgentRunOptions.querySource 死路径已复活（原 0 读取点）</li>
     *   <li>LlmAgentLoop 发射侧（ModelRequest.querySource 构建处，原 :3830）已改用
     *       {@code QuerySource.effectiveValue(params.querySource(), params.querySourceValue())}
     *       替代 {@code params.querySource().canonical()}（守卫消费侧仍用枚举/类别，不变）</li>
     * </ol>
     *
     * @param querySourceValue 精确 querySource 字符串（null = 清空 → 发射侧回退
     *                         category.canonical()，SUBAGENT → {@code agent:subagent}）
     * @return 仅 querySourceValue 不同的副本
     */
    public QueryParams withQuerySourceValue(String querySourceValue) {
        return new QueryParams(
            messages, systemPrompt, userContext, systemContext,
            canUseTool,
            toolUseContext, querySource, querySourceValue, fallbackModel,
            maxOutputTokensOverride, maxTurns, skipCacheWrite,
            taskBudget, deps, config, modelName,
            thinkingConfig,
            onToolProgress);
    }

    /**
     * 派生副本 · 覆盖 thinkingConfig（实际查询配置注入 RetryContext 的通道；
     * CC query.ts:662 thinkingConfig 随查询配置传递）。
     *
     * @param thinkingConfig 新的思考配置
     * @return 仅 thinkingConfig 不同的副本
     */
    public QueryParams withThinkingConfig(ThinkingConfig thinkingConfig) {
        return new QueryParams(
            messages, systemPrompt, userContext, systemContext,
            canUseTool,
            toolUseContext, querySource, querySourceValue, fallbackModel,
            maxOutputTokensOverride, maxTurns, skipCacheWrite,
            taskBudget, deps, config, modelName,
            thinkingConfig != null ? thinkingConfig : ThinkingConfig.disabled(),
            onToolProgress);
    }

    /**
     * 派生副本 · 覆盖 onToolProgress（子 agent 工具进度回调 · CC original:
     * toolExecution.ts:550 createProgressMessage → query.ts:1380-1387 yield update.message →
     * runAgent.ts:792-805 yield progress）。子 agent 流式路径（runSubagentQueryLoop）注入
     * 构造 {@link SubagentMessage.ProgressMessage} 的消费者；主循环恒 null（CC 主循环不
     * yield progress 给上层，仅 AgentTool 转发 bash/powershell progress）。
     *
     * @param onToolProgress 新的工具进度回调（null = 不产出 progress）
     * @return 仅 onToolProgress 不同的副本
     */
    public QueryParams withOnToolProgress(Consumer<Tool.ToolProgress> onToolProgress) {
        return new QueryParams(
            messages, systemPrompt, userContext, systemContext,
            canUseTool,
            toolUseContext, querySource, querySourceValue, fallbackModel,
            maxOutputTokensOverride, maxTurns, skipCacheWrite,
            taskBudget, deps, config, modelName,
            thinkingConfig,
            onToolProgress);
    }

    /**
     * [5b · canUseTool 通道恢复] 派生副本 · 注入受限 canUseTool（CC QueryParams 第 5 必填字段）。
     *
     * <p><b>WHY（INV-6 受限权限）</b>：fork 最刚需的能力是<b>按 input 内容判定</b>的权限
     * （CC {@code createAutoMemCanUseTool}, extractMemories.ts:170-229：Read/Grep/Glob 放行、
     * Bash 仅只读命令、Edit/Write 仅 auto-memory 目录内），`availableTools` 白名单无法表达。
     * CC 侧 fork 走 {@code createSubagentContext()} 后把<b>自己的</b> canUseTool 传给同一个
     * query（forkedAgent.ts:569）；本方法即 Java 侧的等价注入点。
     *
     * <p><b>契约</b>：与内层 {@code ToolPermissionGate.check} 6 参<b>同型</b>
     * （{@code HookPermissionResolver.CanUseTool}，即 CC {@code CanUseToolFn} 的 Java 等价物）。
     * 非 null → 覆盖内层 gate 决策；null → 回落 gate（现状，零行为变化）。
     *
     * @param canUseTool 受限权限判定函数（null = 清除覆盖 → 回落内层 gate）
     * @return 仅 canUseTool 不同的副本
     */
    public QueryParams withCanUseTool(HookPermissionResolver.CanUseTool canUseTool) {
        return new QueryParams(
            messages, systemPrompt, userContext, systemContext,
            canUseTool,
            toolUseContext, querySource, querySourceValue, fallbackModel,
            maxOutputTokensOverride, maxTurns, skipCacheWrite,
            taskBudget, deps, config, modelName,
            thinkingConfig,
            onToolProgress);
    }
}
