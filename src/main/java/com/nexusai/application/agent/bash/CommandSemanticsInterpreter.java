package com.nexusai.application.agent.bash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 命令语义解释器 · 对齐 CC tools/BashTool/commandSemantics.ts.
 *
 * <p>L1 语义: 根据命令名解释退出码. 多数命令 0=success;但 grep/rg/find/diff/test/[ 用
 *            1 表示 "无匹配/有差异/条件 false" (非错误).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 6 command semantics (grep/rg/find/diff/test/[/);
 *       DEFAULT_SEMANTIC (0 以外都是 error);
 *       interpretCommandResult(command, exit, stdout, stderr) → {isError, message?}.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — extract base command → lookup semantic → apply (exit, stdout, stderr) →
 *       返回 {isError, message?}.</li>
 *   <li><b>A3</b>: 状态: NOT_FOUND (用 DEFAULT) / FOUND (specific semantic);
 *       grep 1 → not error;grep 2 → error;diff 1 → "Files differ" (not error).</li>
 *   <li><b>A4</b>: 未识别命令 → DEFAULT (0=ok, 其他=error);
 *       pipe 链 → 取最后一段作为 base command;
 *       空 stdout → 不影响 isError (只看 exit code).</li>
 *   <li><b>A5</b>: 真实场景 — `grep "foo" file.txt` 退出 1 → "No matches found" (非 error);
 *       `diff a b` 退出 1 → "Files differ" (非 error);
 *       `git status` 退出 0 → ok (default semantic).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `splitCommand_DEPRECATED` → 注入式 CommandSplitter (testable);
 *                    TS Map&lt;string, CommandSemantic&gt; → Java Map;
 *                    TS `(exit, stdout, stderr) => Result` → Java Function.
 */
public final class CommandSemanticsInterpreter {

    private static final Logger log = LoggerFactory.getLogger(CommandSemanticsInterpreter.class);

    /** CC CommandSemantic — 解释 (exit, stdout, stderr) 为 {isError, message?}. */
    @FunctionalInterface
    public interface CommandSemantic {
        Result apply(int exitCode, String stdout, String stderr);
    }

    /** CC result. */
    public record Result(boolean isError, String message) {}

    /** CC DEFAULT_SEMANTIC — 只 0 是 success. */
    public static final CommandSemantic DEFAULT_SEMANTIC = (exitCode, stdout, stderr) ->
        new Result(exitCode != 0,
            exitCode != 0 ? "Command failed with exit code " + exitCode : null);

    private static final Map<String, CommandSemantic> COMMAND_SEMANTICS = new LinkedHashMap<>();
    static {
        // grep: 0=matches, 1=no matches, 2+=error
        COMMAND_SEMANTICS.put("grep", (exit, out, err) -> new Result(
            exit >= 2, exit == 1 ? "No matches found" : null));
        // rg: same as grep
        COMMAND_SEMANTICS.put("rg", (exit, out, err) -> new Result(
            exit >= 2, exit == 1 ? "No matches found" : null));
        // find: 0=success, 1=partial (some dirs inaccessible), 2+=error
        COMMAND_SEMANTICS.put("find", (exit, out, err) -> new Result(
            exit >= 2, exit == 1 ? "Some directories were inaccessible" : null));
        // diff: 0=no diff, 1=diff, 2+=error
        COMMAND_SEMANTICS.put("diff", (exit, out, err) -> new Result(
            exit >= 2, exit == 1 ? "Files differ" : null));
        // test/[: 0=true, 1=false, 2+=error
        COMMAND_SEMANTICS.put("test", (exit, out, err) -> new Result(
            exit >= 2, exit == 1 ? "Condition is false" : null));
        COMMAND_SEMANTICS.put("[", (exit, out, err) -> new Result(
            exit >= 2, exit == 1 ? "Condition is false" : null));
    }

    private final Function<String, java.util.List<String>> commandSplitter;

    /**
     * 注入式 CommandSplitter 构造器 · CC 恒走 {@code splitCommand_DEPRECATED}（commandSemantics.ts:113）
     * 无 no-split 路径 —— 旧默认构造器 {@code List.of(s)} 不切管道，属 CC-deviant 双轨，已删除。
     * 生产接线 BashTool.java:126 注入 {@code BashParser::splitCommands}。
     *
     * @param commandSplitter 命令分段器（splitCommand_DEPRECATED 的 Java 对等注入）
     */
    public CommandSemanticsInterpreter(Function<String, java.util.List<String>> commandSplitter) {
        this.commandSplitter = commandSplitter;
    }

    /** CC getCommandSemantic — 主链. */
    public CommandSemantic getCommandSemantic(String command) {
        String baseCommand = heuristicallyExtractBaseCommand(command);
        CommandSemantic semantic = COMMAND_SEMANTICS.get(baseCommand);
        return semantic != null ? semantic : DEFAULT_SEMANTIC;
    }

    /** CC interpretCommandResult — 入口. */
    public Result interpretCommandResult(String command, int exitCode, String stdout, String stderr) {
        CommandSemantic semantic = getCommandSemantic(command);
        return semantic.apply(exitCode, stdout, stderr);
    }

    /** CC heuristicallyExtractBaseCommand — pipe 链取最后一段. */
    String heuristicallyExtractBaseCommand(String command) {
        java.util.List<String> segments = commandSplitter.apply(command);
        String lastCommand = segments.isEmpty() ? command : segments.get(segments.size() - 1);
        return extractBaseCommand(lastCommand);
    }

    /** CC extractBaseCommand — first word. */
    static String extractBaseCommand(String command) {
        if (command == null || command.isEmpty()) return "";
        String trimmed = command.trim();
        return trimmed.split("\\s+", 2)[0];
    }
}
