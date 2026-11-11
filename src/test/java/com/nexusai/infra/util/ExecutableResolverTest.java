package com.nexusai.infra.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-WIN-1] ExecutableResolver 单测。
 *
 * <p><b>WHY</b>：{@link java.lang.ProcessBuilder} 不走 shell、不认 Windows {@code PATHEXT}。
 * npm 全局安装只产出 {@code <name>.cmd}，传裸名会 {@code CreateProcess error=2}
 * （现象：NexusAI 侧「Failed to start MCP server codegraph」）。本测试锁定解析契约，
 * 防止回归到「裸名直接交给 ProcessBuilder」。
 *
 * <p><b>RED tooth</b>：把 {@code resolve} 里的候选扩展逻辑删掉（只保留原名）→
 * {@code windows_bareName_resolvesToCmd} 必红；把「含分隔符则原样返回」的分支删掉 →
 * {@code pathLike_passthrough} 必红。
 */
@DisplayName("[MCP-WIN-1] ExecutableResolver · Windows 裸命令名 → 真实路径")
class ExecutableResolverTest {

    private static final String WIN = "Windows 11";
    private static final String LINUX = "Linux";

    /** 用固定 PATH + 假文件系统谓词，测纯逻辑，不碰真实磁盘。 */
    private static String resolveWin(String command, String pathext, Set<Path> existing) {
        Map<String, String> env = Map.of(
            "PATH", "C:\\npm;C:\\other",
            "PATHEXT", pathext);
        return ExecutableResolver.resolve(command, WIN, env, existing::contains);
    }

    @Test
    @DisplayName("Windows 裸名 codegraph → 命中 codegraph.cmd 完整路径（本 bug 的主用例）")
    void windows_bareName_resolvesToCmd() {
        Path expected = Paths.get("C:\\npm").resolve("codegraph.cmd");
        String got = resolveWin("codegraph", ".COM;.EXE;.BAT;.CMD", Set.of(expected));

        assertThat(got).as("必须解析出真实 .cmd 路径，而不是原样返回裸名")
            .isEqualToIgnoringCase(expected.toString());
        assertThat(got).isNotEqualTo("codegraph");
        assertThat(got.toLowerCase(java.util.Locale.ROOT))
            .as("必须带扩展名（Windows 文件系统大小写不敏感，.CMD/.cmd 等价，"
                + "ProcessBuilder 对两者均可启动——已实测 rc=0）")
            .endsWith("codegraph.cmd");
    }

    @Test
    @DisplayName("PATHEXT 顺序优先：.exe 存在时优先于 .cmd（与 cmd.exe 语义一致）")
    void windows_pathextOrderIsRespected() {
        Path exe = Paths.get("C:\\npm").resolve("tool.exe");
        Path cmd = Paths.get("C:\\npm").resolve("tool.cmd");

        String got = resolveWin("tool", ".COM;.EXE;.BAT;.CMD", Set.of(exe, cmd));

        assertThat(got).as(".EXE 在 PATHEXT 中先于 .CMD ⇒ 取 .exe")
            .isEqualToIgnoringCase(exe.toString());
        assertThat(got.toLowerCase(java.util.Locale.ROOT)).endsWith(".exe");
    }

    @Test
    @DisplayName("PATHEXT 缺失 → 用 Windows 默认 .COM;.EXE;.BAT;.CMD")
    void windows_defaultPathextWhenEnvMissing() {
        Path expected = Paths.get("C:\\npm").resolve("codegraph.CMD");
        Map<String, String> env = Map.of("PATH", "C:\\npm"); // 无 PATHEXT
        String got = ExecutableResolver.resolve("codegraph", WIN, env,
            p -> p.equals(expected) || p.equals(Paths.get("C:\\npm").resolve("codegraph.cmd")));

        assertThat(got).as("PATHEXT 缺失也应按默认扩展探测").startsWith("C:\\npm\\codegraph.");
    }

