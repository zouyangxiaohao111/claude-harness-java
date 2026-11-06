package com.nexusai.domain.mcp_channel_binding;

import com.nexusai.model.mcp_channel_binding.ChannelBindingEntry;
import com.nexusai.repository.mcp_channel_binding.entity.ChannelBindingRecord;
import com.nexusai.repository.mcp_channel_binding.mapper.ChannelBindingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 外部渠道 ↔ 会话 绑定业务逻辑。
 *
 * <p><b>WHY 本服务存在</b>：CC 单进程单会话 ⇒ channel 白名单是进程级全局、消息靠 ambient
 * {@code agentId: ci()} 进当前 agent 队列 ⇒ <b>CC 压根没有「消息该给哪个会话」这个问题</b>。
 * 本仓多会话 ⇒ 必须显式记录绑定关系，且 MCP 连接是全局单例、消费是分会话的
 * （{@code NotificationQueue.drainForQuery} 的 [3a] 规则：具体会话的 turn 只捞
 * {@code sessionId} 匹配的命令）⇒ 归属写错 = 消息永远躺在队列里没人消费。
 *
 * <p><b>互斥语义</b>（用户裁定 2026-11-03）：
 * <ul>
 *   <li>互斥键 = <b>渠道 id</b>（{@code channel_id}），<b>不是</b> serverName ——
 *       两个 server 都声明 {@code id=wechat} 时仍互斥（不做 serverName 兜底的意义就在这）；</li>
 *   <li>同一渠道 id 全局只能绑一个会话 ⇒ DB {@code UNIQUE(channel_id)} 兜底；</li>
 *   <li>同一会话可同时绑多个不同渠道（微信 + 飞书 + 企微并存）。</li>
 * </ul>
 *
 * <p><b>DDD 分层</b>：只持有 domain record（{@link ChannelBindingEntry}），
 * mapper 返回的 {@link ChannelBindingRecord} 经 {@code toDomain()} 互转。
 */
@Service
public class ChannelBindingService {

    private static final Logger log = LoggerFactory.getLogger(ChannelBindingService.class);

    @Autowired private ChannelBindingMapper mapper;

    /**
     * 绑定结果 · 互斥冲突时不抛异常而是返回结构化结果（供上层决定 409 / 转移确认）。
     *
     * @param entry    绑定后的条目（成功时）
     * @param conflict 与之冲突的既有绑定（同一渠道已被别的会话占用时；成功时为 null）
     */
    public record BindResult(ChannelBindingEntry entry, ChannelBindingEntry conflict) {
        public boolean conflicted() { return conflict != null; }
    }

    /** 全部绑定条目（前端渠道面板用）。 */
    public List<ChannelBindingEntry> listAll() {
        return mapper.selectAll().stream().map(ChannelBindingRecord::toDomain).toList();
    }

