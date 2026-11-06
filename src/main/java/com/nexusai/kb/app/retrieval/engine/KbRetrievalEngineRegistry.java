package com.nexusai.kb.app.retrieval.engine;

import com.nexusai.domain.settings.SettingsService;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索引擎注册表 · 对应 WeKnora 的 {@code retrieverEngineMapping}
 * （{@code internal/types/types/tenant.go:17}）+ {@code GetRetrieveEngineService}。
 *
 * <h2>⭐ Spring 容器就是注册表</h2>
 * 构造注入 {@code List<KbRetrievalEngine>} ⇒ 新增驱动只要多一个 {@code @Component}，
 * ⛔ 不需要在任何一个地方登记名字。这与源侧手写 map 是两种做法，但语义等价。
 *
 * <h2>⭐⭐ 解析顺序（越具体越优先）</h2>
 * <ol>
 *   <li><b>知识库级</b> {@code kb_knowledge_base.retriever_engine}（用户显式指定）</li>
 *   <li><b>全局默认</b> {@code settings.kb_retriever_engine}</li>
 *   <li><b>兜底</b> {@code sqlite}（用户 2026-11-04 裁定「我们默认用 sqlite」）</li>
 * </ol>
 *
 * <h2>⚠️ 两条硬约束</h2>
 * <ul>
 *   <li><b>未注册的驱动 ⇒ 抛异常并列出可用驱动</b>，⛔ 不静默回落。
 *       静默回落会让用户以为数据进了 pgvector，实际全在本地 SQLite，
 *       直到换机器才发现全丢 —— 这类故障无法从日志里看出
 *       （因为「一切正常」）。</li>
 *   <li><b>注册了但 {@code available() == false} 的驱动也要抛</b>，
 *       并说明「该驱动在本构建中不可用」。</li>
 * </ul>
 */
