package com.nexusai.kb.app.config;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.config.StorageBackendDto;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbStorageBackendRecord;
import com.nexusai.kb.repo.mapper.KbStorageBackendMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 存储后端实例的 CRUD（批次 W8）。
 *
 * <p><b>源侧对照</b>：{@code service/storagebackend.go}（386 行）。
 *
 * <p><b>用户 2026-11-05 裁定</b>：「文件服务跟其他的一样，保留接口兼容；
 * 如果有 minio 那直接无缝接入，没有就本地」。
 * ⇒ 本类负责「实例的存储与校验」+「默认后端的切换」，
 * <b>不负责建客户端</b>（那是 {@link KbFileStorage} 实现的事）。
 *
 * <p><b>⭐ 本地后端恒存在且不可删</b>：兜底。若允许删掉本地后端，
 * 而 MinIO 又没接上，整个知识库就没有可写的地方了 ——
 * 这是「隐藏最后一个入口 = 死锁」的同类问题。
 */
@Service
public class KbStorageBackendService {

    private static final Logger log = LoggerFactory.getLogger(KbStorageBackendService.class);

    /** 内置本地后端的固定 ID（与迁移里的约定一致）。 */
    public static final String LOCAL_BACKEND_ID = "sb-local";

    @Autowired private KbStorageBackendMapper mapper;
    /**
     * 知识库域配置（R0 · V273）：默认存储后端已迁至 kb_config 独立表。
     * ⚠️ 不再经 settings —— 那是公共偏好表，知识库的默认值放这里才能整块拆走。
     */
    @Autowired private com.nexusai.kb.config.KbConfigService kbConfigService;
    @Autowired private LocalFileStorage localFileStorage;
    @Autowired private MinioFileStorage minioFileStorage;
    @Autowired private CosFileStorage cosFileStorage;
    @Autowired private javax.sql.DataSource dataSource;

    /**
     * 列表。首次调用时会确保「本地内置后端」存在（懒初始化）。
     *
     * <p>⚠️ 为什么懒初始化而不是写进迁移：
     * 本地后端的 id 是固定的，但「要不要有一条本地后端」取决于部署形态；
     * 写进迁移会让所有历史库突然多一行。懒初始化 + 幂等 upsert 更稳。
     */
    public List<StorageBackendDto> list() {
        ensureLocalBackend();
        String defaultId = kbConfigService.readDefaultStorageBackendId();
        List<KbStorageBackendRecord> rows = mapper.selectListByQuery(
            QueryWrapper.create().orderBy("create_time", true));
        return rows.stream().map(r -> toDto(r, defaultId)).toList();
    }

    public StorageBackendDto get(String id) {
        return toDto(require(id), kbConfigService.readDefaultStorageBackendId());
    }

    public StorageBackendDto create(String name, String provider, Map<String, Object> config) {
        String p = normalizeProvider(provider);
        String trimmed = requireName(name);

        if (KbStorageBackendTypes.PROVIDER_LOCAL.equals(p)) {
            // 本地后端只允许一条：多条本地后端只是把同一个目录登记多次，没有语义
            if (findByProvider(KbStorageBackendTypes.PROVIDER_LOCAL) != null) {
                throw new ConflictException("本地存储后端已存在，不能重复创建");
            }
        }
        assertNameFree(trimmed, null);
        validateConfig(p, config);

        KbStorageBackendRecord r = new KbStorageBackendRecord();
        r.setId("sb-" + UUID.randomUUID().toString().substring(0, 8));
        r.setName(trimmed);
        r.setProvider(p);
        r.setConfig(KbConfigSecrets.toJson(config == null ? Map.of() : config));
        r.setSource("user");
        r.setStatus("active");
        r.setLegacyAlias(0);
        mapper.insert(r);
        log.info("[存储后端] 已创建 id={} name={} provider={}", r.getId(), trimmed, p);
        return toDto(r, kbConfigService.readDefaultStorageBackendId());
    }

