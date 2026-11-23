package com.nexusai.application.agent.acp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ACP 真探测 · 「测试连接」用：spawn 适配器 → initialize → session/new → 收尾。
 *
 * <p>⛔ 不伪造「可用」：{@code ok} 只有在 {@code session/new} 成功时才为 true；
 * {@code probeLevel} 如实记录走到了哪一步（failed / process_only / acp_initialize / acp_session）。
 *
 * <p>复用 {@link AcpProcess} + {@link AcpTransport} + {@link AcpClient}，与真实派发同一条链，
 * 不另写一套协议实现。收尾顺序同 {@link AcpAgentRunner}：先收进程，再关传输。
 */
public final class AcpRuntimeProbe {

    private static final Logger log = LoggerFactory.getLogger(AcpRuntimeProbe.class);

    /** 探测结论。 */
    public record Result(boolean ok, String probeLevel, long elapsedMs, String message) {
    }

    /** 关 stdin 后等子进程自行退出的宽限（探测场景比派发更短）。 */
    private static final long EOF_GRACE_MS = 2_000L;

    private AcpRuntimeProbe() {
    }

    public static Result probe(AcpRuntimeSpec spec, long handshakeTimeoutMs) {
        long startMs = System.currentTimeMillis();
        AcpProcess.Started started = null;
        AcpTransport transport = null;
        String level = "failed";
        try {
            started = AcpProcess.start(spec.fullArgv(), spec.cwd(), spec.env());
            level = "process_only";
            transport = AcpTransport.forProcess(started);
            transport.start();
            AcpClient client = new AcpClient(transport);

            client.initialize(handshakeTimeoutMs);
            level = "acp_initialize";

            client.newSession(spec.cwd(), handshakeTimeoutMs);
            level = "acp_session";

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("[AcpRuntimeProbe] ACP 探测成功: bin={} level={} elapsedMs={}",
                    spec.bin(), level, elapsed);
            return new Result(true, level, elapsed, "ACP 握手成功（initialize + session/new）");
        } catch (Throwable e) {
            String stderr = started == null ? "" : started.stderrTail(400);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            if (!stderr.isBlank()) {
                message = message + "；stderr: " + stderr;
            }
            long elapsed = System.currentTimeMillis() - startMs;
            log.warn("[AcpRuntimeProbe] ACP 探测失败: bin={} level={} elapsedMs={} err={}",
                    spec == null ? null : spec.bin(), level, elapsed, message);
            return new Result(false, level, elapsed, message);
        } finally {
            if (started != null) {
                AcpProcess.quiesce(started.process(), EOF_GRACE_MS);
            }
            if (transport != null) {
                try {
                    transport.close();
                } catch (RuntimeException ignore) {
                    // 已关闭
                }
            }
        }
    }
}
