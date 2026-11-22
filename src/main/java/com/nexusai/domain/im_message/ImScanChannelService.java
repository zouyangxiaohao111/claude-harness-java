package com.nexusai.domain.im_message;

import com.mybatisflex.core.update.UpdateChain;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_message.ImScanChannelEntry;
import com.nexusai.repository.im_message.entity.ImScanChannelRecord;
import com.nexusai.repository.im_message.mapper.ImScanChannelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 扫描渠道配置业务逻辑（V315）· 「要不要定时扫这个渠道」。
 *
 * <p><b>为什么是独立表而不是实例上的一个布尔列</b>：见 V315 迁移文件头 —— 这是<b>渠道级运营决策</b>，
 * 不是实例属性；独立表还能随开随关而不重建实例，并记下「上次扫到哪」。
 *
 * <p>⚠️ <b>本仓的老坑</b>：MyBatis-Flex 的 {@code update(entity)} 默认<b>忽略 null 字段</b>
 * ⇒ 实体置 null 再 update ⇒ 那一列根本不进 SQL ⇒ 「上次失败原因」永远清不掉
 * （用户看到的是「上次失败：xxx」一直挂着，即使后来已经成功了）。
 * 所以本服务所有「要把列写成 NULL」的更新一律走 {@link UpdateChain}（对齐 {@code ImChatService.unbind}）。
 */
@Service
public class ImScanChannelService {

    private static final Logger log = LoggerFactory.getLogger(ImScanChannelService.class);

    @Autowired private ImScanChannelMapper mapper;

    /** 全部配置。 */
    public List<ImScanChannelEntry> list() {
        return mapper.selectAllOrdered().stream().map(ImScanChannelRecord::toDomain).toList();
    }

    /** 只取启用中的（调度器每轮用）。 */
    public List<ImScanChannelEntry> listEnabled() {
        return mapper.selectListEnabled().stream().map(ImScanChannelRecord::toDomain).toList();
    }

    /** 按实例查。 */
    public Optional<ImScanChannelEntry> find(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        ImScanChannelRecord r = mapper.selectOneByInstanceId(instanceId);
        return r == null ? Optional.empty() : Optional.of(r.toDomain());
    }

    /** 开启定时扫描（幂等；行不存在则建）。 */
    public ImScanChannelEntry enable(String instanceId) {
        return setEnabled(instanceId, true);
    }

    /**
     * 关闭定时扫描（幂等；行不存在则建一条「已关闭」的行）。
     *
     * <p>为什么不存在也要建：用户的意图是「这个渠道不要扫」⇒ 这个意图本身就该被记下来，
     * 否则下次打开列表时它「从来没配置过」，用户会以为自己没关成功。
     */
    public ImScanChannelEntry disable(String instanceId) {
        return setEnabled(instanceId, false);
    }

