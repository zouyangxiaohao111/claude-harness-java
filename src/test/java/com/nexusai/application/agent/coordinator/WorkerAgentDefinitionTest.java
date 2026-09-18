package com.nexusai.application.agent.coordinator;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.tool.AgentToolUtils;
import com.nexusai.application.agent.tool.ToolNameConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator 缺件补齐 · 定义侧] worker 内置 Agent 定义 · 对齐 CC
 * {@code src/coordinator/workerAgent.ts}（67 行全文）。
 *
 * <h2>WHY 存在（意图验证）</h2>
 * <p>CC coordinator 模式的系统提示有 <b>6 处</b>把 {@code subagent_type: "worker"} 写死给模型
 * （{@code CoordinatorMode.getCoordinatorSystemPrompt()} 逐字对齐 CC coordinatorMode.ts:111-369）。
 * 但 Java 端长期只有「工具侧/提示侧」，<b>没有任何地方注册过名为 {@code worker} 的 agent 定义</b> ——
 * 于是模型每次照提示派活都撞
 * {@code Agent type 'worker' not found. Available agents: ...}
 * （异常文本来自 {@code SubagentExecutor:1594-1600}，可用列表正是
 * {@link BuiltInAgents#getBuiltInAgents()}）。
 *
 * <p>所以本测试钉的不是「有个对象」，而是<b>「模型照提示派活时这条链能闭合」</b>：
 * <ol>
 *   <li>{@code get("worker")} 必须命中（否则派活必失败，见
 *       {@link #workerResolvableViaBuiltInAgentsGet_whenCoordinatorActive()}）</li>
 *   <li>worker 工具清单必须做过内部工具减法（否则 worker 拿到 coordinator 专属编排原语
 *       TeamCreate/TeamDelete/SendMessage/StructuredOutput，可自建团队、绕过协调者）</li>
 *   <li>system prompt 必须与 CC 逐字一致（提示词错一个字就是行为漂移，
 *       且这是「模型看到的唯一指令」）</li>
 * </ol>
 *
 * <h2>夹具独立性（对齐本项目 test-env-failloud-unreachable 教训）</h2>
 * <p>coordinator 门在测试环境里<b>结构上不可达</b>（application.yml 默认
 * {@code nexusai.feature.coordinator-mode: false}，env 也不设）——所以本类不依赖真实 env/Spring，
 * 一律经 {@link BuiltInAgents#setCoordinatorMode} 显式注入
 * {@code new CoordinatorMode(() -> true, () -> "true")} 夹具，{@code @AfterEach} 显式复位。
 * 绝不写成「靠 environment 恰好为真」。
 */
@DisplayName("coordinator 定义侧 · worker AgentDefinition (CC workerAgent.ts)")
class WorkerAgentDefinitionTest {

    /** CC 真源：src/coordinator/workerAgent.ts:43-44 whenToUse 字面量（逐字）。 */
    private static final String CC_WHEN_TO_USE =
        "Worker agent for coordinator mode. Executes research, implementation, and verification tasks "
            + "autonomously with the full standard tool set.";

    /**
     * CC 真源：src/coordinator/workerAgent.ts:49-58 getSystemPrompt 模板字符串<b>内容</b>
     * （反引号内的 785 字符全文，模板字面量不含尾随换行）。
     *
     * <p>用 golden 字面量而非「读 CC 文件」：测试必须能在无 CC 仓库的机器上跑，且差异要肉眼可读。
     */
    private static final String CC_WORKER_SYSTEM_PROMPT = """
        You are a worker agent spawned by a coordinator. Your job is to complete the task described in the prompt thoroughly and report back with a concise summary of what you did and what you found.

        Guidelines:
        - Complete the task fully — don't leave it half-done, but don't gold-plate either.
        - Use tools proactively: read files, search code, run commands, edit files.
        - Be thorough in research: check multiple locations, consider different naming conventions.
        - For implementation: make targeted changes, run tests to verify, commit if appropriate.
        - Report back with actionable findings — the coordinator will synthesize your results.
        - If you encounter errors, investigate and attempt to fix them before reporting failure.
        - NEVER create documentation files unless explicitly instructed.""";

    /** CC workerAgent.ts:49-58 模板内容字节数（python 实测：len(body)=785, 无尾随换行）。 */
    private static final int CC_PROMPT_CHARS = 785;

    /** CC workerAgent.ts:49-58 模板内容 UTF-8 SHA-256（python 实测，见报告 CC 真源核对节）。 */
    private static final String CC_PROMPT_SHA256 =
        "1d6b0f236cb57b77dc2d9a8b213279c5829aa4703ba2e93b269545bf0dccd213";

    /** coordinator 门夹具（feature=true + env="true" → isCoordinatorMode()=true），不依赖 env/Spring。 */
    private static CoordinatorMode coordinatorOn() {
        return new CoordinatorMode(() -> true, () -> "true");
    }

    @AfterEach
    void resetCoordinatorGate() {
        // 显式复位到默认（feature 恒关）——static 槽位必须每例清理，否则污染同 JVM 的其他测试。
        BuiltInAgents.setCoordinatorMode(null);
    }

    // ─────────────────────── 1. 可命中性（本任务存在的理由） ───────────────────────

    @Test
    @DisplayName("coordinator 激活时 BuiltInAgents.get('worker') 命中 —— 模型派活不再撞 Agent type not found")
    void workerResolvableViaBuiltInAgentsGet_whenCoordinatorActive() {
        // GIVEN: coordinator 门显式打开（不依赖 env/Spring）
        BuiltInAgents.setCoordinatorMode(coordinatorOn());

        // WHEN: SubagentExecutor.resolveAgentDefinition → BuiltInAgents.get("worker")
        AgentDefinition def = BuiltInAgents.get("worker");

        // THEN: 必须命中，且是内置定义（source='built-in'）
        assertThat(def)
            .as("coordinator 提示 6 处写死 subagent_type=\"worker\"；此处返回 null 即模型每次派活撞 "
                + "Agent type 'worker' not found（异常文本 SubagentExecutor:1594-1600）")
            .isNotNull();
        assertThat(def.agentType()).isEqualTo("worker");
        assertThat(def.source()).isEqualTo("built-in");
    }

    @Test
    @DisplayName("coordinator 关闭时 get('worker') 为 null —— worker 只在 coordinator 模式存在")
    void workerNotResolvableWhenCoordinatorInactive() {
        // GIVEN: 默认门（feature 恒关）
        BuiltInAgents.setCoordinatorMode(null);

        // WHEN / THEN: CC builtInAgents.ts:33-41 只在 coordinator 模式返回 coordinator agents，
        //   非 coordinator 会话里 worker 不该出现在可用列表（否则模型在普通会话也能派 worker）
        assertThat(BuiltInAgents.get("worker")).isNull();
    }

    @Test
    @DisplayName("getCoordinatorAgents() 只含 worker（CC workerAgent.ts:65-67 返回 [WORKER_AGENT]）")
    void getCoordinatorAgentsReturnsOnlyWorker() {
        List<AgentDefinition> agents = WorkerAgentDefinition.getCoordinatorAgents();

        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).agentType()).isEqualTo("worker");
        assertThat(agents.get(0)).isSameAs(WorkerAgentDefinition.WORKER_AGENT);
    }

    // ─────────────────────── 2. 工具清单（内部编排原语必须减掉） ───────────────────────

    @Test
    @DisplayName("worker 工具 = ASYNC_AGENT_ALLOWED_TOOLS − INTERNAL_WORKER_TOOLS（对齐 CC getWorkerTools）")
    void workerToolsEqualAsyncAllowedMinusInternal() {
        Set<String> async = AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS;
        Set<String> internal = CoordinatorMode.INTERNAL_WORKER_TOOLS;

        // 交集：ASYNC 白名单里真正被减掉的项。实测只有 StructuredOutput 一项
        //   （TeamCreate/TeamDelete/SendMessage 本就不在 async 白名单里）——先钉住这个事实，
        //   使下面的 size 断言有依据；顺带防止「减法写的名字全都命中不了」这种静默失效。
        List<String> removed = async.stream().filter(internal::contains).collect(Collectors.toList());
        assertThat(removed)
            .as("ASYNC 白名单与内部工具集合的交集必须恰为 {StructuredOutput}，否则减法规模断言失去依据")
            .containsExactly(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);

        List<String> workerTools = WorkerAgentDefinition.getWorkerTools();

        assertThat(workerTools)
            .as("worker 拿到内部编排原语 = 可自建/解散团队、直接给别的 agent 发消息、绕过协调者产出结构化输出")
            .doesNotContainAnyElementsOf(internal);
        assertThat(workerTools).hasSize(async.size() - removed.size());
    }

    @Test
    @DisplayName("worker 拿不到 StructuredOutput（CC INTERNAL_ORCHESTRATION_TOOLS 第 4 项，真名非旧名）")
    void workerToolsExcludeStructuredOutput() {
        assertThat(WorkerAgentDefinition.getWorkerTools())
            .as("CC workerAgent.ts:24-29 减法集合含 SYNTHETIC_OUTPUT_TOOL_NAME（值 'StructuredOutput'）；"
                + "写错成旧名 'SyntheticOutput' 会静默漏删")
            .doesNotContain(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME)
            .doesNotContain("SyntheticOutput");
    }

    @Test
    @DisplayName("worker 保留标准工具（Bash/Read/Edit/Write）—— CC 卖点就是 full standard tool set")
    void workerToolsKeepStandardToolSet() {
        assertThat(WorkerAgentDefinition.getWorkerTools())
            .as("whenToUse 明写 'with the full standard tool set'；research/implementation/verification "
                + "三职责都要求 Bash+读写")
            .contains(
                ToolNameConstants.BASH_TOOL_NAME,
                ToolNameConstants.FILE_READ_TOOL_NAME,
                ToolNameConstants.FILE_EDIT_TOOL_NAME,
                ToolNameConstants.FILE_WRITE_TOOL_NAME);
    }

    @Test
    @DisplayName("tools 是显式清单、不是通配符 ['*'] —— 否则内部工具会从通配符漏回来")
    void workerToolsAreExplicitNotWildcard() {
        AgentDefinition worker = WorkerAgentDefinition.WORKER_AGENT;

        assertThat(worker.tools()).isPresent();
        assertThat(worker.usesAllTools())
            .as("CC workerAgent.ts:45 tools=getWorkerTools() 是显式列表；通配符等于取消减法")
            .isFalse();
    }

    // ─────────────────────── 3. prompt 逐字对齐 ───────────────────────

    @Test
    @DisplayName("whenToUse 逐字等于 CC workerAgent.ts:43-44")
    void whenToUseVerbatimCc() {
        assertThat(WorkerAgentDefinition.WORKER_AGENT.whenToUse()).isEqualTo(CC_WHEN_TO_USE);
    }

    @Test
    @DisplayName("agent 专属 prompt 逐字等于 CC（785 字符 + SHA-256 双钉，无尾随换行）")
    void workerSpecificPromptByteExactVsCc() {
        String p = WorkerAgentDefinition.WORKER_SPECIFIC_PROMPT;

        assertThat(p)
            .as("golden 字面量逐字比对：文本块可能吃掉行尾空白/空行，肉眼看不出来，故必须整体相等")
            .isEqualTo(CC_WORKER_SYSTEM_PROMPT);
        assertThat(p.length())
            .as("CC 模板内容字符数（python 实测 len=785）")
            .isEqualTo(CC_PROMPT_CHARS);
        assertThat(sha256Hex(p))
            .as("CC workerAgent.ts:49-58 模板内容 UTF-8 SHA-256（python 实测）")
            .isEqualTo(CC_PROMPT_SHA256);
        assertThat(p)
            .as("CC 闭合反引号紧贴末行 → 内容结尾无换行；text block 恒带尾随 \\n，故实现必须 stripTrailing")
            .doesNotEndWith("\n")
            .endsWith("NEVER create documentation files unless explicitly instructed.");
    }

    @Test
    @DisplayName("getSystemPrompt() 以 CC 首行开头（含第 1 行后的空行）")
    void systemPromptStartsWithCcFirstLine() {
        String built = WorkerAgentDefinition.WORKER_AGENT.getSystemPrompt(null, List.of());

        assertThat(built)
            .as("SubagentExecutor.buildAgentSystemPrompt:3474 以此串作为 worker 的系统提示")
            .startsWith("You are a worker agent spawned by a coordinator.");
        assertThat(built)
            .as("agent 专属段必须以 CC 全文为前缀（其后才是 CC runAgent:901-919 追加的 notes + env 块）")
            .startsWith(CC_WORKER_SYSTEM_PROMPT + "\n\n");
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
