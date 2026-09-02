package com.nexusai.application.agent.config;

import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [IMP-H · F11 · M-23] ToolRegistrationConfig.sessionProjectRootResolver 解析链专属测试
 * （ODF-A1-REF findings#1 登记：DB 解析链行为未覆盖）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：resolver bean 是 Web 后端唯一的
 * per-session 项目目录概念（sessionId → SessionMapper.selectOneById → mainProjectId →
 * ProjectMapper.selectOneById → path），LlmAgentLoop run() 入口冻结 workspaceDir 与
 * ChatService resume worktree 恢复共用。此前无专属测试，解析链行为（含失败分支）未锁定。
 * 本测试直接以 mock mapper 调用 bean 方法（同 ToolRegistrationConfigMemoryBeansTest
 * 直调先例），覆盖：
 * <ol>
 *   <li>happy path：sessionId → mainProjectId → project.path 完整解析</li>
 *   <li>查询失败（SessionMapper.selectOneById 抛异常）→ null（LlmAgentLoop 回落默认）</li>
 *   <li>会话不存在 / 会话未绑定项目（mainProjectId null）→ null</li>
 *   <li>项目不存在 / 项目无 path / path 空白 → null</li>
 *   <li>null / blank sessionId → null 且不触发任何 DB 查询</li>
 * </ol>
 */
@DisplayName("[IMP-H F11 M-23] sessionProjectRootResolver 解析链（SessionMapper→ProjectMapper）")
class ToolRegistrationConfigSessionProjectRootResolverTest {

    private final ToolRegistrationConfig config = new ToolRegistrationConfig();

    private Function<String, String> resolver(SessionMapper sessionMapper, ProjectMapper projectMapper) {
        return config.sessionProjectRootResolver(sessionMapper, projectMapper);
    }

    private static SessionRecord session(String id, String mainProjectId) {
        SessionRecord rec = new SessionRecord();
        rec.setId(id);
        rec.setMainProjectId(mainProjectId);
        return rec;
    }

    private static ProjectRecord project(String id, String path) {
        ProjectRecord rec = new ProjectRecord();
        rec.setId(id);
        rec.setPath(path);
        return rec;
    }

    @Test
    @DisplayName("happy path: sessionId → SessionMapper.selectOneById → mainProjectId → ProjectMapper.selectOneById → path")
    void resolvesFullChainToProjectPath() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-9"));
        when(projectMapper.selectOneById("proj-9")).thenReturn(project("proj-9", "F:/workspace/alpha"));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isEqualTo("F:/workspace/alpha");
        verify(sessionMapper).selectOneById("sess-1");
        verify(projectMapper).selectOneById("proj-9");
    }

    @Test
    @DisplayName("SessionMapper.selectOneById 查询失败（DB 异常）→ null（回落，不向上抛）")
    void sessionQueryFailure_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById(any())).thenThrow(new RuntimeException("db down"));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
        verify(projectMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("会话不存在（selectOneById → null）→ null")
    void sessionNotFound_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(null);

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-ghost")).isNull();
        verify(projectMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("会话未绑定项目（mainProjectId null）→ null")
    void sessionWithoutMainProject_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", null));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
        verify(projectMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("项目不存在（selectOneById → null）→ null")
    void projectNotFound_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-ghost"));
        when(projectMapper.selectOneById("proj-ghost")).thenReturn(null);

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
    }

    @Test
    @DisplayName("项目无 path（null）→ null")
    void projectWithoutPath_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-9"));
        when(projectMapper.selectOneById("proj-9")).thenReturn(project("proj-9", null));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
    }

    @Test
    @DisplayName("项目 path 空白（blank）→ null")
    void projectBlankPath_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-9"));
        when(projectMapper.selectOneById("proj-9")).thenReturn(project("proj-9", "   "));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
    }

    @Test
    @DisplayName("sessionId null → null 且不触发任何 DB 查询")
    void nullSessionId_returnsNullWithoutDbAccess() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply(null)).isNull();
        verify(sessionMapper, never()).selectOneById(any());
        verify(projectMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("sessionId blank → null 且不触发任何 DB 查询")
    void blankSessionId_returnsNullWithoutDbAccess() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("  ")).isNull();
        verify(sessionMapper, never()).selectOneById(any());
        verify(projectMapper, never()).selectOneById(any());
    }
}
