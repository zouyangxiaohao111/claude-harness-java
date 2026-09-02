package com.nexusai.application.agent.workflow.persistence;

import com.nexusai.application.agent.workflow.ProgressBus;
import com.nexusai.application.agent.workflow.ProgressEvent;
import com.nexusai.application.agent.workflow.progress.ProgressStore;
import com.nexusai.application.agent.workflow.progress.RunProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowRunPersistence 测试 · 对齐 CC persistence.ts（W-3c）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>原子 + best-effort</b>（persistence.ts:42-63）— 写盘失败不能阻断 workflow 已完成的事
 *       实（IO 异常吞掉仅 log）；写盘成功必须能被重启后 readRunState 取回（roundtrip）。</li>
 *   <li><b>fault-tolerant 读取</b>（persistence.ts:65-95）— 文件缺失 / schemaVersion 不符 /
 *       损坏 JSON 都返回 null 而非 crash（面板/工具查询不被历史垃圾炸掉）。</li>
 *   <li><b>磁盘有界</b>（persistence.ts:136-171）— 孤儿（无 state.json）先清 + 超 cap 最旧先删；
 *       若 orphan 不优先清，残留的半写入目录会无限累积（每次写中断留一个）。</li>
 *   <li><b>run_done → 自动落盘</b>（persistence.ts:190-210）— 引擎终态事件必须触发磁盘持久化，
 *       否则"重启后 resume / 取历史返回"承诺落空。</li>
 * </ol>
 */
class WorkflowRunPersistenceTest {

    @TempDir
    Path tmpDir;

    private WorkflowRunPersistence newPersistence() {
        return new WorkflowRunPersistence(() -> tmpDir.toString());
    }

    /** 构建测试 RunProgress（最小字段集 + updatedAt 可指定）。 */
    private static RunProgress run(String runId, RunProgress.Status status, long updatedAt) {
        return RunProgress.builder()
                .runId(runId)
                .workflowName("wf-" + runId)
                .status(status)
                .phases(List.of())
                .declaredPhases(List.of())
                .currentPhase(null)
                .agents(List.of())
                .agentCount(0)
                .returnValue(null)
                .error(null)
                .startedAt(updatedAt - 10)
                .description(null)
                .updatedAt(updatedAt)
                .build();
    }

    @Test
    @DisplayName("writeRunState → readRunState 往返保真（完成态可重启取回）")
    void writeAndRead_roundTrip_preservesTerminalState() {
        WorkflowRunPersistence persistence = newPersistence();
        RunProgress terminal = RunProgress.builder()
                .runId("r-1")
                .workflowName("spec")
                .status(RunProgress.Status.FAILED)
                .phases(List.of(new RunProgress.Phase("p1", RunProgress.PhaseState.DONE)))
                .declaredPhases(List.of("p1"))
                .currentPhase(null)
                .agents(List.of())
                .agentCount(0)
                .returnValue(null)
                .error("agent boom")
                .startedAt(1000L)
                .description("run desc")
                .updatedAt(2000L)
                .build();

        persistence.writeRunState(tmpDir.toString(), terminal);
        RunProgress loaded = persistence.readRunState(tmpDir.toString(), "r-1");

        assertThat(loaded).isNotNull();
        assertThat(loaded.runId()).isEqualTo("r-1");
        assertThat(loaded.workflowName()).isEqualTo("spec");
        assertThat(loaded.status()).isEqualTo(RunProgress.Status.FAILED);
        assertThat(loaded.error()).isEqualTo("agent boom");
        assertThat(loaded.updatedAt()).isEqualTo(2000L);
        assertThat(loaded.phases()).containsExactly(new RunProgress.Phase("p1", RunProgress.PhaseState.DONE));
    }

    @Test
    @DisplayName("writeRunState 在 runsDir 缺失时递归建目录")
    void writeRunState_createsMissingRunsDirRecursively() {
        WorkflowRunPersistence persistence = newPersistence();
        Path nested = tmpDir.resolve("a/b");
        persistence.writeRunState(nested.toString(), run("r-1", RunProgress.Status.COMPLETED, 100L));

        assertThat(Files.isRegularFile(nested.resolve("r-1/state.json"))).isTrue();
        assertThat(Files.exists(nested.resolve("r-1/state.json.tmp"))).isFalse();
    }

    @Test
    @DisplayName("readRunState 文件缺失 → null（静默 miss，不 warn）")
    void readRunState_missingFile_returnsNull() {
        WorkflowRunPersistence persistence = newPersistence();
        assertThat(persistence.readRunState(tmpDir.toString(), "no-such-run")).isNull();
    }

    @Test
    @DisplayName("readRunState schemaVersion 不符 → null")
    void readRunState_wrongSchemaVersion_returnsNull() throws Exception {
        Path dir = Files.createDirectories(tmpDir.resolve("r-x"));
        // CC persistence.ts:83 未来 schema 升级引入迁移链；旧版读新版文件必须当 miss 而非崩溃
        Files.writeString(dir.resolve("state.json"),
                "{\"schemaVersion\": 999, \"run\": {\"runId\": \"r-x\", \"status\": \"completed\"}}",
                StandardCharsets.UTF_8);

        assertThat(newPersistence().readRunState(tmpDir.toString(), "r-x")).isNull();
    }

