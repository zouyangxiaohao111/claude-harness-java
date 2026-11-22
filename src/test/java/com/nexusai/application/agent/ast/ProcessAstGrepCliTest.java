package com.nexusai.application.agent.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Windows 命令行引号转义（{@link ProcessAstGrepCli#escapeArg}）。
 *
 * <p>WHY：JDK 在 Windows 上不转义参数内部引号（实测：{@code foo("hi")} 到子进程变成 {@code foo(hi)}，
 * 匹配为空且不报错）。这条转换是「带引号的 AST 模式仍能命中」的唯一保障，必须钉住确切形态。
 */
@DisplayName("ProcessAstGrepCliTest · Windows 引号转义")
class ProcessAstGrepCliTest {

    @Test
    @DisplayName("引号 → 反斜杠+引号；紧邻引号的反斜杠加倍；普通串不变")
    void escapeArgShape() {
        assumeTrue(PlatformKey.isWindows(), "仅 Windows 需要命令行转义");
        assertThat(ProcessAstGrepCli.escapeArg("plain")).isEqualTo("plain");
        assertThat(ProcessAstGrepCli.escapeArg("ab\"cd")).isEqualTo("ab\\\"cd");
        // a + \ + " + b  →  a + \\\ + " + b（1 个原有反斜杠 + 2 个补偿 = 3 个）
        assertThat(ProcessAstGrepCli.escapeArg("a\\\"b")).isEqualTo("a\\\\\\\"b");
        assertThat(ProcessAstGrepCli.escapeArg("")).isEmpty();
    }

    @Test
    @DisplayName("非 Windows 原样返回（POSIX shell 不做二次解析，参数直达 argv）")
    void escapeArgNoopOnPosix() {
        if (PlatformKey.isWindows()) {
            return;
        }
        assertThat(ProcessAstGrepCli.escapeArg("ab\"cd")).isEqualTo("ab\"cd");
    }
}
