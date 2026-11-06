package com.nexusai.kb.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 配置的敏感字段脱敏（批次 W8）。
 *
 * <p><b>为什么单独一个类</b>：向量库与存储后端都要「读出来脱敏、写回去保留原值」，
 * 两处规则必须一致 —— 一处改了另一处忘改，就会出现「界面上是 ****，
 * 保存后真凭据被 **** 覆盖」这种静默毁数据（源侧为此专门写了
 * {@code MergeSecrets} / {@code PreserveIfRedacted}，见 {@code storagebackend.go:160-165}）。
 *
 * <p><b>脱敏占位符</b>：{@link #REDACTED}。⚠️ 前端回传占位符时必须
 * {@link #preserveRedacted 保留旧值}，⛔ 不能把占位符当新密码写库。
 */
public final class KbConfigSecrets {

    /** 脱敏占位符（与源侧 {@code RedactedSecretPlaceholder} 同形）。 */
    public static final String REDACTED = "******";

    private static final ObjectMapper JSON = new ObjectMapper();

    private KbConfigSecrets() {
    }

    /**
     * 解析 JSON 配置为 Map；空/非法一律返回空 Map。
     *
     * <p>⚠️ 非法 JSON 不抛：这是<b>读路径</b>，一个脏行不该让整个列表 500。
     * 写路径另有校验（见各 Service 的 validate）。
     */
    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                return new LinkedHashMap<>();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JSON.convertValue(node, Map.class);
            return new LinkedHashMap<>(m);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Map 序列化为 JSON 字符串。 */
    public static String toJson(Map<String, Object> map) {
        try {
            return JSON.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            throw new IllegalStateException("配置序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 返回值中的敏感字段替换为 {@link #REDACTED}（非空才替换 —— 空值保持空，界面能看出「没配」）。
     *
     * @param config    原始配置
     * @param sensitiveFields 敏感字段名
     * @return 新的 Map（不改入参）
     */
    public static Map<String, Object> mask(Map<String, Object> config, List<String> sensitiveFields) {
        Map<String, Object> out = new LinkedHashMap<>(config == null ? Map.of() : config);
        for (String f : sensitiveFields) {
            Object v = out.get(f);
            if (v != null && !String.valueOf(v).isEmpty()) {
                out.put(f, REDACTED);
            }
        }
        return out;
    }

    /**
     * 写入时用旧值替换「回传的占位符」。
     *
     * <p><b>⚠️ 这是必须的，不是可选加固</b>：前端拿到的响应里密码是 {@code ******}，
     * 用户只改别的字段再保存时，提交上来的密码仍是 {@code ******}。
     * 若直接存，真密码就被六个星号覆盖了 —— 而且不报错，下次连不上才发现。
     *
     * @param incoming 提交的配置
     * @param existing 库里原有的配置
     * @param sensitiveFields 敏感字段名
     * @return 合并后的新 Map（不改入参）
     */
    public static Map<String, Object> preserveRedacted(
        Map<String, Object> incoming,
        Map<String, Object> existing,
        List<String> sensitiveFields
    ) {
        Map<String, Object> out = new LinkedHashMap<>(incoming == null ? Map.of() : incoming);
        for (String f : sensitiveFields) {
            if (REDACTED.equals(out.get(f))) {
                Object old = existing == null ? null : existing.get(f);
                if (old != null) {
                    out.put(f, old);
                } else {
                    // 旧值也没有（之前就没配）⇒ 删掉占位符，避免把 "******" 当密码存进去
                    out.remove(f);
                }
            }
        }
        return out;
    }

    /** 判断一个 JSON 字符串能否解析为对象（写路径校验用）。 */
    public static boolean isJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return true;  // 空 = 用默认 {}
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject();
        } catch (Exception e) {
            return false;
        }
    }
}
