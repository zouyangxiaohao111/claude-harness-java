package com.nexusai.apis.session;

import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.memory.MemoryPromptBuilder;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
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
 */
@DisplayName("[OPD-CM5-F-24] SessionMemoryExportController GET /api/v1/session-memory/export")
class SessionMemoryExportControllerTest {

    @TempDir
    Path tempDir;

    /** 注入式 AgentMemoryDirectory：memoryBase=tempDir（user scope → tempDir/agent-memory/&lt;type&gt;）。 */
    private AgentMemoryDirectory newDirectory() {
        return new AgentMemoryDirectory(
            () -> tempDir.resolve("project").toString(),
            () -> tempDir,
            () -> null,
            () -> tempDir.resolve("project"),
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
}
