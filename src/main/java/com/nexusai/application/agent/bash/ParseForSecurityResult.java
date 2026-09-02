package com.nexusai.application.agent.bash;

import java.util.List;

/**
 * AST 静态分析结果 · 对齐 CC {@code Open-ClaudeCode/src/utils/bash/ast.ts:25-46}.
 *
 * <p>CC 真源三态（ast.ts:42-46）:
 * <pre>
 *   type ParseForSecurityResult =
 *     | { kind: 'simple'; commands: SimpleCommand[] }
 *     | { kind: 'too-complex'; reason: string; nodeType?: string }
 *     | { kind: 'parse-unavailable' }
 * </pre>
 *
 * <p>Fail-closed 设计：只有 {@code Simple} 才允许下游用 argv 做路径/规则校验；
 * {@code TooComplex}（reason + nodeType 供审计）与 {@code ParseUnavailable}
 * （解析失败，调用方走保守路径）都会触发 ask 而非放行。
 */
public sealed interface ParseForSecurityResult
        permits ParseForSecurityResult.Simple,
                ParseForSecurityResult.TooComplex,
                ParseForSecurityResult.ParseUnavailable {

    /**
     * 环境变量赋值 · 对齐 CC {@code SimpleCommand.envVars: {name, value}[]}
     * （ast.ts:31-40）。
     *
     * @param name  CC original: name — 变量名（{@code VAR=x cmd} 前缀赋值）
     * @param value CC original: value — 变量值（引号已解析；含 CMDSUB_PLACEHOLDER 表示
     *             $() 输出占位）
     */
    record BashEnvVar(String name, String value) {}

    /**
     * 重定向 · 对齐 CC {@code Redirect}（ast.ts:25-29）。
     *
     * @param op     CC original: op — 操作符 {@code >}/{@code >>}/{@code <}/{@code <<}/
     *               {@code >&}/{@code >|}/{@code <&}/{@code &>}/{@code &>>}/{@code <<<}
     * @param target CC original: target — 目标（静态 word/string，引号已解析）
     * @param fd     CC original: fd — 文件描述符（{@code 2>file} → fd=2）
     */
    record BashRedirect(String op, String target, Integer fd) {}

    /**
     * 简单命令 · 对齐 CC {@code SimpleCommand}（ast.ts:31-40）。
     *
     * @param argv      CC original: argv — argv[0] 为命令名，其余为参数（引号已解析）
     * @param envVars   CC original: envVars — 前置 {@code VAR=val} 赋值（命令局部）
     * @param redirects CC original: redirects — 输入/输出重定向
     * @param text      CC original: text — 该命令的原始源码 span（供 UI 展示/规则匹配；
     *                  $VAR 已解析或含换行时由 argv 重建）
     */
    record BashSimpleCommand(List<String> argv, List<BashEnvVar> envVars,
                             List<BashRedirect> redirects, String text) {}

    /** {@code {kind: 'simple', commands: SimpleCommand[]}}（ast.ts:43）。 */
    record Simple(List<BashSimpleCommand> commands) implements ParseForSecurityResult {}

    /** {@code {kind: 'too-complex', reason: string, nodeType?: string}}（ast.ts:44）。 */
    record TooComplex(String reason, String nodeType) implements ParseForSecurityResult {}

    /** {@code {kind: 'parse-unavailable'}}（ast.ts:45）——解析器不可用，调用方走保守路径。 */
    record ParseUnavailable() implements ParseForSecurityResult {}
}