@Component
public class KbRetrievalEngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(KbRetrievalEngineRegistry.class);

    /** 用户 2026-11-04 裁定的默认驱动。 */
    public static final String DEFAULT_ENGINE = "sqlite";

    private final Map<String, KbRetrievalEngine> engines = new LinkedHashMap<>();

    @Autowired(required = false)
    private KbKnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired(required = false)
    private SettingsService settingsService;

    /** 知识库域配置（R0 · V273）。{@code required=false} ⇒ 精简装配（单测）下回落 sqlite。 */
    @Autowired(required = false)
    private com.nexusai.kb.config.KbConfigService kbConfigService;

    /**
     * 向量库实例表（W8）。{@code required = false} —— 精简装配（单测）下可以没有，
     * 那时「实例层」自然不生效，回落到 W1 的类型层。
     */
    @Autowired(required = false)
    private com.nexusai.kb.repo.mapper.KbVectorStoreMapper vectorStoreMapper;

    public KbRetrievalEngineRegistry(List<KbRetrievalEngine> discovered) {
        if (discovered != null) {
            for (KbRetrievalEngine e : discovered) {
                if (e == null || e.engineType() == null || e.engineType().isBlank()) {
                    continue;
                }
                KbRetrievalEngine prev = engines.put(e.engineType(), e);
                if (prev != null) {
                    // 同一类型注册两次 = 装配错误，必须喊出来（否则选谁取决于扫描顺序）
                    log.error("[检索引擎] 驱动类型重复注册 type={} {} → {}，后者生效",
                        e.engineType(), prev.getClass().getSimpleName(), e.getClass().getSimpleName());
                }
            }
        }
        log.info("[检索引擎] 已注册驱动 = {}（默认 {}）", engines.keySet(), DEFAULT_ENGINE);
    }

    /** 已注册的驱动类型（供配置界面列出可选项）。 */
    public List<String> registeredTypes() {
        return new ArrayList<>(engines.keySet());
    }

    /** 本机真正可用的驱动类型。 */
    public List<String> availableTypes() {
        List<String> out = new ArrayList<>();
        for (KbRetrievalEngine e : engines.values()) {
            if (e.available()) {
                out.add(e.engineType());
            }
        }
        return out;
    }

    /**
     * 取驱动实例。
     *
     * @param type 驱动类型（null/空 = 用默认 sqlite）
     * @return 驱动（永不 null）
     * @throws IllegalStateException 类型未注册 / 本机不可用
     */
    public KbRetrievalEngine require(String type) {
        String key = (type == null || type.isBlank()) ? DEFAULT_ENGINE : type.trim().toLowerCase();
        KbRetrievalEngine engine = engines.get(key);
        if (engine == null) {
            // ⭐ fail-loud：静默回落会让「换驱动」变成无声的谎话
            throw new IllegalStateException(
                "不支持的检索驱动 '" + key + "'；可用驱动 = " + availableTypes());
        }
        if (!engine.available()) {
            throw new IllegalStateException(
                "检索驱动 '" + key + "' 已注册但本机不可用（缺少依赖或未配置）；可用驱动 = "
                    + availableTypes());
        }
        return engine;
    }

    /**
     * 解析某个知识库实际使用的驱动。
     *
     * <p>顺序（<b>批次 W8 起多了一层</b>，用户 2026-11-05 裁定「实例层覆盖类型层」）：
     * <ol>
     *   <li><b>实例层</b>：库绑了 {@code vector_store_id} ⇒ 用该实例的 {@code engine_type}</li>
     *   <li>知识库级 {@code retriever_engine}</li>
     *   <li>全局默认 {@code settings.kb_retriever_engine}</li>
     *   <li>sqlite</li>
     * </ol>
     *
     * <p>⚠️ 这里<b>不</b>做可用性校验，只在真正取实例时校验 —— 因为「看配置」和「用配置」是两件事，
     * 前者不该因为缺依赖就抛异常（否则整个知识库列表都读不出来）。
     *
     * @param knowledgeBaseId 知识库 ID（null = 只看全局）
     * @return 驱动类型（恒非空）
     */
    public String resolveEngineType(String knowledgeBaseId) {
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank() && knowledgeBaseMapper != null) {
            try {
                KbKnowledgeBaseRecord kb = knowledgeBaseMapper.selectOneById(knowledgeBaseId);
                if (kb != null) {
                    // ① 实例层优先（W8）：绑了具体实例就按实例的引擎类型走
                    String storeType = resolveBoundStoreEngineType(kb.getVectorStoreId());
                    if (storeType != null) {
                        return storeType;
                    }
                    // ② 类型层（W1）
                    if (kb.getRetrieverEngine() != null && !kb.getRetrieverEngine().isBlank()) {
                        return kb.getRetrieverEngine().trim().toLowerCase();
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[检索引擎] 读取知识库驱动配置失败，回落全局 kb={} 原因={}",
                    knowledgeBaseId, e.getMessage());
            }
        }
        // [R0 2026-11-05] 全局默认检索引擎现读 kb_config（V273）；
        // settings.kb_retriever_engine 保留只读兼容，不再是真源。
        // ⚠️ 门控用 kbConfigService 而不是 settingsService —— 后者已不再参与这条判断，
        //    拿它当门会让「有 settings 但没有 kb_config」的精简装配走到空指针分支。
        if (kbConfigService != null) {
            try {
                String global = kbConfigService.readRetrieverEngine();
                if (global != null && !global.isBlank()) {
                    return global.trim().toLowerCase();
                }
            } catch (RuntimeException e) {
                log.warn("[检索引擎] 读取全局驱动配置失败，回落 {} 原因={}",
                    DEFAULT_ENGINE, e.getMessage());
            }
        }
        return DEFAULT_ENGINE;
    }

    /**
     * 查「库绑定的向量库实例」的引擎类型。
     *
     * <p>⚠️ 绑定存在但实例查不到时<b>记 WARN 并回落类型层</b>，⛔ 不直接抛：
     * 实例被误删后，让整个知识库完全不可读比「回落到原类型驱动」更糟 ——
     * 用户至少还能继续用原来的驱动。真正要拦的是「配了不存在的实例还静默成功」，
     * 那种在写入侧（{@code KbVectorStoreService} 删除时校验绑定）已经拦住了。
     *
     * @param vectorStoreId 库绑定的实例 ID（可空）
     * @return 引擎类型；没绑 / 查不到返回 {@code null}
     */
    private String resolveBoundStoreEngineType(String vectorStoreId) {
        if (vectorStoreId == null || vectorStoreId.isBlank() || vectorStoreMapper == null) {
            return null;
        }
        try {
            com.nexusai.kb.repo.entity.KbVectorStoreRecord store =
                vectorStoreMapper.selectOneById(vectorStoreId);
            if (store != null && store.getEngineType() != null
                && !store.getEngineType().isBlank()) {
                return store.getEngineType().trim().toLowerCase();
            }
            log.warn("[检索引擎] 知识库绑定的向量库实例不存在，回落类型层 storeId={}", vectorStoreId);
            return null;
        } catch (RuntimeException e) {
            log.warn("[检索引擎] 读取向量库实例失败，回落类型层 storeId={} 原因={}",
                vectorStoreId, e.getMessage());
            return null;
        }
    }

    /** 解析并取实例（最常用组合）。 */
    public KbRetrievalEngine resolve(String knowledgeBaseId) {
        return require(resolveEngineType(knowledgeBaseId));
    }
}
