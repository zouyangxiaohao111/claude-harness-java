package com.nexusai.application.agent.bash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DestructiveCommandWarning · 对齐 CC tools/BashTool/destructiveCommandWarning.ts.
 *
 * <p>L1 语义: 检测可能有破坏性的 bash 命令,返回人类可读警告字符串用于权限对话框显示。
 * 纯信息展示 — 不影响权限逻辑或 auto-approval。
 * 涵盖 4 大类: git 数据丢失 / git 安全绕过 / 文件递归删除 / 数据库 + 基础设施。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #getDestructiveCommandWarning(String)} (command) → String|null;
 *       16 pattern/warning pair (List&lt;DestructivePattern&gt;)</li>
 *   <li><b>A2 Golden Trace</b>: {@code git reset --hard} → 'discard uncommitted changes';
 *       {@code git push --force} → 'overwrite remote history';{@code rm -rf} → 'recursively force-remove'</li>
 *   <li><b>A3 纯函数</b>: 无副作用;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: null/空 command → null;无害 command → null</li>
 *   <li><b>A5 业务场景</b>: BashTool UI 渲染警告时调用 {@code getDestructiveCommandWarning};不修改 permission 流程</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS regex literal → Java {@link Pattern#compile};
 * TS ordered array → Java {@code List<DestructivePattern>};
 * TS loop-break-on-first-match → Java for-loop early return。
 */
public final class DestructiveCommandWarning {

    private static final Logger log = LoggerFactory.getLogger(DestructiveCommandWarning.class);

    public record DestructivePattern(Pattern pattern, String warning) {}

    private static final List<DestructivePattern> DESTRUCTIVE_PATTERNS = List.of(
        // Git — data loss / hard to reverse
        new DestructivePattern(Pattern.compile("\\bgit\\s+reset\\s+--hard\\b"),
            "Note: may discard uncommitted changes"),
        new DestructivePattern(Pattern.compile("\\bgit\\s+push\\b[^;&|\\n]*[ \\t](--force|--force-with-lease|-f)\\b"),
            "Note: may overwrite remote history"),
        new DestructivePattern(Pattern.compile(
            "\\bgit\\s+clean\\b(?![^;&|\\n]*(?:-[a-zA-Z]*n|--dry-run))[^;&|\\n]*-[a-zA-Z]*f"),
            "Note: may permanently delete untracked files"),
        new DestructivePattern(Pattern.compile("\\bgit\\s+checkout\\s+(--\\s+)?\\.[ \\t]*($|[;&|\\n])"),
            "Note: may discard all working tree changes"),
        new DestructivePattern(Pattern.compile("\\bgit\\s+restore\\s+(--\\s+)?\\.[ \\t]*($|[;&|\\n])"),
            "Note: may discard all working tree changes"),
        new DestructivePattern(Pattern.compile("\\bgit\\s+stash[ \\t]+(drop|clear)\\b"),
            "Note: may permanently remove stashed changes"),
        new DestructivePattern(Pattern.compile(
            "\\bgit\\s+branch\\s+(-D[ \\t]|--delete\\s+--force|--force\\s+--delete)\\b"),
            "Note: may force-delete a branch"),

        // Git — safety bypass
        new DestructivePattern(Pattern.compile(
            "\\bgit\\s+(commit|push|merge)\\b[^;&|\\n]*--no-verify\\b"),
            "Note: may skip safety hooks"),
        new DestructivePattern(Pattern.compile("\\bgit\\s+commit\\b[^;&|\\n]*--amend\\b"),
            "Note: may rewrite the last commit"),

        // File deletion (dangerous paths already handled by checkDangerousRemovalPaths)
        // CC pattern @ destructiveCommandWarning.ts:58：第二分支尾无 '/'，
        // 旧 Java 实现尾带 '/' 导致 "rm -fr <target>"（-f 在前、-r 在后）漏告警。
        new DestructivePattern(Pattern.compile(
            "(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*[rR][a-zA-Z]*f|(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*f[a-zA-Z]*[rR]"),
            "Note: may recursively force-remove files"),
        new DestructivePattern(Pattern.compile("(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*[rR]"),
            "Note: may recursively remove files"),
        new DestructivePattern(Pattern.compile("(^|[;&|\\n]\\s*)rm\\s+-[a-zA-Z]*f"),
            "Note: may force-remove files"),

        // Database
        // [IMP-H R2] CC destructiveCommandWarning.ts:72/:76 均带 /i 大小写不敏感标志 —— Java 必须
        // 补 Pattern.CASE_INSENSITIVE，否则小写 'drop table'/'truncate schema'/'delete from x' 不告警
        // （G21 接线后输出在权限弹窗可见，大小写敏感属 P0 漏报）。
        new DestructivePattern(Pattern.compile("\\b(DROP|TRUNCATE)\\s+(TABLE|DATABASE|SCHEMA)\\b",
                Pattern.CASE_INSENSITIVE),
            "Note: may drop or truncate database objects"),
        new DestructivePattern(Pattern.compile("\\bDELETE\\s+FROM\\s+\\w+[ \\t]*(;|\"|'|\\n|$)",
                Pattern.CASE_INSENSITIVE),
            "Note: may delete all rows from a database table"),

        // Infrastructure
        new DestructivePattern(Pattern.compile("\\bkubectl\\s+delete\\b"),
            "Note: may delete Kubernetes resources"),
        new DestructivePattern(Pattern.compile("\\bterraform\\s+destroy\\b"),
            "Note: may destroy Terraform infrastructure")
    );

    private DestructiveCommandWarning() {}

    /**
     * Returns the first matching destructive pattern warning, or null if no
     * dangerous pattern is detected. Pure function.
     */
    public static String getDestructiveCommandWarning(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        for (DestructivePattern p : DESTRUCTIVE_PATTERNS) {
            if (p.pattern().matcher(command).find()) {
                return p.warning();
            }
        }
        return null;
    }

    /** Internal test access to all registered patterns. */
    static List<DestructivePattern> allPatterns() {
        return new ArrayList<>(DESTRUCTIVE_PATTERNS);
    }
}
