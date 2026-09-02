package com.nexusai.application.agent.command;

import com.nexusai.application.agent.tool.AgentToolConstants;

import java.util.List;

/**
 * StatuslineCommand · 对齐 CC commands/statusline.tsx:1-24.
 *
 * <p>L1 语义: {@code /statusline} 内置斜杠命令 — 生成一段派单 prompt, 让主 agent 创建一个
 * {@code subagent_type = "statusline-setup"} 的 Agent 工具调用来配置状态栏。空参数时回落到
 * 默认 prompt "Configure my statusLine from my shell PS1 configuration"。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: NAME="statusline" / DESCRIPTION / PROGRESS_MESSAGE / ALLOWED_TOOLS(3 项) /
 *       SOURCE="builtin" / DISABLE_NON_INTERACTIVE=true / CONTENT_LENGTH=0 与 CC 字面量 1:1</li>
 *   <li><b>A2 Golden Trace</b>: getPromptForCommand(args) → 单条 text block,
 *       文本 = {@code Create an Agent with subagent_type "statusline-setup" and the prompt "<p>"}</li>
 *   <li><b>A3 纯函数</b>: 无内部状态; 输入 args → 输出 prompt, 确定性</li>
 *   <li><b>A4 边界</b>: args=null/空白/纯空格 → 回落默认 prompt; args 前后空白被 trim</li>
 *   <li><b>A5 业务场景</b>: 用户输入 {@code /statusline show git branch} → 生成含该 prompt 的派单文本</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS object literal + satisfies Command → Java final class + static 常量 +
 * 纯静态方法; TS AGENT_TOOL_NAME import → {@link AgentToolConstants#AGENT_TOOL_NAME}。
 */
public final class StatuslineCommand {

    /** CC statusline.tsx:8 name */
    public static final String NAME = "statusline";
    /** CC statusline.tsx:6 description */
    public static final String DESCRIPTION = "Set up NexusAI's status line UI";
    /** CC statusline.tsx:9 progressMessage */
    public static final String PROGRESS_MESSAGE = "setting up statusLine";
    /** CC statusline.tsx:12 source */
    public static final String SOURCE = "builtin";
    /** CC statusline.tsx:7 contentLength (dynamic content) */
    public static final int CONTENT_LENGTH = 0;
    /** CC statusline.tsx:13 disableNonInteractive */
    public static final boolean DISABLE_NON_INTERACTIVE = true;
    /** CC statusline.tsx:17 default prompt when args blank */
    public static final String DEFAULT_PROMPT = "Configure my statusLine from my shell PS1 configuration";
    /** CC statusline.tsx:5 aliases */
    public static final List<String> ALIASES = List.of();
    /** CC statusline.tsx:10-11 allowedTools */
    public static final List<String> ALLOWED_TOOLS = List.of(
        AgentToolConstants.AGENT_TOOL_NAME,
        "Read(~/**)",
        "Edit(~/.nexusai/settings.json)");

    private StatuslineCommand() {}

    /**
     * CC statusline.tsx:14-22 getPromptForCommand —
     * <pre>
     * const prompt = args.trim() || 'Configure my statusLine from my shell PS1 configuration'
     * return [{ type:'text', text: `Create an ${AGENT_TOOL_NAME} with subagent_type "statusline-setup" and the prompt "${prompt}"` }]
     * </pre>
     *
     * @param args 用户输入的命令参数 (可为 null)
     * @return 单条派单 prompt 文本
     */
    public static String getPromptForCommand(String args) {
        String trimmed = args == null ? "" : args.trim();
        String prompt = trimmed.isEmpty() ? DEFAULT_PROMPT : trimmed;
        return "Create an " + AgentToolConstants.AGENT_TOOL_NAME
            + " with subagent_type \"statusline-setup\" and the prompt \"" + prompt + "\"";
    }
}
