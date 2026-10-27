package com.nexusai.domain.project;

import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目文件树写操作（新建/重命名/删除）· 路径穿越与覆盖保护。
 *
 * <p><b>WHY</b>（规则九）：这几个 API 直接操作绑定项目内的真实文件，
 * 一旦路径校验漏了就能写到项目外；重名覆盖会静默丢用户文件。测试锁定：
 * ① 正常新建文件/目录；② `../` 越界拒绝；③ 重名 409；④ 重命名只允许同目录；
 * ⑤ 目录递归删除；⑥ 项目根不可删。
 */
class ProjectServiceFileOpsTest {

    @TempDir
    Path root;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService();
        ProjectMapper mapper = mock(ProjectMapper.class);
        ProjectRecord rec = new ProjectRecord();
        rec.setId("p1");
        rec.setPath(root.toString());
        when(mapper.selectOneById("p1")).thenReturn(rec);
        ReflectionTestUtils.setField(service, "projectMapper", mapper);
    }

    @Test
    @DisplayName("新建文件/目录：父目录存在即可，目录内可继续建文件")
    void createFileAndDir() throws Exception {
        service.createEntry("p1", "a.txt", false);
        service.createEntry("p1", "sub", true);
        service.createEntry("p1", "sub/b.txt", false);

        assertTrue(Files.isRegularFile(root.resolve("a.txt")));
        assertTrue(Files.isDirectory(root.resolve("sub")));
        assertTrue(Files.isRegularFile(root.resolve("sub/b.txt")));
    }

    @Test
    @DisplayName("新建：../ 越界必须拒绝，且项目外不落文件")
    void createRejectsTraversal() throws Exception {
        Path outside = root.getParent().resolve("escape-" + System.nanoTime() + ".txt");
        assertThrows(NotFoundException.class,
            () -> service.createEntry("p1", "../" + outside.getFileName(), false));
        assertFalse(Files.exists(outside));
    }

    @Test
    @DisplayName("新建：父目录不存在 / 同名已存在 → 明确失败")
    void createRejectsMissingParentAndOverwrite() throws Exception {
        assertThrows(ValidationException.class, () -> service.createEntry("p1", "no-such/a.txt", false));

        Files.writeString(root.resolve("dup.txt"), "keep");
        assertThrows(ConflictException.class, () -> service.createEntry("p1", "dup.txt", false));
        assertTrue("keep".equals(Files.readString(root.resolve("dup.txt"))));
    }

    @Test
    @DisplayName("重命名：同目录改名成功；含分隔符/重名 → 拒绝")
    void renameEntry() throws Exception {
        Files.writeString(root.resolve("old.txt"), "x");

        service.renameEntry("p1", "old.txt", "new.txt");
        assertFalse(Files.exists(root.resolve("old.txt")));
        assertTrue(Files.isRegularFile(root.resolve("new.txt")));

        Files.writeString(root.resolve("other.txt"), "y");
        assertThrows(ValidationException.class, () -> service.renameEntry("p1", "new.txt", "../evil.txt"));
        assertThrows(ConflictException.class, () -> service.renameEntry("p1", "new.txt", "other.txt"));
        assertThrows(ValidationException.class, () -> service.renameEntry("p1", "new.txt", "a/b.txt"));
    }

    @Test
    @DisplayName("删除：文件与目录（递归）可删；项目根不可删")
    void deleteEntry() throws Exception {
        Files.createDirectories(root.resolve("dir/sub"));
        Files.writeString(root.resolve("dir/sub/x.txt"), "x");
        Files.writeString(root.resolve("one.txt"), "1");

        service.deleteEntry("p1", "one.txt");
        assertFalse(Files.exists(root.resolve("one.txt")));

        service.deleteEntry("p1", "dir");
        assertFalse(Files.exists(root.resolve("dir")));

        assertThrows(ValidationException.class, () -> service.deleteEntry("p1", "."));
    }
}
