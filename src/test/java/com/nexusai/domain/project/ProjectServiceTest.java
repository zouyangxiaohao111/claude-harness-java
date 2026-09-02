package com.nexusai.domain.project;

import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.project.dto.FileNodeDto;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ProjectService.buildTree · git ls-files → 目录树（IDE 项目结构）单测。 */
class ProjectServiceTest {

    @Test
    void buildTree_simpleFlatFiles() {
        List<FileNodeDto> tree = ProjectService.buildTree("README.md\npom.xml\n");
        assertEquals(2, tree.size());
        assertTrue(tree.stream().allMatch(n -> "file".equals(n.type())));
        assertEquals("README.md", tree.get(0).name());
    }

    @Test
    void buildTree_nestedDirs_ordered() {
        List<FileNodeDto> tree = ProjectService.buildTree(
            "src/main/java/App.java\nsrc/main/resources/app.yml\npom.xml\n");
        // 目录在前，文件在后
        assertEquals("src", tree.get(0).name());
        assertEquals("dir", tree.get(0).type());
        assertEquals("pom.xml", tree.get(1).name());
        // src 下子目录
        FileNodeDto src = tree.get(0);
        assertNotNull(src.children());
        assertEquals("main", src.children().get(0).name());
        FileNodeDto main = src.children().get(0);
        assertNotNull(main.children());
        assertEquals("java", main.children().get(0).name());
        assertEquals("resources", main.children().get(1).name());
    }

    @Test
    void buildTree_filePathCorrect() {
        List<FileNodeDto> tree = ProjectService.buildTree("src/main/java/App.java\n");
        assertEquals("src/main/java/App.java",
            tree.get(0).children().get(0).children().get(0).children().get(0).path());
    }

    @Test
    void buildTree_emptyInput() {
        assertTrue(ProjectService.buildTree(null).isEmpty());
        assertTrue(ProjectService.buildTree("").isEmpty());
        assertTrue(ProjectService.buildTree("   ").isEmpty());
    }

    @Test
    void buildTree_fileChildrenNull() {
        List<FileNodeDto> tree = ProjectService.buildTree("a.txt\nb/c.txt\n");
        // 目录在前：b（dir）→ a.txt（file）
        assertEquals("b", tree.get(0).name());
        assertEquals("dir", tree.get(0).type());
        assertEquals("a.txt", tree.get(1).name());
        assertNull(tree.get(1).children());
        // b 内文件 c.txt 是 file（children null）
        assertEquals("c.txt", tree.get(0).children().get(0).name());
        assertNull(tree.get(0).children().get(0).children());
    }

    @Test
    void resolveProjectFile_normal() {
        Path root = Path.of("D:/code/ai_project/nexusai").toAbsolutePath().normalize();
        Path resolved = ProjectService.resolveProjectFile(root, "src/App.tsx");
        assertTrue(resolved.startsWith(root));
        assertEquals("App.tsx", resolved.getFileName().toString());
    }

    @Test
    void resolveProjectFile_traversalRejected() {
        Path root = Path.of("D:/code/ai_project/nexusai").toAbsolutePath().normalize();
        assertThrows(NotFoundException.class,
            () -> ProjectService.resolveProjectFile(root, "../secret.txt"));
    }

    @Test
    void resolveProjectFile_traversalNestedRejected() {
        Path root = Path.of("D:/code/ai_project/nexusai").toAbsolutePath().normalize();
        assertThrows(NotFoundException.class,
            () -> ProjectService.resolveProjectFile(root, "src/../../secret.txt"));
    }

    // ── buildTreeFromFs：非 git 项目兜底（文件系统扫描） ──

    @Test
    void buildTreeFromFs_nonGitDir_listsFiles() throws Exception {
        Path tmp = java.nio.file.Files.createTempDirectory("proj-fs");
        try {
            java.nio.file.Files.writeString(tmp.resolve("README.md"), "readme");
            java.nio.file.Files.createDirectories(tmp.resolve("src/main/java"));
            java.nio.file.Files.writeString(tmp.resolve("src/main/java/App.java"), "class App{}");
            java.nio.file.Files.createDirectories(tmp.resolve(".git"));
            java.nio.file.Files.writeString(tmp.resolve(".git/config"), "hidden");

            List<FileNodeDto> tree = ProjectService.buildTreeFromFs(tmp, "test-id");

            // .git 应被排除；README.md + src/main/java/App.java 应出现
            assertEquals(2, tree.size(), "README.md + src 目录");
            assertTrue(tree.stream().noneMatch(n -> n.path().startsWith(".git")),
                ".git 隐藏目录应排除");
            assertTrue(tree.stream().anyMatch(n -> "README.md".equals(n.path())),
                "README.md 应列出");
            assertTrue(tree.stream().anyMatch(n -> "src".equals(n.path())),
                "src 目录应列出");
        } finally {
            // 清理临时目录
            try (var walk = java.nio.file.Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void buildTreeFromFs_nonExistentDir_returnsEmpty() {
        List<FileNodeDto> tree = ProjectService.buildTreeFromFs(
            Path.of("D:/nonexistent-dir-xyz"), "test-id");
        assertTrue(tree.isEmpty(), "不存在目录 → 空树");
    }
}
