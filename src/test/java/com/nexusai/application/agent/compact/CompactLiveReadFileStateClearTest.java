package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.SessionReadFileStateRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>[批 rfs-align-3a] compact 清「活表」—— 会话级 readFileState 的活表清除（对齐 CC 2.1.278）</b>。
 *
 * <h2>本类守的意图（CLAUDE.md 规则九：测意图，不只测行为）</h2>
 * CC 在 compact 成功后<b>先拷快照、再清活表</b>（2.1.88 {@code compact.ts:518-521} /
 * partial {@code :918-921}；2.1.278 发行产物 {@code claude.exe} off 204805268
 * {@code xe=r4t(_e.readFileState); if(_e.readFileState.clear(),…)}）——
 * 那个 {@code _e.readFileState} 是 {@code toolUseContext.readFileState}（<b>活表</b>，
 * Edit/Write 门禁读的同一实例）。<b>WHY 重要</b>：压缩后模型上下文已不含文件内容，
 * 若活表不清，模型可凭已不在上下文的「已读」凭据直接 Edit（比 CC 更宽容 = 静默绕过
 * read-before-write 保护）。本类因此守两条：
 * <ol>
 *   <li><b>compact 成功后活表必须归零</b>（本批唯一的目标收益）。</li>
 *   <li><b>没 compact / compact 失败时活表必须原样</b> —— 反向守卫：把 clear 挂到
 *       「构造 ctx 时」「每次 run」「摘要之前」都会让本条变红。</li>
 * </ol>
 *
 * <h2>逐路径（三条 compact 路径的 tuc 来源不同 ⇒ 分别用例，⛔ 不用统一写法掩盖）</h2>
 * <ul>
 *   <li><b>auto 全量</b>：{@code LlmAgentLoop:6283 → buildAutoContext}（tuc 恒接线）</li>
 *   <li><b>manual 全量（/compact）</b>：{@code ToolRegistrationConfig:3195-3197} 只
 *       {@code setToolUseContext}、<b>不调</b>{@code setReadFileState}（本案按该形态镜像构造
 *       —— 真工厂是 {@code @Configuration} 私有方法，跑它需 {@code @SpringBootTest}，
 *       本仓纪律禁止）</li>
 *   <li><b>partial</b>：{@code PartialCompactService:623} 传一次性空 Map +
 *       {@code assembleForkCacheSharingMaterials:823} best-effort 注入 tuc（本案分别覆盖
 *       「tuc 已注入」与「tuc 缺失 ⇒ 不可达」两态）</li>
 * </ul>
 *
 * <p>⛔ 纯单测（本仓纪律：⛔ 禁 {@code @SpringBootTest} —— 会迁移用户真库）。
 */
@DisplayName("[批 rfs-align-3a] compact 清活表：会话级 readFileState 的活表清除（对齐 CC 2.1.278）")
class CompactLiveReadFileStateClearTest {

    private static final String AUTO_SESSION = "sess-rfs-auto";
    private static final String MANUAL_SESSION = "sess-rfs-manual";
    private static final String PARTIAL_SESSION = "sess-rfs-partial";
    private static final String PARTIAL_NO_TUC_SESSION = "sess-rfs-partial-notuc";
    private static final String NEG_SESSION = "sess-rfs-neg";

    @TempDir
    Path tempDir;

