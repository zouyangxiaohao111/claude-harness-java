package com.nexusai.application.agent.tool;

/**
 * Shell 命令失败异常 · 对齐 CC {@code Open-ClaudeCode/src/utils/errors.ts: ShellError}。
 *
 * <p>CC 真源（errors.ts 实测）：
 * <pre>{@code
 * export class ShellError extends Error {
 *   constructor(
 *     public readonly stdout: string,
 *     public readonly stderr: string,
 *     public readonly code: number,
 *     public readonly interrupted: boolean,
 *   ) {
 *     super('Shell command failed')
 *     this.name = 'ShellError'
 *   }
 * }
 * }</pre>
 *
 * <p><b>[IMP-C4 REQ-G3-2-3] 用途</b>: {@link ToolErrorFormatter#formatError} 对 ShellError 展开
 * exit code / interrupted 标记 / stderr / stdout（对齐 CC toolErrors.ts:24-32 getErrorParts）。
 * Java 端 Bash/PowerShell 执行器若以本类型抛错，formatError 输出 CC 分区文本而非
 * {@code "<Class>: <msg>"}。
 *
 * <p><b>接线边界</b>: 本类为 formatError 的展开载体；Bash/PowerShell 工具族当前经
 * ToolResult.error(data 文本) 表达 shell 失败（IMP-C2 R2「\n Exit code N」标记），
 * 是否改抛 ShellError 由 B/C 域任务按 CC BashTool.tsx 错误构造对齐后接线（登记受控残留）。
 *
 * @param stdout      CC original: stdout（errors.ts ShellError 构造器参数 1）
 * @param stderr      CC original: stderr（构造器参数 2）
 * @param code        CC original: code（退出码，构造器参数 3）
 * @param interrupted CC original: interrupted（是否被用户中断，构造器参数 4）
 */
public class ShellError extends RuntimeException {

    private final String stdout;
    private final String stderr;
    private final int code;
    private final boolean interrupted;

    /**
     * @param stdout      命令标准输出全量（可为空串）
     * @param stderr      命令标准错误全量（可为空串）
     * @param code        退出码（CC errors.ts: {@code code}）
     * @param interrupted 是否被中断（CC errors.ts: {@code interrupted}）
     */
    public ShellError(String stdout, String stderr, int code, boolean interrupted) {
        // CC: super('Shell command failed')
        super("Shell command failed");
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.code = code;
        this.interrupted = interrupted;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    public int code() {
        return code;
    }

    public boolean interrupted() {
        return interrupted;
    }
}
