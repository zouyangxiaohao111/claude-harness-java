package com.nexusai.kb.app.config;

import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地磁盘文件存储（批次 W8）· {@code provider = "local"}。
 *
 * <p><b>⭐ 根目录与既有代码同一个</b>：{@code nexusai.knowledge.storage-dir}
 * （默认 {@code ${user.home}/.nexusai/kb-storage}），见
 * {@code KnowledgeController#storageDir}。⇒ 已经入库的文件不需要迁移，
 * 换个入口读到的还是同一批文件。
 *
 * <p><b>⚠️ 路径逃逸必须拦</b>：{@code key} 来自 URL / 元数据，
 * 若含 {@code ../} 就能读写存储根目录之外的任意文件。
 * 本实现把最终路径 normalize 后强制校验仍在根目录之下 —— 与
 * {@code KnowledgeExtController} 的同类校验口径一致。
 */
@Component
public class LocalFileStorage implements KbFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    public LocalFileStorage(
        @Value("${nexusai.knowledge.storage-dir:}") String storageDir
    ) {
        // [data-root] 未显式配置 ⇒ 统一数据根（安装目录优先，configHome 兜底）
        String dir = (storageDir == null || storageDir.isBlank())
            ? com.nexusai.common.NexusaiDataRoots.sub("kb-storage").toString()
            : storageDir;
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public String provider() {
        return KbStorageBackendTypes.PROVIDER_LOCAL;
    }

    @Override
    public boolean available() {
        // 本地磁盘恒可用 —— 它是「没有 MinIO 就本地」的兜底
        return true;
    }

    @Override
    public String write(String key, InputStream in, long size) {
        Path target = resolveSafely(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException e) {
            throw new IllegalStateException("写入文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream read(String key) {
        Path target = resolveSafely(key);
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolveSafely(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("[本地存储] 删除文件失败: {} ({})", target, e.toString());
        }
    }

    @Override
    public Path localPath(String key) {
        return resolveSafely(key);
    }

    /** 存储根目录（供需要展示「文件在哪」的地方用）。 */
    public Path root() {
        return root;
    }

    /**
     * 把相对键解析为根目录下的绝对路径，并校验没有跳出根目录。
     *
     * @throws ValidationException 键非法（绝对路径 / 含 {@code ../} 逃逸）
     */
    private Path resolveSafely(String key) {
        if (key == null || key.isBlank()) {
            throw new ValidationException("文件键不能为空");
        }
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root)) {
            throw new ValidationException("非法文件路径：" + key);
        }
        return candidate;
    }
}