    @BeforeEach
    void declareNoDatabase() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void cleanup() {
        // 会话级表是进程内静态表 ⇒ 每个用例后必须归零，防跨用例泄漏。
        SessionReadFileStateRegistry.resetForTest();
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 模拟「一次 run 的 base TUC」· 与 {@code SessionReadFileStateRegistryTest#runCtx} 同款
     * （生产形态 = {@code LlmAgentLoop.buildBaseToolUseContext:10602-10603} 把
     * {@code SessionReadFileStateRegistry.forSession(sessionId)} 塞进 base TUC）。
     */
    private static ToolUseContext liveTuc(String sessionId) {
        ToolUseContext base = ToolUseContext.of(
            UUID.nameUUIDFromBytes(("rfs-run-" + sessionId).getBytes()), sessionId, PermissionMode.DEFAULT);
        return base.with(new ToolUseContext.SubagentContextOverrides(
            null, null, null, null, null,
            SessionReadFileStateRegistry.forSession(sessionId),
            null, null, null, null, null, null, null, null));
    }

    /** 往会话活表里塞一条「已读」记录（key = 归一化后的绝对路径形态）。 */
    private static void seedLiveEntry(String sessionId, String key, long mtime) {
        SessionReadFileStateRegistry.forSession(sessionId)
            .set(key, new ToolUseContext.ReadState(mtime, null, null, false, "seed-content"));
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, "s1", role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    private static CompactConversation.SummaryResult okSummary() {
        return new CompactConversation.SummaryResult("summary ok", null);
    }

    private static CompactConversation.SummaryProducer okProducer() {
        return (messages, prompt, preTokens) -> okSummary();
    }

    // ══════════════════════════════════════════════════════════════════════
    // (a) 每条可达的 compact 路径：压缩后活表归零
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(a) compact 后活表处置（逐路径）：已接线 ⇒ 归零；结构不可达 ⇒ 如实返回 0（对账/诚实降级，不假装清空）")
    class LiveTableClearedOnCompact {

        @Test
        @DisplayName("auto 全量：buildAutoContext 接线后压缩 ⇒ 会话活表归零，且快照仍喂给附件恢复")
        void autoFullCompact_clearsLiveTable() throws Exception {
            // 真磁盘文件：附件恢复的 contentReader 走磁盘重读（restoreFileFresh），须真实存在。
            Path f1 = tempDir.resolve("auto-1.txt");
            Files.writeString(f1, "auto content 1");
            // 活表 3 条（快照里应全部可见，压缩后应全部消失）
            seedLiveEntry(AUTO_SESSION, f1.toString(), 100L);
            seedLiveEntry(AUTO_SESSION, tempDir.resolve("auto-2.txt").toString(), 90L);
            seedLiveEntry(AUTO_SESSION, tempDir.resolve("auto-3.txt").toString(), 80L);

            ToolUseContext tuc = liveTuc(AUTO_SESSION);
            CompactConversationContext cc = CompactConversation.buildAutoContext(
                tuc, "claude-sonnet-4-5", "repl_main_thread", null);
            cc.setSummaryProducer(okProducer());

            CompactionResult result = CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), cc, false, null, true, null);

            assertThat(SessionReadFileStateRegistry.forSession(AUTO_SESSION).size())
                .as("compact 成功后会话活表必须归零（CC context.readFileState.clear()，"
                    + "2.1.278 claude.exe off 204805268）")
                .isZero();
            assertThat(result.attachments())
                .as("快照必须在 clear 之前取：附件恢复仍能拿到压缩前的 1 个真实文件")
                .isNotEmpty();
        }

        @Test
        @DisplayName("manual 全量：只 setToolUseContext（不调 setReadFileState，镜像 ToolRegistrationConfig:3195-3197）⇒ 活表归零")
        void manualFullCompact_clearsLiveTable() {
            seedLiveEntry(MANUAL_SESSION, "/p/manual-read.txt", 100L);

            // 镜像 manual 工厂形态：readFileState 字段保持 null（工厂从不设它）
            CompactConversationContext cc = new CompactConversationContext();
            cc.setSessionId(MANUAL_SESSION);
            cc.setAgentId("main");
            cc.setModel("claude-sonnet-4-5");
            cc.setQuerySource("compact");
            cc.setToolUseContext(liveTuc(MANUAL_SESSION));
            cc.setSummaryProducer(okProducer());
            assertThat(cc.getReadFileState()).as("manual 工厂不调 setReadFileState").isNull();

            CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), cc, false, null, false, null);

            assertThat(SessionReadFileStateRegistry.forSession(MANUAL_SESSION).size())
                .as("活表清除不依赖 readFileState 字段（manual 该字段为 null）—— 判据是 toolUseContext")
                .isZero();
        }

