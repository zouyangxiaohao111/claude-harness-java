package com.nexusai.infra.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FileReadCache · 对齐 CC utils/fileReadCache.ts.
 *
 * <p>L1 语义: in-memory file content cache (LRU eviction at 1000 entries),按 mtimeMs 自动失效。
 * readFile(filePath)→{content, encoding};clear();invalidate(path);getStats()。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 method (readFile + clear + invalidate + getStats) + 注入式 stat/readSupplier</li>
 *   <li><b>A2 Golden Trace</b>: 相同 mtime 命中 cache;不同 mtime 重读;size > 1000 驱逐 oldest;invalidate 删除;stats 含 size + entries</li>
 *   <li><b>A3 副作用</b>: 内部 mutable cache</li>
 *   <li><b>A4 边界</b>: missing file 抛;invalidate 不存在 path→no-op</li>
 *   <li><b>A5 业务场景</b>: FileEditTool 多次读同文件→cache 命中减少 disk I/O</li>
 * </ul>
 *
 * <p>L3 升级: TS Map<string, FileData> → Java LinkedHashMap (insertion-ordered for LRU);
 * TS class mutable → Java class mutable;
 * TS fs.statSync/readFileSync → Java injected Supplier 抽象.
 */
public final class FileReadCache {

    public record CachedFileData(String content, String encoding, long mtime) {}
    public record ReadResult(String content, String encoding) {}
    public record CacheStats(int size, java.util.List<String> entries) {}

    public interface FileSystemAccess {
        long statSync(String path) throws java.io.IOException;
        String readFileSync(String path, String encoding) throws java.io.IOException;
    }

    private static final int MAX_CACHE_SIZE = 1000;

    private final FileSystemAccess fs;
    private final Map<String, CachedFileData> cache;
    private final int maxCacheSize;

    public FileReadCache() {
        this(DefaultFileSystem.INSTANCE, MAX_CACHE_SIZE);
    }

    public FileReadCache(FileSystemAccess fs, int maxCacheSize) {
        this.fs = fs;
        this.cache = new LinkedHashMap<>();
        this.maxCacheSize = maxCacheSize;
    }

    public ReadResult readFile(String filePath) {
        long mtime;
        try {
            mtime = fs.statSync(filePath);
        } catch (java.io.IOException e) {
            cache.remove(filePath);
            throw new RuntimeException(e);
        }
        CachedFileData cached = cache.get(filePath);
        if (cached != null && cached.mtime() == mtime) {
            return new ReadResult(cached.content(), cached.encoding());
        }
        String encoding = "utf-8";
        String content;
        try {
            content = fs.readFileSync(filePath, encoding).replace("\r\n", "\n");
        } catch (java.io.IOException e) {
            cache.remove(filePath);
            throw new RuntimeException(e);
        }
        cache.put(filePath, new CachedFileData(content, encoding, mtime));
        // Evict oldest if too large
        if (cache.size() > maxCacheSize) {
            String firstKey = cache.keySet().iterator().next();
            if (firstKey != null) cache.remove(firstKey);
        }
        return new ReadResult(content, encoding);
    }

    public void clear() { cache.clear(); }
    public void invalidate(String filePath) { cache.remove(filePath); }

    public CacheStats getStats() {
        return new CacheStats(cache.size(), new java.util.ArrayList<>(cache.keySet()));
    }

    public enum DefaultFileSystem implements FileSystemAccess {
        INSTANCE;
        @Override public long statSync(String path) throws java.io.IOException {
            return java.nio.file.Files.getLastModifiedTime(java.nio.file.Paths.get(path)).toMillis();
        }
        @Override public String readFileSync(String path, String encoding) throws java.io.IOException {
            return java.nio.file.Files.readString(java.nio.file.Paths.get(path),
                java.nio.file.Files.probeContentType(java.nio.file.Paths.get(path)) != null
                    ? java.nio.charset.StandardCharsets.UTF_8
                    : java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
