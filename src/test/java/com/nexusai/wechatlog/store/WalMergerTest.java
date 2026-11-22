package com.nexusai.wechatlog.store;

import com.nexusai.wechatlog.crypto.CipherSuite;
import com.nexusai.wechatlog.crypto.SqlCipher4;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WalMerger} 的测试。
 *
 * <h2>为什么这一层必须单独测</h2>
 * WAL 合并是「定时同步能不能拿到最新消息」的唯一保证，而两个上游实现（chatlog、
 * wechat-mac-export）都<b>没做</b>这一步。没有测试就等于没有依据。
 *
 * <h2>本测试刻意覆盖的三种状态</h2>
 * <ol>
 *   <li><b>正常</b>：有已 commit 的帧 → 应覆盖主库对应页；</li>
 *   <li><b>陈旧 WAL</b>（本机真实遇到的状态）：帧 salt 与 WAL 头不一致
 *       → 应返回 0 帧，因为数据早已 checkpoint 进主库；</li>
 *   <li><b>未 commit 的尾部帧</b>：应按 SQLite 语义丢弃，不能应用。</li>
 * </ol>
 */
class WalMergerTest {

    private static final byte[] ENC_KEY = hex(
            "9188949b2270d59e24db68a0c76336aee48975fca996dee2f4098d8bfd2d80c4");
    private static final byte[] MAC_KEY = hex(
            "ab8b8dc01d6f5949555ee3b0f0641b41b7b1ed8b127d5cb23498c8de2aa0cf48");
    private static final byte[] SALT = hex("aabbccddeeff00112233445566778899");
    private static final int WAL_SALT1 = 0x12345678;
    private static final int WAL_SALT2 = 0x9abcdef0;

    @Test
    @DisplayName("已 commit 的帧覆盖主库对应页")
    void appliesCommittedFrames() {
        // 主库 2 页，第 2 页将被 WAL 覆盖
        List<byte[]> mainPages = new ArrayList<>();
        mainPages.add(page0());
        mainPages.add(fillerPage((byte) 0xEE));

        byte[] newPage1 = fillerPage((byte) 0x77);
        byte[] wal = buildWal(new Frame(2, newPage1, 2, WAL_SALT1, WAL_SALT2));

        WalMerger.Merged merged = WalMerger.merge(
                mainPages, wal, ENC_KEY, MAC_KEY, CipherSuite.V4_SHA512);

        assertEquals(1, merged.result().appliedFrames(), "应应用 1 帧");
        assertEquals(2, merged.pages().size());
        // 第 2 页应变成 0x77 填充（解密后的内容）
        assertEquals((byte) 0x77, merged.pages().get(1)[0], "第 2 页应被 WAL 覆盖");
        // 第 1 页不受影响
        assertEquals((byte) 0xAB, merged.pages().get(0)[16], "第 1 页不应被改动");
    }

    @Test
    @DisplayName("陈旧 WAL（帧 salt 与头不一致）不应用任何帧")
    void skipsStaleWal() {
        List<byte[]> mainPages = new ArrayList<>();
        mainPages.add(page0());

        // 帧用的是「上一代」salt，与 WAL 头不一致 —— 本机真实状态就是这样
        byte[] wal = buildWal(new Frame(1, fillerPage((byte) 0x11), 1,
                WAL_SALT1 + 1, WAL_SALT2 + 1));

        WalMerger.Merged merged = WalMerger.merge(
                mainPages, wal, ENC_KEY, MAC_KEY, CipherSuite.V4_SHA512);

        assertEquals(0, merged.result().appliedFrames(),
                "陈旧 WAL 不应应用任何帧（数据已在主库里）");
    }

    @Test
    @DisplayName("未 commit 的尾部帧被丢弃")
    void dropsUncommittedTail() {
        List<byte[]> mainPages = new ArrayList<>();
        mainPages.add(page0());
        mainPages.add(fillerPage((byte) 0xEE));

        // 一帧 commit（第 2 页），再跟一帧不 commit（第 2 页，内容不同）
        byte[] committed = fillerPage((byte) 0x55);
        byte[] uncommitted = fillerPage((byte) 0x99);
        byte[] wal = buildWal(
                new Frame(2, committed, 2, WAL_SALT1, WAL_SALT2),
                new Frame(2, uncommitted, 0, WAL_SALT1, WAL_SALT2));   // dbSize=0 ⇒ 未 commit

        WalMerger.Merged merged = WalMerger.merge(
                mainPages, wal, ENC_KEY, MAC_KEY, CipherSuite.V4_SHA512);

        assertEquals(1, merged.result().appliedFrames(), "只应应用已 commit 的那 1 帧");
        assertEquals((byte) 0x55, merged.pages().get(1)[0],
                "未 commit 的尾部帧不应覆盖已 commit 的内容");
    }

    @Test
    @DisplayName("WAL 不存在或过短时安全返回原页")
    void handlesMissingOrShortWal() {
        List<byte[]> mainPages = new ArrayList<>();
        mainPages.add(page0());

        assertSame(mainPages, WalMerger.merge(mainPages, null,
                ENC_KEY, MAC_KEY, CipherSuite.V4_SHA512).pages());
        assertSame(mainPages, WalMerger.merge(mainPages, new byte[10],
                ENC_KEY, MAC_KEY, CipherSuite.V4_SHA512).pages());
    }