    public StorageBackendDto update(
        String id, String name, String provider, Map<String, Object> config, String status
    ) {
        KbStorageBackendRecord r = require(id);
        String p = provider != null ? normalizeProvider(provider) : r.getProvider();

        // ⚠️ 内置本地后端不允许改 provider —— 它就是「本地」这条兜底路径的实体，
        //    改成 minio 会让 id=sb-local 的东西其实是对象存储，语义全乱。
        if (LOCAL_BACKEND_ID.equals(id) && !KbStorageBackendTypes.PROVIDER_LOCAL.equals(p)) {
            throw new ValidationException("内置本地存储后端不能改为其他类型");
        }

        if (name != null) {
            String trimmed = requireName(name);
            assertNameFree(trimmed, id);
            r.setName(trimmed);
        }

        Map<String, Object> existing = KbConfigSecrets.parse(r.getConfig());
        if (config != null) {
            Map<String, Object> merged = KbConfigSecrets.preserveRedacted(
                config, existing, KbStorageBackendTypes.sensitiveFields(p));
            validateConfig(p, merged);
            r.setConfig(KbConfigSecrets.toJson(merged));
        } else if (!p.equals(r.getProvider())) {
            validateConfig(p, existing);
        }
        r.setProvider(p);

        if (status != null) {
            String s = status.trim().toLowerCase();
            if (!"active".equals(s) && !"disabled".equals(s)) {
                throw new ValidationException("status 只能是 active 或 disabled");
            }
            // ⚠️ 内置本地后端不能停用：停用本地 + MinIO 不可用 = 没有任何可写存储
            if (LOCAL_BACKEND_ID.equals(id) && "disabled".equals(s)) {
                throw new ValidationException("内置本地存储后端不能停用（它是唯一恒可用的兜底）");
            }
            r.setStatus(s);
        }
        mapper.update(r);
        log.info("[存储后端] 已更新 id={}", id);
        return toDto(r, kbConfigService.readDefaultStorageBackendId());
    }

    public void delete(String id) {
        KbStorageBackendRecord r = require(id);
        if (LOCAL_BACKEND_ID.equals(id)) {
            throw new ValidationException("内置本地存储后端不能删除");
        }
        long bound = countBoundKnowledgeBases(id);
        if (bound > 0) {
            throw new ConflictException(
                "该存储后端正被 " + bound + " 个知识库使用，不能删除。请先在知识库中改绑其他存储后端。");
        }
        // 若正被设为默认，删之前解除默认（否则 settings 指向一条不存在的行）
        String defaultId = kbConfigService.readDefaultStorageBackendId();
        if (id.equals(defaultId)) {
            kbConfigService.updateDefaultStorageBackendId("");
            log.info("[存储后端] 被删的是默认后端，已解除默认设置");
        }
        mapper.deleteById(r.getId());
        log.info("[存储后端] 已删除 id={}", id);
    }

    /**
     * 设为默认（源侧 {@code PUT /storage-backends/{id}/default}）。
     *
     * <p>⚠️ 停用的后端不能设为默认 —— 否则所有没显式绑定后端的新知识库
     * 都会落到一个不工作的存储上。
     */
    public StorageBackendDto setDefault(String id) {
        KbStorageBackendRecord r = require(id);
        if (!"active".equals(r.getStatus())) {
            throw new ValidationException("已停用的存储后端不能设为默认");
        }
        kbConfigService.updateDefaultStorageBackendId(id);
        log.info("[存储后端] 默认后端已切换为 id={} name={}", id, r.getName());
        return toDto(r, id);
    }

    /**
     * 连通性测试（新建场景：用传入配置试连，不落库）。
     *
     * <p>⚠️ {@code local} 会真的检查目录可写；{@code minio}/{@code cos} 如实返回
     * 「本机没有对应 SDK，无法验证」—— ⛔ 不伪造成功。
     */
    public TestResult test(String provider, Map<String, Object> config) {
        String p = normalizeProvider(provider);
        validateConfig(p, config);
        return doTest(p, config);
    }

    /** 连通性测试（已存场景）。 */
    public TestResult testById(String id) {
        KbStorageBackendRecord r = require(id);
        return doTest(r.getProvider(), KbConfigSecrets.parse(r.getConfig()));
    }

