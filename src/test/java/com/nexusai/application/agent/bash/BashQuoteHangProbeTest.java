package com.nexusai.application.agent.bash;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * [临时探查 2026-09-03] bash 引号丢参复现探针。
 *
 * <p>事故：模型执行 {@code python -c "import docx; print('python-docx OK')"}（含单引号内嵌）
 * 经 BashTool 的 wrapForCwdTracking 包装后，python 以<b>裸进程</b>启动（无 {@code -c}）→
 * 卡住 bash 链 120s 超时。
 *
 * <p>本探针直接调用 {@link ShellExecutor#wrapForCwdTracking} 生成命令串 → 打印 → 真实 Git Bash
 * 执行 → 断言 python 是否收到完整 {@code -c}（正常输出 / 裸启无输出 / 挂起）。
 *
 * <p>临时探查类：不入正式回归（定位后删或收敛为修复测试）。
 */
class BashQuoteHangProbeTest {

    private static final String PYTHON = "C:/Python314/python.exe";

    /** 复现命令 A：与事故一致的含单引号 python 代码（print('python-docx OK')）。 */
    private static final String CMD_A = PYTHON + " -c \"import docx, sys; print('python-docx OK', 123)\"";

    /** 复现命令 B：无单引号对照（print(123)）。 */
    private static final String CMD_B = PYTHON + " -c \"import docx; print(123)\"";

    /** 复现命令 C：模型原始（含 python-docx 全名 + sys）。 */
    private static final String CMD_C = PYTHON + " -c \"import docx, sys; print('python-docx OK')\"";

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void probe_singleQuoteInside_doubleQuoteParam() throws Exception {
        runProbe("CMD_A(单引号 print)", CMD_A);
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void probe_noSingleQuote_control() throws Exception {
        runProbe("CMD_B(无单引号对照)", CMD_B);
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void probe_modelOriginal() throws Exception {
        runProbe("CMD_C(模型原始)", CMD_C);
    }

    /** Java ProcessBuilder 还原 argv 对照：bash -c 'printf $1' probe <wrapped> → 看还原形态。 */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void probe_javaArgvReduction() throws Exception {
        String cmd = PYTHON + " -c \"import docx; print(123)\"";
        Path cwdTrack = Files.createTempFile("bash-cwd-probe-", ".tmp");
        String wrapped = ShellExecutor.wrapForCwdTracking(cmd, cwdTrack);
        String printScript = "printf '[%s]\\n' \"$1\"";
        ProcessBuilder pb = new ProcessBuilder(ShellExecutor.resolveShell(), "-c", printScript, "probe", wrapped);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean fin = p.waitFor(10, TimeUnit.SECONDS);
        String out = fin ? new String(p.getInputStream().readAllBytes()) : "(timeout)";
        if (!fin) { p.destroyForcibly(); }
        System.err.println("[JAVA-ARGV] exit=" + (fin ? p.exitValue() : "?" ));
        System.err.println("[JAVA-ARGV] bash 收到的 $1 还原 = ");
        System.err.println(out.replace("\r", "").strip());
    }

    private void runProbe(String label, String cmd) throws Exception {
        Path cwdTrack = Files.createTempFile("bash-cwd-probe-", ".tmp");
        String wrapped = ShellExecutor.wrapForCwdTracking(cmd, cwdTrack);
        System.err.println("\n===== [" + label + "] wrapForCwdTracking 输出 =====");
        System.err.println("[WRAPPED]" + wrapped);
        System.err.println("===== 生产路径执行（ShellExecutor.bash → 修复后 Windows 含 \" 走临时 .sh 脚本文件）=====");

        ProcessBuilder pb = ShellExecutor.bash(wrapped, null, null);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(15, TimeUnit.SECONDS);
        String out = finished ? new String(p.getInputStream().readAllBytes()) : "";
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(2, TimeUnit.SECONDS);
        }
        System.err.println("[PROBE-FIXED][" + label + "] finished=" + finished + " exit=" + (finished ? p.exitValue() : "HANG"));
        System.err.println("[PROBE-FIXED][" + label + "] out=" + out.replace("\n", "\\n").substring(0, Math.min(out.length(), 300)));

        // 回归断言：修复后（方案 A 脚本文件）事故命令必须自然完成、python -c 参数到达（输出完整）
        assertThat(finished).as("[" + label + "] 修复后不得挂起 15s（曾 python 裸启无 -c 挂满 120s 超时）").isTrue();
        if (finished) {
            assertThat(p.exitValue()).as("[" + label + "] 命令自然完成").isZero();
            String expect = label.contains("CMD_B") ? "123" : "python-docx OK";
            assertThat(out).as("[" + label + "] python 收到完整 -c（输出含 " + expect + "）").contains(expect);
        }
    }
}
