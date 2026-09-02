package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.memory.LoadMemoryPrompt;

import java.util.List;
import java.util.Set;

/**
 * 系统提示组装输入 · 对齐 CC {@code getSystemPrompt(tools, model, additionalWorkingDirectories, mcpClients)}
 * 的参数（prompts.ts:444-449）及会话级动态上下文。
 *
 * <p>CC 签名：
 * <pre>{@code
 * export async function getSystemPrompt(
 *   tools: Tools,                    // → enabledTools: new Set(tools.map(_ => _.name))（prompts.ts:464）
 *   model: string,                   // → model
 *   additionalWorkingDirectories?: string[],  // → additionalWorkingDirs
 *   mcpClients?: MCPServerConnection[],       // → mcpClients
 * ): Promise<string[]>
 * }</pre>
 * 另含 getSystemPrompt 内部计算/读取的会话上下文：
 * skillToolCommands（prompts.ts:459 getSkillToolCommands）、outputStyleConfig（prompts.ts:460
 * getOutputStyleConfig）、settings.language（prompts.ts:461 getInitialSettings）。
 *
 * <p>memory 动态 section 依赖现有 {@link LoadMemoryPrompt}（memory 模块，CC memdir.ts:419-490
 * loadMemoryPrompt 等价）；组件层以 nullable 字段注入，为 null 时 memory compute 返回 null
 * （该 section 不产生内容）。
 *
 * @param enabledTools          当前 LLM 可用工具名集合 · CC original: new Set(tools.map(_ => _.name))
 *                              (prompts.ts:464)
 * @param model                 模型 ID · CC original: model（prompts.ts:445）
 * @param additionalWorkingDirs 附加工作目录 · CC original: additionalWorkingDirectories（prompts.ts:446）
 * @param mcpClients            MCP 客户端连接（connected 过滤 + instructions 拼装见 mcp_instructions
 *                              compute，CC getMcpInstructions prompts.ts:578-608）· CC original: mcpClients
 * @param outputStyleConfig     输出风格配置（null=未配置）· CC original: outputStyleConfig（prompts.ts:460）
 * @param skillToolCommands     skill 命令列表（非空 且 enabledTools 含 Skill 时 session_guidance 注入
 *                              skill 子弹）· CC original: skillToolCommands（prompts.ts:459）
 * @param language              语言偏好 · CC original: settings.language（prompts.ts:461 getInitialSettings）
 * @param memoryLoader          MEMORY.md 加载器（memory 模块）· CC original: loadMemoryPrompt()（memdir.ts:419-490）
 * @param tokenBudgetEnabled {@code feature('TOKEN_BUDGET')} 门 · CC original: feature('TOKEN_BUDGET')
 *                           （prompts.ts:538，token_budget section 注册门；关时 section 不注册）
 */
public record SystemPromptAssemblyInput(
    Set<String> enabledTools,
    String model,
    List<String> additionalWorkingDirs,
    List<McpClientInfo> mcpClients,
    OutputStyleConfig outputStyleConfig,
    List<String> skillToolCommands,
    String language,
    LoadMemoryPrompt memoryLoader,
    boolean tokenBudgetEnabled,
    /**
     * 会话 ID（short 形态 sess-xxx，可空）· CC original: 无对应——CC getSystemPrompt
     * 参数（prompts.ts:444-449）无 sessionId（单进程全局 STATE）。Java 多会话补充字段，
     * 供 {@code env_info_simple} 的 cwd 会话解析（cwd(input.sessionId()) 显式传会话，
     * 不再依赖 MDC——渲染在 ForkJoinPool 线程无 MDC，回落 user.dir 导致 cwd 错误）。
     * null = 无会话上下文（web analyze 等）→ env cwd 走 cwdSupplier/MDC 兜底（现行为）。
     */
    String sessionId,
    /**
     * 会话级非交互门控 · CC original: getIsNonInteractiveSession()（bootstrap/state.ts:1057，
     * {@code !STATE.isInteractive}）——session_guidance '!' 命令子弹门（prompts.ts:368-370，
     * 非交互返回 null → 不注入）。Java 经 sessions.non_interactive_session 会话列接入
     * （SP-10，V57；null/0→false）；既有调用零改动默认 false（Java Web 恒交互式，OPD-SP-22）。
     */
    boolean nonInteractiveSession,
    /**
     * scratchpad 门控 · CC original: isScratchpadEnabled()（permissions/filesystem.ts:298，
     * feature('tengu_scratch')）——scratchpad 目录使用指令段门（prompts.ts:797-819）。
     * Java 经 resolver.scratchpadEnabled()（settings 列，null→false）。
     */
    boolean scratchpadEnabled,
    /**
     * frc 门控 · CC original: feature('CACHED_MICROCOMPACT') && getCachedMCConfigForFRC
     * （prompts.ts:821-839，Function Result Clearing 段）——Java 经 resolver.frcEnabled()
     * （settings 列，null→false；无 CACHED_MICROCOMPACT feature 等价物 → 门控承载）。
     */
    boolean frcEnabled
) {

    /**
     * 9 参便捷构造器（sessionId=null + 三个 SP-10/05/06 门控 false）· 供既有 9 参调用点
     * 零改动迁移（ContextAnalyzeService/ResumeService 等 + 测试），null/false 语义 =
     * 会话上下文缺失 + 门控关闭（现行为零变化）。
     */
    public SystemPromptAssemblyInput(
            Set<String> enabledTools,
            String model,
            List<String> additionalWorkingDirs,
            List<McpClientInfo> mcpClients,
            OutputStyleConfig outputStyleConfig,
            List<String> skillToolCommands,
            String language,
            LoadMemoryPrompt memoryLoader,
            boolean tokenBudgetEnabled) {
        this(enabledTools, model, additionalWorkingDirs, mcpClients, outputStyleConfig,
            skillToolCommands, language, memoryLoader, tokenBudgetEnabled, null,
            false, false, false);
    }

    /**
     * 10 参便捷构造器（三个 SP-10/05/06 门控 false）· 保留既有 10 参调用点零改动迁移
     * （含 sessionId 的旧 canonical 形态，SessionMemoryService/PartialCompactService 等），
     * 门控关闭语义 = 现行为零变化。
     */
    public SystemPromptAssemblyInput(
            Set<String> enabledTools,
            String model,
            List<String> additionalWorkingDirs,
            List<McpClientInfo> mcpClients,
            OutputStyleConfig outputStyleConfig,
            List<String> skillToolCommands,
            String language,
            LoadMemoryPrompt memoryLoader,
            boolean tokenBudgetEnabled,
            String sessionId) {
        this(enabledTools, model, additionalWorkingDirs, mcpClients, outputStyleConfig,
            skillToolCommands, language, memoryLoader, tokenBudgetEnabled, sessionId,
            false, false, false);
    }

    /**
     * MCP 客户端连接信息 · 对齐 CC {@code MCPServerConnection}
     * （CC original: {@code type MCPServerConnection = { name: string; instructions?: string } & ({ type: 'connected' } | { type: 'disconnected' })}
     * ，prompts.ts:578-579 getMcpInstructions 过滤 {@code type === 'connected'} 且含 instructions）。
     *
     * @param name         客户端名 · CC original: name
     * @param instructions 使用说明（可空）· CC original: instructions?
     * @param connected    是否已连接 · CC original: type === 'connected'
     */
    public record McpClientInfo(String name, String instructions, boolean connected) {}
}
