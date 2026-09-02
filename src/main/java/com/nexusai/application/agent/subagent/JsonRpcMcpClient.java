package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpResource;
import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * s19 JSON-RPC over stdio MCP 客户端 · 对齐 CC client.ts MCPServerConnection。
 *
 * <p>CC client.ts 流程:
 * <ol>
 *   <li>connect() → initialize 握手 (protocolVersion + capabilities)</li>
 *   <li>tools/list → 工具发现</li>
 *   <li>tools/call → 工具调用</li>
 * </ol>
 *
 * <p>[MCP-I-9 T6 G1] 占位核心方法（initialize/listTools/callTool）已删 —— McpToolPool 生产
 * 只消费 resources/prompts 解析（listResourcesFromJson/listPromptsFromJson/Capabilities）；
 * tools/call 占位 0 调用方（真实 MCP 工具调用走 AgentMcpTool / McpServerTool transport，
 * D-B10-09 删除 tools/call 响应解析方法与响应 record，DIM-08）。
 * 本类保留解析辅助方法（listResourcesFromJson/listPromptsFromJson/Capabilities）。
 */
@Component
public class JsonRpcMcpClient {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcMcpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * P1-17: 解析 MCP {@code resources/list} 响应 result 节点 → {@link McpResource} 列表
     * · 对齐 CC {@code client.ts:2000-2031 fetchResourcesForClient}.
     *
     * <p>CC 语义（自验）：
     * <ul>
     *   <li>:2014 {@code if (!result.resources) return []} — 无 resources 数组返回空</li>
     *   <li>:2017-2020 每个 resource 追加 {@code server: client.name}</li>
     *   <li>:2021-2027 catch → logMCPError + 返回 []（fail-soft 不抛）</li>
     * </ul>
     *
     * <p>入参为 transport.sendRequest 返回的 <b>result 节点</b>（StdioMcpTransport
     * 已完成 {@code node.get("result")} 解包），非完整 JSON-RPC 响应串。
     *
     * @param result     resources/list 响应 result 节点
     * @param serverName MCP server 名（CC client.name，追加到每个 resource.server）
     * @return 解析后的资源列表；无 resources 数组 / 异常返回空 list
     */
    public List<McpResource> listResourcesFromJson(JsonNode result, String serverName) {
        if (result == null || result.isNull()) {
            return List.of();
        }
        try {
            JsonNode resources = result.path("resources");
            if (!resources.isArray()) {
                log.warn("[JsonRpcMcpClient] 响应无 resources 数组 server={}", serverName);
                return List.of();
            }
            List<McpResource> list = new ArrayList<>();
            for (JsonNode res : resources) {
                String uri = res.path("uri").asText("");
                if (uri.isEmpty()) continue;
                String mimeType = res.hasNonNull("mimeType") ? res.path("mimeType").asText("") : null;
                String description = res.hasNonNull("description") ? res.path("description").asText("") : null;
                list.add(new McpResource(uri,
                    res.path("name").asText(""),
                    mimeType,
                    description,
                    serverName));
            }
            log.info("[JsonRpcMcpClient] 解析 resources/list 响应 server={} 共 {} 资源", serverName, list.size());
            return list;
        } catch (Exception e) {
            log.warn("[JsonRpcMcpClient] 解析 resources/list 响应失败 server={}: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    /**
     * P1-17: 解析 MCP {@code prompts/list} 响应 result 节点 → {@link Command} 列表
     * · 对齐 CC {@code client.ts:2033-2107 fetchCommandsForClient}.
     *
     * <p>CC 语义（自验）：
     * <ul>
     *   <li>:2048 {@code if (!result.prompts) return []}</li>
     *   <li>:2055 {@code argNames = Object.values(prompt.arguments ?? {}).map(k => k.name)}</li>
     *   <li>:2058 {@code name = 'mcp__' + normalizeNameForMCP(client.name) + '__' + prompt.name}</li>
     *   <li>:2059-2064 description??'' / hasUserSpecifiedDescription=!!description /
     *       contentLength:0 / isEnabled:()=>true / isHidden:false / isMcp:true</li>
     *   <li>:2065 progressMessage:'running'</li>
     *   <li>:2066-2070 userFacingName() = {@code `${client.name}:${prompt.name} (MCP)`}</li>
     *   <li>:2072 source:'mcp'（<b>不设 loadedFrom</b> — 普通 MCP prompt 非 skill，见 commands.ts:551-556）</li>
     *   <li>:2097-2103 catch → logMCPError + []</li>
     * </ul>
     *
     * <p>userFacingName 对齐：Java {@code Command.userFacingName()} = displayName 非空优先，
     * 故 displayName 落位 CC 的 {@code `${client.name}:${prompt.name} (MCP)`}。
     *
     * @param result     prompts/list 响应 result 节点
     * @param serverName MCP server 名（CC client.name）
     * @return 映射后的 Command 列表；无 prompts 数组 / 异常返回空 list
     */
    public List<Command> listPromptsFromJson(JsonNode result, String serverName) {
        if (result == null || result.isNull()) {
            return List.of();
        }
        try {
            JsonNode prompts = result.path("prompts");
            if (!prompts.isArray()) {
                log.warn("[JsonRpcMcpClient] 响应无 prompts 数组 server={}", serverName);
                return List.of();
            }
            String normalized = McpStringUtils.normalizeNameForMCP(serverName);
            List<Command> commands = new ArrayList<>();
            for (JsonNode prompt : prompts) {
                String promptName = prompt.path("name").asText("");
                if (promptName.isEmpty()) continue;
                String description = prompt.path("description").asText("");
                // CC :2055 argNames = Object.values(prompt.arguments ?? {}).map(k => k.name)
                List<String> argNames = new ArrayList<>();
                JsonNode argsNode = prompt.path("arguments");
                if (argsNode.isArray()) {
                    for (JsonNode a : argsNode) {
                        String an = a.path("name").asText("");
                        if (!an.isEmpty()) argNames.add(an);
                    }
                } else if (argsNode.isObject()) {
                    argsNode.fieldNames().forEachRemaining(argNames::add);
                }

                Command cmd = new Command();
                // CC :2057 type: 'prompt'
                cmd.setType("prompt");
                // CC :2058 name: 'mcp__' + normalizeNameForMCP(client.name) + '__' + prompt.name
                cmd.setName("mcp__" + normalized + "__" + promptName);
                // CC :2059 description: prompt.description ?? ''
                cmd.setDescription(description);
                // CC :2060 hasUserSpecifiedDescription: !!prompt.description
                cmd.setHasUserSpecifiedDescription(!description.isEmpty());
                // CC :2061 contentLength: 0 (Dynamic MCP content)
                cmd.setContent("");
                // CC :2062 isEnabled: () => true — Command.enabled 默认 true
                // CC :2063 isHidden: false
                cmd.setIsHidden(Boolean.FALSE);
                // CC :2065 progressMessage: 'running'
                cmd.setProgressMessage("running");
                // CC :2066-2070 userFacingName() = `${client.name}:${prompt.name} (MCP)`
                //     — Java Command.userFacingName() 走 displayName 优先，故落位 displayName
                cmd.setDisplayName(serverName + ":" + promptName + " (MCP)");
                cmd.setArgNames(argNames.isEmpty() ? null : argNames);
                // CC :2072 source: 'mcp'（loadedFrom 不设 → 普通 MCP prompt 非 skill）
                cmd.setSource(CommandSource.MCP);
                cmd.setUserInvocable(Boolean.TRUE);
                cmd.setDisableModelInvocation(Boolean.FALSE);
                commands.add(cmd);
            }
            log.info("[JsonRpcMcpClient] 解析 prompts/list 响应 server={} 共 {} 命令", serverName, commands.size());
            return commands;
        } catch (Exception e) {
            log.warn("[JsonRpcMcpClient] 解析 prompts/list 响应失败 server={}: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    /**
     * MCP 能力 (CC InitializeResult.capabilities) · tools/list + tools/call + resources + prompts
     * + 3 类 listChanged 通知门控 + experimental（channel 能力信号）。
     *
     * <p>对齐 CC {@code client.ts:2169 supportsResources = !!client.capabilities?.resources}
     * 与 {@code :2038 !client.capabilities?.prompts} — resources/prompts 布尔用于
     * fetchResources/fetchCommands 能力门控。
     *
     * <p>P2-15 新增三字段：CC 原名 {@code capabilities.{tools,prompts,resources}.listChanged}
     * （useManageMCPConnections.ts:618/:667/:705 的 {@code if (client.capabilities?.X?.listChanged)}
     * 注册门控，camelCase = toolsListChanged/promptsListChanged/resourcesListChanged）。
     *
     * <p>[impl-I-3 T2 · R2-1] 新增 {@code experimental} 字段：CC 原名 {@code client.capabilities?.experimental}
     * （client.ts:196-200 / channelNotification.ts:200 消费 {@code capabilities.experimental['claude/channel']}
     *   + channelPermissions.ts:191-192 双 capability 判定）。channel server 经
     * {@code experimental['claude/channel']: {}}（MCP presence-signal 惯用法）声明通道能力。
     */
    public record Capabilities(boolean toolsList, boolean toolsCall, boolean resources, boolean prompts,
                               boolean toolsListChanged, boolean promptsListChanged, boolean resourcesListChanged,
                               Map<String, Object> experimental) {

        public Capabilities {
            experimental = experimental != null ? Map.copyOf(experimental) : Map.of();
        }

        /** 7-arg 便捷构造（既有调用方零改动）— experimental 默认 Map.of()（CC capabilities.experimental 缺失 → undefined）。 */
        public Capabilities(boolean toolsList, boolean toolsCall, boolean resources, boolean prompts,
                            boolean toolsListChanged, boolean promptsListChanged, boolean resourcesListChanged) {
            this(toolsList, toolsCall, resources, prompts, toolsListChanged, promptsListChanged, resourcesListChanged, Map.of());
        }

        /**
         * P1-17/P2-15: 从 MCP initialize 响应 result 节点解析 capabilities
         * · 对齐 CC client.capabilities（client.ts:2169/2038 能力门控数据源
         *   + useManageMCPConnections.ts:618/:667/:705 listChanged 注册门控）。
         *
         * @param initializeResult initialize 响应 result 节点（含 capabilities 对象）
         * @return Capabilities；缺失字段一律 false（对齐 CC undefined → falsy 门控）
         */
        public static Capabilities fromInitializeResult(JsonNode initializeResult) {
            if (initializeResult == null || initializeResult.isNull()) {
                return new Capabilities(false, false, false, false, false, false, false);
            }
            JsonNode caps = initializeResult.path("capabilities");
            boolean tools = caps.path("tools").isObject();
            boolean resources = caps.path("resources").isObject() || caps.path("resources").isBoolean();
            boolean prompts = caps.path("prompts").isObject() || caps.path("prompts").isBoolean();
            // toolsList/toolsCall 同源（当前 tools 能力只区分是否支持 tools/list 调用）
            // CC :618 client.capabilities?.tools?.listChanged — listChanged 是 capabilities.tools 对象的布尔字段
            boolean toolsListChanged = caps.path("tools").isObject()
                && caps.path("tools").path("listChanged").asBoolean(false);
            boolean promptsListChanged = caps.path("prompts").isObject()
                && caps.path("prompts").path("listChanged").asBoolean(false);
            boolean resourcesListChanged = caps.path("resources").isObject()
                && caps.path("resources").path("listChanged").asBoolean(false);
            // [impl-I-3 T2 · R2-1] 解析 experimental（CC capabilities.experimental，channelNotification.ts:200 消费）
            Map<String, Object> experimental = Map.of();
            JsonNode expNode = caps.path("experimental");
            if (expNode.isObject()) {
                experimental = JSON.convertValue(expNode, new TypeReference<Map<String, Object>>() {});
            }
            return new Capabilities(tools, tools, resources, prompts,
                toolsListChanged, promptsListChanged, resourcesListChanged, experimental);
        }
    }
}

