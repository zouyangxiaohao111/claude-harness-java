package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.workflow.LaunchInput;
import com.nexusai.application.agent.workflow.LaunchResult;
import com.nexusai.application.agent.workflow.NamedWorkflows;
import com.nexusai.application.agent.workflow.WorkflowConstants;
import com.nexusai.application.agent.workflow.WorkflowPorts;
import com.nexusai.application.agent.workflow.WorkflowService;
import com.nexusai.application.agent.workflow.WorkflowServiceImpl;
import com.nexusai.application.agent.workflow.script.ScriptError;
import com.nexusai.application.agent.workflow.script.WorkflowScriptParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * WorkflowTool 真实现 · 对齐 CC {@code WorkflowTool.ts} (Open-ClaudeCode/packages/workflow-engine/src/tool/WorkflowTool.ts)
 * + {@code tool/schema.ts workflowInputSchema}。
 *
 * <p><b>WHY（从 OPD-10 注册桩升级为真实现，W-2d）</b>: CC 中 {@code WorkflowTool = feature('WORKFLOW_SCRIPTS')
 * ? createWorkflowTool(ports) : null}（tools.ts:129-133），flag 关时工具为 null 不进 getAllBaseTools。
 * 本类取代旧 fail-loud 桩：flag 开时真实接线 {@link WorkflowService#launch} 执行 workflow 脚本。</p>
 *
 * <p><b>门控语义</b>: {@link #isEnabled()} = {@code featureFlags.workflowScripts()}
 * （WORKFLOW_SCRIPTS · CC tools.ts:129），默认全关 → isEnabled()==false 不暴露；同时
 * {@code ToolRegistrationConfig} 仅在 flag 开时注册本工具（tools.ts:233 spread，双保险）。
 * Workflow 在 {@code ALL_AGENT_DISALLOWED_TOOLS}（constants/tools.ts）内，不暴露给 hook agent；
 * Java 端 {@link ToolNameConstants#ALL_AGENT_DISALLOWED_TOOLS} 已含 {@code WORKFLOW_TOOL_NAME}（对齐）。</p>
 *
 * <p><b>CC 对齐点</b>（WorkflowTool.ts）：
 * <ul>
 *   <li>{@link #inputSchema()} — 8 字段全列（schema.ts:4-41）：script / name / scriptPath / args /
 *       resumeFromRunId / description / title / maxConcurrency。</li>
 *   <li>{@link #prompt()} — WORKFLOW_TOOL_PROMPT 全量（WorkflowTool.ts:40-53），含 <b>并发>3 提示</b>：
 *       maxConcurrency≠3 必须先经 AskUserQuestion 问用户（默认 3，上限 16）。</li>
 *   <li>{@link #execute(ToolUseBlock, ToolUseContext)} — 三源解析（script &gt; scriptPath &gt; name，
 *       WorkflowTool.ts:226-261）+ parseScript 快速校验（meta+body，失败回错误给模型<b>不进后台</b>，
 *       WorkflowTool.ts:98-107）+ 调 {@link WorkflowService#launch} detached run（W-1e 架构：工具↔面板共享入口）。</li>
 *   <li>{@link #renderToolUseMessage(JsonNode)} — WorkflowTool.ts:76-82。</li>
 *   <li>isReadOnly = false（WorkflowTool.ts:66，构建子代理/后台执行非只读）。</li>
 * </ul>
 *
 * <p><b>EngineContext 信号量</b>（并发&gt;3 运行时强制）：{@code maxConcurrency} 经 LaunchInput →
 * {@code WorkflowServiceImpl.launch} → {@code WorkflowRunEngine.run} → {@code EngineContext.create}
 * （engine/context.ts:47-73）→ {@code SharedResources.semaphore} = {@code Semaphore.clampMaxConcurrency}
 * （constants.ts:23/26 DEFAULT_MAX_CONCURRENCY=3 / MAX_CONCURRENCY_CAP=16）——单 run 并发槽硬钳制；
 * 工具的 {@link #prompt()} 承载「&gt;3 需先问用户」的模型侧提示。</p>
 *
 * <p><b>依赖接线</b>（W-2d 接 P0）：{@link WorkflowService}（经 {@link WorkflowServiceImpl#getWorkflowService()}
 * 进程单例）、{@link WorkflowScriptParser}（parseScript 快速校验）、{@link WorkflowPorts}
 * （经 {@code service.ports()} 透传引擎，本工具不直接触碰）。</p>
 */
public class WorkflowTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTool.class);

    /** CC 工具名 · {@code constants.ts:7} WORKFLOW_TOOL_NAME（引用 ToolNameConstants 单一权威）。 */
    public static final String NAME = ToolNameConstants.WORKFLOW_TOOL_NAME;

    /** args JsonNode → Java Object 转换用 · 对齐 CC z.unknown（schema.ts:17-22）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FeatureFlags featureFlags;
    /** parseScript 编译期校验器 · CC original: parseScript (engine/script.ts:189-229)。 */
    private final WorkflowScriptParser parser;

    public WorkflowTool() {
        this(FeatureFlags.ALL_DISABLED);
    }

    public WorkflowTool(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
        this.parser = new WorkflowScriptParser();
    }

    /**
     * WORKFLOW_TOOL_PROMPT 全量 · CC original: {@code WORKFLOW_TOOL_PROMPT}（WorkflowTool.ts:40-53）逐字。
     *
     * <p>含<b>并发&gt;3 提示</b>（WorkflowTool.ts:46）：maxConcurrency 任意非 3 值必须先经
     * AskUserQuestion 向用户确认（propose 3/6/9，3 标 "(Recommended)"）；唯一例外是用户本会话已显式
     * 指定并发数。运行时强制由 EngineContext 信号量 clamp 承载（见类 Javadoc）。</p>
     */
    private static final String WORKFLOW_TOOL_PROMPT = """
            Use the Workflow tool to execute a workflow script that orchestrates multiple subagents deterministically. The script runs in the background; you receive a run_id immediately and are notified on completion.

            Provide the script inline via "script", or reference a named workflow via "name" (resolved from .nexusai/workflows/ primary, .claude/workflows/ fallback), or an existing file via "scriptPath". Pass "args" as a real JSON value (object/array/string), not a stringified string.

            Use "resumeFromRunId" to resume a prior run — completed agent() calls replay from the journal instantly.

            Concurrency: default is 3 (hard ceiling 16). OMIT maxConcurrency to use 3. To set maxConcurrency to ANY value other than 3, you MUST first ask the user via AskUserQuestion — propose 3 / 6 / 9 (or other tiers matching the fan-out width) with 3 marked "(Recommended)". The ONLY exception: the user has ALREADY specified a concurrency number in this session ("use 6", "maxConcurrency 9") — then honor it without re-asking. Never silently raise concurrency above 3 just because the workflow fans out; 3 is the recommended default.

            Script execution model (common pitfalls — getting these wrong is the #1 cause of script errors): the script is the body of \\`new AsyncFunction\\` — NOT an ESM module, and TypeScript is NOT transpiled. Therefore:
            - Do NOT use \\`import\\` — \\`agent\\`, \\`parallel\\`, \\`pipeline\\`, \\`phase\\`, \\`log\\`, \\`workflow\\`, \\`args\\`, and \\`budget\\` are injected as parameters; reference them directly.
            - Do NOT use TS type annotations, \\`interface\\`, \\`enum\\`, \\`as\\`, or generics — the engine does not transpile, so even a .ts file with type syntax fails to parse.
            - Keep EXACTLY ONE \\`export const meta = {...}\\` (plain literal) and remove every other \\`export\\` / \\`export default\\`.
            - Return the result with a top-level \\`return\\`.
            Prefer .js / .mjs. See /ultracode for the full playbook and quality patterns.""";

    // ════════════════════════════════════════════════════════════════════════
    // Tool 接口实现
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public String name() {
        if (log.isDebugEnabled()) {
            log.debug("WorkflowTool.name(): 返回 CC 工具名 WORKFLOW_TOOL_NAME='Workflow'（对齐 constants.ts:7）");
        }
        return NAME;
    }

    /** CC original: description() (WorkflowTool.ts:68-70)。 */
    @Override
    public String description() {
        return "Execute a workflow script that orchestrates multiple subagents to complete a task";
    }

    /** CC original: prompt() (WorkflowTool.ts:72-74) → WORKFLOW_TOOL_PROMPT 全量（含并发>3 提示）。 */
    @Override
    public String prompt() {
        if (log.isDebugEnabled()) {
            log.debug("WorkflowTool.prompt(): 返回 {} 字符 WORKFLOW_TOOL_PROMPT（含并发>3 AskUserQuestion 提示，WorkflowTool.ts:40-53）",
                    WORKFLOW_TOOL_PROMPT.length());
        }
        // [T3/#21] prompt 文本 .nexusai → 动态 appName（决策 D1/D6）：workflow 目录指引随 appName 联动
        return WORKFLOW_TOOL_PROMPT.replace(".nexusai", "." + NexusaiPaths.getAppName());
    }

    /**
     * 输入 Schema · CC original: {@code workflowInputSchema}（tool/schema.ts:4-41）全字段。
     *
     * <p>CC 字段（8 个全 optional）：
     * <pre>
     * z.object({
     *   script: z.string().optional(),              // 内联脚本源码
     *   name: z.string().optional(),                // 命名 workflow（.nexusai/workflows/<name>.ts|js|mjs 优先，.claude/workflows/ 回落）
     *   scriptPath: z.string().optional(),          // 现有脚本文件绝对路径
     *   args: z.unknown().optional(),               // 任意 JSON 值（object/array/string，非 stringified）
     *   resumeFromRunId: z.string().optional(),     // resume 指定 run（重放 journal）
     *   description: z.string().optional(),         // 本次调用的简短描述（3-5 词）
     *   title: z.string().optional(),               // 进度视图标题
     *   maxConcurrency: z.number().int().min(1).max(16).optional(),  // agent() 并发上限（默认 3，上限 16）
     * })
     * </pre>
     *
     * <p>unknownKeys 语义（schema.ts:4 z.object）：CC zod v4 z.object 默认 strip（未知键剔除不报错）。
     * 广告层 additionalProperties=false（zod v4.4.3 toJSONSchema 实测），运行时经
     * {@link #unknownKeysPolicy()} = STRIP 对齐 CC strip 行为（Tool.java IT-5）。</p>
     */
    @Override
    public JsonNode inputSchema() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode schema = f.objectNode();
        schema.put("type", "object");

        ObjectNode properties = f.objectNode();
        // schema.ts:5-8 script（内联）
        properties.set("script", f.objectNode()
                .put("type", "string")
                .put("description", "Self-contained workflow script source (inline)"));
        // schema.ts:9-12 name
        properties.set("name", f.objectNode()
                .put("type", "string")
                .put("description", "Named workflow, resolved to " + NexusaiPaths.getProjectDirName()
                    + "/workflows/<name>.ts|js|mjs (primary), .claude/workflows/ fallback"));
        // schema.ts:13-16 scriptPath
        properties.set("scriptPath", f.objectNode()
                .put("type", "string")
                .put("description", "Absolute path to an existing script file"));
        // schema.ts:17-22 args（z.unknown → 不声明 type，任意 JSON 值）
        properties.set("args", f.objectNode()
                .put("description",
                        "The args global variable passed through to the script. Pass a real JSON value (object/array/string), not a JSON string."));
        // schema.ts:23-26 resumeFromRunId
        properties.set("resumeFromRunId", f.objectNode()
                .put("type", "string")
                .put("description", "Resume the specified run, replaying the journal"));
        // schema.ts:27-30 description
        properties.set("description", f.objectNode()
                .put("type", "string")
                .put("description", "A short description of this invocation (3-5 words)"));
        // schema.ts:31 title
        properties.set("title", f.objectNode()
                .put("type", "string")
                .put("description", "Progress viewer title"));
        // schema.ts:32-40 maxConcurrency（z.number().int().min(1).max(16)）
        properties.set("maxConcurrency", f.objectNode()
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", 16)
                .put("description",
                        "Concurrency cap for agent(). Defaults to 3 (max 16). When the workflow contains heavy parallel/pipeline fan-out, you may confirm the desired concurrency with the user via AskUserQuestion before launching."));

        schema.set("properties", properties);
        // 全字段 optional（CC 无 required 数组）
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * unknownKeys 运行时策略 · 对齐 CC {@code z.object}（schema.ts:4）默认 strip。
     *
     * <p>CC zod v4 z.object 默认 strip（未知键剔除不报 unrecognized_keys）；Java 默认
     * UNSPECIFIED（跟随 additionalProperties=false 即拒绝）会误拒绝 z.object 工具的未知键——
     * 本工具显式声明 STRIP 对齐 CC strip 语义（Tool.java:584-586 IT-5）。</p>
     */
    @Override
    public UnknownKeysPolicy unknownKeysPolicy() {
        return UnknownKeysPolicy.STRIP;
    }

    /** 门控：WORKFLOW_SCRIPTS flag（CC tools.ts:129），默认关 → 不暴露。 */
    @Override
    public boolean isEnabled() {
        boolean enabled = this.featureFlags.workflowScripts();
        if (log.isDebugEnabled()) {
            log.debug("WorkflowTool.isEnabled() = {}（WORKFLOW_SCRIPTS 门控，CC tools.ts:129）", enabled);
        }
        return enabled;
    }

    /** CC original: isReadOnly (WorkflowTool.ts:66) = false（启动后台 subagent 编排，非只读）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    /**
     * 并发安全 · CC original: {@code isConcurrencySafe: () => true}（wiring.ts:34-36）。
     *
     * <p>Workflow 每次调用启动<b>独立 detached run</b>（后台编排子代理，引擎 runId 独立、
     * journal/run 目录按 runId 隔离）→ 与并发模式兼容（P2 残留收敛：旧实现继承 Tool 默认
     * false 保守拒绝并发，偏离 CC 恒 true）。Tool 接口签名 {@code boolean isConcurrencySafe(JsonNode)}。
     */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** CC original: renderToolUseMessage (WorkflowTool.ts:76-82)。 */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        if (input != null && input.has("resumeFromRunId")) {
            return "Workflow resume: " + input.get("resumeFromRunId").asText();
        }
        String id;
        if (input != null && input.has("name")) {
            id = input.get("name").asText();
        } else if (input != null && input.has("scriptPath")) {
            id = input.get("scriptPath").asText();
        } else if (input != null && input.has("script")) {
            id = "inline";
        } else {
            id = "unknown";
        }
        return "Workflow: " + id;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行 Workflow · 对齐 CC {@code WorkflowTool.ts:84-172 call()}。
     *
     * <p>流程（对应 CC 各步）：
     * <ol>
     *   <li>解析 input → {@link LaunchInput}（8 字段）。</li>
     *   <li>三源解析脚本（script &gt; scriptPath &gt; name，WorkflowTool.ts:226-261）+ <b>parseScript
     *       快速校验</b>（meta + import/export 规则，WorkflowTool.ts:98-107）：失败<b>回错误给模型</b>，
     *       不进后台。</li>
     *   <li>并发&gt;3 提示：maxConcurrency 经 LaunchInput 透传，运行时由 EngineContext 信号量
     *       clamp（EngineContext.create → SharedResources.semaphore）；模型侧指引在 {@link #prompt()}。</li>
     *   <li><b>detached run</b>：调 {@link WorkflowService#launch}（resolveSource + parser.parse +
     *       taskRegistrar.register + inline 持久化 + 后台 {@code WorkflowRunEngine.run}）——launch 返回
     *       快（后台已 kick off），本方法拿 runId 即返回启动消息。</li>
     *   <li>结果映射：成功 → "Workflow started (running in the background)..."；失败 → ToolResult.error
     *       （错误回给模型）。</li>
     * </ol>
     *
     * <p><b>不抛异常</b>（Tool 契约）：全部错误转 {@link ToolResult#error} 返回，LLM 自纠。</p>
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        if (log.isDebugEnabled()) {
            log.debug("WorkflowTool.execute 入口：call.id={} input 字段={}（对齐 CC WorkflowTool.ts:84-172）",
                    call.id(), input == null ? "null" : collectKeys(input));
        }

        // Step 1: input → LaunchInput（8 字段，schema.ts:4-41）
        LaunchInput launchInput;
        try {
            launchInput = toLaunchInput(input);
        } catch (Exception e) {
            log.warn("WorkflowTool 解析 input 失败：{}（回错误给模型）", e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
        if (log.isDebugEnabled()) {
            log.debug("WorkflowTool 解析 LaunchInput：name={} scriptPath={} resumeFromRunId={} maxConcurrency={}（script 内联={}）",
                    launchInput.name(), launchInput.scriptPath(), launchInput.resumeFromRunId(),
                    launchInput.maxConcurrency(), launchInput.script() != null);
        }

        // Step 2: 三源解析 + parseScript 快速校验（CC WorkflowTool.ts:88-107，失败回错误给模型，不进后台）
        ResolvedScript src;
        try {
            src = resolveScriptSource(launchInput, resolveCwd(ctx));
        } catch (Exception e) {
            log.warn("WorkflowTool 脚本源解析失败：{}（回错误给模型，不进后台，CC WorkflowTool.ts:91-96）", e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
        try {
            parser.parse(src.script());
        } catch (ScriptError e) {
            log.warn("WorkflowTool 脚本校验失败：{}（回错误给模型，不进后台，CC WorkflowTool.ts:98-107 meta+body 校验）",
                    e.getMessage());
            return ToolResult.error(call.id(), "Error: script validation failed: " + e.getMessage());
        }

        // Step 3: 并发>3 提示（模型侧指引在 prompt()；运行时 EngineContext 信号量 clamp）
        if (launchInput.maxConcurrency() != null && log.isDebugEnabled()) {
            log.debug("WorkflowTool 显式 maxConcurrency={}（默认 3，上限 {}；>3 需 AskUserQuestion，"
                            + "运行时 EngineContext 信号量 clamp，constants.ts:23/26）",
                    launchInput.maxConcurrency(), WorkflowConstants.MAX_CONCURRENCY_CAP);
        }

        // Step 4: detached run → WorkflowService.launch（W-1e 共享入口，service.ts:188-257）
        WorkflowService service;
        try {
            service = WorkflowServiceImpl.getWorkflowService();
        } catch (IllegalStateException e) {
            log.error("WorkflowTool 无法获取 WorkflowService（Spring bean 未装配）：{}", e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }

        CompletableFuture<LaunchResult> future;
        try {
            future = service.launch(launchInput, ctx, null);
        } catch (Exception e) {
            log.warn("WorkflowTool.launch 调用同步异常：{}", e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }

        // Step 5: 结果映射（launch 快返回 runId；后台 run 完成经 taskRegistrar 通知，非本 future）
        try {
            LaunchResult result = future.get();
            String workflowName = launchInput.name() != null ? launchInput.name()
                    : (launchInput.title() != null ? launchInput.title() : "workflow");
            String scriptPath = src.workflowFile() != null ? src.workflowFile()
                    : (result.scriptPath() != null ? result.scriptPath() : "<inline run " + result.runId() + ">");
            String output = "Workflow started (running in the background).\n"
                    + "run_id: " + result.runId() + "\n"
                    + "workflow: " + workflowName + "\n"
                    + "script: " + scriptPath + "\n"
                    + "\n"
                    + "You will be notified on completion. Use /workflows to view live progress.";
            log.info("WorkflowTool 已启动 workflow（后台运行）：runId={} workflow={} scriptPath={}（CC WorkflowTool.ts:159-171）",
                    result.runId(), workflowName, scriptPath);
            return ToolResult.success(call.id(), output);
        } catch (ExecutionException e) {
            String msg = rootMessage(e);
            log.warn("WorkflowTool.launch 失败：{}（回错误给模型，service.launch 解析/校验失败）", msg);
            return ToolResult.error(call.id(), "Error: " + msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WorkflowTool.launch 等待中断：{}", e.getMessage());
            return ToolResult.error(call.id(), "Error: workflow launch interrupted");
        } catch (Exception e) {
            log.warn("WorkflowTool.launch 失败：{}（回错误给模型）", e.getMessage());
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 内部辅助（对齐 CC WorkflowTool.ts 私有函数）
    // ════════════════════════════════════════════════════════════════════════

    /** 三源解析结果 · CC original: {@code {script, workflowFile?}}（WorkflowTool.ts:88-96）。 */
    private record ResolvedScript(String script, String workflowFile) {
    }

    /**
     * 解析脚本三源 · CC original: {@code resolveScriptSource}（WorkflowTool.ts:226-261）。
     *
     * <p>script（内联）&gt; scriptPath（越界检查 + readFile）&gt; name（sanitize + resolveNamedWorkflow）。
     * 缺 → Error「One of script, name, or scriptPath must be provided」。</p>
     */
    private ResolvedScript resolveScriptSource(LaunchInput input, String cwd) throws Exception {
        if (input.script() != null) {
            if (log.isDebugEnabled()) {
                log.debug("resolveScriptSource：script 内联（CC WorkflowTool.ts:230）");
            }
            return new ResolvedScript(input.script(), null);
        }
        if (input.scriptPath() != null) {
            Path resolved = Path.of(cwd).resolve(input.scriptPath()).normalize();
            if (!containsPath(cwd, resolved)) {
                throw new IllegalArgumentException("scriptPath \"" + input.scriptPath()
                        + "\" is out of bounds (after resolve, " + resolved + " is not within cwd " + cwd + ")");
            }
            if (log.isDebugEnabled()) {
                log.debug("resolveScriptSource：scriptPath={} → readFile（CC WorkflowTool.ts:231-241）", input.scriptPath());
            }
            return new ResolvedScript(Files.readString(resolved), resolved.toString());
        }
        if (input.name() != null) {
            String sanitized = sanitizeWorkflowName(input.name());
            if (sanitized == null) {
                throw new IllegalArgumentException("Named workflow name \"" + input.name()
                        + "\" is invalid (contains path separators or is . / ..)");
            }
            NamedWorkflows.NamedWorkflow found = NamedWorkflows.resolveWithFallback(cwd, sanitized);
            if (found == null) {
                throw new IllegalArgumentException("Named workflow \"" + input.name()
                        + "\" not found (looked in " + WorkflowConstants.WORKFLOW_DIR_NAME
                        + " primary, .claude/workflows/ fallback)");
            }
            if (log.isDebugEnabled()) {
                log.debug("resolveScriptSource：name={} → path={}（CC WorkflowTool.ts:243-258）", input.name(), found.path());
            }
            return new ResolvedScript(found.content(), found.path());
        }
        throw new IllegalArgumentException("One of script, name, or scriptPath must be provided");
    }

    /**
     * cwd 解析 · 与 {@code WorkflowServiceImpl.resolveProjectRoot} 同源（memory：session-bound-dir-is-cc-startup-dir）。
     * 会话绑定项目（sessionId → {@link CwdResolution#getCwd}）；无会话（cron/后台/测试）回落 user.dir。
     */
    private String resolveCwd(ToolUseContext ctx) {
        if (ctx != null && ctx.sessionId() != null) {
            return CwdResolution.getCwd(ctx.sessionId());
        }
        return System.getProperty("user.dir");
    }

    /** input JsonNode → LaunchInput · CC original: Pick&lt;WorkflowInput, 8 字段&gt;（schema.ts:4-41）。 */
    private LaunchInput toLaunchInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("Workflow input must be a JSON object");
        }
        return new LaunchInput(
                textOrNull(input, "script"),
                textOrNull(input, "name"),
                textOrNull(input, "scriptPath"),
                argsOrNull(input),
                textOrNull(input, "description"),
                textOrNull(input, "resumeFromRunId"),
                textOrNull(input, "title"),
                intOrNull(input, "maxConcurrency"));
    }

    private static String textOrNull(JsonNode input, String key) {
        JsonNode n = input.get(key);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    /** args：CC z.unknown 任意 JSON 值 → Java Object（LinkedHashMap/ArrayList/String/Number）。 */
    private static Object argsOrNull(JsonNode input) {
        JsonNode n = input.get("args");
        if (n == null || n.isNull()) {
            return null;
        }
        return MAPPER.convertValue(n, Object.class);
    }

    private static Integer intOrNull(JsonNode input, String key) {
        JsonNode n = input.get(key);
        if (n == null || !n.isNumber()) {
            return null;
        }
        return n.asInt();
    }

    /**
     * 越界检查 · CC original: {@code containsPath}（engine/paths.ts:8-13）。
     * 语义：target 解析后位于 base 内（含相等）；Path.startsWith 按段比较天然满足 sep 边界
     * （{@code /foo} 不是 {@code /foobar} 的父目录）。
     */
    private static boolean containsPath(String base, Path resolvedTarget) {
        Path rb = Path.of(base).toAbsolutePath().normalize();
        Path rt = resolvedTarget.toAbsolutePath().normalize();
        return rt.equals(rb) || rt.startsWith(rb);
    }

    /**
     * 命名 workflow 名校验 · CC original: {@code sanitizeWorkflowName}（engine/paths.ts:20-26）。
     * 拒绝：路径分隔符 / 空字节 / 纯 '.' / '..'；非法返回 null。
     */
    private static String sanitizeWorkflowName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            return null;
        }
        if (".".equals(name) || "..".equals(name)) {
            return null;
        }
        return name;
    }

    /** 解包 ExecutionException/CompletionException 取根因消息。 */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof ExecutionException
                || cur instanceof java.util.concurrent.CompletionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : String.valueOf(cur);
    }

    /** input 键列表（debug 日志用）。 */
    private static String collectKeys(JsonNode input) {
        StringBuilder sb = new StringBuilder("[");
        var it = input.fieldNames();
        boolean first = true;
        while (it.hasNext()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(it.next());
            first = false;
        }
        return sb.append(']').toString();
    }
}
