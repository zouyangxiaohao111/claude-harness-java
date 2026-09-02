package com.nexusai.application.agent.settings.storage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * [R32-b7a-2 Phase 3] FileConfigStorage · Jackson tree merge + 原子写 + 写锁实现.
 *
 * <h2>CC 对齐</h2>
 * <ul>
 *   <li>global source (theme/verbose/autoCompactEnabled 等) → {@code {user.home}/.nexusai.json}
 *       顶层 key 读写 (对齐 CC {@code ~/.claude.json} env.ts:14-24 单点顶层文件).</li>
 *   <li>settings source (model/permissions.defaultMode/language 等) →
 *       {@code NexusaiPaths.getAppConfigHomeDir()/settings.json}（= {user.home}/.nexusai/settings.json）
 *       嵌套 path 读写.</li>
 * </ul>
 * {@code <root>} = {@link NexusaiPaths#getAppConfigHomeDir()}（{@code {user.home}/.{appName}}，
 * 决策 D1 统一自有根）；{@code {appName}} = {@code spring.application.name} 默认 nexusai
 * （见 {@link NexusaiPaths#getAppName()}）。{@code nexusai.home} / env {@code NEXUSAI_HOME} 已废弃
 * （G3 第二轮拍板），不再读取；global 缺省 {@code {user.home}/.nexusai.json}，settings 缺省
 * {@code {user.home}/.nexusai/settings.json}（可经 {@code nexusai.config.global-file / settings-file} 覆盖）。
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>Jackson tree merge</b>: writeSettings(path, value) 只动 path 对应分支, 不影响同级
 *       其他 path (e.g. 设 {@code permissions.defaultMode="plan"} 不会丢 {@code permissions.denyRules}).</li>
 *   <li><b>原子写</b>: tmp 文件 + {@code Files.move(..., ATOMIC_MOVE)} rename — 防止中途崩溃导致文件残缺.</li>
 *   <li><b>写锁</b>: 单一 {@link ReentrantLock} 串行化所有写 (global + settings) — 避免并发写相互覆盖.</li>
 *   <li><b>cache invalidation</b>: 写后立即 reload cache + notify listener.</li>
 *   <li><b>round-trip 保证</b>: 写 {@code null} → JSON null (用 {@link NullMarker} 区分),
 *       写 absent → key 移除.</li>
 *   <li><b>Absent vs JSON null</b>: {@link #readGlobal(String)} / {@link #readSettings(List)}
 *       返回 {@code null} ⇔ key/path 不存在; 返回 {@link #NullMarker} ⇔ key/path 存在但值 JSON null.</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>读路径使用 {@link ConcurrentHashMap} cache — 多 reader 并发.</li>
 *   <li>写路径持有 {@link ReentrantLock} — 写期间阻止其他写;读路径不持锁 (cache 视角).</li>
 * </ul>
 *
 * @see ConfigStorage interface
 * @see ConfigStorageProperties 路径配置
 */
@Component
@DependsOn("nexusaiAppNameInitializer")
public class FileConfigStorage implements ConfigStorage {

    private static final Logger log = LoggerFactory.getLogger(FileConfigStorage.class);

    private final ConfigStorageProperties properties;

    /** 写锁 — 串行化所有 write/unset 操作. */
    private final ReentrantLock writeLock = new ReentrantLock();

    /** global cache: file → parsed ObjectNode. null 表示尚未加载. */
    private volatile ObjectNode globalCache;

    /** settings cache: file → parsed ObjectNode. null 表示尚未加载. */
    private volatile ObjectNode settingsCache;

    /** 监听器列表 — 写后变更通知. */
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    /** Jackson — 写时 pretty + INDENT_OUTPUT 便于人工审阅. */
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    /** [MPL8-D6e] record/POJO → JsonNode 对象序列化（KnownMarketplace 等 record 不再落 textNode 字符串）. */
    private static final ObjectMapper RECORD_MAPPER = new ObjectMapper();

    // ── 构造 ──────────────────────────────────────────────────────────────

    public FileConfigStorage(ConfigStorageProperties properties) {
        // 决策 D1 + G3 第二轮拍板：不再注入 nexusai.home / NEXUSAI_HOME（已废弃）。根路径惰性解析
        //   （见 {@link #home()}）委托 NexusaiPaths.getAppConfigHomeDir()——依赖动态 appName
        //   （NexusaiAppNameInitializer @PostConstruct 写入，本类 @DependsOn 兜底时序），
        //   不在构造器 EAGER 冻结，避免以默认 appName 冻结根导致路径错位。
        this.properties = properties;
    }

    /**
     * 自有根解析 · 决策 D1 + G3 第二轮拍板：直接委托
     * {@link NexusaiPaths#getAppConfigHomeDir()}（= {user.home}/.{appName}，appName = nexusai）。
     *
     * <p>WHY 惰性：appName 由 {@link NexusaiAppNameInitializer} @PostConstruct 写入，本类
     * {@code @DependsOn} 兜底时序；构造器 EAGER 冻结会以默认 appName 冻结根导致路径错位。
     * NexusaiPaths 本身按调用时点解析，读写路径（globalFilePath/settingsFilePath）运行时
     * 调用即已拿到正确根。
     *
     * @return 归一化绝对自有根路径（{user.home}/.nexusai）
     */
    private String home() {
        return NexusaiPaths.getAppConfigHomeDir();
    }

    @PostConstruct
    void warmUp() {
        // 启动时确保自有根 {user.home}/.nexusai 存在（settings.json 父目录，决策 D1），失败不致命
        // (后续 write 会重试). home() 在此首次解析（@DependsOn 已保证 NexusaiAppNameInitializer @PostConstruct 先行）。
        if (log.isInfoEnabled()) {
            log.info("[FileConfigStorage] 初始化: nexusaiHome={}, globalFile={}, settingsFile={}",
                home(), globalFilePath(), settingsFilePath());
        }
        Path root = Paths.get(home());
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("[FileConfigStorage] 创建配置目录失败: {} — 后续写入将重试", root, e);
        }
    }

    // ── 路径解析 ──────────────────────────────────────────────────────────

    /**
     * 实际全局配置文件路径（{@code ConfigStorageProperties.getGlobalFile()} 覆盖优先，
     * 缺省 {@code {user.home}/.nexusai.json}，对齐 CC {@code ~/.claude.json} 用户级全局文件，决策 D1）。
     *
     * <p>public：供 McpConfigFileWriter.globalConfigFilePath() 委托（describeMcpConfigFilePath
     * 必须报告与真实写入一致的路径——若此处报告 hard-code 的 nexusaiHome 路径而实际写入
     * 覆盖路径，前端展示「已写入 <path>」会指向错误位置）。
     */
    public Path globalFilePath() {
        if (properties != null && properties.getGlobalFile() != null
                && !properties.getGlobalFile().isBlank()) {
            return Paths.get(properties.getGlobalFile());
        }
        // 决策 D1 固定名：全局文件默认 {user.home}/.nexusai.json（对齐 CC ~/.claude.json，
        // CC getGlobalClaudeFilePath 固定 homedir()/.claude.json，不受 CLAUDE_CONFIG_DIR 影响）。
        // settingsFilePath() 走 home()=~/.{appName} 动态 —— 与 CC 不对称一致（全局文件固定 homedir，
        // settings 随 config home 动态），无需改为 .{appName}.json。
        return Paths.get(System.getProperty("user.home", "."), ".nexusai.json");
    }

    private Path settingsFilePath() {
        if (properties != null && properties.getSettingsFile() != null
                && properties.getSettingsFile().path() != null
                && !properties.getSettingsFile().path().isBlank()) {
            return Paths.get(properties.getSettingsFile().path());
        }
        return Paths.get(home(), "settings.json");
    }

    // ── global source ─────────────────────────────────────────────────────

    @Override
    public Object readGlobal(String key) {
        if (key == null || key.isBlank()) return null;
        ObjectNode root = loadGlobal();
        JsonNode value = root.get(key);
        return jsonNodeToJavaValue(value);
    }

    @Override
    public void writeGlobal(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("global key blank");
        }
        writeLock.lock();
        try {
            ObjectNode root = loadGlobalLocked();
            applyValue(root, key, value);
            persistGlobal(root);
            notifyChange("global", List.of(key), value);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void unsetGlobal(String key) {
        if (key == null || key.isBlank()) return;
        writeLock.lock();
        try {
            ObjectNode root = loadGlobalLocked();
            root.remove(key);
            persistGlobal(root);
            notifyChange("global", List.of(key), null);
        } finally {
            writeLock.unlock();
        }
    }

    // ── settings source ───────────────────────────────────────────────────

    @Override
    public Object readSettings(List<String> path) {
        if (path == null || path.isEmpty()) return null;
        ObjectNode root = loadSettings();
        JsonNode cursor = root;
        for (String seg : path) {
            if (cursor == null || !cursor.isObject()) return null;
            cursor = cursor.get(seg);
        }
        return jsonNodeToJavaValue(cursor);
    }

    @Override
    public void writeSettings(List<String> path, Object value) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("settings path empty");
        }
        writeLock.lock();
        try {
            ObjectNode root = loadSettingsLocked();
            applyNestedValue(root, path, value);
            persistSettings(root);
            notifyChange("settings", path, value);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void unsetSettings(List<String> path) {
        if (path == null || path.isEmpty()) return;
        writeLock.lock();
        try {
            ObjectNode root = loadSettingsLocked();
            removeNestedKey(root, path);
            persistSettings(root);
            notifyChange("settings", path, null);
        } finally {
            writeLock.unlock();
        }
    }

    // ── 监听器 ────────────────────────────────────────────────────────────

    @Override
    public void addChangeListener(ConfigChangeListener listener) {
        if (listener == null) return;
        listeners.add(listener);
    }

    @Override
    public void removeChangeListener(ConfigChangeListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }

    private void notifyChange(String source, List<String> path, Object value) {
        ConfigChange change = new ConfigChange(source, path, value);
        for (ConfigChangeListener l : listeners) {
            try {
                l.onChange(change);
            } catch (Exception ex) {
                log.warn("[FileConfigStorage] listener 抛异常: {}", ex.getMessage());
            }
        }
    }

    // ── cache + 持久化 ────────────────────────────────────────────────────

    /** 加载 global cache (lock-free 读;未持有写锁). */
    private ObjectNode loadGlobal() {
        ObjectNode cached = globalCache;
        if (cached != null) return cached.deepCopy();
        writeLock.lock();
        try {
            if (globalCache == null) {
                globalCache = loadOrEmpty(globalFilePath());
            }
            return globalCache.deepCopy();
        } finally {
            writeLock.unlock();
        }
    }

    /** 加载 global cache (locked, 不复制, 写路径用). */
    private ObjectNode loadGlobalLocked() {
        if (globalCache == null) {
            globalCache = loadOrEmpty(globalFilePath());
        }
        return globalCache;
    }

    /** 加载 settings cache (lock-free 读). */
    private ObjectNode loadSettings() {
        ObjectNode cached = settingsCache;
        if (cached != null) return cached.deepCopy();
        writeLock.lock();
        try {
            if (settingsCache == null) {
                settingsCache = loadOrEmpty(settingsFilePath());
            }
            return settingsCache.deepCopy();
        } finally {
            writeLock.unlock();
        }
    }

    /** 加载 settings cache (locked). */
    private ObjectNode loadSettingsLocked() {
        if (settingsCache == null) {
            settingsCache = loadOrEmpty(settingsFilePath());
        }
        return settingsCache;
    }

    private ObjectNode loadOrEmpty(Path path) {
        if (!Files.exists(path)) {
            return JsonNodeFactory.instance.objectNode();
        }
        try (JsonParser parser = mapper.getFactory().createParser(path.toFile())) {
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject()) {
                return JsonNodeFactory.instance.objectNode();
            }
            return (ObjectNode) node;
        } catch (IOException e) {
            log.warn("[FileConfigStorage] 读取失败: {} — 视作空配置. err={}",
                path, e.getMessage());
            return JsonNodeFactory.instance.objectNode();
        }
    }

    /** 原子写 global 文件. */
    private void persistGlobal(ObjectNode root) {
        persist(root, globalFilePath());
    }

    /** 原子写 settings 文件. */
    private void persistSettings(ObjectNode root) {
        persist(root, settingsFilePath());
    }

    /** 原子写通用方法: tmp + rename. */
    private void persist(ObjectNode root, Path target) {
        try {
            Path dir = target.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            try (StringWriter sw = new StringWriter();
                 JsonGenerator gen = mapper.getFactory().createGenerator(sw)) {
                mapper.writeValue(gen, root);
                Files.writeString(tmp, sw.toString());
            }
            Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            if (log.isDebugEnabled()) {
                log.debug("[FileConfigStorage] 原子写入: {} ({} bytes)", target, root.size());
            }
        } catch (IOException e) {
            log.error("[FileConfigStorage] 写失败: {}", target, e);
            throw new ConfigStorageException("Failed to persist: " + target, e);
        }
    }

    // ── tree merge helpers ────────────────────────────────────────────────

    /** 把 java value (null/NullMarker/基本类型/Map/JsonNode/List) 写到 ObjectNode 的指定 key. */
    private static void applyValue(ObjectNode root, String key, Object value) {
        if (value == ConfigStorage.NullMarker) {
            root.putNull(key);
        } else if (value == null) {
            // 显式写 null → JSON null (非 absent)
            root.putNull(key);
        } else if (value instanceof Boolean b) {
            root.put(key, b);
        } else if (value instanceof Integer i) {
            root.put(key, i);
        } else if (value instanceof Long l) {
            root.put(key, l);
        } else if (value instanceof Double d) {
            root.put(key, d);
        } else if (value instanceof Float f) {
            root.put(key, f);
        } else if (value instanceof Map<?, ?> || value instanceof JsonNode
                || value instanceof List<?>) {
            // [P1-15] Map/JsonNode/List → JSON 对象/数组（对齐 CC config.ts:481 skillUsage 嵌套对象持久化）。
            //   之前这些类型会落到 String.valueOf 兜底 → 序列化成字符串而非 JSON 对象，形状偏移。
            root.set(key, toJsonNode(value));
        } else {
            root.put(key, String.valueOf(value));
        }
    }

    /** [P1-15][MPL8-D6e] java value → JsonNode 递归转换（Map/List/基本类型/record/JsonNode/null）· 对齐 CC 嵌套对象形状. */
    private static JsonNode toJsonNode(Object value) {
        if (value == null) return JsonNodeFactory.instance.nullNode();
        if (value instanceof JsonNode n) return n;
        if (value instanceof Boolean b) return JsonNodeFactory.instance.booleanNode(b);
        if (value instanceof Integer i) return JsonNodeFactory.instance.numberNode(i);
        if (value instanceof Long l) return JsonNodeFactory.instance.numberNode(l);
        if (value instanceof Double d) return JsonNodeFactory.instance.numberNode(d);
        if (value instanceof Float f) return JsonNodeFactory.instance.numberNode(f);
        if (value instanceof Map<?, ?> m) {
            ObjectNode obj = JsonNodeFactory.instance.objectNode();
            m.forEach((k, v) -> obj.set(String.valueOf(k), toJsonNode(v)));
            return obj;
        }
        if (value instanceof List<?> list) {
            ArrayNode arr = JsonNodeFactory.instance.arrayNode();
            for (Object item : list) {
                arr.add(toJsonNode(item));
            }
            return arr;
        }
        if (value instanceof Record) {
            // [MPL8-D6e] record（如 KnownMarketplace）→ Jackson 对象序列化。
            //   WHY: 之前 record 落到 String.valueOf 兜底 → 序列化成字符串而非 JSON 对象，
            //   MarketplaceConfigStore.getDeclaredMarketplaces 读侧无法解析 source（CC 意图层存对象）。
            //   多态判别键（MarketplaceSource @JsonTypeInfo property="source"）由 Jackson 原生处理。
            return RECORD_MAPPER.valueToTree(value);
        }
        return JsonNodeFactory.instance.textNode(String.valueOf(value));
    }

    /** 在嵌套 path 处写入 value; 中间不存在的 object 自动创建. */
    private static void applyNestedValue(ObjectNode root, List<String> path, Object value) {
        ObjectNode cursor = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String seg = path.get(i);
            JsonNode next = cursor.get(seg);
            if (next == null || !next.isObject()) {
                ObjectNode created = JsonNodeFactory.instance.objectNode();
                cursor.set(seg, created);
                cursor = created;
            } else {
                cursor = (ObjectNode) next;
            }
        }
        applyValue(cursor, path.get(path.size() - 1), value);
    }

    /** 在嵌套 path 处删除 key; 中间存在但非 object 的节点不删除 (absent 语义). */
    private static void removeNestedKey(ObjectNode root, List<String> path) {
        ObjectNode cursor = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String seg = path.get(i);
            JsonNode next = cursor.get(seg);
            if (next == null || !next.isObject()) return;
            cursor = (ObjectNode) next;
        }
        cursor.remove(path.get(path.size() - 1));
    }

    /** JsonNode → Java value (null=absent, NullMarker=JSON null). */
    private static Object jsonNodeToJavaValue(JsonNode node) {
        if (node == null) return null;
        if (node.isNull()) return ConfigStorage.NullMarker;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isDouble() || node.isFloat()) return node.doubleValue();
        if (node.isTextual()) return node.asText();
        // 对象/数组 → 原始 JsonNode (Phase 4 可加 Map/List 转换).
        return node;
    }

    // ── 测试 helper ───────────────────────────────────────────────────────

    /** 测试用: 强制清缓存 (Phase 3 impl 内部). */
    void invalidateCache() {
        globalCache = null;
        settingsCache = null;
    }

    /** 测试用: 暴露 listener 列表大小. */
    int listenerCount() {
        return listeners.size();
    }

    /** 测试用: nexusai home + paths. */
    Map<String, Path> paths() {
        return Map.of(
            "global", globalFilePath(),
            "settings", settingsFilePath());
    }
}