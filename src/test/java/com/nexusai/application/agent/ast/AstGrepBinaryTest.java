package com.nexusai.application.agent.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AstGrepBinary 的资源矩阵与落盘。
 *
 * <p>WHY：资源是「六个平台都随包」的硬约束（{@code resources/rg/} 同矩阵）。少一个平台不会报错，
 * 只会让那台机器上工具静默不可用 —— 这里用 classpath 存在性把矩阵钉住。
 */
@DisplayName("AstGrepBinaryTest · 六平台资源 + 宿主解压")
class AstGrepBinaryTest {

    private static final String[][] PLATFORMS = {
        {"x64-win32", "ast-grep.exe"},
        {"arm64-win32", "ast-grep.exe"},
        {"x64-darwin", "ast-grep"},
        {"arm64-darwin", "ast-grep"},
        {"x64-linux", "ast-grep"},
        {"arm64-linux", "ast-grep"},
    };

    @Test
    @DisplayName("六个平台的 gz 资源都在 classpath 上")
    void allPlatformResourcesPresent() {
        for (String[] p : PLATFORMS) {
            String resource = "/ast-grep/" + p[0] + "/" + p[1] + ".gz";
            try (InputStream in = AstGrepBinaryTest.class.getResourceAsStream(resource)) {
                assertThat(in).as("缺少 %s（先跑 node scripts/fetch-ast-grep.mjs --all）", resource)
                    .isNotNull();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    @DisplayName("宿主平台：可用性判定与解压结果为 >20MB 的可执行文件")
    void extractsHostBinary() throws Exception {
        assumeTrue(AstGrepBinary.available(), "本平台未打包 ast-grep 二进制，跳过解压验证");
        var bin = AstGrepBinary.ensureBinary();
        assertThat(Files.isRegularFile(bin)).isTrue();
        assertThat(Files.size(bin)).isGreaterThan(20_000_000L);
        if (!PlatformKey.isWindows()) {
            assertThat(Files.isExecutable(bin)).isTrue();
        }
        assertThat(PlatformKey.gzResource()).isEqualTo("/ast-grep/" + PlatformKey.dirName() + "/"
            + PlatformKey.exeName() + ".gz");
    }
}
