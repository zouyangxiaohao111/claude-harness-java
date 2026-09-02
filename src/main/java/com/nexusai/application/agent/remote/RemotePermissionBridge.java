package com.nexusai.application.agent.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RemotePermissionBridge · 对齐 CC remote/remotePermissionBridge.ts:52-79 createToolStub。
 *
 * <p>L1 语义: 远程模式 (CCR 容器) 下, 本地 CLI 不认识远端工具 (如 MCP 工具) 时创建最小 Tool stub,
 * 权限请求回落到 FallbackPermissionRequest。stub 的可见部分是 {@code renderToolUseMessage}:
 * 取 input 的前 3 个键值对, 格式化为 {@code key: value} (value 非字符串则 JSON 化), 逗号空格连接; 空 input → ""。
 *
 * <p>[WF-11 · DC-WF8-02 / OPD-WF8-02-02] 补接线：{@link #createToolStub(String)} 对齐 CC
 * {@code createToolStub(toolName)}（remotePermissionBridge.ts:53-79），被远程会话消费方
 * （CC useDirectConnect.ts:94 / useRemoteSession.ts:338 / useSSHSession.ts:98
 * {@code findToolByName(...) ?? createToolStub(...)}；Java RemoteSessionManager /
 * DirectConnectSessionManager）用于渲染远端工具权限请求。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: renderToolUseMessage(Map) + createToolStub(String) + stub 语义常量
 *       IS_ENABLED/IS_READ_ONLY/IS_MCP/NEEDS_PERMISSIONS</li>
 *   <li><b>A2 Golden Trace</b>: {a:1,b:"x"} → "a: 1, b: x"</li>
 *   <li><b>A3 纯函数</b>: 无副作用; 保持 input 插入顺序 (CC Object.entries 顺序)</li>
 *   <li><b>A4 边界</b>: 空 input → ""; 超过 3 个键 → 只取前 3; null value → JSON "null"; 字符串值不加引号</li>
 *   <li><b>A5 业务场景</b>: 远端 MCP 工具 {command:"ls",cwd:"/tmp",extra:1,more:2} → "command: ls, cwd: /tmp, extra: 1"</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Tool stub 对象 → Java 纯静态渲染函数 + 语义常量 + 嵌套 stub 类;
 * TS jsonStringify(value) → Java: 字符串原样, 其余用 String.valueOf (数值/布尔) — 保持 CC "非字符串则序列化" 契约。
 */
public final class RemotePermissionBridge {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC stub isEnabled() → true */
    public static final boolean IS_ENABLED = true;
    /** CC stub isReadOnly() → false */
    public static final boolean IS_READ_ONLY = false;
    /** CC stub isMcp → false */
    public static final boolean IS_MCP = false;
    /** CC stub needsPermissions() → true (回落到 FallbackPermissionRequest) */
    public static final boolean NEEDS_PERMISSIONS = true;

    private RemotePermissionBridge() {}

    /**
     * CC remotePermissionBridge.ts:53-79 createToolStub(toolName) —
     * 为本地未加载的远端工具创建最小 Tool stub（权限请求渲染用）。
     *
     * <p>CC 真源（remotePermissionBridge.ts:53-79）：
     * <pre>{@code
     * export function createToolStub(toolName) {
     *   return {
     *     name: toolName,
     *     inputSchema: {},
     *     isEnabled: () => true,
     *     userFacingName: () => toolName,
     *     renderToolUseMessage: (input) => { ...前 3 键值对渲染... },
     *     call: async () => ({ data: '' }),
     *     description: async () => '',
     *     prompt: () => '',
     *     isReadOnly: () => false,
     *     isMcp: false,
     *     needsPermissions: () => true,
     *   }
     * }</pre>
     *
     * <p>[WF-11 · DC-WF8-02] Java 表达：{@link RemoteToolStub} 实现 {@link Tool} 接口，
     * {@code needsPermissions() → true} 由 {@link #NEEDS_PERMISSIONS} 常量表达（Java Tool 接口
     * 无该谓词，权限决策始终回落到远端 can_use_tool 请求本身）。
     *
     * @param toolName 远端工具名（非空）
     * @return 最小 Tool stub
     */
    public static Tool createToolStub(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("createToolStub toolName is blank");
        }
        return new RemoteToolStub(toolName);
    }

    /**
     * CC remotePermissionBridge.ts:57-71 renderToolUseMessage —
     * <pre>
     * const entries = Object.entries(input)
     * if (entries.length === 0) return ''
     * return entries.slice(0,3).map(([k,v]) =>
     *   `${k}: ${typeof v === 'string' ? v : jsonStringify(v)}`).join(', ')
     * </pre>
     *
     * @param input 工具输入 (保持插入顺序的 Map)
     * @return 渲染后的单行摘要
     */
    public static String renderToolUseMessage(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (i >= 3) break;
            if (i > 0) sb.append(", ");
            Object v = e.getValue();
            String valueStr = (v instanceof String) ? (String) v : String.valueOf(v);
            sb.append(e.getKey()).append(": ").append(valueStr);
            i++;
        }
        return sb.toString();
    }

    /**
     * 最小远端工具 stub · 对齐 CC remotePermissionBridge.ts:53-79 createToolStub 返回对象。
     *
     * <p>仅渲染面有效（{@link #renderToolUseMessage(JsonNode)} 供权限弹窗展示）；execute 恒返回
     * 空结果（远端工具在 CCR 容器执行，本地 stub 不真正执行）。
     */
    private static final class RemoteToolStub implements Tool {
        private final String toolName;

        RemoteToolStub(String toolName) {
            this.toolName = toolName;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return ""; }
        @Override public String prompt() { return ""; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public boolean isEnabled() { return IS_ENABLED; }
        @Override public String userFacingName() { return toolName; }
        @Override public boolean isMcp() { return IS_MCP; }
        @Override public boolean isReadOnly(JsonNode input) { return IS_READ_ONLY; }
        @Override public String renderToolUseMessage(JsonNode input) {
            return RemotePermissionBridge.renderToolUseMessage(toMap(input));
        }
        @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "");
        }

        private static Map<String, Object> toMap(JsonNode node) {
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), asJavaValue(e.getValue())));
            return map;
        }

        private static Object asJavaValue(JsonNode n) {
            if (n == null || n.isNull()) {
                return null;
            }
            if (n.isTextual()) {
                return n.textValue();
            }
            if (n.isNumber()) {
                return n.numberValue();
            }
            if (n.isBoolean()) {
                return n.booleanValue();
            }
            // 对象/数组保持 JsonNode —— String.valueOf → 紧凑 JSON（对齐 CC jsonStringify）
            return n;
        }
    }
}
