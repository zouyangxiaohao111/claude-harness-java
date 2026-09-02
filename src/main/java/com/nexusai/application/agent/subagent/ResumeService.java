package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.prompt.EffectiveSystemPromptBuilder;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptAssemblyInput;
import com.nexusai.application.agent.prompt.SystemPromptSectionCache;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;

import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /resume 后端核心编排 · 对齐 CC {@code resumeAgentBackground}
 * （Open-ClaudeCode/src/tools/AgentTool/resumeAgent.ts:42-265）。
 *
 * <h2>流程（CC resumeAgent.ts 行号）</h2>
 * <ol>
 *   <li>读 transcript + meta（:63-66，getAgentTranscript + readAgentMetadata）</li>
 *   <li>无 transcript 抛错（:67-69）</li>
 *   <li>三层过滤（:70-74，filterUnresolvedToolUses → filterOrphanedThinkingOnlyMessages →
 *       filterWhitespaceOnlyAssistantMessages，复用 {@link MessageFilters}）</li>
 *   <li>reconstructForSubagentResume（:75-79，复用 {@link ContentReplacementState}；
 *       [RES-R6] 重建结果经 Step 8 透传注入 resumed 子 agent query loop；父 live state 不可得
 *       （web 端点）→ parentState=null → 重建返 null，CC :1006 同语义 → loop 保持默认 create）</li>
 *   <li>worktree stat 校验 + 父 cwd 兜底 + utimes（:82-97）</li>
 *   <li>agent 解析（:100-112，fork → ForkSubagentAgentDefinition；否则 activeAgents.find ——
 *       [RES-R6] 经 {@link SubagentTool#agentRegistry()} 的 {@link AgentDefinitionRegistry}
 *       命中自定义 agent，registry 不可得回退 {@link BuiltInAgents#get}；未命中 → GENERAL_PURPOSE_AGENT）</li>
 *   <li>forkParentSystemPrompt 继承（:116-148，renderedSystemPrompt 优先 —— web 端点以主会话
 *       {@link AgentState#systemPrompt()} 等价；[RES-R6-1] rendered 不可得 → getSystemPrompt
 *       + buildEffectiveSystemPrompt 重建（注入 ToolRegistry/McpServerService + currentModel），
 *       两者皆不可得 → null 抛错）</li>
 *   <li>异步续跑（:198-258，registerAsyncAgent + runAsyncAgentLifecycle —— Java 端委托
 *       {@link SubagentTool#executeResumeAsync}，内部 daemon worker + AsyncAgentFinalizer 三态路由）</li>
 *   <li>返回 {agentId, description, outputFile}（:260-264）</li>
 * </ol>
 *
 * <p><b>web 端点差异</b>：无 ToolUseContext —— parentContentReplacementState / renderedSystemPrompt
 * 分别以 null / 主会话 AgentState.systemPrompt() 兜底。 [RES-R6] CRS 直接传参（对齐 CC resumeAgent.ts:76
 * {@code toolUseContext.contentReplacementState} 直接作为 reconstructForSubagentResume 入参，无注入缝）：
 * resumeAgentBackground 由调用方直接传入父 live ContentReplacementState，不可得（web 端点）传 null
 * = CC :1006 feature off；[RES-R6-1] fork 父提示重建已实现（rendered 优先 → getSystemPrompt +
 * buildEffectiveSystemPrompt，注入 ToolRegistry/McpServerService + currentModel，[#25] meta model
 * 已删改现算，见 {@link #resolveForkParentSystemPrompt}）。
 *
 * <p>依赖注入遵循 CommandController 既有风格（setter 注入，plain JUnit 缺省 null 可测）。
 */
@org.springframework.stereotype.Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    /** 异步续跑执行器（@Component bean，setter 注入；plain JUnit 直接 setter mock → 缺省 null fail loud）。 */
    private SubagentTool subagentTool;
    /** 会话级主 AgentState 注册表 · fork resume 父 system prompt 来源（AgentState.systemPrompt()）。 */
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * [RES-R6-1] 工具注册中心 · fork 父提示重建的 enabledTools 源（CC resumeAgent.ts:130
     * {@code options.tools} → prompts.ts:464 {@code new Set(tools.map(_ => _.name))}）。
     *
     * <p>@Component bean，setter 注入（沿用本类既有风格）；plain JUnit 缺省 null → 重建不可得
     * → null → 调用方抛错（fail loud，不伪造字节）。
     */
    private ToolRegistry toolRegistry;
    /**
     * [RES-R6-1] MCP 服务 · fork 父提示重建的 MCP 工具源（CC resumeAgent.ts:130
     * {@code options.tools} 已并入 MCP 工具）。{@link McpServerService#getCurrentTools()}；
     * null → MCP 工具跳过（仅 ToolRegistry.all() 作 enabledTools，软降级不阻断重建）。
     */
    private McpServerService mcpServerService;

    /**
     * [OD-2A-4] 消息持久化载体 · resume 目录上下文恢复的首条消息 cwd 源。
     *
     * <p>CC 真源（自验，不信注释）：{@code sessionStorage.ts:2522/4680}
     * {@code projectPath: firstMessage.cwd} —— /resume 与 session 列表用首条消息的 cwd 作
     * projectPath（V22/G13 已落 messages 表 cwd 列）。Java 端 {@code ResumeService} 恢复
     * {@code SessionCwdHolder} 需要读主会话首条消息 cwd：经本 mapper
     * （{@code eq("session_id", sessionId).orderBy("created_at", true).limit(1)}）取链首
     * （CC firstMessage = chain[0]）。
     *
     * <p>setter 注入（沿用本类既有风格）：plain JUnit 缺省 null → 目录恢复跳过（软降级，
     * 不阻断 resume 主流程；CC 旧 jsonl 无 cwd 字段同容错）。生产经 Spring 注入。
     */
    private MessageMapper messageMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSubagentTool(SubagentTool subagentTool) {
        this.subagentTool = subagentTool;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setSessionAgentStateRegistry(SessionAgentStateRegistry registry) {
        this.sessionAgentStateRegistry = registry;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setMcpServerService(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    /** [OD-2A-4] 消息 mapper 注入（见字段注释）。plain JUnit 不注入 → 目录恢复跳过。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMessageMapper(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }


    /**
     * resume 后台续跑子 Agent · 对齐 CC resumeAgent.ts:42-265。
     *
     * @param agentId   待恢复 sub-agent UUID（S-12 可逆桥编码：a+16hex 原键经 packAgentId 入参 /
     *                  legacy UUID 兼容；transcript/metadata 双键查找，见 {@link #resumeAgentBackground} 首段）
     * @param prompt    resume 追加用户指令
     * @param sessionDir 会话目录根（[R1] config-home 项目 slug 目录，与 SubagentExecutor.resolveSessionDir 同源）
     * @param sessionId 主会话 UUID
     * @param parentContentReplacementState 父 live ContentReplacementState · 对齐 CC resumeAgent.ts:76
     *                             {@code toolUseContext.contentReplacementState}（reconstructForSubagentResume
     *                             的 parentState 入参，直接传参无注入缝）；web 端点父 live state 不可得
     *                             → 传 null = CC :1006 feature off（受控残留）
     * @return {agentId, description, outputFile}（CC :260-264；后台线程实际续跑）。
     *         [R-A45 B-4 D18/B2] agentId 返回 a+16hex（前端入参同形制，CC resumeAgent.ts:261
     *         {@code agentId} 即原 string），前端可持返回值再入二次 resume 回环
     */
    public ResumeAgentResult resumeAgentBackground(UUID agentId, String prompt,
                                                   Path sessionDir, String sessionId,
                                                   ContentReplacementState parentContentReplacementState) {
        String agentIdStr = agentId.toString();
        String sessionIdStr = sessionId;
        // [R3-WF-F IMP-SUB-12 返工] a+16hex transcript 键（D18/B2）：子代理域以
        //   AgentContext.createAgentId() 生成 a+16hex，经 S-12 pack 桥存 ToolUseContext.agentId(UUID)，
        //   transcript/metadata 键还原 a+16hex。resume 请求携带 packed UUID → unpackAgentId 还原
        //   a+16hex 查找新格式 transcript（CC resumeAgent.ts:63-66 getAgentTranscript(asAgentId(agentId))）；
        //   旧格式（UUID 键）经 uuidKey fallback 兼容（pre-migration transcript 不丢）。
        String agentIdHex = AgentContext.unpackAgentId(agentId);

        // ── 1. 读 transcript + meta（CC :63-66）── 双键查找（a+16hex 新格式优先，UUID 旧格式兜底）
        Optional<AgentTranscript.AgentTranscriptResult> transcriptOpt =
            AgentTranscript.getAgentTranscript(sessionDir, sessionIdStr, agentIdHex)
                .or(() -> AgentTranscript.getAgentTranscript(sessionDir, sessionIdStr, agentIdStr));
        AgentTranscript.AgentMetadata meta =
            AgentTranscript.readMetadata(sessionDir, sessionIdStr, agentIdHex)
                .or(() -> AgentTranscript.readMetadata(sessionDir, sessionIdStr, agentIdStr)).orElse(null);

        // ── 2. 无 transcript 抛错（CC :67-69 throw new Error; REST 语义 → 404 NotFound）──
        if (transcriptOpt.isEmpty()) {
            log.warn("[ResumeService] 未找到 transcript, 抛错: agentId={}", agentIdStr);
            throw new NotFoundException("No transcript found for agent ID: " + agentIdStr);
        }
        AgentTranscript.AgentTranscriptResult transcript = transcriptOpt.get();

        // ── 2.5 [OD-2A-4 · INV-2] resume 跨会话持久化：首条消息 cwd → SessionCwdHolder 恢复 ──
        // 对齐 CC sessionStorage.ts:2522/4680 {@code projectPath: firstMessage.cwd} —— CC /resume
        // 与 session 列表用首条消息的 cwd 作为 projectPath 恢复目录上下文（目录在会话启动/绑定时
        // 定死，resume 后不再回落 boundProject）。Java 等价：resume 读主会话首条消息 cwd（G13 已落
        // messages 表 cwd 列，V22）→ 恢复 SessionCwdHolder（会话级可变 cwd 槽），使 resumed 会话
        // getCwd() 解析到原启动目录而非 boundProject/user.dir。同源 OD-2A-6（嵌套 worktree
        // originalCwd 持久化，Enter/Exit 栈接线）。
        // 注意：CC 真源里 subagent resume（resumeAgent.ts）恢复目录走 meta.worktreePath →
        // runWithCwdOverride（:82-92/227-228），而 firstMessage.cwd 是主会话 logs/session 列表的
        // projectPath（:2522/4680，UI/restoreReadFileState）。本方法实现的「首条消息 cwd → 恢复
        // SessionCwdHolder」是 Java 侧对 projectPath 语义的落地（V22/G13 既有设计，任务指定），
        // 与 CC subagent worktreePath 恢复互补而非互斥。resume 目录恢复必须发生在本方法（后台
        // 续跑前）——CC 在 switchSession/restoreSessionMetadata 阶段恢复，Java 等价在 transcript
        // 读取后、异步续跑前。
        restoreSessionCwd(sessionId);

        // ── 3. 三层过滤（CC :70-74，复用 MessageFilters）──
        List<AgentMessage> resumedMessages = MessageFilters.filterWhitespaceOnlyAssistantMessages(
            MessageFilters.filterOrphanedThinkingOnlyMessages(
                MessageFilters.filterUnresolvedToolUses(transcript.messages())));
        if (log.isDebugEnabled()) {
            log.debug("[ResumeService] 三层过滤: transcript.messages.size={} → resumedMessages.size={} "
                    + "(CC resumeAgent.ts:70-74)", transcript.messages().size(), resumedMessages.size());
        }

        // ── 4. reconstructForSubagentResume（CC :75-79）──
        // parentState = 父 live ContentReplacementState，直接传参（对齐 CC resumeAgent.ts:76
        //   toolUseContext.contentReplacementState 直接作为 reconstructForSubagentResume 入参，无注入缝）。
        // web 端点父 live state 不可得 → 传 null → 重建返 null，CC :1006 reconstructForSubagentResume
        //   对 null parent 返回 undefined 同语义（受控残留）。
        // [RES-R6] 重建结果注入 resumed 子 agent query loop（Step 8 透传 → SubagentExecutor Step 20
        //   → query.ts:372-389 applyToolResultBudget 消费同一实例）；null → loop 保持默认 create。
        ContentReplacementState resumedReplacementState =
            ContentReplacementState.reconstructForSubagentResume(
                parentContentReplacementState, resumedMessages, transcript.contentReplacements());
        if (log.isDebugEnabled()) {
            log.debug("[ResumeService] reconstructForSubagentResume: parentState={} 重建结果={} "
                    + "(CC resumeAgent.ts:75-79 + :194)",
                parentContentReplacementState != null, resumedReplacementState != null);
        }

        // ── 5. worktree stat 校验 + 父 cwd 兜底 + utimes（CC :82-97）──
        String resumedWorktreePath = resolveWorktreePath(meta);

        // ── 6. agent 解析（CC :100-112）──
        boolean isResumedFork = meta != null
            && ForkSubagent.FORK_SUBAGENT_TYPE.equals(meta.agentType());
        AgentDefinition selectedAgent = resolveSelectedAgent(meta, isResumedFork);
        if (log.isDebugEnabled()) {
            log.debug("[ResumeService] agent 解析: meta.agentType={}, isResumedFork={}, selectedAgent={} "
                    + "(CC resumeAgent.ts:100-112)",
                meta != null ? meta.agentType() : null, isResumedFork, selectedAgent.agentType());
        }

        // ── 7. forkParentSystemPrompt 继承（CC :116-148）──
        String forkParentSystemPrompt = null;
        if (isResumedFork) {
            forkParentSystemPrompt = resolveForkParentSystemPrompt(sessionId);
            if (forkParentSystemPrompt == null || forkParentSystemPrompt.isBlank()) {
                log.warn("[ResumeService] fork resume 无法重建父 system prompt, 抛错: agentId={}", agentIdStr);
                throw new IllegalArgumentException(
                    "Cannot resume fork agent: unable to reconstruct parent system prompt");
            }
        }

        // ── 8. 异步续跑（CC :198-258，Java 委托 SubagentTool worker + AsyncAgentFinalizer）──
        if (subagentTool == null) {
            log.error("[ResumeService] subagentTool 未注入, 无法后台续跑 resume agent={}", agentIdStr);
            throw new IllegalStateException("SubagentTool not injected into ResumeService; cannot resume agent");
        }
        String uiDescription = meta != null && meta.description() != null
            ? meta.description() : "(resumed)";
        // [RES-R6] resumedReplacementState 透传（CC :194 runAgentParams.contentReplacementState）:
        //   null（父 live state 不可得）→ SubagentExecutor loop 保持默认 create（CC :1006 feature off）。
        // [RES-SP31-1 返工] append 不在此透传（fork-only，CC 真源修正）：fork resume 的
        //   forkParentSystemPrompt 已含 append（resolveForkParentSystemPrompt rendered 补 append /
        //   rebuildForkParentSystemPrompt 重建含 append）；非 fork resume 系统提示不含 append
        //   （runAgent.ts:508-518）→ 无 executeResumeAsync append 通道（原 7.5 段已删）。
        // [hooks-plugin-display 修复] 9 参重载透传创建会话 sessionId（resumeAgentBackground :153 参数）：
        //   async resume 子代理任务需会话归属（sessionId=null → GET /api/v1/tasks?sessionId= 过滤排除）。
        subagentTool.executeResumeAsync(agentId, prompt, selectedAgent, uiDescription,
            forkParentSystemPrompt, resumedMessages, resumedWorktreePath, resumedReplacementState,
            sessionId);

        // ── 9. 返回（CC :260-264）──
        // [R-A45 B-4 D18/B2] agentId 返回 a+16hex（前端入参同形制）：CC resumeAgent.ts:261
        //   {@code agentId} 即原 a+16hex string。前端以返回的 a+16hex 再入二次 resume →
        //   CommandController.toResumeAgentId → packAgentId → unpackAgentId 还原同键 → transcript
        //   命中（回环成立且形制一致）。outputFile 仍为实际输出文件
        //   （BackgroundTaskRunner.taskOutputPath(agentId) 唯一根，写入侧同源）。
        String outputFile = com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath(agentIdStr);
        log.info("[ResumeService] resume 已注册后台续跑: agentId={}(a+16hex={}) type={} description={} outputFile={}",
            agentIdStr, agentIdHex, selectedAgent.agentType(), uiDescription, outputFile);
        return new ResumeAgentResult(agentIdHex, uiDescription, outputFile);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 子步骤（对齐 CC resumeAgent.ts 对应函数）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * worktree stat 校验 + 父 cwd 兜底 + utimes · 对齐 CC resumeAgent.ts:82-97。
     *
     * <p>meta.worktreePath 存在且是目录 → 返回原 worktree 并 bump mtime（:93-97 stale-worktree
     * 清理保护 #22355）；不存在/非目录 → 返 null（调用方回退父 cwd，:85-90 logForDebugging）。
     *
     * @param meta agent 元数据（可为 null）
     * @return 有效原 worktree 路径；无 / 已删除 → null
     */
    static String resolveWorktreePath(AgentTranscript.AgentMetadata meta) {
        if (meta == null || meta.worktreePath() == null) {
            return null;
        }
        Path wt = Paths.get(meta.worktreePath());
        if (!Files.isDirectory(wt)) {
            if (log.isDebugEnabled()) {
                log.debug("[ResumeService] 原 worktree {} 已不存在, 回退父 cwd (CC resumeAgent.ts:86-90)",
                    meta.worktreePath());
            }
            return null;
        }
        try {
            Files.setLastModifiedTime(wt, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            log.warn("[ResumeService] worktree utimes bump 失败 {}: {}", wt, e.getMessage());
        }
        return meta.worktreePath();
    }

    /**
     * agent 解析 · 对齐 CC resumeAgent.ts:100-112。
     *
     * <p>fork → ForkSubagentAgentDefinition；否则 activeAgents.find —— [RES-R6] 扩展为经
     * {@link SubagentTool#agentRegistry()} 暴露的 {@link AgentDefinitionRegistry} 命中自定义 agent
     * （内置 + 自定义合并，custom 覆盖 builtIn，loadAgentsDir.ts:216；CC :106-109
     * {@code activeAgents.find(a => a.agentType === meta.agentType)}）；registry 不可得
     * （plain JUnit 未注入）→ 回退 {@link BuiltInAgents#get}（内置 + fork）；未命中 →
     * GENERAL_PURPOSE_AGENT（CC :109 {@code found ?? GENERAL_PURPOSE_AGENT}）。
     *
     * @param meta         agent 元数据（可为 null）
     * @param isResumedFork 是否 fork resume
     * @return 解析后的 AgentDefinition（恒非 null）
     */
    AgentDefinition resolveSelectedAgent(AgentTranscript.AgentMetadata meta, boolean isResumedFork) {
        if (isResumedFork) {
            return ForkSubagentAgentDefinition.create();
        }
        if (meta != null && meta.agentType() != null && !meta.agentType().isBlank()) {
            AgentDefinition found = null;
            if (subagentTool != null && subagentTool.agentRegistry() != null) {
                found = subagentTool.agentRegistry().findAgent(meta.agentType());
            }
            if (found == null) {
                found = BuiltInAgents.get(meta.agentType());
            }
            if (found != null) {
                return found;
            }
        }
        return BuiltInAgents.GENERAL_PURPOSE_AGENT;
    }

    /**
     * fork 父 system prompt 继承 · 对齐 CC resumeAgent.ts:116-148。
     *
     * <p><b>[RES-R6-1] 两段式</b>（对齐 CC 主源顺序 :118-148）：
     * <ol>
     *   <li><b>rendered 优先</b>（:118-119）：web 端点无 ToolUseContext，以主会话
     *       {@link AgentState#systemPrompt()}（主循环渲染后的父 system prompt）等价；
     *       非空即返回。</li>
     *   <li><b>重建</b>（:120-141）：rendered 不可得 → {@code getSystemPrompt(tools, mainLoopModel,
     *       additionalWorkingDirectories, mcpClients)}（Java = {@link SystemPromptAssembler#assemble}
     *       + {@link SystemPromptAssemblyInput}）+ {@code buildEffectiveSystemPrompt}（Java =
     *       {@link EffectiveSystemPromptBuilder#build}，custom=state.systemPrompt()、
     *       append=state.appendSystemPrompt()）重建 fork 父提示。</li>
     * </ol>
     * 两者皆不可得 → null（调用方抛 "Cannot resume fork agent" :143-147，保持现有错误信息）。
     *
     * <p><b>mainLoopModel 通道（RES-C7 对齐 CC resumeAgent.ts:131）</b>：CC 取
     * {@code toolUseContext.options.mainLoopModel}（resume 时当前主循环模型）；Java 读
     * {@link AgentState#currentModel()}（LlmAgentLoop.run() 模型解析后写入，等价 options.mainLoopModel）。
     * [#25] 原 meta.model() fallback（spawn 持久化扩展字段）已删 —— CC AgentMetadata 无 model 字段，
     * 模型一律现算（state.currentModel()）；不可得 → null → 抛错（不伪造字节）。
     *
     * @param sessionId 主会话 UUID
     * @return 父 rendered system prompt；不可得 → null
     */
    String resolveForkParentSystemPrompt(String sessionId) {
        AgentState state = (sessionAgentStateRegistry != null && sessionId != null)
            ? sessionAgentStateRegistry.get(sessionId) : null;
        // ── 第 1 段：rendered 优先（CC resumeAgent.ts:118-119）──
        // [RES-SP31-1] CC 的 renderedSystemPrompt 是完整渲染提示（含 append 恒末尾）；
        //   Java AgentState.systemPrompt() 仅 custom prompt（不含 append），故非空时补 state.appendSystemPrompt()
        //   恒末尾（对齐 CC systemPrompt.ts:121 append 恒末尾，避免 fork resume 丢失追加指令）。
        String rendered = state != null ? state.systemPrompt() : null;
        if (rendered != null && !rendered.isBlank()) {
            String append = state.appendSystemPrompt();
            if (append != null && !append.isBlank()) {
                rendered = rendered + "\n\n" + append;
            }
            if (log.isDebugEnabled()) {
                log.debug("[ResumeService] fork 父 system prompt 来源=AgentState.systemPrompt "
                        + "(rendered 优先, CC resumeAgent.ts:118-119), session={}, 长度={}, 含 append={}",
                    sessionId, rendered.length(), append != null && !append.isBlank());
            }

            return rendered;
        }
        // ── 第 2 段：getSystemPrompt + buildEffectiveSystemPrompt 重建（CC :120-141）──
        return rebuildForkParentSystemPrompt(state);
    }

    /**
     * 重建 fork 父 system prompt · 对齐 CC resumeAgent.ts:120-141。
     *
     * <p>{@code getSystemPrompt(tools, mainLoopModel, additionalWorkingDirectories, mcpClients)}
     * （:129-134）Java 等价 = {@link SystemPromptAssembler#assemble}（enabledTools 源自
     * {@link ToolRegistry#all()} + {@link McpServerService#getCurrentTools()}；
     * model = state.currentModel()（RES-C7 对齐 CC resumeAgent.ts:131）；
     * [RES-C8] additionalWorkingDirs 取 state.currentToolUseContext().additionalWorkingDirectories().keySet()
     * （对齐 CC resumeAgent.ts:126-128）；mcpClients 取 state.currentToolUseContext().mcpClients()
     * 转 List<McpClientInfo>（对齐 CC prompts.ts:578-608 getMcpInstructions connected 过滤）；
     * currentToolUseContext=null → 软降级空列表（对齐 CC 空数组合法）；
     * 再经 {@link EffectiveSystemPromptBuilder#build}（custom=state.systemPrompt()、append=state.appendSystemPrompt()，
     * :135-141 等价）。
     *
     * <p><b>fail loud</b>（验收标准 5）：ToolRegistry 未注入 / model 不可得 → 无法按 CC 重建 →
     * null → 调用方抛 "Cannot resume fork agent"（不伪造字节）。
     *
     * @param state 主会话状态（可为 null；currentModel 源 + custom/append 源，:135-141）
     * @return 重建后的 fork 父提示；关键原料缺任一 → null
     */
    private String rebuildForkParentSystemPrompt(AgentState state) {
        if (toolRegistry == null) {
            log.warn("[ResumeService] fork 父提示重建跳过: ToolRegistry 未注入 "
                + "（CC resumeAgent.ts:130 options.tools 不可得）→ 返 null 抛错");
            return null;
        }
        // [RES-C7/#25] model 现算 · 对齐 CC resumeAgent.ts:131 options.mainLoopModel（resume 时
        //   当前主循环模型）：仅读 AgentState.currentModel()。原 meta.model() fallback（spawn 持久化
        //   扩展字段）已删（open-decisions F2 #25 删字段改现算 —— CC AgentMetadata 无 model 字段，
        //   sessionStorage.ts:264-272）。不可得 → null → 调用方抛 "Cannot resume fork agent"（fail loud）。
        String model = (state != null) ? state.currentModel() : null;
        String modelSource = "state.currentModel";
        if (model == null || model.isBlank()) {
            log.warn("[ResumeService] fork 父提示重建跳过: 当前会话模型不可得 "
                + "（mainLoopModel 通道，CC resumeAgent.ts:131 options.mainLoopModel 等价）→ 返 null 抛错");
            return null;
        }
        // getSystemPrompt 等价（prompts.ts:444-449）· enabledTools = ToolRegistry.all() + MCP 工具
        Set<String> enabledTools = new java.util.LinkedHashSet<>();
        for (Tool t : toolRegistry.all()) {
            enabledTools.add(t.name());
        }
        if (mcpServerService != null) {
            for (Tool t : mcpServerService.getCurrentTools()) {
                if (t != null && t.isEnabled()) {
                    enabledTools.add(t.name());
                }
            }
        }
        SystemPromptAssembler assembler = new SystemPromptAssembler(
            state != null ? state.systemPromptSectionCache() : new SystemPromptSectionCache());
        // [RES-C8] additionalWorkingDirs · 对齐 CC resumeAgent.ts:126-128
        // Array.from(appState.toolPermissionContext.additionalWorkingDirectories.keys())
        // state.currentToolUseContext().additionalWorkingDirectories() = Map<String, AdditionalWorkingDirectory>
        // → keySet() = 附加工作目录路径集合；currentToolUseContext=null → 空列表软降级
        com.nexusai.application.agent.tool.ToolUseContext tuc =
            (state != null) ? state.currentToolUseContext() : null;
        java.util.List<String> additionalWorkingDirs =
            (tuc != null && tuc.additionalWorkingDirectories() != null)
                ? new java.util.ArrayList<>(tuc.additionalWorkingDirectories().keySet())
                : java.util.List.of();
        // [RES-L2 · C8] mcpClients · 对齐 CC resumeAgent.ts:128 toolUseContext.options.mcpClients
        // tuc.mcpClients() = Map<String, McpClientRuntime> → List<McpClientInfo>
        // name=serverName（Map key），connected=true（在活跃池 = 已连接，McpServerService.getCurrentTools()
        // 活跃快照）。
        // [IMP-E1 DC-2] McpServerInfo 收敛 2 字段后 instructions 由 mcpClients map 值
        // （McpClientRuntime）承载，直接读取（对齐 CC ConnectedMCPServer.instructions）。
        java.util.List<com.nexusai.application.agent.prompt.SystemPromptAssemblyInput.McpClientInfo> mcpClients =
            (tuc != null && tuc.mcpClients() != null)
                ? tuc.mcpClients().entrySet().stream()
                    .map(e -> new com.nexusai.application.agent.prompt.SystemPromptAssemblyInput.McpClientInfo(
                        e.getKey(), e.getValue() != null ? e.getValue().instructions() : null, true))
                    .collect(java.util.stream.Collectors.toList())
                : java.util.List.of();
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(
            enabledTools,
            model,
            additionalWorkingDirs,
            mcpClients,
            null,           // outputStyleConfig（Java 无输出风格配置注入）
            List.of(),      // skillToolCommands（无 SkillCatalog 通道）
            null,           // language（Java 无语言设置通道）
            null,           // memoryLoader（无 memoryStorage 通道）
            false,          // tokenBudgetEnabled（fork 无 TOKEN_BUDGET flag 通道 · 对齐 CC prompts.ts:538 关时恒不注册）
            state != null ? state.sessionId() : null);  // [cwd-session 2026-08-25 修复] env_info_simple 会话 cwd（state null → MDC 兜底）
        SystemPrompt effective = EffectiveSystemPromptBuilder.build(
            () -> assembler.assemble(input),
            null,                                            // overrideSystemPrompt（CC resumeAgent.ts:135 调用点不传 → 保持 null，SP-01）
            state != null ? state.systemPrompt() : null,     // customSystemPrompt（:118-119 替换 default）
            state != null ? state.appendSystemPrompt() : null); // appendSystemPrompt（:121 恒末尾）
        String rebuilt = String.join("\n\n", effective.elements());
        if (log.isDebugEnabled()) {
            log.debug("[ResumeService] fork 父提示已重建: model={} (来源={}), enabledTools={}, additionalWorkingDirs={}, mcpClients={}, 元素数={}, 长度={} "
                    + "（CC resumeAgent.ts:120-141）",
                model, modelSource, enabledTools.size(), additionalWorkingDirs.size(), mcpClients.size(),
                effective.elements().size(), rebuilt.length());
        }
        return rebuilt;
    }

    // ────────────────────────────────────────────────────────────────────────
    // [OD-2A-4] resume 目录上下文恢复（对齐 CC sessionStorage.ts:2522/4680 projectPath: firstMessage.cwd）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * [OD-2A-4] resume 用首条消息 cwd 恢复会话可变 cwd（SessionCwdHolder）· 对齐 CC
     * {@code sessionStorage.ts:2522/4680} {@code projectPath: firstMessage.cwd}。
     *
     * <p><b>CC 真源（自验，不信注释）</b>：CC 会话 transcript 首条消息戳
     * {@code cwd: getCwd()}（sessionStorage.ts:1059），/resume 与 session 列表以
     * {@code firstMessage.cwd} 作 projectPath 恢复目录上下文（:2522/4680）——目录在会话启动/绑定
     * 时定死（CC 中途 cd 永不重锚 projectRoot，state.ts:511-525），resume 后不回落到新启动目录。
     * Java 等价：{@code SessionCwdHolder} 内存态是会话级可变 cwd 槽（{@code CwdResolution.getCwd}
     * 的 L2 层），resume 后回落 boundProject 而非原启动目录 → 用首条消息 cwd 恢复该槽，使
     * resumed 会话 getCwd() 解析到原目录（INV-2）。与 OD-2A-6（嵌套 worktree originalCwd 栈）同源：
     * 跨会话/嵌套 originalCwd 持久化，本方法恢复 cwd 槽，Enter/Exit worktree 栈恢复 originalCwd 槽。
     *
     * <p><b>数据源</b>：G13/V22 已落 messages 表 {@code cwd} 列（写侧
     * {@code MessageService.createUserMessage/appendMessage/replaceSessionMessages + ChatService
     * newAssistantMessage/newToolMessage} 经 {@code CwdResolution.getCwd(sessionId)} 戳入，见
     * {@code MessageRecord.cwd}）。本方法经 {@link #messageMapper} 读主会话首条消息
     * （{@code eq("session_id", sessionId).orderBy("created_at", true).limit(1)}，CC firstMessage =
     * chain[0]）取 cwd。mapper 未注入（plain JUnit）/ 查询异常 → 软降级跳过（CC 旧 jsonl 无 cwd
     * 字段容错，V22 列可空）。
     *
     * <p><b>键形态</b>：resume 以派生 UUID 为键（{@code sessionId.toString()}），与
     * BashTool/EnterWorktreeTool 登记形制一致（SessionCwdHolder 层 UUID 键）；boundProject 层以
     * 原始键 {@code "sess-xxx"} 为键（{@link com.nexusai.common.SessionKeys} 双键，CRON-D5 F2）。
     *
     * @param sessionId 主会话 UUID（SessionCwdHolder 键 = {@code sessionId.toString()}）
     */
    private void restoreSessionCwd(String sessionId) {
        if (sessionId == null || messageMapper == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ResumeService] resume 目录恢复跳过: sessionId/messageMapper 缺失"
                    + " sessionId={}", sessionId);
            }
            return;
        }
        String sessionKey = sessionId;
        try {
            // 首条消息 = 链首（CC firstMessage = chain[0]，sessionStorage.ts:2522/4680）
            List<MessageRecord> rows = messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionKey)
                    .orderBy("created_at", true).limit(1));
            if (rows == null || rows.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ResumeService] resume 目录恢复跳过: 会话无消息（无链首）"
                        + " sessionId={}", sessionId);
                }
                return;
            }
            String firstCwd = rows.get(0).getCwd();
            if (firstCwd == null || firstCwd.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ResumeService] resume 目录恢复跳过: 首条消息无 cwd（CC 旧 jsonl 无 cwd 字段容错）"
                        + " sessionId={}", sessionId);
                }
                return;
            }
            // 恢复会话可变 cwd 槽（对齐 CC projectPath: firstMessage.cwd 恢复目录上下文）；
            // set 内部 realpath+NFC（对齐 setCwdState + setCwd realpathSync，Shell.ts:447-464）。
            com.nexusai.application.agent.agent.SessionCwdHolder.set(sessionKey, firstCwd);
            if (log.isDebugEnabled()) {
                log.debug("[ResumeService] resume 用首条消息 cwd 恢复会话 cwd: sessionId={} cwd={}"
                    + "（对齐 CC sessionStorage.ts:2522/4680 projectPath: firstMessage.cwd）",
                    sessionId, firstCwd);
            }
        } catch (Exception e) {
            // 查询异常 → 软降级（CC 旧 jsonl 无 cwd 字段容错；resume 主流程不受阻）
            if (log.isDebugEnabled()) {
                log.debug("[ResumeService] resume 目录恢复跳过: 首条消息 cwd 查询异常 sessionId={} err={}",
                    sessionId, e.toString());
            }
        }
    }

    /**
     * 会话目录根 · [R1] 与 SubagentExecutor.resolveSessionDir 同源，旧 {java.io.tmpdir}/nexusai-sessions
     * 平铺根 → config-home 项目 slug 目录（{@link com.nexusai.application.agent.tool.SessionStorage#sessionProjectDir}）。
     * 供 subagent sidechain transcript（AgentTranscript.getAgentTranscript/recordSidechainTranscript）
     * 使用 —— 与 SessionStorage.getAgentTranscriptPath 同根，双根分裂消除。
     *
     * @param sessionId 主会话 ID（null → 回落 user.dir 兜底层）
     */
    public static Path resolveSessionDir(String sessionId) {
        return com.nexusai.application.agent.tool.SessionStorage.sessionProjectDir(sessionId);
    }
}
