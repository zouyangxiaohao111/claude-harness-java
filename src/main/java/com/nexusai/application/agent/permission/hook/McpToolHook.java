package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool hook · 对齐 claude.exe v2.1.325 {@code McpToolHookSchema}
 * (type='mcp_tool', {@code pg()} off≈309,296,321；子 schema off≈309,299,683).
 *
 * <p><b>语义</b>: 用已配置的 MCP server 上的某个工具当 hook —— 事件触发时调用该工具，
 * 把它返回的 JSON 按 hook 输出解释。与 command/prompt/agent/http 并列的第 5 种可持久化
 * hook 类型。
 *
 * <p><b>WHY 必须建模</b>: Jackson 按 {@code type} 判别，不认识 {@code mcp_tool} 时该
 * settings 源的整层 hooks 反序列化失败 —— 用户只要混进一条 mcp_tool hook，同文件其余 hook
 * 也会全部失效。
 *
 * <p><b>CC 真源字段</b>:
 * <ul>
 *   <li>{@code server} — 已配置的 MCP server 名 (必填)</li>
 *   <li>{@code tool} — 该 server 上的工具名 (必填)</li>
 *   <li>{@code input} — 传给工具的实参；字符串值支持从 hook 输入 JSON 插值
 *       （{@code ${tool_input.file_path}} 形式）</li>
 *   <li>{@code if} — 权限规则语法过滤条件（与 command 同语义）</li>
 *   <li>{@code timeout} — 该工具调用的超时秒数（正整数）</li>
 *   <li>{@code statusMessage} — 自定义 spinner 文案</li>
 *   <li>{@code once} — true=执行一次后移除</li>
 * </ul>
 *
 * <p>本类只建模配置；执行（调用 MCP 工具、插值、超时）属 Wave2 执行语义。
 *
 * @param server        CC original: server
 * @param tool          CC original: tool
 * @param input         CC original: input（可选 KV；值可为 null，故不用 Map.copyOf）
 * @param ifCondition   CC original: if
 * @param timeout       CC original: timeout；秒
 * @param statusMessage CC original: statusMessage
 * @param once          CC original: once
 */
public record McpToolHook(
    String server,
    String tool,
    Map<String, Object> input,
    @JsonProperty("if") String ifCondition,
    Integer timeout,
    String statusMessage,
    Boolean once
) implements HookCommand, SessionHook {

    public McpToolHook {
        if (input != null) {
            input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        }
    }

    @Override
    public HookType hookType() {
        return HookType.MCP_TOOL;
    }

    @Override
    public String type() {
        return "mcp_tool";
    }
}
