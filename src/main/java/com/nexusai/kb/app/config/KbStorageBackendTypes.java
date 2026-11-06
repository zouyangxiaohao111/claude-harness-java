package com.nexusai.kb.app.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储后端类型元数据（批次 W8）· {@code GET /storage-backends/types} 的数据源。
 *
 * <p><b>源侧对照</b>：WeKnora {@code internal/types/storagebackend.go}
 * （{@code StorageBackendConfig} + {@code ValidateForProvider}，{@code :95-205}）。
 * 源侧支持 local / minio / cos / tos / s3 / oss / ks3 / obs 八种；
 * <b>本仓只做 local / minio / cos</b> —— 用户 2026-11-05 裁定
 * 「保留接口兼容，如果有 minio 那直接无缝接入，没有就本地」。
 * 其余五种属于同一套 S3 兼容协议，将来要接只需再加一条元数据 + 一个实现，
 * ⛔ 不必改接口。
 *
 * <p><b>字段来源</b>（源侧 {@code StorageBackendConfig}）：
 * {@code mode / endpoint / region / access_key_id / secret_access_key / bucket_name /
 * path_prefix / app_id / use_ssl / ...}。本仓只保留前七个（够用），
 * 敏感字段由 {@code KbConfigSecrets} 脱敏。
 */
public final class KbStorageBackendTypes {

    private KbStorageBackendTypes() {
    }

    /** 一个配置字段的元数据（形状与向量库一致，复用 {@link KbVectorStoreTypes.Field}）。 */
    public record Field(
        String name,
        String type,
        boolean required,
        boolean sensitive,
        Object defaultValue,
        String description
    ) {
        static Field req(String name, String desc) {
            return new Field(name, "string", true, false, null, desc);
        }

        static Field opt(String name, String def, String desc) {
            return new Field(name, "string", false, false, def, desc);
        }

        static Field secret(String name, String desc) {
            return new Field(name, "string", false, true, null, desc);
        }

        static Field reqSecret(String name, String desc) {
            return new Field(name, "string", true, true, null, desc);
        }

        static Field bool(String name, String desc) {
            return new Field(name, "boolean", false, false, null, desc);
        }
    }

    /** 一种存储后端的类型元数据。 */
    public record TypeInfo(
        String provider,
        String displayName,
        List<Field> configFields,
        /** 本机能否真正使用（local 恒 true；minio/cos 取决于是否配了凭据 + 是否有实现）。 */
        boolean locallyAvailable
    ) {
    }

    /** provider 常量。 */
    public static final String PROVIDER_LOCAL = "local";
    public static final String PROVIDER_MINIO = "minio";
    public static final String PROVIDER_COS = "cos";

    private static final Map<String, TypeInfo> TYPES = build();

    private static Map<String, TypeInfo> build() {
        Map<String, TypeInfo> m = new LinkedHashMap<>();

        m.put(PROVIDER_LOCAL, new TypeInfo(PROVIDER_LOCAL, "本地磁盘",
            List.of(
                Field.opt("path_prefix", "",
                    "相对路径前缀（位于服务端配置的知识库存储根目录之下，不允许 ../ 跳出）")),
            true));

        m.put(PROVIDER_MINIO, new TypeInfo(PROVIDER_MINIO, "MinIO",
            List.of(
                Field.req("endpoint", "服务地址，如 http://localhost:9000"),
                Field.req("bucket_name", "Bucket 名称"),
                Field.reqSecret("access_key_id", "Access Key"),
                Field.reqSecret("secret_access_key", "Secret Key"),
                Field.opt("path_prefix", "", "对象键前缀"),
                Field.bool("use_ssl", "是否使用 HTTPS")),
            false));

        m.put(PROVIDER_COS, new TypeInfo(PROVIDER_COS, "腾讯云 COS",
            List.of(
                Field.req("region", "地域，如 ap-guangzhou"),
                Field.req("bucket_name", "Bucket 名称（含 appid 后缀）"),
                Field.reqSecret("access_key_id", "SecretId"),
                Field.reqSecret("secret_access_key", "SecretKey"),
                Field.opt("path_prefix", "", "对象键前缀"),
                Field.bool("use_ssl", "是否使用 HTTPS")),
            false));

        return java.util.Collections.unmodifiableMap(m);
    }

    public static List<TypeInfo> all() {
        return List.copyOf(TYPES.values());
    }

    public static TypeInfo get(String provider) {
        return provider == null ? null : TYPES.get(provider.trim().toLowerCase());
    }

    public static boolean isValid(String provider) {
        return get(provider) != null;
    }

    /** 必填配置字段名。 */
    public static List<String> requiredFields(String provider) {
        TypeInfo info = get(provider);
        if (info == null) {
            return List.of();
        }
        return info.configFields().stream().filter(Field::required).map(Field::name).toList();
    }

    /** 敏感字段名（脱敏用）。 */
    public static List<String> sensitiveFields(String provider) {
        TypeInfo info = get(provider);
        if (info == null) {
            return List.of();
        }
        return info.configFields().stream().filter(Field::sensitive).map(Field::name).toList();
    }
}
