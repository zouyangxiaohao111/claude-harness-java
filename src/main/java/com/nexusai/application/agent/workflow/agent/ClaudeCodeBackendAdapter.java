package com.nexusai.application.agent.workflow.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.tool.impl.SubagentMessage;
import com.nexusai.application.agent.workflow.AgentProgressUpdate;
import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.AgentRunResultOk;
import com.nexusai.application.agent.workflow.HostHandle;
import com.nexusai.application.agent.workflow.WorkflowAbortedError;
import com.nexusai.application.agent.workflow.WorkflowHostBundle;
import com.nexusai.application.agent.workflow.output.StructuredContentBlock;
import com.nexusai.application.agent.workflow.output.StructuredOutputExtractor;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 深度集成后端：从 live 会话解析 agent/model/tools，委托核心 runAgent。
 * 实现 {@link AgentAdapter} 接口，由 {@link AgentAdapterRegistry} 注册路由（U5）。
 *
 * <p>CC original: {@code claudeCodeBackend}
 * (Open-ClaudeCode/src/workflow/backends/claudeCodeBackend.ts:203-409)。
 *
 * <p><b>委托链</b>：本 adapter 的 {@code run} 委托现有 Java runAgent 等价
 * {@link SubagentExecutor#executeStreaming(String, String, String, SubagentExecutor.ForkPathParams,
 * Consumer, AbortController)}（对齐 CC claudeCodeBackend.ts:298-312 调 {@code runAgent}）。
 * Java 端映射：
 * <ul>
 *   <li>{@code override.agentId}（CC core 层 subagent 追踪串）→ SubagentExecutor 内部生成
 *       （runAgent.ts:347 {@code override?.agentId ? ... : createAgentId()}），引擎层 engineAgentId
 *       经 {@code ctx.agentId()}（数字序号）区分——两概念勿混（AgentAdapterContext JavaDoc）。</li>
 *   <li>{@code override.abortController} → 第 6 参 {@code abortControllerOverride}（桥接
 *       {@code ctx.signal()}，使 kill 精确达内层）。</li>
 *   <li>{@code model} → {@code modelOverride}。</li>
 *   <li>{@code isAsync: true} → Java 由 {@code agentDefinition.background()} 派生
 *       （SubagentExecutor:1257），故 {@link #WORKFLOW_AGENT} 置 {@code background(true)}。</li>
 *   <li>{@code querySource: 'workflow'} → [Fix-D4] adapter 委托 runAgent（executeStreaming）时经
 *       querySourceOverride 第 7 参显式传 {@code 'workflow'}（CC claudeCodeBackend.ts:304
 *       {@code toolUseContext.options.querySource ?? 'workflow'} 默认值）；SubagentExecutor 守卫类别
 *       经 {@code QuerySource.fromString('workflow')} 归一 WORKFLOW 枚举（canonical 'workflow'）。
 *       <b>persist gate（query.ts:376-378）不命中</b>（'workflow' 非 {@code agent:}/
 *       {@code repl_main_thread} 前缀）→ workflow 子代理 content replacement 不持久化，
 *       对齐 CC 语义（旧实现守卫类别 SUBAGENT → canonical {@code agent:subagent} 误命中
 *       {@code agent:} 前缀导致错误落 sidechain，P1 Report D-4 修正）。</li>
 *   <li>{@code isolation:'worktree'} → [Fix-D1] {@link AgentWorktreeManager} 预创建
 *       {@code wf_<sha256>} 隔离 worktree（fail-closed：建树失败 → dead{worktree-failed}，
 *       CC claudeCodeBackend.ts:219-234）；worktree 路径经 executeStreaming 第 8 参
 *       {@code worktreePathOverride} 透传（CC :311 override.worktreePath），SubagentExecutor
 *       Step 18 以 {@code withEffectiveCwd} 派生子 ToolUseContext（CC runWithCwdOverride 等价面）；
 *       finally 经 {@code AgentWorktreeManager.cleanupWorkflowWorktree}（hasWorktreeChanges
 *       fail-closed，CC :169-200）收尾。</li>
 * </ul>
 *
 * <p><b>W-2b 接线</b>：schema 模式下本 adapter 注入 schema prompt（claudeCodeBackend.ts:272-287）+
 * 终态消息经 {@link StructuredOutputExtractor#classifySchemaMode} 完成「提取 → 校验 → 分类」
 * （claudeCodeBackend.ts:371-397）；引擎边界 {@code StructuredOutputValidator.validateStructuredResult}
 * 保留作二次校验（不匹配 → dead{invalid-structured-output}）。
 */
public final class ClaudeCodeBackendAdapter implements AgentAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeBackendAdapter.class);

    /** schema prompt 注入 / 提取结果序列化用 · CC original: {@code JSON.stringify(schema, null, 2)} */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * workflow 子代理回落定义 · CC original: {@code WORKFLOW_AGENT} (claudeCodeBackend.ts:36-44)。
     *
     * <p>{@code background(true)}：Java SubagentExecutor 以 {@code agentDefinition.background()}
     * 派生 {@code isAsync}（:1257），对齐 CC runAgent 调用显式 {@code isAsync: true}
     * （claudeCodeBackend.ts:303）。
     *
     * <p><b>[Fix-G1] public</b>：由 {@code WorkflowRegistry.buildRegistry} 引用注册进
     * SubagentExecutor.additionalAgentDefinitions（E2E G-1 生产回落路径），
     * 故从 package-private 扩为 public（仅放宽访问，行为不变）。
     */
    public static final AgentDefinition WORKFLOW_AGENT = AgentDefinition.BuiltInAgentDefinition.builder(
            "workflow-worker",
            "subtask dispatched by the agent() hook inside a workflow script",
            (modelId, dirs) ->
                    "You are a workflow sub-agent. Complete the task concisely; your final text is the return value relayed to the workflow.")
            .tools(List.of("*"))
            .background(true)
            .build();

    /** 委托目标（Java runAgent 等价）· CC original: {@code runAgent} (claudeCodeBackend.ts:298-312)。 */
    private final SubagentExecutor subagentExecutor;

    /** worktree 隔离 manager（D-1 接线）· CC original: claudeCodeBackend.ts:219-234 createAgentWorktree 段。 */
    private final AgentWorktreeManager worktreeManager;

    public ClaudeCodeBackendAdapter(SubagentExecutor subagentExecutor, AgentWorktreeManager worktreeManager) {
        this.subagentExecutor = Objects.requireNonNull(subagentExecutor,
                "ClaudeCodeBackendAdapter: subagentExecutor（runAgent 委托）必填");
        this.worktreeManager = Objects.requireNonNull(worktreeManager,
                "ClaudeCodeBackendAdapter: worktreeManager（D-1 isolation 隔离）必填");
    }

    /** 兼容构造（单测/工具场景）· 自建 AgentWorktreeManager（对齐其无参构造 new WorktreeService()）。 */
    public ClaudeCodeBackendAdapter(SubagentExecutor subagentExecutor) {
        this(subagentExecutor, new AgentWorktreeManager());
    }

    /**
     * 唯一标识 · CC original: {@code id: 'claude-code'} (claudeCodeBackend.ts:204)。
     *
     * @return "claude-code"
     */
    @Override
    public String id() {
        return "claude-code";
    }

    /**
     * 能力声明 · CC original: {@code { structuredOutput: true, tools: true }} (claudeCodeBackend.ts:205-206)。
     *
     * @return 完整能力（结构化输出 + 工具调用）
     */
    @Override
    public AgentAdapterCapabilities capabilities() {
        return AgentAdapterCapabilities.full();
    }

    /**
     * agentType → 真实 agent registry（命中 activeAgents 否则回落）· CC original:
     * {@code resolveAgentDefinition} (claudeCodeBackend.ts:47-56)。
     *
     * <p>CC 真源（含注释转述不可信，只信源码行为）：<pre>
     * function resolveAgentDefinition(agentType, toolUseContext): AgentDefinition {
     *   if (!agentType) return WORKFLOW_AGENT
     *   const found = toolUseContext.options.agentDefinitions.activeAgents.find(
     *     a => a.agentType === agentType,
     *   )
     *   return found ?? WORKFLOW_AGENT
     * }</pre>
     * Java 经 {@link SubagentExecutor#resolveAgentDefinition}（未命中返回 null 等价
     * {@code activeAgents.find} 失败），未命中回落 {@link #WORKFLOW_AGENT}（'workflow-worker'）。
     *
     * @param executor  runAgent 委托（提供 activeAgents 查找）
     * @param agentType agent() 入参 agentType（可空）
     * @return 命中的真实 agent，未命中/空 → WORKFLOW_AGENT
     */
    public static AgentDefinition resolveAgentDefinition(SubagentExecutor executor, String agentType) {
        if (agentType == null || agentType.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("ClaudeCodeBackendAdapter.resolveAgentDefinition：agentType 为空 → 回落 WORKFLOW_AGENT（CC claudeCodeBackend.ts:51）");
            }
            return WORKFLOW_AGENT;
        }
        AgentDefinition found = executor.resolveAgentDefinition(agentType);
        if (found != null) {
            if (log.isDebugEnabled()) {
                log.debug("ClaudeCodeBackendAdapter.resolveAgentDefinition：命中真实 agent agentType={} source={}（CC claudeCodeBackend.ts:52-54）",
                        agentType, found.source());
            }
            return found;
        }
        if (log.isDebugEnabled()) {
            log.debug("ClaudeCodeBackendAdapter.resolveAgentDefinition：agentType={} 未命中 → 回落 WORKFLOW_AGENT（CC claudeCodeBackend.ts:55）",
                    agentType);
        }
        return WORKFLOW_AGENT;
    }

    /**
     * model alias → 当前 provider 的实际 model id。v1 直接透传（保留映射扩展点）· CC original:
     * {@code mapWorkflowModel} (claudeCodeBackend.ts:59-63)。
     *
     * @param model agent() 入参 model（可空）
     * @return 原样 model
     */
    public static String mapWorkflowModel(String model) {
        return model;
    }

    /**
     * 执行一次 agent 调用 · CC original: {@code run(params, ctx)} (claudeCodeBackend.ts:207-408)。
     *
     * <p>数据流：resolveAgentDefinition → mapWorkflowModel → abort 桥 → 委托 runAgent →
     * ok{output/outputTokens/model/toolCount/tokenCount} / dead{runagent-threw} /
     * WorkflowAbortedError（kill 路径，引擎不伪装成 dead）。
     *
     * @param params agent() 入参
     * @param ctx    adapter 运行上下文
     * @return 未来完成的 AgentRunResult
     */
    @Override
    public CompletableFuture<AgentRunResult> run(AgentRunParams params, AgentAdapterContext ctx) {
        AgentDefinition agentDef = resolveAgentDefinition(subagentExecutor, params.agentType());
        String model = mapWorkflowModel(params.model());
        if (log.isInfoEnabled()) {
            log.info("ClaudeCodeBackendAdapter.run 入口：runId={} engineAgentId={} agentType={} resolvedAgentType={} model={} schema={} parentAgentId={}（CC claudeCodeBackend.ts:207-217）",
                    ctx.runId(), ctx.agentId(), params.agentType(), agentDef.agentType(),
                    model, params.schema() != null, readParentAgentId(ctx.host()));
        }
        return runBlocking(params, ctx, agentDef, model);
    }

    /**
     * 单次阻塞执行 · CC original: runAgent 委托 + abort 检测（claudeCodeBackend.ts:242-353）。
     *
     * <p>引擎 attemptBackend 已在池线程 join（hooks.ts:253），此处同步执行等效；
     * WorkflowAbortedError（kill）以异常透传，绝不伪装成 dead（claudeCodeBackend.ts:329-335）。
     */
    private CompletableFuture<AgentRunResult> runBlocking(AgentRunParams params, AgentAdapterContext ctx,
                                                          AgentDefinition agentDef, String model) {
        // [Fix-D1] isolation:'worktree' → AgentWorktreeManager 预创建 wf_<sha256> 隔离 worktree
        // （CC claudeCodeBackend.ts:219-234）。fail-closed：建树失败 → dead(worktree-failed)，
        // 绝不静默回落共享 cwd（否则并发 workflow agent 写互踩，CC :227-228 注释）；
        // 共享 cwd 只在未要求隔离（NoIsolation）时使用。
        AgentWorktreeManager.IsolationResult isolation = null;
        if (AgentWorktreeManager.ISOLATION_WORKTREE.equals(params.isolation())) {
            String coreAgentId = generateCoreAgentId();     // CC coreAgentId（createAgentId，:217）
            Path gitRoot = resolveGitRoot(ctx);             // CC findCanonicalGitRoot(getCwd())（worktree.ts:926）
            isolation = worktreeManager.createIsolation(params, ctx.runId(), coreAgentId, gitRoot);
            if (isolation instanceof AgentWorktreeManager.IsolationResult.IsolationFailed failed) {
                // fail-closed：绝不回落共享 cwd · CC :227-233 return {kind:'dead', reason:'worktree-failed', detail}
                return CompletableFuture.completedFuture(AgentWorktreeManager.toWorktreeFailed(failed));
            }
        }
        String worktreePathOverride = isolation instanceof AgentWorktreeManager.IsolationResult.Isolated iso
                ? iso.info().worktreePath().toString() : null;

        // schema prompt 注入（CC claudeCodeBackend.ts:272-287）：schema 模式下把 schema JSON +
        // CRITICAL RULES 追加到 prompt 末尾，指示 agent 在终态文本块直接输出单个 JSON 对象
        // （不依赖 StructuredOutput 工具——不在 workflow 子代理工具集内，CC :266-271 历史教训）。
        String effectivePrompt = params.prompt();
        if (params.schema() != null) {
            effectivePrompt = buildSchemaPrompt(params.prompt(), params.schema());
            if (log.isDebugEnabled()) {
                log.debug("ClaudeCodeBackendAdapter.runBlocking：schema 模式已注入 schema prompt（CC claudeCodeBackend.ts:272-287）");
            }
        }
        // abort 桥：ctx.signal → runAgent 内部 abortController。否则 workflow 被杀时 runAgent 无感
        // （CC 根因注释 claudeCodeBackend.ts:242-255：'x' 无效 = signal 到不了内部 fetch）。
        // 单 agent kill 走 service.kill(runId, engineAgentId) → taskRegistrar.killAgent →
        // agentAbortControllers.get(engineAgentId).abort()；同一控制器接管两条路径。
        AbortController agentAbort = new AbortController();
        Consumer<AbortController> parentAbortListener = ac -> agentAbort.abort();
        if (ctx.signal().isCancelled()) {
            agentAbort.abort();
        } else {
            ctx.signal().onCancel(parentAbortListener);
        }
        if (ctx.registerAgentAbort() != null) {
            ctx.registerAgentAbort().accept(ctx.agentId(), agentAbort);
        }
        try {
            // 逐消息进度累计：tokenCount 覆盖式（累计 usage）+ toolCount 增量式（tool_use 块）·
            // CC claudeCodeBackend.ts:292-327 + :314-326
            int[] progress = {0, 0};
            Consumer<SubagentMessage> messageSink = msg -> {
                if (msg instanceof SubagentMessage.AssistantMessage assistant
                        && ctx.onProgress() != null) {
                    if (assistant.usage() != null) {
                        progress[0] = (int) assistant.usage().totalTokens();
                    }
                    if (assistant.toolContent()) {
                        progress[1] += 1;
                    }
                    ctx.onProgress().accept(new AgentProgressUpdate(progress[0], progress[1]));
                }
            };

            // 委托现有 runAgent（SubagentExecutor）· CC claudeCodeBackend.ts:298-312。
            //   override agentId/abortController/model + isAsync:true + querySource:'workflow'
            //   Java 映射见类 JavaDoc；agentType 空 → resolveAgentDefinition 已回落 'workflow-worker'。
            //   prompt 用注入后的 effectivePrompt（schema 模式含 schema + CRITICAL RULES，CC :272-287）。
            //   [Fix-D4] querySource:'workflow'（CC claudeCodeBackend.ts:304 默认）经第 7 参
            //   querySourceOverride 传入 → workflow 子代理守卫类别归一 WORKFLOW 枚举，persist gate
            //   不命中（CC query.ts:376-378 非 agent:/repl_main_thread 前缀 → content replacement 不持久化）。
            SubagentExecutor.SubagentResult result = subagentExecutor.executeStreaming(
                    effectivePrompt, agentDef.agentType(), model,
                    null,                       // 非 fork path（CC runAgent 无 forkParams）
                    messageSink,
                    agentAbort,                 // override.abortController（CC :309）
                    "workflow",                 // [Fix-D4] querySource:'workflow'（CC claudeCodeBackend.ts:304）
                    worktreePathOverride);      // [Fix-D1] 预创建隔离 worktree 路径（CC :311 override.worktreePath）；null=共享 cwd
            if (log.isDebugEnabled()) {
                log.debug("ClaudeCodeBackendAdapter.runBlocking：runId={} engineAgentId={} agentType={} "
                        + "querySource='workflow' 委托 runAgent（CC claudeCodeBackend.ts:304 默认，persist gate 不命中）",
                        ctx.runId(), ctx.agentId(), agentDef.agentType());
            }

            // abort 检测：kill 必须抛 WorkflowAbortedError，否则 hooks.agent 会把 abort 当普通
            // 失败吞进 dead，workflow 不知被 kill（CC claudeCodeBackend.ts:329-335 + 'x' 无效的
            // 另一侧：signal 到了但结果伪装成正常完成）。
            if (agentAbort.isCancelled() || "aborted".equals(result.status())) {
                if (log.isWarnEnabled()) {
                    log.warn("ClaudeCodeBackendAdapter.run：runId={} engineAgentId={} agentType={} 被 abort → 抛 WorkflowAbortedError（CC claudeCodeBackend.ts:333-335）",
                            ctx.runId(), ctx.agentId(), agentDef.agentType());
                }
                throw new WorkflowAbortedError();
            }

            // schema 模式：adapter 侧「提取 → 校验 → 分类」（CC claudeCodeBackend.ts:371-397）·
            // 引擎边界 validateStructuredResult 保留作二次校验（hooks.ts:346-363）。
            if (params.schema() != null) {
                AgentRunResult classified = classifySchemaOutput(result, params.schema(), model, agentDef);
                if (log.isInfoEnabled()) {
                    log.info("ClaudeCodeBackendAdapter.run schema 模式完成：runId={} engineAgentId={} agentType={} status={} result={}{}（CC claudeCodeBackend.ts:371-397）",
                            ctx.runId(), ctx.agentId(), agentDef.agentType(), result.status(),
                            classified instanceof AgentRunResultOk ? "ok" : "dead",
                            classified instanceof AgentRunResultDead d && d.reason() != null
                                    ? " reason=" + d.reason() : "");
                }
                return CompletableFuture.completedFuture(classified);
            }

            AgentRunResultOk ok = new AgentRunResultOk(
                    result.summaryText(),
                    (int) result.usage().outputTokens(),
                    model != null ? model : agentDef.model().orElse(null),
                    result.totalToolUseCount(),
                    (int) result.totalTokens());
            if (log.isInfoEnabled()) {
                log.info("ClaudeCodeBackendAdapter.run 完成：runId={} engineAgentId={} agentType={} status={} outputTokens={} totalTokens={} toolCount={}（CC claudeCodeBackend.ts:355-407）",
                        ctx.runId(), ctx.agentId(), agentDef.agentType(), result.status(),
                        ok.outputTokens(), ok.tokenCount(), ok.toolCount());
            }
            return CompletableFuture.completedFuture(ok);
        } catch (WorkflowAbortedError e) {
            // kill 不重试：原样透传（CC claudeCodeBackend.ts:333-335；引擎 attemptBackend catch 后 rethrow）
            throw e;
        } catch (Exception e) {
            // abort 以异常形式抛出（SubagentExecutor 可能 throw 而非返回 aborted）→ 仍按 kill 处理
            // · 对齐 CC catch 内 abort 检测（claudeCodeBackend.ts:333-335）
            if (agentAbort.isCancelled()) {
                if (log.isWarnEnabled()) {
                    log.warn("ClaudeCodeBackendAdapter.run：runId={} engineAgentId={} agentType={} 异常且已 abort → 抛 WorkflowAbortedError（CC claudeCodeBackend.ts:333-335）",
                            ctx.runId(), ctx.agentId(), agentDef.agentType());
                }
                throw new WorkflowAbortedError();
            }
            String detail = String.valueOf(e.getMessage());
            if (log.isWarnEnabled()) {
                log.warn("ClaudeCodeBackendAdapter.run 失败：runId={} engineAgentId={} agentType={} → dead{runagent-threw}：{}（CC claudeCodeBackend.ts:336-341）",
                        ctx.runId(), ctx.agentId(), agentDef.agentType(), detail);
            }
            return CompletableFuture.completedFuture(new AgentRunResultDead(
                    AgentRunResult.DeadReason.RUNAGENT_THREW, detail));
        } finally {
            // [Fix-D1] worktree 收尾（CC claudeCodeBackend.ts:342-353 finally →
            //   :169-200 cleanupWorkflowWorktree）：hasWorktreeChanges fail-closed 探测，
            //   无变更自动删 / 有变更或探测失败保留并记日志路径。
            if (isolation instanceof AgentWorktreeManager.IsolationResult.Isolated iso) {
                AgentWorktreeManager.cleanupWorkflowWorktree(iso.info(), agentDef.agentType());
            }
            // 清理（幂等）：listener remove + Map.delete 可重复调用安全 · CC claudeCodeBackend.ts:342-353
            if (ctx.unregisterAgentAbort() != null) {
                ctx.unregisterAgentAbort().accept(ctx.agentId());
            }
            ctx.signal().removeOnCancel(parentAbortListener);
        }
    }

    /**
     * schema 模式 prompt 注入 · CC original: {@code promptText} (claudeCodeBackend.ts:272-287)。
     *
     * <p>schema 非空时把 schema JSON（2 空格缩进，等价 {@code JSON.stringify(schema, null, 2)}）+
     * CRITICAL RULES 4 条按 {@code [].join('\n')} 语义拼到 prompt 末尾；schema 为 null 原样返回。
     * schema 已由 WorkflowHooksImpl.assertValidJsonSchema 前置校验为 Map，序列化失败属配置错误
     * → 抛 IllegalStateException（规则 12 · Fail loud，不吞错）。</p>
     *
     * @param prompt 原始 agent prompt
     * @param schema JSON Schema（可空 → 原样返回 prompt）
     * @return 注入后的 prompt 文本
     */
    private static String buildSchemaPrompt(String prompt, Object schema) {
        if (schema == null) {
            return prompt;
        }
        String schemaJson;
        try {
            schemaJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize workflow schema failed: " + e.getMessage(), e);
        }
        return prompt + "\n\n"
                + "After completing the task, emit your final answer as a single JSON object matching this JSON Schema:\n"
                + "```json\n"
                + schemaJson
                + "\n```\n"
                + "\n"
                + "CRITICAL RULES:\n"
                + "- The JSON object must be the LAST text block in your response. Do not write any prose after it.\n"
                + "- Emit the JSON as plain text (markdown code fences optional).\n"
                + "- Do NOT call any \"StructuredOutput\" or \"SyntheticOutput\" tool — it is not available in this environment.\n"
                + "- Your turn must end with the JSON object. Anything after it (prose, tool calls) will be ignored or cause your answer to be discarded.";
    }

    /**
     * schema 模式分类 · CC original: claudeCodeBackend.ts:371-397（extractStructuredOutput + dead 分类）。
     *
     * <p>终态消息 content 映射：Java {@code SubagentResult.summaryText} = 末条 assistant 消息文本
     * （SubagentExecutor.extractConclusionFromMessages，等价 CC {@code extractTextContent(finalized.content, '\n')}）
     * → 单 text 块交给 {@link StructuredOutputExtractor#classifySchemaMode}。分类结果 ok 时以真实
     * usage/token/model 覆盖（classifySchemaMode 不知最终 usage，其 JavaDoc 已声明）；dead 原样返回。
     *
     * @param result   runAgent 委托结果
     * @param schema   JSON Schema
     * @param model    映射后的 model id
     * @param agentDef 实际 agent 定义（model 回退源）
     * @return ok{output=紧凑 JSON, outputTokens/toolCount/tokenCount/model 真实值} 或 dead 分类
     */
    private AgentRunResult classifySchemaOutput(SubagentExecutor.SubagentResult result, Object schema,
                                                String model, AgentDefinition agentDef) {
        List<StructuredContentBlock> content = List.of(StructuredContentBlock.text(result.summaryText()));
        AgentRunResult classified = StructuredOutputExtractor.classifySchemaMode(schema, content);
        if (classified instanceof AgentRunResultOk ok) {
            return new AgentRunResultOk(ok.output(),
                    (int) result.usage().outputTokens(),
                    model != null ? model : agentDef.model().orElse(null),
                    result.totalToolUseCount(),
                    (int) result.totalTokens());
        }
        return classified;
    }

    /**
     * 解包 host → 父 agentId（仅数据流日志）· CC original: {@code readHostBundle} (hostHandle.ts:40-42)。
     *
     * @param host 不透明 HostHandle
     * @return 父 ToolUseContext.agentId 的字符串，无 host/无 agentId → null
     */
    private static String readParentAgentId(HostHandle host) {
        ToolUseContext tuc = readToolUseContext(host);
        UUID agentId = tuc != null ? tuc.agentId() : null;
        return agentId != null ? agentId.toString() : null;
    }

    /**
     * 解包 host → 父 ToolUseContext（null host / 非 WorkflowHostBundle → null）·
     * CC original: {@code readHostBundle} (hostHandle.ts:40-42)。
     *
     * @param host 不透明 HostHandle
     * @return 父 ToolUseContext；无 host / 载荷非 WorkflowHostBundle → null
     */
    private static ToolUseContext readToolUseContext(HostHandle host) {
        if (host == null) {
            return null;
        }
        Object bundle = HostHandle.unwrap(host);
        if (bundle instanceof WorkflowHostBundle wb) {
            return wb.toolUseContext();
        }
        return null;
    }

    /**
     * 生成 coreAgentId（workflow 子代理追踪串，slug 入参）· CC original: {@code createAgentId()}
     * (claudeCodeBackend.ts:217，uuid.ts:24-27 返回 {@code a}{16hex})。
     *
     * <p>slug = sha256(runId:coreAgentId)（AgentWorktreeManager.makeWorkflowWorktreeSlug），
     * coreAgentId 唯一性保证同 runId 下多 agent 的 worktree slug 唯一（CC :155-158 注释）。
     *
     * @return {@code a}{16 hex}
     */
    private static String generateCoreAgentId() {
        byte[] suffixBytes = new byte[8];
        new SecureRandom().nextBytes(suffixBytes);
        String suffix = HexFormat.of().formatHex(suffixBytes);
        String id = "a" + suffix;
        if (log.isDebugEnabled()) {
            log.debug("ClaudeCodeBackendAdapter.generateCoreAgentId：id={}（CC uuid.ts:24-27）", id);
        }
        return id;
    }

    /**
     * 解析 canonical git 根（AgentWorktreeManager.createIsolation 入参）· CC original:
     * {@code findCanonicalGitRoot(getCwd())}（worktree.ts:926，git.ts:195）。
     *
     * <p>Java 等价：projectRoot = 会话绑定启动目录（boundProject，memory session-bound-dir-is-cc-startup-dir，
     * 经 {@link CwdResolution#getCwd(String)} 四层解析）→ {@link AutoMemPaths#findCanonicalGitRoot}；
     * 无会话（cron/后台/测试）回落 user.dir。非 git 目录 → null（createIsolation 侧建树必然抛 →
     * IsolationFailed fail-closed，对齐 CC worktree.ts:931-935「not in a git repository」throw）。
     *
     * @param ctx adapter 运行上下文（host 解包父 ToolUseContext）
     * @return canonical git 根，非 git 目录 → null
     */
    private static Path resolveGitRoot(AgentAdapterContext ctx) {
        ToolUseContext tuc = readToolUseContext(ctx.host());
        String projectRoot = null;
        if (tuc != null && tuc.sessionId() != null) {
            projectRoot = CwdResolution.getCwd(tuc.sessionId());
        }
        if (projectRoot == null || projectRoot.isBlank()) {
            projectRoot = System.getProperty("user.dir");
        }
        String canonical = AutoMemPaths.findCanonicalGitRoot(projectRoot);
        if (log.isDebugEnabled()) {
            log.debug("ClaudeCodeBackendAdapter.resolveGitRoot：projectRoot={} canonicalGitRoot={}（CC findCanonicalGitRoot(getCwd()) worktree.ts:926）",
                    projectRoot, canonical);
        }
        return canonical != null ? Path.of(canonical) : null;
    }
}
