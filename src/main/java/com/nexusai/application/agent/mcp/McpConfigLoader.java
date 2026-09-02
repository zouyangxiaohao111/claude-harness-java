package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP config loader · 对齐 CC services/mcp/config.ts（保留接线面）。
 *
 * <p>L1 语义: 按 scope 加载 MCP server config (user/project/local/enterprise/dynamic/claudeai),
 *            合并后返回 ScopedServerConfig Map; priority 规则 local > project > user
 *            （enterprise 独占短路，claudeai/dynamic 最低叠加）——对齐 CC config.ts:1231-1238。
 *            本类只暴露核心 merge 算法 + scope 标签;实际 IO (settings 文件读取, plugin cache) 由 caller wired.
 *
 * <p>保留面（生产消费 = McpServerService.importFromMcpJson）：
 * <ul>
 *   <li>{@link #loadAllMcpServers()} — 按 priority 合并（CC getClaudeCodeMcpConfigs 合并序，
 *       config.ts:1231-1238；enterprise 独占短路 config.ts:1084-1096）</li>
 *   <li>{@link #addScopeToServers(Map, String)} — 给 servers 加 scope 字段</li>
 *   <li>构造器 ×2 + safeGet/safeGetOptional（enterprise 空 Optional 语义，config.ts:1470-1477）</li>
 * </ul>
 * <p>已删面（D-B10-08，0 生产消费方，EV-R2-26）: 按名查找方法（生产按名解析 =
 * McpServerService.getServerConfigByName，DB 唯一运行时源 Q-09=C）、显式合并方法、
 * claudeai 提取、优先级序/校验/路径等 5 死方法 + 优先级常量 + 私有 scope 克隆 ——
 * 原按名查找方法测试消费（McpConfigLoaderPriorityTest 3 用例）随删。
 */
public final class McpConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(McpConfigLoader.class);

    /** [IMP-E2 M-13] areMcpConfigsEqual 确定性序列化用（对齐 CC jsonStringify，client.ts:1710-1722）。 */
    private static final ObjectMapper CONFIG_JSON = new ObjectMapper();
    /**
     * enterprise supplier · 对齐 CC doesEnterpriseMcpConfigExist（config.ts:1470-1477）
     * {@code config !== null} 语义:
     * {@code Optional.empty()} = enterprise config null（文件缺失 / 解析失败 / 非法 JSON）→ 不短路;
     * {@code Optional.of(map)} = enterprise config 存在（map 可能为空，空也独占短路）。 */
    private final Supplier<Optional<Map<String, Map<String, Object>>>> enterpriseSupplier;
    private final Supplier<Map<String, Map<String, Object>>> userSupplier;
    private final Supplier<Map<String, Map<String, Object>>> projectSupplier;
    private final Supplier<Map<String, Map<String, Object>>> localSupplier;
    private final Supplier<Map<String, Map<String, Object>>> dynamicSupplier;
    private final Supplier<Map<String, Map<String, Object>>> claudeaiSupplier;

    public McpConfigLoader(
            Supplier<Optional<Map<String, Map<String, Object>>>> enterpriseSupplier,
            Supplier<Map<String, Map<String, Object>>> userSupplier,
            Supplier<Map<String, Map<String, Object>>> projectSupplier,
            Supplier<Map<String, Map<String, Object>>> localSupplier,
            Supplier<Map<String, Map<String, Object>>> dynamicSupplier,
            Supplier<Map<String, Map<String, Object>>> claudeaiSupplier) {
        this.enterpriseSupplier = enterpriseSupplier;
        this.userSupplier = userSupplier;
        this.projectSupplier = projectSupplier;
        this.localSupplier = localSupplier;
        this.dynamicSupplier = dynamicSupplier;
        this.claudeaiSupplier = claudeaiSupplier;
    }

    /** Default empty-loader. */
    public McpConfigLoader() {
        this(Optional::empty, () -> Map.of(), () -> Map.of(), () -> Map.of(),
            () -> Map.of(), () -> Map.of());
    }

    /** CC addScopeToServers — 给 servers 加 scope 字段. */
    public static Map<String, Map<String, Object>> addScopeToServers(
            Map<String, Map<String, Object>> servers, String scope) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (servers == null) return result;
        for (var e : servers.entrySet()) {
            if (e.getValue() == null) continue;
            Map<String, Object> copy = new LinkedHashMap<>(e.getValue());
            copy.put("scope", scope);
            result.put(e.getKey(), copy);
        }
        return result;
    }

    /** CC loadAllMcpServers — 对齐 getClaudeCodeMcpConfigs 合并序.
     *
     *  <p>写入序 user→project→local（后写覆盖前写，local 最后 = 最高，对齐 config.ts:1231-1238
     *  {@code plugin < user < project < local}；Java 无 plugin scope）。
     *  <b>[IMP-E2 S-2]</b> dynamic/claudeai 不再落入最终合并集（EV-E3-011）：CC dynamic 仅作插件
     *  去重目标（enabledManualServers，config.ts:1180-1194），claudeai 在 getAllMcpConfigs 最低
     *  叠加（config.ts:1286-1287），均不进入 getClaudeCodeMcpConfigs 的连接配置集。
     *  enterprise 独占短路（config.ts:1084-1096）：enterprise config 存在（config !== null，
     *  可能空 map）时只返回 enterprise，忽略其余 scope。dynamic/claudeai supplier 保留（未来
     *  插件去重目标），loadAllMcpServers 不再消费。 */
    public Map<String, Map<String, Object>> loadAllMcpServers() {
        Optional<Map<String, Map<String, Object>>> enterpriseOpt = safeGetOptional(enterpriseSupplier);
        if (enterpriseOpt.isPresent()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpConfigLoader] enterprise MCP config 存在（config!==null）→ 独占短路（CC config.ts:1084 + 1470），忽略其余 scope，servers={}",
                    enterpriseOpt.get().size());
            }
            return addScopeToServers(enterpriseOpt.get(), "enterprise");
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        // [IMP-E2 S-2] 最低优先级先写，最高优先级最后写（后写覆盖前写）→ local 最高。
        // dynamic/claudeai 仅作去重目标，不落入最终合并集（CC config.ts:1231-1238）。
        Map<String, Supplier<Map<String, Map<String, Object>>>> sources = new LinkedHashMap<>();
        sources.put("user", userSupplier);
        sources.put("project", projectSupplier);
        sources.put("local", localSupplier);
        for (var e : sources.entrySet()) {
            Map<String, Map<String, Object>> servers = safeGet(e.getValue());
            Map<String, Map<String, Object>> scoped = addScopeToServers(servers, e.getKey());
            for (var s : scoped.entrySet()) {
                result.put(s.getKey(), s.getValue());
            }
        }
        return result;
    }

    /**
     * [IMP-E2 M-13] 两个 MCP server 配置是否等价 · 对齐 CC areMcpConfigsEqual（client.ts:1710-1722）：
     * {@code type 快速比对} + {@code 序列化比较（排除 scope 元数据）}。用于「config 变化 → 重连」
     * 判定（CC print.ts:5471 {@code !areMcpConfigsEqual(currentConfig, desiredConfig)}；
     * Java 侧重连语义由 {@link McpToolPool#getServerCacheKey} / serverConfigKeys 承接，本函数为
     * 命名等价工具，对齐 CC 纯函数契约）。
     *
     * <p>序列化：Jackson 确定性序列化（TreeMap 键排序），排除 {@code "scope"} 字段（scope 为
     * 元数据非连接配置，CC client.ts:1718-1720）。
     *
     * @param a 配置 A（可能 null）
     * @param b 配置 B（可能 null）
     * @return true = 等价（同 type 且排除 scope 后序列化一致）
     */
    public static boolean areMcpConfigsEqual(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) {
            return a == b;
        }
        // 快速 type 比对（CC client.ts:1715）
        if (!Objects.equals(a.get("type"), b.get("type"))) {
            return false;
        }
        String serializedA = serializeWithoutScope(a);
        String serializedB = serializeWithoutScope(b);
        return Objects.equals(serializedA, serializedB);
    }

    /** 确定性序列化（键排序），排除 scope 字段（CC areMcpConfigsEqual client.ts:1718-1720）。 */
    private static String serializeWithoutScope(Map<String, Object> config) {
        try {
            Map<String, Object> copy = new java.util.TreeMap<>();
            for (Map.Entry<String, Object> e : config.entrySet()) {
                if ("scope".equals(e.getKey())) {
                    continue;
                }
                copy.put(e.getKey(), e.getValue());
            }
            return CONFIG_JSON.writeValueAsString(copy);
        } catch (Exception e) {
            log.warn("[McpConfigLoader] 配置序列化失败: {}", e.getMessage());
            return String.valueOf(System.identityHashCode(config));
        }
    }

    private static Map<String, Map<String, Object>> safeGet(
            Supplier<Map<String, Map<String, Object>>> supplier) {
        if (supplier == null) return Map.of();
        try {
            return Objects.requireNonNullElse(supplier.get(), Map.of());
        } catch (Exception ex) {
            log.warn("MCP config supplier failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    /** enterprise supplier 安全取值：null supplier / 异常 / null Optional → Optional.empty()
     *  （= CC doesEnterpriseMcpConfigExist false，不短路）。 */
    private static Optional<Map<String, Map<String, Object>>> safeGetOptional(
            Supplier<Optional<Map<String, Map<String, Object>>>> supplier) {
        if (supplier == null) return Optional.empty();
        try {
            Optional<Map<String, Map<String, Object>>> opt = supplier.get();
            return opt == null ? Optional.empty() : opt;
        } catch (Exception ex) {
            log.warn("enterprise MCP config supplier failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
