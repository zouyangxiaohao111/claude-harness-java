package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Commit attribution tracking hooks · 对齐 CC {@code registerAttributionHooks}
 * （Open-ClaudeCode/src/setup.ts:350-360，仅 {@code feature('COMMIT_ATTRIBUTION')} 单门控；
 * setup.ts:337 的 {@code USER_TYPE==='ant'} 为并列兄弟块 repo 分类预热，不门控注册）＋
 * attributionHooks 模块接口（{@code registerAttributionHooks()} /
 * {@code clearAttributionCaches()} / {@code sweepFileContentCache()}，setup.ts:355 /
 * clear/caches.ts:106 / postCompactCleanup.ts:73）。
 *
 * <p><b>L1 语义</b>: 注册 <b>internal PostToolUse hooks</b>（Edit/Write 文件修改工具），在
 * 工具执行后把文件内容变更计入 {@link CommitAttributionTracker}（claudeContribution 逐文件
 * 累加，commitAttribution.ts:402-433）—— 供 commit/PR attribution 文本（Co-Authored-By /
 * "Generated with Claude Code"）计算。对应 setup.ts:355-360 在 CLI 初始化期（非 bare 模式）
 * 动态注册。
 *
 * <p><b>L2 契约（5 Release Gate）</b>:
 * <ul>
 *   <li><b>A1</b>: {@link #registerAttributionHooks(SessionFileAccessHooks.PostToolUseRegistrar)}
 *       （CC setup.ts:356 {@code registerAttributionHooks()}）＋ {@link #handleFileTool}（PostToolUse 回调）＋
 *       {@link #clearAttributionCaches} / {@link #sweepFileContentCache}（attributionHooks
 *       模块导出）＋ {@link #isEnabled}（COMMIT_ATTRIBUTION 单门控）</li>
 *   <li><b>A2 Golden Trace</b>: COMMIT_ATTRIBUTION 开 → 注册 2 个 internal
 *       PostToolUse hooks（Edit/Write）；Edit 文件 → 读新内容 vs 缓存 oldContent → diff →
 *       tracker.fileStates[path].claudeContribution 累加；返回 proceed()（CC "return {}"，
 *       hooks.ts:2038）</li>
 *   <li><b>A3</b>: 门控纯函数（注入式 supplier，测试可注入）；matcher 过滤在 hook 内
 *       （与 SessionFileAccessHooks 一致，HookRegistry 无 matcher map）</li>
 *   <li><b>A4 边界</b>: 门控关（COMMIT_ATTRIBUTION=false）→ 零注册、零副作用；
 *       文件读取失败 → 跳过追踪（不阻断工具链）；非 Edit/Write → no-op</li>
 *   <li><b>A5 业务场景</b>: /commit 前由本 hook 积累每文件 Claude 贡献，CommitPushPrCommand
 *       attribution 渲染 "Co-Authored-By: &lt;model&gt;"（attribution.ts:80）</li>
 * </ul>
 *
 * <p><b>L3（Java idiom）</b>: TS {@code import('./utils/attributionHooks.js').then(...)} 动态导入
 * + feature() 宏 → Java 注入式 {@code commitAttributionEnabled} supplier（默认
 * {@value #COMMIT_ATTRIBUTION_ENABLED} = 发布构建编译期宏 false 等价）；
 * TS HookCallback {@code {type:'callback', internal:true, timeout}} → Java
 * {@link PostToolUseHook} + {@code registerPostToolUseInternal}（hooks.ts:1440-1442 isInternalHook
 * 等价，SessionFileAccessHooks 同模式）。
 *
 * <p><b>⚠️ fail-loud（CC 真源缺失）</b>: CC {@code attributionHooks.js} 模块在源码仓库
 * <b>不存在</b>（setup.ts:355 动态 import 指向文件缺失，基线 6618ab1 已核验零漂移）——
 * 本类实现的是 CC <b>可观测</b> 契约：① 注册点 setup.ts:350-360；② internal PostToolUse 回调
 * 形态 hooks.ts:2037-2052（return {} + 不用 abort signal + 接收 updateAttributionState）；
 * ③ 追踪函数 commitAttribution.ts:402-480。具体 matcher 工具集合按 commitAttribution.ts:400-401
 * "Called after Edit/Write tool completes" 假设为 Edit/Write；hook 内实际调用的 CC 不可见部分
 * （如 Bash 建文件 / 权限计数）不在本期范围（见实施记录 §9）。
 *
 * @see CommitAttributionTracker
 * @see SessionFileAccessHooks  （同模式 internal PostToolUse hooks 注册器）
 */
public final class RegisterAttributionHooks {

    private static final Logger log = LoggerFactory.getLogger(RegisterAttributionHooks.class);

    /**
     * CC original: {@code feature('COMMIT_ATTRIBUTION')}（setup.ts:350，bun:bundle 编译期宏）。
     * <p>Java 端以编译期常量建模（对齐 IMP-RS-01 requestPrompt 默认工厂未注入=通道关闭先例）：
     * 发布构建宏替换为 false → 门控关；内部构建需开启时置 true 并接线
     * LlmAgentLoop.run() 注册点（见实施记录 §9 follow-up）。
     */
    public static final boolean COMMIT_ATTRIBUTION_ENABLED = false;

    /** CC 注册的 matcher 工具 · 按 commitAttribution.ts:400-401 "Called after Edit/Write tool completes". */
    public static final List<String> REGISTERED_TOOLS = List.of(
        ToolNameConstants.FILE_EDIT_TOOL_NAME,    // Edit
        ToolNameConstants.FILE_WRITE_TOOL_NAME);  // Write

    private final CommitAttributionTracker tracker;
    private final BooleanSupplier commitAttributionEnabled;  // feature('COMMIT_ATTRIBUTION')（可注入）
    private final Supplier<Path> repoRootSupplier;           // 读文件 repoRoot（可注入 @TempDir）

    /**
     * 默认构造：tracker 新实例 + COMMIT_ATTRIBUTION 恒关（编译期宏 false 等价）+
     * repoRoot=CC getAttributionRepoRoot 完整链（同 {@link CommitAttributionTracker} 无参构造：
     * {@link CommitAttributionTracker#getAttributionRepoRoot(String)} =
     * findGitRoot(getCwd()) ?? getOriginalCwd()，commitAttribution.ts:83-85）。生产启用需走
     * 完整构造器注入真实门控。
     */
    public RegisterAttributionHooks() {
        this(new CommitAttributionTracker(),
            () -> COMMIT_ATTRIBUTION_ENABLED,
            () -> Path.of(CommitAttributionTracker.getAttributionRepoRoot(RequestContext.sessionId())));
    }

    /** 完整构造器（测试注入门控 + tracker + repoRoot · 镜像 SessionFileAccessHooks 注入式构造器）. */
    public RegisterAttributionHooks(CommitAttributionTracker tracker,
                                    BooleanSupplier commitAttributionEnabled,
                                    Supplier<Path> repoRootSupplier) {
        this.tracker = tracker != null ? tracker : new CommitAttributionTracker();
        this.commitAttributionEnabled = commitAttributionEnabled != null
            ? commitAttributionEnabled : () -> COMMIT_ATTRIBUTION_ENABLED;
        this.repoRootSupplier = repoRootSupplier != null
            ? repoRootSupplier
            : () -> Path.of(CommitAttributionTracker.getAttributionRepoRoot(RequestContext.sessionId()));
    }

    // ════════════════════════════════════════════════════════════════
    // 1. 注册器 · 对齐 CC registerHookCallbacks（setup.ts:356）
    // ════════════════════════════════════════════════════════════════

    /**
     * 单门控 · 对齐 CC {@code feature('COMMIT_ATTRIBUTION')}（setup.ts:350）可观测注册门控。
     * CC setup.ts:337 的 {@code USER_TYPE==='ant'} 是并列兄弟块（repo 分类预热），不门控
     * registerAttributionHooks —— 本方法不再建模 ant 条件（返工 IMP-GP-01-rework 对齐）。
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(commitAttributionEnabled.getAsBoolean());
    }

    /**
     * 注册 attribution tracking hooks · 对齐 CC {@code registerAttributionHooks()}
     * （setup.ts:355-360，仅 feature('COMMIT_ATTRIBUTION') 单门控）。门控关 → 零注册（log
     * 披露）；开 → 为每个 {@link #REGISTERED_TOOLS} 注册 internal PostToolUse hook。
     *
     * <p>幂等：HookRegistry.registerPostToolUse = LinkedHashMap.put 覆盖（同 SessionFileAccessHooks），
     * 每会话 run() 入口重复调用安全。
     *
     * <p>registrar 复用 {@link SessionFileAccessHooks.PostToolUseRegistrar}（HookRegistry 已
     * implements 该接口，注册目标统一为同一抽象，避免重复接口）—— default
     * {@code registerPostToolUseInternal} 变体 = 标记 internal callback（CC hooks.ts:1440-1442，
     * 不入 tengu_run_hook userHooks）。
     *
     * @param registrar PostToolUse 注册目标（生产传 {@link HookRegistry}，测试传记录桩）
     */
    public void registerAttributionHooks(SessionFileAccessHooks.PostToolUseRegistrar registrar) {
        if (registrar == null) {
            log.warn("[RegisterAttributionHooks] PostToolUseRegistrar 未注入, 跳过注册");
            return;
        }
        if (!isEnabled()) {
            log.info("[RegisterAttributionHooks] 门控关闭 (COMMIT_ATTRIBUTION={}), 跳过 attribution hooks 注册",
                Boolean.TRUE.equals(commitAttributionEnabled.getAsBoolean()));
            return;
        }
        for (String tool : REGISTERED_TOOLS) {
            registrar.registerPostToolUseInternal("attribution:" + tool, (toolName, input, result, ctx, stopHookActive) -> {
                if (toolName != null && toolName.equals(tool)) {
                    handleFileTool(toolName, input);
                }
                return GenericHook.HookResult.proceed();
            });
        }
        log.info("[RegisterAttributionHooks] 注册 {} 个 PostToolUse attribution hooks: {}",
            REGISTERED_TOOLS.size(), REGISTERED_TOOLS);
    }

    // ════════════════════════════════════════════════════════════════
    // 2. PostToolUse 回调 · CC attributionHooks PostToolUse callback（hooks.ts:2037-2052）
    // ════════════════════════════════════════════════════════════════

    /**
     * Edit/Write PostToolUse 回调 · 读文件新内容 vs 内容缓存 oldContent → diff → tracker 累加。
     *
     * <p>CC 语义：attribution hooks 是 internal callback，return {}（hooks.ts:2038），
     * 接收 updateAttributionState 上下文更新 AppState.attribution —— Java 端等价为更新
     * {@link CommitAttributionTracker}（AppState.attribution.fileStates 的承载物）。
     * 文件读取失败 → 跳过本次追踪（不阻断工具链，CC 同 best-effort）。
     */
    private void handleFileTool(String toolName, JsonNode input) {
        String filePath = extractFilePath(toolName, input);
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        String newContent = readContent(filePath);
        if (newContent == null) {
            if (log.isDebugEnabled()) {
                log.debug("[RegisterAttributionHooks] 读取文件失败跳过追踪: tool={} path={}", toolName, filePath);
            }
            return;
        }
        String oldContent = tracker.cachedContent(filePath);
        tracker.updateCachedContent(filePath, newContent);
        tracker.trackFileModification(filePath,
            oldContent != null ? oldContent : "", newContent, System.currentTimeMillis());
        if (log.isDebugEnabled()) {
            log.debug("[RegisterAttributionHooks] Edit/Write 追踪: tool={} path={}",
                toolName, tracker.normalizeFilePath(filePath));
        }
    }

    /** 从工具输入提取 file_path · 仅 Edit/Write（CC file_path 字段）· 其余工具 → null. */
    static String extractFilePath(String toolName, JsonNode input) {
        if (input == null) {
            return null;
        }
        switch (toolName) {
            case ToolNameConstants.FILE_EDIT_TOOL_NAME:
            case ToolNameConstants.FILE_WRITE_TOOL_NAME:
                JsonNode fp = input.get("file_path");
                return (fp != null && fp.isTextual()) ? fp.asText() : null;
            default:
                return null;
        }
    }

    /** 读文件内容（绝对路径原样，相对路径基于 repoRoot 解析）· 失败 → null. */
    private String readContent(String filePath) {
        try {
            Path p = Path.of(filePath);
            Path abs = p.isAbsolute() ? p : repoRootSupplier.get().resolve(p);
            return Files.readString(abs, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("[RegisterAttributionHooks] 读文件失败: path={} err={}", filePath, e.getMessage());
            }
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 3. attributionHooks 模块导出 · clear/caches.ts:106 / postCompactCleanup.ts:73
    // ════════════════════════════════════════════════════════════════

    /** CC original: {@code clearAttributionCaches()}（clear/caches.ts:106）. */
    public void clearAttributionCaches() {
        tracker.clearAttributionCaches();
    }

    /** CC original: {@code sweepFileContentCache()}（postCompactCleanup.ts:73）. */
    public void sweepFileContentCache() {
        tracker.sweepFileContentCache();
    }

    /** 追踪器（测试断言 fileStates 快照 / 生产 attribution 文本消费）. */
    public CommitAttributionTracker tracker() {
        return tracker;
    }
}
