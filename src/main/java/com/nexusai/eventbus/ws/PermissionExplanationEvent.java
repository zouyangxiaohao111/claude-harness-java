package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.permission.explainer.PermissionExplanation;
import com.nexusai.application.agent.permission.explainer.RiskLevel;

/**
 * 权限解释事件 · 服务端 → 前端 STOMP 推送。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/permission-explanations}
 *
 * <h2>触发流程</h2>
 * <ol>
 *   <li>前端收 {@link MessagePermissionRequestEvent} 弹窗，用户点"解释" → SEND
 *       {@link PermissionExplainRequestEvent} 到 {@code /app/sessions/{sessionId}/permission-explain}</li>
 *   <li>{@link com.nexusai.application.agent.permission.WebSocketPermissionPrompter#explainAndSend}
 *       在 RACERS daemon 线程调 {@code explainPermissionRequest}（对齐 CC 异步 promise
 *       generatePermissionExplanation，permissionExplainer.ts:147-250）</li>
 *   <li>生成成功 → 本事件推四字段（{@code riskLevel/explanation/reasoning/risk}，
 *       CC PermissionExplanation.tsx:173-240 展示）；失败 → {@link #unavailable} 事件
 *       （{@code available=false}，CC「Explanation unavailable」:161-166）</li>
 * </ol>
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@code type} = {@code "permission.explanation"}（继承自 {@link StreamEvent} 协议）</li>
 *   <li>{@code requestId} — 关联 {@link PermissionExplainRequestEvent#getRequestId()}，
 *       供弹窗内联展示定位</li>
 *   <li>{@code available} — 解释是否可用；false = CC「Explanation unavailable」语义</li>
 *   <li>{@code riskLevel} — 风险等级（LOW/MEDIUM/HIGH，对齐 CC {@code RiskLevel}
 *       permissionExplainer.ts:14 字符串 union）· 仅 {@code available=true} 时非空</li>
 *   <li>{@code explanation} / {@code reasoning} / {@code risk} — 四字段中的其余三字段
 *       （对齐 CC PermissionExplanation，permissionExplainer.ts:28-33）· 仅
 *       {@code available=true} 时非空</li>
 * </ul>
 *
 * @see PermissionExplainRequestEvent
 * @see com.nexusai.application.agent.permission.WebSocketPermissionPrompter
 * @see com.nexusai.apis.permission.PermissionController
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionExplanationEvent extends StreamEvent {

    /** 唯一请求 ID · 关联 {@link PermissionExplainRequestEvent#getRequestId()}。 */
    private final String requestId;

    /** 解释是否可用 · false = CC「Explanation unavailable」（PermissionExplanation.tsx:161-166）。 */
    private final boolean available;

    /** 风险等级 · CC original: riskLevel (permissionExplainer.ts:29)；仅 available=true 时非空。 */
    private final RiskLevel riskLevel;

    /** 一句话解释 · CC original: explanation (permissionExplainer.ts:30)；仅 available=true 时非空。 */
    private final String explanation;

    /** 推理过程 · CC original: reasoning (permissionExplainer.ts:31)；仅 available=true 时非空。 */
    private final String reasoning;

    /** 风险描述 · CC original: risk (permissionExplainer.ts:32)；仅 available=true 时非空。 */
    private final String risk;

    private PermissionExplanationEvent(String sessionId, String requestId, boolean available,
                                       RiskLevel riskLevel, String explanation,
                                       String reasoning, String risk) {
        super("permission.explanation", sessionId, null);
        this.requestId = requestId;
        this.available = available;
        this.riskLevel = riskLevel;
        this.explanation = explanation;
        this.reasoning = reasoning;
        this.risk = risk;
    }

    /**
     * 静态工厂 · 生成成功（四字段全非空，对齐 CC PermissionExplanation zod 严格校验
     * permissionExplainer.ts:77-84）。
     *
     * @param sessionId   会话 ID
     * @param requestId   关联 {@link PermissionExplainRequestEvent#getRequestId()}
     * @param explanation 权限解释（四字段全必填，null → {@link #unavailable}）
     * @return 携带四字段的 {@code available=true} 事件
     */
    public static PermissionExplanationEvent of(String sessionId, String requestId,
                                                PermissionExplanation explanation) {
        if (explanation == null) {
            return unavailable(sessionId, requestId);
        }
        return new PermissionExplanationEvent(
            sessionId, requestId, true,
            explanation.riskLevel(), explanation.explanation(),
            explanation.reasoning(), explanation.risk());
    }

    /**
     * 静态工厂 · 解释不可用（explainer 未注入 / 门控关闭 / 生成失败 → null，CC 无降级）。
     *
     * <p>前端收到 {@code available=false} 渲染「Explanation unavailable」
     * （对齐 CC PermissionExplanation.tsx:161-166 {@code if (!explanation)}）。
     *
     * @param sessionId 会话 ID
     * @param requestId 关联 {@link PermissionExplainRequestEvent#getRequestId()}
     * @return {@code available=false} 事件（四字段全 null，NON_NULL 序列化省略）
     */
    public static PermissionExplanationEvent unavailable(String sessionId, String requestId) {
        return new PermissionExplanationEvent(sessionId, requestId, false,
            null, null, null, null);
    }

    public String getRequestId() { return requestId; }
    public boolean isAvailable() { return available; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getExplanation() { return explanation; }
    public String getReasoning() { return reasoning; }
    public String getRisk() { return risk; }
}
