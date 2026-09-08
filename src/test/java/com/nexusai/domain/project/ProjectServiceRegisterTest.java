package com.nexusai.domain.project;

import com.nexusai.model.project.dto.ProjectCreateRequest;
import com.nexusai.model.project.dto.ProjectDto;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [V69 · 对齐 CC 项目=目录] ProjectService.register 幂等单测 ——
 * 验证意图：同「目录绝对路径」重复注册 → 复用已注册记录，绝不再 INSERT（防 projects UNIQUE 500）；
 * 且新注册落库 path 统一为正斜杠绝对路径（DB path UNIQUE 字面一致的前提）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceRegisterTest {

    @Mock private ProjectMapper projectMapper;
    @InjectMocks private ProjectService projectService;

    // ── 归一化键：分隔符 / 尾斜杠统一（平台中立断言） ──
    @Test
    void normalizePathKey_backslashTrailingSlash_sameDirectory() {
        // Windows 风格反斜杠 + 尾斜杠 → 与正斜杠无尾斜杠视为同一目录
        assertTrue(ProjectService.normalizePathKey("D:\\code\\a\\proj").equals(
            ProjectService.normalizePathKey("D:/code/a/proj")));
        assertTrue(ProjectService.normalizePathKey("D:/code/a/proj//").equals(
            ProjectService.normalizePathKey("D:/code/a/proj")));
    }

    // ── 归一化键：Windows 忽略大小写（仅 Windows 运行期断言，Linux 不 gate） ──
    @Test
    void normalizePathKey_windowsIgnoresCase() {
        boolean caseInsensitive =
            ProjectService.normalizePathKey("D:/CODE/a/proj").equals(
                ProjectService.normalizePathKey("d:/code/a/proj"));
        // 平台一致：IS_WINDOWS=true 时必须忽略大小写；非 Windows 时不强制
        assertTrue(!ProjectService.IS_WINDOWS || caseInsensitive, "Windows 上盘符/路径大小写应归一");
    }

    // ── 新目录注册：INSERT 一次，落库 path = 正斜杠绝对路径 ──
    @Test
    void register_newDirectory_insertsOnce_withForwardSlashPath(@TempDir Path dir) throws Exception {
        when(projectMapper.selectAll()).thenReturn(List.of());
        String reqPath = dir.toAbsolutePath().normalize().toString().replace('\\', '/');
        ProjectDto dto = projectService.register(new ProjectCreateRequest("proj", reqPath));

        ArgumentCaptor<ProjectRecord> captor = ArgumentCaptor.forClass(ProjectRecord.class);
        verify(projectMapper).insert(captor.capture());
        assertEquals("proj", captor.getValue().getName());
        // 落库 path 必须已是正斜杠绝对（与 V69 path UNIQUE + 前端 normalizePath 契约一致）
        assertEquals(reqPath, captor.getValue().getPath());
        assertEquals(reqPath, dto.path());
        assertFalse(dto.path().contains("\\"), "落库 path 不应含反斜杠");
    }

    // ── 同路径重复注册：直接复用已注册记录，不再 INSERT（本 bug 的核心意图） ──
    @Test
    void register_samePathAgain_reusesExisting_noInsert(@TempDir Path dir) throws Exception {
        ProjectRecord existing = new ProjectRecord();
        existing.setId("proj-existing");
        existing.setName("proj");
        // 历史/存储 path 用反斜杠（Windows Path.toString）→ 归一后仍命中新注册请求
        existing.setPath(dir.toAbsolutePath().normalize().toString());
        when(projectMapper.selectAll()).thenReturn(List.of(existing));

        // 同一目录以正斜杠形式再次注册（对齐前端 normalizePath 传参）
        String reqPath = dir.toAbsolutePath().normalize().toString().replace('\\', '/');
        ProjectDto dto = projectService.register(new ProjectCreateRequest("proj", reqPath));

        // 命中已有 → 返回旧记录，绝不二次 INSERT（否则撞 projects path UNIQUE → 500）
        assertEquals("proj-existing", dto.id());
        assertEquals(existing.getPath(), dto.path());
        verify(projectMapper, never()).insert(any());
    }

    // ── 同名不同目录：视为两个项目（V69 name 去 UNIQUE 的意义） ──
    @Test
    void normalizePathKey_sameNameDifferentDirs_notEqual() {
        assertFalse(ProjectService.normalizePathKey("D:/a/proj").equals(
            ProjectService.normalizePathKey("D:/b/proj")));
    }
}
