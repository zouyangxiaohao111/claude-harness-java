package com.nexusai.kb.app.workspace;

import com.nexusai.domain.project.ProjectService;
import com.nexusai.model.project.dto.ProjectCreateRequest;
import com.nexusai.model.project.dto.ProjectDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.nexusai.application.agent.skill.NexusaiPaths;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库工作区项目 · 注册转发。
 *
 * <p><b>WHY</b>：本类的全部价值在于「把哪条 path 交给了 {@code ProjectService.register}」。
 * 若它传错 path（例如传目录名而不是绝对路径，或传了别的项目根），会话就会挂到错误项目下，
 * 而<b>页面一切正常</b>——正是改造前那个缺陷的形态。所以判据钉在 path 与 name 上。
 */
@DisplayName("[KB工作区] 项目注册转发")
class KbWorkspaceServiceTest {

    @TempDir
    Path tmp;

    @AfterEach
    void resetConfigHome() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    /** 造一个返回固定 DTO 的 mock，并捕获传给 register 的请求。 */
    private static ProjectService mockProjectService(ProjectDto dto) {
        ProjectService svc = Mockito.mock(ProjectService.class);
        Mockito.when(svc.register(Mockito.any())).thenReturn(dto);
        return svc;
    }

    private static ProjectDto dto(String id, String name, String path) {
        return new ProjectDto(id, name, path, "main", 0, 0, null, false, null);
    }

    @Test
    @DisplayName("透传给 register 的是工作区目录的绝对路径（正斜杠归一）")
    void registersWorkspacePath() {
        Path configHome = tmp.resolve("confighome-register");
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        ProjectDto expected = dto("proj-kb", "知识库",
            configHome.resolve("workspace/kb").toString().replace('\\', '/'));
        ProjectService svc = mockProjectService(expected);

        ProjectDto got = new KbWorkspaceService(svc).resolveProject();

        ArgumentCaptor<ProjectCreateRequest> captor =
            ArgumentCaptor.forClass(ProjectCreateRequest.class);
        Mockito.verify(svc, Mockito.times(1)).register(captor.capture());

        String sentPath = captor.getValue().path();
        // 必须落在降级链的第 3 级目录上，且是正斜杠（DB path UNIQUE 靠字面一致）
        assertThat(sentPath).isEqualTo(
            configHome.resolve("workspace/kb").toAbsolutePath().normalize().toString().replace('\\', '/'));
        assertThat(sentPath).doesNotContain("\\");
        // 目录必须真的建出来了（会话一旦绑定，后端会拿它当 cwd，不存在即全工具失败）
        assertThat(Path.of(sentPath)).exists().isDirectory();
        assertThat(got.id()).isEqualTo("proj-kb");
    }

    @Test
    @DisplayName("项目名固定为「知识库」（左栏分组显示名，不取目录名 kb）")
    void usesFixedProjectName() {
        NexusaiPaths.setConfigHomeDirOverride(tmp.resolve("confighome-name").toString());
        ProjectService svc = mockProjectService(dto("proj-kb", "知识库", "x"));

        new KbWorkspaceService(svc).resolveProject();

        ArgumentCaptor<ProjectCreateRequest> captor =
            ArgumentCaptor.forClass(ProjectCreateRequest.class);
        Mockito.verify(svc).register(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("知识库");
    }

    @Test
    @DisplayName("两次调用透传同一 path ⇒ 后端 register 的幂等判据能命中（不产生两个项目）")
    void samePathAcrossCalls() {
        NexusaiPaths.setConfigHomeDirOverride(tmp.resolve("confighome-twice").toString());
        ProjectService svc = mockProjectService(dto("proj-kb", "知识库", "x"));
        KbWorkspaceService service = new KbWorkspaceService(svc);

        service.resolveProject();
        service.resolveProject();

        ArgumentCaptor<ProjectCreateRequest> captor =
            ArgumentCaptor.forClass(ProjectCreateRequest.class);
        Mockito.verify(svc, Mockito.times(2)).register(captor.capture());
        assertThat(captor.getAllValues().get(0).path())
            .isEqualTo(captor.getAllValues().get(1).path());
    }
}