    @Test
    @DisplayName("readRunState 损坏 JSON → null（fault-tolerant 不 crash）")
    void readRunState_corruptedJson_returnsNull() throws Exception {
        Path dir = Files.createDirectories(tmpDir.resolve("r-bad"));
        Files.writeString(dir.resolve("state.json"), "{not json at all", StandardCharsets.UTF_8);

        assertThat(newPersistence().readRunState(tmpDir.toString(), "r-bad")).isNull();
    }

    @Test
    @DisplayName("listPersistedRuns 按 updatedAt 降序 + limit 截断")
    void listPersistedRuns_sortsDescendingAndAppliesLimit() {
        WorkflowRunPersistence persistence = newPersistence();
        persistence.writeRunState(tmpDir.toString(), run("r-low", RunProgress.Status.COMPLETED, 100L));
        persistence.writeRunState(tmpDir.toString(), run("r-high", RunProgress.Status.FAILED, 300L));
        persistence.writeRunState(tmpDir.toString(), run("r-mid", RunProgress.Status.KILLED, 200L));

        List<RunProgress> all = persistence.listPersistedRuns(tmpDir.toString(), null);
        assertThat(all).extracting(RunProgress::runId)
                .containsExactly("r-high", "r-mid", "r-low");

        List<RunProgress> limited = persistence.listPersistedRuns(tmpDir.toString(), 2);
        assertThat(limited).extracting(RunProgress::runId).containsExactly("r-high", "r-mid");
    }

    @Test
    @DisplayName("listPersistedRuns runsDir 不存在 → 空列表")
    void listPersistedRuns_missingRunsDir_returnsEmpty() {
        assertThat(newPersistence().listPersistedRuns(tmpDir.resolve("absent").toString(), null)).isEmpty();
    }

    @Test
    @DisplayName("cleanupOldRuns 孤儿（无 state.json）先清 + 超 cap 最旧先删")
    void cleanupOldRuns_prunesOrphansAndOldest() {
        WorkflowRunPersistence persistence = newPersistence();
        persistence.writeRunState(tmpDir.toString(), run("r5", RunProgress.Status.COMPLETED, 500L));
        persistence.writeRunState(tmpDir.toString(), run("r4", RunProgress.Status.COMPLETED, 400L));
        persistence.writeRunState(tmpDir.toString(), run("r3", RunProgress.Status.COMPLETED, 300L));
        persistence.writeRunState(tmpDir.toString(), run("r2", RunProgress.Status.COMPLETED, 200L));
        persistence.writeRunState(tmpDir.toString(), run("r1", RunProgress.Status.COMPLETED, 100L));
        // 孤儿：半写入目录（无 state.json）→ updatedAt=0 → 沉底先被清（persistence.ts:150-151）
        try {
            Files.createDirectories(tmpDir.resolve("orphan"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        int removed = persistence.cleanupOldRuns(tmpDir.toString(), 2);

        // cap=2 → 保留最新 r5/r4；清掉 r3/r2/r1 + orphan = 4
        assertThat(removed).isEqualTo(4);
        assertThat(Files.exists(tmpDir.resolve("r5"))).isTrue();
        assertThat(Files.exists(tmpDir.resolve("r4"))).isTrue();
        assertThat(Files.exists(tmpDir.resolve("r3"))).isFalse();
        assertThat(Files.exists(tmpDir.resolve("orphan"))).isFalse();
    }

    @Test
    @DisplayName("cleanupOldRuns 幂等：已低于 cap → no-op")
    void cleanupOldRuns_idempotent_whenUnderCap() {
        WorkflowRunPersistence persistence = newPersistence();
        persistence.writeRunState(tmpDir.toString(), run("r1", RunProgress.Status.COMPLETED, 100L));
        assertThat(persistence.cleanupOldRuns(tmpDir.toString(), 50)).isZero();
        assertThat(Files.exists(tmpDir.resolve("r1"))).isTrue();
    }

    @Test
    @DisplayName("attachRunStatePersistence 订阅 run_done → 终态写盘")
    void attachRunStatePersistence_writesTerminalStateOnRunDone() {
        ProgressBus bus = new ProgressBus();
        ProgressStore store = new ProgressStore(bus);
        WorkflowRunPersistence persistence = newPersistence();
        Runnable unsubscribe = persistence.attachRunStatePersistence(bus, store);

        try {
            // store 先于持久化订阅（ProgressStore 构造订阅）；run_done 时 store.get 已是终态
            bus.emit(new ProgressEvent.RunStarted("r-1", "spec", null));
            bus.emit(new ProgressEvent.RunDone("r-1", ProgressEvent.RunStatus.COMPLETED, "return-value", null));

            Path stateFile = tmpDir.resolve("r-1/state.json");
            assertThat(Files.isRegularFile(stateFile)).isTrue();
            RunProgress loaded = persistence.readRunState(tmpDir.toString(), "r-1");
            assertThat(loaded).isNotNull();
            assertThat(loaded.status()).isEqualTo(RunProgress.Status.COMPLETED);
            assertThat(loaded.returnValue()).isEqualTo("return-value");
        } finally {
            unsubscribe.run();
        }
    }
}
