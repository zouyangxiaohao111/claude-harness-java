package com.nexusai.application.agent.permission.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * settings.json 解析器 · 对齐 CC {@code utils/permissions/permissionsLoader.ts:91-114}
 *
 * <h2>JSON 格式</h2>
 * <pre>
 * {
 *   "permissions": {
 *     "allow": ["Bash", "Edit(/Users/foo/**)"],
 *     "deny":  ["Bash(rm -rf /*)"],
 *     "ask":   ["Bash(npm publish:*)"]
 *   }
 * }
 * </pre>
 *
 * <h2>解析行为</h2>
 * <ul>
 *   <li><b>空文件 / 不存在</b> → 返回空 list（<b>不抛异常</b>，lenient 加载）</li>
 *   <li><b>无 {@code permissions} 字段</b> → 返回空 list</li>
 *   <li><b>{@code permissions} 非 object</b> → 返回空 list</li>
 *   <li><b>bucket 字段缺失</b> → 跳过该 bucket（其他正常解析）</li>
 *   <li><b>条目非字符串</b> → 跳过该条目</li>
 *   <li><b>条目字符串无法解析</b>（{@code PermissionRuleValueParser.parse} 返回 null）→ 跳过该条目</li>
 *   <li><b>JSON 格式错误</b> → 抛 {@link RuntimeException}（调用方 catch 处理）</li>
 * </ul>
 *
 * <h2>顺序保持</h2>
 * <p>返回的 list 按 JSON 中 <b>allow → deny → ask</b> 顺序拼接。
 * 这保证了 10 层规则检查时，相同 toolName 的规则按 settings.json 顺序应用，
 * 与 LlmAgentLoop 的预期一致（也方便 PR 3 后续规则编辑时按位置删除）。
 *
 * <h2>Phase 2 简化</h2>
 * <p>本类只解析 standard format（{@code {"permissions": {"allow": [...]}}}）。
 * Phase 2 后续 PR 2 加 Managed Policy format：
 * <ul>
 *   <li>{@code {"remote": {...}}} — 网络拉取的 enterprise policy</li>
 *   <li>{@code {"mdm": {...}}} — macOS MDM 配置</li>
 *   <li>{@code {"filePolicy": {...}}} — managed policy file</li>
 *   <li>{@code {"hkcu": {...}}} — Windows HKCU 注册表</li>
 * </ul>
 * 4 种 policy 合并 → 最高优先级。
 *
 * <h2>无状态 / 线程安全</h2>
 * <p>{@link ObjectMapper} 线程安全（Jackson 默认），{@link PermissionRuleValueParser} 无状态。
 * Spring 单例 OK。
 */
@Component
public class SettingsJsonParser {

    private static final Logger log = LoggerFactory.getLogger(SettingsJsonParser.class);

    private final ObjectMapper objectMapper;
    private final PermissionRuleValueParser ruleValueParser;

