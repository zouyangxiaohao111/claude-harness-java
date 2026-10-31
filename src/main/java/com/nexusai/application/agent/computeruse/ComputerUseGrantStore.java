package com.nexusai.application.agent.computeruse;

import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话级 computer-use 授权位的读写（RMW 单点）· 承载 {@code sessions.computer_use_grants}
 * （V250 列）。
 *
 * <h2>读法（照抄 {@code SessionService.getDisabledTools}
 * {@code domain/session/SessionService.java:612-629}）</h2>
 * <pre>
 * SessionRecord s = sessionMapper.selectOneById(sessionId);
 * String json = s.getDisabledTools();
 * if (json == null || json.isBlank()) return Set.of();
 * try { ... } catch (Exception e) { log.warn(...); return Set.of(); }
 * </pre>
 * ⇒ 本类同款：{@link ComputerUseGrants#parse(String)} 解析失败降级为空 + WARN，<b>不抛</b>。
 *
 * <h2>写法（照抄 {@code SessionPermissionOverlay.persistSessionUpdates}
 * {@code application/agent/permission/SessionPermissionOverlay.java:226,244,263}）</h2>
 * <pre>
 * synchronized (RMW_LOCK) {
 *     SessionRecord record = sessionMapper.selectOneById(sessionId);
 *     ...
 *     record.setSessionPermissionRules(json);
 *     sessionMapper.update(record);          // 忽略 null 字段
 * }
 * </pre>
 * ⇒ 本类同款：静态锁内读改写 + {@code sessionMapper.update(entity)}（忽略 null 字段）。
 *
 * <h2>为什么需要静态锁</h2>
 * <p>列 RMW 有「读-改-写」窗口：同一会话的并发授权（并行工具调用）会丢更新。
 * 授权是人工驱动、低频（每会话每次弹窗最多个位数），持锁期只有一次主键查询 + 一次
 * 单行更新，亚毫秒级 —— 换来「不丢 grant」的确定性（与
 * {@code SessionPermissionOverlay} 类 javadoc「线程安全」段同款论证）。
 */
public final class ComputerUseGrantStore {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseGrantStore.class);

    /** 会话列 RMW 序列化锁（照抄 {@code SessionPermissionOverlay.RMW_LOCK} 的用法）。 */
    private static final Object RMW_LOCK = new Object();

    private final SessionMapper sessionMapper;

    /**
     * @param sessionMapper 会话 mapper（不可为 null）
     */
    public ComputerUseGrantStore(SessionMapper sessionMapper) {
        if (sessionMapper == null) {
            throw new IllegalArgumentException("ComputerUseGrantStore 需要 SessionMapper");
        }
        this.sessionMapper = sessionMapper;
    }

    /**
     * 读会话的 computer-use 授权快照。
     *
     * @param sessionId 会话 id（可空 / 空白 ⇒ 空授权）
     * @return 授权快照（永不 null；解析失败降级为空 + WARN）
     */
    public ComputerUseGrants read(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return ComputerUseGrants.empty();
        }
        try {
            SessionRecord record = sessionMapper.selectOneById(sessionId);
            if (record == null) {
                return ComputerUseGrants.empty();
            }
            return ComputerUseGrants.parse(record.getComputerUseGrants());
        } catch (Exception e) {
            log.warn("[computer-use] 读会话 computer-use 授权失败（降级为空授权）: session={} err={}",
                    sessionId, e.toString());
            return ComputerUseGrants.empty();
        }
    }

    /**
     * 合并写入新的授权（应用 + 授权位）。
     *
     * <p><b>授权位 truthy-only 只增不减</b>（{@link ComputerUseGrantFlags#mergeTruthyOnly}）——
     * 后一次 {@code request_access} 不能撤销先前的剪贴板授权（CCB
     * {@code packages/@ant/computer-use-mcp/src/mcpServer.ts:58-77}）。
     *
     * @param sessionId      会话 id
     * @param newGrants      新增/更新的授权条目
     * @param requestedFlags 本次请求的授权位
     * @return 写入后的授权快照（写失败 ⇒ 返回写入前的快照，不抛）
     */
    public ComputerUseGrants grant(String sessionId,
                                   List<ComputerUseGrant> newGrants,
                                   ComputerUseGrantFlags requestedFlags) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[computer-use] 无会话 id ⇒ 授权无法落库（sessionId 为空）");
            return ComputerUseGrants.empty();
        }
        synchronized (RMW_LOCK) {
            ComputerUseGrants before;
            SessionRecord record;
            try {
                record = sessionMapper.selectOneById(sessionId);
                if (record == null) {
                    log.warn("[computer-use] 会话 {} 不存在 ⇒ computer-use 授权无法落库", sessionId);
                    return ComputerUseGrants.empty();
                }
                before = ComputerUseGrants.parse(record.getComputerUseGrants());
            } catch (Exception e) {
                log.warn("[computer-use] 读会话 computer-use 授权失败（不写库）: session={} err={}",
                        sessionId, e.toString());
                return ComputerUseGrants.empty();
            }
            ComputerUseGrants after = before.merge(newGrants, requestedFlags);
            try {
                record.setComputerUseGrants(after.toJson());
                record.setUpdatedAt(OffsetDateTime.now().toString());
                // 忽略 null 字段（照抄 SessionPermissionOverlay:265 的 sessionMapper.update(entity)）
                sessionMapper.update(record);
            } catch (Exception e) {
                log.warn("[computer-use] 写会话 computer-use 授权失败（不中断批准流）: session={} err={}",
                        sessionId, e.toString());
                return before;
            }
            if (log.isInfoEnabled()) {
                log.info("[computer-use] computer-use 授权已写入会话列: session={} 应用数={} 授权位="
                        + "clipboardRead={} clipboardWrite={} systemKeyCombos={}",
                        sessionId, after.apps().size(),
                        after.flags().clipboardRead(),
                        after.flags().clipboardWrite(),
                        after.flags().systemKeyCombos());
            }
            return after;
        }
    }
}
