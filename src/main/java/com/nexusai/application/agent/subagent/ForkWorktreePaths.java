package com.nexusai.application.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ForkWorktreePaths · 对齐 CC Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:205-210 buildWorktreeNotice
 * + Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:591 worktree slug（R-A11）。
 *
 * <p><b>L1 语义</b>: fork 子 agent 在 isolated git worktree 运行时需要两类派生信息:
 * <ol>
 *   <li><b>路径翻译提示</b> ({@link #buildWorktreeNotice}): 子 agent 继承的对话上下文里的路径是
 *       parent cwd 相对路径, 必须翻译到 worktree cwd 才能正确读写文件 (CC forkSubagent.ts:205-210)。</li>
 *   <li><b>worktree slug</b> ({@link #buildWorktreeSlug}): 子 agent 隔离 worktree 的目录名 slug,
 *       固定 "agent-" 前缀 + agentId 前 8 位 (CC AgentTool.tsx:591 {@code `agent-${earlyAgentId.slice(0, 8)}`})。</li>
 * </ol>
 *
 * <p><b>L2 契约 (Release Gate)</b>:
 * <ul>
 *   <li><b>D1</b>: buildWorktreeNotice 输出必须包含 parentCwd + worktreeCwd 字面字符串 (CC forkSubagent.ts:209)</li>
 *   <li><b>D2</b>: 必须含 "isolated git worktree" — 显式告知子 agent 它在隔离工作区</li>
 *   <li><b>D3</b>: 必须含 "translate them to your worktree root" — 强制路径翻译</li>
 *   <li><b>D4</b>: 必须含 "Re-read files before editing" — 父可能已修改文件, 子 agent 不能信任继承上下文</li>
 *   <li><b>D5</b>: 必须含 "Your changes stay in this worktree" — 防止误改父 agent 的文件</li>
 *   <li><b>D6</b>: buildWorktreeSlug 必须固定 "agent-" 前缀 + earlyAgentId 前 8 位
 *       (CC AgentTool.tsx:591 {@code earlyAgentId.slice(0, 8)}) — 首字符恒 'a'
 *       (CC earlyAgentId=createAgentId()='a'+16hex, uuid.ts:24-27)</li>
 * </ul>
 *
 * <p><b>L3 (Java idiom)</b>: TS template literal → Java String.format / 字符串拼接。
 * 模板字符串与 CC 端字节级一致 (含逗号、句号、空白)。
 */
public final class ForkWorktreePaths {

    private static final Logger log = LoggerFactory.getLogger(ForkWorktreePaths.class);

    private ForkWorktreePaths() {}

    /**
     * 构造 isolated worktree 路径翻译提示 · 对齐 CC forkSubagent.ts:205-210.
     *
     * <p>WHY 三条信息缺一不可:
     * <ol>
     *   <li>告知子 agent 工作目录(否则改父文件)</li>
     *   <li>告知路径需要翻译(否则写错文件路径)</li>
     *   <li>告知重新读文件(否则覆盖父最新修改)</li>
     * </ol>
     *
     * @param parentCwd   CC original: parentCwd (Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:206)
     *                    父 agent 的工作目录, 子 agent 继承的对话上下文里的路径都指这里
     * @param worktreeCwd CC original: worktreeCwd (Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:207)
     *                    fork child 所在的 isolated git worktree 路径, 子 agent 应翻译到这里
     * @return worktree 路径翻译提示文本
     */
    public static String buildWorktreeNotice(String parentCwd, String worktreeCwd) {
        if (log.isDebugEnabled()) {
            log.debug("[ForkWorktreePaths] 构造 worktree 路径翻译提示, parentCwd={}, worktreeCwd={}",
                parentCwd, worktreeCwd);
        }

        // 对齐 CC forkSubagent.ts:209 — 字节级一致
        String notice = String.format(
            "You've inherited the conversation context above from a parent agent working in %s. "
                + "You are operating in an isolated git worktree at %s — same repository, "
                + "same relative file structure, separate working copy. "
                + "Paths in the inherited context refer to the parent's working directory; "
                + "translate them to your worktree root. "
                + "Re-read files before editing if the parent may have modified them since they appear in the context. "
                + "Your changes stay in this worktree and will not affect the parent's files.",
            parentCwd == null ? "" : parentCwd,
            worktreeCwd == null ? "" : worktreeCwd
        );

        return notice;
    }

    /**
     * 构造 worktree slug · 对齐 CC AgentTool.tsx:591 {@code `agent-${earlyAgentId.slice(0, 8)}`}.
     *
     * <p><b>R-A11 (A-11) WHY</b>: CC 在 spawn 前置生成 {@code earlyAgentId = createAgentId()}
     * (AgentTool.tsx:580), 以 {@code earlyAgentId.slice(0, 8)} 取前 8 位作 slug 后缀 —
     * earlyAgentId 恒为 {@code 'a'+16hex} (uuid.ts:24-27), 故 CC slug 首字符恒 'a'。
     * Java 旧实现基于 packed UUID 取 8 位 (随机 hex 首字符), 与 CC 首字符语义不同 (R3-WF-F concerns-4)。
     * 本方法以 a+16hex agentId ({@code agentIdHex}, {@code unpackAgentId} 还原的 CC earlyAgentId 等价)
     * 前 8 位对齐 CC: 无标签 {@code 'a'+16hex} → {@code slice(0,8)} = {@code 'a'+7 hex}。
     *
     * <p>注: 若传入带 label 的 {@code a{label}-{16hex}} (fork 路径不产生, Java 生产 spawn
     * createSubagentContext.java:214 无 label), 本方法与 CC 同样取前 8 字符 (含 label), 行为一致。
     *
     * @param earlyAgentId CC original: earlyAgentId (Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:580)
     *                     a+16hex agent ID (Java 侧 {@code AgentContext.unpackAgentId} 还原), 恒 17 字符 'a'+16hex
     * @return slug 形如 {@code "agent-aXXXXXXX"} (CC AgentTool.tsx:591, 首字符恒 'a')
     */
    public static String buildWorktreeSlug(String earlyAgentId) {
        if (log.isDebugEnabled()) {
            log.debug("[ForkWorktreePaths] 构造 worktree slug, earlyAgentId={} (CC AgentTool.tsx:591 agent-${earlyAgentId.slice(0,8)})",
                earlyAgentId);
        }

        // 对齐 CC AgentTool.tsx:591 — earlyAgentId.slice(0, 8)
        return "agent-" + earlyAgentId.substring(0, 8);
    }
}