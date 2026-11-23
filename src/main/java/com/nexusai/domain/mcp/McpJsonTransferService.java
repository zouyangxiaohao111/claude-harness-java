package com.nexusai.domain.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.mcp.McpOAuth;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.mcp.McpServer;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MCP JSON 导入/导出（[2026-11-23] DB 为唯一真源；⛔ 不读写 {@code ~/.nexusai/mcp.json}）。
 *
 * <p><b>导出</b>：{@code {"mcpServers": {name: {type, command|url, args, env|headers,
 * enabled, autoStart, scope}}}}；密钥/头值/URL 查询参数里的密钥一律掩码为 {@code ****末4位}。
 *
 * <p><b>导入</b>：先做严格 schema 校验（未知字段整单拒绝，不部分写入）→ 自动备份到
 * app data（raw 快照，供人工回滚）→ 事务写 DB（新增/更新/按 deleteMissing 删除）→
 * 返回 diff。掩码值表示「保持原值」（新增 server 不允许出现掩码，否则无法恢复）。
 */
@Service
public class McpJsonTransferService {

    private static final Logger log = LoggerFactory.getLogger(McpJsonTransferService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Set<String> SERVER_FIELDS = Set.of(
            "type", "command", "url", "args", "env", "headers", "enabled", "autoStart", "scope");
    private static final Set<String> ROOT_FIELDS = Set.of("mcpServers", "deleteMissing");
    private static final Set<String> SCOPES = Set.of(
            "project", "user", "local", "dynamic", "enterprise", "claudeai", "managed");
    private static final Set<String> DELETABLE_SCOPES = Set.of("project", "user", "local");
    private static final Set<String> SECRET_QUERY_KEYS = Set.of(
            "key", "ak", "apikey", "api_key", "access_token", "token", "code", "secret");

    private final McpServerMapper mcpServerMapper;
    private Path backupDir;

    public McpJsonTransferService(McpServerMapper mcpServerMapper) {
        this.mcpServerMapper = mcpServerMapper;
    }

    /** 测试注入：备份目录（默认 app data/backups）。 */
    void setBackupDirForTests(Path dir) {
        this.backupDir = dir;
    }

    // ───────────────────────── 导出 ─────────────────────────

    /**
     * 导出（掩码）。只含 stdio/http/sse 三类（协议契约固定这三种）。
     */
    public ObjectNode exportMasked() {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        for (McpServerRecord r : mcpServerMapper.selectAll()) {
            McpServer s = r.toDomain();
            String type = s.getType() == null ? "stdio" : s.getType();
            if (!"stdio".equals(type) && !"http".equals(type) && !"sse".equals(type)) {
                continue;
            }
            ObjectNode n = servers.putObject(s.getName());
            n.put("type", type);
            Map<String, String> env = parseEnv(s.getEnv());
            if ("stdio".equals(type)) {
                n.put("command", s.getCommand());
                List<String> args = parseArgs(s.getArgs());
                if (!args.isEmpty()) {
                    ArrayNode arr = n.putArray("args");
                    args.forEach(arr::add);
                }
                if (!env.isEmpty()) {
                    n.set("env", maskedObject(env));
                }
            } else {
                n.put("url", maskUrl(s.getCommand()));
                Map<String, String> headers = McpOAuth.headersOnly(env);
                if (!headers.isEmpty()) {
                    n.set("headers", maskedObject(headers));
                }
            }
            n.put("enabled", Boolean.TRUE.equals(s.getEnabled()));
            n.put("autoStart", Boolean.TRUE.equals(s.getAutoStart()));
            n.put("scope", s.getScope() == null ? "user" : s.getScope());
        }
        return root;
    }

    /** 建议保存路径（仅提示，后端不写这个文件）。 */
    public String suggestedSavePath() {
        return NexusaiPaths.getAppConfigHomePath()
                .resolve("exports").resolve("mcp-servers-" + ts() + ".json").toString();
    }

    private static ObjectNode maskedObject(Map<String, String> map) {
        ObjectNode out = JSON.createObjectNode();
        map.forEach((k, v) -> out.put(k, mask(v)));
        return out;
    }

    // ───────────────────────── 导入 ─────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public ImportResult importJson(JsonNode body) {
        Parsed parsed = parseAndValidate(body);
        List<McpServerRecord> snapshot = mcpServerMapper.selectAll();
        Map<String, McpServerRecord> existing = new LinkedHashMap<>();
        for (McpServerRecord r : snapshot) {
            existing.put(r.getName(), r);
        }

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, McpServerRecord> toWrite = new LinkedHashMap<>();

        for (ImportServer server : parsed.servers()) {
            McpServerRecord old = existing.get(server.name());
            McpServerRecord target = old != null ? old : newRecord(server.name());
            boolean changed = applyToRecord(target, server, old, warnings);
            if (old == null) {
                toWrite.put(server.name(), target);
                added.add(server.name());
            } else if (changed) {
                toWrite.put(server.name(), target);
                updated.add(server.name());
            } else {
                unchanged.add(server.name());
            }
        }

        if (parsed.deleteMissing()) {
            Set<String> payloadNames = parsed.servers().stream().map(ImportServer::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            for (McpServerRecord r : snapshot) {
                if (payloadNames.contains(r.getName())) {
                    continue;
                }
                if (!DELETABLE_SCOPES.contains(r.getScope() == null ? "" : r.getScope())) {
                    warnings.add("跳过删除（非 user/local/project scope）：" + r.getName());
                    continue;
                }
                removed.add(r.getName());
            }
        }

        Path backup = writeBackup(snapshot);
        boolean mutated = false;
        try {
            for (String name : removed) {
                McpServerRecord r = existing.get(name);
                if (r != null) {
                    mcpServerMapper.deleteById(r.getId());
                }
            }
            for (McpServerRecord record : toWrite.values()) {
                if (existing.containsKey(record.getName())) {
                    // ignoreNulls=false：args/env 被清空时也要真正写 NULL（update(entity) 会忽略 null）
                    mcpServerMapper.updateByQuery(record, false,
                            QueryWrapper.create().eq("id", record.getId()));
                } else {
                    mcpServerMapper.insert(record);
                }
            }
            mutated = true;
        } catch (RuntimeException e) {
            restore(snapshot);
            throw e;
        } finally {
            if (!mutated) {
                log.warn("[McpJsonTransfer] 导入失败，已按备份回滚 name added={} updated={} removed={}",
                        added.size(), updated.size(), removed.size());
            }
        }
        log.info("[McpJsonTransfer] 导入完成 added={} updated={} removed={} unchanged={} backup={}",
                added.size(), updated.size(), removed.size(), unchanged.size(), backup);
        return new ImportResult(added.size(), updated.size(), removed.size(), unchanged.size(),
                added, updated, removed, unchanged, backup.toString(), warnings);
    }

    // ── schema 校验 ──

    private Parsed parseAndValidate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ValidationException("导入内容必须是 JSON 对象");
        }
        List<String> unknownRoot = fieldNames(body).stream()
                .filter(k -> !ROOT_FIELDS.contains(k)).toList();
        if (!unknownRoot.isEmpty()) {
            throw new ValidationException("未知字段（顶层）：" + String.join(", ", unknownRoot));
        }
        JsonNode serversNode = body.get("mcpServers");
        if (serversNode == null || !serversNode.isObject()) {
            throw new ValidationException("缺少 mcpServers 对象");
        }
        if (body.has("deleteMissing") && !body.get("deleteMissing").isBoolean()) {
            throw new ValidationException("deleteMissing 必须是布尔值");
        }
        boolean deleteMissing = body.path("deleteMissing").asBoolean(false);
        List<ImportServer> servers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        serversNode.fields().forEachRemaining(e -> {
            try {
                servers.add(parseServer(e.getKey(), e.getValue()));
            } catch (ValidationException ex) {
                errors.add(ex.getMessage());
            }
        });
        if (!errors.isEmpty()) {
            throw new ValidationException("导入校验失败（整单拒绝，未写库）：" + String.join("；", errors));
        }
        return new Parsed(servers, deleteMissing);
    }

    private ImportServer parseServer(String name, JsonNode node) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("server 名不能为空");
        }
        if (node == null || !node.isObject()) {
            throw new ValidationException(name + "：必须是对象");
        }
        List<String> unknown = fieldNames(node).stream()
                .filter(k -> !SERVER_FIELDS.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new ValidationException(name + "：未知字段 " + String.join(", ", unknown));
        }
        String type = text(node, "type");
        if (type == null || !(type.equals("stdio") || type.equals("http") || type.equals("sse"))) {
            throw new ValidationException(name + "：type 必须是 stdio|http|sse");
        }
        String command = text(node, "command");
        String url = text(node, "url");
        if ("stdio".equals(type)) {
            if (url != null) {
                throw new ValidationException(name + "：stdio 不允许 url");
            }
            if (command == null || command.isBlank()) {
                throw new ValidationException(name + "：stdio 缺少 command");
            }
        } else {
            if (command != null) {
                throw new ValidationException(name + "：" + type + " 不允许 command");
            }
            if (url == null || url.isBlank()) {
                throw new ValidationException(name + "：" + type + " 缺少 url");
            }
        }
        List<String> args = stringList(node.get("args"), name + ".args");
        Map<String, String> env = stringMap(node.get("env"), name + ".env");
        Map<String, String> headers = stringMap(node.get("headers"), name + ".headers");
        if ("stdio".equals(type) && !headers.isEmpty()) {
            throw new ValidationException(name + "：stdio 不允许 headers");
        }
        if (!"stdio".equals(type) && !env.isEmpty()) {
            throw new ValidationException(name + "：远程 server 不允许 env（请用 headers）");
        }
        requireBoolean(node, "enabled", name);
        requireBoolean(node, "autoStart", name);
        boolean enabled = node.path("enabled").asBoolean(true);
        boolean autoStart = node.path("autoStart").asBoolean(true);
        String scope = text(node, "scope");
        if (scope == null) {
            scope = "user";
        }
        if (!SCOPES.contains(scope)) {
            throw new ValidationException(name + "：scope 非法（" + scope + "）");
        }
        return new ImportServer(name, type, command, url, args, env, headers,
                enabled, autoStart, scope);
    }

    // ── 应用/合并 ──

    private boolean applyToRecord(McpServerRecord target, ImportServer server,
                                  McpServerRecord old, List<String> warnings) {
        String oldType = old == null ? null : old.getType();
        String oldCommand = old == null ? null : old.getCommand();
        String oldArgs = old == null ? null : old.getArgs();
        String oldEnv = old == null ? null : old.getEnv();
        String oldScope = old == null ? null : old.getScope();
        Boolean oldEnabled = old == null ? null : old.getEnabled();
        Boolean oldAutoStart = old == null ? null : old.getAutoStart();

        String command = server.command();
        if (!"stdio".equals(server.type())) {
            command = resolveMaskedUrl(server.url(), oldCommand, server.name());
        }
        Map<String, String> baseEnv = parseEnv(oldEnv);
        Map<String, String> incoming = "stdio".equals(server.type()) ? server.env() : server.headers();
        incoming.forEach((k, v) -> {
            if (isMasked(v)) {
                String real = baseEnv.get(k);
                if (real == null || real.isBlank()) {
                    throw new ValidationException(server.name() + "：" + k
                            + " 是掩码值，但当前没有可保留的原值");
                }
                baseEnv.put(k, real);
            } else {
                baseEnv.put(k, v);
            }
        });
        String argsJson = server.args().isEmpty() ? null : serializeArgs(server.args());
        String envJson = baseEnv.isEmpty() ? null : serializeMap(baseEnv);

        target.setType(server.type());
        target.setCommand(command);
        target.setArgs(argsJson);
        target.setEnv(envJson);
        target.setScope(server.scope());
        target.setEnabled(server.enabled());
        target.setAutoStart(server.enabled() && server.autoStart());
        target.setLastError(null);
        target.setPid(null);

        return old == null
                || !eq(oldType, server.type()) || !eq(oldCommand, command)
                || !eq(oldArgs, argsJson) || !eq(oldEnv, envJson)
                || !eq(oldScope, server.scope()) || !eq(oldEnabled, target.getEnabled())
                || !eq(oldAutoStart, target.getAutoStart());
    }

    /** 掩码 URL：仅替换查询参数里的敏感值为原值（其余参数逐字保留）；新增时无法恢复 → 报错。 */
    private static String resolveMaskedUrl(String url, String oldUrl, String name) {
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return url;
        }
        String base = url.substring(0, q);
        Map<String, String> query = parseQuery(url.substring(q + 1));
        boolean hasMasked = query.entrySet().stream()
                .anyMatch(e -> SECRET_QUERY_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))
                        && isMasked(e.getValue()));
        if (!hasMasked) {
            return url;
        }
        if (oldUrl == null || oldUrl.isBlank()) {
            throw new ValidationException(name + "：url 含掩码值，但当前没有可保留的原值");
        }
        String oldBase = oldUrl.contains("?") ? oldUrl.substring(0, oldUrl.indexOf('?')) : oldUrl;
        if (!oldBase.equals(base)) {
            throw new ValidationException(name + "：url 含掩码值且地址已变化，无法确定原值");
        }
        Map<String, String> oldQuery = oldUrl.contains("?")
                ? parseQuery(oldUrl.substring(oldUrl.indexOf('?') + 1)) : Map.of();
        StringBuilder sb = new StringBuilder(base).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            String value = e.getValue();
            if (SECRET_QUERY_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT)) && isMasked(value)) {
                String real = oldQuery.get(e.getKey());
                if (real == null) {
                    throw new ValidationException(name + "：url 参数 " + e.getKey() + " 无原值");
                }
                value = real;
            }
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
              .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ── 回滚 / 备份 ──

    private void restore(List<McpServerRecord> snapshot) {
        try {
            Set<String> ids = new LinkedHashSet<>();
            for (McpServerRecord r : snapshot) {
                ids.add(r.getId());
            }
            for (McpServerRecord current : mcpServerMapper.selectAll()) {
                if (!ids.contains(current.getId())) {
                    mcpServerMapper.deleteById(current.getId());
                }
            }
            for (McpServerRecord r : snapshot) {
                if (mcpServerMapper.selectOneById(r.getId()) != null) {
                    mcpServerMapper.updateByQuery(r, false,
                            QueryWrapper.create().eq("id", r.getId()));
                } else {
                    mcpServerMapper.insert(r);
                }
            }
            log.warn("[McpJsonTransfer] 已按导入前快照回滚 {} 行", snapshot.size());
        } catch (Exception e) {
            log.error("[McpJsonTransfer] 回滚失败（备份文件仍可用于人工恢复）: {}", e.toString());
        }
    }

    private Path writeBackup(List<McpServerRecord> snapshot) {
        try {
            Path dir = backupDir != null ? backupDir
                    : NexusaiPaths.getAppConfigHomePath().resolve("backups");
            Files.createDirectories(dir);
            ObjectNode root = JSON.createObjectNode();
            ObjectNode servers = root.putObject("mcpServers");
            for (McpServerRecord r : snapshot) {
                McpServer s = r.toDomain();
                ObjectNode n = servers.putObject(s.getName());
                n.put("type", s.getType() == null ? "stdio" : s.getType());
                if ("stdio".equals(s.getType())) {
                    n.put("command", s.getCommand());
                    List<String> args = parseArgs(s.getArgs());
                    if (!args.isEmpty()) {
                        ArrayNode a = n.putArray("args");
                        args.forEach(a::add);
                    }
                    Map<String, String> env = parseEnv(s.getEnv());
                    if (!env.isEmpty()) {
                        n.set("env", JSON.valueToTree(env));
                    }
                } else {
                    n.put("url", s.getCommand());
                    Map<String, String> headers = McpOAuth.headersOnly(parseEnv(s.getEnv()));
                    if (!headers.isEmpty()) {
                        n.set("headers", JSON.valueToTree(headers));
                    }
                }
                n.put("enabled", Boolean.TRUE.equals(s.getEnabled()));
                n.put("autoStart", Boolean.TRUE.equals(s.getAutoStart()));
                n.put("scope", s.getScope() == null ? "user" : s.getScope());
            }
            Path file = dir.resolve("mcp-servers-" + ts() + "-" + UUID.randomUUID().toString()
                    .substring(0, 6) + ".json");
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            throw new ValidationException("导入前备份失败，已中止（未写库）：" + e.getMessage());
        }
    }

    // ── 小工具 ──

    private static McpServerRecord newRecord(String name) {
        McpServerRecord r = new McpServerRecord();
        r.setId("mcp-" + UUID.randomUUID().toString().substring(0, 8));
        r.setName(name);
        r.setApprovalStatus("approved");
        r.setCreatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return r;
    }

    static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    static boolean isMasked(String value) {
        return value != null && value.startsWith("****");
    }

    /** 只掩码敏感查询参数的值，其余逐字保留。 */
    static String maskUrl(String url) {
        if (url == null || !url.contains("?")) {
            return url;
        }
        int q = url.indexOf('?');
        Map<String, String> query = parseQuery(url.substring(q + 1));
        StringBuilder sb = new StringBuilder(url.substring(0, q)).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            String value = SECRET_QUERY_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))
                    ? mask(e.getValue()) : e.getValue();
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
              .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Map<String, String> parseEnv(String envJson) {
        Map<String, String> env = new LinkedHashMap<>();
        if (envJson == null || envJson.isBlank()) {
            return env;
        }
        try {
            JsonNode node = JSON.readTree(envJson);
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(e -> {
                    if (e.getValue() != null && e.getValue().isValueNode()) {
                        env.put(e.getKey(), e.getValue().asText());
                    }
                });
            }
        } catch (Exception ignored) {
            // 历史脏 env 从空开始
        }
        return env;
    }

    private static List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(argsJson);
            if (node != null && node.isArray()) {
                List<String> out = new ArrayList<>();
                node.forEach(n -> out.add(n.asText()));
                return out;
            }
        } catch (Exception ignored) {
            // 历史脏 args 从空开始
        }
        return List.of();
    }

    private static String serializeArgs(List<String> args) {
        try {
            return JSON.writeValueAsString(args);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 args 失败", e);
        }
    }

    private static String serializeMap(Map<String, String> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 env 失败", e);
        }
    }

    private static List<String> stringList(JsonNode node, String label) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ValidationException(label + " 必须是字符串数组");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (!n.isTextual()) {
                throw new ValidationException(label + " 含非字符串元素");
            }
            out.add(n.asText());
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node, String label) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ValidationException(label + " 必须是字符串键值对象");
        }
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getValue() == null || !e.getValue().isTextual()) {
                throw new ValidationException(label + "." + e.getKey() + " 必须是字符串");
            }
            out.put(e.getKey(), e.getValue().asText());
        });
        return out;
    }

    private static void requireBoolean(JsonNode node, String field, String name) {
        if (node.has(field) && !node.get(field).isBoolean()) {
            throw new ValidationException(name + "：" + field + " 必须是布尔值");
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new ValidationException(field + " 必须是字符串");
        }
        return v.asText();
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String ts() {
        return OffsetDateTime.now().format(TS);
    }

    // ── 结构 ──

    record Parsed(List<ImportServer> servers, boolean deleteMissing) {
    }

    record ImportServer(String name, String type, String command, String url,
                        List<String> args, Map<String, String> env,
                        Map<String, String> headers, boolean enabled,
                        boolean autoStart, String scope) {
    }

    /**
     * 导入 diff。
     *
     * @param backupPath 导入前 raw 快照（app data/backups）；失败时可用于人工恢复
     */
    public record ImportResult(int added, int updated, int removed, int unchanged,
                               List<String> addedNames, List<String> updatedNames,
                               List<String> removedNames, List<String> unchangedNames,
                               String backupPath, List<String> warnings) {
    }
}
