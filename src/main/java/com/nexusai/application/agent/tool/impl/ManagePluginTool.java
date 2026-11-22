package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.plugin.PluginInstallService;
import com.nexusai.application.agent.skill.InstallPluginRegistrar;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.market.dto.PluginInstallRequest;
import com.nexusai.model.market.dto.PluginInstallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ManagePlugin 工具 · 插件的<strong>装 / 列 / 卸</strong>（合成一个工具，按 {@code action} 分发）。
 *
 * <p><b>WHY 合成</b>：每个单动作工具都要带一份 name + description + prompt + schema ⇒ 常驻上下文成本
 * 随工具数线性增长。合成一个后模型仍是「一次调用干一件事」，但 schema 只付一份。
 *
 * <p><b>三个动作</b>：
 * <ul>
 *   <li>{@code install} — 装市场插件：走完整安装链（落 installed_plugins.json → 解析依赖 →
 *       注册插件自带的 MCP server → 刷新技能/命令/agent 注册表），装完立刻 {@code /名字} 可召唤；</li>
 *   <li>{@code list} — 列已装插件（名字/版本/scope/路径）；</li>
 *   <li>{@code uninstall} — 卸插件（破坏性 ⇒ {@code confirmed} 守卫）。</li>
 * </ul>
 *
 * <p><b>为什么要走工具而不是让模型 curl 手搓</b>：手搓只会得到「一个文件在盘上」——不落记录、不解析依赖、
 * 不注册插件自带 MCP、不刷新注册表 ⇒ 插件不会真正生效。
 */
