package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /update-config settings JSON Schema 生成器 · 对齐 CC updateConfig.ts:10-13
 * {@code generateSettingsSchema() = toJSONSchema(SettingsSchema(), { io: 'input' })}。
 *
 * <p><b>P2-11 / DEL-05 动态校验</b>：CC 在运行时从 Zod {@code SettingsSchema}（utils/settings/types.ts）
 * 动态生成 JSON Schema（每次 {@code generateSettingsSchema()} 调用 toJSONSchema 都与真实类型同步）；
 * Java 旧实现为<b>手写静态 JSON 字符串</b>（原 REAL_SETTINGS_SCHEMA），CC 增删字段需人工同步 → 漂移
 * （EV-WF3-BD-181 △）。本类把静态字符串替换为<b>程序化生成</b>：从声明式字段定义 {@link #FIELDS}
 * （镜像 CC SettingsSchema 字段）在运行时构建 JSON Schema，字段增删只改定义一处、schema 自动再生成
 * （类加载 compute-once，语义对齐 CC 每次调用的同步效果）。
 *
 * <p>字段定义与旧静态串逐字段等价（全部来自 CC types.ts SettingsSchema 手抄，键序 = CC 字面量序）；
 * 特征说明见 {@link #REAL_SETTINGS_SCHEMA} 的已知 △（feature-gated 字段省略、$schema const 等价表达）。
 */
public final class SettingsSchemaGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SettingsSchemaGenerator() {}

    /** 字段名 → JSON Schema 节点（LinkedHashMap 保 CC 字面量序）。 */
    private static final Map<String, JsonNode> FIELDS = new LinkedHashMap<>();

    static {
        // ---- 顶层 $schema（CC z.literal(CLAUDE_CODE_SETTINGS_SCHEMA_URL)，types.ts:258-259）----
        ObjectNode schema = obj();
        schema.put("type", "string");
        schema.put("const", "https://json.schemastore.org/claude-code-settings.json");
        schema.put("description", "JSON Schema reference for Claude Code settings");
        FIELDS.put("$schema", schema);

        putStr("apiKeyHelper", "Path to a script that outputs authentication values");
        putStr("awsCredentialExport", "Path to a script that exports AWS credentials");
        putStr("awsAuthRefresh", "Path to a script that refreshes AWS authentication");
        putStr("gcpAuthRefresh", "Command to refresh GCP authentication");

        // fileSuggestion
        ObjectNode fileSuggestion = obj();
        fileSuggestion.put("type", "object");
        ObjectNode fsProps = fileSuggestion.putObject("properties");
        fsProps.set("type", enumNode("command"));
        fsProps.set("command", typeNode("string"));
        fileSuggestion.put("additionalProperties", true);
        FIELDS.put("fileSuggestion", fileSuggestion);

        putBool("respectGitignore", "Whether file picker should respect .gitignore files (default: true)");

        ObjectNode cleanupPeriodDays = obj();
        cleanupPeriodDays.put("type", "integer");
        cleanupPeriodDays.put("minimum", 0);
        cleanupPeriodDays.put("description", "Number of days to retain chat transcripts (default: 30; 0 disables persistence)");
        FIELDS.put("cleanupPeriodDays", cleanupPeriodDays);

        // env（z.record(string→string)）
        ObjectNode env = obj();
        env.put("type", "object");
        env.set("additionalProperties", typeNode("string"));
        env.put("description", "Environment variables to set for Claude Code sessions");
        FIELDS.put("env", env);

        // attribution
        ObjectNode attribution = obj();
        attribution.put("type", "object");
        ObjectNode attrProps = attribution.putObject("properties");
        attrProps.set("commit", typeNode("string"));
        attrProps.set("pr", typeNode("string"));
        attribution.put("additionalProperties", true);
        FIELDS.put("attribution", attribution);

        putBool("includeCoAuthoredBy", "Deprecated: Use attribution instead");
        putBool("includeGitInstructions", "Include built-in commit and PR workflow instructions (default: true)");

        // permissions
        ObjectNode permissions = obj();
        permissions.put("type", "object");
        ObjectNode permProps = permissions.putObject("properties");
        permProps.set("allow", stringArray());
        permProps.set("deny", stringArray());
        permProps.set("ask", stringArray());
        permProps.set("defaultMode", enumNode("default", "acceptEdits", "plan", "dontAsk", "bypassPermissions", "ignore"));
        permProps.set("disableBypassPermissionsMode", enumNode("disable"));
        permProps.set("additionalDirectories", stringArray());
        permissions.put("additionalProperties", true);
        FIELDS.put("permissions", permissions);

        putStr("model", "Override the default model used by Claude Code");
        FIELDS.put("availableModels", stringArray());

        // modelOverrides（z.record(string→string)）
        ObjectNode modelOverrides = obj();
        modelOverrides.put("type", "object");
        modelOverrides.set("additionalProperties", typeNode("string"));
        FIELDS.put("modelOverrides", modelOverrides);

        putBool("enableAllProjectMcpServers", null);
        FIELDS.put("enabledMcpjsonServers", stringArray());
        FIELDS.put("disabledMcpjsonServers", stringArray());

        // allowedMcpServers / deniedMcpServers（数组 of {serverName/serverCommand/serverUrl}）
        FIELDS.put("allowedMcpServers", mcpServerArray());
        FIELDS.put("deniedMcpServers", mcpServerArray());

        // hooks（z.record(任意)）
        ObjectNode hooks = obj();
        hooks.put("type", "object");
        hooks.put("additionalProperties", true);
        hooks.put("description", "Custom commands to run before/after tool executions");
        FIELDS.put("hooks", hooks);

        // worktree
        ObjectNode worktree = obj();
        worktree.put("type", "object");
        ObjectNode wtProps = worktree.putObject("properties");
        wtProps.set("symlinkDirectories", stringArray());
        wtProps.set("sparsePaths", stringArray());
        worktree.put("additionalProperties", true);
        FIELDS.put("worktree", worktree);

        putBool("disableAllHooks", null);
        FIELDS.put("defaultShell", enumNode("bash", "powershell"));
        putBool("allowManagedHooksOnly", null);
        FIELDS.put("allowedHttpHookUrls", stringArray());
        FIELDS.put("httpHookAllowedEnvVars", stringArray());
        putBool("allowManagedPermissionRulesOnly", null);
        putBool("allowManagedMcpServersOnly", null);

        // strictPluginOnlyCustomization（type: [boolean, array]，items 枚举 skills/agents/hooks/mcp）
        ObjectNode strictPluginOnly = obj();
        ArrayNode strictType = strictPluginOnly.putArray("type");
        strictType.add("boolean");
        strictType.add("array");
        ObjectNode strictItems = strictPluginOnly.putObject("items");
        strictItems.put("type", "string");
        ArrayNode strictEnum = strictItems.putArray("enum");
        for (String v : new String[]{"skills", "agents", "hooks", "mcp"}) {
            strictEnum.add(v);
        }
        FIELDS.put("strictPluginOnlyCustomization", strictPluginOnly);

        // statusLine
        ObjectNode statusLine = obj();
        statusLine.put("type", "object");
        ObjectNode slProps = statusLine.putObject("properties");
        slProps.set("type", enumNode("command"));
        slProps.set("command", typeNode("string"));
        slProps.set("padding", typeNode("number"));
        statusLine.put("additionalProperties", true);
        FIELDS.put("statusLine", statusLine);

        FIELDS.put("enabledPlugins", passthroughObject());
        FIELDS.put("extraKnownMarketplaces", passthroughObject());
        FIELDS.put("strictKnownMarketplaces", passthroughObjectArray());
        FIELDS.put("blockedMarketplaces", passthroughObjectArray());

        FIELDS.put("forceLoginMethod", enumNode("claudeai", "console"));
        putStr("forceLoginOrgUUID", null);
        putStr("otelHeadersHelper", null);
        putStr("outputStyle", null);
        putStr("language", null);
        putBool("skipWebFetchPreflight", null);
        FIELDS.put("sandbox", passthroughObject());

        ObjectNode feedbackSurveyRate = obj();
        feedbackSurveyRate.put("type", "number");
        feedbackSurveyRate.put("minimum", 0);
        feedbackSurveyRate.put("maximum", 1);
        FIELDS.put("feedbackSurveyRate", feedbackSurveyRate);

        putBool("spinnerTipsEnabled", null);

        // spinnerVerbs
        ObjectNode spinnerVerbs = obj();
        spinnerVerbs.put("type", "object");
        ObjectNode svProps = spinnerVerbs.putObject("properties");
        svProps.set("mode", enumNode("append", "replace"));
        svProps.set("verbs", stringArray());
        spinnerVerbs.put("additionalProperties", true);
        FIELDS.put("spinnerVerbs", spinnerVerbs);

        // spinnerTipsOverride
        ObjectNode spinnerTipsOverride = obj();
        spinnerTipsOverride.put("type", "object");
        ObjectNode stoProps = spinnerTipsOverride.putObject("properties");
        stoProps.set("excludeDefault", typeNode("boolean"));
        stoProps.set("tips", stringArray());
        spinnerTipsOverride.put("additionalProperties", true);
        FIELDS.put("spinnerTipsOverride", spinnerTipsOverride);

        putBool("syntaxHighlightingDisabled", null);
        putBool("terminalTitleFromRename", null);
        putBool("alwaysThinkingEnabled", null);
        FIELDS.put("effortLevel", enumNode("low", "medium", "high"));
        putStr("advisorModel", null);
        putBool("fastMode", null);
        putBool("fastModePerSessionOptIn", null);
        putBool("promptSuggestionEnabled", null);
        putBool("showClearContextOnPlanAccept", null);
        putStr("agent", null);
        FIELDS.put("companyAnnouncements", stringArray());
        FIELDS.put("pluginConfigs", passthroughObject());

        // remote
        ObjectNode remote = obj();
        remote.put("type", "object");
        ObjectNode remoteProps = remote.putObject("properties");
        remoteProps.set("defaultEnvironmentId", typeNode("string"));
        remote.put("additionalProperties", true);
        FIELDS.put("remote", remote);

        FIELDS.put("autoUpdatesChannel", enumNode("latest", "stable"));
        putStr("minimumVersion", null);
        putStr("plansDirectory", null);
        putBool("channelsEnabled", null);

        // allowedChannelPlugins
        ObjectNode allowedChannelPlugins = obj();
        allowedChannelPlugins.put("type", "array");
        ObjectNode acpItems = allowedChannelPlugins.putObject("items");
        acpItems.put("type", "object");
        ObjectNode acpProps = acpItems.putObject("properties");
        acpProps.set("marketplace", typeNode("string"));
        acpProps.set("plugin", typeNode("string"));
        acpItems.put("additionalProperties", true);
        FIELDS.put("allowedChannelPlugins", allowedChannelPlugins);

        putBool("prefersReducedMotion", null);
        putBool("autoMemoryEnabled", null);
        putStr("autoMemoryDirectory", null);
        // [V56 · 用户 2026-08-30 拍板] autoDreamEnabled 不再由 settings.json 承载——改由 DB settings
        //   列 auto_dream_enabled 主控（默认开，弃文件）。schema 声明移除（旧声明已孤立；
        //   BundledSkillEnabledGates.writeAutoMemoryToggles 不再写 autoDreamEnabled 文件键）。
        putBool("showThinkingSummaries", null);
        putBool("skipDangerousModePermissionPrompt", null);
        FIELDS.put("disableAutoMode", enumNode("disable"));
        FIELDS.put("sshConfigs", passthroughObjectArray());
        FIELDS.put("claudeMdExcludes", stringArray());
        putStr("pluginTrustMessage", null);
    }

    /**
     * 生成完整 settings JSON Schema（对齐 CC toJSONSchema(SettingsSchema(), {io:'input'}) 产物形状）·
     * 顶层 {@code type:object + additionalProperties:true（.passthrough()）+ properties 全部字段}。
     */
    public static String generate() {
        ObjectNode root = obj();
        root.put("type", "object");
        root.put("additionalProperties", true);
        ObjectNode properties = root.putObject("properties");
        for (Map.Entry<String, JsonNode> e : FIELDS.entrySet()) {
            properties.set(e.getKey(), e.getValue());
        }
        return emit(root, 0);
    }

    // ============== 字段构建 helper ==============

    private static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    /** { "type": t } */
    private static ObjectNode typeNode(String type) {
        ObjectNode o = obj();
        o.put("type", type);
        return o;
    }

    /** { "type": "string", "description": d }（description 可空） */
    private static void putStr(String name, String description) {
        ObjectNode o = typeNode("string");
        if (description != null) {
            o.put("description", description);
        }
        FIELDS.put(name, o);
    }

    /** { "type": "boolean", "description": d }（description 可空） */
    private static void putBool(String name, String description) {
        ObjectNode o = typeNode("boolean");
        if (description != null) {
            o.put("description", description);
        }
        FIELDS.put(name, o);
    }

    /** { "type": "string", "enum": [...] } */
    private static ObjectNode enumNode(String... values) {
        ObjectNode o = typeNode("string");
        ArrayNode en = o.putArray("enum");
        for (String v : values) {
            en.add(v);
        }
        return o;
    }

    /** { "type": "array", "items": { "type": "string" } } */
    private static ObjectNode stringArray() {
        ObjectNode o = typeNode("array");
        o.set("items", typeNode("string"));
        return o;
    }

    /** { "type": "object", "additionalProperties": true }（CC z.record(任意)/.passthrough() 形态） */
    private static ObjectNode passthroughObject() {
        ObjectNode o = typeNode("object");
        o.put("additionalProperties", true);
        return o;
    }

    /** { "type": "array", "items": { "type": "object", "additionalProperties": true } } */
    private static ObjectNode passthroughObjectArray() {
        ObjectNode o = typeNode("array");
        o.set("items", passthroughObject());
        return o;
    }

    /** allowedMcpServers / deniedMcpServers 数组项形态：{ serverName/serverCommand/serverUrl, additionalProperties:true } */
    private static ObjectNode mcpServerArray() {
        ObjectNode o = typeNode("array");
        ObjectNode item = o.putObject("items");
        item.put("type", "object");
        ObjectNode props = item.putObject("properties");
        props.set("serverName", typeNode("string"));
        props.set("serverCommand", stringArray());
        props.set("serverUrl", typeNode("string"));
        item.put("additionalProperties", true);
        return o;
    }

    // ============== JSON 发射器（对齐旧手写 2 空格格式）==============

    /**
     * 把 JsonNode 发射为 2 空格缩进 JSON 文本。简单节点（值全为标量）内联单行（对齐旧手写格式，
     * 如 {@code "model": { "type": "string", ... }}）；含嵌套容器节点展开多行。
     */
    private static String emit(JsonNode node, int indent) {
        StringBuilder sb = new StringBuilder();
        emit(node, indent, sb);
        return sb.toString();
    }

    private static void emit(JsonNode node, int indent, StringBuilder sb) {
        if (node.isObject()) {
            if (isSimple(node)) {
                sb.append('{');
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                int i = 0;
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (i++ > 0) {
                        sb.append(", ");
                    }
                    sb.append('"').append(e.getKey()).append("\": ");
                    emitScalar(e.getValue(), sb);
                }
                sb.append('}');
            } else {
                sb.append("{\n");
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                int count = node.size();
                int i = 0;
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    indent(sb, indent + 1);
                    sb.append('"').append(e.getKey()).append("\": ");
                    emit(e.getValue(), indent + 1, sb);
                    if (++i < count) {
                        sb.append(',');
                    }
                    sb.append('\n');
                }
                indent(sb, indent);
                sb.append('}');
            }
        } else if (node.isArray()) {
            if (isSimple(node)) {
                sb.append('[');
                int i = 0;
                for (JsonNode v : node) {
                    if (i++ > 0) {
                        sb.append(", ");
                    }
                    emitScalar(v, sb);
                }
                sb.append(']');
            } else {
                sb.append("[\n");
                int count = node.size();
                int i = 0;
                for (JsonNode v : node) {
                    indent(sb, indent + 1);
                    emit(v, indent + 1, sb);
                    if (++i < count) {
                        sb.append(',');
                    }
                    sb.append('\n');
                }
                indent(sb, indent);
                sb.append(']');
            }
        } else {
            emitScalar(node, sb);
        }
    }

    /** 容器节点是否「简单」（所有子节点均为标量 → 可内联单行）。 */
    private static boolean isSimple(JsonNode node) {
        for (JsonNode v : node) {
            if (v.isContainerNode()) {
                return false;
            }
        }
        return true;
    }

    private static void emitScalar(JsonNode node, StringBuilder sb) {
        sb.append(node.toString());
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }
}
