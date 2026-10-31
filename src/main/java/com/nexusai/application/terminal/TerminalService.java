package com.nexusai.application.terminal;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 集成终端（PTY）服务 · 每个会话可持有多个终端，cwd = 会话项目目录。
 *
 * <p><b>WHY 由后端 JVM 持有 PTY</b>（用户裁定，不是实现偏好）：Agent 要能「读终端输出」判断
 * dev server / 构建状态。若 PTY 挂在前端进程（xterm.js 只能经 node-pty/WebView 起壳），输出
 * 只在那个进程的内存里，后端工具<b>结构上拿不到</b>；把 PTY 放进后端 JVM 后，读线程写的
 * {@link TerminalBuffer} 与 Agent 工具同进程 ⇒ 无需任何 IPC / 转发即可读。
 *
 * <p><b>关键口径（照实声明，⛔ 不要按「通常做法」理解）</b>
 * <ul>
 *   <li><b>进程退出后不立刻丢弃</b>：读线程在 EOF 后把该终端标记为 {@code closed}（{@code alive=false}）
 *       并<b>保留环形缓冲</b> —— 重连的前端与 Agent 仍要能读到「构建为什么失败」的最后几百行。
 *       每会话最多保留 {@value #MAX_CLOSED_RECORDS_PER_SESSION} 条已关闭记录，超出按启动时间淘汰最旧。
 *       <b>显式 {@link #close} 则立刻移除</b>（用户已经关掉面板，没有再看历史的入口）。</li>
 *   <li><b>cwd 回落</b>：{@link CwdResolution#getCwd(String)} 在「会话存在却未绑定项目」时
 *       <b>fail-loud 抛</b>（该行为是既有裁定，⛔ 不改）。终端场景必须能起来，故 catch 后回落
 *       {@code user.home} 并<b>照实打 WARN</b>（⛔ 不回落到后端 {@code user.dir} 冒充项目根 ——
 *       那是历史误删的根因）。</li>
 *   <li><b>shell 白名单</b>：见 {@link TerminalShellResolver#validate}；启动参数以 {@code String[]}
 *       直传 PTY，不经 shell 解析 ⇒ 无注入面。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：每终端一个 <b>daemon</b> 读线程（{@code pty-{sessionId}-{terminalId}}）——
 * 后端常驻 JVM，daemon 保证进程退出时不会被终端线程吊住。读线程做三件事：写缓冲、推 STOMP、
 * 退出时补最后一帧。JVM 关闭由 {@link #shutdown()}（{@code @PreDestroy}）统一 kill，避免 shell 残留。
 *
 * <p><b>STOMP</b>：topic {@code /topic/sessions/{sessionId}/terminal/{terminalId}}，
 * 数据帧 {@code {terminalId, data, closed:false}}；退出帧 {@code {terminalId, closed:true, exitCode}}。
 * wsTemplate 未注入（纯单测 / headless）⇒ 跳过推送，只写缓冲。
 */
@Service
public class TerminalService {

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);

    /** STOMP topic 前缀（数据帧与退出帧共用）。 */
    public static final String TOPIC_PREFIX = "/topic/sessions/";
    /** STOMP topic 中「终端」段（完整形态 {@code /topic/sessions/{sid}/terminal/{tid}}）。 */
    public static final String TOPIC_TERMINAL_SEGMENT = "/terminal/";

    public static final int DEFAULT_COLS = 80;
    public static final int DEFAULT_ROWS = 24;
    public static final int MAX_COLS = 1000;
    public static final int MAX_ROWS = 1000;

    /** 每会话最多同时存活的终端数（防一次误操作拉起几十个 shell 进程）。 */
    public static final int MAX_ALIVE_PER_SESSION = 20;

    /** 每会话最多保留的「已退出但缓冲还可读」记录数。 */
    public static final int MAX_CLOSED_RECORDS_PER_SESSION = 10;

    /** 单次写入上限（字符）：粘贴大段文本走这里，但别让一次请求塞爆 PTY。 */
    static final int MAX_WRITE_CHARS = 64 * 1024;

    /** {@code sessionId -> (terminalId -> 终端会话)}。 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, TerminalSession>> terminals =
        new ConcurrentHashMap<>();

    private final TerminalShellResolver shellResolver;

    /** null = 非 Spring 环境（纯单测 / headless）：只写缓冲，不推 STOMP。 */
    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    public TerminalService() {
        this(TerminalShellResolver.forCurrentSystem());
    }

    /** 测试注入用（不启动 Spring、不依赖本机装了哪些 shell）。 */
    TerminalService(TerminalShellResolver shellResolver) {
        this.shellResolver = shellResolver != null ? shellResolver : TerminalShellResolver.forCurrentSystem();
    }

    /** 供 plain JUnit 注入 STOMP 模板（Spring 环境走字段注入，不需要调它）。 */
    public void setWsTemplate(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    // ───────────────────────── 创建 / 列表 ─────────────────────────

    /**
     * 新建终端（cwd = 会话项目目录）。
     *
     * @param sessionId 会话 ID
     * @param shell     指定 shell（null/空白 ⇒ 默认探测结果；非白名单 ⇒ 400）
     * @param cols      列数（{@code <=0} ⇒ {@value #DEFAULT_COLS}，超出上限则钳到上限）
     * @param rows      行数（同上）
     * @return 新终端描述
     * @throws ValidationException  sessionId 为空 / shell 不在白名单 / 存活终端数超限
     * @throws IllegalStateException PTY 启动失败（装配或环境异常）
     */
    public TerminalInfo create(String sessionId, String shell, int cols, int rows) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ValidationException("创建终端必须提供 sessionId");
        }
        return createInternal(sessionId, shell, cols, rows, resolveCwd(sessionId));
    }

    /**
     * 以<b>显式 cwd</b> 新建终端（包内可见 = 单测/冒烟用，生产走 {@link #create}）。
     *
     * @param sessionId 会话 ID（仅作为 map 键与 topic 段，不解析 cwd）
     * @param shell     指定 shell（null ⇒ 默认）
     * @param cols      列数
     * @param rows      行数
     * @param cwd       工作目录（绝对路径）
     * @return 新终端描述
     */
    TerminalInfo createInternal(String sessionId, String shell, int cols, int rows, String cwd) {
        int c = clampSize(cols, DEFAULT_COLS, MAX_COLS);
        int r = clampSize(rows, DEFAULT_ROWS, MAX_ROWS);
        String resolvedShell = shellResolver.validate(shell);

        ConcurrentHashMap<String, TerminalSession> bucket =
            terminals.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        long alive = bucket.values().stream().filter(t -> !t.isClosed()).count();
        if (alive >= MAX_ALIVE_PER_SESSION) {
            throw new ValidationException("会话 " + sessionId + " 的存活终端已达上限 "
                + MAX_ALIVE_PER_SESSION + "，请先关闭不用的终端");
        }

        String terminalId = "term-" + UUID.randomUUID().toString().substring(0, 8);
        String[] command = shellResolver.commandFor(resolvedShell);
        PtyProcess process;
        try {
            process = new PtyProcessBuilder()
                .setCommand(command)
                .setEnvironment(buildEnvironment())
                .setDirectory(cwd)
                .setInitialColumns(c)
                .setInitialRows(r)
                // stderr 合并进 stdout：Agent 读 snapshot 时要能看到构建错误（PTY 下二者本就同 tty，
                // 但显式设置可避免 pty4j 在部分平台给出 null 的 getErrorStream）。
                .setRedirectErrorStream(true)
                .start();
        } catch (Exception e) {
            log.error("[TerminalService] 终端启动失败: session={} shell={} cwd={} err={}",
                sessionId, resolvedShell, cwd, e.toString());
            throw new IllegalStateException("终端启动失败: " + e.getMessage(), e);
        }

        TerminalSession session = new TerminalSession(sessionId, terminalId, resolvedShell, cwd, c, r,
            process, new TerminalBuffer());
        bucket.put(terminalId, session);
        startReader(session);
        log.info("[TerminalService] 创建终端: session={} terminal={} shell={} cwd={} size={}x{} pid={}",
            sessionId, terminalId, resolvedShell, cwd, c, r, safePid(process));
        return session.info();
    }

    // ───────────────────────── 能力查询 ─────────────────────────

    /**
     * 本机可用 shell（前端「默认终端」下拉数据源）。
     *
     * <p>与 {@link #create} 的白名单<b>同源</b>（同一个 resolver 实例）⇒ 下拉值回传必过校验。
     *
     * @return 有序白名单（本机一个都没有 ⇒ 空列表）
     */
    public List<String> availableShells() {
        return shellResolver.availableShells();
    }

    /**
     * 默认 shell（{@link #create} 不传 shell 时实际使用的值）。
     *
     * @return 白名单第一项
     * @throws IllegalStateException 本机没有任何可用 shell（与 {@link TerminalShellResolver#resolveDefault()} 一致）
     */
    public String defaultShell() {
        return shellResolver.resolveDefault();
    }

    /**
     * 会话下的终端列表（含已退出但缓冲仍可读的记录，{@code alive=false}）。
     *
     * @param sessionId 会话 ID
     * @return 按启动时间升序的终端描述（无终端 ⇒ 空列表）
     */
    public List<TerminalInfo> list(String sessionId) {
        ConcurrentHashMap<String, TerminalSession> bucket = terminals.get(sessionId);
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }
        List<TerminalSession> snapshot = new ArrayList<>(bucket.values());
        snapshot.sort(Comparator.comparing(TerminalSession::startedAt));
        List<TerminalInfo> out = new ArrayList<>(snapshot.size());
        for (TerminalSession t : snapshot) {
            out.add(t.info());
        }
        return out;
    }

    // ───────────────────────── 输入 / 尺寸 / 关闭 ─────────────────────────

    /**
     * 向 PTY 写入用户输入（xterm.js 的 {@code onData} 直连）。
     *
     * @param sessionId  会话 ID
     * @param terminalId 终端 ID
     * @param data       原始文本（回车是 {@code \r}；控制键序列原样透传）
     * @throws NotFoundException 终端不存在
     * @throws ConflictException 终端已退出 / 写入失败
     */
    public void write(String sessionId, String terminalId, String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        if (data.length() > MAX_WRITE_CHARS) {
            throw new ValidationException("单次写入过长（" + data.length() + " > " + MAX_WRITE_CHARS + " 字符）");
        }
        TerminalSession t = require(sessionId, terminalId);
        if (t.isClosed()) {
            throw new ConflictException("终端已退出，无法写入: " + terminalId);
        }
        try {
            t.write(data);
        } catch (Exception e) {
            log.warn("[TerminalService] 终端写入失败: session={} terminal={} err={}",
                sessionId, terminalId, e.toString());
            throw new ConflictException("终端写入失败: " + e.getMessage());
        }
    }

    /**
     * 调整窗口尺寸（xterm.js {@code onResize}）。
     *
     * @param sessionId  会话 ID
     * @param terminalId 终端 ID
     * @param cols       列数（{@code <=0} ⇒ 保持默认下限 1，见 {@link #clampSize}）
     * @param rows       行数
     * @throws NotFoundException 终端不存在
     * @throws ConflictException 终端已退出 / ioctl 失败
     */
    public void resize(String sessionId, String terminalId, int cols, int rows) {
        TerminalSession t = require(sessionId, terminalId);
        int c = clampSize(cols, DEFAULT_COLS, MAX_COLS);
        int r = clampSize(rows, DEFAULT_ROWS, MAX_ROWS);
        if (t.isClosed()) {
            throw new ConflictException("终端已退出，无法调整尺寸: " + terminalId);
        }
        try {
            t.resize(c, r);
            if (log.isDebugEnabled()) {
                log.debug("[TerminalService] 终端尺寸变更: session={} terminal={} {}x{}",
                    sessionId, terminalId, c, r);
            }
        } catch (Exception e) {
            log.warn("[TerminalService] 终端尺寸变更失败: session={} terminal={} err={}",
                sessionId, terminalId, e.toString());
            throw new ConflictException("终端尺寸变更失败: " + e.getMessage());
        }
    }

    /**
     * 关闭并移除终端（用户点 × / DELETE 端点）。
     *
     * <p>与「进程自然退出」不同：这里<b>连缓冲一起丢弃</b>（前端已经关掉面板，没有再看历史的入口）。
     * 读线程仍会在 EOF 后补一帧 {@code closed}（前端幂等处理）。
     *
     * @param sessionId  会话 ID
     * @param terminalId 终端 ID
     * @throws NotFoundException 终端不存在
     */
    public void close(String sessionId, String terminalId) {
        ConcurrentHashMap<String, TerminalSession> bucket = terminals.get(sessionId);
        if (bucket == null) {
            throw new NotFoundException("终端不存在: " + sessionId + "/" + terminalId);
        }
        TerminalSession t = bucket.remove(terminalId);
        if (t == null) {
            throw new NotFoundException("终端不存在: " + sessionId + "/" + terminalId);
        }
        terminate(t);
        // exitCode 由读线程在 EOF 后补写，此刻可能仍是 -1（只用于日志，不影响关闭语义）
        log.info("[TerminalService] 关闭终端: session={} terminal={}（缓冲已丢弃）", sessionId, terminalId);
    }

    /**
     * 关闭会话下全部终端（会话删除时由 {@code SessionService.delete} 调用）。
     *
     * <p>best-effort：单个终端 kill 失败只打 WARN，不影响其余终端与会话删除主流程。
     *
     * @param sessionId 会话 ID（未知/无终端 ⇒ no-op）
     */
    public void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ConcurrentHashMap<String, TerminalSession> bucket = terminals.remove(sessionId);
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        int count = bucket.size();
        for (TerminalSession t : bucket.values()) {
            try {
                terminate(t);
            } catch (Exception e) {
                log.warn("[TerminalService] closeSession 终止终端失败: terminal={} err={}",
                    t.terminalId(), e.toString());
            }
        }
        log.info("[TerminalService] 会话关闭 → 终止 {} 个终端: session={}", count, sessionId);
    }

    // ───────────────────────── 读取 ─────────────────────────

    /**
     * 最近 {@code lines} 行输出（已剥离 ANSI 转义），供 Agent 判断 dev server / 构建状态、供前端重连回放。
     *
     * @param sessionId  会话 ID
     * @param terminalId 终端 ID
     * @param lines      需要的行数（{@code <=0} ⇒ 全部，最多 {@value TerminalBuffer#DEFAULT_MAX_LINES}）
     * @return 快照文本（无输出 ⇒ 空串）
     * @throws NotFoundException 终端不存在（显式 close 后即不存在）
     */
    public String snapshot(String sessionId, String terminalId, int lines) {
        return require(sessionId, terminalId).buffer().snapshot(lines);
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    /**
     * 后端关闭 → 终止全部 PTY 进程，避免 shell/子进程残留（{@code @PreDestroy}）。
     *
     * <p>⚠️ 此时不推 STOMP（Spring 上下文正在关停，broker 可能已不可用）。
     */
    @PreDestroy
    public void shutdown() {
        int count = 0;
        for (ConcurrentHashMap<String, TerminalSession> bucket : terminals.values()) {
            for (TerminalSession t : bucket.values()) {
                try {
                    terminate(t);
                    count++;
                } catch (Exception e) {
                    log.warn("[TerminalService] 关停时终止终端失败: terminal={} err={}",
                        t.terminalId(), e.toString());
                }
            }
        }
        terminals.clear();
        log.info("[TerminalService] 后端关停 → 已终止 {} 个终端进程", count);
    }

    // ───────────────────────── 读线程 ─────────────────────────

    /** 起读线程（daemon：后端退出不被终端线程吊住）。 */
    private void startReader(TerminalSession session) {
        Thread thread = new Thread(() -> runReader(session),
            "pty-" + session.sessionId() + "-" + session.terminalId());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 读线程主体：bytes → UTF-8 文本 → 缓冲 + STOMP；EOF 后补 closed 帧并标记退出。
     *
     * <p>用 {@link InputStreamReader} 而不是 {@code new String(bytes)}：PTY 读到的是<b>任意切分</b>的
     * 字节块，多字节字符会被劈成两半 —— 直接用 {@code new String} 会产出替换符（中文输出变问号）。
     */
    private void runReader(TerminalSession terminal) {
        try (InputStream in = terminal.process().getInputStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                String text = new String(buf, 0, n);
                terminal.buffer().append(text);
                publishData(terminal, text);
            }
        } catch (Exception e) {
            if (!terminal.isClosed()) {
                log.warn("[TerminalService] 终端读取线程异常: session={} terminal={} err={}",
                    terminal.sessionId(), terminal.terminalId(), e.toString());
            }
        } finally {
            int exit = waitExitCode(terminal);
            terminal.markClosed(exit);
            publishClosed(terminal, exit);
            retainClosedRecord(terminal);
            log.info("[TerminalService] 终端进程退出: session={} terminal={} exit={}",
                terminal.sessionId(), terminal.terminalId(), exit);
        }
    }

    /** 等进程退出码（EOF 之后通常已经退出；最多等 2s，拿不到记 -1）。 */
    private int waitExitCode(TerminalSession terminal) {
        try {
            if (terminal.process().waitFor(2, TimeUnit.SECONDS)) {
                return terminal.process().exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[TerminalService] 取退出码失败: terminal={} err={}", terminal.terminalId(), e.toString());
        }
        return -1;
    }

    /** 已退出终端的保留策略：留在 map 里（缓冲可读），但每会话最多 {@value #MAX_CLOSED_RECORDS_PER_SESSION} 条。 */
    private void retainClosedRecord(TerminalSession terminal) {
        ConcurrentHashMap<String, TerminalSession> bucket = terminals.get(terminal.sessionId());
        if (bucket == null || bucket.get(terminal.terminalId()) != terminal) {
            // 显式 close / closeSession 已经移除 ⇒ 无需保留
            return;
        }
        List<TerminalSession> closed = new ArrayList<>();
        for (TerminalSession t : bucket.values()) {
            if (t.isClosed()) {
                closed.add(t);
            }
        }
        if (closed.size() <= MAX_CLOSED_RECORDS_PER_SESSION) {
            return;
        }
        closed.sort(Comparator.comparing(TerminalSession::startedAt));
        int excess = closed.size() - MAX_CLOSED_RECORDS_PER_SESSION;
        for (int i = 0; i < excess; i++) {
            TerminalSession victim = closed.get(i);
            bucket.remove(victim.terminalId());
            victim.buffer().clear();
            if (log.isDebugEnabled()) {
                log.debug("[TerminalService] 淘汰已退出终端记录（超上限）: terminal={}", victim.terminalId());
            }
        }
    }

    // ───────────────────────── STOMP ─────────────────────────

    /** 终端 topic（数据帧与退出帧共用同一 topic）。 */
    public static String topicOf(String sessionId, String terminalId) {
        return TOPIC_PREFIX + sessionId + TOPIC_TERMINAL_SEGMENT + terminalId;
    }

    private void publishData(TerminalSession terminal, String text) {
        Map<String, Object> payload = new LinkedHashMap<>(4);
        payload.put("terminalId", terminal.terminalId());
        payload.put("data", text);
        payload.put("closed", false);
        publish(terminal, payload);
    }

    private void publishClosed(TerminalSession terminal, int exitCode) {
        Map<String, Object> payload = new LinkedHashMap<>(4);
        payload.put("terminalId", terminal.terminalId());
        payload.put("closed", true);
        payload.put("exitCode", exitCode);
        publish(terminal, payload);
    }

    /**
     * 推一帧（wsTemplate 未注入 ⇒ 跳过；推送异常只 WARN，⛔ 不许打死读线程 ——
     * 否则一个断开的客户端就能让终端「静默停止输出」）。
     */
    private void publish(TerminalSession terminal, Map<String, Object> payload) {
        SimpMessagingTemplate ws = this.wsTemplate;
        String topic = topicOf(terminal.sessionId(), terminal.terminalId());
        if (ws == null) {
            if (log.isDebugEnabled()) {
                log.debug("[TerminalService] wsTemplate 未注入，跳过 STOMP: topic={}", topic);
            }
            return;
        }
        try {
            ws.convertAndSend(topic, payload);
        } catch (Exception e) {
            log.warn("[TerminalService] 终端 STOMP 推送失败: topic={} err={}", topic, e.toString());
        }
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    /** 取终端，不存在 ⇒ 404。 */
    private TerminalSession require(String sessionId, String terminalId) {
        ConcurrentHashMap<String, TerminalSession> bucket = terminals.get(sessionId);
        TerminalSession t = bucket != null ? bucket.get(terminalId) : null;
        if (t == null) {
            throw new NotFoundException("终端不存在: " + sessionId + "/" + terminalId);
        }
        return t;
    }

    /**
     * 会话 cwd 解析（cwd = 会话项目目录）。
     *
     * <p>⚠️ {@link CwdResolution#getCwd} 对「会话存在却未绑定项目 / DB 无此会话」<b>fail-loud 抛</b>
     * （既有裁定，本类不改）。终端必须能起来 ⇒ catch 后回落 {@code user.home}，并<b>照实 WARN</b>：
     * ⛔ 不回落后端进程 {@code user.dir}（那不是任何会话的项目根，历史误删根因）。
     */
    private String resolveCwd(String sessionId) {
        try {
            String cwd = CwdResolution.getCwd(sessionId);
            if (cwd != null && !cwd.isBlank()) {
                return cwd;
            }
            log.warn("[TerminalService] 会话 {} 的 cwd 解析为空 ⇒ 回落 user.home", sessionId);
        } catch (RuntimeException e) {
            log.warn("[TerminalService] 会话 {} 项目根解析失败（{}）⇒ 终端 cwd 回落 user.home",
                sessionId, e.getMessage());
        }
        String home = System.getProperty("user.home");
        return home != null && !home.isBlank() ? home : ".";
    }

    /** 子进程环境：继承后端环境；Unix 补 {@code TERM}（否则 zsh/bash 无颜色、行编辑异常）。 */
    private Map<String, String> buildEnvironment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("win") && !env.containsKey("TERM")) {
            env.put("TERM", "xterm-256color");
        }
        return env;
    }

    private static int clampSize(int value, int fallback, int max) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(value, max);
    }

    /** 终止进程：先 destroy，500ms 不退则 destroyForcibly（读线程会拿到 EOF 并补 closed 帧）。 */
    private void terminate(TerminalSession terminal) {
        PtyProcess process = terminal.process();
        try {
            process.destroy();
        } catch (Exception e) {
            log.warn("[TerminalService] 终止终端失败（destroy）: terminal={} err={}",
                terminal.terminalId(), e.toString());
        }
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (Exception e) {
            log.warn("[TerminalService] 等待终端退出失败: terminal={} err={}",
                terminal.terminalId(), e.toString());
        }
    }

    private static long safePid(PtyProcess process) {
        try {
            return process.pid();
        } catch (Exception e) {
            return -1L;
        }
    }
}
