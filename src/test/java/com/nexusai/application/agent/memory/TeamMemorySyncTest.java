package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.permission.hook.SessionFileAccessHooks;
import com.nexusai.application.agent.team.TeamMemorySyncTypes.SyncState;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [IMP-M-P1-4] Team Memory 全链重建定向测试 · 对齐 CC {@code teamMemorySync/index.ts} + watcher.ts +
 * teamMemSecretGuard.ts + teamMemPaths.ts。
 *
 * <p>WHY（规则九 · 测试验证意图）：旧实现 304 门控 push（304 → skip push，本地编辑丢失）、POST 嵌套
 * {@code content} body、{@code ?org=} 参数、删除集上传 —— 全链与 CC 语义相悖。本测试锁定 CC 语义：
 * PUT 顶层 {@code {entries}} + {@code ?repo=} + If-Match + 412 + 结构化 413、304 不门控 push、
 * 删除不传播、secret 跳过、路径安全（sanitizePathKey）。
 *
 * <p>用 {@link HttpServer}（JDK）作真实 HTTP 桩验证端点契约；用临时 git 仓库（.git/config 带
 * github.com origin）驱动 getGithubRepo 等价。
 */
@DisplayName("[IMP-M-P1-4] team-memory 全链对齐 CC")
class TeamMemorySyncTest {

    /** 可切换响应的 HTTP 桩 · 记录请求 + 按 status/body 响应。 */
    static class StubServer implements AutoCloseable {
        final HttpServer server;
        volatile int status = 200;
        volatile int putStatus = -1;  // <0 时 PUT 复用 status；否则 PUT 专用响应码（区分 GET/PUT 语义）
        /** 非 null 时 GET 请求用此 status（PUT 仍用 status）—— 冲突循环探针测试用。 */
        volatile Integer getStatus = null;
        volatile String body = "{}";
        volatile String etagHeader = null;
        volatile String requestMethod;
        volatile String requestPath;
        volatile String requestQuery;
        volatile String requestBody;
        volatile String ifMatch;
        volatile int putCount = 0;
        volatile String ifNoneMatch;
        volatile String userAgentHeader;

        StubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange ex) throws IOException {
            requestMethod = ex.getRequestMethod();
            requestPath = ex.getRequestURI().getPath();
            if ("PUT".equals(requestMethod)) {
                putCount++;
            }
            requestQuery = ex.getRequestURI().getQuery();
            requestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ifMatch = ex.getRequestHeaders().getFirst("If-Match");
            ifNoneMatch = ex.getRequestHeaders().getFirst("If-None-Match");
            userAgentHeader = ex.getRequestHeaders().getFirst("User-Agent");
            int effectiveStatus = ("PUT".equals(requestMethod) && putStatus >= 0) ? putStatus : status;
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            if (etagHeader != null) {
                ex.getResponseHeaders().set("ETag", etagHeader);
            }
            if ("GET".equals(requestMethod) && getStatus != null) {
                effectiveStatus = getStatus;
            }
            ex.sendResponseHeaders(effectiveStatus, resp.length);
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

    /** 临时基址 AutoMemPaths（memoryBase 指向临时目录，team 目录落在 temp 下，绝不写真实 ~/.claude）。 */
    private static AutoMemPaths autoMemPaths(Path gitRepo, Path memoryBase) {
        return new AutoMemPaths(
            () -> gitRepo.toString(), () -> memoryBase.toString(), () -> null, () -> null);
    }

    /** 门控全开的 TeamMemPaths（测试驱动全链 · IMP-CM-09 双门控：feature+runtime 双开）。 */
    private static TeamMemPaths teamMemPaths(Path gitRepo, Path memoryBase) {
        return new TeamMemPaths(autoMemPaths(gitRepo, memoryBase), () -> true, () -> true, () -> true);
    }

    /** 非空 header 的 AuthHeaderProvider（通过 isAuthAvailable）。 */
    private static TeamMemoryHttpClient client() {
        return new TeamMemoryHttpClient(HttpClient.newHttpClient(),
            () -> Map.of("Authorization", "Bearer test"));
    }

    private final String prevUserDir = System.getProperty("user.dir");

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", prevUserDir);
    }

