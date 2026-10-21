package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务输出软链单测 · 对齐 CC {@code initTaskOutputAsSymlink}（diskOutput.ts:427-451）。
 *
 * <p>验证意图（规则九）：`.output` 必须**指向 transcript**，而不是另写一份内容 ——
 * CC/CCB 的 LocalAgentTask 注册时就把 output 软链到 agent transcript，之后全程不写；
 * 主代理 `Read` output 读到的就是完整执行记录。若此处退化成「写摘要」，
 * 语义与 CC 不一致，且会污染 transcript。
 *
 * <p>Windows 上创建符号链接需要开发者模式/管理员权限；没有权限时实现会 fallback 成空文件
 * （CC 同样有 `initTaskOutput` 兜底）——测试对两种结果都做断言，不假定平台能力。
 */
class TaskOutputSymlinkTest {

    @Test
    void creates_symlink_pointing_at_transcript(@TempDir Path tmp) throws Exception {
        Path transcript = tmp.resolve("agent-abc.jsonl");
        Files.writeString(transcript, "{\"role\":\"user\",\"content\":\"hi\"}\n");

        Path outputDir = tmp.resolve("tasks");
        String out = TaskOutputSymlink.initTaskOutputAsSymlink("agent-abc", outputDir, transcript);
        Path output = Path.of(out);

        assertEquals(outputDir.resolve("agent-abc.output").toString(), out,
            "输出文件名必须是 <taskId>.output");
        assertTrue(Files.exists(output), "输出路径必须存在（软链或 fallback 文件）");

        if (Files.isSymbolicLink(output)) {
            assertEquals(transcript, Files.readSymbolicLink(output),
                "软链目标必须是该 agent 的 transcript");
            assertEquals(Files.readString(transcript), Files.readString(output),
                "读 output 必须等于读 transcript（同一份内容，不是副本）");
        } else {
            // 平台不支持 symlink → CC 的 initTaskOutput 兜底：空文件
            assertEquals(0, Files.size(output), "fallback 必须是空文件（不写摘要）");
        }
    }

    @Test
    void replaces_existing_file_and_retries(@TempDir Path tmp) throws Exception {
        Path transcript = tmp.resolve("agent-def.jsonl");
        Files.writeString(transcript, "line\n");
        Path outputDir = tmp.resolve("tasks");
        Files.createDirectories(outputDir);
        // 预置一个普通文件占位（模拟上一轮遗留 / fallback 产物）
        Files.writeString(outputDir.resolve("agent-def.output"), "stale");

        String out = TaskOutputSymlink.initTaskOutputAsSymlink("agent-def", outputDir, transcript);
        Path output = Path.of(out);

        assertTrue(Files.exists(output));
        if (Files.isSymbolicLink(output)) {
            assertEquals(transcript, Files.readSymbolicLink(output), "占位文件必须被替换成软链");
        } else {
            assertEquals(0, Files.size(output), "无法建链时必须是空文件，绝不能保留旧内容");
        }
    }

    @Test
    void creates_missing_output_dir(@TempDir Path tmp) {
        // 父目录（.../{sessionId}/tasks）不再天然存在 → 实现必须先 mkdir recursive
        Path transcript = tmp.resolve("agent-ghi.jsonl");
        Path outputDir = tmp.resolve("deep").resolve("nested").resolve("tasks");

        String out = TaskOutputSymlink.initTaskOutputAsSymlink("agent-ghi", outputDir, transcript);

        assertTrue(Files.exists(Path.of(out)), "父目录必须自动创建（CC ensureOutputDir）");
    }
}
