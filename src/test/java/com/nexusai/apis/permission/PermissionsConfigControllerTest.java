package com.nexusai.apis.permission;

import com.nexusai.application.agent.permission.PermissionConfigProvider;
import com.nexusai.repository.permissions_config.entity.PermissionsConfigRecord;
import com.nexusai.repository.permissions_config.mapper.PermissionsConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PermissionsConfigController} 测试 · 对齐 CC Statsig org 门
 * {@code 'tengu_disable_bypass_permissions_mode'}（permissionSetup.ts:701 / :934 / :1374）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：方案 A 数据库开关的 REST 管理面。
 * 钉死端点契约：
 * <ol>
 *   <li><b>GET</b>——返回当前缓存开关状态（读缓存，不查 DB）。</li>
 *   <li><b>PUT /disable</b>——写库 + 刷新缓存（禁用门关闭），返回 {@code {disableBypassPermissions: true}}。</li>
 *   <li><b>PUT /enable</b>——写库 + 刷新缓存（恢复），返回 {@code {disableBypassPermissions: false}}。</li>
 * </ol>
 *
 * <p>测试用真实 {@link PermissionConfigProvider} + Mockito 假 {@link PermissionsConfigMapper}，
 * 覆盖写库（update/insert）路径；不依赖真实 SQLite。
 */
class PermissionsConfigControllerTest {

    private PermissionsConfigMapper mapper;
    private PermissionConfigProvider provider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mapper = mock(PermissionsConfigMapper.class);
        when(mapper.selectOneById(1)).thenReturn(configRecord(0));
        provider = new PermissionConfigProvider(mapper);

        PermissionsConfigController controller = new PermissionsConfigController();
        ReflectionTestUtils.setField(controller, "permissionConfigProvider", provider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static PermissionsConfigRecord configRecord(Integer disable) {
        PermissionsConfigRecord r = new PermissionsConfigRecord();
        r.setId(1);
        r.setDisableBypassPermissions(disable);
        return r;
    }

    @Test
    @DisplayName("GET → 200 + 当前开关状态（默认 false）")
    void get_returnsCurrentState() throws Exception {
        mockMvc.perform(get("/api/v1/permissions/bypass-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disableBypassPermissions").value(false));
    }

    @Test
    @DisplayName("缓存置 true 后 GET → 返回 true（读缓存）")
    void get_afterDisable_returnsTrue() throws Exception {
        provider.setDisableBypassPermissions(true);

        mockMvc.perform(get("/api/v1/permissions/bypass-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disableBypassPermissions").value(true));
    }

    @Test
    @DisplayName("PUT /disable → 写库 + refresh，返回 {disableBypassPermissions: true}")
    void putDisable_writesDbAndRefreshes() throws Exception {
        mockMvc.perform(put("/api/v1/permissions/bypass-config/disable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disableBypassPermissions").value(true));

        verify(mapper).update(any(PermissionsConfigRecord.class));
        // 写库后缓存同步为 true
        mockMvc.perform(get("/api/v1/permissions/bypass-config"))
            .andExpect(jsonPath("$.disableBypassPermissions").value(true));
    }

    @Test
    @DisplayName("PUT /enable → 写库 + refresh，返回 {disableBypassPermissions: false}")
    void putEnable_writesDbAndRefreshes() throws Exception {
        // setup：把当前缓存置为 disable（直接写 AtomicBoolean，不触发写库，避免污染 update 计数）
        ReflectionTestUtils.setField(provider, "disableBypassPermissions", new java.util.concurrent.atomic.AtomicBoolean(true));

        mockMvc.perform(put("/api/v1/permissions/bypass-config/enable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disableBypassPermissions").value(false));

        verify(mapper).update(any(PermissionsConfigRecord.class));
    }
}
