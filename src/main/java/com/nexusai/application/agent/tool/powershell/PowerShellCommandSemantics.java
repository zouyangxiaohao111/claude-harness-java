package com.nexusai.application.agent.tool.powershell;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PowerShell 命令退出码语义解释器 · 对齐 CC {@code tools/PowerShellTool/commandSemantics.ts}（真源）。
 *
 * <p><b>WHY（与 Bash CommandSemanticsInterpreter 不同）</b>：PowerShell 原生 cmdlet
 * （Select-String / Compare-Object / Test-Path）失败经终止错误 {@code $?} 而非退出码信号，
 * 无退出码语义；但<b>外部可执行文件</b>（grep.exe / rg.exe / findstr.exe / robocopy.exe）
 * 从 PowerShell 调用时会设置 {@code $LASTEXITCODE}，且大量用非零码表达「信息」而非「失败」——
 * 无此模块时任何非零码都抛 ShellError，把 {@code robocopy} 的「文件复制成功（exit 1）」误判为错误。
 *
 * <p>语义表（CC commandSemantics.ts:32-94）：
 * <ul>
 *   <li><b>DEFAULT_SEMANTIC</b>：isError = exitCode != 0，message = "Command failed with exit code N"（:32-36）</li>
 *   <li><b>GREP_SEMANTIC</b>（grep / rg / findstr）：isError = exitCode &gt;= 2，exitCode==1 → "No matches found"（:41-44）</li>
 *   <li><b>robocopy 位域</b>（:80-93）：isError = exitCode &gt;= 8；exitCode==0 → "No files copied (already in sync)"；
 *       1..7 → {@code exitCode & 1} ? "Files copied successfully" : "Robocopy completed (no errors)"</li>
 * </ul>
 *
 * <p><b>有意省略</b>（CC :50-61 注释，PS 别名歧义不可靠解释）：diff（Compare-Object vs diff.exe）、
 * fc（Format-Custom vs fc.exe）、find（Windows find.exe vs Unix find.exe）、test/[（非 PS 构造）、
 * select-string/compare-object/test-path（原生 cmdlet 恒 exit 0）。
 *
 * <p><b>extractBaseCommand</b>（CC :100-111）：剥 {@code ^[&.]\s+} 调用操作符 → 首 token → 去引号
 * → 路径 basename → lowercase → 去 {@code .exe}。{@code heuristicallyExtractBaseCommand}（:121-125）
 * 按 {@code [;|]} 切段取末段（仅退出码解释用，非安全用途，误判只回退 DEFAULT）。
 */
public final class PowerShellCommandSemantics {

    private static final Logger log = LoggerFactory.getLogger(PowerShellCommandSemantics.class);

    /** CC CommandSemantic — 解释 (exitCode, stdout, stderr) 为 {isError, message?}。 */
    @FunctionalInterface
    public interface CommandSemantic {
        Result apply(int exitCode, String stdout, String stderr);
    }

    /** CC result（commandSemantics.ts:20-27）。 */
    public record Result(boolean isError, String message) {}

    /** CC DEFAULT_SEMANTIC（:32-36）— 仅 0 是 success。 */
    public static final CommandSemantic DEFAULT_SEMANTIC = (exitCode, stdout, stderr) ->
        new Result(exitCode != 0,
            exitCode != 0 ? "Command failed with exit code " + exitCode : null);

    /** CC GREP_SEMANTIC（:41-44）— grep/rg/findstr：0=命中，1=无匹配，2+=错误。 */
    public static final CommandSemantic GREP_SEMANTIC = (exitCode, stdout, stderr) ->
        new Result(exitCode >= 2, exitCode == 1 ? "No matches found" : null);

    /** CC COMMAND_SEMANTICS 表（:62-94）· keys 小写且无 .exe 后缀。 */
    private static final Map<String, CommandSemantic> COMMAND_SEMANTICS = new LinkedHashMap<>();
    static {
        COMMAND_SEMANTICS.put("grep", GREP_SEMANTIC);
        COMMAND_SEMANTICS.put("rg", GREP_SEMANTIC);
        COMMAND_SEMANTICS.put("findstr", GREP_SEMANTIC);
        // robocopy 位域（CC :80-93）：0-7 success，8+ error（16=严重错误）
        COMMAND_SEMANTICS.put("robocopy", (exitCode, stdout, stderr) -> {
            if (exitCode >= 8) {
                return new Result(true, null);
            }
            if (exitCode == 0) {
                return new Result(false, "No files copied (already in sync)");
            }
            return new Result(false,
                (exitCode & 1) != 0 ? "Files copied successfully" : "Robocopy completed (no errors)");
        });
    }

    /**
     * CC interpretCommandResult（commandSemantics.ts:130-142）· 入口。
     *
     * @param command  原始 PowerShell 命令（CC 第一参 input.command）
     * @param exitCode 进程退出码（CC result.code）
     * @param stdout   stdout（CC result.stdout）
     * @param stderr   stderr（CC result.stderr）
     * @return {isError, message?}；message 为 null 表示无特殊语义
     */
    public Result interpretCommandResult(String command, int exitCode, String stdout, String stderr) {
        String baseCommand = heuristicallyExtractBaseCommand(command);
        CommandSemantic semantic = COMMAND_SEMANTICS.getOrDefault(baseCommand, DEFAULT_SEMANTIC);
        Result r = semantic.apply(exitCode, stdout, stderr);
        if (log.isDebugEnabled()) {
            log.debug("PowerShellCommandSemantics: command={} baseCommand={} exitCode={} isError={} message={}",
                command == null ? "" : command, baseCommand, exitCode, r.isError(), r.message());
        }
        return r;
    }

    /**
     * CC heuristicallyExtractBaseCommand（:121-125）· 按 {@code [;|]} 切段取末段。
     * <p>仅退出码解释用；引号/复杂构造可能误切，误判只回退 DEFAULT（非安全用途）。
     */
    public static String heuristicallyExtractBaseCommand(String command) {
        if (command == null) {
            return "";
        }
        String[] segments = command.split("[;|]");
        String last = command;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].trim().isEmpty()) {
                last = segments[i];
                break;
            }
        }
        return extractBaseCommand(last);
    }

    /**
     * CC extractBaseCommand（:100-111）· 剥调用操作符/引号/路径/.exe 后缀，lowercase。
     *
     * <p>{@code & "grep.exe"} → grep；{@code C:\bin\rg.exe} → rg；{@code .\findstr.exe} → findstr。
     */
    public static String extractBaseCommand(String segment) {
        if (segment == null) {
            return "";
        }
        // 剥 ^[&.]\s+ 调用操作符（CC :103）
        String stripped = segment.trim().replaceFirst("^[&.]\\s+", "");
        // 首 token（CC :104）
        String[] parts = stripped.split("\\s+");
        String firstToken = parts.length == 0 ? "" : parts[0];
        // 去引号（CC :106 & "grep.exe"）
        String unquoted = firstToken.replaceAll("^['\"]|['\"]$", "");
        // 路径 basename（CC :108 C:\bin\grep.exe → grep.exe）
        int lastBackslash = unquoted.lastIndexOf('\\');
        int lastSlash = unquoted.lastIndexOf('/');
        int cut = Math.max(lastBackslash, lastSlash);
        String basename = cut >= 0 ? unquoted.substring(cut + 1) : unquoted;
        // lowercase + 去 .exe（CC :110 Windows 大小写不敏感）
        return basename.toLowerCase().replaceFirst("\\.exe$", "");
    }
}
