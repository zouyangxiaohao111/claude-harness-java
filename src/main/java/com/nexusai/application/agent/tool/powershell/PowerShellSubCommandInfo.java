package com.nexusai.application.agent.tool.powershell;

/**
 * 子命令信息 · 对齐 CC {@code SubCommandInfo}（powershellPermissions.ts:519-524）。
 *
 * <p>修复 A5 旧 {@code allSubCommands()} 压平丢 statement 关联：step5 per-subcommand 循环
 * 需要 statement 追踪（fail-closed 第二段 {@code statementsSeenInLoop} 去重）与 isSafeOutput
 * 过滤（safe-output cmdlet 不独立审批）。
 *
 * @param text         子命令原始文本（CC {@code text}）
 * @param element      AST 命令元素（CC {@code element}，含 name/nameType/args/elementTypes）
 * @param statement    所属语句（CC {@code statement}，可为 null —— 未解析命令 fallback 元素）
 * @param isSafeOutput 是否安全输出 cmdlet（CC {@code isSafeOutput}，nameType!=='application'
 *                     && isSafeOutputCommand(name) && args 为空）
 */
public record PowerShellSubCommandInfo(
        String text,
        PowerShellAstService.CommandElement element,
        PowerShellAstService.Statement statement,
        boolean isSafeOutput
) {}
