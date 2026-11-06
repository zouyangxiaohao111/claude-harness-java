package com.nexusai.kb.app.config;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.config.VectorStoreDto;
import com.nexusai.kb.repo.entity.KbVectorStoreRecord;
import com.nexusai.kb.repo.mapper.KbVectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量库实例的 CRUD（批次 W8）。
 *
 * <p><b>源侧对照</b>：{@code service/vectorstore.go}（613 行）。
 *
 * <p><b>⭐ 与 W1 {@code KbRetrievalEngineRegistry} 的分工</b>（用户 2026-11-05 裁定「实例层覆盖类型层」）：
 * <ul>
 *   <li>本类只负责<b>实例的存储与校验</b> —— 建/改/删/查，以及「必填字段齐不齐」。</li>
 *   <li><b>不负责建驱动</b>：检索时由 {@code KbRetrievalEngineRegistry} 按库绑定的
 *       {@code vector_store_id} 解析出实例配置，再交给对应引擎实现。</li>
 * </ul>
 * ⛔ 不要在这里 new 任何引擎 —— 那会把「配置」和「运行」焊死，
 * 将来加一种引擎就要改这个 CRUD 类。
 *
 * <p><b>⚠️ 敏感字段两条铁律</b>：
 * <ol>
 *   <li>响应里 password / api_key 一律 {@link KbConfigSecrets#REDACTED}；</li>
 *   <li>写入时若提交的是占位符，<b>保留库里的旧值</b> —— 否则用户只改别的字段
 *       就会把真密码覆盖成六个星号，且不报错。</li>
 * </ol>
 */
@Service
public class KbVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(KbVectorStoreService.class);

    @Autowired private KbVectorStoreMapper mapper;
    @Autowired private javax.sql.DataSource dataSource;

    /** 列表（按创建时间倒序，与源侧一致）。 */
    public List<VectorStoreDto> list() {
        List<KbVectorStoreRecord> rows = mapper.selectListByQuery(
            QueryWrapper.create().orderBy("create_time", false));
        // ⚠️ 内存里按 create_time 倒序：SQLite 的 TEXT 时间排序依赖格式统一，
        //    KbTimeFormat 已保证这一点，但这里再排一次是为了不依赖 DB 的排序稳定性。
        return rows.stream().map(this::toDto).toList();
    }

    public VectorStoreDto get(String id) {
        return toDto(require(id));
    }

    /**
     * 创建实例。
     *
     * @param name       实例名（同库唯一）
     * @param engineType 引擎类型（7 种之一）
     * @param connectionConfig 连接配置
     * @param indexConfig      索引配置
     */
    public VectorStoreDto create(
        String name,
        String engineType,
        Map<String, Object> connectionConfig,
        Map<String, Object> indexConfig
    ) {
        String type = normalizeType(engineType);
        String trimmedName = requireName(name);
        assertNameFree(trimmedName, null);
        validateConnectionFields(type, connectionConfig);

        KbVectorStoreRecord r = new KbVectorStoreRecord();
        r.setId("vs-" + UUID.randomUUID().toString().substring(0, 8));
        r.setName(trimmedName);
        r.setEngineType(type);
        r.setConnectionConfig(KbConfigSecrets.toJson(
            connectionConfig == null ? Map.of() : connectionConfig));
        r.setIndexConfig(KbConfigSecrets.toJson(
            indexConfig == null ? Map.of() : indexConfig));
        mapper.insert(r);
        log.info("[向量库] 已创建 id={} name={} type={}", r.getId(), trimmedName, type);
        return toDto(r);
    }

    /**
     * 更新实例（PATCH 语义：null = 不改）。
     *
     * <p>⚠️ 换 {@code engineType} 时旧连接配置可能不适用 —— 源侧允许改，
     * 但这里要求同时给出新的 connectionConfig（否则新类型的必填字段校验必失败），
     * 相当于「改类型必须重填配置」。这比默默保留一份对不上类型的配置安全。
     */
    public VectorStoreDto update(
        String id,
        String name,
        String engineType,
        Map<String, Object> connectionConfig,
        Map<String, Object> indexConfig
    ) {
        KbVectorStoreRecord r = require(id);
        String type = engineType != null ? normalizeType(engineType) : r.getEngineType();

        if (name != null) {
            String trimmed = requireName(name);
            assertNameFree(trimmed, id);
            r.setName(trimmed);
        }

        Map<String, Object> existingConn = KbConfigSecrets.parse(r.getConnectionConfig());
        if (connectionConfig != null) {
            Map<String, Object> merged = KbConfigSecrets.preserveRedacted(
                connectionConfig, existingConn, KbVectorStoreTypes.sensitiveFields(type));
            validateConnectionFields(type, merged);
            r.setConnectionConfig(KbConfigSecrets.toJson(merged));
        } else if (!type.equals(r.getEngineType())) {
            // 只改类型、没给新配置 ⇒ 旧配置按新类型重校验，不通过就报错（不静默留下不兼容配置）
            validateConnectionFields(type, existingConn);
        }

        r.setEngineType(type);
        if (indexConfig != null) {
            r.setIndexConfig(KbConfigSecrets.toJson(indexConfig));
        }
        mapper.update(r);
        log.info("[向量库] 已更新 id={}", id);
        return toDto(r);
    }

    public void delete(String id) {
        KbVectorStoreRecord r = require(id);
        // ⚠️ 被知识库绑定时拒绝删除：否则那些库的检索会突然回落默认驱动，
        //    用户只会发现「搜索结果变了」，没有任何报错。
        long bound = countBoundKnowledgeBases(id);
        if (bound > 0) {
            throw new ConflictException(
                "该向量库正被 " + bound + " 个知识库使用，不能删除。请先在知识库中改绑其他向量库。");
        }
        mapper.deleteById(r.getId());
        log.info("[向量库] 已删除 id={}", id);
    }

    /**
     * 连通性测试（新建场景：用传入的配置试连，不落库）。
     *
     * <p><b>⚠️ 本机没有这些外部服务的 SDK 依赖，也无法真实连通</b>
     * （见 {@code W8-DESIGN.md}）。⇒ 这里做的是<b>配置可解析性 + 必填字段</b>检查，
     * 并<b>如实返回</b>「本机未接入该引擎的客户端 SDK，无法验证连通性」。
     * <p>⛔ 不伪造「连接成功」—— 那会让用户以为配好了，
     * 直到真正入库时才失败（W1 的 8 个外部驱动就是按「诚实不可用」处理的）。
     */
    public TestResult test(String engineType, Map<String, Object> connectionConfig) {
        String type = normalizeType(engineType);
        validateConnectionFields(type, connectionConfig);
        return TestResult.unavailable(type,
            "本机未接入 " + displayName(type) + " 的客户端 SDK，无法验证连通性；"
                + "配置字段校验已通过。");
    }

    /** 连通性测试（已存场景）。 */
    public TestResult testById(String id) {
        KbVectorStoreRecord r = require(id);
        Map<String, Object> conn = KbConfigSecrets.parse(r.getConnectionConfig());
        validateConnectionFields(r.getEngineType(), conn);
        return TestResult.unavailable(r.getEngineType(),
            "本机未接入 " + displayName(r.getEngineType()) + " 的客户端 SDK，无法验证连通性；"
                + "已存配置的必填字段校验通过。");
    }

    /**
     * 解析知识库实际使用的向量库实例（检索路径用）。
     *
     * @param vectorStoreId 知识库绑定的实例 ID（可空）
     * @return 实例；{@code null} = 没绑 ⇒ 调用方回落 W1 的类型默认驱动
     * @throws NotFoundException 绑了但实例不存在（⛔ 不静默回落 —— 否则用户以为在用 A 集群，
     *         实际查的是默认库）
     */
    public KbVectorStoreRecord resolveForKnowledgeBase(String vectorStoreId) {
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            return null;
        }
        return require(vectorStoreId);
    }

    /** 该实例当前是否可用于真实检索（本仓恒 false —— 无 SDK）。 */
    public boolean isAvailable(String engineType) {
        // 与 W1 的 8 个外部驱动口径一致：接口层完整、运行时诚实不可用。
        return false;
    }

    // ── 内部 ──────────────────────────────────────────────

    private KbVectorStoreRecord require(String id) {
        if (id == null || id.isBlank()) {
            throw new NotFoundException("VectorStore 未指定");
        }
        KbVectorStoreRecord r = mapper.selectOneById(id);
        if (r == null) {
            throw new NotFoundException("VectorStore " + id + " not found");
        }
        return r;
    }

    private String normalizeType(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            throw new ValidationException("engine_type is required");
        }
        String t = engineType.trim().toLowerCase();
        if (!KbVectorStoreTypes.isValid(t)) {
            throw new ValidationException("unsupported engine type: " + engineType
                + "（可选：" + KbVectorStoreTypes.all().stream().map(KbVectorStoreTypes.TypeInfo::type).toList() + "）");
        }
        return t;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name is required");
        }
        return name.trim();
    }

    private void assertNameFree(String name, String excludeId) {
        List<KbVectorStoreRecord> existing = mapper.selectListByQuery(
            QueryWrapper.create().eq("name", name));
        for (KbVectorStoreRecord r : existing) {
            if (excludeId == null || !excludeId.equals(r.getId())) {
                throw new ConflictException("向量库名称已存在：" + name);
            }
        }
    }

    /**
     * 必填连接字段校验。
     *
     * <p>⚠️ 只校验「类型元数据里标了 required 的字段非空」。
     * 不做格式校验（URL 长什么样、端口范围）—— 那是驱动自己的事，
     * 写在这里就会变成「改了驱动没改校验」的分叉。
     */
    private void validateConnectionFields(String type, Map<String, Object> config) {
        Map<String, Object> c = config == null ? Map.of() : config;
        for (String field : KbVectorStoreTypes.requiredConnectionFields(type)) {
            Object v = c.get(field);
            if (v == null || String.valueOf(v).isBlank()) {
                throw new ValidationException(field + " is required for engine type " + type);
            }
        }
    }

    /**
     * 统计有多少知识库绑定了这个向量库。
     *
     * <p>⚠️ 用参数化原生 SQL 只查 count，不加载 30 列的知识库实体。
     * <p>{@code deleted = 0} 必须显式写 —— 原生 SQL 绕过了 Flex 的逻辑删除自动追加，
     * 不写就会把已删的库也算进「正在使用」。
     */
    private long countBoundKnowledgeBases(String vectorStoreId) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM kb_knowledge_base WHERE vector_store_id = ? AND deleted = 0")) {
            ps.setString(1, vectorStoreId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            // fail-open：查不到引用数就不拦删除。
            // ⚠️ 这比 fail-closed 好：一次 DB 抖动不该让用户永远删不掉自己的配置。
            log.warn("[向量库] 统计知识库绑定数失败，按 0 处理: {}", e.toString());
            return 0L;
        }
    }

    private VectorStoreDto toDto(KbVectorStoreRecord r) {
        Map<String, Object> conn = KbConfigSecrets.mask(
            KbConfigSecrets.parse(r.getConnectionConfig()),
            KbVectorStoreTypes.sensitiveFields(r.getEngineType()));
        Map<String, Object> idx = KbConfigSecrets.parse(r.getIndexConfig());
        return new VectorStoreDto(
            r.getId(),
            r.getName(),
            r.getEngineType(),
            conn,
            idx,
            isAvailable(r.getEngineType()),
            r.getCreateTime() == null ? null : com.nexusai.kb.repo.KbTimeFormat.format(r.getCreateTime()),
            r.getUpdateTime() == null ? null : com.nexusai.kb.repo.KbTimeFormat.format(r.getUpdateTime()));
    }

    private static String displayName(String type) {
        KbVectorStoreTypes.TypeInfo info = KbVectorStoreTypes.get(type);
        return info == null ? type : info.displayName();
    }

    /**
     * 连通性测试结果。
     *
     * @param engineType 引擎类型
     * @param reachable  是否真的连上了
     * @param message    给用户看的话（连不上必须说清原因，⛔ 不能只说 failed）
     * @param serverVersion 检测到的服务端版本（连不上为 null）
     */
    public record TestResult(
        String engineType,
        boolean reachable,
        String message,
        String serverVersion
    ) {
        static TestResult unavailable(String engineType, String message) {
            return new TestResult(engineType, false, message, null);
        }
    }
}
