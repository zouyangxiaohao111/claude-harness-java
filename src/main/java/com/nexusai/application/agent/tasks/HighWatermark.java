package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * High Water Mark 管理器 · ID 单调递增防重用
 *
 * <p>s12.5-1.1 (L3 拆分): 从 TaskService 中提取 high water mark 逻辑。
 *
 * <h2>对齐 CC tasks.ts:114-131</h2>
 * <p>high water mark 保证任务 ID 单调递增，即使任务被删除后 ID 也不会复用。
 * 设计为 package-private，仅由 {@link TaskService} 使用。
 *
 * @see TaskFileStorage
 * @see TaskService
 */
class HighWatermark {

    private static final Logger log = LoggerFactory.getLogger(HighWatermark.class);

    private final TaskFileStorage fileStorage;

    HighWatermark(TaskFileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 读取 high water mark · 对齐 CC tasks.ts:114-123 readHighWaterMark()
     */
    long readHighWaterMark(String taskListId) {
        Path path = fileStorage.getHighWaterMarkPath(taskListId);
        try {
            String content = Files.readString(path).trim();
            long value = Long.parseLong(content);
            return value < 0 ? 0 : value;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 写入 high water mark · 对齐 CC tasks.ts:125-131 writeHighWaterMark()
     */
    void writeHighWaterMark(String taskListId, long value) throws IOException {
        Path path = fileStorage.getHighWaterMarkPath(taskListId);
        Files.writeString(path, String.valueOf(value));
    }

    /**
     * 从文件中查找最高任务 ID · 对齐 CC tasks.ts:246-265 findHighestTaskIdFromFiles()
     */
    long findHighestTaskIdFromFiles(String taskListId) {
        Path dir = fileStorage.getTasksDir(taskListId);
        long highest = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String idStr = fileName.replace(".json", "");
                try {
                    long id = Long.parseLong(idStr);
                    if (id > highest) {
                        highest = id;
                    }
                } catch (NumberFormatException e) {
                    // 忽略非数字文件名
                }
            }
        } catch (IOException e) {
            // 目录不存在或读取失败
        }
        return highest;
    }
}
