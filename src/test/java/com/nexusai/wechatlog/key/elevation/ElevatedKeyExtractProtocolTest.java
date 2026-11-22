package com.nexusai.wechatlog.key.elevation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 父子协议：哨兵参数、候选读写、失败原因旁车文件。 */
class ElevatedKeyExtractProtocolTest {

    @Test
    @DisplayName("哨兵识别与 payload 去头")
    void sentinelAndPayload() {
        String[] args = {ElevatedKeyExtractProtocol.SENTINEL, "acct", "out.txt"};
        assertTrue(ElevatedKeyExtractProtocol.isInvocation(args));
        assertArrayEquals(new String[]{"acct", "out.txt"},
                ElevatedKeyExtractProtocol.payload(args));
        assertFalse(ElevatedKeyExtractProtocol.isInvocation(new String[]{"--server.port=1"}));
        assertFalse(ElevatedKeyExtractProtocol.isInvocation(new String[0]));
    }

    @Test
    @DisplayName("候选写入/读出往返，非法行被忽略")
    void candidatesRoundTrip() throws IOException {
        Path out = Files.createTempFile("wxkey-", ".txt");
        byte[] k1 = new byte[32];
        byte[] k2 = new byte[32];
        java.util.Arrays.fill(k1, (byte) 0x11);
        java.util.Arrays.fill(k2, (byte) 0x22);

        ElevatedKeyExtractProtocol.writeCandidates(out, List.of(k1, k2));
        List<byte[]> read = ElevatedKeyExtractProtocol.readCandidates(out);

        assertEquals(2, read.size());
        assertArrayEquals(k1, read.get(0));

        Files.writeString(out, "garbage\nabc\n" + "11".repeat(32) + "\n");
        assertEquals(1, ElevatedKeyExtractProtocol.readCandidates(out).size());
        Files.deleteIfExists(out);
    }

    @Test
    @DisplayName("失败原因写旁车文件，可被父进程读出")
    void reasonRoundTrip() throws IOException {
        Path out = Files.createTempFile("wxkey-", ".txt");
        ElevatedKeyExtractProtocol.writeReason(out, "版本 4.2.0.53 未适配");
        assertEquals("版本 4.2.0.53 未适配",
                ElevatedKeyExtractProtocol.readReason(out));
        Files.deleteIfExists(ElevatedKeyExtractProtocol.reasonFileFor(out));
        Files.deleteIfExists(out);
    }
}
