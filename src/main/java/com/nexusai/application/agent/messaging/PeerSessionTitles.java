package com.nexusai.application.agent.messaging;

import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.SessionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 跨会话来源标题的唯一读取点（展示用快照）。
 *
 * <p><b>权威来源</b>：会话记录本身（{@link SessionService#getById(String)} → {@link SessionDto#title()}）。
 * 会话删除/归档时 {@code getById} 抛 {@code NotFoundException}（会话表<b>没有</b>软删除列，
 * 删除即消失）⇒ 本类返回 {@code null}，⛔ 不回落成会话 id / 发送方 name（不伪造）。
 *
 * <p><b>安全边界</b>：标题是他人数据，只用于展示（前端按纯文本渲染）；⛔ 不作为路由 / 授权 /
 * 唯一性判定的依据。解析失败<b>绝不阻断</b>发送、hold、投递、列表查询。
 */
public final class PeerSessionTitles {

    private static final Logger log = LoggerFactory.getLogger(PeerSessionTitles.class);

    /** 展示快照长度上限（超出截断，不拒绝；与信封属性上限同量级）。 */
    public static final int MAX_TITLE = 200;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private PeerSessionTitles() {
    }

    /**
     * 从会话记录解析标题（best-effort）。
     *
     * @param service  会话服务（可 null = 未接线）
     * @param sessionId 会话 id（可 null / 空白）
     * @return 规整后的标题；会话不存在 / 无标题 / 读失败 → null
     */
    public static String resolve(SessionService service, String sessionId) {
        if (service == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            SessionDto session = service.getById(sessionId);
            return normalize(session == null ? null : session.title());
        } catch (RuntimeException e) {
            log.debug("peer source_title 解析失败（保持 null，不阻断）session={} err={}",
                sessionId, e.toString());
            return null;
        }
    }

    /**
     * 标题规整：去控制字符、trim；空白 → null；超长截断。
     *
     * <p>⛔ 不做 HTML/属性转义 —— 转义属于渲染层（信封属性由
     * {@link PeerEnvelope#renderModelPayload} 负责，前端按纯文本渲染）。
     *
     * @param raw 原始标题
     * @return 规整后的展示文本；null / 空白 → null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = CONTROL.matcher(raw).replaceAll(" ").trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_TITLE ? trimmed.substring(0, MAX_TITLE) : trimmed;
    }
}
