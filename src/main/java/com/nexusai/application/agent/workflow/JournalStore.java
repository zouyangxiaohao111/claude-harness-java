package com.nexusai.application.agent.workflow;

import java.io.IOException;
import java.util.List;

/**
 * Journal 持久化 · CC original: {@code JournalStore}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:92-96)。
 *
 * <p>WorkflowPorts 8 项依赖抽象之一「文件系统」。jsonl 文件实现 {@link FileJournalStore}。
 *
 * <p><b>read 必须按 seq 重排</b>：append 顺序 = 完成顺序（并行完成序 ≠ 调用序，可能乱序），
 * resume 的 key 索引稳定依赖 seq 重排（journal.ts:35-37）。
 */
public interface JournalStore {

    /**
     * 读取 run 的 journal 条目 · CC original: {@code read(runId): Promise<JournalEntry[]>} (ports.ts:93)。
     * 按 {@code (a.seq??0) - (b.seq??0)} 升序重排；读失败（文件不存在等）返回空列表
     * （对齐 journal.ts:38-40，不抛）。
     *
     * @param runId CC original: {@code runId}
     * @return 按 seq 升序重排后的条目列表（失败 → 空列表）
     */
    List<JournalEntry> read(String runId);

    /**
     * 追加一条 journal 条目 · CC original: {@code append(runId, entry): Promise<void>} (ports.ts:94)。
     * mkdir(recursive) + JSON + '\n' 追加；append 顺序 = 完成顺序（可能乱序，靠 read seq 重排兜底）。
     *
     * @param runId CC original: {@code runId}
     * @param entry CC original: {@code entry}
     * @throws IOException 文件系统 IO 错误（CC 层拒绝吞掉 append 错误）
     */
    void append(String runId, JournalEntry entry) throws IOException;

    /**
     * 截断 run 的 journal · CC original: {@code truncate(runId): Promise<void>} (ports.ts:95)。
     * 递归删除整个 run 目录（journal.ts:46-48）。
     *
     * @param runId CC original: {@code runId}
     * @throws IOException 文件系统 IO 错误
     */
    void truncate(String runId) throws IOException;
}
