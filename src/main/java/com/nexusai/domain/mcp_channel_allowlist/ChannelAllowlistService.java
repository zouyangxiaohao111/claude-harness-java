package com.nexusai.domain.mcp_channel_allowlist;

import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import com.nexusai.repository.mcp_channel_allowlist.entity.ChannelAllowlistRecord;
import com.nexusai.repository.mcp_channel_allowlist.mapper.ChannelAllowlistMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * MCP channel allowlist 业务逻辑（Q-37 ledger 白名单改 DB 表 + REST）。
 *
 * <p>CC original: {@code getChannelAllowlist()}（services/mcp/channelAllowlist.ts:37-44）
 * 从 GrowthBook 'tengu_harbor_ledger' 读 [{marketplace, plugin}]。Java 侧 Q-37 拍板：
 * 白名单落 {@code mcp_channel_allowlist} DB 表，本服务提供 CRUD + 纯比对。
 * 插件级粒度：插件批准 = 其全部 channel server 批准（channelAllowlist.ts L1-16 注释）。
 *
 * <p>DDD 分层：只持有 domain record（{@link ChannelAllowlistEntry}），mapper 返回的
 * {@link ChannelAllowlistRecord} 通过 {@code toDomain()} / {@code fromDomain()} 互转。
 */
@Service
public class ChannelAllowlistService {

    private static final Logger log = LoggerFactory.getLogger(ChannelAllowlistService.class);

    @Autowired private ChannelAllowlistMapper mapper;

    /** 全部白名单条目 · CC original: getChannelAllowlist()（channelAllowlist.ts:37-44）。 */
    public List<ChannelAllowlistEntry> listAll() {
        return mapper.selectAll().stream().map(ChannelAllowlistRecord::toDomain).toList();
    }

    /**
     * 新增白名单条目 · Q-37 REST POST 落库。
     *
     * @param marketplace CC original: marketplace（channelAllowlist.ts:24）
     * @param plugin      CC original: plugin（channelAllowlist.ts:25）
     * @return 落库后的条目（含 id/createdAt）
     */
    public ChannelAllowlistEntry create(String marketplace, String plugin) {
        if (marketplace == null || marketplace.isBlank() || plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("marketplace/plugin 均不能为空");
        }
        ChannelAllowlistEntry entry = new ChannelAllowlistEntry(
            marketplace.trim(), plugin.trim(),
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        ChannelAllowlistRecord record = ChannelAllowlistRecord.fromDomain(entry);
        record.setId(generateId());
        mapper.insert(record);
        if (log.isInfoEnabled()) {
            log.info("[ChannelAllowlistService] 新增白名单: {}@{} id={}", plugin, marketplace, record.getId());
        }
        return new ChannelAllowlistEntry(record.getMarketplace(), record.getPlugin(), record.getCreatedAt());
    }

    /** 删除白名单条目 · Q-37 REST DELETE。 */
    public void delete(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        mapper.deleteById(id);
        if (log.isInfoEnabled()) {
            log.info("[ChannelAllowlistService] 删除白名单 id={}", id);
        }
    }

    /**
     * 纯比对：{marketplace, plugin} 是否在白名单 · CC original:
     * {@code entries.some(e => e.plugin === name && e.marketplace === marketplace)}
     * （channelNotification.ts:288-290 gate allowlist 步）。
     */
    public boolean isAllowed(String marketplace, String plugin) {
        if (marketplace == null || plugin == null) return false;
        return mapper.selectAll().stream()
            .map(ChannelAllowlistRecord::toDomain)
            .anyMatch(e -> marketplace.equals(e.marketplace()) && plugin.equals(e.plugin()));
    }

    /**
     * 按 pluginSource 判定是否在白名单 · CC original: {@code isChannelAllowlisted(pluginSource)}
     * （channelAllowlist.ts:67-76）— {@code parsePluginIdentifier(pluginSource)} 后纯 {marketplace, plugin}
     * 比对；pluginSource null / 无 marketplace → false。
     *
     * <p>parse 语义对齐 CC pluginIdentifier.ts:51-57（只按首个 {@code @} 切，不剥 {@code plugin:} 前缀）；
     * 与 {@link com.nexusai.application.agent.mcp.ChannelAllowlist#parsePluginIdentifier} 同一语义
     * （此处内联避免 domain→application 反向依赖）。
     *
     * @param pluginSource 插件真实来源标识（CC mcpPluginIntegration.ts:341 = {@code name@marketplace}，无前缀）
     */
    public boolean isAllowlisted(String pluginSource) {
        if (pluginSource == null || pluginSource.isEmpty()) return false;
        String name;
        String marketplace;
        int at = pluginSource.indexOf('@');
        if (at >= 0) {
            name = pluginSource.substring(0, at);
            marketplace = pluginSource.substring(at + 1);
        } else {
            name = pluginSource;
            marketplace = null;
        }
        if (marketplace == null || marketplace.isEmpty()) return false;
        return isAllowed(marketplace, name);
    }

    private static String generateId() {
        return "cal-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
