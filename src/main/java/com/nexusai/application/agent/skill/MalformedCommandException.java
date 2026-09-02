package com.nexusai.application.agent.skill;

/**
 * 畸形命令异常 · 对齐 CC {@code errors.ts:10 MalformedCommandError}
 *
 * <p>CC 真源（Read 实证，不信注释）：
 * <pre>
 * export class MalformedCommandError extends Error {}   // errors.ts:10（空 Error 子类）
 * </pre>
 *
 * <p>用于 skill 内联 shell 注入（{@code !`cmd`} / {@code ```! ```}）在权限预检失败
 * / 执行失败时 fail-loud 上抛。message 承载 pattern/command/输出文案（CC
 * promptShellExecution.ts:110-112 / :175-177）：
 * <ul>
 *   <li>权限失败：{@code Shell command permission check failed for pattern "X": msg}
 *       （CC :110-112）</li>
 *   <li>执行失败：{@code Shell command failed for pattern "X": output}（CC :175-177）</li>
 *   <li>interrupted：{@code Shell command interrupted for pattern "X": [Command interrupted]}
 *       （CC :170-172）</li>
 *   <li>其他错误：{@code [Error]\nmessage}（CC :180-182）</li>
 * </ul>
 *
 * <p><b>WHY（规则十二 · 显式失败）</b>：{@code SkillToolImpl.doExecute} 显式 rethrow 本异常
 * （新增 catch 分支），否则会被泛型 {@code catch (Exception)} 吞成 {@code ToolResult.error}
 * —— 权限失败变普通技能失败 = 静默降级。上抛后由 {@code StreamingToolExecutor} 外层 catch
 * 转带 errorCategory 的 {@code ToolResult.error}，不会造成未捕获崩溃。
 */
public class MalformedCommandException extends RuntimeException {

    /**
     * @param message 承载 pattern/command/输出 的异常信息（CC original: MalformedCommandError.message）
     */
    public MalformedCommandException(String message) {
        super(message);
    }
}