    private ImScanChannelEntry setEnabled(String instanceId, boolean enabled) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ValidationException("instanceId 不能为空");
        }
        ImScanChannelRecord existing = mapper.selectOneByInstanceId(instanceId);
        if (existing == null) {
            ImScanChannelRecord r = new ImScanChannelRecord();
            r.setInstanceId(instanceId);
            r.setEnabled(enabled ? 1 : 0);
            r.setCreatedAt(now());
            r.setUpdatedAt(now());
            mapper.insert(r);
            log.info("[ImScanChannelService] 新建扫描配置: instance={} enabled={}", instanceId, enabled);
            return r.toDomain();
        }
        UpdateChain.of(ImScanChannelRecord.class)
            .set(ImScanChannelRecord::getEnabled, enabled ? 1 : 0)
            .set(ImScanChannelRecord::getUpdatedAt, now())
            .where(ImScanChannelRecord::getInstanceId).eq(instanceId)
            .update();
        return find(instanceId).orElseThrow(() -> new ValidationException("扫描配置不存在: " + instanceId));
    }

    /**
     * 记录一次扫描的结果。
     *
     * <p>⚠️ <b>失败时不推进 {@code last_scan_at}</b>：那一列是「上次<b>成功</b>扫到的时间」，
     * 也是下次拉取的增量起点 ⇒ 失败也推进 = 那一段时间的消息被永久跳过（平台侧还可能已经过期）。
     *
     * <p>⚠️ {@code last_error} 传 null 时必须真的写成 NULL ⇒ 走 {@link UpdateChain}
     * （{@code update(entity)} 会忽略 null ⇒ 失败原因永远擦不掉）。
     *
     * <p>⚠️ 本版本（4 参/5 参）= <b>整实例失败语义</b>：失败一律不推进游标。
     * 「部分会话失败但游标照常推进」用
     * {@link #markScanResult(String, int, int, String, String, boolean)}。
     *
     * @param errorOrNull 失败原因；null = 本次成功
     */
    public void markScanResult(String instanceId, int chatCount, int newCount, String errorOrNull) {
        markScanResult(instanceId, chatCount, newCount, errorOrNull, null);
    }

    /**
     * 记录一次扫描的结果（可指定成功时的游标）。
     *
     * <h2>为什么要能指定游标（而不是一律 now()）</h2>
     * {@code last_scan_at} 同时是「下次拉取的起点」⇒ 它必须**等于本轮拉取窗口的上界**，
     * 否则会出现一段**永久跳过**的窗口：
     * <pre>
     *   T1 拉取窗口上界（until）      ← 这条区间内的消息已入库
     *   T2 markScanResult 落库时刻     ← 若游标写成 T2，则 (T1, T2) 这段没人拉过
     * </pre>
     * ⇒ 扫描方把「本轮的窗口上界」传进来，游标就与窗口严丝合缝，
     * 下一个窗口从 T1 开始 ⇒ 中间不漏。
     *
     * <p>⚠️ 本版本语义：<b>失败一律不推进游标</b>（= 整实例失败语义）。
     * 「部分会话失败但游标照常推进」请用
     * {@link #markScanResult(String, int, int, String, String, boolean)}。
     *
     * @param successCursorIso 成功时写入 {@code last_scan_at} 的时刻（ISO-8601）；
     *                         null/空白 ⇒ 用当前时刻
     */
    public void markScanResult(String instanceId, int chatCount, int newCount, String errorOrNull,
                                String successCursorIso) {
        boolean failed = errorOrNull != null && !errorOrNull.isBlank();
        markScanResult(instanceId, chatCount, newCount, errorOrNull, successCursorIso, !failed);
    }

    /**
     * 记录一次扫描的结果（<b>显式</b>指定要不要推进游标）。
     *
     * <h2>🔴 为什么「有失败」与「游标不推进」要拆开（2026-11-19）</h2>
     * 旧语义把两者绑在一起：任一会话失败 ⇒ 该实例游标不推进
     * ⇒ <b>一个坏会话把整个实例钉在同一起点</b>（真机：一个拉不了的会话让好会话永远停在老位置，
     * 而这一轮明明已经拉到 16 条新消息）。
     * <p>现在拆成两件事：
     * <ul>
     *   <li>{@code errorOrNull} 只决定「<b>要不要写失败原因</b>」（用户看得见）；</li>
     *   <li>{@code advanceCursor} 决定「<b>要不要推进游标</b>」：
     *       <b>部分会话失败</b> ⇒ 照常推进；<b>全部会话都失败</b> ⇒ 不推进
     *       （后者才是「凭证坏了 / 平台整体拒绝」的信号）。</li>
     * </ul>
     *
     * @param advanceCursor true ⇒ 把 {@code successCursorIso}（或此刻）写进 {@code last_scan_at}；
     *                      false ⇒ 保持上次成功的值
     */
    public void markScanResult(String instanceId, int chatCount, int newCount, String errorOrNull,
                                String successCursorIso, boolean advanceCursor) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new ValidationException("instanceId 不能为空");
        }
        boolean failed = errorOrNull != null && !errorOrNull.isBlank();
        String cursor = advanceCursor
            ? (successCursorIso == null || successCursorIso.isBlank() ? now() : successCursorIso)
            : null;
        ImScanChannelRecord existing = mapper.selectOneByInstanceId(instanceId);
        if (existing == null) {
            // 没有配置行也要留痕：扫过的实例得能被 UI 看到（「上次扫了 N 个会话」）
            ImScanChannelRecord r = new ImScanChannelRecord();
            r.setInstanceId(instanceId);
            r.setEnabled(1);
            r.setLastChatCount(chatCount);
            r.setLastNewCount(newCount);
            r.setLastError(failed ? errorOrNull : null);
            r.setLastScanAt(cursor);
            r.setCreatedAt(now());
            r.setUpdatedAt(now());
            mapper.insert(r);
            return;
        }
        UpdateChain.of(ImScanChannelRecord.class)
            .set(ImScanChannelRecord::getLastChatCount, chatCount)
            .set(ImScanChannelRecord::getLastNewCount, newCount)
            .set(ImScanChannelRecord::getLastError, failed ? errorOrNull : null)
            // 不推进游标 ⇒ last_scan_at 保持上次成功的值
            .set(ImScanChannelRecord::getLastScanAt, cursor != null ? cursor : existing.getLastScanAt())
            .set(ImScanChannelRecord::getUpdatedAt, now())
            .where(ImScanChannelRecord::getInstanceId).eq(instanceId)
            .update();
        log.debug("[ImScanChannelService] 扫描结果: instance={} chats={} new={} failed={} advance={} cursor={}",
                instanceId, chatCount, newCount, failed, advanceCursor, cursor);
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
