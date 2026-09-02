package com.nexusai.application.agent;

import com.nexusai.application.agent.file.FileHistoryService;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.FileHistoryState;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [FIX-C] makeSnapshot 生产接线 RED→GREEN 测试 · 对齐 CC QueryEngine.ts:641-655.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 文件历史备份管线此前<b>死锁</b>——
 * {@link FileHistoryService#makeSnapshot} 在生产路径<b>零调用方</b>（grep {@code .makeSnapshot(} 于
 * src/main 为空），导致 {@code FileHistoryState.snapshots} 恒为空 → {@code trackEdit}
 * （FileHistoryService.java:106-110）在 Phase 1 就命中"缺失最近快照" warn 并 return →
 * {@code EditFileTool} / {@code WriteFileTool} 的 pre-edit 备份在生产永不落地。
 *
 * <p>本测试钉死接线：{@code LlmAgentLoop.doRun} 必须在 turn 边界（用户 prompt 入队后、
 * {@code queryLoop(...)} 前）调用 {@code makeSnapshot}（对齐 CC QueryEngine.ts:645
 * {@code messagesFromUserInput.forEach(m => fileHistoryMakeSnapshot(..., m.uuid))}，位于
 * {@code for await (query(...))} 循环之前）。若接线缺失/回退，snapshots 恒空 → Test A/B fail。
 *
 * <p>harness 复制自 {@link LlmAgentLoopRunRequestContractTest}（真实 run() 驱动 doRun）；
 * [IMP-SP-08] ModelCaller 恒经 splitSysPromptPrefix → blocks 重载，必须 stub 17-arg blocks
 * 重载（stub 13-arg String 重载不会被委托到）。
 */
class LlmAgentLoopFileHistorySnapshotTest {

    /** 组装一个可真实 run() 的 LlmAgentLoop，并注入指定门控的 FileHistoryService。 */
    private static FileHistoryService runTurnWithFileHistory(FileHistoryService fhs) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("Hello from test provider");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("Hello from test provider", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setFileHistoryService(fhs);
        loop.run(RunRequest.forTest("hello", "test-model", null));
        return fhs;
    }

    /**
     * [FIX-C Test A] run() 一个真实 turn 后 snapshots 非空且 snapshotSequence == 1。
     *
     * <p>RED teeth: makeSnapshot 无生产调用方时 snapshots 恒空 → 本测试 fail。
     * 对齐 CC QueryEngine.ts:645：首个用户 turn 边界即建立 snapshot[0]（非 app 启动预建）。
     */
    @Test
    @DisplayName("run() 真实 turn 后 snapshots 非空 + snapshotSequence==1（CC QueryEngine.ts:645 边界接线）")
    void run_turn_producesNonEmptySnapshots() {
        FileHistoryService fhs = runTurnWithFileHistory(new FileHistoryService(() -> true));

        assertThat(fhs.currentState().snapshots())
            .as("makeSnapshot 必须在 turn 边界被调用，否则 snapshots 恒空（生产备份管线死锁）")
            .isNotEmpty();
        assertThat(fhs.currentState().snapshotSequence())
            .as("首个 turn 边界应建立 snapshot[0]（snapshotSequence 从 0 递增到 1）")
            .isEqualTo(1);
    }

    /**
     * [FIX-C Test B] run() 后 trackEdit 在 mostRecent 快照回填备份 —— 死锁闭环证明。
     *
     * <p>RED teeth: 无 makeSnapshot 调用方时 trackEdit 恒命中 Phase 1 缺失快照 return →
     * {@code trackedFileBackups().get(abs)} 为 null / {@code trackedFiles()} 不含该文件。
     * 本测试证明 trackEdit 在<b>生产态</b>（run() 后）不再 Phase-1 短路，Phase 2/3 回填可达。
     */
    @Test
    @DisplayName("run() 后 trackEdit 在 mostRecent 快照回填备份（trackEdit 不再 Phase-1 短路）")
    void afterRun_trackEdit_backfillsMostRecentSnapshot(@TempDir Path tempDir) throws Exception {
        FileHistoryService fhs = runTurnWithFileHistory(new FileHistoryService(() -> true));

        Path file = tempDir.resolve("target.txt");
        Files.writeString(file, "pre-edit");
        String abs = file.toAbsolutePath().normalize().toString();

        // createBackup 真落盘 → 隔离 nexusai 自有根防止备份写入真实 ~/.nexusai（测试污染）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        try {
            fhs.trackEdit(abs, "msg-1", "sess-1");
        } finally {
            ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
            NexusaiPaths.setAppNameOverride(null);
        }

        FileHistoryState.FileHistorySnapshot mostRecent =
            fhs.currentState().snapshots().get(fhs.currentState().snapshots().size() - 1);
        assertThat(mostRecent.trackedFileBackups().get(abs))
            .as("run() 已建快照后 trackEdit 必须回填 mostRecent 备份（Phase 1 不再短路）")
            .isNotNull();
        assertThat(fhs.currentState().trackedFiles())
            .as("trackEdit Phase 3 必须把 trackingPath 加入 trackedFiles")
            .contains(abs);
    }

    /**
     * [FIX-C Test C] 门控关闭（fileHistoryEnabled=false）→ run() 后 snapshots 仍空。
     *
     * <p>对齐 CC fileHistory.ts:63-71 {@code fileHistoryEnabled() === false} 时 makeSnapshot
     * 内部门控 return（FileHistoryService.java:171-176）——loop 调用点无需显式 gate，
     * 门控活在 makeSnapshot 内部。
     */
    @Test
    @DisplayName("门控关闭（fileHistoryEnabled=false）→ run() 后 snapshots 仍空")
    void gateOff_noSnapshotsAfterRun() {
        FileHistoryService fhs = runTurnWithFileHistory(new FileHistoryService(() -> false));

        assertThat(fhs.currentState().snapshots())
            .as("fileHistoryEnabled=false 时 makeSnapshot 内部门控 return，snapshots 保持空")
            .isEmpty();
        assertThat(fhs.currentState().snapshotSequence())
            .as("门控关闭时 snapshotSequence 保持 0（makeSnapshot 未执行）")
            .isZero();
    }
}
