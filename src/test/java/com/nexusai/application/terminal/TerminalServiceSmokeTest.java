package com.nexusai.application.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>真机冒烟</b>：不启 Spring、不碰 DB，直接 {@code new TerminalService()} 起一个真实 PTY，
 * 写入 {@code echo 42} 并从环形缓冲读回输出。
 *
 * <p><b>WHY 必须有这个测试</b>：pty4j 的可用性<b>完全取决于本机原生库</b>（Windows winpty/ConPTY 的
 * dll/exe、Unix 的 libpty.so），单测用 mock 覆盖不到；这类问题只会在真机暴露成「用户点了没反应」。
 * 本测试就是交付报告里那条「真机验证 PTY-OK」的可重跑版本。
 *
 * <p><b>为什么断言「恰好等于 42 的行」而不是包含命令原文</b>：交互式 shell 会把输入的
 * {@code echo 42} <b>回显</b>到终端里 —— 只断言 {@code contains("42")} 会被回显骗成假绿
 * （命令没执行也算通过）。要求存在一行<b>整行就是 42</b>，才是「命令真的被执行」。
 */
class TerminalServiceSmokeTest {

    @Test
    @DisplayName("真机：PTY 起 shell → 写 echo 42 → snapshot 读到执行结果（非回显）")
    void realPtyEcho() throws Exception {
        TerminalService service = new TerminalService();
        String sessionId = "smoke-pty-session";
        TerminalInfo info = null;
        try {
            // 显式 cwd（user.home）：绕开 CwdResolution 的会话/DB 解析，确保本测试不碰 DB
            info = service.createInternal(sessionId, null, 80, 24, System.getProperty("user.home"));
            assertThat(info.alive()).isTrue();
            assertThat(service.list(sessionId)).hasSize(1);

            String snapshot = "";
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline) {
                // shell 起来之前写入会被丢弃 ⇒ 循环重发，直到看到「整行 42」
                service.write(sessionId, info.id(), "echo 42\r");
                Thread.sleep(1000L);
                snapshot = service.snapshot(sessionId, info.id(), 100);
                if (Arrays.stream(snapshot.split("\n")).anyMatch(line -> "42".equals(line.trim()))) {
                    break;
                }
            }
            System.out.println("PTY-SNAPSHOT>>> " + snapshot.replace("\n", " | "));
            assertThat(Arrays.stream(snapshot.split("\n")).anyMatch(line -> "42".equals(line.trim())))
                .as("PTY 应真的执行 echo 42（快照=%s）", snapshot)
                .isTrue();

            // resize 走真 ioctl —— 失败会抛 ConflictException
            service.resize(sessionId, info.id(), 120, 40);
            TerminalInfo resized = service.list(sessionId).get(0);
            assertThat(resized.cols()).isEqualTo(120);
            assertThat(resized.rows()).isEqualTo(40);
        } finally {
            service.closeSession(sessionId);
        }
        assertThat(service.list(sessionId)).isEmpty();
    }
}
