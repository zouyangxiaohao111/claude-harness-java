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
import com.nexusai.domain.session.MessageService;
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
import java.util.ArrayList;
import java.util.HashSet;
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
 *       [RES-R6] 经 {@link SubagentTool#agentRegistry(String)} 的 {@link AgentDefinitionRegistry}
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
    /**
     * 后台任务注册表 · resume 门禁的状态源（对齐 CC taskRegistry + stopPendingAgentIds）。
     *
     * <p>@Autowired(required=false)：plain JUnit 不注入 → 门 2/3 跳过（仍保留并发门 1）。
     */
    private com.nexusai.application.agent.tasks.BackgroundTaskRunner backgroundTaskRunner;
    /**
     * 并发 resume 集合 · 对齐 CC {@code Nn().resumesInFlight}（gt() 的 in-flight 门）。
     *
     * <p>只在 {@link #resumeAgentBackground} 的方法调用期间持有：进入即 add，finally remove。
     * 之后「仍在跑」由任务表状态判定（门 3）。
     */
    private final java.util.Set<String> resumesInFlight =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
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
     * （{@code eq("session_id", sessionId).orderByUnSafely(SEQ_ASC_NULLS_LAST_ORDER).limit(1)}）取链首
     * （CC firstMessage = chain[0]；{@code NULLS LAST} 保证链首不会是 seq 为 NULL 的存量脏行）。
     *
     * <p>setter 注入（沿用本类既有风格）：plain JUnit 缺省 null → 目录恢复跳过（软降级，
     * 不阻断 resume 主流程；CC 旧 jsonl 无 cwd 字段同容错）。生产经 Spring 注入。
     */
    private MessageMapper messageMapper;

    /**
     * [resume-quota] 会话级并发槽 · 对齐 CC {@code roomForResume}/{@code $xt}/{@code Tr}
     * （claude.exe 2.1.328 偏移 326611441 / 329287293 / 334052811）。未注入（plain JUnit）→ 跳过配额门。
     */
    private ResumeQuota resumeQuota;

    /**
     * [stop-double-write] 内存停止标记 · 对齐 CC {@code Fat()}（偏移 334311557）。
     * 未注入 → 只读磁盘伴生文件（旧行为）。
     */
    private StopMarkers stopMarkers;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSubagentTool(SubagentTool subagentTool) {
        this.subagentTool = subagentTool;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResumeQuota(ResumeQuota resumeQuota) {
        this.resumeQuota = resumeQuota;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStopMarkers(StopMarkers stopMarkers) {
        this.stopMarkers = stopMarkers;
    }

    /**
     * [lineage] 会话用量账本注册表 · 父子血统的<b>真实数据源</b>（节点上的 parentAgentId
     * 由 spawn 点写入、跨 turn 由 {@code sessions.usage_tree_json} 恢复）。
     *
     * <p>未注入（plain JUnit / 会话无账本）→ lineage 恒 false（⛔ 不猜真、不写死真）。
     */
    private com.nexusai.application.agent.cost.SessionUsageAccountingRegistry usageAccountingRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUsageAccountingRegistry(
            com.nexusai.application.agent.cost.SessionUsageAccountingRegistry registry) {
        this.usageAccountingRegistry = registry;
    }

    /** 后台任务注册表注入（resume 门 2/3 的状态源）；plain JUnit 不注入 → 跳过门 2/3。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBackgroundTaskRunner(
            com.nexusai.application.agent.tasks.BackgroundTaskRunner backgroundTaskRunner) {
        this.backgroundTaskRunner = backgroundTaskRunner;
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
     * @param agentId   待恢复 sub-agent 的 agentId（CC 单一形态 a+16hex；原样作为 transcript 键，
     *                  旧 UUID 键不再还原 —— Breaking）
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
    public ResumeAgentResult resumeAgentBackground(String agentId, String prompt,
                                                   Path sessionDir, String sessionId,
                                                   ContentReplacementState parentContentReplacementState) {
        // 5 参 = 用户显式 resume（REST /resume），不受 stoppedByUser 门限制。
        return resumeAgentBackground(agentId, prompt, sessionDir, sessionId,
            parentContentReplacementState, true);
    }

    /**
     * 6 参重载 · {@code userInitiated} 区分「用户显式 resume」与「模型/系统自动 resume」。
     *
     * <p>对齐 CC resume 的 {@code J}（userInitiated）：agent 被用户显式停止后，只有用户本人
     * 可以再把它恢复；模型经 SendMessage 自动 resume 会被 {@link ResumeErrorCode#AGENT_STOPPED_BY_USER}
     * 拒绝（CC {@code AgentStoppedByUserError}）。
     *
     * @param userInitiated true = 用户显式发起（REST /resume）；false = 模型/系统自动发起
     */
    public ResumeAgentResult resumeAgentBackground(String agentId, String prompt,
                                                   Path sessionDir, String sessionId,
                                                   ContentReplacementState parentContentReplacementState,
                                                   boolean userInitiated) {
        return resumeAgentBackground(agentId, prompt, sessionDir, sessionId,
            parentContentReplacementState, userInitiated, false);
    }

    /**
     * 血统感知的恢复入口 · 由<b>恢复发起方</b>的 agentId 现算 {@code lineage}（⛔ 不从工具入参取）。
     *
     * <p>对齐 CC 偏移 333967189：{@code lineage !== undefined} 时跳过 stoppedByUser 探测与 k9。
     * 本仓的 lineage = <b>发起方是目标的后代</b>（目标在发起方的祖先链上）—— 对应 CC 唯一生产点
     * 「恢复 fork 子代理时先递归恢复它的父」，级联里被走过的是<b>祖先</b>：
     * <ul>
     *   <li>子代理：父子链取会话用量账本节点上的 {@code parentAgentId}（spawn 点写入，
     *       跨 turn 由 {@code usage_tree_json} 恢复）；</li>
     *   <li>父恢复子 / 兄弟 / 无关方 → false（⛔ 父级恢复子代理不是 lineage，不放行 k9）；</li>
     *   <li>账本不可得 / 发起方未知 → false（退回 k9 判据，⛔ 不放行）。</li>
     * </ul>
     *
     * @param initiatorAgentId 发起方 agentId（调用方 ToolUseContext.agentId() 的字符串形态；
     *                         null/空白 = 主会话）—— 由 orchestrator 提供，不由模型填写
     */
    public ResumeAgentResult resumeAgentBackgroundForLineage(String agentId, String prompt,
                                                             Path sessionDir, String sessionId,
                                                             ContentReplacementState parentContentReplacementState,
                                                             boolean userInitiated,
                                                             String initiatorAgentId) {
        boolean lineage = computeLineage(
            usageAccountingRegistry != null ? usageAccountingRegistry.peek(sessionId) : null,
            initiatorAgentId, agentId, sessionId);
        if (lineage) {
            log.info("[ResumeService][lineage] 同血统续跑放行 k9: agentId={} initiator={} session={}",
                agentId, initiatorAgentId, sessionId);
        }
        return resumeAgentBackground(agentId, prompt, sessionDir, sessionId,
            parentContentReplacementState, userInitiated, lineage);
    }

    /**
     * lineage 计算（纯函数，便于用例直接锁判据）。
     *
     * <p>方向（对齐 CC）：true = <b>发起方是目标的后代</b>（target 在 initiator 的祖先链上）。
     * 父恢复子 / 兄弟 / 无关方 → false。
     *
     * @param ledger           会话用量账本（父子边的唯一真源；null → false）
     * @param initiatorAgentId 发起方（UUID 串 / a+16hex / 会话 id / name@team）
     * @param targetAgentId    被恢复的 agent
     * @param sessionId        会话 id（主会话节点的键 = 会话 id）
     * @return true = 发起方是目标的后代（目标在其祖先链上）
     */
    public static boolean computeLineage(com.nexusai.application.agent.cost.SessionUsageLedger ledger,
                                  String initiatorAgentId, String targetAgentId, String sessionId) {
        if (ledger == null || targetAgentId == null) {
            return false;
        }
        String targetKey = resolveNodeKey(ledger, targetAgentId, targetAgentId);
        String initiatorKey = resolveNodeKey(ledger,
            normalizeInitiator(initiatorAgentId, sessionId), initiatorAgentId);
        return AgentLineage.linked(initiatorKey, targetKey,
            key -> {
                com.nexusai.infra.llm.UsageAttribution who = ledger.attributionOf(key);
                return who == null ? null : who.parentAgentId();
            });
    }

    /**
     * 账本节点键归一：UUID 串 → a+16hex（{@link AgentContext#canonicalAgentId}）。
     * 主会话传的是会话 id → 原样（主节点键 = 会话 id）。
     */
    private static String normalizeInitiator(String initiatorAgentId, String sessionId) {
        if (initiatorAgentId == null || initiatorAgentId.isBlank()) {
            return sessionId;
        }
        if (initiatorAgentId.equals(sessionId)) {
            return initiatorAgentId;
        }
        String canonical = AgentContext.canonicalAgentId(initiatorAgentId);
        return canonical == null ? initiatorAgentId : canonical;
    }

    /** 取账本里真实存在的节点键（候选按顺序命中第一个存在的；都不存在 → 首个候选）。 */
    private static String resolveNodeKey(com.nexusai.application.agent.cost.SessionUsageLedger ledger,
                                         String... candidates) {
        for (String c : candidates) {
            if (c != null && ledger.attributionOf(c) != null) {
                return c;
            }
        }
        for (String c : candidates) {
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /**
     * 7 参重载 · 增加 {@code lineage}（同一血统的续跑）· 对齐 CC 恢复门
     * {@code (meta.stoppedByUser || 伴生文件 found) && !userInitiated && !lineage}（偏移 333967189
     * {@code A=L!==void 0}；有 lineage 时连伴生探测都跳过 {@code he=...{found:false}}）。
     *
     * @param lineage true = 本次恢复是同一血统的续跑（跳过停止标记探测）
     */
    public ResumeAgentResult resumeAgentBackground(String agentId, String prompt,
                                                   Path sessionDir, String sessionId,
                                                   ContentReplacementState parentContentReplacementState,
                                                   boolean userInitiated, boolean lineage) {
        String agentIdStr = agentId.toString();
        // ── 门 1：并发 resume（对齐 CC gt(): resumesInFlight 集合）──
        //   同一 agent 的两次并发 resume 会起两个 worker 写同一 transcript。
        if (!resumesInFlight.add(agentIdStr)) {
            throw new ResumeErrorCode.ResumeException(
                ResumeErrorCode.SUBAGENT_RESUME_ALREADY_RUNNING,
                "Agent " + agentIdStr + " is already running or being resumed");
        }
        // [stop] 门 B 用的停止周期快照（CC resume 开始时快照 sn，准入后比对）。
        int stopSnapshot = stopMarkers != null ? stopMarkers.stopCount(agentIdStr) : 0;
        ResumeQuota.Release slot = null;
        try {
            // ── 门 2：still stopping（对齐 CC uo(): Gq(agentId) = stopPendingAgentIds.has(id)）──
            //   killAsyncAgent 已置 KILLED 并 abort，但旧 worker 尚未退出；此时 resume 会与它并发。
            if (backgroundTaskRunner != null) {
                if (backgroundTaskRunner.isStopPending(agentIdStr)) {
                    throw new ResumeErrorCode.ResumeException(
                        ResumeErrorCode.SUBAGENT_RESUME_STILL_STOPPING,
                        ResumeErrorCode.stillStoppingText(agentIdStr));
                }
                // ── 门 3：仍在运行（CC uo(): taskRegistry status 非终态 → already running）──
                com.nexusai.application.agent.tasks.BackgroundTask runningTask =
                    backgroundTaskRunner.getTaskById(agentIdStr);
                if (runningTask != null
                        && runningTask.status()
                            == com.nexusai.application.agent.tasks.BackgroundTaskStatus.RUNNING) {
                    throw new ResumeErrorCode.ResumeException(
                        ResumeErrorCode.SUBAGENT_RESUME_ALREADY_RUNNING,
                        "Agent " + agentIdStr + " is already running or being resumed");
                }
            }
            // ── 门 4：会话级并发槽 · 对齐 CC roomForResume（满即拒，不排队）──
            if (resumeQuota != null) {
                slot = resumeQuota.tryAcquire(sessionId);
                if (slot == null) {
                    throw new ResumeErrorCode.ResumeException(
                        ResumeErrorCode.SESSION_FULL,
                        ResumeErrorCode.sessionFullText(agentIdStr));
                }
            }
            // ── 门 5：自动续跑预算 · 对齐 CC restartResumes>=yYa(5) → resumes_spent ──
            if (!userInitiated) {
                int spent = readRestartResumeCount(sessionDir, sessionId, agentId, agentIdStr);
                if (spent >= ResumeErrorCode.MAX_RESTART_RESUMES) {
                    throw new ResumeErrorCode.ResumeException(
                        ResumeErrorCode.SUBAGENT_RESUME_RESUMES_SPENT,
                        "Agent " + agentIdStr + " has already been resumed "
                            + ResumeErrorCode.MAX_RESTART_RESUMES
                            + " times automatically since the last restart; it will not be resumed"
                            + " automatically again." + ResumeErrorCode.START_NEW_AGENT_SUFFIX);
                }
            }
            return doResumeAgentBackground(agentId, prompt, sessionDir, sessionId,
                parentContentReplacementState, agentIdStr, userInitiated, lineage, stopSnapshot);
        } finally {
            if (slot != null) {
                slot.release();
            }
            resumesInFlight.remove(agentIdStr);
        }
    }


    private int readRestartResumeCount(Path sessionDir, String sessionId, String agentId, String agentIdStr) {
        String key = resumeMetaKey(sessionDir, sessionId, agentId, agentIdStr);
        return AgentTranscript.readRestartResumes(sessionDir, sessionId, key);
    }



    /** 台账落在实际存在的 meta 键上（a+16hex 新格式优先，UUID 旧格式兜底）。 */
    private static String resumeMetaKey(Path sessionDir, String sessionId, String agentId, String agentIdStr) {
        String hex = agentId;
        if (AgentTranscript.metadataState(sessionDir, sessionId, hex)
                != AgentTranscript.MetadataState.MISSING) {
            return hex;
        }
        return agentIdStr;
    }

    /**
     * resume 主体（读 transcript → 过滤 → 重建 → 后台续跑）。门禁在
     * {@link #resumeAgentBackground} —— 本方法假定三门已过。
     */
    private ResumeAgentResult doResumeAgentBackground(String agentId, String prompt,
                                                      Path sessionDir, String sessionId,
                                                      ContentReplacementState parentContentReplacementState,
                                                      String agentIdStr, boolean userInitiated,
                                                      boolean lineage, int stopSnapshot) {
        String sessionIdStr = sessionId;
        // [R3-WF-F IMP-SUB-12 返工] a+16hex transcript 键（D18/B2）：子代理域以
        //   AgentContext.createAgentId() 生成 a+16hex，经 S-12 pack 桥存 ToolUseContext.agentId(UUID)，
        //   transcript/metadata 键还原 a+16hex。resume 请求携带 packed UUID → unpackAgentId 还原
        //   a+16hex 查找新格式 transcript（CC resumeAgent.ts:63-66 getAgentTranscript(asAgentId(agentId))）；
        //   旧格式（UUID 键）经 uuidKey fallback 兼容（pre-migration transcript 不丢）。
        String agentIdHex = agentId;

        // ── 1. 读 transcript + meta（CC :63-66）── 双键查找（a+16hex 新格式优先，UUID 旧格式兜底）
        Optional<AgentTranscript.AgentTranscriptResult> transcriptOpt =
            AgentTranscript.getAgentTranscript(sessionDir, sessionIdStr, agentIdHex)
                .or(() -> AgentTranscript.getAgentTranscript(sessionDir, sessionIdStr, agentIdStr));
        AgentTranscript.AgentMetadata meta =
            AgentTranscript.readMetadata(sessionDir, sessionIdStr, agentIdHex)
                .or(() -> AgentTranscript.readMetadata(sessionDir, sessionIdStr, agentIdStr)).orElse(null);

        // ── 2. 无 transcript 抛错（CC :67-69 throw new ResumeAgentStateError{transcriptMissing:true}）──
        //   [五档对齐] CC 把它映射到遥测 {@code no_transcript}（Ks，偏移 356128040），
        //   SendMessage 回执用 degradedClass 保留该档；本仓 REST 仍要求 404（CommandController
        //   的行为契约），故抛 NotFoundException 的子类并携带同样的错误码/分档。
        if (transcriptOpt.isEmpty()) {
            log.warn("[ResumeService] 未找到 transcript, 抛错: agentId={}", agentIdStr);
            throw new ResumeErrorCode.ResumeTranscriptMissingException(
                ResumeErrorCode.transcriptMissingText(agentIdStr, agentIdStr));
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

        // ── 3. 恢复起点：末尾不完整轮截断 + 丢失工具补 is_error（CC oKt / ure）──
        //   CC 恢复起点不是「只取最后一条」，而是<b>整段历史重放</b>，但先切掉末尾未收尾的一轮：
        //   {@code oKt(msgs)} 从尾部回退到最后一个完整边界（assistant 的 stop_reason 为
        //   null|"tool_use" 且非 apiError；user/tool 行本身就是边界）；被切断的 tool_use
        //   补成 {@code tool_result{is_error:true, toolDenialKind:"interrupted"}}，因为
        //   「已执行的工具不重放」（CC 偏移 353419208 区 + 333977 区）。
        List<AgentMessage> truncated = truncateIncompleteTail(transcript.messages());
        // 三层过滤（CC :70-74，复用 MessageFilters）——顺序保持 CC：先过滤再补丢失工具。
        //   全量 unresolved 的 assistant 会被 filterUnresolvedToolUses 丢掉（CC 同判据 ALL 才丢）；
        //   部分未解析的保留下来，再由 fillInterruptedToolResults 补 is_error（CC ure()）。
        List<AgentMessage> filtered = MessageFilters.filterWhitespaceOnlyAssistantMessages(
            MessageFilters.filterOrphanedThinkingOnlyMessages(
                MessageFilters.filterUnresolvedToolUses(truncated)));
        List<AgentMessage> resumedMessages = fillInterruptedToolResults(filtered);
        if (log.isDebugEnabled()) {
            log.debug("[ResumeService] 恢复起点: transcript.messages.size={} → 截断后={} "
                    + "→ 三层过滤后={} → 补丢失工具后={} (CC oKt + resumeAgent.ts:70-74 + ure)",
                transcript.messages().size(), truncated.size(), filtered.size(),
                resumedMessages.size());
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

        // ── 4.5 cwd 校验（对齐 CC uo(): Yt(s?.cwd) → subagent_resume_cwd_refused）──
        //   记录里的目录已不可进入 → 拒绝恢复，而不是悄悄换到别的目录继续跑。
        verifyMetadataCwd(meta);

        // ── 4.6 stoppedByUser 门（对齐 CC 偏移 333967189：(meta.stoppedByUser ‖ 伴生 found)
        //        && !userInitiated && !lineage；lineage 时连探测都跳过）──
        //   用户显式停止过的 agent，只有用户本人可以恢复；模型自动 resume 一律拒绝。
        //   observer-activity 在 CC 是例外，本仓 observer 走 executeResumeAsync 不经此方法 ⇒ 天然例外。
        boolean memoryStopped = stopMarkers != null && stopMarkers.isStopped(agentIdStr);
        verifyNotStoppedByUser(sessionDir, sessionIdStr, agentIdHex, agentIdStr,
            userInitiated, lineage, memoryStopped);

        // ── 5. worktree stat 校验 + 父 cwd 兜底 + utimes（CC :82-97）──
        String resumedWorktreePath = resolveWorktreePath(meta);

        // ── 6. agent 解析（CC :100-112）──
        boolean isResumedFork = meta != null
            && ForkSubagent.FORK_SUBAGENT_TYPE.equals(meta.agentType());
        AgentDefinition selectedAgent = resolveSelectedAgent(meta, isResumedFork, sessionId);
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
                throw new ResumeErrorCode.ResumeException(
                    ResumeErrorCode.SUBAGENT_RESUME_FORK_DROPPED,
                    "Cannot resume this fork agent: unable to reconstruct its parent system prompt."
                        + ResumeErrorCode.START_NEW_AGENT_SUFFIX);
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
        // ── 8.5 门 B 复核（对齐 CC 偏移 333998223：准入后若期间又被停过 ⇒ 丢这条投递）──
        //   快照取自进入 resumeAgentBackground 时；此处与当前值比对。userInitiated / lineage 放行。
        if (!userInitiated && !lineage && stopMarkers != null
                && stopMarkers.isStopped(agentIdStr)
                && stopMarkers.stopCount(agentIdStr) != stopSnapshot) {
            throw new ResumeErrorCode.ResumeException(
                ResumeErrorCode.AGENT_STOPPED_BY_USER,
                ResumeErrorCode.stoppedMeanwhileText(agentIdStr));
        }
        // [restart-rebuild] 复活标记：写 revivedAt 并清 finished（合并保留其它键）。
        //   WHY 清 finished：复活后的这一轮正在跑，若仍留 finished:true，退役表重建会把
        //   「进行中」说成「已完成」；本轮结束时由 SubagentExecutor Step 22 重新落盘 finished:true。
        //   写入放在起跑前，避免 worker 结束写 finished 与本次清 finished 竞争（顺序颠倒会丢终态）。
        AgentTranscript.markRevived(sessionDir, sessionIdStr, agentIdHex);
        if (!agentIdHex.equals(agentIdStr)) {
            AgentTranscript.markRevived(sessionDir, sessionIdStr, agentIdStr);
        }
        subagentTool.executeResumeAsync(agentId, prompt, selectedAgent, uiDescription,
            forkParentSystemPrompt, resumedMessages, resumedWorktreePath, resumedReplacementState,
            sessionId);

        // resume 已启动 → 清「被用户停止」标记（对齐 CC eKt(): 恢复成功后删伴生文件，
        // 否则该 agent 之后的任何自动 resume 都会被同一个标记拦住）。
        AgentTranscript.clearStoppedByUser(sessionDir, sessionIdStr, agentIdHex);
        AgentTranscript.clearStoppedByUser(sessionDir, sessionIdStr, agentIdStr);
        if (stopMarkers != null) {
            stopMarkers.clear(agentIdStr);
        }

        // ── 9. 返回（CC :260-264）──
        // [R-A45 B-4 D18/B2] agentId 返回 a+16hex（前端入参同形制）：CC resumeAgent.ts:261
        //   {@code agentId} 即原 a+16hex string。前端以返回的 a+16hex 再入二次 resume →
        //   原样作为 transcript 键 → 命中（回环成立且形制一致）。outputFile 仍为实际输出文件
        //   （BackgroundTaskRunner.taskOutputPath(sessionId, agentId) 唯一根，写入侧同源）。
        // [批 3b-D7] 输出根所属会话 = 本方法的主会话参数（显式；⛔ 不再由下游读 MDC）
        String outputFile = com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath(
            sessionId, agentIdStr);
        log.info("[ResumeService] resume 已注册后台续跑: agentId={}(a+16hex={}) type={} description={} outputFile={}",
            agentIdStr, agentIdHex, selectedAgent.agentType(), uiDescription, outputFile);
        return new ResumeAgentResult(agentIdHex, uiDescription, outputFile);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 子步骤（对齐 CC resumeAgent.ts 对应函数）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * stoppedByUser 门 · 对齐 CC 偏移 333967189：
     * {@code (meta.stoppedByUser || 伴生文件 found) && !userInitiated && !lineage} → 抛错
     * （{@code origin.kind !== "observer-activity"} 的例外在本仓由 observer 走另一条 resume 路径天然满足）。
     *
     * <p>用户显式发起（{@code userInitiated=true}）一律放行；{@code lineage=true}（同一血统续跑）
     * 时 CC 连伴生探测都跳过（{@code he=...{found:false}}）⇒ 本方法直接返回，不读盘。
     */
    static void verifyNotStoppedByUser(Path sessionDir, String sessionId,
                                       String agentIdHex, String agentIdStr, boolean userInitiated) {
        verifyNotStoppedByUser(sessionDir, sessionId, agentIdHex, agentIdStr,
            userInitiated, false, false);
    }

    /**
     * 6 参门（含 lineage 与内存标记）。
     *
     * @param memoryStopped 内存 {@code stoppedByUser}（{@link StopMarkers}）；lineage=true 时不使用
     */
    static void verifyNotStoppedByUser(Path sessionDir, String sessionId,
                                       String agentIdHex, String agentIdStr,
                                       boolean userInitiated, boolean lineage,
                                       boolean memoryStopped) {
        if (userInitiated || lineage) {
            return;
        }
        if (!memoryStopped && !isStoppedByUser(sessionDir, sessionId, agentIdHex, agentIdStr)) {
            return;
        }
        throw new ResumeErrorCode.ResumeException(
            ResumeErrorCode.AGENT_STOPPED_BY_USER,
            ResumeErrorCode.stoppedByUserText(agentIdStr));
    }

    /** stopped-by-user 标记双键查找（a+16hex 新格式优先，UUID 旧格式兜底；含 meta.stoppedByUser）。 */
    static boolean isStoppedByUser(Path sessionDir, String sessionId,
                                   String agentIdHex, String agentIdStr) {
        return AgentTranscript.isStoppedByUserOrMeta(sessionDir, sessionId, agentIdHex)
            || AgentTranscript.isStoppedByUserOrMeta(sessionDir, sessionId, agentIdStr);
    }

    /**
     * teammate 重拉的历史重放 · 对齐 CC {@code resumeInProcessTeammate}
     * （claude.exe 2.1.328 偏移 356099332：{@code rwe} 读 transcript → 同一变形链）。
     *
     * <p>与 subagent resume 的差异只在「谁来跑」：teammate 走同会话 in-process 重拉，
     * 但<b>恢复起点完全相同</b> —— 末尾不完整轮截断（{@code oKt}）+ 三层过滤 + 未闭合
     * tool_use 补 {@code is_error}（{@code ure}），⛔ 不是「只拿最后一条」。
     *
     * @param sessionDir 会话目录根
     * @param sessionId  会话 ID
     * @param agentId    teammate agentId（{@code name@team}，即 transcript 键）
     * @return 可直接作为 {@code resumeMessages} 重放的消息列表；无 transcript → 空列表
     *         （调用方文案走 {@code (no prior transcript)} 变体）
     */
    public static List<AgentMessage> loadTeammateResumeMessages(Path sessionDir, String sessionId,
                                                                String agentId) {
        if (sessionDir == null || sessionId == null || agentId == null || agentId.isBlank()) {
            return List.of();
        }
        Optional<AgentTranscript.AgentTranscriptResult> transcriptOpt =
            AgentTranscript.getAgentTranscript(sessionDir, sessionId, agentId);
        if (transcriptOpt.isEmpty()) {
            return List.of();
        }
        List<AgentMessage> truncated = truncateIncompleteTail(transcriptOpt.get().messages());
        List<AgentMessage> filtered = MessageFilters.filterWhitespaceOnlyAssistantMessages(
            MessageFilters.filterOrphanedThinkingOnlyMessages(
                MessageFilters.filterUnresolvedToolUses(truncated)));
        List<AgentMessage> resumed = fillInterruptedToolResults(filtered);
        log.info("[ResumeService] teammate 重放历史已装配: agentId={} transcript={} → 重放 {} 条 "
                + "(CC resumeInProcessTeammate: 整段重放，非最后一条)",
            agentId, transcriptOpt.get().messages().size(), resumed.size());
        return resumed;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 恢复起点 · 对齐 CC oKt（末尾不完整轮截断）+ ure（未闭合 tool_use 补 is_error）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 切掉 transcript 末尾「不完整的一轮」· 对齐 CC {@code oKt(msgs)}（偏移 333977 区）。
     *
     * <p>CC 判据（从尾部回退到最后一个完整边界）：
     * <ul>
     *   <li>{@code user}/{@code tool}（CC 的 user 行含 tool_result 块）本身就是边界；</li>
     *   <li>{@code assistant} 必须 {@code stop_reason === null || "tool_use"} 且非 apiError；
     *       本仓无 stop_reason 字段 ⇒ 等价判据 = 非 apiError 且（有正文 或 其 tool_use 全部有结果）；</li>
     *   <li>纯空白 / 孤立 thinking 的 assistant 不是边界（后面会被三层过滤丢弃）。</li>
     * </ul>
     * 只保留边界及其之前的历史；被切掉的尾部（如只发出 tool_use 就被中断的 assistant）
     * 不参与重放。CC 把非会话行搬回尾部，本仓 transcript 结构下直接截断即可。
     */
    static List<AgentMessage> truncateIncompleteTail(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        Set<String> resultIds = new HashSet<>();
        for (AgentMessage m : messages) {
            if ("tool".equals(m.role()) && m.toolCallId() != null) {
                resultIds.add(m.toolCallId());
            }
        }
        int keep = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isCompleteBoundary(messages.get(i), resultIds)) {
                keep = i + 1;
                break;
            }
        }
        if (keep == messages.size()) {
            return messages;
        }
        log.info("[ResumeService] transcript 末尾不完整轮已截断: {} → {} 条 (CC oKt)", messages.size(), keep);
        return new ArrayList<>(messages.subList(0, keep));
    }

    private static boolean isCompleteBoundary(AgentMessage m, Set<String> resultIds) {
        String role = m.role();
        if ("user".equals(role) || "tool".equals(role)) {
            return true;
        }
        if (!"assistant".equals(role)) {
            return false;
        }
        if (m.isApiError()) {
            return false;
        }
        boolean hasBody = m.content() != null && !m.content().isBlank();
        List<AgentMessage.ToolCallInfo> calls = m.toolCalls();
        boolean callsResolved = true;
        if (calls != null) {
            for (AgentMessage.ToolCallInfo call : calls) {
                if (call.id() != null && !resultIds.contains(call.id())) {
                    callsResolved = false;
                    break;
                }
            }
        }
        if (hasBody) {
            return true;
        }
        return calls != null && !calls.isEmpty() && callsResolved;
    }

    /**
     * 为「重放时已丢失」的 tool_use 补 {@code tool_result{is_error:true}} · 对齐 CC
     * {@code ure()}：内容 {@code A call of <name> that a restart lost was refused. Do not make it again.}
     * （CC toolDenialKind="interrupted"；本仓 AgentMessage.isApiError=true 承载 is_error）。
     *
     * <p>WHY：已执行的工具<b>不重跑</b>；没有结果的 tool_use 若原样重放，会被 API 判为
     * 「assistant 声明了工具但缺结果」而直接失败 —— 补一条拒绝结果既是合法消息，也把
     * 「这次调用丢了」如实告诉模型。
     */
    static List<AgentMessage> fillInterruptedToolResults(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        Set<String> resolved = new HashSet<>();
        for (AgentMessage m : messages) {
            if ("tool".equals(m.role()) && m.toolCallId() != null) {
                resolved.add(m.toolCallId());
            }
        }
        List<AgentMessage> out = new ArrayList<>(messages);
        int filled = 0;
        for (AgentMessage m : messages) {
            if (m.toolCalls() == null) {
                continue;
            }
            for (AgentMessage.ToolCallInfo call : m.toolCalls()) {
                if (call.id() == null || !resolved.add(call.id())) {
                    continue;
                }
                String text = "A call of " + (call.name() == null ? "tool" : call.name())
                    + " that a restart lost was refused. Do not make it again.";
                out.add(new AgentMessage("tool", text, true, m.agentId(), true,
                    java.util.UUID.randomUUID().toString(), m.uuid(),
                    List.of(), call.id(), m.timestamp(), null));
                filled++;
            }
        }
        if (filled > 0) {
            log.info("[ResumeService] 为 {} 个丢失的 tool_use 补 is_error 结果 (CC ure/interrupted)", filled);
        }
        return out;
    }

    /**
     * cwd 校验 · 对齐 CC resume 的 {@code Yt(s?.cwd)} 分支。
     *
     * <p>判据：meta 记录了 cwd，且该目录<b>不是可进入的目录</b>（不存在 / 是文件 / 不可访问）
     * → {@code subagent_resume_cwd_refused}，文案逐字取 CC：
     * {@code Cannot resume this agent in the directory its record names: <cwd>. Start a new agent instead.}
     *
     * <p>meta 无 cwd（旧文件 / 外部运行时未传）→ 不阻断，resume 走当前项目目录（CC 对未记录 cwd
     * 的旧记录同样回退到当前会话 cwd）。
     */
    static void verifyMetadataCwd(AgentTranscript.AgentMetadata meta) {
        if (meta == null || meta.cwd() == null || meta.cwd().isBlank()) {
            return;
        }
        boolean usable;
        try {
            usable = Files.isDirectory(Paths.get(meta.cwd()));
        } catch (Exception e) {
            // InvalidPathException / 安全异常：记录里的路径无法评价 ⇒ 与「不可进入」同处理
            usable = false;
        }
        if (!usable) {
            throw new ResumeErrorCode.ResumeException(
                ResumeErrorCode.SUBAGENT_RESUME_CWD_REFUSED,
                "Cannot resume this agent in the directory its record names: " + meta.cwd()
                    + ". Start a new agent instead.");
        }
    }

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
     * {@link SubagentTool#agentRegistry(String)} 暴露的 {@link AgentDefinitionRegistry} 命中自定义 agent
     * （内置 + 自定义合并，custom 覆盖 builtIn，loadAgentsDir.ts:216；CC :106-109
     * {@code activeAgents.find(a => a.agentType === meta.agentType)}）；registry 不可得
     * （plain JUnit 未注入）→ 回退 {@link BuiltInAgents#get}（内置 + fork）；未命中 →
     * GENERAL_PURPOSE_AGENT（CC :109 {@code found ?? GENERAL_PURPOSE_AGENT}）。
     *
     * <p><b>[批 3c]</b> registry 改为<b>会话显式</b>：{@link SubagentTool#agentRegistry(String)} 由调用方
     * （{@link #resumeAgentBackground}）透传主会话 sessionId —— 原无参 {@code agentRegistry()} 的裸 MDC
     * 会话源已删除，若继续用无参重载会静默退化为进程默认 agent-defs（resume 自定义 agent 命中率变化）。
     *
     * @param meta         agent 元数据（可为 null）
     * @param isResumedFork 是否 fork resume
     * @param sessionId    主会话 ID（{@link #resumeAgentBackground} 形参直传；可为 null → 进程默认兜底）
     * @return 解析后的 AgentDefinition（恒非 null）
     */
    AgentDefinition resolveSelectedAgent(AgentTranscript.AgentMetadata meta, boolean isResumedFork,
                                        String sessionId) {
        if (isResumedFork) {
            return ForkSubagentAgentDefinition.create();
        }
        if (meta != null && meta.agentType() != null && !meta.agentType().isBlank()) {
            AgentDefinition found = null;
            if (subagentTool != null && subagentTool.agentRegistry(sessionId) != null) {
                found = subagentTool.agentRegistry(sessionId).findAgent(meta.agentType());
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
     * （{@code eq("session_id", sessionId).orderByUnSafely(SEQ_ASC_NULLS_LAST_ORDER).limit(1)}，
     * CC firstMessage = chain[0]）取 cwd。mapper 未注入（plain JUnit）/ 查询异常 → 软降级跳过（CC 旧 jsonl 无 cwd
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
            // 首条消息 = 链首（CC firstMessage = chain[0]，sessionStorage.ts:2522/4680）。
            // [seq NULL 兜底] ASC 侧**必须** NULLS LAST，不能裸 seq ASC：裸 seq ASC 下 SQLite 视 NULL 最小
            //   → seq 为 NULL 的行（V71 触发器之前写入 / 未升级的库）会被当成「链首」，其 cwd 通常也是 NULL
            //   → 本方法取不到 firstCwd（退化为不恢复目录），更坏的情况是脏行带了一个错的 cwd →
            //   **子代理在错误的工作目录下干活**。语义口径：ASC 侧 NULL 排**末尾**（位置未知，绝不冒充链首）。
            //   片段自带方向（SQLite 文法要求方向在 NULLS LAST 之前），故不得再叠加 .orderBy("seq", ...)；
            //   排序键仍是索引列 seq → 查询计划与裸 seq 完全相同（WHY 见 MessageService.SEQ_ASC_NULLS_LAST_ORDER）。
            List<MessageRecord> rows = messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionKey)
                    .orderByUnSafely(MessageService.SEQ_ASC_NULLS_LAST_ORDER).limit(1));
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
     *
     * <p><b>[F1 2026-09-16] 锚 = 稳定会话绑定项目根</b>（{@code CwdResolution.getProjectRoot}，
     * 经 {@code SessionStorage.sessionProjectRoot} 单点）—— 原「对齐 CC
     * {@code getProjectDir(getOriginalCwd())}」的说法<b>已不成立</b>：originalCwd 层被
     * {@code EnterWorktreeTool} 重锚 ⇒ 进 worktree 后换根，与读侧（hook 的 agent_transcript_path）
     * 分裂。⛔ 勿改回。详见 {@code SessionStorage.sessionProjectRoot} javadoc（含 CC gh-30217 对照）。
     *
     * <p>供 subagent sidechain transcript（AgentTranscript.getAgentTranscript/recordSidechainTranscript）
     * 使用 —— 与读侧 seam {@code SessionStorage.getAgentTranscriptPathForSession} 同根，双根分裂消除。
     *
     * @param sessionId 主会话 ID。null / 空白 / {@code SessionKeys.NO_SESSION} 哨兵 ⇒ 走<b>无会话命名出口</b>
     *                  （值 = 进程 {@code user.dir}，并打 ≥WARN）；DB 明确答「无此会话」/ 会话存在但未绑定
     *                  项目 / <b>无法判定</b>（回源器未接线、抛错、违约返回 null）⇒ <b>fail-loud 抛</b>
     *                  （⛔ 不是「回落 user.dir 兜底层」）
     */
    public static Path resolveSessionDir(String sessionId) {
        return com.nexusai.application.agent.tool.SessionStorage.sessionProjectDir(sessionId);
    }

    /**
     * 解析 agentId 字符串为 resume 入参键 · 对齐 CC {@code asAgentId}（ids.ts:31-33）。
     *
     * <p>a+16hex 原样；其它非空串原样（诚实降级，查不到 transcript 即抛 transcriptMissing）；
     * null/空白 → null（调用方按「不可 resume」处理，fail loud）。
     *
     * <p><b>Breaking</b>：旧 UUID 键不再还原成 a+16hex（按 CC 单一形态；旧数据不可恢复）。
     */
    public static String parseAgentIdString(String agentId) {
        return AgentContext.canonicalAgentId(agentId);
    }
}
