package com.nexusai.application.agent.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * inline 脚本持久化 · CC original: {@code persistInlineScript}
 * (Open-ClaudeCode/packages/workflow-engine/src/tool/persistInline.ts:18-28)。
 *
 * <p>把 inline workflow 脚本写到 run 目录，使调用方可经 {@code scriptPath} + {@code resumeFromRunId}
 * 迭代而无需重发完整脚本（ultracode skill 承诺的 inline 入口往返）。
 *
 * <p>镜像 engine/journal.ts：直接经文件系统写到
 * {@code <cwd>/<WORKFLOW_RUNS_DIR>/<runId>/script.js}——与 journal.jsonl 同一目录，
 * 所以 {@code journalStore.truncate(runId)} 会连同清理。
 *
 * <p><b>固定文件名 {@code script.js}</b>：parseScript 忽略扩展名、runId 已使目录唯一，
 * 稳定名便于记忆（persistInline.ts:15-16）。
 */
public final class InlineScriptPersister {

    private InlineScriptPersister() {
    }

    /**
     * 持久化 inline 脚本 · CC original: persistInline.ts:18-28。
     *
     * @param script 脚本源码
     * @param runId  本次 run id（目录唯一性）
     * @param cwd    host.cwd（projectRoot）
     * @return 写入后的文件路径（绝对路径）
     * @throws IOException 写失败（调用方降级仅 log，不阻塞 run——service.ts:217-221）
     */
    public static Path persist(String script, String runId, String cwd) throws IOException {
        Path dir = Path.of(cwd, WorkflowConstants.WORKFLOW_RUNS_DIR, runId);
        Files.createDirectories(dir);
        Path file = dir.resolve("script.js");
        Files.writeString(file, script);
        return file;
    }
}
