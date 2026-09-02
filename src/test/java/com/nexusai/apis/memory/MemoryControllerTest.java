package com.nexusai.apis.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.context.ClaudemdMemoryType;
import com.nexusai.application.agent.context.MemoryFileInfo;
import com.nexusai.application.agent.memory.MemoryStorage;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.infra.security.BearerTokenAuthFilter;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [OPD-CM3-25] {@link MemoryController} 意图测试 · /memory 命令 REST 等价端点
 * （/memory 查看记忆 + 文件创建 + type-based 更新，对齐 CC memory.tsx 语义）。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：CC /memory 命令（memory.tsx:83-89）先 {@code clearMemoryFileCaches()}
 * + {@code await getMemoryFiles()} 预热后渲染 MemoryFileSelector（MemoryFileSelector.tsx:50-65
 * 现有文件 + 缺失 User/Project 槽位），选中后 mkdir configHome + writeFile 'wx' 幂等创建
 * （memory.tsx:21-42）。本测试钉死 REST 等价契约（type-based 三档）：
 * <ol>
 *   <li><b>GET /api/v1/memory/files[?sessionId=] 先预热再返回三档列表</b>——Managed（只读 editable=false）
 *       / User（可写 editable=true）/ Project（会话级 boundProject 下 PROJECT 文件，无 sessionId → 空）；
 *       若控制器未调 clearMemoryFileCaches + getMemoryFiles（MISS-1 缓存预热接线），view 拿到的会是
 *       陈旧的 getMemoryFiles 缓存；mock 验证调用锁死预热语义。</li>
 *   <li><b>GET 过滤 AutoMem/TeamMem 条目</b>——CC 以 folder options「Open auto-memory folder / Open
 *       team memory folder」承载 MEMORY.md（MemoryFileSelector.tsx:64-70），三档列表不展示。</li>
 *   <li><b>PUT /api/v1/memory/files[?sessionId=] type-based 覆盖写</b>——User 覆盖 {configHome}/CLAUDE.md；
 *       Project 带 sessionId + file 命中白名单（getMemoryFiles PROJECT 精确归一化集合）→ 覆盖写；
 *       Managed → 403 只读；白名单外 / 逃逸 / symlink → 400；文件不存在 → 404（创建走 POST /files）；
 *       content null → 400（空串合法=清空）。写后缓存失效（对齐 GET /files 预热 memory.tsx:86-87）。</li>
 *   <li><b>POST /api/v1/memory/files 幂等创建</b>——路径含 configHome → 递归 mkdir（memory.tsx:24-28）；
 *       writeFile 'wx' 已存在即 EEXIST 保留原内容（memory.tsx:32-41）；响应含 relativePath 提示
 *       （memory.tsx:56 "Opened memory file at ..."）。</li>
 *   <li><b>GET/PUT /api/v1/memory/config 状态行（toggle + dream 状态）</b>——对齐 CC
 *       MemoryFileSelector.tsx:206-254。</li>
 * </ol>
 */
class MemoryControllerTest {

    private static final String NEXUSAI_AUTO_DREAM_PROP = "NEXUSAI_AUTO_DREAM";
    /** 测试会话 ID（MDC 注入 + SessionProjectRoot.setForSession，驱动 CwdResolution boundProject 层）。 */
    private static final String TEST_SESSION = "memory-controller-test-session";

    /** G5：nexusai 自有根唯一 appName（claude-config 固定名会跨用例碰撞 → 静态自增）。 */
    private static final java.util.concurrent.atomic.AtomicInteger NEXUSAI_SEQ =
        new java.util.concurrent.atomic.AtomicInteger();

    private MemoryController controller;
    private ClaudemdEngine claudemdEngine;
    private MemoryStorage memoryStorage;
    private MockMvc mockMvc;
    private String previousAutoDreamProp;

    @TempDir
    Path tempDir;
    private Path configHome;
    private Path projectDir;
    private String nexusaiAppName;
    private String originalUserHome;   // G5：user.home 原值（NexusaiPaths 根隔离复位用）