    @Test
    @DisplayName("魔数不对的文件不被当 WAL 处理")
    void rejectsWrongMagic() {
        List<byte[]> mainPages = new ArrayList<>();
        mainPages.add(page0());

        byte[] fake = new byte[32 + 4096 + 24];
        // 魔数填 0 ⇒ 不是 WAL
        WalMerger.Merged merged = WalMerger.merge(
                mainPages, fake, ENC_KEY, MAC_KEY, CipherSuite.V4_SHA512);
        assertEquals(0, merged.result().appliedFrames());
        assertTrue(merged.result().note().contains("魔数"), "应说明原因: " + merged.result().note());
    }

    @Test
    @DisplayName("页大小不符的 WAL 被拒绝")
    void rejectsWrongPageSize() {
        List<byte[]> mainPages = new ArrayList<>();
        mainPages.add(page0());

        byte[] wal = new byte[32];
        ByteBuffer bb = ByteBuffer.wrap(wal).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(0, 0x377f0682);
        bb.putInt(8, 8192);   // 错页大小
        bb.putInt(16, WAL_SALT1);
        bb.putInt(20, WAL_SALT2);

        WalMerger.Merged merged = WalMerger.merge(
                mainPages, wal, ENC_KEY, MAC_KEY, CipherSuite.V4_SHA512);
        assertEquals(0, merged.result().appliedFrames());
        assertTrue(merged.result().note().contains("页大小"), merged.result().note());
    }

    // ==================================================================
    // 构造辅助
    // ==================================================================

    /**
     * 主库第 1 页（「已解密」形态，含 SQLite 头）。
     *
     * <p>marker 放在 offset 16（SQLite 头的下一个字节），因为 offset 0..15 是文件头，
     * 不能拿来做内容标记 —— 用 offset 0 会因为头字节是 'S' 而断言变得毫无意义，
     * 用 salt 覆盖又会破坏头。
     */
    private static byte[] page0() {
        byte[] plain = new byte[4096];
        byte[] hdr = "SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(hdr, 0, plain, 0, hdr.length);
        plain[16] = (byte) 0xAB;
        return plain;
    }

    /** 一个内容可预测的明文页（首字节为 marker）。 */
    private static byte[] fillerPage(byte marker) {
        byte[] p = new byte[4096];
        Arrays.fill(p, marker);
        return p;
    }

    private record Frame(int pgno, byte[] plainPage, int dbSizeAfterCommit,
                         int salt1, int salt2) {
    }

    /** 按 SQLite WAL 格式构造 WAL 字节（页内容是 SQLCipher 加密的）。 */
    private static byte[] buildWal(Frame... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 头 32 字节
        ByteBuffer head = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        head.putInt(0x377f0682);
        head.putInt(3007000);
        head.putInt(4096);
        head.putInt(1);                 // checkpoint seq
        head.putInt(WAL_SALT1);
        head.putInt(WAL_SALT2);
        head.putInt(0);                 // checksum-1（本实现不校验）
        head.putInt(0);                 // checksum-2
        out.writeBytes(head.array());

        for (Frame f : frames) {
            byte[] encPage = encryptPage(f.plainPage(), f.pgno() - 1);
            ByteBuffer fh = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
            fh.putInt(f.pgno());
            fh.putInt(f.dbSizeAfterCommit());
            fh.putInt(f.salt1());
            fh.putInt(f.salt2());
            fh.putInt(0);
            fh.putInt(0);
            out.writeBytes(fh.array());
            out.writeBytes(encPage);
        }
        return out.toByteArray();
    }

    /** 把明文页加密成 SQLCipher 页。 */
    private static byte[] encryptPage(byte[] plain, int pageIndex) {
        try {
            int reserve = CipherSuite.V4_SHA512.reserve();
            int reserveOffset = 4096 - reserve;
            byte[] iv = new byte[16];
            Arrays.fill(iv, (byte) 0x33);

            int start = (pageIndex == 0) ? 16 : 0;
            byte[] body = Arrays.copyOfRange(plain, start, reserveOffset);

            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ENC_KEY, "AES"),
                    new IvParameterSpec(iv));
            byte[] ct = c.doFinal(body);

            byte[] page = new byte[4096];
            if (pageIndex == 0) {
                System.arraycopy(SALT, 0, page, 0, 16);
            }
            System.arraycopy(ct, 0, page, start, ct.length);
            System.arraycopy(iv, 0, page, reserveOffset, 16);

            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(MAC_KEY, "HmacSHA512"));
            mac.update(page, start, reserveOffset + 16 - start);
            mac.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(pageIndex + 1).array());
            byte[] h = mac.doFinal();
            System.arraycopy(h, 0, page, reserveOffset + 16, 64);
            return page;
        } catch (Exception e) {
            throw new IllegalStateException("构造测试密文失败", e);
        }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
