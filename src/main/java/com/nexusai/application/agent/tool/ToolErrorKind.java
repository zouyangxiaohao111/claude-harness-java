package com.nexusai.application.agent.tool;

/**
 * 工具失败类别 · Java 内部结构装置（无 wire 语义）。
 *
 * <p><b>WHY 需要它</b>：CC 的失败由<u>控制流</u>表达（{@code validateInput} 返回
 * {@code {result:false}}，或 {@code call()} 抛异常），错误块在错误点直构并置
 * {@code is_error:true}；成功结果的 {@code mapToolResultToToolResultBlockParam} 永不参与错误路径
 * （{@code services/tools/toolExecution.ts:1282-1295}）。Java 此前用「错误文案字符串前缀表」
 * 反推错误态，导致 Edit/Write 的失败被成功模板覆盖（假成功）。
 *
 * <p>本枚举是那个「控制流信号」的 Java 承载物：执行层在构造失败结果时显式标注类别，
 * 下游只做结构判定，不再解析自然语言。
 *
 * <p><b>不是 CC 概念</b>：CC 无同名类型，故对上（wire/前端）无任何字段，属 {@code N/A} 表达差异；
 * 仅在 Java 进程内区分「要不要套 {@code <tool_use_error>} 外壳」。
 */
public enum ToolErrorKind {

    /** 工具不在可见工具表 / 未启用 / 被会话剔除（CC {@code <tool_use_error>Error: No such tool available…}）。 */
    UNKNOWN_TOOL(true),

    /** 入参 schema 校验失败（CC {@code <tool_use_error>InputValidationError: …}）。 */
    SCHEMA(true),

    /** 语义校验失败（CC {@code validateInput} 返回 {@code result:false}，如 read-before-write）。 */
    VALIDATE_INPUT(true),

    /** 权限拒绝（deny 规则 / hook deny / 自动分类器 / 用户拒绝）。CC 原文直出，不套壳。 */
    PERMISSION_DENY(false),

    /** {@code call()} 抛异常（CC 内层 catch → {@code formatError}）。原文直出，不套壳。 */
    CALL_THREW(false),

    /** 取消（CC CANCEL_MESSAGE / REJECT_MESSAGE）。原文直出，不套壳。 */
    CANCEL(false),

    /** 用户中断（CC INTERRUPT_MESSAGE_FOR_TOOL_USE 等）。原文直出，不套壳。 */
    INTERRUPT(false),

    /** 执行器合成的兜底失败（流式回退 / 结果缺失 / 拒绝类型）。原文直出，不套壳。 */
    SYNTHETIC(false);

    private final boolean toolUseErrorShell;

    ToolErrorKind(boolean toolUseErrorShell) {
        this.toolUseErrorShell = toolUseErrorShell;
    }

    /**
     * 本类别在模型可见 content 上是否包裹 {@code <tool_use_error>…</tool_use_error>}。
     *
     * <p>判据来源（CC 错误分支逐条）：unknown/schema/validateInput 三条直构时带壳
     * （toolExecution.ts:401/480/670/723）；权限拒绝、{@code call()} 抛错、取消均为原文直出
     * （:1033 / :1589-1737 / :443-451）。
     */
    public boolean wrapsWithToolUseErrorShell() {
        return toolUseErrorShell;
    }
}
