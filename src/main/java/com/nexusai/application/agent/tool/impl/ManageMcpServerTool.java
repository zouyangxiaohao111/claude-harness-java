package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ManageMcpServer 工具 · MCP server 的<strong>加 / 列 / 删</strong>（合成一个工具，按 {@code action} 分发）。
 *
 * <p><b>WHY 合成</b>：同 {@link ManagePluginTool} —— 每个单动作工具各带一份 name/description/prompt/schema，
 * 常驻上下文成本随工具数线性增长。合成后模型仍是「一次调用干一件事」，schema 只付一份。
 *
 * <p><b>三个动作</b>：
 * <ul>
 *   <li>{@code add} — 加一个 MCP server（收<strong>结构化字段</strong>；「一条 claude 命令 / 一段 JSON /
 *       一个 GitHub 项目」这三种来源由**模型自己解析**成这些字段）；</li>
 *   <li>{@code list} — 列已配置的 MCP server（凭证脱敏：只回显 env/headers 的**键名**）；</li>
 *   <li>{@code remove} — 删一个 MCP server（破坏性 ⇒ {@code confirmed} 守卫；删除前先断连）。</li>
 * </ul>
 *
 * <p><b>凭证脱敏是硬要求</b>：{@code McpServerService.toDto} 把 stdio 的 {@code env}、远程的
 * {@code headers}、oauth 配置<b>明文</b>放进 DTO（凭证在 DB 里也明文）⇒ 直接吐 DTO 会把用户的 API key
 * 送进 LLM 上下文（还会进会话历史）。本工具只回显键名，值一律 {@code ***}。
 */