    /**
     * Spring 注入构造器。
     *
     * @param objectMapper   Jackson ObjectMapper（Spring 默认 bean 可用）
     * @param ruleValueParser rule 字符串解析器
     */
    public SettingsJsonParser(ObjectMapper objectMapper, PermissionRuleValueParser ruleValueParser) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is null");
        }
        if (ruleValueParser == null) {
            throw new IllegalArgumentException("ruleValueParser is null");
        }
        this.objectMapper = objectMapper;
        this.ruleValueParser = ruleValueParser;
    }

    /**
     * 解析 settings.json 文件。
     *
     * <h3>行为</h3>
     * <ul>
     *   <li>文件不存在 → 返回空 list（<b>不抛异常</b>，这是最常见情况 —— 首次启动无 settings）</li>
     *   <li>JSON 损坏 → 抛 {@link RuntimeException}（调用方 catch 兜底）</li>
     *   <li>权限拒绝读 → 抛 {@link RuntimeException}</li>
     * </ul>
     *
     * @param path   文件路径
     * @param source 此文件对应的 source（用于标记 rule 来源）
     * @return 规则列表（按 allow → deny → ask 顺序；可能为空；不可变 view）
     * @throws RuntimeException 当文件存在但读取 / 解析失败
     */
    public List<PermissionRule> parse(Path path, PermissionRuleSource source) {
        if (path == null) {
            throw new IllegalArgumentException("path is null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }

        // 文件不存在 → 空 list（这是预期路径，首次启动没有 settings.json）
        if (!Files.exists(path)) {
            return List.of();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(path.toFile());
        } catch (IOException e) {
            // JSON 损坏：抛出供调用方处理（builder 会 catch 并 warn）
            throw new RuntimeException("Failed to parse settings.json: " + path, e);
        }

        // root 可能为 null（如文件全是空白），readTree 返回 MissingNode
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }

        JsonNode permissions = root.path("permissions");
        if (permissions.isMissingNode() || !permissions.isObject()) {
            // 无 permissions 字段或不是 object → 空 list
            return List.of();
        }

        // 按 allow → deny → ask 顺序解析（与 settings.json 写作顺序一致）
        List<PermissionRule> rules = new ArrayList<>();
        parseBucket(permissions, "allow", PermissionBehavior.ALLOW, source, rules);
        parseBucket(permissions, "deny",  PermissionBehavior.DENY,  source, rules);
        parseBucket(permissions, "ask",   PermissionBehavior.ASK,   source, rules);

        // 不可变 view（防御性 copy）
        return List.copyOf(rules);
    }

    /**
     * settings.json 的权限元数据 · 对齐 CC {@code settings.permissions} 下的
     * {@code defaultMode}（settings.ts:575）与 {@code disableBypassPermissionsMode}（settings.ts:576）。
     *
     * @param defaultMode                 CC {@code settings.permissions.defaultMode}（字符串，可为 null）
     * @param disableBypassPermissionsMode CC {@code settings.permissions.disableBypassPermissionsMode === 'disable'}
     */
    public record PermissionsMeta(String defaultMode, boolean disableBypassPermissionsMode) {
        /** 空元数据（settings 缺失 / permissions 缺失 / 文件不存在）。 */
        public static final PermissionsMeta EMPTY = new PermissionsMeta(null, false);
    }

    /**
     * 解析 settings.json 的权限元数据（defaultMode / disableBypassPermissionsMode）。
     *
     * <p>与 {@link #parse(Path, PermissionRuleSource)}（读 allow/deny/ask 桶）互补，
     * 为 {@link com.nexusai.application.agent.permission.InitialPermissionModeResolver} 提供
     * settings 多源输入（CC initialPermissionModeFromCLI 读取 {@code settings.permissions?.defaultMode}
     * 与 {@code settings.permissions?.disableBypassPermissionsMode}）。
     *
     * <h3>行为</h3>
     * <ul>
     *   <li>文件不存在 / 空 / 顶层非 object / permissions 缺失或非 object → {@link PermissionsMeta#EMPTY}
     *       （<b>不抛异常</b>，lenient 加载）</li>
     *   <li>{@code defaultMode} 非文本 → null；{@code disableBypassPermissionsMode} 非 {@code "disable"}
     *       → false</li>
     *   <li>JSON 损坏 → warn 日志 + {@link PermissionsMeta#EMPTY}（对齐 CC getSettings_DEPRECATED
     *       读失败返回空 settings，不阻断启动）</li>
     * </ul>
     *
     * @param path 文件路径
     * @return 权限元数据（defaultMode + disableBypassPermissionsMode）
     */
    public PermissionsMeta parsePermissionsMeta(Path path) {
        if (path == null || !Files.exists(path)) {
            return PermissionsMeta.EMPTY;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(path.toFile());
        } catch (IOException e) {
            log.warn("SettingsJsonParser: {} 解析权限元数据失败（JSON 损坏），返回空元数据", path);
            return PermissionsMeta.EMPTY;
        }
        if (root == null || root.isMissingNode() || root.isNull() || !root.isObject()) {
            return PermissionsMeta.EMPTY;
        }
        JsonNode permissions = root.path("permissions");
        if (permissions.isMissingNode() || !permissions.isObject()) {
            return PermissionsMeta.EMPTY;
        }
        String defaultMode = permissions.path("defaultMode").isTextual()
                ? permissions.path("defaultMode").asText()
                : null;
        boolean disableBypass = "disable".equals(permissions.path("disableBypassPermissionsMode").asText(null));
        return new PermissionsMeta(defaultMode, disableBypass);
    }

    /**
     * 读-改-写合并序列化（对齐 CC {@code updateSettingsForSource}，settings.ts:416-524）。
     *
     * <p><b>行为（mergeWith 语义，settings.ts:473-495）</b>：
     * <ol>
     *   <li><b>读</b>：读取 {@code settingsFile} 现有全部内容（含 hooks/env 及一切未知 key）。</li>
     *   <li><b>改</b>：仅替换 {@code permissions} 对象下的 allow/deny/ask 三个数组
     *       （CC mergeWith 对数组<b>整体替换</b>；空桶写 {@code []}，对齐 CC removeRules
     *       PermissionUpdate.ts:289-293 清空桶语义，避免旧规则残留复活）；
     *       其余 key（hooks/env/additionalDirectories/defaultMode/未知 key）原样保留，顺序不破坏。</li>
     *   <li><b>写</b>：2 空格美化输出 + 结尾换行（对齐 CC {@code jsonStringify(updated, null, 2) + '\n'}，
     *       settings.ts:500-503）。</li>
     * </ol>
     *
     * <p><b>文件不存在 / 顶层非 object</b> → 以空对象为基底合并（对齐 CC
     * {@code mergeWith(existingSettings || {}, ...)}，settings.ts:473-475）。
     *
     * <p><b>JSON 语法损坏</b> → 抛 {@link RuntimeException}，<b>不覆盖写</b>
     * （对齐 CC settings.ts:453-463：语法错误返回 error 而非覆盖写，防止破坏现有配置）。
     *
     * <p>本方法是 CC 写盘链的 Java 落点：{@code persistPermissionUpdate}（PermissionUpdate.ts:222-342）
     * → {@code addPermissionRulesToSettings} / {@code deletePermissionRuleFromSettings}
     * （permissionsLoader.ts:229-296 / :163-216）→ {@code updateSettingsForSource}（settings.ts:416-524）。
     * 旧版「仅输出 permissions 桶」的整文件覆盖写行为已随本重写移除（O16 关闭）。
     *
     * @param settingsFile 目标 settings.json 路径（读-改-写同文件）
     * @param rules        该 source 当前全部规则（空列表 → 三个桶均写 {@code []}）
     * @return 合并后的 JSON 字符串（2 空格美化 + 结尾换行）
     * @throws RuntimeException 当文件存在但 JSON 语法损坏
     */
    public String serialize(Path settingsFile, List<PermissionRule> rules) {
        if (settingsFile == null) {
            throw new IllegalArgumentException("settingsFile is null");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules is null");
        }

        // 1. 读：现有 settings.json 全部内容（未知 key 保留）；文件缺失 → 空对象
        ObjectNode root = readExistingRoot(settingsFile);

        // 2. 改：仅替换 permissions 桶；permissions 缺失或非 object → 用新对象替换
        //    （对齐 CC mergeWith 默认合并：目标非 object 时以 src 替换）
        ObjectNode permissions = ensurePermissionsObject(root);

        // 按 behavior 分桶，空桶也写 []（CC mergeWith 数组整体替换；顺序 allow → deny → ask
        // 对齐 CC SUPPORTED_RULE_BEHAVIORS = ['allow','deny','ask']，permissionsLoader.ts:91-114）
        ArrayNode allowArr = objectMapper.createArrayNode();
        ArrayNode denyArr = objectMapper.createArrayNode();
        ArrayNode askArr = objectMapper.createArrayNode();
        for (PermissionRule rule : rules) {
            switch (rule.ruleBehavior()) {
                case ALLOW -> allowArr.add(rule.ruleValue().toRuleString());
                case DENY -> denyArr.add(rule.ruleValue().toRuleString());
                case ASK -> askArr.add(rule.ruleValue().toRuleString());
            }
        }
        permissions.set("allow", allowArr);
        permissions.set("deny", denyArr);
        permissions.set("ask", askArr);

        if (log.isDebugEnabled()) {
            log.debug("SettingsJsonParser: 合并写回 {} — 保留 {} 个非权限顶层 key，"
                    + "替换权限桶 allow/deny/ask（{} / {} / {} 条）",
                settingsFile, root.size(), allowArr.size(), denyArr.size(), askArr.size());
        }

        // 3. 写：2 空格美化 + 结尾换行（对齐 CC jsonStringify(updated, null, 2) + '\n'）
        return writeJson(root);
    }

    /**
     * 读取 {@code permissions.<field>} 原始字符串数组（不解析、不校验）。
     *
     * <p>对齐 CC {@code getSettingsForSource(destination)} +
     * {@code existingSettings?.permissions?.[field] || []}（PermissionUpdate.ts:248/274/301
     * 读盘路径）。用于增量写盘前读取现有桶内容：
     * <ul>
     *   <li>文件不存在 → 空 list</li>
     *   <li>{@code permissions} 缺失/非 object → 空 list</li>
     *   <li>{@code field} 缺失/非数组 → 空 list</li>
     *   <li>条目非字符串 → 跳过</li>
     *   <li>JSON 语法损坏 → 抛 {@link RuntimeException}（对齐 CC settings.ts:453-463 不覆盖写）</li>
     * </ul>
     *
     * @param settingsFile 目标 settings.json 路径
     * @param field        permissions 下的桶名（{@code allow/deny/ask/additionalDirectories}）
     * @return 原始字符串列表（可能为空；不可变 view）
     */
    public List<String> readPermissionsStringArray(Path settingsFile, String field) {
        if (settingsFile == null) {
            throw new IllegalArgumentException("settingsFile is null");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field is blank");
        }
        if (!Files.exists(settingsFile)) {
            return List.of();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(settingsFile.toFile());
        } catch (IOException e) {
            log.error("SettingsJsonParser: {} JSON 语法损坏，拒绝读取（对齐 CC 不覆盖写语义）", settingsFile);
            throw new RuntimeException("Invalid JSON syntax in settings file at " + settingsFile, e);
        }
        if (root == null || root.isMissingNode() || root.isNull() || !root.isObject()) {
            return List.of();
        }

        JsonNode permissions = root.get("permissions");
        if (permissions == null || !permissions.isObject()) {
            return List.of();
        }
        JsonNode bucket = permissions.get(field);
        if (bucket == null || !bucket.isArray()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (JsonNode entry : bucket) {
            if (entry.isTextual()) {
                out.add(entry.asText());
            }
        }
        return List.copyOf(out);
    }

    /**
     * 单字段 merge 写：读-改-写 {@code permissions.<field>} 数组（整体替换）。
     *
     * <p>对齐 CC {@code updateSettingsForSource(destination, { permissions: { [field]: values } })}
     * （settings.ts:416-524）—— mergeWith 对数组<b>整体替换</b>（:487-491），其余 key 保留。
     * 用于 addRules/removeRules/replaceRules/addDirectories/removeDirectories 落盘。
     *
     * @param settingsFile 目标 settings.json 路径
     * @param field        permissions 下的桶名
     * @param values       新数组值（可为空 → 写 {@code []}，对齐 CC 清空桶语义）
     * @return 合并后的 JSON 字符串（2 空格美化 + 结尾换行）
     * @throws RuntimeException 当文件存在但 JSON 语法损坏
     */
    public String mergeWritePermissions(Path settingsFile, String field, List<String> values) {
        if (settingsFile == null) {
            throw new IllegalArgumentException("settingsFile is null");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field is blank");
        }
        if (values == null) {
            throw new IllegalArgumentException("values is null");
        }

        ObjectNode root = readExistingRoot(settingsFile);
        ObjectNode permissions = ensurePermissionsObject(root);

        ArrayNode arr = objectMapper.createArrayNode();
        for (String v : values) {
            arr.add(v);
        }
        permissions.set(field, arr);

        if (log.isDebugEnabled()) {
            log.debug("SettingsJsonParser: mergeWritePermissions {} 桶 = {} 条（未知 key 保留）",
                field, arr.size());
        }
        return writeJson(root);
    }

    /**
     * 单字段 merge 写：读-改-写 {@code permissions.<field>} 字符串值。
     *
     * <p>对齐 CC {@code updateSettingsForSource(destination, { permissions: { defaultMode: mode } })}
     * （PermissionUpdate.ts:321-325）—— mergeWith 对非数组做深合并，字符串整体替换。用于 setMode 落盘。
     *
     * @param settingsFile 目标 settings.json 路径
     * @param field        permissions 下的字段名（{@code defaultMode}）
     * @param value        新字符串值
     * @return 合并后的 JSON 字符串（2 空格美化 + 结尾换行）
     * @throws RuntimeException 当文件存在但 JSON 语法损坏
     */
    public String mergeWritePermissionsValue(Path settingsFile, String field, String value) {
        if (settingsFile == null) {
            throw new IllegalArgumentException("settingsFile is null");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field is blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("value is null");
        }

        ObjectNode root = readExistingRoot(settingsFile);
        ObjectNode permissions = ensurePermissionsObject(root);
        permissions.put(field, value);

        if (log.isDebugEnabled()) {
            log.debug("SettingsJsonParser: mergeWritePermissionsValue {} = {}（未知 key 保留）", field, value);
        }
        return writeJson(root);
    }

    /**
     * 获取/创建 {@code permissions} 对象节点（原地修改 root）。
     *
     * <p>对齐 CC mergeWith 默认合并：目标 {@code permissions} 非 object 时以新对象替换
     * （settings.ts:473-475 mergeWith(existing || {}, settings)）。
     */
    private ObjectNode ensurePermissionsObject(ObjectNode root) {
        JsonNode permissionsNode = root.get("permissions");
        if (permissionsNode == null || !permissionsNode.isObject()) {
            ObjectNode permissions = objectMapper.createObjectNode();
            root.set("permissions", permissions);
            return permissions;
        }
        return (ObjectNode) permissionsNode;
    }

    /**
     * 序列化 root → 2 空格美化 + 结尾换行（对齐 CC jsonStringify(updated, null, 2) + '\n'）。
     */
    private String writeJson(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (IOException e) {
            // 内存序列化不应失败（Jackson writeValueAsString 对 String 不会抛 IO）
            throw new RuntimeException("Failed to serialize settings to JSON string", e);
        }
    }

    /**
     * 读取现有 settings.json 为 {@link ObjectNode}；文件不存在 / 顶层非 object → 空对象。
     *
     * <p>对齐 CC {@code updateSettingsForSource} 的 existingSettings 读取路径
     * （settings.ts:440-471）：文件缺失 → {@code {}}；JSON 语法损坏 → 返回错误不覆盖写
     * （Java 侧以抛 {@link RuntimeException} 表达，调用方 loader.save 上抛给 Persister）。
     *
     * @param settingsFile 目标文件路径
     * @return 现有根节点（可安全原地修改）；文件缺失/顶层非 object → 空 ObjectNode
     * @throws RuntimeException 当文件存在但 JSON 语法损坏
     */
    private ObjectNode readExistingRoot(Path settingsFile) {
        if (!Files.exists(settingsFile)) {
            if (log.isDebugEnabled()) {
                log.debug("SettingsJsonParser: {} 不存在，以空对象为基底合并写回", settingsFile);
            }
            return objectMapper.createObjectNode();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(settingsFile.toFile());
        } catch (IOException e) {
            // CC settings.ts:453-463：语法损坏 → 返回 error 不覆盖写（防止破坏现有配置）
            log.error("SettingsJsonParser: {} JSON 语法损坏，拒绝覆盖写（对齐 CC 不覆盖语义）", settingsFile);
            throw new RuntimeException("Invalid JSON syntax in settings file at " + settingsFile, e);
        }

        if (root == null || root.isMissingNode() || root.isNull() || !root.isObject()) {
            // 空文件 / null / 顶层非 object（数组、字符串等）→ 以空对象为基底
            return objectMapper.createObjectNode();
        }
        return (ObjectNode) root;
    }

    /**
     * 解析单个 bucket（{@code allow} / {@code deny} / {@code ask} 数组）。
     *
     * <p>静默跳过：
     * <ul>
     *   <li>bucket 字段缺失</li>
     *   <li>bucket 不是 array</li>
     *   <li>条目非字符串</li>
     *   <li>{@link PermissionRuleValueParser#parse(String)} 返回 null</li>
     * </ul>
     *
     * @param permissions JSON 的 permissions 节点
     * @param bucketName  bucket 名（{@code "allow"} / {@code "deny"} / {@code "ask"}）
     * @param behavior    此 bucket 对应的行为
     * @param source      规则来源（标记在每条 rule 上）
     * @param out         输出 list（追加）
     */
    private void parseBucket(JsonNode permissions,
                              String bucketName,
                              PermissionBehavior behavior,
                              PermissionRuleSource source,
                              List<PermissionRule> out) {
        JsonNode bucket = permissions.path(bucketName);
        // bucket 缺失 / 非数组 → 跳过（不抛异常）
        if (bucket.isMissingNode() || !bucket.isArray()) {
            return;
        }

        for (JsonNode entry : bucket) {
            if (!entry.isTextual()) {
                // 非字符串（如 object / number）→ 跳过
                continue;
            }
            PermissionRuleValue value = ruleValueParser.parse(entry.asText());
            if (value == null) {
                // 解析失败 → 跳过该条目（其他条目继续）
                continue;
            }
            out.add(new PermissionRule(source, behavior, value));
        }
    }
}