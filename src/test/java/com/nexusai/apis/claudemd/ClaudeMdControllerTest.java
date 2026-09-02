package com.nexusai.apis.claudemd;

import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [FR-7 · OPD-CM5-F-09 / IMP-F1-7] {@link ClaudeMdController} POST /api/v1/claude-md/include-approval
 * 意图测试 · 前端审批对话框通道。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：审批态 CC 真源为 {@code config.hasClaudeMdExternalIncludesApproved}
 * （config.ts:115），由 {@code getMemoryFiles} 消费：{@code includeExternal =
 * forceIncludeExternal || config.hasClaudeMdExternalIncludesApproved || false}（claudemd.ts:798-801）。
 * Java 无 getCurrentProjectConfig → 本控制器是唯一生产注入点（F1-7 缺口）。测试锁定端点语义：
 * <ol>
 *   <li><b>置位必须落到引擎 Supplier</b>——若 200 但未注册 {@code () -> approved} 进引擎
 *       （ClaudemdEngine:286），前端对话框显示成功但外部 @include 永不加载 = 静默 no-op；
 *       断言注册的 Supplier.get() 返回提交值。</li>
 *   <li><b>审批翻转后必须失效 memoize 缓存</b>——getMemoryFiles memoize keyed on forceIncludeExternal
 *       （ClaudemdEngine:382-384），置位后不失效则 {@code getMemoryFiles(false)} 仍返回旧列表；
 *       对齐 CC clearMemoryFileCaches「settings sync 纯正确性失效」（claudemd.ts:1110-1122）。</li>
 *   <li><b>approved 缺失 → 400</b>——审批态二值契约，拒绝隐式缺省（误传字段名不会静默置 false）。</li>
 *   <li><b>引擎未接线 → 500 fail loud</b>——审批不落地 = 功能无效，无静默降级。</li>
 * </ol>
 *
 * <p><b>WarningShown 对齐（2026-08-23 补齐）</b>：CC Dialog onDone 批准/拒绝**均**置
 * {@code hasClaudeMdExternalIncludesWarningShown=true}（config.ts:123-131）——拒绝后
 * {@code shouldShowClaudeMdExternalIncludesWarning} 返回 false（不再弹窗）。include-approval
 * 置位 WarningShown=true + 注册 Supplier。
 *
 * <p><b>GET /include-status（2026-08-23 新增）</b>：前端启动/加载上下文主动 GET 查询「CLAUDE.md
 * 外部 @import 是否待审批并弹窗」（用户拍板仅 GET，不用 STOMP 推送）。needsApproval=引擎
 * {@code shouldShowClaudeMdExternalIncludesWarning()}；files=引擎
 * {@code getExternalClaudeMdIncludes(getMemoryFiles(true))}（forceIncludeExternal=true 探测，
 * 不受审批门控；与 shouldShow 内部调用同参共享 memoize 缓存）。
 */
@DisplayName("[FR-7] ClaudeMdController /include-approval + GET /include-status")
class ClaudeMdControllerTest {

