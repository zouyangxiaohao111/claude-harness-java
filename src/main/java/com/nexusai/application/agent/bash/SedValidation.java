package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * sed 命令安全校验 · 对齐 CC tools/BashTool/sedValidation.ts.
 *
 * <p>L1 语义: sed 命令 allowlist 校验 — 严格接受 (Pattern 1) 行打印 (-n) 与
 *            (Pattern 2) 替换 (s/pattern/replacement/flags) 命令; 默认拒绝文件参数与 -i;
 *            allowFileWrites=true 时允许 -i 与文件参数; 含 w/W/e/E/y 危险操作 → 拒绝;
 *            checkSedConstraints 跨切面校验 (sed 命令 → 'ask' 或 'passthrough' PermissionResult).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: isLinePrintingCommand / isPrintCommand / sedCommandIsAllowedByAllowlist /
 *       hasFileArgs / extractSedExpressions / checkSedConstraints;
 *       PATTERN1_FLAGS (-n/-E/-r/-z + long) / PATTERN2_FLAGS_BASE (-E/-r) / PATTERN2_FLAGS_WRITE (-i).
 *       PRINT_PATTERN = /^(?:\d+|\d+,\d+)?p$/.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — "sed -n '1p' file" → isLinePrintingCommand=true;
 *       "sed 's/foo/bar/g'" → isSubstitutionCommand=true (allowFileWrites=false → file 缺失 OK);
 *       "sed -i 's/foo/bar/' file" → allowFileWrites=true → true;
 *       "sed 'w file'" → false (denylist);
 *       checkSedConstraints("sed 'w file'", {mode:'default'}) → 'ask';
 *       checkSedConstraints("ls -la", ctx) → 'passthrough'.</li>
 *   <li><b>A3</b>: 状态 — sedMatch=false → false; print pattern 不匹配 → false;
 *       -n flag 缺失 → false; expressions 为空 → false; semicolon 仅 Pattern 1 允许.</li>
 *   <li><b>A4</b>: command=null → false; empty cmd → false; 非法 flag (-f/-i 在只读) → false;
 *       危险 -ew 组合 → throw; malformed shell → false.</li>
 *   <li><b>A5</b>: 真实场景 — BashTool 'sed -n 1p access.log' → ALLOWED (read log 行);
 *       Edit 模式 'sed -i s/foo/bar/ config.yaml' → ALLOWED (in-place edit);
 *       危险 'sed s/foo/bar/w secret' → REJECTED (写文件).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS regex match → Java Pattern;
 *                    TS object spread allowlist → Java List/Set + contains;
 *                    TS throw → Java throw IllegalArgumentException;
 *                    TS shell-quote parse → 简化: split on whitespace (test 注入).
 */
