package com.nexusai.wechatlog.key;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程内存扫描（与 JNA 解耦，便于用假区域表 + 假读取器做单测）。
 *
 * <h2>为什么要拆出来</h2>
 * 旧实现把「枚举区域 + 升序扫描 + 256MB 预算」写死在一个方法里，预算耗尽时静默
 * {@code break}。本机实测：特征串位于 {@code Weixin.dll}（镜像区），而该模块基址以下
 * 已有 536.9MB 可读内存 ⇒ 256MB 预算永远到不了它。
 *
 * <h2>本实现的扫描策略</h2>
 * <ol>
 *   <li><b>镜像区优先</b>：{@code MEM_IMAGE} 区域先扫（特征串通常就在 DLL 里），
 *       其余区域再按剩余预算扫；</li>
 *   <li><b>分块读取</b>，块间保留重叠，避免结构体被切断；</li>
 *   <li>预算耗尽时<b>可观测</b>：返回 {@code budgetExhausted} 与 {@code skippedBytes}，
 *       由调用方记录「已扫 X MB / 跳过 Y MB」；</li>
 *   <li>两遍扫描（找特征串、找地址+长度模式）各自独立预算，避免第一遍把预算吃光。</li>
 * </ol>
 */
public final class MemoryScanner {

    /** 单次读取内存的抽象。失败返回 null 或空数组。 */
    public interface Reader {
        byte[] read(long address, int size);
    }

    /** 进度日志（单测可捕获）。 */
    @FunctionalInterface
    public interface Progress {
        void log(String message);
    }

    /** 一个可读内存区域。{@code image=true} 表示 {@code MEM_IMAGE}。 */
    public record Region(long base, long size, boolean image) {
        public long end() {
            return base + size;
        }
    }

    /**
     * 扫描结果。
     *
     * @param candidates      解析出的 32 字节密钥候选
     * @param needleCount     特征串出现次数
     * @param scannedBytes    实际读取字节数（两遍合计）
     * @param skippedBytes    因预算耗尽未覆盖的字节数（第一遍）
     * @param budgetExhausted 是否触达预算上限
     */
    public record ScanOutcome(List<byte[]> candidates, long needleCount,
                              long scannedBytes, long skippedBytes, boolean budgetExhausted) {
    }

    /** 默认总预算：2GB（本机可读总量约 1.07GB）。可用系统属性覆盖。 */
    public static final long DEFAULT_MAX_TOTAL_SCAN_BYTES =
            Long.getLong("nexusai.wechat.scan.maxBytes", 2048L * 1024 * 1024);

    public static final long MAX_USER_ADDRESS = 0x0000_8000_0000_0000L;

    private static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final int CHUNK_OVERLAP = 0x80;
    private static final int CONFIG_BLOB_MAX = 1024;
    private static final int MAX_NEEDLES = 4096;

    private static final Pattern CONFIG_LITERAL_RE =
            Pattern.compile("[xX]'([0-9a-fA-F]{64,192})'");

    private MemoryScanner() {
    }

    /**
     * 扫描一遍内存，返回密钥候选与诊断信息。
     *
     * @param regions 可读区域（顺序不敏感，内部会镜像优先）
     * @param reader  内存读取器
     * @param needle  特征串
     * @param chain   结构体偏移链
     * @param maxBytes 单遍预算；{@code <=0} 用 {@link #DEFAULT_MAX_TOTAL_SCAN_BYTES}
     * @param progress 进度日志（可为 null）
     */
    public static ScanOutcome scan(List<Region> regions, Reader reader, byte[] needle,
                                   WeChatOffsetChain chain, long maxBytes, Progress progress) {
        if (regions == null || regions.isEmpty() || reader == null
                || needle == null || needle.length == 0 || chain == null) {
            return new ScanOutcome(List.of(), 0, 0, 0, false);
        }
        long budget = maxBytes <= 0 ? DEFAULT_MAX_TOTAL_SCAN_BYTES : maxBytes;
        List<Region> ordered = imagesFirst(regions);
        long totalReadable = ordered.stream().mapToLong(Region::size).sum();

        NeedlePass first = findNeedles(ordered, reader, needle, budget, progress);
        long skipped = Math.max(0, totalReadable - first.covered());
        if (first.exhausted() && progress != null) {
            progress.log(budgetExhaustedMessage(first.scanned(), skipped, budget));
        }
        if (first.addresses().isEmpty()) {
            return new ScanOutcome(List.of(), 0, first.scanned(), skipped, first.exhausted());
        }

        List<byte[]> patterns = new ArrayList<>(first.addresses().size());
        for (long addr : first.addresses()) {
            ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            bb.putLong(addr);
            bb.putLong(needle.length);
            patterns.add(bb.array());
        }

        PatternPass second = findCandidates(ordered, reader, patterns, first.addresses(),
                chain, needle.length, budget, progress);
        return new ScanOutcome(second.candidates(), first.addresses().size(),
                first.scanned() + second.scanned(), skipped,
                first.exhausted() || second.exhausted());
    }

