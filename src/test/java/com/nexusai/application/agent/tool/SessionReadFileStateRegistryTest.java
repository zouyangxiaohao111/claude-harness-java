package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[批 edit-gate-session-scope · B/C] 会话级 readFileState 注册表 + 子代理隔离</b>。
 *
 * <h2>本类守的两条意图（CLAUDE.md 规则九：测意图，不只测行为）</h2>
 * <ol>
 *   <li><b>作用域 = 会话</b>：同一 sessionId 的两个「run」拿到同一张 readFileState
 *       ⇒ 上一轮 Read 过的文件，本轮 Edit 不再被 read-before-write 门禁误拒
 *       （这是本批唯一的目标收益）。</li>
 *   <li><b>分区 = sessionId</b>：不同会话<b>不</b>共享 ⇒ A 会话读过不能让 B 会话改。
 *       两条互为反向，⛔ 不许只留一条：
 *       实现退化成<b>全局单例</b> ⇒ 第 2 条变红；退化成<b>每 ctx 新建</b> ⇒ 第 1 条变红。</li>
 * </ol>
 *
 * <h2>相邻失效面（子代理）</h2>
 * 会话化后若子代理按引用共享会话表，则 {@code SubagentExecutor} 子代理 cleanup 阶段的
 * {@code readFileState().clear()} 会清空<b>整个会话</b>的读状态 ⇒
 * {@link #subagentCtx_neverSharesSessionTable_clearDoesNotWipeSession()} 守这条。
 *
 * <p>⛔ 纯单测（本仓纪律：⛔ 禁 {@code @SpringBootTest} —— 会迁移用户真库）。
 * 夹具与 {@code EditWriteToolGateTest} 同款：声明「确无会话」的 DB 姿态。
 */
@DisplayName("[批 edit-gate-session-scope] 会话级 readFileState：分区 / 跨 run 复用 / 子代理隔离")
class SessionReadFileStateRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabase() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void cleanup() {
        // 静态表跨用例存活 ⇒ 每个用例后必须归零，防跨用例泄漏（本仓 resetForTest 先例同一理由）。
        SessionReadFileStateRegistry.resetForTest();
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════════════

    /** 模拟「一次 run 的 base TUC」：readFileState 取自会话注册表（= 生产
     *  {@code LlmAgentLoop.buildBaseToolUseContext} 的注入形态）。 */
    private static ToolUseContext runCtx(String sessionId) {
        ToolUseContext base = ToolUseContext.of(
            UUID.nameUUIDFromBytes(("run-" + sessionId).getBytes()), sessionId, PermissionMode.DEFAULT);
        return base.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null,
            SessionReadFileStateRegistry.forSession(sessionId),
            null, null, null, null, null, null, null, null));
    }

    private static ToolUseBlock editCall(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", "hello");
        input.put("new_string", "CHANGED");
        return new ToolUseBlock("call-edit", "edit_file", input);
    }

    private static ToolUseBlock readCall(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read", "read_file", input);
    }

    // ══════════════════════════════════════════════════════════════════════
    // (a)(b)(c) 注册表语义
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(a)(b)(c) 注册表：同会话同实例 / 无会话不落共享键 / 容量口径对齐 CC 2.1.278")
    class RegistrySemantics {

        @Test
        @DisplayName("(a) 同 sessionId 两次 forSession 是同一实例；不同 sessionId 不同实例")
        void forSession_sameId_sameInstance_differentId_distinct() {
            FileStateCache a1 = SessionReadFileStateRegistry.forSession("sess-aaa");
            FileStateCache a2 = SessionReadFileStateRegistry.forSession("sess-aaa");
            FileStateCache b1 = SessionReadFileStateRegistry.forSession("sess-bbb");

            assertThat(a1).as("同会话跨 run 必须复用同一张表（本批核心）").isSameAs(a2);
            assertThat(a1).as("按 sessionId 分区：不同会话不得共享").isNotSameAs(b1);
            assertThat(SessionReadFileStateRegistry.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("(b) forSession(null/\"\"/空白) ⇒ null，且绝不落共享键（size 不增长）")
        void forSession_nullOrBlank_returnsNull_andNoSharedKey() {
            int before = SessionReadFileStateRegistry.size();

            assertThat(SessionReadFileStateRegistry.forSession(null)).isNull();
            assertThat(SessionReadFileStateRegistry.forSession("")).isNull();
            assertThat(SessionReadFileStateRegistry.forSession("   ")).isNull();

            assertThat(SessionReadFileStateRegistry.size())
                .as("null/空白会话标识不得创建任何表（否则所有无会话调用方会被串成一桶）")
                .isEqualTo(before);
        }

        @Test
        @DisplayName("(c) 会话表保持 CC 双限 LRU 口径（5000 条 / 25MB）—— 容量随对齐目标 CC 2.1.278")
        void sessionCache_keepsCcDualLimitLru() {
            FileStateCache cache = SessionReadFileStateRegistry.forSession("sess-cap");

            assertThat(cache.max())
                .as("条目数上限随对齐目标 CC 2.1.278 的 LC=5000（rfs-align-3a 批由 100 改标；"
                    + "2.1.88 fileStateCache.ts:18 为 100，勿据此改回）")
                .isEqualTo(ToolUseContext.READ_FILE_STATE_CACHE_SIZE)
                .isEqualTo(5000);
            assertThat(cache.maxSize())
                .as("CC fileStateCache.ts:22 DEFAULT_MAX_CACHE_SIZE_BYTES=25MB（两版一致，本批不动）")
                .isEqualTo(ToolUseContext.DEFAULT_MAX_CACHE_SIZE_BYTES)
                .isEqualTo(25L * 1024L * 1024L);
        }

        @Test
        @DisplayName("(f) evict 只移除该会话，别的会话不受影响")
        void evict_removesOnlyThatSession() {
            FileStateCache a = SessionReadFileStateRegistry.forSession("sess-a");
            FileStateCache b = SessionReadFileStateRegistry.forSession("sess-b");
            a.set("/p/a.txt", new ToolUseContext.ReadState(1L, null, null, false, "x"));

            SessionReadFileStateRegistry.evict("sess-a");

            assertThat(SessionReadFileStateRegistry.peek("sess-a")).isNull();
            assertThat(SessionReadFileStateRegistry.peek("sess-b")).isSameAs(b);
            assertThat(SessionReadFileStateRegistry.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("(h) resetForTest 清空全部（防跨用例泄漏）")
        void resetForTest_clearsAll() {
            SessionReadFileStateRegistry.forSession("sess-x");
            SessionReadFileStateRegistry.forSession("sess-y");
            assertThat(SessionReadFileStateRegistry.size()).isEqualTo(2);

            SessionReadFileStateRegistry.resetForTest();

            assertThat(SessionReadFileStateRegistry.size()).isZero();
            assertThat(SessionReadFileStateRegistry.peek("sess-x")).isNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (d)(e) 核心收益 + 反面
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(d)(e) 跨 run 放行（收益）+ 跨会话拒绝（反面）：两条互为反向")
    class GateScope {

        @Test
        @DisplayName("(d) 同会话 run1 Read → run2（全新 ctx 对象）Edit ⇒ 门禁放行（errorCode 非 6）")
        void sameSession_run2EditPasses_becauseReadStateIsSessionScoped(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ReadFileTool readTool = new ReadFileTool(new PathGuard(workspace));

            // run 1：Read
            ToolUseContext ctxRun1 = runCtx("sess-shared");
            readTool.execute(readCall("a.txt"), ctxRun1);

            // run 2：**全新 ctx 对象**（⛔ 不复用 ctxRun1）
            ToolUseContext ctxRun2 = runCtx("sess-shared");

            // 判别力锚点：先证明两个 ctx 不是同一对象
            // ⇒ 放行只能来自「注册表按 sessionId 复用了 readFileState」，而不是「同一个 ctx 被传了两遍」。
            assertThat(ctxRun2)
                .as("两个 run 必须是不同的 ctx 对象（否则本用例证明不了注册表生效）")
                .isNotSameAs(ctxRun1);
            assertThat(ctxRun2.readFileState())
                .as("两个 run 的 readFileState 必须是同一张会话表")
                .isSameAs(ctxRun1.readFileState());

            Tool.ValidationResult vr =
                editTool.validateInput(editCall("a.txt").input(), ctxRun2);

            assertThat(vr.ok())
                .as("跨 run 复用被 Read 过的文件必须放行（本批目标收益）")
                .isTrue();
            assertThat(vr.errorCode()).isNotEqualTo("6");
        }

        @Test
        @DisplayName("(e) [反面] sess-a Read 过 → 用 sess-b 的 ctx Edit 同一文件 ⇒ 必须拒 errorCode=6")
        void crossSession_editRejected_becausePartitionedBySessionId(@TempDir Path workspace)
                throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            // ⚠️ 两个会话必须映射到**同一磁盘文件**（同一 workspace），否则本用例不成立。
            PathGuard guard = new PathGuard(workspace);
            EditFileTool editTool = new EditFileTool(guard);
            ReadFileTool readTool = new ReadFileTool(guard);

            ToolUseContext ctxA = runCtx("sess-a");
            readTool.execute(readCall("a.txt"), ctxA);

            ToolUseContext ctxB = runCtx("sess-b");
            assertThat(ctxB.readFileState())
                .as("不同会话必须是不同的表（否则退化成全局单例）")
                .isNotSameAs(ctxA.readFileState());

            Tool.ValidationResult vr = editTool.validateInput(editCall("a.txt").input(), ctxB);

            assertThat(vr.ok())
                .as("A 会话的已读状态绝不能放行 B 会话的写 —— 若本条变 ok()==true 说明退化成了全局单例")
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("6");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (g) 相邻失效面：子代理不得清空会话表
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(g) 子代理隔离：withEffectiveCwd 派生独立副本，cleanup 的 clear() 不触会话表")
    class SubagentIsolation {

        @Test
        @DisplayName("子代理 ctx 不与会话表同实例；对其 clear() 后会话表条数不变")
        void subagentCtx_neverSharesSessionTable_clearDoesNotWipeSession() {
            String sessionId = "sess-sub";
            ToolUseContext parentCtx = runCtx(sessionId);
            FileStateCache sessionTable = SessionReadFileStateRegistry.peek(sessionId);
            assertThat(sessionTable).isNotNull();
            sessionTable.set("/p/read-by-main.txt",
                new ToolUseContext.ReadState(1L, null, null, false, "main-read"));
            int before = sessionTable.size();

            // 子代理派生（生产路径：SubagentExecutor Step 18 worktree 覆盖）
            ToolUseContext subCtx =
                SubagentExecutor.withEffectiveCwd(parentCtx, java.nio.file.Path.of(".").toAbsolutePath());

            assertThat(subCtx.readFileState())
                .as("子代理必须拿独立副本（若本条变 isSameAs 说明 clone 被移除 ⇒ 下面 clear() 会清空整个会话）")
                .isNotSameAs(sessionTable);
            assertThat(subCtx.readFileState().size())
                .as("独立副本必须**继承**已读条目（fork 内 Edit 门禁依赖，见 RunForkedAgent.java:369-373）")
                .isEqualTo(before);

            // 子代理 cleanup 阶段（SubagentExecutor :2605）的破坏性动作
            subCtx.readFileState().clear();

            assertThat(SessionReadFileStateRegistry.peek(sessionId))
                .as("会话表对象必须仍在注册表中")
                .isNotNull();
            assertThat(SessionReadFileStateRegistry.peek(sessionId).size())
                .as("子代理 cleanup 绝不能清空会话表（本条 = 会话化后最坏的退化面）")
                .isEqualTo(before);
        }

        @Test
        @DisplayName("同一性守卫条件：子代理 ctx 的 readFileState ≠ peek(sessionId)（守卫据此跳过 clear）")
        void identityGuardCondition_isFalseForSubagentCtx() {
            String sessionId = "sess-guard";
            ToolUseContext subCtx = SubagentExecutor.withEffectiveCwd(
                runCtx(sessionId), java.nio.file.Path.of(".").toAbsolutePath());

            FileStateCache sessionTable = SessionReadFileStateRegistry.peek(sessionId);
            assertThat(sessionTable).isNotNull();
            // SubagentExecutor :2605 守卫的判据 = (peek(sessionId) == subagentCtx.readFileState())
            //   ⇒ 正常路径必须为 false，否则守卫会 fail-loud 跳过 clear()（说明 clone 被移除）。
            assertThat(sessionTable == subCtx.readFileState())
                .as("守卫判据必须为 false（= 子代理不与会话表同实例）")
                .isFalse();
        }
    }
}
