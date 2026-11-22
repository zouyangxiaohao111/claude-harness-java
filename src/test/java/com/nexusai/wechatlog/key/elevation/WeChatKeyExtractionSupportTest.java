package com.nexusai.wechatlog.key.elevation;

import com.nexusai.wechatlog.key.WeChatKeyExtractor;
import com.nexusai.wechatlog.key.WeChatKeyExtractor.KeyExtractionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 决策层：普通权限优先，只有「打开进程失败」才提权。 */
class WeChatKeyExtractionSupportTest {

    static final class StubExtractor implements WeChatKeyExtractor {
        int calls;
        List<byte[]> result = List.of();
        KeyExtractionException failure;

        @Override
        public List<byte[]> extract(Path accountDir) {
            calls++;
            if (failure != null) {
                throw failure;
            }
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

    static final class FakeLauncher implements ElevationLauncher {
        LaunchResult result = LaunchResult.cancelled();
        String candidates;
        int calls;

        @Override
        public LaunchResult launch(List<String> command, Duration timeout) {
            calls++;
            if (candidates != null) {
                try {
                    Files.writeString(Path.of(command.get(command.size() - 1)), candidates);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return result;
        }
    }

    private static KeyExtractionException permission() {
        return new KeyExtractionException("打开微信进程失败", "以管理员身份重新运行",
                KeyExtractionException.Kind.PERMISSION);
    }

    private static WindowsElevatedKeyExtractor elevated(FakeLauncher launcher, Path tempRoot) {
        ElevatedCommandBuilder builder = (acct, out) -> List.of(
                "java.exe", "x.jar", ElevatedKeyExtractProtocol.SENTINEL,
                acct.toString(), out.toString());
        return new WindowsElevatedKeyExtractor(builder, launcher, tempRoot,
                (p, d) -> { }, Duration.ofSeconds(5));
    }

    private static WeChatKeyExtractionSupport support(StubExtractor stub, boolean windows,
                                                      boolean elevated, boolean running,
                                                      WindowsElevatedKeyExtractor ex) {
        return new WeChatKeyExtractionSupport(() -> stub,
                () -> windows, () -> elevated, () -> running, ex);
    }

    @Test
    @DisplayName("普通权限取成功 ⇒ 返回候选，绝不弹 UAC")
    void directSuccessDoesNotElevate() throws IOException {
        StubExtractor stub = new StubExtractor();
        stub.result = List.of(new byte[32]);
        FakeLauncher launcher = new FakeLauncher();
        Path tempRoot = Files.createTempDirectory("nexusai-support-ok-");

        List<byte[]> out = support(stub, true, false, true, elevated(launcher, tempRoot))
                .extract(Path.of("acct"), true);

        assertEquals(1, out.size());
        assertEquals(0, launcher.calls, "不需要提权时不应启动子进程");
    }

    @Test
    @DisplayName("权限失败 + 用户取消 UAC ⇒ 「已取消提权」，不是成功")
    void permissionFailureCancelled() throws IOException {
        StubExtractor stub = new StubExtractor();
        stub.failure = permission();
        FakeLauncher launcher = new FakeLauncher();
        launcher.result = ElevationLauncher.LaunchResult.cancelled();
        Path tempRoot = Files.createTempDirectory("nexusai-support-cancel-");

        KeyExtractionException e = assertThrows(KeyExtractionException.class,
                () -> support(stub, true, false, true, elevated(launcher, tempRoot))
                        .extract(Path.of("acct"), true));

        assertTrue(e.getMessage().contains("已取消提权"), e.getMessage());
        assertEquals(1, launcher.calls);
    }

    @Test
    @DisplayName("提权不可用 ⇒ 说清楚并保留原始权限错误")
    void permissionFailureUnavailable() throws IOException {
        StubExtractor stub = new StubExtractor();
        stub.failure = permission();
        FakeLauncher launcher = new FakeLauncher();
        launcher.result = ElevationLauncher.LaunchResult.unavailable("blocked");
        Path tempRoot = Files.createTempDirectory("nexusai-support-unavail-");

        KeyExtractionException e = assertThrows(KeyExtractionException.class,
                () -> support(stub, true, false, true, elevated(launcher, tempRoot))
                        .extract(Path.of("acct"), true));

        assertTrue(e.getMessage().contains("自动提权不可用"), e.getMessage());
        assertTrue(e.remedy().contains("打开微信进程失败"), e.remedy());
    }

    @Test
    @DisplayName("版本/预算/0 候选失败绝不触发提权")
    void nonPermissionFailuresDoNotElevate() throws IOException {
        var kinds = List.of(
                KeyExtractionException.Kind.VERSION_UNSUPPORTED,
                KeyExtractionException.Kind.SCAN_BUDGET_EXHAUSTED,
                KeyExtractionException.Kind.NO_CANDIDATES);
        for (var kind : kinds) {
            StubExtractor stub = new StubExtractor();
            stub.failure = new KeyExtractionException("x", "y", kind);
            FakeLauncher launcher = new FakeLauncher();
            Path tempRoot = Files.createTempDirectory("nexusai-support-noelev-");

            KeyExtractionException e = assertThrows(KeyExtractionException.class,
                    () -> support(stub, true, false, true, elevated(launcher, tempRoot))
                            .extract(Path.of("acct"), true));
            assertEquals(kind, e.kind());
            assertEquals(0, launcher.calls, kind + " 不应触发提权");
        }
    }

    @Test
    @DisplayName("不允许提权时给「可提权」提示，不启动子进程")
    void allowElevationFalseGivesHint() throws IOException {
        StubExtractor stub = new StubExtractor();
        stub.failure = permission();
        FakeLauncher launcher = new FakeLauncher();
        Path tempRoot = Files.createTempDirectory("nexusai-support-notallowed-");

        KeyExtractionException e = assertThrows(KeyExtractionException.class,
                () -> support(stub, true, false, true, elevated(launcher, tempRoot))
                        .extract(Path.of("acct"), false));

        assertTrue(e.getMessage().contains("管理员权限"), e.getMessage());
        assertEquals(0, launcher.calls);
    }

    @Test
    @DisplayName("非 Windows 或已是管理员：权限失败原样抛出，不提权")
    void noElevationOnNonWindowsOrElevated() throws IOException {
        for (boolean windows : new boolean[]{false, true}) {
            StubExtractor stub = new StubExtractor();
            stub.failure = permission();
            FakeLauncher launcher = new FakeLauncher();
            Path tempRoot = Files.createTempDirectory("nexusai-support-noelev2-");
            boolean alreadyElevated = windows;

            KeyExtractionException e = assertThrows(KeyExtractionException.class,
                    () -> support(stub, windows, alreadyElevated, true,
                            elevated(launcher, tempRoot)).extract(Path.of("acct"), true));
            assertEquals(KeyExtractionException.Kind.PERMISSION, e.kind());
            assertEquals(0, launcher.calls);
        }
    }

    @Test
    @DisplayName("提权成功 ⇒ 返回子进程写入的候选")
    void elevatedSuccessReturnsCandidates() throws IOException {
        StubExtractor stub = new StubExtractor();
        stub.failure = permission();
        FakeLauncher launcher = new FakeLauncher();
        launcher.result = ElevationLauncher.LaunchResult.completed(0);
        launcher.candidates = "11".repeat(32) + "\n" + "22".repeat(32) + "\n";
        Path tempRoot = Files.createTempDirectory("nexusai-support-elevok-");

        List<byte[]> out = support(stub, true, false, true, elevated(launcher, tempRoot))
                .extract(Path.of("acct"), true);

        assertEquals(2, out.size());
        assertEquals(1, launcher.calls);
    }
}
