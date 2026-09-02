package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * [IMP-5 WDS] symlink PWD 注入单元测试 · 对齐 CC permissionSetup.ts:917-928。
 *
 * <p><b>WHY</b>: 用户 {@code cd} 进 symlink 目录时，shell 上报的 PWD 是 symlink 路径，
 * 而 originalCwd（user.dir）是 realpath。CC 会把该 symlink PWD 以 source=session 注入
 * additionalWorkingDirectories，让用户通过 symlink 看到的 cwd 落入权限工作目录范围。
 * 本测试锁定：symlink PWD 解析等于 originalCwd 时注入；非 symlink / 相等 / 解析不同时均不注入。
 */
class PermissionContextBuilderSymlinkPwdTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("symlink PWD 解析等于 originalCwd 时注入 session 附加工作目录")
    void injectsSymlinkPwdWhenResolvesToOriginalCwd() throws IOException {
        Path realDir = Files.createDirectory(tempDir.resolve("real"));
        Path symlink = tempDir.resolve("link");
        assumeTrue(tryCreateSymlink(realDir, symlink), "当前环境不支持创建符号链接，跳过");

        Map<String, AdditionalWorkingDirectory> result =
            PermissionContextBuilder.symlinkPwdWorkingDirectories(
                symlink.toString(), realDir.toRealPath().toString());

        assertEquals(1, result.size());
        AdditionalWorkingDirectory dir = result.get(symlink.toString());
        assertEquals(symlink.toString(), dir.path());
        assertEquals(PermissionRuleSource.SESSION, dir.source());
    }

    @Test
    @DisplayName("中间组件 symlink（symlink 目录下的子目录作为 PWD）时注入 session 附加工作目录")
    void injectsSymlinkPwdWhenIntermediateComponentIsSymlink() throws IOException {
        // CC 语义：realpath(PWD) != PWD 即视为 symlink，不要求 PWD 最后一段是 symlink。
        // 场景：realDir/link 是 symlink → realDir，PWD = link/foo（foo 为 realDir 内真实子目录）。
        Path realDir = Files.createDirectory(tempDir.resolve("real-inter"));
        Path subdir = Files.createDirectory(realDir.resolve("foo"));
        Path symlink = tempDir.resolve("link-inter");
        assumeTrue(tryCreateSymlink(realDir, symlink), "当前环境不支持创建符号链接，跳过");

        Path processPwd = symlink.resolve("foo");
        String originalCwd = subdir.toRealPath().toString();

        Map<String, AdditionalWorkingDirectory> result =
            PermissionContextBuilder.symlinkPwdWorkingDirectories(
                processPwd.toString(), originalCwd);

        assertEquals(1, result.size());
        AdditionalWorkingDirectory dir = result.get(processPwd.toString());
        assertEquals(processPwd.toString(), dir.path());
        assertEquals(PermissionRuleSource.SESSION, dir.source());
    }

    @Test
    @DisplayName("processPwd 非 symlink 时不注入")
    void noInjectionWhenProcessPwdIsNotSymlink() throws IOException {
        Path realDir = Files.createDirectory(tempDir.resolve("real2"));

        Map<String, AdditionalWorkingDirectory> result =
            PermissionContextBuilder.symlinkPwdWorkingDirectories(
                realDir.toString(), realDir.toRealPath().toString());

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("processPwd 等于 originalCwd 时不注入（短路）")
    void noInjectionWhenPwdEqualsOriginalCwd() throws IOException {
        Path realDir = Files.createDirectory(tempDir.resolve("real3"));
        String cwd = realDir.toRealPath().toString();

        Map<String, AdditionalWorkingDirectory> result =
            PermissionContextBuilder.symlinkPwdWorkingDirectories(cwd, cwd);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("processPwd 为 null 或空时不注入")
    void noInjectionWhenPwdBlank() {
        assertTrue(PermissionContextBuilder.symlinkPwdWorkingDirectories(null, "/tmp/x").isEmpty());
        assertTrue(PermissionContextBuilder.symlinkPwdWorkingDirectories("  ", "/tmp/x").isEmpty());
        assertTrue(PermissionContextBuilder.symlinkPwdWorkingDirectories("/tmp/x", null).isEmpty());
    }

    @Test
    @DisplayName("symlink PWD 解析为其他目录时不注入")
    void noInjectionWhenSymlinkResolvesElsewhere() throws IOException {
        Path realDir = Files.createDirectory(tempDir.resolve("real4"));
        Path otherDir = Files.createDirectory(tempDir.resolve("other"));
        Path symlink = tempDir.resolve("link2");
        assumeTrue(tryCreateSymlink(realDir, symlink), "当前环境不支持创建符号链接，跳过");

        Map<String, AdditionalWorkingDirectory> result =
            PermissionContextBuilder.symlinkPwdWorkingDirectories(
                symlink.toString(), otherDir.toRealPath().toString());

        assertTrue(result.isEmpty());
    }

    /** 创建符号链接（Windows 无权限 / 文件系统不支持时返回 false，测试经 assumeTrue 跳过）。 */
    private static boolean tryCreateSymlink(Path target, Path link) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }
}