    /** 镜像区优先，其余在后；各自按基址升序。 */
    static List<Region> imagesFirst(List<Region> regions) {
        List<Region> images = new ArrayList<>();
        List<Region> others = new ArrayList<>();
        for (Region r : regions) {
            (r.image() ? images : others).add(r);
        }
        images.sort(java.util.Comparator.comparingLong(Region::base));
        others.sort(java.util.Comparator.comparingLong(Region::base));
        images.addAll(others);
        return images;
    }

    private record NeedlePass(Set<Long> addresses, long scanned, long covered, boolean exhausted) {
    }

    private static NeedlePass findNeedles(List<Region> ordered, Reader reader, byte[] needle,
                                          long budget, Progress progress) {
        Set<Long> addresses = new LinkedHashSet<>();
        long scanned = 0;
        long coveredTotal = 0;
        boolean exhausted = false;
        long nextLogAt = 64L * 1024 * 1024;

        outer:
        for (Region r : ordered) {
            long offset = 0;
            while (offset < r.size()) {
                if (scanned >= budget) {
                    exhausted = true;
                    break outer;
                }
                int chunk = (int) Math.min(MAX_CHUNK_BYTES, r.size() - offset);
                byte[] data = reader.read(r.base() + offset, chunk);
                if (data == null || data.length == 0) {
                    offset += Math.max(chunk, 1);
                    coveredTotal += 0;
                    continue;
                }
                scanned += data.length;
                coveredTotal += data.length;
                int pos = indexOf(data, needle, 0);
                while (pos >= 0 && addresses.size() < MAX_NEEDLES) {
                    addresses.add(r.base() + offset + pos);
                    pos = indexOf(data, needle, pos + 1);
                }
                offset += Math.max(chunk - CHUNK_OVERLAP, 1);
                if (scanned >= nextLogAt && progress != null) {
                    progress.log(String.format(Locale.ROOT,
                            "内存扫描（特征串）：已读 %.1f MB / 预算 %.1f MB",
                            scanned / 1024.0 / 1024.0, budget / 1024.0 / 1024.0));
                    nextLogAt += 64L * 1024 * 1024;
                }
                if (chunk < MAX_CHUNK_BYTES) {
                    break;
                }
            }
        }
        return new NeedlePass(addresses, scanned, coveredTotal, exhausted);
    }

    /** 预算耗尽的可观测文案（单测直接断言这句包含关键信息）。 */
    public static String budgetExhaustedMessage(long scanned, long skipped, long budget) {
        return String.format(Locale.ROOT,
                "扫描预算耗尽：已扫 %.1f MB / 跳过 %.1f MB / 上限 %.1f MB"
                        + "（可能漏掉高地址模块，可用 -Dnexusai.wechat.scan.maxBytes 提高）",
                scanned / 1024.0 / 1024.0, skipped / 1024.0 / 1024.0, budget / 1024.0 / 1024.0);
    }

    private record PatternPass(List<byte[]> candidates, long scanned, boolean exhausted) {
    }

