package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 企业 managed policy settings 读取器 · 对齐 CC {@code getSettingsForSource('policySettings')}
 * （Open-ClaudeCode/src/utils/settings/settings.ts 的 managed file 层，Java 端仅实现 Layer 1）。
 *
 * <p>WHY (D1-4 / EV-CFG-019): {@link HooksSettings} 旧无参构造 supplier=key-&gt;null
 * 导致 {@code shouldDisableAll / shouldAllowManagedHooksOnly} 生产恒 false —— 企业策略
 * "禁用全部 hooks / 仅 managed hooks"在生产不可观察。本类提供真实 policy 文件读取
 * （{@code nexusai.policy.path}，与 {@code PolicySettingsLoader} 同一配置键），
 * 经 {@link HooksSettings#setManagedPolicySettingsSupplier} 注入生产链。
 *
 * <p><b>CC 真源键</b>（hooksConfigSnapshot.ts:18-88）:
 * <ul>
 *   <li>{@code disableAllHooks} (:22) — 策略禁全部 hook（含 managed）</li>
 *   <li>{@code allowManagedHooksOnly} (:27) — 仅 managed hook 生效</li>
 *   <li>{@code hooks} (:28) — policy hooks（managed hook 集）</li>
 *   <li>{@code strictPluginOnlyCustomization} — hooks 面 plugin-only 锁定（分支 3）</li>
 *   <li>{@code allowedHttpHookUrls / httpHookAllowedEnvVars} — HTTP hook 全局策略</li>
 * </ul>
 *
 * <p><b>无状态约定</b>: 每次读取重读盘（与 {@code HooksConfigSnapshot} 注释"Java settings
 * loader 无状态, 每次重读盘"一致）；文件缺失/路径为空/解析失败 → null（无企业管控场景）。
 *
 * <p><b>local-only 约束</b>: 本类不向外发送任何数据，仅本地策略文件查询。
 */
@Component
public class ManagedPolicySettingsSupplier {

    private static final Logger log = LoggerFactory.getLogger(ManagedPolicySettingsSupplier.class);

    private final ObjectMapper objectMapper;
    /** 企业策略文件路径（来自 {@code nexusai.policy.path} 配置；空 = 无企业管控）. */
    private final String policyFilePath;

    /**
     * @param objectMapper   Jackson（Spring bean）
     * @param policyFilePath 企业策略文件路径（可为空/不存在）
     */
    public ManagedPolicySettingsSupplier(ObjectMapper objectMapper,
                                         @Value("${nexusai.policy.path:}") String policyFilePath) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is null");
        }
        this.objectMapper = objectMapper;
        this.policyFilePath = policyFilePath;
    }

    /**
     * 读取 managed policy 文件并返回 key 对应值 · 等价 CC
     * {@code getSettingsForSource('policySettings')?.[key]}.
     *
     * <p>值形态镜像 {@code FileConfigStorage.jsonNodeToJavaValue}: 基本类型转 Java 值，
     * 对象/数组返回原始 {@link JsonNode}（供 {@link HooksConfigSnapshot} 反序列化为
     * HookMatcher）。
     *
     * @param key 策略键（如 disableAllHooks / hooks）
     * @return 值；无 policy / 文件缺失 / 键缺失 / 解析失败 → null
     */
    public Object get(String key) {
        if (key == null) {
            return null;
        }
        JsonNode root = loadRoot();
        if (root == null) {
            return null;
        }
        return jsonNodeToJavaValue(root.get(key));
    }

    /**
     * 读取整个 managed policy 文件为 Map · 供 {@code PluginOnlyPolicy.isRestrictedToPluginOnly}
     * 等整表检查使用（CC getSettingsForSource 返回整个 settings 对象）。
     *
     * @return policy 全量 Map；无 policy / 解析失败 → 空 Map（不可变）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> all() {
        JsonNode root = loadRoot();
        if (root == null || !root.isObject()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = objectMapper.convertValue(root, Map.class);
            return map != null ? map : Map.of();
        } catch (Exception e) {
            log.warn("ManagedPolicySettingsSupplier: policy 文件转 Map 失败: {} — 视为空", e.toString());
            return Map.of();
        }
    }

    /** 读取 policy 文件根节点；缺失/空路径/解析失败 → null（不抛异常，与无企业管控一致）. */
    private JsonNode loadRoot() {
        if (policyFilePath == null || policyFilePath.isBlank()) {
            return null;
        }
        Path path = Paths.get(policyFilePath);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return objectMapper.readTree(path.toFile());
        } catch (IOException e) {
            log.warn("ManagedPolicySettingsSupplier: 读取 policy 文件失败: {} — 视为无 policy: {}",
                path, e.getMessage());
            return null;
        }
    }

    /** JsonNode → Java 值（null=absent；对象/数组 → 原始 JsonNode，供上层反序列化）. */
    private static Object jsonNodeToJavaValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isInt()) {
            return node.intValue();
        }
        if (node.isLong()) {
            return node.longValue();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.doubleValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        // 对象/数组 → 原始 JsonNode（镜像 FileConfigStorage.jsonNodeToJavaValue）
        return node;
    }
}
