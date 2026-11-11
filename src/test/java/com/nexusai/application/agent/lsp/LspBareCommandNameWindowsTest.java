package com.nexusai.application.agent.lsp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [LSP-WIN-1] Windows 上以<b>裸命令名</b>启动 npm 形态的 LSP server（真实 {@code .cmd}）。
 *
 * <p><b>WHY（规则九）</b>：用户配置里写的是 {@code typescript-language-server} 这类裸名，
 * npm 全局安装只产出 {@code .cmd}。{@link java.lang.ProcessBuilder} 不走 shell、不认
 * {@code PATHEXT} ⇒ 裸名必 {@code CreateProcess error=2 系统找不到指定的文件}。
 *
 * <p><b>为什么必须传裸名</b>：本测试全程通过 {@link ProcessLspClient#start} 传<b>裸名</b>
 * {@code lspbare}，并把承载 {@code lspbare.cmd} 的临时目录通过 {@code env} 注入 PATH。
 * 只有这样才能真正走到 {@code ExecutableResolver} 的解析分支——若直接传解析好的完整路径，
 * 修复被删掉测试依然全绿（已验证过：这是无效测试的典型形态）。
 * 因此 RED tooth = 删掉 {@code ProcessLspClient} 里的 {@code ExecutableResolver.resolve} 调用
 * ⇒ {@link #bareName_startsServerThroughResolver} 必红（CreateProcess error=2）。
 */
@EnabledOnOs(OS.WINDOWS)
@DisplayName("[LSP-WIN-1] Windows 裸命令名 → .cmd 解析后成功启动 LSP")
class LspBareCommandNameWindowsTest {

    private ProcessLspClient client;
    private Path tempDir;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            try {
                client.stop();
            } catch (RuntimeException ignored) {
                // 失败路径下进程可能已不存在
            }
        }
        if (tempDir != null && Files.isDirectory(tempDir)) {
            try (var s = Files.list(tempDir)) {
                s.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
            Files.deleteIfExists(tempDir);
        }
    }

    private static String mockServerPath() throws Exception {
        URL url = LspBareCommandNameWindowsTest.class.getClassLoader().getResource("mock-lsp-server.py");
        assertThat(url).as("mock-lsp-server.py 必须在测试 classpath 上").isNotNull();
        return Paths.get(url.toURI()).toAbsolutePath().toString();
    }

    /**
     * 造一个转发型 {@code .cmd}——与真实 npm shim 职责等价（转发给某解释器执行脚本）。
     * 返回命令行内容不含换行差异风险，故用 CRLF（Windows .cmd 需要）。
     */
    private Path createForwardingCmd(Path dir, String baseName) throws Exception {
        String content = "@ECHO off\r\n"
            + "\"python\" \"" + mockServerPath() + "\" %*\r\n";
        Path f = dir.resolve(baseName + ".cmd");
        Files.writeString(f, content, StandardCharsets.US_ASCII);
        return f;
    }

    @Test
    @DisplayName("阴性对照：不存在的裸名直接交给 ProcessBuilder 必须失败")
    void negativeControl_bareNameIsNotExecutable() {
        assertThatThrownBy(() -> {
            ProcessBuilder pb = new ProcessBuilder("lspbare-definitely-not-installed", "--stdio");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
            p.destroyForcibly();
        }).as("裸名不存在时必须抛 IOException（CreateProcess error=2）——证明这条路径确实会失败")
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("阴性对照：无扩展名 sh 脚本交给 ProcessBuilder 抛 IOException（error=193）")
    void negativeControl_extensionlessScriptIsNotWin32App() throws Exception {
        tempDir = Files.createTempDirectory("lspbare-sh");
        Path sh = tempDir.resolve("lspbare-sh");
        Files.writeString(sh, "#!/bin/sh\nexit 0\n", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> {
            ProcessBuilder pb = new ProcessBuilder(sh.toString(), "--stdio");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
            p.destroyForcibly();
        }).as("无扩展名 sh 脚本在 Windows 上不是有效 Win32 程序（error=193）——"
                + "这正是「把无扩展名文件当候选」的真实后果，证明必须只探测 PATHEXT 扩展名")
            .isInstanceOf(IOException.class);
    }

    /**
     * 核心用例：全程传<b>裸名</b>，PATH 经 env 注入。
     * 删掉 ProcessLspClient 里的 resolve 调用 ⇒ 本用例必红（error=2）。
     */
    @Test
    @DisplayName("核心：ProcessLspClient.start 传裸名 → 内部解析到 .cmd → 进程起来并完成 initialize")
    void bareName_startsServerThroughResolver() throws Exception {
        tempDir = Files.createTempDirectory("lspbare-ok");
        createForwardingCmd(tempDir, "lspbare");
        // 同目录再放一个无扩展名 sh 脚本，模拟真实 npm 布局：必须不被选中
        Files.writeString(tempDir.resolve("lspbare"), "#!/bin/sh\nexit 1\n", StandardCharsets.US_ASCII);

        // 关键：只把临时目录放进 PATH（父进程 PATH 追加在后，保证 lspbare 只能来自临时目录）
        String inheritedPath = System.getenv("PATH");
        String pathext = System.getenv("PATHEXT");
        Map<String, String> env = Map.of(
            "PATH", tempDir.toAbsolutePath() + java.io.File.pathSeparator + inheritedPath,
            "PATHEXT", (pathext == null || pathext.isBlank()) ? ".COM;.EXE;.BAT;.CMD" : pathext);

        client = new ProcessLspClient();
        // 传裸名 —— 解析必须发生在 start 内部（这里被变异删掉就会抛异常）
        client.start("lspbare", new String[0], env, null);

        // 进程真的起来了：initialize 必须是 got response，而不是超时/失败
        LspClient.LspInitializeResult result = client.initialize("file:///tmp/lspbare");

        assertThat(result).as("initialize 必须返回结果（进程真起来并完成 JSON-RPC 握手）").isNotNull();
        assertThat(result.serverInfo().name())
            .as("必须是 mock LSP server 响应，证明执行的是我们造的那个 .cmd 转发的 python")
            .isEqualTo("mock-lsp-server");
        assertThat(client.capabilities())
            .as("必须拿到 capabilities").isNotNull().containsKey("hoverProvider");
        assertThat(client.isInitialized()).isTrue();
    }

    @Test
    @DisplayName("回归：已是完整路径时行为不变（不做二次改写）")
    void fullPath_unchanged() throws Exception {
        tempDir = Files.createTempDirectory("lspbare-full");
        Path cmd = createForwardingCmd(tempDir, "lspbare");

        client = new ProcessLspClient();
        client.start(cmd.toAbsolutePath().toString(), new String[0], Map.of(), null);
        LspClient.LspInitializeResult result = client.initialize("file:///tmp/lspbare");

        assertThat(result).isNotNull();
        assertThat(result.serverInfo().name()).isEqualTo("mock-lsp-server");
        assertThat(cmd.getFileName().toString().toLowerCase(Locale.ROOT)).endsWith(".cmd");
    }
}
