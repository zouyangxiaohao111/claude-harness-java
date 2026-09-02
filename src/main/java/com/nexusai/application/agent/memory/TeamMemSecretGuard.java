package com.nexusai.application.agent.memory;

import java.util.List;

/**
 * TeamMemSecretGuard · 对齐 CC {@code Open-ClaudeCode/src/services/teamMemorySync/teamMemSecretGuard.ts}.
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：{@code checkTeamMemSecrets} teamMemSecretGuard.ts:15-44 ——
 * {@code feature('TEAMMEM')} 门控（:19）→ {@code isTeamMemPath}（:27）→ {@code scanForSecrets}（:31）→
 * 命中则返回「Content contains potential secrets (labels) and cannot be written to team memory.
 * Team memory is shared with all repository collaborators. Remove the sensitive content and try again.」
 * 错误文案（:36-41）。
 *
 * <p><b>G-65 / M-1（F1 落位）</b>：门控组合 = {@code feature('TEAMMEM') && isTeamMemPath}
 * （teamMemSecretGuard.ts:19-29，<b>不要求 auto-memory 启用</b>）。旧实现用
 * {@code isTeamMemFile}（= isTeamMemoryEnabled() && isTeamMemPath()）→ auto-memory 关闭而
 * TEAMMEM 开启时 CC 仍守卫、Java 不守卫。现改 {@link TeamMemPaths#isTeamMemFeatureEnabled()}
 * （Java 端 feature('TEAMMEM') 等价，注入 teamMemoryEnabled supplier）+ isTeamMemPath。
 *
 * <p>调用方（FileWriteTool.ts:157 / FileEditTool.ts:144 validateInput）无条件调用本方法 —— 内部
 * feature('TEAMMEM') 门控使 build flag 关闭时保持惰性（[IMP-CM-07] 生产 teamMemoryEnabled =
 * 真实 first-party OAuth 可用性判定，与 teamMemPaths bean 同源）。
 *
 * <p>DEL-M-19：旧实现有适配器接口（目标端独有），对齐 CC 直接复用
 * {@link TeamMemorySecretScanner#scanForSecrets} 模块级函数，删除适配器。
 */
public final class TeamMemSecretGuard {

    private final TeamMemPaths teamMemPaths;

    public TeamMemSecretGuard(TeamMemPaths teamMemPaths) {
        this.teamMemPaths = teamMemPaths;
    }

    /**
     * 文件写/编辑到 team memory 路径时检查是否含 secret · CC original: {@code checkTeamMemSecrets}
     * （teamMemSecretGuard.ts:15-44）。含 secret 返回错误文案，安全返回 null。
     *
     * @param filePath 归一化绝对文件路径（CC expandPath 后）
     * @param content  待写/编辑内容（Edit 为 new_string）
     */
    public String checkTeamMemSecrets(String filePath, String content) {
        // G-65：feature('TEAMMEM') && isTeamMemPath —— 不要求 isTeamMemoryEnabled()/auto-memory
        if (filePath == null
            || !teamMemPaths.isTeamMemFeatureEnabled()
            || !teamMemPaths.isTeamMemPath(filePath)) {
            return null;
        }
        if (content == null) {
            return null;
        }
        List<TeamMemorySecretScanner.SecretMatch> matches =
            TeamMemorySecretScanner.scanForSecrets(content);
        if (matches.isEmpty()) {
            return null;
        }
        String labels = matches.stream()
            .map(TeamMemorySecretScanner.SecretMatch::label)
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
        return "Content contains potential secrets (" + labels
            + ") and cannot be written to team memory. "
            + "Team memory is shared with all repository collaborators. "
            + "Remove the sensitive content and try again.";
    }
}
