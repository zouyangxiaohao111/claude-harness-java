package com.nexusai.application.agent.coordinator;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.tool.AgentToolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinator 模式的 worker Agent 定义 · 对齐 CC {@code src/coordinator/workerAgent.ts}（67 行全文）。
 *
 * <p><b>WHY 存在</b>：coordinator 系统提示有 6 处要求模型用 {@code subagent_type: "worker"} 派活
 * （{@link CoordinatorMode#getCoordinatorSystemPrompt()}，逐字对齐 CC coordinatorMode.ts:111-369），
 * 但此前 Java 端<b>从未注册过名为 {@code worker} 的 agent</b> —— 模型每次派活都撞
 * {@code Agent type 'worker' not found. Available agents: ...}
 * （{@code SubagentExecutor:1594-1600}，可用列表来自 {@link BuiltInAgents#getBuiltInAgents()}）。
 * CC 侧这条链靠两半闭合：{@code getBuiltInAgents()} 的 coordinator 分支（builtInAgents.ts:33-41）
 * + 本文件；Java 的「工具侧/提示侧」早已对齐，缺的正是本文件（定义侧）。
 *
 * <h2>CC 对齐要点</h2>
 * <ul>
 *   <li>{@code agentType = 'worker'}（workerAgent.ts:42）</li>
 *   <li>{@code whenToUse}（:43-44）逐字</li>
 *   <li>{@code tools = getWorkerTools()}（:35-39, :45）: {@code ASYNC_AGENT_ALLOWED_TOOLS}
 *       减去 {@code INTERNAL_ORCHESTRATION_TOOLS}（:24-29）—— 元数据里的 {@code source: 'built-in'}
 *       / {@code baseDir: 'built-in'} 由 {@link AgentDefinition.BuiltInAgentDefinition} 恒等表达
 *       （{@code source()} 恒返回 {@code "built-in"}、{@code baseDir()} 恒 {@code Optional.of("built-in")}）</li>
 *   <li>{@code getSystemPrompt()}（:48-58）：反引号内 785 字符全文，逐字对齐（含第 1 行后的空行；
 *       末行结尾<b>无</b>换行）</li>
 *   <li>{@link #getCoordinatorAgents()}（:65-67）返回 {@code [WORKER_AGENT]}</li>
 * </ul>
 *
 * <h2>与 CC 的两点刻意差异（均已核对 CC 真源，非遗漏）</h2>
 * <ol>
 *   <li><b>内部工具集合的第二处定义</b>：CC workerAgent.ts:24-29 自建
 *       {@code INTERNAL_ORCHESTRATION_TOOLS}，与 coordinatorMode.ts:29-34 的
 *       {@code INTERNAL_WORKER_TOOLS} 是<b>两份内容相同的常量</b>。Java 端收敛为一处 ——
 *       直接引用 {@link CoordinatorMode#INTERNAL_WORKER_TOOLS}（值面与 CC 两份都相同），
 *       避免「改名只改一处」的静默漏删（对齐本项目 CoordinatorModeInternalToolsTest 的教训）。</li>
 *   <li><b>system prompt 的包装</b>：CC 的 {@code agentDefinition.getSystemPrompt()} 返回裸 785 字符，
 *       但 {@code runAgent.ts:901-919 getAgentSystemPrompt} 会把它塞进
 *       {@code enhanceSystemPromptWithEnvDetails([agentPrompt], model, dirs, tools)}，追加
 *       {@code notes + envInfo}（prompts.ts:728-765）。Java 的对应入口是
 *       {@link AgentDefinition#getSystemPrompt(String, List)}（= CC getAgentSystemPrompt 的位置，
 *       入参同为 modelId + additionalWorkingDirectories），故此处走
 *       {@link BuiltInAgents#buildStandaloneSystemPrompt}（与 statusline-setup / verification
 *       同款独立全文路径）——{@link #WORKER_SPECIFIC_PROMPT} 仍是 CC 的 785 字符全文，
 *       {@link AgentDefinition#getSystemPrompt(String, List)} 的返回值 = 该全文 + notes + env。</li>
 * </ol>
 */
public final class WorkerAgentDefinition {

    private static final Logger log = LoggerFactory.getLogger(WorkerAgentDefinition.class);

    /** CC workerAgent.ts:42 {@code agentType: 'worker'}。 */
    public static final String WORKER_AGENT_TYPE = "worker";

    /** CC workerAgent.ts:43-44 {@code whenToUse} 字面量（逐字）。 */
    public static final String WHEN_TO_USE =
        "Worker agent for coordinator mode. Executes research, implementation, and verification tasks "
            + "autonomously with the full standard tool set.";

    /**
     * CC workerAgent.ts:49-58 {@code getSystemPrompt} 模板字符串内容全文（785 字符，逐字）。
     *
     * <p>文本块解析说明：内容为 10 行（第 1 行后有一个空行 + {@code Guidelines:} + 7 条 bullet），
     * 两处破折号是 CC 原文的 U+2014 EM DASH（非 {@code --}）。
     *
     * <p>{@code stripTrailing()} 的必要性：Java 文本块恒在内容尾追加一个 {@code \n}（关闭分隔符
     * 独占行），而 CC 的闭合反引号紧贴末行 {@code ...unless explicitly instructed.} → 内容结尾
     * <b>无</b>换行。去尾以达逐字一致（同 {@link CoordinatorMode#getCoordinatorSystemPrompt()} 手法；
     * 本 prompt 无 CC 行尾空白行，故只需 stripTrailing，不需要占位符还原。
     * 行尾空白剥离见 JLS 3.10.6 —— 本 prompt 各行均无尾随空白，对照 CC 原文已核）。
     */
    public static final String WORKER_SPECIFIC_PROMPT =
        """
        You are a worker agent spawned by a coordinator. Your job is to complete the task described in the prompt thoroughly and report back with a concise summary of what you did and what you found.

        Guidelines:
        - Complete the task fully — don't leave it half-done, but don't gold-plate either.
        - Use tools proactively: read files, search code, run commands, edit files.
        - Be thorough in research: check multiple locations, consider different naming conventions.
        - For implementation: make targeted changes, run tests to verify, commit if appropriate.
        - Report back with actionable findings — the coordinator will synthesize your results.
        - If you encounter errors, investigate and attempt to fix them before reporting failure.
        - NEVER create documentation files unless explicitly instructed.""".stripTrailing();

    /**
     * worker 可用工具 · 对齐 CC {@code getWorkerTools()}（workerAgent.ts:35-39）。
     *
     * <p>取值 = {@link AgentToolUtils#ASYNC_AGENT_ALLOWED_TOOLS} 减去
     * {@link CoordinatorMode#INTERNAL_WORKER_TOOLS}。减法按字符串相等匹配，<b>写错名字不会报错</b>，
     * 只是静默漏删一项 → worker 拿到 coordinator 专属编排原语（可自建/解散团队、直接给别的 agent
     * 发消息、产出结构化输出绕过协调者）。
     *
     * <p>实测交集说明：{@code ASYNC_AGENT_ALLOWED_TOOLS}（17 项）里真正被减掉的只有
     * {@code StructuredOutput} 一项（{@code ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME}）——
     * TeamCreate / TeamDelete / SendMessage 本就不在 async 白名单内。因此「减法写错」的可观测
     * 后果恰好落在 StructuredOutput 上（由 WorkerAgentDefinitionTest 双向钉住）。
     *
     * <p>迭代序：CC 用 {@code Array.from(Set).filter(...)}，Java 用 {@link ArrayList} 遍历同一
     * LinkedHashSet 语义的 {@link AgentToolUtils#ASYNC_AGENT_ALLOWED_TOOLS}（其实现为
     * {@code Set.of}，迭代序不稳定）——工具集合语义无顺序要求，故不额外排序
     * （与 {@code CoordinatorMode.getCoordinatorUserContext} 明确排序的用法不同：那处要 join 成字符串）。
     *
     * @return worker 的显式工具清单（不含任何内部编排工具）
     */
    public static List<String> getWorkerTools() {
        List<String> workerTools = new ArrayList<>();
        for (String name : AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS) {
            if (!CoordinatorMode.INTERNAL_WORKER_TOOLS.contains(name)) {
                workerTools.add(name);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[WorkerAgentDefinition] getWorkerTools 生成 worker 工具清单: total={} removed={} "
                    + "（CC workerAgent.ts:35-39 ASYNC_AGENT_ALLOWED_TOOLS − INTERNAL_ORCHESTRATION_TOOLS）",
                workerTools.size(), AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS.size() - workerTools.size());
        }
        return workerTools;
    }

    /**
     * worker 内置 Agent 定义 · 对齐 CC {@code WORKER_AGENT}（workerAgent.ts:41-59）。
     *
     * <p>静态常量（非工厂方法）对齐 CC 的 module-level 常量语义：每次 {@link #getCoordinatorAgents()}
     * 返回<b>同一个</b>定义对象（CC {@code return [WORKER_AGENT]} 亦然），故下游按身份缓存/比较安全。
     */
    public static final AgentDefinition WORKER_AGENT = AgentDefinition.BuiltInAgentDefinition.create(
        WORKER_AGENT_TYPE,
        WHEN_TO_USE,
        getWorkerTools(),
        // getSystemPrompt：CC runAgent.ts:901-919 getAgentSystemPrompt = [agentPrompt] + notes + env
        //   （prompts.ts:728-765）；Java 对应入口 = buildStandaloneSystemPrompt（无 DEFAULT_AGENT_PROMPT
        //   前缀，同 statusline-setup / verification）。
        (modelId, dirs) -> BuiltInAgents.buildStandaloneSystemPrompt(WORKER_SPECIFIC_PROMPT, modelId, dirs)
    );

    /**
     * coordinator 模式下可用的 agent 列表 · 对齐 CC {@code getCoordinatorAgents()}
     * （workerAgent.ts:65-67 {@code return [WORKER_AGENT]}）。
     *
     * <p>调用方 = {@link BuiltInAgents#getBuiltInAgents()} 的 coordinator 分支
     * （CC builtInAgents.ts:33-41）。CC 该分支是<b>整表替换</b>（直接 return，base 列表消失），
     * 故本列表当前只有 worker —— 这是 CC 语义，不是遗漏。
     *
     * @return {@code [WORKER_AGENT]}
     */
    public static List<AgentDefinition> getCoordinatorAgents() {
        return List.of(WORKER_AGENT);
    }

    private WorkerAgentDefinition() {
    }
}
