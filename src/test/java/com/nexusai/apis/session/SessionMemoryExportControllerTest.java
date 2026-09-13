package com.nexusai.apis.session;

import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.memory.MemoryPromptBuilder;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [OPD-CM5-F-24] SessionMemoryExportController GET /api/v1/session-memory/export 会话记忆导出测试。
 *
 * <p>WHY (CLAUDE.md 规则 9 · 测试验证意图): 前端有"导出会话记忆"需求（待前端对接.md §26），
 * 后端提供 REST 载体——CC {@code getAgentMemoryEntrypoint(agentType, scope)}
 * （agentMemory.ts:109-114）入口路径 + 记忆目录 .md 文件内容。本测试锁定<b>端点语义</b>:
 * <ol>
 *   <li><b>入口路径</b>——entrypoint = {@code getAgentMemoryDir(agentType, scope)/MEMORY.md}。
 *       若入口与记忆目录分离/丢 MEMORY.md 后缀 → 前端导出到错误路径/读不到记忆。</li>
 *   <li><b>文件列表+内容</b>——记忆目录内全部顶层 .md 文件（含入口 MEMORY.md）及 UTF-8 内容。
 *       若缺文件/缺内容 → 前端导出的会话记忆不完整。</li>
 *   <li><b>非法 scope → 400</b>——scope 非 user/project/local（CC agentMemory.ts:13 三值枚举）
 *       拒绝，避免把目录名拼进错误基址。</li>
 *   <li><b>目录不存在 → entrypoint 仍返回 + 空 files</b>——agent 尚未写记忆时导出应给预期路径
 *       而非报错（CC getAgentMemoryEntrypoint 纯路径解析，无 mkdir/无存在性要求）。</li>
 * </ol>
 *
 * <p><b>[批 4b-2 · 用户裁定 #14] 新增语义（project/local 必填 sessionId）</b>——PROJECT/LOCAL scope
 * 的记忆目录本体 = {@code <会话项目根>/.nexusai/agent-memory[-local]/<type>}，<b>必须有会话项目根</b>：
 * <ol>
 *   <li><b>缺 sessionId ⇒ 400</b>（不是 500、不是静默回落）：改前无 sessionId 时
 *       {@code AgentMemoryDirectory.requireProjectRoot} 恒抛 IllegalStateException → 500；
 *       更早（批 4b-1 前）则回落 config home 拼出 {@code ~/.nexusai/.nexusai/agent-memory/<type>}
 *       假目录原样返回前端。二者都是「本该有却没有」的静默/无意义产物。</li>
 *   <li><b>sessionId 解析不到会话项目根 ⇒ 400</b>（合成/伪造 id、DB 无绑定行）：
 *       ⛔ 绝不回落 user.dir / config home 冒充项目根。</li>
 *   <li><b>user scope 不需要 sessionId（正向对照）</b>：USER 基址 = memoryBase（存储基座，非项目
 *       身份）⇒ 缺 sessionId 仍 200。此对照证明上面两条 400 是「scope 确实需要根」，而非端点整体坏掉。</li>
 * </ol>
 */
@DisplayName("[OPD-CM5-F-24] SessionMemoryExportController GET /api/v1/session-memory/export")
class SessionMemoryExportControllerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        // 会话冻结表是全局静态载体 → 跨用例必须复位（否则 ③ 用例的绑定泄漏到后续用例）
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
    }

    /**
     * 注入式 AgentMemoryDirectory —— <b>生产形状</b>：cwd/projectRoot 两个 supplier 均显式返回
     * {@code null}（生产 = {@code AutoMemPaths.currentSessionProjectRootOrNull} = env 未配置 → null，
     * 批 4b-1 载体删除后再无 config home 回落）。故 PROJECT/LOCAL scope 只能靠<b>调用方显式传入</b>
     * 的会话项目根（本测试 = REST 的 sessionId 解析结果）。
     */
    private AgentMemoryDirectory newDirectory() {
        return new AgentMemoryDirectory(
            () -> null,   // cwdSupplier（生产 = 无载体出口）
            () -> tempDir, // memoryBase（测试隔离）
            () -> null,
            () -> null,   // projectRootSupplier（生产 = 无载体出口）
            s -> s,
            path -> { /* 导出纯读：不 mkdir */ },
            () -> null,
            () -> true,
            MemoryPromptBuilder.productionDefault());
    }

    private MockMvc mockMvc(AgentMemoryDirectory dir) {
        SessionMemoryExportController controller = new SessionMemoryExportController();
        ReflectionTestUtils.setField(controller, "agentMemoryDirectory", dir);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("成功：entrypoint=memoryDir/MEMORY.md + 目录内全部顶层 .md 文件及内容（含入口，排除非 .md/子目录）")
    void success_returnsEntrypointAndMemoryFiles() throws Exception {
        // WHY: CC getAgentMemoryEntrypoint（agentMemory.ts:109-114）= join(getAgentMemoryDir,'MEMORY.md')，
        //   files 含入口 MEMORY.md 与 agent 会话期间写入的 notes.md（buildMemoryPrompt 读取的面）。
        //   若漏文件/漏内容 → 前端导出的会话记忆不完整；若 .snapshot-synced.json 混入 → 非记忆污染导出。
        Path memoryDir = tempDir.resolve("agent-memory").resolve("my-agent");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "# Persistent Agent Memory\n\n记忆正文");
        Files.writeString(memoryDir.resolve("notes.md"), "## 关键决策\n- 对齐 CC 导出面");
        // 非记忆文件与嵌套子目录必须排除（dirent.isFile() 顶层语义）
        Files.writeString(memoryDir.resolve(".snapshot-synced.json"), "{\"syncedFrom\":\"2026-08-23\"}");
        Files.createDirectories(memoryDir.resolve("subdir"));
        Files.writeString(memoryDir.resolve("subdir").resolve("nested.md"), "不应导出");

        Path entrypoint = memoryDir.resolve("MEMORY.md");
        mockMvc(newDirectory()).perform(get("/api/v1/session-memory/export")
                .param("agentType", "my-agent")
                .param("scope", "user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entrypoint").value(entrypoint.toString()))
            // MEMORY.md 恒首位（ASCII 大写 < 小写，路径排序）
            .andExpect(jsonPath("$.files.length()").value(2))
            .andExpect(jsonPath("$.files[0].path").value(entrypoint.toString()))
            .andExpect(jsonPath("$.files[0].content").value("# Persistent Agent Memory\n\n记忆正文"))
            .andExpect(jsonPath("$.files[1].path").value(memoryDir.resolve("notes.md").toString()))
            .andExpect(jsonPath("$.files[1].content").value("## 关键决策\n- 对齐 CC 导出面"));
    }

    @Test
    @DisplayName("记忆目录不存在 → 200 + entrypoint 仍返回预期路径 + files 空（CC 纯路径解析无存在性要求）")
    void missingDirectory_returnsEntrypointWithEmptyFiles() throws Exception {
        // WHY: agent 尚未写记忆（无 mkdir）时导出应给预期入口路径而非报错（agentMemory.ts:109-114
        //   纯路径解析；容错读语义 claudemd.ts:424-437 ENOENT 忽略）。若 500 → 前端首次导出阻断。
        Path entrypoint = tempDir.resolve("agent-memory").resolve("fresh-agent").resolve("MEMORY.md");
        mockMvc(newDirectory()).perform(get("/api/v1/session-memory/export")
                .param("agentType", "fresh-agent")
                .param("scope", "user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entrypoint").value(entrypoint.toString()))
            .andExpect(jsonPath("$.files.length()").value(0));
    }

    @Test
    @DisplayName("默认参数：agentType=general-purpose + scope=user（会话主 agent 语义，BuiltInAgents.GENERAL_PURPOSE）")
    void defaults_useMainAgentTypeAndUserScope() throws Exception {
        // WHY: 前端导出会话记忆多数为当前会话主 agent（general-purpose，SubagentExecutor.java:1271
        //   缺省类型）user scope（loadAgentsDir.java:299 快照面）——缺省参数直连导出，无需前端拼参。
        Path memoryDir = tempDir.resolve("agent-memory").resolve("general-purpose");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "主 agent 记忆");

        mockMvc(newDirectory()).perform(get("/api/v1/session-memory/export"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entrypoint").value(memoryDir.resolve("MEMORY.md").toString()))
            .andExpect(jsonPath("$.files.length()").value(1));
    }

    @Test
    @DisplayName("非法 scope → 400（CC agentMemory.ts:13 三值枚举 user/project/local，拼错基址拒绝）")
    void invalidScope_400() throws Exception {
        // WHY: scope 非枚举值（CC agentMemory.ts:13 AgentMemoryScope）会把目录名拼进错误基址；
        //   REST 以 400 拒绝，避免导出到错误位置。
        mockMvc(newDirectory()).perform(get("/api/v1/session-memory/export")
                .param("agentType", "my-agent")
                .param("scope", "team"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("agentType 空白 → 400（目录名缺失，无法解析记忆目录）")
    void blankAgentType_400() throws Exception {
        // WHY: agentType 是记忆目录名（sanitizeAgentTypeForPath 输入，agentMemory.ts:20-22），
        //   空白 → getAgentMemoryDir 拼出无效路径；REST 以 400 拒绝。
        mockMvc(newDirectory()).perform(get("/api/v1/session-memory/export")
                .param("agentType", " ")
                .param("scope", "user"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AgentMemoryDirectory 未接线 → 500 fail loud（记忆目录解析是本端点唯一职责，无静默降级）")
    void notWired_500() throws Exception {
        // WHY: 记忆目录解析是本端点唯一职责（对齐 MemoryController resolveEngine 语义）——
        //   未接线时返回空/降级会静默丢导出数据，fail loud 强制装配暴露。
        SessionMemoryExportController controller = new SessionMemoryExportController();
        MockMvc unWired = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        unWired.perform(get("/api/v1/session-memory/export")
                .param("agentType", "my-agent")
                .param("scope", "user"))
            .andExpect(status().isInternalServerError());
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // [批 4b-2 · 用户裁定 #14] PROJECT/LOCAL 必填 sessionId + 会话项目根解析（⛔ 不回落 config home/user.dir）
    // ────────────────────────────────────────────────────────────────────────────────

    /** 构造导出请求（sessionId null → 不带该 query 参，贴近前端「不传」的真实形态）。 */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exportRequest(
            String agentType, String scope, String sessionId) {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req =
            get("/api/v1/session-memory/export").param("agentType", agentType).param("scope", scope);
        return sessionId == null ? req : req.param("sessionId", sessionId);
    }

    /**
     * 在**另一条真实线程**上执行 MockMvc 请求并取回结果。
     *
     * <p>WHY（本改造的核心不变量）：本批要消灭的正是「会话态经 ThreadLocal/MDC 隐式传播」。
     * 若解析仍依赖调用线程上的任何 ThreadLocal，登记线程与请求线程不同就会解析失败 —— 本方法
     * 把请求放到独立线程，正是对该不变量的<b>真实</b>验证（非源码字面断言）。
     */
    private static MvcResult performOnWorkerThread(MockMvc mvc, String agentType, String scope, String sessionId)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "session-memory-export-test"));
        try {
            Future<MvcResult> future = pool.submit(() -> mvc.perform(exportRequest(agentType, scope, sessionId)).andReturn());
            return future.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 从响应 JSON 中取字段（JSON 会转义 Windows 反斜杠，故不能用原始串 contains 断路径）。 */
    private static Object jsonField(MvcResult res, String jsonPath) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), jsonPath);
    }

    @Test
    @DisplayName("scope=project 缺 sessionId → 400（改前：500「requireProjectRoot 抛」；⛔ 不回落 config home 拼假目录）")
    void projectScope_missingSessionId_400() throws Exception {
        // WHY: PROJECT 记忆目录本体 = <会话项目根>/.nexusai/agent-memory/<type>，没有会话项目根就
        //   **没有合法产物**。缺 sessionId 时正确响应是 400（请求不可满足），而不是
        //   ① 500（AgentMemoryDirectory.requireProjectRoot 抛 IllegalStateException，客户端无法区分
        //      自身请求错误与后端故障）② 回落 config home/user.dir 拼出 <configHome>/.nexusai/agent-memory
        //      假目录原样返回（批 4b-1 前行为：前端会把一处不存在的「双重嵌套假记忆目录」当真实导出）。
        mockMvc(newDirectory()).perform(exportRequest("my-agent", "project", null))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("scope=local 缺 sessionId → 400（local 基址同样锚在会话项目根，改前：500）")
    void localScope_missingSessionId_400() throws Exception {
        // WHY: LOCAL 基址 = <会话项目根>/.nexusai/agent-memory-local/<type>（或 remote mount）——
        //   与 PROJECT 同属「需要会话项目根」的 scope，缺值时同样必须 400 而非 500/假目录。
        mockMvc(newDirectory()).perform(exportRequest("my-agent", "local", null))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("scope=project + sessionId 解析不到会话项目根（无此会话）→ 400（⛔ 不回落 user.dir/config home）")
    void projectScope_unknownSessionId_400() throws Exception {
        // WHY: 合成/伪造/已删 sessionId（MCP 入站、standalone fork 等现造 id）在冻结表与 DB 都查不到。
        //   此时**绝不能**走「无会话出口」拿进程 user.dir 当项目根 —— 那正是本批要消灭的「冒充项目根」
        //   （会返回 <JVM 启动目录>/.nexusai/agent-memory/<type> 这种与请求方无关的目录）。
        mockMvc(newDirectory()).perform(exportRequest("my-agent", "project", "sess-does-not-exist"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("scope=project + 会话已绑定项目根 → 200 + entrypoint 落在 <会话项目根>/.nexusai/agent-memory/<type>（跨线程）")
    void projectScope_boundSession_exportsUnderSessionProjectRoot_acrossThread() throws Exception {
        // WHY: 导出必须落在**该会话**的项目记忆目录，且解析只能来自「跨线程可见的值」（会话冻结表）——
        //   本用例在测试线程登记绑定、在**另一条线程**发请求：若解析依赖任何 ThreadLocal 就会 MISS
        //   → 400/错目录。这正是 pick-either-of-two-wrong-answers 的判别实验。
        Path sessionProjectRoot = tempDir.resolve("session-project-root");
        Files.createDirectories(sessionProjectRoot);
        Path memoryDir = sessionProjectRoot.resolve(NexusaiPaths.getProjectDirName())
            .resolve("agent-memory").resolve("my-agent");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "会话项目根下的 agent 记忆");
        SessionProjectRoot.setForSession("sess-export-bound", sessionProjectRoot.toString());

        MvcResult res = performOnWorkerThread(mockMvc(newDirectory()), "my-agent", "project", "sess-export-bound");

        org.assertj.core.api.Assertions.assertThat(res.getResponse().getStatus()).isEqualTo(200);
        Path expectedEntrypoint = memoryDir.resolve("MEMORY.md");
        org.assertj.core.api.Assertions.assertThat(jsonField(res, "$.entrypoint"))
            .isEqualTo(expectedEntrypoint.toString());
        org.assertj.core.api.Assertions.assertThat(jsonField(res, "$.files[0].content"))
            .isEqualTo("会话项目根下的 agent 记忆");
    }

    @Test
    @DisplayName("scope=local + 会话已绑定项目根 → 200 + entrypoint 落在 <会话项目根>/.nexusai/agent-memory-local/<type>")
    void localScope_boundSession_exportsUnderSessionProjectRoot() throws Exception {
        // WHY: LOCAL scope 与 PROJECT 同锚会话项目根，但基址目录名不同（agent-memory-local）——
        //   若两者取用同一目录名，LOCAL 记忆会被写进团队共享的 PROJECT 目录（跨机/跨人污染）。
        Path sessionProjectRoot = tempDir.resolve("session-project-root-local");
        Files.createDirectories(sessionProjectRoot);
        Path memoryDir = sessionProjectRoot.resolve(NexusaiPaths.getProjectDirName())
            .resolve("agent-memory-local").resolve("my-agent");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "本机会话记忆");
        SessionProjectRoot.setForSession("sess-export-local", sessionProjectRoot.toString());

        MvcResult res = performOnWorkerThread(mockMvc(newDirectory()), "my-agent", "local", "sess-export-local");

        org.assertj.core.api.Assertions.assertThat(res.getResponse().getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(jsonField(res, "$.entrypoint"))
            .isEqualTo(memoryDir.resolve("MEMORY.md").toString());
    }

    @Test
    @DisplayName("正向对照：scope=user 缺 sessionId 仍 200（USER 基址 = memoryBase 存储基座，与 cwd 无关）")
    void userScope_missingSessionId_200_positiveControl() throws Exception {
        // WHY（正向对照，CLAUDE.md 规则十二/规则九）：上面三条 400 断言必须能与「端点整体坏了」区分开。
        //   USER scope 的基址是 memoryBase（config home 存储基座，**不是项目身份**）⇒ 不依赖会话，
        //   缺 sessionId 必须仍 200。若本用例也红，则那些 400 证明不了「scope 需要根」这一意图。
        Path memoryDir = tempDir.resolve("agent-memory").resolve("my-agent");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "用户级记忆");
        SessionProjectRoot.setForSession("sess-unused", tempDir.toString());

        mockMvc(newDirectory()).perform(exportRequest("my-agent", "user", null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entrypoint").value(memoryDir.resolve("MEMORY.md").toString()))
            .andExpect(jsonPath("$.files.length()").value(1));
    }
}
