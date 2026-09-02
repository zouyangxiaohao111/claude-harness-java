package com.nexusai.domain.settings;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [websearch-ccalign T1 + websearch-resid R-B + websearch-domaincheck] Settings 层 WebSearch 6 字段
 * （websearchEngine/apiKey/proxy/websearchUseSmallModel/websearchBaseUrl/websearchDomainCheckUrl）
 * get/update 映射测试。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>: V37/V38/V39 列入 DB settings 后，WebSearchTool /
 * WebFetchTool 读链依赖 SettingsService.get()/update() 正确透出/合并这 6 个字段（前端可配置）——
 * 本测试锁定：
 * <ol>
 *   <li><b>get() 透出</b>：DB 行的 6 新列经 toDto 原样进入 SettingsDto（record 组件顺序必须
 *       与 toDto 构造一致，否则编译失败/错位）。</li>
 *   <li><b>update() merge</b>：6 字段非 null → 覆盖写回；null → 不覆盖（保留旧值）——
 *       对齐既有 merge 策略（null = 不覆盖）。</li>
 * </ol>
 */
class SettingsServiceTest {

    private static final int SINGLETON_ID = 1;

    private SettingsService newService(SettingsMapper mapper, SettingsRecord dbRow) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(dbRow);
        return service;
    }

    /** 仅 V61 插件双读配置有值、其余全 null 的请求（避免手写 66 参）。 */
    private static SettingsDto v61SettingsDto(Map<String, Boolean> enabledPlugins, Boolean pluginClaudeFallback) {
        return new SettingsDto(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, enabledPlugins, pluginClaudeFallback);
    }

    /** 含 WebSearch 6 字段的 DB 行（autoMemoryEnabled 设值避免 toDto 回落 settings.json 文件读）。 */
    private static SettingsRecord webSearchRow() {
        SettingsRecord s = new SettingsRecord();
        s.setAutoMemoryEnabled(true);
        s.setWebsearchEngine("anysearch");
        s.setApiKey("db-key-123");
        s.setProxy("proxy.db.example:8080");
        s.setWebsearchUseSmallModel(true);
        s.setWebsearchBaseUrl("https://base.example.com");
        s.setWebsearchDomainCheckUrl("https://check.example.com/domain_info");
        return s;
    }

    @Test
    @DisplayName("get()：WebSearch 6 字段（websearchEngine/apiKey/proxy/websearchUseSmallModel/websearchBaseUrl/websearchDomainCheckUrl）原样透出")
    void get_mapsWebSearchFiveFields() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsService service = newService(mapper, webSearchRow());

        SettingsDto dto = service.get();

        assertThat(dto.websearchEngine()).isEqualTo("anysearch");
        assertThat(dto.apiKey()).isEqualTo("db-key-123");
        assertThat(dto.proxy()).isEqualTo("proxy.db.example:8080");
        assertThat(dto.websearchUseSmallModel()).isTrue();
        assertThat(dto.websearchBaseUrl()).as("V38 列 websearch_base_url 必须透出（R-B）").isEqualTo("https://base.example.com");
        assertThat(dto.websearchDomainCheckUrl())
                .as("V39 列 websearch_domain_check_url 必须透出（domaincheck，WebFetchTool.resolveSecurity 消费）")
                .isEqualTo("https://check.example.com/domain_info");
    }

    @Test
    @DisplayName("update()：6 字段非 null → 覆盖写回 DB 行")
    void update_overwritesWebSearchFiveFields() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        // DB 旧值
        SettingsRecord dbRow = new SettingsRecord();
        dbRow.setWebsearchEngine("duckduckgo");
        dbRow.setApiKey("old-key");
        dbRow.setProxy("old.proxy:1080");
        dbRow.setWebsearchUseSmallModel(false);
        dbRow.setWebsearchBaseUrl("https://old.example.com");
        SettingsService service = newService(mapper, dbRow);

        SettingsDto req = new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                "anysearch", "new-key", "new.proxy:8080", true,
                "https://new.example.com",
                "https://new-check.example.com/domain_info",
                null,
                null,
                null,
                // [V52] 压缩配置 12 列未设 → null
                // [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // permissionMode（V44 全局默认，本测试未设 → null）· [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

        service.update(req);

        // 写回行：6 字段覆盖为新值
        assertThat(dbRow.getWebsearchEngine()).isEqualTo("anysearch");
        assertThat(dbRow.getApiKey()).isEqualTo("new-key");
        assertThat(dbRow.getProxy()).isEqualTo("new.proxy:8080");
        assertThat(dbRow.getWebsearchUseSmallModel()).isTrue();
        assertThat(dbRow.getWebsearchBaseUrl()).as("websearchBaseUrl 非 null → 覆盖写回").isEqualTo("https://new.example.com");
        assertThat(dbRow.getWebsearchDomainCheckUrl())
                .as("websearchDomainCheckUrl 非 null → 覆盖写回（V39 列）")
                .isEqualTo("https://new-check.example.com/domain_info");
        verify(mapper).update(dbRow);
    }

    @Test
    @DisplayName("update()：6 字段为 null → 不覆盖（merge 保留旧值）")
    void update_nullWebSearchFieldsKeepOldValues() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = webSearchRow();
        SettingsService service = newService(mapper, dbRow);

        // 请求 6 字段全 null → 不覆盖
        SettingsDto req = new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null,
                null,
                null,
                null,
                // [V52] 压缩配置 12 列未设 → null
                // [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // permissionMode（V44 全局默认，本测试未设 → null）· [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

        service.update(req);

        // 写回行保留 DB 旧值
        assertThat(dbRow.getWebsearchEngine()).isEqualTo("anysearch");
        assertThat(dbRow.getApiKey()).isEqualTo("db-key-123");
        assertThat(dbRow.getProxy()).isEqualTo("proxy.db.example:8080");
        assertThat(dbRow.getWebsearchUseSmallModel()).isTrue();
        assertThat(dbRow.getWebsearchBaseUrl()).as("websearchBaseUrl null → 保留旧值").isEqualTo("https://base.example.com");
        assertThat(dbRow.getWebsearchDomainCheckUrl())
                .as("websearchDomainCheckUrl null → 保留旧值（V39 列）")
                .isEqualTo("https://check.example.com/domain_info");
        verify(mapper).update(dbRow);
    }

    @Test
    @DisplayName("update()：返回 toDto 也透出 6 新字段（写回后返回透出）")
    void update_returnDtoExposesWebSearchFields() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsService service = newService(mapper, webSearchRow());

        SettingsDto req = new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                "duckduckgo", null, null, false, null,
                "https://new-check2.example.com/domain_info",
                null,
                null,
                null,
                // [V52] 压缩配置 12 列未设 → null
                // [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // permissionMode（V44 全局默认，本测试未设 → null）· [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

        SettingsDto dto = service.update(req);

        assertThat(dto.websearchEngine()).isEqualTo("duckduckgo");
        assertThat(dto.apiKey()).isEqualTo("db-key-123");   // null → 保留旧值
        assertThat(dto.proxy()).isEqualTo("proxy.db.example:8080");
        assertThat(dto.websearchUseSmallModel()).isFalse(); // false 非 null → 覆盖
        assertThat(dto.websearchBaseUrl()).as("websearchBaseUrl null → 保留旧值透出").isEqualTo("https://base.example.com");
        assertThat(dto.websearchDomainCheckUrl())
                .as("websearchDomainCheckUrl 非 null → 覆盖透出（V39 列）")
                .isEqualTo("https://new-check2.example.com/domain_info");
        verify(mapper).update(any(SettingsRecord.class));
    }

    // ════════════════════════════════════════════════════════════════════════
    // [agent-swarms-setting V42] get/update 写库后同步 TaskSystemConfig 静态覆盖标志
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("get()：读库后同步 agentSwarmsEnabled 静态覆盖 → isAgentSwarmsEnabled() 放行（设置页加载即生效）")
    void get_syncsOverride_agentSwarmsEnabled() {
        // WHY（规则九）：前端「环境配置」Agent Swarms 开关 → DB 列 V42 → SettingsService.get()
        //   读库后必须 TaskSystemConfig.setAgentSwarmsSettingsOverride(...)，否则设置页加载开关不生效。
        TaskSystemConfig.clearForTest();
        try {
            SettingsRecord s = new SettingsRecord();
            s.setAgentSwarmsEnabled(true);
            SettingsMapper mapper = mock(SettingsMapper.class);
            SettingsService service = newService(mapper, s);

            service.get();

            assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).as("get 读库后静态覆盖生效 → 放行").isTrue();
        } finally {
            TaskSystemConfig.clearForTest();
        }
    }

    @Test
    @DisplayName("update()：写库后同步 agentSwarmsEnabled 静态覆盖 → isAgentSwarmsEnabled() 放行（前端改开关立即生效）")
    void update_syncsOverride_agentSwarmsEnabled() {
        // WHY（规则九）：前端改开关 → update merge 写库 → 尾部同步静态覆盖 → isAgentSwarmsEnabled()
        //   立即生效（不重启）。merge 策略下 dto.agentSwarmsEnabled() 为写库后有效值。
        TaskSystemConfig.clearForTest();
        try {
            SettingsMapper mapper = mock(SettingsMapper.class);
            SettingsService service = newService(mapper, new SettingsRecord());

            SettingsDto req = new SettingsDto(
                    null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null,
                    null,
                    true,
                    null,
                    null,
                    // [V52] 压缩配置 12 列未设 → null
                    // [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // permissionMode（V44 全局默认，本测试未设 → null）· [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

            service.update(req);

            assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).as("update 写库后静态覆盖生效 → 放行").isTrue();
        } finally {
            TaskSystemConfig.clearForTest();
        }
    }

    @Test
    @DisplayName("bridgeAgentSwarmsSettingsSource 安装实时 DB 读源 → 不调 get/update 也读全局 true（换会话即生效）")
    void bridge_installsLiveDBSource() {
        // WHY（规则九 · bug 修复核心）：换会话若仍依赖 get/update 同步静态覆盖标志，则未调 get/update
        //   时 isAgentSwarmsEnabled() 读不到全局 settings → false（正是本 bug）。@PostConstruct 安装
        //   实时 DB 读源后，isAgentSwarmsEnabled() 每次调用实时读 DB → 全局开关所有会话生效。
        //   变异点：@PostConstruct 不装 source → 此测试 fail（不调 get/update 仍 false）。
        TaskSystemConfig.clearForTest();
        try {
            SettingsRecord s = new SettingsRecord();
            s.setAgentSwarmsEnabled(true);
            SettingsMapper mapper = mock(SettingsMapper.class);
            SettingsService service = newService(mapper, s);

            service.bridgeAgentSwarmsSettingsSource();  // 模拟 Spring @PostConstruct 回调

            // 未调用 get()/update() → isAgentSwarmsEnabled() 直接读 DB 全局 true
            assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).as("换会话不调 get/update → 仍读全局 DB true").isTrue();

            // source 权威：override 即使设为 false 也不覆盖实时 DB true
            TaskSystemConfig.setAgentSwarmsSettingsOverride(false);
            assertThat(TaskSystemConfig.isAgentSwarmsEnabled()).as("source 安装后为权威，不依赖 override 镜像").isTrue();
        } finally {
            TaskSystemConfig.clearForTest();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [V44] settings.permission_mode 全局默认权限模式（写侧校验 + merge + 透出）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("update()：PUT permissionMode='plan' → 落库 CC 串 + 返回 DTO 透出")
    void update_persistsPermissionMode() {
        // WHY: 前端「权限模式」全局默认设置 → PUT /api/v1/settings permissionMode → settings.permission_mode
        //   （V44 列）→ InitialPermissionModeSource 读链（DB ?? 磁盘）。列必须存 CC 串（plan）而非枚举 name
        //   （ACCEPT_EDITS 不被 fromString 识别 → 静默折叠 default，设置不生效）。
        SettingsRecord dbRow = new SettingsRecord();
        SettingsService service = newService(mock(SettingsMapper.class), dbRow);

        SettingsDto req = new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null,
                null,
                "plan",
                null,
                // [V52] 压缩配置 12 列未设 → null
                // [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

        SettingsDto dto = service.update(req);

        assertThat(dbRow.getPermissionMode()).as("落库 CC 串 plan（V44 列 permission_mode）").isEqualTo("plan");
        assertThat(dto.permissionMode()).as("update 返回 DTO 透出 permissionMode=plan").isEqualTo("plan");
    }

    @Test
    @DisplayName("update()：permissionMode=null → 不改动（merge 语义，保留旧值）")
    void update_nullPermissionModeKeepsOldValue() {
        // WHY: PUT 全字段可选——未传 permissionMode 不得误改全局默认（对齐既有 merge：null = 不覆盖）。
        SettingsRecord dbRow = new SettingsRecord();
        dbRow.setPermissionMode("dontAsk");
        SettingsService service = newService(mock(SettingsMapper.class), dbRow);

        SettingsDto req = new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null,
                null,
                null,
                null,
                // [V52] 压缩配置 12 列未设 → null
                // [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // permissionMode null → 不覆盖 · [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

        service.update(req);

        assertThat(dbRow.getPermissionMode()).as("permissionMode null → 保留旧值 dontAsk").isEqualTo("dontAsk");
    }

    @Test
    @DisplayName("update()：permissionMode='bubble' → ValidationException（BUBBLE 不可由 UI 设置，fail-loud）")
    void update_invalidPermissionMode_bubble_throws() {
        // WHY: BUBBLE 恒不可由 UI 设置（isSettable 拒绝）——写侧 fail-loud 而非静默折叠最严格 DEFAULT。
        SettingsRecord dbRow = new SettingsRecord();
        SettingsService service = newService(mock(SettingsMapper.class), dbRow);

        SettingsDto req = new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null,
                null,
                "bubble",
                null,
                // [V52] 压缩配置 12 列未设 → null
                // [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

        assertThatThrownBy(() -> service.update(req))
            .as("BUBBLE 不可由 UI 设置——写侧 fail-loud（V44 双防）")
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("update()：permissionMode='ACCEPT_EDITS'（枚举 name 非 CC 串）→ ValidationException")
    void update_invalidPermissionMode_enumName_throws() {
        // WHY: 列必须存 CC 串（acceptEdits）而非枚举 name（ACCEPT_EDITS）——枚举 name 不被
        //   fromString 识别 → 读回静默折叠 DEFAULT，设置"不生效"。isSettable 写侧校验必须拒绝。
        SettingsRecord dbRow = new SettingsRecord();
        SettingsService service = newService(mock(SettingsMapper.class), dbRow);

        SettingsDto req = new SettingsDto(
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                null,
                null,
                "ACCEPT_EDITS",
                null,
                // [V52] 压缩配置 12 列未设 → null
                // [V54] 压缩数值 11 列未设 → null
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);   // [V45] classifierModel 未设 → null · [V56] 提示词对齐门控 12 列未设 → null · [V61] enabledPlugins/pluginClaudeFallback 未设 → null

        assertThatThrownBy(() -> service.update(req))
            .as("枚举 name ACCEPT_EDITS 不被 isSettable 接受——防误存导致设置不生效")
            .isInstanceOf(ValidationException.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [V61] settings.enabled_plugins + settings.plugin_claude_fallback（插件配置 DB 化）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("get()：V61 插件双读配置（enabled_plugins JSON → Map + plugin_claude_fallback）原样透出")
    void get_mapsV61PluginColumns() {
        // WHY（规则九）：V61 列承载前端插件管理页/插件设置页写入值 —— get() 必须把
        //   enabled_plugins JSON 文本反序列化为 Map、plugin_claude_fallback 原样透出，
        //   否则前端读不回配置（GET /api/v1/settings 契约）。
        SettingsRecord s = new SettingsRecord();
        s.setAutoMemoryEnabled(true); // 避免 toDto 回落 settings.json 文件读
        s.setEnabledPlugins("{\"code-formatter@anthropic-tools\":true,\"demo@mkt\":false}");
        s.setPluginClaudeFallback(false);
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsService service = newService(mapper, s);

        SettingsDto dto = service.get();

        assertThat(dto.enabledPlugins())
            .as("V61 列 enabled_plugins JSON 文本必须反序列化为 Map 透出（前端插件管理页写）")
            .containsEntry("code-formatter@anthropic-tools", true)
            .containsEntry("demo@mkt", false);
        assertThat(dto.pluginClaudeFallback())
            .as("V61 列 plugin_claude_fallback 必须原样透出（前端插件设置页写）")
            .isFalse();
    }

    @Test
    @DisplayName("update()：V61 插件双读配置非 null → 覆盖写回；null → 不覆盖（merge）")
    void update_mergesV61PluginColumns() {
        // WHY（规则九）：PUT 全字段可选——enabledPlugins/pluginClaudeFallback 非 null 覆盖写回
        //   （Map → JSON 落 enabled_plugins 列），null 保留旧值（对齐既有 merge：null = 不覆盖）。
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = new SettingsRecord();
        dbRow.setEnabledPlugins("{\"old@mkt\":true}");
        dbRow.setPluginClaudeFallback(true);
        SettingsService service = newService(mapper, dbRow);

        // 两字段均非 null → 覆盖；其余全 null → 不触碰
        SettingsDto req = v61SettingsDto(Map.of("new@mkt", false), false);

        service.update(req);

        assertThat(dbRow.getEnabledPlugins())
            .as("enabledPlugins 非 null → Map 序列化 JSON 覆盖写回 enabled_plugins 列")
            .isEqualTo("{\"new@mkt\":false}");
        assertThat(dbRow.getPluginClaudeFallback())
            .as("pluginClaudeFallback 非 null → 覆盖写回 plugin_claude_fallback 列")
            .isFalse();
        verify(mapper).update(dbRow);
    }

    @Test
    @DisplayName("update()：V61 插件双读配置为 null → 不覆盖（merge 保留旧值）")
    void update_nullV61PluginColumnsKeepOldValues() {
        // WHY（规则九）：PUT 未传 enabledPlugins/pluginClaudeFallback 不得误改插件配置
        //   （对齐既有 merge：null = 不覆盖，前端局部 PUT 场景）。
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = new SettingsRecord();
        dbRow.setEnabledPlugins("{\"kept@mkt\":true}");
        dbRow.setPluginClaudeFallback(false);
        SettingsService service = newService(mapper, dbRow);

        SettingsDto req = v61SettingsDto(null, null);

        service.update(req);

        assertThat(dbRow.getEnabledPlugins())
            .as("enabledPlugins null → 保留旧值 JSON 文本")
            .isEqualTo("{\"kept@mkt\":true}");
        assertThat(dbRow.getPluginClaudeFallback())
            .as("pluginClaudeFallback null → 保留旧值 false")
            .isFalse();
        verify(mapper).update(dbRow);
    }
}
