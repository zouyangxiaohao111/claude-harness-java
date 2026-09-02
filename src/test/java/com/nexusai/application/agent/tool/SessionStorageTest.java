package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [cron-durable-session-fire] SessionStorage transcript 路径三 seam 纯 sessionId 解析实证。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：CC durable fire 回合 transcript 归创建会话
 * （getTranscriptPath() = {projectDir}/{sessionId}.jsonl，sessionStorage.ts:202-205；fire =
 * 创建会话普通回合，useScheduledTasks.ts:71-82）。Java DURABLE fire 的 transcript 键 =
 * RunRequest.sessionId（CronIdleExecutor 创建会话存活判定）：存活 → 创建会话 UUID → 归创建会话文件；
 * 已关 → null → 路径 null → 不写 transcript。已删 per-task 虚拟会话键 override（ThreadLocal），
 * 三 seam（{@link SessionStorage#getTranscriptPath} / {@link SessionStorage#getSessionFile} /
 * {@link SessionStorage#getAgentTranscriptPath}）回到纯 sessionId 解析。
 *
 * <p>本测试直接实证纯 sessionId 解析的<b>路径效果</b>（消费方统一底层），不依赖 cron 链路。
 *
 * <p>RED 条件：残留任何 override 注入 / sessionId 非空却解析 null / null sessionId 返回非 null
 * → 断言变红。
 */
@DisplayName("[cron-durable-session-fire] SessionStorage transcript 路径三 seam：纯 sessionId 解析")
class SessionStorageTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("getTranscriptPath：sessionId 非空 → {configHome}/projects/{slug}/{sessionId}.jsonl（S2 迁 config-home）")
    void getTranscriptPath_resolvesBySessionId() {
        // WHY: DURABLE 创建会话存活 → RunRequest.sessionId=创建会话 UUID → transcript 归创建会话文件
        // （对齐 CC fire 注入活跃会话）；SESSION/普通路径同样归真实会话 UUID（零改动）。
        // [S2] 锚点迁 config-home：getProjectDir(tempDir) = {configHome}/projects/{sanitizePath(tempDir)}
        String realSession = UUID.randomUUID().toString();

        Path path = SessionStorage.getTranscriptPath(tempDir, realSession);

        assertThat(path).isEqualTo(configHomeProjectDir().resolve(realSession + ".jsonl"));
    }

    @Test
    @DisplayName("getTranscriptPath：sessionId=null → 返回 null（headless 无 transcript）")
    void getTranscriptPath_nullSessionId_returnsNull() {
        // WHY: DURABLE 创建会话已关 → RunRequest.sessionId=null → 路径 null → 消费方跳过写 transcript
        // （不产生创建会话文件 / GLOBAL.jsonl 共享污染）。RED: null 若被 GLOBAL 兜底 → 落 GLOBAL 文件。
        assertThat(SessionStorage.getTranscriptPath(tempDir, null)).isNull();
    }

    @Test
    @DisplayName("getTranscriptPath：workspaceDir=null → 返回 null（无工作区根不解析）")
    void getTranscriptPath_nullWorkspaceDir_returnsNull() {
        assertThat(SessionStorage.getTranscriptPath(null, "sess-x")).isNull();
    }

    @Test
    @DisplayName("getSessionFile：content-replacement sidecar 落 {configHome}/projects/{slug}/{sessionId}/session.jsonl（S2 迁 config-home）")
    void getSessionFile_resolvesBySessionId() {
        // WHY: writeContentReplacement（AgentLoopContext:574/2035 消费方）经 getSessionFile 写嵌套
        // sidecar —— 纯 sessionId 解析归真实会话目录，不落 GLOBAL 共享目录。
        String realSession = UUID.randomUUID().toString();

        Path path = SessionStorage.getSessionFile(tempDir, realSession);

        assertThat(path).isEqualTo(configHomeProjectDir().resolve(realSession).resolve("session.jsonl"));
    }

    @Test
    @DisplayName("getAgentTranscriptPath：subagent sidechain 落 {configHome}/projects/{slug}/{sessionId}/subagents/agent-{id}.jsonl（S2 迁 config-home）")
    void getAgentTranscriptPath_resolvesBySessionId() {
        // WHY: subagent sidechain（AgentTranscript 消费方）路径以 sessionId 为目录 —— 纯 sessionId
        // 解析归真实会话（创建会话存活时 DURABLE fire fork 的 subagent transcript 归创建会话）。
        String realSession = UUID.randomUUID().toString();
        String agentId = "agent-abc123";

        Path path = SessionStorage.getAgentTranscriptPath(tempDir, realSession, agentId);

        assertThat(path)
            .isEqualTo(configHomeProjectDir().resolve(realSession).resolve("subagents")
                .resolve("agent-" + agentId + ".jsonl"));
    }

    @Test
    @DisplayName("同 sessionId 跨多次调用恒同路径（归创建会话累积语义）")
    void sameSessionId_consistentAcrossCalls() {
        // WHY: 同创建会话 UUID 跨多次解析必须同路径 —— recurring DURABLE fire 创建会话存活期内
        // transcript 同文件累积（对齐 CC Fix-F1「会话存活期内 recurring 任务 fire 归同一会话」）。
        String realSession = UUID.randomUUID().toString();

        Path p1 = SessionStorage.getTranscriptPath(tempDir, realSession);
        Path p2 = SessionStorage.getTranscriptPath(tempDir, realSession);

        assertThat(p1).isEqualTo(p2)
            .as("同 sessionId 跨多次解析必须同路径（归创建会话 transcript 累积不分裂）");
    }

    @Test
    @DisplayName("不同 sessionId → 不同文件（会话隔离，无跨会话污染）")
    void differentSessionIds_differentPaths() {
        // WHY: 不同会话 UUID 解析到不同 transcript 文件（每会话一文件不变式，跨会话互不污染）。
        Path pA = SessionStorage.getTranscriptPath(tempDir, UUID.randomUUID().toString());
        Path pB = SessionStorage.getTranscriptPath(tempDir, UUID.randomUUID().toString());

        assertThat(pA).isNotEqualTo(pB)
            .as("不同会话必须解析到不同 transcript 文件（会话隔离）");
    }

    @Test
    @DisplayName("[R1] sessionProjectDir（ResumeService/SubagentExecutor/MainSessionBackgroundService resolveSessionDir 统一根）前缀 = {configHome}/projects/")
    void sessionProjectDir_prefixIsConfigHomeProjects() {
        // WHY（规则九 · R1 测试锚点）：三处旧 {tmpdir}/nexusai-sessions 平铺根统一到 config-home
        //   项目 slug 目录（ResumeService.resolveSessionDir / SubagentExecutor.resolveSessionDir /
        //   MainSessionBackgroundService.resolveSessionDir 均委托 SessionStorage.sessionProjectDir）。
        //   RED: 若残留旧平铺根（非 config-home projects 前缀）→ 断言变红。
        String sessionId = UUID.randomUUID().toString();

        Path projectDir = SessionStorage.sessionProjectDir(sessionId);

        assertThat(projectDir)
            .as("resolveSessionDir 统一根必须落在 config-home projects 下（对齐 CC getProjectDir(getOriginalCwd())）")
            .startsWith(SessionStorage.getProjectsDir());
        assertThat(projectDir.getFileName().toString())
            .as("末段 = sanitizePath(originalCwdLayer)（项目 slug 目录）")
            .isEqualTo(com.nexusai.application.agent.memory.AutoMemPaths.sanitizePath(
                com.nexusai.application.agent.agent.CwdResolution.getOriginalCwdLayer(sessionId)));
    }

    /** config-home 项目 slug 目录（S2 派生）· 与 getTranscriptPath 内部派生同源。 */
    private Path configHomeProjectDir() {
        return SessionStorage.getProjectsDir()
            .resolve(com.nexusai.application.agent.memory.AutoMemPaths.sanitizePath(tempDir.toString()));
    }
}