    /** 某会话已绑定的渠道。 */
    public List<ChannelBindingEntry> listBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        return mapper.selectListBySessionId(sessionId).stream().map(ChannelBindingRecord::toDomain).toList();
    }

    /**
     * 按渠道 id 查绑定（互斥判定的唯一读入口）。
     *
     * <p>门序[3] 消费它：{@code Optional.empty()} ⇒ 没人认领这个渠道 ⇒ skip；
     * {@code present} ⇒ 取其 {@code sessionId} 作为本 handler 的消息归属。
     */
    public Optional<ChannelBindingEntry> findByChannelId(String channelId) {
        if (channelId == null || channelId.isBlank()) return Optional.empty();
        ChannelBindingRecord r = mapper.selectOneByChannelId(channelId);
        return r == null ? Optional.empty() : Optional.of(r.toDomain());
    }

    /**
     * 按 server 名查绑定（server → 渠道身份 → 归属会话）。
     *
     * <p><b>为什么需要按 server 查</b>：门序[3] 在 MCP 连接时触发，那时我们手里只有
     * {@code serverName}；渠道 id 可能来自 server 声明的 capability，也可能来自用户在
     * MCP 管理页的显式指定 ⇒ 两条路径都落到 {@code server_name} 列上。
     */
    public Optional<ChannelBindingEntry> findByServerName(String serverName) {
        if (serverName == null || serverName.isBlank()) return Optional.empty();
        List<ChannelBindingRecord> list = mapper.selectListByServerName(serverName);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0).toDomain());
    }

    /**
     * 绑定渠道到会话。
     *
     * @param channelId  渠道 id（互斥键）
     * @param serverName 承载该渠道的 MCP server 名
     * @param sessionId  归属会话
     * @param force      true = 抢占（先把既有绑定解绑）；false = 冲突即返回 conflict
     * @return 绑定结果（冲突时 {@code conflict != null}）
     */
    public BindResult bind(String channelId, String serverName, String sessionId, boolean force) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId 不能为空");
        }
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName 不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        Optional<ChannelBindingEntry> existing = findByChannelId(channelId);
        if (existing.isPresent()) {
            ChannelBindingEntry old = existing.get();
            if (old.sessionId().equals(sessionId)) {
                // 同一会话重复绑定 → 幂等（更新 serverName，不新建行）
                ChannelBindingRecord rec = mapper.selectOneByChannelId(channelId);
                rec.setServerName(serverName);
                rec.setBoundAt(now());
                mapper.update(rec);
                if (log.isInfoEnabled()) {
                    log.info("[ChannelBindingService] 绑定幂等更新: channel={} server={} session={}",
                        channelId, serverName, sessionId);
                }
                return new BindResult(rec.toDomain(), null);
            }
            if (!force) {
                if (log.isInfoEnabled()) {
                    log.info("[ChannelBindingService] 绑定冲突: channel={} 已被 session={} 占用"
                        + "（请求方 session={}，未 force）", channelId, old.sessionId(), sessionId);
                }
                return new BindResult(null, old);
            }
            // force = 抢占：先删旧行再插新行
            mapper.deleteById(old.id());
            if (log.isInfoEnabled()) {
                log.info("[ChannelBindingService] 绑定抢占: channel={} 从 session={} 转到 session={}",
                    channelId, old.sessionId(), sessionId);
            }
        }

        ChannelBindingRecord rec = new ChannelBindingRecord();
        rec.setId("chb-" + UUID.randomUUID().toString().substring(0, 8));
        rec.setChannelId(channelId);
        rec.setServerName(serverName);
        rec.setSessionId(sessionId);
        rec.setBoundAt(now());
        mapper.insert(rec);
        if (log.isInfoEnabled()) {
            log.info("[ChannelBindingService] 新增绑定: channel={} server={} session={} id={}",
                channelId, serverName, sessionId, rec.getId());
        }
        return new BindResult(rec.toDomain(), null);
    }

    /**
     * 解绑（幂等 · 未绑定时返回 false 不报错）。
     *
     * @param channelId  渠道 id
     * @param sessionId  期望的归属会话；null = 不校验归属（管理侧强制解绑）
     * @return 是否真的解绑了
     */
    public boolean unbind(String channelId, String sessionId) {
        if (channelId == null || channelId.isBlank()) return false;
        Optional<ChannelBindingEntry> existing = findByChannelId(channelId);
        if (existing.isEmpty()) return false;
        ChannelBindingEntry e = existing.get();
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(e.sessionId())) {
            if (log.isWarnEnabled()) {
                log.warn("[ChannelBindingService] 解绑跳过: channel={} 归属 session={}，"
                    + "请求方 session={} 不匹配", channelId, e.sessionId(), sessionId);
            }
            return false;
        }
        mapper.deleteById(e.id());
        if (log.isInfoEnabled()) {
            log.info("[ChannelBindingService] 解绑: channel={} server={} session={}",
                channelId, e.serverName(), e.sessionId());
        }
        return true;
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
