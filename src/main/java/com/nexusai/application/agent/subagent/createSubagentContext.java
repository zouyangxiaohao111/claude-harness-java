package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 创建子 Agent 上下文 · 对齐 CC forkedAgent.ts:345-462 createSubagentContext
 *
 * <p>关键设计（对齐 CC）：
 * <ul>
 *   <li><b>Sync 模式</b>：与父 Agent 共享 setAppState 和 setResponseLength 回调</li>
 *   <li><b>Async 模式</b>：完全隔离，独立 abortController</li>
 *   <li><b>共享文件状态</b>：从父克隆 readFileState（避免重复读）</li>
 *   <li><b>消息隔离</b>：子 Agent 有自己的 messages[]</li>
 *   <li><b>返回 ToolUseContext</b>（IMP-SUB-19 #23）：CC createSubagentContext 直接返回
 *       ToolUseContext，无包装 record、无隔离枚举（FULL/SHARED 由 shareSetAppState /
 *       shareSetResponseLength 标志派生）。原 {@code SubagentContext} 包装 record 与
 *       {@code IsolationLevel} 枚举为 Java 侧承载 options/runtime 的结构选择，已删除对齐 CC。</li>
 * </ul>
 */
public class createSubagentContext {

    private static final Logger log = LoggerFactory.getLogger(createSubagentContext.class);

    /**
     * Agent 上下文选项 · 对齐 CC runAgent.ts:700-714 agentOptions
     */
    public record AgentOptions(
        Map<String, Object> tools,
        boolean useExactTools,
        Object thinkingConfig,
        Map<String, Object> mcpClients,
        Map<String, Object> mcpResources,
        Map<String, AgentDefinition> agentDefinitions,
        String querySource,
        boolean canReadOutputFile
    ) {
        public static AgentOptions defaultOptions() {
            return new AgentOptions(
                Map.of(), false, null, Map.of(), Map.of(), Map.of(),
                null, false
            );
        }
    }

    /**
     * 子 Agent 运行时状态 · 对齐 CC runAgent 中 agentAbortController + readFileState
     *
     * <p>ToolUseContext 为 immutable record，运行时可变状态由此类承载。
     *
     * <p>[X4 基础设施保留] Java record 不可变 → 可变态外部化（CC 把可变态塞回
     * ToolUseContext 对象）。IMP-SUB-19 #23 删除包装 record 后 create() 不再返回
     * SubagentRuntime（返回 ToolUseContext）；其承载的 abortController/readFileState/
     * contentReplacementState/localDenialTracking 与 TUC 同一实例（create() 内部注入），
     * 消费方改读 TUC 对应字段。record 类型保留为 Java record 不可变基础设施（open-decisions
     * §F2 X4 保留）。
     */
    public record SubagentRuntime(
        /** 取消控制器镜像（AtomicBoolean）· 透传给 state.cancel()（Phase 2 queryLoop abort 关联）
         *  对齐 CC agentAbortController (runAgent.ts:524-528) */
        AtomicBoolean abortController,
        /** 真实 AbortController 引用 · [Phase A 任务 2] 新增。sync 模式与父共享同一对象,
         *  async 模式独立 + 父订阅链路。与 abortController(AtomicBoolean) 保持镜像关系:
         *  abortControllerRef.abort() → abortController.set(true) (通过 onCancel listener)。 */
        AbortController abortControllerRef,
        /** 文件状态缓存（从父 Agent 克隆）· 对齐 CC readFileState (runAgent.ts:375-378).
         *  [P-CC-02] 类型为 FileStateCache (双限真 LRU: maxEntries=100 + maxSizeBytes=25MB,
         *  用户 2026-08-05 拍板严格对齐 CC, 对齐 CC fileStateCache.ts:30-93). */
        FileStateCache readFileState,
        /** 内容替换状态（从父 Agent 克隆）· 对齐 CC contentReplacementState */
        Map<String, Object> contentReplacementState,
        /** 本地拒绝追踪 · 对齐 CC localDenialTracking */
        Map<String, Object> localDenialTracking,
        /** Agent 启动时间戳 · 对齐 CC agentStartTime */
        long agentStartTimeMs
    ) {
        public SubagentRuntime {
            if (abortController == null) abortController = new AtomicBoolean(false);
            if (abortControllerRef == null) abortControllerRef = new AbortController();
            // [P-CC-02] null 兜底改为 createFileStateCache() 双限真 LRU, 替代原 Map.of() 无界.
            if (readFileState == null) readFileState = ToolUseContext.createFileStateCache();
            if (contentReplacementState == null) contentReplacementState = Map.of();
            if (localDenialTracking == null) localDenialTracking = Map.of();
            if (agentStartTimeMs <= 0) agentStartTimeMs = System.currentTimeMillis();
        }

        /**
         * 创建默认 runtime（独立 agent，无父状态）。
         * [Phase A 任务 2] 现在创建一个真实的 AbortController + AtomicBoolean 镜像，
         * 保证 standalone agent 也能独立 abort（不再依赖 NOOP 单例的"永真/永假"歧义）。
         * [P-CC-02] readFileState 改为 ToolUseContext.createFileStateCache() (FileStateCache 双限真 LRU).
         */
        public static SubagentRuntime standalone() {
            AbortController ownAbort = new AbortController();
            AtomicBoolean mirror = new AtomicBoolean(false);
            ownAbort.onCancel(c -> mirror.set(true));
            return new SubagentRuntime(
                mirror, ownAbort, ToolUseContext.createFileStateCache(), Map.of(), Map.of(),
                System.currentTimeMillis()
            );
        }

        /** 发送取消信号 · [Phase A 任务 2] 改为调 abortControllerRef.abort() 触发 listeners,
         *  AtomicBoolean 镜像通过 onCancel listener 同步翻转为 true。 */
        public void abort() {
            abortControllerRef.abort();
        }

        /** 是否已取消 · [Phase A 任务 2] 改为读 abortControllerRef.isCancelled() (单一真相源)。 */
        public boolean isAborted() {
            return abortControllerRef.isCancelled();
        }
    }

