package com.nexusai.domain.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.connector.ConnectorDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内置连接器目录 · 读取 {@code classpath:/connectors/catalog.json}。
 *
 * <p><b>WHY 静态资产</b>：连接器元数据随版本发布（与 octop catalog 对应），只有用户连接状态才落库。
 * 启动时一次性解析并做 key 去重，运行时零 IO。
 *
 * <p>目录加载失败（文件缺失/JSON 非法）不阻断启动：记 error、按空目录处理
 * —— 连接器市场不可用不应让整个桌面端起不来。
 */
@Component
public class ConnectorCatalog {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCatalog.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE = "/connectors/catalog.json";

    private final List<ConnectorDefinition> connectors;
    private final Map<String, ConnectorDefinition> byKey;

    public ConnectorCatalog() {
        this.connectors = load();
        Map<String, ConnectorDefinition> map = new LinkedHashMap<>();
        for (ConnectorDefinition d : connectors) {
            map.putIfAbsent(d.key(), d);
        }
        this.byKey = Map.copyOf(map);
        log.info("[ConnectorCatalog] 内置连接器 {} 条（可见 {} 条）",
                connectors.size(), visible().size());
    }

    private static List<ConnectorDefinition> load() {
        try (InputStream in = ConnectorCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.error("[ConnectorCatalog] 未找到 {}（连接器市场为空）", RESOURCE);
                return List.of();
            }
            CatalogFile file = JSON.readValue(in, CatalogFile.class);
            List<ConnectorDefinition> out = new ArrayList<>();
            if (file != null && file.connectors() != null) {
                for (ConnectorDefinition d : file.connectors()) {
                    if (d == null || d.key() == null || d.key().isBlank()) {
                        log.warn("[ConnectorCatalog] 跳过缺少 key 的连接器条目");
                        continue;
                    }
                    if (d.authType() == null || d.authType().isBlank()) {
                        log.warn("[ConnectorCatalog] 连接器 {} 缺少 authType，按不可用处理", d.key());
                        continue;
                    }
                    out.add(d);
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.error("[ConnectorCatalog] 解析 {} 失败（连接器市场为空）: {}", RESOURCE, e.toString());
            return List.of();
        }
    }

    /** 全部条目（含默认隐藏）。 */
    public List<ConnectorDefinition> all() {
        return connectors;
    }

    /** 默认可见条目（hidden=false）· {@code GET /api/market/connectors} 的缺省数据源。 */
    public List<ConnectorDefinition> visible() {
        return connectors.stream().filter(d -> !d.hiddenByDefault()).toList();
    }

    /** 按 key 查找（未找到为空）。 */
    public Optional<ConnectorDefinition> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
    }

    /** 按 key 查找，未找到抛 404（REST 入口用）。 */
    public ConnectorDefinition require(String key) {
        return find(key).orElseThrow(() -> new NotFoundException("连接器不存在：" + key));
    }

    /** catalog.json 顶层结构。 */
    private record CatalogFile(Integer version, List<ConnectorDefinition> connectors) {
    }
}
