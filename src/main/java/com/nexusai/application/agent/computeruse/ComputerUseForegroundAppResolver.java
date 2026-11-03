package com.nexusai.application.agent.computeruse;

import java.util.List;

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

    /**
     * 取当前前台应用的全部<b>可用授权键别名</b>（首个 = {@link #currentAppId()}）。
     *
     * <h2>WHY 需要多个别名（本仓 macOS 的必需修正）</h2>
     * <p>Windows 上授权键与前台判定键天然同源（都是 exe 名）⇒ 精确匹配就够。
     * <b>macOS 不是</b>：前台应用同时有<b>稳定</b>的 bundle id（{@code com.apple.finder}）
     * 和<b>随系统语言变化</b>的显示名（中文系统是「访达」，英文是 {@code Finder}）。
     * <br>⇒ 只按其中一个匹配必然在某些语言下 miss（实测：授权 {@code Finder} 后，
     * 中文系统上报「访达」不在白名单，所有输入动作被误拦）。
     * <p>⇒ 让解析器把<b>所有等价键</b>都给出来，门控（{@link ComputerUseInputGate}）
     * 用「命中任一即授权」的语义匹配。
     *
     * <p>默认实现返回 {@code [currentAppId()]}（Windows 等单键平台无需覆写）。
     *
     * @return 别名列表（<b>可能为空</b> = 取不到前台应用 ⇒ 调用方放行）
     */
    default List<String> currentAppAliases() {
        String single = currentAppId();
        return (single == null || single.isBlank()) ? List.of() : List.of(single);
    }
}
