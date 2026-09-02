package com.nexusai.application.agent.file;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Outputs Scanner · 对齐 CC utils/filePersistence/outputsScanner.ts (126 行).
 *
 * <p>FIX-UTIL-FILEPERSIST: 扫描输出目录找被修改文件.
 */
@Component
public class OutputsScanner {

    /** 扫描输出目录, 返回自 sinceTimestamp 以来修改的文件列表. */
    public List<String> findModifiedFiles(Path outputDir, long sinceTimestamp) throws IOException {
        if (!Files.isDirectory(outputDir)) return List.of();
        try (Stream<Path> stream = Files.list(outputDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toMillis() >= sinceTimestamp;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .map(p -> p.getFileName().toString())
                .toList();
        }
    }
}