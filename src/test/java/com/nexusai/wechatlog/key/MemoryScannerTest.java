package com.nexusai.wechatlog.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 扫描循环的单测：用假的区域表 + 假的读取器，验证「高地址镜像区的特征串能被扫到」
 * 「镜像区优先」「预算耗尽可观测」。
 *
 * <p>这些测试直接源于本机事故：特征串在 {@code Weixin.dll}（镜像区、+146MB），
 * 而旧实现按地址升序 + 256MB 预算，在到达该模块前就静默 break。
 */
class MemoryScannerTest {

    private static final byte[] NEEDLE =
            "com.Tencent.WCDB.Config.Cipher".getBytes(StandardCharsets.US_ASCII);
    private static final long MB = 1024L * 1024;

    /** 在指定绝对地址放置若干字节的假内存读取器。 */
    private static final class FakeReader implements MemoryScanner.Reader {
        private final Map<Long, byte[]> placements = new HashMap<>();

        FakeReader put(long address, byte[] bytes) {
            placements.put(address, bytes.clone());
            return this;
        }

        @Override
        public byte[] read(long address, int size) {
            byte[] buf = new byte[size];
            for (Map.Entry<Long, byte[]> e : placements.entrySet()) {
                long p = e.getKey();
                if (p >= address && p + e.getValue().length <= address + size) {
                    System.arraycopy(e.getValue(), 0, buf, (int) (p - address),
                            e.getValue().length);
                }
            }
            return buf;
        }
    }

    private static WeChatOffsetChain chain() {
        return WeChatOffsetTable.builtin411();
    }

    @Test
    @DisplayName("默认预算足以覆盖到高地址镜像区里的特征串（预算改回 256MB 必须红）")
    void defaultBudgetReachesHighAddressImageRegion() {
        long firstBase = 0x10_0000;
        long secondBase = firstBase + 300 * MB + 0x10_0000;
        long needleAddr = secondBase + 10 * MB;

        List<MemoryScanner.Region> regions = List.of(
                new MemoryScanner.Region(firstBase, 300 * MB, true),
                new MemoryScanner.Region(secondBase, 50 * MB, true));
        FakeReader reader = new FakeReader().put(needleAddr, NEEDLE);

        MemoryScanner.ScanOutcome out = MemoryScanner.scan(
                regions, reader, NEEDLE, chain(),
                MemoryScanner.DEFAULT_MAX_TOTAL_SCAN_BYTES, null);

        assertTrue(out.needleCount() > 0,
                "默认预算必须能到达高地址镜像区；当前预算="
                        + MemoryScanner.DEFAULT_MAX_TOTAL_SCAN_BYTES / MB + "MB");
        assertFalse(out.budgetExhausted(), "默认预算下不应耗尽");
    }

    @Test
    @DisplayName("镜像区优先：非镜像大区在前也不会挡住目标镜像区（去掉优先必须红）")
    void imageRegionsAreScannedBeforeNonImage() {
        long nonImageBase = 0x10_0000;
        long imageBase = 0x2000_0000L;
        long needleAddr = imageBase + 1 * MB;

        List<MemoryScanner.Region> regions = List.of(
                new MemoryScanner.Region(nonImageBase, 500 * MB, false),
                new MemoryScanner.Region(imageBase, 50 * MB, true));
        FakeReader reader = new FakeReader().put(needleAddr, NEEDLE);

        MemoryScanner.ScanOutcome out = MemoryScanner.scan(
                regions, reader, NEEDLE, chain(), 100 * MB, null);

        assertTrue(out.needleCount() > 0, "镜像区应先被扫描，预算 100MB 足够覆盖 50MB 镜像区");
        assertTrue(out.budgetExhausted(), "非镜像大区未覆盖完，应标记预算耗尽");
    }

    @Test
    @DisplayName("预算耗尽有可观测日志，并给出跳过的字节数")
    void budgetExhaustionIsObservable() {
        long base = 0x10_0000;
        List<MemoryScanner.Region> regions = List.of(
                new MemoryScanner.Region(base, 100 * MB, false));
        List<String> logs = new ArrayList<>();

        MemoryScanner.ScanOutcome out = MemoryScanner.scan(
                regions, new FakeReader(), NEEDLE, chain(), 20 * MB, logs::add);

        assertTrue(out.budgetExhausted());
        assertTrue(out.skippedBytes() > 70 * MB,
                "应报告跳过约 80MB，实际=" + out.skippedBytes() / MB + "MB");
        assertTrue(logs.stream().anyMatch(s -> s.contains("预算耗尽")),
                "必须有预算耗尽日志，实际日志=" + logs);
        assertTrue(logs.stream().anyMatch(s -> s.contains("跳过")),
                "日志必须写清跳过多少，实际日志=" + logs);
    }
}
