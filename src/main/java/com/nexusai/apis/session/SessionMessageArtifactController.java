package com.nexusai.apis.session;

import com.nexusai.application.session.SessionArtifactService;
import com.nexusai.model.session.dto.SessionArtifactDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 某一轮的产物/来源 · {@code GET /api/v1/sessions/{sessionId}/messages/{messageId}/artifacts}。
 *
 * <p><b>WHY 单独一个类</b>：{@link SessionArtifactController} 的类级前缀是
 * {@code /api/v1/sessions/{sessionId}/artifacts}，而 Spring 的类级 + 方法级路径是<b>拼接</b>的
 * （不是覆盖）⇒ 在该类里写绝对路径会拼成
 * {@code /api/v1/sessions/{sid}/artifacts/api/v1/sessions/...}。所以这里不挂类级前缀，
 * 方法上直接写完整路径（与 {@code KnowledgeController} 同款做法）。
 *
 * <p><b>两种口径，一张表</b>（2026-11-05 用户裁定）：
 * <ul>
 *   <li><b>会话级</b>（{@code GET /sessions/{sid}/artifacts}）：右侧栏看<b>总量</b>；</li>
 *   <li><b>轮次级</b>（本端点）：对话流里每轮气泡下只看<b>本轮</b>产出的东西。</li>
 * </ul>
 * ⇒ 不另存一份数据，只换过滤条件。
 *
 * <p><b>空态</b>：无产物 → 200 + 两个空数组（空列表是合法状态，不用 404）。
 */
@RestController
public class SessionMessageArtifactController {

    /** 归属未知的历史行用的占位路径值（前端传它/留空 ⇒ 取历史行）。 */
    private static final String UNKNOWN = "_unknown";

    @Autowired private SessionArtifactService sessionArtifactService;

    /**
     * 某一轮的产物/来源。
     *
     * @param sessionId 会话 id
     * @param messageId 用户消息 id；{@code _unknown} ⇒ 归属未知的历史行
     * @return 两个桶（产物 / 来源）
     */
    @GetMapping("/api/v1/sessions/{sessionId}/messages/{messageId}/artifacts")
    public ArtifactsResponse listByMessage(@PathVariable String sessionId,
                                           @PathVariable String messageId) {
        String mid = (messageId == null || messageId.isBlank() || UNKNOWN.equals(messageId))
                ? null : messageId;
        SessionArtifactService.ArtifactBuckets buckets =
                sessionArtifactService.listByMessage(sessionId, mid);
        return new ArtifactsResponse(buckets.deliverables(), buckets.sources());
    }

    /**
     * 出站形状（与会话级端点同构，前端两处可复用同一渲染）。
     *
     * @param deliverables 交付物
     * @param sources      来源链接
     */
    public record ArtifactsResponse(List<SessionArtifactDto> deliverables,
                                    List<SessionArtifactDto> sources) {
    }
}
