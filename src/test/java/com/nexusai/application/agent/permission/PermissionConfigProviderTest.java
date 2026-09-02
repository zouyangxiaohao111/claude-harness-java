package com.nexusai.application.agent.permission;

import com.nexusai.repository.permissions_config.entity.PermissionsConfigRecord;
import com.nexusai.repository.permissions_config.mapper.PermissionsConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PermissionConfigProvider} 测试 · 对齐 CC Statsig org 门
 * {@code 'tengu_disable_bypass_permissions_mode'}（permissionSetup.ts:701 / :934 / :1374）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：方案 A 把 CC 无 infra 的 Statsig 门
 * 落为数据库单行开关。钉死三件事：
 * <ol>
 *   <li><b>读 DB</b>——{@code disable_bypass_permissions==1} 才禁用（CC gate true 语义）。</li>
 *   <li><b>缓存</b>——{@code isBypassPermissionsDisabled()} 读缓存不查 DB（启动读一次语义，
 *       只有 refresh() 才重查 DB）。</li>
 *   <li><b>refresh</b>——重读 DB 刷新缓存（登录重读语义），值变化即时生效。</li>
 * </ol>
 */
class PermissionConfigProviderTest {

    private static PermissionsConfigRecord configRecord(Integer disable) {
        PermissionsConfigRecord r = new PermissionsConfigRecord();
        r.setId(1);
        r.setDisableBypassPermissions(disable);
        return r;
    }

    @Test
    @DisplayName("DB disable_bypass_permissions==1 → 禁用（CC gate true）")
    void refresh_readsDisabledTrueFromDb() {
        PermissionsConfigMapper mapper = mock(PermissionsConfigMapper.class);
        when(mapper.selectOneById(1)).thenReturn(configRecord(1));
        PermissionConfigProvider provider = new PermissionConfigProvider(mapper);

        provider.refresh();

        assertThat(provider.isBypassPermissionsDisabled()).isTrue();
    }

    @Test
    @DisplayName("DB disable_bypass_permissions==0 → 不禁用（CC gate false，默认）")
    void refresh_readsDisabledFalseFromDb() {
        PermissionsConfigMapper mapper = mock(PermissionsConfigMapper.class);
        when(mapper.selectOneById(1)).thenReturn(configRecord(0));
        PermissionConfigProvider provider = new PermissionConfigProvider(mapper);

        provider.refresh();

        assertThat(provider.isBypassPermissionsDisabled()).isFalse();
    }

    @Test
    @DisplayName("mapper 未注入（非 Spring/无 DB）→ 回退 false（对齐 CC 生产恒 false）")
    void refresh_withNullMapper_fallsBackFalse() {
        PermissionConfigProvider provider = new PermissionConfigProvider();

        provider.refresh();

        assertThat(provider.isBypassPermissionsDisabled()).isFalse();
    }

    @Test
    @DisplayName("isBypassPermissionsDisabled 读缓存不查 DB（启动读一次：仅 refresh 触 DB）")
    void isDisabled_readsCacheNotDb() {
        PermissionsConfigMapper mapper = mock(PermissionsConfigMapper.class);
        when(mapper.selectOneById(1)).thenReturn(configRecord(1));
        PermissionConfigProvider provider = new PermissionConfigProvider(mapper);

        provider.refresh();
        // 连续读多次：都不应再触 DB
        assertThat(provider.isBypassPermissionsDisabled()).isTrue();
        assertThat(provider.isBypassPermissionsDisabled()).isTrue();
        assertThat(provider.isBypassPermissionsDisabled()).isTrue();

        verify(mapper, times(1)).selectOneById(1);
    }

    @Test
    @DisplayName("refresh 重读 DB：开关值变化即时生效（登录重读语义）")
    void refresh_rereadsDb_reflectsChange() {
        PermissionsConfigMapper mapper = mock(PermissionsConfigMapper.class);
        when(mapper.selectOneById(1)).thenReturn(configRecord(0));
        PermissionConfigProvider provider = new PermissionConfigProvider(mapper);

        provider.refresh();
        assertThat(provider.isBypassPermissionsDisabled()).isFalse();

        when(mapper.selectOneById(1)).thenReturn(configRecord(1));
        provider.refresh();
        assertThat(provider.isBypassPermissionsDisabled()).isTrue();
    }

    @Test
    @DisplayName("setDisableBypassPermissions(true) 写库（update 已有行）+ 刷新缓存")
    void setDisable_writesDbAndRefreshesCache() {
        PermissionsConfigMapper mapper = mock(PermissionsConfigMapper.class);
        when(mapper.selectOneById(1)).thenReturn(configRecord(0));
        PermissionConfigProvider provider = new PermissionConfigProvider(mapper);
        provider.refresh();

        provider.setDisableBypassPermissions(true);

        assertThat(provider.isBypassPermissionsDisabled()).isTrue();
        verify(mapper).update(org.mockito.ArgumentMatchers.any(PermissionsConfigRecord.class));
    }

    @Test
    @DisplayName("setDisableBypassPermissions 无 DB 行 → insert 新行（首次写）")
    void setDisable_insertsWhenRowMissing() {
        PermissionsConfigMapper mapper = mock(PermissionsConfigMapper.class);
        when(mapper.selectOneById(1)).thenReturn(null);
        PermissionConfigProvider provider = new PermissionConfigProvider(mapper);

        provider.setDisableBypassPermissions(true);

        assertThat(provider.isBypassPermissionsDisabled()).isTrue();
        verify(mapper).insert(org.mockito.ArgumentMatchers.any(PermissionsConfigRecord.class));
    }

    @Test
    @DisplayName("setDisableBypassPermissions 无 mapper → 仅内存缓存更新，不写库")
    void setDisable_nullMapper_inMemoryOnly() {
        PermissionConfigProvider provider = new PermissionConfigProvider();
        // 无 mapper，写库路径应短路，仅更新内存缓存
        provider.setDisableBypassPermissions(true);
        assertThat(provider.isBypassPermissionsDisabled()).isTrue();
    }
}