        @Test
        @DisplayName("partial：tuc 已注入（assembleForkCacheSharingMaterials:823 形态）⇒ 活表归零")
        void partialCompact_clearsLiveTable_whenTucWired() {
            seedLiveEntry(PARTIAL_SESSION, "/p/partial-read.txt", 100L);

            CompactConversationContext cc = partialShapeContext(PARTIAL_SESSION, liveTuc(PARTIAL_SESSION));

            List<ChatMessageDto> all = new ArrayList<>();
            all.add(msg("u0", Role.user, "kept 0"));
            all.add(msg("u1", Role.user, "kept 1"));
            all.add(msg("u2", Role.user, "summarize 2"));
            all.add(msg("u3", Role.user, "summarize 3"));

            PartialCompactConversation.partialCompactConversation(all, 2, cc, null);

            assertThat(SessionReadFileStateRegistry.forSession(PARTIAL_SESSION).size())
                .as("partial 压缩成功后活表同样归零（CC partial 的 clear 与全量同形，2.1.88 compact.ts:918-921）")
                .isZero();
        }

        @Test
        @DisplayName("partial：tuc 缺失（会话未注册 AgentState ⇒ :816 早退）⇒ 活表【不可达】，如实返回 0 且不静默清空")
        void partialCompact_liveTableUnreachable_whenTucMissing() {
            seedLiveEntry(PARTIAL_NO_TUC_SESSION, "/p/unreachable.txt", 100L);

            CompactConversationContext cc = partialShapeContext(PARTIAL_NO_TUC_SESSION, null);
            assertThat(cc.getToolUseContext()).as("本用例模拟 tuc 未注入态").isNull();

            List<ChatMessageDto> all = new ArrayList<>();
            all.add(msg("u0", Role.user, "kept 0"));
            all.add(msg("u1", Role.user, "kept 1"));
            all.add(msg("u2", Role.user, "summarize 2"));
            all.add(msg("u3", Role.user, "summarize 3"));

            PartialCompactConversation.partialCompactConversation(all, 2, cc, null);

            assertThat(SessionReadFileStateRegistry.forSession(PARTIAL_NO_TUC_SESSION).size())
                .as("⛔ 本路径活表【结构上不可达】——不得假装清了；该缺口由 "
                    + "assembleForkCacheSharingMaterials:817 的 WARN 在装配侧留痕（已登记）")
                .isEqualTo(1);
        }

