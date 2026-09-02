package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.api.PromptSuggestion;
import com.nexusai.application.agent.memory.AutoDreamConsolidator;
import com.nexusai.application.agent.memory.ExtractMemoriesAgent;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.tool.SystemMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * StopHookPipeline · 对齐 CC {@code Open-ClaudeCode/src/query/stopHooks.ts:96-173} 的
 * 5 阶段流水线（turn-end 内联）。
 *
 * <p><b>[Session H6] P1 关键路径</b>: 此前 LlmAgentLoop §14 只有 blockingError/preventContinuation
 * 两条粗通道, 5 阶段流水线全部缺失。本类把这些 CC 行为落到 Java:
 * <ol>
 *   <li>{@link #saveCacheSafeParams(QuerySource)} — CC stopHooks.ts:96-98</li>
 *   <li>{@link #classifyAndWriteState(QuerySource, String)} — CC stopHooks.ts:108-132</li>
 *   <li>{@link #executePromptSuggestion(boolean)} — CC stopHooks.ts:136-140</li>
 *   <li>{@link #executeExtractMemoriesAndAutoDream(String, ExtractMemoriesAgent, AutoDreamConsolidator, List, boolean, Consumer, boolean, Path, String)}
 *       — CC stopHooks.ts:136-156（FIX-EX 单方法，bareMode 门控并入）</li>
 *   <li>{@link #cleanupComputerUseAfterTurn(String)} — CC stopHooks.ts:164-173</li>
 * </ol>
 *
 * <p><b>已知基础设施缺口（不假实现, 门控判断 + 日志说明未启用）</b>:
 * <ul>
 *   <li>saveCacheSafeParams — Java 无 cache-safe params / forked agent 快照通道</li>
 *   <li>classifyAndWriteState — Java 无 CLAUDE_JOB_DIR / jobs/classifier 对应物</li>
 *   <li>cleanupComputerUseAfterTurn — Java 无 CHICAGO_MCP / computerUse 对应物</li>
 * </ul>
 * 三者按 CC 门控判断后仅日志记录, 绝不假实现。
 *
 * <p>[IMPL-10] DEL-TH-06: 主消费追踪（trackStopHookResult/hookCount 0/1 近似 +
 * stop_hook_summary 生成器）已删除 — CC stopHooks.ts:175-333 是逐 progress 消息累计，
 * Java 聚合模型无 per-progress 通道（见 09 §2 登记），近似追踪不满足 CC 语义。
 */
public final class StopHookPipeline {

    private static final Logger log = LoggerFactory.getLogger(StopHookPipeline.class);

    private static final String CLAUDE_JOB_DIR_KEY = "CLAUDE_JOB_DIR";
    private static final String PROMPT_SUGGESTION_ENV_KEY = "CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION";

    private StopHookPipeline() {}

    // ════════════════════════════════════════════════════════════════════
    // 阶段 1: saveCacheSafeParams
    // ════════════════════════════════════════════════════════════════════

    /**
     * 阶段 1 · 对齐 CC stopHooks.ts:96-98.
     *
     * <p>WHY: 子代理 turn 结束不得覆盖父会话 cache-safe params（prompt-suggestion snapshot /
     * side_question SDK control_request 都读它）。仅 repl_main_thread / sdk 保存。
     *
     * <p>[H-WF4-01 · R-1] 门控对齐 CC canonical 精确相等：CC stopHooks.ts:96 {@code querySource ===
     * 'repl_main_thread' || querySource === 'sdk'} 是对 canonical 字符串的<b>精确匹配</b>。Java
     * {@code USER.canonical()} 同为 {@code "repl_main_thread"}（QuerySource.java:110，USER = 主线程
     * 用户输入 agentId==sessionId）→ 应命中本门控。旧实现 {@code == QuerySource.REPL_MAIN_THREAD}
     * 枚举同一性排除 USER，与 5-W4-9 classify 修复的同一类收窄 bug（X-WF2-05 J-S2/△-3）。
     * 此处用 {@code canonical().equals(...)}（对齐 CC {@code ===} 精确语义，非 classify 的
     * {@code startsWith} 前缀语义 —— saveCacheSafeParams 不匹配 {@code repl_main_thread:outputStyle:custom}）。
     *
     * <p>Java 无 cache-safe params 基础设施 → 门控判断通过后仅日志说明未启用（不假实现）。
     *
     * @param querySource 查询来源
     * @return true = CC 门控通过（本应保存, Java 无基础设施跳过实际保存）
     */
    public static boolean saveCacheSafeParams(QuerySource querySource) {
        boolean gate = querySource != null
            && (querySource.canonical().equals("repl_main_thread")
                || querySource.canonical().equals("sdk"));
        if (gate) {
            log.info("STOP_HOOK saveCacheSafeParams gate 通过 (querySource={}), 但 Java 无 cache-safe "
                + "params 基础设施, 跳过实际保存 · CC stopHooks.ts:96-98", querySource);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK saveCacheSafeParams 跳过 (querySource={}, 仅 repl_main_thread/sdk 保存)",
                    querySource);
            }
        }
        return gate;
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 2: classifyAndWriteState
    // ════════════════════════════════════════════════════════════════════

    /**
     * 阶段 2 · 对齐 CC stopHooks.ts:108-132.
     *
     * <p>WHY: 作为 dispatched job 运行时每个 turn 后分类写 state.json；gate 在 repl_main_thread
     * 上是让后台 fork（extract-memories/auto-dream）不得用它们自己的 assistant messages 污染
     * job 时间线。gate 条件 = CLAUDE_JOB_DIR 存在 && repl_main_thread && !agentId。
     *
     * <p>Java 无 job classifier 基础设施 → 门控判断通过后仅日志说明未启用（不假实现）。
     *
     * <p>[H-WF4-01 · 5-W4-9] 门控对齐 CC 前缀匹配：CC stopHooks.ts:111 {@code querySource.startsWith('repl_main_thread')}
     * （而非 {@code === 'repl_main_thread'} 精确相等）。Java 用 canonical 字符串前缀匹配
     * （{@link QuerySource#canonical()}），USER 与 REPL_MAIN_THREAD 同 canonical
     * {@code "repl_main_thread"}（QuerySource.java:110）均命中 —— 对齐 CC 主线程值域。
     * 旧实现 {@code == QuerySource.REPL_MAIN_THREAD} 将 USER（canonical 同为 repl_main_thread）排除，
     * 收窄了 CC 门控（X-WF2-05 J-S2/△-3）。
     *
     * @param querySource 查询来源
     * @param agentId     子代理 id（null = 主线程）
     * @return true = CC 门控通过（本应分类写 state, Java 无基础设施跳过）
     */
    public static boolean classifyAndWriteState(QuerySource querySource, String agentId) {
        String jobDir = resolveEnvOrProperty(CLAUDE_JOB_DIR_KEY);
        boolean gate = jobDir != null && !jobDir.isBlank()
            && querySource != null && querySource.canonical().startsWith("repl_main_thread")
            && agentId == null;
        if (gate) {
            log.info("STOP_HOOK classifyAndWriteState gate 通过 (jobDir={}), 但 Java 无 job classifier "
                + "基础设施, 跳过实际分类 · CC stopHooks.ts:108-132", jobDir);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK classifyAndWriteState 跳过 (querySource={} agentId={} jobDir={})",
                    querySource, agentId, jobDir);
            }
        }
        return gate;
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 3: executePromptSuggestion
    // ════════════════════════════════════════════════════════════════════

    /**
     * 阶段 3 · 对齐 CC stopHooks.ts:136-140（注入式 PromptSuggestion）。
     *
     * <p>WHY: --bare / SIMPLE 脚本 -p 调用不想要后台 bookkeeping（prompt suggestion 是后台
     * fork agent, 会与 shutdown 争抢资源）。非 bare 且 env 未禁用时以 fire-and-forget 触发
     * （CC: {@code void executePromptSuggestion(stopHookContext)}）。
     *
     * <p>[H6-FIX] 此前用 {@code static final new PromptSuggestion()} 默认 no-op 假触发
     * （enabledSupplier→false, 恒返回空结果, CHANGELOG 0.2.29 H6-2）——懒实现。
     * 现改为注入式: 由 LlmAgentLoop 传入 {@link AgentLoopContext#promptSuggestion()} 实例
     * + 消息派生上下文; 无注入（null）时显式跳过并日志说明, 绝不假触发。CC 参数模型
     * stopHookContext 与 Java PromptSuggestion 的差异见 concern H6-2。
     *
     * <p>[IMP-GP-03 · OPD-WF7-JS-03] 调用 {@link PromptSuggestion#executeSuggestion}——
     * CC 停链执行入口（promptSuggestion.ts:184-237），内部跑 tryGenerateSuggestion 全链
     * （early_conversation/cache_cold/suppress/filter）+ speculation 触发（:214-222）。
     *
     * @param bareMode           --bare 模式标志（CC isBareMode()）
     * @param promptSuggestion   注入的 PromptSuggestion 实例（null = 无生产 bean, 显式跳过）
     * @param context            消息派生上下文（CC stopHookContext.messages 等价；null 容错）
     * @return true = 已异步触发（fire-and-forget）
     */
    public static boolean executePromptSuggestion(boolean bareMode, PromptSuggestion promptSuggestion,
            PromptSuggestion.SuggestionContext context) {
        if (bareMode) {
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK executePromptSuggestion 跳过 (bare mode)");
            }
            return false;
        }
        if (isPromptSuggestionDisabled()) {
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK executePromptSuggestion 跳过 (env 显式禁用)");
            }
            return false;
        }
        if (promptSuggestion == null) {
            // [H6-FIX] 无注入实例 → 显式跳过（此前 static no-op 假触发恒返回空结果, 功能惰性）
            log.info("STOP_HOOK executePromptSuggestion 跳过 (无注入 PromptSuggestion 实例 · 生产未接线) · CC stopHooks.ts:138-140");
            return false;
        }
        // CC: void executePromptSuggestion(stopHookContext) fire-and-forget
        CompletableFuture.runAsync(() -> {
            try {
                promptSuggestion.executeSuggestion(context);
            } catch (Exception e) {
                log.warn("STOP_HOOK executePromptSuggestion 失败(静默): {}", e.getMessage());
            }
        });
        log.info("STOP_HOOK executePromptSuggestion 已异步触发 (fire-and-forget) · CC stopHooks.ts:138-140");
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 4: executeExtractMemoriesAndAutoDream
    // ════════════════════════════════════════════════════════════════════

    /**
     * 阶段 4 · 对齐 CC stopHooks.ts:136-156（单方法，含 bareMode 门控）。
     *
     * <p><b>fallback 链收敛（FIX-EX）</b>: 4/5/6 参重载合并为单方法 —— CC stopHooks.ts:149-152
     * 是单点调用（{@code void extractMemoriesModule!.executeExtractMemories(...)}），外层
     * {@code if (!isBareMode())}（:136）包住 prompt-suggestion/extract-memories/auto-dream 三段。
     * 既有 4 参（isNonInteractiveSession=false + appendSystemMessage=null）与 5 参
     * （appendSystemMessage=null）语义均由本方法显式参数覆盖，不再靠重载委托。
     *
     * <p>WHY: 记忆提取是主会话级行为；子代理 turn 结束时触发会把它自己的片段当作主会话记忆
     * 写入。gate = bareMode == false && agentId == null && agent 非 null &&
     * {@link #isExtractMemoriesModuleEnabled()} && {@link #isExtractModeActive(boolean)}
     * （[IMP-CM-20 OPD-CM3-13/B06] 模块级 feature('EXTRACT_MEMORIES') 与运行时主 flag
     * [sm 决策 2026-08-30] DB auto_memory_enabled 主控调用点 AND，对齐 CC stopHooks.ts:142-143）。
     * autoDream 无 isExtractModeActive 门控（CC: {@code if (!toolUseContext.agentId)} 无条件）。
     *
     * <p>[IMP-M-P0-3b] extract 触发经 {@code extractAgent.executeExtractMemories(snapshot,
     * appendSystemMessage)} —— CC stopHooks.ts:149-152 fire-and-forget（内部自行 in-flight 登记；
     * [rev2 EX-01] drain 仅 headless 类退出路径 = 应用关闭 @PreDestroy，LlmAgentLoop 轮次退出处
     * 不再同步等待，CC print.ts:962-969 对照）。透传 appendSystemMessage 让 memory_saved
     * 系统消息（extractMemories.ts:490-496）直达 UI。autoDream 保持 runAsync。
     * [D5-A/M-11] auto-dream 参数化：workspaceDir/sessionId 由调用方（LlmAgentLoop）按会话
     * 捕获、经本方法透传 consolidateIfNeeded —— 替代 @Bean 共享 volatile（异步 runAsync 的
     * 跨会话交错窗口消除）。
     *
     * @param agentId              子代理 id（null = 主线程）
     * @param extractAgent         记忆提取 agent（@Autowired(required=false)，null = 未注入）
     * @param dreamer              auto-dream 整合器（@Autowired(required=false)，null = 未注入）
     * @param messages             对话快照（主线程复制）
     * @param isNonInteractiveSession 当前会话是否非交互（-p/SDK；CC isExtractModeActive 用它分流）
     * @param appendSystemMessage  UI 系统消息回调（CC toolUseContext.appendSystemMessage；null =
     *                             extract 不追加 memory_saved 系统消息）
     * @param bareMode             --bare / CLAUDE_CODE_SIMPLE 模式（CC stopHooks.ts:136
     *                             {@code if (!isBareMode())} 外层守卫；true → 跳过 extract+dream）
     * @param workspaceDir         会话 transcript 扫描根目录（D5-A/M-11：LlmAgentLoop 按会话
     *                             捕获后透传 consolidateIfNeeded；null = 会话门阻断）
     * @param sessionId            当前 session ID（排除自身 · CC autoDream.ts:164；null = 不排除）
     * @return true = 至少触发了一个异步任务
     */
    public static boolean executeExtractMemoriesAndAutoDream(String agentId,
                                                             ExtractMemoriesAgent extractAgent,
                                                             AutoDreamConsolidator dreamer,
                                                             List<ChatMessageDto> messages,
                                                             boolean isNonInteractiveSession,
                                                             Consumer<SystemMessage> appendSystemMessage,
                                                             boolean bareMode,
                                                             Path workspaceDir,
                                                             String sessionId) {
        // 9 参便捷重载 · 无 fork 原料（非主循环调用方：测试/直构）→ null 原料透传，
        // extract/dream 侧保持既有兜底（supplier / createMinimalCacheSafeParams，不 fail-loud）。
        return executeExtractMemoriesAndAutoDream(agentId, extractAgent, dreamer, messages,
            isNonInteractiveSession, appendSystemMessage, bareMode, workspaceDir, sessionId, null);
    }

    /**
     * 阶段 4 · extract-memories + auto-dream 每轮触发 · 对齐 CC stopHooks.ts:136-156。
     *
     * <p><b>fork 原料透传（IMP-MV2-09 T9）</b>: {@code forkRawMaterial} 承载主线程
     * systemPrompt/userContext/systemContext/消息快照（LlmAgentLoop:5154 按会话捕获 ·
     * CC createCacheSafeParams(context) forkedAgent.ts:131-141），经本方法透传
     * extract/dream —— 修复 fork 空载荷（ToolRegistrationConfig:1468-1469）导致的
     * 无主系统提示 + prompt-cache key 不一致。D5-A workspaceDir 同款按会话捕获传参
     * （异步 runAsync 的跨会话交错窗口消除）。null（非主循环调用方）→ agents 侧兜底。
     *
     * <p>其余语义同 9 参 {@link #executeExtractMemoriesAndAutoDream(String, ExtractMemoriesAgent,
     * AutoDreamConsolidator, List, boolean, Consumer, boolean, Path, String)}。
     *
     * @param forkRawMaterial fork 原料（主线程 systemPrompt/userContext/systemContext/
     *                        消息快照 · forkedAgent.ts:131-141；null = 无捕获，agents 兜底）
     * @return true = 至少触发了一个异步任务
     */
    public static boolean executeExtractMemoriesAndAutoDream(String agentId,
                                                             ExtractMemoriesAgent extractAgent,
                                                             AutoDreamConsolidator dreamer,
                                                             List<ChatMessageDto> messages,
                                                             boolean isNonInteractiveSession,
                                                             Consumer<SystemMessage> appendSystemMessage,
                                                             boolean bareMode,
                                                             Path workspaceDir,
                                                             String sessionId,
                                                             com.nexusai.application.agent.compact.fork.ForkRawMaterial forkRawMaterial) {
        // CC stopHooks.ts:136 if (!isBareMode()) —— bare/SIMPLE 脚本 -p 调用跳过后台 bookkeeping
        if (bareMode) {
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK extract/dream 跳过 (bare mode · CC stopHooks.ts:136 isBareMode())");
            }
            return false;
        }
        if (agentId != null) {
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK extract/dream 跳过 (子代理 agentId={})", agentId);
            }
            return false;
        }
        boolean triggered = false;
        // [IMP-CM-20 OPD-CM3-13/B06] 模块级 EXTRACT_MEMORIES 开关 AND · 对齐 CC stopHooks.ts:142-143
        //   `feature('EXTRACT_MEMORIES') && !toolUseContext.agentId && isExtractModeActive()`：
        //   模块开关（NEXUSAI_EXTRACT_MEMORIES_MODULE，默认 true）与运行时开关
        //   （isExtractModeActive = DB auto_memory_enabled 主控，默认 true；env
        //   NEXUSAI_EXTRACT_MEMORIES 仅作可选强制关/开运维覆盖 · [sm 决策 2026-08-30]）两独立控制。
        if (extractAgent != null && isExtractMemoriesModuleEnabled()
                && isExtractModeActive(isNonInteractiveSession)) {
            final List<ChatMessageDto> snapshot = messages == null ? List.of() : List.copyOf(messages);
            // [IMP-M-P0-3b] fire-and-forget · CC stopHooks.ts:149-152 void executeExtractMemories(...)
            //   内部自行 CompletableFuture.runAsync + inFlight 追踪；不再外层 runAsync 包 extract 直调
            // [IMP-MV2-09 T9] fork 原料透传（主线程 systemPrompt/userContext/systemContext/快照
            //   per-call 参数 · forkedAgent.ts:131-141；null = 无捕获兜底）
            // [IMP-E-2 OPD-CM5-E-04] agentId 透传 impl 作双层防御第二层（CC extractMemories.ts:531-533）
            //   —— 本方法 :282 已 gate agentId==null（调用点第一层），impl 入口再二次防御
            //   （拦截未来非 StopHookPipeline 调用方误传子代理片段）。
            // [sm-cursor-sessionize] sessionId 透传 impl —— 游标/stash 按会话键控（多会话互不串扰）
            extractAgent.executeExtractMemories(snapshot, appendSystemMessage, forkRawMaterial, agentId, sessionId);
            log.info("STOP_HOOK extractMemories 已异步触发 (agentId=null, extractAgent 非空, "
                    + "EXTRACT_MEMORIES 模块开关={}, isExtractModeActive, appendSystemMessage={}, bareMode={}, forkRawMaterial={}) · CC stopHooks.ts:136-153",
                isExtractMemoriesModuleEnabled(),
                appendSystemMessage != null ? "有" : "无", bareMode,
                forkRawMaterial != null ? "有" : "无");
            triggered = true;
        }
        if (dreamer != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    // [FIX-AD] 透传 appendSystemMessage（CC autoDream.ts:126/238-248
                    //   runAutoDream(context, appendSystemMessage)）→ fork 成功且 filesTouched>0
                    //   时追加 verb='Improved' 的 memory_saved 完成消息直达 UI
                    // [IMP-MV2-09 T9] fork 原料透传（autoDream.ts:226 createCacheSafeParams(context)
                    //   全量载荷 · forkedAgent.ts:131-141；null = 无捕获兜底）
                    dreamer.consolidateIfNeeded(workspaceDir, sessionId, appendSystemMessage, forkRawMaterial);
                } catch (Exception e) {
                    log.warn("LlmAgentLoop autoDream failed: {}", e.getMessage());
                }
            });
            log.info("STOP_HOOK autoDream 已异步触发 (agentId=null, dreamer 非空, appendSystemMessage={}, bareMode={}, forkRawMaterial={}) · CC stopHooks.ts:136-156",
                appendSystemMessage != null ? "有" : "无", bareMode,
                forkRawMaterial != null ? "有" : "无");
            triggered = true;
        }
        if (!triggered) {
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK extract/dream 跳过 (无 extractAgent/dreamer 注入)");
            }
        }
        return triggered;
    }

    // ════════════════════════════════════════════════════════════════════
    // 阶段 5: cleanupComputerUseAfterTurn
    // ════════════════════════════════════════════════════════════════════

    /**
     * 阶段 5 · 对齐 CC stopHooks.ts:164-173.
     *
     * <p>WHY: CU lock 是 process-wide module 级变量，子代理释放会让主线程 cleanup 看到
     * isLockHeldLocally()===false → 无 exit notification，且子代理不启动 CU 会话 → 纯跳过。
     * 主线程清理失败必须静默（CC try/catch 空 catch —— dogfooding cleanup 非关键路径）。
     *
     * <p>Java 无 CHICAGO_MCP / computerUse 基础设施 → 门控判断通过后仅日志说明未启用。
     *
     * @param agentId 子代理 id（null = 主线程）
     * @return true = CC 门控通过（主线程，本应清理, Java 无基础设施跳过）
     */
    public static boolean cleanupComputerUseAfterTurn(String agentId) {
        if (agentId != null) {
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK cleanupComputerUseAfterTurn 跳过 (子代理 agentId={})", agentId);
            }
            return false;
        }
        log.info("STOP_HOOK cleanupComputerUseAfterTurn gate 通过 (agentId=null), 但 Java 无 CHICAGO_MCP "
            + "基础设施, 跳过实际清理 · CC stopHooks.ts:164-173");
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMPL-10] DEL-TH-06: 主消费追踪（trackStopHookResult/StopHookConsumption/
    //   createStopHookSummaryMessage）已删除 — hookCount 0/1 近似追踪不满足 CC
    //   stopHooks.ts:175-333 逐条累计语义（Java 聚合模型无 per-progress 通道，见 09 §2）；
    //   类本身保留为 CC stopHooks.ts 5 阶段等价物。
    // ════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════
    // 内部辅助
    // ════════════════════════════════════════════════════════════════════

    /** CC {@code isEnvDefinedFalsy(process.env.CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION)} 的 Java 等价。 */
    private static boolean isPromptSuggestionDisabled() {
        String v = resolveEnvOrProperty(PROMPT_SUGGESTION_ENV_KEY);
        if (v == null || v.isBlank()) {
            return false;
        }
        String t = v.trim();
        return "false".equalsIgnoreCase(t) || "0".equals(t)
            || "off".equalsIgnoreCase(t) || "no".equalsIgnoreCase(t);
    }

    // ════════════════════════════════════════════════════════════════════
    // [H6-FIX] isExtractModeActive Java 等价（CC memdir/paths.ts:69-77）
    // ════════════════════════════════════════════════════════════════════

    // [sm 决策 2026-08-30] 总闸 env key NEXUSAI_EXTRACT_MEMORIES 已删除 —— 主 flag 移至 DB
    //   auto_memory_enabled（BundledSkillEnabledGates.isAutoMemoryEnabled，默认 true），env 仅作
    //   可选强制关/开运维覆盖（ExtractMemoriesAgent.extractionEnvOverride 解析，见 isExtractModeActive）。
    private static final String EXTRACT_MODE_NON_INTERACTIVE_KEY = "NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE";

    /**
     * [IMP-CM-20 OPD-CM3-13/B06] EXTRACT_MEMORIES 模块级开关 env key · CC original:
     * {@code feature('EXTRACT_MEMORIES')}（stopHooks.ts:42/142，bun:bundle 编译期宏；
     * paths.ts:65-67 "Callers must also gate on feature('EXTRACT_MEMORIES')"）。
     * Java 无编译期宏 → 独立 env/system property 开关（默认 true = 模块编译入 / bean 已接线），
     * 与 {@link #isExtractModeActive}（DB auto_memory_enabled 主控，默认 true · [sm 决策 2026-08-30]）
     * 解耦为两独立控制。
     */
    private static final String EXTRACT_MODE_MODULE_KEY = "NEXUSAI_EXTRACT_MEMORIES_MODULE";

    /**
     * [IMP-CM-20 OPD-CM3-13/B06] EXTRACT_MEMORIES 模块级开关 · CC original:
     * {@code feature('EXTRACT_MEMORIES')}（stopHooks.ts:42/142，bun:bundle 编译期宏；paths.ts:65-67
     * "Callers must also gate on feature('EXTRACT_MEMORIES')"）。
     *
     * <p>Java 无编译期宏 → 用 {@code NEXUSAI_EXTRACT_MEMORIES_MODULE} env/system property 建模，
     * <b>默认 true</b>（模块已编译入 / extractMemoriesAgent bean 已接线）。与 {@link #isExtractModeActive}
     * （DB auto_memory_enabled 主控，默认 true · [sm 决策 2026-08-30] 旧 env 总闸
     * NEXUSAI_EXTRACT_MEMORIES 降级为可选运维覆盖）相互独立：模块 ON + 提取 OFF 或
     * 模块 OFF + 提取 ON 均可独立表达，对齐 CC 两独立 flag 语义（O-1 双 flag 独立建模）。
     */
    public static boolean isExtractMemoriesModuleEnabled() {
        return isEnvFlagTruthy(EXTRACT_MODE_MODULE_KEY, true);
    }

    /**
     * [H6-FIX] Java 等价 {@code isExtractModeActive()} · 对齐 CC {@code memdir/paths.ts:69-77}.
     *
     * <p>WHY（对抗核验 H6-1 门控近似）: 此前 Java 仅用 {@code extractAgent != null} 代理整个门控，
     * 丢失 CC 的 isExtractModeActive 交互维度。CC 真源结构:
     * <pre>
     *   extractMemories gate = feature('EXTRACT_MEMORIES') && !agentId && isExtractModeActive()
     *   isExtractModeActive()  = flag('tengu_passport_quail')                    // 默认 false
     *                            && (!getIsNonInteractiveSession()
     *                                || flag('tengu_slate_thimble'))             // 默认 false
     * </pre>
     *
     * <p><b>[sm 决策 2026-08-30] 主 flag 由 env 移至 DB（DB 主控）</b>：总闸不再用
     * {@code NEXUSAI_EXTRACT_MEMORIES}（≈ tengu_passport_quail，旧默认 false → 前端 DB 配了但
     * env 没开 = 永不提取）。主 flag 提为 DB settings 列 {@code auto_memory_enabled}
     * （{@link BundledSkillEnabledGates#isAutoMemoryEnabled()}，默认 true）—— 直接 DB 改即生效；
     * env {@code NEXUSAI_EXTRACT_MEMORIES} 保留为可选强制关/开运维覆盖
     * （{@link ExtractMemoriesAgent#extractionEnvOverride()}，null = 不影响）。
     * <pre>
     *   isExtractModeActive() = (env 覆盖 != null ? env 覆盖 : isAutoMemoryEnabled())   // DB 主控，默认 true
     *                           && (!getIsNonInteractiveSession()
     *                               || flag('tengu_slate_thimble'))                     // 默认 false
     * </pre>
     * 非交互维度（{@code NEXUSAI_EXTRACT_MEMORIES_NON_INTERACTIVE} ≈ tengu_slate_thimble，
     * 默认 false —— 非交互（-p/SDK）默认跳过，CC 默认行为）保留。
     *
     * <p><b>[IMP-CM-20 OPD-CM3-13/B06]</b> 模块级 {@code feature('EXTRACT_MEMORIES')} 不在此函数内
     * （CC paths.ts:65-67 注释：isExtractModeActive 不内嵌 feature 检查，调用方必须另行 AND
     * feature('EXTRACT_MEMORIES')）。模块级开关见 {@link #isExtractMemoriesModuleEnabled}，两独立
     * flag 在 {@link #executeExtractMemoriesAndAutoDream} 调用点 AND（CC stopHooks.ts:142-143）。
     *
     * @param isNonInteractiveSession 当前会话是否非交互（CC getIsNonInteractiveSession）
     * @return true = extract mode active（应执行记忆提取）
     */
    public static boolean isExtractModeActive(boolean isNonInteractiveSession) {
        // [sm 决策] DB auto_memory_enabled 提为总闸（默认 true）· env NEXUSAI_EXTRACT_MEMORIES
        //   降级为可选强制关/开运维覆盖（null = 不影响，交 DB 主控）
        Boolean override = ExtractMemoriesAgent.extractionEnvOverride();
        boolean base;
        if (override != null) {
            base = override;
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK isExtractModeActive base = {}（env NEXUSAI_EXTRACT_MEMORIES 运维覆盖）",
                    override);
            }
        } else {
            base = BundledSkillEnabledGates.isAutoMemoryEnabled();
            if (log.isDebugEnabled()) {
                log.debug("STOP_HOOK isExtractModeActive base = {}（DB settings 列 auto_memory_enabled 主控，默认 true）",
                    base);
            }
        }
        if (!base) {
            return false;
        }
        boolean active = !isNonInteractiveSession
            || isEnvFlagTruthy(EXTRACT_MODE_NON_INTERACTIVE_KEY, false);
        if (log.isDebugEnabled()) {
            log.debug("STOP_HOOK isExtractModeActive = {} (isNonInteractiveSession={}, {}={})",
                active, isNonInteractiveSession, EXTRACT_MODE_NON_INTERACTIVE_KEY,
                System.getProperty(EXTRACT_MODE_NON_INTERACTIVE_KEY, System.getenv(EXTRACT_MODE_NON_INTERACTIVE_KEY)));
        }
        return active;
    }

    /** env/system property 布尔 flag 读取（CC {@code getFeatureValue_CACHED_MAY_BE_STALE} 近似）。 */
    private static boolean isEnvFlagTruthy(String key, boolean defaultValue) {
        String v = resolveEnvOrProperty(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        String t = v.trim();
        if ("false".equalsIgnoreCase(t) || "0".equals(t)
            || "off".equalsIgnoreCase(t) || "no".equalsIgnoreCase(t)) {
            return false;
        }
        if ("true".equalsIgnoreCase(t) || "1".equals(t)
            || "on".equalsIgnoreCase(t) || "yes".equalsIgnoreCase(t)) {
            return true;
        }
        return defaultValue;
    }

    /** System property 优先, 回退 System.getenv（CC 读 process.env；Java 测试可设 property）。 */
    private static String resolveEnvOrProperty(String key) {
        String v = System.getProperty(key);
        if (v != null) {
            return v;
        }
        return System.getenv(key);
    }
}