@Component
public class ManagePluginTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ManagePluginTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PluginInstallService pluginInstallService;

    @Autowired
    public ManagePluginTool(PluginInstallService pluginInstallService) {
        this.pluginInstallService = pluginInstallService;
    }

    @Override
    public String name() {
        return "ManagePlugin";
    }

    @Override
    public String description() {
        return "Manage marketplace plugins (skill / expert / connector packages) via one action.\n"
             + "action=install — install a plugin: it is recorded, its dependencies are resolved, the MCP\n"
             + "  servers it ships are registered and connected, and its skills / commands / agents become\n"
             + "  invocable immediately (no restart needed).\n"
             + "action=list — list installed plugins (name, version, scope, path).\n"
             + "action=uninstall — remove an installed plugin (destructive; requires confirmed=true).\n"
             + "\nUsage examples:\n"
             + "- `managePlugin({ action: \"install\", pluginId: \"zjky-data-analyst@zjky-market\" })`\n"
             + "- `managePlugin({ action: \"list\" })`\n"
             + "- `managePlugin({ action: \"uninstall\", name: \"zjky-code-reviewer\", confirmed: true })`";
    }

    @Override
    public String prompt() {
        return "Manage marketplace plugins.\n"
             + "\nParameters (only the ones the chosen action needs):\n"
             + "- action (required): `install` | `list` | `uninstall`.\n"
             + "- pluginId (install): plugin identifier `name@marketplace` (e.g. `zjky-data-analyst@zjky-market`).\n"
             + "- marketplaceUrl (install, optional): marketplace index URL; empty = default marketplace.\n"
             + "- scope (install, optional): `user` (default) | `project` | `local`.\n"
             + "- name (uninstall): installed plugin name (e.g. `zjky-code-reviewer`).\n"
             + "- confirmed (uninstall, required): must be true — set it only after the user confirmed the\n"
             + "  removal in the conversation; otherwise the call is rejected without any side effect.\n"
             + "\nA plugin package may contain skills, agents and commands — tell the user what it contains\n"
             + "BEFORE installing. MCP servers shipped inside a plugin are registered and connected along\n"
             + "with the plugin.";
    }

    @Override
    public JsonNode inputSchema() {
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        var props = schema.putObject("properties");
        var action = props.putObject("action");
        action.put("type", "string");
        action.putArray("enum").add("install").add("list").add("uninstall");
        action.put("description", "What to do: install | list | uninstall");
        props.putObject("pluginId").put("type", "string")
            .put("description", "[install] Plugin identifier `name@marketplace`");
        props.putObject("marketplaceUrl").put("type", "string")
            .put("description", "[install] Optional marketplace index URL. Empty = default marketplace");
        props.putObject("scope").put("type", "string")
            .put("description", "[install] Install scope: user (default) | project | local");
        props.putObject("name").put("type", "string")
            .put("description", "[uninstall] Installed plugin name (e.g. `zjky-code-reviewer`)");
        props.putObject("confirmed").put("type", "boolean")
            .put("description", "[uninstall] Must be true — set only after the user confirmed the removal");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return "list".equals(actionOf(input));
    }

    @Override
    public boolean isDestructive(JsonNode input) {
        return "uninstall".equals(actionOf(input));
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
            case "install" -> install(call, input);
            case "list" -> list(call);
            case "uninstall" -> uninstall(call, input);
            default -> ToolResult.error(call.id(),
                "Error: action 必须是 install / list / uninstall 之一，收到：" + action);
        };
    }

    // ── action=install ────────────────────────────────────────────────
    private ToolResult<?> install(ToolUseBlock call, JsonNode input) {
        String pluginId = readText(input, "pluginId");
        if (pluginId.isBlank()) {
            return ToolResult.error(call.id(),
                "Error: pluginId 不能为空（格式 `name@marketplace`，例如 `zjky-data-analyst@zjky-market`）");
        }
        String marketplaceUrl = readText(input, "marketplaceUrl");
        if (marketplaceUrl.isBlank()) {
            // 市场 URL 是环境派生值 ⇒ 后端给，不让模型填
            marketplaceUrl = InstallPluginRegistrar.DEFAULT_MARKET_MANIFEST_URL;
        }
        String scope = readText(input, "scope");

        PluginInstallResult result;
        try {
            result = pluginInstallService.install(
                new PluginInstallRequest(pluginId, marketplaceUrl, scope));
        } catch (Exception e) {
            log.warn("[ManagePluginTool] 安装异常 plugin={}: {}", pluginId, e.getMessage());
            return ToolResult.error(call.id(), "Error: 插件安装异常：" + e.getMessage());
        }
        if (result == null || !result.success()) {
            String message = result == null ? "无返回结果" : result.message();
            log.warn("[ManagePluginTool] 安装失败 plugin={} msg={}", pluginId, message);
            return ToolResult.error(call.id(), "Error: 插件安装失败：" + message);
        }
        String name = result.pluginName() != null && !result.pluginName().isBlank()
            ? result.pluginName() : result.pluginId();
        if (log.isInfoEnabled()) {
            log.info("[ManagePluginTool] 安装成功 plugin={} scope={}", result.pluginId(), result.scope());
        }
        return ToolResult.success(call.id(),
            "已安装插件 " + name + "（" + result.pluginId() + "），scope=" + result.scope()
                + "。插件的技能/命令已热加载，可直接用 /" + name + " 调用。");
    }

    // ── action=list ───────────────────────────────────────────────────
    private ToolResult<?> list(ToolUseBlock call) {
        List<PluginInstallService.InstalledPluginDetail> installed;
        try {
            installed = pluginInstallService.listInstalledDetails();
        } catch (Exception e) {
            log.warn("[ManagePluginTool] 读取已装插件失败: {}", e.getMessage());
            return ToolResult.error(call.id(), "Error: 读取已装插件失败：" + e.getMessage());
        }
        if (installed == null || installed.isEmpty()) {
            return ToolResult.success(call.id(), "当前没有已安装的插件。");
        }
        StringBuilder sb = new StringBuilder("已安装插件（").append(installed.size()).append("）：\n");
        for (PluginInstallService.InstalledPluginDetail d : installed) {
            sb.append("- ").append(d.name())
              .append("  version=").append(d.version())
              .append("  path=").append(d.installPath())
              .append('\n');
        }
        return ToolResult.success(call.id(), sb.toString().stripTrailing());
    }

    // ── action=uninstall ──────────────────────────────────────────────
    private ToolResult<?> uninstall(ToolUseBlock call, JsonNode input) {
        String name = readText(input, "name");
        if (name.isBlank()) {
            return ToolResult.error(call.id(), "Error: name 不能为空（要卸载的插件名）");
        }
        if (!isConfirmed(input)) {
            return ToolResult.error(call.id(),
                "Error: 卸载是破坏性操作：尚未得到用户确认（confirmed 不为 true），本次未做任何改动。"
                + "请先在会话里告诉用户要卸载哪个插件、等用户确认后再带 confirmed=true 调用。");
        }
        PluginInstallResult result;
        try {
            result = pluginInstallService.uninstall(name);
        } catch (Exception e) {
            log.warn("[ManagePluginTool] 卸载异常 plugin={}: {}", name, e.getMessage());
            return ToolResult.error(call.id(), "Error: 卸载插件异常：" + e.getMessage());
        }
        if (result == null || !result.success()) {
            String message = result == null ? "无返回结果" : result.message();
            return ToolResult.error(call.id(), "Error: 卸载插件失败：" + message);
        }
        if (log.isInfoEnabled()) {
            log.info("[ManagePluginTool] 卸载成功 plugin={}", name);
        }
        return ToolResult.success(call.id(),
            "已卸载插件 " + name + "（" + result.message() + "）。插件的技能/命令已从注册表移除。");
    }

    // ── 工具 ──────────────────────────────────────────────────────────
    private static String actionOf(JsonNode input) {
        String action = readText(input, "action").toLowerCase();
        return action.isBlank() ? "" : action;
    }

    /** confirmed 字段 · 缺省/非布尔 → false（宁可拒绝，不可误删）。 */
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
}
