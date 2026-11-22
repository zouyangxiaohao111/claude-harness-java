package com.nexusai.wechatlog.key.elevation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 提权执行编排：退出码映射、取消不算成功、临时文件读后即删。 */
class WindowsElevatedKeyExtractorTest {

    static final class FakeLauncher implements ElevationLauncher {
        LaunchResult result = LaunchResult.completed(0);
        String candidates;
        String reason;
        int calls;
        List<String> lastCommand;

        @Override
        public LaunchResult launch(List<String> command, Duration timeout) {
            calls++;
            lastCommand = command;
            Path out = Path.of(command.get(command.size() - 1));
            try {
                if (candidates != null) {
                    Files.writeString(out, candidates);
                }
                if (reason != null) {
                    ElevatedKeyExtractProtocol.writeReason(out, reason);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return result;
        }
    }

    private static WindowsElevatedKeyExtractor extractor(Path tempRoot, FakeLauncher launcher) {
        ElevatedCommandBuilder builder = (acct, out) -> List.of(
                "java.exe", "x.jar", ElevatedKeyExtractProtocol.SENTINEL,
                acct.toString(), out.toString());
        return new WindowsElevatedKeyExtractor(builder, launcher, tempRoot,
                (p, d) -> { }, Duration.ofSeconds(5));
    }

    private static String twoKeys() {
        return "11".repeat(32) + "\n" + "22".repeat(32) + "\n";
    }

    @Test
    @DisplayName("退出码 0 + 写了两个候选 ⇒ SUCCESS，且临时目录被清空")
    void successReadsCandidatesAndCleans() throws IOException {
        Path tempRoot = Files.createTempDirectory("nexusai-elev-ok-");
        FakeLauncher launcher = new FakeLauncher();
        launcher.candidates = twoKeys();

        ElevationOutcome out = extractor(tempRoot, launcher).extract(Path.of("acct"));

        assertEquals(ElevationOutcome.Status.SUCCESS, out.status());
        assertEquals(2, out.candidates().size());
        try (var s = Files.list(tempRoot)) {
            assertEquals(0, s.count(), "临时目录必须被删除");
        }
    }

    @Test
    @DisplayName("退出码 0 但没写文件 ⇒ EXTRACT_FAILED，不能被当成成功")
    void exit0WithoutFileIsFailure() throws IOException {
        Path tempRoot = Files.createTempDirectory("nexusai-elev-nofile-");
        ElevationOutcome out = extractor(tempRoot, new FakeLauncher()).extract(Path.of("acct"));
        assertEquals(ElevationOutcome.Status.EXTRACT_FAILED, out.status());
    }

    @Test
    @DisplayName("用户取消 UAC ⇒ CANCELLED（绝不能映射成 SUCCESS）")
    void cancelIsNotSuccess() throws IOException {
        Path tempRoot = Files.createTempDirectory("nexusai-elev-cancel-");
        FakeLauncher launcher = new FakeLauncher();
        launcher.result = ElevationLauncher.LaunchResult.cancelled();

        ElevationOutcome out = extractor(tempRoot, launcher).extract(Path.of("acct"));

        assertEquals(ElevationOutcome.Status.CANCELLED, out.status());
        assertTrue(out.candidates().isEmpty());
    }

    @Test
    @DisplayName("提权不可用 ⇒ UNAVAILABLE 且带原因")
    void unavailableCarriesDetail() throws IOException {
        Path tempRoot = Files.createTempDirectory("nexusai-elev-unavail-");
        FakeLauncher launcher = new FakeLauncher();
        launcher.result = ElevationLauncher.LaunchResult.unavailable("blocked by policy");

        ElevationOutcome out = extractor(tempRoot, launcher).extract(Path.of("acct"));

        assertEquals(ElevationOutcome.Status.UNAVAILABLE, out.status());
        assertEquals("blocked by policy", out.detail());
    }

    @Test
    @DisplayName("退出码映射：10/11/13/14/其它")
    void exitCodesMap() throws IOException {
        Path tempRoot = Files.createTempDirectory("nexusai-elev-codes-");
        int[] codes = {10, 11, 13, 14, 99};
        ElevationOutcome.Status[] expected = {
                ElevationOutcome.Status.NO_PROCESS,
                ElevationOutcome.Status.NO_CANDIDATES,
                ElevationOutcome.Status.SCAN_BUDGET_EXHAUSTED,
                ElevationOutcome.Status.VERSION_UNSUPPORTED,
                ElevationOutcome.Status.EXTRACT_FAILED};
        for (int i = 0; i < codes.length; i++) {
            FakeLauncher launcher = new FakeLauncher();
            launcher.result = ElevationLauncher.LaunchResult.completed(codes[i]);
            launcher.reason = "reason-" + codes[i];
            ElevationOutcome out = extractor(tempRoot, launcher).extract(Path.of("acct"));
            assertEquals(expected[i], out.status(), "exit=" + codes[i]);
        }
        // 失败时子进程会写 .reason 旁车文件；临时目录仍必须被彻底清空
        try (var s = Files.list(tempRoot)) {
            assertEquals(0, s.count(), "失败分支也不能留下临时目录/旁车文件");
        }
    }
}
