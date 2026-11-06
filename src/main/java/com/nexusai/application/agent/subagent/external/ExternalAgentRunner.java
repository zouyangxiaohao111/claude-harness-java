package com.nexusai.application.agent.subagent.external;

import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.subagent.ResumeService;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 外部 CLI 子代理执行器 · 把一次委派变成「取到可执行文件 → spawn → 解析 stdout →
 * 写 transcript → 返回 {@link SubagentExecutor.SubagentResult}」。
 *
 * <p><b>与进程内子代理的边界</b>：本类<b>不</b>参与工具白名单 / hooks / MCP / 权限弹窗
 * —— 那些是外部 CLI 自己的事。本类只保证「同一套对外形态」：同一个 agentId 命名、
 * 同一份 transcript 落盘（右栏可看）、同一种 {@code SubagentResult} 三态。
 *
 * <p><b>进程控制</b>（照 OpenDesign {@code launch.ts} + pi-subagent 的做法）：
 * <ul>
 *   <li>argv 向量直传，<b>不经 shell</b> ⇒ 无命令注入面</li>
 *   <li>硬超时到点 → Windows {@code taskkill /T /F}，Unix {@code destroyForcibly}
 *       （杀整个进程树，防「CLI 起了子进程但父进程退了」留下孤儿）</li>
 *   <li>stderr 独立线程消费（不读会因管道缓冲写满把子进程卡死）</li>
 * </ul>
 */