@Component
public class ManageMcpServerTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ManageMcpServerTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 凭证值的占位符 · 只出现在输出里，不回显真实值。 */
    private static final String MASKED = "***";

    /** 合法传输类型 · 与 McpConfigAddValidator.TRANSPORTS 一致（stdio|sse|http）。 */
    private static final List<String> TRANSPORTS = List.of("stdio", "sse", "http");

    private final McpServerService mcpServerService;

    @Autowired
    public ManageMcpServerTool(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @Override
    public String name() {
        return "ManageMcpServer";
    }

    @Override
    public String description() {
        return "Manage MCP servers via one action.\n"
             + "action=add — add an MCP server (stdio: command + args + env; remote: type sse/http with url\n"
             + "  and headers). It is validated against the configured MCP policy and registered enabled.\n"
             + "action=list — list configured MCP servers (credential values are masked; only key names shown).\n"
             + "action=remove — remove an MCP server (destructive; requires confirmed=true).\n"
             + "\nUsage examples:\n"
             + "- `manageMcpServer({ action: \"add\", name: \"filesystem\", command: \"npx\", args: [\"-y\","
             + " \"@modelcontextprotocol/server-filesystem\", \"/tmp\"] })`\n"
             + "- `manageMcpServer({ action: \"list\" })`\n"
             + "- `manageMcpServer({ action: \"remove\", name: \"my-server\", confirmed: true })`";
    }

    @Override
    public String prompt() {
        return "Manage MCP servers.\n"
             + "\nParameters (only the ones the chosen action needs):\n"
             + "- action (required): `add` | `list` | `remove`.\n"
             + "- name (add/remove): unique server name (letters, digits, `_`, `-`).\n"
             + "- type (add, optional): `stdio` (default) | `sse` | `http`.\n"
             + "- command (add, stdio): the executable to run, e.g. `npx`.\n"
             + "- args (add, stdio): arguments for the command, e.g. `[\"-y\", \"@scope/server\"]`.\n"
             + "- env (add, stdio): environment variables as a string map.\n"
             + "- url (add, sse/http): the remote endpoint URL.\n"
             + "- headers (add, sse/http): request headers as a string map.\n"
             + "- scope (add, optional): `project` (default) | `user` | `local` | others accepted by the backend.\n"
             + "- enabled (add, optional): default true.\n"
             + "- autoStart (add, optional): default follows enabled.\n"
             + "- confirmed (remove, required): must be true — set it only after the user confirmed the removal\n"
             + "  in the conversation; otherwise the call is rejected without any side effect.\n"
             + "\nDo not pass OAuth client secrets here — the OAuth flow is handled elsewhere.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        var props = schema.putObject("properties");
        var action = props.putObject("action");
        action.put("type", "string");
        action.putArray("enum").add("add").add("list").add("remove");
        action.put("description", "What to do: add | list | remove");
        props.putObject("name").put("type", "string")
            .put("description", "[add/remove] MCP server name (e.g. `filesystem`)");
        props.putObject("type").put("type", "string")
            .put("description", "[add] Transport type: stdio (default) | sse | http");
        props.putObject("command").put("type", "string")
            .put("description", "[add, stdio] executable to run (e.g. `npx`)");
        var args = props.putObject("args");
        args.put("type", "array");
        args.putObject("items").put("type", "string");
        args.put("description", "[add, stdio] arguments for the command");
        props.putObject("env").put("type", "object")
            .put("description", "[add, stdio] environment variables (string map)")
            .put("additionalProperties", true);
        props.putObject("url").put("type", "string")
            .put("description", "[add, sse/http] remote endpoint URL");
        props.putObject("headers").put("type", "object")
            .put("description", "[add, sse/http] request headers (string map)")
            .put("additionalProperties", true);
        props.putObject("scope").put("type", "string")
            .put("description", "[add] write scope: project (default) | user | local");
        props.putObject("enabled").put("type", "boolean")
            .put("description", "[add] whether the server is enabled (default true)");
        props.putObject("autoStart").put("type", "boolean")
            .put("description", "[add] whether to auto-connect on startup (default follows enabled)");
        props.putObject("confirmed").put("type", "boolean")
            .put("description", "[remove] Must be true — set only after the user confirmed the removal");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return "list".equals(actionOf(input));
    }

    @Override
    public boolean isDestructive(JsonNode input) {
        return "remove".equals(actionOf(input));
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return "list".equals(actionOf(input));
    }

    @Override
    public ToolResult<?> execute(ToolUseBlock call) {
        JsonNode input = call.input();
        String action = actionOf(input);
        return switch (action) {
            case "add" -> add(call, input);
            case "list" -> list(call);
            case "remove" -> remove(call, input);
            default -> ToolResult.error(call.id(),
                "Error: action 必须是 add / list / remove 之一，收到：" + action);
        };
    }

    // ── action=add ────────────────────────────────────────────────────
    private ToolResult<?> add(ToolUseBlock call, JsonNode input) {
        String name = readText(input, "name");
        if (name.isBlank()) {
            return ToolResult.error(call.id(), "Error: name 不能为空（MCP server 名）");
        }
        String type = readText(input, "type");
        if (!type.isBlank() && !TRANSPORTS.contains(type)) {
            return ToolResult.error(call.id(),
                "Error: type 非法：" + type + "，合法值只有 " + String.join(" / ", TRANSPORTS));
        }
        String command = readText(input, "command");
        String url = readText(input, "url");
        if (type.isBlank() || "stdio".equals(type)) {
            if (command.isBlank()) {
                return ToolResult.error(call.id(),
                    "Error: stdio 类型的 MCP server 必须给 command（可执行命令，如 `npx`）");
            }
        } else if (url.isBlank()) {
            return ToolResult.error(call.id(),
                "Error: " + type + " 类型的 MCP server 必须给 url（远程地址）");
        }

        List<String> args;
        Map<String, String> env;
        Map<String, String> headers;
        try {
            args = readStringArray(input, "args");
            env = readStringMap(input, "env");
            headers = readStringMap(input, "headers");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        }
        Boolean enabled = readBoolean(input, "enabled");
        Boolean autoStart = readBoolean(input, "autoStart");
        String scope = readText(input, "scope");

        McpServerDto dto;
        try {
            dto = mcpServerService.create(new McpCreateRequest(
                name, command.isBlank() ? null : command, args, env, enabled,
                type.isBlank() ? null : type, url.isBlank() ? null : url, headers,
                null, null, scope.isBlank() ? null : scope, autoStart));
        } catch (Exception e) {
            log.warn("[ManageMcpServerTool] 创建失败 name={}: {}", name, e.getMessage());
            return ToolResult.error(call.id(), "Error: 添加 MCP server 失败：" + e.getMessage());
        }
        if (log.isInfoEnabled()) {
            log.info("[ManageMcpServerTool] 已添加 server={} type={} scope={} enabled={}",
                dto.name(), dto.type(), dto.scope(), dto.enabled());
        }
        StringBuilder sb = new StringBuilder("已添加 MCP server ")
            .append(dto.name())
            .append("（id=").append(dto.id())
            .append("，type=").append(dto.type())
            .append("，scope=").append(dto.scope())
            .append("，enabled=").append(dto.enabled())
            .append("）");
        if (dto.warnings() != null && !dto.warnings().isEmpty()) {
            sb.append("\n警告：\n");
            for (String w : dto.warnings()) {
                sb.append("- ").append(w).append('\n');
            }
        }
        sb.append("该 server 的工具在下一次连接/会话刷新后可用。");
        return ToolResult.success(call.id(), sb.toString());
    }

    // ── action=list ───────────────────────────────────────────────────
    private ToolResult<?> list(ToolUseBlock call) {
        List<McpServerDto> servers;
        try {
            servers = mcpServerService.listAll();
        } catch (Exception e) {
            log.warn("[ManageMcpServerTool] 读取失败: {}", e.getMessage());
            return ToolResult.error(call.id(), "Error: 读取 MCP server 失败：" + e.getMessage());
        }
        if (servers == null || servers.isEmpty()) {
            return ToolResult.success(call.id(), "当前没有已配置的 MCP server。");
        }
        StringBuilder sb = new StringBuilder("已配置的 MCP server（").append(servers.size()).append("）：\n");
        for (McpServerDto s : servers) {
            if (s == null) {
                continue;
            }
            sb.append("- ").append(s.name())
              .append("  id=").append(s.id())
              .append("  type=").append(s.type())
              .append("  scope=").append(s.scope())
              .append("  enabled=").append(s.enabled())
              .append("  status=").append(s.status())
              .append("  runtime=").append(s.runtimeStatus())
              .append("  approval=").append(s.approvalStatus())
              .append('\n');
            String endpoint = describeEndpoint(s);
            if (endpoint != null) {
                sb.append("    endpoint: ").append(endpoint).append('\n');
            }
            String envKeys = describeKeys(s.env());
            if (envKeys != null) {
                sb.append("    env keys: ").append(envKeys).append('\n');
            }
            String headerKeys = describeKeys(s.headers());
            if (headerKeys != null) {
                sb.append("    header keys: ").append(headerKeys).append('\n');
            }
            if (s.lastError() != null && !s.lastError().isBlank()) {
                sb.append("    lastError: ").append(s.lastError()).append('\n');
            }
        }
        return ToolResult.success(call.id(), sb.toString().stripTrailing());
    }

    // ── action=remove ─────────────────────────────────────────────────
    private ToolResult<?> remove(ToolUseBlock call, JsonNode input) {
        String name = readText(input, "name");
        if (name.isBlank()) {
            return ToolResult.error(call.id(), "Error: name 不能为空（要删除的 MCP server 名）");
        }
        if (!isConfirmed(input)) {
            return ToolResult.error(call.id(),
                "Error: 删除是破坏性操作：尚未得到用户确认（confirmed 不为 true），本次未做任何改动。"
                + "请先在会话里告诉用户要删除哪个 MCP server、等用户确认后再带 confirmed=true 调用。");
        }
        String id;
        String actualName;
        try {
            List<McpServerDto> servers = mcpServerService.listAll();
            McpServerDto target = servers == null ? null : servers.stream()
                .filter(s -> s != null && name.equals(s.name()))
                .findFirst()
                .orElse(null);
            if (target == null) {
                return ToolResult.error(call.id(), "Error: 未找到已配置的 MCP server：" + name);
            }
            id = target.id();
            actualName = target.name();
        } catch (Exception e) {
            log.warn("[ManageMcpServerTool] 查询 server 失败 name={}: {}", name, e.getMessage());
            return ToolResult.error(call.id(), "Error: 查询 MCP server 失败：" + e.getMessage());
        }
        try {
            // 先断连再删：McpServerService.delete 只删 DB 行 + 清 OAuth 凭据、**不 teardown 连接**
            // ⇒ 不先 stop 的话，该 server 的 mcp__* 工具会留在会话里直到重启
            try {
                mcpServerService.stop(id);
            } catch (Exception e) {
                log.info("[ManageMcpServerTool] 停止 server 失败（可能本就未连接），继续删除: {}", e.getMessage());
            }
            mcpServerService.delete(id);
        } catch (Exception e) {
            log.warn("[ManageMcpServerTool] 删除失败 server={}: {}", actualName, e.getMessage());
            return ToolResult.error(call.id(), "Error: 删除 MCP server 失败：" + e.getMessage());
        }
        if (log.isInfoEnabled()) {
            log.info("[ManageMcpServerTool] 已删除 server={} id={}", actualName, id);
        }
        return ToolResult.success(call.id(), "已删除 MCP server " + actualName + "。");
    }

    // ── 工具 ──────────────────────────────────────────────────────────
    private static String actionOf(JsonNode input) {
        String action = readText(input, "action").toLowerCase();
        return action.isBlank() ? "" : action;
    }

    /** stdio → 命令 + 参数；远程 → url。都不含凭证。 */
    private static String describeEndpoint(McpServerDto s) {
        String type = s.type();
        boolean remote = type != null && !"stdio".equals(type);
        if (remote) {
            String url = s.url() != null && !s.url().isBlank() ? s.url() : s.command();
            return url != null && !url.isBlank() ? url : null;
        }
        if (s.command() == null || s.command().isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.command());
        if (s.args() != null && !s.args().isEmpty()) {
            sb.append(' ').append(String.join(" ", s.args()));
        }
        return sb.toString();
    }

    /** 只列键名 · 值一律遮蔽。 */
    private static String describeKeys(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String key : map.keySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append('=').append(MASKED);
        }
        return sb.toString();
    }

    private static boolean isConfirmed(JsonNode input) {
        if (input == null || !input.has("confirmed")) {
            return false;
        }
        JsonNode node = input.get("confirmed");
        return node.isBoolean() && node.asBoolean();
    }

    private static String readText(JsonNode input, String field) {
        if (input == null || !input.has(field)) {
            return "";
        }
        JsonNode node = input.get(field);
        return node.isTextual() ? node.asText().trim() : "";
    }

    private static Boolean readBoolean(JsonNode input, String field) {
        if (input == null || !input.has(field)) {
            return null;
        }
        JsonNode node = input.get(field);
        return node.isBoolean() ? node.asBoolean() : null;
    }

    private static List<String> readStringArray(JsonNode input, String field) {
        if (input == null || !input.has(field) || input.get(field).isNull()) {
            return null;
        }
        JsonNode node = input.get(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " 必须是字符串数组");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(field + " 的每一项都必须是字符串");
            }
            out.add(item.asText());
        }
        return out;
    }

    private static Map<String, String> readStringMap(JsonNode input, String field) {
        if (input == null || !input.has(field) || input.get(field).isNull()) {
            return null;
        }
        JsonNode node = input.get(field);
        if (!node.isObject()) {
            throw new IllegalArgumentException(field + " 必须是字符串键值对对象");
        }
        Map<String, String> out = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException(field + " 的值必须是字符串（键 " + entry.getKey() + "）");
            }
            out.put(entry.getKey(), entry.getValue().asText());
        }
        return out;
    }
}
