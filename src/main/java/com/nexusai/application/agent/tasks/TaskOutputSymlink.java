package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * 任务输出文件 ↔ transcript 的软链初始化 · 对齐 CC {@code initTaskOutputAsSymlink}
 * （`utils/task/diskOutput.ts:427-451`）。
 *
 * <p><b>为什么必须是软链</b>（CC / CCB 双向核对，2026-10-21）：
 * <ul>
 *   <li>{@code Tasks/LocalAgentTask.tsx:540/:608} 注册子代理时调用
 *       {@code initTaskOutputAsSymlink(agentId, getAgentTranscriptPath(agentId))}；</li>
 *   <li>此后**全程没有任何代码往 output 写内容** —— 任务结束只把 outputPath 拼进通知文本，
 *       由主代理自己 `Read` 这个路径。</li>
 * </ul>
 * ⇒ `.output` 与 `subagents/agent-*.jsonl` 是**同一个文件**，不是两份拷贝。
 * 主代理读 output 就等于读完整执行记录；若另写一份摘要，读到的语义与 CC 不一致。
 *
 * <p><b>失败策略</b>（与 CC 一致）：symlink → 已存在则 unlink 重试 → 再失败则建空文件兜底。
 * 输出初始化失败绝不外抛，避免阻断任务注册。
 */
public final class TaskOutputSymlink {

    private static final Logger log = LoggerFactory.getLogger(TaskOutputSymlink.class);

    private TaskOutputSymlink() {
    }

    /**
     * 把 {@code <sessionDir>/<sessionId>/tasks/<taskId>.output} 初始化为 {@code targetPath} 的软链。
     *
     * @param taskId     任务 ID（= 子代理 agentId）
     * @param outputDir  输出目录（调用方各自决定：子代理走 tmp 任务根、主会话后台化走项目目录）
     * @param targetPath 软链目标（通常是该 agent 的 transcript .jsonl）
     * @return 输出文件路径（软链成功或兜底普通文件）
     */
    public static String initTaskOutputAsSymlink(String taskId, Path outputDir, Path targetPath) {
        Path outputPath = outputDir.resolve(taskId + ".output");
        try {
            Files.createDirectories(outputDir);
            try {
                Files.createSymbolicLink(outputPath, targetPath);
                log.info("任务输出 symlink 已创建: output={} -> target={}", outputPath, targetPath);
                return outputPath.toString();
            } catch (Exception e) {
                // 已存在 / 平台不支持 symlink / 无效 target → unlink 重试（CC 裸 catch + 重试）
                log.warn("symlink 创建失败，尝试 unlink 重试: {}: {}", outputPath, e.getMessage());
                try {
                    Files.deleteIfExists(outputPath);
                    Files.createSymbolicLink(outputPath, targetPath);
                    log.info("任务输出 symlink 重试成功: output={}", outputPath);
                    return outputPath.toString();
                } catch (Exception e2) {
                    log.warn("symlink 重试失败，fallback 普通文件: {}", e2.getMessage());
                    return writeFallbackOutput(outputPath);
                }
            }
        } catch (Exception e) {
            log.warn("输出目录创建失败: {}", e.getMessage());
            return writeFallbackOutput(outputPath);
        }
    }

    /** 兜底：O_EXCL 建空文件（绝不跟随/截断已存在路径，对齐 CC diskOutput.ts:17-18 SECURITY）。 */
    private static String writeFallbackOutput(Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            try (FileChannel ignored = FileChannel.open(
                    outputPath, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                // 空文件即已创建
            }
            return outputPath.toString();
        } catch (Exception e) {
            log.error("fallback 输出文件创建失败: {}", e.getMessage());
            return outputPath.toString();
        }
    }
}
