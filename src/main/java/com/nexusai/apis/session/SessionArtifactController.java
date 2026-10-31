package com.nexusai.apis.session;

import com.nexusai.application.session.SessionArtifactService;
import com.nexusai.model.session.dto.SessionArtifactDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话「产物 / 来源」只读 REST · GET /api/v1/sessions/{sessionId}/artifacts。
 *
 * <p><b>语义</b>：读 {@code session_artifact} 表（V205，由 {@link SessionArtifactService}
 * 轮末异步抽取写入），按 kind 分两桶返回。前端定时轮询本端点即可。
 *
 * <p><b>⛔ 本端点不触发抽取</b>：抽取只由 ChatService 轮末触发 —— 否则前端每次轮询都会打一次
 * 模型（轮询频率 × 会话数 = 费用放大器）。
 *
 * <p><b>空态</b>：无产物/来源 → 200 + 两个空数组（空列表是合法状态，不用 404，
 * 与 TodoStatusController 同款约定）。会话 id 不存在同样返回空数组（只读投影，不暴露存在性）。
 *
 * <p>路由与既有 /api/v1/sessions/** 无冲突（ChatController /{sessionId}、TodoStatusController /todos、
 * SessionFilesController /files）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/artifacts")
public class SessionArtifactController {

    @Autowired private SessionArtifactService sessionArtifactService;

    /**
     * 会话产物/来源列表 · GET /api/v1/sessions/{sessionId}/artifacts。
     *
     * @param sessionId 会话 id（sess-xxx）
     * @return 200 {deliverables: SessionArtifactDto[], sources: SessionArtifactDto[]}
     */
    @GetMapping
    public ArtifactsResponse list(@PathVariable String sessionId) {
        SessionArtifactService.ArtifactBuckets buckets = sessionArtifactService.list(sessionId);
        return new ArtifactsResponse(buckets.deliverables(), buckets.sources());
    }

    /**
     * 产物内容预览 · GET /api/v1/sessions/{sessionId}/artifacts/{artifactId}/content。
     *
     * <p><b>WHY</b>：产物 {@code local_path} 是<b>归档副本</b>的绝对路径
     * （{@code {configHome}/artifacts/{sessionId}/...}），不在任何项目根内 ⇒ 浏览器端
     * 走项目文件通道必然报「文件路径非法」。产物只能走本端点。
     *
     * <p><b>错误语义</b>：不属于该会话 / 产物行不存在 / 文件已删除 → 404；
     * 路径越出本会话归档目录 → 400（GlobalExceptionHandler 统一出 RFC 7807 体）。
     *
     * @param sessionId  会话 id（sess-xxx）
     * @param artifactId 产物行 id（art-xxx）
     * @return 200 {content, mime, size, truncated, binary}
     */
    @GetMapping("/{artifactId}/content")
    public SessionArtifactService.ArtifactContent content(@PathVariable String sessionId,
                                                          @PathVariable String artifactId) {
        return sessionArtifactService.readContent(sessionId, artifactId);
    }

    /** 出站形状（两桶分列，前端按桶渲染）。 */
    public record ArtifactsResponse(List<SessionArtifactDto> deliverables, List<SessionArtifactDto> sources) {}
}
