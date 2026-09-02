package com.nexusai.domain.session;

import com.nexusai.application.agent.permission.hook.SkillImprovementSuggestionStore;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.chat.ChatService;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionFileMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [R3] SessionService.delete 双通道同步 · 文件侧清理意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：R3 问题 = 删会话只删 DB 行（messages/session_files/sessionMapper），
 * config-home transcript sidecar（{@code {configHome}/projects/{slug}/{sessionId}.jsonl} + 会话目录
 * {@code .../{sessionId}/}）残留 —— automemory/Read/跨会话查询仍能读到已删会话内容（双通道不同步）。
 * 本测试锁定：
 * <ol>
 *   <li>delete 时调用 {@link SessionStorage#deleteSessionFiles}（文件侧清理）——且不阻断 DB 删除主流程</li>
 *   <li>config-home 下 transcript 文件与会话目录（session.jsonl/subagents）实际被删除</li>
 *   <li>DB 侧（messageMapper/sessionFileMapper/sessionMapper）删除仍执行（双通道同步，非替代）</li>
 * </ol>
 * 变异点：若删除 SessionStorage.deleteSessionFiles 接线（回到「只删 DB 行」），transcript 文件
 * 断言变红 → 已删会话内容仍可被 automemory/Read 读取（双通道不同步）。
 */
@DisplayName("[R3] SessionService.delete 双通道同步：文件侧清理（config-home transcript + sidecar）")
class SessionServiceDeleteFileCleanupTest {

    @TempDir
    Path tempConfigHome;

    private String originalConfigDirOverride;

    @BeforeEach
    void setUp() throws Exception {
        // 隔离 config-home：ClaudePaths.getClaudeConfigHomeDir() 指向临时目录，避免污染真实 ~/.claude
        originalConfigDirOverride = readConfigDirOverride();
        ClaudePaths.setConfigDirOverride(tempConfigHome.toString());
        // G5：SessionStorage.getProjectsDir 已迁 nexusai 自有根（SessionStorage.java:118）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempConfigHome.getFileName());
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(originalConfigDirOverride);
        NexusaiPaths.setAppNameOverride(null);
    }

    private static String readConfigDirOverride() throws Exception {
        Field f = ClaudePaths.class.getDeclaredField("configDirOverride");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private SessionService newService(SessionMapper sessionMapper,
                                      MessageMapper messageMapper,
                                      SessionFileMapper sessionFileMapper,
                                      ChatService chatService,
                                      SkillImprovementSuggestionStore suggestionStore) throws Exception {
        SessionService svc = new SessionService();
        inject(svc, "sessionMapper", sessionMapper);
        inject(svc, "messageMapper", messageMapper);
        inject(svc, "sessionFileMapper", sessionFileMapper);
        inject(svc, "chatService", chatService);
        inject(svc, "suggestionStore", suggestionStore);
        return svc;
    }

    @Test
    @DisplayName("R3: delete 删 DB 行 + 清理 config-home transcript 与会话目录（双通道同步）")
    void delete_cleansFileSideAndDbRows() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);
        SkillImprovementSuggestionStore suggestionStore = mock(SkillImprovementSuggestionStore.class);

        String sessionId = "sess-abc12345";
        SessionRecord rec = new SessionRecord();
        rec.setId(sessionId);
        when(sessionMapper.selectOneById(sessionId)).thenReturn(rec);

        // 预置文件侧产物（config-home 项目 slug 目录内）：扁平 transcript + 会话目录（session.jsonl）
        // projectRoot = boundProject/originalCwd 层（deleteSessionFiles 内部经 getProjectDir 派生 slug）
        Path projectRoot = Path.of(com.nexusai.application.agent.agent.CwdResolution.getOriginalCwdLayer(sessionId));
        Path transcript = SessionStorage.getTranscriptPath(projectRoot, sessionId);
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, "{\"type\":\"user\",\"content\":\"hi\"}\n");
        Path sessionFile = SessionStorage.getSessionFile(projectRoot, sessionId);
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, "{\"type\":\"content-replacement\"}\n");

        newService(sessionMapper, messageMapper, sessionFileMapper, chatService, suggestionStore)
                .delete(sessionId);

        // 1) 文件侧已清理：transcript 与 session.jsonl 均不存在（双通道同步）
        assertThat(Files.exists(transcript))
            .as("delete 后 config-home transcript 必须删除（R3 双通道同步）")
            .isFalse();
        assertThat(Files.exists(sessionFile))
            .as("delete 后会话目录 session.jsonl 必须删除（R3 双通道同步）")
            .isFalse();
        // 2) DB 侧删除仍执行（文件清理是补充，不是替代）
        verify(sessionMapper).deleteById(sessionId);
        verify(messageMapper).deleteByQuery(org.mockito.ArgumentMatchers.any());
        verify(sessionFileMapper).deleteByQuery(org.mockito.ArgumentMatchers.any());
        // 3) projectRoot 冻结解绑（clearSession 后被 getForSession 查询为 null）
        assertThat(com.nexusai.common.SessionProjectRoot.getForSession(sessionId))
            .as("delete 应解绑 projectRoot 冻结（clearSession）")
            .isNull();
    }

    @Test
    @DisplayName("R3: 文件不存在 / 删除失败 → best-effort 不阻断 DB 删除（fail-loud 不抛）")
    void delete_fileSideMissing_doesNotBlockDbDelete() throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionFileMapper sessionFileMapper = mock(SessionFileMapper.class);
        ChatService chatService = mock(ChatService.class);
        SkillImprovementSuggestionStore suggestionStore = mock(SkillImprovementSuggestionStore.class);

        String sessionId = "sess-xyz99999";
        SessionRecord rec = new SessionRecord();
        rec.setId(sessionId);
        when(sessionMapper.selectOneById(sessionId)).thenReturn(rec);
        // 无任何文件侧产物（文件不存在）

        newService(sessionMapper, messageMapper, sessionFileMapper, chatService, suggestionStore)
                .delete(sessionId);

        // 文件侧不存在 → deleteIfExists 静默跳过（best-effort），DB 删除主流程不受阻
        verify(sessionMapper).deleteById(sessionId);
    }

    @Test
    @DisplayName("R3: deleteSessionFiles 双键尝试（DB 原始键 + 派生 UUID）都清理")
    void deleteSessionFiles_cleansBothKeyForms() throws Exception {
        // WHY: transcript 主文件按派生 UUID（state.sessionId().toString()）写，会话目录按 DB 原始键
        //   （"sess-xxx"）写 —— delete 时两个键形态的产物都要清理（缺一可能残留）。
        String sessionId = "sess-abcd1234";
        Path projectRoot = Path.of(com.nexusai.application.agent.agent.CwdResolution.getOriginalCwdLayer(sessionId));
        String canonical = com.nexusai.common.SessionKeys.canonicalUuid(sessionId).toString();
        Path uuidTranscript = SessionStorage.getTranscriptPath(projectRoot, canonical);
        Files.createDirectories(uuidTranscript.getParent());
        Files.writeString(uuidTranscript, "{}\n");
        Path dbSessionDir = SessionStorage.getProjectDir(projectRoot).resolve(sessionId);
        Files.createDirectories(dbSessionDir.resolve("subagents"));
        Files.writeString(dbSessionDir.resolve("subagents").resolve("agent-x.jsonl"), "{}\n");

        SessionStorage.deleteSessionFiles(projectRoot, sessionId);

        assertThat(Files.exists(uuidTranscript)).as("派生 UUID transcript 必须删除").isFalse();
        assertThat(Files.exists(dbSessionDir)).as("DB 原始键会话目录必须递归删除").isFalse();
    }
}
