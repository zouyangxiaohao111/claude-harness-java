package com.nexusai.apis.features;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端 feature 门控查询端点 · 对齐 CC {@code useAwaySummary.ts}（blur 5min 离开摘要双门控）。
 *
 * <p><b>WHY 存在（OPD-CM5-F-15 拍板 · decisions-log 第 26 批）</b>: CC 触发层
 * {@code useAwaySummary.ts:53-55} 用 {@code feature('AWAY_SUMMARY')}（编译期 feature flag）与
 * {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_sedge_lantern', false)}（GrowthBook flag，
 * 默认 false）双重门控，两者都开才允许 blur 5min 触发离开摘要。Web 前端 {@code useAwaySummary.ts}
 * 无任何门控、blur 无条件触发（CM-E3 探查 R3）——本项目无 bun:bundle 编译宏与 GrowthBook，
 * 故以 Spring 配置项模拟（对齐 CC feature()/GB 默认关闭语义），并提供本 REST 端点把当前门控值
 * 暴露给前端：前端读本端点，双 false（默认）时不得触发离开摘要（阻塞 away-summary 门控）。
 *
 * <p><b>配置项</b>:
 * <ul>
 *   <li>{@code nexusai.feature.away-summary}（默认 {@code false}）· 对齐 CC
 *       {@code feature('AWAY_SUMMARY')}（useAwaySummary.ts:54 / REPL.tsx:1246）。</li>
 *   <li>{@code nexusai.feature.tengu-sedge-lantern}（默认 {@code false}）· 对齐 CC
 *       {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_sedge_lantern', false)}
 *       （useAwaySummary.ts:48-50）。</li>
 * </ul>
 *
 * <p><b>响应契约</b>（camelCase 字段，CC 原名见 {@link FeaturesResponse} 各字段 javadoc）:
 * <pre>{ "awaySummary": false, "tenguSedgeLantern": false }</pre>
 * 前端必须双 true 才可触发 away-summary（与 CC useAwaySummary.ts 双门控 AND 语义一致）。
 *
 * <p><b>鉴权</b>: 与 /api/v1/context、/api/v1/settings 等只读配置端点同级，未纳入
 * {@code BearerTokenAuthFilterConfig} 白名单（不强制 bearer；返回两个低敏布尔，无越权风险）。
 */
@RestController
@RequestMapping("/api/v1/features")
public class FeaturesController {

    private static final Logger log = LoggerFactory.getLogger(FeaturesController.class);

    /**
     * {@code AWAY_SUMMARY} feature flag · 对齐 CC {@code feature('AWAY_SUMMARY')}
     * （Open-ClaudeCode/src/hooks/useAwaySummary.ts:54 + REPL.tsx:1246）。默认 {@code false}。
     */
    @Value("${nexusai.feature.away-summary:false}")
    private boolean awaySummary;

    /**
     * {@code tengu_sedge_lantern} GB flag · 对齐 CC {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_sedge_lantern', false)}
     * （Open-ClaudeCode/src/hooks/useAwaySummary.ts:48-50）。默认 {@code false}。
     */
    @Value("${nexusai.feature.tengu-sedge-lantern:false}")
    private boolean tenguSedgeLantern;

    /**
     * 查询前端 feature 门控 · GET /api/v1/features。
     *
     * <p>返回 AWAY_SUMMARY + tengu_sedge_lantern + agentSwarms 门控当前值（awaySummary/tengu 默认
     * false，agentSwarms 默认 false 未 opt-in）。
     *
     * <p><b>[team-backend-features-agentSwarms-cache] agentSwarms 必须实时读</b>：settings 开关
     * （settings.agentSwarmsEnabled → TaskSystemConfig.setAgentSwarmsSettingsOverride）运行时动态
     * 生效，若缓存为 final 字段（启动时初始化一次）则 settings 开关后仍返回启动值 → 前端 TeamPanel
     * 永不显示。awaySummary/tenguSedgeLantern 为 @Value 配置项（启动固定）可缓存，agentSwarms 不可。
     *
     * @return 当前 feature 门控值
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public FeaturesResponse features() {
        // agentSwarms 运行时可变（settings 开关动态生效）→ 每次请求实时读，勿缓存 final
        boolean agentSwarms = com.nexusai.application.agent.tasks.TaskSystemConfig.isAgentSwarmsEnabled();
        if (log.isDebugEnabled()) {
            log.debug("[FeaturesController] 查询 feature 门控: awaySummary={} tenguSedgeLantern={} agentSwarms={}"
                    + "（对齐 CC useAwaySummary.ts:48-55 双门控 + agentSwarmsEnabled.ts · agentSwarms 实时读）",
                awaySummary, tenguSedgeLantern, agentSwarms);
        }
        return new FeaturesResponse(awaySummary, tenguSedgeLantern, agentSwarms);
    }

    /**
     * GET /api/v1/features 响应 · 前端 feature 门控查询载体。
     *
     * @param awaySummary       {@code AWAY_SUMMARY} feature flag · CC original: feature('AWAY_SUMMARY')
     *                          (Open-ClaudeCode/src/hooks/useAwaySummary.ts:54)
     * @param tenguSedgeLantern {@code tengu_sedge_lantern} GB flag · CC original:
     *                          getFeatureValue_CACHED_MAY_BE_STALE('tengu_sedge_lantern', false)
     *                          (Open-ClaudeCode/src/hooks/useAwaySummary.ts:48-50)
     * @param agentSwarms       {@code agentSwarms} 门控 · CC original: isAgentSwarmsEnabled()
     *                          （agentSwarmsEnabled.ts，TaskSystemConfig.isAgentSwarmsEnabled）
     *                          —— 前端 TeamPanel 渲染门控（默认 false 不显示）
     */
    public record FeaturesResponse(
            @JsonProperty("awaySummary") boolean awaySummary,
            @JsonProperty("tenguSedgeLantern") boolean tenguSedgeLantern,
            @JsonProperty("agentSwarms") boolean agentSwarms) {
    }
}