public final class ExternalAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(ExternalAgentRunner.class);

    /** stderr 保留上限（诊断用，不塞进模型上下文）。 */
    private static final int STDERR_LIMIT = 8192;
    /** 结论文本上限（对齐 DSH conductor head+tail 截断思路，留给模型的可读上限）。 */
    private static final int RESULT_LIMIT = 20_000;

    private ExternalAgentRunner() {
    }

    /**
     * 执行一次外部委派。
     *
     * @param def         外部运行时 agent 定义
     * @param prompt      任务文本
     * @param model       模型覆盖（可 null）
     * @param cwd         工作目录（可 null）
     * @param sessionId   主会话 id（transcript 落盘用）
     * @param agentId     子代理 id（a+16hex；由调用方生成以便与面板一致）
     * @param description 任务描述（写 meta）
     * @return 与进程内子代理同形的结果
     */
    public static SubagentExecutor.SubagentResult run(
            com.nexusai.application.agent.subagent.AgentDefinition.ExternalAgentDefinition def,
            String prompt, String model, Path cwd,
            String sessionId, String agentId, String description) {

        long startMs = System.currentTimeMillis();
        ExternalRuntimeSpec spec;
        try {
            ExternalRuntimeSpec.Resolution resolution =
                    ExternalRuntimeSpec.tryResolve(def, model, cwd);
            if (!resolution.isAvailable()) {
                // 不可用 = 可预期状态（没装 / 名字写错），给**人话原因**而不是栈
                return fail(resolution.unavailableReason(), agentId, startMs);
            }
            spec = resolution.spec();
        } catch (Exception e) {
            return fail("外部运行时解析失败: " + e.getMessage(), agentId, startMs);
        }

        List<String> argv = spec.buildArgv(prompt);
        log.info("[ExternalAgentRunner] 启动外部子代理: runtime={} bin={} stream={} promptVia={} "
                        + "argvSize={} timeoutMs={} cwd={} agentId={}",
                spec.runtime(), spec.bin(), spec.streamFormat(), spec.promptVia(),
                argv.size(), spec.timeoutMs(), cwd, agentId);

        Process process = null;
        try {
            List<String> fullArgv = new ArrayList<>();
            fullArgv.add(spec.bin());
            fullArgv.addAll(argv);
            ProcessBuilder pb = new ProcessBuilder(fullArgv)
                    .directory(cwd != null ? cwd.toFile() : null)
                    .redirectErrorStream(false);
            // 递归深度必须**真的**传给外部 CLI，否则能力位里写的 depthLimit=0 只是注释。
            // 对外统一用 PI_SUBAGENT_DEPTH / PI_SUBAGENT_MAX_DEPTH 两个名字：pi 原生认它们，
            // 其它 CLI 会忽略（无害）；这样「禁递归」在至少一个真实后端上是可验证的。
            int depthLimit = spec.capabilities() == null ? 0 : spec.capabilities().depthLimit();
            pb.environment().put("NEXUSAI_SUBAGENT_DEPTH", "1");
            pb.environment().put("NEXUSAI_SUBAGENT_MAX_DEPTH", String.valueOf(depthLimit));
            pb.environment().put("PI_SUBAGENT_DEPTH", "1");
            pb.environment().put("PI_SUBAGENT_MAX_DEPTH", String.valueOf(depthLimit));
            // profile 级环境变量（照 OpenDesign env 语义）：只作用于这个子进程，不污染服务进程。
            // 典型用途：该 agent 单独走代理 / 单独指定 key 变量 / 给 CLI 传它自己认的开关。
            if (spec.env() != null && !spec.env().isEmpty()) {
                pb.environment().putAll(spec.env());
                log.info("[ExternalAgentRunner] 已注入 profile env: keys={}", spec.env().keySet());
            }
            process = pb.start();

            // stdin 必须显式处理，否则会静默挂死（2026-11-04 真机踩到）：
            //   codex 在没有 `-` / 无 stdin 重定向时会打印 "Reading additional input from stdin..."
            //   然后**一直等**，直到外部超时被杀（exit 143）。
            //   - promptVia=stdin：把任务写进去再关闭；
            //   - promptVia=argv：立即关闭 stdin（等价 shell 的 `< /dev/null`）。
            if (ExternalRuntimeSpec.supportsStdin(spec.promptVia())) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(prompt == null ? new byte[0] : prompt.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException e) {
                    log.warn("[ExternalAgentRunner] 写 stdin 失败（进程可能已退出）: {}", e.getMessage());
                }
            } else {
                try {
                    process.getOutputStream().close();
                } catch (IOException ignore) {
                    // 进程已退出
                }
            }

            // stdout：按行解析（流式协议必须逐行，不能等 EOF）
            ExternalStreamParser.Accumulator acc =
                    ExternalStreamParser.accumulator(spec.streamFormat());
            List<String> stderrBuf = new ArrayList<>();
            Thread stderrThread = startStderrDrain(process, stderrBuf);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    acc.acceptLine(line);
                }
            }

            boolean finished = process.waitFor(spec.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                killTree(process);
                return fail("外部运行时 '" + spec.runtime() + "' 超时（" + spec.timeoutMs() + "ms），已终止进程树",
                        agentId, startMs);
            }
            stderrThread.join(1500);
            int exit = process.exitValue();

            ExternalStreamParser.Parsed parsed = acc.freeze();
            String stderr = String.join("\n", stderrBuf);
            if (stderr.length() > STDERR_LIMIT) {
                stderr = stderr.substring(stderr.length() - STDERR_LIMIT);
            }

            // transcript 落盘：成功/失败都写（失败也有诊断价值）
            recordTranscript(sessionId, agentId, description, def, parsed, exit, stderr);

            if (exit != 0 && parsed.text().isBlank()) {
                return fail("外部运行时 '" + spec.runtime() + "' 退出码 " + exit
                                + (stderr.isBlank() ? "" : "；stderr: " + abbreviate(stderr, 800)),
                        agentId, startMs);
            }
            // 退出码 0 但**没有任何正文** = 没答上来（2026-11-04 真机：pi 只吐 session 事件就退出）。
            // ⛔ 不能标 completed —— 上层会把空结论当成功，用户看到「已完成但没有回答」。
            if (parsed.text().isBlank()) {
                return fail("外部运行时 '" + spec.runtime() + "' 未返回任何正文（exit=" + exit
                                + (stderr.isBlank() ? "" : "；stderr: " + abbreviate(stderr, 800))
                                + "）。可能是该 CLI 未登录/额度不足，或它只输出了元数据事件",
                        agentId, startMs);
            }

            long durationMs = System.currentTimeMillis() - startMs;
            String summary = truncate(parsed.text());
            log.info("[ExternalAgentRunner] 外部子代理结束: runtime={} exit={} toolCalls={} "
                            + "reasoningLen={} textLen={} durationMs={} agentId={}",
                    spec.runtime(), exit, parsed.toolCalls().size(),
                    parsed.reasoning().length(), summary.length(), durationMs, agentId);

            return new SubagentExecutor.SubagentResult(
                    summary, parsed.toolCalls().size(), durationMs, agentId,
                    "completed", 0L, AgentUsage.EMPTY);

        } catch (IOException e) {
            killTree(process);
            return fail("外部运行时启动失败: " + e.getMessage(), agentId, startMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            return new SubagentExecutor.SubagentResult(
                    "外部子代理被中断", 0, System.currentTimeMillis() - startMs, agentId,
                    "aborted", 0L, AgentUsage.EMPTY);
        } finally {
            killTree(process);
        }
    }

    /**
     * 把解析结果写成与进程内子代理同格式的 sidechain transcript。
     *
     * <p>为什么必须走 {@link AgentTranscript#recordSidechainTranscript}：那是本仓
     * transcript 写入的**唯一漏斗**，增量推送（右栏实时）也挂在那条路径上。
     */
    private static void recordTranscript(String sessionId, String agentId, String description,
                                         com.nexusai.application.agent.subagent.AgentDefinition.ExternalAgentDefinition def,
                                         ExternalStreamParser.Parsed parsed, int exit, String stderr) {
        if (sessionId == null || agentId == null) {
            log.warn("[ExternalAgentRunner] sessionId/agentId 为空，跳过 transcript 落盘");
            return;
        }
        try {
            Path sessionDir = ResumeService.resolveSessionDir(sessionId);
            AgentTranscript.writeMetadata(sessionDir, sessionId, agentId,
                    new AgentTranscript.AgentMetadata(def.agentType(), null, description));

            String ts = OffsetDateTime.now().toString();
            List<Map<String, Object>> messages =
                    ExternalStreamParser.toTranscriptMessages(parsed, ts);
            if (exit != 0) {
                // 失败也留痕：末条带 isError，右栏 inferStatus 会判 failed
                Map<String, Object> err = new java.util.LinkedHashMap<>();
                err.put("role", "assistant");
                err.put("content", stderr.isBlank() ? ("退出码 " + exit) : stderr);
                err.put("isError", true);
                err.put("timestamp", ts);
                messages.add(err);
            }
            if (!messages.isEmpty()) {
                AgentTranscript.recordSidechainTranscript(sessionDir, sessionId, agentId, messages);
            }
        } catch (Exception e) {
            // transcript 失败绝不掩盖真实结果（只 warn）
            log.warn("[ExternalAgentRunner] transcript 落盘失败 agentId={}: {}", agentId, e.getMessage());
        }
    }

    /** stderr 必须被消费，否则管道缓冲写满会把子进程卡死。 */
    private static Thread startStderrDrain(Process process, List<String> sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) {
                        if (sink.size() < 500) sink.add(line);
                    }
                }
            } catch (IOException ignore) {
                // 进程已退出 / 流已关闭
            }
        }, "external-agent-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 杀整个进程树（Windows 必须 /T，否则 CLI 起的子进程会成孤儿）。 */
    private static void killTree(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            if (ExternalRuntimeSpec.isWindows()) {
                new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(process.pid()))
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignore) {
            // 退化为下面的通用 destroy
        }
        try {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignore) {
            process.destroyForcibly();
        }
    }

    private static SubagentExecutor.SubagentResult fail(String message, String agentId, long startMs) {
        log.warn("[ExternalAgentRunner] 外部子代理失败: {}", message);
        return new SubagentExecutor.SubagentResult(message, 0,
                System.currentTimeMillis() - startMs, agentId, "failed", 0L, AgentUsage.EMPTY);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        if (s.length() <= RESULT_LIMIT) return s;
        int head = RESULT_LIMIT * 2 / 3;
        int tail = RESULT_LIMIT - head;
        return s.substring(0, head) + "\n…[已截断 " + (s.length() - RESULT_LIMIT) + " 字符]…\n"
                + s.substring(s.length() - tail);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** agentId 生成（与进程内子代理同格式 a+16hex，面板/端点无需区分）。 */
    public static String newAgentId() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return "a" + hex.substring(0, 16);
    }
}
