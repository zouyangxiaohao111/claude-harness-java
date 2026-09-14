package com.nexusai.application.agent.config;

import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
 *   <li>+ [S2 · F-24-merge Step 2 2026-09-14] 归一化契约：命中的 path 经 realpath+NFC 归一后返回；
 *       归一化后目录无效（不存在）⇒ null（=「有会话但绑定失效」）。原 happy path 夹具用了不存在的
 *       盘符（违反 {@code ProjectService} 落库校验），已改夹具（见该用例 javadoc）。</li>
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

    /**
     * happy path：会话 → 项目 → 路径的完整解析链。
     *
     * <p><b>[S2 · F-24-merge Step 2 2026-09-14 夹具修正 —— 原夹具断言了错的行为]</b>
     * 原夹具用 {@code "F:/workspace/alpha"}（本机不存在的盘符）当「happy path」，并断言
     * resolver 原样返回它。但那<b>违反生产不变量</b>：{@code ProjectService.normalizeProjectPath}
     * 只在 {@code Files.isDirectory(abs)} 通过时才落库 ⇒ <b>真实的 {@code projects.path} 必然指向
     * 存在的目录</b>，「DB 里有不存在的 path」在生产不可达。
     * <p>Step 2 起 resolver 在返回前做 realpath + NFC 归一 + 用同一判据校验（差异 B 消除，见
     * {@code ProjectRootNormalizationDivergenceExperimentTest}）⇒ 不存在的路径按「有会话但绑定失效」
     * 返回 null（= 正确的生产语义）。故本用例改为用<b>真实存在的 @TempDir</b> 作夹具，并断言返回
     * <b>归一化后</b>的值（= resolver 的新契约），同时保留原有意图：<b>查询链</b>（两个 mapper 的
     * 调用与入参）与三态入口不变。
     */
    @Test
    @DisplayName("happy path: sessionId → SessionMapper.selectOneById → mainProjectId → ProjectMapper.selectOneById → path（返回归一值）")
    void resolvesFullChainToProjectPath(@TempDir Path projectDir) throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        // ⚠️ 夹具必须是真实存在的目录（见 javadoc）；用正斜杠形态模拟 ProjectService 的存储形态
        String dbPath = projectDir.toString().replace('\\', '/');
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-9"));
        when(projectMapper.selectOneById("proj-9")).thenReturn(project("proj-9", dbPath));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1"))
            .as("解析链成立；返回值 = realpath+NFC 归一形态（Step 2 契约）")
            .isEqualTo(projectDir.toRealPath().toString());
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