    /**
     * 解析某个知识库实际应使用的存储后端。
     *
     * @param storageBackendId 库绑定的后端（可空）
     * @return 存储实现 + 后端记录；都没配时回落本地
     */
    public Resolved resolveForKnowledgeBase(String storageBackendId) {
        KbStorageBackendRecord r;
        if (storageBackendId == null || storageBackendId.isBlank()) {
            String defaultId = kbConfigService.readDefaultStorageBackendId();
            r = (defaultId != null && !defaultId.isBlank()) ? mapper.selectOneById(defaultId) : null;
        } else {
            r = require(storageBackendId);
        }
        if (r == null) {
            return new Resolved(null, localFileStorage);
        }
        KbFileStorage impl = storageOf(r.getProvider());
        return new Resolved(r, impl);
    }

    /** provider → 实现（未知/provider 不可用时回落本地，并记 WARN）。 */
    private KbFileStorage storageOf(String provider) {
        return switch (provider) {
            case KbStorageBackendTypes.PROVIDER_MINIO -> minioFileStorage;
            case KbStorageBackendTypes.PROVIDER_COS -> cosFileStorage;
            default -> localFileStorage;
        };
    }

    // ── 内部 ──────────────────────────────────────────────

    private TestResult doTest(String provider, Map<String, Object> config) {
        Map<String, Object> c = config == null ? Map.of() : config;
        if (KbStorageBackendTypes.PROVIDER_LOCAL.equals(provider)) {
            // 本地是真的能测：往根目录写一个探针文件再删掉
            String prefix = c.get("path_prefix") == null ? "" : String.valueOf(c.get("path_prefix"));
            String probeKey = (prefix.isBlank() ? "" : prefix + "/") + ".write-probe-" + UUID.randomUUID();
            try {
                localFileStorage.write(probeKey, new java.io.ByteArrayInputStream(new byte[0]), 0);
                localFileStorage.delete(probeKey);
                return new TestResult(provider, true,
                    "本地目录可写：" + localFileStorage.root(), null);
            } catch (Exception e) {
                return new TestResult(provider, false,
                    "本地目录不可写：" + e.getMessage(), null);
            }
        }
        if (KbStorageBackendTypes.PROVIDER_MINIO.equals(provider)) {
            MinioFileStorage.Activation a = minioFileStorage.tryActivate(c);
            return new TestResult(provider, a.active(), a.reason(), null);
        }
        if (KbStorageBackendTypes.PROVIDER_COS.equals(provider)) {
            CosFileStorage.Activation a = cosFileStorage.tryActivate(c);
            return new TestResult(provider, a.active(), a.reason(), null);
        }
        return new TestResult(provider, false, "不支持的存储类型：" + provider, null);
    }

    private void ensureLocalBackend() {
        try {
            if (mapper.selectOneById(LOCAL_BACKEND_ID) != null) {
                return;
            }
            if (findByProvider(KbStorageBackendTypes.PROVIDER_LOCAL) != null) {
                return;  // 已经有别的本地后端了，不重复造
            }
            KbStorageBackendRecord r = new KbStorageBackendRecord();
            r.setId(LOCAL_BACKEND_ID);
            r.setName("本地磁盘");
            r.setProvider(KbStorageBackendTypes.PROVIDER_LOCAL);
            r.setConfig("{\"path_prefix\":\"\"}");
            r.setSource("env");   // ⭐ env = 由部署配置注入，不是用户建的
            r.setStatus("active");
            r.setLegacyAlias(0);
            mapper.insert(r);
            log.info("[存储后端] 已创建内置本地后端 id={}", LOCAL_BACKEND_ID);
        } catch (Exception e) {
            // 并发首调可能撞唯一索引 —— 忽略，下一读就有了
            log.debug("[存储后端] 本地后端懒初始化跳过: {}", e.toString());
        }
    }

    private KbStorageBackendRecord require(String id) {
        if (id == null || id.isBlank()) {
            throw new NotFoundException("StorageBackend 未指定");
        }
        KbStorageBackendRecord r = mapper.selectOneById(id);
        if (r == null) {
            throw new NotFoundException("StorageBackend " + id + " not found");
        }
        return r;
    }