    @Test
    @DisplayName("含路径分隔符的命令原样返回（绝对/相对/UNC 都不改写）")
    void pathLike_passthrough() {
        Set<Path> none = Set.of();
        assertThat(ExecutableResolver.resolve("/usr/bin/node", WIN, Map.of(), none::contains))
            .isEqualTo("/usr/bin/node");
        assertThat(ExecutableResolver.resolve("./scripts/run.sh", WIN, Map.of(), none::contains))
            .isEqualTo("./scripts/run.sh");
        assertThat(ExecutableResolver.resolve("C:\\Windows\\system32\\cmd.exe", WIN, Map.of(), none::contains))
            .isEqualTo("C:\\Windows\\system32\\cmd.exe");
        assertThat(ExecutableResolver.resolve("\\\\server\\share\\x.exe", WIN, Map.of(), none::contains))
            .isEqualTo("\\\\server\\share\\x.exe");
    }

    @Test
    @DisplayName("非 Windows 原样返回（Unix 由内核按 PATH 解析）")
    void nonWindows_passthrough() {
        String got = ExecutableResolver.resolve("codegraph", LINUX,
            Map.of("PATH", "/usr/local/bin"), p -> true);

        assertThat(got).as("非 Windows 不做改写").isEqualTo("codegraph");
    }

    @Test
    @DisplayName("探测全部失败 → 原样返回，错误信息仍由 ProcessBuilder 给出")
    void notFound_passthrough() {
        String got = resolveWin("nonexistent-tool", ".COM;.EXE;.BAT;.CMD", Set.of());

        assertThat(got).as("找不到时保持原值，不伪造路径").isEqualTo("nonexistent-tool");
    }

    @Test
    @DisplayName("已带扩展名的命令只探测原名，不叠加 PATHEXT")
    void alreadyHasExtension_noDoubleExtension() {
        List<String> names = ExecutableResolver.candidateNames("tool.cmd", ".COM;.EXE;.BAT;.CMD");

        assertThat(names).as("带扩展名时不生成 tool.cmd.EXE 这类叠加名")
            .containsExactly("tool.cmd");
    }

    @Test
    @DisplayName("PATH 中多个目录：按目录顺序命中即停")
    void multiplePathDirs_firstHitWins() {
        Path inSecond = Paths.get("C:\\other").resolve("codegraph.cmd");
        String got = resolveWin("codegraph", ".CMD", Set.of(inSecond));

        assertThat(got).as("第一个目录无命中 ⇒ 继续到第二个目录")
            .isEqualToIgnoringCase(inSecond.toString())
            .startsWith("C:\\other\\");
    }

    @Test
    @DisplayName("candidateNames：裸名不探测原名（npm 的 sh 脚本会 error=193），只按 PATHEXT 扩展")
    void candidateNames_shape() {
        List<String> names = ExecutableResolver.candidateNames("cg", ".EXE;.CMD");

        assertThat(names).as("裸名本身绝不能作为候选——npm 同目录有无扩展名的 sh 脚本，"
                + "命中它会 CreateProcess error=193（不是有效的 Win32 应用程序，实测）")
            .doesNotContain("cg");
        assertThat(names.stream().map(String::toLowerCase).collect(Collectors.toSet()))
            .as("大小写变体归并后应为 cg.exe / cg.cmd")
            .isEqualTo(Set.of("cg.exe", "cg.cmd"));
    }

    @Test
    @DisplayName("同目录同时存在无扩展名 sh 脚本与 .cmd → 必须选中 .cmd（本 bug 的真实形态）")
    void realNpmLayout_picksCmdOverExtensionless() {
        // 真实 npm 全局目录布局：codegraph（sh 脚本）、codegraph.cmd、codegraph.ps1
        Path sh = Paths.get("C:\\npm").resolve("codegraph");
        Path cmd = Paths.get("C:\\npm").resolve("codegraph.cmd");
        Path ps1 = Paths.get("C:\\npm").resolve("codegraph.ps1");

        String got = resolveWin("codegraph", ".COM;.EXE;.BAT;.CMD", Set.of(sh, cmd, ps1));

        assertThat(got.toLowerCase(java.util.Locale.ROOT))
            .as("必须选 .cmd；选到无扩展名 sh 脚本会 CreateProcess error=193")
            .endsWith("codegraph.cmd");
        assertThat(got).isNotEqualTo(sh.toString());
    }
}