        /** 镜像 {@code PartialCompactService.buildContext:611-623} 的形态（含 :623 的一次性空 Map）。 */
        private static CompactConversationContext partialShapeContext(String sessionId, ToolUseContext tuc) {
            CompactConversationContext cc = new CompactConversationContext();
            cc.setSessionId(sessionId);
            cc.setAgentId("main");
            cc.setModel("claude-sonnet-4-5");
            cc.setQuerySource("compact");
            cc.setReadFileState(new LinkedHashMap<>());
            cc.setSummaryProducer(okProducer());
            if (tuc != null) {
                cc.setToolUseContext(tuc);
            }
            return cc;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (b) 反面：未 compact / compact 失败 ⇒ 活表必须原样（防挂错生命周期）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(b) 反面守卫：不该清的时候一条都不能少")
    class LiveTableUntouched {

        @Test
        @DisplayName("只构造上下文、不跑 compact ⇒ 活表条目必须还在（防把 clear 挂到构造/每次 run）")
        void noCompactYet_liveTableUntouched() {
            seedLiveEntry(NEG_SESSION, "/p/keep-a.txt", 100L);
            seedLiveEntry(NEG_SESSION, "/p/keep-b.txt", 90L);

            CompactConversationContext cc = CompactConversation.buildAutoContext(
                liveTuc(NEG_SESSION), "claude-sonnet-4-5", "repl_main_thread", null);
            cc.setSummaryProducer(okProducer());

            assertThat(SessionReadFileStateRegistry.forSession(NEG_SESSION).size())
                .as("仅构造上下文（buildAutoContext）不得清活表 —— 生命周期挂在压缩成功之后")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("compact 失败（摘要为空 ⇒ no_summary 抛错）⇒ 活表必须原样（防把 clear 提前到摘要之前）")
        void failedCompact_liveTableUntouched() {
            seedLiveEntry(NEG_SESSION, "/p/neg-a.txt", 100L);
            seedLiveEntry(NEG_SESSION, "/p/neg-b.txt", 90L);

            CompactConversationContext cc = CompactConversation.buildAutoContext(
                liveTuc(NEG_SESSION), "claude-sonnet-4-5", "repl_main_thread", null);
            // 摘要为空串 ⇒ 走 no_summary 抛错分支（compact.ts:493-506）
            cc.setSummaryProducer((messages, prompt, preTokens) ->
                new CompactConversation.SummaryResult("", null));

            assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), cc, false, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);

            assertThat(SessionReadFileStateRegistry.forSession(NEG_SESSION).size())
                .as("压缩失败不得作废会话已读状态（clear 必须在摘要成功之后，对齐 CC 步骤顺序）")
                .isEqualTo(2);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // (d) 容量：100 → 5000（对齐 CC 2.1.278 的 LC=5000），字节上限 25MB 不动
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("(d) 容量：5000 条 / 25MB（LC=5000 直译，字节上限两版一致不动）")
    class Capacity {

        @Test
        @DisplayName("会话活表 max()=5000、maxSize()=25MB（CC 2.1.278: var LC=5000,T=26214400）")
        void sessionCacheAlignedToCc21278() {
            FileStateCache cache = SessionReadFileStateRegistry.forSession("sess-cap-21278");

            assertThat(cache.max())
                .as("条目数上限 = CC 2.1.278 的 LC=5000（claude.exe off 199640137: var LC=5000,T=26214400,L=4096）")
                .isEqualTo(5000)
                .isEqualTo(ToolUseContext.READ_FILE_STATE_CACHE_SIZE);
            assertThat(cache.maxSize())
                .as("字节上限 = CC 两版一致的 25MB（T=26214400）—— 本批不动")
                .isEqualTo(25L * 1024L * 1024L)
                .isEqualTo(ToolUseContext.DEFAULT_MAX_CACHE_SIZE_BYTES);
        }

        @Test
        @DisplayName("写 5001 条不同 key ⇒ size()=5000（超限 LRU 驱逐按 5000 生效，不是把 100 隐式放大）")
        void writes5001Keys_keeps5000() {
            FileStateCache cache = SessionReadFileStateRegistry.forSession("sess-cap-evict");
            for (int i = 0; i < 5001; i++) {
                cache.set("/p/cap-" + i + ".txt",
                    new ToolUseContext.ReadState(1L, null, null, false, "x"));
            }

            assertThat(cache.size())
                .as("5000 是硬限：第 5001 条触发 LRU 驱逐，终态恰 5000")
                .isEqualTo(5000);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // clearReadFileState 的返回值即「清了几条活表」（供调用方日志/审计）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("clearReadFileState() 返回被清活表条目数；tuc 未接线 ⇒ 0（不静默）")
    void clearReadFileState_returnsClearedLiveCount() {
        seedLiveEntry(NEG_SESSION, "/p/c1.txt", 1L);
        seedLiveEntry(NEG_SESSION, "/p/c2.txt", 2L);

        CompactConversationContext wired = new CompactConversationContext().setSessionId(NEG_SESSION);
        wired.setToolUseContext(liveTuc(NEG_SESSION));
        assertThat(wired.clearReadFileState()).as("活表 2 条 ⇒ 返回 2").isEqualTo(2);
        assertThat(SessionReadFileStateRegistry.forSession(NEG_SESSION).size()).isZero();

        CompactConversationContext unwired = new CompactConversationContext().setSessionId(NEG_SESSION);
        assertThat(unwired.clearReadFileState())
            .as("tuc 未接线 ⇒ 0 = 活表未清（调用方可据此登记，不静默）")
            .isZero();
    }
}