    /**
     * 创建子 Agent 上下文 · parent + overrides 模式 · 返回 ToolUseContext（对齐 CC
     * forkedAgent.ts:345-462 createSubagentContext 直接返回 ToolUseContext）。
     *
     * <p><b>WHY B 方案 (CLAUDE.md 规则一)</b>：
     * CC {@code Open-ClaudeCode/src/utils/forkedAgent.ts:345-462 createSubagentContext}
     * 用 object literal spread <code>{...parentContext, ...overrides}</code>
     * 模式构造子 ctx. Java 端 46-arg 拆解构造模式与 CC 架构同构性差 — B 方案
     * 用 {@link ToolUseContext#with(ToolUseContext.SubagentContextOverrides)}
     * 模拟同构语义, 让 Java 端与 CC 在"父 ctx + 差异化 overrides"模式上严格对齐.
     *
     * <p><b>行为对齐 CC</b>:
     * <ul>
     *   <li>字段继承: overrides 为 null 字段 → 从 parent 继承 (CC forkedAgent.ts:446-461 spread 默认)</li>
     *   <li>agentId: overrides.agentId 优先, 否则 {@code packAgentId(createAgentId())} 新 a+16hex
     *       （R3-WF-F IMP-SUB-12 返工 · 对齐 CC :448 {@code overrides?.agentId ?? createAgentId()}，
     *       S-12 可逆编码桥；原 {@code UUID.randomUUID()} 已删）</li>
     *   <li>abortController: sync (hasParent) 共享父引用, standalone 独立 (CC :350-354)</li>
     *   <li>readFileState: overrides 优先, 否则从父 clone (CC :379-381)</li>
     *   <li>contentReplacementState: overrides 优先, 否则 new HashMap<>() (CC :399-403)</li>
     *   <li>requireCanUseTool: 纯 override 透传 (CC :460 overrides?.requireCanUseTool,
     *       override 缺省 = undefined = falsy, 不取父值兜底)</li>
     * </ul>
     *
     * <p>[IMP-SUB-19 #23] 原 3 参 create(parent, overrides, options) 已删除：该重载仅把
     * fork 专属 {@link AgentOptions} 塞进已删除的 {@code SubagentContext} 包装 record。
     * CC 对齐后 AgentOptions 由调用方（SubagentExecutor）本地持有，并经
     * {@code mergeOptionsAgentDefinitions(AgentOptions)} 消费（options.agentDefinitions 并入
     * 子 registry · CC runAgent.ts:700-714 / print.ts:4381-4383）。
     *
     * @param parentToolUseContext 父 Agent 的 ToolUseContext (可为 null, null 时独立 standalone 路径)
     * @param overrides           覆写字段 (null 时仅继承 parent, agentId 由 create() 内部生成)
     * @return 子 Agent 的 ToolUseContext（对齐 CC createSubagentContext 返回类型）
     */
    public static ToolUseContext create(
            ToolUseContext parentToolUseContext,
            ToolUseContext.SubagentContextOverrides overrides) {

        boolean hasParent = parentToolUseContext != null;
        // [B-1][B 返工 R-1] sync/async 意图由 overrides.shareSetAppState/shareSetResponseLength 显式表达
        //   (默认 null = sync: shareSetAppState=true, shareSetResponseLength=true);
        // abort 决策对齐 CC 双层语义（R-1 修复）:
        //   - 层 1（调用方 SubagentExecutor = CC runAgent.ts:520-528）:
        //       agentAbortController = override?.abortController
        //         ? override.abortController
        //         : isAsync ? new AbortController() : toolUseContext.abortController
        //     → sync 显式传父引用 / async 显式传 new AbortController()（unlinked, 独立运行）
        //   - 层 2（本方法 = CC forkedAgent.ts:350-354）:
        //       abortController = overrides?.abortController
        //         ?? (shareAbortController ? parentContext.abortController : createChildAbortController(parentContext.abortController))

        // [B 返工 R-1] abort 决策链: 显式 override > shareAbortController=true 共享父引用 >
        //   父.createChild()（默认, CC :354）> standalone 独立
        AbortController parentAbort = hasParent ? parentToolUseContext.abortController() : null;
        AbortController ownAbort;
        AtomicBoolean mirrorAtomic;
        if (overrides != null && overrides.abortController() != null) {
            // 显式 override 优先 (CC forkedAgent.ts:350-351) — SubagentExecutor 按 CC runAgent.ts:524-528
            //   sync 传父引用（共享）/ async 传 new AbortController()（独立 unlinked）
            ownAbort = overrides.abortController();
            mirrorAtomic = new AtomicBoolean(ownAbort.isCancelled());
            ownAbort.onCancel(c -> mirrorAtomic.set(true));
        } else if (hasParent && parentAbort != null) {
            if (overrides != null && Boolean.TRUE.equals(overrides.shareAbortController())) {
                // shareAbortController=true → 共享父引用 (CC :352-353)
                ownAbort = parentAbort;
            } else {
                // 默认 → 父.createChild(): 独立对象, 父 abort 级联到子 (CC :354 createChildAbortController)
                ownAbort = parentAbort.createChild();
            }
            mirrorAtomic = new AtomicBoolean(ownAbort.isCancelled());
            ownAbort.onCancel(c -> mirrorAtomic.set(true));
        } else {
            // standalone（无父）: 独立 AbortController, mirror 跟随
            ownAbort = new AbortController();
            mirrorAtomic = new AtomicBoolean(false);
            ownAbort.onCancel(c -> mirrorAtomic.set(true));
        }

        // [B 方案] overrides.agentId 优先, 否则 createAgentId() 新 a+16hex（R3-WF-F IMP-SUB-12 返工）。
        // 对齐 CC forkedAgent.ts:448: agentId: overrides?.agentId ?? createAgentId() —— 生产生成点接线，
        //   UUID.randomUUID() → AgentContext.createAgentId()（a+16hex），经 packAgentId 可逆编码存入
        //   ToolUseContext.agentId（Java record UUID 基础设施承载，S-12 可逆编码桥，见 AgentContext）。
        // resume 路径 overrides.agentId() = 原 spawn 的 packed UUID → 直接继承（CC :448 override 胜出），
        //   经 SubagentExecutor unpackAgentId 还原同一 a+16hex 续写 transcript。
        UUID agentId = (overrides != null && overrides.agentId() != null)
            ? overrides.agentId()
            : AgentContext.packAgentId(AgentContext.createAgentId());
        // sessionId: 父继承或独立生成
        // [session-id-short] standalone 子代理会话键统一 short 形态（sess-xxx）；该键用作 cwd/
        // worktree 隔离桶键（非 DB 会话），随机 short 语义合适。子代理 TUC agentId 保持 packed a+16hex。
        String sessionId = hasParent
            ? parentToolUseContext.sessionId()
            : "sess-" + UUID.randomUUID().toString().substring(0, 8);

        // [IMP-SUB-19 #23] IsolationLevel 枚举已删（CC 无隔离枚举，FULL/SHARED 由
        //   shareSetAppState/shareSetResponseLength 标志派生，由 with() 应用）。
        //   原 derived shareSetAppState/shareSetResponseLength 计算仅服务于包装 record，
        //   删除（with() 用 overrides 原始值应用共享语义，行为不变）。

        // [B 方案] readFileState: overrides 优先 (CC forkedAgent.ts:379-381 cloneFileStateCache).
        //   overrides.readFileState() != null → 用 overrides (caller 显式提供, 通常是父 clone)
        //   否则: 有父 + 父 readFileState 非空 → 从父 clone; 否则 new 空 cache
        FileStateCache tucReadFileState;
        if (overrides != null && overrides.readFileState() != null) {
            // overrides 显式提供 (通常是 ToolUseContext.with() 内部从父 clone, 见 with() 末段)
            tucReadFileState = overrides.readFileState();
        } else if (hasParent && parentToolUseContext.readFileState() != null
                && parentToolUseContext.readFileState().size() > 0) {
            // 父 cache 非空 → 从父 clone (CC :379-381)
            tucReadFileState = ToolUseContext.cloneFileStateCache(parentToolUseContext.readFileState());
        } else {
            // standalone 或父 cache 空 → new 空 cache
            tucReadFileState = ToolUseContext.createFileStateCache();
        }

        // [B 方案] contentReplacementState: overrides 优先, 否则 new HashMap<>()
        Map<String, Object> contentReplacementState =
            (overrides != null && overrides.contentReplacementState() != null)
                ? overrides.contentReplacementState()
                : new HashMap<>();

        // [B 方案] parent.with(overrides) 模式 · 字段继承 + 差异化覆写
        //   hasParent=true → 用 parent.with(overrides), 由 ToolUseContext.with() 处理:
        //     - 9 字段 (permissionContext/permissionMode/mcpClients/isNonInteractiveSession/
        //       renderedSystemPrompt/effectiveCwd/messages/abortController/agentType) 自动从父继承
        //     - overrides 显式提供的字段覆写父
        //   hasParent=false → 用 parent.with(overrides) 报 NPE (null 调用),
        //     改用 standalone 构造路径 (与 A 方案一致: 独立 sessionId + DEFAULT mode + NOOP/独立 abort)
        ToolUseContext agentCtx;
        if (hasParent) {
            // [B 方案] 在调用 parent.with() 前, 把已计算的值注入 overrides:
            //   - agentId: 强制用 create() 内部生成的 UUID (overrides.agentId()==null 时, CC :448 createAgentId)
            //     with() 内部对 agentId 不做特殊处理, 必须 caller 显式注入新 UUID.
            //   - abortController: 强制用 ownAbort (sync 共享 / standalone 独立), 避免 with() 走父 abort.
            //   - readFileState: 强制用 tucReadFileState (从父 clone), 避免 with() 二次 clone 浪费.
            ToolUseContext.SubagentContextOverrides effectiveOverrides =
                new ToolUseContext.SubagentContextOverrides(
                    agentId,                                  // 强制注入新 UUID (CC :448)
                    overrides != null ? overrides.agentType() : null,
                    overrides != null ? overrides.messages() : null,
                    ownAbort,                                 // 强制注入 ownAbort (sync 共享 / standalone 独立)
                    null,                                     // shareAbortController: abortController 已显式注入 ownAbort, 无需 share 语义 (S7)
                    tucReadFileState,                         // 强制注入从父 clone 的 readFileState
                    overrides != null ? overrides.permissionContext() : null,
                    overrides != null ? overrides.shareSetAppState() : null,
                    overrides != null ? overrides.shareSetResponseLength() : null,
                    overrides != null ? overrides.criticalSystemReminder_EXPERIMENTAL() : null,
                    overrides != null ? overrides.contentReplacementState() : null,
                    // [S7 合并] options/getAppState: null → 父继承/内部决策 (CC :262/:274)
                    overrides != null ? overrides.options() : null,
                    overrides != null ? overrides.getAppState() : null,
                    // [Session M.1.1-R4] requireCanUseTool: 纯 override 透传 (CC forkedAgent.ts:460)
                    overrides != null ? overrides.requireCanUseTool() : null
                );
            agentCtx = parentToolUseContext.with(effectiveOverrides);
        } else {
            // standalone 路径 (无父) — 构造最小独立 ctx (与 A 方案一致)
            // [B-1] 补 honor overrides.agentType()/overrides.messages() · 对齐 CC runAgent.ts:347-358
            //   (agentId/agentType 恒有值). 旧实现硬编码 null/empty → 独立子 agent 的
            //   agentType=null, SubagentStart hooks 按 agentType 路由失效 (LlmAgentLoop /
            //   ExecAgentHook 依赖 agentType). messages 无父可继承, override 提供时使用.
            String propagatedTaskListId = "";  // 无父 → 无 taskListId
            java.util.Map<String, com.nexusai.application.agent.tool.ToolUseContext.AdditionalWorkingDirectory> emptyAwd =
                java.util.Map.of();
            java.util.List<com.nexusai.application.agent.tool.Tool> emptyTools =
                java.util.List.of();
            // List<?> messages — wildcard ctor 字段, 显式类型变量避免 raw List 推断歧义
            java.util.List<?> standaloneMessages = (overrides != null && overrides.messages() != null)
                ? overrides.messages()
                : java.util.List.of();
            java.util.Map<String, com.nexusai.application.agent.tool.McpClientRuntime> emptyMcp =
                java.util.Map.of();
            java.util.Map<String, com.nexusai.application.agent.tool.ToolDecisionInfo> emptyDecisions =
                java.util.Map.of();
            agentCtx = new ToolUseContext(
                agentId, sessionId, PermissionMode.DEFAULT, emptyAwd, emptyTools,
                propagatedTaskListId, ownAbort, standaloneMessages, null, PermissionMode.DEFAULT,
                emptyMcp, false, "", null, null, emptyDecisions, null,
                null, null, null, null,    // Stage 3.2 C2 (4 fields: getAppState/setAppState/setStreamMode/setSDKStatus)
                null, null, null, null, null, null, null, null, null, null,  // Stage 3.3 UI (10 fields, prompt 回调通道 已删 S9)
                false, null, null, null, null, (overrides != null ? overrides.agentType() : null),
                false, false, null, null, null, null, null,
                tucReadFileState,
                java.util.List.of()   // [MCP-I-9 Q-30] standalone 无父连接
            );
        }

        // 数据流日志 (CLAUDE.md 编码后必须添加数据流日志 · 中文 · slf4j + logback)
        // abort 来源 WHY: sync/async 语义由调用方（SubagentExecutor=CC runAgent.ts:524-528）经
        //   overrides.abortController 显式表达（sync=父引用 / async=独立 unlinked）; 本方法默认
        //   createChild 级联（CC forkedAgent.ts:354）; standalone 独立。日志区分便于审计 async 偏移回归。
        if (log.isInfoEnabled()) {
            String abortSource;
            if (overrides != null && overrides.abortController() != null) {
                abortSource = "override(调用方sync共享/async独立)";
            } else if (hasParent && parentAbort != null) {
                abortSource = (overrides != null && Boolean.TRUE.equals(overrides.shareAbortController()))
                    ? "共享父引用" : "父createChild(级联)";
            } else {
                abortSource = "standalone独立";
            }
            log.info("createSubagentContext.create [parent+overrides 对齐 CC]: agentId={}(a+16hex={}), agentType={}, "
                    + "hasParent={}, permissionContext={}, permissionMode={}, mcpClients.size={}, "
                    + "isNonInteractive={}, renderedPrompt.length={}, effectiveCwd={}, "
                    + "messages.size={}, ownAbort={}({})",
                agentId,
                AgentContext.unpackAgentId(agentId),
                agentCtx.agentType(),
                hasParent,
                agentCtx.permissionContext() != null ? "已透传" : "null(无父)",
                agentCtx.permissionMode(),
                agentCtx.mcpClients() != null ? agentCtx.mcpClients().size() : 0,
                agentCtx.isNonInteractiveSession(),
                agentCtx.renderedSystemPrompt() != null ? agentCtx.renderedSystemPrompt().length() : 0,
                agentCtx.effectiveCwd() != null ? agentCtx.effectiveCwd().toString() : "null",
                agentCtx.messages() != null ? agentCtx.messages().size() : 0,
                ownAbort == com.nexusai.application.agent.tool.AbortController.NOOP ? "NOOP" : "ownAbort",
                abortSource);
        }

        // [IMP-SUB-19 #23] 对齐 CC createSubagentContext 返回类型：直接返回 ToolUseContext。
        //   原 SubagentRuntime 构建（abortControllerRef/readFileState/contentReplacementState/
        //   localDenialTracking 与 TUC 同一实例）与 SubagentContext 包装 record 一并删除；
        //   AgentOptions 由调用方本地持有（见 SubagentExecutor Step 5 merge）。
        return agentCtx;
    }

    /**
     * [P3-7] 浅克隆父 Agent 的 contentReplacementState · 对齐 CC forkedAgent.ts:399-403 cloneContentReplacementState.
     *
     * <p>L1 语义: fork path 时子 Agent 共享父 Agent 的 contentReplacementState (浅克隆),
     * 避免子 Agent 重复占位替换 (recordContentReplacement). non-fork path 独立.
     *
     * <p>null parent → 返回空 Map (向后兼容 standalone agent).
     *
     * @param parent 父 Agent 的 contentReplacementState (可为 null)
     * @return 浅克隆的 Map (可独立修改, 不影响 parent)
     */
    static Map<String, Object> cloneContentReplacementState(Map<String, Object> parent) {
        if (parent == null || parent.isEmpty()) return new HashMap<>();
        return new HashMap<>(parent);
    }
}
