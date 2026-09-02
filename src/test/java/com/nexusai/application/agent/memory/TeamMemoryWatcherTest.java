package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.team.TeamMemorySyncTypes.SyncState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-D-4] TeamMemoryWatcher ENTRY_CREATE 递归注册子树 · 对齐 CC
 * {@code fs.watch({recursive:true})}（Open-ClaudeCode/src/services/teamMemorySync/watcher.ts:179-181）。
 *
 * <p>WHY（规则九 · 测试验证意图）：CC fs.watch recursive 原生递归 —— {@code mkdir -p a/b} 时 b 在 a 注册
 * 前创建，事件仍被 FSEvents/inotify 捕获（watcher.ts:150-160 注释）。Java WatchService 非递归：旧实现
 * ENTRY_CREATE 仅注册该目录自身，b 在 a 注册前创建 → b 的事件依赖 WatchService 批次 catch-up（Windows
 * 上经常命中，Linux inotify 上丢失，探查 CM-D2 △-② / OPD-CM5-D-08）。修复 = ENTRY_CREATE 目录时递归
 * 注册其子树（{@code registerRecursive(child)}），不依赖竞态 catch-up。本测试锁定<b>意图</b>：mkdir -p
 * a/b 后 b 内的写入必须可靠触发 push（递归注册保证 b 被监听，事件不丢失）。
 */
@DisplayName("[IMP-D-4] TeamMemoryWatcher ENTRY_CREATE 递归注册子树")
class TeamMemoryWatcherTest {

    private final String prevUserDir = System.getProperty("user.dir");

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", prevUserDir);
    }

    @Test
    @DisplayName("mkdir -p a/b 竞态：b 内二次写入须触发 push（b 经 a 的子树递归注册被监听，OPD-CM5-D-08）")
    void entryCreate_recursiveSubtreeRegistration_capturesSecondNestedWrite(
            @TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY：mkdir -p a/b 一次性创建，b 在 a 注册前已存在。修复前 ENTRY_CREATE(a) 仅注册 a —— b 是否被
        // 监听依赖 WatchService 批次 catch-up（Windows 常命中、inotify 不命中，探查 CM-D2 △-②）。修复后
        // ENTRY_CREATE(a) 递归注册 a 的子树（a+b）→ b 被确定性监听 → b 内二次写入（v2）命中 b 的 WatchKey
        // → debounce push → PUT#2 携带 v2。断言 b 内二次写入必须触发 push = 锁定"子树被监听"这一意图。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        Path teamDir = Paths.get(paths.getTeamMemPath());
        Files.createDirectories(teamDir);
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            TeamMemoryWatcher watcher = new TeamMemoryWatcher(svc, paths, client(), stub::baseUrl);
            watcher.resetForTesting(SyncState.create());
            System.setProperty("user.dir", gitRepo.toString());
            watcher.startFileWatcher(teamDir.toString());
            try {
                // mkdir -p a/b + 首次写入 v1：b 在 a 注册前已创建（其事件已丢失）；a 的 ENTRY_CREATE
                // 处理时递归注册子树是 b 被监听的唯一路径（修复核心）
                Path nestedFile = teamDir.resolve("a").resolve("b").resolve("nested.md");
                Files.createDirectories(nestedFile.getParent());
                Files.writeString(nestedFile, "v1", StandardCharsets.UTF_8);
                // PUT#1：a 的 ENTRY_CREATE → debounce push（walkDir 读盘 → 上传 v1）。
                // 该同步点同时证明 a 的 ENTRY_CREATE 已被处理（修复下此时 a+b 均已注册）
                awaitPutCount(stub, 1);
                assertThat(stub.putBodies)
                    .as("首次写入（v1）应随 a 的 ENTRY_CREATE push 上传")
                    .anyMatch(b -> b.contains("\"v1\""));
                // 二次写入 v2：b 被确定性监听（IMP-D-4 递归注册）→ 命中 b 的 WatchKey → 触发 PUT#2。
                // 修复前 b 是否被监听依赖批次 catch-up（Windows 常命中 / inotify 丢失）—— 本断言锁定
                // "子树被监听"意图，使递归注册成为确定性保障而非碰运气
                Files.writeString(nestedFile, "v2", StandardCharsets.UTF_8);
                awaitPutCount(stub, 2);
                assertThat(stub.putBodies)
                    .as("mkdir -p a/b 后 b 内二次写入必须触发 push（IMP-D-4 递归注册保证 b 被监听）")
                    .anyMatch(b -> b.contains("\"v2\""));
            } finally {
                watcher.stopTeamMemoryWatcher();
            }
        }
    }

    /** 轮询等待 stub 收到第 n 次 PUT（debounce 2s + push 执行 + HTTP 往返，Windows WatchService 有投递延迟）。 */
    private static void awaitPutCount(StubServer stub, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (stub.putCount >= n) {
                return;
            }
            Thread.sleep(200);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 测试辅助（最小面，与 TeamMemorySyncTest 同构）
    // ────────────────────────────────────────────────────────────────

    /** 可切换响应的 HTTP 桩 · 记录请求方法 + 累积 PUT body（供断言 delta push 内容）。 */
    static class StubServer implements AutoCloseable {
        final HttpServer server;
        volatile int status = 200;
        volatile String body = "{}";
        volatile String requestMethod;
        volatile String requestBody;
        final List<String> putBodies = new ArrayList<>();
        volatile int putCount = 0;

        StubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange ex) throws IOException {
            requestMethod = ex.getRequestMethod();
            requestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("PUT".equals(requestMethod)) {
                putCount++;
                putBodies.add(requestBody);
            }
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** 带 github.com origin 的临时 git 仓库 · 驱动 getGithubRepo（读 .git/config remote origin url）。 */
    private static Path fakeGitRepo(Path root, String ownerRepo) throws IOException {
        Path gitDir = Files.createDirectories(root.resolve(".git"));
        String[] parts = ownerRepo.split("/");
        Files.writeString(gitDir.resolve("config"),
            "[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n"
            + "[remote \"origin\"]\n\turl = https://github.com/" + ownerRepo + ".git\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n");
        return root;
    }

    /** 门控全开 TeamMemPaths（与 TeamMemorySyncTest 等价；memoryBase 指向临时目录，绝不写真实 ~/.claude）。 */
    private static TeamMemPaths teamMemPaths(Path gitRepo, Path memoryBase) {
        AutoMemPaths auto = new AutoMemPaths(
            () -> gitRepo.toString(), () -> memoryBase.toString(), () -> null, () -> null);
        return new TeamMemPaths(auto, () -> true, () -> true, () -> true);
    }

    /** 非空 header 的 AuthHeaderProvider（通过 isAuthAvailable）。 */
    private static TeamMemoryHttpClient client() {
        return new TeamMemoryHttpClient(java.net.http.HttpClient.newHttpClient(),
            () -> Map.of("Authorization", "Bearer test"));
    }

    /** TeamMemorySyncService（push 需经 claudemdEngine.clearMemoryFileCaches()，用最小 ClaudemdEngine）。 */
    private static TeamMemorySyncService service(StubServer stub, Path gitRepo, Path memoryBase) {
        AutoMemPaths auto = new AutoMemPaths(
            () -> gitRepo.toString(), () -> memoryBase.toString(), () -> null, () -> null);
        ClaudemdEngine engine = new ClaudemdEngine(auto,
            new MemoryFileDetection(auto, () -> true, () -> true));
        return new TeamMemorySyncService(client(), teamMemPaths(gitRepo, memoryBase), engine);
    }
}
