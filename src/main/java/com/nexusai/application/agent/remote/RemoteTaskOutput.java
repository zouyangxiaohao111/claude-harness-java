package com.nexusai.application.agent.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * remote_agent 输出文件工具 · 对齐 CC utils/task/diskOutput.ts
 * （initTaskOutput :400-421 / appendTaskOutput :268-270 / evictTaskOutput :288-298）。
 *
 * <p>CC 语义：
 * <ul>
 *   <li>initTaskOutput — O_CREAT|O_EXCL 建空文件保证路径存在；文件已存在（resume）O_EXCL 抛错被
 *       fire-and-forget 吞掉（diskOutput.ts:418-420 track + catch）→ Java 端 exists 跳过；</li>
 *   <li>appendTaskOutput — 追加写入（append-only）；</li>
 *   <li>evictTaskOutput — 刷盘后从内存 map 移除（CC 不删磁盘文件，diskOutput.ts:284-286）→
 *       Java 无内存缓冲，仅日志标记。</li>
 * </ul>
 */
public final class RemoteTaskOutput {

    private static final Logger log = LoggerFactory.getLogger(RemoteTaskOutput.class);

    private RemoteTaskOutput() { /* utility class */ }

    /** 输出文件绝对路径 · 对齐 CC getTaskOutputPath（diskOutput.ts:52-55）= {taskOutputDir}/{taskId}.output */
    public static Path outputPath(Path taskOutputDir, String taskId) {
        return taskOutputDir.resolve(taskId + ".output");
    }

    /**
     * 初始化输出文件（空文件）。文件已存在（--resume 恢复）时跳过 — 对齐 CC initTaskOutput
     * O_EXCL 失败被 fire-and-forget 吞掉（diskOutput.ts:400-421）。
     */
    public static void init(Path outputPath) {
        if (outputPath == null) {
            return;
        }
        try {
            Files.createDirectories(outputPath.getParent());
            if (!Files.exists(outputPath)) {
                Files.createFile(outputPath);
            }
            if (log.isDebugEnabled()) {
                log.debug("RemoteTaskOutput.init: 输出文件就绪 path={}", outputPath);
            }
        } catch (IOException e) {
            // CC track(...).catch(()=>{}) 吞掉 — 不阻塞注册/恢复
            if (log.isDebugEnabled()) {
                log.debug("RemoteTaskOutput.init: 初始化失败 {}: {}", outputPath, e.getMessage());
            }
        }
    }

    /** 追加输出 · 对齐 CC appendTaskOutput（diskOutput.ts:268-270）。失败仅日志。 */
    public static void append(Path outputPath, String content) {
        if (outputPath == null || content == null || content.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("RemoteTaskOutput.append: 写入失败 path={} 错误={}", outputPath, e.getMessage());
        }
    }

    /** 刷盘/内存移除 — Java 无内存缓冲，仅日志（对齐 CC evictTaskOutput 不删磁盘文件）。 */
    public static void evict(String taskId) {
        if (log.isDebugEnabled()) {
            log.debug("RemoteTaskOutput.evict: taskId={} 内存缓冲已清（磁盘文件保留）", taskId);
        }
    }
}
