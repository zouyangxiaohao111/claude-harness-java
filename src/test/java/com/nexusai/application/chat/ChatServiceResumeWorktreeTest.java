package com.nexusai.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.worktree.WorktreeCwdTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * [WF-5-r2] ChatService.restoreWorktreeForResume resume 读回链路测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>：WF-4 已在 {@code processUserMessage}
 * 入口接线 {@code restoreWorktreeForResume}（对齐 CC sessionRestore.ts:332-366），但
 * grep 无任何测试覆盖 resume 读回。V17 DROP COLUMN 已落地，若 resume 读回链路断裂（
 * transcript worktree-state 未读回 / 读回后未写 tracker / exit 写 null 未覆盖 enter），
 * 用户 /resume 后 cwd 不会回到原 worktree，属静默状态丢失。本测试锁定五类可观测不变量：
 * <ol>
 *   <li><b>正常读回</b>——transcript 有 worktree-state → 调 restore 后
 *       {@code setCwd} + {@code setOriginalCwd(=worktreePath)}（残留 1）+ 完整会话对象恢复
 *       （残留 2）落 tracker。</li>
 *   <li><b>目录消失</b>——worktreePath 指向不存在目录（TOCTOU 校验，CC :343-350
 *       process.chdir 抛 ENOENT）→ tracker 被清（getCwd / getOriginalCwd /
 *       getWorktreeSession 均 null）。</li>
 *   <li><b>无 worktree-state</b>——空 transcript → 不恢复（tracker 空，CC :340）。</li>
 *   <li><b>null 覆盖</b>——enter 写 object、exit 写 null（last-wins，CC
 *       sessionStorage.ts:3605 worktreeStates.set 覆盖）→ enter 的 cwd 不被恢复。</li>
 *   <li><b>fresh 守卫</b>——已有 fresh worktree 会话对象 → 跳过 transcript 恢复（残留 3，
 *       CC :336-339 getCurrentWorktreeSession() 有值 → saveWorktreeState(fresh) 优先）。</li>
 * </ol>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入
 * {@code sessionProjectRootResolver}（返回临时目录，与 EnterWorktreeTool 写 transcript 同源）。
 * 被测符号 {@code restoreWorktreeForResume} 为 private，经 {@link Method#setAccessible} 反射调用
 * （对齐项目 R32B9 系列 getDeclaredMethod 先例）。断言只比 Path 对象 / String 值，
 * 不比对路径分隔符格式（Windows/Linux 差异）。
 */
@DisplayName("[WF-5-r2] ChatService restoreWorktreeForResume resume 读回（worktree-state → tracker）")
class ChatServiceResumeWorktreeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspaceDir;

    private ChatService service;
    private String sessionId;
    private String sessionKey;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        // 注入 sessionProjectRootResolver → 返回临时 workspaceDir；restore 用它与
        //   EnterWorktreeTool.persistWorktreeState 相同的 transcript 目录读回。
        ReflectionTestUtils.setField(service, "sessionProjectRootResolver",
            (Function<String, String>) id -> workspaceDir.toString());
        // 合规 UUID 串：parseSessionUuid 直接返回自身 → sessionKey == sessionId。
        sessionId = UUID.randomUUID().toString();
        sessionKey = sessionId;
    }

    @AfterEach
    void tearDown() {
        // WorktreeCwdTracker 为静态 ConcurrentHashMap，逐用例清本会话 key 防串扰。
        WorktreeCwdTracker.clearCwd(sessionKey);
        WorktreeCwdTracker.clearOriginalCwd(sessionKey);
        WorktreeCwdTracker.clearWorktreeSession(sessionKey);
    }

    /** 反射调用 private {@code restoreWorktreeForResume(String)}。 */
    private void restore() throws Exception {
        Method m = ChatService.class.getDeclaredMethod("restoreWorktreeForResume", String.class);
        m.setAccessible(true);
        m.invoke(service, sessionId);
    }

    /** 构造最小 worktreeSession JSON（对齐 CC PersistedWorktreeSession 的 worktreePath + originalCwd）。 */
    private ObjectNode worktreeSession(String worktreePath, String originalCwd) {
        ObjectNode n = JSON.createObjectNode();
        n.put("worktreePath", worktreePath);
        n.put("originalCwd", originalCwd);
        return n;
    }

    /** 构造完整 worktreeSession JSON（对齐 EnterWorktreeTool.persistWorktreeState 落盘形状）。 */
    private ObjectNode fullWorktreeSession(String worktreePath, String originalCwd,
                                           String worktreeName, String worktreeBranch,
                                           boolean hookBased) {
        ObjectNode n = JSON.createObjectNode();
        n.put("worktreePath", worktreePath);
        n.put("originalCwd", originalCwd);
        n.put("worktreeName", worktreeName);
        if (worktreeBranch != null) {
            n.put("worktreeBranch", worktreeBranch);
        }
        n.put("sessionId", sessionKey);
        n.put("hookBased", hookBased);
        return n;
    }

    @Test
    @DisplayName("残留1+2：resume 恢复 cwd + originalCwd(=worktreePath) + 完整会话对象")
    void resumesCwdOriginalCwdAndFullSession() throws Exception {
        // WHY: resume 核心路径——用户 enter worktree 后退出会话，/resume 必须回到原 worktree
        //   cwd；且 CC :352-353 setOriginalCwd(getCwd()) 时 getCwd()=worktreePath，故 originalCwd
        //   必须等于 worktreePath（残留 1，而非 transcript 里的"进入前目录"，后者留给 Exit 回退）；
        //   :359 restoreWorktreeSession 恢复完整会话对象（残留 2），否则 worktreeName/hookBased
        //   等会话状态丢失。
        Path worktreePath = Files.createDirectory(workspaceDir.resolve("wt-live"));
        String enteringCwd = "/home/user/orig-project"; // 进入前目录，仅存 transcript，不被 resume 覆盖

        SessionStorage.writeWorktreeState(workspaceDir, sessionKey,
            fullWorktreeSession(worktreePath.toString(), enteringCwd, "wt-live", "feature/x", false));

        restore();

        assertEquals(worktreePath, WorktreeCwdTracker.getCwd(sessionKey));
        // 残留 1: originalCwd == worktreePath（对齐 CC :352-353 setOriginalCwd(getCwd())）
        assertEquals(worktreePath.toString(), WorktreeCwdTracker.getOriginalCwd(sessionKey));
        // 残留 2: 完整会话对象恢复
        WorktreeCwdTracker.WorktreeSession restored = WorktreeCwdTracker.getWorktreeSession(sessionKey);
        assertNotNull(restored);
        assertEquals(worktreePath.toString(), restored.worktreePath());
        assertEquals("wt-live", restored.worktreeName());
        assertEquals("feature/x", restored.worktreeBranch());
        assertEquals(false, restored.hookBased());
        assertEquals(sessionKey, restored.sessionId());
    }

    @Test
    @DisplayName("目录消失：worktreePath 指向不存在目录 → tracker 被清")
    void clearsTrackerWhenDirectoryGone() throws Exception {
        // WHY: CC :343-350 process.chdir 作 TOCTOU 存在性校验，目录被手动删除后必须清态，
        //   否则 tracker 残留指向已消失目录的 cwd，后续工具 workdir 指向鬼路径。
        Path gone = workspaceDir.resolve("wt-gone"); // 不创建，指向不存在目录
        // 预置 tracker 状态，验证 clear 真正移除（非仅"未写入"）。
        WorktreeCwdTracker.setCwd(sessionKey, gone);
        WorktreeCwdTracker.setOriginalCwd(sessionKey, "/home/user/orig-project");
        SessionStorage.writeWorktreeState(workspaceDir, sessionKey,
            worktreeSession(gone.toString(), "/home/user/orig-project"));

        restore();

        assertNull(WorktreeCwdTracker.getCwd(sessionKey));
        assertNull(WorktreeCwdTracker.getOriginalCwd(sessionKey));
        assertNull(WorktreeCwdTracker.getWorktreeSession(sessionKey));
    }

    @Test
    @DisplayName("无 worktree-state：空 transcript → 不恢复（tracker 空）")
    void noWorktreeState_doesNotRestore() throws Exception {
        // WHY: 从未进入 worktree 的会话 resume 时无 worktree-state entry，不得凭空 cd；
        //   readWorktreeState 对不存在 transcript 返回 null，restore 早退（CC :340）。
        restore();

        assertNull(WorktreeCwdTracker.getCwd(sessionKey));
        assertNull(WorktreeCwdTracker.getOriginalCwd(sessionKey));
        assertNull(WorktreeCwdTracker.getWorktreeSession(sessionKey));
    }

    @Test
    @DisplayName("null 覆盖：enter 写 object 后 exit 写 null（last-wins）→ enter 的 cwd 不被恢复")
    void nullOverride_doesNotRestore() throws Exception {
        // WHY: exit 写 null 覆盖 enter 写 object（CC sessionStorage.ts:3605 last-wins），
        //   resume 必须识别"已退出 worktree"而不回 cd 到 enter 时的 path——否则退出后又
        //   被错误拉回原 worktree。
        Path enterPath = Files.createDirectory(workspaceDir.resolve("wt-enter"));
        SessionStorage.writeWorktreeState(workspaceDir, sessionKey,
            worktreeSession(enterPath.toString(), "/home/user/orig-project"));
        SessionStorage.writeWorktreeState(workspaceDir, sessionKey, null); // exit 写 null，last-wins

        restore();

        assertNull(WorktreeCwdTracker.getCwd(sessionKey));
        assertNull(WorktreeCwdTracker.getOriginalCwd(sessionKey));
        assertNull(WorktreeCwdTracker.getWorktreeSession(sessionKey));
    }

    @Test
    @DisplayName("残留3 fresh 守卫：已有 fresh worktree 会话 → 跳过 transcript 恢复，保留当前状态")
    void freshSession_skipsTranscriptRestore() throws Exception {
        // WHY: CC sessionRestore.ts:336-339 getCurrentWorktreeSession() 有值 → saveWorktreeState(fresh)
        //   优先，不读 transcript 覆盖。若 Java 仍读 transcript，则 mid-session /resume 会把已 active
        //   的 worktree 状态替换成 transcript 里的陈旧值（状态漂移）。
        Path freshPath = Files.createDirectory(workspaceDir.resolve("wt-fresh"));
        // 预置 fresh 会话（Enter 已设置 cwd + 完整会话对象）
        WorktreeCwdTracker.setCwd(sessionKey, freshPath);
        WorktreeCwdTracker.setWorktreeSession(sessionKey,
            new WorktreeCwdTracker.WorktreeSession(freshPath.toString(), "feature/fresh",
                "wt-fresh", false, sessionKey));
        // transcript 指向另一个（陈旧）但实际存在的 worktree 路径
        Path stalePath = Files.createDirectory(workspaceDir.resolve("wt-stale"));
        SessionStorage.writeWorktreeState(workspaceDir, sessionKey,
            fullWorktreeSession(stalePath.toString(), "/home/user/orig", "wt-stale",
                "feature/stale", false));

        restore();

        // fresh 优先：cwd + 会话对象保持 fresh，不被 transcript 覆盖
        assertEquals(freshPath, WorktreeCwdTracker.getCwd(sessionKey));
        assertEquals("wt-fresh", WorktreeCwdTracker.getWorktreeSession(sessionKey).worktreeName());
        assertEquals("feature/fresh", WorktreeCwdTracker.getWorktreeSession(sessionKey).worktreeBranch());
    }
}