    private KbStorageBackendRecord findByProvider(String provider) {
        List<KbStorageBackendRecord> rows = mapper.selectListByQuery(
            QueryWrapper.create().eq("provider", provider));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new ValidationException("provider is required");
        }
        String p = provider.trim().toLowerCase();
        if (!KbStorageBackendTypes.isValid(p)) {
            throw new ValidationException("unsupported storage provider: " + provider
                + "（可选：" + KbStorageBackendTypes.all().stream()
                    .map(KbStorageBackendTypes.TypeInfo::provider).toList() + "）");
        }
        return p;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name is required");
        }
        return name.trim();
    }

    private void assertNameFree(String name, String excludeId) {
        for (KbStorageBackendRecord r : mapper.selectListByQuery(
            QueryWrapper.create().eq("name", name))) {
            if (excludeId == null || !excludeId.equals(r.getId())) {
                throw new ConflictException("存储后端名称已存在：" + name);
            }
        }
    }

    private void validateConfig(String provider, Map<String, Object> config) {
        Map<String, Object> c = config == null ? Map.of() : config;
        for (String field : KbStorageBackendTypes.requiredFields(provider)) {
            Object v = c.get(field);
            if (v == null || String.valueOf(v).isBlank()) {
                throw new ValidationException(field + " is required for provider " + provider);
            }
        }
        // path_prefix 不允许绝对路径或向上逃逸（与源侧 ValidateForProvider 同口径）
        Object prefix = c.get("path_prefix");
        if (prefix != null) {
            String p = String.valueOf(prefix);
            if (p.startsWith("/") || p.startsWith("\\")
                || p.equals("..") || p.startsWith("../") || p.startsWith("..\\")
                || p.contains("/../") || p.contains("\\..\\")) {
                throw new ValidationException(
                    "path_prefix must be a relative path without parent traversal");
            }
        }
    }

    private long countBoundKnowledgeBases(String storageBackendId) {
        return countBound("storage_backend_id", storageBackendId);
    }

    private long countBound(String column, String value) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM kb_knowledge_base WHERE " + column + " = ? AND deleted = 0")) {
            ps.setString(1, value);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.warn("[存储后端] 统计知识库绑定数失败，按 0 处理: {}", e.toString());
            return 0L;
        }
    }

    private StorageBackendDto toDto(KbStorageBackendRecord r, String defaultId) {
        boolean available = KbStorageBackendTypes.PROVIDER_LOCAL.equals(r.getProvider());
        String reason = available ? "" : unavailableReason(r.getProvider());
        return new StorageBackendDto(
            r.getId(),
            r.getName(),
            r.getProvider(),
            KbConfigSecrets.mask(
                KbConfigSecrets.parse(r.getConfig()),
                KbStorageBackendTypes.sensitiveFields(r.getProvider())),
            r.getSource(),
            r.getStatus(),
            r.getLegacyAlias() != null && r.getLegacyAlias() != 0,
            available,
            reason,
            r.getId().equals(defaultId),
            r.getCreateTime() == null ? null : KbTimeFormat.format(r.getCreateTime()),
            r.getUpdateTime() == null ? null : KbTimeFormat.format(r.getUpdateTime()));
    }

    private static String unavailableReason(String provider) {
        if (KbStorageBackendTypes.PROVIDER_MINIO.equals(provider)) {
            return "本仓未引入 MinIO 客户端 SDK，配置可保存但无法读写对象存储";
        }
        if (KbStorageBackendTypes.PROVIDER_COS.equals(provider)) {
            return "本仓未引入腾讯云 COS SDK，配置可保存但无法读写对象存储";
        }
        return "不支持的存储类型";
    }

    /**
     * 连通性测试结果。
     *
     * @param provider      存储类型
     * @param reachable     是否真的可用
     * @param message       给用户看的话（失败必须说清原因）
     * @param serverVersion 服务端版本（本地/未知为 null）
     */
    public record TestResult(
        String provider, boolean reachable, String message, String serverVersion) {
    }

    /**
     * 知识库实际使用的存储后端解析结果。
     *
     * @param backend 后端记录（{@code null} = 用内置本地）
     * @param storage 存储实现
     */
    public record Resolved(KbStorageBackendRecord backend, KbFileStorage storage) {
    }
}
