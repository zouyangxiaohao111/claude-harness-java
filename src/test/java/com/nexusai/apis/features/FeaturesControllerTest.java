package com.nexusai.apis.features;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [IMP-BACK-2] FeaturesController GET /api/v1/features 端点测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 触发层
 * {@code useAwaySummary.ts:53-55} 用 {@code feature('AWAY_SUMMARY')} + GB flag
 * {@code 'tengu_sedge_lantern'}（默认 false）双门控，两者都开才允许 blur 5min 触发离开摘要。
 * Web 前端 useAwaySummary.ts 无任何门控、blur 无条件触发（CM-E3 探查 R3）——OPD-CM5-F-15
 * 拍板后端新增 GET /api/v1/features 把门控值暴露给前端，<b>默认 false 阻塞 away-summary 门控</b>。
 * 本测试锁定端点语义:
 * <ol>
 *   <li><b>默认双 false</b>——前端读到时必须不得触发 away-summary（若默认返回 true，
 *       前端绕过开关始终触发，违背 CC 双门控默认关）。</li>
 *   <li><b>可开关</b>——{@code nexusai.feature.away-summary=true} / {@code nexusai.feature.tengu-sedge-lantern=true}
 *       分别使对应字段置 true（产品按需开启，对齐 CC feature()/GB 可配置）。</li>
 * </ol>
 */
@DisplayName("[IMP-BACK-2] FeaturesController GET /api/v1/features")
class FeaturesControllerTest {

    /** 构造端点（默认字段 false，@Value 注入经 ReflectionTestUtils 显式覆写以模拟配置）。 */
    private MockMvc mockMvc(boolean awaySummary, boolean tenguSedgeLantern) {
        FeaturesController controller = new FeaturesController();
        ReflectionTestUtils.setField(controller, "awaySummary", awaySummary);
        ReflectionTestUtils.setField(controller, "tenguSedgeLantern", tenguSedgeLantern);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        // 清理 settings override 静态标志，防测试间串状态（TaskSystemConfig.clearForTest 归 null）
        TaskSystemConfig.clearForTest();
    }

    @Test
    @DisplayName("settings 开关开启后 features.agentSwarms 实时为 true（不缓存启动值）")
    void agentSwarms_reflectsRuntimeOverride() throws Exception {
        // WHY: [team-backend-features-agentSwarms-cache] agentSwarms 是运行时可变门控（settings
        //   开关 → setAgentSwarmsSettingsOverride），若缓存为 final 字段（启动初始化一次）则 settings
        //   开关后 features 仍返回启动值 false → 前端 TeamPanel 永不显示。必须方法内实时读。
        //   变异点：final 缓存字段 → 此测试 fail（override=true 后仍 false）。
        TaskSystemConfig.setAgentSwarmsSettingsOverride(true);  // 模拟前端设置页开启开关

        mockMvc(false, false)
            .perform(get("/api/v1/features"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentSwarms").value(true));
    }

    @Test
    @DisplayName("默认（未配置）→ 200 + awaySummary=false + tenguSedgeLantern=false（阻塞 away-summary 门控）")
    void defaultFlags_areFalse() throws Exception {
        // WHY: 对齐 CC useAwaySummary.ts:47-51 'tengu_sedge_lantern' 3P 默认 false + feature('AWAY_SUMMARY')
        // 默认关 —— 前端读默认双 false 时不得触发离开摘要（OPD-CM5-F-15「默认 false；阻塞 away-summary 门控」）。
        // 若默认误返回 true，前端 blur 5min 会无条件触发，绕过 CC 双门控。
        // agentSwarms 为 final 字段（构造时读 TaskSystemConfig.isAgentSwarmsEnabled 静态门控），
        // 断言键存在（透出给前端 TeamPanel 渲染门控）；值由 TaskSystemConfig 门控决定（默认 false 未 opt-in）。
        mockMvc(false, false)
            .perform(get("/api/v1/features"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.awaySummary").value(false))
            .andExpect(jsonPath("$.tenguSedgeLantern").value(false))
            .andExpect(jsonPath("$.agentSwarms").exists());
    }

    @Test
    @DisplayName("away-summary=true → 200 + awaySummary=true + tenguSedgeLantern=false（可单独开启 AWAY_SUMMARY）")
    void awaySummaryEnabled_flagTrue() throws Exception {
        // WHY: 对齐 CC feature('AWAY_SUMMARY') 独立可配置（useAwaySummary.ts:54）——产品开启
        // AWAY_SUMMARY 后 awaySummary 必须反映 true，否则前端读不到开启态、away-summary 永不触发。
        mockMvc(true, false)
            .perform(get("/api/v1/features"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.awaySummary").value(true))
            .andExpect(jsonPath("$.tenguSedgeLantern").value(false));
    }

    @Test
    @DisplayName("tengu-sedge-lantern=true → 200 + awaySummary=false + tenguSedgeLantern=true（可单独开启 GB flag）")
    void tenguSedgeLanternEnabled_flagTrue() throws Exception {
        // WHY: 对齐 CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_sedge_lantern', false)
        // （useAwaySummary.ts:48-50）——GB flag 独立可配置，开启后 tenguSedgeLantern 必须反映 true。
        mockMvc(false, true)
            .perform(get("/api/v1/features"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.awaySummary").value(false))
            .andExpect(jsonPath("$.tenguSedgeLantern").value(true));
    }

    @Test
    @DisplayName("双 true → 200 + 双 true（前端可触发 away-summary，对齐 CC 双门控 AND）")
    void bothEnabled_bothTrue() throws Exception {
        // WHY: CC useAwaySummary.ts:53-55 双门控 AND（feature && gbEnabled）——仅双 true 前端才可
        // 触发离开摘要；端点必须如实返回双 true，供前端做 AND 判定。
        mockMvc(true, true)
            .perform(get("/api/v1/features"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.awaySummary").value(true))
            .andExpect(jsonPath("$.tenguSedgeLantern").value(true));
    }
}