    @BeforeEach
    void setUp() throws Exception {
        configHome = tempDir.resolve("claude-config");
        projectDir = tempDir.resolve("project");
        // [环境修复 · 2026-08-30] SessionProjectRoot.setForSession 校验「绝对路径 + 目录存在」，
        //   projectDir 不创建则绑定被拒绝 → boundProject() 回落 user.dir（@TempDir 跨盘致 relativize
        //   500 / 命中真实 .claude/CLAUDE.md 致 404 误判 200）。显式创建使绑定生效，Project 档测试
        //   真正走会话级 boundProject（作者原意）。
        Files.createDirectories(projectDir);
        // ClaudePaths 静态 configHome 覆写（getClaudeConfigHomeDir 指向临时目录，Java 无法进程内改 env）
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // Managed 档定位确定性：getManagedFilePath() 指向临时目录（避免依赖真实 C:\Program Files\ClaudeCode）
        ClaudePaths.setManagedFilePathOverride(tempDir.resolve("managed").toString());
        // G5：User 档 + settings.json 已迁移 nexusai 自有根（NexusaiPaths.getAppConfigHomeDir）→
        //   唯一 appName + user.home 隔离到 tempDir（防写真实 ~/.nexusai，且使 NexusaiPaths 根落临时目录）。
        nexusaiAppName = "nexusai-test-" + NEXUSAI_SEQ.incrementAndGet();
        NexusaiPaths.setAppNameOverride(nexusaiAppName);
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        controller = new MemoryController();
        claudemdEngine = mock(ClaudemdEngine.class);
        ReflectionTestUtils.setField(controller, "claudemdEngine", claudemdEngine);
        // dream 锁目录 = tempDir/memory（ConsolidationLock 锁 mtime 读取面）
        memoryStorage = new MemoryStorage(Paths.get(tempDir.toString(), "memory"));
        ReflectionTestUtils.setField(controller, "memoryStorage", memoryStorage);
        // 工作目录覆写：MDC sessionId + setForSession → CwdResolution boundProject 层（方案 b）。
        RequestContext.setSession(TEST_SESSION);
        SessionProjectRoot.setForSession(TEST_SESSION, projectDir.toString());
        // setControllerAdvice：GlobalExceptionHandler 将 ValidationException → 400 / ForbiddenException →
        // 403 / NotFoundException → 404（无 advice 则 500 传播为 ServletException）
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        // NEXUSAI_AUTO_DREAM 进程属性隔离（isAutoDreamEnabledByRuntimeChain 读 property 优先；
        // 清除防其他测试注入污染）
        previousAutoDreamProp = System.getProperty(NEXUSAI_AUTO_DREAM_PROP);
        System.clearProperty(NEXUSAI_AUTO_DREAM_PROP);
        // [V56] DB 静态桥接兜底清除——「无 DB 默认 true」用例依赖桥接为 null（前一用例泄漏 mapper
        //   会令默认开误判；防御性复位保证用例隔离，AutoDreamConsolidatorTest:94 同款惯例）
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);   // G5：复位 user.home（NexusaiPaths 根隔离）
            originalUserHome = null;
        }
        SessionProjectRoot.clearSession(TEST_SESSION);
        RequestContext.clear();
        if (previousAutoDreamProp != null) {
            System.setProperty(NEXUSAI_AUTO_DREAM_PROP, previousAutoDreamProp);
        } else {
            System.clearProperty(NEXUSAI_AUTO_DREAM_PROP);
        }
        // [V56] DB 静态桥接复位（防用例间泄漏）
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
    }

    /** 会话绑定 boundProject（CwdResolution.getOriginalCwdLayer() 经 MDC TEST_SESSION 解析；与控制器同源）。 */
    private String boundProject() {
        return CwdResolution.getOriginalCwdLayer();
    }

    @Test
    @DisplayName("GET /api/v1/memory/files → 三档结构（Managed 只读 / User 可写 / Project 会话级）+ 预热 verify")
    void listFiles_warmsCacheAndReturnsSlots() throws Exception {
        // 会话绑定 boundProject 下 PROJECT 文件（引擎加载面）+ User 文件落盘（GET 磁盘直读）
        // [D6] 项目级目录 = nexusai 自有（.nexusai）可写；另附一条 .claude 只读回落条目（editable=false）
        String existingPath = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        String claudeReadonlyPath = Paths.get(boundProject(), ".claude", "CLAUDE.md").toString();
        // G5：User 档 = nexusai 自有根（决策 D1）
        String userMemoryPath = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "CLAUDE.md").toString();
        String managedMemoryPath = Paths.get(tempDir.toString(), "managed", "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(userMemoryPath).getParent());
        Files.writeString(Paths.get(userMemoryPath), "# user disk");
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(existingPath, ClaudemdMemoryType.PROJECT, "# existing project claude.md", List.of()),
            MemoryFileInfo.of(claudeReadonlyPath, ClaudemdMemoryType.PROJECT, "# claude read-only", List.of())));

        mockMvc.perform(get("/api/v1/memory/files"))
            .andExpect(status().isOk())
            // Managed + User + Project(.nexusai 可写) + Project(.claude 只读) = 4
            .andExpect(jsonPath("$.length()").value(4))
            // [0] Managed：全局只读，磁盘无 → exists=false / editable=false / file=CLAUDE.md
            .andExpect(jsonPath("$[0].type").value("Managed"))
            .andExpect(jsonPath("$[0].path").value(managedMemoryPath))
            .andExpect(jsonPath("$[0].file").value("CLAUDE.md"))
            .andExpect(jsonPath("$[0].editable").value(false))
            .andExpect(jsonPath("$[0].exists").value(false))
            .andExpect(jsonPath("$[0].content").value(""))
            // [1] User：用户可写，磁盘存在 → exists=true / editable=true / content=磁盘内容
            .andExpect(jsonPath("$[1].type").value("User"))
            .andExpect(jsonPath("$[1].path").value(userMemoryPath))
            .andExpect(jsonPath("$[1].file").value("CLAUDE.md"))
            .andExpect(jsonPath("$[1].editable").value(true))
            .andExpect(jsonPath("$[1].exists").value(true))
            .andExpect(jsonPath("$[1].content").value("# user disk"))
            // [2] Project：会话级 boundProject 下 nexusai 自有（.nexusai）PROJECT 文件 → editable=true / file=相对路径
            .andExpect(jsonPath("$[2].type").value("Project"))
            .andExpect(jsonPath("$[2].path").value(existingPath))
            .andExpect(jsonPath("$[2].file").value(
                Paths.get(boundProject()).toAbsolutePath().normalize()
                    .relativize(Paths.get(existingPath).toAbsolutePath().normalize()).toString()))
            .andExpect(jsonPath("$[2].editable").value(true))
            .andExpect(jsonPath("$[2].exists").value(true))
            .andExpect(jsonPath("$[2].content").value("# existing project claude.md"))
            // [3] Project：.claude 段只读回落仍列出供只读展示（D6 containsClaudeDir → editable=false）
            .andExpect(jsonPath("$[3].type").value("Project"))
            .andExpect(jsonPath("$[3].path").value(claudeReadonlyPath))
            .andExpect(jsonPath("$[3].file").value(
                Paths.get(boundProject()).toAbsolutePath().normalize()
                    .relativize(Paths.get(claudeReadonlyPath).toAbsolutePath().normalize()).toString()))
            .andExpect(jsonPath("$[3].editable").value(false))
            .andExpect(jsonPath("$[3].exists").value(true))
            .andExpect(jsonPath("$[3].content").value("# claude read-only"));

        // [D6] .claude 只读回落仅展示，磁盘不落盘（nexusai 只写自有根 .nexusai）
        assertFalse(Files.exists(Paths.get(boundProject(), ".claude")),
            ".claude 段不得被 nexusai 落盘（只读回落）");

        // MISS-1 缓存预热（memory.tsx:86-87）：clearMemoryFileCaches + getMemoryFiles 均被调用
        verify(claudemdEngine).clearMemoryFileCaches();
        verify(claudemdEngine).getMemoryFiles(false);
    }

    @Test
    @DisplayName("GET /api/v1/memory/files → 无 sessionId（query/MDC 全空）→ Project 档空列表，仅 Managed+User")
    void listFiles_noSessionId_projectEmpty() throws Exception {
        RequestContext.clear(); // 三源（query/MDC）全空 → 读宽容：Project 档不 400，返回空

        mockMvc.perform(get("/api/v1/memory/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].type").value("Managed"))
            .andExpect(jsonPath("$[1].type").value("User"))
            .andExpect(jsonPath("$[?(@.type=='Project')]").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/memory/files → query ?sessionId= 驱动 Project 档（无 MDC 时 query 生效）")
    void listFiles_querySessionId_drivesProjectTier() throws Exception {
        RequestContext.clear(); // 清 MDC → 仅靠 query ?sessionId=
        // 用 projectDir 直接（boundProject() 依赖 MDC，已清 → 会回落 user.dir 跨盘，relativize 抛异常）
        String existingPath = Paths.get(projectDir.toString(), ".claude", "CLAUDE.md").toString();
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(existingPath, ClaudemdMemoryType.PROJECT, "# project", List.of())));

        mockMvc.perform(get("/api/v1/memory/files").param("sessionId", TEST_SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.type=='Project')]").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/memory/files → 过滤 AutoMem/TeamMem 条目（三档不含），User/Project 正常")
    void listFiles_filtersAutoMemAndTeamMemEntries() throws Exception {
        // G5：User 档 = nexusai 自有根（决策 D1）；AutoMem/TeamMem 为引擎假条目（按 type 过滤，路径仅占位）
        String userMemoryPath = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "CLAUDE.md").toString();
        // [D6 严格化] PROJECT 样例用 .nexusai 可写条目（.claude 段 editable=false，非本用例意图）
        String existingPath = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        String autoMemPath = Paths.get(configHome.toString(), "MEMORY.md").toString();
        String teamMemPath = Paths.get(configHome.toString(), "team", "MEMORY.md").toString();
        Files.createDirectories(Paths.get(userMemoryPath).getParent());
        Files.writeString(Paths.get(userMemoryPath), "# user");
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(userMemoryPath, ClaudemdMemoryType.USER, "# user", List.of()),
            MemoryFileInfo.of(existingPath, ClaudemdMemoryType.PROJECT, "# project", List.of()),
            MemoryFileInfo.of(autoMemPath, ClaudemdMemoryType.AUTO_MEM, "# auto mem", List.of()),
            MemoryFileInfo.of(teamMemPath, ClaudemdMemoryType.TEAM_MEM, "# team mem", List.of())));

        mockMvc.perform(get("/api/v1/memory/files"))
            .andExpect(status().isOk())
            // Managed + User + Project = 3（AutoMem/TeamMem 不在三档）
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].type").value("Managed"))
            .andExpect(jsonPath("$[1].type").value("User"))
            .andExpect(jsonPath("$[1].exists").value(true))
            .andExpect(jsonPath("$[2].type").value("Project"))
            .andExpect(jsonPath("$[2].exists").value(true))
            .andExpect(jsonPath("$[?(@.type=='AutoMem')]").isEmpty())
            .andExpect(jsonPath("$[?(@.type=='TeamMem')]").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/memory/files → 仅 AutoMem/TeamMem 文件时只返回 Managed+User（Project 空）")
    void listFiles_onlyAutoMemAndTeamMemReturnsOnlySlots() throws Exception {
        String autoMemPath = Paths.get(configHome.toString(), "MEMORY.md").toString();
        String teamMemPath = Paths.get(configHome.toString(), "team", "MEMORY.md").toString();
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(autoMemPath, ClaudemdMemoryType.AUTO_MEM, "# auto mem", List.of()),
            MemoryFileInfo.of(teamMemPath, ClaudemdMemoryType.TEAM_MEM, "# team mem", List.of())));

        mockMvc.perform(get("/api/v1/memory/files"))
            .andExpect(status().isOk())
            // 无 PROJECT 文件 → Project 档空；仅 Managed + User
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].type").value("Managed"))
            .andExpect(jsonPath("$[1].type").value("User"))
            .andExpect(jsonPath("$[?(@.type=='AutoMem')]").isEmpty())
            .andExpect(jsonPath("$[?(@.type=='TeamMem')]").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/memory/files → claudemdEngine 未接线 → 500（fail loud）")
    void listFiles_engineNotWiredIs500() throws Exception {
        ReflectionTestUtils.setField(controller, "claudemdEngine", null);

        mockMvc.perform(get("/api/v1/memory/files"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("POST /api/v1/memory/files → 路径含 nexusai home 递归 mkdir + 新建文件 created=true + relativePath 提示")
    void createFile_createsInConfigHome() throws Exception {
        // G5：User 槽位 = nexusai 自有根（决策 D1，memory.tsx:24-28 configHome 镜像）
        String path = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "CLAUDE.md").toString();

        mockMvc.perform(post("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"path\":\"" + escapeJson(path) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value(path))
            .andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.content").value(""))
            // memory.tsx:56：onDone "Opened memory file at {relativePath}"
            .andExpect(jsonPath("$.message", Matchers.startsWith("Opened memory file at ")))
            .andExpect(jsonPath("$.relativePath").isNotEmpty());

        // memory.tsx:24-28：nexusai home 递归创建 + memory.tsx:33-36：writeFile 'wx' 空文件落盘
        assertTrue(Files.isDirectory(Paths.get(NexusaiPaths.getAppConfigHomeDir())));
        assertTrue(Files.exists(Paths.get(path)));
        assertEquals("", Files.readString(Paths.get(path)));
    }

    @Test
    @DisplayName("POST /api/v1/memory/files → 文件已存在 EEXIST 保留原内容 created=false")
    void createFile_preservesExistingContent() throws Exception {
        // G5：User 槽位 = nexusai 自有根
        String path = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        Files.writeString(Paths.get(path), "# existing content");

        mockMvc.perform(post("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"path\":\"" + escapeJson(path) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(false))
            // memory.tsx:37-41 EEXIST 捕获保留原内容（不覆盖）
            .andExpect(jsonPath("$.content").value("# existing content"));

        assertEquals("# existing content", Files.readString(Paths.get(path)));
    }

    @Test
    @DisplayName("POST /api/v1/memory/files → path 缺失 → 400")
    void createFile_missingPathIs400() throws Exception {
        mockMvc.perform(post("/api/v1/memory/files").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/memory/files → 白名单外任意绝对路径 → 400（IMP-MV2-17 拒绝绝对路径逃逸）")
    void createFile_rejectsAbsolutePathEscape() throws Exception {
        // CC 创建面 = 选择器可达路径（user/project 槽位 + 现有记忆文件），无自由路径输入；
        // 任意绝对路径（如 /tmp/evil.txt）在创建面外 → 400，不得落盘
        String evilPath = Paths.get(tempDir.toString(), "evil", "payload.txt").toString();

        mockMvc.perform(post("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"path\":\"" + escapeJson(evilPath) + "\"}"))
            .andExpect(status().isBadRequest());

        assertFalse(Files.exists(Paths.get(evilPath)), "白名单外路径不得创建文件");
    }

    @Test
    @DisplayName("POST /api/v1/memory/files → `..` 归一化逃逸出记忆面 → 400（不落盘 configHome 外）")
    void createFile_rejectsNormalizedParentTraversalEscape() throws Exception {
        // configHome/../evil.txt 经 toAbsolutePath().normalize() = tempDir/evil.txt（槽位外）→ 拒绝，
        // 防 `..` 逃逸在 configHome 外落盘（白名单比较基于归一化路径）
        String escapePath = Paths.get(configHome.toString(), "..", "evil.txt").toString();

        mockMvc.perform(post("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"path\":\"" + escapeJson(escapePath) + "\"}"))
            .andExpect(status().isBadRequest());

        assertFalse(Files.exists(Paths.get(tempDir.toString(), "evil.txt")),
            "归一化逃逸路径不得在记忆面外落盘");
    }

    @Test
    @DisplayName("POST /api/v1/memory/files → 现有记忆文件路径（getMemoryFiles 列表内）→ 放行 EEXIST no-op")
    void createFile_allowsExistingMemoryFilePath() throws Exception {
        // CC 选择器选项含现有记忆文件（MemoryFileSelector.tsx:50-65）——POST 到已存在文件
        // 为 EEXIST 幂等 no-op（memory.tsx:37-41），白名单须放行该面
        // [D6] 项目级目录 = nexusai 自有（.nexusai，决策 D1/D6）；.claude 段不可创建（恒 400）→ 用 .nexusai
        String existingPath = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(existingPath).getParent());
        Files.writeString(Paths.get(existingPath), "# managed content");
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(existingPath, ClaudemdMemoryType.PROJECT, "# managed content", List.of())));

        mockMvc.perform(post("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"path\":\"" + escapeJson(existingPath) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(false))
            .andExpect(jsonPath("$.content").value("# managed content"));

        assertEquals("# managed content", Files.readString(Paths.get(existingPath)));
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files → User 覆盖写 {nexusaiHome}/CLAUDE.md 成功 + 返回 type/file/content/message")
    void updateFile_user_overwritesConfigHomeClaudeMd() throws Exception {
        // WHY（type-based User 档）：User → 主文件唯一（nexusaiHome/CLAUDE.md，决策 D1），file 忽略；
        // 覆盖写（CC writeFile flag 'w' 等价），文件须已存在（创建走 POST /files）。
        String path = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        Files.writeString(Paths.get(path), "# old user");

        mockMvc.perform(put("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"User\",\"content\":\"# new user\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("User"))
            .andExpect(jsonPath("$.file").value("CLAUDE.md"))
            .andExpect(jsonPath("$.path").value(path))
            .andExpect(jsonPath("$.content").value("# new user"))
            .andExpect(jsonPath("$.message", Matchers.startsWith("Updated memory file at ")));

        assertEquals("# new user", Files.readString(Paths.get(path)));
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files?sessionId= → Project file 命中白名单覆盖写成功 + 写后缓存失效")
    void updateFile_project_writesWhitelistedProjectFile() throws Exception {
        // WHY（type-based Project 档）：会话级 boundProject 下白名单（getMemoryFiles PROJECT 精确集合）
        // 命中 → 覆盖写；白名单归一化精确比对防逃逸。写后 clearMemoryFileCaches（对齐 GET /files 预热）。
        // [D6] 项目级目录 = nexusai 自有（.nexusai，决策 D1/D6）；.claude 段不参与白名单（恒 400）→ 用 .nexusai
        String path = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(path).getParent());
        Files.writeString(Paths.get(path), "# old project");
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(path, ClaudemdMemoryType.PROJECT, "# old project", List.of())));

        // file = 相对 boundProject 的 .nexusai 路径（D6 白名单仅 nexusai 可写面）
        String projectFile = NexusaiPaths.getProjectDirName() + "/CLAUDE.md";
        mockMvc.perform(put("/api/v1/memory/files")
                .param("sessionId", TEST_SESSION)
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"Project\",\"file\":\"" + projectFile + "\",\"content\":\"# new project\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("Project"))
            .andExpect(jsonPath("$.file").value(projectFile))
            .andExpect(jsonPath("$.content").value("# new project"));

        assertEquals("# new project", Files.readString(Paths.get(path)));
        // 白名单预取 1 次 + 写后失效 1 次（至少触发，验证写后缓存失效接线）
        verify(claudemdEngine, atLeastOnce()).clearMemoryFileCaches();
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files → Managed 全局只读 → 403（ForbiddenException）")
    void updateFile_managedReadOnly403() throws Exception {
        mockMvc.perform(put("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"Managed\",\"content\":\"# x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files → Project 缺 sessionId（三源全空）→ 400")
    void updateFile_projectMissingSessionId400() throws Exception {
        RequestContext.clear(); // body/query/MDC 三源全空

        mockMvc.perform(put("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"Project\",\"file\":\".claude/CLAUDE.md\",\"content\":\"# x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files?sessionId= → Project file `..` 逃逸出 boundProject → 400（不落盘）")
    void updateFile_projectParentTraversal400() throws Exception {
        // WHY（白名单精确归一化比对）：targetPath=boundProject/../evil.txt 归一化后 = tempDir/evil.txt，
        // 不在 PROJECT 白名单集合 → 400，防 `..` 逃逸在记忆面外落盘。
        // [D6 严格化] 白名单必须用 .nexusai 可写条目（.claude 段被 containsClaudeDir 跳过 → 白名单空则
        // 任何路径都 400，测试空转失意图）；用 nexusai 条目才能真正走到 `..` 归一化逃逸比对分支。
        String existingPath = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(existingPath).getParent());
        Files.writeString(Paths.get(existingPath), "# old");
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(existingPath, ClaudemdMemoryType.PROJECT, "# old", List.of())));

        mockMvc.perform(put("/api/v1/memory/files")
                .param("sessionId", TEST_SESSION)
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"Project\",\"file\":\"../evil.txt\",\"content\":\"# hijack\"}"))
            .andExpect(status().isBadRequest());

        assertFalse(Files.exists(Paths.get(tempDir.toString(), "evil.txt")),
            "归一化逃逸路径不得在记忆面外落盘");
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files?sessionId= → Project file 绝对路径注入 → 400（不落盘）")
    void updateFile_projectAbsolutePath400() throws Exception {
        // WHY（防绝对路径注入）：file 传绝对路径（tempDir/other/CLAUDE.md）→ Paths.get(boundProject, abs)
        // resolve 返回 abs（绝对覆盖）→ 归一化后不在白名单 → 400。
        // [D6 严格化] 白名单用 .nexusai 可写条目（.claude 段被跳过 → 白名单空则测试空转失意图）。
        String existingPath = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(existingPath).getParent());
        Files.writeString(Paths.get(existingPath), "# old");
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(existingPath, ClaudemdMemoryType.PROJECT, "# old", List.of())));
        String absPath = Paths.get(tempDir.toString(), "other", "CLAUDE.md").toString();

        mockMvc.perform(put("/api/v1/memory/files")
                .param("sessionId", TEST_SESSION)
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"Project\",\"file\":\"" + escapeJson(absPath)
                    + "\",\"content\":\"# hijack\"}"))
            .andExpect(status().isBadRequest());

        assertFalse(Files.exists(Paths.get(absPath)), "绝对路径注入不得落盘");
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files?sessionId= → Project file 白名单外相对路径 → 400")
    void updateFile_projectOutsideWhitelist400() throws Exception {
        // WHY（精确集合比对，非前缀匹配）：file 相对 boundProject 但不在 PROJECT 白名单集合 → 400。
        // [D6 严格化] 白名单用 .nexusai 可写条目（.claude 段被跳过 → 白名单空则测试空转失意图）。
        String existingPath = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(existingPath).getParent());
        Files.writeString(Paths.get(existingPath), "# old");
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(existingPath, ClaudemdMemoryType.PROJECT, "# old", List.of())));

        mockMvc.perform(put("/api/v1/memory/files")
                .param("sessionId", TEST_SESSION)
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"Project\",\"file\":\"docs/other.md\",\"content\":\"# hijack\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files?sessionId= → Project 白名单内但磁盘文件不存在 → 404（创建走 POST）")
    void updateFile_projectWhitelistedButMissing404() throws Exception {
        // WHY（不创建语义）：本端点只更新已存在文件（CC writeFile 'w'）；白名单命中但磁盘无文件 → 404
        // （创建仍走 POST /files 'wx'）——若实现 upsert 创建，前端误删可被静默重建，违背「创建走 POST」。
        String existingPath = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        // 不创建磁盘文件
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(existingPath, ClaudemdMemoryType.PROJECT, "# old", List.of())));

        mockMvc.perform(put("/api/v1/memory/files")
                .param("sessionId", TEST_SESSION)
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"Project\",\"file\":\"" + NexusaiPaths.getProjectDirName()
                    + "/CLAUDE.md\",\"content\":\"# new\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files → content 缺失（body 无 content）→ 400（不写盘）")
    void updateFile_missingContent400() throws Exception {
        mockMvc.perform(put("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"User\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files → type 缺失 → 400")
    void updateFile_typeMissing400() throws Exception {
        mockMvc.perform(put("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"content\":\"# x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files → type 非法（不在 Managed/User/Project）→ 400")
    void updateFile_typeInvalid400() throws Exception {
        mockMvc.perform(put("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"Foo\",\"content\":\"# x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/memory/files?sessionId= → Project target 为 symlink → 400；Windows 无法建 symlink 时普通文件不被误拒")
    void updateFile_projectSymlinkRejectedOrRegularPasses() throws Exception {
        // WHY（防 symlink 指向白名单外）：白名单命中但 target 为符号链接 → 400（安全纵深层）。
        // Windows 建 symlink 需管理员/开发者模式 —— 真实创建失败时改为逻辑测试：普通文件不被
        // isSymbolicLink 检查误拒（覆盖写成功）。
        // [D6] 项目级目录 = nexusai 自有（.nexusai）；.claude 段不参与白名单 → 用 .nexusai 才达 symlink 检查层
        String linkPath = Paths.get(boundProject(), NexusaiPaths.getProjectDirName(), "CLAUDE.md").toString();
        Files.createDirectories(Paths.get(linkPath).getParent());
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of(
            MemoryFileInfo.of(linkPath, ClaudemdMemoryType.PROJECT, "# old", List.of())));

        boolean symlinkCreated;
        try {
            Files.createSymbolicLink(Paths.get(linkPath),
                Paths.get(tempDir.toString(), "outside", "CLAUDE.md"));
            symlinkCreated = true;
        } catch (Exception e) {
            // Windows 未开开发者模式/无管理员权限 → 真实 symlink 创建失败（不伪造断言）
            symlinkCreated = false;
        }

        if (symlinkCreated) {
            mockMvc.perform(put("/api/v1/memory/files")
                    .param("sessionId", TEST_SESSION)
                    .contentType(APPLICATION_JSON)
                    .content("{\"type\":\"Project\",\"file\":\"" + NexusaiPaths.getProjectDirName()
                        + "/CLAUDE.md\",\"content\":\"# hijack\"}"))
                .andExpect(status().isBadRequest());
        } else {
            // 逻辑测试：真实普通文件 → isSymbolicLink=false → 覆盖写成功（证明检查无假阳性）
            Files.writeString(Paths.get(linkPath), "# old");
            mockMvc.perform(put("/api/v1/memory/files")
                    .param("sessionId", TEST_SESSION)
                    .contentType(APPLICATION_JSON)
                    .content("{\"type\":\"Project\",\"file\":\"" + NexusaiPaths.getProjectDirName()
                        + "/CLAUDE.md\",\"content\":\"# new\"}"))
                .andExpect(status().isOk());
            assertEquals("# new", Files.readString(Paths.get(linkPath)));
        }
    }

    @Test
    @DisplayName("/api/v1/memory/** → 无 Bearer token → 401（IMP-MV2-17 鉴权收敛，filter 覆盖 memory 端点族）")
    void endpoints_requireBearerToken401() throws Exception {
        // standalone MockMvc 默认不挂 filter —— 本用例独立装配 BearerTokenAuthFilter
        // （requireOAuthAuth=true deny-all），验证 /memory 端点族已被鉴权面覆盖
        MockMvc authMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new BearerTokenAuthFilter(
                mock(AccountOAuthTokenService.class), new ObjectMapper(), true))
            .build();

        authMvc.perform(get("/api/v1/memory/files"))
            .andExpect(status().isUnauthorized());
        authMvc.perform(post("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"path\":\"/tmp/whatever.md\"}"))
            .andExpect(status().isUnauthorized());
        authMvc.perform(put("/api/v1/memory/files")
                .contentType(APPLICATION_JSON)
                .content("{\"type\":\"User\",\"content\":\"# x\"}"))
            .andExpect(status().isUnauthorized());
        authMvc.perform(get("/api/v1/memory/config"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/memory/files → 携带有效 Bearer token → 放行 200")
    void endpoints_withValidBearerTokenPasses() throws Exception {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        AccountOAuthToken valid = new AccountOAuthToken();
        valid.setProvider("github");
        valid.setIdentity("alice");
        valid.setAccessToken("valid-token");
        valid.setExpiresAt(System.currentTimeMillis() + 10 * 60 * 1000L);
        when(tokenService.readByAccessToken("valid-token")).thenReturn(valid);
        when(claudemdEngine.getMemoryFiles(false)).thenReturn(List.of());

        MockMvc authMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new BearerTokenAuthFilter(tokenService, new ObjectMapper(), true))
            .build();

        authMvc.perform(get("/api/v1/memory/files")
                .header("Authorization", "Bearer valid-token"))
            .andExpect(status().isOk());

    }

    @Test
    @DisplayName("GET /api/v1/memory/files → claudemdEngine 未接线 → 500（fail loud · resolveEngine）")
    void listFiles_unwiredEngineIs500() throws Exception {
        // WHY（补盲 · MM-F2 R-4）：resolveEngine null 分支 0 测试。
        // CC memory.tsx:83-89 getMemoryFiles() 抛错 → call 抛错（无 catch 静默）—— Java fail loud
        // IllegalStateException → GlobalExceptionHandler 兜底 500。
        MemoryController unwired = new MemoryController();
        MockMvc bareMvc = MockMvcBuilders.standaloneSetup(unwired)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        bareMvc.perform(get("/api/v1/memory/files"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("GET /api/v1/memory/files → 引擎 getMemoryFiles 抛异常 → 500（CC getMemoryFiles 失败即命令失败）")
    void listFiles_engineFailureIs500() throws Exception {
        when(claudemdEngine.getMemoryFiles(false)).thenThrow(new RuntimeException("disk unavailable"));

        mockMvc.perform(get("/api/v1/memory/files"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("POST /api/v1/memory/files → mkdir nexusai home 失败 → 500（memory.tsx:24-28 IO 失败）")
    void createFile_mkdirConfigHomeFailureIs500() throws Exception {
        // WHY（补盲 · MM-F2 R-4）：mkdir 失败分支 0 测试。
        // G5 适配：User 槽位 = nexusai 自有根（决策 D1）。把 nexusai home 本身占为普通文件 →
        //   ensureMemoryHome createDirectories 抛 IOException → RuntimeException → 500。
        Path blockedHome = Paths.get(NexusaiPaths.getAppConfigHomeDir());
        Files.writeString(blockedHome, "i am a file, not a directory");
        try {
            String path = Paths.get(blockedHome.toString(), "CLAUDE.md").toString();
            mockMvc.perform(post("/api/v1/memory/files")
                    .contentType(APPLICATION_JSON)
                    .content("{\"path\":\"" + escapeJson(path) + "\"}"))
                .andExpect(status().isInternalServerError());
        } finally {
            Files.deleteIfExists(blockedHome);
        }
    }

    @Test
    @DisplayName("getRelativeMemoryPath → home/cwd 相对取短（对齐 MemoryUpdateNotification.tsx:7-20）")
    void relativeMemoryPath_prefersShorter() {
        String home = "C:/Users/test";
        String cwd = "C:/work/proj";

        // 路径在 home 下 → ~/CLAUDE.md
        String underHome = MemoryController.getRelativeMemoryPath("C:/Users/test/CLAUDE.md", home, cwd);
        assertEquals("~/CLAUDE.md", underHome);

        // 路径在 cwd 下 → ./CLAUDE.md（相对 cwd 更短）
        String underCwd = MemoryController.getRelativeMemoryPath("C:/work/proj/CLAUDE.md", home, cwd);
        assertEquals("./CLAUDE.md", underCwd);

        // 两不适用 → 绝对路径
        String elsewhere = MemoryController.getRelativeMemoryPath("C:/elsewhere/mem.md", home, cwd);
        assertEquals("C:/elsewhere/mem.md", elsewhere);
    }

    @Test
    @DisplayName("GET /api/v1/memory/config → 未配置默认（无 DB 无 env）：autoMemory=true / autoDream=true / dreamStatus=never / lastConsolidatedAtMs=0")
    void config_get_returnsDefaultsWhenUnconfigured() throws Exception {
        // 注：isAutoMemoryEnabled 首层 env 门（CLAUDE_CODE_DISABLE_AUTO_MEMORY 等）假定测试 JVM
        // 未设置（AutoMemPathsTest:588 同款约定）；NEXUSAI_AUTO_DREAM 属性已由 setUp 清除；
        // DB 静态桥接未注入（null）→ 无 DB 值 → autoDream 回落默认 true（V56 用户拍板默认开，
        // 覆盖旧 OPD-CM3-24 Q1「未配置恒 false」——P0 矛盾修复：与运行时门控一致）。
        mockMvc.perform(get("/api/v1/memory/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.autoMemoryEnabled").value(true))
            .andExpect(jsonPath("$.autoDreamEnabled").value(true))
            .andExpect(jsonPath("$.dreamStatus").value("never"))
            .andExpect(jsonPath("$.lastConsolidatedAtMs").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/memory/config → DB settings 列配置值生效（autoMemory=false / autoDream=true · V56 DB 主控）")
    void config_get_readsTogglesFromDbSettingsColumn() throws Exception {
        // WHY（V56 用户拍板 2026-08-30）：autoDreamEnabled 改由 DB settings 列 auto_dream_enabled
        //   主控，弃 settings.json 文件承载键——旧 settings.json 三源链断言已失效（config_put_*
        //   不再写文件键会 NPE）。本用例以 bridgeSettingsMapper + mock SettingsMapper 断言 DB 列
        //   读链（参考 AutoDreamConsolidatorTest 同款模式）。autoMemoryEnabled 同走 DB 列（V34）。
        SettingsRecord rec = new SettingsRecord();
        rec.setId(1);
        rec.setAutoMemoryEnabled(false);
        rec.setAutoDreamEnabled(true);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
        try {
            mockMvc.perform(get("/api/v1/memory/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoMemoryEnabled").value(false))
                .andExpect(jsonPath("$.autoDreamEnabled").value(true));
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
        }
    }

    @Test
    @DisplayName("GET /api/v1/memory/config → DB settings 列 auto_dream_enabled=true → autoDream=true（DB 主控）")
    void config_get_dbAutoDreamTrueReturnsTrue() throws Exception {
        SettingsRecord rec = new SettingsRecord();
        rec.setId(1);
        rec.setAutoDreamEnabled(true);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
        try {
            mockMvc.perform(get("/api/v1/memory/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoDreamEnabled").value(true));
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
        }
    }

    @Test
    @DisplayName("GET /api/v1/memory/config → DB settings 列 auto_dream_enabled=false → autoDream=false（DB 主控关闭）")
    void config_get_dbAutoDreamFalseReturnsFalse() throws Exception {
        SettingsRecord rec = new SettingsRecord();
        rec.setId(1);
        rec.setAutoDreamEnabled(false);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
        try {
            mockMvc.perform(get("/api/v1/memory/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoDreamEnabled").value(false));
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
        }
    }

    @Test
    @DisplayName("GET /api/v1/memory/config → 锁文件存在：dreamStatus=last_ran + lastConsolidatedAtMs=锁 mtime")
    void config_get_dreamStatusLastRanFromLockMtime() throws Exception {
        Path memoryDir = Paths.get(tempDir.toString(), "memory");
        Files.createDirectories(memoryDir);
        Path lock = memoryDir.resolve(".consolidate-lock");
        Files.writeString(lock, "12345");
        long mtime = 1_700_000_000_000L;
        Files.setLastModifiedTime(lock, FileTime.fromMillis(mtime));

        mockMvc.perform(get("/api/v1/memory/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dreamStatus").value("last_ran"))
            .andExpect(jsonPath("$.lastConsolidatedAtMs").value(mtime));
    }

    @Test
    @DisplayName("PUT /api/v1/memory/config → 落 DB settings 列（autoDream 不再写文件）+ GET 闭环生效")
    void config_put_persistsTogglesToDbAndClosesReadWriteLoop() throws Exception {
        // WHY（V56 用户拍板 2026-08-30）：autoDreamEnabled 仅落 DB settings 列（不再写 settings.json
        //   文件承载键）——旧「写 settings.json + 读文件键」断言在 V56 后读 autoDreamEnabled 会 NPE
        //   （文件不再含该键）。autoMemoryEnabled 仍 DB + settings.json 双写（读链回落兼容）。
        // G5：settings.json 承载于 nexusai 自有根（BundledSkillEnabledGates.java:368）
        Files.createDirectories(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        Path settingsFile = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json");
        Files.writeString(settingsFile, "{\"theme\":\"dark\",\"permissions\":{\"allow\":[\"Bash\"]}}");
        SettingsRecord rec = new SettingsRecord();
        rec.setId(1);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
        try {
            mockMvc.perform(put("/api/v1/memory/config")
                    .contentType(APPLICATION_JSON)
                    .content("{\"autoMemoryEnabled\":false,\"autoDreamEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoMemoryEnabled").value(false))
                .andExpect(jsonPath("$.autoDreamEnabled").value(true));

            // DB 写链：autoMemory + autoDream 均落 DB settings 列（V56 DB 主控）
            assertFalse(rec.getAutoMemoryEnabled());
            assertTrue(rec.getAutoDreamEnabled());

            // settings.json 写链：autoMemory 仍双写（读链回落兼容）；autoDream 不再写文件（V56 弃用）
            //   CC updateSettingsForSource mergeWith 语义：未知键原样保留（settings.ts:473-495）
            JsonNode root = new ObjectMapper().readTree(settingsFile.toFile());
            assertEquals("dark", root.get("theme").asText());
            assertEquals("Bash", root.get("permissions").get("allow").get(0).asText());
            assertFalse(root.get("autoMemoryEnabled").asBoolean());
            assertFalse(root.has("autoDreamEnabled"), "V56 autoDreamEnabled 不再写 settings.json 文件承载键");

            // toggle 读写闭环：GET 读 DB settings 列（BundledSkillEnabledGates 桥接）
            mockMvc.perform(get("/api/v1/memory/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoMemoryEnabled").value(false))
                .andExpect(jsonPath("$.autoDreamEnabled").value(true));
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
        }
    }

    @Test
    @DisplayName("PUT /api/v1/memory/config → 部分更新：仅写提供的键到 DB，null 不触碰")
    void config_put_partialOnlyWritesProvidedKey() throws Exception {
        // WHY（V56）：autoDreamEnabled 部分更新落 DB 列；未提供的 autoMemoryEnabled → null（不触碰）。
        //   旧「读 settings.json 的 autoDreamEnabled 键」断言在 V56 后 NPE（文件不再写该键）。
        SettingsRecord rec = new SettingsRecord();
        rec.setId(1);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
        try {
            mockMvc.perform(put("/api/v1/memory/config")
                    .contentType(APPLICATION_JSON)
                    .content("{\"autoDreamEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoDreamEnabled").value(false));

            // DB 写链：autoDreamEnabled=false 落 DB 列；autoMemoryEnabled 未提供 → null（不触碰）
            assertFalse(rec.getAutoDreamEnabled());
            assertNull(rec.getAutoMemoryEnabled(), "未提供的键不得被写入");

            // 部分更新未提供 autoMemory → 不触发 settings.json 写（文件不应存在）
            assertFalse(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json")),
                "部分更新仅 autoDream → 不应触发 settings.json 写");
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
        }
    }

    @Test
    @DisplayName("PUT /api/v1/memory/config → 空 body（全 null）不触发写盘，返回当前状态")
    void config_put_emptyBodyDoesNotWrite() throws Exception {
        mockMvc.perform(put("/api/v1/memory/config").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dreamStatus").value("never"));

        assertFalse(Files.exists(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json")),
            "空 partial（全 null）不得触发写盘");
    }

    @Test
    @DisplayName("GET /api/v1/memory/config → memoryStorage 未接线 → 500（fail loud）")
    void config_get_memoryStorageNotWiredIs500() throws Exception {
        ReflectionTestUtils.setField(controller, "memoryStorage", null);

        mockMvc.perform(get("/api/v1/memory/config"))
            .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("PUT /api/v1/memory/config → 发射 tengu_auto_memory_toggled / tengu_auto_dream_toggled（MemoryFileSelector.tsx:244/:251 logEvent 等价）")
    void config_put_emitsToggleTelemetry() throws Exception {
        Telemetry telemetry = new Telemetry();
        ReflectionTestUtils.setField(controller, "telemetry", telemetry);

        mockMvc.perform(put("/api/v1/memory/config")
                .contentType(APPLICATION_JSON)
                .content("{\"autoMemoryEnabled\":false,\"autoDreamEnabled\":true}"))
            .andExpect(status().isOk());

        // CC logEvent('tengu_auto_memory_toggled'|'tengu_auto_dream_toggled', {enabled})
        assertEquals(1, telemetry.getCounter("tengu_auto_memory_toggled"));
        assertEquals(1, telemetry.getCounter("tengu_auto_dream_toggled"));
    }

    @Test
    @DisplayName("PUT /api/v1/memory/config → 部分更新仅发射对应 toggle 事件（未提供的键不发射）")
    void config_put_partialEmitsOnlyProvidedToggle() throws Exception {
        Telemetry telemetry = new Telemetry();
        ReflectionTestUtils.setField(controller, "telemetry", telemetry);

        mockMvc.perform(put("/api/v1/memory/config")
                .contentType(APPLICATION_JSON)
                .content("{\"autoDreamEnabled\":false}"))
            .andExpect(status().isOk());

        assertEquals(0, telemetry.getCounter("tengu_auto_memory_toggled"));
        assertEquals(1, telemetry.getCounter("tengu_auto_dream_toggled"));
    }

    /** JSON 字符串值转义（Windows 反斜杠路径在 JSON 中需转义为 \\）。 */
    private static String escapeJson(String path) {
        return path.replace("\\", "\\\\");
    }
}