    // ────────────────────────────────────────────────────────────────
    // PUT 契约 · CC index.ts:462-553
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT 顶层 {entries} + ?repo= 单参数 + If-Match 剥引号 + 200 更新 lastKnownChecksum")
    void upload_topLevelEntries_repoParam_ifMatch(@TempDir Path tmp) throws Exception {
        // WHY: 旧实现 POST + 嵌套 {organizationId,repo,content:{entries}} + ?org=&repo= —— 与 CC
        // 端点契约相悖（index.ts:485-487 PUT 顶层 {entries}；:163-167 仅 ?repo=）。对齐后请求必须
        // 是 PUT + 顶层 entries + 仅 repo 参数。
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:resp\"}";
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            TeamMemoryHttpClient.UploadResult result =
                client.upload(state, stub.baseUrl(), "acme/demo",
                    Map.of("MEMORY.md", "hello"), "sha256:etag");

            assertThat(result.success()).isTrue();
            assertThat(result.checksum()).isEqualTo("sha256:resp");
            assertThat(state.lastKnownChecksum).isEqualTo("sha256:resp");
            assertThat(stub.requestMethod).isEqualTo("PUT");
            // 仅 ?repo= 参数（client 用 encodeURIComponent 把 / 编码为 %2F；JDK HttpServer 暴露时已解码），无 ?org=
            assertThat(stub.requestQuery).startsWith("repo=acme/demo");
            assertThat(stub.requestQuery).doesNotContain("org=");
            // 顶层 {entries}
            assertThat(stub.requestBody).isEqualTo("{\"entries\":{\"MEMORY.md\":\"hello\"}}");
            // If-Match 带引号包裹（剥内部引号）
            assertThat(stub.ifMatch).isEqualTo("\"sha256:etag\"");
        }
    }

    @Test
    @DisplayName("PUT 200 → UploadResult.lastModified 从响应体解析（CC index.ts:514 response.data?.lastModified）")
    void upload_parsesLastModified(@TempDir Path tmp) throws Exception {
        // WHY: 旧实现 UploadResult.ok(checksum, null) —— lastModified 恒 null（verify-report 缺口 3）。
        // CC uploadTeamMemory 200 分支 :514 `lastModified: response.data?.lastModified` 解析响应体字段。
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:resp\",\"lastModified\":\"2026-08-06T08:00:00Z\"}";
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            TeamMemoryHttpClient.UploadResult result =
                client.upload(state, stub.baseUrl(), "acme/demo",
                    Map.of("MEMORY.md", "hello"), "sha256:etag");

            assertThat(result.success()).isTrue();
            assertThat(result.checksum()).isEqualTo("sha256:resp");
            assertThat(result.lastModified()).isEqualTo("2026-08-06T08:00:00Z");
        }
    }

    @Test
    @DisplayName("PUT 412 → conflict=true；结构化 413 → 解析 error_code/max_entries/received_entries")
    void upload_412Conflict_andStructured413(@TempDir Path tmp) throws Exception {
        try (StubServer stub = new StubServer()) {
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            // 412 → conflict
            stub.status = 412;
            stub.body = "{}";
            TeamMemoryHttpClient.UploadResult conflict =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(conflict.success()).isFalse();
            assertThat(conflict.conflict()).isTrue();
            assertThat(conflict.error()).isEqualTo("ETag mismatch");

            // 结构化 413（anthropic/anthropic#293258）→ error_code + max_entries + received_entries
            stub.status = 413;
            stub.body = "{\"error\":{\"details\":{"
                + "\"error_code\":\"team_memory_too_many_entries\","
                + "\"max_entries\":5,\"received_entries\":7}}}";
            TeamMemoryHttpClient.UploadResult tooMany =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(tooMany.success()).isFalse();
            assertThat(tooMany.serverErrorCode()).isEqualTo("team_memory_too_many_entries");
            assertThat(tooMany.serverMaxEntries()).isEqualTo(5);
            assertThat(tooMany.serverReceivedEntries()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("结构化 413 严格化：error_code 非 literal / max|received 非正整数（0/负/字符串/小数）→ 三项全不缓存")
    void upload_413_malformedRejected(@TempDir Path tmp) throws Exception {
        // WHY（IMP-CM-10 · CM-D1 △-2）：旧实现 canConvertToInt 接受任意 error_code + 0/负 max_entries →
        // 畸形值被缓存为 serverMaxEntries → 下次 push 按 0 裁剪到全空。CC TeamMemoryTooManyEntriesSchema
        // （types.ts:47-57）z.literal('team_memory_too_many_entries') + z.number().int().positive() 会拒。
        // 测试锁定：error_code 非该 literal 或 max/received 非正整数 → 三项全 null（不缓存畸形值）。
        try (StubServer stub = new StubServer()) {
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            // ① error_code 非 'team_memory_too_many_entries' → safeParse 失败 → 全 null
            stub.status = 413;
            stub.body = "{\"error\":{\"details\":{"
                + "\"error_code\":\"entry_too_large\","
                + "\"max_entries\":5,\"received_entries\":7}}}";
            TeamMemoryHttpClient.UploadResult wrongCode =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(wrongCode.serverErrorCode()).as("error_code 非该 literal → 不缓存").isNull();
            assertThat(wrongCode.serverMaxEntries()).isNull();
            assertThat(wrongCode.serverReceivedEntries()).isNull();

            // ② max_entries=0 → 全 null（z.positive() 拒）
            stub.body = "{\"error\":{\"details\":{"
                + "\"error_code\":\"team_memory_too_many_entries\","
                + "\"max_entries\":0,\"received_entries\":7}}}";
            TeamMemoryHttpClient.UploadResult zeroMax =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(zeroMax.serverMaxEntries()).as("max_entries=0（z.positive 拒）→ 不缓存").isNull();

            // ③ received_entries 为负数 → 全 null
            stub.body = "{\"error\":{\"details\":{"
                + "\"error_code\":\"team_memory_too_many_entries\","
                + "\"max_entries\":5,\"received_entries\":-3}}}";
            TeamMemoryHttpClient.UploadResult negRecv =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(negRecv.serverReceivedEntries()).as("received_entries=-3（z.positive 拒）→ 不缓存").isNull();

            // ④ max_entries 为字符串（z.number 拒字符串）→ 全 null
            stub.body = "{\"error\":{\"details\":{"
                + "\"error_code\":\"team_memory_too_many_entries\","
                + "\"max_entries\":\"5\",\"received_entries\":7}}}";
            TeamMemoryHttpClient.UploadResult stringMax =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(stringMax.serverMaxEntries()).as("max_entries 字符串（z.number 拒）→ 不缓存").isNull();

            // ⑤ max_entries 小数（z.int 拒）→ 全 null
            stub.body = "{\"error\":{\"details\":{"
                + "\"error_code\":\"team_memory_too_many_entries\","
                + "\"max_entries\":5.5,\"received_entries\":7}}}";
            TeamMemoryHttpClient.UploadResult fracMax =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(fracMax.serverMaxEntries()).as("max_entries=5.5（z.int 拒）→ 不缓存").isNull();

            // ⑥ 正控制：合法 literal + 正整数 → 缓存（与 upload_412Conflict_andStructured413 一致）
            stub.body = "{\"error\":{\"details\":{"
                + "\"error_code\":\"team_memory_too_many_entries\","
                + "\"max_entries\":5,\"received_entries\":7}}}";
            TeamMemoryHttpClient.UploadResult valid =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(valid.serverErrorCode()).isEqualTo("team_memory_too_many_entries");
            assertThat(valid.serverMaxEntries()).isEqualTo(5);
            assertThat(valid.serverReceivedEntries()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("batch 字节估算对齐 JSON.stringify：转义字符逐字节精确（IMP-CM-10 · CM-D1 △-1）")
    void batch_jsonBytes_escapeByteCounts() {
        // WHY（IMP-CM-10）：旧实现 jsonBytes 对 0x00-0x07/0x0B/0x0E-0x1F 仅 +1 → 少算 4B/字符 →
        // batch 边界偏移（极端内容下潜在 gateway 413）。对齐 CC Buffer.byteLength(JSON.stringify(s))
        // （index.ts:436-439，jsonStringify ≡ JSON.stringify，utils/slowOperations.ts:170-194）。
        // 期望值以 node Buffer.byteLength(JSON.stringify(s)) 实测为准（2026-08-15）。
        assertThat(TeamMemorySyncService.jsonBytes("")).as("空串 = 一对引号 2B").hasSize(2);
        assertThat(TeamMemorySyncService.jsonBytes("a")).as("a = \"a\" 3B").hasSize(3);
        assertThat(TeamMemorySyncService.jsonBytes("a\nb")).as("\\n 2B 转义 → 6B").hasSize(6);
        assertThat(TeamMemorySyncService.jsonBytes("ab")).as("u0001 6B 转义 → 10B（旧实现 6B 少算 4B）").hasSize(10);
        assertThat(TeamMemorySyncService.jsonBytes("a\tb")).as("\\t 2B 转义 → 6B").hasSize(6);
        assertThat(TeamMemorySyncService.jsonBytes("a\bb")).as("\\b 2B 转义 → 6B").hasSize(6);
        assertThat(TeamMemorySyncService.jsonBytes("a\fb")).as("\\f 2B 转义 → 6B").hasSize(6);
        assertThat(TeamMemorySyncService.jsonBytes("a\rb")).as("\\r 2B 转义 → 6B").hasSize(6);
        assertThat(TeamMemorySyncService.jsonBytes("a\"b")).as("\\\" 2B 转义 → 6B").hasSize(6);
        assertThat(TeamMemorySyncService.jsonBytes("a\\b")).as("\\\\ 2B 转义 → 6B").hasSize(6);
        assertThat(TeamMemorySyncService.jsonBytes("中")).as("中文不转义 3B UTF-8 + 2 引号 → 5B").hasSize(5);
    }

    @Test
    @DisplayName("secret 扫描 \\s 对齐 ECMAScript 空白集：NBSP/LS/FEFF 边界命中、NEL 不命中，\\w 保持 ASCII（IMP-MV2-01 · B3 △-2）")
    void secretScanner_unicodeWhitespaceBoundary() {
        // WHY（IMP-MV2-01 · B3 △-2）：CC JS \\s = ECMAScript WhiteSpace ∪ LineTerminator（含 U+FEFF、
        // 不含 U+0085 NEL）；Java (?U:\\s) = Unicode White_Space 属性恰好相反（NEL 误检/FEFF 漏检，B3
        // EV-15/16 双端实测矩阵）→ 字符类改为显式 ECMAScript 集合（TeamMemorySecretScanner#ECMA_WS）。
        // 尾边界 $ 同步 JS 化：JS $（无 m）仅匹配输入末尾，不匹配最终 \\n/\\r/U+2028/U+2029 前
        // （ECMA-262 §22.2.2.2 Multiline=false 仅 e=InputLength；双引擎实测）→ Java $ 认 U+0085 行终止
        // 导致的 NEL 尾误检须以零宽断言 (?=\\z|[\\n\\r\\u2028\\u2029]\\z) 替代（中间态实测仅换字符类
        // 仍命中）；该断言比 JS $ 宽但可观察等价（终止符位置被字符类分支先行命中，77/77 矩阵；更忠实
        // 移植 (?=\\z) 亦等价）。\\w/\\d 保持 ASCII（与 JS 一致，不加全局旗标）。
        String nbsp = new String(Character.toChars(0x00A0));
        String ls = new String(Character.toChars(0x2028));
        String nel = new String(Character.toChars(0x0085));
        String feff = new String(Character.toChars(0xFEFF));

        // gcp-api-key = \\b(AIza[\\w-]{35}) + BOUNDARY —— NBSP/行分隔符是 JS \\s 边界
        String secret = "AIza" + "A".repeat(35);
        assertThat(TeamMemorySecretScanner.scanForSecrets(secret + nbsp))
            .as("NBSP 边界必须命中 gcp-api-key（JS \\s 含 U+00A0；旧实现漏检）")
            .contains(new TeamMemorySecretScanner.SecretMatch("gcp-api-key", "GCP API Key"));
        assertThat(TeamMemorySecretScanner.scanForSecrets(secret + ls))
            .as("行分隔符边界必须命中 gcp-api-key")
            .contains(new TeamMemorySecretScanner.SecretMatch("gcp-api-key", "GCP API Key"));
        // NEL/FEFF 双向边界（B3 EV-15/16）：ECMAScript WhiteSpace 含 FEFF 不含 NEL（Java (?U:\\s) 相反）
        assertThat(TeamMemorySecretScanner.scanForSecrets(secret + feff))
            .as("FEFF 边界必须命中 gcp-api-key（JS \\s 含 U+FEFF；旧实现漏检，上传泄露方向收敛）")
            .contains(new TeamMemorySecretScanner.SecretMatch("gcp-api-key", "GCP API Key"));
        assertThat(TeamMemorySecretScanner.scanForSecrets(secret + nel))
            .as("NEL 边界不命中（JS \\s 不含 U+0085 且 JS $ 不认 NEL；旧实现误检）").isEmpty();
        // 尾边界 $ 语义回归：裸串尾与最终 \\n 前仍命中（JS $ 语义保持）
        assertThat(TeamMemorySecretScanner.scanForSecrets(secret))
            .as("裸串尾必须命中 gcp-api-key（$ 匹配输入末尾）")
            .contains(new TeamMemorySecretScanner.SecretMatch("gcp-api-key", "GCP API Key"));
        assertThat(TeamMemorySecretScanner.scanForSecrets(secret + "\n"))
            .as("行尾 \\n 前必须命中（JS \\s 含 \\n；Java 经 ECMA_WS 类分支命中，非 $）")
            .contains(new TeamMemorySecretScanner.SecretMatch("gcp-api-key", "GCP API Key"));
        // 非空白边界 → 不命中（回归，CC 语义：仅空白/;引号/换行/串尾作边界）
        assertThat(TeamMemorySecretScanner.scanForSecrets(secret + "x"))
            .as("非空白边界不命中").isEmpty();

        // azure-ad-client-secret（第二处 \\s 字符类 + $ 修改点）同集合语义
        String azureSecret = "abc1Q~" + "x".repeat(32);
        assertThat(TeamMemorySecretScanner.scanForSecrets(azureSecret + feff))
            .as("azure FEFF 后置边界必须命中（ECMAScript \\s 含 U+FEFF）")
            .contains(new TeamMemorySecretScanner.SecretMatch("azure-ad-client-secret", "Azure AD Client Secret"));
        assertThat(TeamMemorySecretScanner.scanForSecrets(feff + azureSecret))
            .as("azure FEFF 前置边界必须命中")
            .contains(new TeamMemorySecretScanner.SecretMatch("azure-ad-client-secret", "Azure AD Client Secret"));
        assertThat(TeamMemorySecretScanner.scanForSecrets(azureSecret + nel))
            .as("azure NEL 后置边界不命中（JS \\s 不含 U+0085）").isEmpty();

        // \\w 保持 ASCII（JS \\w=[A-Za-z0-9_]）：CJK 内容不命中 github-fine-grained-pat
        // （守护：不加全局 UNICODE_CHARACTER_CLASS，防 \\w/\\d 过度放宽）
        assertThat(TeamMemorySecretScanner.scanForSecrets("github_pat_" + "中".repeat(82)))
            .as("\\w 保持 ASCII：CJK 字符不命中").isEmpty();
    }
    @Test
    @DisplayName("getSecretLabel 公共 API：规则集 ID → 人类可读 label（specialCase + capitalize · secretScanner.ts:243-268/:301-303）")
    void secretScanner_getSecretLabelPublicApi() {
        // WHY（2026-08-16 补盲 · MM-B3 §14-4）：getSecretLabel 是公共 API（secretScanner.ts:301-303
        // export），此前全测试目录 0 覆盖（EV-20）。label 由 ruleIdToLabel 生成：kebab 拆分 → specialCase
        // 17 项优先（aws/gcp/api/pat/ad/tf/oauth/npm/pypi/jwt/github/gitlab/openai/digitalocean/
        // huggingface/hashicorp/sendgrid，:245-263）→ 否则 capitalize → 空格 join（:264-267）。
        // 36 条 ruleId 全小写 → 两端输出一致（capitalize 对纯小写输入无差异，MM-B3 △-1 仅「含大写输入」
        // 可分叉——本测试用规则集内小写 id，不钻该角例，角例由 IMP-MV2-02 capitalize 定夺后处理）。
        assertThat(TeamMemorySecretScanner.getSecretLabel("gcp-api-key")).isEqualTo("GCP API Key");
        assertThat(TeamMemorySecretScanner.getSecretLabel("github-pat")).isEqualTo("GitHub PAT");
        assertThat(TeamMemorySecretScanner.getSecretLabel("anthropic-api-key")).isEqualTo("Anthropic API Key");
        assertThat(TeamMemorySecretScanner.getSecretLabel("slack-app-token")).isEqualTo("Slack App Token");
        assertThat(TeamMemorySecretScanner.getSecretLabel("private-key")).isEqualTo("Private Key");
        assertThat(TeamMemorySecretScanner.getSecretLabel("digitalocean-access-token"))
            .isEqualTo("DigitalOcean Access Token");
        assertThat(TeamMemorySecretScanner.getSecretLabel("hashicorp-tf-api-token"))
            .isEqualTo("HashiCorp TF API Token");
        assertThat(TeamMemorySecretScanner.getSecretLabel("sendgrid-api-token"))
            .isEqualTo("SendGrid API Token");
        // 未知 id → title 转换回退（全小写输入两端一致）
        assertThat(TeamMemorySecretScanner.getSecretLabel("foo-bar")).isEqualTo("Foo Bar");
    }

    @Test
    @DisplayName("redactSecrets 仅替换捕获组 span：边界字符存活、secret 值不回显（secretScanner.ts:310-324）")
    void secretScanner_redactSecrets_captureGroupOnly() {
        // WHY（补盲 · MM-B3 §14-4）：redactSecrets 替换语义 = CC :316-322 match.replace(g1,'[REDACTED]')
        // —— 仅捕获组 1 的 span 变 [REDACTED]，模式中的边界字符（空格/引号/;）必须存活
        // （:319-321 回调）；secret 值绝不回显（PSR M22174）。
        // gcp-api-key = \b(AIza[\w-]{35}) + BOUNDARY(?:[\x60'"\s;]|\\[nr]|$) —— 捕获组 + 边界 `;`
        String secret = "AIza" + "A".repeat(35);
        String redacted = TeamMemorySecretScanner.redactSecrets("key " + secret + "; rest");

        assertThat(redacted)
            .as("捕获组 span 替换为 [REDACTED]、边界 ';' 存活")
            .isEqualTo("key [REDACTED]; rest");
        assertThat(redacted)
            .as("secret 值绝不回显")
            .doesNotContain("AIza");
        // 无命中 → 原样返回（幂等）；空内容 → 原样
        assertThat(TeamMemorySecretScanner.redactSecrets("plain text without secrets"))
            .isEqualTo("plain text without secrets");
        assertThat(TeamMemorySecretScanner.redactSecrets("")).isEmpty();
    }

    @Test
    @DisplayName("redactSecrets 无捕获组规则整体替换 + 全局替换 + 大小写 flags（secretScanner.ts:312-324/:133-135）")
    void secretScanner_redactSecrets_globalAndNoCaptureGroup() {
        // WHY（补盲 · MM-B3 §14-4）：①无捕获组规则（github-pat）→ 整个 match 替换（CC :319-321 回调
        // g1 为 offset number 判定无组 → 整体 [REDACTED]）；②强制 g flag → 全部出现替换（:313-314）；
        // ③slack-app-token 带 'i'（:135）→ 大小写不敏感命中。
        String token = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        assertThat(TeamMemorySecretScanner.redactSecrets("a " + token + " b"))
            .as("无捕获组 → 整个 token 替换")
            .isEqualTo("a [REDACTED] b");
        assertThat(TeamMemorySecretScanner.redactSecrets(token + " " + token))
            .as("全局替换：两处同时替换")
            .isEqualTo("[REDACTED] [REDACTED]");
        assertThat(TeamMemorySecretScanner.redactSecrets("XAPP-1-ABCDEFG12-42-abcdefg"))
            .as("slack-app-token 带 i flag → 大写输入同样命中替换")
            .isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("getSecretLabel capitalize 对齐 CC：首字母大写其余保留（IMP-MV2-02 · B3 △-1）")
    void secretScanner_getSecretLabel_capitalizePreservesCase() {
        // WHY（IMP-MV2-02 · B3 △-1）：CC stringUtils.ts:20-22 capitalize = charAt(0).toUpperCase() +
        // slice(1) —— 首字母大写，其余原样保留（JSDoc 明示：Unlike lodash capitalize, this does NOT
        // lowercase the remaining characters）。旧 Java 实现其余 toLowerCase(Locale.ROOT) 是偏差
        // （javadoc :223 失实）→ 已对齐。36 个 ruleId 全小写 kebab-case → 规则集内 label 输出不变
        // （下方 specialCase/纯小写回归）；公共 API getSecretLabel 对含大写输入行为对齐 CC。
        assertThat(TeamMemorySecretScanner.getSecretLabel("MyToken"))
            .as("首字母大写其余保留（CC stringUtils.ts:20-22；旧实现 \"Mytoken\" 是偏差）")
            .isEqualTo("MyToken");
        assertThat(TeamMemorySecretScanner.getSecretLabel("fooBar"))
            .as("camelCase 内部大写不丢失（CC capitalize('fooBar') → 'FooBar'）").isEqualTo("FooBar");
        assertThat(TeamMemorySecretScanner.getSecretLabel("hello world"))
            .as("仅首字符大写、其余保留（CC capitalize('hello world') → 'Hello world'）")
            .isEqualTo("Hello world");
        // 规则集内回归：specialCase 17 项优先 + 纯小写段 capitalize 路径与对齐前一致
        assertThat(TeamMemorySecretScanner.getSecretLabel("github-pat"))
            .as("specialCase 优先（GitHub）").isEqualTo("GitHub PAT");
        assertThat(TeamMemorySecretScanner.getSecretLabel("aws-access-token"))
            .as("specialCase + 小写段 capitalize 规则集内输出不变").isEqualTo("AWS Access Token");
        assertThat(TeamMemorySecretScanner.getSecretLabel("foo-bar-baz"))
            .as("纯小写段 capitalize 规则集内行为不变").isEqualTo("Foo Bar Baz");
    }

    @Test
    @DisplayName("GET fetch：304 → notModified；404 → isEmpty + 清 lastKnownChecksum；200 → checksum 取 body/ETag")
    void fetch_304_404_200(@TempDir Path tmp) throws Exception {
        try (StubServer stub = new StubServer()) {
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();
            state.lastKnownChecksum = "sha256:prev";

            // 304 → notModified（If-None-Match 剥引号后带引号包裹）
            stub.status = 304;
            stub.body = "";
            TeamMemoryHttpClient.FetchResult notModified =
                client.fetchOnce(state, stub.baseUrl(), "acme/demo", "\"sha256:prev\"");
            assertThat(notModified.success()).isTrue();
            assertThat(notModified.notModified()).isTrue();
            assertThat(stub.ifNoneMatch).isEqualTo("\"sha256:prev\"");

            // 404 → isEmpty + 清 lastKnownChecksum
            stub.status = 404;
            stub.body = "";
            TeamMemoryHttpClient.FetchResult empty =
                client.fetchOnce(state, stub.baseUrl(), "acme/demo", "sha256:prev");
            assertThat(empty.success()).isTrue();
            assertThat(empty.isEmpty()).isTrue();
            assertThat(state.lastKnownChecksum).isNull();

            // 200 → checksum 取 body.checksum（无则 ETag 剥引号）
            stub.status = 200;
            stub.body = "{\"organizationId\":\"o\",\"repo\":\"acme/demo\",\"version\":1,"
                + "\"lastModified\":\"2026-01-01T00:00:00Z\",\"checksum\":\"sha256:body\","
                + "\"content\":{\"entries\":{\"MEMORY.md\":\"hi\"},"
                + "\"entryChecksums\":{\"MEMORY.md\":\"sha256:abc\"}}}";
            stub.etagHeader = "\"etag-header\"";
            TeamMemoryHttpClient.FetchResult ok =
                client.fetchOnce(state, stub.baseUrl(), "acme/demo", null);
            assertThat(ok.success()).isTrue();
            assertThat(ok.isEmpty()).isFalse();
            assertThat(ok.checksum()).isEqualTo("sha256:body");
            assertThat(state.lastKnownChecksum).isEqualTo("sha256:body");
            assertThat(ok.data().content().entries()).containsEntry("MEMORY.md", "hi");
        }
    }

    @Test
    @DisplayName("pull 写盘后调用 clearMemoryFileCaches（CC index.ts:851-855 filesWritten>0 → clear）")
    void pull_clearsMemoryFileCachesAfterWrite(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: verify-report 缺口 1 —— 旧实现 pull 写盘后从不失效 getMemoryFiles memoize 缓存，
        // claudemd 读到陈旧 team memory。CC index.ts:852-855 明确 `if (filesWritten > 0) clearMemoryFileCaches()`。
        fakeGitRepo(gitRepo, "acme/demo");
        String serverBody = "{\"organizationId\":\"o\",\"repo\":\"acme/demo\",\"version\":1,"
            + "\"lastModified\":\"2026-01-01T00:00:00Z\",\"checksum\":\"sha256:server\","
            + "\"content\":{\"entries\":{\"MEMORY.md\":\"fresh\"},"
            + "\"entryChecksums\":{\"MEMORY.md\":\"" + TeamMemoryDelta.hashContent("fresh") + "\"}}}";
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = serverBody;
            TrackingClaudemdEngine engine = new TrackingClaudemdEngine(gitRepo, memoryBase);
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase, engine);
            SyncState state = SyncState.create();
            System.setProperty("user.dir", gitRepo.toString());

            TeamMemorySyncService.PullResult pull = svc.pullTeamMemory(state, stub.baseUrl(), false);
            assertThat(pull.success()).isTrue();
            assertThat(pull.filesWritten()).isEqualTo(1);
            // filesWritten>0 → clearMemoryFileCaches 必须被调（缓存失效接线）
            assertThat(engine.clearCalls).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("GET ?view=hashes 探针：缺 entryChecksums → probe 失败")
    void fetchHashes_missingEntryChecksums(@TempDir Path tmp) throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:c\",\"version\":2}";  // 无 entryChecksums
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();
            TeamMemoryHttpClient.HashesResult result =
                client.fetchHashes(state, stub.baseUrl(), "acme/demo");
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("entryChecksums");
            assertThat(stub.requestQuery).endsWith("view=hashes");
        }
    }

    @Test
    @DisplayName("GET ?view=hashes 3xx → error(unknown, httpStatus) 不落 readTree（CC validateStatus 200||404 拒绝 3xx）")
    void fetchHashes_3xxRejected(@TempDir Path tmp) throws Exception {
        // WHY: CC fetchTeamMemoryHashes validateStatus 仅接受 200/404（index.ts:330）—— 3xx 触发 axios
        // 拒绝 → classifyAxiosError 'http' → errorType 'unknown' + httpStatus（:346-352）。Java 若漏 3xx
        // 拦截会落入 readTree（非 JSON HTML 抛 parse），错误语义漂移。3xx 必须显式转 error(unknown, code)。
        try (StubServer stub = new StubServer()) {
            stub.status = 302;
            stub.body = "<html><body>redirect</body></html>";  // 非 JSON —— 若落 readTree 会抛 parse
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();
            TeamMemoryHttpClient.HashesResult result =
                client.fetchHashes(state, stub.baseUrl(), "acme/demo");
            assertThat(result.success()).isFalse();
            assertThat(result.errorType()).isEqualTo("unknown");
            assertThat(result.httpStatus()).isEqualTo(302);
            assertThat(result.entryChecksums()).isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 服务级 sync · 304 不门控 push / 删除不传播 / secret 跳过
    // ────────────────────────────────────────────────────────────────

    /** 记录 clearMemoryFileCaches 调用次数的 ClaudemdEngine 追踪器（验证 pull 写盘后缓存失效接线）。 */
    static class TrackingClaudemdEngine extends ClaudemdEngine {
        volatile int clearCalls = 0;

        TrackingClaudemdEngine(Path gitRepo, Path memoryBase) {
            super(new AutoMemPaths(() -> gitRepo.toString(), () -> memoryBase.toString(), () -> null, () -> null),
                new MemoryFileDetection(new AutoMemPaths(
                    () -> gitRepo.toString(), () -> memoryBase.toString(), () -> null, () -> null),
                    () -> true, () -> true));
        }

        @Override
        public void clearMemoryFileCaches() {
            clearCalls++;
            super.clearMemoryFileCaches();
        }
    }

    private TeamMemorySyncService service(StubServer stub, Path gitRepo, Path memoryBase,
                                          TrackingClaudemdEngine engine) {
        return new TeamMemorySyncService(client(), teamMemPaths(gitRepo, memoryBase), engine);
    }

    private TeamMemorySyncService service(StubServer stub, Path gitRepo, Path memoryBase) {
        return new TeamMemorySyncService(client(), teamMemPaths(gitRepo, memoryBase),
            new TrackingClaudemdEngine(gitRepo, memoryBase));
    }

    private TeamMemorySyncService service(StubServer stub, Path gitRepo, Path memoryBase,
                                          Telemetry telemetry) {
        TeamMemorySyncService svc = new TeamMemorySyncService(client(),
            teamMemPaths(gitRepo, memoryBase), new TrackingClaudemdEngine(gitRepo, memoryBase));
        svc.setTelemetry(telemetry);
        return svc;
    }

    /** 记录发射事件名的 Telemetry 假实现（参照 SessionFileAccessHooksTest.RecordingTelemetry 模式）。 */
    static final class RecordingTelemetry extends Telemetry {
        final List<String> events = new java.util.ArrayList<>();

        @Override
        public void recordEvent(String eventName, java.util.Map<String, Object> attributes) {
            events.add(eventName);
            super.recordEvent(eventName, attributes);
        }
    }

    /** 写本地 team 文件（服务端 pull 落到磁盘的等价物）。 */
    private void writeLocalTeamFile(TeamMemPaths paths, String relKey, String content) throws IOException {
        Path f = Paths.get(paths.getTeamMemPath()).resolve(relKey);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("sync 304 不门控 push：fetch 304 后本地编辑仍被 PUT（旧实现 304→skip push 丢失编辑，INV-10）")
    void sync_304DoesNotGatePush(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: 旧实现 fetch 304 → 直接 skip push，本地编辑永远不会上传（数据丢失）。CC 中 push 独立于
        // fetch 的 ETag（index.ts:19-19 删除不传播 + :1153-1191 sync pull→push），304 只影响 pull 的盘写。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        // 本地有一条编辑（pull 前就存在）
        writeLocalTeamFile(paths, "MEMORY.md", "local edit");
        try (StubServer stub = new StubServer()) {
            stub.status = 304;      // GET 恒 304（not modified）
            stub.putStatus = 200;   // PUT 须 200：CC validateStatus 仅接受 200/412（index.ts:491）—— PUT 返 3xx 是上传失败
            stub.body = "";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            SyncState state = SyncState.create();
            System.setProperty("user.dir", gitRepo.toString());

            TeamMemorySyncService.SyncResult result = svc.syncTeamMemory(state, stub.baseUrl());

            // push 必须仍发生：PUT 请求出现，且 body 含本地编辑（304 不吞编辑）
            assertThat(result.success()).isTrue();
            assertThat(stub.requestMethod).isEqualTo("PUT");
            assertThat(stub.requestBody).contains("local edit");
        }
    }

    @Test
    @DisplayName("删除不传播：本地删除的文件不上传，下次 pull 会恢复（CC index.ts:18-19）")
    void push_localDeletionDoesNotPropagate(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: 旧删除集计算方法会上传「local 无、remote 有」的删除集；CC 明确文件删除不传播
        // （删除本地文件不会从服务端移除，下次 pull 恢复）。push 只上传 local 存在的 key 的 delta。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        // 服务端有 MEMORY.md + OLD.md
        String serverBody = "{\"organizationId\":\"o\",\"repo\":\"acme/demo\",\"version\":1,"
            + "\"lastModified\":\"2026-01-01T00:00:00Z\",\"checksum\":\"sha256:server\","
            + "\"content\":{\"entries\":{\"MEMORY.md\":\"a\",\"OLD.md\":\"b\"},"
            + "\"entryChecksums\":{\"MEMORY.md\":\"" + TeamMemoryDelta.hashContent("a")
            + "\",\"OLD.md\":\"" + TeamMemoryDelta.hashContent("b") + "\"}}}";
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = serverBody;
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            SyncState state = SyncState.create();
            System.setProperty("user.dir", gitRepo.toString());

            // pull 把服务端两条写到盘
            TeamMemorySyncService.PullResult pull = svc.pullTeamMemory(state, stub.baseUrl(), false);
            assertThat(pull.success()).isTrue();
            assertThat(pull.filesWritten()).isEqualTo(2);

            // 本地删除 OLD.md + 编辑 MEMORY.md → push 只应上传 MEMORY.md，绝不上传 OLD.md
            Files.deleteIfExists(Paths.get(paths.getTeamMemPath()).resolve("OLD.md"));
            Files.writeString(Paths.get(paths.getTeamMemPath()).resolve("MEMORY.md"), "a2",
                StandardCharsets.UTF_8);

            // 服务端接下来返回 200（上传成功）
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";

            TeamMemoryHttpClient.PushResult push = svc.pushTeamMemory(state, stub.baseUrl());
            assertThat(push.success()).isTrue();
            assertThat(stub.requestBody).contains("MEMORY.md");
            assertThat(stub.requestBody).contains("\"a2\"");
            assertThat(stub.requestBody).doesNotContain("OLD.md");
        }
    }

    @Test
    @DisplayName("secret 跳过：含 gitleaks 命中的文件不上传，只记 ruleId/label 不记值")
    void push_secretFilesSkipped(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: PSR M22174 —— secret 值永不离开机器；命中规则的文件整文件跳过（只记 ruleId + label）。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        writeLocalTeamFile(paths, "CLEAN.md", "clean content");
        writeLocalTeamFile(paths, "SECRET.md", "token is ghp_012345678901234567890123456789012345"); // github-pat
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            SyncState state = SyncState.create();
            System.setProperty("user.dir", gitRepo.toString());

            // 本地读取：SECRET.md 被 secret 扫描命中并跳过（PSR M22174，只记 ruleId+label 不记值）
            TeamMemorySyncService.LocalRead read = svc.readLocalTeamMemory(null);
            assertThat(read.entries()).containsOnlyKeys("CLEAN.md");
            assertThat(read.skippedSecrets()).anyMatch(s -> "SECRET.md".equals(s.path()));
            assertThat(read.skippedSecrets())
                .allMatch(s -> !s.ruleId().isEmpty() && !s.label().isEmpty());

            TeamMemoryHttpClient.PushResult push = svc.pushTeamMemory(state, stub.baseUrl());
            assertThat(push.success()).isTrue();
            // 仅 CLEAN.md 上传；SECRET.md 及 secret 值绝不离开机器
            assertThat(stub.requestBody).contains("CLEAN.md");
            assertThat(stub.requestBody).doesNotContain("SECRET.md");
            assertThat(stub.requestBody).doesNotContain("ghp_");
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 路径安全 · teamMemPaths.ts sanitizePathKey / validateTeamMemKey
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sanitizePathKey：null 字节 / URL 编码遍历 / 反斜杠 / 绝对路径 → PathTraversalError")
    void sanitizePathKey_rejectsTraversal(@TempDir Path tmp) {
        // WHY: 路径注入向量必须拒绝（CC teamMemPaths.ts:22-64）—— null 字节截断 syscall、
        // %2e%2e%2f URL 编码遍历、反斜杠 Windows 分隔符遍历、绝对路径。
        assertThatThrownBy(() -> TeamMemPaths.sanitizePathKey("a\u0000b"))
            .isInstanceOf(TeamMemPaths.PathTraversalError.class);
        assertThatThrownBy(() -> TeamMemPaths.sanitizePathKey("%2e%2e%2fetc"))
            .isInstanceOf(TeamMemPaths.PathTraversalError.class);
        assertThatThrownBy(() -> TeamMemPaths.sanitizePathKey("a\\b"))
            .isInstanceOf(TeamMemPaths.PathTraversalError.class);
        assertThatThrownBy(() -> TeamMemPaths.sanitizePathKey("/etc/passwd"))
            .isInstanceOf(TeamMemPaths.PathTraversalError.class);
        // 正常 key 通过
        assertThat(TeamMemPaths.sanitizePathKey("patterns.md")).isEqualTo("patterns.md");
    }

    @Test
    @DisplayName("validateTeamMemKey：.. 段逃逸 → PathTraversalError；合法相对 key → 解析绝对路径")
    void validateTeamMemKey_escapesRejected(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        // .. 段逃逸（resolve 消除后超出 team 目录）
        assertThatThrownBy(() -> paths.validateTeamMemKey("../../etc/passwd"))
            .isInstanceOf(TeamMemPaths.PathTraversalError.class);
        // 合法 key → 解析为 team 目录内绝对路径
        String abs = paths.validateTeamMemKey("sub/MEMORY.md");
        assertThat(abs).startsWith(paths.getTeamMemPath());
        assertThat(Paths.get(abs).getFileName().toString()).isEqualTo("MEMORY.md");
    }

    // ────────────────────────────────────────────────────────────────
    // SyncState 数据对象（DEL-M-16）· 非状态机
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SyncState 是数据对象非状态机：create() 默认值 + 可变字段")
    void syncState_dataObject() {
        SyncState s = SyncState.create();
        assertThat(s.lastKnownChecksum).isNull();
        assertThat(s.serverChecksums).isEmpty();
        assertThat(s.serverMaxEntries).isNull();
        // 可变：pull/push 写入（非枚举状态机）
        s.lastKnownChecksum = "sha256:x";
        s.serverChecksums.put("a", "sha256:1");
        s.serverMaxEntries = 5;
        assertThat(s.lastKnownChecksum).isEqualTo("sha256:x");
        assertThat(s.serverChecksums).containsEntry("a", "sha256:1");
        assertThat(s.serverMaxEntries).isEqualTo(5);
    }

    // ────────────────────────────────────────────────────────────────
    // TeamMemSecretGuard（DEL-M-19）· 直连 scanner + isTeamMemFile
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkTeamMemSecrets：team 路径 + secret → 错误文案含 label；非 team 路径/无 secret → null")
    void guard_checkTeamMemSecrets(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        String teamFile = paths.getTeamMemPath() + "MEMORY.md";
        TeamMemSecretGuard guard = new TeamMemSecretGuard(paths);

        // 非 team 路径 → null（即使含 secret）
        assertThat(guard.checkTeamMemSecrets(gitRepo.resolve("other.md").toString(),
            "ghp_012345678901234567890123456789012345")).isNull();
        // team 路径 + 无 secret → null
        assertThat(guard.checkTeamMemSecrets(teamFile, "normal content")).isNull();
        // team 路径 + github PAT → 错误文案（label 出现，值不出现）
        String err = guard.checkTeamMemSecrets(teamFile,
            "token: ghp_012345678901234567890123456789012345");
        assertThat(err).contains("GitHub PAT");
        assertThat(err).contains("cannot be written to team memory");
        assertThat(err).doesNotContain("ghp_");
    }

    // ────────────────────────────────────────────────────────────────
    // Watcher 纯逻辑 · isPermanentFailure（watcher.ts:61-73）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isPermanentFailure：no_oauth/no_repo/4xx(除409/429) → 永久；成功/409/429 → 可重试")
    void watcher_isPermanentFailure() {
        assertThat(TeamMemoryWatcher.isPermanentFailure(
            new TeamMemoryHttpClient.PushResult(false, 0, null, false, "x", "no_oauth", null, null, null, null, null))).isTrue();
        assertThat(TeamMemoryWatcher.isPermanentFailure(
            new TeamMemoryHttpClient.PushResult(false, 0, null, false, "x", "no_repo", null, null, null, null, null))).isTrue();
        assertThat(TeamMemoryWatcher.isPermanentFailure(
            new TeamMemoryHttpClient.PushResult(false, 0, null, false, "x", "unknown", 413, null, null, null, null))).isTrue();
        assertThat(TeamMemoryWatcher.isPermanentFailure(
            new TeamMemoryHttpClient.PushResult(false, 0, null, false, "x", "unknown", 409, null, null, null, null))).isFalse();
        assertThat(TeamMemoryWatcher.isPermanentFailure(
            new TeamMemoryHttpClient.PushResult(false, 0, null, false, "x", "unknown", 429, null, null, null, null))).isFalse();
        assertThat(TeamMemoryWatcher.isPermanentFailure(
            new TeamMemoryHttpClient.PushResult(true, 1, null, false, null, null, null, null, null, null, null))).isFalse();
    }

    @Test
    @DisplayName("watcher 嵌套子目录写入在抑制态下不误清 suppression（WatchKey.watchable() 补子目录段，CM-D2 △-③）")
    void watcher_nestedEventSuppression_notClearedByNestedWrite(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY（IMP-CM-10）：WatchService event context() 相对各注册目录，嵌套子目录 WatchKey 事件 context
        // 仅文件名（缺子目录段）。旧实现 resolve(teamDir) → child=teamDir/nested.md（不存在）→ 抑制态
        // stat 误清 pushSuppressedReason（CC watcher.ts:191 join(teamDir, filename)，防 167K 事件场景）。
        // 修复：key.watchable() 定位实际注册目录 → child=sub/nested.md（存在）→ 不误清。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        Path teamDir = Paths.get(paths.getTeamMemPath());
        Files.createDirectories(teamDir.resolve("sub"));
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            TeamMemoryWatcher watcher = new TeamMemoryWatcher(svc, paths, client(), stub::baseUrl);
            watcher.resetForTesting(SyncState.create());
            System.setProperty("user.dir", gitRepo.toString());
            watcher.startFileWatcher(teamDir.toString());
            java.lang.reflect.Field suppressedField =
                TeamMemoryWatcher.class.getDeclaredField("pushSuppressedReason");
            suppressedField.setAccessible(true);
            try {
                // 抑制态（too-many-entries 恢复动作场景，watcher.ts:187-204）
                suppressedField.set(watcher, "http_413");
                // 嵌套子目录写入 → sub 的 WatchKey 事件（context 仅 "nested.md"）
                Files.writeString(teamDir.resolve("sub").resolve("nested.md"), "x", StandardCharsets.UTF_8);
                Thread.sleep(2000);   // 等待 eventLoop 处理（Windows WatchService 轮询）
                assertThat((String) suppressedField.get(watcher))
                    .as("嵌套子目录写入（真实文件存在）不得清除 suppression（旧实现 child 缺子目录段 → 误清）")
                    .isEqualTo("http_413");
                // 正控制：真正删除该文件 → unlink 恢复动作 → suppression 清除（证明 eventLoop 存活）
                Files.delete(teamDir.resolve("sub").resolve("nested.md"));
                Thread.sleep(2000);
                assertThat((String) suppressedField.get(watcher))
                    .as("正控制：真实 unlink 必须清除 suppression（eventLoop 存活）")
                    .isNull();
            } finally {
                watcher.stopTeamMemoryWatcher();
            }
        }
    }


    // ────────────────────────────────────────────────────────────────
    // TMS-07 · 401/403 → auth + skipRetry（errors.ts:232 classifyAxiosError）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fetchOnce 401 → auth + skipRetry:true + 文案含响应体（index.ts:277-284）")
    void fetchOnce_401_authClassified_skipRetryWithBody() throws Exception {
        // WHY: DRIFT-6 扩展 —— 旧实现 401 落入 code>=400 通用分支 → errorType 'unknown' +
        // skipRetry:false → 401 也重试 4 次。CC classifyAxiosError 401/403→'auth'（errors.ts:232）
        // + fetch 侧 skipRetry:true + 文案 `Not authorized for team memory sync: {body}`。
        try (StubServer stub = new StubServer()) {
            stub.status = 401;
            stub.body = "{\"error\":\"unauthorized\"}";
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            TeamMemoryHttpClient.FetchResult result =
                client.fetchOnce(state, stub.baseUrl(), "acme/demo", null);

            assertThat(result.success()).isFalse();
            assertThat(result.errorType()).isEqualTo("auth");
            assertThat(result.skipRetry()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(401);
            assertThat(result.error()).startsWith("Not authorized for team memory sync: ");
            assertThat(result.error()).contains("\"unauthorized\"");
        }
    }

    @Test
    @DisplayName("fetchOnce 403 → auth + skipRetry:true（同上 classifyAxiosError 分支）")
    void fetchOnce_403_authClassified() throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.status = 403;
            stub.body = "{\"error\":\"forbidden\"}";
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            TeamMemoryHttpClient.FetchResult result =
                client.fetchOnce(state, stub.baseUrl(), "acme/demo", null);

            assertThat(result.success()).isFalse();
            assertThat(result.errorType()).isEqualTo("auth");
            assertThat(result.skipRetry()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("fetchHashes 401 → 'Not authorized' + auth（index.ts:365-371）")
    void fetchHashes_401_notAuthorized() throws Exception {
        // WHY: DRIFT-6 扩展 hashes 侧 —— CC auth 分支文案 'Not authorized' + errorType 'auth'
        // + httpStatus；旧实现落入 code>=400 → 'HTTP 401' + 'unknown'。
        try (StubServer stub = new StubServer()) {
            stub.status = 401;
            stub.body = "{\"error\":\"unauthorized\"}";
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            TeamMemoryHttpClient.HashesResult result =
                client.fetchHashes(state, stub.baseUrl(), "acme/demo");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Not authorized");
            assertThat(result.errorType()).isEqualTo("auth");
            assertThat(result.httpStatus()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("upload 401 → errorType 'auth' + httpStatus（index.ts:524-525 分类）")
    void upload_401_authClassified() throws Exception {
        // WHY: DRIFT-6 扩展 upload 侧 —— CC uploadTeamMemory catch 分支 errorType =
        //   kind==='http'||'other'?'unknown':kind → 401/403（auth）→ 'auth' + httpStatus。
        try (StubServer stub = new StubServer()) {
            stub.status = 401;
            stub.body = "{\"error\":\"unauthorized\"}";
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            TeamMemoryHttpClient.UploadResult result =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");

            assertThat(result.success()).isFalse();
            assertThat(result.errorType()).isEqualTo("auth");
            assertThat(result.httpStatus()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("PUT upload 3xx → fail(unknown, httpStatus) 不落 200 路径（CC validateStatus 200||412 拒绝 3xx）")
    void upload_3xxRejected(@TempDir Path tmp) throws Exception {
        // WHY: CC uploadTeamMemory validateStatus 仅接受 200/412（index.ts:491）—— 3xx 触发 axios
        // 拒绝 → classifyAxiosError 'http'（errors.ts:237）→ errorType 'unknown' + httpStatus
        // （index.ts:524-525）。旧实现缺 3xx 分支 → 3xx 落入 200 路径：非 JSON HTML 抛 parse 后仍
        // 返回 UploadResult.ok(null,null) success:true（fail-open，△-18）→ 服务端未接收但本地
        // serverChecksums 已更新。3xx 必须显式转 fail，镜像 fetchOnce :382-383 / fetchHashes :568-570。
        try (StubServer stub = new StubServer()) {
            stub.status = 302;
            stub.body = "<html><body>redirect</body></html>";  // 非 JSON —— 若落 200 路径 readTree 会抛 parse
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();
            TeamMemoryHttpClient.UploadResult result =
                client.upload(state, stub.baseUrl(), "acme/demo", Map.of("a", "b"), "sha256:etag");
            assertThat(result.success()).isFalse();
            assertThat(result.errorType()).isEqualTo("unknown");
            assertThat(result.httpStatus()).isEqualTo(302);
            assertThat(result.conflict()).isFalse();
            assertThat(result.checksum()).isNull();
            assertThat(result.lastModified()).isNull();
            assertThat(state.lastKnownChecksum).isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────
    // TMS-08 · 200 响应 Zod 等价校验（DRIFT-11，TeamMemoryDataSchema types.ts:29-38）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("200 畸形体（缺 organizationId）→ parse + skipRetry:true（index.ts:234-245）")
    void fetchOnce_malformed200_missingOrganizationId_rejected() throws Exception {
        // WHY: DRIFT-11 —— 旧实现 json.path 空默认值，任意可解析 JSON 均被接受（畸形 200 体
        // 静默变空数据）。CC Zod safeParse 缺 organizationId → skipRetry:true + errorType 'parse'。
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"repo\":\"acme/demo\",\"version\":1,"
                + "\"lastModified\":\"2026-01-01T00:00:00Z\",\"checksum\":\"sha256:c\","
                + "\"content\":{\"entries\":{}}}";
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();

            TeamMemoryHttpClient.FetchResult result =
                client.fetchOnce(state, stub.baseUrl(), "acme/demo", null);

            assertThat(result.success()).isFalse();
            assertThat(result.errorType()).isEqualTo("parse");
            assertThat(result.skipRetry()).isTrue();
            assertThat(result.error()).isEqualTo("Invalid team memory response format");
        }
    }

    @Test
    @DisplayName("200 畸形体（version 非 number / entries 值非 string / content 缺失）→ parse 拒绝")
    void fetchOnce_malformed200_schemaVariants_rejected() throws Exception {
        try (StubServer stub = new StubServer()) {
            TeamMemoryHttpClient client = client();
            SyncState state = SyncState.create();
            String validPrefix = "{\"organizationId\":\"o\",\"repo\":\"acme/demo\",";

            // version 必须是 number（Zod z.number()）
            stub.status = 200;
            stub.body = validPrefix + "\"version\":\"1\","
                + "\"lastModified\":\"2026-01-01T00:00:00Z\",\"checksum\":\"sha256:c\","
                + "\"content\":{\"entries\":{}}}";
            assertThat(client.fetchOnce(state, stub.baseUrl(), "acme/demo", null).errorType())
                .isEqualTo("parse");

            // content.entries 值必须全 string（Zod z.record(z.string(), z.string())）
            stub.body = validPrefix + "\"version\":1,"
                + "\"lastModified\":\"2026-01-01T00:00:00Z\",\"checksum\":\"sha256:c\","
                + "\"content\":{\"entries\":{\"a\":123}}}";
            assertThat(client.fetchOnce(state, stub.baseUrl(), "acme/demo", null).errorType())
                .isEqualTo("parse");

            // content 缺失（必填对象）
            stub.body = validPrefix + "\"version\":1,"
                + "\"lastModified\":\"2026-01-01T00:00:00Z\",\"checksum\":\"sha256:c\"}";
            assertThat(client.fetchOnce(state, stub.baseUrl(), "acme/demo", null).errorType())
                .isEqualTo("parse");

            // entryChecksums 若存在必须为 object 且值全 string
            stub.body = validPrefix + "\"version\":1,"
                + "\"lastModified\":\"2026-01-01T00:00:00Z\",\"checksum\":\"sha256:c\","
                + "\"content\":{\"entries\":{},\"entryChecksums\":{\"a\":123}}}";
            assertThat(client.fetchOnce(state, stub.baseUrl(), "acme/demo", null).errorType())
                .isEqualTo("parse");
        }
    }

    // ────────────────────────────────────────────────────────────────
    // TMS-09 · logPull 失败路径遥测 errorType/status（DRIFT-12，index.ts:1195-1216）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pull 401 失败 → tengu_team_mem_sync_pull 遥测 errorType=auth + status=401")
    void pull_401_emitsErrorTypeAndStatusTelemetry(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: DRIFT-12 —— 旧实现 emitPull 仅 success/files_written/not_modified/duration_ms，
        // PullResult.errorType 携带值却从不入遥测、无 httpStatus 字段 → pull 失败归因在遥测侧不可见。
        fakeGitRepo(gitRepo, "acme/demo");
        try (StubServer stub = new StubServer()) {
            stub.status = 401;
            stub.body = "{\"error\":\"unauthorized\"}";
            AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase, telemetry);
            SyncState state = SyncState.create();
            System.setProperty("user.dir", gitRepo.toString());

            TeamMemorySyncService.PullResult pull = svc.pullTeamMemory(state, stub.baseUrl(), false);

            assertThat(pull.success()).isFalse();
            Map<String, Object> attrs = telemetry.attrsOf("tengu_team_mem_sync_pull");
            assertThat(attrs).containsEntry("success", false);
            assertThat(attrs).containsEntry("errorType", "auth");
            assertThat(attrs).containsEntry("status", 401);
        }
    }

    @Test
    @DisplayName("pull no_oauth 门失败 → 遥测 errorType=no_oauth（index.ts:785，无 status 属性）")
    void pull_noOAuth_emitsErrorTypeTelemetry(@TempDir Path gitRepo, @TempDir Path memoryBase) {
        TeamMemoryHttpClient noAuthClient =
            new TeamMemoryHttpClient(HttpClient.newHttpClient(), Map::of);
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        TeamMemorySyncService svc = new TeamMemorySyncService(noAuthClient,
            teamMemPaths(gitRepo, memoryBase), new TrackingClaudemdEngine(gitRepo, memoryBase));
        svc.setTelemetry(telemetry);
        SyncState state = SyncState.create();

        TeamMemorySyncService.PullResult pull = svc.pullTeamMemory(state, "http://localhost:1", false);

        assertThat(pull.success()).isFalse();
        Map<String, Object> attrs = telemetry.attrsOf("tengu_team_mem_sync_pull");
        assertThat(attrs).containsEntry("success", false);
        assertThat(attrs).containsEntry("errorType", "no_oauth");
        assertThat(attrs).doesNotContainKey("status");
    }

    // ────────────────────────────────────────────────────────────────
    // G-66（F1）· readdir 非三类错误 rethrow（M-3，index.ts:624-632）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readdir 非 {ENOENT,EACCES,EPERM} 错误（NotDirectory）→ IOException 显式 rethrow")
    void readLocalTeamMemory_readdirError_rethrown(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: M-3/G-66 —— 旧实现 listFiles()==null 整体静默跳过子树（EIO 类下 Java 静默漏传，
        // fail-open 比 CC 宽容）。CC walkDir 对非 ENOENT/EACCES/EPERM 的 readdir 错误 rethrow
        // （index.ts:624-632）→ push 显式失败。team 目录路径被普通文件占据 → Files.list 抛
        // NotDirectoryException（非三类）→ 必须 rethrow 而非静默返回空。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        Path teamDir = Paths.get(paths.getTeamMemPath());
        Files.createDirectories(teamDir.getParent());
        Files.writeString(teamDir, "i am a file, not a directory");
        TeamMemorySyncService svc = service(null, gitRepo, memoryBase);

        assertThatThrownBy(() -> svc.readLocalTeamMemory(null))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("push 遇 readdir 非三类错误 → 显式失败（不静默上传空集）")
    void pushTeamMemory_readdirError_failsExplicitly(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        Path teamDir = Paths.get(paths.getTeamMemPath());
        Files.createDirectories(teamDir.getParent());
        Files.writeString(teamDir, "i am a file, not a directory");
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            SyncState state = SyncState.create();
            System.setProperty("user.dir", gitRepo.toString());

            TeamMemoryHttpClient.PushResult push = svc.pushTeamMemory(state, stub.baseUrl());

            assertThat(push.success()).isFalse();
            assertThat(push.error()).contains("read local team memory failed");
            // 绝不以空集静默上传（旧 listFiles()==null → 空 entries → 空 delta 假成功）
            assertThat(stub.putCount).isZero();
        }
    }

    @Test
    @DisplayName("readdir ENOENT（team 目录不存在）→ 吞掉返回空 entries（CC :625-628）")
    void readLocalTeamMemory_missingTeamDir_swallowed(@TempDir Path gitRepo, @TempDir Path memoryBase) throws IOException {
        // WHY: G-66 只改「非三类 rethrow」，ENOENT/EACCES/EPERM 仍吞掉 —— 目录不存在不是错误
        // （CC :625-628）。防过度收紧破坏空目录/fresh repo 场景。
        TeamMemorySyncService svc = service(null, gitRepo, memoryBase);

        TeamMemorySyncService.LocalRead read = svc.readLocalTeamMemory(null);

        assertThat(read.entries()).isEmpty();
        assertThat(read.skippedSecrets()).isEmpty();
    }

    @Test
    @DisplayName("symlink 不跟随（OPD-CM5-D-03 P0 安全）：team 目录内 symlink→外部文件/目录 既不读取也不上传")
    void readLocalTeamMemory_symlinkNotFollowed(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: OPD-CM5-D-03（P0 安全项）—— CC walkDir 用 readdir withFileTypes 的
        //   Dirent.isDirectory()/isFile()（index.ts:581），Node Dirent 对 symlink 返回 false
        //   （isSymbolicLink 而非 isDirectory/isFile）→ team 目录内 symlink 既不递归也不读取。
        //   旧 Java File.isDirectory()/isFile() 跟链（探查 △-9）→ symlink→外部目录被遍历、
        //   symlink→外部文件被读取并上传（fail-open，外部内容离开机器）。
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        Path teamDir = Paths.get(paths.getTeamMemPath());
        Files.createDirectories(teamDir);
        // 真实文件照常读取（正控制）
        Files.writeString(teamDir.resolve("REAL.md"), "real content", StandardCharsets.UTF_8);
        // 外部目标（team 目录之外）：目录 + 文件，内容带 "external" 标记以便断言绝不进入 entries
        Path extDir = Files.createDirectories(memoryBase.resolve("external-dir"));
        Files.writeString(extDir.resolve("SECRET-outside.md"), "external secret", StandardCharsets.UTF_8);
        Path extFile = memoryBase.resolve("external-file.md");
        Files.writeString(extFile, "external file content", StandardCharsets.UTF_8);

        // team 目录内放 symlink→外部目录 + symlink→外部文件
        Path linkDir = teamDir.resolve("link-dir");
        Path linkFile = teamDir.resolve("link-file.md");
        try {
            Files.createSymbolicLink(linkDir, extDir);
            Files.createSymbolicLink(linkFile, extFile);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "symlink 创建失败（无权限/无 Developer Mode），跳过 symlink 用例: " + e.getMessage());
            return;
        }

        TeamMemorySyncService svc = service(null, gitRepo, memoryBase);
        TeamMemorySyncService.LocalRead read = svc.readLocalTeamMemory(null);

        // 仅真实文件进入；symlink→外部文件不读取、symlink→外部目录不递归
        assertThat(read.entries())
            .as("symlink→外部文件不读取、symlink→外部目录不递归（Dirent 不 follow · index.ts:581）")
            .containsOnlyKeys("REAL.md");
        assertThat(read.entries().values())
            .as("外部内容绝不进入上传集（NOFOLLOW_LINKS 对齐 Dirent，PSR 安全方向）")
            .noneMatch(v -> v.contains("external"));
    }

    // ────────────────────────────────────────────────────────────────
    // TMS-06 · stop flush 不重置 hasPendingChanges + @PreDestroy 关闭 flush
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stopTeamMemoryWatcher flush 后不重置 hasPendingChanges（watcher.ts:345-351，EV-TMS-40）")
    void stopTeamMemoryWatcher_flushDoesNotResetPending(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: DRIFT-10 微差 —— Java 旧实现 flush 后额外置 hasPendingChanges=false（:420），
        // CC stopTeamMemoryWatcher 不重置（watcher.ts:345-351）。行为判别：flush #1 后新增本地
        // 编辑（不重新 schedulePush —— pending 标记若存活，stop #2 无需 schedule 直接 flush 该变更）。
        // 旧实现（flush 后置 false）stop #2 无 pending → 不推新变更。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        writeLocalTeamFile(paths, "MEMORY.md", "local edit");
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            TeamMemoryWatcher watcher = new TeamMemoryWatcher(svc, paths, client(), stub::baseUrl);
            watcher.resetForTesting(SyncState.create());
            System.setProperty("user.dir", gitRepo.toString());

            watcher.schedulePush();   // hasPendingChanges=true + 2s debounce
            watcher.stopTeamMemoryWatcher();  // flush #1 → PUT MEMORY.md（成功也回写 serverChecksums）
            assertThat(stub.putCount).isEqualTo(1);

            // flush 后新增本地编辑 —— 不调 schedulePush（pending 未被重置才应在下次 stop 被 flush）
            writeLocalTeamFile(paths, "NEW.md", "new change");
            watcher.stopTeamMemoryWatcher();  // flush #2 —— pending 未重置 → 推 NEW.md

            assertThat(stub.putCount).as("stop flush 后 pending 必须存活（CC 不重置），新变更随下次 stop 推出")
                .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("@PreDestroy shutdown() = registerCleanup 等价（watcher.ts:228）：关闭时 flush pending")
    void preDestroyShutdown_flushesPending(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: DRIFT-8/10 —— Java 无 registerCleanup/shutdown hook 等价物，stopTeamMemoryWatcher
        // 生产 0 调用方 → 关闭不 flush。CC startFileWatcher 注册 registerCleanup(stopTeamMemoryWatcher)
        // （watcher.ts:228）。@PreDestroy 注解存在性（E1）+ shutdown() 触发 flush（E3）双断言。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        writeLocalTeamFile(paths, "MEMORY.md", "local edit");
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            TeamMemoryWatcher watcher = new TeamMemoryWatcher(svc, paths, client(), stub::baseUrl);
            watcher.resetForTesting(SyncState.create());
            System.setProperty("user.dir", gitRepo.toString());

            assertThat(TeamMemoryWatcher.class.getMethod("shutdown")
                    .getAnnotation(jakarta.annotation.PreDestroy.class))
                .as("@PreDestroy shutdown() 必须存在（Spring 关闭时注册的关闭 flush）")
                .isNotNull();

            watcher.schedulePush();
            watcher.shutdown();   // Spring 容器关闭回调

            assertThat(stub.putCount).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // TMS-01 · notify 生产装配断言（DRIFT-9，sessionFileAccessHooks.ts:201/:205）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SessionFileAccessHooks：Edit/Write team 文件 → notifyTeamMemoryWrite；Read 不 notify")
    void sessionFileAccessHooks_teamEditWrite_notifiesWatcher(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: notify 装配消费侧契约（CC sessionFileAccessHooks.ts:201/:205）—— Edit/Write 命中
        // team 文件后必须调 teamMemWatcher.notifyTeamMemoryWrite()（防 fs.watch 漏事件）；Read 不
        // notify（仅统计事件）。门控全开 + watcher 注入时 notify 必须可达。
        fakeGitRepo(gitRepo, "acme/demo");
        AutoMemPaths autoMemPaths = autoMemPaths(gitRepo, memoryBase);
        // IMP-CM-09 双门控：feature+runtime 双开
        TeamMemPaths paths = new TeamMemPaths(autoMemPaths, () -> true, () -> true, () -> true);
        String teamFile = paths.getTeamMemPath() + "MEMORY.md";
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(
            new RecordingTelemetry(),
            () -> System.getProperty("user.home"),
            autoMemPaths,
            () -> true,   // autoMemoryEnabled（CC isAutoMemoryEnabled）
            () -> true,   // teamMemFeatureEnabled（CC feature('TEAMMEM')）
            () -> true);  // teamMemoryRuntimeEnabled（CC tengu_herring_clock）
        CountingWatcher watcher = new CountingWatcher();
        hooks.setTeamMemoryWatcher(watcher);
        ObjectMapper mapper = new ObjectMapper();

        JsonNode editInput = mapper.createObjectNode().put("file_path", teamFile);
        hooks.handleSessionFileAccess("Edit", editInput);
        assertThat(watcher.notifyCalls).isEqualTo(1);

        JsonNode writeInput = mapper.createObjectNode().put("file_path", teamFile);
        hooks.handleSessionFileAccess("Write", writeInput);
        assertThat(watcher.notifyCalls).isEqualTo(2);

        // Read 只发统计事件，不 notify（CC :196-198）
        JsonNode readInput = mapper.createObjectNode().put("file_path", teamFile);
        hooks.handleSessionFileAccess("Read", readInput);
        assertThat(watcher.notifyCalls).isEqualTo(2);

        // 非 team 文件 → 不 notify
        JsonNode otherInput = mapper.createObjectNode()
            .put("file_path", gitRepo.resolve("other.md").toString());
        hooks.handleSessionFileAccess("Edit", otherInput);
        assertThat(watcher.notifyCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("LlmAgentLoop 生产装配点调用 setTeamMemoryWatcher（DRIFT-9/OPD-R2-TMS-01 验收 ③）")
    void llmAgentLoop_productionAssembly_wiresTeamMemoryWatcher() throws Exception {
        // WHY: 验收标准 ③ `grep setTeamMemoryWatcher 生产装配点存在` —— 生产 LlmAgentLoop 的
        // registerSessionFileAccessHooks 装配点必须把 TeamMemoryWatcher 注入 SessionFileAccessHooks
        // （旧实现 `new SessionFileAccessHooks(telemetry)` 后无 setTeamMemoryWatcher 调用 →
        // 生产 teamMemoryWatcher=null → notify 不可达）。源级断言（LlmAgentLoopDriftAndLazinessTest 先例）。
        List<String> lines = Files.readAllLines(Path.of(
            "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java"));
        assertThat(lines).as("生产装配点必须存在 setTeamMemoryWatcher 调用（notify 生产可达）")
            .anyMatch(l -> l.contains("setTeamMemoryWatcher(teamMemoryWatcher)"));
    }

    /** 记录发射事件+属性的 Telemetry 假实现（参照 SessionMemoryRev2AlignmentTest.AttrRecordingTelemetry）。 */
    static final class AttrRecordingTelemetry extends Telemetry {
        final Map<String, Map<String, Object>> attrsByEvent = new HashMap<>();

        @Override
        public void recordEvent(String eventName, java.util.Map<String, Object> attributes) {
            attrsByEvent.put(eventName, attributes);
            super.recordEvent(eventName, attributes);
        }

        Map<String, Object> attrsOf(String eventName) {
            return attrsByEvent.get(eventName);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // IMP-CM-07 · first-party OAuth 鉴权接线（index.ts:151-184 isUsingOAuth + getAuthHeaders）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isFirstPartyOAuthAvailable：provider firstParty + baseUrl first-party + 双 scope → 可用")
    void oauth_isFirstPartyOAuthAvailable_fullScopes() {
        // WHY: OPD-CM3-08 拍板「接线保持与 CC 一致」—— TeamMemPaths 生产构造的 () -> false 必须替换为
        // 真实 OAuth 判定（index.ts:151-160 isUsingOAuth：provider firstParty + base URL first-party +
        // accessToken + inference/profile 双 scope）。注入双 scope token + firstParty 语义 → 可用。
        TeamMemoryHttpClient.OAuthTokens full = new TeamMemoryHttpClient.OAuthTokens(
            "test-token", Set.of(TeamMemoryHttpClient.CLAUDE_AI_INFERENCE_SCOPE,
                                 TeamMemoryHttpClient.CLAUDE_AI_PROFILE_SCOPE));
        assertThat(TeamMemoryHttpClient.isFirstPartyOAuthAvailable("firstParty", true, () -> full)).isTrue();
    }

    @Test
    @DisplayName("isFirstPartyOAuthAvailable：非 firstParty / 非 1P base URL / 缺 profile → 不可用（保持惰性不炸）")
    void oauth_isFirstPartyOAuthAvailable_notUsable() {
        // WHY: 对齐 CC isUsingOAuth（index.ts:151-160）：provider 非 firstParty（bedrock/vertex/foundry）
        // 或 base URL 非 first-party（自定义网关）→ false；env token 仅 inference scope（auth.ts:1265
        // 缺 profile）→ false。三个失败面各自独立 —— 链惰性（不炸）。
        TeamMemoryHttpClient.OAuthTokens full = new TeamMemoryHttpClient.OAuthTokens(
            "test-token", Set.of(TeamMemoryHttpClient.CLAUDE_AI_INFERENCE_SCOPE,
                                 TeamMemoryHttpClient.CLAUDE_AI_PROFILE_SCOPE));
        // 非 firstParty provider
        assertThat(TeamMemoryHttpClient.isFirstPartyOAuthAvailable("bedrock", true, () -> full)).isFalse();
        // 非 first-party base URL
        assertThat(TeamMemoryHttpClient.isFirstPartyOAuthAvailable("firstParty", false, () -> full)).isFalse();
        // 无 token
        assertThat(TeamMemoryHttpClient.isFirstPartyOAuthAvailable("firstParty", true, () -> null)).isFalse();
        // inference-only（缺 profile scope）
        TeamMemoryHttpClient.OAuthTokens inferenceOnly = new TeamMemoryHttpClient.OAuthTokens(
            "test-token", Set.of(TeamMemoryHttpClient.CLAUDE_AI_INFERENCE_SCOPE));
        assertThat(TeamMemoryHttpClient.isFirstPartyOAuthAvailable("firstParty", true, () -> inferenceOnly)).isFalse();
    }

    @Test
    @DisplayName("FirstPartyOAuthAuthHeaderProvider：可用时产 Bearer+anthropic-beta+User-Agent，不可用时空 header")
    void oauth_firstPartyHeaderProvider_headers() {
        // WHY: 默认构造器注入 FirstPartyOAuthAuthHeaderProvider（对齐 getAuthHeaders index.ts:169-184）——
        // isAuthAvailable() = header 非空语义须反映真实 OAuth：可用时 Authorization Bearer + anthropic-beta
        // + User-Agent；不可用（无 token）→ 空 header → 整链惰性。
        TeamMemoryHttpClient.FirstPartyOAuthAuthHeaderProvider usable = new TeamMemoryHttpClient.FirstPartyOAuthAuthHeaderProvider(
            () -> new TeamMemoryHttpClient.OAuthTokens("tok", Set.of(
                TeamMemoryHttpClient.CLAUDE_AI_INFERENCE_SCOPE,
                TeamMemoryHttpClient.CLAUDE_AI_PROFILE_SCOPE)),
            () -> true);
        Map<String, String> h = usable.headers();
        assertThat(h).containsEntry("Authorization", "Bearer tok");
        assertThat(h).containsEntry("anthropic-beta", TeamMemoryHttpClient.OAUTH_BETA_HEADER);
        assertThat(h).containsKey("User-Agent");

        TeamMemoryHttpClient.FirstPartyOAuthAuthHeaderProvider unusable = new TeamMemoryHttpClient.FirstPartyOAuthAuthHeaderProvider(
            () -> null, () -> true);
        assertThat(unusable.headers()).isEmpty();
    }

    @Test
    @DisplayName("TeamMemPaths 生产构造不再 () -> false（IMP-CM-09 验收：双门控拆分接线 FeatureFlags）")
    void teamMemPaths_productionConstructor_noConstantFalse() throws Exception {
        // WHY: DOC-04 验收「TeamMemPaths 生产构造不再传恒 false supplier」。IMP-CM-09（OPD-CM3-11/B04）
        // 双门控拆分：单参构造升级为双 supplier（编译开关 feature('TEAMMEM') + 运行时开关 tengu_herring_clock），
        // ToolRegistrationConfig:997 生产接线 = FeatureFlags.teamMem()/tenguHerringClock()。源级断言：
        // 构造签名必须含双开关参数且不再 () -> false。
        java.util.List<String> lines = java.nio.file.Files.readAllLines(Path.of(
            "src/main/java/com/nexusai/application/agent/memory/TeamMemPaths.java"));
        assertThat(lines).as("TeamMemPaths 构造签名必须含编译开关参数 teamMemFeatureEnabled（无 () -> false）")
            .anyMatch(l -> l.contains("BooleanSupplier teamMemFeatureEnabled"));
        assertThat(lines).as("TeamMemPaths 构造签名必须含运行时开关参数 teamMemoryRuntimeEnabled")
            .anyMatch(l -> l.contains("BooleanSupplier teamMemoryRuntimeEnabled"));
        assertThat(lines).as("TeamMemPaths 不再含恒 false supplier")
            .noneMatch(l -> l.contains("() -> false"));
    }

    @Test
    @DisplayName("检测链生产构造不再 () -> false（IMP-CM-09 双门控拆分：消除双实例门控分裂）")
    void detectionChain_productionConstructors_noConstantFalse() throws Exception {
        // WHY: IMP-CM-07 R1 消除双实例门控分裂（MemoryFileDetection/SessionFileAccessHooks 生产构造
        // 原先传 () -> false，内部 new TeamMemPaths 门控恒 false，与 teamMemPaths bean 分裂）。
        // IMP-CM-09（OPD-CM3-11/B04）双门控拆分：生产构造升级为 feature('TEAMMEM') + tengu_herring_clock
        // 双 supplier（FeatureFlags 注入，与 teamMemPaths bean 同源）。源级断言：双开关参数 + 不再 () -> false。
        java.util.List<String> mfd = java.nio.file.Files.readAllLines(Path.of(
            "src/main/java/com/nexusai/application/agent/memory/MemoryFileDetection.java"));
        assertThat(mfd).as("MemoryFileDetection 生产构造必须含编译开关参数 teamMemFeatureEnabled")
            .anyMatch(l -> l.contains("teamMemFeatureEnabled"));
        assertThat(mfd).as("MemoryFileDetection 生产构造必须含运行时开关参数 teamMemoryRuntimeEnabled")
            .anyMatch(l -> l.contains("teamMemoryRuntimeEnabled"));
        assertThat(mfd).as("MemoryFileDetection 不再含恒 false supplier")
            .noneMatch(l -> l.contains("() -> false"));

        java.util.List<String> sfh = java.nio.file.Files.readAllLines(Path.of(
            "src/main/java/com/nexusai/application/agent/permission/hook/SessionFileAccessHooks.java"));
        assertThat(sfh).as("SessionFileAccessHooks 生产构造必须含编译开关参数 teamMemFeatureEnabled")
            .anyMatch(l -> l.contains("teamMemFeatureEnabled"));
        assertThat(sfh).as("SessionFileAccessHooks 生产构造必须含运行时开关参数 teamMemoryRuntimeEnabled")
            .anyMatch(l -> l.contains("teamMemoryRuntimeEnabled"));
        assertThat(sfh).as("SessionFileAccessHooks 不再含恒 false supplier")
            .noneMatch(l -> l.contains("() -> false"));
    }


    // ────────────────────────────────────────────────────────────────
    // IMP-MV2-40 登记类固化（B-3 UA / B-4 timeout 分类 / B-11 冲突载荷 / B-17 resetForTesting）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[B-3 登记固化] 出站 User-Agent = 'claude-code-java'（△-10：CC claude-code/${VERSION}，userAgent.ts:8-9）")
    void ua_header_sent_toServer(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: 登记类 △-10 固化现状 —— Java 硬编码 UA 与 CC 版本化 UA 的差异已登记不修；
        //   本用例锁定当前值，防未来无意识变更（服务端 UA 统计契约）。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        writeLocalTeamFile(paths, "MEMORY.md", "hello");
        // 生产 UA 仅由 FirstPartyOAuthAuthHeaderProvider.headers() 注入（HttpClient:130-137）；
        // 通用测试 provider 无 UA —— 必须用 first-party provider 驱动出站头。
        TeamMemoryHttpClient client = new TeamMemoryHttpClient(HttpClient.newHttpClient(),
            new TeamMemoryHttpClient.FirstPartyOAuthAuthHeaderProvider(
                () -> new TeamMemoryHttpClient.OAuthTokens("tok", Set.of(
                    TeamMemoryHttpClient.CLAUDE_AI_INFERENCE_SCOPE,
                    TeamMemoryHttpClient.CLAUDE_AI_PROFILE_SCOPE)),
                () -> true));
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";
            TeamMemorySyncService svc = new TeamMemorySyncService(client, paths,
                new TrackingClaudemdEngine(gitRepo, memoryBase));
            SyncState state = SyncState.create();
            System.setProperty("user.dir", gitRepo.toString());

            svc.pushTeamMemory(state, stub.baseUrl());

            assertThat(stub.userAgentHeader).as("出站 User-Agent 固化值（B-3 登记）").isEqualTo("claude-code-java");
        }
    }

    @Test
    @DisplayName("[B-4 登记固化] 连接失败（IOException 面）→ errorType 'network'（△-11/13/14：HttpTimeoutException extends IOException 同落本分支）")
    void fetchOnce_ioError_classifiedAsNetwork() throws Exception {
        // WHY: 登记类 △-11/13/14 固化现状 —— 30s 请求超时抛 HttpTimeoutException（extends
        //   IOException，JDK 公开 API 事实）与连接失败同落 catch(IOException) → 'network'（CC 'timeout'）。
        //   重试行为两端一致。用关闭端口的 ConnectException（IOException）覆盖同一分支。
        int closedPort;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            closedPort = ss.getLocalPort();
        }
        TeamMemoryHttpClient client = client();
        SyncState state = SyncState.create();

        TeamMemoryHttpClient.FetchResult result =
            client.fetchOnce(state, "http://localhost:" + closedPort, "acme/demo", null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorType()).as("IOException 面 → 'network'（B-4 登记固化）").isEqualTo("network");
        assertThat(result.skipRetry()).as("network 非 skipRetry，仍重试（与 CC 重试行为一致）").isFalse();
    }

    @Test
    @DisplayName("[B-11 登记固化] 冲突耗尽 → PushResult errorType='conflict' + conflict=true（△-21 载荷固化）")
    void push_conflictExhausted_carriesConflictErrorType(@TempDir Path gitRepo, @TempDir Path memoryBase)
            throws Exception {
        // WHY: 登记类 △-21 固化现状 —— 可达路径（412 连续 MAX_CONFLICT_RETRIES+1 次）Java PushResult
        //   带 errorType='conflict'；CC 返回对象无 errorType（index.ts:1099-1104）。遥测与
        //   isPermanentFailure 两端一致，仅返回载荷差异，登记不修；本用例锁定载荷形态。
        fakeGitRepo(gitRepo, "acme/demo");
        writeLocalTeamFile(teamMemPaths(gitRepo, memoryBase), "MEMORY.md", "local edit");
        try (StubServer stub = new StubServer()) {
            stub.status = 412;                                    // PUT 恒 412（冲突）
            stub.getStatus = 200;                                 // 探针 GET 成功（空 entryChecksums）
            stub.body = "{\"checksum\":\"sha256:probe\",\"version\":1,\"entryChecksums\":{}}";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            SyncState state = SyncState.create();
            System.setProperty("user.dir", gitRepo.toString());

            TeamMemoryHttpClient.PushResult push = svc.pushTeamMemory(state, stub.baseUrl());

            assertThat(push.success()).isFalse();
            assertThat(push.conflict()).isTrue();
            assertThat(push.error()).isEqualTo("Conflict resolution failed after retries");
            assertThat(push.errorType()).as("△-21 载荷固化：Java 填 'conflict'（CC 该路径无 errorType）")
                .isEqualTo("conflict");
            assertThat(stub.putCount).as("MAX_CONFLICT_RETRIES=2 → 共 3 次 PUT 后耗尽").isEqualTo(3);
        }
    }

    @Test
    @DisplayName("[IMP-D-3 补齐] resetForTesting 重置 debounce/watchService + skipWatcher/pushSuppressedReason 注入（OPD-CM5-D-07，对齐 CC _resetWatcherStateForTesting watcher.ts:365-378）")
    void resetForTesting_clearsDebounceAndSupportsOpts(@TempDir Path gitRepo, @TempDir Path memoryBase) throws Exception {
        // WHY: OPD-CM5-D-07 拍板补齐 —— CC _resetWatcherStateForTesting（watcher.ts:365-378）重置
        //   debounceTimer/watcher + 支持 {syncState?, skipWatcher?, pushSuppressedReason?} 注入；旧 Java
        //   resetForTesting（:462-469）不重置 debounceFuture（B-17 登记差异：旧测试 2s debounce 任务
        //   跨测试泄漏触发 executePush）。补齐后：重置后 debounceFuture/watchService 为空；
        //   skipWatcher=true → watcherStarted=true；pushSuppressedReason 注入抑制。
        fakeGitRepo(gitRepo, "acme/demo");
        TeamMemPaths paths = teamMemPaths(gitRepo, memoryBase);
        writeLocalTeamFile(paths, "MEMORY.md", "local edit");
        try (StubServer stub = new StubServer()) {
            stub.status = 200;
            stub.body = "{\"checksum\":\"sha256:push-ok\"}";
            TeamMemorySyncService svc = service(stub, gitRepo, memoryBase);
            TeamMemoryWatcher watcher = new TeamMemoryWatcher(svc, paths, client(), stub::baseUrl);
            watcher.resetForTesting(SyncState.create());
            System.setProperty("user.dir", gitRepo.toString());

            watcher.schedulePush();  // 2s debounce 排定
            java.lang.reflect.Field debounce = TeamMemoryWatcher.class.getDeclaredField("debounceFuture");
            debounce.setAccessible(true);
            assertThat(debounce.get(watcher)).as("schedulePush 后 debounceFuture 非空").isNotNull();

            // 全参形式：重置 debounce + 注入 skipWatcher/pushSuppressedReason
            watcher.resetForTesting(SyncState.create(), true, "http_413");

            assertThat(debounce.get(watcher))
                .as("IMP-D-3：resetForTesting 必须重置 debounceFuture（对齐 CC :370，原 B-17 登记差异关闭）")
                .isNull();

            java.lang.reflect.Field started = TeamMemoryWatcher.class.getDeclaredField("watcherStarted");
            started.setAccessible(true);
            assertThat(started.getBoolean(watcher))
                .as("skipWatcher=true → watcherStarted=true（CC opts?.skipWatcher ?? false）").isTrue();

            java.lang.reflect.Field suppressed =
                TeamMemoryWatcher.class.getDeclaredField("pushSuppressedReason");
            suppressed.setAccessible(true);
            assertThat((String) suppressed.get(watcher))
                .as("pushSuppressedReason 注入生效（CC opts?.pushSuppressedReason ?? null）").isEqualTo("http_413");

            // 单参形式默认：skipWatcher=false + suppression 清空
            watcher.resetForTesting(SyncState.create());
            assertThat(started.getBoolean(watcher)).as("单参形式默认 watcherStarted=false").isFalse();
            assertThat((String) suppressed.get(watcher)).as("单参形式默认 pushSuppressedReason=null").isNull();

            watcher.stopTeamMemoryWatcher();  // 清理
        }
    }
    /** 记录 notifyTeamMemoryWrite 调用次数的 watcher 桩（TMS-01 notify 装配断言）。 */
    static final class CountingWatcher extends TeamMemoryWatcher {
        volatile int notifyCalls = 0;

        CountingWatcher() {
            super(null, null, null, () -> "http://localhost:1");
        }

        @Override
        public void notifyTeamMemoryWrite() {
            notifyCalls++;
        }
    }
}
