package com.nexusai.wechatlog.service;

import com.nexusai.wechatlog.crypto.CipherSuite;
import com.nexusai.wechatlog.crypto.KeyMatcher;
import com.nexusai.wechatlog.key.WeChatKeyExtractor;
import com.nexusai.wechatlog.key.elevation.ElevatedCommandBuilder;
import com.nexusai.wechatlog.key.elevation.ElevationLauncher;
import com.nexusai.wechatlog.key.elevation.WeChatKeyExtractionSupport;
import com.nexusai.wechatlog.key.elevation.WindowsElevatedKeyExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缓存 → 取密钥的决策单测。
 *
 * <p>核心两条：缓存命中绝不再取（不提权）；缓存失效/坏缓存必须自愈为「取一次」，
 * 且<b>先校验后落盘</b>。
 */
class WeChatLogServiceCacheTest {

    static final class StubExtractor implements WeChatKeyExtractor {
        int calls;
        List<byte[]> result = List.of();

        @Override
        public List<byte[]> extract(Path accountDir) {
            calls++;
            return result;
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public String platformName() {
            return "stub";
        }
    }

    static final class MatcherStub implements BiFunction<List<Path>, List<byte[]>, List<KeyMatcher.Match>> {
        final Set<String> acceptedPrefixes;
        int calls;

        MatcherStub(Set<String> acceptedPrefixes) {
            this.acceptedPrefixes = new HashSet<>(acceptedPrefixes);
        }

        @Override
        public List<KeyMatcher.Match> apply(List<Path> dbs, List<byte[]> candidates) {
            calls++;
            for (byte[] c : candidates) {
                if (acceptedPrefixes.contains(hex(c).substring(0, 2))) {
                    return List.of(new KeyMatcher.Match(Path.of("dummy.db"), c,
                            CipherSuite.V4_SHA512, KeyMatcher.Origin.RAW_KEY));
                }
            }
            return List.of();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static List<byte[]> key(byte fill) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, fill);
        return List.of(b);
    }

    /** 构造一个「永不提权」的 support：非 Windows ⇒ 直接委托 stub。 */
    private static WeChatKeyExtractionSupport support(StubExtractor stub) throws IOException {
        Path temp = Files.createTempDirectory("nexusai-svc-elev-");
        ElevationLauncher unused = (cmd, timeout) ->
                ElevationLauncher.LaunchResult.unavailable("unused");
        ElevatedCommandBuilder builder = (a, o) -> List.of("java.exe", a.toString(), o.toString());
        WindowsElevatedKeyExtractor ex = new WindowsElevatedKeyExtractor(
                builder, unused, temp, (p, d) -> { }, Duration.ofSeconds(1));
        return new WeChatKeyExtractionSupport(() -> stub,
                () -> false, () -> false, () -> true, ex);
    }

    private static void writeKeys(Path keyCache, String content) throws IOException {
        Files.createDirectories(keyCache.getParent());
        Files.writeString(keyCache, content);
    }

    private static WeChatLogService service(Path workRoot, Path keyCache,
                                            MatcherStub matcher, StubExtractor stub) throws IOException {
        return new WeChatLogService(workRoot, keyCache, 60, null, null,
                matcher, support(stub));
    }

    @Test
    @DisplayName("缓存命中 ⇒ 不取密钥（因此不会提权）")
    void cacheHitDoesNotExtract() throws IOException {
        Path workRoot = Files.createTempDirectory("nexusai-svc-hit-");
        Path keyCache = workRoot.resolve(".keys");
        writeKeys(keyCache, "bb".repeat(32) + "\n");

        StubExtractor stub = new StubExtractor();
        MatcherStub matcher = new MatcherStub(Set.of("bb"));
        var matches = service(workRoot, keyCache, matcher, stub)
                .resolveMatches(List.of(Path.of("dummy.db")), Path.of("acct"), true);

        assertFalse(matches.isEmpty());
        assertEquals(0, stub.calls, "缓存命中不应再取密钥");
    }

    @Test
    @DisplayName("缓存失配 ⇒ 取一次，校验通过后落盘")
    void cacheInvalidTriggersExtractionAndSaves() throws IOException {
        Path workRoot = Files.createTempDirectory("nexusai-svc-invalid-");
        Path keyCache = workRoot.resolve(".keys");
        writeKeys(keyCache, "aa".repeat(32) + "\n");

        StubExtractor stub = new StubExtractor();
        stub.result = key((byte) 0xbb);
        MatcherStub matcher = new MatcherStub(Set.of("bb"));
        var matches = service(workRoot, keyCache, matcher, stub)
                .resolveMatches(List.of(Path.of("dummy.db")), Path.of("acct"), true);

        assertFalse(matches.isEmpty());
        assertEquals(1, stub.calls);
        assertTrue(Files.readString(keyCache).contains("bb".repeat(32)),
                "校验通过的候选必须落盘");
    }

    @Test
    @DisplayName("校验不过的候选绝不落盘（先校验后落盘）")
    void invalidFreshCandidatesAreNotCached() throws IOException {
        Path workRoot = Files.createTempDirectory("nexusai-svc-nomatch-");
        Path keyCache = workRoot.resolve(".keys");

        StubExtractor stub = new StubExtractor();
        stub.result = key((byte) 0xcc);
        MatcherStub matcher = new MatcherStub(Set.of());

        assertThrows(com.nexusai.wechatlog.key.WeChatKeyExtractor.KeyExtractionException.class,
                () -> service(workRoot, keyCache, matcher, stub)
                        .resolveMatches(List.of(Path.of("dummy.db")), Path.of("acct"), true));
        assertEquals(1, stub.calls);
        assertFalse(Files.exists(keyCache), "校验失败不应写出缓存文件");
    }

    @Test
    @DisplayName("坏缓存（乱码/半写）⇒ 忽略并重新取，原子写不留 .tmp")
    void garbageCacheIsReExtracted() throws IOException {
        Path workRoot = Files.createTempDirectory("nexusai-svc-garbage-");
        Path keyCache = workRoot.resolve(".keys");
        writeKeys(keyCache, "not-a-key\nzzzz\n");

        StubExtractor stub = new StubExtractor();
        stub.result = key((byte) 0xbb);
        MatcherStub matcher = new MatcherStub(Set.of("bb"));
        var matches = service(workRoot, keyCache, matcher, stub)
                .resolveMatches(List.of(Path.of("dummy.db")), Path.of("acct"), true);

        assertFalse(matches.isEmpty());
        assertEquals(1, stub.calls);
        assertTrue(Files.readString(keyCache).contains("bb".repeat(32)));
        assertFalse(Files.exists(keyCache.resolveSibling(".keys.tmp")),
                "原子写完成后不应残留 .tmp");
    }
}
