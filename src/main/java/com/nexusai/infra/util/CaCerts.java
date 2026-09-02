package com.nexusai.infra.util;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CA Certs · 对齐 CC utils/caCerts.ts (115 行).
 *
 * <p>FIX-UTIL-CACERTS: CA 证书加载/缓存.
 *
 * <p>L1 行为: 给定 certs path, 读取 PEM 内容并缓存.
 */
@Component
public class CaCerts {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String certPath) throws IOException {
        String cached = cache.get(certPath);
        if (cached != null) return cached;
        String content = Files.readString(Path.of(certPath));
        cache.put(certPath, content);
        return content;
    }

    public void invalidate() {
        cache.clear();
    }

    public boolean isLoaded(String certPath) {
        return cache.containsKey(certPath);
    }
}