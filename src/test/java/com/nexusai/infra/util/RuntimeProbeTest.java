package com.nexusai.infra.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RuntimeProbe 边界测试。
 *
 * <p>只测与机器无关的判据（不存在 / 0 字节别名），不依赖测试机是否装了 node / python。
 * 真实「命中」由手工实跑验证（2026-11-15：node/npx/python/uv 全部命中并返回版本）。
 */
class RuntimeProbeTest {

    @Test
    void missingCommandIsUnavailableAndSaysNotFound() {
        RuntimeProbe.Result r =
            RuntimeProbe.probe("nope", "--version", "nexusai-definitely-missing-runtime-xyz");

        assertThat(r.available()).isFalse();
        assertThat(r.path()).isNull();
        assertThat(r.error()).isEqualTo("not found on PATH");
    }

    @Test
    void zeroByteFileIsNotPlausibleExecutable(@TempDir Path dir) throws Exception {
        // Windows Store 的 python.exe 别名就是 0 字节 reparse point：文件存在但执行会弹商店
        Path empty = Files.createFile(dir.resolve("python.exe"));

        assertThat(RuntimeProbe.isPlausibleExecutable(empty)).isFalse();
    }

    @Test
    void regularNonEmptyFileIsPlausibleOnCurrentPlatform(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("some-executable"), "x");

        // Windows 只看文件形态；POSIX 要求可执行位，测试里显式设置
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertThat(file.toFile().setExecutable(true)).isTrue();
        }
        assertThat(RuntimeProbe.isPlausibleExecutable(file)).isTrue();
    }
}
