package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 后台安装 reconcile 数据面的配置存储 · 对齐 CC {@code marketplaceManager.ts}
 * {@code getDeclaredMarketplaces}（:161-193）/ {@code loadKnownMarketplacesConfig}（:264-298）/
 * {@code saveKnownMarketplacesConfig}（:327-350）。
 *
 * <p>职责：向 reconcile 数据面暴露三个 seam ——
 * <ol>
 *   <li><b>declared 意图</b>：{@link #getDeclaredMarketplaces()} —— settings.extraKnownMarketplaces
 *       （Java 意图层，MPL1 saveMarketplaceToSettings 写入），CC :161-193 merged settings 等价；</li>
 *   <li><b>materialized 文件态</b>：{@link #loadKnownMarketplacesConfig()} —— known_marketplaces.json
 *       （抛错版，load→mutate→save 必须用抛错版防覆盖损坏文件，CC :301-308）；</li>
 *   <li><b>落盘</b>：{@link #saveKnownMarketplacesConfig(Map)} + {@link #clearMarketplacesCache()}。</li>
 * </ol>
 *
 * <p>文件 I/O 全部委托 {@link MarketplaceManager}（既有 @Component，load/save/clear 已对齐 CC），
 * 本类只新增 declared 读取与 reconcile 需要的记录类型，不复制文件读写逻辑。
 */
public class MarketplaceConfigStore {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceConfigStore.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 文件态 I/O 委托（既有 MarketplaceManager）。 */
    private final MarketplaceManager manager;

    /** settings 意图层读取（extraKnownMarketplaces）；null = 未接线 → declared 空（早期 return）。 */
    private final ConfigStorage configStorage;

    /** 默认 original cwd（CC getOriginalCwd）；会话有 cwd 概念时注入。 */
    private final Supplier<String> cwdSupplier;

    public MarketplaceConfigStore(MarketplaceManager manager, ConfigStorage configStorage,
                                  Supplier<String> cwdSupplier) {
        this.manager = manager;
        this.configStorage = configStorage;
        // 方案1 接线：经 CwdResolution.getOriginalCwdLayer(RequestContext.sessionId()) 取会话 original cwd
        //   （对齐 CC getOriginalCwd · addMarketplaceSource resolve(source.path)=process.cwd()=启动 cwd 语义，
        //    marketplaceManager.ts:1792-1793）。startup 无会话时回落 user.dir，与旧行为零变化。
        this.cwdSupplier = cwdSupplier != null ? cwdSupplier
            : () -> CwdResolution.getOriginalCwdLayer(RequestContext.sessionId());
    }

    /** 简化构造：不接线 settings（declared 空）与 cwd（默认 user.dir）。 */
    public MarketplaceConfigStore(MarketplaceManager manager) {
        this(manager, null, null);
    }

    /** CC DeclaredMarketplace（marketplaceManager.ts:144-152）：{source, sourceIsFallback?, autoUpdate?}。 */
    public record DeclaredMarketplace(MarketplaceSource source, boolean sourceIsFallback, Boolean autoUpdate) {

        /** 便捷构造：无 autoUpdate（CC :141 可空，缺省回退 JSON 态）。 */
        public DeclaredMarketplace(MarketplaceSource source, boolean sourceIsFallback) {
            this(source, sourceIsFallback, null);
        }
    }

    /**
     * 读取 settings 意图层 declared marketplaces · CC {@code getDeclaredMarketplaces}（:161-193）。
     *
     * <p>Java 等价：settings.extraKnownMarketplaces（MPL1 saveMarketplaceToSettings 写入的
     * KnownMarketplace 对象）。sourceIsFallback 恒 false —— 隐式官方 marketplace（enabledPlugins 引用）
     * 属 MPL9 official 层，Java 未建模，此处不回填 implicit。
     *
     * <p><b>[D6 修复] JsonNode 兼容</b>：生产 ConfigStorage（{@code FileConfigStorage}）的
     * {@code readSettings} 对嵌套对象返回原始 {@link com.fasterxml.jackson.databind.JsonNode}
     * （FileConfigStorage.java:189 jsonNodeToJavaValue "对象/数组 → 原始 JsonNode"），而非 Map；
     * 仅 {@code FakeConfigStorage}（测试）返回 Map。旧实现 {@code !(raw instanceof Map)} 提前 return
     * → 生产 declared 恒空 → reconcile 永不触发（D6 反射发现的深层原因：装配后仍不安装）。
     * 本方法兼容 Map 与 ObjectNode 两种返回值，测试（Map）与生产（JsonNode）均可达。
     *
     * @return name → declared；settings 未接线 / 无条目 → 空 Map（不抛）
     */
    public Map<String, DeclaredMarketplace> getDeclaredMarketplaces() {
        Map<String, DeclaredMarketplace> declared = new LinkedHashMap<>();
        if (configStorage == null) {
            if (log.isDebugEnabled()) {
                log.debug("ConfigStorage 未接线，declared marketplaces 为空（早期 return 分支）");
            }
            return declared;
        }
        Object raw;
        try {
            raw = configStorage.readSettings(List.of("extraKnownMarketplaces"));
        } catch (Exception e) {
            log.warn("读取 settings.extraKnownMarketplaces 失败，declared 空：{}", e.getMessage());
            return declared;
        }
        if (raw instanceof Map<?, ?> entries) {
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                putIfSource(declared, String.valueOf(e.getKey()), e.getValue());
            }
        } else if (raw instanceof com.fasterxml.jackson.databind.JsonNode node && node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                putIfSource(declared, e.getKey(), e.getValue());
            }
        }
        return declared;
    }

    /** 能解析出 source 才登记 declared；否则 warn 跳过。autoUpdate 同源提取（CC :141 可空）。 */
    private void putIfSource(Map<String, DeclaredMarketplace> declared, String name, Object value) {
        MarketplaceSource source = extractSource(value);
        if (source != null) {
            Boolean autoUpdate = extractAutoUpdate(value);
            declared.put(name, new DeclaredMarketplace(source, false, autoUpdate));
        } else if (log.isWarnEnabled()) {
            log.warn("declared marketplace '{}' 缺少可解析 source，跳过", name);
        }
    }

    /** 从 settings 条目（KnownMarketplace 对象 / JSON Map / JsonNode）提取 autoUpdate。 */
    private Boolean extractAutoUpdate(Object value) {
        try {
            if (value instanceof KnownMarketplace km) {
                return km.autoUpdate();
            }
            if (value instanceof Map || value instanceof com.fasterxml.jackson.databind.JsonNode) {
                return JSON.convertValue(value, KnownMarketplace.class).autoUpdate();
            }
        } catch (Exception e) {
            log.warn("declared marketplace autoUpdate 反序列化失败：{}", e.getMessage());
        }
        return null;
    }

    /** 从 settings 条目（KnownMarketplace 对象 / JSON Map / JsonNode）提取 source。 */
    private MarketplaceSource extractSource(Object value) {
        try {
            if (value instanceof KnownMarketplace km) {
                return km.source();
            }
            if (value instanceof Map || value instanceof com.fasterxml.jackson.databind.JsonNode) {
                return JSON.convertValue(value, KnownMarketplace.class).source();
            }
        } catch (Exception e) {
            log.warn("declared marketplace source 反序列化失败：{}", e.getMessage());
        }
        return null;
    }

    /** known_marketplaces.json 读取（抛错版，损坏不覆盖）· 委托 MarketplaceManager。 */
    public Map<String, KnownMarketplace> loadKnownMarketplacesConfig() {
        return manager.loadKnownMarketplacesConfig();
    }

    /** known_marketplaces.json 落盘 · 委托 MarketplaceManager。 */
    public void saveKnownMarketplacesConfig(Map<String, KnownMarketplace> config) {
        manager.saveKnownMarketplacesConfig(config);
    }

    /** marketplace memoize 全清 · CC clearMarketplacesCache（marketplaceManager.ts:122-123）。 */
    public void clearMarketplacesCache() {
        manager.clearMarketplacesCache();
    }

    /** marketplaces 缓存目录 · CC getMarketplacesCacheDir（:110-112）。 */
    public String getMarketplacesCacheDir() {
        return manager.getMarketplacesCacheDir();
    }

    /** original cwd（diff 源归一化 projectRoot 兜底）· CC getOriginalCwd。 */
    public String getOriginalCwd() {
        return cwdSupplier.get();
    }
}