public final class SedValidation {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SedValidation.class);

    public static final Pattern PRINT_PATTERN = Pattern.compile("^(?:\\d+|\\d+,\\d+)?p$");
    public static final Pattern SED_PREFIX = Pattern.compile("^\\s*sed\\s+");

    public static final List<String> PATTERN1_FLAGS = List.of(
        "-n", "--quiet", "--silent", "-E", "--regexp-extended", "-r", "-z", "--zero-terminated", "--posix");
    public static final List<String> PATTERN2_FLAGS_BASE = List.of(
        "-E", "--regexp-extended", "-r", "--posix");
    public static final List<String> PATTERN2_FLAGS_WRITE = List.of("-i", "--in-place");

    public static final String DANGEROUS_COMBINATION = "(-e[wWe]|-w[eE])";

    public record SedExpressionParse(List<String> expressions) {}

    /** CC isPrintCommand — 严格匹配 p / Np / N,Mp. */
    public static boolean isPrintCommand(String cmd) {
        if (cmd == null || cmd.isBlank()) return false;
        return PRINT_PATTERN.matcher(cmd).matches();
    }

    /** CC validateFlagsAgainstAllowlist — 支持 combined flags (-nE) + 长 flags. */
    public static boolean validateFlagsAgainstAllowlist(List<String> flags, List<String> allowedFlags) {
        if (flags == null) return true;
        for (String flag : flags) {
            if (flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                // Combined flags (e.g., -nE)
                for (int i = 1; i < flag.length(); i++) {
                    if (!allowedFlags.contains("-" + flag.charAt(i))) return false;
                }
            } else {
                if (!allowedFlags.contains(flag)) return false;
            }
        }
        return true;
    }

    /** CC isLinePrintingCommand — sed -n 'Np' 形式. */
    public static boolean isLinePrintingCommand(String command, List<String> expressions) {
        if (command == null || !SED_PREFIX.matcher(command).find()) return false;
        if (expressions == null || expressions.isEmpty()) return false;
        // Parse flags from simple split (CC 用 tryParseShellCommand 复杂解析)
        List<String> tokens = simpleShellSplit(command);
        List<String> flags = new ArrayList<>();
        for (String t : tokens) {
            if (t.startsWith("-") && !"--".equals(t)) flags.add(t);
        }
        if (!validateFlagsAgainstAllowlist(flags, PATTERN1_FLAGS)) return false;
        // -n 必含
        boolean hasN = false;
        for (String flag : flags) {
            if ("-n".equals(flag) || "--quiet".equals(flag) || "--silent".equals(flag)) {
                hasN = true; break;
            }
            if (flag.startsWith("-") && !flag.startsWith("--") && flag.contains("n")) {
                hasN = true; break;
            }
        }
        if (!hasN) return false;
        // expressions 全部 p 命令
        for (String expr : expressions) {
            for (String sub : expr.split(";")) {
                if (!isPrintCommand(sub.trim())) return false;
            }
        }
        return true;
    }

    /** CC sedCommandIsAllowedByAllowlist — Pattern 1 OR Pattern 2 + denylist. */
    public static boolean sedCommandIsAllowedByAllowlist(String command, boolean allowFileWrites) {
        if (command == null) return false;
        List<String> expressions;
        try {
            expressions = extractSedExpressions(command);
        } catch (Exception ex) {
            return false;
        }
        if (expressions.isEmpty()) return false;
        boolean hasFileArgs = hasFileArgs(command);
        boolean p1 = false, p2 = false;
        if (allowFileWrites) {
            p2 = isSubstitutionCommand(command, expressions, hasFileArgs, true);
        } else {
            p1 = isLinePrintingCommand(command, expressions);
            p2 = isSubstitutionCommand(command, expressions, hasFileArgs, false);
        }
        if (!p1 && !p2) return false;
        // Pattern 2 不允许 semicolon
        if (p2) {
            for (String expr : expressions) {
                if (expr.contains(";")) return false;
            }
        }
        // Defense-in-depth denylist
        for (String expr : expressions) {
            if (containsDangerousOperations(expr)) return false;
        }
        return true;
    }

    /** CC hasFileArgs — 简化版 split + 启发式 (跳过 sed 命令名). */
    public static boolean hasFileArgs(String command) {
        if (command == null || !SED_PREFIX.matcher(command).find()) return false;
        var m = SED_PREFIX.matcher(command);
        m.find();
        String withoutSed = command.substring(m.end());
        List<String> tokens = simpleShellSplit(withoutSed);
        boolean hasE = false;
        int argCount = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.startsWith("-")) {
                if (("-e".equals(t) || "--expression".equals(t)) && i + 1 < tokens.size()) {
                    hasE = true; i++;
                }
                continue;
            }
            argCount++;
            if (hasE) return true;
            if (argCount > 1) return true;
        }
        return false;
    }

    /** CC extractSedExpressions. */
    public static List<String> extractSedExpressions(String command) {
        if (command == null) return Collections.emptyList();
        var m = SED_PREFIX.matcher(command);
        if (!m.find()) return Collections.emptyList();
        String withoutSed = command.substring(m.end());
        if (withoutSed.matches(".*" + DANGEROUS_COMBINATION + ".*")) {
            throw new IllegalArgumentException("Dangerous flag combination detected");
        }
        List<String> tokens = simpleShellSplit(withoutSed);
        List<String> expressions = new ArrayList<>();
        boolean foundE = false, foundExpr = false;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (("-e".equals(t) || "--expression".equals(t)) && i + 1 < tokens.size()) {
                foundE = true; expressions.add(tokens.get(i + 1)); i++;
                continue;
            }
            if (t.startsWith("--expression=")) {
                foundE = true; expressions.add(t.substring("--expression=".length()));
                continue;
            }
            if (t.startsWith("-e=")) {
                foundE = true; expressions.add(t.substring(3));
                continue;
            }
            if (t.startsWith("-")) continue;
            if (!foundE && !foundExpr) {
                expressions.add(t); foundExpr = true;
                continue;
            }
            break;
        }
        return expressions;
    }

    /** CC isSubstitutionCommand — Pattern 2. */
    public static boolean isSubstitutionCommand(String command, List<String> expressions,
            boolean hasFileArguments, boolean allowFileWrites) {
        if (!allowFileWrites && hasFileArguments) return false;
        if (command == null || !SED_PREFIX.matcher(command).find()) return false;
        if (expressions == null || expressions.size() != 1) return false;
        List<String> tokens = simpleShellSplit(command);
        List<String> flags = new ArrayList<>();
        for (String t : tokens) {
            if (t.startsWith("-") && !"--".equals(t)) flags.add(t);
        }
        List<String> allowed = new ArrayList<>(PATTERN2_FLAGS_BASE);
        if (allowFileWrites) allowed.addAll(PATTERN2_FLAGS_WRITE);
        if (!validateFlagsAgainstAllowlist(flags, allowed)) return false;
        String expr = expressions.get(0).trim();
        if (!expr.startsWith("s")) return false;
        if (!expr.startsWith("s/")) return false;
        String rest = expr.substring(2);
        // 找 / 分隔符 (跳过反斜杠转义)
        int count = 0, lastPos = -1;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '/') { count++; lastPos = i; }
        }
        if (count != 2) return false;
        String exprFlags = rest.substring(lastPos + 1);
        return exprFlags.matches("^[gpimIM]*[1-9]?[gpimIM]*$");
    }

    /** CC containsDangerousOperations — denylist（sedValidation.ts:473-629 全量边界正则）. */
    public static boolean containsDangerousOperations(String expression) {
        if (expression == null) return false;
        String cmd = expression.trim();
        if (cmd.isEmpty()) return false;
        if (cmd.matches(".*[^\\x01-\\x7F].*")) return true; // non-ASCII
        if (cmd.contains("{") || cmd.contains("}")) return true;
        if (cmd.contains("\n")) return true;
        // # comment 但不是 s# 分隔符
        int hashIdx = cmd.indexOf('#');
        if (hashIdx >= 0 && !(hashIdx > 0 && cmd.charAt(hashIdx - 1) == 's')) return true;
        // negation
        if (cmd.startsWith("!") || cmd.matches(".*[/\\d$]!.*")) return true;
        // tilde in GNU step address
        if (cmd.matches(".*\\d\\s*~\\s*\\d.*")
            || cmd.matches(".*,\\s*~\\s*\\d.*")
            || cmd.matches(".*\\$\\s*~\\s*\\d.*")) return true;
        if (cmd.startsWith(",")) return true;
        if (cmd.matches(".*,\\s*[+-].*")) return true;
        if (cmd.matches(".*s\\\\.*") || cmd.matches(".*\\\\[|#%@].*")) return true;
        // escaped slashes followed by w/W（sedValidation.ts:537-539）
        if (cmd.matches(".*\\\\/.*[wW].*")) return true;
        // slash → 非 slash chars → 空白 → wWeE（sedValidation.ts:544-546）
        if (cmd.matches(".*/[^/]*\\s+[wWeE].*")) return true;
        // malformed substitution（sedValidation.ts:550-552）
        if (cmd.startsWith("s/") && !cmd.matches("^s/[^/]*/[^/]*/[^/]*$")) return true;
        // paranoid s-command ending in dangerous char（sedValidation.ts:557-563）
        if (cmd.matches("^s.") && cmd.matches(".*[wWeE]$")
            && !cmd.matches("^s([^\\\\\\n]).*?\\1.*?\\1[^wWeE]*$")) return true;
        // y command followed by w/W/e/E
        if (cmd.matches(".*y([^\\\\\\n]).*") && cmd.matches(".*[wWeE].*")) return true;
        // standalone w/W/e/E commands at start (POSIX) + pattern/range variants（sedValidation.ts:569-595）
        if (cmd.matches("^[wW]\\s*\\S+.*")) return true;
        if (cmd.matches("^\\d+\\s*[wW]\\s*\\S+.*")) return true;
        if (cmd.matches("^\\$\\s*[wW]\\s*\\S+.*")) return true;
        if (cmd.matches("^/[^/]*/[IMim]*\\s*[wW]\\s*\\S+.*")) return true;
        if (cmd.matches("^\\d+,\\d+\\s*[wW]\\s*\\S+.*")) return true;
        if (cmd.matches("^\\d+,\\$\\s*[wW]\\s*\\S+.*")) return true;
        if (cmd.matches("^/[^/]*/[IMim]*,[^/]*/[IMim]*\\s*[wW]\\s*\\S+.*")) return true;
        if (cmd.matches("^e.*")) return true;
        if (cmd.matches("^\\d+\\s*e.*")) return true;
        if (cmd.matches("^\\$\\s*e.*")) return true;
        if (cmd.matches("^/[^/]*/[IMim]*\\s*e.*")) return true;
        if (cmd.matches("^\\d+,\\d+\\s*e.*")) return true;
        if (cmd.matches("^\\d+,\\$\\s*e.*")) return true;
        if (cmd.matches("^/[^/]*/[IMim]*,[^/]*/[IMim]*\\s*e.*")) return true;
        // substitution with dangerous flags
        var sm = Pattern.compile("s([^\\\\\\n]).*?\\1.*?\\1(.*?)$").matcher(cmd);
        if (sm.find()) {
            String flags = sm.group(2) == null ? "" : sm.group(2);
            if (flags.contains("w") || flags.contains("W")) return true;
            if (flags.contains("e") || flags.contains("E")) return true;
        }
        return false;
    }

    /** CC checkSedConstraints — 跨切面校验（返回 permission.PermissionResult）. */
    public static PermissionResult checkSedConstraints(String command, String permissionMode) {
        boolean allowFileWrites = "acceptEdits".equals(permissionMode);
        // CC splitCommand_DEPRECATED（commands.ts:265，COMMAND_LIST_SEPARATORS:523 含 && || ; ;; |）。
        // 复用 BashParser.splitCommands（引号/子shell/heredoc 深度感知），与 BashModeValidation:125 同款
        // 等价实现。旧的简化 split 仅切顶层 ;| 不切 && → "echo hi && sed 'w file'" 整串 1 段
        // baseCmd=echo 被跳过 → passthrough（deny→allow 反向安全分歧，危险 sed 写命令绕过弹窗）。
        List<String> commands = BashParser.splitCommands(command);
        for (String cmd : commands) {
            String trimmed = cmd.trim();
            if (trimmed.isEmpty()) continue;
            String baseCmd = trimmed.split("\\s+")[0];
            if (!"sed".equals(baseCmd)) continue;
            if (!sedCommandIsAllowedByAllowlist(trimmed, allowFileWrites)) {
                // ask + decisionReason {type:'other', reason}（CC sedValidation.ts:666-677）
                String reason = "sed command contains operations that require explicit approval "
                    + "(e.g., write commands, execute commands)";
                if (log.isDebugEnabled()) {
                    log.debug("sed 命令命中危险操作白名单拦截, 返回 ask: [{}]", trimmed);
                }
                return new PermissionResult.Ask(
                    "sed command requires approval (contains potentially dangerous operations)",
                    new PermissionDecisionReason.Other(reason),
                    List.of(), null, null, null, false, null, List.of());
            }
        }
        // passthrough（CC sedValidation.ts:679-684）
        if (log.isDebugEnabled()) {
            log.debug("sed 约束校验通过, 返回 passthrough: [{}]", command);
        }
        return new PermissionResult.Passthrough("No dangerous sed operations detected",
            null, List.of(), null, null);
    }

    // ---- internals ----

    /** 简化版 shell-quote 解析 · 对齐 CC {@code tryParseShellCommand}
     *  （sedValidation.ts:52/159/312/403）。引号感知 + 反斜杠转义。 */
    private static List<String> simpleShellSplit(String s) {
        if (s == null || s.isBlank()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        boolean inQuote = false;
        char quoteChar = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar) { inQuote = false; }
                else cur.append(c);
            } else if (c == '\'' || c == '"') {
                inQuote = true; quoteChar = c;
            } else if (c == '\\' && i + 1 < s.length()) {
                // 未引号反斜杠转义：保留转义序列（CC shell-quote 保留为单 token）
                cur.append(c).append(s.charAt(++i));
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { result.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) result.add(cur.toString());
        return result;
    }

    /** CC extractSedExpressions — 公开版 (测试用). */
    public static List<String> extractExpressions(String command) {
        return extractSedExpressions(command);
    }

    private SedValidation() {}
}