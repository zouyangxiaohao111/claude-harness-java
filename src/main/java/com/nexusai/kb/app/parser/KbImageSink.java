package com.nexusai.kb.app.parser;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 解析副产物（图片）落盘目录解析。
 *
 * <p><b>为什么不写死路径</b>：本仓临时根统一由 {@link NexusaiPaths#getAppTempPath()} 决定
 * （受 appName / CLAUDE_CODE_TMPDIR / 平台影响），另起一套路径会让「换 appName 后旧临时文件
 * 再也清理不到」。故默认落在 {@code {appTemp}/kb-parse/<文件名>-<纳秒>/}，一个文档一个目录，
 * 便于调用方整体删除。
 *
 * <p><b>优先用调用方给的目录</b>：上层编排（入库任务）通常已按 knowledge id 建好目录并负责生命周期，
 * 此时直接复用，避免双层临时目录。
 */
final class KbImageSink {

    private static final Logger log = LoggerFactory.getLogger(KbImageSink.class);

    private KbImageSink() {
    }

    /**
     * 解析图片输出目录（不存在则创建）。
     *
     * @param file 源文件（用于生成易读的子目录名）
     * @param ctx  解析上下文（{@code workDir} 非空则直接用）
     * @return 已存在的目录
     */
    static Path resolveImageDir(Path file, KbParseContext ctx) {
        Path base = ctx != null ? ctx.workDir() : null;
        if (base == null) {
            String name = file.getFileName() == null ? "doc" : file.getFileName().toString();
            base = NexusaiPaths.getAppTempPath()
                .resolve("kb-parse")
                .resolve(sanitize(name) + "-" + System.nanoTime());
        }
        try {
            Files.createDirectories(base);
            return base;
        } catch (IOException e) {
            throw new KbParseException("创建解析副产物目录失败: " + base + " — " + e.getMessage(), e);
        }
    }

    /** 文件名 → 目录安全片段（去掉路径分隔符与 Windows 非法字符）。 */
    static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "doc";
        }
        String s = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        // 目录名过长在 Windows 上会触发路径长度上限，截断到 60 字符留余量
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /**
     * 尽力递归删除目录（清理临时副产物）。
     *
     * <p>失败只记 warn：清理不成功不该让「已经解析成功」的结果变成失败。
     */
    static void deleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力清理
                }
            });
        } catch (IOException e) {
            log.warn("KbImageSink: 清理解析副产物目录失败 dir={} 原因={}", dir, e.getMessage());
        }
    }
}
