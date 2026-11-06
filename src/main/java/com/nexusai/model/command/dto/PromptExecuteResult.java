package com.nexusai.model.command.dto;

import com.nexusai.model.command.Command;

/**
 * prompt 型内置命令的执行结果 · 对齐 CC {@code processSlashCommand} 对 {@code type:'prompt'}
 * 命令的返回形态（{@code getPromptForCommand()} → {@code {type:'prompt', content, ...}}）。
 *
 * <p><b>WHY 需要这个 DTO（2026-11-04）</b>：{@code /init} 是 {@code type=prompt} 命令，它的价值
 * <b>全在提示词正文</b>（{@link com.nexusai.application.agent.init.InitCommand#getPrompt()}）。
 * 旧实现下通用分支 {@code /builtins/{name}/execute} 对所有非特例命令只回
 * {@link BuiltInCommandDto} 元数据（name/type/description/aliases，<b>不含 content</b>）⇒
 * 前端 R 到 200 后弹「已执行 /init」，但提示词从未进入对话、模型从未被要求生成 CLAUDE.md
 * —— 「报成功但什么都没发生」，与 P2-18 的 /compact 同款故障形态。
 *
 * <p>本 DTO 把正文交给调用方，由前端把它作为一条 user 消息发出（对齐 CC 把它注入本轮对话的语义）。
 *
 * @param type    恒 {@code "prompt"}（调用方据此分流：注入对话，而非只弹 toast）
 * @param name    命令名（如 {@code init}）
 * @param content 提示词正文（{@link Command#getContent()}）
 */
public record PromptExecuteResult(
    String type,
    String name,
    String content
) {
    /** 常量 type —— 与 CC 的 {@code 'prompt'} 字面量一致，前端按此分流。 */
    public static final String TYPE_PROMPT = "prompt";

    /**
     * 由领域命令构造（仅 type=prompt 且 content 非空的命令应走本构造）。
     *
     * @param c 领域命令（{@code c.getType()=='prompt'}）
     * @return prompt 执行结果
     */
    public static PromptExecuteResult from(Command c) {
        return new PromptExecuteResult(TYPE_PROMPT, c.getName(), c.getContent());
    }
}
