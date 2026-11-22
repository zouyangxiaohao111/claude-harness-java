package com.nexusai.wechatlog.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 守卫：抓到的密钥候选必须<b>合并</b>写入，不能覆盖。
 *
 * <h2>真实事故（2026-11-08）</h2>
 * 某次只抓到 1 把（当时微信只访问了 contact.db），而 {@code persistCapturedKeys}
 * 用的是覆盖写 → <b>把之前辛苦抓到的 14 把全冲掉了</b>，导致 10 个库立刻变成「缺密钥」，
 * 用户之前解出来的明文仍在，但无法继续增量更新。
 *
 * <p>密钥的获取成本极高（要提权、微信会卡顿、历史上还多次被 SIGKILL），
 * 所以这条规则必须有测试守住 —— 否则下次改代码很容易又写回 {@code writeString}。
 *
 * <h2>测试策略</h2>
 * 直接调用生产代码的 {@code MacOsKeyExtractor.mergeKeys(...)}（包级可见的静态纯函数），
 * 而不是在测试里再实现一遍合并逻辑 —— 后者只会证明「测试自己是对的」。
 */
class KeyPersistenceMergeTest {

    private static byte[] key(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) {
            k[i] = (byte) (seed + i);
        }
        return k;
    }

    private static String hexOf(byte[] k) {
        StringBuilder sb = new StringBuilder();
        for (byte b : k) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("已有 14 把 + 新抓 1 把 → 合并成 15 把，历史一把不丢")
    void mergesInsteadOfOverwriting() {
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            existing.add(hexOf(key(i * 3)));
        }

        byte[] fresh = key(0x70);
        List<String> merged = MacOsKeyExtractor.mergeKeys(existing, List.of(fresh));

        assertEquals(15, merged.size(), "14 历史 + 1 新 = 15，不能是 1（覆盖写就是事故）");
        assertEquals(existing.get(0), merged.get(0), "第一把历史密钥必须还在且在最前");
        assertTrue(merged.contains(hexOf(fresh)), "新密钥必须在结果里");
    }

    @Test
    @DisplayName("重复抓到同一把密钥不产生重复行（幂等）")
    void deduplicates() {
        byte[] k = key(1);
        List<String> first = MacOsKeyExtractor.mergeKeys(List.of(), List.of(k));
        assertEquals(1, first.size());

        // 第二次再抓同一把
        List<String> second = MacOsKeyExtractor.mergeKeys(first, List.of(k));
        assertEquals(1, second.size(), "同一把密钥不该重复写入");
    }

    @Test
    @DisplayName("历史文件里的垃圾行被丢弃，合法行保留")
    void dropsGarbageLines() {
        List<String> messy = List.of(
                "# 注释行",
                "",
                "abc",                       // 太短
                "zz".repeat(32),             // 非十六进制
                hexOf(key(9))                // 合法
        );
        List<String> merged = MacOsKeyExtractor.mergeKeys(messy, List.of());
        assertEquals(1, merged.size(), "只应保留 1 把合法密钥");
        assertEquals(hexOf(key(9)), merged.get(0));
    }

    @Test
    @DisplayName("大小写归一：大写十六进制与已有的小写视为同一把")
    void normalisesCase() {
        byte[] k = key(5);
        String lower = hexOf(k);
        String upper = lower.toUpperCase();

        List<String> merged = MacOsKeyExtractor.mergeKeys(List.of(upper), List.of(k));
        assertEquals(1, merged.size(), "大写已存在时不应再加一份小写");
        assertEquals(lower, merged.get(0), "应归一为小写");
    }

    @Test
    @DisplayName("长度不是 32 字节的候选被忽略")
    void ignoresWrongLengthKeys() {
        List<byte[]> bad = List.of(new byte[16], new byte[0], new byte[31]);
        assertTrue(MacOsKeyExtractor.mergeKeys(List.of(), bad).isEmpty());
    }

    @Test
    @DisplayName("countValidKeys 与 mergeKeys 对合法行数口径一致")
    void countMatchesMerge() {
        List<String> lines = List.of(
                hexOf(key(1)), "bad", hexOf(key(2)), "");
        assertEquals(2, MacOsKeyExtractor.countValidKeys(lines));
        assertEquals(2, MacOsKeyExtractor.mergeKeys(lines, List.of()).size());
    }

    @Test
    @DisplayName("密钥文件权限必须是 600（仅本人可读）")
    void keyFileIsOwnerOnly(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".keys");
        Files.writeString(f, hexOf(key(0)) + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));

        var perms = Files.getPosixFilePermissions(f);
        assertEquals("rw-------", PosixFilePermissions.toString(perms),
                "密钥文件必须是 600，否则同机其他用户可读");
    }
}
