package com.nexusai.application.project;

import com.nexusai.common.SessionProjectRoot;
import com.nexusai.model.project.dto.ProjectBindRequest;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [批 4a · 用户裁定 #12] 绑定/解绑路径的会话 projectRoot 冻结值失效。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：{@link SessionProjectRoot#setForSession} 是 <b>putIfAbsent
 * 首写胜</b>（对齐 CC stable identity：会话内不重锚）。但<b>显式改绑</b>（A 项目 → B 项目 / 解绑）
 * 必须让旧冻结值失效，否则 CwdResolution / agent-memory / transcript / 权限 baseDir 全部仍解析到
 * <b>旧项目根</b>——跨项目污染（用户裁定 #12 的原话场景：「PATCH 改绑不更新旧冻结值」）。
 *
 * <p>RED：删掉 {@code bind()} 里的 {@code clearSession}（或把它挪到 {@code setForSession} 之后）
 * ⇒ 改绑后仍读到旧项目根 ⇒ 第一个用例红。
 */
@DisplayName("[批 4a #12] ProjectSessionBindingService 改绑/解绑 → 冻结值失效")
class ProjectSessionBindingServiceRebindTest {

    @TempDir
    Path oldRoot;

    @TempDir
    Path newRoot;

    @AfterEach
    void cleanup() {
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
    }

    private ProjectSessionBindingService serviceWith(SessionMapper sessionMapper, ProjectMapper projectMapper) {
        ProjectSessionBindingService svc = new ProjectSessionBindingService();
        ReflectionTestUtils.setField(svc, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(svc, "projectMapper", projectMapper);
        ReflectionTestUtils.setField(svc, "claudeToNexusaiMigrator",
            mock(ClaudeToNexusaiMigrator.class));
        return svc;
    }

    @Test
    @DisplayName("改绑项目（A → B）→ 旧冻结值失效，解析到新项目根（不残留旧绑定）")
    void bind_rebind_invalidatesOldFrozenRoot() {
        String sessionId = "sess-rebind-bind";
        SessionRecord session = new SessionRecord();
        session.setId(sessionId);
        session.setMainProjectId("proj-a");
        ProjectRecord project = new ProjectRecord();
        project.setId("proj-b");
        project.setPath(newRoot.toString());
        project.setBound(Boolean.TRUE);   // 非首绑 ⇒ 跳过 .claude→.nexusai 一次性导入分支
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById(sessionId)).thenReturn(session);
        when(projectMapper.selectOneById("proj-b")).thenReturn(project);

        // 前置：会话已冻结在旧项目根 A
        SessionProjectRoot.setForSession(sessionId, oldRoot.toString());
        assertThat(SessionProjectRoot.getForSession(sessionId)).isEqualTo(oldRoot.toString());

        serviceWith(sessionMapper, projectMapper).bind(sessionId, new ProjectBindRequest("proj-b"));

        assertThat(SessionProjectRoot.getForSession(sessionId))
            .as("改绑后必须解析到新项目根 B（清缓存 + 写新值），不得残留旧冻结值 A")
            .isEqualTo(newRoot.toString())
            .isNotEqualTo(oldRoot.toString());
    }

    @Test
    @DisplayName("解绑项目 → 冻结值清除（未绑定 ⇒ null）")
    void unbind_clearsFrozenRoot() {
        String sessionId = "sess-unbind";
        SessionRecord session = new SessionRecord();
        session.setId(sessionId);
        session.setMainProjectId("proj-a");
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(sessionId)).thenReturn(session);

        SessionProjectRoot.setForSession(sessionId, oldRoot.toString());
        assertThat(SessionProjectRoot.getForSession(sessionId)).isEqualTo(oldRoot.toString());

        serviceWith(sessionMapper, mock(ProjectMapper.class)).unbind(sessionId);

        assertThat(SessionProjectRoot.getForSession(sessionId))
            .as("解绑后冻结值必须清除（否则仍按旧项目根写目录）")
            .isNull();
    }
}
