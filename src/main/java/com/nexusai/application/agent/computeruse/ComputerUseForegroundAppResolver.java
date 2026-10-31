package com.nexusai.application.agent.computeruse;

/**
 * 前台应用解析器 · 供 computer-use 的<b>档位门控</b>判定「当前动作打向哪个应用」。
 *
 * <h2>CCB 对应物</h2>
 * <p>CCB 用 {@code adapter.executor.getFrontmostApp()}
 * （{@code packages/@ant/computer-use-mcp/src/toolCalls.ts:480}）拿前台应用，再按
 * {@code tierSatisfies(frontmostTier, actionKind)} 放行 / 拒绝。
 * <p>CCB 在 Windows 上用 PowerShell 取前台窗口的 exe 路径与进程名
 * （{@code src/utils/computerUse/platforms/win32.ts:757-771} 的 {@code getFrontmostApp}）。
 *
 * <h2>本仓的键归一</h2>
 * <p>解析结果统一为<b>归一后的 exe 名</b>（{@link ComputerUseAppPolicy#normalizeAppKey}）——
 * 与授权列 {@code sessions.computer_use_grants} 的 {@code appId} <b>同一命名空间</b>
 * （这正是本仓对 CCB「授权键与前台判定键不同命名空间」缺陷的修正）。
 */
public interface ComputerUseForegroundAppResolver {

    /**
     * 取当前前台应用的授权键。
     *
     * @return 归一后的 exe 名（如 {@code "chrome"}）；<b>未知 / 解析失败 ⇒ null</b>
     *         （调用方按 CCB {@code toolCalls.ts:502-506} 的语义放行：
     *         "No frontmost app (rare — login window?). Let it through."）
     */
    String currentAppId();
}
