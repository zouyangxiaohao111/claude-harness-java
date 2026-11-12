package com.nexusai.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据根解析契约（用户 2026-11-12 裁定：安装目录优先，configHome 兜底）。
 *
 * <p><b>WHY 直接测 {@code resolve} 而不测 {@code dataRoot()}</b>：{@code dataRoot()} 读真实进程环境
 * 并缓存结果，测它会把数据写到开发机真实目录；{@code resolve} 是可注入版本（env + configHome），
 * 与生产走同一段逻辑，只是把两个外部输入换成固定值。
 */
class NexusaiDataRootsTest {

    @TempDir
    Path tmp;

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    private Path installDir() throws Exception {
        return Files.createDirectories(tmp.resolve("D").resolve("Apps").resolve("NexusAI"));
    }

    @Test
    void 未设置安装目录_回落配置根_行为与改造前一致() {
        Path configHome = tmp.resolve("cfg");
        assertEquals(
            configHome.toAbsolutePath().normalize(),
            NexusaiDataRoots.resolve(env(Map.of()), configHome));
    }

    @Test
    void 安装目录可用_落到_data_workspace_default_并真正创建() throws Exception {
        Path install = installDir();
        Path root = NexusaiDataRoots.resolve(
            env(Map.of(NexusaiDataRoots.INSTALL_DIR_ENV, install.toString())),
            tmp.resolve("cfg"));
        assertEquals(install.resolve("data").resolve("workspace").resolve("default").toAbsolutePath().normalize(), root);
        assertTrue(Files.isDirectory(root), "数据根必须已创建");
    }

    @Test
    void 安装目录可用_账户标识进目录名() throws Exception {
        Path install = installDir();
        Path root = NexusaiDataRoots.resolve(
            env(Map.of(
                NexusaiDataRoots.INSTALL_DIR_ENV, install.toString(),
                NexusaiDataRoots.USER_ENV, "zcw")),
            tmp.resolve("cfg"));
        assertEquals(install.resolve("data").resolve("workspace").resolve("zcw").toAbsolutePath().normalize(), root);
    }

    @Test
    void 安装目录路径不存在_降级配置根_不得在错位置建目录() {
        Path configHome = tmp.resolve("cfg");
        assertEquals(
            configHome.toAbsolutePath().normalize(),
            NexusaiDataRoots.resolve(
                env(Map.of(NexusaiDataRoots.INSTALL_DIR_ENV, tmp.resolve("no-such-install").toString())),
                configHome));
    }

    @Test
    void 用户名清洗_非法字符替换_空则default() {
        assertEquals("a_b_c", NexusaiDataRoots.safeUserDirName("a/b:c"));
        assertEquals("zcw", NexusaiDataRoots.safeUserDirName("zcw"));
        assertEquals("default", NexusaiDataRoots.safeUserDirName(null));
        assertEquals("default", NexusaiDataRoots.safeUserDirName("   "));
    }
}
