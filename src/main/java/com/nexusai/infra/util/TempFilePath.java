package com.nexusai.infra.util;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * TempFilePath · 对齐 CC utils/tempfile.ts.
 */
public final class TempFilePath {

    public static final String DEFAULT_PREFIX = "claude-prompt";
    public static final String DEFAULT_EXTENSION = ".md";
    public static final int HASH_SLICE = 16;

    private TempFilePath() {}

    public static String generateTempFilePath(
        String prefix,
        String extension,
        String contentHash,
        Supplier<String> idGenerator,
        Supplier<String> tmpdir,
        BiFunction<String, String, String> sha256Hasher) {
        String p = (prefix == null) ? DEFAULT_PREFIX : prefix;
        String e = (extension == null) ? DEFAULT_EXTENSION : extension;
        String dir = (tmpdir == null) ? "" : tmpdir.get();
        String id;
        if (contentHash != null) {
            String hash;
            if (sha256Hasher != null) {
                hash = sha256Hasher.apply(contentHash, "utf-8");
            } else {
                hash = contentHash;
            }
            int len = hash.length();
            id = len > HASH_SLICE ? hash.substring(0, HASH_SLICE) : hash;
        } else {
            id = (idGenerator == null) ? UUID.randomUUID().toString() : idGenerator.get();
        }
        if (id == null) id = UUID.randomUUID().toString();
        if (dir == null || dir.isEmpty()) return p + "-" + id + e;
        return dir + "/" + p + "-" + id + e;
    }
}