    private static PatternPass findCandidates(List<Region> ordered, Reader reader,
                                              List<byte[]> patterns, Set<Long> needles,
                                              WeChatOffsetChain chain, int nameLength,
                                              long budget, Progress progress) {
        Set<String> sink = new LinkedHashSet<>();
        long scanned = 0;
        boolean exhausted = false;
        long nextLogAt = 64L * 1024 * 1024;

        outer:
        for (Region r : ordered) {
            long offset = 0;
            while (offset < r.size()) {
                if (scanned >= budget) {
                    exhausted = true;
                    break outer;
                }
                int chunk = (int) Math.min(MAX_CHUNK_BYTES, r.size() - offset);
                byte[] data = reader.read(r.base() + offset, chunk);
                if (data == null || data.length == 0) {
                    offset += Math.max(chunk, 1);
                    continue;
                }
                scanned += data.length;
                for (byte[] pattern : patterns) {
                    int pos = indexOf(data, pattern, 0);
                    while (pos >= 0) {
                        collectFromStringNode(reader, r.base() + offset + pos,
                                needles, chain, nameLength, sink);
                        pos = indexOf(data, pattern, pos + 1);
                    }
                }
                offset += Math.max(chunk - CHUNK_OVERLAP, 1);
                if (scanned >= nextLogAt && progress != null) {
                    progress.log(String.format(Locale.ROOT,
                            "内存扫描（结构体链）：已读 %.1f MB / 预算 %.1f MB",
                            scanned / 1024.0 / 1024.0, budget / 1024.0 / 1024.0));
                    nextLogAt += 64L * 1024 * 1024;
                }
                if (chunk < MAX_CHUNK_BYTES) {
                    break;
                }
            }
        }

        List<byte[]> out = new ArrayList<>();
        for (String h : sink) {
            if (h.length() >= 64) {
                out.add(hexToBytes(h.substring(0, 64)));
            }
        }
        return new PatternPass(out, scanned, exhausted);
    }

    private static void collectFromStringNode(Reader reader, long qaddr, Set<Long> needleSet,
                                              WeChatOffsetChain chain, int nameLength,
                                              Set<String> sink) {
        long nodeBase = qaddr - chain.nodeBack();
        if (nodeBase <= 0) {
            return;
        }
        byte[] node = reader.read(nodeBase, 0x50);
        if (node == null || node.length < 0x40) {
            return;
        }
        long nNeedle = u64(node, chain.nodeNeedleOffset());
        long nLen = u64(node, chain.nodeLengthOffset());
        if (!needleSet.contains(nNeedle) || nLen != nameLength) {
            return;
        }
        long configPtr = u64(node, chain.nodeConfigOffset());
        if (!(configPtr >= 0x10000 && configPtr < MAX_USER_ADDRESS)) {
            return;
        }
        byte[] obj = reader.read(configPtr + chain.configBlobOffset(), 0x28);
        if (obj == null || obj.length < 0x18) {
            return;
        }
        long dataPtr = u64(obj, chain.dataPtrOffset());
        long dataLen = u64(obj, chain.dataLenOffset());
        if (!(dataLen > 0 && dataLen <= CONFIG_BLOB_MAX
                && dataPtr >= 0x10000 && dataPtr < MAX_USER_ADDRESS)) {
            return;
        }
        byte[] blob = reader.read(dataPtr, (int) dataLen);
        if (blob == null || blob.length != dataLen) {
            return;
        }
        byte[] decoded = xorRepeat(blob, chain.xorMask());
        String text = new String(decoded, StandardCharsets.US_ASCII);
        Matcher m = CONFIG_LITERAL_RE.matcher(text);
        while (m.find()) {
            String run = m.group(1).toLowerCase(Locale.ROOT);
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            if (run.length() > 96) {
                for (int s = 0; s <= run.length() - 64; s += 32) {
                    starts.add(s);
                }
                starts.add(run.length() - 64);
            }
            for (int start : new LinkedHashSet<>(starts)) {
                if (start < 0 || start + 64 > run.length()) {
                    continue;
                }
                String encKeyHex = run.substring(start, start + 64);
                if (isProbable32ByteKey(encKeyHex)) {
                    sink.add(encKeyHex);
                }
            }
        }
    }

    /** 子串查找（字节级）。 */
    static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        byte first = needle[0];
        outer:
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            if (haystack[i] != first) {
                continue;
            }
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 从字节数组的小端位置读 64 位无符号数。 */
    static long u64(byte[] buf, int offset) {
        if (buf == null || offset < 0 || offset + 8 > buf.length) {
            return -1;
        }
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (buf[offset + i] & 0xFFL);
        }
        return v;
    }

    /** 用固定掩码做循环异或。 */
    static byte[] xorRepeat(byte[] data, byte[] mask) {
        if (mask == null || mask.length == 0) {
            return data.clone();
        }
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ mask[i % mask.length]);
        }
        return out;
    }

    /** 粗筛：64 个十六进制字符解出的 32 字节不应是全零/全同值。 */
    static boolean isProbable32ByteKey(String hex) {
        if (hex == null || hex.length() != 64) {
            return false;
        }
        try {
            byte[] b = hexToBytes(hex);
            if (b.length != 32) {
                return false;
            }
            boolean allSame = true;
            for (int i = 1; i < b.length; i++) {
                if (b[i] != b[0]) {
                    allSame = false;
                    break;
                }
            }
            return !allSame;
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
