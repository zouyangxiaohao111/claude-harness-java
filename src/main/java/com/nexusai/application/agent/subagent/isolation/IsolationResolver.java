package com.nexusai.application.agent.subagent.isolation;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Phase A 任务 4: 解析子 Agent 有效工作目录.
 *
 * <p>P0-1 修复后职责 (单一职责): 仅做 explicitCwd 优先级解析, 其他情况回退 userDir;
 * worktree 创建职责 100% 下沉到 {@code SubagentExecutor} Step 18 (事务性边界, Step 21 finally 清理闭环).
 * 之前双轨方案 (IsolationResolver + SubagentExecutor 各自创建 worktree) 造成路径不一致 + worktree 孤儿化泄漏.
 *
 * <p>P1 修复: 简化后删除 worktree 创建 catch 块, 不再有"异常丢失根因"问题.
 *
 * @see com.nexusai.application.agent.worktree.WorktreeCwdTracker
 * @see com.nexusai.application.agent.tool.impl.SubagentExecutor
 */
public final class IsolationResolver {

    private static final Logger log = LoggerFactory.getLogger(IsolationResolver.class);

    /** P0-1 修复后的主构造器 (Spring bean 注入 / 测试首选). */
    public IsolationResolver() {
        // 无状态: worktree 创建下沉到 SubagentExecutor Step 18
    }

    /**
     * 兼容委托构造器.
     *
     * @param worktreeService 已忽略 — P0-1 修复后不再用于创建 worktree
     * @deprecated worktreeService 不再使用; 隔离 worktree 创建由 {@code SubagentExecutor} Step 18 负责.
     */
    @Deprecated
    public IsolationResolver(WorktreeService worktreeService) {
        // P0-1 修复: 兼容签名, 忽略 worktreeService
    }

    /**
     * 解析有效工作目录.
     *
     * <p>优先级: explicitCwd (非 null) → explicitCwd.toAbsolutePath();
     *   其他 (含 isolation=worktree / remote / null) → userDir.toAbsolutePath().
     *
     * @param isolation   仅用于日志 — P0-1 修复后不再触发 worktree 创建
     * @param explicitCwd 显式 cwd (可为 null)
     * @param agent       仅用于日志 (可为 null)
     * @param userDir     回退目标; null 时兜底到 System.getProperty("user.dir")
     * @return 有效工作目录 (非 null, absolute path)
     */
    public Path resolve(String isolation, Path explicitCwd, AgentDefinition agent, Path userDir) {
        Path effectiveUserDir = userDir != null
                ? userDir
                : Path.of(System.getProperty("user.dir", "."));

        if (explicitCwd != null) {
            Path resolved = explicitCwd.toAbsolutePath();
            if (log.isInfoEnabled()) {
                log.info("[IsolationResolver] 显式 cwd 优先: cwd={} (isolation={}, agent={})",
                        resolved, isolation, agent != null ? agent.agentType() : "?");
            }
            return resolved;
        }

        // P0-1 修复: isolation=worktree 不再触发 WorktreeService.createAgentWorktree;
        //   职责下沉到 SubagentExecutor Step 18. 此处只回退 userDir + 输出可观测日志.
        Path resolved = effectiveUserDir.toAbsolutePath();
        if (log.isDebugEnabled()) {
            log.debug("[IsolationResolver] 回退 userDir: cwd={} (isolation={}, agent={}, P0-1 修复后 worktree 由 SubagentExecutor 负责)",
                    resolved, isolation, agent != null ? agent.agentType() : "?");
        }
        return resolved;
    }
}