    private ClaudeMdController controller;
    private ClaudemdEngine engine;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ClaudeMdController();
        engine = mock(ClaudemdEngine.class);
        ReflectionTestUtils.setField(controller, "claudemdEngine", engine);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 接受 → 200 回显 true + 注册 Supplier 返回 true + WarningShown=true 置位 + 失效 memoize 缓存。 */
    @Test
    void includeApproval_approvedTrue_registersTrueAndClearsCache() throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON).content("{\"approved\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));

        ArgumentCaptor<Supplier<Boolean>> supplier = ArgumentCaptor.forClass(Supplier.class);
        verify(engine).setHasClaudeMdExternalIncludesApproved(supplier.capture());
        assertNotNull(supplier.getValue(), "必须把审批态 Supplier 注册进引擎（否则外部 @include 永不加载）");
        assertTrue(supplier.getValue().get(), "approved=true 时注册 Supplier 必须返回 true");

        // CC Dialog onDone 批准/拒绝均置 WarningShown=true（config.ts:123-131）——置位后不再弹窗
        ArgumentCaptor<Supplier<Boolean>> warningShownSupplier = ArgumentCaptor.forClass(Supplier.class);
        verify(engine).setHasClaudeMdExternalIncludesWarningShown(warningShownSupplier.capture());
        assertNotNull(warningShownSupplier.getValue(), "必须把 WarningShown Supplier 注册进引擎");
        assertTrue(warningShownSupplier.getValue().get(), "审批后 WarningShown 必须为 true（不再弹窗）");

        verify(engine).clearMemoryFileCaches();
    }

    /** 拒绝 → 200 回显 false + 注册 Supplier 返回 false + WarningShown=true 置位 + 失效 memoize 缓存。 */
    @Test
    void includeApproval_approvedFalse_registersFalseAndClearsCache() throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON).content("{\"approved\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false));

        ArgumentCaptor<Supplier<Boolean>> supplier = ArgumentCaptor.forClass(Supplier.class);
        verify(engine).setHasClaudeMdExternalIncludesApproved(supplier.capture());
        assertNotNull(supplier.getValue());
        assertFalse(supplier.getValue().get(), "approved=false 时注册 Supplier 必须返回 false");

        // CC Dialog onDone 拒绝也置 WarningShown=true（config.ts:123-131）——拒绝后不再弹窗
        ArgumentCaptor<Supplier<Boolean>> warningShownSupplier = ArgumentCaptor.forClass(Supplier.class);
        verify(engine).setHasClaudeMdExternalIncludesWarningShown(warningShownSupplier.capture());
        assertNotNull(warningShownSupplier.getValue(), "拒绝后也必须注册 WarningShown Supplier（对齐 CC onDone）");
        assertTrue(warningShownSupplier.getValue().get(), "拒绝后 WarningShown 必须为 true（不再弹窗）");

        verify(engine).clearMemoryFileCaches();
    }

    /** approved 字段缺失 → 400（二值契约，拒绝隐式缺省；误传字段名不静默置 false）。 */
    @Test
    void includeApproval_missingApproved_rejected400() throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(engine, never()).setHasClaudeMdExternalIncludesApproved(any());
    }

    /** 请求体缺失 → 400（@RequestBody 必填）。 */
    @Test
    void includeApproval_missingBody_rejected400() throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(engine, never()).setHasClaudeMdExternalIncludesApproved(any());
    }

    /** 引擎未接线 → 500 fail loud（无静默降级，对齐 ExtractMemoriesController.resolveMemoryStorage）。 */
    @Test
    void includeApproval_engineNotWired_failLoud500() throws Exception {
        ReflectionTestUtils.setField(controller, "claudemdEngine", null);
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON).content("{\"approved\": true}"))
                .andExpect(status().isInternalServerError());
    }

    /** 无外部 include → needsApproval=false + files 空数组；verify 探测链被调（shouldShow + getExternalClaudeMdIncludes）。 */
    @Test
    void includeStatus_noExternalIncludes() throws Exception {
        when(engine.shouldShowClaudeMdExternalIncludesWarning()).thenReturn(false);
        when(engine.getMemoryFiles(true)).thenReturn(List.of());
        when(engine.getExternalClaudeMdIncludes(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/claude-md/include-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsApproval").value(false))
                .andExpect(jsonPath("$.files").isArray())
                .andExpect(jsonPath("$.files").isEmpty());

        verify(engine).shouldShowClaudeMdExternalIncludesWarning();
        verify(engine).getExternalClaudeMdIncludes(any());
    }

    /** 存在外部 include 且未审批 → needsApproval=true + files 列出外部文件绝对路径（不受审批门控探测）。 */
    @Test
    void includeStatus_hasExternalUnapproved() throws Exception {
        when(engine.shouldShowClaudeMdExternalIncludesWarning()).thenReturn(true);
        when(engine.getMemoryFiles(true)).thenReturn(List.of());
        when(engine.getExternalClaudeMdIncludes(any()))
                .thenReturn(List.of(new ClaudemdEngine.ExternalClaudeMdInclude("D:/external/team.md", "CLAUDE.md")));

        mockMvc.perform(get("/api/v1/claude-md/include-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsApproval").value(true))
                .andExpect(jsonPath("$.files[0]").value("D:/external/team.md"));
    }

    /** 先 POST include-approval 批准 → 再 GET include-status → needsApproval=false（审批态落库后前端不再弹窗）。 */
    @Test
    void includeStatus_afterApproval() throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON).content("{\"approved\": true}"))
                .andExpect(status().isOk());

        when(engine.shouldShowClaudeMdExternalIncludesWarning()).thenReturn(false);
        when(engine.getMemoryFiles(true)).thenReturn(List.of());
        when(engine.getExternalClaudeMdIncludes(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/claude-md/include-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsApproval").value(false));
    }

    /** 引擎未接线 → GET /include-status → 500 fail loud（与 include-approval 一致）。 */
    @Test
    void includeStatus_engineNotWired_failLoud500() throws Exception {
        ReflectionTestUtils.setField(controller, "claudemdEngine", null);
        mockMvc.perform(get("/api/v1/claude-md/include-status"))
                .andExpect(status().isInternalServerError());
    }
}
