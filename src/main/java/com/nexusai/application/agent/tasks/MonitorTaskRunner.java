package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.bash.ShellExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitor 的命令模式 / WebSocket 模式执行器 · 对齐 claude.exe v2.1.325 的 MonitorTool。
 *
 * <p><b>CC 语义（真源，off≈164,355,xxx）</b>：
 * command 与 ws 二选一；command = "Shell command or script. Each stdout line is an event;
 * exit ends the watch."；description 是通知摘要；timeout_ms 到点结束。
 *
 * <p><b>与 MonitorMcpTaskRunner 的分工</b>：后者是历史实现（固定轮询 MCP 状态），
 * 本类承接 CC 的命令/连接契约。TaskStop 通过 BackgroundTaskRunner 登记的 stopper 停止本类任务。
 */
@Component
public class MonitorTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(MonitorTaskRunner.class);

    /** 逐行事件进模型通知队列的上限（超出只落 outputFile）。 */
    static final int MAX_EVENT_NOTIFICATIONS = 200;

    private final BackgroundTaskRunner backgroundTaskRunner;
    private final TaskFrameworkService taskFrameworkService;
    private final NotificationQueue notificationQueue;

    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "monitor-timeout");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public MonitorTaskRunner(BackgroundTaskRunner backgroundTaskRunner,
                             TaskFrameworkService taskFrameworkService,
                             NotificationQueue notificationQueue) {
        this.backgroundTaskRunner = backgroundTaskRunner;
        this.taskFrameworkService = taskFrameworkService;
        this.notificationQueue = notificationQueue;
    }

    /** 运行态句柄。 */
    private static final class Handle {
        final String taskId;
        final String description;
        final String sessionId;
        final String agentId;
        final String outputFile;
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicInteger events = new AtomicInteger();
        final AtomicBoolean overflowNotified = new AtomicBoolean();
        volatile Process process;
        volatile WebSocket webSocket;

        Handle(String taskId, String description, String sessionId, String agentId, String outputFile) {
            this.taskId = taskId;
            this.description = description;
            this.sessionId = sessionId;
            this.agentId = agentId;
            this.outputFile = outputFile;
        }
    }

    /**
     * 启动命令监控 · 每行 stdout/stderr 是一个事件，命令退出即结束。
     */
    public String startCommand(String command, String description, long timeoutMs,
                               String toolUseId, String agentId, String sessionId) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        Handle h = register(description, toolUseId, agentId, sessionId);
        String cwd = resolveCwd(sessionId);
        Process process;
        try {
            ProcessBuilder pb = cwd == null || cwd.isBlank()
                ? ShellExecutor.bash(command, null)
                : ShellExecutor.bash(command, cwd);
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (IOException e) {
            finish(h, BackgroundTaskStatus.FAILED, -1, "script failed");
            throw new IllegalStateException("Monitor 命令启动失败: " + e.getMessage(), e);
        }
        h.process = process;
        registerStopper(h);

        Thread reader = new Thread(() -> readLoop(h, process), "monitor-cmd-" + h.taskId);
        reader.setDaemon(true);
        reader.start();

        scheduleTimeout(h, timeoutMs);
        log.info("[MonitorTaskRunner] command monitor started taskId={} desc={}",
            h.taskId, description);
        return h.taskId;
    }

    /**
     * 启动 WebSocket 监控 · 每条消息是一个事件，连接关闭即结束。
     */
    public String startWebSocket(String url, List<String> allowedDomains, String description,
                                 long timeoutMs, String toolUseId, String agentId, String sessionId) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("ws 地址非法: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("ws") && !scheme.equals("wss"))) {
            throw new IllegalArgumentException("ws 只接受 ws:// 或 wss:// 地址");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("ws 地址缺少 host");
        }
        if (allowedDomains != null && !allowedDomains.isEmpty() && !domainAllowed(host, allowedDomains)) {
            throw new IllegalArgumentException("ws host 不在 allowed_domains 内: " + host);
        }

        Handle h = register(description, toolUseId, agentId, sessionId);
        WebSocket ws;
        try {
            ws = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build()
                .newWebSocketBuilder()
                .buildAsync(uri, new Listener(h))
                .get(25, TimeUnit.SECONDS);
        } catch (Exception e) {
            finish(h, BackgroundTaskStatus.FAILED, -1, "script failed");
            throw new IllegalStateException("Monitor WebSocket 连接失败: " + e.getMessage(), e);
        }
        h.webSocket = ws;
        registerStopper(h);
        scheduleTimeout(h, timeoutMs);
        log.info("[MonitorTaskRunner] ws monitor started taskId={} url={} desc={}",
            h.taskId, url, description);
        return h.taskId;
    }

    private void readLoop(Handle h, Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onEvent(h, line);
            }
            int exit = process.waitFor();
            if (h.done.get()) {
                return;
            }
            finish(h, exit == 0 ? BackgroundTaskStatus.COMPLETED : BackgroundTaskStatus.FAILED,
                exit, exit == 0 ? "stream ended" : "script failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!h.done.get()) {
                finish(h, BackgroundTaskStatus.KILLED, -1, "stopped");
            }
        } catch (Exception e) {
            log.error("[MonitorTaskRunner] read loop failed taskId={}: {}", h.taskId, e.getMessage(), e);
            if (!h.done.get()) {
                finish(h, BackgroundTaskStatus.FAILED, -1, "script failed");
            }
        }
    }

    /** WebSocket 监听器 · 文本/二进制消息都作为一条事件。 */
    private final class Listener implements WebSocket.Listener {
        private final Handle handle;
        private final StringBuilder text = new StringBuilder();

        Listener(Handle handle) {
            this.handle = handle;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket,
                                                             CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                onEvent(handle, message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket,
                                                               java.nio.ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            onEvent(handle, new String(bytes, StandardCharsets.UTF_8));
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket,
                                                              int statusCode, String reason) {
            if (!handle.done.get()) {
                finish(handle, BackgroundTaskStatus.COMPLETED, statusCode, "stream ended");
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("[MonitorTaskRunner] ws error taskId={}: {}", handle.taskId, error.toString());
            if (!handle.done.get()) {
                finish(handle, BackgroundTaskStatus.FAILED, -1, "script failed");
            }
        }
    }

    /** 一行 stdout / 一条 ws 消息 → 写 outputFile + （限额内）入队事件通知。 */
    private void onEvent(Handle h, String line) {
        if (h.done.get()) {
            return;
        }
        String safe = line == null ? "" : line;
        appendLine(h.outputFile, safe);
        int n = h.events.incrementAndGet();
        if (n <= MAX_EVENT_NOTIFICATIONS) {
            enqueue(h, "<monitor_event task_id='" + esc(h.taskId) + "' description='"
                + esc(h.description) + "'>" + esc(safe) + "</monitor_event>");
        } else if (h.overflowNotified.compareAndSet(false, true)) {
            appendLine(h.outputFile, "[monitor] 其余事件仅写入输出文件（通知上限 "
                + MAX_EVENT_NOTIFICATIONS + " 条）");
        }
    }

    private Handle register(String description, String toolUseId, String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Monitor 需要显式 sessionId");
        }
        String taskId = TaskIdGenerator.generate(TaskType.MONITOR);
        String outputFile = BackgroundTaskRunner.taskOutputPath(sessionId, taskId);
        try {
            Files.createDirectories(Path.of(outputFile).getParent());
        } catch (Exception e) {
            log.warn("[MonitorTaskRunner] cannot create output dir {}: {}", outputFile, e.getMessage());
        }
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.MONITOR, BackgroundTaskStatus.RUNNING,
            description == null ? "" : description, toolUseId,
            System.currentTimeMillis(), null, null,
            outputFile, 0L, false,
            agentId, true, sessionId,
            null, null, null, null, null, null);
        Handle h = new Handle(taskId,
            description == null ? "" : description,
            sessionId,
            agentId,
            outputFile);
        handles.put(taskId, h);
        if (backgroundTaskRunner != null) {
            backgroundTaskRunner.registerExternalTask(task, null);
        }
        return h;
    }

    /** 进程/连接就绪后补登停止钩子（TaskStop → stopTask → 本类 stop）。 */
    private void registerStopper(Handle h) {
        if (backgroundTaskRunner != null) {
            backgroundTaskRunner.setExternalStopper(h.taskId, () -> stop(h.taskId, "stopped"));
        }
    }

    private void scheduleTimeout(Handle h, long timeoutMs) {
        if (timeoutMs <= 0) {
            return;
        }
        timeoutScheduler.schedule(() -> stop(h.taskId, "timed out"), timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 停止一个 Monitor 任务（TaskStop / 超时共用）。 */
    public void stop(String taskId, String reason) {
        Handle h = handles.get(taskId);
        if (h == null || !h.done.compareAndSet(false, true)) {
            return;
        }
        Process p = h.process;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        WebSocket ws = h.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, reason).exceptionally(ex -> null);
            } catch (Exception e) {
                log.debug("[MonitorTaskRunner] ws close failed taskId={}: {}", taskId, e.toString());
            }
        }
        finishState(h, BackgroundTaskStatus.KILLED, -1, reason);
    }

    private void finish(Handle h, BackgroundTaskStatus status, int exitCode, String verb) {
        if (!h.done.compareAndSet(false, true)) {
            return;
        }
        finishState(h, status, exitCode, verb);
    }

    private void finishState(Handle h, BackgroundTaskStatus status, int exitCode, String verb) {
        handles.remove(h.taskId);
        if (backgroundTaskRunner != null) {
            backgroundTaskRunner.unregisterExternalTask(h.taskId);
        }
        BackgroundTask base = backgroundTaskRunner != null
            ? backgroundTaskRunner.getTask(h.taskId).orElse(null) : null;
        if (base != null && taskFrameworkService != null) {
            BackgroundTask done = base.withStatus(status)
                .withEndTime(System.currentTimeMillis())
                .withExitCode(exitCode)
                .withNotified();
            taskFrameworkService.updateTaskState(h.taskId, done);
        }
        enqueue(h, "<monitor_result task_id='" + esc(h.taskId) + "' description='"
            + esc(h.description) + "' status='" + status.getStatusString()
            + "' exit_code='" + exitCode + "'>Monitor \"" + esc(h.description) + "\" "
            + esc(verb) + "</monitor_result>");
        log.info("[MonitorTaskRunner] monitor finished taskId={} status={} verb={} exit={}",
            h.taskId, status.getStatusString(), verb, exitCode);
    }

    private void enqueue(Handle h, String xml) {
        if (notificationQueue == null) {
            return;
        }
        notificationQueue.enqueuePendingNotification(new NotificationQueue.QueueItem(
            xml, "task-notification", NotificationQueue.Priority.NEXT,
            h.agentId, null, false, null, false, null, h.sessionId));
    }

    private static String resolveCwd(String sessionId) {
        try {
            return CwdResolution.getCwd(sessionId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 域名白名单：精确或后缀匹配（www.example.com 命中 example.com）。 */
    static boolean domainAllowed(String host, List<String> domains) {
        if (host == null || domains == null) {
            return false;
        }
        String lower = host.toLowerCase();
        for (String d : domains) {
            if (d == null || d.isBlank()) {
                continue;
            }
            String rule = d.trim().toLowerCase();
            if (lower.equals(rule) || lower.endsWith("." + rule)) {
                return true;
            }
        }
        return false;
    }

    private static void appendLine(String file, String line) {
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(file), line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.debug("[MonitorTaskRunner] append output failed {}: {}", file, e.toString());
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
