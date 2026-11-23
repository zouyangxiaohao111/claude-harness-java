package com.nexusai.application.agent.config;

import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话级「已启动应用」读取点（V337 enabled_apps）：三态 + 跨会话隔离 + fail-soft。
 */
@DisplayName("SessionEnabledAppsConfig · enabled_apps 读取")
class SessionEnabledAppsConfigTest {

    @AfterEach
    void tearDown() {
        SessionEnabledAppsConfig.reset();
        // LlmAgentLoop.setSessionMapper 同时桥接其它两个静态配置 ⇒ 一并复位（防跨测试污染）
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
    }

    @Test
    @DisplayName("生产注入点：LlmAgentLoop.setSessionMapper 会桥接 enabled_apps")
    void bridgedByLlmAgentLoopSetter() {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(row("sess-a", "[\"A\"]"));

        new com.nexusai.application.agent.LlmAgentLoop(
            mock(com.nexusai.infra.llm.LlmProviderFactory.class)).setSessionMapper(mapper);

        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-a")).containsExactly("A");
    }

    private static SessionRecord row(String id, String enabledAppsJson) {
        SessionRecord r = new SessionRecord();
        r.setId(id);
        r.setEnabledApps(enabledAppsJson);
        return r;
    }

    private static void given(SessionRecord... rows) {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            for (SessionRecord r : rows) {
                if (r.getId() != null && r.getId().equals(id)) {
                    return r;
                }
            }
            return null;
        });
        SessionEnabledAppsConfig.setSessionMapper(mapper);
    }

    @Test
    @DisplayName("未设置（列 null）⇒ null（= 按 defaultOpen 种子）")
    void unset_returnsNull() {
        given(row("sess-a", null));
        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-a")).isNull();
    }

    @Test
    @DisplayName("空数组 ⇒ 空列表（显式全不启动，与 null 区分）")
    void emptyArray_returnsEmptyList() {
        given(row("sess-a", "[]"));
        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-a")).isEmpty();
    }

    @Test
    @DisplayName("数组 ⇒ 去空白后的启动集")
    void list_returnsTrimmedNames() {
        given(row("sess-a", "[\" A \",\"\",\"B\"]"));
        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-a")).containsExactly("A", "B");
    }

    @Test
    @DisplayName("切会话不串：各读各的")
    void perSessionIsolation() {
        given(row("sess-a", "[\"A\"]"), row("sess-b", "[\"B\"]"));
        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-a")).containsExactly("A");
        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-b")).containsExactly("B");
    }

    @Test
    @DisplayName("fail-soft：无 mapper / 脏 JSON / 会话不存在 / 空 id ⇒ null")
    void failSoft() {
        SessionEnabledAppsConfig.reset();
        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-a")).isNull();

        given(row("sess-a", "not-json"));
        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-a")).isNull();

        assertThat(SessionEnabledAppsConfig.getEnabledApps("sess-missing")).isNull();
        assertThat(SessionEnabledAppsConfig.getEnabledApps(null)).isNull();
        assertThat(SessionEnabledAppsConfig.getEnabledApps("  ")).isNull();
    }
}
