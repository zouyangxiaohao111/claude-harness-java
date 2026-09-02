package com.nexusai.model.command.dto;

/**
 * /effort 执行请求体 · 对齐 CC effort.tsx call(args) 入参（commands/effort/effort.tsx:171-182）。
 *
 * @param args 斜杠命令参数字符串（low/medium/high/max/auto/unset/current/status/help 等；
 *             缺省/空 → 显示当前档位）
 */
public record EffortExecuteRequest(
        String args
) {
}
