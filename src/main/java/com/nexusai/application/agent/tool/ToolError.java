package com.nexusai.application.agent.tool;

/**
 * 工具失败载荷 · Java 内部结构装置（无 wire 语义）· 替代「错误文案即 String」的旧约定。
 *
 * <p><b>问题</b>：旧实现把失败文案塞进 {@code ToolResult.data(String)}，下游靠前缀表
 * {@code isToolErrorData} 反推错误态。任何未入表的文案（如 Edit 的 {@code "path is empty"}）
 * 都被判成成功，再被 Edit/Write 的成功模板 mapper 渲染成
 * {@code "The file  has been updated successfully."}（实测假成功）。
 *
 * <p><b>做法</b>：失败结果的 {@code data} 改为本记录，错误态由类型承载、类别由枚举承载。
 * 下游做 {@code instanceof} 判定，永不解析文案。
 *
 * <p><b>与 CC 的关系</b>：CC 的 {@code ToolResult<T>} 只有 {@code data/newMessages/contextModifier/mcpMeta}
 * 四字段（{@code Tool.ts:321-336}），<u>没有</u> is_error；失败由控制流表达。本记录是那个控制流
 * 信号在 Java 侧的结构化承载，属 {@code N/A} 表达差异（不新增 wire 字段、不改 4 字段契约）。
 *
 * @param message 模型可见的错误文本（空/空白 → {@code "unknown error"}）
 * @param kind    失败类别（决定是否套 {@code <tool_use_error>} 外壳）
 */
public record ToolError(String message, ToolErrorKind kind) {

    public ToolError {
        if (message == null || message.isBlank()) {
            message = "unknown error";
        }
        if (kind == null) {
            kind = ToolErrorKind.CALL_THREW;
        }
    }

    /**
     * 文本投影 = 错误原文。
     *
     * <p><b>WHY 覆盖</b>：既有大量消费者以 {@code String.valueOf(result.data())} /
     * {@code truncateResult(Object)} 提取文本（如 Bash mapper、实时 UI 推送、hook 摘要）。
     * 若不覆盖，错误文本会变成 record 的默认 toString（
     * {@code ToolError[message=…, kind=…]}）泄漏到模型或界面。覆盖后这些路径退化为旧行为
     * （旧实现 data 就是这段 String），保证改动前后文本一致。
     */
    @Override
    public String toString() {
        return message;
    }

    /** 便捷工厂 · 默认类别 {@link ToolErrorKind#CALL_THREW}。 */
    public static ToolError of(String message) {
        return new ToolError(message, ToolErrorKind.CALL_THREW);
    }

    /** 便捷工厂 · 显式类别。 */
    public static ToolError of(String message, ToolErrorKind kind) {
        return new ToolError(message, kind);
    }

    /**
     * 历史 category 字符串 → 类别（对齐旧 {@code ToolResult.error(id, msg, category)} 的语义）。
     *
     * <p>仅识别既有两种取值（{@code "validation"} / {@code "permission"}），其余归
     * {@link ToolErrorKind#CALL_THREW}；不猜测更多类别。
     */
    public static ToolErrorKind kindOfCategory(String category) {
        if (category == null) {
            return ToolErrorKind.CALL_THREW;
        }
        return switch (category) {
            case "validation" -> ToolErrorKind.VALIDATE_INPUT;
            case "permission" -> ToolErrorKind.PERMISSION_DENY;
            default -> ToolErrorKind.CALL_THREW;
        };
    }
}
